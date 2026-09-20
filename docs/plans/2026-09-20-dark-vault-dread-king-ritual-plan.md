# 黯影宝库「死王仪式」实施计划

**目标：** 在 `dragonsurvival:dragon_hunters_castle` 内开启黯影宝库时，于宝库顶生成一个不可见的标记实体，
播放 `suspense` 音效 140 tick 后召唤**不祥**死者之王。

**架构：** 三层单向依赖 —— 检测层（1 个 mixin 注入原版 `unlock(...)`）/ 编排层（结构判定 + 刷怪点）/
执行层（`Marker` 子类：计时 + 音乐 + 召唤）。三层只通过数据耦合，每层可独立替换与验证。

**采用方案：** 方案 C（在 brainstorming 阶段经 A/B/C/D 四方案对比后由用户选定，见
`docs/plans/2026-09-20-dark-vault-dread-king-ritual-design.md` §三）。
本计划不再重复方案对比，只做**实施分解**。

**基线：** `disaster2` @ `a7c07d1`（= `master` @ `bbc9f22`，版本 `0.9.6`）
**设计文档：** `docs/plans/2026-09-20-dark-vault-dread-king-ritual-design.md`
**机制依据：** `docs/死者之王机制总结.md`

---

## 一、验证范式（与技能模板的差异说明）

本项目**没有测试套件**（`memory/project-context.md`：*No test suite. Verification is
`gradlew build` + static grep probes + live game runs.*）。因此本计划**不使用** TDD 的
RED-GREEN 步骤（没有测试框架可跑），每个任务的「验证」由三件套组成：

1. `.\gradlew.bat build` 退出码 0
2. **静态探针** —— 对 `build/libs/beloong-0.9.6.jar` 内产物取证（class 是否存在、
   `mixins.json` 是否含新条目、常量池是否含注册名）
3. **实机运行** —— 本项目的最终验收门槛，用例见第六节

> 说明：mixin 的正确性由 `beloong.mixins.json` 的 `injectors.defaultRequire = 1` **在启动时**
> 强制校验 —— 注入点找不到会**直接崩游戏**而不是静默失效。这是本项目「宁可响亮失败」的既定取向，
> 也意味着 T5 的验证天然包含「能启动」。

## 二、任务顺序的理由（自底向上）

按 **执行层 → 编排层 → 检测层** 的顺序实现，而非自顶向下。理由：

- 设计刻意让执行层不依赖触发者（只认「我在哪、还剩几 tick」），因此 **T3 之后它就能被
  `/summon beloong:dread_king_ritual_marker` 单独实机验证** —— 音乐时长、不祥状态、
  音乐时长与 NBT 续跑这几个最容易出错的点，在接触 mixin 之前就能全部证伪；
- 本项目的瓶颈是实机验证（不是编译），把「可独立实机验证」的层先立起来能显著缩短反馈环；
- 若 T5 的 mixin 注入出问题，已完成的 T1–T4 不受影响。

## 三、外部前置（不在本计划范围内）

⚠️ **整合包尚未把黯影宝库放进城堡**。实际依赖 jar 内全部 119 个结构 NBT 已扫描确认：
`dark_vault` 只生成在 `dragonsurvival:treasure_angry_{cave,forest,sea}`，`dragon_hunters_castle`
的 21 个拼图块里一个宝库都没有。

因此 **T6 的实机验收需要先手动在城堡内放一个黯影宝库**（`/setblock` 或创造模式放置）。
这不在本计划的交付物内，但**必须**在验收时准备好，否则用例 3/4/5 无法执行。

---

## 一、任务分解

### T1 —— 配置节与 lang

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. 在 `Config.java` 的 `SERVER_SPEC` 区，照 `DragonSummon`（`:197-210`）写一个静态内部类：

   ```java
   public static final class DreadKingRitual {
       private DreadKingRitual() {}
       public static ModConfigSpec.BooleanValue enabled;
       public static ModConfigSpec.ConfigValue<String> structure;
       public static ModConfigSpec.DoubleValue musicVolume;
   }
   ```

2. 在 `static { ... }` 块中 `SERVER_BUILDER.push("dread_king_ritual")`，定义三个键后 `pop()`：

   | 键 | 定义方式 | 默认 |
   |---|---|---|
   | `enabled` | `.define("enabled", true)` | `true` |
   | `structure` | `.define("structure", "dragonsurvival:dragon_hunters_castle")` | 见左 |
   | `musicVolume` | `.defineInRange("musicVolume", 0.5, 0.0, 16.0)` | `0.5` |

   注释里必须写明**音量同时决定可闻半径**（半径 = `16 × max(volume, 1)` 格；唱片机约定值是
   4.0 = 64 格），否则后人会以为它只影响响度。

3. 两个 lang 文件各加一条：`"entity.beloong.dread_king_ritual_marker"`（中：死王仪式标记 / 英：Dread King Ritual Marker）。
   **加在各自文件的 `entity.beloong.*` 位置附近以保持键序**（若文件里还没有该前缀，就加在 `entity.` 区块内）。

**Verification:**
```powershell
.\gradlew.bat build            # exit 0
Select-String -Path src\main\java\com\zonlong\beloong\Config.java -Pattern "dread_king_ritual|musicVolume|DreadKingRitual"
foreach ($f in 'zh_cn','en_us') { (Get-Content "src\main\resources\assets\beloong\lang\$f.json" -Raw | ConvertFrom-Json).'entity.beloong.dread_king_ritual_marker' }
```

**Commit:** `新增死王仪式配置节与实体 lang 键`

---

### T2 —— 执行层：标记实体类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/entity/DreadKingRitualMarker.java`

**Steps:**
1. 类体骨架（**继承原版 `Marker`**，不继承 `Entity`）：

   ```java
   public class DreadKingRitualMarker extends Marker {
       public static final int LIFETIME_TICKS = 140;   // = suspense.ogg 实测 7.010s（140.2 tick），用户要求取整
       private static final int PARTICLE_INTERVAL_TICKS = 5;
       private static final int PARTICLES_PER_BURST = 8;
       private static final double PARTICLE_RADIUS = 3.0D;
       private int lifeTicks = LIFETIME_TICKS;
       private boolean musicPlayed;
       public DreadKingRitualMarker(EntityType<? extends DreadKingRitualMarker> type, Level level) { super(type, level); }
   }
   ```

2. `tick()` —— ⚠️ **不要调 `super.tick()`，也不要调 `baseTick()`**（D17）：

   ```java
   @Override
   public void tick() {
       if (level().isClientSide) return;                 // 理论不可达（本实体不发客户端），防御性保留
       if (!musicPlayed) {
           playRitualAudio();
           musicPlayed = true;
       }
       if (lifeTicks % PARTICLE_INTERVAL_TICKS == 0) emitRitualParticles();   // D22
       if (--lifeTicks > 0) return;
       try {
           summonDeadKing();
       } catch (Exception e) {
           LOGGER.error("[BeLoong] dread_king_ritual 召唤失败 @ {} {}", level().dimension().location(), position(), e);
       } finally {
           discard();                                     // ★ 恰好尝试一次，绝不在 tick 里反复抛
       }
   }
   ```

   - **为什么故意不跑 `baseTick()`**：`Marker.tick()` 是空实现且不调 `Entity.baseTick()`（`Marker.java:20-22` vs
     `Entity.java:430-432`），我们有意继承。收益是免疫 `baseTick()` 里的 `handlePortal()`
     （`Entity.java:448`）⇒ 标记实体**不会被传送门搬走**，避免「宝库旁有传送门 ⇒ 仪式在别的维度触发」。
     代价已逐条排除，见设计文档 §五 核对表 #5/#6。**此处的"不调 super"是刻意设计，不是遗漏 —— 改它前先读那段核对表。**
   - **为什么 `try/catch/finally` 不能省**：`Level.guardEntityTick`（`Level.java:607-621`）在
     `removeErroringEntities`（**默认 `false`**）下会 `throw new ReportedException` ⇒ **崩整个服务端**；
     若管理员设为 `true` ⇒ **静默 `discard()`、仪式无声消失**。自己兜住把两种坏结局都换成
     「恰好一次 + 可诊断的 ERROR 日志」。
   - `--lifeTicks` 必须先减后判（`> 0` 才 return），保证第 140 tick 那一次一定会执行召唤。
   - **`lifeTicks` 是唯一合法计时源**：`tickCount` 虽然照常自增（它在 `ServerLevel.tickNonPassenger:772`，
     不在 `baseTick()`），但 `Entity.saveWithoutId`（`Entity.java:1737` 起）**不写 `tickCount`** ⇒ 读档归零。

3. `playRitualAudio()`（**唯一一处音量语义**）：
   ```java
   level().playSound(null, getX(), getY(), getZ(),
           SoundRegistry.DEAD_KING_SUSPENSE.get(),
           SoundSource.RECORDS,
           Config.DreadKingRitual.musicVolume.get().floatValue(), 1.0F);
   ```
   import：`io.redspace.ironsspellbooks.registries.SoundRegistry`（铁魔法已是 `required` 依赖，直接引用，**不加** `isLoaded` 守卫）。
   注释里写明音量**同时决定可闻半径**（半径 = `16 × max(volume, 1)` 格），并指向设计文档 §五 的推导。
   ⚠️ **不要**加「重进补发」逻辑 —— `SoundInstance` 没有 seek，补发只能从头重播；该局限是结构性的且用户已接受（D4）。

   `emitRitualParticles()`（D22）—— 每 `PARTICLE_INTERVAL_TICKS` tick 调一次：
   ```java
   for (int i = 0; i < PARTICLES_PER_BURST; i++) {
       double angle = getRandom().nextDouble() * Math.PI * 2.0D;
       double radius = PARTICLE_RADIUS * Math.sqrt(getRandom().nextDouble());   // sqrt 才保证面密度均匀
       serverLevel.sendParticles(ParticleHelper.BLOOD_GROUND,
               getX() + Math.cos(angle) * radius, getY(), getZ() + Math.sin(angle) * radius,
               1, 0.0D, 0.0D, 0.0D, 0.0D);                                        // count=1 才能落在圆内
   }
   ```
   import：`io.redspace.ironsspellbooks.util.ParticleHelper`（其 `BLOOD_GROUND` 就是 `ParticleRegistry` 里那个
   `SimpleParticleType`）。⚠️ 两个要点**不能改**：`count = 1`（`sendParticles` 对 `count > 1` 是「±offset
   **长方体**随机」，会摊成方阵而不是圆）、`R × sqrt(u)`（否则粒子向圆心堆积）。Y 用标记实体自身 Y 即可 ——
   粒子自带物理（`Particle.hasPhysics` 默认 `true`）会各自落到脚下表面并停住，**不需要**高度图采样。

4. `summonDeadKing()` —— **逐字复刻 `DeadKingCorpseEntity.java:75-90` 的序列**：

   ```java
   DeadKingBoss boss = new DeadKingBoss(level());
   boss.moveTo(position());
   // 刻意不调 setSpawnPos —— D20：spawnPos 保持 null，tickDeath() 因而不生成灵魂
   boss.finalizeSpawn((ServerLevel) level(), level().getCurrentDifficultyAt(boss.getOnPos()), MobSpawnType.TRIGGERED, null);
   boss.setPersistenceRequired();
   level().addFreshEntity(boss);
   boss.onOminousTrigger();                           // ★ 直调，幂等；不要走"给玩家挂 Trial Omen"
   MagicManager.spawnParticles(level(), ParticleTypes.SCULK_SOUL, getX(), getY() + 2.5, getZ(), 80, .2, .2, .2, .25, true);
   level().playSound(null, getX(), getY(), getZ(), SoundRegistry.DEAD_KING_SPAWN.get(), SoundSource.MASTER, 20, 1);
   ```
   import：`io.redspace.ironsspellbooks.entity.mobs.dead_king_boss.DeadKingBoss`、
   `io.redspace.ironsspellbooks.capabilities.magic.MagicManager`（**注意是这个包**）、
   `net.minecraft.core.particles.ParticleTypes`、`net.minecraft.sounds.SoundSource`、
   `net.minecraft.world.entity.MobSpawnType`。

5. `addAdditionalSaveData` / `readAdditionalSaveData`：**必须 `super` 调用**（`Marker` 用它保存
   `data` 标签），再写/读 `lifeTicks` 与 `musicPlayed`。
   两者都落 NBT 是硬要求：前者让重启后续跑剩余 tick，后者防止区块重载后**重放音乐**
   （重放会让音乐从头发、倒计时从中间走，直接违背「时长 = 音乐长度」）。

6. 明确**不做**的四件事：
   - **不要**覆盖 `getAddEntityPacket` —— `Marker` 已让它 `throw`（`Marker.java:38-41`），这正是我们要的兜底
   - **不要**覆写 `broadcastToPlayer` —— `ChunkMap.addEntity` 的 `i != 0` 短路已是结构性硬保证（D19）
   - **不要**加 `defineSynchedData` —— `Marker` 已把它覆写为空（`Marker.java:24-26`），本实体不参与同步
   - **不要**用 `tickCount` / `firstTick` 承担任何语义（前者读档归零，后者因不跑 `baseTick` 而恒为 `true`）

**Verification:**
```powershell
.\gradlew.bat build     # exit 0
# 静态探针：class 已产出，且常量池含 140 与该类名
$jar='build\libs\beloong-0.9.6.jar'
# （用 jar tf 确认 entity/DreadKingRitualMarker.class 存在）
```
> 本任务**还不能**用 `/summon` —— 实体尚未注册（T3）。此处只做编译与产物验证。

**Commit:** `新增死王仪式标记实体（执行层）`

---

### T3 —— 注册实体（第一个实机闸门）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/registry/ModEntities.java`

**Steps:**
1. 照 `TORNADO`（`:42-49`）加：

   ```java
   public static final DeferredHolder<EntityType<?>, EntityType<DreadKingRitualMarker>> DREAD_KING_RITUAL_MARKER =
           ENTITIES.register("dread_king_ritual_marker", () -> EntityType.Builder
                   .<DreadKingRitualMarker>of(DreadKingRitualMarker::new, MobCategory.MISC)
                   .sized(0.0F, 0.0F)
                   .clientTrackingRange(0)
                   .build("beloong:dread_king_ritual_marker"));
   ```

2. ⚠️ `.sized(0.0F, 0.0F)` 与 `.clientTrackingRange(0)` 是**承重结构，不是可调参数**：
   `ChunkMap.addEntity` 的逻辑是 `int i = entitytype.clientTrackingRange() * 16; if (i != 0) { ...创建 TrackedEntity... }`
   （`ChunkMap.java:1110-1121`）⇒ 传 0 时 **`ChunkMap` 连 `TrackedEntity` 都不会创建**，实体从结构上
   不可能被发送到任何客户端（与玩家距离、人数、维度全无关）。
   **必须原样保留，并在 Holder 上方写注释说明** —— 否则后人"顺手"照抄龙卷风改成 10，会让服务端
   在配对时触发 `Marker.getAddEntityPacket` 的 `throw` 而**运行期崩溃**。
3. **不要**加 `.updateInterval(1)` —— 那是龙卷风为投射物同步加的；本实体永不发送客户端，加了只是徒增开销。
4. 更新类 javadoc 的实体清单（加上第二个条目），并注明本实体"**永不发送客户端，无需渲染器**"。
5. `BeLoongCore` **不需要改**：`ModEntities.register(modEventBus)` 已在 `:94`。

**Verification:**
```powershell
.\gradlew.bat build     # exit 0
# 常量池探针
javap -p -c -classpath build\libs\beloong-0.9.6.jar com.zonlong.beloong.registry.ModEntities | Select-String "dread_king_ritual_marker"
```
**实机（关键闸门）：** 启动游戏 → `/summon beloong:dread_king_ritual_marker ~ ~ ~`
- ✅ 命令不报错（说明已注册、`MobCategory.MISC` 合法、`summon` 不需要 `noSummon()`）
- ✅ **看不到任何实体**（`clientTrackingRange(0)` + `getAddEntityPacket` throw 生效）
- ✅ 立刻听到 suspense 音效（`musicVolume = 0.5` ⇒ 16 格内可闻）
- ✅ **140 tick（7 秒）后**听到 `dead_king_spawn` 音效并出现死者之王
- ✅ 仪式期间周围半径 3 格内出现 `blood_ground` 血渍，且圆外四角没有（D22）
- ✅ 该死王是**不祥**态（环绕 `TRIAL_OMEN` 粒子 / 血量 1000）
- ✅ 打死它 → **不生成灵魂**（D20：`spawnPos` 为 null）—— 反过来若生成了灵魂，说明 D20 失效

> 这条闸门一次性覆盖设计文档 §七 B 的用例 1、2、7a、10。四条都过再进 T4。

**Commit:** `注册死王仪式标记实体（beloong:dread_king_ritual_marker）`

---

### T4 —— 编排层：结构判定与刷怪点

**Files:**
- Create: `src/main/java/com/zonlong/beloong/dreadking/DreadKingRitualStarter.java`

**Steps:**
1. 对外只暴露一个入口：
   ```java
   public static void start(ServerLevel level, BlockPos vaultPos, ServerPlayer player)
   ```
2. 逻辑顺序（**开关先于结构判定，结构判定先于任何副作用**）：
   ```java
   if (!Config.DreadKingRitual.enabled.get()) return;
   if (!isInTargetStructure(level, vaultPos)) return;
   BlockPos spawnPos = findSpawnPos(level, vaultPos);
   DreadKingRitualMarker marker = new DreadKingRitualMarker(ModEntities.DREAD_KING_RITUAL_MARKER.get(), level);
   marker.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
   level.addFreshEntity(marker);
   ```
3. `isInTargetStructure`：
   ```java
   ResourceLocation id = ResourceLocation.tryParse(Config.DreadKingRitual.structure.get());
   if (id == null) return false;
   Holder<Structure> holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
           .getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElse(null);
   if (holder == null) return false;
   return level.structureManager()
           .getStructureWithPieceAt(vaultPos, HolderSet.direct(holder))
           .isValid();
   ```
   - 签名已核实：`StructureManager.getStructureWithPieceAt(BlockPos, HolderSet<Structure>)`
     （`net/minecraft/world/level/StructureManager.java:114`），与 `LocationPredicate.java:54` 同款语义
   - **每次现取，不缓存 `Holder`** —— 结构是数据包注册表，`/reload` 后旧 Holder 会陈旧
   - 配置里的结构 ID 写错时**静默不触发**；请打一条 DEBUG/INFO 日志（含解析后的 ID 与命中结果），
     否则「装了但从不触发」无从排查（这正是当前整合包尚未放入宝库时会出现的外观）
4. `findSpawnPos(level, vaultPos)` —— D10 的净空兜底：
   - 从 `vaultPos.above()` 起向上扫描，返回第一个能容纳 **0.9 × 3.5 格**（死王碰撞箱，
     `EntityRegistry.java:277-281`）净空的位置
   - 上限 `+8`；全部不满足则**返回 `vaultPos.above()`** 并打 `LOGGER.warn`
     —— 忠于「出现在宝库顶部」这一需求，不擅自改到别处
   - 净空判据用 `level.noCollision(AABB)` 或逐格 `getBlockState(..).isAir()` 皆可；
     推荐前者（一次调用，且自动考虑非完整方块）

**Verification:**
```powershell
.\gradlew.bat build     # exit 0
Select-String -Path src\main\java\com\zonlong\beloong\dreadking\DreadKingRitualStarter.java -Pattern "getStructureWithPieceAt|enabled.get|findSpawnPos"
```
> 本任务**无法单独实机验证**（没有触发者）。它的行为由 T5 完成后的用例 3/4 覆盖。
> 若希望提前验证，可临时在 `BeLoongCore` 挂一个调试监听器调用 `start(...)`，**验完删除**。

**Commit:** `新增死王仪式编排层（结构判定与刷怪点）`

---

### T5 —— 检测层：Mixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/minecraft/DreadKingRitualTriggerMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`

**Steps:**
1. `beloong.mixins.json` 的 **`mixins` 数组**（**不是** `client`）加一条 `"minecraft.DreadKingRitualTriggerMixin"`。
   放错数组 ⇒ 服务端不会应用它 ⇒ 功能静默失效（`defaultRequire` 只管已声明的注入点）。
   包分类规范（D16）：目标是**原版**类 ⇒ 放 `mixin/minecraft/`，`mixins.json` 条目带同名前缀；
   目标是第三方模组的则放 `mixin/<该模组命名空间>/`。
2. Mixin 类：
   ```java
   @Mixin(VaultBlockEntity.Server.class)
   public class DreadKingRitualTriggerMixin {
       @Inject(method = "tryInsertKey", remap = false,
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/world/level/block/entity/vault/VaultBlockEntity$Server;unlock"
                               + "(Lnet/minecraft/server/level/ServerLevel;"
                               + "Lnet/minecraft/world/level/block/state/BlockState;"
                               + "Lnet/minecraft/core/BlockPos;"
                               + "Lnet/minecraft/world/level/block/entity/vault/VaultConfig;"
                               + "Lnet/minecraft/world/level/block/entity/vault/VaultServerData;"
                               + "Lnet/minecraft/world/level/block/entity/vault/VaultSharedData;"
                               + "Ljava/util/List;)V",
                        shift = At.Shift.BEFORE, remap = false))
       private static void beloong$onKeyAccepted(ServerLevel level, BlockPos pos, BlockState state,
                                                 VaultConfig config, VaultServerData serverData,
                                                 VaultSharedData sharedData, Player player,
                                                 ItemStack stack, CallbackInfo ci) {
           if (player instanceof ServerPlayer serverPlayer) {
               DreadKingRitualStarter.start(level, pos, serverPlayer);
           }
       }
   }
   ```
3. ⚠️ **两处 `remap = false` 是必需的，不是可选风格**：原版目标必须显式关掉重映射 ——
   NeoForge 运行时即用 Mojang 官方名（无混淆），dev 命名空间 == 运行时命名空间。
   若留默认 `true`，mixin 注解处理器会**直接报错**（不是警告）：
   `Unable to locate obfuscation mapping for @Inject target tryInsertKey`，构建失败。
   项目内先例：`PossibleBiomesFilterMixin`（原版 `BiomeSource`）与 `CloneParameterListMixin`
   （TerraBlender）都写了 `remap = false`。
4. **处理器签名必须逐字匹配 `tryInsertKey` 的形参表**（`VaultBlockEntity.java:268-277`）——
   **不是 `unlock` 的**（`unlock` 的参数顺序是 `level, state, pos, ...` 且**不含 player`**）。
   全部 8 个形参 + `CallbackInfo` 一个不能少、顺序不能错。
   描述符本身已用 `javap -c` 对 `build/moddev/artifacts/neoforge-21.1.236-merged.jar` 核对过，
   并确认 `tryInsertKey` 内 `unlock` 只有一处 `invokestatic` 调用点。
5. 注入点之所以选 `unlock` 的 INVOKE：它是「钥匙被接受」的唯一位置（`unlock()` 全类只有一处调用，
   见 `VaultBlockEntity.java:289`），因此**被拒绝的路径（无效钥匙 / 已领奖）天然不触发**。
6. 本类**只做转发**，不含任何判定逻辑（判定在 T4）。转发调用**自身兜异常**：本 mixin 运行在原版
   开箱流程内部，若我们的代码抛出会连带破坏原版开箱，仪式不该有能力弄坏它。

**Verification:**
```powershell
.\gradlew.bat build     # exit 0
# 1) mixins.json 条目在正确的数组里（且不在 client 数组）
Select-String -Path src\main\resources\beloong.mixins.json -Pattern "DreadKingRitualTriggerMixin"
# 2) jar 内 mixins.json 的 mixins 数组含该条目、client 数组不含
```
> ⚠️ **关于「启动即校验」的一个修正**：`defaultRequire = 1` 的校验发生在**目标类被加载、mixin 被应用**
> 的那一刻，而**不是**模组加载期。`VaultBlockEntity$Server` 只在「世界里存在宝库方块、其 ticking
> 被创建」时才会被加载，因此**启动到主菜单并不足以**验证本注入点。
> ⇒ 本任务的运行时校验**并入 T6 用例 3**（在城堡内开启黯影宝库 ⇒ 该步必然加载目标类）。
> 若描述符写错，那一步会**响亮崩溃**（`InjectionError`/`Critical injection failure`），
> 不会静默失效——这是可以接受的验证时点。必要时用 `javap -p` 核对 `unlock` 的完整签名。

**Commit:** `新增死王仪式检测层（mixin 注入 vault unlock）`

---

### T6 —— 端到端实机验收

**Files:** 无（纯运行验证）

**前置：** 在城堡内手动放置一个黯影宝库（见 §〇之三）。

**Steps:** 执行设计文档 §七 B 的 9 条用例，逐条记录证据：

| # | 操作 | 期望 |
|---|---|---|
| 1 | `/summon beloong:dread_king_ritual_marker`（宝库顶） | 音效 + 140 tick 后原地出现死王 —— **T3 已过** |
| 2 | 检查死王为不祥态 | **T3 已过** |
| 3 | 城堡内用暗影钥匙开黯影宝库 | 与 1 相同 |
| 4 | **非**城堡处放置黯影宝库并开启 | **不触发** |
| 5 | 用**备用**暗影钥匙对**同一已开过**的宝库再右键 | **不触发**（方案 C 的核心收益） |
| 6 | 16 格外 / 音效响起后进场 | 听不到 / 只听到剩余部分（D4 已知局限） |
| 7a | 打死**仪式召唤**的不祥死王 | **不生成**灵魂 —— **T3 已过** |
| 7b | 打死**尸体复活**途径的死王（普通/不祥各一次） | **仍生成**灵魂 —— 回归闸门，证明 D20 没把灵魂全局弄没 |
| 8 | 仪式中走远至区块卸载，再回来 | 倒计时从暂停处继续，音效可能已放完 |
| 9 | `/kill @e[type=beloong:dread_king_ritual_marker]` | 不召唤 |
| 10 | 仪式进行中**重启服务器**（停服再开） | 倒计时从**剩余** tick 续跑；**音效不重放** —— 这条专门验证 `lifeTicks`/`musicPlayed` 真的落进了 NBT（`Entity.saveWithoutId` 不写 `tickCount`，所以这也是「不能用 tickCount 计时」的实证） |
| 11 | 仪式进行中把宝库顶那一格用方块堵死 | 死王出现在净空扫描找到的位置；若日志出现 WARN 则说明该房间连 +8 格净空都没有（见 DoD 第 7 条） |
| 12 | 仪式期间观察宝库顶周围 | 半径 3 格内持续出现 `blood_ground` 血渍，且**圆外四角没有**（验证 D22 的「圆盘而非方阵」）；地形不平时血渍各自落在脚下表面 |

**计时取证：** 用 `/time query gametime`（项目已验证 RCON 可用）在音乐开始与死王出现各取一次，
差值应为 **140 tick** ± 少量。

**回归面（必须一并检查）：**
- 宝库的**正常开箱**不受影响：战利品照常喷出、`dragonsurvival:dark/open_vault` 进度照常授予
- 非城堡的其他黯影宝库（`treasure_angry_*` 里自然生成的）**不触发** —— 这是配置项默认值的正确行为，
  也是本功能与整合包后续动作的边界
- 关闭配置 `enabled = false` 后重启，宝库照常开箱、无仪式

**Commit:** 无（若验收暴露缺陷，回到对应任务修复并追加提交）

---

## 二、提交策略

每个任务一个原子提交（T1–T5 共 5 个，T6 视修复情况追加），便于回滚与二分。
提交信息沿用仓库风格（中文、动词开头）：
`新增死王仪式配置节与实体 lang 键` / `新增死王仪式标记实体（执行层）` /
`注册死王仪式标记实体（beloong:dread_king_ritual_marker）` /
`新增死王仪式编排层（结构判定与刷怪点）` / `新增死王仪式检测层（mixin 注入 vault unlock）`

**注意**：`memory/` 被 gitignore，本计划涉及的 memory 更新不会进入提交（设计阶段已更新完毕）。

## 三、风险与回退

| # | 风险 | 概率 | 影响 | 处置 |
|---|---|---|---|---|
| 1 | **mixin 描述符写错** | 中 | 启动崩溃 | **响亮失败，符合预期**。按 `javap -p` 核对 `unlock` 签名后修正；`defaultRequire = 1` 保证不会静默失效 |
| 2 | **`getStructureWithPieceAt` 判定过严** —— 它只认**结构拼图块**范围，不认整个结构包围盒 | 低 | 手动放置的宝库若不落在任何拼图块内 ⇒ 不触发 | 回退到 `getStructureAt(pos, structure)`（包围盒级，项目内先例：`StructureEffectHandler.java:76`）。两者判定口径的差异已记在设计文档 D8 |
| 3 | **验收时城堡里没有宝库**（整合包尚未放入） | **高** | 用例 3/4/5 无法执行 | §〇之三：先 `/setblock` 手动放置。**这是流程问题，不是代码问题** |
| 4 | 死王在矮房间里窒息/卡住 | 中 | 观感与战斗失效 | D10 的净空扫描（上限 +8）+ 扫不到时的 WARN 日志。若日志频繁出现，说明该结构的天花板普遍过低，需回头调整锚点策略 |
| 5 | 玩家在 16 格外 ⇒ 听不到音乐但 Boss 照样出现 | **确定** | 已知行为 | D5 用户已确认。若反馈不佳，只需调大 `musicVolume`（半径随之为 `16 × volume` 格） |
| 6 | 音乐与倒计时因区块暂停而错位（无声倒计时） | 中 | 已知行为 | D12 用户已确认。若要消除，需改为强制加载区块（已被否决）或让音乐也随区块暂停（客户端音效做不到） |
| 7 | 铁魔法/龙之生存大版本更新导致 mixin 目标或 public API 变动 | 低 | 崩溃（而非静默） | 依赖是 `required` 且版本范围已声明；`defaultRequire = 1` 会在启动时暴露 |

## 四、完成定义（DoD）

1. `.\gradlew.bat build` 退出码 0，产物 `build/libs/beloong-0.9.6.jar`
2. `beloong.mixins.json` 的新条目位于 **`mixins`** 数组
3. `ModEntities` 含 `DREAD_KING_RITUAL_MARKER`，且**未**带 `.updateInterval`
4. 两个 lang 文件都含 `entity.beloong.dread_king_ritual_marker`
5. 设计文档 §七 B 的 10 条用例逐条有证据；计时差值 = 140 tick ± 少量
6. 回归面三条全过（正常开箱不受影响 / 非城堡不触发 / `enabled = false` 生效）
7. 实机日志中**没有** `findSpawnPos` 的 WARN（若有，需在设计文档 §八 记录该结构的净空状况）
8. 实机日志中**没有** `Ticking entity` 崩溃报告 —— 有则说明 `tick()` 内仍有 `catch` 未覆盖的抛出路径
   （D18 的兜底失效），必须修掉而不是放过
9. 用例 10（重启续跑 + 音乐不重放）通过 ⇒ 证明 `lifeTicks`/`musicPlayed` 的 NBT 往返正确
