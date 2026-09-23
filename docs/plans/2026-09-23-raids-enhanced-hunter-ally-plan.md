# 让「袭击增强」的生物把龙之生存猎人视为同伴 —— 实施计划

**目标：** 让 Raids:Enhanced 的 4 个袭击者（`ZapperIllager` / `GolemOfLastResort` / `RaidBlimp` / `RaidDrill`）
把龙之生存的 6 个 `hunter_*` 生物视为同伴，不再把它们选为攻击目标。
**架构：** 把原版 `AbstractIllager` 里的 `#minecraft:illager_friends` 阵营判定**提升到 `Raider` 层**
（注入 `Entity#isAlliedTo` 的 HEAD + `instanceof Raider` 守卫），并单独注入飞艇的硬编码白名单 `RaidBlimp#checkTargetClass`。
**方案：** 设计文档的方案 1 —— 见 `docs/plans/2026-09-23-raids-enhanced-hunter-ally-design.md`（已批准）。

**关于"提交"：** 本项目由用户自行提交，本计划**不含** commit 步骤；每个任务末尾给出的是**验证命令**。

---

## 前置：本项目没有测试源

`src/test` / `src/gametest` **都不存在** ⇒ 本计划**不使用 TDD 的"先写失败测试"步骤**。
验证沿用本项目自己的门禁口径（`memory/learned-patterns.md`：运行期验证在本工程不稳定，批次门不能只依赖它）：

- **一级（静态，可靠）**：`gradlew build` + jar 内自检 + 构建产物核对
- **二级（实机）**：游戏内实测，且**必须包含负向对照**（否则无法区分"修好了"与"整个 AI 挂了"）

---

## 任务依赖

```
T1 build.gradle（curse 可选依赖）──→ T5 P2 mixin ──┐
                                                          │
T2 mods.toml（依赖声明）──────────────────────────────────┤
                                                          ├─→ T6 一级验证 ─→ T7 二级验证 ─→ T8 归档
T3 模组自带标签 ──────────────────────────────────────────┤
                                                          │
T4 P1 mixin（不依赖任何新依赖）───────────────────────────┘
```

- **T1 / T2 / T3 / T4 相互独立**，可任意顺序或并行。
- **T5 依赖 T1**：要 `import ...RaidBlimp`，类必须先在编译类路径上。
- **T6 依赖 T1–T5 全部完成**。
- **T7 依赖 T1** 的 `localRuntime`（否则开发运行目录里没有袭击增强，测不了）。

**关于版本号与产物名：** 当前 `gradle.properties:22` 是 `mod_version=0.9.8`
⇒ 下面命令里的 jar 名写作 `beloong-0.9.8.jar`。若你中途升了版本，按实际产物名替换。

---

## Task 1：加 raids-enhanced 可选依赖（curse maven）

> **⚠️ 事后更正（已按此实施）**：本任务最初写的是 **Modrinth** maven 坐标 `maven.modrinth:raidsenhanced:1.0.2`。
> 那个坐标**会导致游戏无法加载**，已改用 curse maven。
> **根因**：Modrinth 按「版本号」解析时命中的是同一个 1.0.2 的 **Forge** 构建
> （jar 内是 `META-INF/mods.toml`），而 NeoForge 1.21.1 只认 `META-INF/neoforge.mods.toml`
> ⇒ 该 jar 根本不可能被 NeoForge 加载。详见下方"为什么不用 Modrinth"。

**Files:**
- Modify: `build.gradle`（只在 `dependencies { }` 块内；**不需要新增 maven 仓库**）

**Steps:**

1. `repositories { }` **无需改动** —— 项目已有 `cursemaven`。

2. 在 `dependencies { }` **块末尾**（`playeranimator` 那行之后、闭合 `}` 之前）追加：

   ```groovy
   // Raids:Enhanced（袭击增强）— 可选依赖
   // 本模组注入 Entity#isAlliedTo（原版）与 RaidBlimp#checkTargetClass（该模组内部），
   // 让它的袭击者把龙之生存猎人视为同伴；未安装时 @Pseudo 令该 Mixin 整体跳过。
   // fdlib（其前置）已在上方以 1.0.9 声明，满足它要求的 [1.0.8,2.0.0)。
   compileOnly "curse.maven:raids-enhanced-1467121:7676063"
   localRuntime "curse.maven:raids-enhanced-1467121:7676063"
   ```

   `compileOnly` 与 `localRuntime` **锁同一版本**，与项目其它可选依赖的既有做法一致
   （避免"按 A 编译、按 B 运行"的偏差）。

**已核实的事实（无需再查）：**
- curse 文件 `1467121:7676063` 的 jar 内 `META-INF/neoforge.mods.toml` 声明：
  `modId="raidsenhanced"`、**`version="1.0.2"`**、`[[mixins]] config="raidsenhanced.mixins.json"`
  ⇒ 与 Task 2 的 `versionRange="[1.0.2,)"` 一致。
- 它的必需前置 `fdlib` 项目已依赖且版本为 **1.0.9**（`build.gradle:184-185`），满足其 `[1.0.8,2.0.0)` ⇒ **无需改动**
- 它同时要求 `neoforge` `[21.1.218,)`（项目用 21.1.236 ✓）与 `minecraft [1.21.1]` ✓

**为什么不用 Modrinth（记下来的教训）：**
`maven.modrinth:<slug>:<版本号>` 是按**版本号**去解析的，而同一个版本号在 Modrinth 上可能同时存在
**多个 loader 的构建**。实际拿到的那个 1.0.2 jar 内是 `META-INF/mods.toml`（Forge 布局）——
NeoForge 1.20.5+ 已改为只读 `META-INF/neoforge.mods.toml`，所以它**永远加载不了**（表现为游戏无法加载）。
curse 的文件 id 是**逐文件**唯一的，不存在选错 loader 的可能，因此本项目对这类可选兼容一律用 curse maven。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain 2>&1 | Select-String 'BUILD SUCCESSFUL|BUILD FAILED|error:'
# 确认解析到的是 curse 的 jar：
Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\curse.maven\raids-enhanced-1467121" -Recurse -Filter '*.jar' |
  ForEach-Object { "{0,12:N0}  {1}" -f $_.Length, $_.FullName }
# 并确认它是 NeoForge 布局（必须列出 META-INF/neoforge.mods.toml，而不是 META-INF/mods.toml）：
Add-Type -AssemblyName System.IO.Compression.FileSystem
$p=(Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\curse.maven\raids-enhanced-1467121" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$z=[System.IO.Compression.ZipFile]::OpenRead($p)
$z.Entries | Where-Object { $_.FullName -match 'META-INF/(neoforge\.)?mods\.toml' } | ForEach-Object { $_.FullName }
$z.Dispose()
```
期望：`BUILD SUCCESSFUL`，且解出的路径是 **`META-INF/neoforge.mods.toml`**。
若解出 `META-INF/mods.toml`（Forge 布局）⇒ 坐标又错了，该 jar 无法在 NeoForge 上加载。

---

## Task 2：`mods.toml` 补可选依赖声明

**Files:**
- Modify: `src/main/templates/META-INF/neoforge.mods.toml`（当前最后一块依赖结束于 `:204`，在其后插入）

**Steps:**

在 `irons_spellbooks` 那个 `[[dependencies.${mod_id}]]` 块之后追加：

```toml
[[dependencies.${mod_id}]]
    modId="raidsenhanced"
    type="optional"
    versionRange="[1.0.2,)"
    ordering="AFTER"
    side="BOTH"
```

**为什么写 `versionRange`（与项目既有可选依赖不同的地方）：**
本项目既有的可选依赖多数不写 `versionRange`，但上一轮代码审查专门把这一点列为第 2 条缺陷
（`docs/reviews/2026-09-18-post-0.9.3-bug-list.md:33-38`）——
原话是"崩溃现场查不到任何依赖线索"，且**它给出的两条修法之一就是"补进 `mods.toml` 并加 `versionRange`"**。
本兼容层注入的是该模组的**内部方法** `checkTargetClass`，版本偏差会导致静默失效
⇒ 用 `versionRange` 让"版本不匹配"在启动日志里可见。若你更想严格遵循既有做法，去掉这一行即可。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat processResources --console=plain 2>&1 | Select-String 'BUILD SUCCESSFUL|error:'
Select-String -Path build\resources\main\META-INF\neoforge.mods.toml -Pattern 'raidsenhanced' -Context 0,4
```
期望：构建通过，且展开后的 `mods.toml` 里出现 `raidsenhanced` + `type="optional"`。

---

## Task 3：新增模组自带标签（6 个猎人 → `#minecraft:illager_friends`）

**Files:**
- Create: `src/main/resources/data/minecraft/tags/entity_type/illager_friends.json`

**Steps:**

写入（`entity_type` 目录需新建）：

```json
{
  "values": [
    "dragonsurvival:hunter_ambusher",
    "dragonsurvival:hunter_griffin",
    "dragonsurvival:hunter_hound",
    "dragonsurvival:hunter_knight",
    "dragonsurvival:hunter_leader",
    "dragonsurvival:hunter_spearman"
  ],
  "replace": false
}
```

**⚠️ `"replace": false` 不能省。** 原版该标签的内容是 `{"values": ["#minecraft:illager"]}`；
写成 `true` 会把 `#illager` 一起清空 ⇒ **灾厄村民之间互相不再算盟友**，"Johnny" 卫道士会转而攻击其他灾厄村民。

**与前一步的关系：** 整合包侧（`BeLoong-Server/kubejs/data/minecraft/tags/entity_type/illager_friends.json`）
那份**保留不动**；两份同路径文件在 `replace:false` 下**合并为并集**，互不覆盖。

**6 个 ID 的双源核对（已做）：** DS 生成的 `data/dragonsurvival/tags/entity_type/hunter_faction.json`
与 `DSEntities.java` 各 `REGISTRY.register(...)` 首参**逐一一致**。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain 2>&1 | Select-String 'BUILD SUCCESSFUL|error:'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead('build\libs\beloong-0.9.8.jar')
$e=$z.Entries|Where-Object{$_.FullName -eq 'data/minecraft/tags/entity_type/illager_friends.json'}
$sr=New-Object System.IO.StreamReader($e.Open());$t=$sr.ReadToEnd();$sr.Close()
$t | ConvertFrom-Json | Select-Object replace, @{n='count';e={$_.values.Count}}
$z.Dispose()
```
期望：`replace=False`、`count=6`。

---

## Task 4：P1 —— 让全部袭击者把猎人视为同伴

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/minecraft/RaiderHunterAllyMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`（`mixins` 段加一行）

**Steps:**

1. 新建 mixin：

   ```java
   package com.zonlong.beloong.mixin.minecraft;

   import net.minecraft.tags.EntityTypeTags;
   import net.minecraft.world.entity.Entity;
   import net.minecraft.world.entity.raid.Raider;
   import org.spongepowered.asm.mixin.Mixin;
   import org.spongepowered.asm.mixin.injection.At;
   import org.spongepowered.asm.mixin.injection.Inject;
   import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

   /**
    * 让<b>全部袭击者</b>把「龙之生存猎人」视为同伴。
    *
    * <h3>为什么注入 {@code Entity} 而不是 {@code Raider}</h3>
    * {@code #minecraft:illager_friends} 这套阵营判定在原版里<b>只有一处消费</b> ——
    * {@code AbstractIllager#isAlliedTo}；而 {@code Raider} 自己<b>没有声明</b> {@code isAlliedTo}
    * （继承自 {@code Entity}），Mixin 无法向"未声明该方法的类"注入。
    * 这里注入声明的祖先类并用 {@code instanceof Raider} 收窄作用域，等价且不复制原版逻辑。
    *
    * <h3>覆盖范围</h3>
    * 原版 4 种灾厄村民 + 劫掠兽 + 女巫 + 任意模组袭击者（含 Raids:Enhanced 的 4 个，
    * 它们 {@code extends FDRaider extends Raider}）。经 {@code TargetingConditions#test} 排除目标，
    * 并使 {@code HurtByTargetGoal} 不再还手。
    *
    * <h3>与数据包的分工</h3>
    * 名单由 {@code #minecraft:illager_friends} 决定：本模组自带一份默认值（6 个猎人），
    * 整合包可在同一标签上继续追加，两份合并为并集。
    */
   @Mixin(Entity.class)
   public abstract class RaiderHunterAllyMixin {

       @Inject(method = "isAlliedTo(Lnet/minecraft/world/entity/Entity;)Z",
               at = @At("HEAD"), remap = false, cancellable = true)
       private void beloong$raidersTreatHuntersAsAllies(Entity other, CallbackInfoReturnable<Boolean> cir) {
           // mixin 里 this 不是 Entity 类型，取 getTeam() 需显式转型（比 @Shadow 少一处声明）
           if (!((Object) this instanceof Raider self)) {
               return;
           }
           // 与原版 AbstractIllager#isAlliedTo 同构：要求双方都没有记分板队伍
           if (self.getTeam() != null || other.getTeam() != null) {
               return;
           }
           if (other.getType().is(EntityTypeTags.ILLAGER_FRIENDS)) {
               cir.setReturnValue(true);
           }
       }
   }
   ```

   **⚠️ `remap = false` 不能省**（这是本任务最容易踩的坑）。原版目标（`Entity`）在 NeoForge
   运行时用的就是 Mojang 官方名、dev 命名空间 == 运行时命名空间；留默认 `true` 会让注解处理器
   去查混淆映射并**直接构建失败**：`Unable to locate obfuscation mapping for @Inject target`。
   项目内两处先例已把这条写进注释：
   - `mixin/minecraft/DreadKingRitualTriggerMixin.java:103-107`（原话即上述理由）
   - `mixin/PossibleBiomesFilterMixin.java:74,80` —— `@Mixin(原版类)` + `@Inject(at = @At("HEAD"), remap = false, cancellable = true)`，
     **与本任务的形态完全一致**，可照抄。

   `@Mixin(Entity.class)` 上的 `remap` 保持默认（两处先例都没写）。

   **注意**：`Entity#isAlliedTo` **有两个重载**（`(Entity)` 与 `(Team)`），描述符里的
   `Lnet/minecraft/world/entity/Entity;` 必须写全，否则会解析失败或挂错重载。

2. 在 `beloong.mixins.json` 的 `"mixins"` 段（非 `client`：`isAlliedTo` 两端都会被调）追加：

   ```json
   "minecraft.RaiderHunterAllyMixin"
   ```

   紧挨着既有条目 `"minecraft.DreadKingRitualTriggerMixin"`（`mixins` 段的最后一行）追加即可。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain 2>&1 | Select-String 'BUILD SUCCESSFUL|BUILD FAILED|error:'
Test-Path build\classes\java\main\com\zonlong\beloong\mixin\minecraft\RaiderHunterAllyMixin.class
Select-String -Path src\main\resources\beloong.mixins.json -Pattern 'RaiderHunterAllyMixin'
```
期望：构建通过、class 存在、`mixins.json` 里能搜到该条目。

---

## Task 5：P2 —— 飞艇的硬编码白名单也要认标签

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/raidsenhanced/RaidBlimpTargetMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`（`mixins` 段加一行）

**Steps:**

1. 新建 mixin：

   ```java
   package com.zonlong.beloong.mixin.raidsenhanced;

   import com.finderfeed.raids_enhanced.content.entities.raid_blimp.RaidBlimp;
   import net.minecraft.tags.EntityTypeTags;
   import net.minecraft.world.entity.Entity;
   import org.spongepowered.asm.mixin.Mixin;
   import org.spongepowered.asm.mixin.Pseudo;
   import org.spongepowered.asm.mixin.injection.At;
   import org.spongepowered.asm.mixin.injection.Inject;
   import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

   /**
    * 让 Raids:Enhanced 的飞艇把「龙之生存猎人」从候选目标里剔除。
    *
    * <h3>为什么必须单独注入</h3>
    * {@code RaidBlimp#checkTargetClass} 是<b>硬编码类判断</b>，由
    * {@code FDTargetFinder.getEntitiesInCylinder(..., e -> checkTargetClass(e))} 调用：
    * 投弹（{@code RaidBlimp:498}）与 6 门炮（{@code RaidBlimpCannonsController:54}）。
    * 这条路径<b>既不经过 {@code TargetingConditions}，也不走 {@code Mob#setTarget}</b>
    * ⇒ 基于 {@code isAlliedTo} 的修复碰不到它。
    *
    * <h3>可选依赖</h3>
    * Raids:Enhanced 是可选依赖：{@code @Pseudo} 让本 Mixin 在该模组缺席时整体跳过。
    *
    * <h3>已知取舍</h3>
    * 按项目规范用 {@code require = 0}：上游若重命名/改签名了 {@code checkTargetClass}，
    * 本注入会<b>静默失效</b>（不会崩，但兼容层不再起作用）。
    */
   @Pseudo
   @Mixin(value = RaidBlimp.class, remap = false)
   public abstract class RaidBlimpTargetMixin {

       @Inject(method = "checkTargetClass(Lnet/minecraft/world/entity/Entity;)Z",
               at = @At("HEAD"), cancellable = true, remap = false, require = 0)
       private void beloong$ignoreHunters(Entity target, CallbackInfoReturnable<Boolean> cir) {
           // mixin 里 this 的静态类型是混入类，取实体方法需显式转型
           // （RaidBlimp 经 FDRaider→Raider→…→Entity，转型成立）
           Entity self = (Entity) (Object) this;
           // 与 P1 同一口径：双方都无记分板队伍
           if (self.getTeam() != null || target.getTeam() != null) {
               return;
           }
           if (target.getType().is(EntityTypeTags.ILLAGER_FRIENDS)) {
               cir.setReturnValue(false);
           }
       }
   }
   ```

2. 在 `beloong.mixins.json` 的 `"mixins"` 段追加：

   ```json
   "raidsenhanced.RaidBlimpTargetMixin"
   ```

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain 2>&1 | Select-String 'BUILD SUCCESSFUL|BUILD FAILED|error:'
Test-Path build\classes\java\main\com\zonlong\beloong\mixin\raidsenhanced\RaidBlimpTargetMixin.class
Select-String -Path src\main\resources\beloong.mixins.json -Pattern 'RaidBlimpTargetMixin'
```
期望：构建通过、class 存在、`mixins.json` 里能搜到。若编译报 `package com.finderfeed... does not exist`
⇒ T1 的 `compileOnly` 没生效，回 T1 排查。

---

## Task 6：一级验证（静态 / 构建）

**Files:** 无（只读验证）

**Steps:**

1. 全量构建。
2. jar 内自检：1 个新资源（标签）+ 2 个新 class + `beloong.mixins.json` 条目数 +2。
3. 若开发环境可跑客户端：启动一次，检查日志无 Mixin 报错。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain 2>&1 | Select-String 'BUILD SUCCESSFUL|BUILD FAILED|error:'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead('build\libs\beloong-0.9.8.jar')
$want=@(
 'data/minecraft/tags/entity_type/illager_friends.json',
 'com/zonlong/beloong/mixin/minecraft/RaiderHunterAllyMixin.class',
 'com/zonlong/beloong/mixin/raidsenhanced/RaidBlimpTargetMixin.class',
 'beloong.mixins.json'
)
foreach($w in $want){ $e=$z.Entries|Where-Object{$_.FullName -ceq $w}; if($e){'OK  '+$w}else{'MISS '+$w} }
$z.Dispose()

# mixins.json 条目数（实施前 40：33 mixins + 7 client；实施后应为 42）
$j = Get-Content src\main\resources\beloong.mixins.json -Raw | ConvertFrom-Json
"mixins=$($j.mixins.Count)  client=$($j.client.Count)  合计=$($j.mixins.Count + $j.client.Count)"
```
期望：`BUILD SUCCESSFUL`；4 项全 `OK`；合计 **42**。

**可选（能启动客户端时，用于确认 Mixin 真的 apply 了）：**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat runClient --console=plain 2>&1 | Select-String 'Mixin|raidsenhanced|RaiderHunterAlly|RaidBlimpTarget'
```
期望：出现我们两个 Mixin 的 apply 记录，且**没有** `Mixin apply failed` / `InjectionError` / `NoSuchMethodError`。
（注意：本项目记忆库记载运行期验证不稳定，此项**不能**作为唯一门禁。）

---

## Task 7：二级验证（实机，**由用户执行**）

**Files:** 无

**前置：** T1 的 `localRuntime` 会把 Raids:Enhanced 放进开发运行时的类路径。
（现状核实：`run\mods` 里**没有** raidsenhanced，`dependencies` 里也没声明
⇒ 加 T1 之前开发环境**根本加载不到该模组**，本任务无法测。）

| # | 操作 | 期望 |
|---|---|---|
| 1 | 召唤 `raidsenhanced:zapper`（生成蛋 `raidsenhanced:zapper_spawn_egg`）靠近猎人首领 | **不锁定** |
| 2 | 同上 `raidsenhanced:golem_of_last_resort` | **不锁定** |
| 3 | `raidsenhanced:raid_blimp` 悬停在猎人与村民上方 | 投弹 / 炮击**不瞄猎人** |
| 4 | **负向对照**：把上面 3 个的目标换成普通村民 / 铁傀儡 | **照常锁定并攻击** —— 证明不是"整体 AI 失效" |
| 5 | **负向对照**：把猎人首领**从两份** `#minecraft:illager_friends` 里都移除 | 袭击者**恢复攻击** —— 证明判定确实读标签。⚠️ **只删一份无效**（两份是并集） |
| 6 | 原版对照：掠夺者仍不打猎人 | 无回归 |
| 7 | 劫掠兽 | 由"会打"变为**不打**（本方案的附带收益） |
| 8 | 自包含验证：**临时移除整合包侧那份标签**，只留模组自带那份 | 袭击者仍**不攻击** —— 证明模组自包含 |

第 4、5、8 条是关键：**没有负向对照，就无法区分"修好了"和"整个 AI 挂了"。**

---

## Task 8：归档（更新记忆库状态）

**Files:**
- Modify: `memory/decisions-log.md`

**Steps:**

把 2026-09-23 条目标题里的 `（**设计已批准，未实施**）` 改为 `（**已实施，静态验证通过 / 实机验证通过**）`，
并勾掉「待办」里已完成的项；若实机发现偏差，补「实测结果」段。

**Verification:** `Select-String -Path memory\decisions-log.md -Pattern '已实施' | Select-Object -First 3`

---

## 回滚

删除 3 个新文件 + 撤销 4 处修改即可完全回到现状：

| 动作 | 目标 |
|---|---|
| 删除 | `mixin/minecraft/RaiderHunterAllyMixin.java` |
| 删除 | `mixin/raidsenhanced/RaidBlimpTargetMixin.java` |
| 删除 | `data/minecraft/tags/entity_type/illager_friends.json` |
| 撤销 | `beloong.mixins.json` 两条 |
| 撤销 | `mods.toml` 的 `raidsenhanced` 块 |
| 撤销 | `build.gradle` 的两行依赖 |

⚠️ 回滚标签文件后，**整合包侧那份要自行补上 6 个 hunter**，否则原版灾厄村民也会恢复攻击猎人。

---

## 风险与已知取舍

| 风险 | 影响 | 缓解 |
|---|---|---|
| 上游改名 `checkTargetClass` | 飞艇那部分**静默失效**（不崩） | 已用 `mods.toml` 的 `versionRange` 让版本偏差可见；javadoc 已注明 |
| 玩家/生物带记分板队伍 | 同伴判定不生效（照抄原版语义） | 与原版灾厄村民一致，属预期 |
| `compileOnly` 与实装版本不一致 | 运行时行为可能不同 | 已核实 1.0.2；`compileOnly`/`localRuntime` 锁同版本 |
| 范围伤害/投射物 | **不免疫**（D1 的边界） | 刻意为之，见设计文档「非目标」 |
