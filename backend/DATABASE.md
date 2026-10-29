# 网盘后端数据库连接

服务器上的 `server` 配置使用本机 MySQL `127.0.0.1:13306` 的 `netdisk` 数据库和专用账号 `netdisk_app`。数据库密码由 Spring Boot 从服务器的 `/etc/netdisk/secrets/spring.datasource.password` 读取，不提交 Git。

在服务器 IDEA 的运行配置中，将 **Active profiles** 设为 `server`；或在服务器终端运行：

```bash
cd /www/wwwroot/netdisk/backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=server
```

默认测试不启用 `server`，使用 H2 内存数据库。若要单独执行真实数据库的只读连接检查（需要有权读取服务器密钥文件）：

```bash
NETDISK_DB_INTEGRATION_TEST=true ./mvnw -Dtest=NetdiskDatabaseConnectionTest test
```

服务必须由有权读取密钥文件的系统用户运行，不能将密码复制进源码、Git 或日志。部署到 Docker 时需以只读方式挂载密钥，按容器运行用户设置访问权限；使用普通 bridge 网络时 `127.0.0.1` 指容器自身，需另行处理网络，Linux host 网络可以访问本机端口。`NETDISK_DB_URL` 和 `NETDISK_DB_USERNAME` 可按部署环境覆盖，密码由 configtree 文件提供。
