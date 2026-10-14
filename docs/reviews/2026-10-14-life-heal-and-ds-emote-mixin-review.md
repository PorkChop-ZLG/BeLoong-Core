# 灵愈技能 + 龙之生存表情 Mixin 修复 审查报告（2026-10-14）

- **审查对象**：PR #20「灵愈」（merge `af41f9f`）、PR #21「修复 DS 表情键位导致的客户端断连」（merge `51a0d2c`）
- **范围**：这两个 PR 的改动；当前 `master`，工作树干净
- **方法**：**2 名子代理分片独立审查**（各带范围说明书）→ 主审**逐条复核**并取证（模组源码 + **DS fork** 源码 + NeoForge 补丁源码 + 实跑构建与语言门）；**每条结论附 `文件:行号` 或实跑证据**
- **前提**：委托方要求「简单的检查，主要看是否符合项目约定」⇒ 只看**约定偏离**与顺手发现的缺陷，**不做数值平衡评估**；**只出报告，不开修**
- **规范基线**：`AGENTS.md`（项目级，权威）+ `docs/自制龙之生存技能总设计.md`

## 严重

1. **合并后没跑过 `runClient`，Mixin 的「应用期」完全未验证（取证缺口，非已证缺陷）** —— `beloong.mixins.json:7`
   项目唯一一次客户端运行（10-14 21:57 起）打的是 `Preparing beloong.mixins.json (54)`，而现树是 **55**（client 7 + mixins 48）
   ⇒ 那次运行**不含该条目**。主审已重跑构建，`build/classes/.../DragonEmoteScreenMixin.class` 与 22:37 的 `beloong-0.10.5.jar` 现在都有了，
   但那只是**编译期**证据；§二/§四 要求的是**应用期**验证（处理器签名、注入点、`@Shadow` 漂移只在启动时抛，本项目已复发三次）。
   **修法**：跑一次 `runClient`，在 `run/logs/debug.log` 确认 `Mixing dragonsurvival.DragonEmoteScreenMixin … into …DragonEmoteScreen`。

## 重要

2. **权威文档未同步，且已落后两个技能** —— `docs/自制龙之生存技能总设计.md:3,9,74-80`
   该文档写「3 个龙技能」「本文覆盖的三个技能」，§3.2 的注册表只列 **3** 个键；而 `AbilityEffectRegistry` 实际注册 **5** 个
   （`tp_loong_palace`/`air_strike`/`tornado`/`execute`/`life_heal`）⇒ **上次「斩杀」就没补**，本 PR 又加了第 5 个；
   且 `life_heal` 在 `docs/plans/` 里零留痕（§九 要求 design + plan 成对）。
   **修法**：按 §九 在总设计追加「修订 rev N」节登记灵愈、更新 §3.2 注册表，并补 `docs/plans/2026-10-14-life-heal-{design,plan}.md`
   （顺带按 §十一 把 `DragonEmoteScreenMixin:76` 的"复制 DS 解析式"登记成**升级必查项**）。

3. **0 级专属（黑白）图标缺失** —— `life_heal.json:106-109`（目录下只有 `life_heal_1.png`）
   规范原文：`自制龙之生存技能总设计.md:46`「图标张数 … **2（0 级、1–6 级共用）**」、`:332`「`tornado_0`（**黑白，用于 0 级**）」；
   其余四个技能都有 `_0`，而灵愈 7 条 `texture_entries` **全指向 `life_heal_1`** ⇒ 未学习（0 级）与已学习同图。
   **修法**：补一张灰度 `life_heal_0.png`（可沿用 `tools/make_execute_icons.py` 的派生方式），把 `from_level 0` 指过去。
   （**尺寸合规** ✓：`life_heal_1.png` 实测 32×32；`execute_1.png` 的 64×64 是已授权的例外。）

## 可选

4. **新增语言键插错位置（破坏组内字母序）** —— `zh_cn.json:265-267`、`en_us.json:265-267`
   `dragon_ability.beloong.*` 组是按字母序排的（`air_strike` → `execute` → `tornado` → `tp_loong_palace`），
   新增的 `life_heal.*` 被追加在末尾 ⇒ 应在 `execute.dynamic_desc`（`:259`）之后。
   **修法**：移到正确位置（两个语言文件同步）。`check_lang_parity.py` 只比键集/占位符，**抓不到**这个。

5. **打断判据与注释口径不符（已取证：事件发生在减伤之前）** —— `LifeHealInterruptHandler.java:44-51`
   NeoForge 自己文档化的伤害序列（`DamageContainer.java:24-37`）：`hurt` → `isInvulnerableTo` → **`LivingIncomingDamageEvent`** →
   `LivingShieldBlockEvent` → `actuallyHurt` → **armor / magic / mob_effect 减免** → `LivingDamageEvent.Pre` → **absorption 减免**；
   而"受击后无敌窗"只是在 `:1190-1196` 记进容器的 `Reduction.INVULNERABILITY`，**并不取消事件**
   ⇒ 默认优先级下 `event.getAmount()` 是**减伤前**的值，被护甲/吸收完全挡下的攻击**仍会**打断引导。
   **修法**：把判据挪到 `EventPriority.LOWEST`（并写明理由），或只把注释改成"本次事件未被取消且原始伤害 > 0"。

6. **javadoc 的「只有三条停止路径」不完备** —— `LifeHealInterruptHandler.java:19`
   DS fork 的 `DragonAbilityInstance.java:103-108` 还有第 4 条：`usage_blocked`/禁魔 ⇒ `setDisabled(true)` + `stopCasting(dragon,false)`
   （另有 `:184-191` max duration、`:201`、`:214`）。**修法**：补记第 4 条，或改口径为"主要三条"。

7. **`activation.sound.start` 很可能不发声（待实机）** —— `life_heal.json:100`
   项目实测过：DS 的 `sound.start` 由 `DragonAbilityInstance#tickActions` 在 `currentTick == castTime` 这一拍播放，且服务端那支按原版约定
   **排除施法者本人** ⇒ 龙卷风那次**完全不发声**（`docs/plans/2026-10-11-tornado-sound-and-numbers-design.md` §3.1）。
   灵愈用同一字段，**是否同样无声未实机确认**（激活类型不同，不能直接套用）。**修法**：实机听一次；若无声就删 `start`。

8. **日志缺 `<subsystem>:` 段，且每次按键 WARN 一次** —— `DragonEmoteScreenMixin.java:85`
   §十一 规定 `[BeLoong] <subsystem>: <message>`，同项目先例是 `[BeLoong] darkdoppelganger: …`；本条写作 `[BeLoong] emote '{}' …` ✗。
   **修法**：改为 `[BeLoong] dragonsurvival-emote: …`，并降为 `debug` 或一次性。

9. **`:71` 的防御分支放行原方法体，堵不住它自己镜像的那个空指针** —— `DragonEmoteScreenMixin.java:71`
   `handler == null || handler.body() == null` 时 `return`（放行 DS 原方法），而原体 `DragonEmoteScreen.java:233-234` 对 `handler.body()`
   **没有任何判空**（子代理 javap 确认该处字节码无 `ifnull`；主审在 fork 源码上复核了 `:233-234`）⇒ 该分支只是把同一处 NPE 让回给 DS。
   **修法**：该分支也走"停表情 + `ci.cancel()`"（至少同样 `ci.cancel()`）。

## 已知取舍（不是缺陷）

- **PR #21 的语义选择**：键位解析为 `null` 时不停留在"无操作"，而是**停掉全部表情**（`DragonEmoteScreenMixin.java:80-83`），javadoc 已写明理由。
- **`@Mixin(..., remap = false)` 不是偏离**：全仓 55 个 `@Mixin` 里 **32 个**这么写，同目录 DS mixin 全部如此（`ClientFlightHandlerMixin:86` 等）。
- **`sound.looping` 用在 `channeled` 上合法**：只有 `SimpleActivation` 的 codec 会拒绝该字段（`life_heal.json:67,101`）。
- **满血仍扣魔力**（`LifeHealEffect.java:80-83` 明写刻意保留）；**打断冷却 = 技能常规冷却**（`LifeHealInterruptHandler.java:36-41` 说明原版只有一个冷却字段）。

## 通过项（合规面记录）

- **PR #21**：分包 `mixin/dragonsurvival/` ✓；条目 `dragonsurvival.DragonEmoteScreenMixin` 前缀正确且进了 **`client` 数组** ✓；
  **处理器签名与目标完全对齐**（javap 实测 `DragonEmoteScreen.addEmote` 是 `public static void (String)` 且由该类自己声明 ⇒
  `private static void …(String, CallbackInfo)` + `cancellable=true` 正确）✓；解析路径与原方法**逐字同源**（`:76` vs DS `:234`）✓；
  DS 在 `mods.toml:129-131` 是 `required` ⇒ 无需 `@Pseudo`/`require=0` ✓；崩溃面核对：`getEmote` 确为 `orElse(null)`、
  `beginPlayingEmote(null)` 提前返回 ⇒ 崩点确在 `SyncEmote` 编码，且 `:82-83` 与 `EmoteHandler` 的"停全部"通道同源 ✓。
- **PR #20**：三个新类都在 `ability/` 且**无任何客户端专属类引用**（§七 硬性禁令）✓；注册与 `ClawSwordAdvancementHandler` 先例同款 ✓；
  中英键集 **331/331** ✓、三个新键的 `%s` 个数与代码实参一致、色码序列逐键一致 ✓；新代码零日志 ⇒ §十一 不适用 ✓；
  `isLifeHeal(null)` 有判空 ✓；`getDescription` 对 `lookup` 做了 **level-0 clamp**（`LifeHealEffect.java:104`）✓ ⇒ 无崩溃面 ✓。
- **构建/门**：`gradlew build --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL**，**3 个警告 = 基线**（两条已知 `@Shadow`/`@Accessor`）
  ⇒ 两个 PR **都没引入新警告** ✓；`check_lang_parity.py`（设 `PYTHONIOENCODING=utf-8`）→ ✅ 完整通过 ✓；产物 `beloong-0.10.5.jar`。

## 未能验证

- **未实机**：灵愈的引导/回血/打断/冷却手感与 `sound.start`/`looping` 对施法者本人是否发声；PR #21 的 mixin 是否真的挂上（见第 1 条）。
- **数值平衡**未评估（超出范围）。
- 子代理① 的 javap 对象是 `dragons-survival-420799-8973485.jar`（`version = 2.0.71`，与 `build.gradle:147` 坐标一致）；
  **未核整合包/实机侧**的 DS 版本是否同为 2.0.71。
- 语言门的**全量**占位符/色码比对：子代理那台因 GBK 控制台在末行 emoji 处抛 `UnicodeEncodeError` 而中断 ✗；
  **主审设 `PYTHONIOENCODING=utf-8` 重跑后完整通过**，此项已补齐。
