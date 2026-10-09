# 冥界骑士「太阳破防」实施计划

**目标：** 让 Mowzie's Mobs 的「太阳祝福」三招（太阳耀斑 / 太阳射线 / 太阳打击）能直接伤害并击穿《首领崛起》冥界骑士的护盾（每次命中扣 1 层免疫层数），并以`beloong`标签作为可扩展的破防入口。
**架构：** 数据层（3 个 `mowziesmobs:` 伤害类型 + `beloong:underworld_knight_guard_break` 标签 + 原版标签补回 + 死亡信息）→ 转换层（Mowzie 侧 3 处 `@ModifyArg` 换伤害源，保留 `on_fire`）→ 判定层（骑士侧单点 `hurt` HEAD 注入：穿透 + 返回 true 才扣 1 层）→ 观测层（英文 ASCII 锚点 + COMMON 开关）。
**选定方式：** Approach 1（自底向上分层 + 内嵌端到端里程碑 M1）
**设计文档：** [2026-10-09-underworld-knight-solar-guard-break-design.md](2026-10-09-underworld-knight-solar-guard-break-design.md)
**调研文档：** [首领崛起-冥界骑士-调研.md](../首领崛起-冥界骑士-调研.md)

> **本项目现实（TDD 步骤的替代）**：本仓库**没有测试框架**。因此每个任务的"RED→GREEN"用
> **① `gradlew build`（含 Mixin AP 目标校验）② 静态探针（`javap` / `mixin.debug.export` 导出后核对）③ 数据文件语法与内容比对** 替代；
> **实机验收（T1–T11）由用户在整合包侧手动执行**（已确认）。每个任务完成后**单独提交**（用户已授权）。

---

## 任务清单（按依赖排序）

### T0：提交设计/调研/记忆三类文档
**Files：** 已存在，无需新增
- `docs/plans/2026-10-09-underworld-knight-solar-guard-break-design.md`
- `docs/首领崛起-冥界骑士-调研.md`
- `memory/decisions-log.md`、`memory/learned-patterns.md`

**Steps：**
1. `git status --short` 核对只有上述 4 个文件（＋本计划文档）
2. 提交

**Verification：** `git log --oneline -1` 显示 `docs: 冥界骑士太阳破防设计与调研`；`git status` 干净

---

### T1：三个太阳伤害类型（数据）
**Files（新建）：**
- `src/main/resources/data/mowziesmobs/damage_type/solar_flare.json`
- `src/main/resources/data/mowziesmobs/damage_type/solar_beam.json`
- `src/main/resources/data/mowziesmobs/damage_type/sun_strike.json`

**内容（5 字段显式写全；数值照抄原版 `player_attack`/`mob_projectile`）：**
`message_id` = `mowziesmobs.solar_flare` / `mowziesmobs.solar_beam` / `mowziesmobs.sun_strike`；
`exhaustion` = 0.1；`scaling` = `when_caused_by_living_non_player`；`effects` = `hurt`；`death_message_type` = `default`

**Steps：** 写 3 个 json → 语法校验 → 提交
**Verification：**
```powershell
Get-ChildItem src\main\resources\data\mowziesmobs\damage_type\*.json | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json | Out-Null; 'ok ' + $_.Name }
.\gradlew.bat build --offline   # 期望 BUILD SUCCESSFUL
```
**Commit：** `feat(mowziesmobs): 新增太阳耀斑/射线/打击三种伤害类型`

---

### T2：语义标签 + 原版标签补回
**Files（新建）：**
- `src/main/resources/data/beloong/tags/damage_type/underworld_knight_guard_break.json` → `{"values":["mowziesmobs:solar_flare","mowziesmobs:solar_beam","mowziesmobs:sun_strike"]}`
- `src/main/resources/data/minecraft/tags/damage_type/is_player_attack.json` → `{"values":["minecraft:player_attack","mowziesmobs:solar_flare"]}`
- `src/main/resources/data/minecraft/tags/damage_type/is_projectile.json` → `{"values":[…原版 12 项…,"mowziesmobs:solar_beam","mowziesmobs:sun_strike"]}`（**先读出原版内容再追加**，不要手抄漏项）

**Steps：**
1. `jar xf`/`ZipFile` 读出原版 `data/minecraft/tags/damage_type/is_projectile.json` 的完整 values
2. 写 3 个 json → 校验 → 提交
**Verification：** 三个文件 `ConvertFrom-Json` 通过；`is_projectile` 的 values 数 = 原版数 + 2；`beloong` 标签三项与 T1 文件名一一对应
**Commit：** `feat(beloong): 新增破防伤害类型标签并补回原版玩家攻击/弹射物标签`

---

### T3：死亡信息翻译（中英）
**Files（修改）：** `src/main/resources/assets/beloong/lang/zh_cn.json`、`en_us.json`
**内容（中文不带空格）：** `death.attack.mowziesmobs.solar_flare` / `.solar_beam` / `.sun_strike` 各 2 条
**Steps：** 追加 6 条 → 校验 → 提交
**Verification：** 中英各新增 3 键、键集合一致；`ConvertFrom-Json` 通过；键名 = `death.attack.` + T1 的 `message_id`
**Commit：** `feat(beloong): 太阳三招的死亡信息（中英）`

---

### T4：伤害类型常量与转换工具
**Files（新建）：**
- `src/main/java/com/zonlong/beloong/compat/mowziesmobs/SolarDamageTypes.java`（3 个 `ResourceKey<DamageType>` + `convert(DamageSource, ResourceKey)`，`on_fire` 直通、`holderOrThrow` 异常降级 + 节流 WARN）
- `src/main/java/com/zonlong/beloong/registry/ModDamageTypeTags.java`（`UNDERWORLD_KNIGHT_GUARD_BREAK`）

**Steps：** 写两个类（照 `ability/AirStrikeEffect.java:40-42,124,128` 的造源范式）→ 编译 → `javap` 核对 → 提交
**Verification：**
```powershell
.\gradlew.bat compileJava --offline
& "$env:JAVA_HOME\bin\javap.exe" -p -cp build\classes\java\main com.zonlong.beloong.compat.mowziesmobs.SolarDamageTypes
```
**Commit：** `feat(beloong): 太阳伤害类型常量与伤害源转换工具`

---

### T5：耀斑转换 Mixin
**Files（新建/修改）：**
- `src/main/java/com/zonlong/beloong/mixin/mowziesmobs/SolarFlareAbilitySolarDamageMixin.java`
- `src/main/resources/beloong.mixins.json`（注册）

**目标（已用字节码核实）：** `protected void beginSection(Lcom/bobmowzie/mowziesmobs/server/ability/AbilitySection;)V`
**注入：** `@ModifyArg` on `Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z` **index 0**（bytecode 131），`remap = false`，`require = 1`（Mowzie 为必选依赖）
**Steps：** 写 mixin + 注册 → 构建（AP 校验目标）→ `javap -c` 核对原类方法确实存在 → 提交
**Verification：** `gradlew build --offline` 成功且**无新增警告**；`javap -c -p -cp <mowzie jar> …SolarFlareAbility | findstr hurt` 能看到 index 0 目标调用
**Commit：** `feat(mowziesmobs): 太阳耀斑改用 beloong 太阳伤害类型`

---

### T6：骑士侧判定 Mixin + 配置开关
**Files（新建/修改）：**
- `src/main/java/com/zonlong/beloong/mixin/legendarymonsters/UnderworldKnightGuardBreakMixin.java`
- `src/main/resources/beloong.mixins.json`（注册）
- `src/main/java/com/zonlong/beloong/Config.java`（新增 COMMON 段 `[solar_guard_break] enabled = true`，含 `.translation(...)`）
- `src/main/resources/assets/beloong/lang/{zh_cn,en_us}.json`（该配置的 section/value/tooltip 键）

**逻辑（设计 §3.2）：** HEAD-cancellable；`enabled` 关 ⇒ 直通；`level().isClientSide` ⇒ 直通；`source.is(标签)` ⇒ `processPurt(source, amount, true)` ⇒ 返回 true 且层数>0 时 `removeOneImmuneStack()` ⇒ 锚点 ⇒ `cir.setReturnValue(dealt)`。`@Pseudo` + `require = 0`（bossesrise 为可选依赖）
**Steps：** 写 mixin + 注册 + 配置 + 翻译 → **临时把 `require` 改 `1`** 让 AP 校验目标 → 改回 `0` → 构建 → 提交
**Verification：** 两次 `gradlew build --offline` 均成功（第二次为 `require=0` 的最终形态）；lang 中英键集合一致；`javap -p` 确认 `processPurt`/`getImmuneStacks`/`removeOneImmuneStack` 为 public
**Commit：** `feat(legendarymonsters): 太阳破防标签可击穿冥界骑士护盾（含 COMMON 开关）`

---

### T7：端到端静态探针（S5）
**Files：** 不改代码
**Steps：**
1. 必要时临时移走 `run/mods` 里会导致服务端起不来的 iris/sodium（**记录并事后还原**）
2. `runServer` 加 `-Dmixin.debug.verbose=true -Dmixin.debug.export=true`
3. 日志确认 `Mixing … SolarFlareAbilitySolarDamageMixin … into …` 与 `… UnderworldKnightGuardBreakMixin … into …`
4. 对导出的 transformed 类 `javap -c` 复核：插入点恰在目标调用上、`convert` 被调用
**Verification：** 日志两行 `Mixing`；导出类字节码含 `SolarDamageTypes.convert` 与 `processPurt(..., true)` 调用
**Commit：** 无（探针任务）

---

### M1 里程碑（实机门 · 由用户执行）
**范围：** 耀斑链端到端 —— 吃「接受太阳祝福」→ 潜行左键打骑士
**期望：** ① 伤害落地（不再被盾挡）② `DATA_IMMUNE_STACKS` 1→0 ③ 日志出现 `[BeLoong] solar-guard-break: source=mowziesmobs:solar_flare …`
**未通过则：** 停止后续任务，按锚点/导出字节码定位（数据层 ⇒ 启动日志；转换层 ⇒ 类型是否为 `mowziesmobs:solar_flare`；判定层 ⇒ 锚点是否出现）

---

### T8：太阳射线转换 Mixin（含 E3 探针）
**Files（新建/修改）：** `mixin/mowziesmobs/EntitySolarBeamSolarDamageMixin.java` + `beloong.mixins.json`
**目标：** `public void tick()V`
**注入：** `@ModifyArg` on `DamageUtil.dealMixedDamage(...)Pair;` index **1**（bytecode 1187）+ `@ModifyArg` on `Lnet/minecraft/world/entity/Entity;hurt(...)Z` index 0（bytecode 1209）；`on_fire`（1182）不动
**Verification：** 构建通过；**导出字节码确认 1187 与 1209 两处都被处理**；若只处理一处 ⇒ 按 `javap` 偏移补 `ordinal` 并把偏移写进注释（重跑探针）
**Commit：** `feat(mowziesmobs): 太阳射线改用 beloong 太阳伤害类型（保留燃烧）`

---

### T9：太阳打击转换 Mixin（E3 主战场）
**Files（新建/修改）：** `mixin/mowziesmobs/EntitySunstrikeSolarDamageMixin.java` + `beloong.mixins.json`
**目标：** `public void damageEntityLivingBaseNearby(D)V`
**注入：** `@ModifyArg` on `Entity;hurt(...)Z` index 0（bytecode 324 与 347 两处），靠 handler 判别 `on_fire` 决定是否转换
**Verification：** 构建通过；导出字节码确认 **324 处被换类型、347 处保持 `on_fire`**；若多匹配行为不符 ⇒ 补 `ordinal`（记录 324/347 偏移）
**Commit：** `feat(mowziesmobs): 太阳打击改用 beloong 太阳伤害类型（保留燃烧）`

---

### T10：锚点与配置收尾核对
**Files（可能修改）：** `Config.java`、lang、三个 Mowzie mixin 的锚点调用
**Steps：** 统一锚点文案与 5 s 节流；核对 COMMON 段的 section/value/tooltip 中英齐全（项目规范）
**Verification：** `gradlew build --offline` 成功；lang 中英键集合差异 = 0；锚点格式与设计 §3.3 一致
**Commit：** `chore(beloong): 统一太阳破防日志锚点与配置翻译`

---

### T11：文档增补与验收清单交付
**Files（修改/新建）：**
- `docs/首领崛起-冥界骑士-调研.md`（增补"§十一 无敌判定与破防全链 + 本次设计索引"）
- `docs/reviews/2026-10-09-underworld-knight-solar-guard-break-acceptance.md`（**给你执行的逐步验收清单**：T1–T11 步骤、预期数值/日志/文案、回归项 R1–R3）

**Verification：** 两个文件存在、链接可达；清单步骤与设计 §6.2 一一对应
**Commit：** `docs: 冥界骑士太阳破防验收清单与调研增补`

---

## 验证矩阵（与设计文档一致）

| 层 | 编号 | 由谁执行 |
|---|---|---|
| 静态/构建期 | S1–S4 | 我（每个任务内） |
| 静态探针 | S5 | 我（T7/T8/T9） |
| 实机功能 | T1–T11（设计 §6.2 的编号） | **你**（M1 + 最终轮） |
| 回归 | R1–R3 | 你（最终轮） |

## 风险与回退

| 风险 | 触发点 | 回退 |
|---|---|---|
| E3 `@ModifyArg` 多匹配行为不符 | T8/T9 | 按 `javap` 偏移补 `ordinal`；或把转换改到"源构造点"（`DamageSources.mobProjectile` 的 `@ModifyExpressionValue`） |
| 骑士侧注入静默失效（bossesrise 可选 + `require=0`） | T6/M1 | 锚点缺失即暴露；临时 `require=1` 复验 |
| `mowziesmobs:` 命名空间被未来版本遮蔽 | 未来升级 | 整体改到 `beloong:`（类型/标签/`message_id`/翻译键四处同步） |
| dev 端服务端起不来（iris/sodium） | T7 | 临时移走 → 探针完事还原（记录到本计划执行日志） |

## 里程碑

| 里程碑 | 内容 | 门 |
|---|---|---|
| **M1** | 耀斑链端到端（T1–T7 后） | 用户实机：伤害落地 + 扣 1 层 + 锚点 |
| **M2** | 射线/打击接入（T8–T9 后） | 用户实机：三招齐活 + 燃烧保留 + 只扣 1 层 |
| **M3** | 交付（T10–T11 后） | 用户执行完整验收清单（含 T5 语义保留、T6–T9 边界、R1–R3） |
