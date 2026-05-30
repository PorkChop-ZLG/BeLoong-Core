# Design: tp_loong_palace Ability Entity Effect

## Summary

为 DragonSurvival 模组注册一个自定义 `AbilityEntityEffect`——`beloong:tp_loong_palace`，用于在龙宫维度和主世界之间传送。

## Components

### 1. TpLoongPalaceEffect.java

**路径:** `src/main/java/com/zonlong/beloong/ability/TpLoongPalaceEffect.java`

- 无参 `record`，实现 `AbilityEntityEffect`
- `MapCodec` 使用 `MapCodec.unit(TpLoongPalaceEffect::new)`（无配置字段）
- `apply(dragon, ability, target)`：
  - 判断 `target` 所在维度
  - **主世界 → 龙宫**：从 `Config.DimensionTransport.overworldToLoongPalace` 读取目标维度/x/z/fallbackY，通过高度图查落脚点，调用 `target.teleportTo()` 传送
  - **龙宫 → 主世界**：调用 `ServerPlayer.findRespawnPositionAndUseSpawnBlock()` 查重生点；有效则传送到重生点，无效则回退到目标维度的世界出生点
  - 其他维度：不做任何操作

### 2. 注册

在 `BeLoongCore.java` 中通过 `modEventBus.addListener(RegisterEvent.class, ...)` 注册到 `AbilityEntityEffect.REGISTRY`，注册键 `beloong:tp_loong_palace`。

### 3. 翻译键

新增 `message.beloong.tp_loong_palace.dimension_not_found` 用于目标维度不可用时的提示。

### 4. 测试用 Datapack Ability

**路径:** `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tp_loong_palace.json`

- `activation_type: "dragonsurvival:simple"` — 主动技能
- `target_type: "dragonsurvival:self"` — 目标为自身
- `effect_type: "beloong:tp_loong_palace"` — 使用自定义效果（无参）
- 标配施法动画、冷却、法力消耗、升级配置
