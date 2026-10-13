# 诊断：放置被否决 → 客户端幽灵物品（通用缺口）

**日期：** 2026-10-13
**性质：** 诊断记录 + **暂不实施**的加固方案（按用户裁定：等实际发生再补）
**关联：** `docs/plans/2026-10-13-loong-palace-placement-desync-design.md`（已完成的那次修复：只覆盖"我们自己取消"）

---

## 1. 起因：一次不可复现的观察

修完"龙宫放置被拦 ⇒ 幽灵龙魂"之后实测中出现**一次**，此后无法复现：

> 玩家在龙宫放置龙魂 → 被拦、物品栏被我们回滚（正确 ✓）→ 前往主世界 → **立刻**放置龙魂 →
> **客户端显示龙魂被消耗，但服务端没有放置塑像** → 退出重进后：塑像确实没放、龙魂回到物品栏。

⇒ 症状与修掉的那个 BUG **同类**（客户端少 1、服务端不少），但发生在**主世界**，且**不是我们**造成的。

## 2. 已验证的排除项

| 嫌疑 | 结论 | 证据 |
|---|---|---|
| 本模组的龙宫保护 | **排除** | 主世界 ⇒ `LoongPalaceProtectionPolicy.isLoongPalace(level)` 为假 ⇒ `shouldPreventEdit` 直接 `return false` |
| FTB Chunks 认领保护 | **排除** | 测试存档 `run/saves/二重身崩溃测试/ftbchunks/*.snbt` 内为 **`chunks: { }`** ⇒ 该存档**没有任何认领** |
| 异常 / 崩溃 / 丢包 | **排除** | 23:10–23:18 窗口内无 `Exception`；只有无关的 spawn-placement / legacy-tag / recipe 警告 |
| 上一轮新增的槽位同步包 | **排除** | 它只把**服务端那份正确值**写给客户端，不可能导致"服务端不放置" |

## 3. 结构性根因：取消方只回方块、不回物品栏

`CommonHooks#onPlaceItemIntoWorld`（review-src `CommonHooks.java:594-650`）：

```
608-609  int size = itemstack.getCount();  DataComponentMap components = ...;   // 放置前快照
612-615  captureBlockSnapshots = true;  ret = item.getItem().useOn(context);    // 内含 consume(1)
630-631  itemstack.setCount(size);  itemstack.applyComponents(components);      // 还原（仅服务端那份，无网络包）
640      EventHooks.onBlockPlace(...)                                            // 取消点在这里
643-650  if (eventResult) { ret = FAIL; snap.restore(flags | Block.UPDATE_CLIENTS) }   // 只回滚方块
```

而客户端走的是 `ItemStack#useOn` 的**另一条分支**（`ItemStack.java:366` 只在 `!isClientSide` 时进
`onPlaceItemIntoWorld`），直接执行原版 `BlockItem#place`，其中 `itemstack.consume(1, player)`
（`BlockItem.java:98`）只看 `hasInfiniteMaterials()`、**不判 side**。

⇒ **"客户端已消耗 / 服务端未放置"时，NeoForge 只在经过 `EntityPlaceEvent` 的取消路径上纠正方块
（`UPDATE_CLIENTS`），物品栏在任何路径上都不会被纠正。**

按否决发生的位置，可见性不同：

| 否决点 | 客户端幽灵方块 | 客户端幽灵物品 |
|---|---|---|
| `EntityPlaceEvent` 被取消（我们 / FTB 等） | 否（`UPDATE_CLIENTS` 纠正） | **是** |
| 物品 `place()` 自己返回 `PASS`（早于事件） | 否 | **是** |
| `BlockItem.place` 内部失败（`FAIL`，如目标位置/面不合法） | **是** | **是** |

## 4. 龙之生存侧的结构性不对称（独立于本次事故，是颗雷）

`DSDataAttachments.java`：

```java
PLAYER_DATA = REGISTRY.register("player_data",
        () -> AttachmentType.serializable(PlayerData::new).copyOnDeath().build());   // ← 没有 .sync(...)
```

`DragonSoulItem.java:108` 的放置门读的是**服务端那份**：

```java
if (player != null && (!player.isCrouching()
        || !player.getData(DSDataAttachments.PLAYER_DATA).enabledDragonSoulPlacement))
    return InteractionResult.PASS;
```

⇒ **`PLAYER_DATA` 不同步给客户端** ⇒ 客户端的 `enabledDragonSoulPlacement` 永远是本地默认 `true`
（`PlayerData.java:12`）⇒ 只要它与服务端持久化的那份不一致，就会稳定复现
"客户端预测放置并消耗 / 服务端 `place()` 返回 `PASS`"。
（触发路径：玩家用键位把它关掉 ⇒ 服务端持久化 `false`；客户端任何一次附件重建后又回到 `true`。）

**两条自我否证（避免以后再走一遍）：**
- `PlayerData.java:52` 的读取**带 key 判断**（`if (nbt.contains(...))`）⇒ **旧存档缺键不会读成 `false`**（保留默认 `true`）。
- `TOGGLE_DRAGON_SOUL_PLACEMENT` 默认绑 **`GLFW_KEY_UNKNOWN`（未绑定）** ⇒ 误触基本不可能 ⇒ 本次多半**不是**这条。

## 5. 剩余假设（都依赖时序 ⇒ 只中一次、不可复现）

- **H2｜蹲下竞态（倾向）**：放置门要求 `player.isCrouching()`，而蹲下状态由**另一个包**
  （`ServerboundPlayerCommandPacket`）传递。刚跨维度抵达时同一瞬间"蹲下 + 右键"，服务端可能仍看到
  "未蹲下" ⇒ `PASS` ⇒ 不放置、不消耗；客户端本地已蹲 ⇒ 预测成功并消耗。
- **H3｜跨维度世界同步竞态**：客户端在尚未同步完的主世界位置预测成功，服务端
  `getPlacementState` / `placeBlock` 失败 ⇒ `FAIL`。
- **H1｜`enabledDragonSoulPlacement` 瞬时不一致**（见 §4）：需要一次显式切换 ⇒ 本次**弱**，但结构性存在。

**判别判据（万一复发，很便宜）：** 重登前看**主世界那个位置有没有"幽灵塑像"方块**——
有 ⇒ H3；没有（只有物品消失）⇒ H2 或 §4 的 `PASS` 路径。

## 6. 加固方案（**已记录，暂不实施**）

把回滚从"**我们取消时**"提升为"**事件最终被取消时**"：

> 新增一个 `EventPriority.LOWEST` 的 `BlockEvent.EntityPlaceEvent` 处理器：
> 若 `event.isCanceled()` 且 `event.getEntity() instanceof ServerPlayer` ⇒ 回滚该玩家的**双手槽位**
> （复用 `LoongPalaceProtectionHandler#syncSlot` 的包格式：`ClientboundContainerSetSlotPacket(-2, 0, slot, stack)`）。
>
> 实现注意：`@SubscribeEvent(priority = EventPriority.LOWEST)` 才能观察到其它处理器（含默认优先级）的取消；
> `FakePlayer` 跳过；**不限龙宫维度** —— 这条不变量是通用的。

| | 说明 |
|---|---|
| 代价 | 只在"某次玩家放置真的被取消"时多发 2 个槽位包；被取消本身稀有 ⇒ 可忽略 |
| 收益 | **顺带覆盖 FTB Chunks 自己取消放置**的场景（其 NeoForge 入口 `FTBChunksForge` 确实引用了 `EntityPlaceEvent` / `BlockEvent`）。正式整合包一旦启用领地，**每个玩家在自己无权限的领地里放置任何方块**都会出现幽灵物品 —— 比龙魂严重得多 |
| 不覆盖 | H2 / H3 那类"事件都没触发就失败"的场景（只能由 DS / 原版侧修；DS 的 Java 源码在只读参考区，不是本仓库工程） |

**触发条件（何时才补）：** 只要出现"**非本模组取消的放置**留下了幽灵物品"的实际反馈，
或整合包开始使用 FTB Chunks 领地认领 ⇒ 按本草实施。

## 7. 未能验证

- 那一次事故的**具体触发原因**：日志不记录放置判定，且不可复现 ⇒ **未确认**（§5 为排序后的假设）。
- §4 的 `PLAYER_DATA` 不同步是否真的参与过本次事故：**未确认**（键位默认未绑定，倾向无关）。
- 未做代码级复现实验（例如人为把服务端 `enabledDragonSoulPlacement` 置 `false` 后验证"客户端消耗 / 服务端 PASS"）——
  若要落地 §6 或向 DS 上游报告，建议先做这个实验。
