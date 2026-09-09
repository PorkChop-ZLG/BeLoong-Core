# 龙宫维度 Dramatic Skys 天空迁移计划

## 一、目标

将 OptiFine 资源包 `Dramatic Skys` 的完整昼夜天空效果迁移到本模组龙宫维度，替换当前 `nebula` 天空盒。

### 第一阶段范围

- 实现纯昼夜循环天空：`stars`、`day`、`night`、`mask` 四层。
- 不实现太阳、月亮、阳光 flare。
- 不实现天空层旋转。
- 保留龙宫天气禁用（但改为 `has_skylight: true` + 其它方式）。
- 天灾维度保持原版，不动。

## 二、已确认决策

| 项目 | 决策 |
|---|---|
| `has_skylight` | 从 `false` 改回 `true` |
| 天气禁用方式 | 改用非 Mixin 的 NeoForge 事件方案 |
| 第一阶段图层 | `stars` / `day` / `night` / `mask` 四层 |
| 旋转 | 暂不实现 |
| 渲染方式 | 单图集 Cube UV 渲染，不再切成六面 |
| 太阳/月亮 | 后续再做 |
| 参考代码 | 需要时克隆 `FlashyReese/nuit` 的 `1.20.x/stable` 分支 |

## 三、资源包分析

### 3.1 Dramatic Skys 目录结构

```
Dramatic Skys/assets/
├── skybox/
│   ├── day.png              # 白天天空，3072×2048
│   ├── night.png            # 夜晚天空，3072×2048
│   ├── stars.png            # 星空，3072×2048
│   ├── mask.png             # 夜晚/遮罩，3072×2048
│   ├── mask_moon.png        # 月亮遮罩，后续使用
│   ├── sun.png              # 太阳/日出日落贴图，后续使用
│   └── sunflare.png         # 阳光 flare，后续使用
├── minecraft/optifine/sky/world0/
│   └── sky1~sky9.properties # OptiFine 图层配置
└── celestial/sky/overworld/
    ├── sky.json
    ├── variables.json
    └── objects/*.json       # 精确 UV mapping + 透明度公式
```

### 3.2 图片格式

所有核心天空图集都是 `3072×2048` 的 3×2 六面图集：

```text
┌──────────┬──────────┬──────────┐
│ Bottom   │ Top      │ South    │
├──────────┼──────────┼──────────┤
│ West     │ North    │ East     │
└──────────┴──────────┴──────────┘
```

### 3.3 Celestial JSON 提供的权威 UV 映射

`assets/celestial/sky/overworld/objects/sky1_stars.json` 等文件给出了单图集 → 六面的精确 24 顶点坐标和 UV。各面 UV 子区域为：

| 面 | UV 区域 |
|---|---|
| Bottom | `u ∈ [0, 1/3]`，`v ∈ [0, 0.5]` |
| Top | `u ∈ [1/3, 2/3]`，`v ∈ [0, 0.5]` |
| South | `u ∈ [2/3, 1]`，`v ∈ [0, 0.5]` |
| West | `u ∈ [0, 1/3]`，`v ∈ [0.5, 1]` |
| North | `u ∈ [1/3, 2/3]`，`v ∈ [0.5, 1]` |
| East | `u ∈ [2/3, 1]`，`v ∈ [0.5, 1]` |

这组 UV 与 OptiFine 文档图片中的 3×2 布局一致。

### 3.4 图层与昼夜透明度

从 `variables.json` 可得到精确 fade 公式：

- `day_fade`
- `night_fade`
- `sunrise_fade`（后续使用）
- `sunset_fade`（后续使用）

第一阶段需要移植：

| 图层 | 贴图 | 混合 | 透明度 |
|---|---|---|---|
| Stars | `stars.png` | alpha | `nightFade` |
| Mask | `mask.png` | alpha | `nightFade` |
| Day | `day.png` | screen | `dayFade` |
| Night | `night.png` | add | `nightFade` |

资源包原始顺序（OptiFine）为：

1. `sky1` Stars
2. `sky3` Mask
3. `sky4` Day
4. `sky5` Night

## 四、总体架构

### 4.1 数据包修改

`src/main/resources/data/beloong/dimension_type/loong_palace.json`

```jsonc
{
  // 其余字段不变
  "has_skylight": true,
  "effects": "beloong:loong_palace"
}
```

### 4.2 天气禁用（非 Mixin）

新增服务器事件处理器：

```
com.zonlong.beloong.compat.loongpalace.LoongPalaceWeatherHandler
```

- 监听 `LevelTickEvent` / `LevelEvent.Load`。
- 仅当 `ServerLevel.dimension()` 为 `beloong:loong_palace` 时执行：
  - `serverLevel.setRainLevel(0.0F)`
  - `serverLevel.setThunderLevel(0.0F)`
- 不 Mixin 原版类。

> 注意：若验证发现事件方案仍会在客户端出现短暂雨雪，则追加在 `LoongPalaceSkyEffects` 中覆写 `renderSnowAndRain`/`tickRain` 返回 `true` 作为客户端兜底；若仍不够，再回来确认是否允许服务端 Mixin。

### 4.3 客户端新渲染器

新增/重构：

```
com.zonlong.beloong.client.sky.CubeAtlasSkyRenderer
```

- 不再接收 6 张独立贴图。
- 改为接收一张 `3072×2048` 的完整图集 `ResourceLocation`。
- 按照 Celestial JSON 的 24 顶点 + UV 子区域绘制六面。
- 顶点与 UV 直接使用资源包提供的世界坐标表，避免“切面 + 旋转”带来的接缝问题。

### 4.4 分层渲染堆栈

新增：

```
com.zonlong.beloong.client.sky.DramaticSkyRenderer
com.zonlong.beloong.client.sky.SkyLayer
com.zonlong.beloong.client.sky.SkyBlendMode
```

`LoongPalaceSkyEffects.renderSky()` 改为调用 `DramaticSkyRenderer.render(...)`，渲染顺序：

1. 可选底色（深蓝/黑，后续根据实际效果调整）
2. `stars.png`，alpha，alpha = nightFade
3. `mask.png`，alpha，alpha = nightFade
4. `day.png`，screen，alpha = dayFade
5. `night.png`，add，alpha = nightFade

### 4.5 混合模式实现

| 模式 | 计划实现方式 |
|---|---|
| `alpha` | `SRC_ALPHA, ONE_MINUS_SRC_ALPHA` |
| `add` | `SRC_ALPHA, ONE` |
| `screen` | 参考 Nuit 源码实现；若无法用固定管线达到理想效果，则使用自定义 shader |

### 4.6 昼夜 fade 计算

在 Java 中实现 Celestial `variables.json` 的等价函数：

```java
float dayFade(long dayTime) { ... }
float nightFade(long dayTime) { ... }
```

使用 `ClientLevel.getDayTime()` 作为输入。

## 五、需要迁移的资源

把以下文件从 `Dramatic Skys/assets/skybox/` 复制到：

```
src/main/resources/assets/beloong/textures/skybox/
├── day.png
├── night.png
├── stars.png
└── mask.png
```

`mask_moon.png`、`sun.png`、`sunflare.png` 暂不复制，留给太阳/月亮阶段。

旧的：

```
src/main/resources/assets/beloong/textures/environment/loong_palace_sky/
```

将不再被使用，可归档或删除。

## 六、代码改动清单

### 新增

- `client/sky/CubeAtlasSkyRenderer.java`
- `client/sky/DramaticSkyRenderer.java`
- `client/sky/SkyLayer.java`
- `client/sky/SkyBlendMode.java`
- `compat/loongpalace/LoongPalaceWeatherHandler.java`

### 修改

- `client/sky/LoongPalaceSkyEffects.java`
  - 从 `CubeSkyRenderer` 切换为 `DramaticSkyRenderer`。
- `BeLoongCore.java` 或专门的注册类
  - 注册 `LoongPalaceWeatherHandler`。
- `src/main/resources/data/beloong/dimension_type/loong_palace.json`
  - `has_skylight` 改回 `true`。
- `docs` 更新本计划。

### 可选删除/归档

- `client/sky/CubeSkyRenderer.java`（如果不再被引用）
- `loong_palace_sky/` 旧六面贴图

## 七、实施步骤

1. 克隆/参考 Nuit `1.20.x/stable` 源码，确认：
   - `single-sprite-square-textured` 的 UV 映射；
   - `screen` / `add` / `alpha` 混合实现；
   - 多图层渲染顺序。
2. 复制四个图集资源到模组资源目录。
3. 实现 `CubeAtlasSkyRenderer`，用 Celestial 24 顶点表绘制六面。
4. 实现 `SkyBlendMode` 与 `SkyLayer`。
5. 实现 `DramaticSkyRenderer` 四层堆叠与 fade。
6. 修改 `LoongPalaceSkyEffects` 接入新渲染器。
7. 修改 `loong_palace.json` 恢复 `has_skylight: true`。
8. 实现 `LoongPalaceWeatherHandler`，用 NeoForge 事件抑制天气。
9. 编译并进入游戏验证。

## 八、验证清单

1. 龙宫维度：
   - 白天天空显示 Dramatic Skys 的 `day` 效果。
   - 夜晚天空显示 `night + stars + mask` 效果。
   - 日出/日落期间四层 fade 过渡自然。
   - 六面接缝正确。
2. 天气：
   - 龙宫内无雨、雪、雷。
   - 主世界和其它维度天气不受影响。
3. 资源：
   - 旧的 `nebula` 天空不再加载。
4. 天灾维度：
   - 保持原版天空和天气，不受影响。

## 九、风险与待确认

- `screen` 混合模式在原版 fixed-function 渲染下不能直接表达，可能需要自定义 shader；届时参考 Nuit 实现。
- 事件式天气抑制可能在极端 tick 时序下出现客户端短暂雨雪；若发生，需要追加客户端 `renderSnowAndRain`/`tickRain` 兜底或重新评估是否允许 Mixin。
- `has_skylight: true` 会恢复龙宫天空光照，这与旧“纯数据包禁天气”不同，因此必须确认事件抑制有效后再最终保留。
- 本计划不修改太阳/月亮/旋转，进度独立于后续阶段。
