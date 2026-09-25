# NPC 对话界面（客户端）代码审查

> 审查范围：`src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java`（460 行）、
> `src/main/java/com/zonlong/beloong/client/NpcDialogueOptionButton.java`（147 行）；
> 只读引用上游契约 `dialogue/NpcDialogueOpenPayload.java`、`dialogue/NpcDialogueEntry.java`
> 审查基线：`24bb701`（工作树干净，未运行构建，未运行游戏）
> 审查方式：只读静态审查 + 原版源码核实（1.21.1 NeoForge patched sources，
> `~/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_*.jar`）。
> 下文凡写 `Xxx.java:NN` 而未写路径的，均指该 jar 内的 `net/minecraft/...` 或 `net/neoforged/...` 原版/NeoForge 源码。

## 结论

整体质量**良好偏上**：状态机没有死角（空页 / 单页 / 末页 / 连点 / 越界都推演过，全部安全），
布局常量集中、注释用中文且解释"为什么"，`§` 颜色代码的换行测宽与逐字揭示**与原版
`StringDecomposer.iterateFormatted` 的语义一致**（这是最容易写错的一处，写对了），
`renderBackground` 留空、`isPauseScreen() == false`、按钮继承 `AbstractWidget` 的用法都经原版核实为正确。
**未发现 Critical 级问题**（无崩溃、无越界、无连接中止路径）。

最值得修的 3 件事：

1. **M1（无界输入 × O(n²) 排版）** —— `wrap()` 对每个字符都做一次"整行重新测宽"，而 `pages` 的
   线格式没有长度上限（单页最多 32767 字节，页数不限）。长页会让客户端主线程停顿数秒到数十秒。
   这是本次最接近"严重"的一条，修法是一行 clamp + 一次算法改写。
2. **M2（正文没有纵向适配）** —— 正文从 `0.860·h` 往下长，**不裁剪也不换锚点**；GUI scale 4
   （480×270）下第 3 行就压住"继续"箭头，第 4 行直接跑出屏幕底部。
3. **M4/M6（状态机与文档三处同错）** —— 类顶部 javadoc、`docs/NPC系统总设计.md` §5.5、
   `docs/plans/2026-09-20-npc-dialogue-design.md` §3.4 都写"末页打字完 → WAIT_CLICK → 点击才弹选项"，
   而实现是**末页打完直接弹选项**（`advanceOrFinish()`），`WAIT_CLICK` 永远不会出现在末页。
   同一文件第 206 行的注释是对的、第 30-31 行是错的 —— 说明是改实现后漏改注释。

## 严重问题（Critical）

**未发现。** 逐项排查了以下可能的严重路径，均有防御或不可能触发：

- `pages` 为空 → `open()` 在 `:135-137` 提前返回，`loadPage()` 的 `pages.get(0)` 不会越界；
- `pageIndex` 越界 → `WAIT_CLICK` 只在 `pageIndex + 1 < pages.size()` 时进入（`:226-232`），
  故 `:207` 的 `pageIndex++` 恒 ≤ `size - 1`；
- 渲染期添加控件（`tick()` / `mouseClicked` 里 `showOptions()` → `addRenderableWidget`）→ 这两处
  都**不在** `children()` 的迭代过程中（`tick()` 的两个分支是 `if/else if`），不会 CME；
- 服务端下发内容的解码 → `handleClient` 在渲染线程执行（已核实，见"已验证"§11），`getString()` /
  `Component.translatable` 对畸形格式串走原版 catch 分支（`TranslatableContents.java:101-122`），不抛；
- 玩家死亡 / 实体卸载 → 不崩（详见 Minor m11 与"已验证"§14）。

## 重要问题（Major）

### M1. `pages` 无长度上限 + `wrap()` 是 O(n²) ⇒ 单页过长会卡死客户端 — `NpcDialogueScreen.java:365-389,166-172`

- **现象/代码**：`wrap()` 逐个"原子单元"累加，每加一个字都重新测整行宽度：
  ```java
  if (!isCode && current.length() > 0
          && this.font.width(Component.literal(current + unit)) > maxWidth) { ... }
  ```
  第 n 个字符要测 n 个字符的宽度（`current + unit` 还额外产生一次字符串拼接），整页成本 ~O(n²)；
  而 `font.width(Component)` 走 `Language.getVisualOrder`（每次都是新 `Component` 实例 ⇒ 无缓存）。
- **证据**：本文件 `:365-389`、`:166-172`；
  `Font.java:292-302`（`width` → `splitter.stringWidth`）、`StringSplitter.java:42-49`（`iterateFormatted`
  全串遍历）、`MutableComponent.java:100-109`（`visualOrderText` 缓存是**实例级**的）；
  上游无上限：`NpcDialogueOpenPayload.java:64` 用 `ByteBufCodecs.list()`，而
  `ByteBufCodecs.java:351-353` 的 `collection(factory, codec)` 默认 `Integer.MAX_VALUE`，
  页内字符串上限 32767 字节（`ByteBufCodecs.java:135`）。
- **影响**：一个 ~3 万字的中文页会在**主线程**（`Screen.tick` / `Minecraft.setScreen` → `init()`）
  上做约 5×10⁸ 次字符级操作并分配约 3 万个 `Component`，表现为进页瞬间数秒~数十秒卡死；
  不崩、不报错、无法 ESC（线程被占）。触发条件：数据包里写了一页很长的文本（整合包作者很可能这么干），
  或（理论上）被改过的服务端/中间人下发一页 32KB 文本。当前随模组分发的 `p1/p2` 只有 21/32 字，**不会**触发。
- **建议修法**：两处都要做。
  1. `wrap()` 改成**增量测宽**：用 `font.getSplitter().stringWidth(unit)` / `font.width(unit)` 逐单元累加，
     只在超宽时回退（或直接换用 `font.getSplitter().splitLines(raw, maxWidth, Style.EMPTY)`，
     顺带解决 M3）；
  2. `loadPage()` 开头对 `raw` 做硬上限，例如 `raw = raw.length() > 512 ? raw.substring(0, 512) + "…" : raw;`
     （阈值取 512 已远超 3 行容量，见 M2）；
  3. 顺手把线格式收紧：`NpcDialogueEntry.Page.STREAM_CODEC.apply(ByteBufCodecs.list(64))`
     —— 与 `NpcDialogueOpenPayload` "线载荷永不抛、永不失控"的设计意图一致。

### M2. 正文没有纵向适配：多行会压住"继续"箭头并溢出屏幕底部 — `NpcDialogueScreen.java:55,57,328-343`

- **现象/代码**：`TEXT_TOP = 0.860F`、`ARROW_Y = 0.964F`，`renderBody()` 从 `(int)(height*TEXT_TOP)`
  起每行 `+= font.lineHeight + LINE_GAP`（= 11 px），**没有任何 clamp / 裁剪 / 行数上限**。
- **证据**：本文件 `:55,:57,:74,:328-343`。y 向下增大 ⇒ `0.860·h`（正文首行）在 `0.787·h`（选项底边）**下方**，
  与选项不冲突（这一点是对的），但**必然**与 `0.964·h` 的箭头冲突：
  - h=270（1920×1080 配 GUI scale 4）：正文首行 y≈232，行距 11 ⇒ 三行 y≈232/243/254；
    箭头外框占 `0.964·270 ± (7+2) ≈ 251~269` ⇒ **第 3 行压在箭头上**；第 4 行 y≈265 已在 270 屏高之外。
  - 每行容量 ≈ `0.80·480 / 9 ≈ 42` 个汉字（CJK 在原版字体里是 9 px 宽）⇒ **约 85 字**（2 行）以下才安全。
  - h=360（GUI scale 3）：箭头盒 ≈ 338~356，第 4 行 y≈343 起压箭头，第 6 行出屏（可容 ≈168 字）。
- **影响**：长页出现"文字与箭头叠字"，更长则正文被切在屏幕外（内容静默丢失，玩家看不到；
  `drawCenteredString` 不会截断，只是画到视口外）。当前示例数据（21/32 字）刚好安全，属**潜在**缺陷。
- **建议修法**：三选一（推荐第 1 个）：① `renderBody()` 计算 `maxLines = (arrowTop - TEXT_TOP*y) / lineHeight`，
  超出部分不画并在末行追加省略号；② 行数超限时把起始 y 上移（`TEXT_TOP` 改成"多行区块底部锚定"，
  但注意类注释 `:48-54` 明确说过向上生长会压到装饰线，需要同时抬高 `RULE_Y`/`NAME_Y`）；
  ③ 在 `loadPage()` 里按行数断言并给日志告警，先保住"不静默丢内容"。无论哪种，都应留一条
  "GUI scale 4 / 640×360 / 超宽屏"的实机验收项。

### M3. `§` 颜色代码在**自动换行的续行**上失效（每行独立解析） — `NpcDialogueScreen.java:365-389 + 339`

- **现象/代码**：`wrap()` 按宽度切行时只搬字符、**不重发当前生效的格式码**；`renderBody()` 又把每行
  当成一个独立 `Component.literal(line)` 画。
- **证据**：原版的 `§` 是有状态的，但状态只活在一次 `iterateFormatted` 调用里
  （`StringDecomposer.java:100-142`：`Style style = currentStyle` 从 `Style.EMPTY` 起步，`:106-117` 逐码 `applyLegacyFormat`）；
  `Component.literal(x).getVisualOrderText()` 每次都以 `Style.EMPTY` 重新开始
  （`MutableComponent.java:100-109` → `Language.java:62-71` / `ClientLanguage.java:90-93`）。
  因此"第一行开头 `§c` 红字、无 `§r`"时，第 2 行会退回白色。原版自己的换行（`Font.split` →
  `StringSplitter.splitLines`，`:150,:202,:258`）会把当前样式带进下一行，本实现没有。
- **影响**：任何"一段带颜色的长文本跨行"都会出现**前一行有色、续行无色**。短页（不换行）看不出来，
  所以容易漏测。`§l`/`§o` 等状态码同理。
- **建议修法**：`wrap()` 在每次 `out.add(current.toString())` 前记录当前生效的格式码前缀，
  新行以该前缀开头（用一个 `StringBuilder codes` 维护 `§x` 栈，遇 `§r` 清空）。
  或者直接用 `font.getSplitter().splitLines(Component.translatable(key), maxWidth, Style.EMPTY)`
  —— 它返回的每行已带正确样式，同时天然修掉 M1 的性能问题（但会改变"按可见字符数计数"的接口，
  需把 `revealed` 改成按行内 codepoint 计数，见 m3/m4 的注意点）。

### M4. 状态机注释与实现不符（且两份设计文档同错） — `NpcDialogueScreen.java:26-34` vs `:226-232`

- **现象/代码**：类 javadoc 写
  `TYPING ... 显示完 ⇒ WAIT_CLICK`、`WAIT_CLICK --点击且末页--> SHOWING_OPTIONS`；
  实现是 `advanceOrFinish()`：**末页打完直接 `showOptions()`**，只有中间页才进 `WAIT_CLICK`。
- **证据**：本文件 `:26-34`（错）与 `:206`（对，注释"只有中间页会进入这里（最后一页在 advanceOrFinish 里直接弹选项）"）、
  `:219-232`（对）。文档同错：`docs/NPC系统总设计.md:587-588`、
  `docs/plans/2026-09-20-npc-dialogue-design.md:141-146`。而 `:219-225` 的注释明确写了
  "实机反馈：文字播完还要再点一次才出选项是多余的" ⇒ 是**改了实现没改注释/文档**。
- **影响**：维护者照文档推演会以为"末页一定会先显示箭头、等一次点击"，据此改 `mouseClicked` 的
  `WAIT_CLICK` 分支或 `renderArrow()` 的显示条件时极易引入 bug（例如给末页补一个 `WAIT_CLICK` 状态，
  或在 `advanceOrFinish` 里加 `showOptions()` 的重复调用）。文档自称"以源码为准"，这类失真要优先清除。
- **建议修法**：改三处文字（不动代码）：类 javadoc 改成
  `TYPING --显示完(中间页)--> WAIT_CLICK` / `TYPING --显示完(末页)--> SHOWING_OPTIONS`；
  `NPC系统总设计.md` §5.5 表格的 `TYPING`/`WAIT_CLICK` 出口列按此重写；`plans/...-design.md` §3.4 加一行
  "后续修订：末页直接弹选项（见实现 `advanceOrFinish`）"。

### M5. 窗口尺寸变化（F11 / 改 GUI 缩放）会重启当前页的打印机 — `NpcDialogueScreen.java:156-163,166-172`

- **现象/代码**：`init()` 在非 `SHOWING_OPTIONS` 时无条件调用 `loadPage()`，而 `loadPage()` 会
  `revealed = 0; state = TYPING;`。
- **证据**：原版窗口缩放链 `Minecraft.java:1327-1334`（`resizeDisplay()` → `screen.resize(...)`）→
  `Screen.java:463-467`（`resize` → `repositionElements()`）→ `Screen.java:459-461` →
  `Screen.java:350-358`（`rebuildWidgets()`：`clearWidgets()` → `init()`）。
  即"按 F11 或改 GUI scale"**必然**调用本类的 `init()`。
- **影响**：① 正打到一半 → 从头再打一遍；② 已经打到 `WAIT_CLICK`（在等点击）→ 退回 `TYPING`，
  玩家刚看完整页却要重新等一遍（`charsPerTick=1` 时几十秒）；③ 窗口每次拖拽都会重置。观感缺陷，可稳定复现。
- **建议修法**：把"初始化一页"和"按新宽度重排"分开。最小改法：加 `private boolean loaded;`，
  `init()` 里非 `SHOWING_OPTIONS` 分支改成 `if (!loaded) loadPage();`，`loadPage()` 末尾置 `loaded = true`
  （打字进度完全保留）。更完整的做法是再加一个 `rewrap()`：按新 `width` 重新 `wrap()` 当前页，
  并把 `revealed` 按 `revealed / visibleTotal` 的比例映射到新的 `visibleTotal` 上
  —— 既保住进度，又不丢"跨 GUI 缩放自适应"。

### M6. `docs/NPC系统总设计.md` §5.5 另有两处与源码不符 — 文档 `:598`、`:618-619`

- **现象/代码**：
  1. 文档把 `TEXT_TOP = 0.860` 描述为"正文起始（**自下而上**排版）"。源码 `:48-54` 的注释恰恰相反：
     "刻意锚定'顶部'而不是'区块中轴'：多行正文若按中轴对齐会**向上生长** …… 锚定顶部后，
     无论几行都**只往下长**"。**值对，方向说反了**。
  2. 文档说 `GOLD`/`GOLD_BRIGHT`"（选项按钮悬停时在两档金色间插值）"。实际悬停插值用的是
     `NpcDialogueOptionButton.NORMAL_RGB = 0x1A1F26`（深蓝黑）→ `HOVER_RGB = 0xC8A05A`（暖金）
     （`NpcDialogueOptionButton.java:28-30,89-90`）；`GOLD_BRIGHT` 只用于箭头内部三角
     （`NpcDialogueScreen.java:354`），`GOLD` 用于装饰线与箭头外框。
  3. （同一节的 `renderBackground` 理由不完整：只提 `renderBlurredBackground`，漏了
     `renderMenuBackground` 与 NeoForge `ScreenEvent.BackgroundRendered`，后者见 m1。）
- **证据**：`docs/NPC系统总设计.md:591-619` 对照源码 `:45-89`；另：文档同节列的
  `NAME_Y/RULE_Y/RULE_HALF_WIDTH/TEXT_TOP/ARROW_Y/OPTION_LEFT/OPTION_WIDTH/OPTION_BOTTOM/OPTION_HEIGHT/OPTION_GAP/
  TEXT_MAX_WIDTH/LINE_GAP/GRADIENT_START/GRADIENT_BOTTOM` **数值全部与源码一致**（见"已验证"§18），
  行数声明"460 行 / 147 行"也一致。
- **影响**：维护者按"自下而上"去调 `TEXT_TOP` 会往反方向改（以为调大是往上移）；按"两档金色插值"
  去改 `GOLD`/`GOLD_BRIGHT` 会发现选项按钮毫无变化，白调试一轮。
- **建议修法**：文档改成"正文首行**顶部**锚定，多行只向下生长"；颜色一行改成
  "`GOLD`/`GOLD_BRIGHT`：装饰线与箭头用；选项悬停底色由 `NORMAL_RGB`→`HOVER_RGB` 插值，见按钮类"。

## 次要问题（Minor）

### m1. 覆写 `renderBackground` 为空会漏掉 NeoForge 的 `ScreenEvent.BackgroundRendered` — `NpcDialogueScreen.java:261-264`
- **证据**：默认实现除了 `renderBlurredBackground` / `renderMenuBackground`，末尾还有
  `Screen.java:384` 的 `NeoForge.EVENT_BUS.post(new ScreenEvent.BackgroundRendered(...))`；
  另外 `Screen.java:378-380` 在 `minecraft.level == null` 时会画全景图（本屏只在世界内打开，不受影响）。
- **影响**：装在这个整合包里、监听该事件的第三方模组（部分小地图/界面美化模组用它做每屏背景后处理）
  在本屏不会收到回调 —— 不崩，只是"某些模组在本界面表现不一致"。
- **建议修法**：保持不模糊/不盖暗底的前提下补发事件：
  `NeoForge.EVENT_BUS.post(new ScreenEvent.BackgroundRendered(this, guiGraphics));`（需 import）。
  若判定不需要，请在 `:252-260` 的注释里显式写明"刻意不发该事件"，避免下一个人当成漏写。

### m2. 每帧新建 `Component` 并重算 visual order — `NpcDialogueScreen.java:333-342`
- **证据**：`Component.literal(shown)` 每帧、每行新建一个 `MutableComponent`，其
  `visualOrderText` 缓存是实例字段（`MutableComponent.java:100-109`）⇒ 每帧重新走一遍
  `Language.getVisualOrder` / 字符串分解；`prefixByVisibleChars()`（`:411-426`）在打字期间每帧
  还新建一个 `StringBuilder`+`String`。当前行数（1~3 行）下开销可忽略，属"热点上的分配习惯"问题。
- **建议修法**：按行缓存 `String`；揭示改用原版的跳过语义
  —— `StringDecomposer.iterateFormatted(text, skip, Style.EMPTY, sink)` 是最贴合"跳过 n 个字符再画"的原版入口
  （`StringDecomposer.java:88-100` 已有 `skip` 参数），可一次性去掉自写的
  `countVisible`/`prefixByVisibleChars` 与每帧 `Component` 分配（**但注意** `skip` 数的是 UTF-16 位置而非可见字符数，
  迁移时要和 m3/m4 一起处理）。

### m3. 尾部孤立的 `§` 计数与渲染不一致（差 1） — `NpcDialogueScreen.java:392-403,411-426`
- **证据**：原版遇到末尾孤立的 `§` 是 `break`（`StringDecomposer.java:106-109`，该字符**不渲染**）；
  本实现的 `countVisible()` / `prefixByVisibleChars()` 在 `i + 1 < length` 不成立时把它**当成 1 个可见字符**
  （`:395-400`、`:416-423`）。
- **影响**：`visibleTotal` 比实际可显示的多 1 ⇒ 该页打字多耗 1 个 tick 份额、进度条式的"打完即推进"
  晚一帧/一 tick（无可见错误，因为原版不画这个 `§`）。属健壮性/一致性问题。
- **建议修法**：两处判据改成"`§` 结尾 → 不计入可见数（丢弃）"，与原版对齐。

### m4. 代理对（emoji/增补平面字符）按 UTF-16 char 计数，可能被切成半个 — `NpcDialogueScreen.java:365-389,392-426`
- **证据**：原版对 high surrogate 才做"成对合并/否则替换为 U+FFFD"处理（`StringDecomposer.java:12-14,118-135`）。
  本实现按 `charAt(i)` 逐单元推进，`§` 之外的字符一律计 1，可能把 high/low surrogate 分到两行或两次揭示之中。
- **影响**：打字机揭到一半时短暂渲染出替换字符（U+FFFD）"�"；换行测宽也可能把一对代理对拆到两行。
  当前中文文案不触发，属边界健壮性。
- **建议修法**：`wrap()`/`countVisible()`/`prefixByVisibleChars()` 的单元判定加
  `Character.isHighSurrogate(c) && i+1 < len && Character.isLowSurrogate(next)` → 取两个 char 作一个单元。

### m5. 选项文案没有宽度适配或截断 — `NpcDialogueScreen.java:238-242` + `NpcDialogueOptionButton.java:72-75`
- **证据**：按钮宽度恒为 `0.22·width` 屏宽（`:61,:238`），而绘制时文字从
  `x + PADDING_LEFT + ICON_SIZE + ICON_GAP`（= 左边 21 px）开始无裁剪地画（按钮 `:69-75`），
  底衬在 55% 处开始淡出（按钮 `:39,111-118`）。
- **影响**：英文/其他语言把 `beloong.dialogue.option.leave` 译长（>（0.22·w − 21)px，GUI scale 4 下约 84 px）
  时，文字会画到渐隐区之外，观感是"文字飘在透明处"。其余语言版本目前只有 "Leave"（28 px），安全。
- **建议修法**：用 `AbstractWidget.renderScrollingString(...)`（`AbstractWidget.java:101-132`，现成能力）
  或对 `font.width(label) > availWidth` 时截断加省略号。

### m6. `OPTION_GAP` 声明但从未使用；`showOptions()` 是"硬编码 1 个按钮"而非 N 个 — `NpcDialogueScreen.java:69,235-243`
- **证据**：全仓仅 `:69` 一处出现 `OPTION_GAP`（grep 9 处命中已核对）；`showOptions()` 只有一次
  `addRenderableWidget(..., bottom - OPTION_HEIGHT, ...)`，没有按序号上排的循环。
- **影响**：不影响当前功能。风险是将来加第二个选项时，若照抄这一行会把两个按钮**完全重叠**在
  同一位置（不是"堆叠错位"而是"完全重合"，很难第一眼看出）。
- **建议修法**：要么删掉 `OPTION_GAP`（等真要用时再加），要么现在就把 `showOptions()` 写成
  `for (int i = 0; i < labels.size(); i++) { int y = bottom - (i + 1) * (OPTION_HEIGHT + OPTION_GAP) + OPTION_GAP; ... }`
  的形式，并回答"选项多于 5 个时往上会不会撞到正文"（正文在 `0.860·h`，选项上界是
  `OPTION_BOTTOM·h`，只有 ~0.073·h 的可用高度 ≈ GUI scale 4 下的 20 px ⇒ **只够 1 颗**，
  这才是"多选项"改造时真正的约束，建议写进类注释）。

### m7. `onClick(double,double)` 覆写的是 NeoForge 已标 `@Deprecated` 的重载 — `NpcDialogueOptionButton.java:120-123`
- **证据**：`AbstractWidget.java:134-139` 在 2 参 `onClick` 上写了
  `@Deprecated Neo: Use onClick(double, double, int) instead`；NeoForge 的
  `IAbstractWidgetExtension.java:35-37` 提供 3 参默认实现并委托给 2 参；
  `AbstractWidget.mouseClicked`（`:157-165`）调用的是 **3 参**版本。
- **影响**：当前行为**正确**（默认实现会转发到 2 参），但编译器会给出"覆写了已废弃方法"的警告，
  且将来 NeoForge 若移除转发就会静默失效（点了没反应）。
- **建议修法**：改成 `public void onClick(double mouseX, double mouseY, int button) { this.onPress.run(); }`。

### m8. `updateWidgetNarration` 只播 TITLE，丢掉了按钮用法提示 — `NpcDialogueOptionButton.java:126-128`
- **证据**：原版 `AbstractWidget.updateWidgetNarration` 是抽象方法（`AbstractWidget.java:352`），
  配套的 `defaultButtonNarrationText`（`:354-363`）会加 TITLE + USAGE
  （`narration.button.usage.focused/hovered`），且 TITLE 用 `createNarrationMessage()`
  =`gui.narrate.button` 的"按钮：xxx"包装（`:91-97`）。本实现用的是裸 `getMessage()`。
- **影响**：屏幕阅读器只会念出"离开"，不会提示"这是一个按钮、按回车激活"。无障碍体验降级。
- **建议修法**：`updateWidgetNarration(o) { defaultButtonNarrationText(o); }`。

### m9. `onClose()` 覆写为 `setScreen(null)` 是冗余的 — `NpcDialogueScreen.java:245-250`
- **证据**：原版默认 `onClose()` 是 `this.minecraft.popGuiLayer()`（`Screen.java:224-226`），
  而 `ClientHooks.popGuiLayer` 在 GUI 层栈为空时**就是** `minecraft.setScreen(null)`
  （`ClientHooks.java:239-243`）。本屏由 `setScreen` 打开（`:150`），栈必为空 ⇒ 两者等价。
- **影响**：无功能差异。差别只在"若有人用 `pushGuiLayer` 把它压栈"，那时 `setScreen(null)` 会
  **清空整个 GUI 层栈**（`Minecraft.java:1041` 的 `clearGuiLayers`）而不是只弹一层。
- **建议修法**：删掉这个覆写（用默认），或保留但在注释里说明"刻意不用 GUI 层语义"。

### m10. `nameKey` 在客户端缺键时直接显示原始键名，未下沉到下一级回退 — `NpcDialogueScreen.java:144-148`
- **证据**：`:144-148` 只判 `Optional` 是否存在，不判译文是否可用；
  `Component.translatable(缺失键)` 的原版行为是显示键本身
  （`TranslatableContents.java:112` `getOrDefault(key)`）。文档 §5.7 也把"名字是 `entity.minecraft.xxx`"
  列为已知症状，但那是"`lang` 缺 name 键"的情形 —— 而 `nameKey` 存在、值缺失时会显示
  `beloong.dialogue.xxx.name` 这种**更难看**的键名。
- **影响**：整合包作者写了一个 key 但忘了/拼错了 lang 条目时，界面上直接出现翻译键。属观感问题。
- **建议修法**：`payload.nameKey().filter(k -> Language.getInstance().has(k))` 后再走
  `map(Component::translatable)`，缺键时自然落到实体显示名；`fallbackNameKey` 同理可加一层
  `.getString()` 为空时的兜底（空串目前会画出空名字）。

### m11. 玩家死亡时本屏不自动关闭（`isPauseScreen=false` 的派生后果） — `NpcDialogueScreen.java:267-270`
- **证据**：`Minecraft.java:1825-1833`：`screen != null` 时走第一个分支（只处理睡醒），
  "玩家死亡且不是 DeathScreen ⇒ `setScreen(null)`" 这条 **else-if 只有 screen == null 时才会走到**；
  而 `setScreen(null)` 内部会在 `player.isDeadOrDying()` 时改开 DeathScreen（`Minecraft.java:1033-1038`）。
- **影响**：不崩、不卡。但玩家死亡后界面仍停在对话上（世界还在跑），要么按 ESC / 点「离开」
  才能看到死亡界面。NPC 被击杀/卸载更没有联动 —— 界面本来就只持有一份名字快照（`pages` + `Component`，
  **不持有 `Entity` 引用**，见"已验证"§14），所以只是"对话内容还在，人已经不在了"。
- **建议修法**：若判定需要，可在 `tick()` 里做一次轻量检查（`minecraft.player.isDeadOrDying()` → `onClose()`）；
  更完整的做法是服务端在实体移除/失效时下发一个 `close` 包 —— 但那会引入"客户端←服务端状态同步"，
  与当前"一次性下发、之后纯本地"的设计冲突，属于设计取舍，不必为它改架构。

### m12. 屏幕比例常量与像素常量的单位混用，超宽屏下单按钮会被拉长 — `NpcDialogueScreen.java:59-63,68`
- **证据**：`OPTION_LEFT/OPTION_WIDTH/OPTION_BOTTOM` 是屏宽/屏高比例，`OPTION_HEIGHT` 是 GUI 像素
  （`:67-69` 的注释也写了"GUI 像素"）；按钮内部的图标/内边距/文字全是固定像素
  （`NpcDialogueOptionButton.java:44-46`）。
- **影响**：21:9 或 32:9 下按钮宽被拉到 0.22·w（可能 300+ px），而图标仍是 9 px、"离开"仍是 18 px，
  比例失衡（观感问题，未实机验证）。正文同理（`TEXT_MAX_WIDTH=0.80` 屏宽）。
- **建议修法**：把选项宽度改成 `min(0.22·w, 140)` 之类的封顶，或给按钮加一个"内容宽度"上限。

### m13. 渲染顺序注释里的渐变起点是旧值 0.72 — `NpcDialogueScreen.java:277-278`
- **证据**：`:277-278` 写"渐变从 0.72 屏高开始"，而常量是 `GRADIENT_START = 0.62F`（`:77`）；
  `docs/plans/2026-09-20-npc-dialogue-design.md:490,506-507` 明确记录了实机调参
  "0.72→0.62、末端 alpha 0xC0→0xE6" ⇒ 改值后漏改注释。
- **影响**：调试渐变参数时按注释推算会错，"选项目的不被压暗"的论证本身是对的（super.render 在最后
  `:287`），只有数字过期。
- **建议修法**：改为"渐变从 0.62 屏高开始"（或干脆不写数字，只写"渐变与选项在纵向重叠"）。

### m14. `@OnlyIn(Dist.CLIENT)` 在方法上重复标注 — `NpcDialogueScreen.java:39,131`
- **证据**：类已标 `@OnlyIn(Dist.CLIENT)`，静态方法 `open` 再次标注（方法位于客户端专用类内，
  且 `NpcDialogueOpenPayload.handleClient` 已用"专用服务器不会被调用"论证安全性）。
- **影响**：无功能影响；冗余标注会让人误以为这个方法有独立的加载期约束。
- **建议修法**：删掉 `:131` 的注解。

## 建议（Nit）

- `NpcDialogueOptionButton.java:72` 用全限定名 `net.minecraft.client.Minecraft.getInstance().font`，
  与本文件其它 `import` 风格不一致；建议 `import net.minecraft.client.Minecraft;` 后用
  `Minecraft.getInstance().font`（原版 widget 没有 `getFont()`，只能这样取，见 `AbstractWidget.java:101-132` 同样写法）。
- `NpcDialogueScreen.java:352` 的 `Math.sin(this.tickCount * 0.15) * 2.0`：`0.15`/`2.0` 是魔法数，
  建议提为 `ARROW_FLOAT_SPEED`/`ARROW_FLOAT_AMPLITUDE` 常量，与"布局常量集中在类顶部"的既定做法一致。
- `NpcDialogueScreen.java:392-426` 的三个文本工具是纯函数、无状态、逻辑微妙（`§`、代理对），
  最适合单测；本仓没有 `test` source set，至少把边界用例（末尾 `§`、`§§c`、emoji、空串）
  写成类注释里的清单，供将来实机/脚本回归。
- `NpcDialogueOptionButton.java:41` `ICON_COLOR = 0xFFEDEDED` 与 `TEXT_COLOR = 0xFFFFFFFF` 是两个近似白，
  注释里没说为什么不同；若只是参考图取色，建议注明"参考图图标比文字略暗"。
- `NpcDialogueScreen.java:305-307` 先 `translate` 再 `scale` 且以屏宽中点为原点 —— 正确，
  但可加一行注释说明"以名字中轴为缩放中心，否则 `nameScale≠1` 时名字会跑偏"，避免后人改成先 scale 后 translate。
- 建议在类常量区补一条注释，把 M6 里发现的"多选项可用高度只有 `OPTION_BOTTOM - 正文底部`≈0.073·h"写清楚，
  它是当前布局最紧的约束。

## 已验证正确的部分

以下每一项都是我**主动去原版/NeoForge 源码核实过**的（而不是"看起来没问题"）：

1. **空 `pages` 防御到位**：`open()` 在 `:135-137` 直接返回，`NpcDialogueScreen` 内所有
   `pages.get(...)`（`:167`）都受 `pages.size()` 约束。上游契约
   `NpcDialogueEntry.java:39` 也声明"非空；空数组由加载器丢弃"。
2. **单页 / 多页 / 空文本页三种情形无死角**：单页 ⇒ 打完 `advanceOrFinish()` 直接
   `showOptions()`（`:226-232`）；页文本为空 ⇒ `visibleTotal=0`，首个 `tick()` 的
   `revealed(1) >= 0` 成立 ⇒ 立刻推进（`:178-183`，不会卡在 TYPING）；多页 ⇒ `WAIT_CLICK`
   出现且恒非末页 ⇒ `pageIndex++` 不会越界（`:205-210,226-232`）。
3. **`TYPING` 点击不跳页、连点不会一次翻两页**：`:199-204` 只做"本页补全 + advanceOrFinish"，
   翻页必须再点一次（`WAIT_CLICK` 分支 `:205-210`）。推演连点 3 次：补全→翻页(TYPING)→补全，
   每次点击最多推进一个状态，不会跳页。
4. **`init()` 用对了，resize 后按钮不会错位、也不会重复添加**：`resize` →
   `repositionElements()` → `rebuildWidgets()`（`clearWidgets()` 先清空）→ `init()`
   （`Screen.java:459-467,350-358`），而 `showOptions()` 只在 `state == SHOWING_OPTIONS` 时被
   `init()` 调用（`:156-163`）⇒ 坐标按新 `width/height` 重算，且旧按钮已被清掉，不会叠两个。
5. **`SHOWING_OPTIONS` 之外不存在"看不见但能点"的按钮**：`addRenderableWidget` 只在
   `showOptions()`（`:241-242`）调用，而状态机没有任何从 `SHOWING_OPTIONS` 回去的路径 ⇒
   控件生命周期与状态严格同步；`state != SHOWING_OPTIONS` 时 `children()` 为空。
6. **`renderBackground` 留空的代价清单已核实**：默认实现只有
   `renderPanorama`(level==null) / `renderBlurredBackground` / `renderMenuBackground` /
   NeoForge `ScreenEvent.BackgroundRendered`（`Screen.java:377-385`）。
   `renderTransparentBackground`（`Screen.java:417-419`）**不是** `renderBackground` 调用的，
   它由 `AbstractContainerScreen`、`BookEditScreen` 等自带屏幕自行调用 ⇒ 留空**不会**顺带移除任何
   本屏原本会有的绘制；1.21.1 里 `processBlurEffect` 的调用者只有 `Screen.renderBlurredBackground`
   与 `GameRenderer`（全 jar 检索），tooltip 背景走 `TooltipRenderUtil`（`GuiGraphics.java:1517`），
   **不依赖** `renderBackground`；本屏也不渲染任何 tooltip ⇒ 不存在"tooltip 层/输入焦点异常"。
   唯一实际代价是漏发 NeoForge 事件（见 m1）。
7. **渐变方向与渲染状态正确**：`fillGradient(x1,y1,x2,y2,colorFrom,colorTo)` 把 `colorFrom` 放在
   `y1` 顶点（`GuiGraphics.java:384-390`）⇒ `0x00000000` 在 0.62·h（上、全透明）、
   `0xE6000000` 在屏幕底部（下、90% 黑），方向正确。它走 `RenderType.gui()`
   （`GuiGraphics.java:347-348`），该 RenderType 用 `TRANSLUCENT_TRANSPARENCY`
   （`RenderType.java:698-705,1091`）= `SRC_ALPHA / ONE_MINUS_SRC_ALPHA` 标准混合
   （`RenderStateShard.java:76-83`），**不是叠加混合**，且状态清理时 disableBlend ⇒
   顶部全透明不会"污染"后续绘制的混合或 shader 颜色。
8. **`font.width` 确实把 `§x` 当作零宽**（Q10 的核实点）：`Font.java:292-294` →
   `StringSplitter.java:29-40` → `StringDecomposer.iterateFormatted`（`:100-117` 消费掉 `§`+码字符）。
   因此 `wrap()` 的测宽（`:380`）、`drawCenteredString` 的居中（`GuiGraphics.java:425-426` 与
   `:411-413`）、`countVisible()`（`:392-403`）三者对 `§` 的处理**互相一致** —— 这一点是整段文本逻辑
   能对齐的关键，写对了。
9. **`§` 在渲染期确实会被解释**（所以"名字金色由语言文件决定"这条注释成立）：
   `Component.literal(...)` 走 `MutableComponent.getVisualOrderText()`（`:100-109`）→
   `Language.getVisualOrder`（`Language.java:62-71`）→ `StringDecomposer.iterateFormatted`；
   lang 里的值经 `TranslatableContents.decompose`（`:101-122`）变成 `FormattedText` 片段后同样
   进入 `iterateFormatted`。已对照实际资源：`assets/beloong/lang/zh_cn.json:147` 为
   `"§6铁傀儡"`、`:148-149` 为两页正文（第二页含 `\n`）。
   附带核实：格式串异常（例如译文里混入 `%s` 而没有参数）会抛 `TranslatableFormatException`，
   但 `decompose()` 会 `catch` 并回退成原样字符串（`:114-120`）⇒ **不会崩溃**（Q17 的一条子问题）。
10. **同 tick 收到两个 `open` 包是安全的**：注册用 `registrar.playToClient` 且未 `executesOn`
    （`BeLoongCore.java:149-158`），`PayloadRegistrar` 默认 `HandlerThread.MAIN`（`:29`）并把 handler
    包进 `MainThreadPayloadHandler`（`:166-167`）→ `context.enqueueWork`（`MainThreadPayloadHandler.java:13-18`）
    ⇒ 两个包都在渲染线程的任务队列里依次执行，第二次 `setScreen` 会触发第一次的
    `old.removed()`（`Minecraft.java:1049-1052`），无泄漏、无竞态。
11. **`NpcDialogueOpenPayload` 的"默认在主线程"注释与 `NPC系统总设计.md:574-575` 的同一说法成立**
    （依据同第 10 条：`PayloadRegistrar.java:122-130` 的 javadoc 明写
    "When the handling thread is set to MAIN, all registered handlers will be wrapped in
    MainThreadPayloadHandler. The initial handling thread is MAIN."）。
    残余风险仅一点：若将来有人给 registrar 加 `.executesOn(HandlerThread.NETWORK)`，
    `setScreen` 会跑到网络线程上，而原版只在 `SharedConstants.IS_RUNNING_IN_IDE` 时才打印
    "setScreen called from non-game thread"（`Minecraft.java:1020-1022`）——生产环境里会静默数据竞争。
    建议在 `handleClient` 注释里补一句"前提是本包注册时未改 `executesOn`"。
12. **关闭界面确实不需要通知服务端**（Q3）：`NpcDialogueHandler` 是纯无状态的
    （`:36-76` 只做表查询 + `PacketDistributor.sendToPlayer`），没有 per-player 会话、冷却或持久数据；
    客户端侧也没有回发任何包 ⇒ 关闭时"什么都不做"是完备的。
    同理 `removed()`/`onClose()` **无需**中止打字机 —— `Screen.tick()` 只在
    `minecraft.screen == this` 时被调用（`Minecraft.java:1835-1841`），界面一关就自然停。
13. **不持有实体引用**：类字段只有 `speakerName`（`Component`）与 `pages`
    （`:100-111`），`Entity` 只在 `open()` 里用一次取名（`:140`）就被丢弃 ⇒ 不存在悬垂实体引用或
    因实体卸载导致的内存滞留。
14. **没有静态可变字段**（Q18）：`:45-94` 全是 `static final` 常量，其余字段都是实例字段
    （`:103-111`）⇒ 跨屏幕、跨世界不会残留状态。
15. **`AbstractWidget` 用法正确**（Q13/Q14）：
    `renderWidget(GuiGraphics,int,int,float)` 是抽象必需实现（`AbstractWidget.java:99`），
    且 `AbstractWidget.render` 是 `final`（`:66`）——本类没有去覆写已废弃的 `renderButton`；
    `updateWidgetNarration` 也是抽象必需实现（`:352`），已提供；
    **点击音效没丢**：`playDownSound` 在 `AbstractWidget.mouseClicked` 里（`:157-165`），
    覆写 `onClick` 不影响它；
    **状态机没被破坏**：`isHovered`/`isFocused` 由 `final render()`（`:66-76`）与
    `setFocused`（`:314-317`）维护，本类只在 `tickHover()`（`:61-63`）里**读** `isHovered()`，
    没有绕过 `super` 的写法；`wasHovered` 在 1.21.1 已不存在（滚轮相关字段是 `PERIOD_PER_SCROLLED_PIXEL`，`AbstractWidget.java:29-30`），故无一致性隐患。
16. **颜色与插值 API 用法正确**：`Mth.approach(value, limit, stepSize)` 的语义是"按步长逼近 limit"
    （`Mth.java:319-322`），与 `tickHover()` 的参数顺序一致；`FastColor.ARGB32.lerp(delta, min, max)`
    是按下标插值（`FastColor.java:80-86`），`0xFF000000|NORMAL_RGB → 0xFF000000|HOVER_RGB` 用法正确；
    `(alpha << 24) | rgb` 中 `rgb` 已 `& 0xFFFFFF`（`:90,:106`）⇒ 不会串到 alpha 位。
    `renderBackgroundBand` 的左侧半圆内缩用 `radius - round(sqrt(r²-dx²))`（`:98-103`）在
    `dx=r` 时为满内缩、`dx=0` 时为 0，形状正确（`radius = height/2 = 10`）。
17. **`tick()` 不会被"不暂停"影响**：`screen.tick()` 的调用不受 `pause` 影响
    （`Minecraft.java:1835-1841`）；`pause` 只由 `isPauseScreen()` 参与计算（`:1234-1241`）
    ⇒ 打字机在 `isPauseScreen()==false` 下照常每 tick 推进，设计意图成立。
18. **文档 §5.5 的布局常量表逐项核对通过**（值全部一致）：`NAME_Y 0.790`（源 `:45`）、
    `RULE_Y 0.822`（`:47`）、`RULE_HALF_WIDTH 0.165`（`:65`）、`TEXT_TOP 0.860`（`:55`）、
    `ARROW_Y 0.964`（`:57`）、`OPTION_LEFT/OPTION_WIDTH 0.661/0.22`（`:59,61`）、
    `OPTION_BOTTOM 0.787`（`:63`）、`OPTION_HEIGHT/OPTION_GAP 20/8`（`:68,69`）、
    `TEXT_MAX_WIDTH 0.80`（`:72`）、`LINE_GAP 2`（`:74`）、
    `GRADIENT_START/GRADIENT_BOTTOM 0.62/0xE6000000`（`:77,79`）、
    `WHITE/GOLD/GOLD_BRIGHT`（文档 `:618`）与源 `:82-85` 一致；行数声明
    "460/147"（文档 `:581`）与实际一致、"加载器 127 行"（文档 `:430`）与
    `NpcDialogueLoader.java` 实际 127 行一致。**只有方向/理由的三处措辞不符（见 M4/M6/m13）。**
19. **ESC 关闭可用**：未覆写 `shouldCloseOnEsc`（默认 true，`Screen.java:220-222`），
    `keyPressed` 里 `keyCode == 256 && shouldCloseOnEsc()` → `onClose()`（`:150-153`）⇒
    三个状态下 ESC 都能关；`SHOWING_OPTIONS` 下点空白不关闭是有意为之（`:211-214`，注释已说明）。
20. **`speaker.getDisplayName()` 的语义与注释相符**：`Entity.getName()` 先取命名牌自定义名、
    否则取 `EntityType` 名（`Entity.java:2606-2609`），`getDisplayName()` 在此基础上套队伍前后缀
    （`Entity.java:2861-2864`）⇒ "实体在场时命名牌自定义名仍生效"成立。
    附带影响（未列为问题，仅提示）：`getDisplayName()` 还挂了 hover/insertion 事件，本屏不处理悬停，
    无副作用。

## 无法静态确认的项

| # | 待确认点 | 需要的验证动作 |
|---|---|---|
| 1 | M2 的实际观感：正文与"继续"箭头、快捷栏/血条的相对位置 | 实机在 GUI scale 4（480×270）、3（640×360）、超宽窗口各截一张"3~5 行长文本"的图 |
| 2 | M3：`§` 颜色在自动换行续行上失效的实际表现 | 写一页 ~120 字、以 `§c` 开头且不写 `§r` 的文案，实机看第 2 行是否变白 |
| 3 | M1 的量级：多长的页会在本机上卡多久 | 用一个 2000/8000/30000 字的测试页各测一次进页耗时（建议做成调试命令或临时数据文件，测完删除） |
| 4 | M12：超宽屏（21:9）下选项底衬被拉长后的观感、图标/文字比例 | 实机在 3440×1440 或 2560×1080 分辨率下截图 |
| 5 | `nameScale = 1.5` 时名字上缘是否触及装饰线（算术上 h=270 时名字盒 [206.6,220.1]、装饰线 221.9，留 1.8 px；投影会再占 1 px） | 实机在 GUI scale 4 下看"名字下沿与金线是否贴住/穿模" |
| 6 | 打字机节奏在实际帧率下的观感（`charsPerTick` 1 vs 20） | 实机试玩两档 |
| 7 | 玩家死亡 / NPC 被击杀时界面停留的实际体验（m11） | 实机：打开对话后 `/kill @s`、以及击杀 NPC，观察界面与操作路径 |
| 8 | 名字三级回退的第三级（实体未加载） | 实机在实体刚离开视距/区块卸载的边界右键，确认显示 `entity.minecraft.xxx` 而不是键名 |
| 9 | 悬停金色过渡在真实帧率下的流畅度（每 tick 逼近 0.25） | 实机把鼠标在按钮上快速移入/移出，看是否有 1 帧滞后感（`isHovered` 由 `final render` 更新，`tick()` 读上一帧值） |

## 越界发现

（不在本次审查范围内，仅报告，未修改任何代码。）

1. **线格式缺上限**（上游 `dialogue/NpcDialogueOpenPayload.java:64`）：
   `NpcDialogueEntry.Page.STREAM_CODEC.apply(ByteBufCodecs.list())` 未给 `maxSize`，
   解出的是 `Integer.MAX_VALUE`（`ByteBufCodecs.java:351-353`）。这与该文件类注释里
   "线载荷是全函数、永不抛"的设计意图只差半步 —— **不抛**成立，但**不失控**没成立。
   建议 `list(64)` + 单页字符串另设上限。客户端侧已在 M1 里给了防御建议，但根治在 codec。
2. **`NpcDialogueOpenPayload.handleClient` 的主线程前提没有写在注册处**：
   `BeLoongCore.java:151-158` 依赖 `PayloadRegistrar` 的默认 `HandlerThread.MAIN`。
   建议在注册处加一行注释"此处不得加 `executesOn(NETWORK)`：`handleClient` 直接 `setScreen`"，
   把跨文件的隐式约束变成显式约束（对应"已验证"§11 的残余风险）。
3. **`docs/NPC系统总设计.md` §5.5 的行数声明会随代码漂移**：`460 行 / 147 行` 今天准确，
   但没有随构建校验的机制；本项目其它文档也有同类数字（如"加载器 127 行"）。
   若在意，可改成不带数字的描述，或由探针脚本生成。
4. **`NpcDialogueEntry.Page.sound` 当前"只解析不播放"**（`NpcDialogueEntry.java:68-70`）：
   文档已声明，客户端也确实没有消费它；本次审查确认客户端**没有**遗漏播放调用（该字段根本没上线，
   `NpcDialogueOpenPayload` 不含 sound）。仅提示：将来接配音时，`Page.STREAM_CODEC` 已经准备好了字段。
