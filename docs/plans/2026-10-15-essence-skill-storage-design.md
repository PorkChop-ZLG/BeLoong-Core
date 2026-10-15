# 魔源封存技能（Essence Skill Storage）设计文档

**Date:** 2026-10-15
**Status:** **已实施并实机验收**（2026-10-15：T1–T10 通过 → 作者首轮实机"效果基本符合预期"、日志 0 错误）
→ **§13 修订 rev 1**（tooltip 元素着色 + 法阵/爆发时间轴）
→ **§14 修订 rev 2**（2026-10-16：法阵放大到直径 9 格 + 体型缩放 + 绘制节奏与延留）
→ **§15 修订 rev 3**（2026-10-16：五角星按弦长采样 + 整摞只消耗 1 个 + 创造不消耗）
→ **§16 修订 rev 4**（2026-10-16：改回密集堆叠成实线 + 删掉内圈）—— 该轮待实机复核

> **现行状态指针**：**以 §16 为准**的是**法阵的密度与构成**（外圈 + 五角星，`LINE_SPACING` 是唯一密度旋钮）；
> **以 §15 为准**的是**"用掉一个"的语义**与"创造不消耗"；**以 §14 为准**的是法阵尺寸/体型缩放/时间轴；
> **以 §13 为准**的是 tooltip 元素着色。
> §一–§十二 的其余结论（组件形态、指令、拒绝路径、崩溃点防护、语言键、音效转码、证据索引）**全部仍然有效**。
**Approach:** 方案 A / A1 —— **自建数据组件（值存 `ResourceLocation`）+ 自写 `use`/`useOn` 双入口 + 只复用 DS 的核心 API**
**Applies to:** Minecraft 1.21.1 / NeoForge（具体版本现场取 `gradle.properties`，本文不写死）

> **现行状态指针**：以本文件为准。本设计**推翻了** `docs/plans/2026-09-13-element-essence-design.md` 的两条 Non-Goal
> （「**不给物品任何效果**」「**不做数据驱动**」）—— 已在那一篇追加 **rev 2** 登记，本文 §9 反向登记。
> 上一轮给元素魔源文档做的改动（头部 / §6.1 / §6.3 的现状校正）是该文的 **rev 1**，与本设计无关。

---

## 一、问题陈述

### 1.1 需求（用户原话，共 5 条）

| # | 需求 |
|---|---|
| 1 | 让**十种元素魔源**可以**存储龙之生存的技能**。玩家**是龙**的时候，**潜行 + 右键**使用，可以**获得存储的技能**，并且**魔源变为空的** |
| 2 | 参考龙之生存源码与铁魔法「卷轴」的**数据组件**做法 |
| 3 | 新增指令 **`beloong essence <龙之生存技能> hand`**，把技能注入主手持有的魔源；**主手不是魔源类物品时要有提示** |
| 4 | 目的在于提供一个**快捷获取龙之生存技能**的方式，同时拓展魔源的功能 |
| 5 | （追加）成功获得技能后要有**粒子 + 音效 + Title**（非 actionbar）；标题「你已习得：\<技能名\>」、副标题「请查看你的技能列表」，文本**必须用翻译键**；**Title 颜色按元素不同、硬编码**，颜色**从 10 张贴图中提取**；**粒子也按元素不同**，先用原版近似粒子；音效先统一用**一个**，用户提供的音频已转换完成 |

### 1.2 把设计推向当前形态的查证事实

| # | 事实 | 出处 |
|---|---|---|
| F1 | DS 已有同类指令与底层 API：`/dragon-ability add <targets> <ability>` → `MagicData.getData(player).addAbility(...)` | `DragonAbilityCommand.java:43-57`、`MagicData.java:88-90, 505-534` |
| F2 | `addAbility` **不自动同步**，改完必须发包 | `MagicData.java:504` 注释；`DragonAbilityCommand.java:133` |
| F3 | `addAbility` 的等级**由玩家当前成长度决定**（`upgrade.attempt` + `InputData.growth`），且**成长不足时会停在 0 级** | `MagicData.java:516-521`、`DragonGrowthUpgrade.java:20-42`、`DragonAbilityInstance.java:351-353` |
| F4 | DS **已有**「物品装技能」先例（组件 `DSDataComponents.DRAGON_ABILITIES` + 测试物品 `magic_stick`），但它的右键处理器 **`stack.consume(1, player)` 消耗整件**、且**不要求潜行** | `MagicData.java:276-282`、`DSItems.java:461-486`（该处标注 `// --- Test Item ---`） |
| F5 | 铁魔法卷轴：**一个类管所有法术**；组件 `persistent + networkSynchronized + cacheEncoding`；**值存 `ResourceLocation`**；查不到时**优雅回退** `noneSpell`；**空 = 没有组件**；但**施法后消耗卷轴** | `ComponentRegistry.java:40`、`SpellContainer.java:64-69`、`SpellRegistry.java:74-80`、`ItemRegistry.java:188-189`、`Scroll.java:29, 39-50` |
| F6 | 潜行右键**对着方块**时原版走 `useOn`，**不会**调 `use()` ⇒ 单写 `use()` 会"对着方块用不出来" | `ServerPlayerGameMode.java:363, 365, 381, 389, 396` |
| F7 | `MagicData` **没有**公开的 `currentSpecies` getter；`setCurrentSpecies` 会把旧龙种被动技能**关掉再开**；`refresh` 会 `getAbilities().clear()` 后按龙种默认表**重建**（= 抹掉已学技能） | `MagicData.java:75`（私有）、`:134-150`、`:537-553` |
| F8 | `currentSpecies == null` 时 `getAbilities()` 返回**不可变** `Map.of()` ⇒ `addAbility` 内部 `.put()` 抛 `UnsupportedOperationException` | `MagicData.java:164-170`、`:172-178` |
| F9 | `getAbility(key)` 在 `currentSpecies == null` 时**安全**返回 `null` | `MagicData.java:156-162` |
| F10 | DS 的 `DragonAbilityArgument` 是 **public**、可在 `RegisterCommandsEvent` 里直接 new、**自带全量技能 tab 补全**；但未知 id 时 `parse` **返回 `null` 不做检查** | `DragonAbilityArgument.java:28-48` |
| F11 | 技能名/描述的翻译键可由 DS 自己的 `Translation.Type.ABILITY` / `ABILITY_DESCRIPTION` 推导出 `dragon_ability.<ns>.<path>` / `.desc` | `Translation.java:83-84, 144-151`（与本仓库既有键格式逐字一致） |
| F12 | `beloong` 根命令的 `requires` **不随 Brigadier 子树合并传递** ⇒ 每处都要重写权限谓词 | `command/RouteCommand.java:33-35` |
| F13 | `ServerLevel.sendParticles` 的 `count > 1` 是**长方体随机**，圆形分布必须逐颗定点发 | `memory/learned-patterns.md`「`ServerLevel.sendParticles` 的 `count > 1` 是长方体随机」 |
| F14 | 从贴图机械取"代表色"会**撞色**；而用优化器搜"最少撞色"会**骗过自己的判据** | `tools/extract_essence_colors.py` 三次运行的实测输出；同源教训见 `memory/learned-patterns.md`「像素级机械自检挡不住"观感失败"」 |

---

## 二、目标与非目标

### 目标

1. 十件元素魔源各自可**封存一个龙之生存技能**（默认空）。
2. 龙玩家**潜行 + 右键**习得该技能；魔源**变空但不消失**。
3. 一条 op 指令把任意 DS 技能注入主手魔源。
4. 习得瞬间有**按元素区分**的 Title（颜色）、粒子，以及统一音效。
5. 全程**不重写 DS 已有的判定与算式**。

### 非目标（本次明确不做）

| # | 不做 | 理由 |
|---|---|---|
| N1 | **生存途径**（配方 / 祭坛 / 掉落）把技能装进魔源 | 用户 2026-10-15 裁定：本次只有指令；符合本项目「模组侧只给触发器 / 判据 / 入口」的惯例 |
| N2 | 一件魔源封存**多个**技能 | 需求是"存储**的**技能"（单数语义）；多技能要引入列表 + 选择 UI |
| N3 | 冻结技能**等级**到魔源里 | 用户选定"只存哪个技能，等级由使用者成长度决定"（与 `/dragon-ability add` 同语义） |
| N4 | 复用 DS 的 `DSDataComponents.DRAGON_ABILITIES` / `DragonAbilityHolder` | 它的右键处理器消耗整件、且不要求潜行（F4）⇒ 两条需求都要靠 mixin 反着改，比自建更脆 |
| N5 | 新增配置项 | 项目约定「默认不加配置项」 |
| N6 | 给魔源加**配方** | 同 N1 |
| N7 | 把填充态做成**独立物品** | 与铁魔法卷轴同构：空 = 没有组件（F5） |
| N8 | 修 `aff` 那 4 个立体声旧音效 | 不在本板块范围；仅记录（§13） |

---

## 三、架构

### 3.1 自建数据组件（A1：值存 `ResourceLocation`）

新增 `registry/ModDataComponents.java` —— **本仓库第一个数据组件注册中心**。

```java
public static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, BeLoongCore.MODID);

public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> ESSENCE_SKILL =
        COMPONENTS.registerComponentType("essence_skill", builder -> builder
                .persistent(ResourceLocation.CODEC)                    // 存档
                .networkSynchronized(ResourceLocation.STREAM_CODEC)     // 同步到客户端（tooltip / glint / 名称要用）
                .cacheEncoding());
```

- 组件 ID：`beloong:essence_skill`。
- **空魔源 = 没有这个组件**（F5）。
- API 依据：`DeferredRegister.java:667`（`registerComponentType`）、`ResourceLocation.java:36, 39`（两个 codec）。

**为什么存 `ResourceLocation` 而不是 DS 的 `Holder<DragonAbility>`**：

| | 存 `ResourceLocation`（本设计） | 存 `Holder<DragonAbility>`（用 `DragonAbility.CODEC`） |
|---|---|---|
| 技能被数据包移除后 | 组件仍能解码 ⇒ 物品**留着**，右键给"技能已不存在"提示 | `RegistryFixedCodec` 解码失败 ⇒ **整个 ItemStack 反序列化失败**，物品在世界加载时变空气 |
| 与参考实现的关系 | 与铁魔法卷轴**逐字同构**（F5） | DS 自己的做法 |
| 代价 | 使用时多一次注册表查找 | — |

### 3.2 物品类 `item/effect/EssenceItem.java`

十件魔源共用一个具名类，**替代** `ModItems` 里现有的匿名工厂（`essenceSupplier`）。
包位置选 `item/effect/` 的理由：该包的既有约定就是「有行为的物品类」（`EternalPorkchopEffect`
/ `AmplificationCharmEffect` / `DawnLightEffect`），**且不重建 `item/essence/`** ——
元素魔源设计文档 §6.2 明确记着「整个 `item/essence/` 包已不存在」（V1/V2 被否决后删除）。

| 方法 | 签名 | 职责 |
|---|---|---|
| `use` | `InteractionResultHolder<ItemStack> use(Level, Player, InteractionHand)` | 右键**空中**入口 |
| `useOn` | `InteractionResult useOn(UseOnContext)` | 右键**方块**入口（F6 要求两个都实现）；两者委托同一个私有方法 |
| `isFoil` | `boolean isFoil(ItemStack)` | 有组件 ⇒ `true`（填充态附魔光效） |
| `appendHoverText` | 继承原有元素 tooltip，填充时**追加**技能行与提示行 | |

API 依据：`Item.java:153`（`useOn`）、`:165`（`use`）、`:348`（`isFoil`）。

**客户端返回 `pass`**（不做预测），由服务端独占判定 —— 避免 `docs/自定义物品总设计.md` §9.2 记过的
「客户端先挥手、服务端随后回绝」那类观感问题。

### 3.3 指令 `command/EssenceCommand.java`

```
/beloong essence <dragon_ability> hand          # 权限等级 2
```

- 参数类型直接复用 DS 的 `DragonAbilityArgument`（F10）⇒ **白捡全量技能 tab 补全**。
- **显式判空**：该参数对未知 id 返回 `null` 且不报错（F10），我们补一条自己的提示 —— DS 自己的命令没判，是它的 NPE 隐患，**不继承**。
- `hand` 是**字面量**而非参数：将来加 `offhand` 即再挂一个兄弟字面量。目前只认主手。
- ⚠️ **必须重写 `.requires(source -> source.hasPermission(2))`**（F12）—— 漏了会与 `npc` / `route` / `cg` 的权限不一致。
- 校验顺序与提示见 §4.2。

### 3.4 习得反馈（§6 给全部常量）

服务端在 `ServerPlayer` 上做四件事：清组件 → `SyncMagicData` → Title 三连包 → 粒子 + 音效。

| 反馈 | 实现 | 依据 |
|---|---|---|
| Title 三连包 | `player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20))` → `SubtitleText` → `TitleText` | 三个包签名已核；`send` 是 public（`ServerCommonPacketListenerImpl.java:172`），项目先例 `LoongPalaceProtectionHandler.java:340` |
| 主标题颜色 | `Component.translatable(key, 技能名).withStyle(s -> s.withColor(TextColor.fromRgb(该元素硬编码色)))` | 任意 RGB 只能走 `Style`，语言文件放不下（语言文件只有 16 个传统色码） |
| 粒子 | `ServerLevel.sendParticles(type, x, y, z, 1, 0, 0, 0, 0)` **逐颗定点**发 32 颗（2 圈 × 16 点） | F13 |
| 音效 | `level.playSound(null, x, y, z, ModSounds.ESSENCE_LEARN.get(), SoundSource.PLAYERS, 1f, 1f)` | 与 DS 自己的物品路径同音源（`MagicData.java:280`） |

### 3.5 复用的第三方清单（一律不重写）

| 用途 | 复用什么 | 出处 |
|---|---|---|
| 授予技能 + 按成长度定级 | `MagicData.getData(player).addAbility(player, holder)` | `MagicData.java:505-534` |
| 已学判定 | `MagicData.getData(player).getAbility(key)`（`currentSpecies == null` 时安全返回 null） | `MagicData.java:156-162`（F9） |
| 客户端同步 | `new SyncMagicData(magic.serializeNBT(player.registryAccess()))` | `SyncMagicData.java:13-27` |
| 龙判定 | `DragonStateProvider.isDragon(player)` | `DragonStateProvider.java:37-43` |
| 技能注册表 | `player.registryAccess().registryOrThrow(DragonAbility.REGISTRY)` | `DragonAbility.java:53` |
| 指令参数 + 补全 | `DragonAbilityArgument` | `DragonAbilityArgument.java:28-48` |
| 技能名 / 描述翻译键 | `Translation.Type.ABILITY` / `ABILITY_DESCRIPTION` | `Translation.java:83-84, 144-151`（F11） |

---

## 四、行为规格

### 4.1 成功路径（服务端，九步）

| # | 步骤 | 不满足时 |
|---|---|---|
| 1 | `level.isClientSide()` 判定 | 客户端 → `pass` |
| 2 | `player.isShiftKeyDown()` | 否 → `pass`（**静默**，不打扰普通右键） |
| 3 | `stack.get(ESSENCE_SKILL) != null` | 否 → `pass`（空魔源就是普通物品） |
| 4 | 按 ID 查注册表得到 `Holder<DragonAbility>` | 查不到 → actionbar `unknown_skill` + `fail`，**保留组件不销毁数据** |
| 5 | `DragonStateProvider.isDragon(player)` | 否 → actionbar `only_dragon` + `fail` |
| 6 | `magic.getAbility(key) == null`（尚未学会） | 已会 → actionbar `already_known` + `fail`，**不清空魔源** |
| 7 | `magic.addAbility(player, holder)` | 返回 `false` → 同第 6 步；抛 `UnsupportedOperationException` → 见 §5 |
| 8 | `stack.remove(ESSENCE_SKILL)` → `SyncMagicData` → Title → 粒子 + 音效 | — |
| 9 | `return InteractionResultHolder.success(stack)` | — |

**只有第 7 步成功之后才清空魔源** ⇒ 任何一条拒绝路径都不会让玩家白亏一件。

### 4.2 指令的五步校验

| # | 校验 | 失败提示键 |
|---|---|---|
| 1 | 执行者是玩家 | `beloong.command.essence.not_a_player` |
| 2 | 主手 `stack.getItem() instanceof EssenceItem` | `beloong.command.essence.not_essence` |
| 3 | `DragonAbilityArgument` 解析结果非 null | `beloong.command.essence.unknown_ability` |
| 4 | 写入组件（**覆盖**已有值） | — |
| 5 | `sendSuccess` | `beloong.command.essence.success`（带入技能名） |

### 4.3 两个已裁定的边界行为

| 场景 | 行为 | 理由 |
|---|---|---|
| 主手是**整摞**（count > 1） | **整摞一起填充** | 「主手持有的物品」字面就是那个 ItemStack；一行代码、无失败路径。代价：只想填 1 个要先分摞 |
| 该魔源**已经装着技能** | **覆盖** | 目前没有别的办法清空魔源（只能被龙用掉）⇒ 若拒绝，操作者走进死路。代价：静默丢掉旧技能 |

### 4.4 已知副作用（刻意接受）

- **填充态与空态不会互相堆叠**（1.21 组件语义的必然结果，与铁魔法卷轴一致）；同技能的多件仍可堆叠。
- 魔源默认 `stacksTo(64)` 保持不变。
- 被拒绝的多次右键会重复显示 actionbar 提示（节流不做，提示本身就是反馈）。

---

## 五、崩溃点与防护

### 5.1 风险

`MagicData.currentSpecies == null` 时：

```
getAbilities() → Map.of()            (MagicData.java:164-170，不可变)
    ↓
addAbility 内部  getAbilities().put(...)   (MagicData.java:532)
    ↓
UnsupportedOperationException
```

这个异常从 `Item.use` 冒到服务端 tick ⇒ **不是"技能没给上"，而是服务器崩**。

### 5.2 为什么不用看起来更"干净"的两条路（已取证）

| 方案 | 否决理由 |
|---|---|
| `magic.setCurrentSpecies(player, speciesKey)` | 它会把旧龙种的被动技能 `setActive(false)` 再 `setActive(true)` 一遍（`MagicData.java:136-149`）⇒ **每次用魔源都会让被动技能闪一下** |
| `magic.refresh(player, species)` | `refresh` 会 `getAbilities().clear()` 后按龙种默认表重建（`MagicData.java:550-553`）⇒ **抹掉玩家已学的技能**，比崩还糟 |
| 读 `currentSpecies` 做前置判断 | **没有公开 getter**（`MagicData.java:75` 是私有字段） |

### 5.3 采用的防护

```java
try {
    granted = magic.addAbility(player, holder);
} catch (UnsupportedOperationException e) {
    // 英文 ASCII 日志（AGENTS.md §十一）
    LOGGER.warn("[BeLoong] essence: MagicData.currentSpecies not populated for {}, "
            + "refusing to grant {}", player.getGameProfile().getName(), abilityKey.location());
    player.displayClientMessage(Component.translatable("item.beloong.essence.state_error"), true);
    return InteractionResultHolder.fail(stack);   // 不清空魔源
}
```

- **不改动 DS 任何状态**，与项目既有先例同风格（`registry/ModAttributes.getFlightLevel` 的 null 检查 + 一次性 WARN，见 `AGENTS.md` §十一）。
- DS 自己的两条调用路径（`DragonAbilityCommand` / `MagicData.handleItemBasedLeveling`）**只靠 `isDragon()` 兜**，没有这层防护。

---

## 六、反馈的取值（全部为实测或已核对的常量）

### 6.1 Title 颜色（硬编码，从贴图实测）

依据 `tools/extract_essence_colors.py`（只读、可重跑）：把每张贴图的不透明像素按亮度排序切成
**暗 30% / 中 40% / 亮 30%** 三档、各档求均值，再对最大通道 < 190 的候选按比例提亮（保色相）。
**下列颜色全部落在各自贴图自己的色域内。**

| 元素 | 取档 | 硬编码 RGB | HSL 观感 |
|---|---|---|---|
| 金 | 亮30% | `0xF4F380` | 淡金 |
| 木 | 亮30% | `0x05BE05` | 绿 |
| 水 | 中40% | `0x0EC9DD` | 青 |
| 火 | 亮30% | `0xEF551B` | 橙红 |
| 土 | 亮30% | `0xFED8AA` | 浅沙 |
| 风 | 暗30% | `0x06B3BE` | 深青 |
| 雷 | **暗30%** | `0x7E08BE` | **紫** |
| 冰 | 亮30% | `0xB9EDFA` | 极浅冰蓝 |
| 光 | 中40% | `0xF19D1D` | 金橙 |
| 暗 | 中40% | `0x6444BE` | 暗紫 |

**「雷」为什么取紫**：金系（金/雷/光/土）本就挤在 H=32–45°，若雷也取金档，四个元素会挤成一团。
雷的**暗档实测就是紫** `#7E08BE`（贴图里确有紫），且这恰好呼应元素魔源设计文档 D9「**紫承身份、金只入电弧**」。

**残留重合（如实登记，是这 10 张贴图的客观限制）**：

| 对 | 色相差 | 明度差 | 说明 |
|---|---|---|---|
| 水 `#0EC9DD` ↔ 风 `#06B3BE` | **2.3°** | 20 | **最需要实机确认的一对** |
| 雷 `#7E08BE` ↔ 暗 `#6444BE` | 23° | 37 | 可辨但同属紫系 |
| 金 `#F4F380` ↔ 土 `#FED8AA` | 27° | — | 勉强 |

⚠️ **不要为这组颜色写"自动选色"**：本轮实测穷举 3¹⁰ 种取档、以"撞色对数最少"为目标，脚本搜到
**0 撞色** —— 但结果是把「光」选成暗橙 `#BF6010`、把「雷」选成紫靠明度差绕过判据，**观感上不成立**（F14）。
这与 `memory/learned-patterns.md`「像素级机械自检挡不住"观感失败"」是同一个坑。

### 6.2 粒子（10 种不同原版粒子，名字全部已核对存在于 1.21.1）

| 元素 | 粒子 | 元素 | 粒子 |
|---|---|---|---|
| 金 | `minecraft:crit` | 风 | `minecraft:gust` |
| 木 | `minecraft:composter` | 雷 | `minecraft:electric_spark` |
| 水 | `minecraft:splash` | 冰 | `minecraft:snowflake` |
| 火 | `minecraft:flame` | 光 | `minecraft:end_rod` |
| 土 | `minecraft:falling_dust`（附 `Blocks.DIRT` 的 BlockState） | 暗 | `minecraft:sculk_soul` |

### 6.3 音效

| 项 | 值 |
|---|---|
| 音效事件 | `beloong:item.essence_learn` |
| 资源文件 | `assets/beloong/sounds/item/essence_learn.ogg`（**已产出**，27,706 字节） |
| 格式实测 | Ogg/Vorbis、**单声道**、44100 Hz、3.0199 s、峰值 **-1.4 dBFS**、均值 -19.8 dBFS |
| 施加的处理 | `-vn`、`-map_metadata -1`、`atrim=start=0.187` + `asetpts`（只去掉开头 < -45 dB 的真实静音）、`-ac 1`、`-ar 44100`、`-c:a libvorbis -q:a 5`。**无增益、无动态处理** |
| 副标题键 | `subtitles.beloong.item.essence_learn` |

**电平基线（不是凭感觉定的）**：本模组既有 5 个音效的峰值区间是 **-2.4 ~ 0.0 dBFS**、均值区间
**-20.1 ~ -13.6 dBFS**；新音效（-1.4 / -19.8）落在区间内 ⇒ **不需要动增益**。

**该音效的结构已实测**（`tools/audio_rms_profile.py`，逐 100 ms RMS）：它是**设计好的 riser** ——
0.0→1.2 s 从 -52 dB 单调爬到 -9 dB，**1.30 s 是峰值**，主体 1.20–1.90 s，之后衰减到 ~2.85 s。
⇒ **用户裁定保留该渐强**，不裁前段；爆发点 1.3 s 落在标题（10/60/20 tick = 4.5 s）完全显示期间。

---

## 七、文件清单

### 新增（5）

| 文件 | 内容 |
|---|---|
| `src/main/java/com/zonlong/beloong/item/effect/EssenceItem.java` | 具名物品类（`use`/`useOn`/`isFoil`/`appendHoverText` + 元素数据表） |
| `src/main/java/com/zonlong/beloong/registry/ModDataComponents.java` | 数据组件注册中心（`essence_skill`） |
| `src/main/java/com/zonlong/beloong/command/EssenceCommand.java` | `/beloong essence <ability> hand` |
| `src/main/resources/data/beloong/tags/item/essences.json` | **声明性** tag（见 §8 D10） |
| `src/main/resources/assets/beloong/sounds/item/essence_learn.ogg` | **已产出** |

### 修改（6）

| 文件 | 改动 |
|---|---|
| `item/ModItems.java` | `essenceSupplier(...)` 匿名工厂 → `EssenceItem::new`；10 处注册改为传入元素枚举 |
| `registry/ModItemTags.java` | 加 `ESSENCES` 常量 |
| `registry/ModSounds.java` | 加 `ESSENCE_LEARN` |
| `BeLoongCore.java` | 注册 `ModDataComponents`；`onRegisterCommands` 里加 `EssenceCommand.register(...)` |
| `assets/beloong/sounds.json` | 加 `item.beloong.essence_learn` 条目 |
| `assets/beloong/lang/{zh_cn,en_us}.json` | 新增 **13** 键（中英同补，331 → **344**） |
| `assets/beloong/models/item/*.json` | **不改**（填充态靠 glint，不新增贴图） |

### 文档（3）

| 文件 | 改动 |
|---|---|
| 本文 | 新建 |
| `docs/plans/2026-10-15-essence-skill-storage-plan.md` | 实施计划（成对） |
| `docs/plans/2026-09-13-element-essence-design.md` | 追加 **rev 2**：标记两条 Non-Goal 被本设计取代 |

### 新增语言键（**13**）

`item.beloong.essence.stored`（已封存技能：%s）、`.hint`、`.learned.title`（你已习得：%s）、
`.learned.subtitle`（请查看你的技能列表）、`.only_dragon`、`.already_known`、`.unknown_skill`、`.state_error`、
`subtitles.beloong.item.essence_learn`、`beloong.command.essence.not_a_player`、`.not_essence`、
`.unknown_ability`、`.success`（已注入 %s）。

> 逐个数：`item.beloong.essence.*` **8** 个 + `subtitles.beloong.item.essence_learn` **1** 个 +
> `beloong.command.essence.*` **4** 个 = **13**。

> ⚠️ **为什么不用 `title.beloong.*` 命名空间**：本仓库**已有** `title.beloong.growth_multiplier` /
> `title.beloong.treasure_value` 两个键，但它们用在 **actionbar**（`TreasureGrowthHandler:73` 的
> `displayClientMessage(msg, true)`，且该处注释自己写着"actionbar：金色财宝值 + 绿色成长速度"）——
> 那是**热键栏上方的浮字**，与本次真正用的**屏幕中央 Title**
> （`ClientboundSetTitleTextPacket`）**不是同一种东西**。
> ⇒ 共用 `title.beloong.*` 会让读者把两者混为一谈。本次的标题键**刻意**放在
> `item.beloong.essence.learned.*` 下（按功能归组，与 `item.beloong.dawn_light.no_boss` 同风格）。
> 顺带记一笔：`title.beloong.*` 这个命名空间名与实际用途（actionbar）**不符**，属既有命名瑕疵，
> 不在本次范围内、也不建议轻易改（改键名会让资源包失效）。

⚠️ `.learned.title` 的 `%s` **只有一个且位于句末** —— 刻意如此：元素魔源设计文档 §3 记过
「`%s` 按出现顺序取参」，中文模板里 `%s` 夹在中间曾渲染出「自然界的**金魔源**元素凝聚成的魔源」。
门 `check_lang_parity.py` 逐键比对占位符个数与颜色码。

---

## 八、决策记录

| # | 决策 | 理由 / 被否方案 |
|---|---|---|
| **D1** | 采用**方案 A**（自建组件 + 自写双入口），不采用"全量复用 DS 的 `DRAGON_ABILITIES` + 让 DS 的事件干活" | 需求里「潜行才触发」与「变空而非消耗」恰好是 DS 那套现成机制**反着**的地方（F4）⇒ 复用等于先借壳再 mixin 掰回来，比自建更脆。第三方案（自建组件 + 复用 `DragonAbilityHolder` 值类型）为"存一个技能"引入了 add/remove 列表 + 条件 + 适用龙种，过度设计 |
| **D2** | 组件值存 **`ResourceLocation`**，不存 `Holder<DragonAbility>` | §3.1 的表：后者在技能被数据包移除时会让**整件物品变成空气**；前者与铁魔法卷轴同构 |
| **D3** | **空 = 没有组件**，不另立"空"组件，不做独立物品 | 与铁魔法卷轴逐字同构（F5） |
| **D4** | 只存"哪个技能"，**等级由使用者成长度决定** | 用户裁定；与 `/dragon-ability add` 同语义（F3）。代价：成长不足时得到 0 级技能（已知但不可施放） |
| **D5** | 已学会时**拒绝且不清空魔源** | 用户裁定；避免玩家白亏一件 |
| **D6** | 可装**任意** DS 技能（含内置），不限于 beloong 自制的 5 个 | 用户裁定；指令补全自动覆盖注册表全部条目。靠**权限等级 2** 限制滥用 |
| **D7** | 填充态用**附魔光效**区分，不新增贴图 | 用户裁定；零新美术，`isFoil` 一个覆写 |
| **D8** | 本次**不做生存获取途径** | 用户裁定（N1） |
| **D9** | `use` 与 `useOn` **两个入口都实现** | F6：潜行对着方块时原版只走 `useOn`，单写 `use` 会"对着方块用不出来" |
| **D10** | 额外加**声明性**物品 tag `beloong:essences`（含 10 件） | 用户要求"加个 tag 方便整合包侧修改"，且明确"**tag 暂时没用**" ⇒ **判定仍用 `instanceof EssenceItem`，tag 不参与任何逻辑**。将来的扩展位；若日后要改成按 tag 判定，那是**行为变更**，需重新登记 |
| **D11** | 「魔源类物品」判定用 `stack.getItem() instanceof EssenceItem` | 精确、覆盖 10 件；与 D10 的 tag 并存但互不影响 |
| **D12** | 整摞一起填充 | §4.3 的表 |
| **D13** | 指令**覆盖**已有技能 | §4.3 的表：没有别的清空手段，拒绝会走进死路 |
| **D14** | `currentSpecies` 崩溃点用 `try/catch (UnsupportedOperationException)` 挡，**不调** `setCurrentSpecies` / `refresh` | §5.2 的取证：前者会让被动技能闪、后者会抹掉已学技能 |
| **D15** | 客户端返回 `pass`，判定全在服务端 | 避免 `docs/自定义物品总设计.md` §9.2 记过的"客户端先挥手"观感问题 |
| **D16** | 「雷」取紫档 `0x7E08BE` | §6.1：避开金系四元素挤色，且呼应 D9「紫承身份」 |
| **D17** | 颜色**纯取自贴图**，不做色相偏离 | 用户裁定；代价是水/风、雷/暗两对相近（已在 §6.1 如实登记）。若要 10 色一眼可辨，需允许偏离色相 —— **那是另一个决策，本次不做** |
| **D18** | 保留音效的 1.2 s riser，不裁前段 | 用户裁定；实测它是设计好的渐强（§6.3），爆发点 1.3 s 与标题动画配合成立 |
| **D19** | 音效给**单声道** | MC 世界定位音效的规范。**注意本模组既有 5 个音效里 4 个是立体声**（§13），本次按规范做，不改旧件 |
| **D20** | `hand` 做成**字面量**而非参数 | 为将来加 `offhand` 留结构位；目前只认主手 |

---

## 九、与既有文档的冲突登记

`docs/plans/2026-09-13-element-essence-design.md` 的 §8 Non-Goals 里两条被本设计**推翻**：

| 被推翻的 Non-Goal | 本设计为何推翻 |
|---|---|
| 「**不给物品任何效果**（用户明确"暂时为最基础的物品"）」 | 本次需求就是给魔源加"封存技能"的效果。注意：原文是"**暂时**"，本次即那个"之后" |
| 「**不做数据驱动**：8 件是固定内容，不引入 Codec/JSON 注册表」 | 本设计引入**数据组件**（`ResourceLocation` 的 Codec），且技能集合来自数据包注册表 `dragonsurvival:dragon_ability` |

**未被推翻、仍然有效**的两条（不要顺手改）：「不做动画贴图」（`.mcmeta` 不得存在）与「不改动用户手绘的五张贴图」。
「不修改 `Rarity` 枚举」同样仍有效。该文的 §10（rev 1）与本次的 rev 2 是**两轮不同的修订**。

---

## 十、验证计划

### 10.1 静态门与构建

```powershell
$env:PYTHONIOENCODING="utf-8"; python tools\check_lang_parity.py      # 键集 / 占位符 / 颜色码
.\gradlew.bat build --rerun-tasks --no-build-cache
```
⚠️ `check_lang_parity.py` 裸跑会因控制台 GBK 打不出 `✅` 而 exit 1，**那与键无关**（已在元素魔源文档 §10.3 登记）。

### 10.2 产物解析（不靠字符串匹配）

| 检查 | 期望 |
|---|---|
| 解析 `build/libs/beloong-<版本>.jar` 的 `META-INF/neoforge.mods.toml` | 依赖块数量与模板一致（当前 27，其中 15 required） |
| `beloong:essence_skill` 组件在 jar 内可查 | 用 `javap` 或常量池解析确认 `ModDataComponents` 已注册 |
| 音效进包 | jar 内有 `assets/beloong/sounds/item/essence_learn.ogg`；`sounds.json` 有对应条目 |
| 10 件魔源仍是**同一个** class | `item/effect/EssenceItem.class` **一份**；另有两个**预期内**的附加 class：`EssenceItem$Element.class`（内嵌元素枚举）与 `EssenceItem$1.class`（javac 为枚举 `switch` 生成的**合成类**，与"每件一个类"无关）。判据是 **jar 里没有 10 个 `<元素>EssenceItem.class`、也没有 `item/essence/` 包** |

### 10.3 实机验收清单（作者执行）

| # | 场景 | 期望 | 判据 |
|---|---|---|---|
| 1 | `/beloong essence beloong:execute hand`（主手空手） | 提示"主手不是魔源" | 聊天栏 |
| 2 | 主手拿石头执行同一命令 | 同上 | 聊天栏 |
| 3 | 主手拿金魔源执行 | 提示"已注入 斩杀" | 聊天栏 |
| 4 | 悬停该魔源 | 有**附魔光效** + tooltip 多出"已封存技能：斩杀" | 目视 |
| 5 | 潜行 + 右键（**对着空气**） | Title「你已习得：斩杀」**淡金色**、副标题「请查看你的技能列表」、粒子 + 音效；魔源**变空但还在** | 目视 |
| 6 | 潜行 + 右键（**对着方块**）另一件填充魔源 | 同上（验证 `useOn` 入口） | 目视 |
| 7 | 再次潜行右键同一件已空的魔源 | **什么都不发生**（空魔源=普通物品） | 目视 |
| 8 | 已学会后再用另一件装着同一技能的魔源 | 提示"你已经掌握了这个技能"，**魔源仍是有技能的** | 目视 |
| 9 | 非龙玩家潜行右键填充魔源 | 提示"只有龙才能习得"，**魔源不清空** | 目视 |
| 10 | 十种元素各测一次 | Title **颜色各不相同**；粒子类型各不相同 | 目视（重点看 水/风 与 雷/暗） |
| 11 | 打开技能列表 | 新技能在列表里；主动技能已自动占一个快捷栏位 | 目视 |
| 12 | 成长度不足的龙使用 | 技能**能习得**但为 0 级（DS 提示"需要升级"） | 目视 —— 这是 D4 的既定语义，**不是缺陷** |

### 10.4 需要 spark / 日志取证的项

无（本功能不在热路径上）。

---

## 十一、证据索引（源码级）

**Dragon Survival**（`D:\Minecraft\开源模组参考文件\DragonSurvival`，只读）
`registry/dragon/ability/DragonAbility.java:42-49, 53, 64-65, 170-173` ·
`registry/attachments/MagicData.java:75, 88-90, 134-150, 156-162, 164-170, 172-178, 276-282, 504-534, 537-553` ·
`network/magic/SyncMagicData.java:13-27` · `commands/DragonAbilityCommand.java:43-57, 133` ·
`commands/arguments/DragonAbilityArgument.java:28-48` · `common/capability/DragonStateProvider.java:37-43` ·
`common/codecs/DragonAbilityHolder.java:124-165` · `registry/DSItems.java:461-486`（`// --- Test Item ---`）·
`registry/data_components/DSDataComponents.java:22` ·
`registry/datagen/Translation.java:83-84, 144-151` · `registry/dragon/ability/upgrade/DragonGrowthUpgrade.java:20-42` ·
`registry/dragon/ability/DragonAbilityInstance.java:351-353` · `common/handlers/magic/ManaHandler.java:96-123`

**Iron's Spells 'n Spellbooks**（`D:\Minecraft\开源模组参考文件\irons-spells-n-spellbooks`，只读，参考其组件做法）
`registries/ComponentRegistry.java:40` · `capabilities/magic/SpellContainer.java:64-69` ·
`api/registry/SpellRegistry.java:74-80` · `registries/ItemRegistry.java:188-189` · `item/Scroll.java:29, 39-50`

**NeoForge 21.1.236 源码树**（`build/review-src/neoforge-21.1.236`）
`net/minecraft/server/level/ServerPlayerGameMode.java:340-401`（潜行右键走 `useOn` 的四个关键行 363/365/381/389/396）·
`net/minecraft/world/item/Item.java:153, 165, 348` · `net/minecraft/resources/ResourceLocation.java:36, 39` ·
`net/neoforged/neoforge/registries/DeferredRegister.java:667` ·
`net/minecraft/core/particles/ParticleTypes.java`（10 个粒子常量逐个核对）·
`net/minecraft/network/protocol/game/Clientbound{SetTitleText,SetSubtitleText,SetTitlesAnimation}Packet.java` ·
`net/minecraft/server/network/ServerCommonPacketListenerImpl.java:172`

**本仓库**
`command/RouteCommand.java:33-35`（Brigadier 权限不随合并传递）· `:46-54`（命令形状）·
`compat/ftbchunks/LoongPalaceProtectionHandler.java:340`（`player.connection.send` 先例）·
`docs/自定义物品总设计.md` §9.2（客户端先挥手）· `docs/plans/2026-09-13-element-essence-design.md` D9 / D20 / §3 / §6.5 / §8 ·
`memory/learned-patterns.md`「`ServerLevel.sendParticles` 的 `count > 1` 是长方体随机」
/「像素级机械自检挡不住"观感失败"」

**本轮自建的工具（`tools/`，本机、不进版本库）**
`extract_essence_colors.py`（三档取色，只读）· `audio_rms_profile.py`（逐 100 ms RMS）

**音频处理**
源 `D:\Desktop\minecraft\10月15日.mp4`（H.264 + AAC / 44.1 kHz / 立体声 / 3.20 s）；
ffmpeg `D:\VideoTools\bin\ffmpeg\ffmpeg.exe`（`libvorbis` 可用；全程离线，未下载任何东西）

---

## 十二、已知行为与未能验证

### 已知行为（刻意设计，不是缺陷）

| # | 行为 | 依据 |
|---|---|---|
| K1 | 填充态与空态**不互相堆叠** | 1.21 组件语义（D3） |
| K2 | 成长度不足时技能停在 **0 级**（习得了但不可施放） | D4 + `DragonGrowthUpgrade.java:20-42` |
| K3 | 整摞 64 件会被**一次全部填充** | D12 |
| K4 | 再次用指令会**静默覆盖**旧技能 | D13 |
| K5 | 水/风 与 雷/暗 两组标题色**相近** | D17 + §6.1 的实测 |
| K6 | 音效有 **1.2 s 渐强**才爆发 | D18 |
| K7 | `beloong:essences` tag **不参与任何判定** | D10 |

### 未能验证

| # | 事项 |
|---|---|
| U1 | **`UnsupportedOperationException` 防护从未被真的触发过** —— 它是从 `MagicData.java:164-170` + `:532` 的代码路径推断的，未构造出 `currentSpecies == null` 的运行时场景 |
| U2 | **`DragonAbilityArgument` 对未知 id 返回 `null` 后 Brigadier 的实际行为**未实测（DS 自己的命令没判这个空）。本设计要求显式判空，因此两种行为都不致命 |
| U3 | 潜行右键在**对着方块**时是否真的走 `useOn` —— 是读 `ServerPlayerGameMode.java` 得出的，未实机点过 |
| U4 | 10 个标题色的**观感区分度**未实机看（只做了数值上的撞色检测，而 §6.1 已证明数值判据不可全信） |
| U5 | 粒子逐颗定点发 32 颗的**性能与观感**未实测 |
| U6 | 该音效的 1.2 s 渐强在**实际游戏音量**下是否显得拖沓 —— 我听不到，只能给数据 |
| U7 | DS 是 **fork**：本文引用的 API 未与上游 diff，不保证上游同名 API 行为一致 |

---

## 十三、修订 rev 1（2026-10-15，实机验收后）：tooltip 元素着色 + 法阵/爆发时间轴

**触发**：作者完成第一轮实机测试（效果符合预期；`run/logs` 检查 **0 条 ERROR/FATAL、无新崩溃、无本功能相关日志行**），并提出 4 条修改。
**性质**：本节是**追加修订**，§一–§十二 的其余结论仍然有效。被本节取代的条目在下面 §13.1 里逐条点名，其余**未点名的一律不变**。

### 13.1 四条需求与落地

| # | 需求（作者原话要点） | 落地 | 取代了什么 |
|---|---|---|---|
| 1 | 物品的 tooltip 用定下的 10 种魔源 title 颜色硬编码 | `EssenceItem#appendHoverText`：**三行全用** `TextColor.fromRgb(element.titleColor())` —— 与 Title、法阵粒子**同源** | 取代原先的"默认白 + `GRAY` + `DARK_GRAY`"三层灰阶 |
| 2 | 粒子持续时间太短、数量太少、范围太小 | 旧实现是**一次性** 2 圈 × 16 点（半径 1.0/1.5）= **32 颗、零持续时间**；现改为 **60 tick 的完整时间轴**（§13.2），峰值约 **200 颗/tick**、爆发半径扩到约 **6.6 格** | 取代 `EssenceItem` 里原来的 `RING_RADII` / `RING_HEIGHTS` / `POINTS_PER_RING` 三个常量与那段循环（已删除） |
| 3 | 音频中段有高潮；粒子要华丽并**与音频同时长** | 时间轴按音频的**实测**形状编排（§13.2），总长 60 tick = 3.00 s，对齐音效的 3.0199 s | 取代 §6.3 里"爆发点 1.3 s 落在标题停留期内"这种**只看标题**的时序论证 —— 现在粒子本身就有完整时间轴 |
| 4 | 五角星 + 外圈的法阵（统一普通粒子、按魔源变色）；高潮时以玩家为球心的**向外球状爆发**（用该元素的粒子）；音频结束时同步消失；**不要额外同步代码** | 新类 `item/effect/EssenceLearnRitual`：法阵用 `minecraft:dust` 着元素色，爆发用 `Element#particle()`；服务端 tick 驱动，**无自定义包** | 新增；无被取代项 |

### 13.2 时间轴（照音频实测编排）

音频实测形状（`tools/audio_rms_profile.py`，逐 100 ms RMS）：0.00→1.20 s 从 −52 dB 单调爬到 −9 dB 的**渐强**，**1.30 s 峰值**，主体（峰值−12 dB 以内）**1.20–1.90 s**，之后衰减到 ~2.85 s，总长 3.0199 s。

| tick | 秒 | 音频位置 | 粒子 |
|---|---|---|---|
| 0–11 | 0.00–0.60 | 渐强起点 | 法阵被"画"出来（按进度逐点出现） |
| 12–23 | 0.60–1.20 | 渐强末段 | 法阵完整并旋转：外圈顺时针 2.5°/tick、内圈逆时针 4°/tick、五角星自转 1.2°/tick |
| **24–37** | **1.20–1.90** | **高潮（主体）** | 法阵保留 + **球壳爆发**（半径 0.35 → ~6.6 格线性外扩） |
| 38–51 | 1.90–2.60 | 衰减 | 密度从 1 线性降到 0（球壳继续外扩变稀 = 冲击波余韵） |
| 52–59 | 2.60–3.00 | 尾音 | **不再产生新粒子**，已有粒子在存活期内散尽 ⇒ 约 3.1 s 时粒子场清空 |

**"与音频同时长"是靠 `density()` 这条曲线做的**：高潮结束前恒为 1，之后线性衰减到 0。粒子有存活时间，所以最后一个粒子在 2.60 s 产生、最迟约 3.1 s 消失，与音效 3.02 s 的收尾同步 —— **不需要任何自定义同步代码**（这正是需求 4 要的效果）。

### 13.3 ⚠️ 一条必须知道的原版限制：**无法给单颗粒子指定定向速度**

`ServerLevel.sendParticles(type, x, y, z, count, dx, dy, dz, speed)` 里：

- `dx/dy/dz` 是随机**位置**偏移（内部 `random.nextGaussian() * d`），
- `speed` 是**随机方向**的速度大小（`random.nextGaussian() * speed`），

⇒ **没有"朝外"的定向速度入口**。所以"向外爆发"是用**逐 tick 外扩的球壳**等价表达的：每 tick 在半径 `r(t)` 的球面上撒点，粒子存活期内后一壳叠在前一壳之外，视觉上就是向外膨胀的实心球。

这个做法有三个好处：① **对全部 10 种粒子类型都成立**（不依赖它们支持速度参数）；② 不需要自定义粒子（省掉客户端注册）；③ 不需要自定义包。

球面布点用 **Fibonacci 球**（`GOLDEN_ANGLE`），两层壳错开半径 0.45 格与半个相位，避免重叠成一层。

### 13.4 变更清单

| 动作 | 文件 |
|---|---|
| 新增 | `src/main/java/com/zonlong/beloong/item/effect/EssenceLearnRitual.java`（`@EventBusSubscriber`，服务端 tick 驱动） |
| 修改 | `item/effect/EssenceItem.java`：`appendHoverText` 三行着色；`celebrate` 去掉一次性圈粒子、改为 `EssenceLearnRitual.start(...)`；删除 `RING_*` / `POINTS_PER_RING` 三个常量与 `ChatFormatting` / `ServerLevel` 两个 import |
| 未改 | 组件、指令、语言键、音效、tag —— 全部不动（本轮没有新增任何语言键） |

### 13.5 已知行为（刻意设计，不是缺陷）

| # | 行为 | 依据 |
|---|---|---|
| K8 | 同一玩家**同时只有一个仪式**；连续用两件魔源会**替换**掉上一个 | `start()` 里 `removeIf(ritual -> ritual.player == player)` —— 避免叠出两套法阵 |
| K9 | 玩家**换维度 / 退出 / 死亡重生**时仪式立即结束 | `Ritual#advance` 检查 `player.isRemoved()`（这三条路径都会移除实体）。不这么做就会在别的世界里继续撒粒子 |
| K10 | 法阵画在**脚下（贴地，+0.12 格）**，不是悬浮在身前 | 需求 4 说的是"法阵"，贴地才读作法阵 |
| K11 | 客户端把"粒子"设为**最少**时大部分会被剔除 | 原版行为（客户端粒子设置决定，服务端无法绕过） |

### 13.6 未能验证

| # | 事项 |
|---|---|
| U8 | **整套粒子编排的观感未实机看过** —— 法阵的"可读性"（五角星能不能认出来）、爆发的厚度、以及 `density()` 的淡出是否自然，都只能由作者看 |
| U9 | 峰值约 200 颗/tick 的**性能影响未实测**（尤其是低配客户端）。若卡顿，旋钮是 `OUTER_POINTS` / `INNER_POINTS` / `STAR_POINTS_PER_EDGE` / `BURST_SHELL_POINTS` 四个常量 |
| U10 | **两种元素的爆发粒子可能观感不对**：`土` 用的 `minecraft:falling_dust` **会下坠**、`暗` 用的 `minecraft:sculk_soul` **会上飘**，在球壳里不是各向同性的。若看着别扭，可只把这两种的爆发粒子换掉（法阵颜色不受影响） |
| U11 | `@EventBusSubscriber(modid = ...)` 在**专用服务端**上的注册未实机验证（dev 单人局已随本轮 build 通过编译，但未启动） |
| U12 | `Vec3.fromRGB24(...).toVector3f()` 得到的 dust 颜色与 title 色的**视觉一致性**未比对（数值上是同一个 RGB，但 dust 在屏幕上有自己的亮度叠加） |

---

## 十四、修订 rev 2（2026-10-16，第二轮实机反馈）：法阵放大到直径 9 格 + 体型缩放 + 绘制节奏与延留

**触发**：作者第二轮实机反馈 4 条。**同时澄清了上一轮的"崩溃"** —— 那是**另一个会话关掉了 `runClient` 窗口**
（`runClient` 是 Gradle daemon 的子进程，daemon 被硬杀 ⇒ 客户端无异常、无崩溃报告、日志戛然而止），
**与本功能无关**（诊断见 §14.4）。⇒ 本节起，§13.6 的 U8/U9（观感、性能）**仍待复核**，但"崩溃"不再列为风险。

### 14.1 四条反馈与落地

| # | 反馈（作者原话要点） | 落地 |
|---|---|---|
| 1 | 五角法阵太小；线条粒子可以少一点 | 外圈**直径 9 格**（半径 1.75 → 4.5）；线条基点数**减半**（32→16、14→7、每边 9→5）；**星座半径比 0.77 → 1.0**（星的 5 个尖落在外圈上，原实现留了一圈空隙、星显得更小） |
| 2 | 法阵大小按玩家体型缩放；**只用 SCALE 属性**，算它相对原版默认值的倍数；**只放大不缩小、最多 4 倍** | `Ritual#resolveSizeFactor`：`player.getAttributeValue(SCALE) / Attributes.SCALE.value().getDefaultValue()` 再 `Mth.clamp(…, 1.0, 4.0)`。**半径与点数都乘该倍数**（点数也乘是为了保持线密度不随体型变稀） |
| 3 | 画圈过程变慢，**到音效高潮时画完**；法阵**残留延长 1 秒**，不必与音效等长 | `DRAW_TICKS: 12 → 24`（=`CLIMAX_START_TICK`，**tick 23 时恰好画满**，tick 24 = 1.20 s 接爆发）；法阵生成延到 **tick 72（3.60 s）**、总时长 **80 tick（4.00 s）** ⇒ 法阵可见期从约 3.1 s → **约 4.1 s（+1.0 s）** |
| 4 | 爆发**不**延长 | 新增独立的 `burstDensity()`：爆发在 **tick 56（2.80 s）**停止生成，与音频衰减段一起收尾；法阵另走 `circleDensity()` |

### 14.2 ⚠️ 为什么"按体型缩放"用的是 SCALE 属性，而不是 DS 的视觉体型（三条取证）

| 候选量 | 服务端可用 | 能否反映体型 | 依据 |
|---|---|---|---|
| `player.getBbWidth()` | ✅ | ❌ **区分不出龙与人** | DS 龙体的 `scaling_proportions.width` **生成值全是 0.6**，与普通玩家相同；碰撞箱只是 `width × SCALE` |
| `DragonStateHandler#getVisualScale(player, partialTick)` | ❌ **不可用** | ✅（但拿不到） | **硬守卫**：`if (!PROXY.isOnRenderThread()) { logOrThrow("Visual scale update should only be used for rendering purposes!"); return SCALE属性; }` ⇒ 服务端调用会往 DS 日志刷错误 |
| `Attributes.SCALE`（+ 其 `getDefaultValue()`） | ✅ | ✅ | 默认 1.0（`Attributes.java:125` 的 `RangedAttribute(..., 1.0, 0.0625, 16.0)`）、放大护符 +1.0 就体现在它上面 |

- 用**属性自己的** `getDefaultValue()` 而不是写死 1.0 ⇒ 原版改基准值时自动跟上。
- 属性未挂载时 `getAttributeValue` 返回 `0.0`，被"只放大"的下限钳成 1× ⇒ **天然无需判空**
  （与 `AGENTS.md` §十一「读属性可能 NPE」的防御式先例一致的结论，但这里连判空都不必要）。

### 14.3 新几何与新时间轴（数值已核对）

**几何**（`BASE_OUTER_RADIUS = 4.5`；点数 = 基础值 × 倍数）：

| 体型倍数 | 外圈 | 内圈 | 五角星 | 每颗间距 | 法阵合计 |
|---|---|---|---|---|---|
| **1×（默认）** | r=4.5（直径 **9.0** 格）16 点 | r=1.62，7 点 | 边长 5.29，每边 5 点 | 1.77 / 1.45 / 1.06 格 | **48 颗/tick** |
| 2×（放大护符） | r=9.0（直径 18）32 点 | r=3.24，14 点 | 边长 10.58，每边 10 点 | 同上（密度不变） | 96 颗/tick |
| 4×（上限） | r=18.0（直径 36）64 点 | r=6.48，28 点 | 边长 21.16，每边 20 点 | 同上 | 192 颗/tick |

- **旧版对照**：外圈 r=1.75（直径 3.5）、点数 32 ⇒ 间距 **0.34 格**、合计 **91 颗/tick**。
  ⇒ 线密度从"几乎连续"降到"**点串/念珠**"（间距 0.34 → 1.77 格，**稀疏 5.2 倍**），这正是"线条粒子少一点"要的方向；
  配合 `DUST_SCALE 1.1 → 2.5` 让每颗更醒目。**若实机觉得太稀**，旋钮是 `BASE_OUTER_POINTS`（16 → 24/32）。
- 爆发半径同样乘倍数（否则大体型玩家的爆发会显得比他的法阵小得多）。

**时间轴**（`tick = 1/20 秒`）：

| tick | 秒 | 音频 | 粒子 |
|---|---|---|---|
| 0–23 | 0.00–1.20 | 渐强（爬到 −9 dB） | 法阵被"画"出来，**1.20 s 刚好画完** |
| 24–37 | 1.20–1.90 | **高潮（主体）** | 法阵完整并旋转 + **球壳爆发** |
| 38–55 | 1.90–2.80 | 衰减到尾音 | 爆发密度降到 0；法阵继续 |
| 56–71 | 2.80–3.60 | 已结束 | 只剩法阵，密度线性降到 0 |
| 72–79 | 3.60–4.00 | — | 不再产生新粒子，已有粒子散尽（约 4.1 s） |

### 14.4 「另一个会话关掉了窗口」的机制（本轮澄清，非缺陷）

`runClient` = `gradlew` → **Gradle daemon** → 客户端**子进程**。daemon 被硬杀 ⇒ 整条树消失，特征是
**客户端无异常、无崩溃报告、无 `hs_err`、日志戛然而止，且 daemon 日志里没有
`stop() called on daemon` / `Build finished` / `exit value`**（对照同目录另两个 daemon 都有优雅停止记录）。
⇒ 与代码无关。**推论：切项目时不要按名字杀 java 进程**（`AGENTS.md` §二 的原话是 `.\gradlew.bat --stop`，
那次连优雅停止记录都没有，说明是更强的杀法）。

### 14.5 决策记录

| # | 决策 | 理由 |
|---|---|---|
| **D21** | 外圈**直径 9 格**（半径 4.5） | 作者指定 |
| **D22** | 缩放**只用 SCALE 属性**，取相对原版默认值的倍数，钳到 **1×–4×**（只放大不缩小） | 作者指定；且 §14.2 证明另两个候选量一个不可用、一个无区分度 |
| **D23** | **点数与半径一起乘倍数** | 若只放大半径、点数不变，4× 时线会稀到 7 格/颗、读不出"圈"。代价：大体型玩家粒子更多（4× 时 192 颗/tick） |
| **D24** | 星座半径比 **0.77 → 1.0** | 标准五芒星是"顶点落在外圈上"；原实现留空隙让星比圈小，是"法阵显得小"的一部分原因 |
| **D25** | 法阵与爆发**用两条独立密度曲线** | 作者要求"爆发不延长"；只有一条曲线做不到"法阵多留 1 秒而爆发按时收" |
| **D26** | **不**为"包量放大"引入自定义粒子 | 上轮报过"约 4000 包/秒"，但该轮的错误归因（崩溃）已排除；点数减半后包量反而下降 ⇒ 按 YAGNI 不做 |

### 14.6 未能验证（本轮新增）

| # | 事项 |
|---|---|
| U13 | **"点数减半 + 直径 9 格"之后的观感未实机看过** —— 间距 1.77 格能不能读作一条线（还是念珠），只能由作者判定；太稀就把 `BASE_OUTER_POINTS` 调大 |
| U14 | **4× 上限（直径 36 格、192 颗/tick）下的性能与观感未实测** |
| U15 | `Attributes.SCALE` 在当前整合包里是否被别的模组也改动过（若被改动，法阵会跟着变大小）—— 未核对 |
| U16 | 绘制"到 1.20 s 刚好画满"的**观感是否与音频同拍** —— tick 23 画满、tick 24 起爆发，理论上同拍，但听感/观感只能由作者判断 |

---

## 十五、修订 rev 3（2026-10-16，第三轮实机反馈）：五角星读不出形状 + 整摞消耗 + 创造模式

### 15.1 四条问题：现象 → 根因 → 落地

| # | 现象（作者原话要点） | 根因 | 落地 |
|---|---|---|---|
| 1 | **画出来的不是五角星**，是"里面一个圆圈、外面一个圆圈" | **五角星的弦被严重欠采样**。弦长 = `2R·sin72° ≈ 1.902R` = **8.56 格**（默认体型），而每条弦只采 **5** 个点 ⇒ 1.71 格/颗；更糟的是**弦的中点最靠近圆心**，按参数 `t` 均匀采样会让 3/5 的点挤在半径 0.45R–0.72R 的**内侧** ⇒ 整颗星退化成"中间一圈散点 + 外圈上几个点"，读不出星形 | 改为**按弦长反算点数**（新常量 `STAR_CHORD_SPACING = 0.8` 格）⇒ 默认每条弦 **11** 点（星共 55 点/tick）；并把参数改为 `t = i/(n-1)` —— **两端都采到**，星的**尖**才会被画出来（尖是五角星的身份） |
| 2 | 放大后内部**从 2 个圈变 3 个圈**，也没有五角星 | **同一个根因**：弦长与半径同比变长（2× 时 17.1 格），而每弦点数只跟着翻倍到 10 ⇒ **稀疏程度不变**，且内侧那堆散点更宽 ⇒ 被看成第 3 个圈 | 同上。按弦长采样后间距恒定 ⇒ **任何体型下星都读得出来** |
| 3 | 一摞装着技能的魔源用一次，**整摞封存的技能都没了** | ⚠️ **数据组件是整摞 `ItemStack` 共有**的，而我直接对 `stack` 调了 `remove(...)` ⇒ 清掉的是**整摞**，不是手里那一个 | 新增 `EssenceItem#clearStoredSkill`：手里只有一个就地移除；**多于一个则拆出 1 个**、只移除它，再把那 1 个空的放回背包（背包满则 `drop` 到脚下，**不静默吞掉玩家物品**） |
| 4 | 创造模式使用时**不该消耗**封存的技能 | 未判创造模式 | `player.hasInfiniteMaterials()` ⇒ 直接返回：技能照样习得，魔源照旧是满的（与其它"可无限使用"的原版物品一致） |

### 15.2 取证：星形读不出来是**采样密度**问题，不是数学问题

用 `tools/plot_essence_circle.py` 把 Java 的公式在 Python 里复现并把粒子位置画成图，一图定位：

- **修复前**：红点（五角星）散成 5 段**短划** + 几个贴在外圈上的点 —— 完全没有星形。
- **修复后**：清晰的五角星，5 个尖正好落在外圈上，绿色内圈居中。

⇒ **教训（可复用）**：**圆是曲线，少量大点仍能被眼睛"连"成一个圈；直线/弦不行** ——
弦必须**按长度**采样。同一份代码里"外圈 16 点也读得像圈"与"星 5 点/弦完全读不出"并存，就是这条规律的实例。

⚠️ `plot_essence_circle.py` 是**诊断工具**，公式是照抄 Java 的（两侧必须同步改）；
它只回答"点位分布是什么形状"，**不能**替代实机观感（dust 的真实尺寸与亮度叠加只有实机能看）。

### 15.3 决策记录

| # | 决策 | 理由 |
|---|---|---|
| **D27** | 五角星**按弦长反算点数**（`STAR_CHORD_SPACING = 0.8` 格），不再用固定"每条边 N 点" | 固定点数随半径放大必然变稀；按长度采样才能让形状在任意体型下都读得出来 |
| **D28** | 弦的采样 `t = i/(n-1)`（**两端都采**） | 否则星的**尖**永远画不到（`t` 最大只有 `(n-1)/n`），而尖正是五角星的身份 |
| **D29** | 整摞只消耗**1 个**：拆出 1 个 → 移除组件 → 放回背包（满了掉脚下） | 数据组件整摞共有；直接 `remove` 会清掉整摞（实测踩过） |
| **D30** | **创造模式不消耗**（`hasInfiniteMaterials()`） | 作者明确要求；与原版"创造模式不消耗物品"一致 |

### 15.4 未能验证（本轮新增）

| # | 事项 |
|---|---|
| U17 | **实机观感** —— §15.2 的渲染图只证明"点位分布是五角星"，dust 在游戏里的真实尺寸/亮度叠加仍只有实机能看 |
| U18 | **"背包满则掉脚下"这条分支从未被触发过**（代码路径上是 `getInventory().add()` 返回 false 才走），未实机验证 |
| U19 | 创造模式下的行为**未实机验证**（只保证不进消耗分支） |
| U20 | 2× / 4× 体型下"弦长采样"的粒子量（2× 时 110 点/5 弦）**性能未实测** |

---

## 十六、修订 rev 4（2026-10-16，第四轮实机反馈）：改回"密集堆叠成实线" + 删掉内圈

### 16.1 两条反馈与落地

| # | 反馈（作者原话要点） | 落地 |
|---|---|---|
| 1 | 稀疏点阵的效果**明显不如第一版那种"密集粒子堆积成的线"** | 引入**唯一旋钮** `LINE_SPACING = 0.35`（格），点数**按线长反算**（`ceil(长度 / 间距)`）：外圈 81 点、五角星 5 条弦 × 25 = 125 点，**法阵合计 206 颗/tick**；`DUST_SCALE` 从 2.5 **回到 1.2**（≈ 第一版）：**小点 + 密排 = 细而实的一根线** |
| 2 | 中间那个**圆圈不需要**，只要"五角星 + 最外围一圈" | 删除内圈：常量 `INNER_RADIUS_RATIO` / `BASE_INNER_POINTS` / `INNER_SPIN_DEGREES` 与对应绘制循环**全部移除**（残留检查 0 命中） |

### 16.2 这一轮推翻的是我自己的判断（记录在案）

§14/§15 我一路在"省粒子"：点数减半 → 点放大到 2.5 补偿。作者的实机结论是
**"点阵观感不如密排的线"** ⇒ 说明**对法阵这类"画线"的效果，密度优先于节省**：
- 密度不够时，**放大点**只能把"点串"变成"粗点串"，补救不了"线"这个观感；
- 第一版之所以好看，正是因为 **0.34 格/颗 + 小点** ⇒ 粒子彼此堆叠成连续的一根线。

⇒ 现在 `LINE_SPACING` 成为**唯一**的密度旋钮（往小更实更费、往大更虚更省），
而 `DUST_SCALE` 只负责"线有多粗"。

### 16.3 代价（量化，需实机确认可接受）

点数按**线长**反算 ⇒ 体型放大时粒子量**按比例**涨：

| 体型倍数 | 外圈直径 | 外圈点 | 五角星点 | 法阵合计/tick |
|---|---|---|---|---|
| **1×（默认）** | 9 格 | 81 | 125 | **206** |
| 2×（放大护符） | 18 格 | 162 | 245 | **407** |
| 4×（上限） | 36 格 | 324 | 490 | **814** |

- 与"省粒子"路线对比：§15 的 48 颗/tick → 现在 206 颗/tick（**4.3 倍**），但换来的是实线观感。
- 包量仍是**每次调用 1 个包**（原版 API 限制）⇒ 默认体型约 **206 包/tick ≈ 4100 包/秒**。
- **若性能吃不住**：先调 `LINE_SPACING`（0.35 → 0.5 约省 30%），**不要**回到"稀疏 + 放大点"。

### 16.4 决策记录

| # | 决策 | 理由 |
|---|---|---|
| **D31** | **密度优先于节省**：引入 `LINE_SPACING` 作为唯一密度旋钮，点数按线长反算 | 作者实机裁定"点阵不如密排的线"；且按线长反算天然保证任何体型下密度恒定 |
| **D32** | `DUST_SCALE` 回到 **1.2**（≈ 第一版） | 密排之后点不需要大；点大只会把线糊粗 |
| **D33** | **删除内圈**，法阵只有"外圈 + 五角星" | 作者明确要求（只要中间五角星 + 最外围一圈） |
| **D34** | 保留 §15 的 `t = i/(n-1)`（弦两端都采） | 星尖必须画出来；与密度无关，独立成立 |

### 16.5 未能验证（本轮新增）

| # | 事项 |
|---|---|
| U21 | **206 颗/tick（2× 时 407、4× 时 814）的性能未实测** —— 尤其 4× 上限是压力点 |
| U22 | 实机观感：`LINE_SPACING = 0.35` + `DUST_SCALE = 1.2` 是否**真的读作一根线**（渲染图只是点位分布，dust 的屏幕尺寸只有实机能看） |
| U23 | 去掉内圈后"中间空不空"的观感 —— 五角星的弦本身会围出一个内五边形，理论上不空，但需作者判定 |
| U24 | 约 **4100 包/秒**（默认体型）在实机上的网络/客户端处理负担未实测 |

---

## 十六之补、rev 4 后续（同日）：体型倍数改用原版 `LivingEntity#getScale()`

**触发**：作者追问"原版的体型 attribute 是怎么控制玩家体型的？是直接乘吗？"取证后**替换**了算法。

### 补.1 算法替换

| | 替换前（§14 起） | 替换后 |
|---|---|---|
| 取量 | `player.getAttributeValue(Attributes.SCALE) / Attributes.SCALE.value().getDefaultValue()` | **`player.getScale()`** |
| 钳制 | `Mth.clamp(…, 1.0, 4.0)`（只放大不缩小、上限 4 倍） | **完全不变**（作者要求保留） |
| 差异 | 对玩家数值**等价**；但（a）属性缺席时依赖"除以默认值"这条换算兜底，（b）第三方若覆写 `sanitizeScale` 则不跟随，（c）自己维护了一条换算 | 自带 null 兜底（属性表未建时返回 `1.0F`）、自带 `sanitizeScale`、不写死默认值；**与渲染 / 碰撞箱同源** |

### 补.2 取证：原版 `SCALE` 的完整作用链（本次逐条核对源码）

`Attributes.SCALE` 在整个原版树里**只有 2 处引用**：
`LivingEntity.java:330`（给每个生物挂上该属性）与 `LivingEntity.java:554`（`getScale()` 读它）
⇒ **所有体型效果都汇聚到 `getScale()` 这一个方法**。

```java
// LivingEntity.java:552-555
public float getScale() {
    AttributeMap attributemap = this.getAttributes();
    return attributemap == null ? 1.0F : this.sanitizeScale((float)attributemap.getValue(Attributes.SCALE));
}
```

属性注册（`Attributes.java:124-126`）：`RangedAttribute("attribute.name.generic.scale", 1.0, 0.0625, 16.0)`
⇒ **默认 1.0、范围 0.0625–16，属性值本身就是"相对正常体型的倍数"**。

**跟着缩放的（4 条链）**：

| 链 | 源码 | 机制 |
|---|---|---|
| 碰撞箱 | `LivingEntity:3424` → `EntityDimensions.scale()` `:25-34` | `getDefaultDimensions(pose).scale(getScale())`；`scale(f)` = `new EntityDimensions(width*f, height*f, eyeHeight*f, attachments.scale(f,f,f), false)` ⇒ **宽/高/眼高/乘客挂点全乘** |
| 渲染 | `LivingEntityRenderer:97-98` | `float f8 = entity.getScale(); poseStack.scale(f8, f8, f8);`（阴影 `:283` 也乘） |
| 第三人称相机 | `client/Camera:67-68` | 相机距离乘 `getScale()` |
| 乘客挂点 | `LivingEntity:3739` | `getScale() * getAgeScale()` |

具体数字：玩家基础 `0.6 × 1.8`、眼高 `1.62`（`EntityType:808-809`）⇒ `SCALE = 2.0` 时碰撞箱 **1.2 × 3.6**、眼高 **3.24**。

**两个反直觉细节**：

1. `EntityDimensions.scale` 有 **`!this.fixed` 守卫**（`:30`）⇒ 尺寸被标记 `fixed` 的生物**缩放整个跳过**。
   （DS 的 `DragonSizeHandler:71` 就是自己算好尺寸连同 `fixed` 标志下发 ⇒ DS 龙的尺寸**不走**这条乘法。）
2. 碰撞箱**不是立刻**更新（`LivingEntity:2573-2578`）：
   `float f6 = this.getScale(); if (f6 != this.appliedScale) { this.appliedScale = f6; this.refreshDimensions(); }`
   ⇒ 与缓存值比较，**改属性后下一 tick 才刷新碰撞箱**。

覆写 `sanitizeScale` 的只有 `EnderDragon:987` 与 `Shulker:569` ⇒ **对玩家 `getScale()` 就是属性原值**。

**⚠️ 不跟着缩放的（对手感重要，将来别误判）**：

| 不缩放 | 源码 | 后果 |
|---|---|---|
| 交互距离 | `Player.blockInteractionRange()` `:2285`、`entityInteractionRange()` `:2289` | 各自只读自己的属性 ⇒ **2 倍体型够不到更远** |
| 台阶高度 | `LivingEntity.maxUpStep()` `:3732` | 读 `Attributes.STEP_HEIGHT`，**没有**乘 `getScale()` |
| 移速 / 跳跃 / 重力 | 各自独立的属性或常量 | 大体型**不会走得更快**（相对尺寸显得慢） |

⇒ "体型变大"在原版里**只改身体尺寸与观感**，没有同步改玩法参数。

### 补.3 决策记录

| # | 决策 | 理由 |
|---|---|---|
| **D35** | 体型倍数改用 **`player.getScale()`**，替换"读属性 ÷ `getDefaultValue()`" | 它**就是**原版"体型"的定义（渲染与碰撞箱同源）；自带 null 兜底与 `sanitizeScale`；少一条自己维护的换算。钳制（只放大、上限 4×）**保留不变** |
