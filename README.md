# 云屿 HDFS 网盘

云屿是一个使用 Vue 3、Spring Boot 和 HDFS 的个人网盘课程项目。MySQL 保存账号、目录和文件元数据；文件内容通过后端使用 WebHDFS 读写。当前为单机、单后端进程的实现，适用于课程演示和单机部署。

仓库提供独立单节点的 Compose 课程演示环境，包含 MySQL、HDFS、后端和前端。启动及限制见 [Compose 部署说明](deploy/README.md)：需要 Linux、Docker 等依赖，HTTPS 反向代理仍需手动配置。该环境使用独立数据卷，不会自动接管已有的生产数据。

## 功能状态

| 功能 | 状态 |
| --- | --- |
| 注册、登录、退出、修改密码 | 已实现；修改密码使当前会话退出，其他会话在后续请求时失效 |
| 文件夹创建、多级目录、当前目录搜索和分页 | 已实现 |
| 文件上传、下载、重命名、删除及用量统计 | 已实现；删除先移入回收站，永久删除不可恢复 |
| 分片上传、中断续传、重试和同名处理 | 已实现，支持同名“保留两份”或“跳过”；新上传任务按后缀限制类型 |
| 移动与批量操作 | 支持同目录批量移动和逐项批量删除；单次移动最多 100 项 |
| 在线预览 | 支持常见图片、文本与 PDF，并有预览大小限制 |
| 文件分享 | 支持提取码、有效期、访问次数限制及取消；文件状态或版本变化后失效 |
| 回收站 | 支持文件及目录树恢复和永久删除；单次最多 1000 个节点 |
| 文件版本管理 | 当前版本尚未实现 |
| Docker Compose 部署 | 独立测试环境已验证启动、上传下载和重启持久化；HTTPS 代理需手动配置 |
| 数据库/HDFS 备份与恢复演练 | 尚未完成 |

**上传类型：** 新建上传任务仅允许以下文件名后缀（不区分大小写）：`txt`, `md`, `csv`, `json`, `pdf`, `png`, `jpg`, `jpeg`, `gif`, `webp`, `doc`, `docx`, `xls`, `xlsx`, `ppt`, `pptx`, `zip`, `rar`, `7z`, `tar`, `gz`, `mp3`, `mp4`。后缀检查不检测真实文件内容或病毒；已有文件及已有任务不受新策略影响，已有文件仍可重命名。新文件的上传时间记录发布完成时间；历史文件的创建时间可能早于上传完成。

**限速边界：** 登录限速保存在单个后端进程中，进程重启后清零；相同出口 IP 的用户共享 IP 总限额，不是分布式攻击防护。用户注册没有邮箱验证或 CAPTCHA。文件数量和用量展示不等于已实现用户持久容量配额。

## 目录

```text
backend/                     Spring Boot、Maven Wrapper、后端测试
backend/qa/                  单独运行的隔离集成、故障与性能测试
frontend/                    Vue 3、TypeScript、Vite
.github/workflows/ci.yml     后端测试和前端构建检查
docs/design/database/        数据库设计及基础建表脚本
docs/design/api/             接口草案、上传迁移和实现说明
docs/design/uml/             可编辑图源和 PNG
```

`docs/design/api/api-design.md` 和 UML 图中包含规划内容，以实际接口和上面的功能状态为准。`deploy/` 提供独立的 Compose 环境与启动说明。

## 运行环境

- Linux x86-64，Java 17；仓库含 Maven Wrapper，首次构建需网络下载依赖。
- Node.js 24.15.0 或受 `frontend/package.json` 和锁文件依赖支持的版本；CI 使用 Node.js 24.15.0。
- MySQL 8.4（建表脚本要求 MySQL 8.0.17 及以上），InnoDB 与 `utf8mb4_0900` 排序规则。
- 单节点 Hadoop 3.5.0 HDFS 和 WebHDFS；当前实现要求 NameNode WebHDFS 与 DataNode WebHDFS 均由后端本机访问，默认分别为 `127.0.0.1:9870` 和 `127.0.0.1:9864`。DataNode 重定向地址必须与后端的目标校验一致。
- HTTPS 反向代理及域名：生产 `server` profile 使用 Secure Cookie，浏览器应通过 HTTPS 同源访问。

当前环境的已验证路径包括 `/opt/hadoop`、`/www/wwwroot/netdisk`、HDFS `/netdisk/temp` 与 `/netdisk/users`。这些是本机配置示例，不代表软件在所有机器上开箱即用。

## 准备数据库

下面的五个 SQL 文件仅适用于**新建的空 `netdisk` 数据库**。脚本包括非事务性 DDL，按顺序各执行一次；不要对已有表或已有上传记录盲目重复执行。已有部署应先核对实际表、字段和索引状态，再决定迁移步骤。

1. 在本机 MySQL 创建 `netdisk` 空数据库，并为网盘创建专用账号。创建表和迁移使用有 DDL 权限的管理员；应用运行时只授予 `netdisk.*` 所需的 `SELECT`、`INSERT`、`UPDATE` 和 `DELETE` 权限。MySQL 容器与宿主机连接的客户端来源地址可能不是 `localhost`，账号 Host 必须与实际来源匹配。
2. 在仓库根目录，用有 DDL 权限的账号依次执行（`-p` 在终端交互读取管理员密码）：

   ```bash
   mysql -u <管理员用户名> -p netdisk < docs/design/database/schema.sql
   mysql -u <管理员用户名> -p netdisk < docs/design/api/migration-upload.sql
   mysql -u <管理员用户名> -p netdisk < docs/design/api/multipart-schema.sql
   mysql -u <管理员用户名> -p netdisk < docs/design/api/trash-schema.sql
   mysql -u <管理员用户名> -p netdisk < docs/design/api/share-schema.sql
   ```

   示例中的尖括号应替换，不能作为命令原样输入。`migration-upload.sql` 要求基础建表后 `nd_upload` 尚无数据；现有数据库不应将其当成幂等脚本重跑。
3. 应用账号密码由 `server` profile 读取服务器外部文件 `/etc/netdisk/secrets/spring.datasource.password`，文件名是 Spring 属性名。根据实际运行用户设置目录与文件的只读权限，密码不应放进 Git、镜像、命令行参数或日志。

`backend/src/main/resources/application-server.yaml` 默认连接 `jdbc:mysql://127.0.0.1:13306/netdisk`，账号为 `netdisk_app`；可用 `NETDISK_DB_URL` 和 `NETDISK_DB_USERNAME` 覆盖。连接串的 MySQL 时区需与数据库会话配置一致。数据库密钥目录不可读、账号 Host 不匹配或权限不足时后端可能启动失败。

## 准备 HDFS

按 [Hadoop 单节点部署文档](https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-common/SingleCluster.html) 安装并配置 NameNode、DataNode 和 WebHDFS，确保后端进程通过环回地址访问 NameNode 和 DataNode，且 DataNode 返回的 WebHDFS 重定向地址可由后端访问。**不要把 HDFS 和 MySQL 端口直接暴露到公网。**

当前客户端通过本机 WebHDFS 的 `user.name=hdfs` 访问 `/netdisk/temp`、`/netdisk/users`；应以 HDFS 管理员创建这些目录并设置最小必要访问权限。该部署依赖本机网络隔离和 HDFS 简单认证边界，不应将这一 HTTP 接口暴露给不可信客户端。示例检查：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  # 依机器调整
sudo -u hdfs env JAVA_HOME="$JAVA_HOME" /opt/hadoop/bin/hdfs dfs -ls -d /netdisk /netdisk/temp /netdisk/users
curl --fail --silent --show-error 'http://127.0.0.1:9870/webhdfs/v1/netdisk/temp?user.name=hdfs&op=GETFILESTATUS'
```

必须对新建集群初始化 NameNode；已有 HDFS 不能重复格式化。NameNode 元数据、DataNode 数据与 MySQL 元数据共同决定文件可用性。默认单个 DataNode、一个副本不提供高可用性。

## 构建与启动

以下命令以当前仓库版本及已完成前述依赖配置为前提。命令不会配置 HTTPS 或管理系统服务。

```bash
# 在仓库根目录
cd backend
./mvnw --batch-mode --no-transfer-progress test
./mvnw --batch-mode --no-transfer-progress package
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=server
```

`server` profile 默认仅监听 `127.0.0.1:18084`，进程须能读取外部数据库密钥。上传/清理使用 `/run/lock/netdisk-multipart.lock`：同一数据库与 HDFS 路径只能由**一个**受该锁保护的后端实例访问，不得通过其他 profile 或不同主机绕过。更多状态和清理规则见 [分片上传实现说明](docs/design/api/multipart-implementation.md)。

新开终端构建前端：

```bash
cd frontend
npm ci
npm run build
```

将 Nginx 站点根目录指向 `frontend/dist`，将 `/api/` 代理到 `http://127.0.0.1:18084`，并为路由提供 `index.html` 回退。反向代理必须覆盖外来 `Forwarded` 和 `X-Forwarded-*` 头并根据实际协议、主机与端口设置可信值；后端只接受本机代理访问。单个分片为 8 MiB，代理请求体限制需允许该大小，合并 2 GiB 文件时也需足够的代理读取超时。TLS 证书申请、端口和站点配置须按实际环境完成并经过 `nginx -t` 检查。

当前服务器使用 `netdisk-backend.service` 与 `netdisk-hdfs-{namenode,datanode,secondarynamenode}.service`；这些 systemd 单元尚未随仓库提供，不能把该名称当成新机器自动存在。当前后端监听端口为 18084，MySQL 宿主机映射端口 13306；换机器请检查端口冲突并按需调整配置。

## 账号与演示

系统支持注册，注册成功后使用本人设置的账号密码登录；仓库没有默认密码或演示账号。账号名需为 3–64 位英文字母、数字、`.`、`_`、`-`，密码至少 12 个字符且 UTF-8 不超过 72 字节。也可用交互式 CLI 创建账号，参见 [账号创建说明](backend/docs/CREATE_ACCOUNT.md)；账号创建工具在本机交互式终端运行，不需要停止网盘服务。不要将真实账号凭据写入说明或截图。

Vite 可用于本机调试：`cd frontend && npm run dev`，默认 `/api` 代理到 `127.0.0.1:18084`。`localdev` profile 关闭 Secure Cookie，必须限定为本机调试，不能向公网暴露；正式部署只使用 HTTPS。

## 测试与验证

- `cd backend && ./mvnw test`：常规测试使用 H2，部分显式指定真实 MySQL 的测试默认跳过，不代表已覆盖真实数据库及 HDFS。
- `cd frontend && npm ci && npm run build`：安装锁文件依赖并运行 TypeScript 检查与生产构建。
- `backend/qa/combined-qa`、`integration-qa`、`reliability-extra`、`proxy-security`、`move-qa`、`trash-qa`、`share-qa`：各目录 README 提供独立测试及资源要求；不要让测试指向生产数据库或真实用户 HDFS 路径。
- `backend/qa/performance-qa`：提供单机隔离性能测试、2 GiB 测试原始记录与限制；测试会使用真实 HDFS 空间并影响共享服务器资源。
- 验证记录见 [可靠性与安全性记录](backend/qa/VERIFICATION-20261123.md)。自动测试通过不等同于课程“优”等级认证。

## 常见问题

- **登录后返回未登录**：检查 HTTPS、浏览器 Cookie、服务器时间、会话有效性及反向代理转发头；生产 `server` profile 的 Cookie 需要 HTTPS。
- **接口返回 503 或上传失败**：检查 MySQL、HDFS、WebHDFS NameNode/DataNode 是否可从后端本机访问，以及 HDFS 容量、身份和目录权限；查看后端日志。
- **上传任务限额或空间不足**：当前单文件最大 2 GiB，每用户同时最多 2 个活跃任务、总 2 GiB；全局最多 4 个活跃任务、总 4 GiB。合并期间会有额外存储开销，空间检查保留至少 10 GiB，且不能阻止同盘其他服务继续占用空间。
- **并发或同名冲突**：刷新后重试；上传同名时选择保留两份或跳过，不会静默覆盖。
- **端口或锁冲突**：检查 18084 是否被占用，确认同一数据库和 HDFS 目录没有另一后端实例正在运行。不要为了绕过锁启动第二个生产实例。
