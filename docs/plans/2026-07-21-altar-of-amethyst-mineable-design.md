# Altar of Amethyst 可挖掘化 设计文档

**Date:** 2026-07-21  
**Status:** Approved  
**Approach:** Mixin target `Altar_Of_Amethyst_Block` + 标签驱动

## Problem Statement

灾变模组（Cataclysm）中的紫水晶祭坛方块（`cataclysm:altar_of_amethyst`）的 `destroyTime` 为 `-1`（不可破坏），且 `noLootTable()` 导致没有任何掉落物。玩家无法在生存模式下获取该方块。

需求：使其可被下界合金镐挖掘，掉落自身方块物品，挖掘速度与黑曜石相当（硬度 50）。

## Design

### Architecture

```
mixin/cataclysm/
  AltarOfAmethystMixin.java           ← 新增
  BurningArenaStructureMixin.java
  ...

data/cataclysm/loot_table/blocks/
  altar_of_amethyst.json              ← 新增

data/minecraft/tags/block/
  mineable/pickaxe.json               ← 追加 cataclysm:altar_of_amethyst
  needs_netherite_tool.json           ← 追加 cataclysm:altar_of_amethyst
```

### Components

**Mixin: AltarOfAmethystMixin.java**

- Target: `com.github.L_Ender.cataclysm.blocks.Altar_Of_Amethyst_Block`
- `@ModifyArg` on constructor → `super(properties)` call
  - Replace `destroyTime: -1` → `50` (obsidian-grade)
  - Add `.requiresCorrectToolForDrops()`
  - Preserve `explosionResistance: 3600000`
- `@Inject` on `getLootTable()` at `@At("HEAD")`, cancellable
  - Override `noLootTable()` effect
  - Return `this.builtInRegistryHolder().key()` (block's own loot table)

**Loot Table: `data/cataclysm/loot_table/blocks/altar_of_amethyst.json`**

- Type: `minecraft:block`
- Single pool: drop `cataclysm:altar_of_amethyst` with `survives_explosion` condition

**Tags (overriding vanilla):**

- `data/minecraft/tags/block/mineable/pickaxe.json` → add `"cataclysm:altar_of_amethyst"`
- `data/minecraft/tags/block/needs_netherite_tool.json` → add `"cataclysm:altar_of_amethyst"`

### Data Flow

```
Player mines with netherite pickaxe
  → vanilla isCorrectToolForDrops()
    → #mineable/pickaxe tag check → ✓
    → #needs_netherite_tool tag check → ✓
  → vanilla getDestroyProgress()
    → destroyTime = 50 (mixin-modified)
    → obsidian-speed mining
  → Block destroyed
  → vanilla getLootTable()
    → mixin returns block's own loot table key
    → data/cataclysm/loot_table/blocks/altar_of_amethyst.json
    → drops altar item
```

### Error Handling

| Scenario | Behavior |
|----------|----------|
| Cataclysm not installed | Mixin can't find target class, framework skips injection, no effect |
| Tags overwritten by other mods | Worst case: incorrect mining speed; never crashes |
| No netherite pickaxe | vanilla `isCorrectToolForDrops()` returns false → no drop + extremely slow mining |

## Decisions Made

- **标签驱动工具判定** (非硬编码): 使用 `#mineable/pickaxe` 和 `#needs_netherite_tool` 标签，任何注册到这些标签的工具都能挖掘
- **`@ModifyArg` 而非 `@Overwrite`** : 更精准，只修改 Properties 参数不影响方法逻辑
- **无 Config 开关**: 用户要求直接生效
- **Loot table 使用 `survives_explosion`**: 与原版方块行为一致，TNT 破坏时概率不掉落

## Non-Goals

- 不修改 Cataclysm 其他三个祭坛（ALTAR_OF_FIRE, ALTAR_OF_VOID, ALTAR_OF_ABYSS）
- 不保留祭坛内部的烹饪物品（破坏时清空）

## Next Steps

Invoke planning skill to create implementation plan.
