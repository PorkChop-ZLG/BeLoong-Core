# 龙宫放置被拦 → 客户端幽灵物品（design）

**日期：** 2026-10-13
**目标：** 修掉"在龙宫放置被拦时，客户端已按预测消耗物品、服务端没有，导致手上出现幽灵物品（只有重登才恢复）"。
**触发案例：** 龙之生存的**龙魂**（`dragonsurvival:dragon_soul`，stack=1 且带数据组件）。
**状态：** 已实施（2026-10-13），**待实机验收**

---

## 1. 现象

生存模式、手持龙魂、蹲下右键龙宫的方块：

- 服务端：保护生效 ⇒ **方块没被放置**、**物品没被消耗**（正确）；
- 客户端：认为放置成功 ⇒ **本地把龙魂消耗掉了** ⇒ 槽位视觉上变空；
- 双方不一致，且**没有任何一方会自动纠正** ⇒ 只有**退出重进**（整体库存同步）才恢复。

## 2. 根因链（对 2.0.71 参考源码核实；该版本与整合包 jar 一致）

**① 龙魂放置走标准 `BlockItem` 路径**
`DragonSoulItem#place`（`DragonSoulItem.java:101`）只做前置判断，通过后 `super.place(context)`（`:118`）。

**② 放置事件只在服务端发** —— `ItemStack.java:363-368`（NeoForge 补丁）：
```java
if (!context.getLevel().isClientSide) return CommonHooks.onPlaceItemIntoWorld(context);  // 服务端
return onItemUse(context, (c) -> getItem().useOn(context));                              // 客户端：纯原版
```

**③ 客户端在本地消耗**：`BlockItem.java:98` = `itemstack.consume(1, player);`，
而 `ItemStack#consume` 只判 `hasInfiniteMaterials()`（创造模式）⇒ **不判 side**（`ItemStack.java`）。

**④ 服务端被取消后只回滚方块，不回滚物品栏** —— `CommonHooks.java:594-650`：
```
608-609  int size = itemstack.getCount(); DataComponentMap components = ...;   // 放置前
612-615  captureBlockSnapshots = true; ret = item.getItem().useOn(context);    // 内含 consume(1)
630-631  itemstack.setCount(size); itemstack.applyComponents(components);      // ★ 先还原成放置前
640      EventHooks.onBlockPlace(...)                                            // ★ 我们在这里取消
643-650  if (eventResult) { ret = FAIL; snap.restore(flags | UPDATE_CLIENTS) }  // ★ 只回方块，且推给客户端
```
⇒ 服务端净消耗 = 0 ✓；**方块侧由 `UPDATE_CLIENTS` 自动纠正**（所以没有幽灵方块）✓；
**物品栏没有任何包** ⇒ 客户端永远停留在"少一个"的状态 ✓✓

**⑤ 为什么只有龙魂暴露**：它 `stack = 1` 且带数据组件 ⇒ 少 1 = **整个槽位空掉**，肉眼可见；
普通方块堆叠少 1 几乎看不出，且任何一次库存同步（拾取/开箱/换维度）都会顺手纠正。
⇒ **这是通用缺口，龙魂只是把它放大。** 创造模式不受影响（`hasInfiniteMaterials`）。

**⑥ 为什么我们没兜住**：`LoongPalaceProtectionHandler#onBlockPlace`（`:65-76`）取消时只
`setCanceled(true)` + `notifyPlayer`（`:294`，仅发消息）。而同一文件里对**桶 / 花盆 / 悬挂实体**
三道门**已经**做了客户端回滚（`:302` `denyPlayerInteraction`）—— **放置门漏了同一件事**。

## 3. 方案对比与选择

| | 方案 | 代价 | 覆盖 |
|---|---|---|---|
| **1 ✅ 采用** | **我们侧在取消放置后补一次物品栏同步** | 每次被拦多 2 个槽位包 | **通用** —— 所有因我们取消而 desync 的物品（龙魂、任意 BlockItem、桶…） |
| 2 | DS 侧让客户端不预测消耗 | 破坏客户端预测手感；参考区只读、DS 非本仓库工程；**修不了别的模组** | 仅龙魂 |
| 3 | 客户端也取消放置 | **本路径上无效**：客户端收不到 `EntityPlaceEvent`（`ItemStack.java:366` 只在 `!isClientSide` 时发）；要改挂 `UseItemOnBlockEvent`，影响面大 | — |
| 4 | 只针对龙魂特判 | 把 DS 的物品类耦合进龙宫保护，且不通用 | 仅龙魂 |

**选 1**：与既有三道门同一做法（先例现成）、通用、且不动 DS。

## 4. 实现

在 `LoongPalaceProtectionHandler#onBlockPlace` 的玩家分支里，`setCanceled(true)` 之后调用
`syncHeldItemSlots(player)`：

```java
private static void syncHeldItemSlots(ServerPlayer player) {
    if (player instanceof FakePlayer) return;                 // 假玩家没有真实客户端（与 notifyPlayer 同一先例）
    syncSlot(player, player.getInventory().selected);         // 主手当前槽
    syncSlot(player, Inventory.SLOT_OFFHAND);                 // 副手（= 40，用命名常量）
}
private static void syncSlot(ServerPlayer player, int slot) {
    player.connection.send(new ClientboundContainerSetSlotPacket(
            -2, 0, slot, player.getInventory().getItem(slot)));
}
```

**三个要点（都有依据）**

1. **为什么双手**：`EntityPlaceEvent` **不带 hand**（javap 核实：只有 `getEntity` /
   `getBlockSnapshot` / `getPlacedBlock` / `getPlacedAgainst`）⇒ 无法知道是哪只手，双手即覆盖。
2. **时机正好**：事件触发**之前** NeoForge 已在 `CommonHooks.java:630` 把服务端 ItemStack
   **还原为放置前的数量** ⇒ 这里读到的就是"应有"的正确值，**不需要自己 +1**。
3. **不需要同步方块**：NeoForge 已用 `snap.restore(flags | Block.UPDATE_CLIENTS)` 推给客户端。

包格式与 `denyPlayerInteraction`（`:307-309`）一致：`ClientboundContainerSetSlotPacket(-2, 0, slot, stack)`。

## 5. 已知取舍

1. **每次被拦的玩家放置多 2 个槽位包**。龙宫内高频右键时段可考虑节流；当前实测量级不值得加复杂度。
2. **只覆盖"我们取消"的场景**。别的模组若也取消放置事件且不回滚物品，同样会 ghost —— 那是它们的问题。
3. **不改客户端放行策略**：这是**必须**的，因为客户端根本收不到该事件（见方案 3）。
4. 不新增配置项（§十：默认生效）；不改龙之生存（参考区只读）。

## 6. 顺带发现：第三处文档漂移（待裁定）

`docs/龙宫维度总设计.md` §8.2 写"**客户端侧 → 放行（不干预客户端预测）**"——
对 `EntityPlaceEvent` 而言这句**多余**：NeoForge 只在服务端发它（`ItemStack.java:366`），客户端收不到。
而真正的客户端预测（`ItemUseOnBlockEvent` 双端 + `BlockItem.place`）我们**并没有**回滚
—— 这正是本 BUG 的认识根源：文档写了"不干预预测"，却没人负责**回滚**它。
建议在 §8.2 补一句"**取消放置时由取消方负责回滚客户端物品栏**"。

## 7. 验收口径

一句话：**在龙宫放置被拦后，手上物品数量不变（无需重登），方块也不出现。**
