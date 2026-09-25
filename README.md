# 哥布林自治聚落模组

这是 Minecraft 1.21.11 的独立 Fabric 模组项目。代码位于 [`goblin-settlement-mod`](goblin-settlement-mod)，设计、当前进度和追加式开发日志位于 [`goblin-settlement-plan`](goblin-settlement-plan)。

## 当前进度

已实现聚落存档与土地权限、哥布林居民、真实公共箱子中的橡木板施工，以及最多八处两格工程的独立进度和派工。两名居民共享库存并行施工、缺料暂停、停服重载后补料接续已在项目专用服务端世界验证。当前仍是管理员命令登记工地与仓库的原型；自主选址、蓝图和完整生活行为尚未实现。详情见 [当前状态](goblin-settlement-plan/CURRENT_STATUS.md)。

## 构建

需要 Java 21。进入 `goblin-settlement-mod` 后运行 `./gradlew build`（Windows 可用 `gradlew.bat build`）。本机离线验证使用 Gradle 9.2.1 和已缓存依赖。生成的 JAR 在 `goblin-settlement-mod/build/libs/`。

仓库只跟踪哥布林项目源码与文档；Minecraft 实例、测试世界、运行日志和构建产物不入库。
