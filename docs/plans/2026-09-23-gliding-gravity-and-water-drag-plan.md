> # ❌ 已否决，未实施（2026-09-23）
>
> 本计划已执行过 T1–T5、后经实机测试效果不佳，**四处代码改动全部回退**，
> 工作树回到 `c9181cf`。**不要按本计划实施。**
> 设计背景与"为何否决却仍保留"见同名的 `-design.md` 顶部说明。

---

# 滑翔去重力 + 水中去阻力 · 实施计划

**状态：** ❌ **已否决，未实施**（见上方说明）

**目标：** 让滑翔状态不再受重力下坠（属性法，在 DS 计算前归零），并让水中飞行不受水的阻力与推动（新增 1 个原版 mixin）。

**架构：** 抽出共用谓词 `StableFlightState`；`ClientFlightHandlerMixin` 的 HEAD 改为状态机（滑翔挂修饰符 / 其余摘除）、TAIL 按「滑翔 / 悬停 / 非稳定」三路分流；新增 `PlayerFluidInteractionMixin` 覆写 `isAffectedByFluids` 与 `isPushedByFluid`。

**上游文档：** `docs/plans/2026-09-23-gliding-gravity-and-water-drag-design.md`（已批准）

**验证基线：** 本项目无测试套件 ⇒ 每任务验证 = **编译门 + grep 静态探针**，最终由实机场景验收（§6）。

---

## ⚠️ 实施前必须知道的两条硬约束

**A. `isAffectedByFluids` 是本系统的「输出」，不得作为任何谓词的「输入」。**
T4 的 mixin 让它返回 false 后，若谓词还去读它，就会自我否定：mixin 生效 → 谓词失效 → mixin 失效 → 谓词生效 → 抖动。
⇒ 水中判定统一用 `player.isInWater() && !player.canStandOnFluid(level().getFluidState(blockPosition()))`。
（副作用是好事：DS 的 `handleDragonSwimming`（`LivingEntityMixin:196`）也会因该标志为 false 而退出，水中不再有 DS 游泳逻辑干扰我们。）

**B. `ServerFlightHandler.stableHover` 必须直接读 DS 字段，不得引入副本。**
这是上一轮实测②③异常的结论（判定前提必须与 DS 物理前提同源）。`StableFlightState` 里只能有这一处读取。

---

### Task 1: 新增共用谓词类 `StableFlightState`

**依赖：** 无

**Files:**
- Create: `src/main/java/com/zonlong/beloong/flight/StableFlightState.java`

**Steps:**
1. 建 `public final class StableFlightState`，私有构造器。
2. **保持与端无关**：类内**不** import `Minecraft` / `Config`；客户端专属量由调用方以参数传入（这样它被任何一侧加载都不会炸）。
3. 提供四个静态方法：

```java
/** 共同前置：本模组是否应当接管该玩家的飞行。 */
public static boolean isEligible(Player player, boolean fixEnabled, boolean paused,
                                 boolean jumping, boolean sneak);

/** 滑翔且飞行等级足够 → 取消重力（不碰竖直/水平动力学）。 */
public static boolean isGlideGravitySuppressed(Player player, double flightLevel);

/** 空中稳定悬停 → 锁高度 + 取消重力。 */
public static boolean isAirStableHover(Player player, double flightLevel);

/** 水中稳定飞行 → 锁高度 + 取消重力 + 去水阻力。注意：不得读 isAffectedByFluids。 */
public static boolean isWaterStableFlight(Player player, double flightLevel);
```

4. `isEligible` 的条件顺序（与现有 `ClientFlightHandlerMixin#beloong$isEligible` 等价）：
   `fixEnabled` → `ServerFlightHandler.stableHover`（**唯一读取点**）→ `paused || isPassenger() || hasEffect(LEVITATION)` → `DragonStateProvider.isDragon` → `FlightData.isWingsSpread() && hasFlight()` → `!ServerFlightHandler.isSpin()` → `!jumping && !sneak`。
   ⚠️ **不再包含 `!isGliding`** —— 滑翔从"被排除"改为"被接管（只去重力）"。
5. 三个分流谓词都先要求 `flightLevel >= 1.0`（决策 D4）；`isGlideGravitySuppressed` 用 `isGliding`；`isAirStableHover` 用 `isFlying`；`isWaterStableFlight` 用 `isInWater() && !canStandOnFluid(...)`。
6. 类 javadoc 写明约束 A 与 B。

**Verification:**
```
cd D:\Minecraft\BeLoong-Core && .\gradlew.bat compileJava --console=plain
```
期望：`BUILD SUCCESSFUL`，无新增警告。

---

### Task 2: `ClientFlightHandlerMixin` 的 HEAD 改为状态机

**依赖：** Task 1

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 把现有 `beloong$clearZeroGravity`（`at = @At("HEAD")`）改为按状态决定：

```java
@Inject(method = "flightControl", at = @At("HEAD"), remap = false)
private static void beloong$syncGravityBeforeFlight(CallbackInfo ci) {
    LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) { beloong$setZeroGravity(null, false); return; }   // 见步骤 2
    double level = ModAttributes.getFlightLevel(player);
    boolean suppress = StableFlightState.isEligible(player,
                                Config.FIX_STABLE_HOVER.get(), Minecraft.getInstance().isPaused(),
                                player.input.jumping, player.input.shiftKeyDown)
                       && StableFlightState.isGlideGravitySuppressed(player, level);
    beloong$setZeroGravity(player, suppress);
}
```

2. 保持「无条件执行、不得放在任何 early-return 之后」的性质——否则用户关掉配置时修饰符会残留、重力永久为 0（这是原设计的硬要求，注释里要保留）。为处理 `player == null`，把 `beloong$setZeroGravity` 改成接受 `@Nullable LocalPlayer` 并在内部判空返回。
3. 保留原注释并补充：**为什么滑翔要在 HEAD 挂、悬停只能在 TAIL 挂**（DS `:408` 自己的重力项 + `:481/:525` 依赖真实重力）。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java -Pattern 'syncGravityBeforeFlight|isGlideGravitySuppressed'
```
期望：编译通过；grep 各命中一次。

---

### Task 3: `ClientFlightHandlerMixin` 的 TAIL 改为三路分流

**依赖：** Task 2

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 用 `StableFlightState` 替换本地谓词：**删除** `beloong$isEligible` 与 `beloong$stableWaterHover`。
2. TAIL 主体改为：

```java
LocalPlayer player = Minecraft.getInstance().player;
if (player == null) return;
double level = ModAttributes.getFlightLevel(player);
if (!StableFlightState.isEligible(player, Config.FIX_STABLE_HOVER.get(),
        Minecraft.getInstance().isPaused(), player.input.jumping, player.input.shiftKeyDown)) {
    return;
}

// 路径 1：滑翔 → 只去重力，其余一律不碰（重力已在 HEAD 置 0）
if (StableFlightState.isGlideGravitySuppressed(player, level)) {
    return;
}

// 路径 2：空中稳定悬停 → 锁高度 + 取消重力
if (StableFlightState.isAirStableHover(player, level)) {
    ClientFlightHandlerAccessor.beloong$setAy(0.0);
    Vec3 delta = player.getDeltaMovement();
    player.setDeltaMovement(delta.x, 0, delta.z);
    beloong$setZeroGravity(player, true);
    return;
}

// 路径 3：水中稳定飞行 → 同路径 2（水阻力由 PlayerFluidInteractionMixin 去掉）
if (StableFlightState.isWaterStableFlight(player, level)) {
    ...同上...
    return;
}

// 路径 4：非稳定悬停（level < 1，仅空中）→ 追加 -g
...保持现有实现与 wasFlying 守卫...
```

3. 三条"锁定"路径的代码相同，抽成一个 `private static void beloong$lockAltitude(LocalPlayer player)` 以免重复。
4. 更新类 javadoc：滑翔**不再**是"排除"，而是"只去重力"；水中说明阻力已由另一个 mixin 处理；补上约束 A 的说明。
5. ⚠️ 保留现有注释「水平方向（ax/az）刻意完全不碰」——那是上一轮用户裁定的结果，不要回退。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java -Pattern 'beloong\$isEligible|beloong\$stableWaterHover|isGliding\(player\)'
```
期望：编译通过；三条 grep **均无命中**（旧谓词已删、`isGliding` 直接调用已改由谓词承担）。

---

### Task 4: 新增原版 mixin `PlayerFluidInteractionMixin`

**依赖：** Task 1

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/minecraft/PlayerFluidInteractionMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`

**Steps:**
1. 建类（**按项目约定放 `mixin/minecraft/`**）：

```java
@Mixin(Player.class)
public abstract class PlayerFluidInteractionMixin {
    private static boolean beloong$shouldIgnoreFluids(Player player) {
        if (!(player instanceof LocalPlayer local)) return false;      // 只处理本地玩家
        double level = ModAttributes.getFlightLevel(local);
        return StableFlightState.isEligible(local, Config.FIX_STABLE_HOVER.get(),
                    Minecraft.getInstance().isPaused(),
                    local.input.jumping, local.input.shiftKeyDown)
               && StableFlightState.isWaterStableFlight(local, level);
    }

    @Inject(method = "isAffectedByFluids", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$ignoreWaterDrag(CallbackInfoReturnable<Boolean> cir) {
        if (beloong$shouldIgnoreFluids((Player) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "isPushedByFluid", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$ignoreFluidPush(CallbackInfoReturnable<Boolean> cir) {
        if (beloong$shouldIgnoreFluids((Player) (Object) this)) cir.setReturnValue(false);
    }
}
```

2. `remap = false` 必须写在 **`@Mixin` 与每个 `@Inject` 上**（本项目对原版目标的硬约定；漏写会让 sponge-mixin 注解处理器报 `Unable to locate obfuscation mapping`）。
3. `beloong.mixins.json` 的 **`client`** 列表加一行 `"minecraft.PlayerFluidInteractionMixin"`（注意前缀是 `minecraft.`）。
4. 类 javadoc 写明：这是需求 2 的**唯一**实现途径（`WATER_MOVEMENT_EFFICIENCY` 等属性均不可行，已核实）；并写明**约束 A**（本方法成为输出后，任何谓词都不得再读它）。

**Verification:**
```
.\gradlew.bat build --console=plain
Select-String -Path src\main\resources\beloong.mixins.json -Pattern 'PlayerFluidInteractionMixin'
```
期望：`BUILD SUCCESSFUL`，警告数仍为既有 3 条；grep 命中一行。

---

### Task 5: 静态终检

**依赖：** Task 1–4

**Files:** 无改动

**Steps:**
1. 原版 mixin 审计（应恰好 4 个，其中 3 个是既有的）：
```
Select-String -Path (Get-ChildItem -Recurse -File src\main\java\*.java).FullName -Pattern '@Mixin\('
```
逐条核对：新增的应只有 `PlayerFluidInteractionMixin`（`Player`）；既有的 `VaultBlockEntity.Server` / `Climate.ParameterList` / `BiomeSource` 不在本次范围。
2. 确认 `StableFlightState` 内**没有** `Minecraft` / `Config` / `isAffectedByFluids` 的引用：
```
Select-String -Path src\main\java\com\zonlong\beloong\flight\StableFlightState.java -Pattern 'Minecraft|Config\.|isAffectedByFluids'
```
期望：**无命中**（约束 A 与"端无关"都靠这条守住）。
3. 全项目 `LOGGER.*` 无 CJK（沿用上一轮那段括号平衡扫描）。
4. 全量构建：
```
.\gradlew.bat build --console=plain
```
期望：`BUILD SUCCESSFUL`，警告 3 条。

**Verification:** 上述三条 grep + build 成功。

---

### Task 6: 实机验收（需用户参与）

**依赖：** Task 5

**Files:** 无改动

**Steps:** 按设计文档 §6 执行

| # | 场景 | 期望 |
|---|---|---|
| 3 | `stable_hover=true` + `level≥1` + 冲刺滑翔 | 平视**不再缓慢下沉**；俯冲仍能加速；抬头仍能爬升 |
| 4 | `stable_hover=false`（阴性对照） | 滑翔与未装本模组一致（DS 原版含重力） |
| 5 | `level=0` + 滑翔 | **仍会下沉**（非稳定语义未被绕过） |
| 6 | `stable_hover=true` + `level≥1`，入水飞行 | **无水的减速**、**无水流推动**、高度锁住；水平手感与同等条件的空中一致 |
| 7 | `level=0` 或 `stable_hover=false`，入水 | 与 DS 原版一致（正常阻力/游泳） |
| 8 | 冲刺入水残余项观察 | 是否因"游泳上浮"块而有轻微上浮；有则记录（设计 §2.4 的待定增量） |
| 9 | 回归：上一轮四组对照 ①②③④ | 仍成立 |

**Verification:**
1. 启动日志中 `PlayerFluidInteractionMixin` 正常 Mixing，无注入错误。
2. 场景 3/6 的读数靠眼睛与手感；场景 5/7 是阴性对照，必做。
3. 场景 8 若出现明显上浮，记录现象，再决定是否追加 `updateSwimming` 处理（设计 §2.4）。

---

## 任务依赖图

```
T1 ──┬─> T2 ──> T3 ──┐
     └─> T4 ──────────┼─> T5 ──> T6
                      │
（T2/T3 与 T4 可并行，但按串行执行以保持每步可编译）
```

## 交付物

- 新增 2 个文件（`flight/StableFlightState.java`、`mixin/minecraft/PlayerFluidInteractionMixin.java`）
- 修改 2 个文件（`ClientFlightHandlerMixin.java`、`beloong.mixins.json`）
- 原版 mixin 由 3 个（既有）变为 **4 个**；DS mixin 数量不变
