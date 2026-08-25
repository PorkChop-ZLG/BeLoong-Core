# 龙盔甲默认回退隐藏配置设计

**日期：** 2026-08-14  
**状态：** 已确认  
**方案：** 方案 A（RETURN 处拦截通用默认盔甲回退）

## Problem Statement

在龙之生存模组中，当龙玩家装备盔甲时，龙模型会渲染对应盔甲外观。若某件盔甲没有专门绘制的龙模型贴图，DS 会回退到通用默认盔甲贴图。

现在需要在 BeLoong-Core 中提供一个配置选项，开启后：

- 如果盔甲没有“物品专属贴图”或“材料专属贴图”，最终只能回退到通用默认盔甲时，直接不显示该盔甲。
- 如果存在物品专属或材料专属贴图，则正常显示。

## Design

### 架构

- 新增客户端配置项 `hideUndevelopedDragonArmor`，默认 `true`。
- 新增客户端 Mixin `DragonArmorRenderLayerMixin`，注入 DS 的 `DragonArmorRenderLayer.generateArmorTextureResourceLocation(Player, EquipmentSlot)`。
- 在方法 RETURN 处判断返回值是否为通用默认盔甲贴图：
  - 配置开启且是通用回退 → 返回一个不存在的贴图位置。
  - 否则保持原返回值。
- `renderArmorSlot` 的 `hasResource(...)` 会因贴图不存在而跳过该槽位，实现“直接不显示”。

### 组件

1. `src/main/java/com/zonlong/beloong/Config.java`
   - 客户端配置区新增：
     - `HIDE_UNDEVELOPED_DRAGON_ARMOR`
     - 默认 `true`
     - key：`hideUndevelopedDragonArmor`

2. `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/DragonArmorRenderLayerMixin.java`
   - 新增 Mixin。
   - `@Inject(method = "generateArmorTextureResourceLocation", at = @At("RETURN"), cancellable = true)`
   - 通过 `isGenericArmorFallback` 识别通用默认贴图。
   - 命中时返回 `beloong:textures/armor/__hidden__/<原路径>`。

3. `src/main/resources/beloong.mixins.json`
   - 在 `client` 列表注册 `dragonsurvival.DragonArmorRenderLayerMixin`。

### 数据流

```text
客户端渲染帧
  -> DragonArmorRenderLayer.purgeUnusedArmorTextures()
  -> prepareArmorTexture(player)
  -> generateArmorTexture(player, imageResource)
  -> renderArmorSlot(...)
       -> generateArmorTextureResourceLocation(player, slot)
            ├─ 物品专属贴图存在 -> 返回该贴图
            ├─ 材料专属贴图存在 -> 返回该贴图
            └─ 否则走到通用默认贴图
                  └─ Mixin RETURN 拦截
                        ├─ 配置关闭 -> 返回通用默认贴图
                        └─ 配置开启 -> 返回不存在的贴图
                              -> hasResource=false
                              -> 跳过该槽位
```

### 错误处理

- 配置关闭时完全保持 DS 原行为。
- 通用回退识别只匹配 `textures/armor/<model>/<prefix>_<slot>.png` 结构，避免误伤物品/材料贴图。
- 若 DS 未来新增其他通用前缀，需要同步扩展识别逻辑。
- 配置切换后，已生成的缓存贴图可能需要重新装备/清理缓存后才生效，属于 DS 缓存机制限制。

### 测试策略

1. `./gradlew compileJava` 构建通过。
2. 游戏内验证：
   - 配置开启时，无专用贴图的模组盔甲不显示。
   - 配置开启时，DS 专属龙盔甲正常显示。
   - 配置开启时，有材料专属贴图的盔甲正常显示。
   - 配置关闭时，恢复通用默认盔甲显示。
   - 配置切换后重新装备，行为正确刷新。
   - Curios / 非盔甲物品不受影响。

## Decisions Made

- **默认开启**：用户确认默认隐藏未专门绘制的盔甲。
- **仅抑制最终通用默认盔甲**：物品专属、材料专属贴图仍然优先显示。
- **使用 RETURN 拦截**：避免依赖方法内调用顺序，降低 DS 升级带来的脆弱性。
- **返回不存在的贴图位置**：让 DS 原有 `hasResource` 检查自然跳过，不修改 DS 源码。

## Non-Goals

- 不修改龙之生存源码。
- 不改变物品专属/材料专属贴图的显示逻辑。
- 不处理 Curios 的隐藏逻辑。
- 不处理配置切换后缓存立即刷新的问题（沿用 DS 缓存机制）。

## Next Steps

调用 planning skill 创建详细实施计划。
