# 维度传送机制实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现主世界与龙宫维度之间的双向传送——主世界飞到 8848 以上进入龙宫，龙宫掉到 0 以下返回主世界。

**Architecture:** 在 `Config.java` 的 `SERVER_SPEC` 中新增 `dimension_transport` 分类配置；新建 `DimensionTransportHandler` 通过 `PlayerTickEvent.Post` 检测 Y 坐标并执行传送，使用 Heightmap 查找安全落脚点。

**Tech Stack:** NeoForge 21.1, Minecraft 1.21.1, Java 21, ModConfigSpec

---

### Task 1: 扩展 Config.java 添加 dimension_transport 配置

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: 添加 dimension_transport 配置段到 SERVER_SPEC**

将 `Config.java` 中服务端配置部分（当前第46-49行）：

```java
private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
```

替换为以下内容。

关键设计：`DimensionTransport` 内部类仅持有字段引用，实际赋值和 `SERVER_SPEC` 构建在 `Config` 的 `static {}` 块中顺序执行，避免 JVM 类加载顺序问题。

```java
private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

// ==================== dimension_transport ====================

/** dimension_transport 配置字段聚合 */
public static final class DimensionTransport {
    private DimensionTransport() {}

    // 共享配置
    public static ModConfigSpec.IntValue checkIntervalTicks;
    public static ModConfigSpec.IntValue cooldownTicks;

    // overworld → loong_palace
    public static ModConfigSpec.BooleanValue owToLP_enabled;
    public static ModConfigSpec.IntValue owToLP_triggerY;
    public static ModConfigSpec.ConfigValue<String> owToLP_targetDimension;
    public static ModConfigSpec.DoubleValue owToLP_targetX;
    public static ModConfigSpec.DoubleValue owToLP_targetZ;
    public static ModConfigSpec.DoubleValue owToLP_fallbackY;

    // loong_palace → overworld
    public static ModConfigSpec.BooleanValue lpToOw_enabled;
    public static ModConfigSpec.IntValue lpToOw_triggerY;
    public static ModConfigSpec.ConfigValue<String> lpToOw_targetDimension;
    public static ModConfigSpec.DoubleValue lpToOw_targetX;
    public static ModConfigSpec.DoubleValue lpToOw_targetZ;
    public static ModConfigSpec.DoubleValue lpToOw_fallbackY;
}

static {
    SERVER_BUILDER.push("dimension_transport");

    DimensionTransport.checkIntervalTicks = SERVER_BUILDER
            .comment("玩家 Y 坐标检查间隔（ticks），默认 20 = 每秒一次")
            .defineInRange("checkIntervalTicks", 20, 1, 1200);

    DimensionTransport.cooldownTicks = SERVER_BUILDER
            .comment("传送后冷却时间（ticks），防止循环传送")
            .defineInRange("cooldownTicks", 100, 0, 72000);

    SERVER_BUILDER.push("overworldToLoongPalace");
    DimensionTransport.owToLP_enabled = SERVER_BUILDER
            .comment("是否启用主世界 → 龙宫传送")
            .define("enabled", true);
    DimensionTransport.owToLP_triggerY = SERVER_BUILDER
            .comment("触发传送的 Y 轴高度（玩家 Y > 此值时传送）")
            .defineInRange("triggerY", 8848, -4064, 100000);
    DimensionTransport.owToLP_targetDimension = SERVER_BUILDER
            .comment("目标维度 ID")
            .define("targetDimension", "beloong:loong_palace");
    DimensionTransport.owToLP_targetX = SERVER_BUILDER
            .comment("目标固定 X 坐标")
            .defineInRange("targetX", 0.0, -3.0E7, 3.0E7);
    DimensionTransport.owToLP_targetZ = SERVER_BUILDER
            .comment("目标固定 Z 坐标")
            .defineInRange("targetZ", 0.0, -3.0E7, 3.0E7);
    DimensionTransport.owToLP_fallbackY = SERVER_BUILDER
            .comment("高度图查找失败时的回退 Y 坐标")
            .defineInRange("fallbackY", 64.0, -2032.0, 2032.0);
    SERVER_BUILDER.pop();

    SERVER_BUILDER.push("loongPalaceToOverworld");
    DimensionTransport.lpToOw_enabled = SERVER_BUILDER
            .comment("是否启用龙宫 → 主世界传送")
            .define("enabled", true);
    DimensionTransport.lpToOw_triggerY = SERVER_BUILDER
            .comment("触发传送的 Y 轴高度（玩家 Y < 此值时传送）")
            .defineInRange("triggerY", 0, -2032, 2032);
    DimensionTransport.lpToOw_targetDimension = SERVER_BUILDER
            .comment("目标维度 ID")
            .define("targetDimension", "minecraft:overworld");
    DimensionTransport.lpToOw_targetX = SERVER_BUILDER
            .comment("目标固定 X 坐标")
            .defineInRange("targetX", 0.0, -3.0E7, 3.0E7);
    DimensionTransport.lpToOw_targetZ = SERVER_BUILDER
            .comment("目标固定 Z 坐标")
            .defineInRange("targetZ", 0.0, -3.0E7, 3.0E7);
    DimensionTransport.lpToOw_fallbackY = SERVER_BUILDER
            .comment("高度图查找失败时的回退 Y 坐标")
            .defineInRange("fallbackY", 64.0, -2032.0, 2032.0);
    SERVER_BUILDER.pop();

    SERVER_BUILDER.pop(); // dimension_transport
}

public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew build
```

预期：BUILD SUCCESSFUL（配置定义不涉及运行时逻辑，仅定义字段即可编译通过）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "feat: 添加 dimension_transport 配置段到 SERVER_SPEC"
```

---

### Task 2: 创建 DimensionTransportHandler

**Files:**
- Create: `src/main/java/com/zonlong/beloong/transport/DimensionTransportHandler.java`

- [ ] **Step 1: 创建 handler 类骨架**

```java
package com.zonlong.beloong.transport;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = BeLoongCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public class DimensionTransportHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每个玩家的冷却剩余 ticks */
    private static final Map<UUID, Integer> COOLDOWNS = new HashMap<>();

    /** 每个玩家的检查间隔计数器 */
    private static final Map<UUID, Integer> TICK_COUNTERS = new HashMap<>();

    private DimensionTransportHandler() {}
}
```

- [ ] **Step 2: 添加 PlayerTickEvent.Post 订阅方法**

```java
@SubscribeEvent
public static void onPlayerTick(PlayerTickEvent.Post event) {
    // 仅服务端处理
    if (!(event.getEntity() instanceof ServerPlayer player)) {
        return;
    }

    // 死亡或已移除的玩家跳过
    if (!player.isAlive() || player.isRemoved()) {
        return;
    }

    UUID uuid = player.getUUID();

    // 冷却递减
    int cooldown = COOLDOWNS.getOrDefault(uuid, 0);
    if (cooldown > 0) {
        COOLDOWNS.put(uuid, cooldown - 1);
        return;
    }

    // 间隔检查
    int interval = Config.DimensionTransport.checkIntervalTicks.get();
    int counter = TICK_COUNTERS.getOrDefault(uuid, 0) + 1;
    if (counter < interval) {
        TICK_COUNTERS.put(uuid, counter);
        return;
    }
    TICK_COUNTERS.put(uuid, 0);

    // 检查两个方向的触发条件
    tryTransport(player,
            player.level().dimension().location().toString(),
            Level.OVERWORLD.location().toString(),
            Config.DimensionTransport.owToLP_enabled.get(),
            Config.DimensionTransport.owToLP_triggerY.get(),
            Config.DimensionTransport.owToLP_targetDimension.get(),
            Config.DimensionTransport.owToLP_targetX.get(),
            Config.DimensionTransport.owToLP_targetZ.get(),
            Config.DimensionTransport.owToLP_fallbackY.get(),
            true); // overworld rule: trigger when Y > threshold

    tryTransport(player,
            player.level().dimension().location().toString(),
            ResourceLocation.fromNamespaceAndPath("beloong", "loong_palace").toString(),
            Config.DimensionTransport.lpToOw_enabled.get(),
            Config.DimensionTransport.lpToOw_triggerY.get(),
            Config.DimensionTransport.lpToOw_targetDimension.get(),
            Config.DimensionTransport.lpToOw_targetX.get(),
            Config.DimensionTransport.lpToOw_targetZ.get(),
            Config.DimensionTransport.lpToOw_fallbackY.get(),
            false); // loong palace rule: trigger when Y < threshold
}
```

- [ ] **Step 3: 添加 tryTransport 方法**

```java
/**
 * 检查并执行传送。
 *
 * @param above true = 玩家 Y > triggerY 时触发，false = 玩家 Y < triggerY 时触发
 */
private static void tryTransport(ServerPlayer player,
        String currentDim, String sourceDim,
        boolean enabled, int triggerY,
        String targetDimStr, double targetX, double targetZ, double fallbackY,
        boolean above) {

    if (!enabled) return;
    if (!currentDim.equals(sourceDim)) return;

    double playerY = player.getY();
    boolean triggered = above ? playerY > triggerY : playerY < triggerY;
    if (!triggered) return;

    // 解析目标维度
    ResourceLocation targetDimId = ResourceLocation.tryParse(targetDimStr);
    if (targetDimId == null) {
        LOGGER.warn("[DimensionTransport] 无效的目标维度 ID: {}", targetDimStr);
        return;
    }

    // 防止传送到同一维度
    if (currentDim.equals(targetDimId.toString())) return;

    // 获取目标 ServerLevel
    ServerLevel targetLevel = player.server.getLevel(
            net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, targetDimId));
    if (targetLevel == null) {
        LOGGER.warn("[DimensionTransport] 找不到目标维度: {}", targetDimId);
        return;
    }

    // 解除骑乘
    if (player.isPassenger()) {
        player.stopRiding();
    }

    // 高度图查找安全落脚点
    int topBlockY = targetLevel.getHeight(
            Heightmap.Types.MOTION_BLOCKING, (int) targetX, (int) targetZ);
    double safeY;
    if (topBlockY > targetLevel.getMinBuildHeight()) {
        safeY = topBlockY + 1.0;
    } else {
        safeY = fallbackY;
    }

    // 执行传送
    player.teleportTo(targetLevel,
            targetX, safeY, targetZ,
            player.getYRot(), player.getXRot());

    // 设置冷却
    UUID uuid = player.getUUID();
    COOLDOWNS.put(uuid, Config.DimensionTransport.cooldownTicks.get());
    TICK_COUNTERS.remove(uuid); // 传送后重置检查计数器

    LOGGER.debug("[DimensionTransport] {} 从 {} 传送到 {} ({}, {}, {})",
            player.getName().getString(), sourceDim, targetDimId,
            targetX, safeY, targetZ);
}
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew build
```

预期：BUILD SUCCESSFUL。修复任何编译错误（import 缺失等）。

- [ ] **Step 5: 补充玩家登出清理逻辑**

在 `DimensionTransportHandler` 类中添加第二个订阅方法，避免 `COOLDOWNS` 和 `TICK_COUNTERS` Map 无限增长：

```java
@SubscribeEvent
public static void onPlayerLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
    UUID uuid = event.getEntity().getUUID();
    COOLDOWNS.remove(uuid);
    TICK_COUNTERS.remove(uuid);
}
```

- [ ] **Step 6: 再次编译验证**

```bash
./gradlew build
```

预期：BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/zonlong/beloong/transport/DimensionTransportHandler.java
git commit -m "feat: 实现维度传送 tick 处理器"
```

---

### Task 3: 游戏内验证

- [ ] **Step 1: 启动游戏**

```bash
./gradlew runServer
```

- [ ] **Step 2: 验证配置生成**

检查 `run/server/config/beloong-server.toml` 是否包含完整 `[dimension_transport]` 段。

- [ ] **Step 3: 验证主世界 → 龙宫传送**

```
/execute in minecraft:overworld run tp @p 0 9000 0
```

预期：玩家被传送到龙宫维度配置的固定坐标

- [ ] **Step 4: 验证龙宫 → 主世界传送**

先在龙宫维度：
```
/execute in beloong:loong_palace run tp @p 0 -10 0
```

预期：玩家被传送到主世界维度配置的固定坐标

- [ ] **Step 5: 验证冷却机制**

连续触发传送，确认 5 秒内不会重复传送。

- [ ] **Step 6: 验证 enabled = false**

修改 `beloong-server.toml`，将 `enabled` 设为 `false`，重启服务器，确认不再触发传送。

- [ ] **Step 7: Commit**

```bash
# 如果有修改则提交，否则跳过
```
