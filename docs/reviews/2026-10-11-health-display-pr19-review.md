# 血量显示 PR #19 审查报告（2026-10-11）

- **审查对象**：`7617d86 Merge pull request #19 from wuhanhao456/master`（作者 wuhanhao456，2026-10-10，两个提交 `373f40d` / `f6d9508`）
- **范围**：`git diff 7617d86^1..7617d86`，10 个文件、+448/−76
- **方法**：主审逐条取证 + 1 名独立审查者交叉复核；凡与审查者结论不一致处（见 §五）均以源码/字节码/实测为准
- **前提**：委托方已实机确认该 PR 功能正常，本报告只谈**规范符合性**与**回归风险**
- **规范基线**（`memory/project-context.md` + `memory/user-preferences.md` + 现网统计）：
  mixin 源文件 **45/50 含中文 javadoc**；`docs/**/*.md` **173/184 含中文**；`BeLoongCore.LOGGER` **63 处**调用点（`LoggerFactory` 3 文件为既有先例）；
  此前 **0 个 `IMixinConfigPlugin`**、只有 **1 份** `*.mixins.json`；`neo_version=21.1.236`；**项目无测试套件**

## 一、C-x（必须处理）及处置

| 编号 | 位置 | 问题 | 处置（2026-10-11） |
|---|---|---|---|
| **C-1** | `build.gradle:146-148`、`:364-365` | 新增 JUnit 5.11.4 + `src/test`，而 `build` 依赖 `test` ⇒ 离线/无网必失败（实测 `build --offline` 退出码 1，`No cached version available` 6 个 JUnit 产物）；且这**不是新做法**——项目曾主动删除同一套设施：`8a5cd7e chore: remove test source and configuration`（−218 行） | ✅ **已修**：删除 `src/test/**`、JUnit 三行依赖、`tasks.named('test', Test)` 配置块，恢复"无自动化测试"形态 |
| **C-2** | `mixin/healthbars/HealthTrackerMixin.java:26` | 可选模组目标缺 `require = 0`，而配置是 `defaultRequire: 1` ⇒ Health Bars 在场但 `tick` 改名即**启动期硬崩**（`@Pseudo` 只兜"类不存在"） | ✅ **已修**：`@Inject(..., require = 0, remap = false)`，与 `mixin/legendarymonsters/*`、`mixin/raidsenhanced/*` 等既有可选目标写法一致 |
| **C-3** | `HealthBarsMixinPlugin.java`、`beloong.healthbars.mixins.json`、`neoforge.mods.toml:62-63` | 项目首个 `IMixinConfigPlugin` + 第二份 mixin 配置；插件只按 modId 判定，`@Shadow` 字段/方法改名仍硬崩 ⇒ 复杂度净增、"防崩"是假的 | ✅ **已修**：删除插件与独立配置；`HealthTrackerMixin` 并入 `beloong.mixins.json` 的 `client` 数组；toml 只保留 `beloong.mixins.json` 一份配置 |
| **C-4** | `build.gradle`（依赖块）、`neoforge.mods.toml`（依赖段） | 未登记为可选依赖 ⇒ 版本偏差在启动日志不可见 | ✅ **已修**：`implementation "curse.maven:health-bars-1367734:7122333"`（按委托方要求联网下载、不走 local-repo）+ toml `modId="healthbars" type="optional" versionRange="[21.1.0,)" ordering="AFTER" side="CLIENT"`；modId/版本**取自实际 jar** |
| **C-5** | `docs/fixes/2026-10-10-growth-health-numbers.md:23-27` | 删除既有 `AsteorBarHealthFixMixin` 无裁定、无留痕；新 hook 只打 Health Bars，AsteorBar 侧"换维度后血量被钳到 20"**无替代** | ⏸ **暂不处理**（委托方：AsteorBar 修复是否回退尚未实测） |

## 二、I-x（应修）——委托方指示本轮不处理

| 编号 | 位置 | 问题 | 状态 |
|---|---|---|---|
| I-1 | 4 个新增 `.java` + 1 个新增 `.md` | 注释/javadoc/文档**全英文**（基线 45/50、173/184 为中文；**日志用英文这条没违反**） | ⏸ 未处理 |
| I-2 | `HealthBarsMixinPlugin.java:25,27` | 日志缺 `[BeLoong] <subsystem>:` 段（规范原文 `memory/project-context.md:340-344`） | ✅ 随 C-3 **自动消失**（插件已删） |
| I-3 | `beloong.healthbars.mixins.json:1-8` | 缺 `"refmap"`，与 `beloong.mixins.json:4` 两种写法并存 | ✅ 随 C-3 **自动消失**（配置已删） |
| I-4 | `docs/fixes/2026-10-10-growth-health-numbers.md:38-51` | 文档称用 NeoForge **21.1.248** 构建、"产出 jar 要求 21.1.248+"，与仓库基线 `21.1.236` 冲突（本 PR 未改 `gradle.properties`，仓库构建仍产 `[21.1.236,)`） | ⏸ 未处理 |
| I-5 | `build.gradle`（无 healthbars 条目） | dev 环境没有 Health Bars ⇒ hook 在 dev 永不生效（`run/logs/*` 中 `healthbars` 命中 0） | ✅ 随 C-4 **部分解决**（`implementation` 会进 dev 运行时）；但还需补它的前置，见 §四 T-2 |

## 三、S-x（可选）

- **S-1** `docs/fixes/` 是新目录（现行为 `docs/plans/*-design.md` 成对 + `docs/reviews/`）⇒ 建议并入或在 `project-context.md` 登记用途。⏸
- **S-2** `HealthTrackerMixin.java:16` javadoc 仅一行 ⇒ 建议补"字符串 target + `@Shadow` 私有字段"的耦合与代价（样板 `mixin/raidsenhanced/RaidBlimpTargetMixin.java:12-44`）。⏸
- **S-3** `GrowthHealthDeltaTracker.java:13` 的 `SYNC_WINDOW_TICKS = 2` 是唯一旋钮 ⇒ 建议在 javadoc 标出"调大=更耐包序抖动、但更晚出数"。⏸
- **S-4** `build.gradle` 中 AsteorBar 的 `compileOnly`/`localRuntime` 在删除 mixin 后已**无消费者**（全仓只剩那 3 行）⇒ 与 C-5 一并决定去留。⏸
- **S-5** 字符串 target 的同类先例 `BwgRegionBiomeRewriteMixin` 已被删除，教训记在 `worldgen/RegionBiomeRewriter.java:22-27`——注意那条讲的是"**只认某一个实现类**会被新实现绕过"，与字符串写法本身无关；此处代价是**无编译期校验**，值得写进注释。⏸

## 四、已知取舍 / 需委托方确认（不是缺陷）

- **T-1 只屏蔽显示、不动底层**：新方案不写血量（比被删的旧方案干净）⇒ 但"客户端血量被钳到默认上限"本身仍在，会影响**所有**读 `getHealth()` 的 HUD。若实机复现，应单开"客户端血量钳位"议题，而不是恢复旧 mixin。
- **T-2 dev 运行时还必须补 Health Bars 的 required 前置**：其 jar 的 `META-INF/neoforge.mods.toml` 声明 `puzzleslib` **required**（`[21.1.19,)`）；`implementation` 会把 healthbars 带进 dev 运行时 ⇒ 缺前置时 `runClient` 直接报缺依赖。整合包内实测 **`Puzzles Lib 21.1.52`**（modId `puzzleslib`、file id **8251525**）；坐标已确认并落地：`localRuntime "curse.maven:puzzles-lib-495476:8251525"`（下载件 1425176 字节，与整合包内文件大小一致）。整合包与各服务端自带该前置，因此这里只是 dev 运行时依赖。
- **T-3 三处硬崩点**：`fuzs.healthbars.client.helper.HealthTracker` 类名 / `tick(LivingEntity)` / `lastHealthDelta` 任一改动即硬崩或静默失效（代码与文档已自陈）⇒ C-2 + C-4 是其最低护栏。
- **T-4 `reset()` 不清 `lastTick/lastHealth/lastMaxHealth/lastGrowth`**（`GrowthHealthDeltaTracker.java:29-34`）：`initialized=false` 保证读不到 ⇒ 刻意最小清理，建议注释点明。
- **T-5 `health == 0` 同 tick 又回血**（`:70`）：净值会替换"−满血"，HUD 只显示净值。
- **T-6 状态机边界已守**：NaN/Inf/`maxHealth <= 0`（`:38-41`）、`tick < lastTick`（`:43-51`）、队列每窗清空无上限（`:79-91`）⇒ **未发现 C 级缺陷**。
- **T-7 合规项（正向）**：`mixin/healthbars/` 目录前缀、`beloong$` 前缀、`@Unique`、`*Mixin` 后缀、`remap = false`、复用 DS 自己的 `getGrowth()`（不复制算式）均符合；并**删掉了历史审查 L7-S2 清单里一个未受保护的 mixin**（净减一个硬崩面）。
- **T-8 `LoadingModList.get()` 时序**（原插件内）：若该阶段 mod 列表未就绪，功能会静默不生效、只留一条 WARN ⇒ 已随 C-3 删除插件而失效；改用 `@Pseudo` 后"模组不存在"由 Mixin 直接跳过。
- **T-9 提交信息英文**（`f6d9508`/`373f40d`）：历史协作者亦有英文式（PR #18）⇒ 按"不替他规范化"仅登记。

## 五、复核更正（推翻/修正独立审查者的结论）

- **R-1 "37 个测试用例"是对的，审查者算作 43 有误**：11 个非参数化 `@Test` + 参数化 26 例（2+3+3+12+2+4）= **37** ✓（审查者自己的分项相加也是 37）。*注：该测试文件已按 C-1 删除。*
- **R-2 "注释与文档口径矛盾"不成立**：注释在**非增长**分支（`GrowthHealthDeltaTracker.java:77-78`「heal followed by a hit must not disappear」），文档那句限定为「**During a growth/maximum-health transition** the number represents the net …」（`docs/fixes/…md:15`）⇒ 讲的是两条不同路径。
- **R-3 撤回主审自己的一条**：`LoggerFactory`（`HealthBarsMixinPlugin`）不构成偏离（既有 3 文件先例）；真正的问题是日志**格式**缺 `<subsystem>:` 段（I-2），而它随 C-3 消失。
- **R-4 字符串 target 的引用需限定**：见 S-5（那条教训讲的是"过窄目标"，非字符串写法本身）。
- **R-5 21.1.248 的严重度**：降为 I-4（PR 未改 `gradle.properties`；风险限于"部署作者本机 jar / 后人照文档改依赖"）。

## 六、本轮新增的独立取证（此前未做）

以下三项是修复过程中对**真实 jar**（`curse.maven:health-bars-1367734:7122333`，110.5 KB）的核实，此前只有 PR 文档的口头声明：

1. **目标确实存在**：`fuzs/healthbars/client/helper/HealthTracker.class` ✓，`public void tick(net.minecraft.world.entity.LivingEntity)` ✓，`private float lastHealthDelta` ✓。
2. **覆盖机制确实有效**：`getLastHealthDelta()` 第一条字节码即 `getfield lastHealthDelta:F` ✓；`tick()` 在开头与偏移 83 各 `putfield lastHealthDelta` ✓ ⇒ 我们的 `@Inject(at = TAIL)` 写入**在它之后**生效 ✓。
3. **元数据**：`modId=healthbars`、`version=21.1.0`、`displayTest="IGNORE_ALL_VERSION"`、依赖 `neoforge [21.1.21,)` / `minecraft [1.21.1]` / **`puzzleslib [21.1.19,)` required**；整合包 `BeLoong-Server\mods` **没有**装 Health Bars（`【化龍】服务端` 装了）⇒ toml 用 `side="CLIENT"` 是正确选择。

## 七、构建/静态门证据块（本机 2026-10-11）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `gradlew test build`（修复前） | **1** | 2m14s，`maven.neoforged.net:443` 连接超时；37 个测试无法复核 |
| `gradlew build --offline`（修复前） | **1** | 1s，`:testCompileClasspath` 缺 6 个 JUnit 5.11.4 产物 |
| 对照：PR 合入前本会话多次 `build --offline` | 0 | BUILD SUCCESSFUL |
| `gradlew build`（修复后） | 见提交记录 | 期望恢复为 SUCCESSFUL |

## 八、结论

- **C-1 ~ C-4 已按委托方裁定修复**：删除测试设施、补 `require = 0`、合并为单份 `beloong.mixins.json`、登记 `healthbars` 可选依赖（联网下载）。
- **C-5 暂不处理**（AsteorBar 修复是否回退待实测）；I/S 项按指示保留现状，其中 I-2/I-3 随 C-3 自然消失。
- **仍需委托方处理**：实机验收"无 Health Bars / 有 Health Bars"两种情形与换维度、重生场景（C-5 的回退风险一并观察）。
- **前置已解决**：Health Bars 的 required 前置 Puzzles Lib 坐标已确认（`curse.maven:puzzles-lib-495476:8251525`，v21.1.52），并以 `localRuntime` 落地（见 T-2）。
