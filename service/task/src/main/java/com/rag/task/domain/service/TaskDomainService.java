package com.rag.task.domain.service;

import com.rag.proto.task.GetTaskReq;
import com.rag.proto.task.GetTaskResp;
import com.rag.proto.task.Task;
import com.rag.task.domain.entity.TaskProgressEntity;
import com.rag.task.mapper.TaskProgressMapper;
import com.rag.task.service.RedisPipelines;
import com.rag.task.service.TaskDefs;
import com.rag.task.service.TaskRedisKeys;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><b>{@code GetTask} 业务逻辑</b> —— 1:1 对应 Go 端
 * {@code service/task/rpc/internal/logic/get_task_logic.go} 的 {@code GetTaskLogic.GetTask}。</p>
 *
 * <h2>运行路径（Go 出处逐段标注）</h2>
 * <pre>
 *  uid &lt;= 0                → status.Error(InvalidArgument, "user_id invalid")   [:42-45]
 *  Redis HGETALL task:progress:&lt;uid&gt;                                            [:47-51]
 *    ├─ 命中（err==nil 且非空）→ 直接用                                            [:51]
 *    └─ 未命中（err != nil 或空）→ 查 task_progress 回源 + 回填缓存                 [:52-80]
 *  对每条 AllTaskDefs 组装 Task（progress 缺省 0）                                  [:83-101]
 * </pre>
 *
 * <h2>🔴 三个必须原样保留的行为</h2>
 *
 * <h3>1. Redis 只是优化，不是 source of truth</h3>
 * <p>Go 的条件是 {@code if rerr != nil || len(pm) == 0} —— <b>Redis 报错也算未命中</b>，
 * 直接回源查库。⇒ Redis 不可用时接口<b>依然可用</b>（降级为纯 DB 读）。
 * <p>反过来，写缓存失败<b>绝不影响返回</b>：回填那两行是
 * {@code _, _ = pipe.Exec(ctx) // 回填失败不影响返回}（[:76]）与
 * {@code _ = l.svcCtx.Rdb.Expire(...).Err()}（[:78]），错误全被丢弃。
 *
 * <h3>2. 缓存 key 与 sink 的写 key 不一致 ⇒ 缓存几乎永远 miss</h3>
 * <p>本方法读 {@code task:progress:<uid>}，而两个 sink 写的是<b>另外两个</b> key
 * （详见 {@link TaskRedisKeys} 的类注释）。这不是本类的 bug，是 Go 的现状。
 *
 * <h3>3. {@code required_progress} 取自任务定义，<b>不取</b> DB 的 {@code target}</h3>
 * <p>Go 注释写明「required_progress 来自任务定义（更合理，不要用 progress 表的 target 覆盖 defs）」
 * （[:86-87]）。⇒ 即使 DB 里 {@code target} 被写成别的值，客户端看到的总要求进度不变。
 *
 * <h2>⚠️ 「DB 无行」时的 EXPIRE 是 no-op</h2>
 * <p>Go 在无行分支只执行 {@code Expire(pkey, 30s)}（[:78]），而此刻该 key <b>并不存在</b>
 * （HGETALL 没命中 ⇒ 没有任何 HSET）⇒ 它<b>不是空结果缓存</b>，没有行时每次调用都会再查库。
 * 保留这一行为，不要改写成"写空标记防穿透"（那是新增行为）。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.service} → {@code com.rag.task.domain.service}（D7）；</li>
 *         <li>proto 包名 {@code com.rag.task.task.proto} → {@code com.rag.proto.task}（D7 · proto 集中化）;</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 校验判据、缓存读/回源/回填的<b>错误处理边界</b>、
 *       {@code required_progress} 的取值来源、{@code pack}/{@code unpackProgress} 的切分规则
 *       （含 {@code split("\\|", -1)} 保留尾部空串这条），逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskDomainService.java:55-56（类声明）
 * B 出处：…/service/TaskDomainService.java:67（PROGRESS_SEPARATOR）
 * B 出处：…/service/TaskDomainService.java:70（PROGRESS_FIELDS = 3）
 * B 出处：…/service/TaskDomainService.java:87-143（getTask 主体）
 * B 出处：…/service/TaskDomainService.java:151-168（readProgressCache：Redis 异常 ⇒ null ⇒ 回源）
 * B 出处：…/service/TaskDomainService.java:176-188（fillProgressCache：吞错）
 * B 出处：…/service/TaskDomainService.java:191-197（expireQuietly：吞错）
 * B 出处：…/service/TaskDomainService.java:207-211（packProgress）
 * B 出处：…/service/TaskDomainService.java:222-228（unpackProgressValue：段数≠3 ⇒ 0）
 * B 出处：…/service/TaskDomainService.java:231-240（parseInt64Default）
 *       </pre></li>
 * </ol>
 */
@Service
public class TaskDomainService {

    private static final Logger log = LoggerFactory.getLogger(TaskDomainService.class);

    /**
     * 进度值的字段分隔符 —— Go {@code packProgress} 注释 {@code value="status|progress|target"}。
     *
     * <p>⚠️ Go 的 {@code strings.Split} <b>保留</b>尾部空串（{@code "a|b|" → ["a","b",""]}），
     * 所以 Java 侧必须用 {@code split("\\|", -1)}；默认的 {@code split("\\|")}
     * 会丢掉尾部空串，使 {@code "doing|5|"} 这类值在两侧得到不同的 progress。
     */
    private static final String PROGRESS_SEPARATOR = "\\|";

    /** 打包后的字段数 —— Go {@code if len(parts) != 3 { return "", 0, 0 }}。 */
    private static final int PROGRESS_FIELDS = 3;

    private final StringRedisTemplate redis;
    private final TaskProgressMapper taskProgressMapper;

    public TaskDomainService(StringRedisTemplate redis, TaskProgressMapper taskProgressMapper) {
        this.redis = redis;
        this.taskProgressMapper = taskProgressMapper;
    }

    /**
     * 获取用户全部任务的进度。
     *
     * @param req {@code GetTaskReq}；只用 {@code user_id}（字段号 1）
     * @return 固定包含 {@link TaskDefs#ALL_TASK_DEFS} 全部条目的响应
     * @throws StatusRuntimeException {@code INVALID_ARGUMENT}（uid ≤ 0）或任何 DB 异常（→ UNKNOWN）
     */
    public GetTaskResp getTask(GetTaskReq req) {
        // ---------- 1. 参数校验 ----------
        long uid = req.getUserId();
        if (uid <= 0) {
            // Go: return nil, status.Error(codes.InvalidArgument, "user_id invalid")
            // ⚠️ 文案是 L1 契约：Go 的 task-api 会把 gRPC error 原样往上抛给 HTTP 层。
            throw Status.INVALID_ARGUMENT.withDescription("user_id invalid").asRuntimeException();
        }

        String cacheKey = TaskRedisKeys.userProgressCache(uid);

        // ---------- 2. Redis 读（出错等同于未命中）----------
        Map<String, String> packed = readProgressCache(cacheKey);
        if (packed == null || packed.isEmpty()) {
            // ---------- 3. 回源：查 task_progress ----------
            List<TaskProgressEntity> rows = taskProgressMapper.selectByUserId(uid);
            // ⚠️ Go 是 `return nil, err`（裸 error，非 status）⇒ grpc-go 归为 UNKNOWN。
            //    这里让异常自然上抛，由 grpc/TaskGrpcService 与 service/TaskApiService
            //    的兜底分支翻译成 UNKNOWN，不要在这里吞掉或改写 ——
            //    吞掉会让接口在 DB 故障时"假装成功返回 0 进度"。
            packed = new HashMap<>();
            if (!rows.isEmpty()) {
                for (TaskProgressEntity row : rows) {
                    String value = packProgress(row.getStatus(), row.getProgress(), row.getTarget());
                    packed.put(String.valueOf(row.getTaskId()), value);
                }
                // 回填缓存：Go `pipe.HSet` + `pipe.Expire`，错误被显式丢弃
                fillProgressCache(cacheKey, packed);
            } else {
                // ⚠️ 对不存在的 key 设 TTL —— 等价于 no-op，见类注释「DB 无行时的 EXPIRE 是 no-op」
                expireQuietly(cacheKey, TaskRedisKeys.EMPTY_PROGRESS_CACHE_TTL);
            }
        }

        // ---------- 4. 按任务定义组装响应 ----------
        GetTaskResp.Builder resp = GetTaskResp.newBuilder();
        for (TaskDefs.TaskDef def : TaskDefs.ALL_TASK_DEFS) {
            // Go: var progress int64 = 0；未开始时默认 0（[:85]）
            long progress = 0L;
            // ⚠️ Go 注释：required_progress 来自任务定义，不要用 progress 表的 target 覆盖 defs
            long required = def.requiredProgress();

            String value = packed.get(String.valueOf(def.taskId()));
            if (value != null) {
                // Go: _, p, _ := unpackProgress(packed) —— status / target 目前 proto 用不上
                progress = unpackProgressValue(value);
            }

            resp.addTask(Task.newBuilder()
                    .setName(def.name())
                    .setDesc(def.desc())
                    .setTaskId(def.taskId())
                    .setCompletionProgress(progress)
                    .setRequiredProgress(required)
                    .build());
        }
        return resp.build();
    }

    /**
     * HGETALL 进度缓存 —— Go {@code l.svcCtx.Rdb.HGetAll(ctx, pkey).Result()}。
     *
     * @return 命中内容；<b>Redis 异常时返回 null</b>（对应 Go 的 {@code rerr != nil} 分支，
     *         调用方会走回源查询）
     */
    private Map<String, String> readProgressCache(String cacheKey) {
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(cacheKey);
            Map<String, String> result = new HashMap<>();
            if (entries != null) {
                for (Map.Entry<Object, Object> e : entries.entrySet()) {
                    result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return result;
        } catch (Exception e) {
            // ⚠️ Redis 不可用不是接口错误：Go 把 rerr 与空结果同等对待，一律回源查库。
            //    这里吞掉异常并返回 null，让调用方回源 —— 与 Go 一致。
            log.warn("hgetall task progress cache failed, fallback to db: key={} err={}",
                    cacheKey, e.getMessage());
            return null;
        }
    }

    /**
     * 回填进度缓存 —— Go {@code pipe.HSet(ctx, pkey, kv)} + {@code pipe.Expire(ctx, pkey, taskProgressTTL())}。
     *
     * <p>⚠️ <b>整段错误被丢弃</b>（Go 源码注释：「回填失败不影响返回」），
     * 所以这里吞掉所有异常，不影响本次 RPC 的响应。
     */
    private void fillProgressCache(String cacheKey, Map<String, String> packed) {
        try {
            // ⚠️ 用 putAll（一次 HSET 多字段）而不是逐字段 put（多次 HSET）：
            //    Go 是单条 `pipe.HSet(ctx, pkey, kv)`（kv 是整个 map）。
            RedisPipelines.execQuietly(redis, ops -> {
                ops.opsForHash().putAll(cacheKey, packed);
                ops.expire(cacheKey, TaskRedisKeys.PROGRESS_CACHE_TTL);
            });
        } catch (Exception e) {
            // 回填失败不影响返回（Go `_, _ = pipe.Exec(ctx)`）
            log.warn("refill task progress cache failed: key={} err={}", cacheKey, e.getMessage());
        }
    }

    /** TTL 设置，错误丢弃 —— Go {@code _ = ...Expire(...).Err()}。 */
    private void expireQuietly(String cacheKey, Duration ttl) {
        try {
            redis.expire(cacheKey, ttl);
        } catch (Exception e) {
            log.warn("expire task progress cache failed: key={} err={}", cacheKey, e.getMessage());
        }
    }

    /**
     * 打包进度 —— Go {@code packProgress}：
     * {@code status + "|" + strconv.FormatInt(progress, 10) + "|" + strconv.FormatInt(target, 10)}。
     *
     * <p>⚠️ Go 注释「status 不要包含 '|'」—— 因为解析端按 {@code |} 切成<b>恰好 3 段</b>，
     * status 里多一个 {@code |} 就会让整条值失配（返回 0/0/0）。
     * 本模块的 status 取值是 doing/done，不含 {@code |}。
     */
    static String packProgress(String status, Long progress, Long target) {
        return nullToEmpty(status)
                + "|" + (progress == null ? 0L : progress)
                + "|" + (target == null ? 0L : target);
    }

    /**
     * 解析进度并<b>只取第二段</b> —— Go {@code _, p, _ := unpackProgress(packed)}。
     *
     * <p>Go 的语义：段数不等于 3 ⇒ 返回 {@code ("", 0, 0)}；
     * 段数等于 3 ⇒ {@code progress = parseInt64Default(parts[1], 0)}
     * （空串或非法数字都回落 0）。
     *
     * <p>⚠️ 用 {@code split("\\|", -1)} 保留尾部空串，理由见 {@link #PROGRESS_SEPARATOR}。
     */
    static long unpackProgressValue(String packed) {
        String[] parts = packed.split(PROGRESS_SEPARATOR, -1);
        if (parts.length != PROGRESS_FIELDS) {
            return 0L;
        }
        return parseInt64Default(parts[1], 0L);
    }

    /** Go {@code parseInt64Default}：空串或解析失败一律回落默认值。 */
    private static long parseInt64Default(String s, long def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
