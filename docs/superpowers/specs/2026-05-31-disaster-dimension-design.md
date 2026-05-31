# Disaster Dimension（纯数据包）

## 概述

以纯数据包方式添加 `disaster` 维度，该维度直接复用原版主世界的所有世界生成内容（地形、群系、结构、洞穴、矿物），等同于另一个独立的主世界。

## 文件清单

### `data/beloong/dimension_type/disaster.json`

维度类型，属性值与 `minecraft:overworld` 一致：

| 属性 | 值 | 说明 |
|------|-----|------|
| `ultrawarm` | `false` | 非炎热维度 |
| `natural` | `true` | 自然维度 |
| `coordinate_scale` | `1.0` | 坐标比例 1:1 |
| `has_skylight` | `true` | 有天空光照 |
| `has_ceiling` | `false` | 无基岩天花板 |
| `ambient_light` | `0.0` | 无环境光 |
| `monster_spawn_light_level` | `{type: uniform, min: 0, max: 7}` | 怪物生成光照条件 |
| `monster_spawn_block_light_limit` | `0` | 怪物生成方块光照上限 |
| `piglin_safe` | `false` | 猪灵不僵尸化 |
| `bed_works` | `true` | 床可正常使用 |
| `respawn_anchor_works` | `false` | 重生锚不可用 |
| `has_raids` | `true` | 可触发袭击 |
| `logical_height` | `384` | 逻辑高度 |
| `min_y` | `-64` | 最低 Y |
| `height` | `384` | 总高度 |
| `infiniburn` | `#minecraft:infiniburn_overworld` | 可无限燃烧的方块 |
| `effects` | `minecraft:overworld` | 视觉效果（天空颜色、云等） |

### `data/beloong/dimension/disaster.json`

维度定义，使用原版主世界的噪声设置和群系源：

```json
{
  "type": "beloong:disaster",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:overworld",
    "biome_source": {
      "type": "minecraft:multi_noise",
      "preset": "minecraft:overworld"
    }
  }
}
```

## 效果

- 地形、生物群系、结构、洞穴、矿物生成与原版主世界完全一致
- 独立维度，坐标与主世界不互通
- 拥有完整昼夜循环和天空光照
- 玩家可通过传送门或其他方式进入（由后续功能决定）

## 不需要的

- 不需要自定义 biome — 复用原版 `minecraft:multi_noise` 的 `overworld` preset
- 不需要自定义 noise_settings — 复用 `minecraft:overworld`
- 不需要任何 Java 代码 — 纯数据包实现
