# 龙宫放置被拦 → 客户端幽灵物品（plan）

**日期：** 2026-10-13
**设计：** `docs/plans/2026-10-13-loong-palace-placement-desync-design.md`
**状态：** 已实施（2026-10-13），**待实机验收**（见 Task 3 的 8 条）

## Task 1：在放置被拦后回滚客户端物品栏

**修改** `src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionHandler.java`

- 玩家分支内 `event.setCanceled(true)` 之后，调用新增的 `syncHeldItemSlots(player)`。
- 新增 `private static void syncHeldItemSlots(ServerPlayer)`：
  `FakePlayer` 直接 return；否则同步 `player.getInventory().selected` 与 `Inventory.SLOT_OFFHAND`。
- 新增 `private static void syncSlot(ServerPlayer, int)`：发
  `ClientboundContainerSetSlotPacket(-2, 0, slot, player.getInventory().getItem(slot))`。
- 新增 `import net.minecraft.world.entity.player.Inventory;`（`ClientboundContainerSetSlotPacket`、
  `FakePlayer`、`ServerPlayer` 已在 import 里）。
- javadoc 写清为什么需要（NeoForge 只回方块不回物品栏）与为什么双手（事件不带 hand）。

**判据：** 编译通过；非玩家 actor 分支不受影响。

## Task 2：验证

```powershell
# ① 构建（新证据）
.\gradlew.bat build --rerun-tasks --no-build-cache
# ② 警告数不得超出基线（ParameterListAccessor ×2 / PossibleBiomesFilterMixin ×1 = 3 条）
# ③ 产物复核：beloong-<版本>.jar 存在；本改动不新增类，故只需确认时间戳是本次构建
Get-Item build\libs\beloong-*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 3 Name,LastWriteTime,Length
```

> 本改动是**纯 Java 逻辑**（无 mixin、无数据包、无配置项）⇒ 不需要 `runClient` 应用期验证；
> 但**必须**实机验收（下）。

## Task 3：实机验收清单

| # | 场景 | 期望值 | 判据 |
|---|---|---|---|
| 1 | 生存模式，手持**龙魂**，蹲下右键龙宫方块 | 被拦 + 提示；**手上龙魂仍在，无需重登** | 槽位数量不变；方块不出现 |
| 2 | 同上，改用**普通方块**（石头，堆叠 64） | 数量不变（64 → 64） | 数量不变 |
| 3 | 同上，**副手**持物品放置 | 副手数量不变 | 两处槽位都正确 |
| 4 | **龙宫外**正常放置龙魂塑像 | 正常消耗 1、方块出现 | 无回归 |
| 5 | 有 FTB `bypass_protection` 的账号在龙宫放置 | 正常放置并消耗 | 无回归 |
| 6 | **创造模式**在龙宫放置 | 正常（本就不消耗） | 无回归 |
| 7 | 龙宫内**原版手挖**方块 | 仍被拦（现有 12 道门） | 无回归 |
| 8 | 快速连续右键放置（约 20 次/秒） | 手感无异常、无卡顿 | 观察是否有明显掉帧 |

**旋钮：** 无（不新增配置项）。

## 待裁定（随本轮一并记录）

- design §6 的第三处文档漂移：`龙宫维度总设计.md` §8.2 的"客户端侧 → 放行（不干预客户端预测）"
  对 `EntityPlaceEvent` 是多余描述；建议补"取消放置时由取消方负责回滚客户端物品栏"。
