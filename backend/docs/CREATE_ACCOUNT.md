# 创建网盘登录账号

账号创建工具必须在服务器本机交互式终端运行，不启用公开注册接口。工具使用网盘后端数据源、MyBatis-Plus 与登录相同的 BCrypt 密码编码器；已有同名账号不会被覆盖或重置。登录名支持 3–64 位 ASCII 字母、数字、`.`、`_`、`-`。密码至少 12 个字符，UTF-8 编码不超过 72 字节。

1. 在服务器终端进入后端目录并打包：

   ```bash
   cd /www/wwwroot/netdisk/backend
   ./mvnw package
   ```

2. 在同一交互式终端运行（不要通过管道或命令参数传入密码）：

   ```bash
   java -Dloader.main=xyz.yychainsaw.backend.cli.CreateNetdiskUser \
     -cp target/backend-0.0.1-SNAPSHOT.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher
   ```

3. 根据提示输入自选登录名和两次密码；密码输入不会回显，终端必须具有读取 `/etc/netdisk/secrets/` 的权限。网盘账号和 MySQL 专用账号是不同的账号。

注意：开发环境的 HTTP 本机调试仅用于测试；正式输入账号密码前，应先通过 Nginx 配置 HTTPS 并限制后端监听范围。此工具不更改现有账号，如需密码重置，需要另行设计和实现。
