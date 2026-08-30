# 末地返回传送门自定义外观（YUNG's Better End Island 兼容）设计文档

**Date:** 2026-08-30
**Status:** Approved
**Approach:** BeLoong Core 内通过 Mixin 挂接 YUNG（不修改 YUNG 源码）

## Problem Statement

整合包使用 YUNG's Better End Island（BEI）重做末地战斗。当前返回传送门/中央祭坛的外观由 BEI 的 `tower_bottom_open` 结构决定。需求是：

1. 末影龙死亡、返回传送门出现时，把中央传送门/祭坛区域替换为 `activated.nbt` 的外观。
2. 末影龙复活后，把中央传送门/祭坛区域替换为 `deactivated.nbt` 的外观。
3. 保留 YUNG 中央塔，只替换中央传送门/祭坛区域。
4. 首次生成（龙尚未被击杀过）不干预，保持 YUNG 原样。
5. 实现放在 BeLoong Core，不修改 YUNG 源码。

## Design

### Architecture

```
YUNG ExitPortalUtils.spawnPortal(...)
        │
        │ 放置完 YUNG 的底部传送门后
        ▼
BeLoong Core Mixin @Inject(RETURN)
        │
        ├─ 条件：DragonSummon.enabled && isBottomOnly && hasDragonEverSpawned()
        │
        ├─ isActive == true  → 覆盖放置 activated.nbt
        └─ isActive == false → 覆盖放置 deactivated.nbt
```

### Components

#### 1. 配置

不新增配置项。新功能复用 `Config.DragonSummon.enabled` 作为总开关。

#### 2. NBT 资源

将两个 NBT 放入 BeLoong Core：

```
src/main/resources/data/beloong/structure/end_return_portal_activated.nbt
src/main/resources/data/beloong/structure/end_return_portal_deactivated.nbt
```

#### 3. Mixin：`ExitPortalUtilsMixin`

- 目标：`com.yungnickyoung.minecraft.betterendisland.world.util.ExitPortalUtils`
- 注入点：`spawnPortal(IBetterDragonFight, ServerLevel, boolean isActive, boolean isBottomOnly, boolean noCrystalsOverride)` 的 `RETURN`
- 条件：
  - `Config.DragonSummon.enabled.get() == true`
  - `isBottomOnly == true`
  - `((IBetterDragonFight) dragonFight).hasDragonEverSpawned() == true`
- 根据 `isActive` 调用辅助类放置对应 NBT。

#### 4. 辅助类：`CustomEndPortalAppearance`

- `apply(ServerLevel level, EndDragonFight fight, boolean active)`
- 通过 `EndDragonFightAccessor.getPortalLocation()` 获取传送门中心。
- 通过 `StructureManager` 加载：
  - `beloong:end_return_portal_activated`
  - `beloong:end_return_portal_deactivated`
- 对齐逻辑：
  - 使用硬编码偏移：结构原点为 `portalLocation.offset(-7, -1, -7)`。
  - 该偏移是在旧“四基岩中心对齐 `portalLocation`”的基础上整体上移一格得到的。
  - 后续如需微调，只改这一个常量即可。
- 使用 `StructurePlaceSettings` + `template.placeInWorld(...)` 覆盖放置。

#### 5. Mixin 注册

在 `beloong.mixins.json` 的 `mixins` 列表加入：

```json
"betterendisland.ExitPortalUtilsMixin"
```

### Data Flow

```
龙死亡 → YUNG spawnPortal(active=true, bottomOnly=true)
       → BEI 放置原版/塔底传送门
       → 我们的 Mixin RETURN
       → 条件满足 → 覆盖放置 activated.nbt

复活完成 → YUNG spawnPortal(active=false, bottomOnly=true)
       → BEI 放置未激活传送门
       → 我们的 Mixin RETURN
       → 条件满足 → 覆盖放置 deactivated.nbt
```

### Error Handling

| 情况 | 行为 |
|---|---|
| `DragonSummon.enabled = false` | 完全跳过，不影响 YUNG 原逻辑 |
| `isBottomOnly = false` | 跳过，不干扰完整中央塔生成 |
| `hasDragonEverSpawned() = false` | 跳过，首次生成保持 YUNG 原样 |
| `portalLocation == null` | 记录 warn 日志并跳过，不崩溃 |
| NBT 模板不存在/加载失败 | 记录 warn 日志并跳过，不崩溃 |
| 放置过程中出现异常 | try-catch 包裹，记录 error，不影响游戏运行 |

### Testing Strategy

1. 首次进入末地：确认 YUNG 中央塔保持原样，不出现自定义 NBT。
2. 击杀末影龙：确认中央返回传送门区域变成 `activated.nbt`，四个基岩与原版水晶复活点对齐。
3. 触发复活：确认复活流程结束后变成 `deactivated.nbt`。
4. 再次击杀/复活：确认 activated / deactivated 能反复正确切换。
5. 关闭 `dragon_summon.enabled`：确认完全恢复 YUNG 原行为。
6. 兼容检查：确认自定义 NBT 放置不破坏 BEI 龙战状态判定。

## Decisions Made

- 采用 BeLoong Core Mixin 挂接 YUNG，不修改 YUNG 源码。
- 只替换中央传送门/祭坛区域，保留 YUNG 中央塔。
- 只在 `hasDragonEverSpawned() == true` 时生效，首次生成不干预。
- 复用 `DragonSummon.enabled` 作为总开关，不新增配置项。
- 使用硬编码偏移 `portalLocation.offset(-7, -1, -7)`，不依赖 NBT 中的 bedrock 锚点。
- 挂接点为 `ExitPortalUtils.spawnPortal` 的 RETURN，并只处理 `isBottomOnly == true`。

## Non-Goals

- 不修改 YUNG 源码。
- 不替换 YUNG 完整中央塔。
- 不处理首次生成/未击杀状态的 portal 外观。
- 不新增独立配置开关。
- 不处理非 BEI 环境（原版末地）的返回传送门外观。

## Next Steps

调用 planning skill 创建详细实施计划。
