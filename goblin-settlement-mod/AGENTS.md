# 哥布林模组开发接续

- 当前工程是独立的 Fabric 模组。新对话先读 `../goblin-settlement-plan/CURRENT_STATUS.md`，再按任务读取设计或一张函数任务卡。
- 修改代码前检查相关目录是否已有实现；需要纯计算时先看 `function-bank/README.md`。
- 实际改动与重要验证追加到 `../goblin-settlement-plan/UpdateLog.md`；旧日志不可改、删或重排。段标题写带时区起止时间，每条写记录时间；中断遗漏以补记追加。
- 每轮项目工作后更新 CURRENT_STATUS.md，写清完成、验证、阻塞和下一步。它可覆盖更新，UpdateLog.md 不可。
- 当前配置：Minecraft 1.21.11 / Fabric Loader 0.19.2 / Java 21 字节码。固定 Gradle 9.2.1 的离线构建已通过；游戏验证范围见状态文件，勿将原型当作完整可玩版本。
- 单个工程、单个发布 JAR；服务端决定世界状态，客户端负责显示。不要修改相邻 ai-chat-mod 或把试验包直接装入常用存档。
- 函数库仅是候选成果，未验收文件不得加入运行时源码。用户当前请求决定是否继续开发；任务列表不代表执行授权。
