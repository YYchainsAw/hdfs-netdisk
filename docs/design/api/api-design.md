# 网盘基础版 API 接口设计（草案）

> 状态：设计稿，接口和功能尚未实现。本文件与 `docs/design/database/schema.sql`、`database-design.md` 配套阅读。实现可靠的上传重试前，须先审阅并应用本目录的 `migration-upload.sql`；当前原始建表脚本尚不包含上传内容状态和租约字段。

## 1. 范围与约定

- 前端通过 HTTPS 同源访问后端，统一前缀 `/api/v1`。Nginx 反向代理后端；浏览器不直连 HDFS、WebHDFS 或数据库。部署未启用 HTTPS 前不投入实际账号使用。
- 第一版单个部署可服务一个或多个预先创建的账号；不开放注册。账号由受控的初始化流程创建，密码使用 Argon2id 或 bcrypt 加盐哈希，不能存明文。
- ID 和版本号均作为**十进制字符串**传输，避免 JavaScript 对 64 位整数精度损失；`parentId: "0"` 是当前用户虚拟根目录，不是数据库记录。文件大小使用十进制字节数字符串。
- 成功的 JSON 默认使用 `{ "data": ..., "requestId": "服务端追踪号" }`；无响应体的 `204`、文件二进制和错误响应例外。时间为 UTC ISO 8601，如 `2026-10-29T06:30:00Z`。
- 数据库连接会话统一使用 UTC；过期、租约和终态时间以同一数据库的 UTC 时间为准，不能混用应用服务器本地时钟。返回给前端时转换为带 `Z` 的 ISO 8601 字符串。
- 所有对象的查询和操作都依据登录会话推导当前用户并校验归属，不接受客户端指定用户 ID、HDFS 地址、磁盘路径或存储对象 key。无权访问的资源按 `404` 处理，避免泄露是否存在。
- 文件名统一先做 Unicode NFC，再限制 1–191 个 Unicode 字符；拒绝 `/`、NUL、控制字符、`.`、`..`。区分大小写、保留末尾空格；同一用户的同一目录内文件和目录共用名称空间。
- 文件仅在节点 `READY` 时出现在列表和下载接口。第一版不包含回收站、分享、在线预览、历史版本、断点续传和容量配额。删除不可通过网盘恢复。

## 2. 登录、CSRF 与会话

设计采用服务端会话 Cookie（`HttpOnly; Secure; SameSite=Lax; Path=/`）；必须在 HTTPS 下部署。状态变更请求（含登录、登出、上传及文件操作）需发送 `X-CSRF-TOKEN`，服务端校验令牌与当前会话匹配并检查可信 Origin；不可只依赖 SameSite。无跨域业务时不启用凭据跨域 CORS。

| 方法与路径 | 认证 | 说明 |
| --- | --- | --- |
| `GET /auth/csrf` | 可匿名 | 建立或复用匿名会话并返回当前 CSRF 令牌，且禁止缓存；响应例：`{"data":{"csrfToken":"..."}}` |
| `POST /auth/login` | CSRF 必需 | 请求 `{"username":"alice","password":"..."}`。成功后更换会话 ID 与 CSRF 令牌，返回 `{"data":{"user":{"id":"1","username":"alice"},"csrfToken":"..."}}`；后续请求使用新令牌 |
| `GET /auth/me` | 已登录 | 返回当前账号的 `id`、`username`；未登录返回 401 |
| `POST /auth/logout` | 已登录、CSRF | 使会话失效并清除 Cookie，返回 204；需要再次登录时先重新获取 CSRF |

认证失败不区分账号是否存在；对登录失败限速并记录安全事件，不记录密码和令牌。会话过期时返回 401，前端提示重新登录；权限检查必须在每次请求执行。

## 3. 错误和并发协议

错误格式：`{"error":{"code":"NAME_CONFLICT","message":"目标目录已有同名项目","details":{}},"requestId":"追踪号"}`。不得在错误中返回堆栈、真实存储路径或其他用户信息。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` | 名称、ID、请求格式等不合法 |
| 401 | `UNAUTHENTICATED` | 会话不存在或过期 |
| 403 | `CSRF_INVALID` | CSRF 或 Origin 校验失败 |
| 404 | `NOT_FOUND` | 节点或任务不存在、已删除或无权访问 |
| 409 | `NAME_CONFLICT` | 目标目录有同名文件或目录；上传任务进入 `CONFLICT` |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一幂等键对应不同创建参数 |
| 409 | `INVALID_STATE`、`UPLOAD_IN_PROGRESS`、`CONTENT_ALREADY_UPLOADED` | 当前状态不能执行、同任务已有写入者、内容已经就绪 |
| 409 | `DIRECTORY_NOT_EMPTY`、`INVALID_MOVE` | 删除非空目录或移动到自身/子目录 |
| 412 | `VERSION_MISMATCH` | `If-Match` 与当前节点版本不一致 |
| 413 | `FILE_TOO_LARGE` | 超出配置的单文件大小限制 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 上传内容类型不是约定的二进制流 |
| 422 | `CONTENT_MISMATCH` | 上传长度或可选摘要校验不符 |
| 428 | `VERSION_REQUIRED` | 修改、移动或删除节点缺少 `If-Match` |
| 429 | `RATE_LIMITED` | 请求过于频繁 |
| 503 | `STORAGE_UNAVAILABLE` | HDFS 或其他必要服务不可用；可以带 `Retry-After` |

`GET /nodes/{id}` 返回 `ETag: "v3"`（例子，3 为节点当前 version）。修改、移动、删除必须携带对应的强条件头 `If-Match: "v3"`，后端必须在同一数据库事务中检查并更新版本，未匹配返回 412。列表每项也返回 `version`，前端可拼出对应头；版本不匹配时刷新并请用户重新操作。创建子目录和上传不依赖 parent version：事务中锁定根对应用户行或实际父目录行，验证权限、目录状态和名称唯一，避免旧列表引发同名竞争；锁与文件夹删除、移动使用一致规则。

## 4. 文件与目录接口

`Node` 示例（文件夹的 `sizeBytes` 为 null）：

```json
{"id":"25","parentId":"0","name":"报告.pdf","type":"FILE","sizeBytes":"102400","version":"3","updatedAt":"2026-10-29T06:30:00Z"}
```

| 方法与路径 | 请求 | 成功响应与规则 |
| --- | --- | --- |
| `GET /nodes?parentId=0&keyword=&page=1&pageSize=50` | `parentId` 必填；可选名称子串 `keyword`；1 开始分页，默认 50，最大 100 | `200 {"data":{"items":[Node],"page":1,"pageSize":50,"hasMore":false}}`；仅当前目录 READY 项；目录优先、名称再 ID 排序。并发修改时分页不是快照，前端可刷新 |
| `GET /nodes/{id}` | id 为非根节点 | `200 {"data":Node}`，响应包含 `ETag: "v3"` |
| `POST /folders` | `{"parentId":"0","name":"文档"}` | `201 {"data":Node}`；返回新节点 ETag；父节点不存在或不可用返回 404，名字冲突返回 409 |
| `GET /nodes/{id}/content` | 文件节点 | `200` 流式下载；使用安全编码的 `Content-Disposition: attachment`，有长度时提供 `Content-Length`。第一版不承诺 Range；目录返回 409 |
| `PATCH /nodes/{id}/name` | `If-Match: "v3"`；`{"name":"新名称.pdf"}` | `200 {"data":Node}`，新 ETag；同名 409，过期版本 412 |
| `POST /nodes/{id}/move` | `If-Match: "v3"`；`{"targetParentId":"8"}` | `200 {"data":Node}`，新 ETag；目标不存在 404、同名或非法循环 409 |
| `DELETE /nodes/{id}` | `If-Match: "v3"` | `202 {"data":{"id":"25","state":"DELETING"}}`：业务删除已受理且节点从正常列表消失，HDFS 清理异步完成；第一版目录仅允许空目录删除。最终无回收站 |

删除需由用户界面事先确认，并在事务中校验版本、锁定目录及记录清理任务；清理失败不报告存储已物理删除。已经开始的下载需与后台清理协调；如果流开始后发生故障，关闭连接，前端提示重试，不能在二进制响应中追加 JSON 错误。

## 5. 上传协议

### 5.1 状态

现有任务状态使用数据库中的 `UPLOADING`、`CONFLICT`、`FINALIZING`、`COMPLETED`、`FAILED`、`CANCELLED`，不另造持久化的任务状态。

增量设计在 `nd_upload` 中加入：

| 字段 | 意义 |
| --- | --- |
| `content_state` | `AWAITING` 等待全量发送、`RECEIVING` 有写入尝试、`READY` 已关闭并校验 |
| `attempt_no` | 每次领取全量写入尝试递增；用于阻止过期写入者提交 |
| `lease_token`、`lease_until` | 当前写入者的服务端令牌与租约，按数据库时间检查；不暴露给客户端 |
| `received_size`、`content_sha256` | 服务端实际接收长度和摘要；`READY` 时必须有值 |
| `resolution` | 已接受的 `KEEP_BOTH` 或 `SKIP`；没有做过冲突选择时为 null |
| `terminal_at` | 首次进入 `COMPLETED`、`FAILED` 或 `CANCELLED` 的数据库时间，非终态为 null |

`nd_upload.sha256` 是客户端可选的预期摘要；`content_sha256` 始终为服务端实际计算值。写入前任务 `temp_key` 可以是未使用的占位唯一值；每次领取新尝试时替换为**全新、不复用的随机临时对象 key**，并在同一数据库事务中登记上一次尝试需要清理的 key。不可让新旧写入者使用同一个 HDFS 对象。

### 5.2 创建任务

`POST /uploads`，请求头 `Idempotency-Key: <1–128 位 ASCII 随机字符串>`；以登录用户和该键作为幂等范围。

```json
{"parentId":"0","name":"报告.pdf","sizeBytes":"102400","sha256":null}
```

合法 `sha256` 为 64 位十六进制或 null。响应 `201`：

```json
{"data":{"id":"88","status":"UPLOADING","contentState":"AWAITING","expiresAt":"2026-10-29T07:30:00Z"}}
```

重复同键且参数完全一致返回 `200` 和原任务（包含当前状态）；目录、规范化名称、长度或摘要不同返回 `409 IDEMPOTENCY_CONFLICT`。不重用不同用户的任务。终态和 `terminal_at` 必须同一事务记录；以该数据库时间为起点至少保留幂等记录 30 天，可配置延长。清理前仍须确认相关恢复与存储清理义务已完成；未完成时继续保留。记录清理后 `GET` 返回 404，`POST` 同键会被视为新的请求。客户端应在保留期内重试，并为新的一次上传生成新随机键，不依赖无限期去重。创建时检查目标目录有效，提交时仍须重新检查。

`expires_at` 仅用于尚未完成的 `UPLOADING` 或 `CONFLICT` 任务清理：活动租约有效时不得仅因该时间删除对象；服务端应在有效传输期间延长任务到期时间。到期且无活动写入的任务转 `FAILED` 并安全清理。`FINALIZING` 不因到期直接删除或判失败，必须先对账恢复；终态幂等记录的保留期限另行配置，不把任务过期等同于记录删除。

### 5.3 发送内容

`PUT /uploads/{id}/content`，`Content-Type: application/octet-stream`，请求体是**完整文件**。浏览器用上传事件显示进度；网络进度不代表已经发布成功。允许空文件，但按配置拒绝超大文件；服务端边读边限制实际字节数，不信任 Content-Length。成功返回 `200 {"data":{"id":"88","status":"UPLOADING","contentState":"READY"}}`。

领取写入尝试须锁定任务并验证用户、任务状态、过期时间：由 `AWAITING` 原子切换到 `RECEIVING`，增加 `attempt_no`，生成新 `temp_key`、`lease_token` 和 `lease_until`；并发 PUT 返回 `409 UPLOAD_IN_PROGRESS`。流式写入时定期按任务 ID、attempt、token 和未过期租约 CAS 续租。租约失效的写入者必须停止并不可发布。

如果 `RECEIVING` 的租约已经过期，由恢复器或新的 PUT 在锁定任务后再次确认数据库时间超过 `lease_until`，将旧 key 记入清理队列、撤销旧令牌并将状态设回 `AWAITING`；再领取新的 attempt 和随机 key。两个恢复者之间仍使用行锁或条件更新，只有一个能成功领取。回收不能只依据浏览器断开或网络超时判断写入线程已经退出。

当字节流关闭、长度和服务端 SHA-256 校验通过后，只允许持有**当前 attempt + token 且租约未过期**的工作者在事务中把 `content_state` 改为 `READY`、写入接收长度与摘要并清除租约；CAS 未命中即为过期写入者，不得影响当前尝试。若有预期摘要必须匹配；不匹配或长度不符返回 422，任务可标记 FAILED 并清理临时对象。收到内容但响应丢失时先查询任务：`READY` 则继续提交；`RECEIVING` 等待或按租约恢复；已 READY 时再 PUT 返回 `409 CONTENT_ALREADY_UPLOADED`，前端查询而不是盲目覆盖。中断或租约过期后只能从头发送，领取新尝试并使用新 key。

过期对象登记到清理任务；清理前检查当前引用、进行安全宽限并确认 HDFS 写入/租约已关闭或恢复。失效工作者不能提交，即使其旧对象后来出现，也不能污染新对象；定期扫描受控临时前缀以处理重新出现的孤儿。若 HDFS 暂时无法确认安全状态，则延后清理。

### 5.4 提交与冲突选择

`POST /uploads/{id}/complete` 无请求体，只有 `UPLOADING + READY` 才可首次提交。服务端事务中锁定任务和父目录，重新检查目录状态与权限，尝试插入 `PENDING` 文件节点以预留名称：

- 不冲突：任务转 `FINALIZING`，返回 `202 {"data":{"id":"88","status":"FINALIZING"}}`；后台将**当前已验证尝试的唯一 key**发布到唯一正式 key，再以数据库事务将节点改为 `READY`、任务改为 `COMPLETED`。只有完成后才算上传成功。
- 名称被占用（含同名文件或目录、PENDING、DELETING）：返回 `409 NAME_CONFLICT`，同时任务进入 `CONFLICT`；错误 `details` 至少包含 `uploadId`、`requestedName`、`choices: ["KEEP_BOTH","SKIP"]`，不泄露其他用户信息。
- 目标目录不存在或不属于当前用户：返回 `404 NOT_FOUND`；存在但不是可写的 READY 目录：返回 `409 INVALID_STATE`。两种情况均令任务转 `FAILED`、写入 `terminal_at` 并排队安全清理，不会删除或覆盖原文件。

`POST /uploads/{id}/resolve`，请求示例 `{"choice":"KEEP_BOTH"}` 或 `{"choice":"SKIP"}`，仅在 `CONFLICT` 首次决策：

- `KEEP_BOTH`：在锁定目录和任务后生成 `报告 (1).pdf` 等候选名称，依靠唯一约束抢占；竞争时重新编号并有限重试，成功后转 `FINALIZING` 并返回 202。不得静默替换原文件。
- `SKIP`：转 `CANCELLED`，登记临时对象清理，返回 `200`。
- 同一决策重复提交时返回当前结果；不同决策或决策后改选返回 `409 INVALID_STATE`。为可靠重试，实施前须将已接受的冲突选择持久化：在任务上新增可空 `resolution` 字段（见本目录增量脚本）；不得只根据 `CANCELLED` 猜测它来自跳过或取消。

自动命名在 NFC 规范化后按 Unicode 字符数限制为 191：普通文件名以最后一个非首字符的点分隔扩展名，点开头且没有其他点的名称视为无扩展名；将 ` (数字)` 插入扩展名前。名字超长时在保留后缀和可容纳的扩展名的前提下截短主体并重新规范化；若后缀加扩展名本身无法容纳，返回明确的命名冲突错误，不截断成非法名。用户可取消该任务，并以较短名称重新创建上传。每个候选都通过完整名称校验及数据库唯一约束。

重复 `complete`：`FINALIZING` 返回 202、`COMPLETED` 返回 200 和最终节点、`CONFLICT` 返回相同的 409；其余终态返回 409。HDFS 发布和数据库提交没有共同事务；任何不确定结果保持 `FINALIZING` 并由恢复逻辑核验，不得返回虚假成功。

### 5.5 查询与取消

| 方法与路径 | 成功响应与规则 |
| --- | --- |
| `GET /uploads/{id}` | `200 {"data":{"id":"88","status":"COMPLETED","contentState":"READY","requestedName":"报告.pdf","targetName":"报告 (1).pdf","node":Node,"expiresAt":"..."}}`；非完成态的 node 为 null，完成后若节点随后被删除也可能为 null；不暴露临时/正式 key、租约令牌 |
| `POST /uploads/{id}/cancel` | 对 `UPLOADING` 或 `CONFLICT` 原子设置 `CANCELLED`、使当前令牌失效并登记清理；`200` 返回任务状态。已取消重复调用返回当前结果；`FINALIZING` 或 `COMPLETED` 返回 409，不回滚已发布文件 |

写入时取消与完成必须在任务行上串行化。转为 `CANCELLED` 或可确认的 `FAILED` 时，同一事务将 `content_state` 设为 `AWAITING`、清空 `lease_token` 和 `lease_until`，并将当前临时 key 登记清理；若已写入 `received_size` 或 `content_sha256` 也一并清空。这样满足迁移中的租约 CHECK，并使旧工作者的 CAS 失败。清理队列的执行仍必须等待 HDFS 写入安全结束，不能把撤销数据库租约误当作已经关闭 HDFS 流。

失败后的终态不复用。客户端异常重试先查询状态；即使完成后的节点又被删除，重复提交仍返回已完成任务及 `node: null`，不能重新创建文件。恢复器对 `FINALIZING` 对账 HDFS 对象和节点，幂等完成或安全回滚，不能把未完成文件展示为成功。

## 6. 联调验收

1. 无 Cookie 或错误 CSRF 的接口被拒绝；登录轮换会话后旧令牌失效，用户不能访问其他用户资源。
2. 同名上传返回冲突；选择保留两份得到不同名称，跳过不改变原文件；重复同一选择不产生重复节点。
3. 并发同名上传只允许一个占用原名称；独立的自动编号候选仍受唯一约束保护。
4. 并发发送同一任务内容仅一个持有租约；旧尝试在过期后即使恢复也不能标记 READY 或发布；新旧临时 key 不同。
5. 同一个幂等键、同一参数只建立一个任务；参数不同返回 409；流响应丢失时查询状态恢复。
6. 旧版本重命名/移动/删除返回 412；非空目录删除及移动到子目录返回 409；文件夹删除和子节点写入竞争不会丢失数据。
7. 断电、HDFS 不可用、空间不足和提交中途崩溃时不返回伪成功；恢复器不会删除正常文件或正在写入的对象。

上述租约、版本和 HDFS 恢复语义需要后端实现以及故障注入测试；SQL 表本身无法保证跨系统事务或服务端安全。
