# 灾变结构生成高度修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 Mixin 修复 Burning_Arena_Structure 和 RuinedCitadelStructure，使其尊重数据包 `worldgen/structure` JSON 中定义的 `start_height` 配置。

**Architecture:** 每个 Mixin 三层改造 — (1) 替换 CODEC 静态字段以解析 `start_height` 等 JSON 字段，(2) `@Inject` 重写 `findGenerationPoint` 使用解析到的 HeightProvider，(3) 绕过 `private static generatePieces`，改为在 GenerationStub lambda 中直接调用 `public static start()`。使用 static `@Unique` 字段存储解析值（结构是注册表单例），避免实例字段强制转换问题。

**Tech Stack:** NeoForge 1.21.1, Mixin 0.8.7, SpongePowered ASM, Parchment mappings

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `Config.java` (修改) | 新增 `cataclysm_fix` 配置节 + 布尔开关 |
| `BurningArenaStructureMixin.java` (新建) | CODEC 替换 + `findGenerationPoint` 重写 + 静态存储 |
| `RuinedCitadelStructureMixin.java` (新建) | 同上模式，针对 Ruined Citadel |
| `beloong.mixins.json` (修改) | 注册两个新 Mixin |

---

### Task 1: 添加配置开关

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

- [ ] **Step 1: 在 SERVER_BUILDER 中新增 cataclysm_fix 配置节**

在 `Config.java` 的 `static {}` 块末尾（`SERVER_BUILDER.pop(); // disaster_portal` 之后、`public static final ModConfigSpec SERVER_SPEC` 之前）插入以下代码：

```java
        // ========== cataclysm_fix ==========
        SERVER_BUILDER.push("cataclysm_fix");

        SERVER_BUILDER.pop(); // cataclysm_fix
```

并在类体中声明对应的 `BooleanValue`：

在 `Config.java` 的 `DisasterPortal` 内部类之后、`static {}` 块之前插入：

```java
    // ==================== cataclysm_fix ====================

    public static final class CataclysmFix {
        private CataclysmFix() {}

        /** 修复灾变结构忽略 start_height 配置的问题（默认启用） */
        public static ModConfigSpec.BooleanValue fixCataclysmStructureHeight;
    }
```

- [ ] **Step 2: 在 static {} 中定义配置项**

在步骤 1 创建的 `SERVER_BUILDER.push("cataclysm_fix")` 和 `SERVER_BUILDER.pop()` 之间插入：

```java
        CataclysmFix.fixCataclysmStructureHeight = SERVER_BUILDER
                .comment("修复灾变结构（Burning Arena、Ruined Citadel）忽略 data pack 中 start_height 配置的问题",
                         "关闭后回退到灾变原版硬编码 Y 值")
                .define("fixCataclysmStructureHeight", true);
```

完整的 `cataclysm_fix` 节在 static {} 中应如下：

```java
        // ========== cataclysm_fix ==========
        SERVER_BUILDER.push("cataclysm_fix");

        CataclysmFix.fixCataclysmStructureHeight = SERVER_BUILDER
                .comment("修复灾变结构（Burning Arena、Ruined Citadel）忽略 data pack 中 start_height 配置的问题",
                         "关闭后回退到灾变原版硬编码 Y 值")
                .define("fixCataclysmStructureHeight", true);

        SERVER_BUILDER.pop(); // cataclysm_fix
```

- [ ] **Step 3: 编译验证**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`

Expected: BUILD SUCCESSFUL（无编译错误）

---

### Task 2: BurningArenaStructureMixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/BurningArenaStructureMixin.java`

- [ ] **Step 1: 创建 Mixin 类骨架**

```java
package com.zonlong.beloong.mixin;

import com.github.L_Ender.cataclysm.Cataclysm;
import com.github.L_Ender.cataclysm.structures.Burning_Arena_Structure;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(Burning_Arena_Structure.class)
public abstract class BurningArenaStructureMixin extends Structure {

    protected BurningArenaStructureMixin(StructureSettings settings) {
        super(settings);
    }

    // === Layer 1: 静态存储字段 ===

    /** Burning Arena 硬编码原始值 Y=21，作为 CODEC 默认值 */
    private static final HeightProvider BELOONG$DEFAULT_START_HEIGHT =
            ConstantHeight.of(VerticalAnchor.absolute(21));

    @Unique
    private static HeightProvider beloong$startHeight = BELOONG$DEFAULT_START_HEIGHT;

    @Unique
    private static Optional<Heightmap.Types> beloong$projectStartToHeightmap = Optional.empty();

    @Unique
    private static LiquidSettings beloong$liquidSettings = LiquidSettings.APPLY_WATERLOGGING;

    // === Layer 2: CODEC 替换 ===

    @Shadow @Final @Mutable
    private static MapCodec<Burning_Arena_Structure> CODEC;

    static {
        CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Structure.settingsCodec(instance),
                HeightProvider.CODEC.optionalFieldOf("start_height", BELOONG$DEFAULT_START_HEIGHT)
                        .forGetter(s -> beloong$startHeight),
                Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap")
                        .forGetter(s -> beloong$projectStartToHeightmap),
                LiquidSettings.CODEC.optionalFieldOf("liquid_settings", LiquidSettings.APPLY_WATERLOGGING)
                        .forGetter(s -> beloong$liquidSettings)
        ).apply(instance, BurningArenaStructureMixin::beloong$create));
    }

    @Unique
    private static Burning_Arena_Structure beloong$create(
            StructureSettings settings,
            HeightProvider startHeight,
            Optional<Heightmap.Types> projectStartToHeightmap,
            LiquidSettings liquidSettings) {
        beloong$startHeight = startHeight;
        beloong$projectStartToHeightmap = projectStartToHeightmap;
        beloong$liquidSettings = liquidSettings;
        return new Burning_Arena_Structure(settings);
    }

    // === Layer 3: findGenerationPoint 重写 ===

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void beloong$onFindGenerationPoint(GenerationContext context,
            CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        if (!Config.CataclysmFix.fixCataclysmStructureHeight.get()) {
            return;
        }

        ChunkPos chunkpos = context.chunkPos();
        int y = beloong$startHeight.sample(
                context.random(),
                new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor())
        );
        BlockPos blockpos = new BlockPos(chunkpos.getMinBlockX(), y, chunkpos.getMinBlockZ());

        cir.setReturnValue(Optional.of(new GenerationStub(blockpos, builder -> {
            Rotation rotation = Rotation.getRandom(context.random());
            Burning_Arena_Structure.start(
                    context.structureTemplateManager(), blockpos, rotation, builder, context.random()
            );
        })));
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`

Expected: BUILD SUCCESSFUL

---

### Task 3: RuinedCitadelStructureMixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/RuinedCitadelStructureMixin.java`

- [ ] **Step 1: 创建 Mixin 类**

```java
package com.zonlong.beloong.mixin;

import com.github.L_Ender.cataclysm.structures.RuinedCitadelStructure;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(RuinedCitadelStructure.class)
public abstract class RuinedCitadelStructureMixin extends Structure {

    protected RuinedCitadelStructureMixin(StructureSettings settings) {
        super(settings);
    }

    // === Layer 1: 静态存储字段 ===

    /** Ruined Citadel 硬编码原始值 Y=53，作为 CODEC 默认值 */
    private static final HeightProvider BELOONG$DEFAULT_START_HEIGHT =
            ConstantHeight.of(VerticalAnchor.absolute(53));

    @Unique
    private static HeightProvider beloong$startHeight = BELOONG$DEFAULT_START_HEIGHT;

    @Unique
    private static Optional<Heightmap.Types> beloong$projectStartToHeightmap = Optional.empty();

    @Unique
    private static LiquidSettings beloong$liquidSettings = LiquidSettings.APPLY_WATERLOGGING;

    // === Layer 2: CODEC 替换 ===

    @Shadow @Final @Mutable
    private static MapCodec<RuinedCitadelStructure> CODEC;

    static {
        CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Structure.settingsCodec(instance),
                HeightProvider.CODEC.optionalFieldOf("start_height", BELOONG$DEFAULT_START_HEIGHT)
                        .forGetter(s -> beloong$startHeight),
                Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap")
                        .forGetter(s -> beloong$projectStartToHeightmap),
                LiquidSettings.CODEC.optionalFieldOf("liquid_settings", LiquidSettings.APPLY_WATERLOGGING)
                        .forGetter(s -> beloong$liquidSettings)
        ).apply(instance, RuinedCitadelStructureMixin::beloong$create));
    }

    @Unique
    private static RuinedCitadelStructure beloong$create(
            StructureSettings settings,
            HeightProvider startHeight,
            Optional<Heightmap.Types> projectStartToHeightmap,
            LiquidSettings liquidSettings) {
        beloong$startHeight = startHeight;
        beloong$projectStartToHeightmap = projectStartToHeightmap;
        beloong$liquidSettings = liquidSettings;
        return new RuinedCitadelStructure(settings);
    }

    // === Layer 3: findGenerationPoint 重写 ===

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void beloong$onFindGenerationPoint(GenerationContext context,
            CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        if (!Config.CataclysmFix.fixCataclysmStructureHeight.get()) {
            return;
        }

        ChunkPos chunkpos = context.chunkPos();
        int y = beloong$startHeight.sample(
                context.random(),
                new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor())
        );
        BlockPos blockpos = new BlockPos(chunkpos.getMinBlockX(), y, chunkpos.getMinBlockZ());

        cir.setReturnValue(Optional.of(new GenerationStub(blockpos, builder -> {
            Rotation rotation = Rotation.getRandom(context.random());
            RuinedCitadelStructure.start(
                    context.structureTemplateManager(), blockpos, rotation, builder, context.random()
            );
        })));
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`

Expected: BUILD SUCCESSFUL

---

### Task 4: 注册 Mixin

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`

- [ ] **Step 1: 添加 Mixin 类名到 mixins 列表**

在 `beloong.mixins.json` 的 `"mixins"` 数组中追加两个条目：

```json
"mixins": [
    "DragonDestructionHandlerMixin",
    "BlockBreakEffectMixin",
    "BlockConversionEffectMixin",
    "ExplodeBlockEffectMixin",
    "FireEffectMixin",
    "BlockHarvestEffectMixin",
    "BonemealEffectMixin",
    "MixinDragonGrowthHandler",
    "TreasureBlockMixin",
    "CloneParameterListMixin",
    "BurningArenaStructureMixin",
    "RuinedCitadelStructureMixin"
],
```

- [ ] **Step 2: 编译验证**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew compileJava`

Expected: BUILD SUCCESSFUL

---

### Task 5: 构建并运行测试

- [ ] **Step 1: 完整构建**

Run: `cd e:/Minecraft/BeLoong-Core && ./gradlew build`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 启动客户端测试**

将构建产物放入测试环境，启动 Minecraft，创建新世界，执行：

```
/locate structure cataclysm:burning_arena
/locate structure cataclysm:ruined_citadel
```

传送到结构位置，确认：
- 结构在 KubeJS JSON 配置的高度生成（而非 Y=21 或 Y=53）
- 结构模板完整拼接，Boss 正常生成

- [ ] **Step 3: 验证配置开关**

关闭配置：
```toml
[cataclysm_fix]
fixCataclysmStructureHeight = false
```

重启，确认结构回退到原始硬编码 Y 值。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/zonlong/beloong/Config.java
git add src/main/java/com/zonlong/beloong/mixin/BurningArenaStructureMixin.java
git add src/main/java/com/zonlong/beloong/mixin/RuinedCitadelStructureMixin.java
git add src/main/resources/beloong.mixins.json
git commit -m "feat: 修复 Burning Arena 和 Ruined Citadel 忽略 start_height 配置的问题"
```
