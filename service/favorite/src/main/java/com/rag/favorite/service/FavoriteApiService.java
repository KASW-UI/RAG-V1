package com.rag.favorite.service;

import com.rag.favorite.domain.service.FavoriteDomainService;
import com.rag.favorite.dto.favorite.CreateFavoriteReq;
import com.rag.favorite.dto.favorite.CreateFavoriteResp;
import com.rag.favorite.dto.favorite.DeleteFavoriteReq;
import com.rag.favorite.dto.favorite.DeleteFavoriteResp;
import com.rag.favorite.dto.favorite.FavoriteItem;
import com.rag.favorite.dto.favorite.GetFavoritesByFolderReq;
import com.rag.favorite.dto.favorite.GetFavoritesByFolderResp;
import com.rag.favorite.dto.folder.CreateFavoriteFolderReq;
import com.rag.favorite.dto.folder.CreateFavoriteFolderResp;
import com.rag.favorite.dto.folder.DeleteFavoriteFolderReq;
import com.rag.favorite.dto.folder.DeleteFavoriteFolderResp;
import com.rag.favorite.dto.folder.FavoriteFolder;
import com.rag.favorite.dto.folder.GetFavoriteFoldersResp;
import com.rag.favorite.dto.folder.UpdateFavoriteFolderNameReq;
import com.rag.favorite.dto.folder.UpdateFavoriteFolderNameResp;
import com.rag.favorite.metrics.FavoriteRpcMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>favorite 域 7 个 HTTP 用例</b> —— 对应 B 侧
 * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/} 下的
 * 7 个用例类：
 * <ul>
 *   <li>{@code FavoriteCreateFolderService}（75 行）、</li>
 *   <li>{@code FavoriteCreateItemService}（102 行）、</li>
 *   <li>{@code FavoriteDeleteFolderService}（72 行）、</li>
 *   <li>{@code FavoriteDeleteItemService}（75 行）、</li>
 *   <li>{@code FavoriteListFoldersService}（75 行）、</li>
 *   <li>{@code FavoriteListItemsService}（83 行）、</li>
 *   <li>{@code FavoriteUpdateFolderNameService}（77 行）。</li>
 * </ul>
 *
 * <p><b>抄写基准</b>：B 侧 7 个类（映射表 §1.2 / §3.1：**N:1 · 用例内聚**）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>7 个类合 1 个</b>（D5：7 个用例类同层同质，不是职责边界 ⇒ 合并）。
 *           合并后 <b>方法名保持一字不差</b>（{@code createFavoriteFolder} 等），
 *           便于与 B 侧逐条对照。</li>
 *       <li><b>不再经过自我 gRPC</b>（<b>D2</b>）：B 侧是
 *           {@code api 用例 → FavoriteRpcClient → gRPC → favorite-rpc 进程 → FavoriteDomainService}；
 *           C 侧改为<b>域内直连</b> {@link FavoriteDomainService} ⇒ 少一层序列化往返。</li>
 *       <li>请求/响应的<b>出参类型</b>从 protobuf 换成 DTO record（<b>A9</b>：DTO 必须 immutable record）。</li>
 *       <li><b>指标 + span 合一</b>（映射表 §1.8）：B 侧的 {@code FavoriteSpans} 常量
 *           落在共享层的 {@link FavoriteRpcMetrics} 里 ⇒ 本类直接用它的常量。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。7 个用例的判据、鉴权、错误码、错误文案、
 *       span 属性名与取值、日志措辞<b>逐条未改</b>。
 *       <b>唯一需要额外论证的是错误映射</b>：B 侧 api 收到的是 gRPC status 异常，
 *       C 侧直连后收到的是领域异常（{@code FavoriteExceptions.Rpc}）
 *       ⇒ 映射点见 {@link FavoriteApiSupport#invokeRpc} 的
 *       {@code FavoriteExceptions.Rpc} 分支（与 B 侧 {@code BizCodeFromError} 逐字等价，
 *       详见该方法 javadoc）。</li>
 *   <li><b>B 出处</b>：见上 7 个类（逐个标注在每个方法的 javadoc 里）。</li>
 * </ol>
 *
 * <h2>⚠️ 7 个用例<b>全部</b>要 uid（无公开读）</h2>
 * <p>B 侧 7 个 logic <b>每个</b>的第一件事都是 {@code extractUserID(ctx)}
 * ⇒ claim 解析失败 ⇒ <b>HTTP 200 + {code:5004}</b>（<b>不是</b> 401；
 * 401 只由鉴权层的验签失败 / 黑名单命中产生）。
 * <p>⇒ 本类的 7 个方法<b>无一例外</b>都调 {@code support.userId(httpRequest)}。
 *
 * <h2>⚠️ span 属性<b>逐条照抄</b>，包括「不 trim」的那些</h2>
 * <p>B 侧各 logic 的 {@code span.SetAttributes} 取值<b>互不相同</b>，且有<b>刻意不统一</b>的地方：
 * <ul>
 *   <li>{@code target_id} 属性 ⇒ <b>TrimSpace 过</b>；但下游请求里的 {@code target_id} ⇒ <b>原值</b>（不 trim）。
 *       这是 Go 的形状，不是 bug ⇒ 两侧都要保留这种「不一致」。</li>
 *   <li>{@code target_type} ⇒ <b>原样</b>（不 lower、不 trim）。</li>
 *   <li>{@code folder_name} ⇒ <b>原样</b>（不 trim）—— 与落库用的 {@code normalizeFolderName} 不同。</li>
 * </ul>
 * ⇒ ⇒ <b>不许</b>在移植时「顺手统一」。
 */
@Service
public class FavoriteApiService {

    private final FavoriteDomainService domainService;

    private final FavoriteApiSupport support;

    public FavoriteApiService(FavoriteDomainService domainService, FavoriteApiSupport support) {
        this.domainService = domainService;
        this.support = support;
    }

    /**
     * 新建收藏夹 —— 对应 B 侧 {@code FavoriteCreateFolderService#createFavoriteFolder}。
     *
     * <p><b>B 出处</b>：
     * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteCreateFolderService.java:44-73}
     * <pre>
     *   Go: api/internal/logic/favorite/create_favorite_folder_logic.go:33-45（span 属性）
     *       api/internal/logic/favorite/create_favorite_folder_logic.go:57-62（nil ⇒ 5001）
     * </pre>
     *
     * <p><b>L2 业务语义</b>：解析 userId（失败 ⇒ 5004）→ 调 rpc 的
     * {@code CreateFavoriteFolder} → 下游返回 {@code null} ⇒ 5001
     * → 成功 ⇒ {@code {folderId}}（<b>出参是字符串</b>，对齐 Go 的 {@code ,string}）。
     *
     * <p><b>⚠️ 本层不校验 folder name 是否为空 / 是否重名</b>：那两条业务规则在 rpc 侧
     * ⇒ api 层只是透传。Go 的 api 侧同样不校验。
     */
    public CreateFavoriteFolderResp createFavoriteFolder(CreateFavoriteFolderReq req,
                                                          HttpServletRequest httpRequest) {
        // 🔴 B 侧: create_favorite_folder_logic.go:33-45
        //     userID, err := extractUserID(ctx)      ← 失败 ⇒ 直接 return（**不 RecordError**、不设属性）
        //     span.SetAttributes(attribute.Int64("biz.user_id", userID),
        //                         attribute.String("biz.folder_name", req.Name))   ← **未 trim 的原值**
        return FavoriteRpcMetrics.tracedApi(
                FavoriteRpcMetrics.API_CREATE_FAVORITE_FOLDER,
                () -> support.userId(httpRequest),
                (span, userId) -> {
                    span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId);
                    // 🔴 Go 记的是 req.Name 的**原值**（不 trim）—— 与落库用的 normalizeFolderName 不同
                    span.setAttribute(FavoriteRpcMetrics.ATTR_FOLDER_NAME,
                            FavoriteApiSupport.nullToEmpty(req.name()));
                },
                userId -> {
                    com.rag.proto.favorite.CreateFavoriteFolderReq rpcReq =
                            com.rag.proto.favorite.CreateFavoriteFolderReq.newBuilder()
                                    .setUserId(userId)
                                    .setName(FavoriteApiSupport.nullToEmpty(req.name()))
                                    .build();

                    com.rag.proto.favorite.CreateFavoriteFolderResp rpcResp =
                            support.invokeRpc(() -> domainService.createFavoriteFolder(rpcReq), userId, null);

                    // Go: create_favorite_folder_logic.go:57-62 —— rpcResp == nil ⇒ 5001
                    // ⚠️ Go 这一支【只判 nil】（响应消息里没有 success 字段）⇒ 用 nullResponse，
                    //    不是 nullOrUnsuccessfulResponse。
                    if (rpcResp == null) {
                        throw support.nullResponse(userId, null);
                    }

                    support.logInfo(String.format("create favorite folder success, folder_id=%d",
                            rpcResp.getFolderId()), userId, null);
                    // ⚠️ 出参是**字符串**（B 侧 CreateFavoriteFolderResp.java:34-36 的
                    //    getFolderId() 返回 String，字段本体是 Long）
                    return new CreateFavoriteFolderResp(String.valueOf(rpcResp.getFolderId()));
                });
    }

    /**
     * 新增收藏 —— 对应 B 侧 {@code FavoriteCreateItemService#createFavorite}。
     *
     * <p><b>B 出处</b>：
     * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteCreateItemService.java:49-100}
     * <pre>
     *   Go: api/internal/logic/favorite/create_favorite_logic.go:34-48（span 属性）
     *       api/internal/logic/favorite/create_favorite_logic.go:58（下游请求用**未 trim**的原值）
     *       api/internal/logic/favorite/create_favorite_logic.go:61,71（articleLogOption(req.TargetId)）
     *       api/internal/logic/favorite/create_favorite_logic.go:64-69（nil ⇒ 5001）
     * </pre>
     *
     * <p><b>⚠️ 日志里的 articleId 就是 {@code targetId}</b>：Go 的
     * {@code create_favorite_logic.go:61,71} 用 {@code articleLogOption(req.TargetId)}
     * —— 即「被收藏对象的 ID」。在 favorite 域里 target_id 存的就是文章 ID，
     * 但<b>字段名仍叫 target_id</b>。本实现原样透传（不 trim —— Go 的
     * {@code articleLogOption} 里有 {@code strings.TrimSpace}，见
     * {@link FavoriteApiSupport#logInfo}）。
     */
    public CreateFavoriteResp createFavorite(CreateFavoriteReq req, HttpServletRequest httpRequest) {
        // 🔴 B 侧: create_favorite_logic.go:34-48
        //     span.SetAttributes(
        //         attribute.Int64("biz.user_id", userID),
        //         attribute.Int64("biz.folder_id", req.FolderId),
        //         attribute.String("biz.target_id", strings.TrimSpace(req.TargetId)),   ← **trim 过**
        //         attribute.String("biz.target_type", req.TargetType))                  ← **未规范化**
        return FavoriteRpcMetrics.tracedApi(
                FavoriteRpcMetrics.API_CREATE_FAVORITE,
                () -> support.userId(httpRequest),
                (span, userId) -> {
                    span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId);
                    span.setAttribute(FavoriteRpcMetrics.ATTR_FOLDER_ID,
                            FavoriteApiSupport.nullToZero(req.folderId()));
                    // 🔴 Go 侧这**三个**属性的取值各不相同，别「顺手统一」：
                    //    · target_id   ⇒ TrimSpace 过
                    //    · target_type ⇒ 原样（不 lower、不 trim）
                    //    · folder_name ⇒ 原样（不 trim）
                    span.setAttribute(FavoriteRpcMetrics.ATTR_TARGET_ID,
                            FavoriteApiSupport.nullToEmpty(req.targetId()).trim());
                    span.setAttribute(FavoriteRpcMetrics.ATTR_TARGET_TYPE,
                            FavoriteApiSupport.nullToEmpty(req.targetType()));
                },
                userId -> {
                    // ⚠️ 下游请求里带的是**未 trim 的原值**（Go: create_favorite_logic.go:58
                    //    `TargetId: req.TargetId`）⇒ 与 span 属性（trim 过）不是同一个值
                    //    —— 这是 Go 的形状，不是 bug。
                    String targetId = req.targetId();

                    com.rag.proto.favorite.CreateFavoriteReq rpcReq =
                            com.rag.proto.favorite.CreateFavoriteReq.newBuilder()
                                    .setUserId(userId)
                                    .setFolderId(FavoriteApiSupport.nullToZero(req.folderId()))
                                    .setTargetId(FavoriteApiSupport.nullToEmpty(targetId))
                                    .setTargetType(FavoriteApiSupport.nullToEmpty(req.targetType()))
                                    // ⚠️ title / cover 是 ,optional 字段，【缺席时在 Go 侧是零值 ""】，
                                    // 而 protobuf 生成的 setXxx(null) 会抛 NullPointerException
                                    // （内部是 Objects.requireNonNull）。
                                    // ⇒ 显式把 null 归一成 ""，与 Go 的零值 struct 同构。
                                    // Go 出处：api/internal/types/types.go:18-19 的 tag 是 ,optional，
                                    //   core/mapping/unmarshaler.go:856-862 的
                                    //   `if mapValue == nil { if opts.optional() { return nil } ... }`
                                    //   ⇒ 缺席与显式 null 都让字段保持零值 ""。
                                    .setTitle(FavoriteApiSupport.nullToEmpty(req.title()))
                                    .setCover(FavoriteApiSupport.nullToEmpty(req.cover()))
                                    .build();

                    com.rag.proto.favorite.CreateFavoriteResp rpcResp =
                            support.invokeRpc(() -> domainService.createFavorite(rpcReq), userId, targetId);

                    // Go: create_favorite_logic.go:64-69 —— rpcResp == nil ⇒ 5001
                    if (rpcResp == null) {
                        throw support.nullResponse(userId, targetId);
                    }

                    support.logInfo(String.format("create favorite success, favorite_id=%d",
                            rpcResp.getFavoriteId()), userId, targetId);
                    // ⚠️ 出参是**字符串**（B 侧 CreateFavoriteResp.java:30-32 的
                    //    getFavoriteId() 返回 String，字段本体是 Long）
                    return new CreateFavoriteResp(String.valueOf(rpcResp.getFavoriteId()));
                });
    }

    /**
     * 删除收藏夹 —— 对应 B 侧 {@code FavoriteDeleteFolderService#deleteFavoriteFolder}。
     *
     * <p><b>B 出处</b>：
     * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteDeleteFolderService.java:47-70}
     * <pre>
     *   Go: api/internal/logic/favorite/delete_favorite_folder_logic.go:33-45（span 属性）
     *       api/internal/logic/favorite/delete_favorite_folder_logic.go:57-62（nil || !Success ⇒ 5001）
     * </pre>
     *
     * <p><b>⚠️ 「他人收藏夹」的归属校验在 favorite-rpc 侧</b>（Go 的 rpc logic 返回
     * {@code PermissionDenied} → api 侧还原成 5005）。api 层不做归属判断。
     */
    public DeleteFavoriteFolderResp deleteFavoriteFolder(DeleteFavoriteFolderReq req,
                                                          HttpServletRequest httpRequest) {
        // 🔴 B 侧: delete_favorite_folder_logic.go:33-45
        //     span.SetAttributes(attribute.Int64("biz.user_id", userID),
        //                         attribute.Int64("biz.folder_id", req.FolderId))
        return FavoriteRpcMetrics.tracedApi(
                FavoriteRpcMetrics.API_DELETE_FAVORITE_FOLDER,
                () -> support.userId(httpRequest),
                (span, userId) -> {
                    span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId);
                    span.setAttribute(FavoriteRpcMetrics.ATTR_FOLDER_ID,
                            FavoriteApiSupport.nullToZero(req.folderId()));
                },
                userId -> {
                    com.rag.proto.favorite.DeleteFavoriteFolderReq rpcReq =
                            com.rag.proto.favorite.DeleteFavoriteFolderReq.newBuilder()
                                    .setUserId(userId)
                                    .setFolderId(FavoriteApiSupport.nullToZero(req.folderId()))
                                    .build();

                    com.rag.proto.favorite.DeleteFavoriteFolderResp rpcResp =
                            support.invokeRpc(() -> domainService.deleteFavoriteFolder(rpcReq), userId, null);

                    // Go: delete_favorite_folder_logic.go:57-62 —— rpcResp == nil || !rpcResp.Success ⇒ 5001
                    if (rpcResp == null || !rpcResp.getSuccess()) {
                        throw support.nullOrUnsuccessfulResponse(userId, null);
                    }

                    support.logInfo(String.format("delete favorite folder success, folder_id=%d",
                            FavoriteApiSupport.nullToZero(req.folderId())), userId, null);
                    return new DeleteFavoriteFolderResp(true);
                });
    }

    /**
     * 删除收藏 —— 对应 B 侧 {@code FavoriteDeleteItemService#deleteFavorite}。
     *
     * <p><b>B 出处</b>：
     * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteDeleteItemService.java:46-70}
     * <pre>
     *   Go: api/internal/logic/favorite/delete_favorite_logic.go:33-45（span 属性）
     *       api/internal/logic/favorite/delete_favorite_logic.go:57-62（nil || !Success ⇒ 5001）
     * </pre>
     *
     * <p><b>⚠️ 「收藏暂不可撤回」（5304）在 rpc 侧</b>：Go 的
     * {@code rpc/internal/logic/delete_favorite_logic.go:86} 用
     * {@code GRPCError(codes.FailedPrecondition, 5304)} 发出，
     * 而 {@code FAILED_PRECONDITION} 不在 {@code BizCodeFromError} 的白名单里
     * ⇒ api 侧<b>只能</b>靠 description 文案反查还原 5304
     * （这正是 {@code com.rag.favorite.favorite.common.errmsg.FavoriteGrpcError} 存在的原因）。
     * <br>⚠️ <b>D2 域内直连后这条链路的形状变了</b>：领域层直接抛
     * {@code FavoriteExceptions.Rpc}（<b>自带</b> bizCode=5304）⇒ 还原走
     * {@link FavoriteApiSupport#invokeRpc} 的领域异常分支 ⇒ 5304 原样保留。
     */
    public DeleteFavoriteResp deleteFavorite(DeleteFavoriteReq req, HttpServletRequest httpRequest) {
        // 🔴 B 侧: delete_favorite_logic.go:33-45
        //     span.SetAttributes(attribute.Int64("biz.user_id", userID),
        //                         attribute.Int64("biz.favorite_id", req.FavoriteId))
        return FavoriteRpcMetrics.tracedApi(
                FavoriteRpcMetrics.API_DELETE_FAVORITE,
                () -> support.userId(httpRequest),
                (span, userId) -> {
                    span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId);
                    span.setAttribute(FavoriteRpcMetrics.ATTR_FAVORITE_ID,
                            FavoriteApiSupport.nullToZero(req.favoriteId()));
                },
                userId -> {
                    com.rag.proto.favorite.DeleteFavoriteReq rpcReq =
                            com.rag.proto.favorite.DeleteFavoriteReq.newBuilder()
                                    .setUserId(userId)
                                    .setFavoriteId(FavoriteApiSupport.nullToZero(req.favoriteId()))
                                    .build();

                    com.rag.proto.favorite.DeleteFavoriteResp rpcResp =
                            support.invokeRpc(() -> domainService.deleteFavorite(rpcReq), userId, null);

                    // Go: delete_favorite_logic.go:57-62 —— rpcResp == nil || !rpcResp.Success ⇒ 5001
                    if (rpcResp == null || !rpcResp.getSuccess()) {
                        throw support.nullOrUnsuccessfulResponse(userId, null);
                    }

                    support.logInfo(String.format("delete favorite success, favorite_id=%d",
                            FavoriteApiSupport.nullToZero(req.favoriteId())), userId, null);
                    return new DeleteFavoriteResp(true);
                });
    }

    /**
     * 修改收藏夹名字 —— 对应 B 侧 {@code FavoriteUpdateFolderNameService#updateFavoriteFolderName}。
     *
     * <p><b>B 出处</b>：
     * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteUpdateFolderNameService.java:46-75}
     * <pre>
     *   Go: api/internal/logic/favorite/update_favorite_folder_name_logic.go:33-46（span 属性）
     *       api/internal/logic/favorite/update_favorite_folder_name_logic.go:59-64（nil || !Success ⇒ 5001）
     * </pre>
     *
     * <p><b>⚠️ 收藏夹不存在（5201）/ 名称为空（5202）/ 名称已存在（5203）都在 rpc 侧</b>，
     * api 层原样透传（Go 侧由 {@code BizCodeFromError} 的「按 description 反查文案」还原）。
     */
    public UpdateFavoriteFolderNameResp updateFavoriteFolderName(UpdateFavoriteFolderNameReq req,
                                                                  HttpServletRequest httpRequest) {
        // 🔴 B 侧: update_favorite_folder_name_logic.go:33-46
        //     span.SetAttributes(attribute.Int64("biz.user_id", userID),
        //                         attribute.Int64("biz.folder_id", req.FolderId),
        //                         attribute.String("biz.folder_name", req.Name))   ← **未 trim 的原值**
        return FavoriteRpcMetrics.tracedApi(
                FavoriteRpcMetrics.API_UPDATE_FAVORITE_FOLDER_NAME,
                () -> support.userId(httpRequest),
                (span, userId) -> {
                    span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId);
                    span.setAttribute(FavoriteRpcMetrics.ATTR_FOLDER_ID,
                            FavoriteApiSupport.nullToZero(req.folderId()));
                    // 🔴 Go 记的是 req.Name 的**原值**（不 trim）—— 与落库用的 normalizeFolderName 不同
                    span.setAttribute(FavoriteRpcMetrics.ATTR_FOLDER_NAME,
                            FavoriteApiSupport.nullToEmpty(req.name()));
                },
                userId -> {
                    com.rag.proto.favorite.UpdateFavoriteFolderNameReq rpcReq =
                            com.rag.proto.favorite.UpdateFavoriteFolderNameReq.newBuilder()
                                    .setUserId(userId)
                                    .setFolderId(FavoriteApiSupport.nullToZero(req.folderId()))
                                    .setName(FavoriteApiSupport.nullToEmpty(req.name()))
                                    .build();

                    com.rag.proto.favorite.UpdateFavoriteFolderNameResp rpcResp =
                            support.invokeRpc(() -> domainService.updateFavoriteFolderName(rpcReq), userId, null);

                    // Go: update_favorite_folder_name_logic.go:59-64 —— nil || !Success ⇒ 5001
                    if (rpcResp == null || !rpcResp.getSuccess()) {
                        throw support.nullOrUnsuccessfulResponse(userId, null);
                    }

                    support.logInfo(String.format("update favorite folder name success, folder_id=%d",
                            FavoriteApiSupport.nullToZero(req.folderId())), userId, null);
                    return new UpdateFavoriteFolderNameResp(true);
                });
    }

    /**
     * 查询收藏夹列表 —— 对应 B 侧 {@code FavoriteListFoldersService#getFavoriteFolders}。
     *
     * <p><b>B 出处</b>：
     * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteListFoldersService.java:45-72}
     * <pre>
     *   Go: api/internal/logic/favorite/get_favorite_folders_logic.go:33-42（span 属性）
     *       api/internal/logic/favorite/get_favorite_folders_logic.go:51-56（nil ⇒ 5001）
     *       api/internal/logic/favorite/get_favorite_folders_logic.go:58（folders 无条件 make）
     * </pre>
     *
     * <p><b>⚠️ {@code folders} 永远非 null</b>：Go 的
     * {@code get_favorite_folders_logic.go:58} 是
     * {@code make([]types.FavoriteFolder, 0, len(rpcResp.Folders))} —— <b>无条件 make</b>，
     * 空结果输出 {@code []} 而不是 {@code null}。⇒ 本实现无条件给空集合赋值。
     */
    public GetFavoriteFoldersResp getFavoriteFolders(HttpServletRequest httpRequest) {
        // 🔴 B 侧: get_favorite_folders_logic.go:33-42
        //     span.SetAttributes(attribute.Int64("biz.user_id", userID))    ← **只有这一个属性**
        return FavoriteRpcMetrics.tracedApi(
                FavoriteRpcMetrics.API_GET_FAVORITE_FOLDERS,
                () -> support.userId(httpRequest),
                (span, userId) -> span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId),
                userId -> {
                    com.rag.proto.favorite.GetFavoriteFoldersReq rpcReq =
                            com.rag.proto.favorite.GetFavoriteFoldersReq.newBuilder()
                                    .setUserId(userId)
                                    .build();

                    com.rag.proto.favorite.GetFavoriteFoldersResp rpcResp =
                            support.invokeRpc(() -> domainService.getFavoriteFolders(rpcReq), userId, null);

                    // Go: get_favorite_folders_logic.go:51-56 —— rpcResp == nil ⇒ 5001
                    // ⚠️ Go 这一支【只判 nil】（响应消息里没有 success 字段）⇒ 用 nullResponse。
                    if (rpcResp == null) {
                        throw support.nullResponse(userId, null);
                    }

                    List<FavoriteFolder> folders = new ArrayList<>(rpcResp.getFoldersCount());
                    for (com.rag.proto.favorite.FavoriteFolder proto : rpcResp.getFoldersList()) {
                        folders.add(support.toFolderDto(proto));
                    }

                    support.logInfo(String.format("get favorite folders success, count=%d", folders.size()),
                            userId, null);
                    return new GetFavoriteFoldersResp(folders);
                });
    }

    /**
     * 查询收藏夹下的收藏 —— 对应 B 侧 {@code FavoriteListItemsService#getFavoritesByFolder}。
     *
     * <p><b>B 出处</b>：
     * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteListItemsService.java:51-80}
     * <pre>
     *   Go: api/internal/logic/favorite/get_favorites_by_folder_logic.go:33-45（span 属性）
     *       api/internal/logic/favorite/get_favorites_by_folder_logic.go:57-62（nil ⇒ 5001）
     *       api/internal/logic/favorite/get_favorites_by_folder_logic.go:64（favorites 无条件 make）
     * </pre>
     *
     * <p><b>⚠️ {@code favorites} 永远非 null</b>：Go 的
     * {@code get_favorites_by_folder_logic.go:64} 是
     * {@code make([]types.FavoriteItem, 0, len(rpcResp.Favorites))} —— <b>无条件 make</b>，
     * 空结果输出 {@code []} 而不是 {@code null}。
     */
    public GetFavoritesByFolderResp getFavoritesByFolder(GetFavoritesByFolderReq req,
                                                         HttpServletRequest httpRequest) {
        // 🔴 B 侧: get_favorites_by_folder_logic.go:33-45
        //     span.SetAttributes(attribute.Int64("biz.user_id", userID),
        //                         attribute.Int64("biz.folder_id", req.FolderId))
        return FavoriteRpcMetrics.tracedApi(
                FavoriteRpcMetrics.API_GET_FAVORITES_BY_FOLDER,
                () -> support.userId(httpRequest),
                (span, userId) -> {
                    span.setAttribute(FavoriteRpcMetrics.ATTR_USER_ID, userId);
                    span.setAttribute(FavoriteRpcMetrics.ATTR_FOLDER_ID,
                            FavoriteApiSupport.nullToZero(req.folderId()));
                },
                userId -> {
                    com.rag.proto.favorite.GetFavoritesByFolderReq rpcReq =
                            com.rag.proto.favorite.GetFavoritesByFolderReq.newBuilder()
                                    .setUserId(userId)
                                    .setFolderId(FavoriteApiSupport.nullToZero(req.folderId()))
                                    .build();

                    com.rag.proto.favorite.GetFavoritesByFolderResp rpcResp =
                            support.invokeRpc(() -> domainService.getFavoritesByFolder(rpcReq), userId, null);

                    // Go: get_favorites_by_folder_logic.go:57-62 —— rpcResp == nil ⇒ 5001
                    if (rpcResp == null) {
                        throw support.nullResponse(userId, null);
                    }

                    List<FavoriteItem> favorites = new ArrayList<>(rpcResp.getFavoritesCount());
                    for (com.rag.proto.favorite.FavoriteItem proto : rpcResp.getFavoritesList()) {
                        favorites.add(support.toItemDto(proto));
                    }

                    support.logInfo(String.format("get favorites by folder success, count=%d", favorites.size()),
                            userId, null);
                    return new GetFavoritesByFolderResp(favorites);
                });
    }
}
