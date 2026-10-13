# BeLoong-Core 项目约定（给 AI 智能体）

> 本文件是**项目级**约定，也是进入本仓库后第一份该读的东西。工作区级的 `..\AGENTS.md` 讲的是整个
> `D:\Minecraft`（10 个自有项目、环境事实、构建产物），本文件只补充 **BeLoong-Core 自己**；就近生效，两者不冲突。
>
> ⚠️ **最重要的一条边界**：`memory/` 与 `tools/` 都被 `.gitignore` 排除 ⇒ **干净克隆里没有它们**。
> 所以本文件必须自足。若你发现 `tools\validate_*.py` 不存在，说明你在一个没有本地门的工作区里——
> **不要凭空宣称"静态门全绿"**，只能报 `build` 结果并说明缺了哪些门。

## 一、这是什么

『化龍』整合包的定制魔改模组（模组名「化龙核心」），modid `beloong`。
`Minecraft 1.21.1` + `NeoForge` + `ModDevGradle` + `Java 21`——**具体版本只看 `gradle.properties`**
（`neo_version` / `parchment_*` / `mod_version`），不要在任何文档里写死版本号。

包结构（`src/main/java/com/zonlong/beloong/`）按**功能**分包；根级只有 `BeLoongCore`（@Mod 入口：
注册器 + 事件 handler + 数据 reload listener + 命令 + payload 注册）、`BeLoongCoreClient`（客户端初始化）、
`Config`（`CLIENT` / `COMMON` / `SERVER` 三份 spec）：

| 包 | 职责 |
|---|---|
| `mixin/` | 最大的一块；**按目标模组命名空间**分子目录（见 §四） |
| `client/` | 渲染器、天空、HUD、模型/动画，以及**客户端专属的处理逻辑**（从 `BeLoongCoreClient` 注册） |
| `compat/` | 跨模组兼容层（ftbchunks / lockdown / betterendisland / ironsspellbooks / dragonsurvival / healthbars …） |
| `registry/` | 物品、方块+BE、实体、音效、粒子、属性、效果、页签、判据、tag |
| `block/` `item/` `entity/` `fluid/` | 各类游戏对象本体 |
| `ability/` | Dragon Survival 技能效果（斩杀 / 龙卷风 / 空袭…） |
| `dialogue/` `route/` `treasure/` `structure/` `waystoneplacement/` `worldgen/` | 数据驱动子系统（Codec + 服务端 reload listener，可被数据包覆盖） |
| `cg/` | CG 运镜系统 |
| `teleport/` `transport/` | 维度传送（facade + handler） |
| `perf/` | 护栏（如特效实体上限：生成预筛 + 入世界闸门） |
| `command/` `network/` `hellgate/` `dreadking/` `util/` | 命令、载荷、具体功能包 |

资源侧：`assets/beloong/`（lang / models / textures / particles / sounds）、`data/beloong/`（维度、伤害类型、
技能定义、数据驱动内容），并覆盖 `minecraft` / `cataclysm` / `curios` / `dragonsurvival` / `terrablender`
命名空间的数据。datagen 输出到 `src/generated/resources`。
⚠️ 资源清单的真实位置是 **`src/main/templates/META-INF/neoforge.mods.toml`**（不是 README 描述的样子）。

## 二、构建与验证（命令必须可直接执行）

- 构建：`.\gradlew.bat build --rerun-tasks --no-build-cache`
  —— 要**新证据**必须带这两个 flag，否则会拿到 `UP-TO-DATE` / `FROM-CACHE`。
- 只编译：`.\gradlew.bat compileJava`；产物：`build/libs/beloong-<版本>.jar`。
- **本项目没有测试套件**：验证 = 静态门 + `build` + **作者在实机上验收**。行为正确性不要用"应该没问题"结案。
- 静态门（**只活在本机工作区**）：`python tools\validate_execute.py`、`validate_execute_line.py`、
  `validate_tornado.py`、`check_lang_parity.py`。缺了就按抬头那条办。
  ⚠️ **跑门之前先确认它校验的是当前检出**：工作区级 `D:\Minecraft\tools\YSMParser\` 下另有一批同名门
  （`route_invariants.py` / `cg_invariants.py` / `stage_invariants.py`），根路径**硬编码**到已从
  `git worktree` 移除的 `D:\Minecraft\BeLoong-Core-NPC` 旧副本 ⇒ 照原样跑会校验**另一个树**、
  并可能给出"全绿"的假信号。**不要据它们宣称门全绿。**
- **临时探针要有收尾清单**：为验证临时加的数据包 / 进度 / 调试命令一律用可识别命名（如 `*_test`），
  因为**它们会随 jar 发布**。收尾时必须删除，并**验证 jar 内无残留**（列 zip 条目确认，别只看源码目录）。
- 交付时给**证据块**（命令 + 退出码 + 警告数 + 产物复核），并遵守两条：
  - **核验产物要解析、不要字符串匹配** —— 子串匹配在"多个字段共用同一字面量"时必然给出误导结论；
  - **构建失败时 jar 是旧的、且不会报错** ⇒ 一看到 `FAILED`，随后所有针对产物的结论都作废，先修构建。
- 需要实机验收时，给「**编号 + 场景 + 期望值 + 判据**」的清单，并标出哪个常量是旋钮、往哪边拧。

## 三、从哪读源码（不要每次重新寻找与提取）

**NeoForge / 原版：**

| 用途 | 路径 |
|---|---|
| 只读源码树（引用一律给 `路径:行`） | `build/review-src/neoforge-21.1.236/` |
| 它的来源（`build/` 被清掉后照此重建） | 解压 `build\moddev\artifacts\neoforge-21.1.236-sources.jar` 到 `build/review-src/neoforge-21.1.236/` |
| `javap` 用二进制 | `build\moddev\artifacts\neoforge-21.1.236-merged.jar` |
| 原版反编译 | `D:\Minecraft\闭源模组解压文件\minecraft-1.21.1` |

纪律：**锁定版本的第三方 API 要 `javap` 打实际 jar**，不要只读源码克隆。

⚠️ **同名不同物（会白跑好几轮）**：要读源码就用上表那份 `build/review-src/` —— 它含**完整的、已打补丁的原版源码**
（`net/minecraft/**` 有 5000+ 个 `.java`）。**Gradle 缓存里那份同名 `neoforge-<版本>-sources.jar` 只有
NeoForge 自己的类、没有 `net/minecraft`**，拿它去找 `LivingEntity` / `HurtByTargetGoal` 之类会一路 MISS。
⇒ 要核对"源码里到底怎么写的"，**先读源码树；`javap` 是最后手段**（只在需要确认**编译产物**里有没有某成员时才用）。

**平台行为 / 事件语义：读补丁，不要按名字推断。** NeoForge 的补丁源码在
`neoforge-<版本>-userdev.jar` 的 `patches/**.java.patch`（`build/` 被清掉后，从 Gradle 缓存的
`modules-2/.../neoforge-<版本>-userdev.jar` 取）。**事件类存在 ≠ 它会发**，两个实测案例：

- `MobEffectEvent.Expired` **只在** `LivingEntity.tickEffects`（自然到期）发；`MobEffectEvent.Remove`
  **只在 3 处**发：`removeAllEffects()` / `removeEffect(...)` / NeoForge 新增的
  `removeEffectsCuredBy(EffectCure)`（牛奶与死亡走它）。⇒ **自然到期不发 `Remove`**，想守住"到期"
  只有 `Expired` 一条路（代码注释里"`Remove` 覆盖自然过期"的说法是错的）。
- `AdvancementEarnEvent` 被 post 在 `PlayerAdvancements.award` 的 **`display().ifPresent(...)` lambda 内部**
  ⇒ **省略 `display` 的隐形进度永不触发它**。要覆盖隐形进度得用 `AdvancementProgressEvent`
  （在 `display` 之外发），并自筛 `ProgressType.GRANT` + `isDone()`。

**相关模组：**

- 开源参考（**只读**）：`D:\Minecraft\开源模组参考文件\`（本项目依赖的关键项：`DragonSurvival` ——
  **是 fork、不是上游**、`Cataclysm`、`Legendary-Monsters-1.21.1-NeoForge`、`MowziesMobs-Public`、
  `FDBosses-master`、`raidsenhanced`、`HealthBars-1.21.1`、`DragonSurvivalIAFIntegration`）。
- 闭源反编译：`D:\Minecraft\闭源模组解压文件\`。
- 读法：先 `rg` 定位再精读；结论**附来源路径**。
- 两个坑：① 源码树常常**只有 Java、没有资源**（贴图 / 音效 / `sounds.json` 要开实际 jar）；
  ② 参考版本**可能与应用版本不一致** ⇒ 关键结论按 `build.gradle` 的 curse `fileId` 对账。

## 四、Mixin 规范

- **分包按「目标」命名空间**：vanilla → `mixin/minecraft/`；第三方 → `mixin/<modid>/`；
  `beloong.mixins.json` 的条目带同前缀；**客户端专属** mixin 放进 `client` 数组。
- **必选目标（原版 + 硬依赖模组）**：注入 vanilla 时 `@Inject` / `@Redirect` 要写 `remap = false`
  （NeoForge 跑官方名，dev 命名空间 == 运行时命名空间）——漏写会让 annotation processor
  **构建期硬报错**（不是警告）。全局默认是 `injectors.defaultRequire = 1`。
  **`@At` 只在它自身带成员引用时（`INVOKE` / `FIELD` 的 `target=`）才同样需要 `remap = false`**；
  `@At("HEAD")` / `("TAIL")` / `("RETURN")` 之类不用（先例：`PossibleBiomesFilterMixin` 只写在 `@Inject` 上；
  `DreadKingRitualTriggerMixin` 的 `@At` 写了，因为它的 `target=` 指向 `unlock(...)`）。
- **注入点必须是目标类「自己声明」的方法/字段**。要影响一个**继承来的**方法，就注入**声明它的那个祖先**，
  再用 `instanceof` 把作用域收窄 —— `@Pseudo` 兜的是"类不存在"，**兜不住"方法不在这个类里"**。
  ⚠️ **用 `javap -p` 打实际 jar 的完整成员表确认"到底谁声明了它"**，别只凭源码里的继承链印象。
  先例：`Raider` 自己**没有**声明 `isAlliedTo`（继承自 `Entity`），所以同伴判定要注入 `Entity`
  再用 `instanceof Raider` 收窄；`Hunter` 同理（`LivingEntity`/`Mob`/`PathfinderMob` 都没覆写它）。
- **可选目标（可选模组 / 未在 `mods.toml` 声明的模组）**：**必须 `@Pseudo` + `require = 0`**，否则该模组
  缺席或改名 = **启动期硬崩**。注意 `@Pseudo` 只兜"类不存在"，**兜不住"方法改名"**。
- 命名：`*Mixin`；访问器 `*Accessor`。
- ⚠️ **现状有残留**（2026-10-11 实测）：`FSweepButtonControlMixin`（目标模组**不在** `mods.toml` 里，
  风险最高）、`NetMagnetItemMixin`、`mixin/betterendisland/` 下两个，尚未补 `@Pseudo` / `require = 0`。
  **改到这些文件时顺手修正**。
- **只认当前树**：至少 12 个 mixin 已被删除，历史路径与旧文档都不可信。

## 五、语言文件与配置翻译

- `assets/beloong/lang/zh_cn.json` 与 `en_us.json`：**键集必须一致**，新增键**中英同步**，并沿用文件既有的排序习惯。
- 占位符 `%s` 的个数必须与代码传入的参数个数一致；`§x` 颜色码两端要一致；字面 `%` 写成 `%%`
  —— 不一致 = **渲染期抛异常**，构建期发现不了。
- 门：`python tools\check_lang_parity.py`（逐键比对占位符与颜色码）。
- **NeoForge 配置翻译键**：新增配置节必须补 `beloong.configuration.*` 的 zh + en。键推导 =
  显式 `.translation()` > 节级翻译 > `modid.configuration.<最后一段>`；**语义通用的平铺键要显式加前缀**
  （先例 `dragonSummonBlock`）以避免跨节冲突；dev 下缺键会报 `Untranslated configuration keys`。
  这是"新增配置项"的一部分，不是可选的收尾润色。

## 六、负责范围（越界前先问）

- **本仓库只负责模组侧**。**整合包侧的文件一律不修改，除非用户明确授权。**
- **两条路径要分清，别混：**
  - **本机 dev 实测（可写）**：要复现**配置驱动**的功能就得改 `run\config\*.toml`（dev 运行目录；
    `run/` 是构建产物、不进版本管理）。日志读 `run\logs\latest.log`（INFO）与 `run\logs\debug.log`
    —— ⚠️ **只有 `debug.log` 含 DEBUG 行**；两者都是 **UTF-8，可直接读**。
  - **整合包实例（只读）**：`D:\BeLoong\.minecraft\versions\BeLoong`。那里的 `logs/` 是**实机验收的证据来源**，
    但 ⚠️ **日志是 GBK / CP936 编码**，要按 CP936 读；**不要改**它里面的 `config/beloong-server.toml`。
- 惯例：模组侧只提供**触发器 / 判据 / 配置项 / 数据驱动入口**，真实进度与最终配置由整合包侧编写。
- **收尾时把为测试改过的本地配置还原**（`run/config/*.toml`），别把测试态留在工作区。

## 七、类放置位置

- 见 §一 的包职责表。新类的默认去处：渲染 / HUD / **客户端专属处理** → `client/`；注册对象 → `registry/`；
  实体 → `entity/`；方块 / 物品 / 流体 → `block/` `item/` `fluid/`；DS 技能效果 → `ability/`；
  跨模组兼容 → `compat/`；命令 → `command/`；载荷 → `network/`；数据驱动系统 → 自己的包
  （**Codec + 服务端 reload listener**，在 `BeLoongCore` 注册）。
- **客户端专属类不得被公共类引用** —— 专用服务端会在常量池里撞见它，抛 `NoClassDefFoundError`，
  而它**发生在进入方法体之前**，外层 `try/catch` 捕获不到。要做"公共类里调客户端"，用
  `@OnlyIn(Dist.CLIENT)` 守卫的方法，或把入口放在客户端专属事件的注册处（先例：`client/` 下的 handler
  由 `BeLoongCoreClient` 注册）。反之，**双端都要用的注册（如属性）绝不能放在客户端类里**。

## 八、代码审查规范

- **本文件优先于 `memory/*`**：两边冲突时按本文件办，并顺手把 memory 那条改掉
  —— `memory/` 不进版本管理，**干净克隆只看得见本文件**。（先例：`memory/user-preferences.md` 曾写
  "审查编号用 `C-x` / `I-x` / `S-x`"，与下面的连续编号冲突，已按本节更正。）
- 落盘 `docs/reviews/YYYY-MM-DD-<slug>-*.md`。
- **用子代理做审查，但数量不要多**：**2–3 名**，按互不重叠的切片分工（如 服务端链路 / 客户端与同步 / 数据与门），
  每名给一份明确的"范围 + 要什么"说明书。**最终整合成一份文档**。
- 文档必须**简洁**，**按严重程度分节**（严重 / 重要 / 可选 / 已知取舍）。**问题编号用 `1. 2. 3. 4.` 连续编排：
  严重程度只决定它落在哪一节，不重新起编，也不加 `C-` / `I-` / `S-` 前缀。** 例如：

  ```
  ## 严重
  1. 兜底路径会把更强的标记降级 —— ExecuteMarkHandler.java:108
  3. 门抓不到它守护的那个回归 —— tools/validate_execute.py:316
  ## 重要
  2. 被挡下时只看 hurt() 的布尔 —— ExecuteThresholdEffect.java:192
  ```

- 每条只写三样：`文件:行号` + 问题（1–2 句）+ 修法（1 句）。
- **缺陷与「已知取舍」必须分开列**；报告要便于逐条勾选 ⇒ **先出报告、由用户逐条裁定，不要默认开修**。
- 派审查者时把「**已裁定、不许当缺陷报**」的清单交给他们，否则每轮都会重报同一批刻意设计。
- **审查者的结论必须自己复核**：与源码 / 实测不符时以复核为准，并在报告里注明更正了什么。
- 报告末尾要有「**未能验证**」一节，明确写出哪些结论没有取证。

## 九、文档与留痕

- 功能改动走 `docs/plans/YYYY-MM-DD-<slug>-design.md` + `-plan.md` **成对**；审查走 `docs/reviews/`；
  每个大系统另有 `docs/<系统>总设计.md`——**那是该系统的权威文档**。
- **每轮修订追加一节（rev 2/3/4…）并标记被取代的旧章节，不删改历史**；决定要留"谁 / 何时 / 为何 + 被否的替代方案"。
- **数值类改动的权威来源是 `src/main/resources/data/...` 的 JSON**；文档里的表要跟着更新，
  并写明口径（例如"打满 / 基础值 / 不含护甲"）。
- `memory/decisions-log.md` 是权威决定记录，**动你没设计过的子系统前先读**。
- **动作前先确认分支**：`memory/` 是 gitignored、**与本分支无关**，它可能描述的是兄弟分支的状态。

## 十、协作方式（用户偏好）

- **中文、极简**。给他「**可选项 + 推荐项 + 一句代价**」，他一句话就能定案；不要长段落复述需求。
- 他会明确说「**找出原因，不要修复**」——那时**只给机制与量化，一行代码都不要改**。
- **要机制，不要结论**：源码行号 + 数值推导 + "为什么表现为这个手感"。
- **实测口径优先于理论**：他一句"经过测试…"可以覆盖任何文档推断。
- 报告里认可"**推翻自己先前结论**"的诚实记录；详细偏好见 `memory/user-preferences.md`（若存在）。

## 十一、其它硬性约定

- **日志一律英文 ASCII**，格式 `[BeLoong] <subsystem>: <message>`，用 SLF4J `{}` 占位；
  **中文只进注释与 javadoc**。⚠️ 多行日志的**中文续行**最容易漏，要按整条语句扫，不能只看第一行。
- **复用第三方自己的量与判定**，不要复制算式、也不要造状态副本（项目历史两次踩坑都源于此）。
- **崩溃面优先**：`injectors.defaultRequire = 1` 下，注入点 / `@Shadow` / lambda 序号漂移 = **启动期硬崩**。
  改到原版或 DS 的私有面时，在设计文档里登记「**升级必查项**」。
- 数据包 tag 里引用**可选模组**的 id，必须写 `"required": false`，否则缺该模组的整合包**整个加载不了**。
  同源的三条：**同一路径**的 tag 文件按资源包优先级**覆盖**、**不同路径**的**并集**；
  `"replace": false` 不能随便省（原版 `minecraft:illager_friends` 的值就是 `{"values":["#minecraft:illager"]}`，
  写成 `true` 会把 `#illager` 一起清空）；引用原版已有的聚合 tag 前**先开实际 jar 看它的内容**
  （`#minecraft:raiders` 与 `#minecraft:illager` 名字像，成员并不相同）。
- **"谁把谁当同伴"的唯一收口点是 `Entity#isAlliedTo`**：`TargetingConditions#test`（主动锁定）与
  `HurtByTargetGoal#canUse`（被打后还手）**两处都走它** ⇒ 改这一处就等于同时改"选不选它当目标"与
  "要不要还手"，比逐个 goal / 逐个 tag 去堵可靠得多。
- **取消 / 拦截"移除类"事件时，判据必须按对象**，绝不能按"本次有没有处理过任何东西"。
  反例：`if (checkAndApply(player)) event.setCanceled(true);` —— 全局标志会被**别的对象**污染，
  当前对象就被无限取消；MobEffect 的表现是 **duration = 0 却永不消失、且属性修饰符仍在**，
  并且**每 tick 自我触发**形成自持循环（未门禁效果每 tick 递减 1 ⇒ "补满"总返回 `true`）。
  正确写法：让被调方法返回"处理了哪些对象"（`Set<Key>`），判定用 `contains(thisKey)`；
  而**主动移除**路径的判据该问"这个对象**该不该在**"（两条判据不同，别合并）。
- 拉第三方依赖优先用 **curse maven 的 `fileId`**（`<slug>-<projectId>:<fileId>`）；Modrinth 按版本号解析
  **会选错 loader**。
- `tools/` 与 `memory/` **刻意不进版本管理**：**不要 `git add -f`，也不要为它反加白名单**。
  代价是"门全绿"只在工作区可复现——所以交付时要连命令一起给。
- **提交由用户做，智能体不 commit**；他的提交信息很短，**不要替他规范化**。

## 十二、明确不写进本文件的东西

- **会漂移的事实**：commit hash、当前分支、文件数、语言键数、除加载器版本外的版本号——
  需要时**现场取**（`git log` / `gradle.properties` / `git ls-files`）。
- 与工作区级 `..\AGENTS.md` 重复的内容（10 个项目清单、沙箱边界、PowerShell 策略）：**只给指针**。
- 大段架构叙述：那是 `memory/project-context.md` 与 `docs/*总设计.md` 的活。
