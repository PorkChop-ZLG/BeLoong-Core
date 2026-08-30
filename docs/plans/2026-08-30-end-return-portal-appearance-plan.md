# 末地返回传送门自定义外观实施计划

**Date:** 2026-08-30
**Status:** Ready for implementation
**Design:** [2026-08-30-end-return-portal-appearance-design.md](./2026-08-30-end-return-portal-appearance-design.md)

## 实施步骤

### 1. 复制 NBT 资源

将两个 NBT 文件复制到：

```
src/main/resources/data/beloong/structure/end_return_portal_activated.nbt
src/main/resources/data/beloong/structure/end_return_portal_deactivated.nbt
```

### 2. 新增辅助类 `CustomEndPortalAppearance`

路径：
`src/main/java/com/zonlong/beloong/compat/betterendisland/CustomEndPortalAppearance.java`

职责：
- `apply(ServerLevel level, EndDragonFight fight, boolean active)`
- 从 `EndDragonFightAccessor.getPortalLocation()` 获取 `portalLocation`
- 加载 `beloong:end_return_portal_activated` / `beloong:end_return_portal_deactivated`
- 计算放置原点：`portalLocation.offset(-7, -1, -7)`
- 使用 `StructurePlaceSettings` + `template.placeInWorld(...)` 覆盖放置
- try-catch 包裹，失败只记录日志

关键代码骨架：

```java
public final class CustomEndPortalAppearance {
    private static final ResourceLocation ACTIVATED = ResourceLocation.fromNamespaceAndPath("beloong", "end_return_portal_activated");
    private static final ResourceLocation DEACTIVATED = ResourceLocation.fromNamespaceAndPath("beloong", "end_return_portal_deactivated");

    public static void apply(ServerLevel level, EndDragonFight fight, boolean active) {
        BlockPos portalLocation = ((EndDragonFightAccessor) fight).getPortalLocation();
        if (portalLocation == null) {
            BeLoongCore.LOGGER.warn("Cannot apply custom end portal appearance: portalLocation is null");
            return;
        }

        ResourceLocation id = active ? ACTIVATED : DEACTIVATED;
        Optional<StructureTemplate> template = level.getStructureManager().get(id);
        if (template.isEmpty()) {
            BeLoongCore.LOGGER.warn("Cannot apply custom end portal appearance: missing structure {}", id);
            return;
        }

        BlockPos origin = portalLocation.offset(-7, -1, -7);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE);
        try {
            template.get().placeInWorld(level, origin, origin, settings, level.random, 2);
        } catch (Exception e) {
            BeLoongCore.LOGGER.error("Failed to place custom end portal appearance {}", id, e);
        }
    }
}
```

### 3. 新增 Mixin `ExitPortalUtilsMixin`

路径：
`src/main/java/com/zonlong/beloong/mixin/betterendisland/ExitPortalUtilsMixin.java`

目标：
`com.yungnickyoung.minecraft.betterendisland.world.util.ExitPortalUtils`

注入点：5 参数 `spawnPortal` 的 `RETURN`

关键代码骨架：

```java
@Mixin(value = ExitPortalUtils.class, remap = false)
public abstract class ExitPortalUtilsMixin {
    @Inject(method = "spawnPortal(Lcom/yungnickyoung/minecraft/betterendisland/world/IBetterDragonFight;Lnet/minecraft/server/level/ServerLevel;ZZZ)V",
            at = @At("RETURN"), remap = false)
    private static void beloong$applyCustomEndPortal(
            IBetterDragonFight dragonFight,
            ServerLevel serverLevel,
            boolean isActive,
            boolean isBottomOnly,
            boolean noCrystalsOverride,
            CallbackInfo ci) {
        if (!Config.DragonSummon.enabled.get()) return;
        if (!isBottomOnly) return;
        if (!dragonFight.hasDragonEverSpawned()) return;

        EndDragonFight fight = (EndDragonFight) dragonFight;
        CustomEndPortalAppearance.apply(serverLevel, fight, isActive);
    }
}
```

### 4. 注册 Mixin

修改 `src/main/resources/beloong.mixins.json`，在 `mixins` 列表加入：

```json
"betterendisland.ExitPortalUtilsMixin"
```

### 5. 构建与运行验证

1. `./gradlew build`
2. `./gradlew runClient`
3. 按设计文档的验证步骤逐项测试：
   - 首次进入末地不改变
   - 击杀龙后出现 activated
   - 复活后出现 deactivated
   - 关闭 `dragon_summon.enabled` 后恢复原样
4. 若四个 bedrock 对齐有偏差，调整 `CustomEndPortalAppearance` 中的原点偏移常量。

## 风险点

- `ExitPortalUtils.spawnPortal` 方法签名可能随 YUNG 版本变化；若构建/运行报 Mixin 找不到方法，需要按实际 YUNG 版本调整 `method` 描述。
- NBT 放置可能覆盖 YUNG 塔底结构；如果 15×15 超出预期区域，需要缩小 NBT 或调整放置位置。
- 对齐偏移可能需要多次游戏内实测。
