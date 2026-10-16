# 元素魔源封存技能 —— 代码审查（2026-10-16）

**范围**：本会话新增的「元素魔源封存技能」的全部实现 —— `EssenceItem`、`EssenceLearnRitual`、`EssenceCommand`、
`ModDataComponents`、`ModItems`（10 件注册）、`ModItemTags.ESSENCES`、`ModSounds.ESSENCE_LEARN`、
`BeLoongCore` 的接入，以及资源侧（tag JSON / `sounds.json` / 音效 ogg / 中英语言键）。

**方法**（按 `AGENTS.md` §八）：派 **3 名子代理**按互不重叠的切片审查
（① 服务端交互链路 ② 粒子编排 ③ 注册·数据·门），外加主审查者的独立自审。
子代理被明确要求**只读、不改代码、不跑构建**；构建/门/产物/日志由主审查者统一取证。
⚠️ **三名子代理的结论都已由主审查者回到源码逐条复核**，**两处与源码/设计不符已在条目里更正**（见 4、15）。

**状态**：功能已由作者实机验收通过（"功能已经完成，实机测试已经通过，观感符合要求"）⇒
本审查**只出报告、不开修**，条目请逐条裁定。

> ✅ **2026-10-16 同日裁定与修复**（作者：「3 个重要的都要修，可选里只修 5 6 8 10，
> 再把相关文档、注释都更新修正一下」）：
> **已修**：重要 1、2、3 + 可选 5、6、8、10；**并顺带修正可选 7、11**（它们是"错误的数字/注释"，
> 按"相关注释一并修正"处理）。修复记录见设计文档 **§20 修订 rev 8**。
> **未修**：可选 4（机制成立，但修法与 **D15** 冲突 ⇒ 登记为已知开销）、
> 可选 9（待确认稀有度是否有意保留）、可选 12（空目录，先确认是否属刻意预留）、可选 13（子代理自判"无实测收益"）。

**日志复核**（`run/logs`，2026-10-16 会话）：
- `latest.log` 363 条 `ERROR/FATAL`（`[线程/LEVEL]` 口径）**全部来自第三方/原版**：
  FTB Chunks 148 + FTB Library 148（图标缺失噪音）、`RecipeManager` 26、`SoundManager` 18、GeckoLib 15、
  `LootDataType` 4、`Util` 3、NeoForge `ServerLifecycleHooks` 1 ⇒ **来自 `beloong` 的 0 条**。
- `EssenceItem` / `EssenceLearnRitual` / `EssenceCommand` / `essence` 关键字**零命中**；
  `run/crash-reports/` 最新仍是 2026-10-14（早于本功能）⇒ **无新崩溃报告**。
- 本次会话 `debug.log` 确证 `EssenceLearnRitual` 已挂上游戏事件总线。

---

## 严重

（无）

---

## 重要

1. **爆发球壳只覆盖约 74% 的球面，且外层壳整个落在上半球** —— `EssenceLearnRitual.java:509-510`
   `index = i + shell * BURST_SHELL_POINTS * 0.5D` 让两层索引区间 [0,56) 与 [28,84) **互相重叠**，
   而 `y = 1.0D - 2.0D * index / totalPoints` 又用 `totalPoints = 112` 归一 ⇒ 实际只用掉 84/112 个索引：
   **外层壳只落在上半球**（y = +0.07R ~ +R），**y < −0.482R 的下极帽永不采样**（25.9% 球面），
   而 y ∈ [0.018, 0.5] 反而被双倍覆盖（48%）。
   证据：点位分布验算 + 渲染 `docs/pictures/preview/burst_shells.png`（蓝=外层只在上半，灰圈=完整球轮廓）。
   地面使用时下半球多半埋在脚下所以看不出来；**在空中/水上**会明显看到"穹顶"。
   修法：`y` 改用**每层自己的** `SHELL_POINTS` 归一（`y = 1.0D - 2.0D * (i + 0.5D) / BURST_SHELL_POINTS`），
   相位偏移只作用于 `theta`（已验算：缺失 25.9% → 约 1%，两层仍因 `BURST_SHELL_GAP` 分成内外两层）。
   > 设计文档 §7 写的是"两层壳错开**半径与相位**"；实现把"相位"错写成了 **y 索引的偏移**，这是根因。

2. **3×/4× 体型时球壳超出 `sendParticles` 的 32 格投递门 ⇒ 整层静默丢包** —— `EssenceLearnRitual.java:495`
   `ServerLevel.java:1295-1302` 的投递门是 `player.blockPosition().closerToCenterThan(point, longDistance ? 512 : 32)`，
   而公开重载 `ServerLevel.java:1268` **恒传 `false`**（= 32 格）。
   球壳半径 `(0.35 + 0.45·age) × F`：**4× 时最大 57.2 格**，`age ≥ 18`（tick 42–55，共 **14/32 tick**）
   内外两壳都 > 32 ⇒ 对站在球心的施法者**一格都不发**；**3× 时 8 tick**；2× 最大 28.6 格不受影响。
   修法：把爆发半径钳到 ~30 格以内，或自建包走 per-player 的 `longDistance=true` 重载（`ServerLevel.java:1276-1293`）。

3. **设计文档 §6.3 的音效事件 ID 少一段（照抄会 `/playsound` 失败）** —— `…-design.md:312`
   文档写 `beloong:item.essence_learn`，实际注册名是 **`beloong:item.beloong.essence_learn`**
   （`ModSounds.java:96-97` 的 `register("item.beloong.essence_learn")` = 命名空间 `beloong` + 该路径；
   子代理另用 `javap -p -c` 打 jar 常量池复核了同一字符串）。同文档 §7 的 `sounds.json` 键才是对的。
   修法：该格改成 `beloong:item.beloong.essence_learn`。

---

## 可选

4. **"潜行 + 对着方块"时客户端同 tick 发两个交互包** —— `EssenceItem.java:195` + `:217`
   `MultiPlayerGameMode.useItemOn:300-312` 的 `startPrediction` lambda **无条件**发 `ServerboundUseItemOnPacket`；
   `performUseItemOn:333` 潜行时跳过方块交互 ⇒ `:361/364` 调 `itemstack.useOn(...)` ⇒ 客户端返回 PASS ⇒ `:367` PASS ⇒
   `Minecraft.startUseItem:1757-1770` 既不 `consumesAction` 也不是 `FAIL` ⇒ **下落到 `:1778` 再发
   `ServerboundUseItemPacket`**。服务端先走 UseItemOn（授予 + 移除组件），第二个包在 `:227` 的
   `abilityId == null` 处 PASS ⇒ **功能正确、不会重复授予**，代价是每 tick 一个被丢弃的包。
   **修法：不改。** ⚠️ **更正子代理的建议**（它主张"客户端潜行时返回 `SUCCESS` 以短路回落"）：
   那会让客户端**先播放动作**，正是 **D15** 要避免的观感问题（`docs/自定义物品总设计.md` §9.2 记过该症状）
   ⇒ 建议接受该开销，并在设计文档登记为已知项。

5. **对着实体潜行右键不会习得** —— `EssenceItem.java:195`（缺 `interactLivingEntity` 覆写）
   `Player.interactOn:1109-1114`：命中 LivingEntity 时走物品的 `interactLivingEntity`，默认 PASS 后
   **不回落到 `use`** ⇒ 潜行对着生物右键什么都不发生。修法：覆写 `interactLivingEntity` 委托给 `tryLearn`（约 4 行）。

6. **密度衰减是"按前缀截断"（蚀退）而不是整体变稀** —— `EssenceLearnRitual.java:448,462`
   `ringDrawn = ceil(74 × density)` 从 `i = 0` 起算 ⇒ 收尾时**只剩起笔那一段被重画**、笔迹从收笔处向起笔处蚀退；
   tick 47（`points = 28`）起连下半球也不再发点（**51% 球面**）。
   修法：改成按步长抽样（每 k 个取 1）保留均匀分布的存活点。⚠️ 这是**已实机认可**的观感，改前请先确认。

7. **类 javadoc 的"约 4.1 s 散尽"与 dust 寿命不符** —— `EssenceLearnRitual.java:61,130`
   末批粒子发射于 tick 71（密度在 tick 72 归零），而 dust 寿命 `max(8/(0.2..1.0)×scale, 1)` = **[8,40] tick**
   （`DustParticleBase.java:32-33`）⇒ 最迟 tick 111 ≈ **5.55 s** 才清空。修法：该数字改成"最多再留约 2 秒（≈5.6 s）"。

8. **`ACTIVE` 静态列表在"服务器停在世界里"时不清理** —— `EssenceLearnRitual.java:228`
   陈旧 `Ritual` 会持有 `ServerPlayer` 引用，直到下一次服务器 tick 靠 `advance()` 的 `player.isRemoved()` 回收
   （⇒ 有界、自愈、不崩）。子代理另核了⑦：退出/重生/换维度都走 `PlayerList.remove`→`removePlayerImmediately`
   ⇒ 这三种情况**没有**残留。修法：在 `ServerStoppedEvent` 里 `ACTIVE.clear()`（防御性）。

9. **稀有度 `UNCOMMON` 的可视作用已被覆盖** —— `EssenceItem.java:170`
   名字颜色已由 `getName`（`:374`）用元素硬编码色给出，而 `Rarity` 在 1.21.1 的可见效果就是名字颜色
   ⇒ 现在只剩"非 `COMMON`"这个事实（JEI/其它模组可能仍读它）。不是缺陷，请确认是否有意保留。

10. **计划文档里的枚举与实现不符** —— `…-plan.md:91`（同源 `:93`、`:110`）
    计划写顶层 `enum EssenceElement { METAL("metal_essence", 0xF4F380, …) }`（带物品 id），
    实际是**嵌套** `EssenceItem.Element` 且**只带颜色**（tooltip 键由 `ModItems` 逐件传入，`EssenceItem.java:94-121`）。
    修法：就地标注实际名与"只带颜色"，或按 §九 在「计划之外真实发生的事」补一条偏离记录。

11. **`ModSounds` 类注释的键名规则段数不准** —— `ModSounds.java:18`
    注释写 `<类型>.<命名空间>.<方块 ID>.<用途>`（四段），而新增的 `item.beloong.essence_learn` 与既有的
    `ability.beloong.execute` 都只有三段。修法：写成 `<类型>.<命名空间>.<ID>[.<用途>]`。

12. **jar 里有空目录条目** —— `data/beloong/dragon_ability/`（源码同名目录也为空、`git ls-files` 0 命中；
    技能定义实际在 `data/beloong/dragonsurvival/dragon_ability/`）。**非本功能引入**，但随 jar 发布。
    修法：删掉该空目录。⚠️ `AGENTS.md` §四 记过 `mixin/bomd/` 空目录是**刻意**的 ⇒ 请先确认是否同类预留。

13. **`direction()` 每次重算 yaw 基底（循环不变量）** —— `EssenceLearnRitual.java:468-479,318-329`
    默认体型约 304 次调用/tick（≈1520 次三角），4× 约 5900 次；子代理实测其量级**仅 tick 预算的 0.1–0.4%，
    无实测收益**。修法（可选）：yaw 基底移入构造器、弦两端提到段外层。**建议不做。**

---

## 已知取舍（不是缺陷，登记备查）

14. **名称译键缺失时显示原始键名** —— `EssenceItem.java:423` 的 `abilityName` 走
    `Component.translatable("dragon_ability.<ns>.<path>")`；第三方 DS 技能若没提供该键，
    `TranslatableContents` 的标准回退会把键名当字面文本显示。作者刻意"不自己拼字符串"，故保留。

15. **计划文档 §T10 的产物快照数字与当前同名 jar 不同** —— `…-plan.md:428` 记
    `beloong-0.10.6.jar`（2026-10-15 22:15:43，5,290,603 字节，631 条目），当前同名 jar 是 5,296,955 字节 / **633** 条目
    （多出 2 条 = `EssenceLearnRitual.class` + `$Ritual.class`）。
    ⚠️ **更正**：子代理把它列为「可选」，但该行**自带时间戳**、是明确标注的**当轮快照**，对那一刻数字正确
    ⇒ 主审查**降级为已知取舍**（不必改；若想更稳可加一句"当轮快照、后续轮次已重打包"）。

16. 以下均已登记、不再重复报：**创造模式不消耗**（D30）、**背包满时掉脚下**（U18，`Inventory.add` 返回 false 的分支可达）、
    **每颗粒子单独 `sendParticles(count=1)`**（D26，约 4100 包/秒，已评估可接受）、
    **多玩家同时使用未实测**（U25）、**`ESSENCES` tag 仅声明不参与判定**（有意为之；已核全仓库 0 处代码读它）。

---

## 独立复核为实的证据（子代理②③，避免误报）

- **一笔勾勒闭合**：φ 序列 0→144→288→432→576→720，`gcd(144,360)=72` ⇒ 5 个顶点各命中一次；
  `t = i/(n-1)` 使末点落回起点 ✓。
- **`direction()` 的坐标系**与 `Vec3.directionFromRotation` 一致（`F = (-sin yaw, cos yaw)`，
  右向量 `F × up = (-F.z, F.x)`），φ 增大 = 俯视顺时针 ✓。
- **无除零 / 无粒子爆炸**：`pointsPerSegment - 1 ≥ 3`；`Math.max(8,…)`/`Math.max(4,…)` 在 `sizeFactor ∈ [1,4]`
  下恒不生效（实际 74/23 起步，4× 为 294/89）✓。
- **`sizeFactor` 不可能 NaN**：`RangedAttribute.java:46-47` 对 NaN 返回 `minValue`、`Mth.java:147-149` 只钳两端 ✓。
- **`new double[2]` 每 tick 仅 1 次**（`:449`），不是每点 ✓。
- **dust 无重力且速度全 0**（`ClientPacketListener.java:2255-2260`）⇒ 点位静止，脱手法阵不漂移 ✓。
- **单机暂停时仪式与音频一起冻住**：`IntegratedServer.java:106-113` 不调 `super.tickServer`
  ⇒ `ServerTickEvent.Post` 不发 ✓（不会空转）。
- **音效参数逐值成立**：单声道 / 44.1 kHz / Vorbis / 3.019887 s / 27,706 B / 峰值 −1.4 dB、均值 −19.8 dB（D19 ✓）。
- **件数不变量 19/19/19**；语言 **344/344**；`check_lang_parity.py` exit 0、`verify_essence_lang.py` ALL CHECKS PASSED。
- **tag** `replace:false`、10 项、顺序与 `ModItems` 逐项一致；**jar 内** `lang×2` / `sounds.json` / `essences.json`
  与源码 **SHA256 相同**；`sounds.json` 6 条目与 `ModSounds` 6 个注册一一对应、5 个 subtitle 键两种语言都在。
- **探针残留扫描**仅命中已知误报（`TemplateUpdate` / `TemplateVersionStore` / `LockdownTemplateMigration`）。

---

## 未能验证

- **未跑 `runClient`**：本项目行为正确性以"作者实机验收"为准（已通过）。本轮全部结论是**源码级 + 数值级**
  （判据为 NeoForge 21.1.236 源码行号、点位验算、渲染图、产物解析）；**警告数=基线 3 这条本轮没重新取证**
  （子代理按分工不跑构建，主审查者读的是上一次构建结果）。
- **重要 1 / 2 的实机观感未验**：作者验收是在**默认体型 + 地面**下过的；空中/水上（1）与 3×/4×（2）都只是验算。
- **整合包里是否真有玩家到 3×/4×**（`SCALE` 是否被别的模组改动，U15）未核对。
- **副手路径完全未验**：两个入口都接受任意手，但验收清单只测了主手，命令也只认主手 `hand`。
- **`ACTIVE` 跨"退出到标题 → 进新世界"的运行时行为**只有调用链证据，未实测。
- **专用服务端**：`Language` 无 `@OnlyIn`（服务端调用安全）已核，但专用服务端上的键表内容未取证（U11）。
- **星在屏幕上的感知笔顺**：验证的是 φ 序列与坐标映射（数学上为 上→右下→左上→右上→左下），
  未验证俯视视角下的左右手性感知。
- **音效转码过程**（"裁掉开头 0.187 s 低于 −45 dB 的真实静音"）未与源 mp4 比对，只验成品参数。
