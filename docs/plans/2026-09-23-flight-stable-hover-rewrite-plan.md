# 稳定悬停修复重构 · 实施计划

**目标：** 用 GRAVITY 属性替代原版 mixin 实现稳定悬停的重力取消，并把判定来源从「客户端读 DS 的 SERVER 配置」改为「服务端同步」，从而消除 F-1/F-3/F-5/F-6/F-7 并修正 F-2/F-4。

**架构：** 删掉 `LivingEntityStableHoverMixin`（唯一改原版的 mixin）；改由 DS 侧的 `ClientFlightHandlerMixin` 在 `flightControl` 的 HEAD 摘除 / TAIL 挂载一个 `ADD_MULTIPLIED_TOTAL = -1.0` 的瞬时属性修饰符，使 `LivingEntity.travel` 读到重力 0；`stableHover` 值由服务端在读登录事件时通过新增的 BeLoong payload 同步给客户端。

**上游文档：**
- 设计：`docs/plans/2026-09-23-flight-stable-hover-rewrite-design.md`
- 审查（冲突 F-1..F-9）：`docs/reviews/2026-09-23-flight-system-conflicts-and-creative-flight-comparison.md`

**与设计文档的一处细化（本计划新增，需在实施时落实）：**

设计文档 §2.5 写「`flightLevel < 1` 分支去掉 `noMoveInput`」。逐行核对 DS 后发现需**更精确**：DS 只在两处施加 `-g`——移动分支（`ClientFlightHandler.java:499`，`stableHover=true` 时）与 `else` 分支中 `wasFlying` 为真的情况（`:525`）。因此忠实模拟应为：

```java
} else if (flightLevel < 1.0 && !ServerFlightHandler.isGliding(player)
        && (!noMoveInput || ClientFlightHandler.wasFlying)) {
```

`ClientFlightHandler.wasFlying` 是 `public static boolean`（`ClientFlightHandler.java:139`），可直接读。若只用 `!noMoveInput`，起飞首 tick（`wasFlying == false`）会被多减一个 `-g`，出现一次轻微下沉。

**验证基线：** 本项目无测试套件（`memory/project-context.md`），因此每个任务的验证是 **编译门 + grep 静态探针**，最终由 **实机四组场景对照**（设计文档 §7）验收。不写自动化测试。

---

### Task 1: 新增客户端飞行状态缓存

**依赖：** 无

**Files:**
- Create: `src/main/java/com/zonlong/beloong/network/ClientFlightStatusCache.java`

**Steps:**
1. 照 `treasure/ClientTreasureCache.java` 的写法建一个极简静态持有类：`INSTANCE` 单例、`private volatile boolean stableHover = false;`、`get()` / `loadFromSync(boolean)`。
2. 默认值必须是 `false`（保守：payload 未送达时不干预，退化为 DS 原版行为）。

**Verification:**
```
cd D:\Minecraft\BeLoong-Core && .\gradlew.bat compileJava --console=plain
```
期望：`BUILD SUCCESSFUL`，无新增警告。

---

### Task 2: 新增同步 payload

**依赖：** Task 1

**Files:**
- Create: `src/main/java/com/zonlong/beloong/network/FlightStatusSyncPayload.java`

**Steps:**
1. 照 `network/TreasureSyncPayload.java` 结构写 `record FlightStatusSyncPayload(boolean stableHover) implements CustomPacketPayload`。
2. `TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "flight_status_sync"))`。
3. `STREAM_CODEC` 用 `ByteBufCodecs.BOOL` + `StreamCodec.composite(...)`，缓冲区类型 `RegistryFriendlyByteBuf`。
4. `handleClient` 直接写 `ClientFlightStatusCache.INSTANCE.loadFromSync(payload.stableHover())`（默认在主线程执行，无需 `enqueueWork`）。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
```

---

### Task 3: 在 BeLoongCore 注册 payload 并在登录时发送

**依赖：** Task 2

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

**Steps:**
1. `:131-135` 的 `RegisterPayloadHandlersEvent` 监听里，在现有 `playToClient(TreasureSyncPayload...)` 之后追加 `playToClient(FlightStatusSyncPayload.TYPE, FlightStatusSyncPayload.STREAM_CODEC, FlightStatusSyncPayload::handleClient)`。
2. `:199` `onPlayerLogin(PlayerEvent.PlayerLoggedInEvent)` 末尾追加：
   ```java
   PacketDistributor.sendToPlayer(player,
           new FlightStatusSyncPayload(ServerFlightHandler.stableHover));
   ```
3. 新增 import：`by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler`、`com.zonlong.beloong.network.FlightStatusSyncPayload`。
4. 加一行 `LOGGER.debug`（英文）记录发送值，便于 T8 在专用服务器核对。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\BeLoongCore.java -Pattern 'FlightStatusSyncPayload'
```
期望：编译通过；grep 命中注册与发送两处。

---

### Task 4: `ModAttributes.getFlightLevel` 属性缺失时打一次性 WARN

**依赖：** 无（可与 T1–T3 并行，但按串行执行）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/registry/ModAttributes.java`

**Steps:**
1. 在 `:66-78` 的属性查找失败分支（`raw == null`）里加一条一次性 `BeLoongCore.LOGGER.warn(...)`，英文 ASCII，格式 `[BeLoong] flight: ...`，说明 `dragonsurvival:flight_level` 未注册、稳定悬停将被禁用。
2. 加一个 `private static boolean warnedMissing;` 保证只打一次（`cachedFlightLevel` 仍保持 `null` 以允许重试）。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\registry\ModAttributes.java -Pattern 'warn'
```
期望：编译通过；grep 命中 warn 与 warnedMissing 两处。

---

### Task 5: `ClientFlightHandlerMixin` 加常量与统一判定方法（不改注入逻辑）

**依赖：** Task 1（读缓存）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 加两个常量：
   ```java
   private static final ResourceLocation beloong$ZERO_GRAVITY_ID =
           ResourceLocation.fromNamespaceAndPath("beloong", "stable_hover_zero_gravity");
   private static final AttributeModifier beloong$ZERO_GRAVITY =
           new AttributeModifier(beloong$ZERO_GRAVITY_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
   ```
2. 加 `private static boolean beloong$shouldStabilize(LocalPlayer p)`，条件严格照设计文档 §2.4，其中 `syncedStableHover` 读 `ClientFlightStatusCache.INSTANCE.get()`（**不再读 `ServerFlightHandler.stableHover`**）。`FlightData` 只读一次并复用。
3. 加 `private static boolean beloong$stableWaterHover(LocalPlayer p)`：`isInWater() && isAffectedByFluids() && !canStandOnFluid(level().getFluidState(blockPosition()))`。
4. 加 `private static void beloong$setZeroGravity(LocalPlayer p, boolean on)`：`AttributeInstance g = p.getAttribute(Attributes.GRAVITY);` 判空后 `addOrUpdateTransientModifier` / `removeModifier(beloong$ZERO_GRAVITY_ID)`。
5. 本任务**只加方法，不改任何 `@Inject`**，保持行为不变以便单独验证编译。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
```
期望：编译通过（此时新方法尚未被调用，会有 unused 提示但不应报错）。

---

### Task 6: `flightControl` 加 HEAD 注入——无条件摘除修饰符

**依赖：** Task 5

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 在现有 `@Inject(method = "flightControl", at = @At("TAIL"), remap = false)` **之前**新增：
   ```java
   @Inject(method = "flightControl", at = @At("HEAD"), remap = false)
   private static void beloong$clearZeroGravity(CallbackInfo ci) {
       LocalPlayer player = Minecraft.getInstance().player;
       if (player != null) beloong$setZeroGravity(player, false);
   }
   ```
2. **必须无条件执行**（在任何 early-return 之前），否则 `Config.FIX_STABLE_HOVER` 关掉时会残留修饰符。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java -Pattern 'at = @At\("HEAD"\)'
```
期望：编译通过；grep 命中 HEAD 注入。

---

### Task 7: 改写 TAIL 主体——统一判定 + 挂载修饰符

**依赖：** Task 6

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 把 `beloong$fixStableHoverDrift`（现 `:59-157`）整体替换为：
   ```java
   LocalPlayer player = Minecraft.getInstance().player;
   if (player == null || !beloong$shouldStabilize(player)) return;

   Input movement = player.input;
   boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;

   if (noMoveInput) {
       ClientFlightHandlerAccessor.beloong$setAx(0.0);
       ClientFlightHandlerAccessor.beloong$setAz(0.0);
   }
   ClientFlightHandlerAccessor.beloong$setAy(0.0);
   Vec3 delta = player.getDeltaMovement();
   player.setDeltaMovement(delta.x, 0, delta.z);

   beloong$setZeroGravity(player, true);
   ```
2. 删除原 `:104-117` 的水中分叉、`:135-147` 的滑翔分叉、`:148-155` 的非稳定分支——水的处理已由 `beloong$shouldStabilize` 内的 `beloong$stableWaterHover` 覆盖；滑翔与旋转已被判定排除。
3. 更新类头 javadoc，写明：不再读 `ServerFlightHandler.stableHover`（改用同步值）、滑翔已排除、重力由属性实现。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java -Pattern 'look.scale|ServerFlightHandler.stableHover'
```
期望：编译通过；grep **无命中**（滑翔接管与静态配置读取都已消失）。

---

### Task 8: 新增「非稳定悬停模拟」分支（F-4）

**依赖：** Task 7

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 在 `beloong$fixStableHoverDrift` 的早退**之前**插入独立判断（不能用 `shouldStabilize`，它的 `flightLevel >= 1` 会把这一支排除）：
   ```java
   if (beloong$isNonStableHoverFallback(player)) {
       Vec3 d = player.getDeltaMovement();
       player.setDeltaMovement(d.x, d.y - player.getAttributeValue(Attributes.GRAVITY), d.z);
       return;
   }
   ```
2. `beloong$isNonStableHoverFallback` 的条件 = `shouldStabilize` 的前置项（配置/同步值/龙/展翅/飞行能力/非旋转/非滑翔/在飞或水中/无垂直输入），但 `flightLevel < 1.0`，且 **`(!noMoveInput || ClientFlightHandler.wasFlying)`**（见文档开头与设计文档的细化）。
3. 保留原有中文注释，但把 `:150-151`「总重力达到 -(gravity×2)」一句按核对结果改写：DS 在移动分支给 `-g`（`:499`）、`else`+`wasFlying` 给 `-g + ay`（`:525`，其中 `ay ≥ 0.99g ⇒ ≈0`），追加 `-g` 后分别为 `-2g` 与 `≈-1g`，两种情形都等于 DS 的 `stableHover=false`。

**Verification:**
```
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\mixin\dragonsurvival\ClientFlightHandlerMixin.java -Pattern 'wasFlying'
```
期望：编译通过；grep 命中 `wasFlying`。

---

### Task 9: 删除 `LivingEntityStableHoverMixin` 并从 mixin 配置移除

**依赖：** Task 7、Task 8（新能力已就位后才拆旧的）

**Files:**
- Delete: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/LivingEntityStableHoverMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`

**Steps:**
1. 删除该文件。
2. 从 `beloong.mixins.json` 的 `client` 数组移除 `"dragonsurvival.LivingEntityStableHoverMixin"` 一行（当前 `:8`）。
3. 确认 `minecraft.DreadKingRitualTriggerMixin`（`:48`）等其它条目未被波及。

**Verification:**
```
.\gradlew.bat build --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\mixin -Pattern 'LivingEntityStableHover' -Recurse
Select-String -Path src\main\resources\beloong.mixins.json -Pattern 'LivingEntityStableHover'
```
期望：`build` 成功；两条 grep **均无命中**。

---

### Task 10: 更新 `Config.FIX_STABLE_HOVER` 的说明

**依赖：** 无

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java:21`

**Steps:**
1. 改写 `.comment(...)`：说明它是本模组的总开关，实际生效还需 DS 的 `stable_hover = true` **且** `dragonsurvival:flight_level >= 1`；并说明滑翔不在此修复范围内。
2. 检查 `assets/beloong/lang/zh_cn.json` 与 `en_us.json` 里是否已有该配置项的 tooltip 键；若新增了配置键则必须同步补双语（项目硬约定）。

**Verification:**
```
.\gradlew.bat build --console=plain
python tools\check_lang_parity.py
```
期望：build 成功；语言键中英对齐（脚本存在，见 `tools/`）。

---

### Task 11: 静态终检 + 全量构建

**依赖：** 全部前置任务

**Files:** 无改动

**Steps:**
1. 全项目确认**对原版零 mixin**：
   ```
   Select-String -Path (Get-ChildItem -Recurse -File src\main\java -Filter *.java).FullName -Pattern '@Mixin'
   ```
   逐条核对，不应再出现 `LivingEntity` / `Player` / 其它原版类（现有的 `PossibleBiomesFilterMixin`、`ParameterListAccessor`、`CloneParameterListMixin`、`DreadKingRitualTriggerMixin` 是既有例外，不在本次范围）。
2. 确认全项目无中文日志（项目硬约定：`LOGGER.*` 只能英文 ASCII）。
3. 全量构建：
   ```
   .\gradlew.bat build --console=plain
   ```
   期望：`BUILD SUCCESSFUL`，警告数仍为既有 3 条（`PossibleBiomesFilterMixin` ×1、`ParameterListAccessor` ×2）。

**Verification:** 上述两条 grep + build 成功。

---

### Task 12: 实机验收（需用户参与）

**依赖：** Task 11

**Files:** 无改动

**Steps:** 按设计文档 §7 执行：

| # | 场景 | 期望 |
|---|---|---|
| A | DS `stable_hover=false` | 飞行手感与"未装本模组"完全一致（**阴性对照，必做**） |
| B | `stable_hover=true` + `flight_level=0` | 静止与移动时竖直行为都等于 DS 的 `stableHover=false` |
| C | `stable_hover=true` + `flight_level=1` | 无输入时高度恒定；跳跃/下潜仍由 DS 驱动（0.4 / −0.5） |
| D | 冲刺滑翔 | 与 DS 原版一致（本模组不介入） |

**Verification:**
1. 启动日志中 `LivingEntityStableHoverMixin` 不再出现；`Mixing dragonsurvival.*` 无新增错误。
2. 悬停时 `/attribute` 或临时日志确认 `Attributes.GRAVITY` = 0，脱离后恢复 0.08。
3. 专用服务器：核对 T3 加的 debug 日志，确认客户端收到的值与 `dragonsurvival-server.toml` 的 `stable_hover` 一致，并在该环境重跑场景 C。

---

## 任务依赖图

```
T1 ──> T2 ──> T3 ─┐
                  ├─> T5 ──> T6 ──> T7 ──> T8 ──> T9 ──┐
T4 ───────────────┘                                    ├─> T11 ──> T12
T10 ───────────────────────────────────────────────────┘
```

## 交付物

- 删除 1 个文件、新增 2 个文件、修改 5 个文件
- 全项目对原版 **零 mixin**
- 冲突收敛：F-1/F-3/F-5/F-6/F-7 消除；F-2 按 (a) 排除滑翔；F-4 修正；F-8/F-9 一并处理
