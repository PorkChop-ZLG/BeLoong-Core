# 滑翔去重力 + 跟随视线 · 实施计划

**Goal:** 让 DS 滑翔在**任何** `stable_hover` / `flight_level` 组合下都不受重力影响，并改为跟随视线飞行（可上可下）。
**Architecture:** 一个 mixin（`ClientFlightHandlerMixin`）+ 两个注入点。HEAD 按 `isGliding()` 挂/摘 `ADD_MULTIPLIED_TOTAL = -1.0` 的瞬时重力修饰符（同时覆盖 DS `ClientFlightHandler:408` 与原版 `LivingEntity.travel:2331` 两个向下来源）；TAIL 最优先处理滑翔——把速度方向按 `T = 0.10` 插值到视线、大小严格保持，然后 `return`，使滑翔彻底脱离 `Config` / `stableHover` / 飞行等级门控。
**Approach:** 设计文档（`docs/plans/2026-09-27-glide-gravity-free-look-follow-design.md`）的 **Approach A**，用户已逐节确认。
**Plan shape:** 案 1 —— 单批实现 + 分层验收（T1–T6）。

> **偏离声明（TDD）**：本项目**没有测试套件**，planning 模板的 RED-GREEN-REFACTOR 不适用。每步验证 = `gradlew build` + 静态探针；行为验收集中在 T6 的实机清单。这是项目既有约定，不是省略验证。
>
> **提交**：按项目惯例，**所有提交由用户执行**，本计划不含 agent 提交步骤。

---

### T1: HEAD 重力闸门

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`（HEAD 处理器，现 `:83-89`）

**Steps:**
1. 基线确认：`git status --short` 只应有本次会话的文档改动；`gradlew build` 通过（**记录警告数，预期 3 条**：`PossibleBiomesFilterMixin` 的 `@Shadow` + `ParameterListAccessor` 的 2 条 `@Accessor`）
2. 方法改名 `beloong$clearZeroGravity` → `beloong$glideGravityGate`
3. 把"无条件摘除"改为"按闸门挂/摘"：

```java
    @Inject(method = "flightControl", at = @At("HEAD"), remap = false)
    private static void beloong$glideGravityGate(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            // 滑翔 → 挂零重力（同时覆盖 DS :408 与原版 travel 的 d0）；非滑翔 → 摘除。
            // 每 tick 必有一次写操作，故修饰符不会残留。
            beloong$setZeroGravity(player, ServerFlightHandler.isGliding(player));
        }
    }
```

4. 重写该方法的 javadoc：说明"必须每 tick 无条件执行一次写操作（挂或摘）"这一不变式，以及"关掉 `fixStableHoverDrift` 时也必须摘除，否则重力永久为 0"

**Verification:** `gradlew build` 通过

---

### T2: TAIL 滑翔分支 + 方向插值

**Files:**
- Modify: 同上（TAIL 处理器 `beloong$fixStableHoverDrift`，现 `:94-107`；新增常量与 helper）

**Steps:**
1. 新增常量与 helper（放在 HEAD/TAIL 处理器之前，与既有常量区同处）：

```java
    /**
     * 滑翔时速度方向向视线插值的系数。
     *
     * <p><b>必须 {@code 0 < T < 0.5}</b>：{@code lerp} 结果为零要求 {@code (1-T)/T == 1} 即 {@code T == 0.5}，
     * 此时 180° 掉头会让 {@code normalize()} 除零。{@code T = 0.10} 对应约 22 tick（≈1.1 s）转过 90%。</p>
     */
    private static final double beloong$GLIDE_TURN = 0.10;

    /**
     * 滑翔时让速度方向跟随视线，**大小严格保持**。
     *
     * <p>去重力后 DS 只提供向上的竖直分量（{@code ClientFlightHandler:430} 仅在抬头时给 {@code ay}），
     * 所以低头能否下降完全由这里提供。末尾 {@code normalize().scale(speed)} 只抵消等长向量插值的弦长缩短，
     * 不动 DS 在 TAIL 之前已施加的 {@code ELYTRA_FLY_DRAG} 拖曳。</p>
     */
    private static void beloong$followLook(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        double speed = delta.length();
        if (speed <= 1.0E-5) {
            return;
        }
        Vec3 target = player.getLookAngle().scale(speed);
        player.setDeltaMovement(delta.lerp(target, beloong$GLIDE_TURN).normalize().scale(speed));
    }
```

2. 在 TAIL 处理器最前插入滑翔分支（**必须早于 `Config` 与 `beloong$isEligible`**），并把 `player` 判空提到最前：

```java
    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void beloong$fixStableHoverDrift(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        // ===== 滑翔：独立于本模组开关、DS 的 stableHover 与飞行等级（需求 1/3）=====
        // 必须最优先 return：需求 1 的"不受门控"由控制流保证，而非条件表达式。
        if (ServerFlightHandler.isGliding(player)) {
            // 旋转攻击保持 DS 原版动力学（只跳过滤线，重力仍已归零）
            if (!ServerFlightHandler.isSpin(player)) {
                beloong$followLook(player);
            }
            return;
        }

        if (!Config.FIX_STABLE_HOVER.get()) {
            return;
        }

        if (!beloong$isEligible(player)) {
            return;
        }
        // …以下 level>=1 悬停锁定 / level<1 追加 -g 全部保持现状…
```

3. 确认 `Vec3` 已在 import 列表中（现有文件已导入 `net.minecraft.world.phys.Vec3`）

**Verification:** `gradlew build` 通过

---

### T3: 删除已成死代码的滑翔排除项

**Files:**
- Modify: 同上（`beloong$isEligible`，现 `:185-188`）

**Steps:**
1. 删除：

```java
        // 滑翔完全交给 DS 原版（含重力），本 Mixin 不接管
        if (ServerFlightHandler.isGliding(player)) {
            return false;
        }
```

2. 同步更新 `beloong$isEligible` 的 javadoc（现 `:154-159`）：排除项列表里移除"滑翔"，改注"旋转只跳过滤线、不跳过去重力（滑翔分支已在更上游 `return`，本方法不会再遇到滑翔态）"

**Verification:**
```powershell
# isEligible 内不应再出现 isGliding
Select-String -Path src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java -Pattern 'isGliding'
# 预期：只出现在 glideGravityGate（HEAD）、TAIL 滑翔分支、javadoc 里；不出现在 isEligible 范围内
```
`gradlew build` 通过

---

### T4: 文案同步（不得再说"滑翔不受影响"）

**Files:**
- Modify: `ClientFlightHandlerMixin.java`（类 javadoc `:44`、`:158`）
- Modify: `src/main/java/com/zonlong/beloong/Config.java`（`:27`、`:30`）
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. 类 javadoc `:44`：把"滑翔不再被接管：`isGliding` 被排除出判定，DS 原版滑翔物理一字不动"改写为"滑翔由本 mixin 接管：去重力 + 跟随视线（不受本开关与飞行等级门控）"
2. 类 javadoc 增补设计要点：两个向下来源（DS `:408` 与原版 `travel` 的 `d0`）由 HEAD 的同一属性修饰符覆盖；并写明**前提**——`ClientFlightHandler` 内部不写 `isGliding()` 的任何判定输入，故 HEAD/TAIL 同 tick 恒同值（若 DS 改动此前提，最坏是一 tick 内挂了又摘）
3. `Config.java:27`：`<p>滑翔不在本修复范围内，完全由 DS 原版处理。</p>` → `<p>滑翔由独立路径处理，不受本开关门控。</p>`
4. `Config.java:30`：`.comment("稳定悬停修复总开关；需同时满足 DS 的 stable_hover=true 且 flight_level>=1；滑翔不受影响")` → `…；滑翔另有独立处理（不受本开关影响）"`
5. 两份 lang 的 `beloong.configuration.fixStableHoverDrift.tooltip` 末句：
   - zh：`滑翔不受本修复影响。` → `滑翔另有独立处理：不受重力、跟随视角，且不受本开关与飞行等级影响。`
   - en：`Gliding is not affected.` → `Gliding is handled separately: gravity-free and look-directed, unaffected by this switch or the flight level.`

**Verification:**
```powershell
# 键数与集合必须完全一致（预期 219 / 219）
$z=(Get-Content src/main/resources/assets/beloong/lang/zh_cn.json -Raw|ConvertFrom-Json).PSObject.Properties.Name
$e=(Get-Content src/main/resources/assets/beloong/lang/en_us.json -Raw|ConvertFrom-Json).PSObject.Properties.Name
"$($z.Count) / $($e.Count)"; Compare-Object $z $e
# 已失真表述不得残留
Select-String -Path src/main/java/com/zonlong/beloong/Config.java,src/main/resources/assets/beloong/lang/*.json -Pattern '滑翔不受|Gliding is not affected'
```
`gradlew build` 通过

---

### T5: 静态门

**Files:** 无（只读校验）

**Steps:**
1. `gradlew build` —— 必须成功，且 Mixin AP 警告仍为 **3 条**、不多不少
2. 确认 `src/main/resources/beloong.mixins.json` **未被修改**（本次不新增 mixin 文件、不移动注入点）⇒ DS 侧仍为 14 个文件 / 19 个注入点
3. 残留扫描：
```powershell
# 旧方法名不得残留
Select-String -Path src/main/java -Pattern 'beloong\$clearZeroGravity'
# 语言键
$z=(Get-Content src/main/resources/assets/beloong/lang/zh_cn.json -Raw|ConvertFrom-Json).PSObject.Properties.Name
$e=(Get-Content src/main/resources/assets/beloong/lang/en_us.json -Raw|ConvertFrom-Json).PSObject.Properties.Name
"$($z.Count) / $($e.Count)"; Compare-Object $z $e
```
4. 记录 `git diff --stat` 供用户提交

**Verification:** 上述命令输出符合预期 + `gradlew build` 成功

---

### T6: 实机验收（**由用户执行**）

**Files:** `run/` 客户端

**Steps:** 按设计文档 §2.5 逐条勾选。**每组必须记录三个变量**（诊断教训：任一不同会让结论反过来）：

| 变量 | 取值 |
|---|---|
| DS `stable_hover` | true / false |
| 玩家 `flight_level` | 数值 |
| 饱食度 | 数值 |

> ⚠️ **前置条件（设计 §2.6 B-1）**：A1–A4 必须在**饱食度 > 6** 时执行，并确认此刻 `isGliding()` 为真。
> 生产服 `flight_hunger_threshold = 4`，饱食度 5~6 时原版会取消冲刺 ⇒ `isGliding()` 必为假 ⇒
> 本改动对该状态零作用（且仍会被追加 `-g`）。**不得据此判定改动失效**——那是需求边界，不是缺陷。

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| A1 | `stable_hover=false`、`flight_level=0`、滑翔中平视（饱食度 > 6） | 不下沉 | ☐ |
| A2 | 同上，低头 45° | 能下降，俯角越大越快；**同期记录空袭速度/伤害**（B-4） | ☐ |
| A3 | 同上，抬头 45° | 能爬升 | ☐ |
| A4 | `stable_hover=true`、`flight_level≥1`，重做 A1–A3 | 与 A1–A3 一致 | ☐ |
| A5 | 关掉 `fixStableHoverDrift`，重做 A1–A3 | 与 A1–A3 **完全一致** | ☐ |
| A6 | 滑翔中松 Ctrl / 撞墙 / 入水 | 立刻回 DS 原版。**判读见 B-3**：切换那一 tick 仍零重力属正常 | ☐ |
| A7 | 旋转攻击中 | 不跟随视线，重力仍归零 | ☐ |
| A8 | 滑翔 → 松 Ctrl 悬停 → 再滑翔 | 过渡无抖动、无突然上跳 | ☐ |
| A9 | 水中展翅飞行 | 完全 DS 原版 | ☐ |
| R1 | 非滑翔悬停（level≥1） | 高度锁定不变 | ☐ |
| R2 | 非滑翔 level<1 + `stable_hover=true` | 追加 `-g` 不变 | ☐ |
| R3 | `stable_hover=false` 的非滑翔 | 本模组不介入 | ☐ |
| R4 | 非龙 / 无翅 | 不介入 | ☐ |

**数值锚点**（设计文档 §5.4，`T = 0.10` 理论值）：

| 指标 | 期望 | 实测 |
|---|---|---|
| 平视滑翔 10 s 高度漂移 | `< 1 格` | |
| 45° 低头 3 s 下降量 | `10 ~ 60 格` | |
| 转过 90% | ≈ 22 tick ≈ 1.1 s | |

**Verification:** 全部 ☑；若 `T` 手感不合适，只改 `beloong$GLIDE_TURN` 一个常量（`0.15 → 约 14 tick`、`0.20 → 约 10 tick`）后重跑 T2 的验证与 A1–A3。

**验收期临时手段**：允许临时加一条英文 ASCII 的 `LOGGER.info` 打印 `isGliding/stableHover/flightLevel/foodLevel`（状态跳变时打，**不打逐 tick**）。**T6 结束必须删除**（先例 `93066f6 chore: 移除调试日志`）。

---

## 关键文件清单

| 动作 | 路径 |
|---|---|
| Modify | `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java` |
| Modify | `src/main/java/com/zonlong/beloong/Config.java` |
| Modify | `src/main/resources/assets/beloong/lang/zh_cn.json` |
| Modify | `src/main/resources/assets/beloong/lang/en_us.json` |
| **不改** | `src/main/resources/beloong.mixins.json` |
| **不改** | `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerAccessor.java` |

## 非目标（本计划不做）

- 水中去阻力；DS `:460`/`:531` 分支；`AirStrikeEffect`；滑翔动画分档；相机
- F-4（level<1 的 `noMoveInput` 限制）
- 清理 `beloong$setAy(0.0)` 的可疑空操作
- 新增任何配置项与语言键

---

# 修订 rev 2：滑翔各方向加速（2026-09-27）

**动因**：rev 1 验收"功能正常"，但只有视角朝上才有加速——DS 的竖直能量只有向上两条（`:415-419`、`:430`→`:449`），向下的唯一来源重力已被 rev 1 归零，而 rev 1 的保大小插值只转向不加能。设计与决策见设计文档 §6（D10–D13）。

## rev 2 追加任务

### T7: `beloong$followLook` 增加"沿视线补速"

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 新增 import `by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes`
2. 新增常量 `beloong$GLIDE_ACCEL = 0.25`（放在 `beloong$NORMALIZE_EPSILON` 之后），javadoc 注明"取自 DS 抬头 `ay = viewVector.y/4` 的上限（正上方 0.25）"
3. 按设计 §6.2 的代码替换 `beloong$followLook` 方法体：先方向插值（不变），再在 `look.y <= 0` 且 `speed < targetSpeed` 时沿 `look` 补 `min(0.25, targetSpeed − speed)`，`targetSpeed = 0.8 × getAttributeValue(DSAttributes.FLIGHT_SPEED) × 2`
4. 在 `beloong$GLIDE_TURN` 的 javadoc 补一句"本系数只管方向；速度由 `beloong$GLIDE_ACCEL` 管"
5. 在 `beloong$followLook` 的 javadoc 写明"抬头走 DS、平视/低头走本模组"这一**有意的不对称**及其原因（DS 竖直能量只有向上）

**Verification:** `gradlew compileJava --rerun-tasks --no-build-cache` 成功，警告仍 3 条

### T8: 文案同步（tooltip 补"沿视线加速"）

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. `fixStableHoverDrift.tooltip` 末句由"滑翔另有独立处理：不受重力、跟随视角，且不受本开关与飞行等级影响。"改为"…不受重力、跟随视角并沿视线加速，且不受本开关与飞行等级影响。"
2. en 同步：`gravity-free and look-directed` → `gravity-free, look-directed and accelerating along the look`

**Verification:** 键集 `zh_cn=219 / en_us=219` 且 `Compare-Object` 无差异

### T9: 静态门（rev 2）

**Steps:** 复用 T5 的四项（build + 警告数、`beloong.mixins.json` 未动、残留扫描、键集），并额外确认 `DSAttributes` import 已加、`beloong$GLIDE_ACCEL` 只出现于常量声明与那一处 `Math.min`。

**Verification:** 四项全绿 + `gradlew compileJava --rerun-tasks --no-build-cache` 通过

### T10: 实机验收 rev 2（**由用户执行**）

按设计 §6.3 的 A10–A13，并重跑 rev 1 的 A1–A9（至少 A1–A3 与 A12）。四个新场景：

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| A10 | 平视滑翔 3 s | 速度明显增长并稳定在约 1.6 格/tick，不再单调减速 | ☐ |
| A11 | 低头 45° 滑翔 | 俯冲加速，强度与抬头相当 | ☐ |
| A12 | 抬头滑翔 | **与 rev 1 一致**（叠加式，不应变快） | ☐ |
| A13 | 三方向各 10 s | 均收敛、无超调、无失控增长 | ☐ |

> 若 A10/A11 的强度不合手感，只改 `beloong$GLIDE_ACCEL`（0.25 → 0.12 → 0.08 递减）或目标系数（`0.8` → `0.5`），改完重跑 T7 验证 + A10–A12。

---

# 修订 rev 3：按 DS 抬头强度重做（取消上限 + 竖直镜像 + 水平底座）

**动因**：用户实测 rev 2 后要求 ① 竖直不设上限 ② 竖直增量对齐 DS 抬头 ③ 加正反馈 ④ 水平/向下接近重力时代。设计与决策见设计文档 §7（D14–D18）。**rev 3 删除了 rev 2 的目标速度与上限机制。**

## rev 3 任务

### T11: 改写 `beloong$followLook`（竖直镜像 + 水平底座，删上限）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 删除常量 `beloong$GLIDE_ACCEL`
2. 新增两个常量（放在 `beloong$NORMALIZE_EPSILON` 之后）：
   - `beloong$GLIDE_BASE_ACCEL = 0.25` —— DS `ay = viewVector.y/4` 的上限，同时用作水平底座
   - `beloong$GLIDE_FEEDBACK = 0.128` —— 等于 DS `:415-419` 的 `3.2 × 0.04`（正反馈系数）
   两者 javadoc 都要写明来源与"反馈只进竖直"的原因
3. 按设计 §7.3 改写 `beloong$followLook`：
   - 保留方向插值（第一步）与 `beloong$NORMALIZE_EPSILON` 守卫
   - 删除 `targetSpeed` / `speed < targetSpeed` / `Math.min(...)` 整段
   - 在 `look.y <= 0.0` 内加入：竖直镜像项 `look.y * (BASE + FEEDBACK * h)`（`h = delta.horizontalDistance()`）+ 水平底座 `lookH.normalize().scale(BASE)`
4. 方法 javadoc 补：DS 抬头公式的出处（`:449` + `:415-419`）、镜像映射、**正反馈只进竖直的原因**（§7.2 的发散论证）、以及"水平/向下结果会比重力时代更强"是有意选择
5. **确认 `DSAttributes` import 是否仍被使用**——若 rev 3 不再需要 `FLIGHT_SPEED`，删掉该 import（否则会有未使用 import）

**Verification:** `gradlew compileJava --rerun-tasks --no-build-cache` 成功、警告仍 3 条

### T12: 静态门（rev 3）

**Steps:** 复用 T5/T9 的四项（build + 警告数、`mixins.json` 未动、残留扫描、键集 219/219），并确认：
- `beloong$GLIDE_ACCEL` 与 `targetSpeed` **零残留**
- `beloong$GLIDE_FEEDBACK` 只出现在常量声明、竖直项、javadoc
- 无未使用 import（`DSAttributes` 已按 T11 步骤 5 处理）

**Verification:** 全绿 + `compileJava --rerun-tasks --no-build-cache` 通过

> **无需改 lang**：现有 tooltip"滑翔另有独立处理：不受重力、跟随视角并沿视线加速…"在 rev 3 下仍准确。

### T13: 实机验收 rev 3（**由用户执行**）

按设计 §7.6 的 A14–A18，并重跑 A1–A3、A17。

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| A14 | 低头 45° 持续 10 s | 竖直持续增长至 ≈4~5 格/tick 并稳定，不再撞 1.6 | ☐ |
| A15 | 竖直向下 | 可达 ≈6 格/tick 量级，且**不得无限增长**（失控判据） | ☐ |
| A16 | 平视持续 10 s | 水平增至 ≈2.5 格/tick 并稳定 | ☐ |
| A17 | 抬头 | 仍与 rev 1/2 一致（DS 原样） | ☐ |
| A18 | 三方向各 15 s | 均收敛；关注 h 是否突破 ~4、竖直是否突破 ~7 | ☐ |

> **失控时的第一处置**：把 `beloong$GLIDE_FEEDBACK` 从 `0.128` 降到 **0.09 以下**（低于竖直刹车率 9%）。其次调 `beloong$GLIDE_BASE_ACCEL`。

---

# 修订 rev 4：水平底座移出俯仰门控（方案 A）

**动因**：rev 3 实测出现 ① 完全平视没有动力 ② 飞着飞着突然加速。两者同源——**常数量（水平底座 0.25）被 `look.y <= 0` 的符号门控**（`look.y = -sin(xRot)`，摄像机高出一丝即为正 ⇒ 整块跳过；穿越零点则 0.25/tick 瞬间通断）。设计与决策见设计文档 §8（D19–D20）。

## rev 4 任务

### T14: 把水平底座移出 `look.y <= 0` 门 ✅

**Files:** `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. `2a` 水平底座提到 `if (look.y <= 0.0)` **之前**，滑翔期间始终施加
2. `2b` 竖直镜像项**留在门内**（保住"抬头竖直不介入 DS"），`horizontalSpeed` 随之移入该块
3. 注释写明：`look.y = -sin(xRot)` 的浮点边界、9%/tick 悬崖（1 秒掉到 15%）、0.25 阶跃 vs 创造飞行的 0.15
4. 方法 javadoc 的"两个有意的不对称"改为"两处有意的取舍"，注明**水平底座不受竖直的门控**（取舍：抬头水平不再等于 DS 原样）

**Verification:** `gradlew compileJava --rerun-tasks --no-build-cache` 成功、警告仍 3 条 —— ✅ 已通过

### T15: 实机验收 rev 4（**由用户执行**）

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| A19 | 水平附近来回微调视角（±1°）保持 10 s | **速度不得出现台阶**；平视不再失去动力 | ☐ |
| A20 | 完全平视持续 10 s | 稳定在 ≈2.5 格/tick（维持而非衰减） | ☐ |
| A21 | 抬头滑翔 | 竖直仍与 rev 1/2 一致；水平**略强于 DS 原版**（D19 已知取舍） | ☐ |

## 未决（记录，未改）

**竖直偏快**：低头 45° 的竖直输入在 `h=2.5` 时约 0.403，是重力时代 0.143 的 **2.8 倍**；即使 `GLIDE_FEEDBACK` 归零，常数项 `0.707×0.25 = 0.177` 仍比重力时代快 1.24 倍。要回到重力时代量级需**同时**降 `GLIDE_BASE_ACCEL`（0.25 → ≈0.20）与 `GLIDE_FEEDBACK`。**等用户裁定。**

---

# 修订 rev 5：代码审查后的七项修复

**动因**：`code-review` 技能产出的审查报告（1 Critical + I-1..I-4 + S-1..S-7，含独立审查者发现）。**审查对象 = commit `cff0020`。** 设计与决策见设计文档 §9。

**用户裁定**：修复 **C-1 / I-1 / I-2 / S-1 / S-2 / S-4 / S-5 / S-7**；不修 **C-2**（整合包已关撞墙伤害）、**I-3**、**I-4**（判定为非 bug）、**S-3**、**S-6**。

## rev 5 任务

### T16: C-1 滑翔分支加暂停守卫 ✅
**Files:** `ClientFlightHandlerMixin.java`（TAIL）
**Steps:** 在 `player == null` 判空之后、滑翔分流之前插入 `if (Minecraft.getInstance().isPaused()) return;`，注释写明：`ClientHooks.fireClientTickPre()` 在 `Minecraft.tick():1799` **无条件**触发，而 `level.tickEntities()`/`level.tick()` 与 DS 方法体都被暂停挡住 ⇒ 暂停期间注入无对冲。

### T17: I-1 反馈源封顶（杀掉 `h↔|y|` 闭环） ✅
**Files:** 同上（常量区 + `beloong$followLook`）
**Steps:** 新增 `beloong$GLIDE_FEEDBACK_MAX_SPEED = 1.0`；竖直项改用 `min(result.horizontalDistance(), cap)`；`GLIDE_FEEDBACK` 的 javadoc 重写为完整收敛论证（环增益公式、临界值 0.255、为何 1.0 是 DS 自身量级而非随手取值）。

### T18: I-2 反馈项补 `FLIGHT_SPEED` ✅
**Files:** 同上
**Steps:** 重新 import `DSAttributes`；竖直项改为 `0.128 × FS × min(h, cap)`；`GLIDE_BASE_ACCEL` 的 javadoc 注明**刻意不乘 FS**（它镜像的 `ay` 本身不带 FS，水平底座是本模组自有常量、以 FS=1 标定）。

### T19: S-1 / S-2 阈值 ✅
`NORMALIZE_EPSILON = 1.0E-4 / (1.0 - 2.0 * GLIDE_TURN)`；`lookH` 守卫改为 `length() > NORMALIZE_EPSILON`。

### T20: S-4 命名/注释漂移 ✅
修饰符 id → `beloong:zero_gravity`；TAIL 处理器 → `beloong$flightTweaks`；`DSAttributesMixin` 的 `dragonturvival` 笔误修正，"加载顺序"理由改写为三条真正承重的机制（DeferredRegister 排队 / 类加载早于 RegisterEvent / `GameData` 把 ATTRIBUTE 排最前）。

### T21: S-7 删除死代码 ✅
删除 `ClientFlightHandlerAccessor.java` + `beloong.mixins.json` 的 `client` 条目 + `beloong$setAy(0.0)` 调用（语义空操作，理由写入原处注释）。DS 侧 mixin **14 → 13** 个文件。

### T22: S-5 文档漂移 ✅
设计文档加"现行状态"横幅、§7.2 加更正指向、修正反向的最坏情况措辞、新增 §9（含 §9.3 两条被推翻的旧结论）。本文件追加 rev 5 段落。

### T23: 静态门（rev 5） ✅
`compileJava --rerun-tasks --no-build-cache` 通过、警告仍 3 条；`ClientFlightHandlerAccessor`/`stable_hover_zero_gravity`/`beloong$fixStableHoverDrift`/`dragonturvival`/`1.0E-5` 零残留；键集 219/219。

### T24: 实机验收 rev 5（**由用户执行**）

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| A22 | 滑翔中按 Esc 暂停 10 s 后恢复 | **速度不跳变、不瞬移、不被服务端拉回**（C-1 判据） | ☐ |
| A23 | 低头 45° 持续 15 s | 竖直收敛于 **≈3 格/tick**；水平不超过 ≈5 | ☐ |
| A24 | 平视持续 10 s | 水平仍 ≈2.5（与 rev 4 一致） | ☐ |
| A25 | 用不同生长阶段的龙 | 竖直强度随 `FLIGHT_SPEED` 变化（±20% 量级） | ☐ |
| A26 | 重跑 A1–A3、A19–A21 | 全部不变 | ☐ |

> **若 A23 仍偏快**：按设计 §8.5 同时降 `GLIDE_BASE_ACCEL`（0.25 → ≈0.20）与 `GLIDE_FEEDBACK`。

---

## 计划状态：✅ 全部完成（2026-09-27 收尾）

| 任务 | 状态 |
|---|---|
| T1–T6（rev 1：去重力 + 跟随视线 + 文案 + 静态门 + 实机 A1–A9） | ✅ |
| T7–T10（rev 2：沿视线补速 + tooltip + 静态门 + 实机 A10–A13） | ✅ |
| T11–T13（rev 3：竖直镜像 + 水平底座 + 删上限 + 实机 A14–A18） | ✅ |
| T14–T15（rev 4：水平底座移出俯仰门控 + 实机 A19–A21） | ✅ |
| T16–T24（rev 5：审查后七项修复 + 静态门 + 实机 A22–A26） | ✅ |

**验收结论**：用户实机确认"基本完美"。未修项见设计 §10「已知残留」（C-2 / I-3 / I-4 / S-3 / S-6，均已裁定不修并记录）。
