# 建筑之杖 × 龙宫保护：认领语义对等（design）

**日期：** 2026-10-13
**目标：** 让**建筑之杖（Building Wands，modid `wands`）**把 `beloong:loong_palace` 视同"已被他人认领的区块"，
从而沿用它**自身**的认领保护行为（含它原本的红色提示 `wands.message.claimed_chunk`）。
**本意是扩充它的 FTB Chunks 兼容，不是新增一套保护。**

**状态：** 待实施

---

## 1. 问题（为什么现有 12 道门拦不住它）

本模组的玩家编辑保护走 **NeoForge 方块事件**（`BlockEvent.BreakEvent` /
`BlockEvent.EntityPlaceEvent`，见 `compat/ftbchunks/LoongPalaceProtectionHandler.java:56,65`）。
建筑之杖不用这条路：它自己判一次认领，然后**直接**写方块。

`net.nicguzzo.wands.wand.Wand.place_block(BlockPos, BlockState)` 的字节码（对
`building-wands-363392-8207219.jar`，即 **3.0.5**）：

```
 0: iconst_0                     boolean r = false;
 3: getfield level
 6: checkcast ServerLevel        ← 硬转，无 instanceof 门 ⇒ 该方法只在服务端跑
14: ClaimDispatch.claimCanInteract((ServerLevel) level, pos, player)   ← 认领门是第一条语句
17: ifne 42
20-37: player.displayClientMessage("wands.message.claimed_chunk".RED, true)   ← 红字提示
40: ireturn                     return false
42+: 之后才 setBlockAndUpdate / 调 destroyBlock
```

而那道门 `net.nicguzzo.compat.claims.ClaimDispatch.claimCanInteract` 是纯派发器：

```java
if (has_opac      && !Opac.canInteract(level, player, pos))      return false;
if (has_ftbchunks && !FTBChunks.canInteract(level, player, pos)) return false;   // ← 我们够不着的那一环
if (has_flan …) / if (has_goml …)
return true;
```

其中 `FTBChunks.canInteract` = `!manager.shouldPreventInteraction(player, MAIN_HAND, pos, Protection.EDIT_BLOCK, null)`。
龙宫**未被认领**且 `no_wilderness_dimensions` 为空 ⇒ FTB 判定链落到"未认领 + 未启用荒野保护 ⇒ 放行"
⇒ 建筑之杖畅通无阻。**我们无法通过"换我们自己的 API 调用"改变它问到的答案**（详见讨论轮的结论）。

## 2. 方案：把龙宫伪装成"已被认领"

在 `ClaimDispatch.claimCanInteract` 的 **HEAD** 注入：**若目标维度是龙宫且该玩家无 FTB 绕过权限 ⇒ 直接返回 `false`**。

这与"该区块真的被认领了"对建筑之杖而言**不可区分**：

| | 真实认领 | 本方案 |
|---|---|---|
| `claimCanInteract` 的返回值 | `false` | `false` |
| 红字提示 `wands.message.claimed_chunk` | 显示 | 显示（沿用，已确认可接受） |
| `Wand.place_block` 的后续（`setBlockAndUpdate` / `destroyBlock`） | 不执行 | 不执行 |

## 3. 覆盖面（全 jar 161 个 class 扫过）

| 写方块的口 | 是否被本方案覆盖 | 依据 |
|---|---|---|
| `Wand.place_block` | ✅ | 门在方法第一条语句（偏移 14 < 49/102/…） |
| `Wand.destroyBlock` | ✅ | 全 jar 只有 `Wand` 引用它，且其调用点全部落在 `place_block` 内 ⇒ 被 return 前置挡住 |
| `UndoManager.undo/redo` | ❌ **不覆盖** | 它不查认领门；`record(BlockPos,…)` 只存 `BlockPos`、不带维度 |

**`UndoManager` 属已知取舍，不在本方案范围**：建筑之杖在**真实已认领区块**里同样不拦 `undo/redo`
⇒ 不覆盖它才符合"和它原有的保护效果一样"这一验收口径。
（且 `place_block` 的门在所有写入**之前**，被拦下的尝试不会写入 undo 记录。）

## 4. 与"只不可破坏、放行部分交互"的关系

只注入 `claimCanInteract` 一个点，而它**全 jar 只有一个调用者**（`Wand.place_block`）⇒
**只影响"放/挖"这一个动作**，不触碰任何右键交互。要求 3 自动满足。

## 5. 判定与开关

新增 `LoongPalaceProtectionPolicy.shouldTreatAsClaimed(LevelAccessor, Entity)`：

```java
if (!ModList.get().isLoaded("ftbchunks")) return false;   // 与 LoongPalaceProtectionHandler 的注册门一致
return shouldPrevent(level, actor);                       // = !isClientSide && isLoongPalace(level) && !hasBypass(actor)
```

- **只对龙宫生效**：非龙宫维度一律返回 `false` ⇒ 本模组**完全不干预**建筑之杖在其他维度的行为。
  ⚠️ 这一点是刻意的：`ClaimProtectionHelper.isClaimed` 会对**所有**位置走 FTB 标准判定，而建筑之杖用的是
  `Protection.EDIT_BLOCK`（它认 `ftbchunks:edit_whitelist` 白名单）—— 照搬会让本模组在**别处**比建筑之杖更严。
- **绕过权限**：复用 FTB `bypass_protection`（与现有 12 道门同一套），`FakePlayer` 永不绕过。
- **不新增配置项**（§十：默认生效）。判据与既有"玩家破坏/放置"完全一致。

## 6. 已知取舍

1. **只覆盖建筑之杖**，不是通用方案。其他内置 FTB 兼容的模组需要各自处理（或改用 FTB 侧的通用改法）。
2. **`UndoManager` 不覆盖**（理由见 §3）。
3. **不读 `[loong_palace.environment_protection] enabled` 总开关** —— 与既有 `shouldPreventEdit`（玩家破坏/放置）
   保持一致；见 §9 的文档漂移。
4. `ClaimDispatch` 是**可选模组**的内部类 ⇒ `@Pseudo` + `require = 0`，缺席时整体静默跳过。

## 7. 升级必查项（`injectors.defaultRequire = 1` 下的启动崩面）

| 锚点 | 判据 |
|---|---|
| `net.nicguzzo.compat.claims.ClaimDispatch#claimCanInteract(ServerLevel, BlockPos, Player)` | 方法名/描述符漂移 ⇒ `@Pseudo` 兜不住，注入静默失效或启动报错 |
| `Wand.place_block` 仍是唯一调用者 | 若作者新增调用点（例如把"使用/交互"也接到这道门），覆盖面会扩大到超出要求 3 |
| `Wand.destroyBlock` 仍只被 `place_block` 调用 | 若出现新调用点，需补第二个注入点 |

复查命令见 plan 文档。

## 8. 验收口径

一句话：**在龙宫里用建筑之杖放方块 → 被拦 + 红字 `wands.message.claimed_chunk`；龙宫外一切照旧。**

## 9. 顺带发现（本文档范围外，待裁定）

- **§8.1 文档漂移 A**：总开关 `enabled` 一栏写"关掉后下面 11 项**与玩家编辑保护**全部失效"，
  但 `LoongPalaceProtectionHandler.shouldPreventEdit`（`:247`）**没有**读该开关 ⇒ 关掉后玩家破坏/放置**仍然被拦**。
- **§8.2 文档漂移 B**：写"FTB Chunks 缺席时 `isManagerLoaded()` 为假 ⇒ 保护仍然生效"，
  但 `LoongPalaceProtectionHandler.register()` 只在 `ModList.get().isLoaded("ftbchunks")` 时被调用
  （`BeLoongCore`）⇒ **FTB Chunks 缺席时玩家编辑保护整个不存在**。本方案的 FTB 缺席行为与之一致。
