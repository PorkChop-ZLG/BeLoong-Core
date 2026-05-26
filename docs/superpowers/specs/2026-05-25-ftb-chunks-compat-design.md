# 龙之生存破坏行为 × FTB Chunks 领地保护兼容

## 概述

当龙之生存的方块破坏行为（连锁挖掘、大型龙碰撞破坏）作用于 FTB Chunks 认领区块时，会绕过领地保护，破坏其他玩家的方块。此问题根因在于龙之生存直接调用 `ServerPlayerGameMode.destroyBlock()` 和 `Level.destroyBlock()`/`Level.removeBlock()`，不经过 `BlockEvent.BreakEvent`，而 FTB Chunks 的保护逻辑依赖该事件。

本兼容通过 Mixin 注入，在龙之生存每次尝试破坏方块时，检查 FTB Chunks 领地归属，阻止越权破坏。

## 新建文件

```
src/main/java/com/zonlong/beloong/mixin/
└── DragonDestructionHandlerMixin.java     # 拦截 DS 破坏调用，添加 FTB Chunks 领地检查
```

## 修改文件

- **build.gradle** — 添加 FTB Chunks 作为 `compileOnly` 依赖（`curse.maven:ftb-chunks-forge-314906:6295696`）
- **neoforge.mods.toml** — 添加 `ftbchunks` 为可选依赖
- **Config.java** — 添加 `FIX_FTB_CHUNKS_COMPAT` 服务端配置开关（默认 `true`）
- **beloong.mixins.json** — 添加 `server` 列表，注册 `DragonDestructionHandlerMixin`

## Mixin 注入点

目标类：`by.dragonsurvivalteam.dragonsurvival.server.handlers.DragonDestructionHandler`

DS 源码中有三个破坏调用点，分别位于两个 lambda 方法中：

| Lambda 方法 | 破坏调用 | 所属功能 |
|---|---|---|
| `lambda$destroyBlocksInRadius$1` (L79) | `ServerPlayerGameMode.destroyBlock(BlockPos)` | 连锁挖掘 |
| `lambda$checkAndDestroyCollidingBlocks$0` (L42) | `Level.destroyBlock(BlockPos, boolean)` | 大型龙碰撞 |
| `lambda$checkAndDestroyCollidingBlocks$0` (L44) | `Level.removeBlock(BlockPos, boolean)` | 大型龙碰撞 |

每个注入点使用 `@Inject(method = "..."), at = @At(value = "INVOKE", target = "..."), cancellable = true`，在调用破坏方法前插入 FTB Chunks 检查。若该位置受保护，则 `ci.cancel()` 跳过本次调用。

## 保护检查逻辑

```java
private static boolean isProtected(Entity actor, BlockPos pos) {
    if (!ModList.get().isLoaded("ftbchunks")) return false;
    if (!Config.FIX_FTB_CHUNKS_COMPAT.get()) return false;
    return FTBChunksAPI.api().getManager()
        .shouldPreventInteraction(actor, InteractionHand.MAIN_HAND, pos, Protection.EDIT_BLOCK, null);
}
```

关键点：
- `ModList.get().isLoaded("ftbchunks")` 守卫确保 FTB Chunks 未安装时不崩溃
- `Protection.EDIT_BLOCK` 对应方块破坏权限
- 玩家身份从 lambda 参数直接获取（`lambda$destroyBlocksInRadius$1` 的 `ServerPlayer` 参数；`lambda$checkAndDestroyCollidingBlocks$0` 从 `PlayerTickEvent#getEntity()` 提取）

## 依赖

- **新增依赖**：FTB Chunks (可选)。运行时若未安装，配置开关仍存在但不起作用。
- **已有依赖**：Dragon Survival (必需)。Mixin 目标类来自此模组。

## 配置项

| 键名 | 类型 | 默认值 | 配置类型 | 说明 |
|------|------|--------|----------|------|
| `fixFTBChunksCompat` | 布尔 | `true` | 服务端 | 启用 FTB Chunks 领地保护兼容 |
