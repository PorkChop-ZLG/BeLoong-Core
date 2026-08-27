# DragonSurvival 新版本兼容性审查报告

**日期：** 2026-08-14  
**审查对象：** BeLoong-Core × 最新 DragonSurvival（`origin/1.21.1`）  
**DS 本地源码：** `D:\Minecraft\DragonSurvival`  
**结论：** 未发现会导致加载崩溃或 Mixin 应用失败的严重不兼容，但存在 4 个重要行为级不兼容/风险。

---

## 1. 总体结论

- BeLoong 引用的所有 DS 类、方法、字段仍然存在。
- 所有 Mixin 目标（包括 lambda 方法名/描述符）都与当前 DS 编译产物匹配。
- `air_strike.json` / `tp_loong_palace.json` 仍符合当前 DS 数据格式。
- FTB Chunks 保护相关 Mixin 目标未变化。
- 没有发现 Critical 级别的编译期或加载期不兼容。

---

## 2. 重要问题

### 2.1 `ClientFlightHandlerMixin` 基于旧版 DS 飞行物理，修复效果可能不准

DS 最新版无输入悬停分支已经变化：

```java
double yMotion = ToggleFlight.hasEnoughFoodToStartFlight(player) ? -gravity + ay : -(gravity * 4) + ay;
player.setDeltaMovement(deltaMovement);
```

BeLoong 的 `ClientFlightHandlerMixin` 仍按旧假设工作：

- 稳定悬停时只清零 `ax/az`，非创造模式没有清零 `deltaMovement.y`，导致“稳定悬停漂移修复”可能不再完全生效。
- 低飞行等级时追加的 `-gravity` 没有考虑 DS 当前已经应用过的 `-gravity + ay`，最终垂直速度不是预期的 `-2g`。

**建议：** 根据当前 DS 的无输入分支重新计算 Mixin 的修正公式。

---

### 2.2 `ManaLossHandler` 可能因 DS 新的法力/经验转换机制而扣除经验

DS 最新版中 `ManaHandler.consumeMana` 已接入法力 ↔ 经验转换系统：

- 当玩家法力不足且启用了经验转换时，会直接扣除经验值。

BeLoong 的 `ManaLossHandler` 直接调用：

```java
ManaHandler.consumeMana(player, deduction);
```

因此 `mana_loss` 效果在法力耗尽后可能开始消耗玩家经验。

**建议：** 如果 `mana_loss` 只应扣除法力，应绕过 `ManaHandler.consumeMana`，直接操作 `MagicData`，或增加一个不进入经验转换的专用扣蓝路径。

---

### 2.3 `ModMobEffects` 静态初始化时跨模组属性查找仍存在时序风险

`FLIGHT_BAN` 和 `SERENE` 在 `static final` 初始化时通过 `BuiltInRegistries.ATTRIBUTE.get(...)` 查找 DS 属性：

```java
BuiltInRegistries.ATTRIBUTE.get(
        ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"));
```

但 NeoForge 的 `DeferredRegister` 条目要等到 `RegisterEvent` 才会真正进入注册表，而 `ModMobEffects` 在模组构造阶段就初始化了。

虽然这不是本次 DS 更新新引入的问题，但审计确认该模式在当前 DS/NeoForge 生命周期下仍然不安全。

**建议：** 改为懒加载属性，或在 `RegisterEvent` / `FMLCommonSetupEvent` 之后再绑定属性修饰符。

---

### 2.4 `DragonArmorRenderLayerMixin` 仍可用，但存在效率与路径假设问题

- 当前 DS 仍然先生成/缓存“复合盔甲贴图”，再判断是否隐藏。视觉上隐藏应该有效，但被隐藏的槽位仍可能参与贴图合成，产生空/透明缓存贴图，造成不必要的渲染开销。
- `isGenericArmorFallback` 假设 `textures/armor/` 后只有一层目录；如果未来 DS 模型路径包含 `/`，会绕过隐藏逻辑。

**建议：** 后续可考虑在更早阶段跳过 `prepareArmorTexture` / `renderArmorSlot`，并让通用回退判断不依赖模型路径层级。

---

## 3. 兼容性良好的部分

- 所有 DS import 均存在。
- 所有 Mixin 目标均匹配当前 DS 源码/编译产物：
  - `ClientFlightHandler.flightControl`
  - `DragonDestructionHandler` 两个 lambda
  - 6 个 DS block effect 的 `apply`
  - `DragonGrowthHandler` / `DragonStage.ticksToGrowth`
  - `TreasureBlock.tick`
  - `DSAttributes.<clinit>` / `attachAttributes`
  - `ToggleFlight.lambda$handleServer$1`
  - `DragonStateHandler.<init>`
  - `ProjectileDamageEffect.apply`
  - `DragonArmorRenderLayer.generateArmorTextureResourceLocation`
- 自定义技能数据文件与当前 DS 注册表格式兼容。
- `DragonStateHandlerMixin` 的默认 DISABLED 覆盖仍然有效。
- FTB Chunks 保护相关 Mixin 目标未变化。

---

## 4. 后续建议

1. 根据当前 DS 飞行物理重新校准 `ClientFlightHandlerMixin`。
2. 确认 `mana_loss` 是否需要避开 DS 经验转换机制。
3. 将 `ModMobEffects` 的跨模组属性查找改为懒加载或注册后绑定。
4. 优化 `DragonArmorRenderLayerMixin` 的隐藏时机和路径判断。
5. 发布前重新构建 DS 并实际启动游戏验证 lambda Mixin。
