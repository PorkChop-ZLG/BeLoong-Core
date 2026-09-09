# 龙宫维度 Dramatic Skys 天空迁移计划

## 一、目标

将 OptiFine 资源包 `Dramatic Skys` 的完整昼夜天空效果迁移到本模组龙宫维度，替换旧 `nebula` 天空盒。

### 已确认范围

- 第一阶段：实现 `stars` / `day` / `night` / `mask` 四层昼夜循环。
- 暂不实现太阳、月亮、阳光 flare、层旋转。
- 龙宫保留完整 `has_skylight: true`。
- **不再禁用原版天气**，龙宫与主世界一样保留雨/雪/雷。
- 天灾维度保持原版。

## 二、关键决策

| 项目 | 决策 |
|---|---|
| `has_skylight` | 保持 `true` |
| 天气 | 不再禁用，不添加任何天气抑制代码 |
| 第一阶段图层 | `stars` / `day` / `night` / `mask` |
| 旋转 | 暂不实现 |
| 渲染方式 | 单图集 Cube UV 渲染 |
| 太阳/月亮 | 后续再做 |
| 参考代码 | `FlashyReese/nuit` 的 `1.20.x/stable` 分支 |

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

核心天空图集均为 `3072×2048` 的 3×2 六面图集：

```text
┌──────────┬──────────┬──────────┐
│ Bottom   │ Top      │ South    │
├──────────┼──────────┼──────────┤
│ West     │ North    │ East     │
└──────────┴──────────┴──────────┘
```

### 3.3 Celestial JSON 提供的权威 UV 映射

各面 UV 子区域：

| 面 | UV 区域 |
|---|---|
| Bottom | `u ∈ [0, 1/3]`，`v ∈ [0, 0.5]` |
| Top | `u ∈ [1/3, 2/3]`，`v ∈ [0, 0.5]` |
| South | `u ∈ [2/3, 1]`，`v ∈ [0, 0.5]` |
| West | `u ∈ [0, 1/3]`，`v ∈ [0.5, 1]` |
| North | `u ∈ [1/3, 2/3]`，`v ∈ [0.5, 1]` |
| East | `u ∈ [2/3, 1]`，`v ∈ [0.5, 1]` |

### 3.4 图层与昼夜透明度

第一阶段移植：

| 图层 | 贴图 | 混合 | 透明度 |
|---|---|---|---|
| Stars | `stars.png` | alpha | `nightFade` |
| Mask | `mask.png` | alpha | `nightFade` |
| Day | `day.png` | screen | `dayFade` |
| Night | `night.png` | add | `nightFade` |

## 四、总体架构

### 4.1 数据包

`dimension_type/loong_palace.json`

```jsonc
"has_skylight": true,
"effects": "beloong:loong_palace"
```

### 4.2 客户端渲染器

- `CubeAtlasSkyRenderer`
  - 单图集六面渲染。
  - 使用 Celestial 24 顶点 UV 表。
- `SkyBlendMode`
  - `alpha`
  - `add`
  - `screen`
- `SkyLayer`
  - 图层数据。
- `DramaticSkyRenderer`
  - 四层堆叠 + 昼夜 fade。
- `LoongPalaceSkyEffects`
  - 接入 DramaticSkyRenderer。

### 4.3 天气

- 不添加任何天气抑制逻辑。
- 龙宫保留原版天气。

## 五、资源迁移

已复制：

```
src/main/resources/assets/beloong/textures/skybox/
├── day.png
├── night.png
├── stars.png
└── mask.png
```

旧 `loong_palace_sky/` 六面贴图已弃用。

## 六、验证清单

1. 龙宫：
   - 白天/夜晚 Dramatic Skys 效果正常。
   - 昼夜 fade 正常。
   - 六面接缝正常。
2. 天气：
   - 龙宫保留下雨/下雪/打雷等原版天气。
3. 天空光照：
   - `has_skylight: true`，方块正常受天空光照。
4. 天灾：
   - 保持原版，不受影响。

## 七、后续阶段

- 太阳、月亮
- 阳光 flare
- 天空层旋转
- 如需再评估是否对龙宫天气做特殊限制
