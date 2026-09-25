# 简易 NPC 对话系统 设计文档

**日期：** 2026-09-20
**状态：** 待批准（由对话整理而成，供 planning 使用）
**分支：** `NPC`
**采用方案：** 纯客户端 + 数据包 JSON + 自绘 GUI —— **零 mixin、零网络包、零服务端逻辑**
**测试范围：** 仅 `minecraft:iron_golem`（数据格式天然支持多实体，但本次只交付铁傀儡一份）
**参考图：** 用户提供 `参考1.png` ~ `参考5.png`。本文档**所有布局数值均从参考5（1920×1080 原生截图）量得**，其余四张仅作交叉验证（见 §3.1 的偏差说明）

---

## 一、问题陈述

### 1.1 需求（用户口径）

整合包需要一套「右键实体 → 弹出原神式对话框 → 逐字显示若干页文本 → 结束后弹出选项」的**简易** NPC 对话：

1. 右键**配置好的实体**触发；**触发方式（空手 / 手持任意物品）由数据文件配置**，缺省为空手；对话文本居中显示在屏幕下方。
2. 同一类型的实体**只有一段对话**；文本走**翻译键**。
3. 对话结束（所有文本显示完）后弹出**选项框**，本次只需要「离开」。
4. 实体清单、对话键、页数**数据驱动**；页内用 `\n` 分行；支持 `§` 颜色代码。
5. JSON 中**预留可选的声音字段**，为将来接入配音留位。
6. UI 参考《原神》：底部渐变压暗、名字居中带金色装饰线、正文白色居中、逐字打字机、右侧竖排选项、悬停金色过渡、等待点击时底部有向下箭头。

### 1.2 为什么可以做得这么简（三条事实）

| # | 事实 | 推论 |
|---|---|---|
| 1 | 同类型实体只有一段对话，且文本在语言文件里 | **没有任何需要持久化的东西** —— 无进度、无 flags、无 per-instance 状态、无对话树 |
| 2 | v1 唯一的选项是「离开」 | 对话**没有副作用** → 整条链路可以跑在客户端，**不需要服务端参与、不需要网络包** |
| 3 | 后续复杂对话交给第三方对话框模组 | 本功能的长期定位是**骨架**（谁在什么时候被右键 + 把文本显示出来），不负责对话逻辑 → 现在多做的抽象将来都会被丢掉 |

### 1.3 讨论中被排除的复杂度（及理由）

本功能在立项前的调研覆盖了 10 个同类模组（详见 §12）。下表每一项都是**因为有成熟先例**才敢砍掉的：

| 被排除项 | 理由 |
|---|---|
| 对话树 / 分支 / `next` | 同类型只有一段对话，线性即可。ADM 的 `nodes` 图是为"一段对话多分支"设计的 |
| `priority` / 多绑定命中规则 | 一个实体类型只绑定一份对话，不存在冲突（RPG Dialogue 的 `dialogue_speakers` 才有优先级问题） |
| `requires` 前置条件 | v1 无条件。且条件判定**必须服务端做**（RPG Dialogue 索性不把条件不成立的选项发给客户端），一旦引入就要加网络包 |
| 登录同步 payload + 客户端缓存 | 数据随模组 jar 分发，客户端自己就能读（§8.3） |
| 进度存储（flags / 记忆 / `has_seen`） | 用户明确"没有存储的必要" |
| 数据包 `data/` 与配置文件双宿主 | ADM 支持 `config/adm-dialogues/` 作为第二宿主；本模组只需 jar 内一份 |
| 头像 / 立绘 / 背景图 | 客户端资源不能放在 `data/`，会引入资源包生成（ADM 就为此外挂了一个生成式资源包），v1 不做 |
| 打字音效 | `sound` 字段只做**数据通路**，实装推迟到有音频资源时 |
| 原版 `minecraft:dialog` 注册表 | **1.21.6 才引入**（非 1.21.5），1.21.1 不存在；整合包内的 Vanilla Backport 也**没有**回移它（本地全库搜 `dialog` 零命中）。但字段命名对齐原版 schema，将来升级可直接迁移 |

---

## 二、目标与非目标

### 目标

1. 右键铁傀儡 → 打开一个原神式对话屏（世界保持清晰，只有底部渐变压暗）；**触发方式由 JSON 的 `trigger` 字段决定**（`empty_hand` 空手 / `any` 手持任意物品），**缺省 `empty_hand`**。
2. 多页文本，**逐字打字机**（默认 1 字/tick，配置可调）。
3. 点击语义：补全当前页 → 翻页 → 最后一页显示完后弹出选项。
4. 文本走翻译键，支持 `§` 颜色代码，页内 `\n` 分行。
5. **数据驱动**：实体、**触发方式**、对话键、页数、可选声音全在 JSON。
6. 数据读取**在客户端完成**，不发网络包、不加服务端逻辑。
7. 「离开」按钮：右侧竖排、宽度自适应但**左缘对齐**、悬停金色**带过渡**、左侧带图标。
8. 提供**单一"打开对话"入口**，便于将来整体替换为第三方模组。

### 非目标（明确不做）

- ❌ 不做对话树 / 分支 / 条件 / 进度存储
- ❌ 不做服务端逻辑、**不发任何网络包**
- ❌ **不改任何 mixin**
- ❌ 不挂铁傀儡以外的实体（只交付一份示例数据）
- ❌ 不接入第三方模组（只留接缝，见 §11）
- ❌ 不做头像 / 立绘 / 背景图 / 打字音效
- ❌ 不实装 `sound` 播放（只解析字段）
- ❌ 不做实例级区分（不读 NBT / 自定义名，只按实体类型）
- ❌ 选项只有 `close` 一种动作
- ❌ **不做潜行判断**（潜行不改变触发行为；用户裁定，2026-09-20）

---

## 三、界面规格（从参考图量测）

### 3.1 量测表与偏差说明

参考5 是 1920×1080 原生截图（预览 1066×600，折算系数 0.9 到 GUI 缩放 2 的 960×540 坐标系）：

| 元素 | 放大裁切实测（见 §4.2 修订 R2） | 折算到 960×540 | **本文档取值** |
|---|---|---|---|
| 名字中轴 | 79.6% 屏高 | y ≈ 430 | `y = h * 0.796` |
| **装饰线** | **83.1% 屏高（位于名字与正文之间，横贯）** | y ≈ 449 | `y = h * 0.831`；宽 `0.33 w`，居中；**两端各一个小菱形端饰** |
| 正文中轴 | 85.8% 屏高 | y ≈ 463 | `y = h * 0.858` |
| 继续箭头 | 参考3：96.4% 屏高 | y ≈ 521 | `y = h * 0.964`，居中；**菱形外框 + 内部向下亮金三角**，约 14 px |
| 选项列左缘 | 66.1% 屏宽 | x ≈ 634 | `x = w * 0.661` |
| 选项按钮高 | 4.8% 屏高 | 26 | `26` GUI px |
| 选项间距 | 4.3% 屏高 | 22 | `22` GUI px |
| 选项①中轴 / ②中轴 | 67.2% / 76.3% 屏高 | 363 / 412 | 由"最下一颗**底边** `y = h*0.787`、向上排"推导 |
| 底部渐变起点 | 约 72% 屏高起向下渐黑 | — | `fillGradient(0, h*0.72, w, h, 0x00000000, 0xC0000000)` |

> **为什么按屏高百分比锚定，而不是固定像素**：参考1/2 算出的名字距底为 73~89 GUI px，而参考3/4/5 为 105~114 GUI px —— **像素距离差一倍，百分比却完全一致**（79.7% / 83.6% / 78.9% / 80.4% / 86.5%）。
> 说明前两张视频帧的下边缘被裁切过。百分比锚定同时还能跨 GUI 缩放保持一致，故取百分比。

### 3.2 与参考图的一处**刻意差异**

参考5 里「正文」与「右侧选项」**同时出现**；但用户明确要求「**显示完所有文本后**弹出选项」。
⇒ **按用户口径实现**：选项只在最后一页显示完后出现；参考图仅用于**样式**，不用于该时序。

另一处派生结论（依据参考5）：选项出现时**继续箭头应当隐藏**（参考5 是选项态，图中没有箭头）。

### 3.3 绘制顺序与坐标

渲染顺序（从下到上）：

```
① 世界（原版照常渲染，不清屏、不模糊）
② 底部渐变      fillGradient(0, h*0.72, w, h, 0x00000000, 0xC0000000)
③ 装饰线 + 名字  同一条水平中轴，y = h*0.80
④ 正文          居中，y = h*0.86（白色 + 原版投影）
⑤ 继续箭头      居中，y = h*0.955（等待点击时显示；选项出现后隐藏）
⑥ 选项列        左缘 x = w*0.665，最下一颗底边 y = h*0.765，向上排，间距 18
```

细则：

| 元素 | 规格 |
|---|---|
| 名字 | **居中**；金色（由语言文件的 `§` 决定，代码不写死颜色）；字号 `pose().scale(nameScale)`，配置默认 **1.5** |
| 装饰线 | **位于名字与正文之间**（`y = h*0.831`），横贯居中、宽约 `0.33 w`；金色；**两端各一个小菱形端饰**（用 `fill` 拼 4~7 行像素画菱形） |
| 正文 | 白色 `0xFFFFFFFF`；**原版投影**（`drawString(font, text, x, y, color, true)`），**不做四向描边**（见 §8.8）；居中 |
| 继续箭头 | **菱形外框 + 内部向下亮金三角**（`fill` 拼像素），约 14×14 px；上下浮动 ±2px（周期约 20 tick） |
| 选项常态 | 底色深黑半透明 `0xA0000000`，文字白色，左侧图标 |
| 选项悬停 | 底色过渡到半透明暖金（起始值 `0xC0C8A05A`），**有过渡**：每 tick 靠拢 0.25（约 200ms），用 `FastColor.ARGB32.lerp` |
| 选项宽度 | **按文字宽度自适应**，但**左缘固定**（参考图即如此，因此右边缘天然参差） |
| 选项图标 | 左侧自绘「离开」图标（用 `fill` 拼门形/箭头），距按钮左缘 6px；**不引入贴图资源**。※参考图里是白色三点气泡，我们要的是"离开"语义，故换成门/箭头 |
| 圆角 | 参考图是**全圆角胶囊**（半径 = 高/2 = 13）；v1 用 `fill` 画矩形 + 四角逐层 `fill` 做假圆角 |
| 暂停 | `isPauseScreen() = false`（用户裁定：暂停破坏沉浸感） |
| ESC | 保留可关闭（`shouldCloseOnEsc()` 默认 true） |

### 3.4 交互状态机

```
TYPING(第 i 页, 已显示 n 字)
    每 tick: n += charsPerTick（配置，默认 1）
    ├─ n 达本页可见字符数 → 显示箭头 → WAIT_CLICK
    └─ 点击 → 本页立刻全显示 → 显示箭头 → WAIT_CLICK

WAIT_CLICK                          ← 箭头在"等待点击"期间始终显示
    ├─ 点击 && 非最后一页 → 第 i+1 页（TYPING, n = 0）
    ├─ 点击 && 是最后一页 → 隐藏箭头、弹出选项 → SHOWING_OPTIONS
    └─ ESC → 关闭

SHOWING_OPTIONS
    ├─ 点击「离开」 → 关闭
    └─ ESC → 关闭
```

打字机的两个实现要点（都不难，但错了会很难看）：

1. **必须预排版**：进入一页时先按可用宽度把整页切成若干行并缓存；随后按"可见字符总数"逐字揭示各行前缀。
   若每次渲染都重新 `font.split(...)`，字变多时会**突然重排**，观感很差。
2. **不能把 `§` 切碎**：揭示计数按**可见字符**走，遇到 `§` 时把它与后面的代码字符作为**一个整体**跳过、不计入可见数。

---

## 四、架构

### 4.1 变更清单

```
新增  src/main/java/com/zonlong/beloong/dialogue/
      ├── NpcDialogueEntry.java        record + Codec（entity / 可选 name / pages[]，页含可选 sound）
      └── NpcDialogueLoader.java       客户端 reload listener（单例 INSTANCE，含 EntityType 查询）

新增  src/main/java/com/zonlong/beloong/client/
      ├── NpcDialogueScreen.java       屏幕：状态机 + 全部渲染
      ├── NpcDialogueOptionButton.java 自绘选项按钮（hover 插值）
      └── NpcDialogueHandler.java      客户端右键入口（游戏总线）

改动  src/main/java/com/zonlong/beloong/BeLoongCoreClient.java
      + 一个 static @SubscribeEvent 注册客户端 reload listener（mod 总线，与同类注册同构）
      + 构造函数里 NeoForge.EVENT_BUS.register(new NpcDialogueHandler())（与 LoongPalaceSkyTickHandler 同构）

改动  src/main/java/com/zonlong/beloong/Config.java
      + [npc_dialogue] enabled / charsPerTick / nameScale

新增  src/main/resources/assets/beloong/beloong/npc_dialogue/iron_golem.json
改动  src/main/resources/assets/beloong/lang/zh_cn.json    +4 条键
改动  src/main/resources/assets/beloong/lang/en_us.json    +4 条键
```

> **新增/改动都不碰 `beloong.mixins.json`** —— 本功能零 mixin。

### 4.2 决策记录

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D1** | 触发事件 | `PlayerInteractEvent.EntityInteract` | 项目已有同款先例（`compat/ftbchunks/LoongPalaceProtectionHandler:111-124`）。该事件**两侧都会派发**，因此必须只在一侧受理 |
| **D2** | 受理侧 | **仅客户端**（`event.getLevel().isClientSide()`） | v1 无副作用；服务端受理就得回一个"打开对话"包，纯属多余 |
| **D3** | 触发方式 | **数据驱动**：JSON `trigger` 字段 —— `empty_hand`（空手，**缺省值**）/ `any`（手持任意物品）；**不做潜行判断** | 用户裁定（2026-09-20，见 §4.2 修订 R1）。缺省空手以保住"手持铁锭右键铁傀儡 = 修血"的原版语义；`any` 改为**逐实体**显式选择（RPG Dialogue 用同款 `holding` 反条件表达同一件事） |
| **D4** | 是否取消事件 | **不取消** | 空手右键铁傀儡原版无任何行为；取消没有收益，反而会吃掉第三方模组的交互（将来要接第三方，现在就不该抢） |
| **D5** | 数据位置 | 模组 jar 内 **`assets/beloong/beloong/npc_dialogue/<entity>.json`**（**不是 `data/`**，见修订 R3） | 客户端资源管理器以 `PackType.CLIENT_RESOURCES` 构造，只看得到 `assets/` 树；路径前缀 `beloong/` 同时起到跨模组命名空间隔离的作用（与现有 4 个 loader 的 `beloong/<系统>` 约定一致） |
| **D6** | 读取侧 | **客户端** `SimpleJsonResourceReloadListener`，经 `RegisterClientReloadListenersEvent` 注册 | 渲染全在客户端；客户端同样把模组 jar 当资源包加载，`data/` 可读 ⇒ **零网络包**（风险见 R0） |
| **D7** | 文件粒度 | **一个文件 = 一个实体类型** | 加一个实体 = 加一个文件，改动面最小；与 `waystone_placement` 的"一文件一清单"风格一致 |
| **D8** | 页数表达 | **`pages` 数组长度**，不设 `"pages": 3` 计数字段 | 少一个必须与数组保持同步的字段，就少一类"键名对不上"的故障（ADM 专门做 `/npc validate` 查这类问题） |
| **D9** | 文本载体 | 翻译键；页内 `\n` 分行；支持 `§` | 用户要求。`§` 已核实原版渲染链真的解析（§8.1） |
| **D10** | 名字来源 | JSON `name` 可选；缺省用 `entity.getDisplayName()` | 不配置也能跑；铁傀儡默认就显示"铁傀儡" |
| **D11** | 名字颜色 | 由语言文件里的 `§` 决定（示例给 `§6` 金色），**代码不写死颜色** | 改配色只需改语言文件，不必动代码（也符合"文本归文本"） |
| **D12** | 名字字号 | `pose().scale(nameScale)`，配置默认 **1.5** | 用户要求"比正文大一号"；但位图字体非整数缩放有参差（风险 R1），故做成配置可退回 1.0 |
| **D13** | 背景 | **覆写 `renderBackground` 为空**，只画自己的底部渐变 | 原版默认会模糊世界 + 盖暗底，见 §8.2。这是本功能最容易踩的坑 |
| **D14** | 暂停 | `isPauseScreen() = false` | 用户裁定：不暂停，保持沉浸感 |
| **D15** | 继续箭头可见性 | "等待点击"时显示；**选项出现后隐藏** | 参考5 是选项态，图中无箭头 |
| **D16** | 布局锚定 | **按屏高百分比**，不按固定像素 | 见 §3.1 的偏差说明；同时跨 GUI 缩放一致 |
| **D17** | 选项来源 | v1 **硬编码**「离开」（label 走翻译键），**不放进 JSON** | 选项一旦数据驱动就得定义 `action` 语义，而 action 将来归第三方模组，现在定义是白做 |
| **D18** | `sound` 字段 | **解析保留、v1 不播放** | 用户要求"预留"；解析它意味着 schema 从此定型，将来实装只是加一行 `playLocalSound` |
| **D19** | 开关配置 | 加 `[npc_dialogue] enabled = true` | 将来第三方模组自带"实体→对话"绑定时（RPG Dialogue 的 `dialogue_speakers` 就是），一键让位，不必挖代码 |
| **D20** | 重复绑定处理 | 同一 `entity` 被多个文件绑定时：按 `ResourceLocation` 排序后处理，**后处理者胜 + `LOGGER.warn`** | 保证行为确定（不依赖文件系统枚举顺序），且失败可见 |

### 设计修订（2026-09-20，用户裁定 + 参考图放大重测）

**R1 —— 触发方式改为数据驱动。** 初版把"空手且非潜行"写死为触发守卫。用户裁定改为 JSON 的 `trigger` 字段：
`empty_hand`（空手，**缺省值**）/ `any`（手持任意物品），并**取消潜行判断**。
代价：选 `any` 的实体会吃掉原版"手持铁锭给铁傀儡回血"的行为 —— 这是**逐实体**的显式选择，不再是全局写死。

**R2 —— 参考图量测更正。** 初版从缩略图读出的三处结论是**错的**，经 3×/5× 放大裁切重测后更正：

| # | 初版（错） | 实测（对） |
|---|---|---|
| 1 | 装饰线"与名字同高、名字左右各一条、外端渐隐" | **名字与正文之间的一条横贯金线**（`y = h*0.831`，宽约 `0.33 w`），**两端各一个小菱形端饰** |
| 2 | 最下一颗按钮**底边** = `0.765h` | `0.765h` 是**中轴**；按钮高 4.8% 屏高（≈26 GUI px）、间距 4.3%（≈22 GUI px），均比初版估算更大 |
| 3 | 继续箭头 = 简单金色小三角 | **菱形外框 + 内部向下的亮金三角** |

证据：`docs/pictures/原神对话UI参考/参考5.png` 的 x800~1140 / y830~980 与 x680~1320 / y850~910 区域 5× 与 3× 放大；`参考3.png` 的 x330~510 / y470~550 区域 7× 放大。
另有两处新增观察：竖排选项的**最下一颗紧贴名字上方**（底边 ≈427 对名字顶边 ≈424）；选项是**全圆角胶囊**、左侧为白色三点气泡图标（我们要"离开"语义，故换成门/箭头）。

**R3 —— 数据必须放 `assets/` 而不是 `data/`（2026-09-20，代码审查发现，实现已改）。**

初版 D5 把数据放在 `data/beloong/beloong/npc_dialogue/`，并据此推断"客户端能读到 jar 内 `data/`"。
**这个推断是错的**，已核到原版源码级：

| 事实 | 依据 |
|---|---|
| 客户端重载管理器绑定 `CLIENT_RESOURCES`（目录名 `assets`） | `Minecraft.java:487` `new ReloadableResourceManager(PackType.CLIENT_RESOURCES)`；`PackType.java` `CLIENT_RESOURCES("assets")` / `SERVER_DATA("data")` |
| 路径解析按 pack type 加前缀 | `FallbackResourceManager:170` → `packresources.listResources(this.type, this.namespace, path, …)` |
| 因此该监听器**只看得到 `assets/`** | 推论。后果：数据放 `data/` 时 `entries` 恒为空、右键永远无效，**且不报任何错** |
| 初版引用的"先例"其实不成立 | DS 的 `DragonPartLoader` 目录是 `"skin/parts"`，其文件在 `assets/dragonsurvival/skin/parts/` —— 它证明的是 `assets/`，不是 `data/` |

**当前口径**：数据放 `assets/beloong/beloong/npc_dialogue/*.json`，loader 的目录字符串仍是 `"beloong/npc_dialogue"`（一行代码都不用改）。
代价：数据**不能**被存档数据包覆盖（R2 本来就已经接受了这一点）；反过来它可被**资源包**覆盖，对纯客户端 UI 而言反而更自然。
若将来确实需要数据包覆盖，则改为"服务端读取 + 登录下发"（先例：`TreasureSyncPayload` + `ClientTreasureCache`）。

同批次还修了两处（详见计划文档的审查记录）：**I1** 加载器改用 `ifError/ifSuccess` 严格判定
（`resultOrPartial` 会收下 DFU 的 *partial* 结果，导致坏页被静默丢弃、文件却仍注册）；
**I2** 补上配置分组自身的语言键 `beloong.configuration.npc_dialogue`（NeoForge 用
`modId + ".configuration." + key` 取分组标题，缺键会在配置界面显示原始键名）。

**R4 —— 数据驱动迁到 `data/`、改为服务端权威（2026-09-25，用户裁定）。**

> ⚠️ **本节之后，本文档的 D2 / D5 / D6 与 §九的 R0 / R2 已不再是最新口径。**
> 权威文档：**`docs/plans/2026-09-25-npc-dialogue-data-driven-design.md`**。

理由有两条，第二条是根因：

1. 数据应当住在数据包里（可被存档数据包覆盖、由服务端定义），而不是靠资源包覆盖。
2. **将来要接原版进度判据**控制对话进行到哪一步。判据的**写**（把对话推进记成进度）只能在服务端
   `PlayerAdvancements#award` 授予，**读**（按进度筛选该看到的内容）也应当在服务端完成 ⇒
   §1.2 那条"唯一选项是『离开』⇒ 对话**没有副作用** ⇒ 整条链路可以跑在客户端"的前提**被打破**。

处置（详见新文档 §4.2）：

| 首版决策 | 新口径 |
|---|---|
| D2 受理侧 = 客户端 | **服务端** |
| D5 数据位置 = `assets/` | **`data/beloong/beloong/npc_dialogue/`**（目录字符串 `"beloong/npc_dialogue"` **不变** —— 它是 PackType 相对的） |
| D6 读取侧 = 客户端 reload listener | **`AddReloadListenerEvent`**（服务端），并新增一个 `playToClient` 按需下发 |
| R2 "存档数据包覆盖对客户端不可见" | **不再是限制** —— 数据包覆盖现在生效 |
| R0（已解决）"客户端读不到 `data/`" | 结论仍正确，且正是本次换注册点的原因 |

**本次改动**另推翻了一处旧审查结论，勿按旧理由回改：
首版审查 **S3**（`NpcDialogueLoader.entries` 加 `volatile`）当时**不采纳**，理由是"`apply` 与 `get()`
都在客户端主线程，原版用主线程执行器跑 `apply`"。
搬迁后 `apply` 的线程换成了服务端主线程（`MinecraftServer` 作为 gameExecutor），
**但"两者同处一条主线程"这个性质没变，因此 S3 的结论依然成立：仍不加 `volatile`。**
（新文档 §十二 I-1 记录了这段经过 —— 我一度把它误判成"跨线程"，是靠独立代码审查纠正的。）

未变：UI 规格与调参结论（§3、§13.3）、文案仍走翻译键（`assets/.../lang`）、触发语义
（`empty_hand`/`any`）、失败隔离规则、零 mixin。

---

## 五、组件

| 组件 | 职责 | 关键点 |
|---|---|---|
| `NpcDialogueEntry` | record + `Codec`：`entity`（`ResourceLocation`）、`trigger`（枚举，缺省 `EMPTY_HAND`）、`name`（`Optional<String>`）、`pages`（非空 `List<Page>`）；嵌套 `Page(String text, Optional<ResourceLocation> sound)`、`enum Trigger { EMPTY_HAND, ANY }` | `entity` 的 `ResourceLocation → EntityType` 转换走 `comapFlatMap`，未知实体类型在**解析期**就报错（与 `StructureEffectEntry:21-25` 同款） |
| `NpcDialogueLoader` | 客户端 reload listener；产出 `Map<EntityType<?>, NpcDialogueEntry>`；提供 `get(EntityType<?>)` | 目录 `"beloong/npc_dialogue"`；单例 `INSTANCE`；用 `resultOrPartial(...)` **隔离坏文件**而不是抛异常 |
| `NpcDialogueScreen` | 状态机 + 渲染 | `tick()` 推进打字机；`mouseClicked` 走状态机；`renderBackground` 空实现；`isPauseScreen()` false |
| `NpcDialogueOptionButton` | 自绘按钮 + hover 插值 | 继承 `AbstractWidget`（白拿 hover / 点击 / 键盘导航），重写 `renderWidget`；宽度 = 文字宽 + 图标 + padding |
| `NpcDialogueHandler` | 右键入口（游戏总线） | `@SubscribeEvent` 方法；判客户端 + 空手 + 查表命中 → 调 `openDialogue` |
| `openDialogue(player, entity, entry)` | **接缝点**（§11） | v1 实现 = `Minecraft.getInstance().setScreen(new NpcDialogueScreen(...))`；将来替换成第三方 API 只改这里 |

---

## 六、数据流与错误处理

### 6.1 数据流

```
资源重载（启动 / F3+T）
  RegisterClientReloadListenersEvent → NpcDialogueLoader 解析
      data/beloong/beloong/npc_dialogue/*.json
      → Map<EntityType<?>, NpcDialogueEntry>

玩家空手右键铁傀儡
  PlayerInteractEvent.EntityInteract（客户端侧）
      → 空手？→ NpcDialogueLoader.get(IRON_GOLEM) 命中？
      → openDialogue(...) → new NpcDialogueScreen → setScreen
      → （屏幕内部纯本地状态机，不与服务端交互）
```

**没有任何服务端往返**。服务端对本功能完全无感（既不派发额外逻辑，也不收发包）。

### 6.2 错误处理与边界

| 情形 | 行为 | 依据 |
|---|---|---|
| JSON 解析失败 | `resultOrPartial` → `LOGGER.error` → **只丢弃该文件**，其余照常加载 | 沿用现有 4 个 loader 的范式；反面教材见 MCA Conversations 的 `DATAPACK.md`：严格枚举解析在遇到不认识的 id 时会把**整个数据包重载/世界创建搞崩** |
| `entity` 指向不存在的实体类型 | 解析期报错、丢弃该文件 | `comapFlatMap` + `DataResult.error` |
| `pages` 为空数组 | 丢弃该文件 + 报错 | 空对话没有意义；也避免"打开后立刻弹选项"这种困惑态 |
| 同一实体被多个文件绑定 | 排序后后处理者胜 + `warn` | D20 |
| 语言文件缺翻译键 | 屏幕上会显示**键名本身** | 这是**有意的可观测失败**：比"静默显示空白"好排查，故意不做额外兜底 |
| 玩家跑远 / 实体死亡 / 实体被卸载 | v1 **不处理** | 对话是纯客户端 UI、无副作用，玩家 ESC 即可。加距离校验要引入服务端状态，与 §1.2 相悖 |
| 玩家在对话中掉线 | 无需处理 | 无服务端状态 |
| 服务端 `/reload` | 对客户端无影响 | 数据在客户端侧加载 |
| 语言文件改动 | 游戏内 **F3+T** 重载资源包生效 | — |
| 存档数据包覆盖 `npc_dialogue/*.json` | **专用服务器上客户端看不到**（已知限制） | 见 R2 |

---

## 七、示例数据与语言文件

```jsonc
// src/main/resources/assets/beloong/beloong/npc_dialogue/iron_golem.json
{
  "entity": "minecraft:iron_golem",
  "trigger": "empty_hand",                      // 可选；缺省 empty_hand。可选值：empty_hand / any
  "name": "beloong.dialogue.iron_golem.name",   // 可选；缺省用实体显示名
  "pages": [
    { "text": "beloong.dialogue.iron_golem.p1", "sound": "beloong:dialogue.iron_golem.1" },
    { "text": "beloong.dialogue.iron_golem.p2" }
  ]
}
```

```jsonc
// assets/beloong/lang/zh_cn.json（en_us 同步加）
"beloong.dialogue.iron_golem.name": "§6铁傀儡",       // §6 = 金色；颜色由这里决定
"beloong.dialogue.iron_golem.p1":   "……第一页",
"beloong.dialogue.iron_golem.p2":   "……第二页\n第二行",  // 页内 \n 分行
"beloong.dialogue.option.leave":    "离开"
```

要点：

- `trigger` 缺省即 `empty_hand`；写 `any` 会让该实体**手持任何物品右键都触发**（含铁锭，会吃掉原版回血）；
- `§` 建议在语言文件里写作 **`\u00a7`**（避免文件编码问题）；
- 页数 = `pages` 长度，文案增删页**不需要改任何代码**；
- `sound` 可选、语义为"**每页开播一次**"（配音的天然粒度就是一句一音），v1 只解析不播放。

---

## 八、已核实的技术前提（附出处）

> 原版源码取自本机反编译产物：
> `C:\Users\D_Ink\.gradle\caches\neoformruntime\intermediate_results\decompile_4eaa4bb73e8ecf66b41d931147114443211226c1_output.jar`
> NeoForge 源码取自：`~\.gradle\caches\modules-2\...\neoforge-21.1.236-sources.jar`

| # | 结论 | 出处 |
|---|---|---|
| 8.1 | **`§` 颜色代码真的可用** | `StringDecomposer.java:90`（`c0 == 167` → `ChatFormatting.getByCode`）；关键在 `:128-129` 的 `FormattedText` 重载会**转调** String 版 ⇒ `Component.translatable(键)` 的**语言文件值里的 `§` 同样生效** |
| 8.2 | **默认背景会把世界糊掉** | `Screen.java:350-357`：`renderBackground` → `renderBlurredBackground`（`gameRenderer.processBlurEffect`）+ `renderMenuBackground`（`blit(INWORLD_MENU_BACKGROUND)`）。⇒ 必须覆写为空（D13） |
| 8.3 | 客户端可注册资源重载监听器，**但只能看到 `assets/`** | `neoforge-21.1.236-sources.jar` → `net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java`：`registerReloadListener(PreparableReloadListener)`，`implements IModBusEvent`，仅客户端、Minecraft 构造期。它交出的 `Minecraft.resourceManager` 是 `new ReloadableResourceManager(PackType.CLIENT_RESOURCES)`（`Minecraft.java:487`），而路径解析按 pack type 加前缀（`FallbackResourceManager:170` → `type.getDirectory()`）⇒ **`data/` 下的文件对此监听器不可见**（详见修订 R3） |
| 8.4 | 颜色插值有现成 API | `FastColor.java:80` `ARGB32.lerp(float, int, int)` |
| 8.5 | 文字切分 API 是**按宽度**的 | `Font.java:306` `substrByWidth(FormattedText, int)`、`:302` `plainSubstrByWidth(String, int)` ⇒ 打字机要自己按**可见字符数**切（D15 的要点） |
| 8.6 | 渐变可用 | `GuiGraphics.java:221` / `:225` `fillGradient(...)` |
| 8.7 | 暂停 / ESC 可覆写 | `Screen.java:391` `isPauseScreen()`（默认 true）、`:199` `shouldCloseOnEsc()`（默认 true） |
| 8.8 | **原版没有四向描边** | `GuiGraphics.java:266-317` 全部 `drawString` 重载只有 `boolean`（投影）参数；`:328` 另有一个 `drawStringWithBackdrop`（带底衬，不是描边）。⇒ 用户说的"黑色描边"实际是**投影**；真描边需自己叠 4 次黑字，随时可加 |
| 8.9 | 原版 `minecraft:dialog` 注册表在 1.21.1 **不可用** | 该注册表 1.21.6 才引入（minecraft.wiki 历史表）；本机 `D:\Minecraft\开源模组参考文件\VanillaBackport` 全库大小写不敏感搜 `dialog` **零命中**，其 `gradle.properties` 锁 `minecraft_version = 1.21.1` |
| 8.10 | 右键实体事件两侧都会派发 | 项目内先例：`compat/ftbchunks/LoongPalaceProtectionHandler` 同时处理 `onEntityInteract` 与 `onEntityInteractSpecific`（`:111-124`） |

---

## 九、风险与已知取舍

| # | 风险 / 取舍 | 说明与对策 |
|---|---|---|
| ~~R0~~ | ~~客户端能否读到模组 jar 的 `data/`~~ | **已解决（2026-09-20，且结论与原假设相反）**：客户端重载管理器是 `CLIENT_RESOURCES`，**读不到 `data/`**，因此数据改放 `assets/`（修订 R3）。运行时观测点：loader 现在同时打印"扫描到的文件数"，恒为 0 即说明目录放错树 |
| **R1** | 名字 `1.5x` 缩放会**笔画参差** | MC 字体是位图、采样为最近邻（`RenderType.text` 的纹理状态 `blur=false`），非整数倍缩放会出现"有的笔画 1px、有的 2px"。1.5x 是"大小接近原神"与"观感"的折中；`nameScale` 可设 `1.0`（最干净）或 `2.0`（完全清晰但偏大）。**实机看过再定** |
| **R2** | 存档数据包覆盖对客户端不可见 | 专用服务器上，世界数据包不发给客户端 ⇒ 在 `saves/<world>/datapacks/` 里改对话客户端看不到。整合包场景无影响（jar 随包分发）。若将来需要，加同步包即可 |
| **R3** | 选项宽度自适应 + 左缘对齐 ⇒ **右边缘参差** | 这是参考图的原样（Genshin 就是参差的），非缺陷。若观感不适，改成固定宽度即可 |
| ~~R4~~ | ~~装饰线位置的口径~~ | **已解决（2026-09-20）**：放大裁切重测确认装饰线位于**名字与正文之间**（横贯、两端菱形端饰）。初版"与名字同高、左右分列、外端渐隐"的读法是错的 → 见 §4.2 修订 R2 |
| **R5** | 所有间距都是**起始值** | §3.1 的数值取自单张截图，必然需要试玩微调。已按"配置/常量集中"的方式组织，便于调参 |
| **R6** | 名字缺省用 `entity.getDisplayName()` | 该名字会随实体自定义名变化（玩家用命名牌改过的铁傀儡会显示自定义名）。对"整合包 NPC"而言这通常是**期望行为**；若不想要，JSON 里显式写 `name` 键即可 |

---

## 十、测试策略

### 一级（实现方执行，可靠）

1. `.\gradlew.bat build` 通过。
2. `build/resources/main/assets/beloong/beloong/npc_dialogue/iron_golem.json` 确实被打进产物 jar。
3. **目录树验证**：客户端资源重载后，loader 的日志能报出**扫描到的文件数 ≥ 1**（该数恒为 0 即说明数据放错了树，见修订 R3）。
4. 静态确认：`NpcDialogueScreen` 覆写了 `renderBackground`（否则世界会被糊掉）、`isPauseScreen()` 返回 `false`。
5. 静态确认：`beloong.mixins.json` **未被修改**。

### 二级（需实机）

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 空手右键铁傀儡 | 弹出对话屏；**世界清晰不模糊**，底部约 38% 渐变压暗（起点 0.62 屏高） |
| 2 | 观察第一页 | 名字金色居中且大于正文；**名字与正文之间**一条横贯金线、两端菱形端饰；正文**纯白**居中、逐字出现（1 字/tick） |
| 3 | 打字中点击 | 当前页**立刻全部显示** |
| 4 | **中间页**显示完后 | 底部中央出现金色箭头（菱形外框 + 内三角），轻微浮动 |
| 5 | 再点击 | 进入下一页；箭头消失、重新逐字 |
| 6 | **最后一页**显示完 | **自动弹出选项**（无需再点）；箭头不出现 |
| 7 | 鼠标移到「离开」上 | 底衬从深蓝黑**平滑**过渡到暖金（约 200ms，非瞬变） |
| 8 | 点击「离开」/ 任意时刻 ESC | 对话关闭，回到游戏 |
| 9 | 手持铁锭右键铁傀儡（缺省 `empty_hand`） | **不触发对话**，走原版修血 |
| 10 | 把示例数据改成 `"trigger": "any"` 后 F3+T | 手持铁锭也能弹出对话（原版回血被吃掉，属预期） |
| 11 | 对未配置的实体（如牛）空手右键 | 无任何反应 |
| 12 | 往 `iron_golem.json` 加第 3 页、改语言文件、F3+T | 无需改代码即多出一页 |
| 13 | 把语言文件里的键改错 | 屏幕上显示键名本身（**可见失败**，符合设计） |
| 14 | 多人（联机）打开对话 | 世界不停顿（`isPauseScreen=false`），其他玩家不受影响 |

---

## 十一、后续

1. **接缝点已就位**：所有打开对话的路径都收敛到 `openDialogue(player, entity, entry)`。将来接第三方对话框模组时，只把该方法的实现换成第三方 API，其余（触发 + 映射 + 数据）不动。
   > 这正是调研里 Taterzens ↔ Blabber 的"NPC 模组 + 独立对话库"适配方式：本模组将来扮演 **NPC 模组**那一侧。
2. **一键让位**：`[npc_dialogue] enabled`（D19）用于在第三方模组自带实体绑定时关闭本功能，避免右键冲突。
3. 可能的扩展（按需，均**不改现有 JSON 结构**）：
   - 多实体：加 JSON 文件即可（格式已支持）；
   - 实例级区分（特定 NBT / 自定义名的实体说不同的话）：加匹配谓词；
   - 条件与动作：需引入服务端判定 + 网络包（见 §1.3 的说明）；
   - `sound` 实装播放；
   - 头像 / 立绘：需要资源包，是**架构分流点**而非加字段。

---

## 十二、来源

### 12.1 参考图（用户提供）

| 文件 | 用途 |
|---|---|
| `参考1.png` | 首版布局：居中名字/正文、右侧选项胶囊、底部渐变 |
| `参考2.png` | 同上的第二个样本（交叉验证） |
| `参考3.png` | **继续箭头**（距底 3.4%、金色、居中） |
| `参考4.png` | **悬停态**（背景转金）、选项在正文上方 |
| `参考5.png` | **1920×1080 原生截图**，本文档所有量测的主口径：装饰线、名字/正文比例、选项左缘对齐 |

### 12.2 原版 / NeoForge 源码

见 §8 表格中逐条的 `文件:行号`，以及两处 jar 路径。

### 12.3 同类模组调研（立项前的方案比较）

| 模组 | 平台 | 许可 | 借鉴点 |
|---|---|---|---|
| **ADM**（Aviel's Dialogue Mischiefs） | 1.21.1 / **NeoForge** | Apache-2.0 | 同平台同架构的最直接参考：`data/<ns>/adm_dialogues/*.json`、`/npc validate` 的 ERROR/WARN 分级、按语言覆盖文本。**当参考不当依赖**（很新） |
| **RPG Dialogue** | 1.21.1 / Fabric+NeoForge | ARR（闭源） | "实体类型 或 `#tag` + priority + requires" 的绑定模型；`holding` 反条件保住原版交互的写法；**从不把条件不成立的选项发给客户端** |
| **Blabber** | Fabric 独占 | LGPL-3.0 | 架构参考：条件用**原版数据包谓词**而非自造 DSL；状态机放服务端、只推 id 与可用性 |
| **Easy NPC** | 含 1.21.1 | MIT | 优先级阶梯常量；LOCK vs HIDE 的 UX 取舍；**"关闭按钮关掉又没别的出口 ⇒ 玩家被卡死"** 的教训 |
| **MCA Reborn / MCA: Conversations** | 1.21.1 / 1.20.1 | GPL-3.0 | `DATAPACK.md` 的失败模式清单（严格解析炸重载、动作键顺序静默覆盖、最后一项是隐式兜底）；`mca` 命名空间=覆盖、其他=合并 的覆写语义 |
| **BRNTalk** | 1.20.1 / 1.21.1 | MIT | 加载期严格校验（悬空 `nextId` 直接拒绝整个脚本）并在 `/reload` 后给在线 OP 推摘要 |
| **VNDialog** | 含 1.21.1 | MIT | 反例：用"命令返回值必须为 1"做条件 —— 未校验、隐式、且以 OP 执行 |
| **原版 `minecraft:dialog`（1.21.6+）** | — | — | 字段命名对齐的长期目标（`type`/`title`/`body`/`actions`/`label`/`action`），将来升级可直接迁移 |

---

## 十三、收尾（实现后回填）

> 本节在实现完成后填写，格式对齐 `2026-09-18-dead-king-advancement-triggers-design.md` 的第十节。

### 13.1 实机验证

**结论：符合要求**（2026-09-21）。共三轮实机反馈 + 一轮代码审查。

| 轮次 | 用户反馈 | 根因与处置 |
|---|---|---|
| 一（00:06） | ①第一段文字变灰；②正文偏上、与分割线太近；③第二页两行压到分割线、且"有的白有的灰"；④最后一页要再点一下才出选项 | ①③的**颜色**是占位文案自带 `§7`/`§r`（**不是渲染问题**，反而证明 `§` 生效）；③的**位置**是按"区块中轴"对齐导致多行**向上生长**；④改为末页播完直接弹选项（`advanceOrFinish()`） |
| 二（00:24） | ①底部遮罩太淡；②选项圆角太大；③选项太高、太短 | ①渐变起点 0.72→0.62、末端 alpha 0xC0→0xE6；②③重做底衬：固定 0.22 屏宽 / 高 20 / 左半圆角 / 右侧渐隐（逐列 `fill`） |
| 三（13:16） | 基本符合要求 | 收尾 |

**反向确认**（§10 二级清单中除上述偏差外的各项均通过）：配置分组在模组菜单可见且显示中文名；空手右键触发；**世界不模糊**（`renderBackground` 覆写生效）；名字金色居中且大于正文；装饰线位于名字与正文之间、两端菱形端饰；继续箭头只在中间页出现并轻微浮动；悬停金色过渡平滑（非瞬变）；ESC 与「离开」均可关闭；手持铁锭**不**触发（缺省 `empty_hand`）。

### 13.2 实现后审查

批次一（T1–T4）后做过一次独立代码审查：**1 Critical + 2 Important + 3 Suggestion**，全部处置完毕（明细见计划文档 §四）。其中 **C1 推翻了本设计文档初版的路径假设**（见修订 R3）—— 若不纠正，症状会是"右键无反应且不报任何错"，属最难排查的一类。S3（`entries` 加 `volatile`）经复核**不采纳**：`apply` 与 `get()` 都在客户端主线程。

### 13.3 实机调参记录（起始值 → 最终取值）

| 常量 | 起始值（参考图量得） | 最终值 | 变化原因 |
|---|---|---|---|
| `NAME_Y` | 0.796 | **0.790** | 与装饰线拉开距离 |
| `RULE_Y` | 0.831 | **0.822** | 同上 |
| 正文锚点 | 区块中轴 `0.858` | **首行顶部 `0.860`** | 多行文字原先向上生长会压线 |
| `GRADIENT_START` | 0.72 | **0.62** | 实机反馈"几乎看不清" |
| `GRADIENT_BOTTOM` | `0xC0000000` | **`0xE6000000`** | 同上 |
| `OPTION_HEIGHT` | 26 | **20** | 参考图实为 ≈20 GUI px |
| `OPTION_WIDTH` | 按文字自适应 | **固定 `0.22` 屏宽** | 参考图是等长底衬（**右侧渐隐**导致初版误读成"自适应"） |
| 选项底衬形状 | 整圆胶囊 | **左半圆角 + 右侧渐隐** | 参考图右侧无硬边 |
| `OPTION_GAP` | 22 | **8** | 配合高度 20 |

**仍未定/待观察**（不影响验收）：

- 名字 `nameScale` 默认 1.5 —— 位图字体非整数缩放有轻微参差，配置可退回 `1.0`；
- 选项可点击区域含渐隐的右段（鼠标移到看不见的右侧也会高亮）；如需收紧，裁到可见段即可；
- `iron_golem` 的示例文案是**占位**，待替换为正式内容（改语言文件后 F3+T 即生效）；
- `sound` 字段只解析不播放（设计 D18），实装待有音频资源。
