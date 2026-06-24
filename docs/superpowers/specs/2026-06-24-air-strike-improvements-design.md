# Air Strike 改进设计

## 概述

对 `AirStrikeEffect`（龙击长空）进行四项改进：碰撞箱数据驱动、爪牙武器伤害叠加、属性加成、自定义伤害类型与死亡消息。

## 改动

### 1. 碰撞箱数据驱动

- 新增 `collisionSize: LevelBasedValue` 字段
- 碰撞扫描由硬编码 `player.getBoundingBox().inflate(1.0)` 改为 `player.getBoundingBox().inflate(collisionSize.calculate(level))`
- 支持 `minecraft:linear`、`minecraft:lookup` 等所有 LevelBasedValue 格式

### 2. 爪牙武器伤害

- 代码中取 SWORD 爪牙槽（`ClawInventoryData.getData(player).getSword()`）
- 叠加其 `ATTACK_DAMAGE` 属性修饰符值到基础伤害
- **空值处理**：SWORD 槽为空时，武器伤害 = 0

### 3. 属性加成

- 伤害计算中乘以 `DSAttributes.DRAGON_ABILITY_DAMAGE` 属性值
- 代码写死，不可在 JSON 中配置
- **空值处理**：属性不存在时，scale = 1.0

### 4. 自定义伤害类型

- JSON 文件：`data/beloong/damage_type/air_strike.json`
- 伤害类型写死在代码中，不可在 JSON 中配置
- 死亡消息（中英文）通过翻译键配置

## Record 结构

| 字段 | 类型 | 用途 |
|------|------|------|
| `baseDamage` | `LevelBasedValue` | 基础伤害 |
| `speedFactor` | `LevelBasedValue` | 速度乘数 |
| `collisionSize` | `LevelBasedValue` | 碰撞箱膨胀大小 |
| `minSpeed` | `LevelBasedValue` | 最低速度阈值 |

## 伤害公式

```
伤害 = (baseDamage + SWORD_ATTACK_DAMAGE) × 当前速度(m/tick) × speedFactor × dragon_ability_damage
```

JSON 中不暴露公式、伤害类型、属性——三者均写死在代码中。

空值保护：
- SWORD 槽为空 → 武器伤害 = 0
- `dragon_ability_damage` 属性不存在 → scale = 1.0

## 伤害类型

```json
// data/beloong/damage_type/air_strike.json
{
  "message_id": "beloong.air_strike",
  "exhaustion": 0.1,
  "scaling": "when_caused_by_living_non_player",
  "effects": "hurt",
  "death_message_type": "default"
}
```

## 翻译

| Key | zh_cn | en_us |
|-----|-------|-------|
| `death.attack.beloong.air_strike` | %s遭受到了可爱星星的撞击 | %s was struck by a cute star |
| `death.attack.beloong.air_strike.player` | %s在试图逃离%s时遭受到了可爱星星的撞击 | %s was struck by a cute star whilst trying to escape %s |

## 逐项修改清单

### AirStrikeEffect.java

| # | 位置 | 当前 | 改为 |
|---|------|------|------|
| 1 | Record 字段 | 3 字段 (baseDamage, speedFactor, minSpeed) | 加 `collisionSize` → 4 字段 |
| 2 | CODEC | 3 字段 | 加 `collision_size`，保留 `FLEXIBLE_LBV` |
| 3 | 伤害计算 | `base + speed * speedFactor` | `(base + weaponDamage) * speed * speedFactor * dragon_ability_damage` |
| 4 | actionbar 显示 | 旧公式算出的 damage，位于碰撞扫描前 | 伤害计算移到 actionbar 之前，显示新公式结果 |
| 5 | 碰撞箱 | `inflate(1.0)` 硬编码 | `inflate(collisionSize.calculate(level))` |
| 6 | 伤害来源 | `player.damageSources().mobAttack(player)` | `new DamageSource(airStrikeHolder, dragon)` |
| 7 | getDescription() | 显示 base, speedFactor, minSpeed | 追加 collisionSize |
| 8 | import | 缺少 | 添加 `DamageType`, `DamageSource`, `Holder`, `DSAttributes`, `ClawInventoryData`, `Attributes`, `ResourceKey`, `Registries`, `ResourceLocation` |

### air_strike.json（ability 定义）

| # | 字段 | 操作 |
|---|------|------|
| 9 | `collision_size` | 新增 `"collision_size": { "type": "minecraft:linear", "base": 1.0, "per_level_above_first": 0.0 }` |

### 新建文件

| # | 文件 | 内容 |
|---|------|------|
| 10 | `data/beloong/damage_type/air_strike.json` | 伤害类型定义（见上方 JSON） |
| 11 | `en_us.json` | 增加 2 条死亡消息 |
| 12 | `zh_cn.json` | 增加 2 条死亡消息 |

## 文件变更汇总

| 文件 | 操作 |
|------|------|
| `AirStrikeEffect.java` | 修改 |
| `air_strike.json` (ability) | 修改 |
| `data/beloong/damage_type/air_strike.json` | 新建 |
| `zh_cn.json` | 修改 |
| `en_us.json` | 修改 |
