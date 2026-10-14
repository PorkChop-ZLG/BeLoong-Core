# 龙宫禁用铁魔法施法（design）

**日期：** 2026-10-13
**目标：** 玩家在 `beloong:loong_palace` 维度内**禁止"常规铁魔法施法"**（法术书/法杖/剑右键、快捷施法、卷轴），
但**放行 `/cast` 命令**（整合包的"龙技能"经 mcfunction 走这条）并为**未来的铁魔法×龙之生存联动模组预留放行**；
**进出龙宫维度时打断正在进行的施法**。
**状态：** **已实施并实机验收**（2026-10-14）—— 用户实测："功能正常"

---

## 1. 为什么能拦得住：官方可取消事件

铁魔法在 `AbstractSpell#attemptInitiateCast` 里发 **`SpellPreCastEvent`**
（`api/events/SpellPreCastEvent.java`，`implements ICancellableEvent`，`extends PlayerEvent`），
而这一句同时就是它的判定门（参考源码 `…/api/spells/AbstractSpell.java:263-303`）：

```java
268      if (level.isClientSide) return false;                    // ★ 客户端根本不走这条路
275      if (!playerMagicData.isCasting()) {
276          CastResult castResult = canBeCastedBy(...);          // 蓝量 / 冷却 / 学习状态
281          if (!castResult.isSuccess() || !checkPreCastConditions(...)
281                  || EVENT_BUS.post(new SpellPreCastEvent(...)).isCanceled())
282              return false;                                    // ★ 取消 ⇒ 直接返回
288-291      initiateCast(...)                                    // 进入"正在施法"
295-296      UpdateCastingStatePacket + OnCastStartedPacket        // 通知客户端
```

**关键在于顺序**：蓝量扣减在 `castSpell`（`:316-320`）、冷却在 `:328`、卷轴移除在施法**完成后**
⇒ **在 `:281` 取消 = 不扣蓝、不进冷却、不消耗卷轴、客户端也不会进入施法状态**
（客户端状态由 `UpdateCastingStatePacket` / `CancelCastPacket` 下发，见 `ClientMagicData`）
⇒ **不存在"客户端已预测"那类幽灵问题**。

## 2. 判据：`CastSource` 黑名单

`CastSource` 全文只有 6 个值（已对**真实 jar `irons_spellbooks-1.21.1-3.16.3.jar`** javap 核对）：
`SPELLBOOK, SCROLL, SWORD, MOB, COMMAND, NONE`。

| 处理 | `CastSource` | 覆盖 |
|---|---|---|
| **禁** | `SPELLBOOK`、`SCROLL`、`SWORD` | 法术书/法杖右键、按键施法、**快捷施法**、剑施法、卷轴 |
| **放行** | `COMMAND` | **`/cast` 命令 + mcfunction ⇒ 整合包的"龙技能"** |
| **放行（默认）** | `MOB`、`NONE`、**以及未来新增的任何 source** | 用**黑名单**而非白名单 ⇒ **为联动模组预留放行** |

**四个调用点与你四项要求 1:1**（全部经过本门）：

| 要求 | 路径 | source |
|---|---|---|
| 法术书/法杖右键 | `ServerPlayerEvents#onUseItem`（`PlayerInteractEvent.RightClickItem`，`:130`） | `SPELLBOOK`/`SWORD` |
| 快捷施法 | `Utils#serverSideInitiateQuickCast`（`:436`） | 同上（**取所选施法工具的 source**） |
| 卷轴 | `Scroll.java:77` | `SCROLL` |
| `/cast` 指令 | `CastCommand.java:59` | `COMMAND` |
| （按键施法） | `Utils#serverSideInitiateCast` ← C2S `CastPacket` | `SPELLBOOK`/`SWORD` |

⚠️ **限制（写进文档，不做代码）**：`CastSource` **无法区分"法术书右键"与"快捷施法"**（两者同源）
⇒ 只能一起禁（正合要求）。若将来需要单独控制，得换钩子。

## 3. 边界①：施法"跨维度完成" —— 必须主动打断

`MagicManager#tick` 在施法时间结束后**直接**调 `spell.castSpell(...)`（`:57/66/75`）**不再过 pre-cast 事件**
⇒ 在龙宫**外**起手、进龙宫后完成 ⇒ 法术仍会在龙宫生效（`CastType.CONTINUOUS` 还会反复触发）。

**对策**：监听 `EntityTravelToDimensionEvent`（`getDimension()` 为目标维度，此时玩家仍在出发维度
⇒ 可同时判"进"与"出"），若任一端是龙宫则调 **public** 的 `Utils.serverSideCancelCast(player, false)`。

- `CancelCastPacket#cancelCast` **内部已判 `isCasting()`**（已读源码核实）⇒ **未施法时调用无副作用、无 NPE**。
- 传 `false`：不因此额外加冷却（这是被我们禁止/打断的施法，不该惩罚玩家）。
- **铁魔法自己不做这件事**（它的 `onChangeDimensions`（`:741`）只做两件事：拦召唤物跨维度、传送时清掉施法者的召唤物）
  ⇒ 这一环必须我们补，**且顺带白拿一个好处**：玩家召唤物在传送时会被铁魔法清掉，所以"已召唤物在龙宫继续破坏"不是主要问题。

## 4. 边界②：怪物施法不受约束（**不做**）

`SpellPreCastEvent extends PlayerEvent` ⇒ 怪物/NPC 的施法（`SyncCastingMobAimingDataPacket` 那条线）**不发此事件**
⇒ 龙宫里的怪物仍能放法术。要覆盖需 mixin 其施法路径 ⇒ **本轮不做，记为已知取舍**。

## 5. 开关与豁免

- 读既有总开关 `[loong_palace.environment_protection] enabled`（`Config.LoongPalaceProtection.environmentProtectionEnabled`）；
- **FTB `bypass_protection` 玩家放行**（与既有 12 道门同一套判据、同一 helper：`LoongPalaceProtectionPolicy#hasBypass`）；
- `FakePlayer` 永不放行（与既有约定一致），且不给它发提示；
- **不新增配置项**（§十：默认生效）。

## 6. 接口约定（留给未来联动模组）

联动模组若要**代码级**让龙技能施放铁魔法，请用 **`CastSource.COMMAND`**（或自行注册一个新的 `CastSource`）
—— 任何**不在黑名单**里的 source 都会被自动放行。若图省事传 `SPELLBOOK`/`SCROLL`/`SWORD`，会被一并拦下。

## 7. 升级必查项（`irons_spellbooks` 是 required 依赖，故不涉及可选模组那套）

| 锚点 | 判据 |
|---|---|
| `api/events/SpellPreCastEvent`（含 `getCastSource()`） | 改名/改包 ⇒ 编译期就报错（无需运行时兜底） |
| `api/spells/CastSource` 的 6 个常量 | 新增常量 ⇒ 落在"默认放行"侧，符合预留意图；若语义变化需重估 |
| `api/util/Utils#serverSideCancelCast(ServerPlayer, boolean)` | 改名/改签名 ⇒ 编译期报错 |
| `AbstractSpell.java:281` 的 post 点 + 事件在扣蓝/冷却**之前** | 若上游把 post 点后移，取消将不再"零副作用" |
| `MagicManager#tick` 里 `castSpell` 仍无二次门 | 若上游补了门，边界①的打断可简化 |

## 8. 验收口径

一句话：**龙宫内手持法术书/法杖/剑/卷轴（含快捷施法）一律施不出法术且无任何消耗；`/cast` 照常可用；
带着施法进出龙宫会被打断；龙宫外一切照旧。**
