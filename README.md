# 哥布林自治聚落模组

这是 Minecraft 1.21.11 的独立 Fabric 模组项目。代码位于 [`goblin-settlement-mod`](goblin-settlement-mod)，设计、当前进度和追加式开发日志位于 [`goblin-settlement-plan`](goblin-settlement-plan)。

## 当前进度

七阶段最基本代码已进入初版统一验收。当前候选包含真实仓库取料施工、农业和食物、工具、林业、家庭与扩地、道路和桥梁、住房、六级傀儡及玩家关系。较早版本的两居民施工和一格小麦种收已在专用世界验证；目前源码已通过完整离线构建与五项独立检查，专用服务端可启动，失效仓库登记可在保存重进后恢复。其余新循环尚未完成游戏内验收，详情见 [当前状态](goblin-settlement-plan/CURRENT_STATUS.md)。

## 构建

需要 Minecraft 1.21.11、Fabric Loader 0.19.2、对应 Fabric API 和 Java 21。进入 `goblin-settlement-mod` 后运行 `./gradlew build`（Windows 可用 `gradlew.bat build`）。本机离线验证使用 Gradle 9.2.1 和已缓存依赖。生成的 JAR 在 `goblin-settlement-mod/build/libs/`。

## 专用世界试用

把生成的 `goblin-settlement-0.1.0.jar` 与对应 Fabric API 放入测试实例的 `mods` 目录。在适合建村的位置执行 `/goblinsettlement found`，在已认领地块里放置箱子并用 `/goblinsettlement warehouse <x> <y> <z>` 登记，随后召唤 `goblin_settlement:goblin`。用 `/goblinsettlement status` 查看人口与真实仓库库存；更多工程、农田、树木和交通命令可在游戏内输入 `/goblinsettlement` 查看补全。当前 JAR 是验收中的候选版本，请先在专用世界试用。

仓库只跟踪哥布林项目源码与文档；Minecraft 实例、测试世界、运行日志和构建产物不入库。
