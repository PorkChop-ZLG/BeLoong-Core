# BWG 群系注入天灾维度（Java 方案）

## 概述

以 Java 代码方式将 Oh The Biomes We've Gone (BWG) 模组的所有生物群系添加到天灾维度（disaster），使其群系构成与安装了 BWG 的主世界一致（原版群系 + BWG 群系）。同时支持：BWG 配置中禁用的群系从主世界移除，但天灾维度中仍可出现。

BWG 和 TerraBlender 作为可选依赖，未安装时天灾维度仍可正常使用（只有原版群系）。

## 核心思路

天灾维度打上 `terrablender:overworld_regions` 标签让 TerraBlender 介入群系生成。通过 Mixin 拦截 `Regions.get()` 调用，对天灾维度替换 BWG 自带的 3 个区域（会检查配置文件）为一个自定义区域（跳过配置过滤，始终启用全部 BWG 群系）。

```
服务器启动
  → TerraBlender 扫描所有维度
    → 发现 beloong:disaster 匹配 overworld_regions 标签
    → LevelUtils.initializeBiomes() 调用 Regions.get()
      → Mixin @Redirect 拦截
        → 当前维度 == beloong:disaster？
          → 是：过滤掉 biomeswevegone 命名空间的 Region，添加 DisasterBiomeRegion
          → 否：返回原始列表
    → 主世界维度：BWG 配置正常生效（禁用群系从主世界消失）
    → 天灾维度：DisasterBiomeRegion 跳过配置检查（禁用群系仍然出现）
```

## 文件清单

| 文件 | 职责 |
|------|------|
| `BWGIntegration.java` | 可选依赖检测与管理，提供统一入口 |
| `DisasterBiomeRegion.java` | 自定义 TerraBlender Region，复用 BWG 群系选择器，跳过配置过滤 |
| `mixin/BWGTerraBlenderRegionsMixin.java` | @Redirect 拦截 `Regions.get()`，对天灾维度替换区域列表 |
| `data/beloong/tags/dimension_type/overworld_regions.json` | 静态标签，将 `beloong:disaster` 接入 TerraBlender |

## 组件详解

### BWGIntegration

- 使用 `ModList.get().isLoaded("biomeswevegone")` 和 `ModList.get().isLoaded("terrablender")` 检测依赖
- `init()` 在主 mod 构造器中调用，依赖未安装时静默跳过
- 通过反射访问 BWG 的 `BWGBiomes`、`TerraBlenderBiomeSelectors` 等公开类

### DisasterBiomeRegion extends Region

- 构造：`super(name, RegionType.OVERWORLD, weight)`，weight 设为 BWG 三个区域权重之和（默认 8+8+8=24），确保天灾维度中 BWG 群系的生成比重与主世界相当。该值可通过配置文件调整
- `addBiomes()` 方法通过反射读取 BWG 的 `TerraBlenderBiomeSelectors` / `BWGBiomeSelectors` 中的 2D 群系数组（middleBiomes、plateauBiomes 等）
- 使用 `TerrablenderOverworldBiomeBuilder` 将群系映射到气候参数
- 关键差异：不检查 `BWGWorldGenConfig.INSTANCE.biomes`，群系始终启用
- 原版群系由 TerraBlender 的 `DefaultOverworldRegion`（index 0）正常提供

### Mixin：BWGTerraBlenderRegionsMixin

- 目标：`LevelUtils.initializeBiomes()` 中对 `Regions.get(regionType)` 的调用
- 使用 `@Redirect` 替换返回值
- 判断 `levelResourceKey.location()` 是否为 `beloong:disaster`
- 天灾维度：过滤 `biomeswevegone` 命名空间的 Region，追加 `DisasterBiomeRegion`
- 其他维度：原样返回
- 为什么用 `@Redirect` 而非 `@Inject`：直接替换返回值，无需操作全局状态，无线程安全问题
- 需在 `beloong.mixins.json` 中注册该 Mixin 类，并注明 TerraBlender 为可选 mod：`"refmap": "beloong.refmap.json"`

### 标签文件

```json
// data/beloong/tags/dimension_type/overworld_regions.json
{
  "values": ["beloong:disaster"]
}
```

作为 TerraBlender 扫描的触发器。这是唯一的新增 JSON 文件。

## 构建配置变更

`build.gradle` 新增：

```groovy
// BWG + TerraBlender — 可选依赖
compileOnly "curse.maven:oh-the-biomes-weve-gone-XXXXXX:YYYYYYYY"
compileOnly "curse.maven:terrablender-XXXXXX:YYYYYYYY"
localRuntime "curse.maven:oh-the-biomes-weve-gone-XXXXXX:YYYYYYYY"
localRuntime "curse.maven:terrablender-XXXXXX:YYYYYYYY"
```

`neoforge.mods.toml` 新增可选依赖声明。

## 表面规则

无需额外处理。TerraBlender 的 `NamespacedSurfaceRuleSource` 根据群系命名空间（`biomeswevegone`）自动分发 BWG 已注册的表面规则，天灾维度中 BWG 群系会正确显示地表方块。

## 结构

BWG 结构通过 `DatapackBuiltinEntriesProvider` 注册到 `Registries.STRUCTURE_SET`。天灾维度使用 `"settings": "minecraft:overworld"`。

结构生成取决于 BWG 如何将结构集注入 `NoiseGeneratorSettings`：
- 若 BWG 通过 Mixin 修改 `StructureSettings`：天灾维度共享同一 noise settings 引用，结构可能自动出现
- 若通过其他机制：需额外处理

实施阶段先验证现有行为，若未生成再通过方案补充（自定义 noise settings 或 Mixin）。

## 边界情况

| 场景 | 行为 |
|------|------|
| BWG 未安装 | `BWGIntegration.init()` 检测后跳过，Mixin 不注册，只有原版群系 |
| TerraBlender 未安装 | 同上（BWG 依赖 TerraBlender，实际上不会出现） |
| BWG 配置禁用某群系 | 主世界受影响，天灾维度不受影响 |
| 反射访问 BWG 类失败 | 记录警告日志，退化到纯原版群系 |
| 其他模组也有 TerraBlender 区域 | 其群系会同时出现在天灾维度，与主世界行为一致 |
| TerraBlender 升级 | Mixin 目标 `Regions.get()` 是核心静态 API，稳定性高 |
| BWG 升级重构内部类 | 需要跟进反射目标 |

## 测试策略

1. 无 BWG 环境：进入天灾维度，确认只有原版群系
2. 安装 BWG 环境：飞行遍历，确认 BWG 群系的地形、植被、地表方块正确
3. 配置独立性：BWG 配置禁用某群系，验证主世界消失但天灾维度仍存在
4. 结构验证：在天灾维度中寻找 BWG 结构，确认是否生成

## 不需要的

- 不需要重新定义 BWG 群系的气候参数——复用 BWG 已有的 `TerraBlenderBiomeSelectors`
- 不需要重新定义表面规则——复用 BWG 已有的 `BWGOverworldSurfaceRules`
- 不需要修改维度定义 JSON（disaster.json、disaster_type.json）
