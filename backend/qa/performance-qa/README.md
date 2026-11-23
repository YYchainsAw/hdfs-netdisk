# 隔离性能基线测试

本测试在现有 `combined-qa` 隔离脚本基础上，创建一次性 MySQL 8.4 容器、临时数据库、随机 HDFS 测试目录和独立后端进程。测试数据不进入生产数据库或生产用户目录。脚本退出时由隔离脚本清理本次容器、匿名卷、HDFS 测试目录与锁文件。

测试包括：单个文件的 8 MiB 分片上传、最终合并、下载及 SHA-256 校验；随后使用两个临时账号并发完成 3 个各 16 MiB 的上传、合并、下载和校验。每片顺序发送，并发场景为 **3 个文件任务同时进行**，不代表单个文件有 3 片并发。结果输出 `PERF` 行，包括耗时和吞吐量；并发吞吐同时计入上传与下载的逻辑字节，不应与单向链路带宽直接比较。

## 前置条件

- Linux 服务器可运行 Docker、Java 17、Python 3 和 Maven Wrapper，已有 `mysql:8.4` 镜像及已启动的 HDFS；仓库内存在 `backend/qa/combined-qa`。
- 当前脚本要求至少约 1.9 GiB 可用内存，并对 HDFS 剩余空间进行检查；不应与其他资源密集的测试同时运行。
- 单节点 HDFS 与其他业务共用磁盘，容量检查只反映检查时刻，不能阻止其他程序随后占用磁盘。运行前后应检查空间和服务状态，出现异常立即停止并核对隔离资源。
- 避开生产业务高峰；本测试测量的是服务器本机到隔离后端的性能，不代表公网带宽或全部用户体验。

在目录下运行：

```bash
bash run-performance-baseline.sh | tee performance-result.txt
```

默认单文件大小为 32 MiB。可通过 `NETDISK_PERF_FILE_MIB` 设定 8 至 2048 的 8 MiB 整数倍；超过 256 MiB 时，必须显式设置 `NETDISK_PERF_LARGE=YES` 和 `NETDISK_PERF_SKIP_PARALLEL=YES`，只进行单文件测试，并确认时间、磁盘及业务条件适合。最大 2 GiB 测试可能耗时较长，可通过 `NETDISK_PERF_TIMEOUT_SECONDS` 设置 120–7200 秒。脚本没有硬性磁盘或网络限速，2 GiB 的合并阶段会同时读写大量数据，可能影响同盘业务。不得将实际运行结果写成未执行的 2 GiB 结果。

```bash
NETDISK_PERF_FILE_MIB=128 bash run-performance-baseline.sh | tee performance-128m.txt
```

记录时请同时保存测试日期、项目提交、服务器资源、文件大小、并发方式、原始 `PERF` 行、实际运行次数、异常和限制。多次运行取值范围或中位数，不应将单次测试结果当成性能保证。不要将临时口令或个人用户数据写入报告。

如果脚本被强制终止，自动清理可能未执行；应根据脚本输出和进程信息核对，只清理由本次创建的 `netdisk-multipart-it-*` 容器和 `/netdisk/{temp,users}/test-<本次随机标识>` 路径。不得清理其他数据。
