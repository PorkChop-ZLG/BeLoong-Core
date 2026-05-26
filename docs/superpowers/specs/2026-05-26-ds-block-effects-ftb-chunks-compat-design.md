# 龙之生存 Block Effect × FTB Chunks 领地保护兼容

## 概述

龙之生存的 dragon ability 系统中，多个 `AbilityBlockEffect` 实现类会直接修改/破坏方块，绕过 FTB Chunks 领地保护。本兼容通过 Mixin 注入，在每个破坏性 effect 的 `apply()` 方法入口处检查 FTB Chunks 领地归属，阻止越权操作。

## 问题分析

### 调用链

```
DragonAbility 触发 → Targeting 类 (LookingAt/Area/Disc/DragonBreath/Self)
  → BlockTargeting.effects().forEach()
    → AbilityBlockEffect.apply(player, ability, pos, direction)
      → 直接调用 Level.destroyBlock() / setBlock() / explode() 等
```

5 个 targeting 类均通过 `effect.apply()` 分发，在 effect 层拦截可覆盖全部路径。

### 破坏性 Effect 清单

| # | 目标类 | 破坏行为 | 所属模块 |
|---|--------|---------|---------|
| 1 | `BlockBreakEffect` | `serverLevel.destroyBlock(pos, dropLoot)` | block_effects |
| 2 | `BlockConversionEffect` | `serverLevel.setBlock(pos, newState)` 替换方块 | block_effects |
| 3 | `ExplodeBlockEffect` | `serverLevel.explode(...)` | block_effects |
| 4 | `FireEffect` | `setBlock(AIR)` / `setBlock(fire)` / `setBlock(SNOWY=false)` / `onCaughtFire()` | block_effects |
| 5 | `BlockHarvestEffect` | `Block.dropResources()` 掉落他人方块战利品 | block_effects |
| 6 | `BonemealEffect` | `performBonemeal()` 催熟他人作物 | block_effects |

### 弹射物路径（暂不处理）

当前注册的 projectile block effect（`ProjectileBlockParticleEffect`、`ProjectileBlockRunFunctionEffect`、`ProjectileAreaCloudEffect`）均为非破坏性，暂无兼容需求。

## 新建文件

```
src/main/java/com/zonlong/beloong/
├── util/
│   └── ClaimProtectionHelper.java          # isClaimed() 共用工具（从 DragonDestructionHandlerMixin 提取）
└── mixin/
    ├── BlockBreakEffectMixin.java           # 拦截 BlockBreakEffect.apply()
    ├── BlockConversionEffectMixin.java      # 拦截 BlockConversionEffect.apply()
    ├── ExplodeBlockEffectMixin.java         # 拦截 ExplodeBlockEffect.apply()
    ├── FireEffectMixin.java                 # 拦截 FireEffect.apply()
    ├── BlockHarvestEffectMixin.java         # 拦截 BlockHarvestEffect.apply()
    └── BonemealEffectMixin.java             # 拦截 BonemealEffect.apply()
```

## 修改文件

- **Config.java** — 新增 `DS_FTBCHUNKS_COMPAT` 服务端配置项（`ds_ftbchunks_compat`，默认 `true`）
- **DragonDestructionHandlerMixin.java** — `isClaimed()` 实现改为委托 `ClaimProtectionHelper`
- **beloong.mixins.json** — 注册 6 个新 Mixin（server 列表）
- **zh_cn.json** — 添加配置项翻译

## Mixin 注入点

全部 6 个 Mixin 统一采用以下模式：

```java
@Mixin(目标Effect类.class)
public abstract class XxxEffectMixin {
    @Inject(method = "apply",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
```

| Mixin 类 | 目标类 | 注入方法 | remap |
|----------|-------|---------|-------|
| `BlockBreakEffectMixin` | `BlockBreakEffect` | `apply` | false |
| `BlockConversionEffectMixin` | `BlockConversionEffect` | `apply` | false |
| `ExplodeBlockEffectMixin` | `ExplodeBlockEffect` | `apply` | false |
| `FireEffectMixin` | `FireEffect` | `apply` | false |
| `BlockHarvestEffectMixin` | `BlockHarvestEffect` | `apply` | false |
| `BonemealEffectMixin` | `BonemealEffect` | `apply` | false |

## ClaimProtectionHelper

```java
public class ClaimProtectionHelper {
    private static final Protection ALWAYS_BLOCK =
        (player, pos, hand, chunk, entity) -> ProtectionPolicy.CHECK;

    public static boolean isClaimed(Entity actor, BlockPos pos) {
        if (actor == null || pos == null) return false;
        if (!ModList.get().isLoaded("ftbchunks")) return false;
        if (!Config.DS_FTBCHUNKS_COMPAT.get()) return false;

        var manager = FTBChunksAPI.api().getManager();
        if (manager == null) return false;

        return manager.shouldPreventInteraction(
            actor, InteractionHand.MAIN_HAND, pos, ALWAYS_BLOCK, null);
    }
}
```

`ALWAYS_BLOCK` 策略：忽略团队白名单和权限设置，只要区块被认领就阻止，比 FTB Chunks 默认的 `EDIT_BLOCK` 策略更严格。

## 配置项

| 键名 | 类型 | 默认值 | 配置类型 | 中文名 |
|------|------|--------|----------|--------|
| `ds_ftbchunks_compat` | 布尔 | `true` | 服务端 | 龙之生存FTB区块兼容 |

## 依赖

- **已满足**：FTB Chunks (可选)、Dragon Survival (必需)
- 无新增依赖
