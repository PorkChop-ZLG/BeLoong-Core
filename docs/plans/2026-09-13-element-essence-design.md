# 元素魔源（Element Essence）八件套 设计文档

**Date:** 2026-09-13
**Status:** Approved（用户已确认命名、稀有度、中文名、贴图方案；美术方向待实机观感复核）
**Approach:** 参数化物品类 + 元素枚举 + 程序化生成的 12 帧动态贴图
**Applies to:** Minecraft 1.21.1 / NeoForge 21.1.236

---

## 1. 需求

为 BeLoong-Core 添加 8 个基础物品「元素魔源」，对应五行（金木水火土）加冰风雷三种元素：

1. 8 个物品，中文名「X魔源」，无任何额外效果；
2. 每个物品有**动态贴图**（12 帧），贴图由本项目自行绘制；
3. 每个物品有 tooltip，文本格式为「自然界的X元素凝聚成的魔源，可以为龙的成长提供魔力。」；
4. 物品 ID 与英文名需与用户共同讨论定案。

## 2. 命名与属性（已定案）

| 元素 | 物品 ID | en_us 名 | zh_cn 名 | tooltip 代入词（中 / 英） |
|---|---|---|---|---|
| 金 | `beloong:metal_essence` | Metal Essence | 金魔源 | 金属 / metal |
| 木 | `beloong:wood_essence` | Wood Essence | 木魔源 | 木 / wood |
| 水 | `beloong:water_essence` | Water Essence | 水魔源 | 水 / water |
| 火 | `beloong:fire_essence` | Fire Essence | 火魔源 | 火 / fire |
| 土 | `beloong:earth_essence` | Earth Essence | 土魔源 | 土 / earth |
| 冰 | `beloong:ice_essence` | Ice Essence | 冰魔源 | 冰 / ice |
| 风 | `beloong:wind_essence` | Wind Essence | 风魔源 | 风 / wind |
| 雷 | `beloong:thunder_essence` | Thunder Essence | 雷魔源 | 雷 / thunder |

**属性**：`new Item.Properties().rarity(Rarity.UNCOMMON)`。

- `stacksTo(64)`（`Item.Properties` 默认值，与"最基础物品"的定位一致）。
- `Rarity.UNCOMMON`：见 §2.1。

### 2.1 「金」的两处命名依据

- **ID/英文名用 `metal` 而非 `gold`**：金是五行之"金"（金属），不是黄金这一具体材质。
- **tooltip 里「金」代入「金属」而非「金」**：中文「金元素」会被读成"金钱"的金，且与「金属」不是同一个词；「金属元素」才是中文的正确说法。其余七项保持单字（木/水/火/土/冰/风/雷），因为「木元素」「火元素」本身就是中文通用说法。

### 2.2 稀有度取值的由来（重要，勿改回）

用户最初要求"原版金色对应的稀有度"。**1.21.1 原版四个稀有度没有任何一个是金色**（`Rarity.java:16-19`）：

| 稀有度 | 显示色 | 色值 |
|---|---|---|
| `COMMON` | 白 | `#FFFFFF` |
| `UNCOMMON` | 黄 | `#FFFF55` |
| `RARE` | 青 | `#55FFFF` |
| `EPIC` | 品红 | `#FF55FF` |

而 `ChatFormatting.GOLD` 是 `#FFAA00`（`ChatFormatting.java:23`）——**金色是原版颜色常量，但原版稀有度没有谁用它**。这个印象很可能来自 1.12 及以前（1.13 扁平化重排了稀有度语义）。

用户最终选择 **`Rarity.UNCOMMON`**，因为它是原版四个中最接近"金"的观感（黄色），且零额外代码。也评估过用 FML 的 `RuntimeEnumExtender` 给 `Rarity` 注入一个金色常量（技术上可行：`Rarity` 声明了 `@IndexedEnum`/`@NamedEnum(1)`/`@NetworkedEnum(BIDIRECTIONAL)` 并实现 `IExtensibleEnum`，是官方支持的扩展点），但因代价超出 8 个基础物品的收益而未采用。

## 3. 架构

**一个参数化物品类 + 一个元素枚举**。8 件行为完全相同（无效果、仅 tooltip），分别写 8 个类没有任何收益；而项目现有 `item/effect/` 下每个物品一个类，是因为那 4 件各有独立行为。

```
item/essence/ElementType.java        元素枚举：id 名 + 中英文代入词
item/essence/ElementEssenceItem.java extends Item，唯一覆写 appendHoverText()
ModItems.java                        8 个 DeferredItem<Item> 常量（显式逐个注册）
ModCreativeModeTabs.java             8 个 output.accept(...)
```

**tooltip 用单一 key + 单一 `%s`**，8 件共用模板，代入词按元素翻译键解析：

```java
tooltipComponents.add(Component.translatable(
        "item.beloong.element_essence.tooltip",              // 模板
        Component.translatable(element.tooltipNameKey())));   // 代入的词：元素词
```

**为什么只传一个参数（元素词）**：`%s` 按出现顺序取参（`TranslatableContents.decomposeTemplate`：无显式序号时 `i++` 递增取 `args[i]`）。初版实现同时传了「物品名 + 元素词」两个参数，验证脚本立刻抓出中文被渲染成「自然界的**金魔源**元素凝聚成的魔源」——因为中文模板的第一个 `%s` 位于「自然界的…元素」中间，取到的是 `args[0]`（物品名）。

中文需要元素词、英文需要物品名，而两者都落在 `args[0]`，**一个模板无法同时满足**。修正为只传元素词，英文侧的物品名改用固定的 `Elemental Essence` 表述：

| 语言 | 模板 | 渲染结果 |
|---|---|---|
| zh_cn | `自然界的%s元素凝聚成的魔源，可以为龙的成长提供魔力。` | 自然界的**金属**元素凝聚成的魔源，可以为龙的成长提供魔力。 |
| en_us | `An Elemental Essence condensed from the %s element; it provides mana for a dragon's growth.` | An Elemental Essence condensed from the **metal** element; … |

英文用 `An Elemental Essence` 而非 `A %s Essence` 的两个理由：① 八种元素的英文词首音全为元音（earth/fire/ice/…），`An` 一律成立，不必按元素选冠词；② 避免 "Metal Essence … the metal element" 在一句里把 metal 重复三遍（用户已定「英文名与 ID 等同」，物品名本身含元素词）。

为什么不把完整句子拆成 8 条独立 tooltip 键：模板 + 代入词的写法让「金→金属」这类单点差异集中在一处；同时把元素词做成独立键（`element.beloong.*`）后，资源包作者可以单独覆盖某一个元素词而不必重写整句。

## 4. 动态贴图技术规格（已从 NeoForge 源码核实）

| 项 | 值 | 依据 |
|---|---|---|
| 单帧尺寸 | 16×16 | 与项目现有物品贴图一致 |
| 帧数 | **12** | 见 §4.1 |
| 纹理画布 | 16×192（竖排帧条） | `SpriteContents.java:207-219`：帧索引 → `x = (i%列数)*宽`、`y = (i/列数)*高`；单列时高度 = 16×帧数 |
| `.mcmeta` | `<贴图名>.png.mcmeta` | 与项目现有 15 个 `.mcmeta` 一致 |
| `frametime` | **1**（单位 tick，即 20 fps → 0.6 s 一轮） | `AnimationMetadataSection.java:12` 默认 1；`AnimationMetadataSectionSerializer.java:20` |
| `interpolate` | **false** | `AnimationMetadataSectionSerializer.java:51`；像素风开插值会糊（`dawn_light` 开了，但那是一张柔和的图） |
| `width`/`height` | 必须显式写 16 | `AnimationMetadataSection.java:34-43`：不写则按 `min(宽,高)` 推断帧尺寸 |

### 4.1 为什么是 12 帧

纹理高度必须是 16 的整数倍，故帧数只能取 {2,4,8,12,16,20,…}。候选三档：

| 帧数 | 画布 | 一轮时长 | 评估 |
|---|---|---|---|
| **12（选定）** | 16×192 | 0.6 s | 流动/环绕类动效足够顺；文件小 |
| 16 | 16×256 | 0.8 s | 仅整圆旋转的步进更规整（22.5° vs 30°），本设计不做整圆旋转，收益拿不到 |
| 8 | 16×128 | frametime 1 时太快、2 时需靠插值糊 | 流动动效偏跳 |

## 5. 美术设计

### 5.1 共同基座

八件共用同一个**棱面宝石**轮廓（`tools/generate_element_essence_textures.py` 的 `draw_body`），参数固定：

```
CENTER=7.5  RADIUS_X=5.4  RADIUS_Y=5.6
RIM_INNER=0.93   FACET_INNER=0.52   CORE_INNER=0.22
SHADE_STEPS=(1.0, 0.72, 0.44)
```

- 深色轮廓环（`radius > 0.93`，精确色，不参与明暗缩放）
- 棱面按 `cos(angle - 225°)` 三档明暗，跨度 0.44–1.0（**刻意大于 2 倍**，否则 16×16 下会读成球体而非棱面）
- 中心高光核 + 一圈 0.3 混合的过渡环
- **扫光带**：`travelling_highlight` 沿角度一圈，12 帧走完一次，混合强度 0.85

八件的差异只来自**色系**与**动效**，不来自外形——用户明确选择"共用基座 + 元素动效"以保留套装感。

### 5.2 色板

| 元素 | facet（晶面） | highlight（高光/核心） | rim（轮廓） | accent（动效） |
|---|---|---|---|---|
| 金 | `#FFD966` | `#FFFBEA` | `#7A5E14` | `#FFFFFF` |
| 木 | `#6FBF4A` | `#CDF09B` | `#27501F` | `#EAFBC8` |
| 水 | `#4A9BE0` | `#C3E6FA` | `#173F66` | `#EAF7FF` |
| 火 | `#FF8A3D` | `#FFE08A` | `#7A2410` | `#FFF3C4` |
| 土 | `#A9793F` | `#E4C593` | `#4E3418` | `#F6E4C2` |
| 冰 | `#8FE3F0` | `#F0FDFF` | `#337A93` | `#FFFFFF` |
| 风 | `#A8D8CF` | `#F2FEFB` | `#42695F` | `#FFFFFF` |
| 雷 | `#8B5CF6` | `#FFE9A3` | `#2E1459` | `#FFC24A` |

**雷为什么是紫金**：用户指定雷用"紫金配色"。紫（facet）承担元素身份，金只出现在电弧（accent）与核心高光（highlight）——若让金占据晶面，雷会与金的魔源在缩略图里撞色。

### 5.3 逐元素动效

| 元素 | 动效 | 实现函数 |
|---|---|---|
| 金 | 高光带绕行 + 两枚追随火星 | `draw_metal_overlay` |
| 木 | 顶部叶芽逐帧长到 6 格后回折 | `draw_wood_overlay` |
| 水 | 水滴沿正面下落 → 触底化成横贯涟漪亮带 | `draw_water_overlay` |
| 火 | 火舌逐帧跳动 + 火星溅射 | `draw_fire_overlay` |
| 土 | 暗色裂纹扩展 + 整体下沉 1 px 的脉动 | `draw_earth_overlay` |
| 冰 | 结晶沿竖直/水平轴交替向外生长 + 四角闪光 | `draw_ice_overlay` |
| 风 | 三颗粒子带拖尾绕宝石一圈 | `draw_wind_overlay` |
| 雷 | 金色锯齿电弧闪现 4 帧 → 余辉 8 帧 | `draw_thunder_overlay` |

### 5.4 一条已踩过的坑：overlay 必须画在基座之上

初版把叠加层分成"内部叠加（画在基座前）"与"外部叠加（画在基座后）"，意图让水滴/裂纹读成宝石内部细节。**这个分层不成立**：基座是不透明的，画在它下面的像素会被 100% 覆盖。实测取证（水，frame 9）：

```
after overlay  (8,11) = (234,247,255)    ← accent，水滴/涟漪写进去了
after body     (8,11) = ( 33, 68, 99)    ← 基座轮廓色，被完全盖掉
```

`(8,11)` 的 `radius ≈ 0.71`，在宝石轮廓内，因此必然被基座覆盖。**修正**：删除内/外分层，改为**单遍**——基座先画，所有动效一律画在其上，用高对比色（accent/highlight/rim）直接盖在宝石表面。

由此产生一个**已知且已接受的取舍**：16×16 内宝石半径仅约 5 像素，"落在宝石内部"的动效（水的涟漪、土的裂纹、雷的余辉）在正常大小下会被读成**表面纹路**而非形状变化。这是像素预算的硬限制；金/木/火/冰/风/雷的轮廓外动效不受影响。

## 6. 文件清单

**新增 Java（2）**
- `src/main/java/com/zonlong/beloong/item/essence/ElementType.java`
- `src/main/java/com/zonlong/beloong/item/essence/ElementEssenceItem.java`

**修改 Java（2）**
- `src/main/java/com/zonlong/beloong/item/ModItems.java`（+8 常量）
- `src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java`（+8 accept）

**新增资源（8×3 = 24）**
- `src/main/resources/assets/beloong/textures/item/<element>_essence.png`（16×192，12 帧）
- `src/main/resources/assets/beloong/textures/item/<element>_essence.png.mcmeta`
- `src/main/resources/assets/beloong/models/item/<element>_essence.json`（`parent: minecraft:item/generated`）

**修改资源（3）**
- `assets/beloong/lang/en_us.json`
- `assets/beloong/lang/zh_cn.json`
- `.gitignore`（加 `preview/`）

**新增工具（本地，`tools/` 已在 `.gitignore` 内）**
- `tools/generate_element_essence_textures.py` — 生成 8 张贴图与 `.mcmeta`，支持 `--preview-dir` 先预览
- `tools/dump_essence_ascii.py` — 把指定帧按调色板槽位打成 ASCII 网格，用于逐像素排查叠加层是否落位
- `tools/verify_essence_lang.py` — 用真实语言文件重演占位符展开，断言 tooltip 最终文本正确（见 §9.3）

## 7. 决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | ID 后缀 `_essence` | 用户从三个候选中选定；`source` 在 Java/Minecraft 语境歧义重（`BiomeSource`/`SoundSource`） |
| D2 | 英文名与 ID 等同 | 用户明确要求；代价是英文名无法区分八件，但 tooltip 与贴图可区分 |
| D3 | 中文名「X魔源」 | 用户先在「X元素魔源」与「X魔源」间选定后者（八件都不以四字同尾收束，靠首字区分） |
| D4 | tooltip 单一模板 + 代入词 | 见 §3；让单点差异集中，且元素词可被资源包单独覆盖 |
| D5 | 「金」代入「金属」 | 见 §2.1 |
| D6 | `Rarity.UNCOMMON` | 见 §2.2 |
| D7 | 12 帧 / frametime 1 / 不插值 | 见 §4.1 |
| D8 | 共用基座 + 元素动效 | 用户选定；保留套装感 |
| D9 | 雷用紫金 | 用户指定；紫承身份、金只入电弧，避免与金撞色 |
| D10 | 贴图由脚本程序化生成 | 可复现、可微调、可审查，符合项目"设计→实现→取证"习惯；沿用 `tools/extract_banner_texture.py` 的"先预览再落盘"约定 |
| D11 | 保留生成脚本与 ASCII 诊断工具 | 日后微调配色时可直接复用，不必重画 |
| D12 | `stacksTo` 保持默认 64 | "最基础物品"即默认属性；未提出珍贵材料需求 |
| D13 | 8 件在创造页签中按五行+冰风雷顺序排列 | 与语言文件、枚举声明顺序一致，便于对照 |

## 8. Non-Goals

- **不给物品任何效果**（用户明确"暂时为最基础的物品"）；不新增属性、不注册药水效果、不加配方。
- **不做数据驱动**：8 件是固定内容，不引入 Codec/JSON 注册表。项目现有数据驱动项（水区域/结构效果/财宝成长/石碑放置）都是"整合包作者可能想改"的内容，本项不符合该判据。
- **不加配置项**：无任何可调开关（与天灾群系映射表同理，属结构性内容）。
- **不修改 `Rarity` 枚举**：见 §2.2。
- **不做 32×32 高清贴图**：与项目现有物品贴图尺寸保持一致。

## 9. 验证计划

1. `gradlew.bat build` 通过。
2. 核对 jar 内存在 8 个 `<element>_essence.png` + `.mcmeta` + 模型 JSON，以及 `item/essence/*.class`。
3. `python tools/verify_essence_lang.py`：用**真实语言文件**重演 Minecraft 的占位符展开，打印两种语言下 8 件的最终 tooltip 文本，并断言中文字面与需求完全一致、无未解析的键泄漏。这条是必须的——D4 的初版实现（双参数）就是被它抓出中文渲染错误的。
4. 用 `tools/dump_essence_ascii.py` 抽查每个元素的若干帧，确认该元素的 accent 像素确实出现（防止再次出现"叠加层被静默吞掉"这类只有看图才能发现的问题）。
5. 实机（由用户执行）：确认 8 件在创造页签可见、贴图在 20 fps 下循环顺滑且无跳帧接缝、物品名显示为黄色、tooltip 文案正确。
