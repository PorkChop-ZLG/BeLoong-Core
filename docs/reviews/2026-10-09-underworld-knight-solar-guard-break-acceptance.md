# 冥界骑士「太阳破防」验收清单

**被测版本：** BeLoong-Core **0.10.3**（含提交 `eb69ab1` … `14ce26d`）
**日期：** 2026-10-09
**设计 / 计划：** [设计文档](../plans/2026-10-09-underworld-knight-solar-guard-break-design.md) ／ [实施计划](../plans/2026-10-09-underworld-knight-solar-guard-break-plan.md)
**执行方：** 实机部分由你在整合包侧执行；静态项（构建、`javap`、jar 内容、lang 键一致性）已由开发方完成（见计划文档"执行日志"）

---

## 0. 前置准备

1. 把 `build/libs/beloong-0.10.3.jar` 放进整合包 `mods/`，**并删除旧的 `beloong-0.10.1.jar`**（两个 beloong 同存会撞车）。
2. 确认装了：**Mowzie's Mobs ≥ 1.8.2**、**Bosses'Rise 2.1.2**。
3. 配置 `config/beloong-common.toml` 里应有：

```toml
[solar_guard_break]
    #允许 Mowzie 的太阳伤害（太阳耀斑 / 太阳射线 / 太阳打击）击穿《首领崛起》冥界骑士的护盾，每次命中扣 1 层免疫层数
    enabled = true
```

---

## 1. 启动日志检查（不过就先别往下测）

日志：`<整合包>\.minecraft\logs\latest.log`

| # | 期望出现的行 | 说明 |
|---|---|---|
| S1 | `Mixing mowziesmobs.SolarFlareAbilitySolarDamageMixin … into …SolarFlareAbility` | 耀斑注入生效（**首次使用耀斑时**才打印，因为 Mixin 在类加载时应用）|
| S2 | `Mixing legendarymonsters.UnderworldKnightGuardBreakMixin … into …UnderworldKnightEntity` | 骑士侧判定生效 |
| S3 | **不应**出现 `Registry loading errors` / `missing following references` | 3 个伤害类型 json + 标签正确加载 |
| S4 | `Mixing mowziesmobs.EntitySolarBeamSolarDamageMixin … into …EntitySolarBeam` | 射线注入生效（首次使用射线后）|
| S5 | `Mixing mowziesmobs.EntitySunstrikeSolarDamageMixin … into …EntitySunstrike` | 太阳打击注入生效（首次触发后）|

> S1/S4/S5 是"类加载时应用"的注入，所以它们**只在对应技能第一次被使用时**才出现在日志里；S2 通常在启动阶段就出现（骑士类在注册阶段被加载过）。

---

## 2. 功能验收（T1–T4）

**场景准备**

```
/summon block_factorys_bosses:underworld_knight
/give @s mowziesmobs:grant_suns_blessing     # 右键使用 → 获得「太阳祝福」效果
```

骑士初始护盾 **1 层**（50% 血量闸门后会变成 2 层；阶段切换会重置为 1 层）。

| # | 用哪招 | 期望 |
|---|---|---|
| T1 | **太阳耀斑**（贴到 3.2 格内，潜行 + 左键）| ① 骑士掉血（原本 0 伤害）② 日志出现计数式锚点（形如 `#1 source=mowziesmobs:solar_flare (this-source=1) stacks=1->0 consumed=true dealt=true hp=…->…`）③ 再打一次显示 `stacks=0->0 consumed=false` |
| T2 | **太阳射线**（潜行 + 右键发射光束，持续命中）| 同 T1，且 ① 骑士**仍在燃烧**（`on_fire` 段保留）② **一次命中只扣 1 层**（不会一次扣 2 层）|
| T3 | **太阳打击**（`EntitySunstrike`：太阳鸟的打击 / 太阳祝福相关触发）| 同 T2 |
| T4 | 用太阳伤害把骑士打死 | 死亡信息为 `%1$s被%2$s的太阳耀斑烧成灰烬` / `…太阳射线贯穿` / `…太阳打击碾碎`（中英各 3 条），**不出现** `%2$s` 残留 |

**注意**：锚点已改为**计数式**（层数变化必记 + 其余按 1 秒聚合），每行都带 `stacks=…`、`dealt=…`、**`hp=before->after`** 与累计统计 ⇒ **判断标准是"hp 有没有下降 / 层数有没有掉"，不要靠血条视觉或单行日志**（伤害量级很小：耀斑基础 2.0 × 配置倍率，再经护甲削减）。

### 2.1 破防反馈与锚点（T12 / T13 之后新增）

| # | 检查 | 期望 |
|---|---|---|
| T12a | 用太阳伤害**打掉一层**护盾的瞬间 | 出现模组原生的"印记破碎"反馈：`KNIGHT_STACK_REMOVE` + `KNIGHT_HURT` 两个音效（音调偏高）+ `MARK_GLINT_EXP` / `MARK_GLINT_EXP_2` 粒子（位置在骑士身上，不是印记上）|
| T12b | 层数已为 0 后继续用太阳伤害打 | **不再**出现上述音效/粒子（避免误导），但仍会记录锚点（`consumed=false`）|
| T13 | 连续用太阳射线打 10 秒左右 | 日志里能同时看到：① 层数变化行（若期间扣层）② 若干条带 `#序号` 与 `totals: hits=… dealt=…` 的聚合行 ⇒ 能直接读出"总共命中多少次、其中多少次真的掉血" |

---

## 3. 语义保留（T5，验证 D7 的"补回原版标签"）

| # | 检查 | 期望 |
|---|---|---|
| T5a | 穿**弹射物保护**附魔被太阳射线打中 | 仍按 `#minecraft:is_projectile` 减伤（`mowziesmobs:solar_beam` 已补进该标签）|
| T5b | 用太阳耀斑完成 `adventure/overoverkill`（50 颗心伤害）| 仍能达成（`mowziesmobs:solar_flare` 已补进 `#minecraft:is_player_attack`）|

---

## 4. 边界与负向（T6–T9）

| # | 场景 | 期望 |
|---|---|---|
| T6 | 普通武器 / 其它模组伤害打骑士 | **与未装本功能完全一致**：护盾照挡、`stuck` 破防照旧、冥界印记照旧（骑士不因本功能变脆）|
| T7 | 开场（377t）/ 复活（440t）过场中打骑士 | 不掉血、不掉层（`processPurt` 的 `isCinematic()` 提前返回）|
| T8 | 护盾层数 = 0 后继续用太阳伤害打 | 伤害照常、层数不再变化（不会扣成负数、wisp 显示正常）|
| T9 | 关闭 `[solar_guard_break] enabled` 后重进 | 三招回到原版：被护盾挡下、无锚点日志、不扣层 |

---

## 5. 回归（R1–R3）

| # | 检查 | 期望 |
|---|---|---|
| R1 | 太阳三招打**其它**生物（玩家 / 龙 / 普通怪）| 数值与行为不变（只换了伤害类型；死亡信息文案会变，这是预期）|
| R2 | 骑士其它机制：`stuck` 破防姿势、冥界印记、阶段切换、成就 | 无变化 |
| R3 | 日志 | 无新增异常 / 无 `solar-guard-break … is unavailable` 警告 |

---

## 6. 本期明确**不做**（出现以下现象不算缺陷）

1. **手感调整** —— "只扣 1 层 + 不倒地"维持现状；护盾会被模组自身的闸门/阶段装回去（`setImmuneStacks`），是否压制这一点留待后续按手感决定。
2. **太阳鸟（Umvuthi）施法同样破防** —— 有意为之（类型转换在实体侧，与施法者无关）。
3. **actionbar 提示** —— 破防反馈只做了音效 + 粒子（对齐"击中冥界印记"的原生反馈）；模组那句 actionbar 提示的语义是"被击倒"，与"只扣 1 层"不符，故不搬。
4. **T4 死亡信息暂未实机验证** —— 两轮实机里骑士都没被太阳伤害打死；文案已随版本发布（中英各 3 条），待下一次击杀时顺带确认。

> **已从"不做"移出**：破防音效/粒子反馈（T12，`c642dd5`）、计数式锚点（T13，`e69b650`）—— 两项均已在 M2 之后实现，验收项见 §2.1。

---

## 7. 失败时的取证格式

请把 `latest.log` 里下面几类行（有则给，没有就说"没有"）贴给我：

- `solar-guard-break`（锚点 / 缺失类型警告）
- `Mixing mowziesmobs.` / `Mixing legendarymonsters.UnderworldKnight`
- `Registry loading errors` / `missing following references`
- 崩栈（如果有）：`crash-reports/` 最新文件 + `latest.log` 末尾 60 行
