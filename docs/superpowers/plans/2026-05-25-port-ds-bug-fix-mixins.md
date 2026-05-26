# 移植 DS Bug Fix Mixin 实现计划

> **面向执行代理：** 必须使用子技能：superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来逐任务实现此计划。步骤使用 checkbox (`- [ ]`) 语法追踪。

**目标：** 将 DS_bug_fix 模组的 3 个 mixin 类移植到 BeLoong-Core，修复龙之生存的两个 bug。

**架构：** 三个仅客户端的 mixin 类放在 `com.zonlong.beloong.mixin` 包下，由 `beloong.mixins.json` 注册。配置开关通过 `Config.java` 的两个布尔值管理，无需自定义配置界面。

**技术栈：** Minecraft 1.21.1, NeoForge 21.1.219, Mixin (via NeoForge MDG 2.x), Java 21

---

### 文件结构

| 操作 | 文件 |
|------|------|
| 新建 | `src/main/resources/beloong.mixins.json` |
| 新建 | `src/main/java/com/zonlong/beloong/mixin/OutlineBufferSourceAccessor.java` |
| 新建 | `src/main/java/com/zonlong/beloong/mixin/ClientFlightHandlerMixin.java` |
| 新建 | `src/main/java/com/zonlong/beloong/mixin/DragonItemRenderLayerMixin.java` |
| 修改 | `src/main/java/com/zonlong/beloong/Config.java` |

---

### Task 1: 创建 Mixin 配置文件

**文件：**
- 新建：`src/main/resources/beloong.mixins.json`

- [ ] **步骤 1: 创建 beloong.mixins.json**

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

- [ ] **步骤 2: 验证文件位置**

运行：`ls src/main/resources/beloong.mixins.json`

- [ ] **步骤 3: 提交**

```bash
git add src/main/resources/beloong.mixins.json
git commit -m "feat: 添加 mixin 配置文件"
```

---

### Task 2: 创建 OutlineBufferSourceAccessor

**文件：**
- 新建：`src/main/java/com/zonlong/beloong/mixin/OutlineBufferSourceAccessor.java`

- [ ] **步骤 1: 创建目录并编写访问器接口**

```bash
mkdir -p src/main/java/com/zonlong/beloong/mixin
```

然后写入文件：

```java
package com.zonlong.beloong.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OutlineBufferSource.class)
public interface OutlineBufferSourceAccessor {

    @Accessor("bufferSource")
    MultiBufferSource.BufferSource ds_bug_fix$getNormalBufferSource();

    @Accessor("outlineBufferSource")
    MultiBufferSource.BufferSource ds_bug_fix$getOutlineBufferSource();

    @Accessor("teamR")
    int ds_bug_fix$getTeamR();

    @Accessor("teamG")
    int ds_bug_fix$getTeamG();

    @Accessor("teamB")
    int ds_bug_fix$getTeamB();

    @Accessor("teamA")
    int ds_bug_fix$getTeamA();
}
```

- [ ] **步骤 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/OutlineBufferSourceAccessor.java
git commit -m "feat: 添加 OutlineBufferSource 访问器 mixin"
```

---

### Task 3: 创建 ClientFlightHandlerMixin

**文件：**
- 新建：`src/main/java/com/zonlong/beloong/mixin/ClientFlightHandlerMixin.java`

- [ ] **步骤 1: 写入 ClientFlightHandlerMixin**

```java
package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.zonlong.beloong.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientFlightHandler.class)
public abstract class ClientFlightHandlerMixin {

    @Shadow private static double ax;
    @Shadow private static double az;
    @Shadow private static double ay;

    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void fixStableHoverDrift(CallbackInfo ci) {
        if (!Config.FIX_STABLE_HOVER.get()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        DragonStateProvider.getOptional(player).ifPresent(handler -> {
            if (!handler.isDragon()) return;

            FlightData flightData = FlightData.getData(player);
            if (!flightData.isWingsSpread() || !flightData.hasFlight()) return;

            Input movement = player.input;
            boolean shouldHover = ServerFlightHandler.stableHover
                    && !movement.jumping
                    && !movement.shiftKeyDown
                    && !ServerFlightHandler.isSpin(player)
                    && !ServerFlightHandler.isGliding(player);

            boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;

            if (shouldHover && noMoveInput) {
                ax = 0.0;
                az = 0.0;

                if (player.isCreative()) {
                    ay = 0.0;
                    Vec3 delta = player.getDeltaMovement();
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
            }
        });
    }
}
```

- [ ] **步骤 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/ClientFlightHandlerMixin.java
git commit -m "feat: 添加稳定悬停漂移修复 mixin"
```

---

### Task 4: 创建 DragonItemRenderLayerMixin

**文件：**
- 新建：`src/main/java/com/zonlong/beloong/mixin/DragonItemRenderLayerMixin.java`

- [ ] **步骤 1: 写入 DragonItemRenderLayerMixin**

```java
package com.zonlong.beloong.mixin;

import com.mojang.blaze3d.vertex.*;
import com.zonlong.beloong.Config;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Optional;

@Mixin(targets = "by.dragonsurvivalteam.dragonsurvival.client.render.entity.dragon.DragonItemRenderLayer", remap = false)
public abstract class DragonItemRenderLayerMixin {

    @Unique
    private MultiBufferSource.BufferSource ds_bug_fix$itemOutlineBuf;

    @ModifyArgs(method = "renderStackForBone", at = @At(value = "INVOKE", target = "Lsoftware/bernie/geckolib/renderer/layer/BlockAndItemGeoLayer;renderStackForBone(Lcom/mojang/blaze3d/vertex/PoseStack;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lnet/minecraft/world/item/ItemStack;Lsoftware/bernie/geckolib/animatable/GeoAnimatable;Lnet/minecraft/client/renderer/MultiBufferSource;FII)V"), remap = false)
    private void ds_bug_fix$isolateOutlineBuffer(Args args) {
        if (!Config.FIX_GLOWING_OUTLINE.get()) {
            return;
        }
        MultiBufferSource bufferSource = args.get(4);
        if (!(bufferSource instanceof OutlineBufferSource outline)) {
            return;
        }

        MultiBufferSource.BufferSource normalBuf = ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getNormalBufferSource();
        int color = net.minecraft.util.FastColor.ARGB32.color(
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamA(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamR(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamG(),
                ((OutlineBufferSourceAccessor) outline).ds_bug_fix$getTeamB());

        ds_bug_fix$itemOutlineBuf = MultiBufferSource.immediate(new ByteBufferBuilder(1536));

        args.set(4, new MultiBufferSource() {
            @Override
            public VertexConsumer getBuffer(RenderType rt) {
                if (rt.isOutline()) {
                    return ds_bug_fix$itemOutlineBuf.getBuffer(rt);
                }
                VertexConsumer normal = normalBuf.getBuffer(rt);
                Optional<RenderType> outlineVariant = rt.outline();
                if (outlineVariant.isPresent()) {
                    VertexConsumer outConsumer = ds_bug_fix$itemOutlineBuf.getBuffer(outlineVariant.get());
                    return VertexMultiConsumer.create(colorReplacing(outConsumer, color), normal);
                }
                return normal;
            }
        });
    }

    @Inject(method = "renderStackForBone", at = @At("TAIL"), remap = false)
    private void ds_bug_fix$flushIsolatedOutline(CallbackInfo ci) {
        if (ds_bug_fix$itemOutlineBuf != null) {
            ds_bug_fix$itemOutlineBuf.endBatch();
            ds_bug_fix$itemOutlineBuf = null;
        }
    }

    @Unique
    private static VertexConsumer colorReplacing(VertexConsumer delegate, int color) {
        return new VertexConsumer() {
            public VertexConsumer addVertex(float x, float y, float z) {
                delegate.addVertex(x, y, z).setColor(color);
                return this;
            }
            public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
            public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
            public VertexConsumer setUv1(int u, int v) { return this; }
            public VertexConsumer setUv2(int u, int v) { return this; }
            public VertexConsumer setNormal(float x, float y, float z) { return this; }
        };
    }
}
```

- [ ] **步骤 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/DragonItemRenderLayerMixin.java
git commit -m "feat: 添加发光效果龙身体隐形修复 mixin"
```

---

### Task 5: 修改 Config.java

**文件：**
- 修改：`src/main/java/com/zonlong/beloong/Config.java`

- [ ] **步骤 1: 重写 Config.java**

```java
package com.zonlong.beloong;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FIX_GLOWING_OUTLINE = BUILDER
            .comment("修复手持发光物品时龙身体变隐形的问题")
            .define("fixGlowingOutline", true);

    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = BUILDER
            .comment("修复稳定悬停时的漂移问题（生存模式水平漂移，创造模式向上漂移）")
            .define("fixStableHover", true);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
```

- [ ] **步骤 2: 检查主类是否引用了旧字段**

需要确认 `BeLoongCore.java` 中的 `commonSetup` 方法是否使用了 `LOG_DIRT_BLOCK`、`MAGIC_NUMBER` 等已删除的示例字段。如果有，需要一并清理。

运行：`grep -n "Config\." src/main/java/com/zonlong/beloong/BeLoongCore.java`

- [ ] **步骤 3: 清理 BeLoongCore.java 中对旧配置字段的引用**

根据步骤 2 的结果，如果 `BeLoongCore.java:commonSetup` 中有以下引用：
- `Config.LOG_DIRT_BLOCK.getAsBoolean()`
- `Config.MAGIC_NUMBER_INTRODUCTION.get()`
- `Config.MAGIC_NUMBER.getAsInt()`
- `Config.ITEM_STRINGS.get()`

需要将 `BeLoongCore.java` 的 `commonSetup` 方法中的示例日志代码清理为：

```java
private void commonSetup(FMLCommonSetupEvent event) {
    LOGGER.info("HELLO FROM COMMON SETUP");
}
```

同时删除不再使用的 import：
- `net.minecraft.core.registries.BuiltInRegistries`
- `net.minecraft.world.level.block.Blocks`

- [ ] **步骤 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/Config.java src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: 重构配置，添加 bug 修复开关并移除示例条目"
```

---

### Task 6: 编译验证

- [ ] **步骤 1: 运行 Gradle 编译**

```bash
./gradlew build
```

预期：`BUILD SUCCESSFUL`

- [ ] **步骤 2: 检查编译产物**

```bash
ls build/libs/beloong-*.jar
```

预期：存在 `beloong-0.0.4.jar` 文件

---

### 完成

所有任务完成后，运行 `git log --oneline -5` 确认提交历史清晰完整。
