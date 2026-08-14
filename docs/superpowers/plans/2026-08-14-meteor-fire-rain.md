# 流星火雨（Meteor Fire Rain）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为天灾维度 `beloong:disaster` 实现专属天气事件「流星火雨」——随机/命令触发，天空染红 + 陨石尾焰 + 落地大爆炸，破坏方块并伤害所有生物（含玩家）。

**Architecture:** 服务端权威内存态状态机（`INACTIVE → ACTIVE → COOLDOWN`）+ 自定义 `MeteorEntity`（重力下坠 + 尾焰 + 落地爆炸）+ `MeteorRainSyncPayload` 网络同步客户端氛围 + `beloongcore weather meteorrain` 命令。所有调优参数走服务端 TOML 配置 `meteor_rain`。

**Tech Stack:** Minecraft 1.21.1, NeoForge 21.1.219, Java 21, Parchment 2024.11.17（无新增 Mixin）。

**设计文档:** `docs/superpowers/specs/2026-08-14-meteor-fire-rain-design.md`

---

### Task 1: `Config.java` 添加 `meteor_rain` 服务端配置节

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: 在 `Config.java` 顶部声明 `MeteorRain` 内部类字段**

在 `StructureEffects` 内部类之后（约第 154 行）、`static {}` 块之前添加：

```java
// ==================== meteor_rain ====================

public static final class MeteorRain {
    private MeteorRain() {}

    public static ModConfigSpec.BooleanValue enabled;
    public static ModConfigSpec.DoubleValue triggerChance;
    public static ModConfigSpec.IntValue checkIntervalTicks;
    public static ModConfigSpec.IntValue minDurationTicks;
    public static ModConfigSpec.IntValue maxDurationTicks;
    public static ModConfigSpec.IntValue cooldownTicks;
    public static ModConfigSpec.IntValue meteorsPerPlayerPerSpawn;
    public static ModConfigSpec.IntValue spawnIntervalTicks;
    public static ModConfigSpec.IntValue spawnRadius;
    public static ModConfigSpec.IntValue spawnHeight;
    public static ModConfigSpec.DoubleValue explosionPower;
    public static ModConfigSpec.DoubleValue entityDamage;
    public static ModConfigSpec.BooleanValue fire;
}
```

- [ ] **Step 2: 在 `static {}` 块末尾（`structure_effects` pop 之后、`SERVER_SPEC` build 之前）添加配置赋值**

```java
// ========== meteor_rain ==========
SERVER_BUILDER.push("meteor_rain");

MeteorRain.enabled = SERVER_BUILDER
        .comment("是否启用流星火雨天气（仅天灾维度）")
        .define("enabled", true);
MeteorRain.triggerChance = SERVER_BUILDER
        .comment("每次判定（每 checkIntervalTicks）触发流星火雨的概率，0.0~1.0")
        .defineInRange("triggerChance", 0.001, 0.0, 1.0);
MeteorRain.checkIntervalTicks = SERVER_BUILDER
        .comment("状态机判定间隔（ticks），默认 100 = 5 秒")
        .defineInRange("checkIntervalTicks", 100, 1, 1200);
MeteorRain.minDurationTicks = SERVER_BUILDER
        .comment("流星火雨最短持续时间（ticks），默认 600 = 30 秒")
        .defineInRange("minDurationTicks", 600, 20, 72000);
MeteorRain.maxDurationTicks = SERVER_BUILDER
        .comment("流星火雨最长持续时间（ticks），默认 2400 = 2 分钟")
        .defineInRange("maxDurationTicks", 2400, 20, 72000);
MeteorRain.cooldownTicks = SERVER_BUILDER
        .comment("结束后的冷却时间（ticks），默认 12000 = 10 分钟")
        .defineInRange("cooldownTicks", 12000, 0, 72000);
MeteorRain.meteorsPerPlayerPerSpawn = SERVER_BUILDER
        .comment("每次生成波次每玩家的陨石数量上限")
        .defineInRange("meteorsPerPlayerPerSpawn", 3, 1, 64);
MeteorRain.spawnIntervalTicks = SERVER_BUILDER
        .comment("生成波次间隔（ticks），默认 40 = 2 秒")
        .defineInRange("spawnIntervalTicks", 40, 1, 1200);
MeteorRain.spawnRadius = SERVER_BUILDER
        .comment("玩家附近陨石落点半径（方块）")
        .defineInRange("spawnRadius", 24, 4, 128);
MeteorRain.spawnHeight = SERVER_BUILDER
        .comment("陨石生成高度（Y），应高于天际")
        .defineInRange("spawnHeight", 320, 128, 512);
MeteorRain.explosionPower = SERVER_BUILDER
        .comment("爆炸威力（TNT 为 4.0）")
        .defineInRange("explosionPower", 5.0, 1.0, 64.0);
MeteorRain.entityDamage = SERVER_BUILDER
        .comment("对范围内生物的额外直接伤害")
        .defineInRange("entityDamage", 20.0, 0.0, 1000.0);
MeteorRain.fire = SERVER_BUILDER
        .comment("爆炸是否产生火焰")
        .define("fire", true);

SERVER_BUILDER.pop(); // meteor_rain
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileJava
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "feat: 添加 meteor_rain 服务端配置节"
```

---

### Task 2: 创建伤害类型资源与语言键、陨石纹理占位

**Files:**
- Create: `src/main/resources/data/beloong/damage_type/meteor.json`
- Create: `src/main/resources/assets/beloong/textures/entity/meteor.png`（占位纹理，可先复制 `assets/beloong/textures/item/eternal_porkchop.png` 或使用原版岩浆纹理）
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`

- [ ] **Step 1: 创建伤害类型 JSON**

`data/beloong/damage_type/meteor.json`：

```json
{
  "message_id": "beloong.meteor",
  "exhaustion": 0.1,
  "scaling": "when_caused_by_living_non_player",
  "effects": "burning",
  "death_message_type": "default"
}
```

- [ ] **Step 2: 更新语言文件**（追加到已有 JSON，勿覆盖）

`zh_cn.json` 追加：

```json
{
  "death.attack.beloong.meteor": "%1$s 被陨石砸中",
  "death.attack.beloong.meteor.player": "%1$s 在流星火雨中被 %2$s 的陨石砸中",
  "command.beloong.meteorrain.started": "已在天灾维度开始流星火雨",
  "command.beloong.meteorrain.stopped": "已停止天灾维度的流星火雨",
  "command.beloong.meteorrain.status.active": "流星火雨进行中，剩余 %s 秒",
  "command.beloong.meteorrain.status.inactive": "流星火雨未进行",
  "command.beloong.meteorrain.status.cooldown": "流星火雨冷却中，剩余 %s 秒",
  "command.beloong.meteorrain.only_disaster": "流星火雨仅在天灾维度（beloong:disaster）可用",
  "command.beloong.meteorrain.usage": "用法：beloongcore weather meteorrain [start|stop|status]",
  "message.beloong.meteorrain.start": "天灾降临，流星火雨开始了！",
  "message.beloong.meteorrain.end": "流星火雨结束了。"
}
```

`en_us.json` 追加对应英文。

- [ ] **Step 3: 编译验证**

```bash
./gradlew processResources
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/data/beloong/damage_type/ src/main/resources/assets/beloong/lang/ src/main/resources/assets/beloong/textures/entity/
git commit -m "feat: 添加 meteor 伤害类型、本地化与陨石纹理占位"
```

---

### Task 3: 创建 `registry/ModEntities.java` 与 `entity/MeteorEntity.java`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ModEntities.java`
- Create: `src/main/java/com/zonlong/beloong/entity/MeteorEntity.java`

- [ ] **Step 1: 创建 `ModEntities.java`**

```java
package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MeteorEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BeLoongCore.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<MeteorEntity>> METEOR =
            ENTITIES.register("meteor", () -> EntityType.Builder.<MeteorEntity>of(MeteorEntity::new, MobCategory.MISC)
                    .sized(0.98F, 0.98F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("meteor"));

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
```

- [ ] **Step 2: 创建 `MeteorEntity.java`**

核心：`Entity` 子类，重力下坠 + 尾焰粒子 + `fuseTicks` 兜底 + 落地 `explode()`。关键片段：

```java
package com.zonlong.beloong.entity;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModEntities;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.AABB;

public class MeteorEntity extends Entity {

    static final ResourceKey<DamageType> METEOR = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "meteor"));

    private int fuseTicks = 200; // 兜底自爆计时

    public MeteorEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public MeteorEntity(Level level, double x, double y, double z) {
        super(ModEntities.METEOR.get(), level);
        this.setPos(x, y, z);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("fuse", fuseTicks);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.fuseTicks = tag.getInt("fuse");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return; // 服务端权威

        // 尾焰粒子（在服务端发粒子，客户端渲染）
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 4,
                    0.3, 0.3, 0.3, 0.0);
            sl.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 2,
                    0.3, 0.3, 0.3, 0.0);
        }

        // 重力下坠
        setDeltaMovement(getDeltaMovement().add(0.0, -0.06, 0.0));
        move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());

        // 落地判定：触地 / 垂直碰撞 / 兜底计时
        if (onGround() || verticalCollision || --fuseTicks <= 0) {
            explode();
        }
    }

    private void explode() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            discard();
            return;
        }
        Holder<DamageType> type = serverLevel.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(METEOR);
        DamageSource source = new DamageSource(type, this);

        float power = Config.MeteorRain.explosionPower.get().floatValue();
        boolean fire = Config.MeteorRain.fire.get();
        serverLevel.explode(this, source, null, getX(), getY(), getZ(),
                power, fire, Explosion.BlockInteraction.DESTROY);

        // 额外直接伤害，保证“巨大伤害”
        float extraDamage = Config.MeteorRain.entityDamage.get().floatValue();
        if (extraDamage > 0) {
            double r = 6.0;
            for (LivingEntity target : serverLevel.getEntitiesOfClass(LivingEntity.class,
                    new AABB(getX() - r, getY() - r, getZ() - r,
                            getX() + r, getY() + r, getZ() + r))) {
                if (target.isAlive()) {
                    target.hurt(source, extraDamage);
                }
            }
        }
        discard();
    }
}
```

> 注：`ServerLevel.explode(...)` 的重载签名以 NeoForge 21.1.219 实际为准（`source`/`damageCalculator` 参数可能为 `@Nullable` 或使用 `Vec3` 版本），实现时若签名不符，按编译错误修正参数顺序/类型。

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileJava
```

预期：`MeteorEntity` 引用 `Config.MeteorRain` 已存在（Task 1 完成），编译通过；`ModEntities` 未被主类注册（留待 Task 9）。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/registry/ModEntities.java src/main/java/com/zonlong/beloong/entity/MeteorEntity.java
git commit -m "feat: 添加 MeteorEntity 自定义陨石实体与 ModEntities 注册"
```

---

### Task 4: 创建 `weather/MeteorRainState.java` 与 `weather/MeteorRainManager.java`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/weather/MeteorRainState.java`
- Create: `src/main/java/com/zonlong/beloong/weather/MeteorRainManager.java`

- [ ] **Step 1: 创建 `MeteorRainState.java`**

```java
package com.zonlong.beloong.weather;

public class MeteorRainState {
    public enum Phase { INACTIVE, ACTIVE, COOLDOWN }

    private Phase phase = Phase.INACTIVE;
    private int ticksRemaining;

    public Phase phase() { return phase; }
    public int ticksRemaining() { return ticksRemaining; }
    public boolean isActive() { return phase == Phase.ACTIVE; }

    public void start(int durationTicks) {
        this.phase = Phase.ACTIVE;
        this.ticksRemaining = durationTicks;
    }

    public void enterCooldown(int cooldownTicks) {
        this.phase = Phase.COOLDOWN;
        this.ticksRemaining = cooldownTicks;
    }

    public void stop() {
        this.phase = Phase.INACTIVE;
        this.ticksRemaining = 0;
    }

    /** 递减计时；返回 true 表示相位在本 tick 发生了迁移。 */
    public boolean decrementAndMaybeTransition(int cooldownTicks) {
        if (phase == Phase.INACTIVE) return false;
        if (--ticksRemaining > 0) return false;
        if (phase == Phase.ACTIVE) {
            enterCooldown(cooldownTicks);
        } else {
            stop();
        }
        return true;
    }
}
```

- [ ] **Step 2: 创建 `MeteorRainManager.java`**

```java
package com.zonlong.beloong.weather;

import com.zonlong.beloong.Config;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MeteorRainManager {

    public static final MeteorRainManager INSTANCE = new MeteorRainManager();

    private final Map<ResourceKey<Level>, MeteorRainState> states = new ConcurrentHashMap<>();

    private MeteorRainManager() {}

    public MeteorRainState stateFor(ResourceKey<Level> dimension) {
        return states.computeIfAbsent(dimension, k -> new MeteorRainState());
    }

    public void start(ServerLevel level) {
        MeteorRainState s = stateFor(level.dimension());
        int duration = randomBetween(Config.MeteorRain.minDurationTicks.get(),
                Config.MeteorRain.maxDurationTicks.get(), level);
        s.start(duration);
    }

    public void stop(ServerLevel level) {
        stateFor(level.dimension()).stop();
    }

    public boolean isActive(ResourceKey<Level> dimension) {
        return stateFor(dimension).isActive();
    }

    /** 推进一次状态机；返回 true 表示相位发生迁移（需要向客户端广播）。 */
    public boolean tick(ServerLevel level) {
        MeteorRainState s = stateFor(level.dimension());
        switch (s.phase()) {
            case INACTIVE -> {
                if (level.getRandom().nextDouble() < Config.MeteorRain.triggerChance.get()) {
                    start(level);
                    return true;
                }
                return false;
            }
            case ACTIVE, COOLDOWN -> {
                return s.decrementAndMaybeTransition(Config.MeteorRain.cooldownTicks.get());
            }
        }
        return false;
    }

    private static int randomBetween(int min, int max, ServerLevel level) {
        if (max <= min) return min;
        return min + level.getRandom().nextInt(max - min + 1);
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileJava
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/weather/
git commit -m "feat: 添加 MeteorRainState/MeteorRainManager 天气状态机"
```

---

### Task 5: 创建 `network/MeteorRainSyncPayload.java`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/network/MeteorRainSyncPayload.java`

- [ ] **Step 1: 创建 payload（仅同步 boolean active，客户端只关心自身维度）**

```java
package com.zonlong.beloong.network;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.ClientMeteorRainState;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MeteorRainSyncPayload(boolean active) implements CustomPacketPayload {

    public static final Type<MeteorRainSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "meteor_rain_sync"));

    public static final StreamCodec<ByteBuf, MeteorRainSyncPayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(MeteorRainSyncPayload::new, MeteorRainSyncPayload::active)
                    .cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(MeteorRainSyncPayload payload, IPayloadContext context) {
        ClientMeteorRainState.update(payload.active());
    }
}
```

> 注：payload 需用 `RegistryFriendlyByteBuf` 类型的 `STREAM_CODEC` 供 registrar 使用；实现时参照 `TreasureSyncPayload` 的 `.mapStream(buf -> (ByteBuf) buf)` 写法以保证 Play 阶段类型正确。

- [ ] **Step 2: 编译验证**

预期：因 `ClientMeteorRainState` 尚未创建而编译失败（留待 Task 8）。

```bash
./gradlew compileJava
```

- [ ] **Step 3: 提交（暂缓，待 Task 8 后与客户端一起提交）**

---

### Task 6: 创建 `weather/MeteorRainHandler.java`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/weather/MeteorRainHandler.java`

- [ ] **Step 1: 创建事件处理器**

职责：`ServerTickEvent.Post` 驱动状态机 + 生成陨石 + 状态变化广播；登录/换维度补发同步。

```java
package com.zonlong.beloong.weather;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.entity.MeteorEntity;
import com.zonlong.beloong.network.MeteorRainSyncPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class MeteorRainHandler {

    static final ResourceKey<net.minecraft.world.level.Level> DISASTER =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "disaster"));

    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!Config.MeteorRain.enabled.get()) return;

        ServerLevel disaster = event.getServer().getLevel(DISASTER);
        if (disaster == null) return;

        boolean anyPlayer = disaster.players().stream().anyMatch(ServerPlayer::isAlive);
        if (!anyPlayer) return;

        // 节流判定
        if (++tickCounter < Config.MeteorRain.checkIntervalTicks.get()) return;
        tickCounter = 0;

        boolean transitioned = MeteorRainManager.INSTANCE.tick(disaster);
        if (transitioned) {
            broadcast(disaster, MeteorRainManager.INSTANCE.isActive(DISASTER));
        }

        // ACTIVE 期间生成陨石（按 spawnIntervalTicks 波次）
        if (MeteorRainManager.INSTANCE.isActive(DISASTER)
                && tickCounter % Config.MeteorRain.spawnIntervalTicks.get() == 0) {
            for (ServerPlayer player : disaster.players()) {
                spawnMeteorsAround(player);
            }
        }
    }

    private void spawnMeteorsAround(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        int count = Config.MeteorRain.meteorsPerPlayerPerSpawn.get();
        int radius = Config.MeteorRain.spawnRadius.get();
        int height = Config.MeteorRain.spawnHeight.get();

        for (int i = 0; i < count; i++) {
            double x = player.getX() + (level.random.nextDouble() * 2 - 1) * radius;
            double z = player.getZ() + (level.random.nextDouble() * 2 - 1) * radius;
            MeteorEntity meteor = new MeteorEntity(level, x, height, z);
            level.addFreshEntity(meteor);
        }
    }

    private void broadcast(ServerLevel level, boolean active) {
        for (ServerPlayer p : level.players()) {
            PacketDistributor.sendToPlayer(p, new MeteorRainSyncPayload(active));
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(DISASTER)) return;
        PacketDistributor.sendToPlayer(player,
                new MeteorRainSyncPayload(MeteorRainManager.INSTANCE.isActive(DISASTER)));
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(DISASTER)) return;
        PacketDistributor.sendToPlayer(player,
                new MeteorRainSyncPayload(MeteorRainManager.INSTANCE.isActive(DISASTER)));
    }
}
```

> 注：波次生成与节流判定共用 `tickCounter`，需注意整除逻辑——若 `spawnIntervalTicks` 大于 `checkIntervalTicks`，用独立计数器 `spawnCounter` 替代，避免波次永远不触发。实现时以独立 `spawnCounter` 更稳妥。

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileJava
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/weather/MeteorRainHandler.java
git commit -m "feat: 添加 MeteorRainHandler 事件处理器（状态机驱动+陨石生成+同步）"
```

---

### Task 7: 创建 `command/MeteorRainCommand.java`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/command/MeteorRainCommand.java`

- [ ] **Step 1: 创建命令类（NeoForge 1.21.1 `RegisterCommandsEvent` + Brigadier）**

```java
package com.zonlong.beloong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.weather.MeteorRainHandler;
import com.zonlong.beloong.weather.MeteorRainManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.zonlong.beloong.network.MeteorRainSyncPayload;

public class MeteorRainCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("beloongcore")
                .then(Commands.literal("weather")
                        .then(Commands.literal("meteorrain")
                                .requires(src -> src.hasPermission(2))
                                .executes(this::status)
                                .then(Commands.literal("start").executes(this::start))
                                .then(Commands.literal("stop").executes(this::stop))
                                .then(Commands.literal("status").executes(this::status))
                        )
                )
        );
    }

    private int start(CommandContext<CommandSourceStack> ctx) {
        ServerLevel disaster = ctx.getSource().getServer().getLevel(MeteorRainHandler.DISASTER);
        if (disaster == null) {
            ctx.getSource().sendFailure(Component.translatable("command.beloong.meteorrain.only_disaster"));
            return 0;
        }
        MeteorRainManager.INSTANCE.start(disaster);
        broadcast(disaster, true);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.beloong.meteorrain.started"), true);
        return 1;
    }

    private int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel disaster = ctx.getSource().getServer().getLevel(MeteorRainHandler.DISASTER);
        if (disaster == null) {
            ctx.getSource().sendFailure(Component.translatable("command.beloong.meteorrain.only_disaster"));
            return 0;
        }
        MeteorRainManager.INSTANCE.stop(disaster);
        broadcast(disaster, false);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.beloong.meteorrain.stopped"), true);
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        var state = MeteorRainManager.INSTANCE.stateFor(MeteorRainHandler.DISASTER);
        Component msg = switch (state.phase()) {
            case ACTIVE -> Component.translatable("command.beloong.meteorrain.status.active",
                    state.ticksRemaining() / 20);
            case COOLDOWN -> Component.translatable("command.beloong.meteorrain.status.cooldown",
                    state.ticksRemaining() / 20);
            case INACTIVE -> Component.translatable("command.beloong.meteorrain.status.inactive");
        };
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private void broadcast(ServerLevel level, boolean active) {
        for (var p : level.players()) {
            PacketDistributor.sendToPlayer(p, new MeteorRainSyncPayload(active));
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileJava
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/command/MeteorRainCommand.java
git commit -m "feat: 添加 beloongcore weather meteorrain 命令"
```

---

### Task 8: 创建客户端氛围渲染（渲染器 + 客户端状态 + 事件）

**Files:**
- Create: `src/main/java/com/zonlong/beloong/client/ClientMeteorRainState.java`
- Create: `src/main/java/com/zonlong/beloong/client/MeteorEntityRenderer.java`
- Create: `src/main/java/com/zonlong/beloong/client/MeteorRainClientEvents.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`

- [ ] **Step 1: 创建 `ClientMeteorRainState.java`**

```java
package com.zonlong.beloong.client;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ClientMeteorRainState {
    private static boolean active;

    public static boolean isActive() { return active; }

    public static void update(boolean newActive) {
        if (active == newActive) return;
        active = newActive;
        // 开始/结束警告（字幕提示）
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable(newActive
                            ? "message.beloong.meteorrain.start"
                            : "message.beloong.meteorrain.end"), false);
        }
    }
}
```

- [ ] **Step 2: 创建 `MeteorEntityRenderer.java`**

发光岩石 billboard 渲染（火焰尾焰粒子已在服务端下发，此处仅渲染本体）。关键签名：

```java
package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.MeteorEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class MeteorEntityRenderer extends EntityRenderer<MeteorEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/meteor.png");

    public MeteorEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(MeteorEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(MeteorEntity entity, float yaw, float partialTicks,
                       PoseStack pose, MultiBufferSource buffer, int light) {
        // 简易 billboard 发光方块渲染：向摄像机面绘制一个带发光混合的纹理四边形，
        // 颜色带橙红偏色 + 满亮度（0xF000F0）。
        // 具体实现参照原版 FallingBlockEntity 渲染或使用 RenderType.entityTranslucent。
    }
}
```

> 注：完整的 billboard 渲染（`Matrix4f`/`VertexConsumer` + `RenderType.entityTranslucentEmissive`）在实现时按原版 `FallingBlockEntityRenderer` 或 `FireballRenderer` 的写法补全；本计划只固定类签名与纹理路径，避免早期锁定渲染细节。

- [ ] **Step 3: 创建 `MeteorRainClientEvents.java`**

天空染红/变暗 + 全屏暗红蒙层。关键钩子：

```java
package com.zonlong.beloong.client;

import com.zonlong.beloong.BeLoongCore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

@EventBusSubscriber(modid = BeLoongCore.MODID, value = Dist.CLIENT)
public class MeteorRainClientEvents {

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!ClientMeteorRainState.isActive()) return;
        // 染红/变暗：把雾色插值到暗红
        event.setRed(0.55f);
        event.setGreen(0.12f);
        event.setBlue(0.10f);
    }

    // 全屏暗红蒙层：订阅 RenderGuiLayerEvent（或 1.21.1 对应的 HUD 事件），
    // 在 active 时绘制一个半透明暗红 overlay；实现时按可用事件名修正。
}
```

- [ ] **Step 4: 在 `BeLoongCoreClient.java` 注册陨石渲染器**

在 `registerRenderers` 方法内添加：

```java
event.registerEntityRenderer(ModEntities.METEOR.get(), MeteorEntityRenderer::new);
```

并在文件顶部补充 `import com.zonlong.beloong.registry.ModEntities;` 与 `import com.zonlong.beloong.client.MeteorEntityRenderer;`。

- [ ] **Step 5: 编译验证**

```bash
./gradlew compileJava
```

- [ ] **Step 6: 提交（含 Task 5 的 payload）**

```bash
git add src/main/java/com/zonlong/beloong/network/MeteorRainSyncPayload.java src/main/java/com/zonlong/beloong/client/ src/main/java/com/zonlong/beloong/BeLoongCoreClient.java
git commit -m "feat: 添加流星火雨客户端氛围渲染与网络同步"
```

---

### Task 9: 在 `BeLoongCore.java` 装配全部子系统

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **Step 1: 注册实体、事件处理器、命令与网络包**

在构造函数中：

1. `ModItems.register(modEventBus);` 附近添加 `ModEntities.register(modEventBus);`
2. 事件处理器区域添加：
   ```java
   NeoForge.EVENT_BUS.register(new MeteorRainHandler());
   NeoForge.EVENT_BUS.register(new MeteorRainCommand());
   ```
3. 网络包注册区（现有 `RegisterPayloadHandlersEvent` lambda 内）追加：
   ```java
   evt.registrar(MODID).playToClient(
           MeteorRainSyncPayload.TYPE,
           MeteorRainSyncPayload.STREAM_CODEC,
           MeteorRainSyncPayload::handleClient);
   ```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileJava
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: 在 BeLoongCore 装配流星火雨子系统"
```

---

### Task 10: 完整构建与游戏内验证

- [ ] **Step 1: 完整构建**

```bash
./gradlew build
```

修复所有编译错误（尤其 `explode` 签名、payload codec、渲染事件名）。

- [ ] **Step 2: 启动游戏验证**

1. 进入天灾维度（通过现有天灾传送门）。
2. 执行 `beloongcore weather meteorrain start` → 观察天空染红、出现警告、陨石下坠带尾焰、落地爆炸并破坏方块、玩家/生物受伤。
3. 执行 `beloongcore weather meteorrain status` → 正确回报进行中/剩余秒数。
4. 执行 `beloongcore weather meteorrain stop` → 立即恢复，天空/蒙层消失。
5. 在**不同群系**重复验证陨石均出现；返回主世界/龙宫确认无陨石。
6. 随机验证：调小 `triggerChance`/`minDurationTicks`，等待自然触发与自然结束。
7. 边界验证：天灾维度无玩家时不生成；事件结束后进入冷却不立即重启；玩家在火雨中重登/换维度后氛围状态正确。

- [ ] **Step 3: 提交修复**

```bash
git add -A
git commit -m "fix: 流星火雨运行时修复与调优"
```

---

## 依赖顺序

Task 1（配置）→ Task 3（实体，引用配置）→ Task 4（状态机，引用配置）→ Task 5/8（网络 + 客户端，互引）→ Task 6（处理器，引用 1/3/4/5）→ Task 7（命令，引用 4/5/6）→ Task 9（装配）→ Task 10（验证）。

Task 2（资源）可与 Task 3 并行。
