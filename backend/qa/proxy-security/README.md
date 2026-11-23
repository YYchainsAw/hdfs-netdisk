# 反向代理伪造头隔离验证

该测试在后端源码的临时副本中使用 H2 内存数据库、Spring Boot 随机本地端口和独立的 Nginx 进程，不读取生产数据库密码，也不更改或重载生产 Nginx。测试会读取当前宝塔网盘 `netdisk-app.conf` 中的 `/api/` 代理块，只把上游地址改为临时测试后端。它验证攻击者伪造 `Forwarded`、`X-Forwarded-*` 并交替登录自己的账号时，不能通过伪造地址绕过同一真实客户端针对受害者账号的登录限速。

在有足够可用内存的服务器上运行：

```bash
bash run-proxy-security.sh
```

默认从 `/www/wwwroot/netdisk/backend` 复制源码，可用 `NETDISK_QA_PROJECT_ROOT` 或 `NETDISK_QA_BACKEND_SRC` 指向隔离快照。需要预装 Java 17、Maven Wrapper 依赖缓存和宝塔 Nginx。临时文件创建于 `/tmp/netdisk-proxy-qa-src.*`，正常退出时自动清理。此测试模拟的是同一回环地址的攻击者与生产反向代理配置；不能替代不同真实公网 IP、多代理链或分布式攻击测试。限速仅单进程内生效，进程重启后重置。
