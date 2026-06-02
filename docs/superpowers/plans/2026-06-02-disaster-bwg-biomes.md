# BWG 群系注入天灾维度 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 Java 代码将 BWG 群系注入天灾维度，支持 BWG 配置独立控制（主世界禁用的群系在天灾维度仍可出现）

**Architecture:** 天灾维度通过标签接入 TerraBlender 的 overworld region 系统。Mixin @Redirect 拦截 `Regions.get()` 调用，对天灾维度用自定义 `DisasterBiomeRegion` 替换 BWG 原生区域，该区域通过反射读取 BWG 群系选择器数组但跳过配置过滤

**Tech Stack:** NeoForge 1.21.1, TerraBlender API, Mixin Extra (SpongePowered), CurseMaven dependencies

---

## File Structure

```
Create: src/main/java/com/zonlong/beloong/worldgen/BWGIntegration.java         — 可选依赖检测，统一入口
Create: src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeRegion.java    — 自定义 TerraBlender Region
Create: src/main/java/com/zonlong/beloong/mixin/TerraBlenderRegionsMixin.java  — @Redirect 拦截 Regions.get()
Create: src/main/resources/data/beloong/tags/dimension_type/overworld_regions.json  — 标签触发器
Modify: src/main/resources/beloong.mixins.json                                 — 注册新 Mixin
Modify: build.gradle                                                           — 添加可选依赖
Modify: src/main/templates/META-INF/neoforge.mods.toml                          — 可选依赖声明
Modify: src/main/java/com/zonlong/beloong/BeLoongCore.java                     — 调用 BWGIntegration.init()
```

---

### Task 1: 添加 BWG 和 TerraBlender 可选依赖

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/templates/META-INF/neoforge.mods.toml`

- [ ] **Step 1: 在 build.gradle dependencies 中添加 BWG 和 TerraBlender 可选依赖**

在 `dependencies` 块末尾添加：

```groovy
// BWG + TerraBlender — 可选依赖，仅在安装时生效
compileOnly "curse.maven:oh-the-biomes-weve-gone-1070780:6215671"
compileOnly "curse.maven:terrablender-940057:6202148"
localRuntime "curse.maven:oh-the-biomes-weve-gone-1070780:6215671"
localRuntime "curse.maven:terrablender-940057:6202148"
```

- [ ] **Step 2: 在 neoforge.mods.toml 中添加可选依赖声明**

在 `[[dependencies.${mod_id}]]` 块区域的末尾（`side="BOTH"` 行后），添加：

```toml
[[dependencies.${mod_id}]]
    modId="biomeswevegone"
    type="optional"
    ordering="AFTER"
    side="BOTH"

[[dependencies.${mod_id}]]
    modId="terrablender"
    type="optional"
    ordering="AFTER"
    side="BOTH"
```

- [ ] **Step 3: 提交**

```bash
git add build.gradle src/main/templates/META-INF/neoforge.mods.toml
git commit -m "build: add BWG and TerraBlender as optional dependencies"
```

---

### Task 2: 创建 TerraBlender 维度标签

**Files:**
- Create: `src/main/resources/data/beloong/tags/dimension_type/overworld_regions.json`

- [ ] **Step 1: 创建标签 JSON 文件**

```json
{
  "values": ["beloong:disaster"]
}
```

这是 TerraBlender 识别天灾维度的触发器——当前唯一的非 Java 数据文件。

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/data/beloong/tags/dimension_type/overworld_regions.json
git commit -m "feat: add disaster dimension to terrablender overworld regions tag"
```

---

### Task 3: 创建 BWGIntegration 可选依赖门面

**Files:**
- Create: `src/main/java/com/zonlong/beloong/worldgen/BWGIntegration.java`

- [ ] **Step 1: 编写 BWGIntegration.java**

```java
package com.zonlong.beloong.worldgen;

import com.zonlong.beloong.BeLoongCore;
import net.neoforged.fml.ModList;

/**
 * BWG/TerraBlender 可选依赖门面。
 * 负责检测依赖、注册 DisasterBiomeRegion、触发反射初始化。
 */
public final class BWGIntegration {

    private static final String BWG_MOD_ID = "biomeswevegone";
    private static final String TB_MOD_ID = "terrablender";

    private static boolean initialized = false;

    private BWGIntegration() {}

    /**
     * 在主 mod 构造器中调用。若 BWG 或 TerraBlender 未安装，静默跳过。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!ModList.get().isLoaded(TB_MOD_ID)) {
            BeLoongCore.LOGGER.info("[BeLoongCore] TerraBlender not installed, skipping BWG biome injection");
            return;
        }
        if (!ModList.get().isLoaded(BWG_MOD_ID)) {
            BeLoongCore.LOGGER.info("[BeLoongCore] BWG not installed, disaster dimension will use vanilla biomes only");
            return;
        }

        try {
            DisasterBiomeRegion.register();
            BeLoongCore.LOGGER.info("[BeLoongCore] DisasterBiomeRegion registered for BWG biome injection");
        } catch (Exception e) {
            BeLoongCore.LOGGER.error("[BeLoongCore] Failed to register DisasterBiomeRegion", e);
        }
    }

    public static boolean isEnabled() {
        return initialized
                && ModList.get().isLoaded(BWG_MOD_ID)
                && ModList.get().isLoaded(TB_MOD_ID);
    }
}
```

- [ ] **Step 2: 在 BeLoongCore.java 构造器中调用 BWGIntegration.init()**

在 `BeLoongCore` 构造器中，`ModCreativeModeTabs.register(modEventBus);` 之后添加：

```java
BWGIntegration.init();
```

需要新增 import：
```java
import com.zonlong.beloong.worldgen.BWGIntegration;
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/worldgen/BWGIntegration.java src/main/java/com/zonlong/beloong/BeLoongCore.java
git commit -m "feat: add BWGIntegration optional dependency facade"
```

---

### Task 4: 创建 DisasterBiomeRegion

**Files:**
- Create: `src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeRegion.java`

- [ ] **Step 1: 编写 DisasterBiomeRegion.java**

```java
package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.api.TerrablenderOverworldBiomeBuilder;

import java.lang.reflect.Field;
import java.util.function.Consumer;

/**
 * 天灾维度专用的 TerraBlender Region。
 * 复用 BWG 的 BWGBiomeSelectors / TerraBlenderBiomeSelectors 中的群系-气候参数映射，
 * 但跳过 {{@code BWGWorldGenConfig}} 的配置过滤，始终启用全部 BWG 群系。
 */
public class DisasterBiomeRegion extends Region {

    private static final ResourceLocation REGION_NAME =
            ResourceLocation.fromNamespaceAndPath("beloong", "disaster_bwg");
    private static final int WEIGHT = 24; // BWG 三个区域权重之和 (8+8+8)

    private final TerrablenderOverworldBiomeBuilder builder;

    private DisasterBiomeRegion(TerrablenderOverworldBiomeBuilder builder) {
        super(REGION_NAME, RegionType.OVERWORLD, WEIGHT);
        this.builder = builder;
    }

    /**
     * 通过反射读取 BWG 的群系选择器数组并注册 Region。
     * 若反射失败则记录错误并跳过。
     */
    public static void register() {
        try {
            // 反射获取 BWGBiomeSelectors 类
            Class<?> bwgSelectorsClass = Class.forName(
                    "net.potionstudios.biomeswevegone.world.level.levelgen.biome.selector.BWGBiomeSelectors");
            // 反射获取 TerraBlenderBiomeSelectors 类
            Class<?> tbSelectorsClass = Class.forName(
                    "net.potionstudios.biomeswevegone.world.level.levelgen.biome.selector.TerraBlenderBiomeSelectors");

            // 读取 BWG REGION_1 使用的群系数组
            ResourceKey<Biome>[][] oceans = getBiomeArray(bwgSelectorsClass, "OCEANS_BWG");
            ResourceKey<Biome>[][] middleBiomes = getBiomeArray(bwgSelectorsClass, "MIDDLE_BIOMES_BWG");
            ResourceKey<Biome>[][] middleBiomesVariant = getBiomeArray(bwgSelectorsClass, "MIDDLE_BIOMES_VARIANT_BWG");
            ResourceKey<Biome>[][] plateauBiomes = getBiomeArray(bwgSelectorsClass, "PLATEAU_BIOMES_BWG");
            ResourceKey<Biome>[][] plateauBiomesVariant = getBiomeArray(bwgSelectorsClass, "PLATEAU_BIOMES_VARIANT_BWG");
            ResourceKey<Biome>[][] shatteredBiomes = getBiomeArray(bwgSelectorsClass, "SHATTERED_BIOMES_BWG");
            ResourceKey<Biome>[][] beachBiomes = getBiomeArray(bwgSelectorsClass, "BEACH_BIOMES_BWG");
            ResourceKey<Biome>[][] peakBiomes = getBiomeArray(bwgSelectorsClass, "PEAK_BIOMES_BWG");
            ResourceKey<Biome>[][] peakBiomesVariant = getBiomeArray(bwgSelectorsClass, "PEAK_BIOMES_VARIANT_BWG");
            ResourceKey<Biome>[][] slopeBiomes = getBiomeArray(bwgSelectorsClass, "SLOPE_BIOMES_BWG");
            ResourceKey<Biome>[][] slopeBiomesVariant = getBiomeArray(tbSelectorsClass, "SLOPE_BIOMES_VARIANT_TERRABLENDER");

            TerrablenderOverworldBiomeBuilder biomeBuilder = new TerrablenderOverworldBiomeBuilder(
                    oceans, middleBiomes, middleBiomesVariant,
                    plateauBiomes, plateauBiomesVariant, shatteredBiomes,
                    beachBiomes, peakBiomes, peakBiomesVariant,
                    slopeBiomes, slopeBiomesVariant
            );

            DisasterBiomeRegion region = new DisasterBiomeRegion(biomeBuilder);
            Regions.register(region);
            BeLoongCore.LOGGER.info("[BeLoongCore] DisasterBiomeRegion registered with {} BWG biome arrays", 11);

        } catch (ClassNotFoundException e) {
            BeLoongCore.LOGGER.error("[BeLoongCore] BWG selector classes not found — BWG version may be incompatible", e);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            BeLoongCore.LOGGER.error("[BeLoongCore] Failed to access BWG biome selector fields", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Biome>[][] getBiomeArray(Class<?> clazz, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getField(fieldName);
        return (ResourceKey<Biome>[][]) field.get(null);
    }

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        this.builder.addBiomes(pair -> {
            // 直接通过，不检查 BWGWorldGenConfig
            // DEFERRED_PLACEHOLDER 会自动回退到 DefaultOverworldRegion（原版群系）
            mapper.accept(pair);
        });
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/zonlong/beloong/worldgen/DisasterBiomeRegion.java
git commit -m "feat: add DisasterBiomeRegion for BWG biome injection in disaster dimension"
```

---

### Task 5: 创建 TerraBlenderRegionsMixin

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/TerraBlenderRegionsMixin.java`

**设计说明：** 使用标准 Mixin 的 `@Inject` + `@Redirect` + ThreadLocal 组合，无需引入 MixinExtra 额外依赖。ThreadLocal 在 `@Inject(HEAD)` 中设置维度上下文，在 `@Redirect` 中读取。

- [ ] **Step 1: 编写 Mixin 类**

```java
package com.zonlong.beloong.mixin;

import com.zonlong.beloong.worldgen.BWGIntegration;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.util.LevelUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截 {@code Regions.get()} 调用，对天灾维度替换区域列表。
 * 使用 ThreadLocal 传递维度上下文（HEAD inject → Redirect 链）。
 */
@Mixin(value = LevelUtils.class, remap = false)
public abstract class TerraBlenderRegionsMixin {

    private static final ResourceLocation DISASTER_STEM =
            ResourceLocation.fromNamespaceAndPath("beloong", "disaster");

    private static final ThreadLocal<Boolean> IS_DISASTER_DIM = ThreadLocal.withInitial(() -> false);

    /** 在方法入口记录是否为天灾维度 */
    @Inject(method = "initializeBiomes", at = @At("HEAD"), remap = false)
    private static void captureDimensionContext(
            RegistryAccess registryAccess,
            Holder<DimensionType> dimensionType,
            ResourceKey<LevelStem> levelResourceKey,
            ChunkGenerator chunkGenerator,
            long seed,
            CallbackInfo ci) {
        IS_DISASTER_DIM.set(
                levelResourceKey != null && levelResourceKey.location().equals(DISASTER_STEM));
    }

    /** 拦截 Regions.get() 调用，对天灾维度替换区域列表 */
    @Redirect(
            method = "initializeBiomes",
            at = @At(value = "INVOKE", target = "Lterrablender/api/Regions;get(Lterrablender/api/RegionType;)Ljava/util/List;"),
            remap = false)
    private static List<Region> redirectRegionsGet(RegionType type) {
        List<Region> regions = new ArrayList<>(Regions.get(type));

        if (IS_DISASTER_DIM.get() && BWGIntegration.isEnabled()) {
            // 移除 biomeswevegone 命名空间的 Region（BWG 原生，受配置控制）
            regions.removeIf(r -> r.getName().getNamespace().equals("biomeswevegone"));

            // 添加 beloong 命名空间的 DisasterBiomeRegion（绕过配置过滤）
            for (Region r : Regions.get(RegionType.OVERWORLD)) {
                if (r.getName().getNamespace().equals("beloong")) {
                    regions.add(r);
                    break;
                }
            }
        }

        return regions;
    }
}
```

- [ ] **Step 2: 在 beloong.mixins.json 中注册 Mixin**

在 `"mixins"` 数组末尾添加（`"TreasureBlockMixin"` 之后）：

```json
"TerraBlenderRegionsMixin"
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/zonlong/beloong/mixin/TerraBlenderRegionsMixin.java src/main/resources/beloong.mixins.json
git commit -m "feat: add TerraBlenderRegionsMixin to redirect Regions.get for disaster dimension"
```

---

### Task 6: 构建验证

- [ ] **Step 1: 执行 Gradle 构建**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```

预期：BUILD SUCCESSFUL。编译应通过，确保所有新类正确 import 了 TerraBlender 和 Mixin Extra 的 API。

- [ ] **Step 2: 检查编译产物中是否包含所有新文件**

```bash
jar tf build/libs/beloong-*.jar | grep -E "(BWGIntegration|DisasterBiomeRegion|TerraBlenderRegionsMixin|overworld_regions)"
```

预期输出：
```
com/zonlong/beloong/worldgen/BWGIntegration.class
com/zonlong/beloong/worldgen/DisasterBiomeRegion.class
com/zonlong/beloong/mixin/TerraBlenderRegionsMixin.class
data/beloong/tags/dimension_type/overworld_regions.json
```

---

### Task 7: 游戏内验证（需启动 Minecraft）

- [ ] **Step 1: 无 BWG 环境验证**

启动 Minecraft（仅安装 BeLoongCore，不安装 BWG/TerraBlender）。
进入天灾维度 `/execute in beloong:disaster run tp @s ~ ~ ~`。
飞行遍历，确认只有原版群系正常生成。
检查日志：应出现 `TerraBlender not installed` 或 `BWG not installed` info 消息。

- [ ] **Step 2: 安装 BWG 环境验证**

安装 BWG + TerraBlender + 依赖。启动游戏，进入天灾维度。
飞行遍历多种地形和气候区域（F3 查看群系名称），确认 BWG 群系正常出现。
检查地表方块、树木、植被是否正确。

- [ ] **Step 3: 配置独立性验证**

修改 `config/biomeswevegone/world_gen.json`，将某个常见群系设为 false（如 `"biomeswevegone:prairie": false`）。
进入主世界，确认 Prairie（草原）不再出现。
进入天灾维度，确认 Prairie 仍然正常生成。

- [ ] **Step 4: 结构验证（探索性）**

在天灾维度中飞行探索，使用 `/locate structure biomeswevegone:prairie_house` 或类似命令，检查 BWG 结构是否生成。
若未生成，则结构需要后续补充方案处理（不在本次实施范围内）。
```

---

### Self-Review

**1. Spec coverage:**
- 维度标签接入 TerraBlender → Task 2 ✓
- 可选依赖检测 → Task 3 (BWGIntegration) ✓
- 自定义 Region 复用 BWG 群系选择器 → Task 4 (DisasterBiomeRegion) ✓
- Mixin 拦截 Regions.get → Task 5 ✓
- 配置独立（主世界禁用不影响天灾维度） → Task 4 中 addBiomes 无配置过滤 ✓
- 构建配置变更 → Task 1 ✓
- 边界情况（BWG 未安装/反射失败等） → Task 3 init() catch + Task 4 异常处理 ✓
- 测试策略 → Task 6 (构建) + Task 7 (游戏内) ✓
- 表面规则 → spec 已说明无需额外处理，不在计划中 ✓
- 结构 → spec 已说明先验证，不在计划中（测试时验证） ✓

**2. Placeholder scan:** 无 TBD/TODO/implement later，所有代码步骤完整。

**3. Type consistency:** 
- `DisasterBiomeRegion.register()` 被 `BWGIntegration.init()` 调用 ✓
- `Regions.register(region)` 在 `DisasterBiomeRegion.register()` 中被调用 ✓
- Mixin 中 `BWGIntegration.isEnabled()` 检查是否正确初始化 ✓
- 标签文件路径 `data/beloong/tags/dimension_type/overworld_regions.json` 与 TerraBlender 的 `DimensionTypeTags.OVERWORLD_REGIONS` 路径约定一致 ✓
- 维度 stem key `beloong:disaster` 与 disaster.json 中定义一致 ✓
