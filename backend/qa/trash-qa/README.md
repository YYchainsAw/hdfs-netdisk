# 回收站隔离集成测试

测试使用独立 MySQL 8.4 临时容器、随机 HDFS 目录和单独的本机锁，不连接生产数据库或读取生产用户文件。测试会占用服务器资源，运行前需确认约 1.4 GiB 以上可用内存、足够的磁盘空间、Docker 和 HDFS 正常，以及本机已有 mysql:8.4 镜像、Java 17、Maven Wrapper 缓存。

在服务器上运行 `bash run-trash-it.sh`。脚本应用基础建表、上传迁移和回收站迁移后运行真实 MySQL/HDFS 测试，覆盖子目录递归回收、恢复名称冲突、版本与用户隔离、上传/删除竞争、永久删除及 HDFS 故障恢复。结束时清理本次临时容器、匿名卷、随机 HDFS 测试目录和本机锁。

默认项目目录为 `/www/wwwroot/netdisk`。需要使用隔离源码时设置 `NETDISK_QA_PROJECT_ROOT`、`NETDISK_QA_BACKEND_SRC`、`NETDISK_QA_SCHEMA`。测试进程配置为空的 configtree 并使用随机密码创建独立数据库；不要将生产数据库地址作为测试参数。`--hold` 会额外启动监听 `127.0.0.1:18086` 的隔离后端，供浏览器联调；完成后在运行终端按回车或 Ctrl+C 触发清理。

如果脚本被强制终止，清理可能不完整，须确认标识后只处理本次创建的 `netdisk-multipart-it-*` 容器和 `/netdisk/{temp,users}/test-<本次随机标识>` 目录，不得清理其他数据。测试结果不替代备份或恢复演练。
