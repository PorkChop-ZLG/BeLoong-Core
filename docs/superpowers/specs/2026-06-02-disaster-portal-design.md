# 天灾维度传送门设计

## 概述

为天灾维度（disaster）和主世界之间设计一个传送门系统，模仿末地创世（End Remastered）模组的实现方式。

## 需求摘要

1. 传送门框架类似原版末地传送门框架，名为"天灾传送门框架"
2. 激活需要来自 Cataclysm 和 FDBosses 的 12 种不同眼球（具体 ID 待提供）
3. 12 种眼球必须全部不同，且全部放上后才激活
4. 眼球消耗性放入框架，不可取回
5. 传送门框架仅创造模式获得
6. 主世界 → 天灾维度：坐标 1:1 同步，传送后生成数据包结构（含返回传送门）
7. 天灾维度 → 主世界：照搬原版末地返回逻辑，回到玩家出生点
8. 不同眼球放置后框架纹理不同（BlockState EnumProperty 方案）

## 文件清单

| 文件 | 说明 |
|------|------|
| `block/DisasterPortalFrame.java` | 传送门框架方块 |
| `block/DisasterPortalFrameEntity.java` | 框架 BlockEntity |
| `block/DisasterPortalBlock.java` | 传送门方块（接触传送） |
| `registry/ModBlocks.java` | 方块 + BlockEntity 注册 |
| `Config.java` | 眼球 ID 等配置项 |
| `BeLoongCore.java` | 注册新方块到事件总线 |

资源文件（模型/纹理/语言）在 `src/main/resources/assets/beloong/` 下按需创建。

## 核心组件

### DisasterPortalFrame — 传送门框架

**继承**：`Block implements EntityBlock`

**照搬 End Remastered 的 `AncientPortalFrame`**，以下为核心差异点：

**BlockState 属性**：
- `FACING`（EnumProperty<Direction>，4 个水平方向）
- `HAS_EYE`（BooleanProperty）
- `EYE_TYPE`（EnumProperty<String>，13 个值：`"empty"` + 12 个眼球 ID）

**传送门形状检测**（5×5 环形，中间 3×3 空心）：
```
    v   v   v
  >           <
  >           <
  >           <
    ^   ^   ^
```
使用 `BlockPatternBuilder`，匹配自身 `DisasterPortalFrame` 方块。

**useItemOn()**：
- 校验手持物品是否在 Config 的 12 眼列表中
- 校验当前框架 `HAS_EYE == false`
- 调用 `isFrameAbsent()` 去重检查
- 设置 `EYE_TYPE` 为对应眼球 ID，`HAS_EYE = true`，消耗 1 个物品
- 遍历所有框架：若全部 `HAS_EYE=true` → 中间 3×3 填充 `DisasterPortalBlock`

**isFrameAbsent()**：
- 通过 `getCompletedPortalShape(false)` 定位传送门结构
- 遍历 5×5 所有框架 BlockEntity，检查是否存在相同眼球
- 存在则返回 false（拒绝放置），不存在则返回 true（允许放置）

### DisasterPortalFrameEntity — 框架 BlockEntity

**照搬 End Remastered 的 `AncientPortalFrameEntity`**：
- 存储字段：`String eye = "empty"`（眼球 ID）
- `saveAdditional` / `loadAdditional`：NBT 持久化
- `getUpdatePacket` / `getUpdateTag`：客户端同步
- 辅助方法：`isEmpty()`, `getEye()`, `setEye()`
- 材质数据注册在 `ModBlocks` 中

### DisasterPortalBlock — 传送门方块

**继承**：`Block`

**entityInside()**：
```
if (level.isClientSide) return;
if (!(entity instanceof ServerPlayer player)) return;

// 冷却检查，防止循环传送
if (player.getPersistentData().getLong("beloong_portal_cooldown") > level.getGameTime()) return;

if (当前维度 == Config.sourceDimension) {
    // 下行：主世界 → 天灾
    targetLevel = 获取 Config.disasterDimension 对应的 ServerLevel
    targetX = player.getX()
    targetZ = player.getZ()
    targetY = targetLevel.getHeight(MOTION_BLOCKING, floor(targetX), floor(targetZ)) + 1
    确保目标区块已加载
    player.teleportTo(targetLevel, targetX, targetY, targetZ, ...)
    player.fallDistance = 0

    // 放置结构模板（仅在目标位置无方块或为空气时放置，避免覆盖玩家建筑）
    BlockPos structurePos = new BlockPos(floor(targetX), targetY - 1, floor(targetZ))
    if (targetLevel.getBlockState(structurePos).isAir()) {
        StructureTemplate.placeInWorld(targetLevel, structurePos, ...)
    }
} else {
    // 上行：天灾 → 主世界（原版末地逻辑）
    respawnPos = player.getRespawnPosition()
    respawnDim = player.getRespawnDimension()
    if (respawnPos == null) {
        respawnPos = player.server.getLevel(Level.OVERWORLD).getSharedSpawnPos()
        respawnDim = Level.OVERWORLD
    }
    targetLevel = player.server.getLevel(respawnDim)
    确保目标区块已加载
    player.teleportTo(targetLevel, respawnPos.getX()+0.5, respawnPos.getY(), respawnPos.getZ()+0.5, ...)
    player.fallDistance = 0
}

// 设置冷却
player.getPersistentData().putLong("beloong_portal_cooldown", level.getGameTime() + cooldownTicks)
```
```

**结构模板放置**：
- 使用 `StructureTemplate.placeInWorld()` 放置配置的结构模板
- 结构模板路径默认 `beloong:disaster/return_portal`
- 结构模板内包含一个预制激活的返回传送门

### 方块模型与纹理

**BlockState JSON**（`disaster_portal_frame.json`）：
```json
{
  "variants": {
    "facing=north,has_eye=false,eye_type=empty": { "model": "beloong:block/disaster_portal_frame" },
    "facing=north,has_eye=true,eye_type=cataclysm:xxx": { "model": "beloong:block/disaster_portal_frame_xxx" },
    ...
  }
}
```
每个眼球类型一个模型文件，指向不同纹理。

## 配置项

在 `Config.java` 服务端配置中添加：

```java
public static final class DisasterPortal {
    public static ConfigValue<List<? extends String>> eyeItems;       // 12 个眼球 ID
    public static ConfigValue<String> sourceDimension;                // 默认 minecraft:overworld
    public static ConfigValue<String> disasterDimension;              // 天灾维度 ID
    public static ConfigValue<String> returnStructureTemplate;        // 默认 beloong:disaster/return_portal
    public static ConfigValue<Integer> teleportCooldownTicks;        // 冷却 ticks
}
```

## 与 End Remastered 的关键差异

| End Remastered | 本实现 |
|------|------|
| 眼球是自定义 `EREnderEye` 物品 | 眼球来自外部模组，校验物品 ID |
| `useOn()` 逻辑在物品类中 | `useItemOn()` 逻辑在框架方块中 |
| 激活后放置 `Blocks.END_PORTAL` | 激活后放置自定义 `DisasterPortalBlock` |
| 传送由原版末地逻辑处理 | 传送由 `entityInside()` 自定义处理 |
| 眼球类型 JSON 配置文件 | 眼球列表在 Config 中配置 |
| 眼球可右键抛出定位要塞 | 无此功能 |
