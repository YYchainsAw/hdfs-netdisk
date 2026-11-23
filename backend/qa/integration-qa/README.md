# 上传下载隔离集成测试

该测试只创建一次性 MySQL 8.4 容器及其全新数据库，并在现有 HDFS 下使用随机测试目录。测试不会连接项目的生产 MySQL 数据库或读取生产网盘目录。运行仍会占用服务器资源，并在真实 HDFS 上创建和删除测试数据；应先确认本机与 HDFS 存储正常、资源充足。脚本退出时会删除本次测试容器、匿名卷、临时目录和随机 HDFS 测试目录。

## 运行条件

- Linux 服务器可执行 Docker、Java 17、Python 3、curl、openssl、sudo；本机有 `mysql:8.4` 镜像、HDFS 已启动，`hdfs` 用户可使用 `/opt/hadoop/bin/hdfs`。
- Maven Wrapper 及依赖已在本机缓存（脚本以离线模式构建）；空闲内存不少于约 1.4 GiB，运行强杀测试还需至少 1 GiB HDFS 空余。
- 真实后端单实例服务使用相同的进程锁；如服务已启动且持有该锁，本测试会拒绝并发启动。端口 18086 必须空闲才能运行强杀测试。
- 当前脚本默认项目在 `/www/wwwroot/netdisk`，其 `backend`、`docs/design/database/schema.sql`、`docs/design/api/migration-upload.sql` 和 `docs/design/api/multipart-schema.sql` 已存在。

## 执行

在服务器将本目录完整复制到独立目录后运行：

```bash
bash run-multipart-it.sh
```

此命令验证真实 MySQL 与 HDFS：大小为 8 MiB 以上的多分片续传、重复上传、下载字节一致、用户隔离、同名并发、取消、上传限制、垃圾回收和活动文件保护。还会在测试进程内模拟磁盘容量不足，验证创建、写片和合并失败后恢复。运行会模拟启动生命周期中的租约释放，但该命令不执行 JVM 强杀。

如需追加真正的 JVM 强杀恢复和临时浏览器联调，在交互式终端执行：

```bash
bash run-multipart-it.sh --hold
```

该模式会额外在分片写入中及 128 MiB 文件合并中强杀测试 JVM，重新启动、续传并核对下载摘要。完成后会在服务器 `127.0.0.1:18086` 暂时启动测试后端，测试账号为 `alice`，测试密码为 `integration-password`；该账号仅存在于本次临时数据库。看到 `E2E_READY` 后可通过 SSH 隧道进行浏览器测试，完成后在终端按回车或 Ctrl+C 清理环境。测试账号和密码不能用于生产服务。

更换项目路径或测试独立源码快照时，可设置 `NETDISK_QA_PROJECT_ROOT`、`NETDISK_QA_BACKEND_SRC`、`NETDISK_QA_SCHEMA`。脚本强制覆盖数据源连接、密码和服务器 secret 配置导入，数据库密码每次随机生成；不要向上述变量传入生产数据库地址。若系统中断导致退出清理未执行，请核对并清理本次 `netdisk-multipart-it-*` 容器和本次随机 `/netdisk/{temp,users}/test-<随机标识>` 目录，不要清理其他数据。

`DownloadController.java` 和 `DownloadMapper.java` 仅用于隔离源码快照缺少下载模块时的回退；完整项目本身已有对应文件时，脚本使用项目文件。测试对照运行时输出的 SHA-256 核对源码和数据库迁移版本。
