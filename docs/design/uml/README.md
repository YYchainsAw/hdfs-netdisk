# 网盘系统 UML 设计图

`src/` 保存 draw.io 可编辑源文件，`png/` 保存使用 draw.io Desktop 导出的对应图片。

- `netdisk-use-case`：系统用户及文件分享访问者的主要用例。
- `netdisk-class`：主要控制器、服务和数据访问组件的职责及部分依赖关系，非完整代码类图。
- `netdisk-database-erd`：主要数据库表、关键字段和部分外键关系；其他外键在字段中标明。
- `upload-conflict-flow`：分片上传、补传和同名冲突处理流程。

图的内容依据 2026-11-24 核对的集成工作树 `/opt/netdisk/worktrees/course-integrated`（提交 `4b320b3`）及其数据库设计和迁移脚本绘制。项目根目录当时检出的提交为 `2d7505f`，其代码可能早于该集成版本；图不能视为该检出版本已包含全部功能的证明。后续代码或数据库调整后，应同步核对并更新图。
