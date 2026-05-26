# FTB Chunks 领地保护兼容 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 Mixin 注入龙之生存的方块破坏方法，在破坏前检查 FTB Chunks 领地归属，阻止越权破坏。

**Architecture:** 一个 Mixin 类注入 `DragonDestructionHandler` 的两个 lambda 方法，拦截三次破坏调用（连锁挖掘 `destroyBlock` + 大型龙碰撞 `destroyBlock`/`removeBlock`），每次调用前用 `FTBChunksAPI.api().getManager().shouldPreventInteraction(...)` 检查领地保护。

**Tech Stack:** Java 21, NeoForge 1.21.1, Mixin 0.8, Dragon Survival 2.0.53+, FTB Chunks 2101.1.8+

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/java/com/zonlong/beloong/mixin/DragonDestructionHandlerMixin.java` | 新建 | 拦截 DS 破坏调用，添加 FTB Chunks 检查 |
| `src/main/resources/beloong.mixins.json` | 修改 | 注册 server 端 mixin |
| `src/main/java/com/zonlong/beloong/Config.java` | 修改 | 添加 `FIX_FTB_CHUNKS_COMPAT` 服务端开关 |
| `build.gradle` | 修改 | 添加 FTB Chunks compileOnly + localRuntime |
| `src/main/templates/META-INF/neoforge.mods.toml` | 修改 | 添加 ftbchunks 可选依赖 |

---

### Task 1: 添加 FTB Chunks 依赖

**Files:**
- Modify: `build.gradle:125-151`
- Modify: `src/main/templates/META-INF/neoforge.mods.toml:90-102`

- [ ] **Step 1: 在 build.gradle 中添加 FTB Chunks 依赖**

在 `dependencies` 块的 Dragon Survival 和 GeckoLib 声明之后，添加：

```groovy
    // FTB Chunks - optional compatibility for claim protection
    compileOnly "curse.maven:ftb-chunks-forge-314906:6295696"
    localRuntime "curse.maven:ftb-chunks-forge-314906:6295696"
```

`compileOnly` 确保编译时可用但不打包进 JAR；`localRuntime` 确保本地测试可用。

- [ ] **Step 2: 在 neoforge.mods.toml 中添加 ftbchunks 可选依赖**

在最后一个 `[[dependencies.${mod_id}]]` 块之后添加：

```toml
[[dependencies.${mod_id}]]
    modId="ftbchunks"
    type="optional"
    ordering="AFTER"
    side="BOTH"
```

- [ ] **Step 3: 刷新 Gradle 依赖**

```bash
./gradlew --refresh-dependencies
```

验证：building 成功，且能在外部库中看到 `FTB-Chunks-neoforge-2101.1.8.jar`。

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/main/templates/META-INF/neoforge.mods.toml
git commit -m "feat: 添加 FTB Chunks 可选依赖"
```

---

### Task 2: 添加配置开关

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java:32-38`

- [ ] **Step 1: 在 Config.java 服务端配置中添加开关**

将现有的空服务端配置块：

```java
    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    // 暂无服务端配置项，保留空配置用于未来拓展

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
```

替换为：

```java
    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    /** 修复 FTB Chunks 领地保护兼容（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_FTB_CHUNKS_COMPAT = SERVER_BUILDER
            .comment("修复 FTB Chunks 领地保护兼容 — 阻止龙之生存破坏其他玩家认领的区块")
            .translation("config.beloong.fixFTBChunksCompat")
            .define("fixFTBChunksCompat", true);

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git commit -m "feat: 添加 FTB Chunks 兼容配置开关"
```

---

### Task 3: 编写 DragonDestructionHandlerMixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/DragonDestructionHandlerMixin.java`

- [ ] **Step 1: 创建 Mixin 类文件**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.server.handlers.DragonDestructionHandler;
import com.zonlong.beloong.Config;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为龙之生存的方块破坏行为添加 FTB Chunks 领地保护兼容。
 *
 * <p>拦截 DragonDestructionHandler 中两处破坏逻辑：
 * <ul>
 *   <li>连锁挖掘（destroyBlocksInRadius）— player.gameMode.destroyBlock()</li>
 *   <li>大型龙碰撞破坏（checkAndDestroyCollidingBlocks）— level.destroyBlock() / level.removeBlock()</li>
 * </ul>
 */
@Mixin(DragonDestructionHandler.class)
public abstract class DragonDestructionHandlerMixin {

    /**
     * 检查指定位置是否受 FTB Chunks 领地保护。
     *
     * @param actor 执行操作的实体（通常是玩家）
     * @param pos   目标方块位置
     * @return true 如果该位置受保护且操作应被阻止
     */
    private static boolean isProtected(Entity actor, BlockPos pos) {
        if (!ModList.get().isLoaded("ftbchunks")) {
            return false;
        }

        if (!Config.FIX_FTB_CHUNKS_COMPAT.get()) {
            return false;
        }

        return FTBChunksAPI.api().getManager()
                .shouldPreventInteraction(actor, InteractionHand.MAIN_HAND, pos, Protection.EDIT_BLOCK, null);
    }

    // ========== 连锁挖掘 ==========

    /**
     * 拦截 lambda$destroyBlocksInRadius$1 中的 player.gameMode.destroyBlock(position) 调用。
     * Lambda 签名: (BlockEvent.BreakEvent, float, ServerPlayer, BlockPos) -> void
     */
    @Inject(
            method = "lambda$destroyBlocksInRadius$1",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z"
            ),
            cancellable = true,
            remap = false
    )
    private static void beforeMultiMiningDestroyBlock(ServerPlayer player, BlockPos pos, CallbackInfo ci) {
        if (isProtected(player, pos)) {
            ci.cancel();
        }
    }

    // ========== 大型龙碰撞破坏 ==========

    /**
     * 拦截 lambda$checkAndDestroyCollidingBlocks$0 中的 level.destroyBlock(position, false) 调用。
     * Lambda 签名: (MiscCodecs.DestructionData, PlayerTickEvent, BlockPos) -> void
     */
    @Inject(
            method = "lambda$checkAndDestroyCollidingBlocks$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"
            ),
            cancellable = true,
            remap = false
    )
    private static void beforeTrampleDestroyBlock(PlayerTickEvent event, BlockPos pos, CallbackInfo ci) {
        if (isProtected(event.getEntity(), pos)) {
            ci.cancel();
        }
    }

    /**
     * 拦截 lambda$checkAndDestroyCollidingBlocks$0 中的 level.removeBlock(position, false) 调用。
     * Lambda 签名同上，仅目标方法不同。
     */
    @Inject(
            method = "lambda$checkAndDestroyCollidingBlocks$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"
            ),
            cancellable = true,
            remap = false
    )
    private static void beforeTrampleRemoveBlock(PlayerTickEvent event, BlockPos pos, CallbackInfo ci) {
        if (isProtected(event.getEntity(), pos)) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew build
```

确保编译通过，无错误。如果 lambda 方法名不匹配（因 DS 版本差异），检查实际编译产物中的方法名并调整。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/zonlong/beloong/mixin/DragonDestructionHandlerMixin.java
git commit -m "feat: 添加 DragonDestructionHandler Mixin 实现 FTB Chunks 兼容"
```

---

### Task 4: 注册 Mixin 配置

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`

- [ ] **Step 1: 添加 server mixin 列表**

将 `beloong.mixins.json` 从：

```json
{
  "required": true,
  "package": "com.zonlong.beloong.mixin",
  "client": [
    "ClientFlightHandlerMixin",
    "DragonItemRenderLayerMixin",
    "OutlineBufferSourceAccessor"
  ],
  "compatibilityLevel": "JAVA_21",
  "injectors": {
    "defaultRequire": 1
  }
}
```

修改为：

```json
{
  "required": true,
  "package": "com.zonlong.beloong.mixin",
  "client": [
    "ClientFlightHandlerMixin",
    "DragonItemRenderLayerMixin",
    "OutlineBufferSourceAccessor"
  ],
  "server": [
    "DragonDestructionHandlerMixin"
  ],
  "compatibilityLevel": "JAVA_21",
  "injectors": {
    "defaultRequire": 1
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/beloong.mixins.json
git commit -m "feat: 在 mixin 配置中注册 DragonDestructionHandlerMixin"
```

---

### Task 5: 构建并验证

- [ ] **Step 1: 完整构建**

```bash
./gradlew clean build
```

确保 BUILD SUCCESSFUL。

- [ ] **Step 2: 检查输出 JAR**

```bash
unzip -l build/libs/beloong-*.jar | grep -E "(DragonDestructionHandler|beloong.mixins)"
```

确认 `DragonDestructionHandlerMixin.class` 和 `beloong.mixins.json` 都包含在内。

- [ ] **Step 3: Commit（如有修正）**

如有任何构建问题修复，提交修正。

---

### Task 6: 运行游戏验证

- [ ] **Step 1: 启动 Minecraft 客户端**

```bash
./gradlew runClient
```

- [ ] **Step 2: 验证 FTB Chunks 未安装时仍正常工作**

在未安装 FTB Chunks 的环境中：
- 进入世界，变身为龙
- 使用连锁挖掘（multi-mining）功能破坏方块 → 应正常工作
- 大型龙碰撞破坏方块 → 应正常工作
- 检查日志无报错

- [ ] **Step 3: 验证 FTB Chunks 安装时领地保护生效**

在安装了 FTB Chunks 的环境中：
- 两个玩家，玩家 A 认领区块
- 玩家 B（龙形态）尝试在玩家 A 的区块内破坏方块 → 应被阻止
- 玩家 B（龙形态）在玩家 A 的区块内移动（大型龙碰撞） → 不应破坏区块内方块
- 玩家 B 在自己的区块内破坏方块 → 应正常工作

- [ ] **Step 4: 验证配置开关**

- 将 `fixFTBChunksCompat` 设为 `false`，重启游戏
- 重复步骤 3 → 领地保护不再生效（回到原始行为）
- 检查 `/config/beloong-server.toml` 文件包含正确配置项

- [ ] **Step 5: 最终提交**

```bash
git add -A
git commit -m "chore: 完成 FTB Chunks 兼容功能验证"
```
