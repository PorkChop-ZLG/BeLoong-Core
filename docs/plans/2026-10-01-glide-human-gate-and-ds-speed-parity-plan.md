# 滑翔缺陷修复 · 实施计划（rev 6）

**依据设计：** `docs/plans/2026-10-01-glide-human-gate-and-ds-speed-parity-design.md`（D1–D5，用户已裁定 D3 = a）
**依据诊断：** `docs/reviews/2026-10-01-glide-human-form-leak-and-speed-overshoot-diagnosis.md`（含附 A/B）
**状态：** 待执行（**尚未改代码**）
**唯一改动文件（预期）：** `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

---

## 任务

### T1 · 龙门（缺陷 1，Critical）
**Steps:**
1. HEAD（现 `:278`）改为"**是龙且滑翔**"才挂零重力：
   `beloong$setZeroGravity(player, DragonStateProvider.isDragon(player) && ServerFlightHandler.isGliding(player))`
2. TAIL 的滑翔分流（现 `:309`）之前加同一判定的守卫（与 `ClientFlightHandler:343` 的 `handler.isDragon()` 同源）。
3. 注释写明三件事：① 判据**必须**与 `:343` 同源；② 不得用"有 `FlightData`/有 `hasFlight`/展翅"替代（三者对人类同样为真）；
   ③ 成因是 `revertToHumanForm:872-888` 不碰 `FlightData` 且 `FlightEffect.apply/remove` 对非龙 return。
4. 更新类 javadoc 里 §"守卫结构"的表述，**补上被遗漏的第三层 lambda 内 `isDragon`**（自我更正的落点）。

**Verification:** `gradlew compileJava --rerun-tasks --no-build-cache` 成功、警告仍 3 条。

### T2 · 重力基础值缓存（D2 的前置，⚠️ 顺序敏感）
**Steps:**
1. 新增本模组私有静态字段（客户端、仅本地玩家）：`beloong$gravityBaseline`。
2. **在 HEAD 挂零重力修饰符之前**读 `player.getAttributeValue(Attributes.GRAVITY)` 并写入该字段（每 tick 覆盖）。
3. javadoc 写明：**一旦归零就读不到原值**；该缓存天然包含 DS 体型对重力的 `+10%/+20%` 修正，故不要自己复刻体型表。

**Verification:** 临时日志打印（或断点）确认滑翔中缓存值 ≈0.088~0.106 而非 0；验完删除临时日志。

### T3 · 竖直改为"重力等效下压"（D2）
**Steps:**
1. 删除 `beloong$GLIDE_BASE_ACCEL`、`beloong$GLIDE_FEEDBACK`、`beloong$GLIDE_FEEDBACK_MAX_SPEED` 三个常量及其 javadoc。
2. 竖直注入改为：`look.y <= 0` 时 `y += (look.y < 0 ? -1 : 0) * ...`——
   等价实现为 **`A(θ) = g_base · (2 − 0.75·cos²θ)`**，方向恒为 −y（`look.y <= 0` 的叠加式门不变）。
   注意 `vd = cos²θ`，θ 由 `look.y = -sin(θ)` 反推：`vd = 1 − look.y²`。
3. **不加**正反馈、**不设**上限、**不乘** `FS`（它们本身就是 DS/原版自己的量）。
4. 注释写明 `A(θ)` = DS `:408` 的 `g·(−1+0.75·vd)` **加** 原版 `travel:2331` 的 `−g`，并给三点数值（平视 0.110 / 45° 0.143 / 竖直 0.176）。

### T4 · 平视前向等效 + 爬升（D3a + D4）
**Steps:**
1. 新增一个**每 tick 目标值**形态的前向注入，目标 `beloong$GLIDE_LEVEL_FORWARD_TARGET ≈ 0.079`（平视、`FS=1` 标定），
   随俯角**连续衰减到 0**（低头时由 DS 自己的 `dM` 与 `ax/az` 承担，避免重复叠加）。
2. **必须有爬升**：复刻 DS 的线性累加器（`+0.004/tick` 爬到目标，约 20 tick），**不要**用常数、也不要用指数缓动——
   与 DS 同构才能保证观感一致，并且累加器在俯角切换处天然连续（rev 4 的"阶跃"教训）。
3. 注释写明 `0.079` 的来源 = 重力时代平视稳态下坠 `0.79` 经 `:410-413` 的 `0.1·vd·FS·|y|` 换算所得。

### T5 · 常量与文档收尾
**Steps:**
1. 常量区最终只剩 `GLIDE_TURN`、`NORMALIZE_EPSILON`、`GLIDE_LEVEL_FORWARD_TARGET`（+ 新的累加器状态字段）。
2. 类 javadoc 的"量级"小节按新模型重算（不再引用 rev 3/4 的 2.5/3.0 那套）。
3. 把诊断文档附 A 的结论（滑翔**不**走 elytra 分支、0.91 生效）回填进设计文档 §2.2 的刹车率处。

### T6 · 静态门
四项复用既有口径：`compileJava --rerun-tasks --no-build-cache`（警告 3 条）、`beloong.mixins.json` 未动、
零残留（`GLIDE_BASE_ACCEL`/`GLIDE_FEEDBACK`/`targetSpeed` 等）、语言键 zh/en 各 280 且集合一致。
**无需改语言文件**（tooltip 文案"沿视线加速"需复核是否仍准确，若不准再改并保持双语同步）。

### T7 · 实机验收（**由用户执行**）

| # | 场景 | 期望 | 判据来源 |
|---|---|---|---|
| A1 | 龙→人（祭坛）后空中按 Ctrl 10 s | **不掉落、不加速**；正常自由落体与坠落伤害 | 缺陷 1 |
| A2 | 龙形态平视滑翔 10 s | 渐进爬到 ≈**0.8 格/tick**（≈16 格/秒）；**不缓沉**；前 1 秒能看出加速过程 | D3a/D4 |
| A3 | 龙形态低头 45° 15 s | 竖直终端 ≈**1.6 格/tick**、水平 ≈2.0（总 ≈2.6）；起步为斜坡 | D2/D4 |
| A4 | 龙形态抬头 45° | 与 DS 原样一致（本模组不介入） | 叠加式门 |
| A5 | 滑翔中按 Esc 暂停 10 s 后恢复 | 不跳变、不瞬移 | rev 5 守卫 |
| A6 | `flight_level` 悬停 / 非稳定模拟 | 与 rev 5 一致（本次不动该路径） | 回归 |
| A7 | 非龙玩家（从未变龙）按 Ctrl | 与 A1 相同（不得出现飞行） | 缺陷 1 补测 |

> 注：A2/A3 的数值是一阶解析值（水平刹车 `0.91×0.99 ≈ 9.91%/tick`、竖直 `0.98×0.98 = 3.96%/tick` 再加 `:410-413` 的 `0.1·vd`）。
> 若实机偏离 ±20% 以上，先核对 T2 的重力缓存是否取到了非零值。

---

## 不在本次范围

- DS 自身那两条**没有**龙分流的处理器（`handleEarlyFlightLogic:173-190`、`handleWallCollisionsWhenFlying:192-214`）
  ⇒ 人类"滑翔"仍会被服务端记账、可能吃撞墙伤害。属 DS 行为。
- `docs/飞行等级系统.md` §4.4 未覆盖滑翔接管（文档缺口，**待用户另裁**）。
- C-2/I-3/I-4/S-3/S-6（rev 5 已裁定不修的既有残留）。

---

## 计划状态（2026-10-01 回填）

| 任务 | 状态 | 证据 |
|---|---|---|
| T1 龙门（HEAD + TAIL） | ✅ | `ClientFlightHandlerMixin`：HEAD `:311` 的 `isDragon && isGliding`、TAIL `:343` 的统一前置守卫 |
| T2 重力基准缓存（先摘→读→按需挂） | ✅ | HEAD `:308` 读入 `beloong$gravityBaseline` |
| T3 竖直改为重力等效下压 `A(θ)` | ✅ | `:255-258` |
| T4 平视前向等效 + DS 式爬升 | ✅ | `:260-272`（`levelness` 连续混合） |
| T5 常量与 javadoc 收尾 | ✅ | 三个旧常量零残留；类 javadoc 的守卫结构/性能/diff 三处已更新 |
| T6 静态门 | ✅ | `compileJava --rerun-tasks --no-build-cache` 通过、警告 **3 条**（与基线一致）；`mixins.json` 未动（`client=4`/`mixins=34`/`defaultRequire=1`）；语言键 **280/280** 集合一致 |
| T7 实机验收 A1–A7 | ⏳ **待用户执行** | — |

> 实机若 A2/A3 偏离设计值 ±20% 以上，先核对 T2 的重力缓存是否取到了非零值
> （最可能的失败形态是写序写反 ⇒ `A(θ)` 恒为 0 ⇒ 滑翔退化成"只转向不加速"）。
>
> 另外：tooltip 已随模型更新（原"沿视线加速"不再准确 ⇒ 改为"转向与速度均接近原版"），中英同步。

### 追加：平视悬崖的结构修复（同轮，见设计 §6.4）

实机报"完全平视时几乎没有任何动力" ⇒ 根因是前向项被放回了 `look.y <= 0` 符号门内
（`look.y = -sin(xRot)`，"完全平视"正好落在门边界上）。已按 **rev 5 的结构**修复：
`levelness` 改为由 `|look.y|` 决定的**对称带**，前向项**移出符号门**，只有下压留在门内。
量级不变（`TARGET = 0.079` / `RAMP = 0.004`）。

| # | 追加验收 | 期望 |
|---|---|---|
| **A8** | 平视保持 10 s，并让俯仰在 `0` 两侧各偏 ±1° 来回 | 速度维持在 ≈0.80 格/tick、**不掉速**；动力**不得**随俯仰符号跳变 |
| **A9** | 同一会话内**连续三次**做同一个"平视滑翔"动作（每次结束后松开 Ctrl ≥1 tick 再做下一次） | 三次的**起步形状必须一致**（都从慢到快）。修复前：第 2、3 次因为累加器带着满值进来，起步会明显更快 |
| **A10** | 平视滑翔 10 s **不松手** | 约 1 s 后稳定在 ≈0.8 格/tick（≈16 格/秒），**不得**出现"周期性掉速再爬"（复位若被错误放进滑翔路径就会出现） |

### A9 的量化做法（F3 读数，可选但推荐）

平视滑翔，用 F3 的 XYZ（3 位小数）读坐标：

| 时刻 | 记 | 期望（本修复生效） | 期望（修复前） |
|---|---|---|---|
| t=0 进入滑翔 | p0 | — | — |
| t=1 s | p1（`d1 = |p1−p0|`） | `d1 ≈ 7~8 格` | `d1 ≈ 13 格` |
| t=2 s | p2（`d2 = |p2−p1|`） | `d2 ≈ 21 格` | `d2 ≈ 21 格` |
| **判据** | `d2 / d1` | **≳ 2.5** | **≈ 1.6** |

前提：尽量从**接近 0 的水平速度**起滑（原地起跳、到最高点再按 W+Ctrl），否则 v0 会稀释这个比值。
更稳的判据是**同动作重复三次互相对比**——三次之间唯一的差异就是累加器的初值。
