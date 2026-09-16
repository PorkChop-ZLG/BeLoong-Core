# 爪牙剑进度触发器 实施计划

**Goal:** 让龙形态玩家在爪牙剑被实际换入主手时触发一个可复用的进度判据，并用父/子两个进度教会玩家「爪牙槽里的剑能用来击杀生物」。

**Architecture:** `ModCriteria` 用 `DeferredRegister<CriterionTrigger<?>>`（`Registries.TRIGGER_TYPE`）注册两个 `SimpleCriterionTrigger`；`ClawSwordAdvancementHandler` 挂 **3 个事件**——`AttackEntityEvent` 与 `LivingIncomingDamageEvent` 触发换手判据（共同覆盖**玩家攻击**与**龙技能 `DamageEffect(use_claw)`** 两条换手路径），`LivingDeathEvent` 触发击杀判据；判定依据是 DS `ClawInventoryData` 的 **public 字段** `switchedTool` / `switchedToolSlot`。两个进度 JSON 以 `parent` 表达「先换手 → 后击杀」。**零 mixin。**

**Approach:** 方案 A（父/子双进度）。设计见 `docs/plans/2026-09-16-claw-sword-advancement-design.md`（**v2**，含 F1–F12 事实证据表与 D1–D9 决策）。

> **v2 修订（2026-09-16）**：用户裁定龙技能 `DamageEffect(use_claw=true)` 的换手路径**允许且应当触发**。
> 据此撤回 D1、删除 D7，换手判据改由两个事件共同覆盖。
> 附带收益：`LivingIncomingDamageEvent` 的触发点就在 `LivingEntity#hurt()` 内部，
> 与 Mixin 注入顺序无关 ⇒ **F6 时序假设不再承重**，本计划不再需要回退梯度。
> 另修正一处 v1 错误：**NeoForge 21.1.236 已移除 `LivingHurtEvent`**，正确事件是 `LivingIncomingDamageEvent`。

**背景事实（已对锁定构件验证）：** DS 在 `neoforge.mods.toml` 里是 `type="required"` + `ordering="AFTER"`，因此**不需要** `ModList.get().isLoaded("dragonsurvival")` 守卫，且 DS 保证先于本模组加载。

**本项目无测试套件**（见 `memory/project-context.md`），因此本计划的验证手段是：
`gradlew` 构建 + JSON 语法/键一致性静态自检 + 实机行为确认，而非单元测试。
每个任务的「Verification」给出**可直接执行的命令**。

**环境前置（已实测，2026-09-16）：**
- 基线构建**已验证为绿**：`.\gradlew.bat build` → `BUILD SUCCESSFUL`，`EXIT=0`（3 秒，configuration cache 命中）。
- 在 DSH 沙箱的 `workspace-write` 模式下 `gradlew` **完全跑不起来**：
  `java.io.FileNotFoundException: C:\Users\D_Ink\.gradle\wrapper\dists\...\gradle-9.2.1-bin.zip.lck (拒绝访问)`
  —— Gradle 的 wrapper 与缓存位于 `C:\Users\D_Ink\.gradle`，在 `D:\Minecraft` 工作区之外。
  ⇒ 本计划中所有 `gradlew` 步骤需要在**放宽沙箱权限**（`danger-full-access`）下执行，
  或由用户在自己的终端运行。

---

### Task 1: 两个判据类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/compat/dragonsurvival/ClawSwordSwapTrigger.java`
- Create: `src/main/java/com/zonlong/beloong/compat/dragonsurvival/ClawSwordKillTrigger.java`

**Steps:**
1. 两个文件除类名外逐字同形，照 DS 自己的 `common/criteria/BeDragonTrigger.java`（已在参考源核对 1.21.1 写法）：
   ```java
   package com.zonlong.beloong.compat.dragonsurvival;

   import com.mojang.serialization.Codec;
   import com.mojang.serialization.codecs.RecordCodecBuilder;
   import net.minecraft.advancements.critereon.ContextAwarePredicate;
   import net.minecraft.advancements.critereon.EntityPredicate;
   import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
   import net.minecraft.server.level.ServerPlayer;
   import org.jetbrains.annotations.NotNull;

   import java.util.Optional;

   /** 在爪牙槽中的剑被实际换入主手时触发。 */
   public class ClawSwordSwapTrigger extends SimpleCriterionTrigger<ClawSwordSwapTrigger.Instance> {
       public void trigger(ServerPlayer player) {
           this.trigger(player, instance -> true);
       }

       @Override
       public @NotNull Codec<Instance> codec() {
           return Instance.CODEC;
       }

       public record Instance(Optional<ContextAwarePredicate> player)
               implements SimpleCriterionTrigger.SimpleInstance {
           public static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                   EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
           ).apply(instance, Instance::new));
       }
   }
   ```
2. `ClawSwordKillTrigger` 同形，仅类名与 javadoc 不同（javadoc 写「在爪牙槽中的剑换入主手期间击杀生物时触发」）。
3. 中文 javadoc，与本项目其余源码一致。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core; .\gradlew.bat compileJava --console=plain
```
期望 `EXIT=0`。

---

### Task 2: 判据注册器

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ModCriteria.java`

**Steps:**
1. 照 `ModAttributes` 的既有形态（`DeferredRegister.create(Registries.XXX, "beloong")`）：
   ```java
   public class ModCriteria {
       public static final DeferredRegister<CriterionTrigger<?>> REGISTRY =
               DeferredRegister.create(Registries.TRIGGER_TYPE, "beloong");

       public static final Supplier<ClawSwordSwapTrigger> CLAW_SWORD_SWAP =
               REGISTRY.register("claw_sword_swap", ClawSwordSwapTrigger::new);

       public static final Supplier<ClawSwordKillTrigger> CLAW_SWORD_KILL =
               REGISTRY.register("claw_sword_kill", ClawSwordKillTrigger::new);

       private ModCriteria() {}
   }
   ```
2. **注意**：注册名 `claw_sword_swap` / `claw_sword_kill` 必须与 Task 5、6 的 JSON 中
   `"trigger": "beloong:<name>"` **逐字一致**——这是本任务唯一的硬约束。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core; .\gradlew.bat compileJava --console=plain
```

---

### Task 3: 事件处理器（3 个事件）

**Files:**
- Create: `src/main/java/com/zonlong/beloong/compat/dragonsurvival/ClawSwordAdvancementHandler.java`

**Steps:**
1. 实例方法 + `@SubscribeEvent`，由 `BeLoongCore` 构造期显式注册（与 `StructureEffectHandler`、`ManaLossHandler` 一致，不用 `@EventBusSubscriber`）。
2. 抽一个私有静态辅助供三处共用，避免逻辑漂移：
   ```java
   /** 该玩家此刻是否"爪牙槽中的剑正被换在主手" */
   private static boolean hasClawSwordSwappedIn(ServerPlayer player) {
       ClawInventoryData data = ClawInventoryData.getData(player);
       return data.switchedTool
               && data.switchedToolSlot == ClawInventoryData.Slot.SWORD.ordinal();
   }
   ```
3. `onAttack(AttackEntityEvent)` —— 攻击路径、**换手发生的瞬间**（覆盖"打空"）：
   `event.getEntity() instanceof ServerPlayer player` 且 `hasClawSwordSwappedIn(player)`
   → `ModCriteria.CLAW_SWORD_SWAP.get().trigger(player)`
4. `onIncomingDamage(LivingIncomingDamageEvent)` —— **主力路径**，覆盖攻击 + 龙技能：
   ⚠️ **检的是攻击者的换手状态，不是受害者的**（`ClawInventoryData` 挂在玩家身上，
   而事件主体是挨打的一方）：
   ```java
   if (event.getSource().getEntity() instanceof ServerPlayer attacker
           && hasClawSwordSwappedIn(attacker)) {
       ModCriteria.CLAW_SWORD_SWAP.get().trigger(attacker);
   }
   ```
5. `onDeath(LivingDeathEvent)` —— 击杀判据，**同样检攻击者**，条件与第 4 步**完全相同**。
   ⚠️ **不要**加 `DamageTypes.PLAYER_ATTACK` 之类的伤害类型限制——v2 已删除该限制，
   龙技能击杀必须计入：
   ```java
   if (event.getSource().getEntity() instanceof ServerPlayer attacker
           && hasClawSwordSwappedIn(attacker)) {
       ModCriteria.CLAW_SWORD_KILL.get().trigger(attacker);
   }
   ```
6. ⚠️ **必须用 `LivingIncomingDamageEvent`**：NeoForge 21.1.236 **已移除 `LivingHurtEvent`**
   （该类在 `21.1.236-sources.jar` 中不存在），写成 `LivingHurtEvent` 会**编译失败**。
7. 三个方法都写中文 javadoc，说明**为什么**这么判，并注明两点：
   - 换手判据为何由两个事件共同触发（互补：`AttackEntityEvent` 是"换手瞬间"的最近似表达
     且覆盖打空；`LivingIncomingDamageEvent` 覆盖技能路径且不依赖注入顺序。重复触发幂等无害）
   - 击杀判据为何**不**加伤害类型限制（v2：技能路径必须计入；顺序由
     `LivingIncomingDamageEvent` 先于 `LivingDeathEvent` 保证）
8. 不要缓存 `Slot.SWORD.ordinal()` 为 `static` 字段，按无状态写法保持一致。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat compileJava --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\compat\dragonsurvival\ClawSwordAdvancementHandler.java -Pattern 'LivingHurtEvent'
```
期望：编译 `EXIT=0`，且第二条 grep **无命中**（证明没有误用已移除的事件）。
`LivingIncomingDamageEvent` 拼错或用了 `LivingHurtEvent` 都会在这里或编译期暴露。

---

### Task 4: 接线到模组主类

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

**Steps:**
1. 构造函数的「注册阶段」区块加一行（紧邻 `ModAttributes.REGISTRY.register(modEventBus);`）：
   ```java
   ModCriteria.REGISTRY.register(modEventBus);   // 进度判据
   ```
2. 「事件处理器」区块加一行（紧邻其余 `NeoForge.EVENT_BUS.register(new X())`）：
   ```java
   NeoForge.EVENT_BUS.register(new ClawSwordAdvancementHandler());
   ```
3. 补 `import`。**不要**加 `ModList.get().isLoaded("dragonsurvival")` 守卫（DS 是 required 依赖）。
4. 只改这两处，不动其余注册顺序。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core; .\gradlew.bat build --console=plain
```
期望 `EXIT=0`。这是 **Java 侧的构建门**——通过后才动资源。

---

### Task 5: 根进度 JSON

**Files:**
- Create: `src/main/resources/data/beloong/advancement/claw_sword/swap.json`

**Steps:**
1. 内容（纯 JSON，**不要**写注释）：
   ```json
   {
     "criteria": { "swap": { "trigger": "beloong:claw_sword_swap" } },
     "display": {
       "title": { "translate": "advancements.beloong.claw_sword.swap.title" },
       "description": { "translate": "advancements.beloong.claw_sword.swap.description" },
       "icon": { "id": "minecraft:diamond_sword", "count": 1 },
       "frame": "task",
       "background": "minecraft:textures/gui/advancements/backgrounds/stone.png",
       "show_toast": true,
       "announce_to_chat": false,
       "hidden": false
     },
     "requirements": [["swap"]],
     "sends_telemetry_event": true
   }
   ```
2. `{"id": ..., "count": 1}` 是 1.20.5+ 的 item stack 写法（已对照 DS 的 `root.json` 确认）。
3. 图标/背景是**原版占位**（D3），跑通后要删。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
Get-Content src\main\resources\data\beloong\advancement\claw_sword\swap.json -Raw | ConvertFrom-Json | Out-Null; Write-Host "JSON OK"
# ⚠️ 若报 "Invalid object passed in"，是 PowerShell 5.1 默认按 GBK 读 UTF-8 导致，改用：
# [System.IO.File]::ReadAllText("$PWD\src\main\resources\data\beloong\advancement\claw_sword\swap.json",[System.Text.Encoding]::UTF8) | ConvertFrom-Json | Out-Null
```

---

### Task 6: 子进度 JSON

**Files:**
- Create: `src/main/resources/data/beloong/advancement/claw_sword/kill.json`

**Steps:**
1. 内容（注意 `parent` 必须与 Task 5 的进度 ID 一致：`beloong:claw_sword/swap`）：
   ```json
   {
     "parent": "beloong:claw_sword/swap",
     "criteria": { "kill": { "trigger": "beloong:claw_sword_kill" } },
     "display": {
       "title": { "translate": "advancements.beloong.claw_sword.kill.title" },
       "description": { "translate": "advancements.beloong.claw_sword.kill.description" },
       "icon": { "id": "minecraft:iron_sword", "count": 1 },
       "frame": "task",
       "show_toast": true,
       "announce_to_chat": false,
       "hidden": false
     },
     "requirements": [["kill"]],
     "sends_telemetry_event": true
   }
   ```
2. 子进度**没有** `background`（只有根节点需要）。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
Get-Content src\main\resources\data\beloong\advancement\claw_sword\kill.json -Raw | ConvertFrom-Json | Out-Null; Write-Host "JSON OK"
# 同上：UTF-8 文件在 PS 5.1 下需 [System.IO.File]::ReadAllText(...,[System.Text.Encoding]::UTF8)
```
外加一致性检查——**JSON 里的 trigger 名必须与 Java 注册名逐字相同**：
```powershell
Select-String -Path src\main\resources\data\beloong\advancement\claw_sword\*.json -Pattern 'beloong:claw_sword_'
Select-String -Path src\main\java\com\zonlong\beloong\registry\ModCriteria.java -Pattern 'register\("claw_sword'
```
两边输出的 `claw_sword_swap` / `claw_sword_kill` 必须一一对应。

---

### Task 7: 语言文件

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. 两个文件各加 4 条，**键按字母序插入**（项目既有约定），中文/英文语气对齐现有条目：
   - `advancements.beloong.claw_sword.swap.title` = 爪中之刃 / Blade in the Claw
   - `advancements.beloong.claw_sword.swap.description` = 爪牙槽里的剑，会在你空手攻击时自动换入爪中 / A sword in your claw slot is swapped into your claw when you attack empty-handed
   - `advancements.beloong.claw_sword.kill.title` = 爪牙的锋芒 / Claw and Fang
   - `advancements.beloong.claw_sword.kill.description` = 用换入爪中的剑击杀一只生物 / Kill a mob with the sword swapped into your claw
2. 文案刻意写「**空手**攻击」——因为 `ToolUtils.shouldUseDragonTools` 要求手上**没有**真工具时才会换手，写成"龙不能持剑"是错的。

**Verification:** 交叉校验——JSON 里出现的每个 `translate` 键，必须在**两个**语言文件里都存在：
```powershell
cd D:\Minecraft\BeLoong-Core
$keys = Select-String -Path src\main\resources\data\beloong\advancement\claw_sword\*.json -Pattern 'advancements\.beloong\.[a-z_.]+' -AllMatches |
        ForEach-Object { $_.Matches.Value } | Sort-Object -Unique
$zh = ([System.IO.File]::ReadAllText("$PWD\src\main\resources\assets\beloong\lang\zh_cn.json",[System.Text.Encoding]::UTF8) | ConvertFrom-Json).PSObject.Properties.Name
$en = ([System.IO.File]::ReadAllText("$PWD\src\main\resources\assets\beloong\lang\en_us.json",[System.Text.Encoding]::UTF8) | ConvertFrom-Json).PSObject.Properties.Name
foreach ($k in $keys) { "$k  zh=$($zh -contains $k)  en=$($en -contains $k)" }
```
期望 4 个键两列全为 `True`。

---

### Task 8: 全量构建与静态自检

**Files:** 无（只验证）

**Steps:**
1. 全量构建。
2. 确认没有残留阻塞调用/未注册项：grep 新增的三个 Java 文件里没有 `// TODO`、没有 `getChunk(` 之类的阻塞 API。
3. 确认 `BeLoongCore` 的两处接线确实存在。
4. **不要**声称实机可用——见 Task 9。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain; Write-Host "EXIT=$LASTEXITCODE"
Select-String -Path src\main\java\com\zonlong\beloong\BeLoongCore.java -Pattern 'ModCriteria|ClawSwordAdvancementHandler'
```
期望 `EXIT=0`，且 grep 命中 2 行（各含 1 处 import + 1 处调用）。

---

### Task 9: 实机验证（**必须由用户执行——我无法在游戏内成为龙并挥剑**）

**Files:** 无

**Steps:**
1. 启动客户端：`.\gradlew.bat runClient`
2. 启动日志里确认**没有** advancement 解析报错（形如 `Failed to parse advancement`）。
   这一条我可以代跑：起客户端后 grep `run/logs/latest.log`。
3. 用户操作 —— **路径一：玩家攻击**
   a. 用 DS 的方式成为龙形态（龙之编辑器 / 对应命令）
   b. 把一把剑放进**爪牙槽**（背包界面左侧那个爪牙箭头展开）
   c. **空手**攻击一只生物 → 期望弹出根进度 toast「爪中之刃」
   d. 继续用它击杀该生物 → 期望弹出子进度 toast「爪牙的锋芒」
4. 用户操作 —— **路径二：龙技能（v2 新增覆盖面，必须一并验证）**
   a. 给龙配一个 `use_claw = true` 的技能
   b. 用该技能击杀一只生物 → 期望**同样**弹出两次 toast
   - 若路径一通过而路径二不通过 → 说明 `LivingIncomingDamageEvent` 没接上或
     `getSource().getEntity()` 对技能路径返回的不是玩家（复核 F12）
5. **关于 F6**：v2 后它**不再是承重假设**，只影响"攻击打空时是否也触发根进度"。
   - 若"命中触发、打空不触发" → 属**预期内的降级，无需任何回退**
   - 若**命中也不触发** → 问题不在 F6。依次检查：判据是否注册成功 →
     `switchedTool` 是否真为 true（加临时日志）→ DS 的换手是否被其他模组拦住
   - v1 计划里"改挂 `LivingHurtEvent`"的旧回退**已作废**（该事件在 21.1.236 不存在）

**Verification:** 路径一与路径二各出现两次 toast；无日志报错。

---

## 风险与回退

| 风险 | 概率 | 回退 |
|---|---|---|
| F6 时序假设不成立（`AttackEntityEvent` 早于换手） | 低（Mixin `HEAD` 语义明确） | **v2 后无需回退**：`LivingIncomingDamageEvent` 覆盖同一路径且不依赖注入顺序。F6 只影响"打空是否触发" |
| 误用已移除的 `LivingHurtEvent` | — | **编译期即失败**（该类在 21.1.236 不存在）；Task 3 已写明正确事件与 grep 检查 |
| 进度 JSON 字段名/格式在 1.21.1 有误 | 低（已对照 DS 自己的 `root.json`） | 起服务端看日志的 advancement 解析错误 |
| 判据注册时机问题（冻结注册表） | 极低 | 失败会在启动期响亮报错，不是静默失效 |

## 验证门（Gate）

全部满足才可声称完成：

1. `.\gradlew.bat build` → `EXIT=0`
2. 两个 JSON 通过 `ConvertFrom-Json`
3. JSON 的 trigger 名与 `ModCriteria` 的注册名逐字一致
4. 4 个 `advancements.beloong.*` 键在 zh_cn 与 en_us 里都存在
5. `ClawSwordAdvancementHandler` 中**不出现** `LivingHurtEvent`
6. 启动日志无 advancement 解析错误
7. **用户实机确认攻击路径与技能路径各两次 toast**（Task 9）——在此之前只能说
   "已实现，待实机验证"
