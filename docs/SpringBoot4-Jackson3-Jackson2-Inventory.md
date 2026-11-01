# RAG · Jackson 2 使用全量清单（Spring Boot 4 → 全 Jackson 3 迁移用）

> ⛔ **本报告结论已作废（2026-11-02 · D14 工具链回退）**
>
> **本项目现为「全项目 Jackson 2」**：Spring Boot **3.5.11** + `com.fasterxml.jackson.*` **2.19.4**
> ⇒ 业务代码、第三方依赖、运行时 classpath **全部 Jackson 2** ⇒ **classpath 上零 Jackson 3**。
> ⇒ 本报告的「要不要迁 / 迁了多少钱 / 哪些文件要改」**全部不再适用**（Boot 4 时代的评估过程**保留作历史存档**）。
> ⇒ 现行口径 ⇒ [`docs/决策记录.md`](决策记录.md) **D14** · 索引 ⇒ [`docs/README.md`](README.md)。

> **生成方式**：脚本全量扫描 RAG 仓 **1,381** 个 `.java`（排除 `target/`），按 `import` 判定 Jackson 依赖并归类。
> **命中 208 个文件**，其中 **206 个在 `service/` 下**（＝会被搬进 RAG-V1 的范围）；另 2 个在 `tools/`（不搬）。
> **核对日期**：2026-11-01 · 配套报告：[`SpringBoot4-Jackson3-Migration-Cost.md`](SpringBoot4-Jackson3-Migration-Cost.md)
> **注**：另有 12 个文件仅在**注释/javadoc**里出现 `com.fasterxml.jackson` 字样（无 import）⇒ **编译无影响**。

## 一、聚合（service/ 206 个）

| 迁移模式 | 文件数 | 主代码 | 测试 | 迁移动作 |
|---|---:|---:|---:|---|
| J2-ANNOTATION-ONLY | 106 | 106 | 0 | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） |
| J2-PLAIN(Mapper/注解增强) | 45 | 22 | 23 | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） |
| J2-TREE-API | 20 | 10 | 10 | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） |
| J3-ONLY | 19 | 14 | 5 | **原样保留**（已是 Jackson 3） |
| J2-COERCION | 6 | 2 | 4 | 改包名；**coercion 行为适配**（Jackson 3 只 LogicalType 级生效，类级无效） |
| J2-BEAN-CONFIG(可删) | 5 | 5 | 0 | **删除该类**；其「刻意配置」并入 Boot 4 的 `JsonMapper.BuilderCustomizer`（共享 1 份） |
| MIXED-J2+J3 | 5 | 4 | 1 | **零改动**（DTO：注解 J2 + 自定义 deserializer J3，两侧都已是目标态） |

## 二、按域（service/）

| 域 | 命中 | 主 | 测 | 模式分布 |
|---|---:|---:|---:|---|
| article | 21 | 16 | 5 | J2-ANNOTATION-ONLY 10 · J2-PLAIN 8 · J2-BEAN-CONFIG 1 · J2-TREE-API 1 · J3-ONLY 1 |
| comment | 31 | 26 | 5 | J2-ANNOTATION-ONLY 18 · J2-PLAIN 6 · J2-TREE-API 3 · J2-COERCION 2 · J3-ONLY 1 · J2-BEAN-CONFIG 1 |
| common | 11 | 7 | 4 | J2-PLAIN 6 · J3-ONLY 4 · J2-ANNOTATION-ONLY 1 |
| favorite | 19 | 15 | 4 | J2-ANNOTATION-ONLY 12 · J2-TREE-API 4 · J3-ONLY 2 · MIXED-J2+J3 1 |
| follow | 8 | 7 | 1 | J2-ANNOTATION-ONLY 5 · J3-ONLY 2 · J2-PLAIN 1 |
| hot | 10 | 6 | 4 | J2-PLAIN 5 · J2-ANNOTATION-ONLY 3 · J2-BEAN-CONFIG 1 · J2-TREE-API 1 |
| like | 41 | 28 | 13 | J2-ANNOTATION-ONLY 14 · J2-PLAIN 10 · J2-TREE-API 6 · J2-COERCION 4 · J3-ONLY 3 · MIXED-J2+J3 3 · J2-BEAN-CONFIG 1 |
| message | 20 | 20 | 0 | J2-ANNOTATION-ONLY 16 · J3-ONLY 2 · J2-PLAIN 1 · MIXED-J2+J3 1 |
| task | 16 | 11 | 5 | J2-TREE-API 5 · J2-PLAIN 4 · J3-ONLY 3 · J2-ANNOTATION-ONLY 3 · J2-BEAN-CONFIG 1 |
| users | 29 | 27 | 2 | J2-ANNOTATION-ONLY 24 · J2-PLAIN 4 · J3-ONLY 1 |

## 三、Jackson 2 API 命中频次（service/，按 import+源码 token）

| Token | 文件数 | | Token | 文件数 |
|---|---:|---|---|---:|
| `@JsonProperty` | 109 | | `ObjectMapper` | 82 |
| `new ObjectMapper()` | 36 | | `JsonMapper` | 34 |
| `FAIL_ON_UNKNOWN_PROPERTIES` | 24 | | `LogicalType` | 22 |
| `@JsonInclude` | 22 | | `DeserializationFeature` | 21 |
| `JsonNode` | 18 | | `CoercionAction` | 16 |
| `CoercionInputShape` | 16 | | `@JsonIgnore` | 14 |
| `@JsonPropertyOrder` | 11 | | `@JsonIgnoreProperties` | 10 |
| `ObjectNode` | 9 | | `@JsonAutoDetect` | 7 |
| `JsonParser` | 7 | | `@JsonFormat` | 7 |
| `MismatchedInputException` | 4 | | `@JsonDeserialize` | 4 |
| `TypeReference` | 4 | | `FAIL_ON_TRAILING_TOKENS` | 2 |
| `StdDeserializer / extends StdDeserializer` | 2 | | `@JsonSerialize / ToStringSerializer` | 1 |
| `JsonProcessingException` | 1 | | `registerModule / SimpleModule` | 0 |

## 四、5 个 `Jackson2ObjectMapperConfiguration` 的「刻意配置」清单（迁移时须并入 Boot 的 `JsonMapper.BuilderCustomizer`）

| 域 | 关 `FAIL_ON_UNKNOWN_PROPERTIES` | `LogicalType.Textual` coercion | `LogicalType.Boolean` coercion |
|---|:--:|:--:|:--:|
| like-rpc | ✅ | ✅ | ✅ |
| comment-rpc | ✅ | ✅ | ✅ |
| hot-rpc | ✅ | ✅ | ✅ |
| task-rpc | ✅ | ✅ | — |
| article-rpc | ✅ | — | — |

> ⇒ 合并到共享配置时取**并集**（Textual + Boolean + 未知字段策略）；**article / task 的差异需先核对原因**（是「不需要」还是「漏配」）。

## 五、逐文件清单（206 个）

| # | 域 | 主/测 | 迁移模式 | 迁移动作 | Jackson 2 import 摘要 |
|---:|---|:--:|---|---|---|
| 1 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty, annotation.JsonPropertyOrder |
| 2 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 3 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 4 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 5 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 6 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty, annotation.JsonPropertyOrder |
| 7 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 8 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 9 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 10 | article | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty, annotation.JsonPropertyOrder |
| 11 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 12 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 13 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 14 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 15 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 16 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 17 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 18 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 19 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 20 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 21 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 22 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 23 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 24 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 25 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 26 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 27 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonAutoDetect, annotation.JsonProperty |
| 28 | comment | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnoreProperties, annotation.JsonProperty, annotation.JsonPropertyOrder |
| 29 | common | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude |
| 30 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 31 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 32 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 33 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 34 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 35 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 36 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 37 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 38 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 39 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 40 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 41 | favorite | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty |
| 42 | follow | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 43 | follow | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 44 | follow | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty, annotation.JsonPropertyOrder |
| 45 | follow | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 46 | follow | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonAutoDetect, annotation.JsonProperty |
| 47 | hot | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty, annotation.JsonPropertyOrder |
| 48 | hot | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty, annotation.JsonPropertyOrder |
| 49 | hot | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnoreProperties |
| 50 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 51 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 52 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 53 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 54 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 55 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 56 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 57 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 58 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 59 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 60 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 61 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonAutoDetect, annotation.JsonProperty |
| 62 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnoreProperties, annotation.JsonProperty |
| 63 | like | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty, annotation.JsonPropertyOrder |
| 64 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 65 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonFormat |
| 66 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonFormat, annotation.JsonProperty |
| 67 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonFormat, annotation.JsonProperty |
| 68 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 69 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 70 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonFormat, annotation.JsonProperty |
| 71 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 72 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 73 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonFormat, annotation.JsonProperty |
| 74 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 75 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 76 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonFormat, annotation.JsonProperty |
| 77 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 78 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 79 | message | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonAutoDetect, annotation.JsonProperty |
| 80 | task | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnoreProperties, annotation.JsonProperty |
| 81 | task | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 82 | task | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 83 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty |
| 84 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 85 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnore, annotation.JsonInclude, annotation.JsonProperty |
| 86 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 87 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 88 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 89 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty |
| 90 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 91 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude |
| 92 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 93 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 94 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnore, annotation.JsonInclude, annotation.JsonProperty |
| 95 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnore, annotation.JsonInclude, annotation.JsonProperty |
| 96 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty |
| 97 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 98 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude |
| 99 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonIgnore, annotation.JsonInclude, annotation.JsonProperty |
| 100 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 101 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 102 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 103 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty |
| 104 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty |
| 105 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonProperty |
| 106 | users | 主 | J2-ANNOTATION-ONLY | **零改动**（`com.fasterxml.jackson.annotation` 两代通用） | annotation.JsonInclude, annotation.JsonProperty |
| 107 | article | 主 | J2-BEAN-CONFIG(可删) | **删除该类**；其「刻意配置」并入 Boot 4 的 `JsonMapper.BuilderCustomizer`（共享 1 份） | databind.DeserializationFeature, databind.ObjectMapper, databind.json.JsonMapper |
| 108 | comment | 主 | J2-BEAN-CONFIG(可删) | **删除该类**；其「刻意配置」并入 Boot 4 的 `JsonMapper.BuilderCustomizer`（共享 1 份） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 109 | hot | 主 | J2-BEAN-CONFIG(可删) | **删除该类**；其「刻意配置」并入 Boot 4 的 `JsonMapper.BuilderCustomizer`（共享 1 份） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 110 | like | 主 | J2-BEAN-CONFIG(可删) | **删除该类**；其「刻意配置」并入 Boot 4 的 `JsonMapper.BuilderCustomizer`（共享 1 份） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 111 | task | 主 | J2-BEAN-CONFIG(可删) | **删除该类**；其「刻意配置」并入 Boot 4 的 `JsonMapper.BuilderCustomizer`（共享 1 份） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 112 | comment | 主 | J2-COERCION | 改包名；**coercion 行为适配**（Jackson 3 只 LogicalType 级生效，类级无效） | core.JsonParser, databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 113 | comment | 测 | J2-COERCION | 改包名；**coercion 行为适配**（Jackson 3 只 LogicalType 级生效，类级无效） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 114 | like | 主 | J2-COERCION | 改包名；**coercion 行为适配**（Jackson 3 只 LogicalType 级生效，类级无效） | core.JsonParser, databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 115 | like | 测 | J2-COERCION | 改包名；**coercion 行为适配**（Jackson 3 只 LogicalType 级生效，类级无效） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 116 | like | 测 | J2-COERCION | 改包名；**coercion 行为适配**（Jackson 3 只 LogicalType 级生效，类级无效） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 117 | like | 测 | J2-COERCION | 改包名；**coercion 行为适配**（Jackson 3 只 LogicalType 级生效，类级无效） | databind.DeserializationFeature, databind.ObjectMapper, databind.cfg.CoercionAction, databind.cfg.CoercionInputShape, databind.json.JsonMapper, databind.type.LogicalType |
| 118 | article | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | core.type.TypeReference, databind.ObjectMapper |
| 119 | article | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 120 | article | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 121 | article | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 122 | article | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 123 | article | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 124 | article | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 125 | article | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 126 | comment | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 127 | comment | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 128 | comment | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | annotation.JsonIgnoreProperties, annotation.JsonProperty, annotation.JsonPropertyOrder, databind.DeserializationFeature, databind.ObjectMapper, databind.json.JsonMapper |
| 129 | comment | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 130 | comment | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 131 | comment | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper, databind.json.JsonMapper |
| 132 | common | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 133 | common | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | core.JsonGenerator, databind.ObjectMapper |
| 134 | common | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | annotation.JsonInclude, annotation.JsonProperty, core.JsonParser, databind.DeserializationFeature, databind.ObjectMapper |
| 135 | common | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.DeserializationFeature, databind.ObjectMapper |
| 136 | common | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 137 | common | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 138 | follow | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 139 | hot | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 140 | hot | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 141 | hot | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 142 | hot | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 143 | hot | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 144 | like | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 145 | like | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 146 | like | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 147 | like | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 148 | like | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 149 | like | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 150 | like | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 151 | like | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 152 | like | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 153 | like | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 154 | message | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | core.type.TypeReference, databind.ObjectMapper |
| 155 | task | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 156 | task | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 157 | task | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 158 | task | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.DeserializationFeature, databind.ObjectMapper, databind.json.JsonMapper |
| 159 | users | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 160 | users | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | core.type.TypeReference, databind.ObjectMapper |
| 161 | users | 测 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | databind.ObjectMapper |
| 162 | users | 主 | J2-PLAIN(Mapper/注解增强) | 改包名 → `tools.jackson.databind.*`；**默认值语义复核**（`new ObjectMapper()` 处 Jackson 3 的 `FAIL_ON_TRAILING_TOKENS` 默认 true） | core.type.TypeReference, databind.ObjectMapper |
| 163 | article | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper, databind.node.ObjectNode |
| 164 | comment | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper |
| 165 | comment | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | annotation.JsonInclude, annotation.JsonProperty, annotation.JsonPropertyOrder, databind.ObjectMapper, databind.node.ObjectNode |
| 166 | comment | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.ObjectMapper, databind.node.ObjectNode |
| 167 | favorite | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode |
| 168 | favorite | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | annotation.JsonProperty, databind.DeserializationFeature, databind.JsonNode, databind.ObjectMapper |
| 169 | favorite | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper |
| 170 | favorite | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode |
| 171 | hot | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper |
| 172 | like | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper |
| 173 | like | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper |
| 174 | like | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.ObjectMapper, databind.node.ObjectNode |
| 175 | like | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.ObjectMapper, databind.node.ObjectNode |
| 176 | like | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.ObjectMapper |
| 177 | like | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.ObjectMapper |
| 178 | task | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.json.JsonMapper |
| 179 | task | 测 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.json.JsonMapper |
| 180 | task | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper |
| 181 | task | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | annotation.JsonProperty, core.JsonProcessingException, databind.JsonNode, databind.ObjectMapper |
| 182 | task | 主 | J2-TREE-API | 改包名；**`JsonNode.toString()` 须字节对拍**（影响 `payload_hash`） | databind.JsonNode, databind.ObjectMapper |
| 183 | article | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 184 | comment | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 185 | common | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 186 | common | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 187 | common | 测 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 188 | common | 测 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 189 | favorite | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 190 | favorite | 测 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 191 | follow | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 192 | follow | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 193 | like | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 194 | like | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 195 | like | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 196 | message | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 197 | message | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 198 | task | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 199 | task | 测 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 200 | task | 测 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 201 | users | 主 | J3-ONLY | **原样保留**（已是 Jackson 3） | — |
| 202 | favorite | 测 | MIXED-J2+J3 | **零改动**（DTO：注解 J2 + 自定义 deserializer J3，两侧都已是目标态） | annotation.JsonProperty |
| 203 | like | 主 | MIXED-J2+J3 | **零改动**（DTO：注解 J2 + 自定义 deserializer J3，两侧都已是目标态） | annotation.JsonProperty |
| 204 | like | 主 | MIXED-J2+J3 | **零改动**（DTO：注解 J2 + 自定义 deserializer J3，两侧都已是目标态） | annotation.JsonProperty |
| 205 | like | 主 | MIXED-J2+J3 | **零改动**（DTO：注解 J2 + 自定义 deserializer J3，两侧都已是目标态） | annotation.JsonProperty |
| 206 | message | 主 | MIXED-J2+J3 | **零改动**（DTO：注解 J2 + 自定义 deserializer J3，两侧都已是目标态） | annotation.JsonProperty |
