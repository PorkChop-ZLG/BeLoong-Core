# 核验报告：对话系统与客户端界面

> 核验对象：`docs/reviews/2026-09-25-npc-dialogue-server-review.md`（A）、`docs/reviews/2026-09-25-npc-dialogue-client-review.md`（B）
> 核验基线：`24bb701`
> 核验方式：从一手源码独立重新推导；审查者报告仅作待验证输入
> 原版证据：`sourcesAndCompiledWithNeoForge_0fdaf824…_output.jar`（1.21.1 + NeoForge patched，下文 `Xxx.java:N` 指该 jar）、
> `neoforge-21.1.236-sources.jar`、`datafixerupper-8.0.16-sources.jar`。抽取到临时目录后逐行读取，未运行构建、未改任何源码。

---

## 总体裁决

| 报告 | 成立 | 部分成立 | 不成立 |
|---|---|---|---|
| A（服务端） | C1、M3 | M1、M2、越界1 | **M4（主结论错）** |
| B（客户端） | M2、M3、M4、M5、M6、m1~m5、m7~m14（m6 结论对但算式错） | M1、m6 | B 的越界#4（"sound 根本没上线"错） |

**可信度评价**：两份报告的**取证方式**都是可信的（原版行号我抽查 20 余处，除下文列出的偏差外均逐字准确），
但**结论强度**都偏大：

- A 的系统性偏差是**把文档/注释失真升格为严重问题**，并在此基础上**虚构了一个不存在的缺口（M4）**——
  M4 的第一条论据（"同名文件覆盖不了"）与第二条结论（"`pages: []` 后模组原条目仍生效"）自相矛盾，且两条都错。
  A 的 C1 是本次唯一货真价实的 P0，取证链（载荷无上限 → 编码期抛 → netty 线程无法 catch → 断线）我逐环节复现，成立。
- B 的系统性偏差是**把量级算错**（M1 的"O(n²)/5×10⁸ 次/数秒~数十秒"实测模型错了 2~3 个数量级；m6 的可用高度用错了锚点），
  并且**低估了载荷问题的服务端侧后果**（它把"线格式不失控"只当成上游越界发现，而它的修法 #3 反而会把问题从"编码期掉线"原样保留）。
  B 的 M2 是本报告里最扎实的一条：我独立算了一遍，行号与像素结论全部复现。
- **建议采信**：A-C1（升级为唯一 P0）、B-M2、B-M3、B-M5、B-M6、B-M4，以及两边全部 m 级中我从源码复现出来的那些。
  **建议丢弃**：A-M4 的主结论、B-M1 的量级、B 越界#4、B-m6 的数字。

---

## 逐条裁决

### A-C1 载荷无长度上限 + 编码期异常踢人

- **裁决**：**成立**（四个子问题全部复现，(e) 的修法"方向对但不够"）
- **我的核实**：
  - (a) `ByteBufCodecs.java:135` = `STRING_UTF8 = stringUtf8(32767)`；`Utf8String.java:33-35` 在 `string.length() > maxLength` 时
    `throw new EncoderException(...)`，`:42-44` 在 UTF-8 字节超 `utf8MaxBytes(32767)` 时同样抛。
    因为 `utf8MaxBytes(32767)=98301` 且实际字节数 ≤ 3×UTF-16 长度，**字符数上限 32767 先于字节上限生效**。
    `ByteBufCodecs.java:384-386` `list()` → `collection(ArrayList::new, codec)` → `:351-353` `maxSize = Integer.MAX_VALUE`；带限重载在 `:388-390` 未被使用。
  - (b) 编码链：`Player.java:1094` 事件 → 我们的 handler → `Connection.java:363-370`（不在事件循环线程时 `eventLoop().execute(...)`）
    → `channel.writeAndFlush` → 管线里 `CustomPacketPayload.java:36-44`（把 `EncoderException` 包成 `RuntimeException("Failed encoding custom payload …")`）
    → `PacketEncoder.java:36-42`（`isSkippable()` 为 `false` ⇒ 非 skippable 分支 **原样重抛**；`Packet.java:17-19` 默认 false，
    `ClientboundCustomPayloadPacket.java` 全文 87 行**未覆写** `isSkippable`）→ `Connection.java:144-182` `exceptionCaught`
    → 非 `SkipPacketException` ⇒ `:155` 组 `disconnect.genericReason("Internal Exception: …")`，CLIENTBOUND 侧 `:173-177` 先尽力发 `ClientboundDisconnectPacket` 再断开。
    另两条兜底上限：`CompressionEncoder.java:33-34`（>8388608 字节，`CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH = 8388608`）、
    `Varint21LengthFieldPrepender.java:21-22`（帧长 > 3 字节 VarInt），二者也是编码期抛 + 同一条断线路径。
  - (c) 触发面：JSON 侧完全无上限（`NpcDialogueEntry.java:157` `Codec.list(Page.CODEC)`；`:73` `Codec.STRING.fieldOf("text")`；
    `:156` `Codec.STRING.optionalFieldOf("name")`），**数据包可任意覆写**这些字段（见 A-M4 的核实），
    所以"单页 >32767 字符"、"页数多到超 8MB/2MB"都在整合包作者手里；`fallbackNameKey` 走
    `EntityType#getDescriptionId()` 恒短，安全。
  - (d) "无法在调用点 catch" **准确**：调用线程是服务端主线程，`Connection.java:365` 判定不在事件循环 ⇒
    `:368` 投递到 netty 线程，编码在 `writeAndFlush` 内发生；即便在事件循环上，异常也走 netty 的
    `exceptionCaught` 通道而非调用栈。A 这条推理无误，也值得写进注释。
  - (e) **修法有效性**：A 的第 1 层（加载期拦截）**对当前可达路径确实充分** —— 我 grep 全仓，
    `new NpcDialogueOpenPayload` **只有一处**（`NpcDialogueHandler.java:71`），实参 `entry.pages()` 只可能来自
    `NpcDialogueLoader` 的表；`Codec.string(min,max)` 在 DFU 里是 `Codec.java:486-497` 的 `validate` ⇒ 返回 `DataResult.error`，
    能保住"单文件隔离"。**两点不足**：
    1. `text` 是**翻译键**，真正被渲染的字符串是 `Component.translatable(text).getString()` 的结果
       （`NpcDialogueScreen.java:167`），它来自语言文件，**不受任何加载期校验约束**。所以 A 的修法治不了客户端侧（见 B-M1 裁决）。
    2. 即使页数被限到 64，总字节仍可能撞上 2MB 帧上限（64×32767 汉字 ≈ 6.3MB 未压缩，压缩后 ~2MB 量级）。
       页数上限应按**字节预算**倒推（例如 32 页 × 2048 字符），而不是只按页数。
- **重定级**：**P0**（玩家断线；`Packet.isSkippable()=false` 使异常必然升级为断连）
- **建议修法是否有效**：**部分有效**。加载期校验是唯一真正有效的一层（A 说对了）；但需追加"按字节预算定页数上限"，
  并明确写入"渲染文本来自 lang、另需客户端侧上限"。A 的第 2 层（`list(MAX_PAGES)`）单独使用**无效**（`writeCount` 抛 `EncoderException`，`ByteBufCodecs.java:343-349`），
  A 自己也说了这点，正确。

### A-M1 "空手右键本无任何原版行为"是错的

- **裁决**：**成立**（事实判断正确；但 A 把"文档失真"与"界面冲突"混在一条，需要拆）
- **我的核实**：
  - `Villager.java:329-356`：空手、非刷怪蛋、存活、非交易中、非睡眠、非 `isSecondaryUseActive()`（潜行）⇒ 非幼年 ⇒
    服务端分支 `:351 startTrading(player)` → `:365-368 openTradingScreen(...)`。**空手开交易成立**。
  - `AbstractHorse.java:708-731`：空手（`itemstack.isEmpty()`，`:716`）⇒ `:728 doPlayerRide(player)` → `:729 sidedSuccess`（消费动作）。**空手上马成立**。
  - `IronGolem.java:273-289`：非铁锭 ⇒ `:276 PASS`。A 说"只对 mobInteract 返回 PASS 的实体成立"成立。
  - **A 没提、但确实是别的空手右键原版行为**（我另找到 3 条）：
    `Boat.java:802-813`（空手 / 非潜行 ⇒ `:810 player.startRiding(this)`，`CONSUME`）；
    `ItemFrame.java:362-392`（框内已有物品 + **空手** ⇒ `:382-386` 旋转，`flag=true` 与手持是否为空无关）；
    `Pig.java:135-142`（已上鞍 + 手持非食物 + 非潜行 ⇒ `:139 startRiding`，与"空手"无关而与物品是否为食物有关）。
    `Minecart.java` 亦有 `interact` 覆写（同类）。
  - **语义判断**：`NpcDialogueEntry.java:51-53` 与 §5.2:463-465 的措辞是"保住了原版**物品**交互"——这一句**是对的**
    （`NpcDialogueHandler.java:63-65` 在主手非空时 return，物品交互确实不被本功能插手）。
    错的只是 `NpcDialogueHandler.java:25-26` / §5.4:543 / §5.7:651 的**理由**："空手右键本无任何原版行为"。
    A 在这一点上的判断正确：**"物品交互"与"空手交互"是两件事**，`trigger: empty_hand` 保住的是前者，保不住后者。
  - A 的"对话界面被交易界面顶掉"我按报文顺序推演认为**成立**：本模组载荷在 `Player.java:1094`（事件派发点）内发出，
    `Mob.interact` → `Villager.mobInteract:351` 在其**之后**才发开屏包，两者共用同一 netty 事件循环队列 ⇒ FIFO ⇒
    客户端先开对话屏、随即被 `MerchantScreen` 覆盖。属**高置信推理，未实机**。
- **重定级**：**P3 + D2**（注释/文档失真，代码本身无缺陷）；其派生的可用性风险单列 **P1**（整合包为村民/马配置对话 ⇒ 对话看不见）
- **建议修法是否有效**：方向有效但**不够**。A 说"§5.7 排障表'改回 empty_hand'对物品交互完全无效"成立；
  但最小修法应写成两条：(1) 事实句改写为"不取消 ⇒ 原版交互照旧；空手本身也可能有原版行为（村民交易、上马、上船、旋转展示框）"；
  (2) 因为界面冲突的胜者**确定是原版界面**，比"考虑不为其配置"更该写明的是"为这类实体配置对话在本版本**无效**"。
  A 建议的第三档 `exclusive`（`setCancellationResult(InteractionResult.SUCCESS)` + `setCanceled(true)`，
  `PlayerInteractEvent.java:118,145`，派发点 `CommonHooks.java:797-801` 返回 `isCanceled() ? getCancellationResult() : null`）
  机制上可行（我核实 `Player.java:1095` 是 `cancelResult != null` 才提前返回），但那是功能变更，超出本次"改文档"的范围。

### A-M2 SOUND_STREAM_CODEC 往返不一致

- **裁决**：**部分成立**（机制完全正确；"真 Bug"的定性过重）
- **我的核实**：
  - `ResourceLocation.java:265-273` `isValidPath("")` 的 for 循环不执行 ⇒ 返回 `true`；
    `:110-125 tryBySeparator("", ':')`：`indexOf(':') == -1` ⇒ `:122-123` `isValidPath("")` 真 ⇒ `new ResourceLocation("minecraft", "")`，**非 null**。
  - 因此 `decode(encode(Optional.empty()))` = `decode("")` = `Optional.of(minecraft:"")` ≠ `Optional.empty()`。
    反向 `encode(Optional.of(minecraft:""))` = `"minecraft:"` → `:119-120` `new ResourceLocation("minecraft", "")` ⇒ 恒等但丢失"空"的语义。A 全对。
  - **A 与 B 都没说清的一点**：`sound` **确实上线**。`NpcDialogueOpenPayload.java:44,64` 传的是
    `List<NpcDialogueEntry.Page>`，而 `NpcDialogueEntry.java:102-105` 的 `Page.STREAM_CODEC` 是
    `composite(STRING_UTF8 text, SOUND_STREAM_CODEC sound, Page::new)` ⇒ 客户端收到的 `Page.sound()` 已经是
    `Optional.of(minecraft:"")`。B 的越界#4（"该字段根本没上线"）**是错的**。
  - **实际影响为零**：我 grep 全仓 `.sound()`，除 JSON/lang 外**无任何读取点**（客户端只用 `pages[].text`），
    且 `assets/beloong/sounds.json` 里根本没有 `beloong:dialogue.iron_golem.1` 这条（只有 `block.beloong.loong_palace_portal.*` 三条）。
- **定性**：**latent codec 缺陷 + 注释与实现不符**，不是"今天在制造错误的运行时行为"。准确表述是：
  "`Page.STREAM_CODEC` 破坏了它自己声明的'与 `CODEC` 语义一致'（`NpcDialogueEntry.java:94-100`）——JSON 侧 `Optional.empty()` 表示'无声音'，
  线格式解出来却是'有一个 `minecraft:` 声音'；今天不可观测，一旦按 `:69` 的'实装时只需加一行播放调用'去接配音，所有无声音的页会变成带坏 id 的页。"
- **重定级**：**P2 + D2**（需未来的播放代码才显形；当前无运行时影响）
- **建议修法是否有效**：**有效**。`s -> s.isEmpty() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(s))` 使空值往返恒等，
  且不影响真实 RL（真实 RL 的 `toString()` 非空）。替代方案 `ByteBufCodecs.optional(STRING_UTF8)`（`ByteBufCodecs.java:317-332`，布尔标志）
  更彻底。A 关于"把'全函数'限定为解码侧"的建议也成立。

### A-M3 日志"扫描数"名不副实

- **裁决**：**成立**
- **我的核实**：`SimpleJsonResourceReloadListener.java:37-54`：`GsonHelper.fromJson` 与 `output.put` 同在 `try`（`:44-49`），
  `:50-52` 的 `catch (IllegalArgumentException | IOException | JsonParseException)` 只打 **本类自己的** `LOGGER`（`:19`）
  `"Couldn't parse data file {} from {}"`，**不入 map**。⇒ `files.size()`（`NpcDialogueLoader.java:118`）是
  "Gson 解析成功数"，不是"扫描到的文件数"。A 对统计口径的核实无误。
- **实际影响**：主症状仍可检出 —— 目录名/树写错时 `listMatchingResources` 返回空 ⇒ `files.size()==0`（这对"放错树"依然有效）；
  受影响的只是"某文件少个逗号"这种情形：计数比实际少 1、且报错不带 `[BeLoong]`/`BeLoong` 前缀，`grep BeLoong` 抓不到。
  另外文档 §5.3:496 称"**唯一的**运行时观测点是那行 INFO"本身就不成立 —— 同一 loader 还有
  `NpcDialogueLoader.java:96-97`（ERROR）与 `:106-108`（WARNING）两个观测点，§5.7:644 也在用前者。A 这条也对。
- **重定级**：**P3 + D2**（文档失真；日志措辞属 D3）。无运行时影响 ⇒ 不是 Major。
- **建议修法是否有效**：有效。文档改成"**成功侧**的唯一观测点"，并补一句"JSON 语法错误看 `Couldn't parse data file`（无 BeLoong 前缀）"。

### A-M4 数据包无法关闭某条内置对话（上级与 A 的争议）

- **裁决**：**A 不成立，上级正确**（A 的"现象/代码"段对，但由它推出的两条结论都错）
- **我的核实（完整影子替换链）**：
  1. `SimpleJsonResourceReloadListener.java:38-40` → `FileToIdConverter.listMatchingResources`（`FileToIdConverter.java:30-32`）
     → `ResourceManager#listResources`。
  2. `MultiPackResourceManager.java:89-98`：逐 namespace 合并 `FallbackResourceManager.listResources`。
  3. `FallbackResourceManager.java:154-199`：`map`/`map1` 是 `HashMap<ResourceLocation, …>`，`:176 map.put(...)` 对**同一 ResourceLocation 反复覆盖**，
     循环 `:163` 按 `fallbacks` 顺序（`push` 递增 = 优先级递增，见 `:42-56` 与 `:65` 的反向遍历）⇒ **最终只剩每个路径的胜出资源（高优先级数据包）**。
  4. 优先级链：`MinecraftServer.java:1504-1511` 用 `rebuildSelected(selectedIds).stream().map(Pack::open)`（保持 `selected` 顺序）
     构造 `MultiPackResourceManager`；`selected` 里**世界数据包排在最后**（`MinecraftServer.java:1568-1604`：
     先放 level.dat 的 enabled 列表（`addModPacks` 把模组数据包并入），再把 `PackSource.WORLD.shouldAddAutomatically()==true`
     的新发现包 `set.add` 到末尾；`PackSource.java:12` WORLD 为 `shouldAddAutomatically=true`，
     `FolderRepositorySource.java:31` 给这类发现包配 `DISCOVERED_PACK_SELECTION_CONFIG = new PackSelectionConfig(false, Pack.Position.TOP, false)`
     ⇒ `Pack.Position.TOP`；
     `ResourcePackLoader.java:259-273 reorderNewlyDiscoveredPacks` 只把**新发现的 BOTTOM** 包挪到最前，TOP 的保持原位
     ⇒ 世界数据包优先级最高）。
  ⇒ **把 `data/beloong/beloong/npc_dialogue/iron_golem.json` 放进世界数据包，就是同名覆盖（影子替换）**，模组原件**根本不会进入 `files` map**。
  5. 覆盖成 `"pages": []`：`NpcDialogueLoader.java:99-104` 打 ERROR 并 `return`（HashMap 不留条目）⇒ `:113 Map.copyOf(parsed)` 无该实体
     ⇒ `:124-126 get()` 返回 null ⇒ `NpcDialogueHandler.java:55-58` 直接 return ⇒ **该条对话被关掉**。
     A 的"模组原条目**仍然生效**"在影子替换下不可能成立。
  6. 我实测过 `SimpleJsonResourceReloadListener.java:47-49` 的 `IllegalStateException("Duplicate data file ignored with ID …")`：
     `fileToId`（`FileToIdConverter.java:25-28`）= `file.withPath(path.substring(prefix.length()+1, path.length()-ext.length()))`，
     namespace 保留、去头去尾长度固定 ⇒ 在 path 上是**单射**，而 `listResources` 的 key 又是唯一的 ResourceLocation
     ⇒ **这个分支不可达（死代码）**。顺带一个隐患：它抛的是 `IllegalStateException`，**不在 `:50` 的 catch 列表里**
     （`IllegalArgumentException`/`IOException`/`JsonParseException`），真被触发会穿出 `prepare` 直接搞崩整次重载 ——
     所以它是"不可达的雷"，不是"重复绑定"的正常路径；A 把 `NpcDialogueLoader.java:105-109` 的重复 WARNING 与它混为一谈了。
- **A-M4 的残留有效部分**：`pages: []` 作为"关闭某条对话"的**唯一手段**确实**没有文档**，而且日志级别是 ERROR、
  文案是 `declares no pages, ignored` —— 一个**正常用法**却打 ERROR，会误导排障（§5.7:644 又教人把 `Failed to parse…` 当成文件被拒）。
  这一点是真缺口，属文档 + 日志措辞问题。
- **重定级**：主结论 **不成立**；残留部分 **P3 + D3**（文档缺口 + 日志级别/文案误导）。**不是** A 给的 Major。
- **建议修法是否有效**：A 的 (a)"把 `pages: []` 改成删除语义"**不需要**——它已经是删除语义；
  A 的 (b) `"disabled": true` 属可选糖，可以做但不是修 bug。我建议的修法：
  文档补一节"**如何关闭一条内置对话**：用数据包同名覆盖该文件并写 `"pages": []`（会打一条 ERROR，属正常用法）"，
  并把 `NpcDialogueLoader.java:101-102` 的 ERROR 降为 INFO/WARN、文案改为"被数据包覆盖为无页，视为关闭"。
  A 的另一条建议"把'重复绑定的胜者由文件名排序决定'写进文档"—— 方向对（见 A-m4），但它与"关闭"是两回事。

### A 的越界发现 `StructureEffectEntry` 裸 `getKey`

- **裁决**：**部分成立**（机制描述正确，但不可达，A 自己也只是"大概不可达"）
- **我的核实**：`StructureEffectEntry.java:20-26` 确用 `ResourceLocation.CODEC.comapFlatMap(解码, BuiltInRegistries.MOB_EFFECT::getKey)`；
  DFU `MapEncoder.java:22-39` 的 `comap` 把 `function.apply(input)` 的**返回值直接交给下游 `encode`**，没有 null 保护；
  `Codec.java:242-243` `comapFlatMap = Codec.of(comap(from), flatMap(to))` 的 `from` 是裸 `Function`
  ⇒ 与 `NpcDialogueEntry.java:119-141` 用 `flatXmap`+显式 `DataResult.error` 的处理确实不对称。A 对机制的判断成立。
  **可达性**：我 grep 全 `src` 下 `.encode(` / `encodeStart`，**零命中**；而该 record 的唯一构造路径是
  `:20-31` 的解码器（只产出注册表命中的 `MobEffect`，其 `getKey` 必非 null）⇒ **当前不可达**。
- **重定级**：**P4**（潜在不一致，无触发路径；A 给的定性过高）
- **建议修法是否有效**：A 的 n2（把注释里的"同款做法"限定为"同款的**解码**做法"）**有效**，是零成本且正确的措辞修正。

### B-M1 `wrap()` 的复杂度与量级

- **裁决**：**部分成立** —— (a) 成立；(b) **不成立（量级错）**；(c) 只有"线载荷无上限"这一半是同一根因；(d) B 的三条修法**不是相互充分**
- **我的核实**：
  - (a) `NpcDialogueScreen.java:369-385`：对每个非 `§` 单元，只要 `current.length() > 0` 就
    `this.font.width(Component.literal(current + unit))`（`:379-380`）——"每加一字重测整行"**属实**。
    `Font.java:292-293` → `StringSplitter.java:42-49` → `StringDecomposer`，且 `Component.literal` 每次新建实例
    （`MutableComponent.java:100-109` 的缓存是实例级）⇒ 无跨调用缓存，也对。
  - (b) **B 的 O(n²) 是错的**。关键点 B 漏了：`current` 在超宽时会**立即 flush 并清空**（`:381-382`），
    所以被测量的字符串长度上界是"一行能放下的单元数 L"，**不是整页长度 n**。
    每行内第 k 个单元测 k 个字符 ⇒ 每行 ~L²/2 次字符访问；行数 ~n/L ⇒ **总量 Θ(n·L)，L 由换行宽度钳住**。
    代入真实值（GUI scale 4 ⇒ 480×270，`TEXT_MAX_WIDTH=0.80` ⇒ maxWidth=384px，汉字步进 9px ⇒ L≈42）：
    - 3000 字页：`font.width` 调用 ≈ 3.0×10³ 次，字符访问 ≈ 3000×42/2 ≈ **6.3×10⁴**；
    - 线格式单页上限 32767 字：调用 ≈ 3.3×10⁴ 次，字符访问 ≈ **6.9×10⁵**（≈ 780 行）。
    按每次字符访问 50~100ns（新实例 + 逐字 `getVisualOrder`/glyph 查表）估 ≈ **数十毫秒**，不是"数秒~数十秒"。
    B 的"3 万字页 ≈ 5×10⁸ 次字符级操作"比真实值大 **约 700 倍**（那是把整页当成一行累加才会得到的数字）。
  - **我另外找到 B 漏掉的真正客户端成本**：`renderBody`（`:328-343`）对**所有已揭示行**每帧调用
    `countVisible` + `drawCenteredString`（`:337-339`），并不因为行数多而停手 —— 一个 32767 字的页会是
    **每帧 ~780 行、~3.3×10⁴ 个 glyph quad**（其中 777 行在屏幕外，见 B-M2），这才是"长页掉帧"的实际来源。
    而这一条**不受线格式上限保护**：真正被渲染的是 lang 值，一个 10 万字的语言条目能让它无界增长（客户端侧无任何上限）。
  - (c) **与 A-C1 只共享一半根因**："线载荷无长度/页数上限"是共同的；但 B 的客户端成本有一半来自
    **lang 侧文本**，A 的加载期校验对它完全无效 ⇒ **不应合并为一条，应合并"线载荷无上限"这一半**。
  - (d) 三条修法的相互充分性：
    `增量测宽`（逐单元累加）**正确但收益有限**（把 Θ(n·L) 降到 Θ(n)，1 万倍量级上只省几十毫秒），
    且**不解决**每帧重绘整页；
    `loadPage` clamp **是客户端唯一真正有效的兜底**（无论来源是线载荷还是 lang 都能挡住），但会静默改变内容语义（B 自己也承认）；
    `list(64)` **单独使用无效**：`writeCount`（`ByteBufCodecs.java:343-349`）在编码期抛 `EncoderException` ⇒
    **仍然走同一条断线路径**，只是异常类型/位置变了；必须与 A-C1 第 1 层（加载期校验）配套才有意义。
- **重定级**：`wrap()` 的算法冗余 **P4**（纯优化，无功能影响）；真正的长页代价 **P2**（受线格式上限约束时约百毫秒级；
  lang 侧无界时可达秒级，但需异常 lang 资源）
- **建议修法是否有效**：**不足**。我建议：`loadPage` 里按**行数**而非字数 clamp（`maxLines = (arrowTop - TEXT_TOP*height) / (font.lineHeight + LINE_GAP)`，
  与 B-M2 的修法复用同一个算式），超出部分追加省略号；`renderBody` 加 `y + lineHeight > arrowTop` 的提前 break；
  `wrap()` 若要改就用 `font.getSplitter().splitLines(Component.translatable(key), maxWidth, Style.EMPTY)`
  （一次同时修掉本条的冗余、B-M3 的样式丢失、B-m3/m4 的 `§`/代理对边界）。

### B-M2 正文无纵向适配

- **裁决**：**成立**（我独立算了一遍，B 的数字与结论全部复现）
- **我的核实（算式）**：`LINE_GAP=2`（`:74`），`Font.lineHeight = 9`（`Font.java:38`）⇒ `renderBody` 行距 = 11px（`:329`）；
  第 k 行（1-based）顶边 `y_k = (int)(h·0.860) + (k-1)·11`（`:330,340`）；
  箭头盒 `[ (int)(h·0.964) - 9 , (int)(h·0.964) + 9 ]`（`ARROW_RADIUS=7`，`:87,:351-353`，再叠加 `Math.sin*2` 的浮动 `:352`）。
  - **h=270**（1920×1080 + GUI scale 4 = 480×270）：`y = 232`；`y_k = 232/243/254/265/276…`；
    箭头 = 260 ± 9 = **[251, 269]**。第 3 行文字盒 [254, 262] 与 [251,269] **重叠**；第 4 行 [265, 273] **下沿出屏 3px**。
    ⇒ B 的"第 3 行压箭头"精确成立；"第 4 行已在 270 屏高之外"应修正为"**部分出屏**"（顶边 265 仍在屏内）。
  - **h=360**（GUI scale 3 = 640×360）：`y = 309`；`y_k = 309/320/331/342/353/364`；箭头 = 347 ± 9 = **[338, 356]**。
    ⇒ 第 4 行 [342, 350] 压箭头（B 说 343，差 1px，可忽略）；第 6 行顶边 364 > 360 ⇒ **完全出屏**，B 正确。
  - 行容量：maxWidth = 0.80·480 = 384px，汉字 9px ⇒ 42 字/行 ⇒ 2 行 = 84 字；与 B 的"约 85 字（2 行）以下才安全"一致。
    顺带核对 B 的方向判断：正文首行 0.860·h 在选项底边 0.787·h **下方**（y 向下增大），二者不冲突 —— 这点 B 说对了。
  - 补充：**GUI scale 4（480×270）是最常见的自动档**，所以"只要写够 3 行就撞箭头"不是苛刻边界。
- **重定级**：**P1**（观感/内容丢失缺陷，触发路径是普通的数据包文案长度）
- **建议修法是否有效**：① 行数上限 + 省略号 **有效**（推荐）；
  ② 改锚点 **有效但必须同时上移 `RULE_Y`/`NAME_Y`**（B 已指出，`NpcDialogueScreen.java:48-54` 的注释说得很清楚）；
  ③ 只打日志告警 **不足**（不解决"看不见"）。另建议补一条"GUI scale 4 下 3 行文案"的实机验收项。

### B-M3 `§` 颜色在续行丢失

- **裁决**：**成立**
- **我的核实**：`renderBody` 对每行独立 `Component.literal(shown)`（`NpcDialogueScreen.java:339`）；
  `StringDecomposer.java:100-117` 的样式状态 `Style style = currentStyle` 起于**本次调用**的入参，
  `§` 只在该次遍历内累积 ⇒ 换行处不传递。对照原版自己的换行 `StringSplitter.java:195-215`：
  `:198 Style style = p_style` 起步、`:213 style = linebreakfinder.getSplitStyle()` **把样式带进下一行** ⇒
  本实现与原版行为确实不同，B 的机制描述准确。
- **现有 shipped 文案下是否可见**：**不可见**，我核对了两份语言文件：
  `zh_cn.json:147` 的 `§6` 只用在**名字**（`speakerName`，单独一个 Component 走 `drawCenteredString`，不经 `wrap`），
  `:148-149` 与 `en_us.json:148-149` 的正文**不含任何 `§`**；p2 的 `\n` 只产生两段 18/17 字的单行。
  ⇒ 要显形必须同时满足"含 `§`"+"长到换行"，两份文档都没提示这个组合。
- **重定级**：**P2**（观感缺陷；`docs/NPC系统总设计.md:456` 明确把 `§` 列为正文可用特性 ⇒ 一旦作者照文档用就会踩到，接近 P1）
- **建议修法是否有效**：两条都有效。用 `font.getSplitter().splitLines(...)` 更优（顺带修 B-M1 与 B-m3/m4），
  B 关于"`revealed` 的计数口径要跟着改"的告诫也正确。

### B-M4 状态机"末页是否进 WAIT_CLICK"

- **裁决**：**成立**（**确认上级的核实，未推翻**）
- **我的核实**：`advanceOrFinish()`（`:226-232`）只有 `pageIndex + 1 < pages.size()` 才置 `WAIT_CLICK`，
  否则直接 `showOptions()`；`showOptions()` 置 `state = SHOWING_OPTIONS`（`:236`）。
  进入 `TYPING` 的两条路径（`loadPage():171`、`tick()` 打满后 `:182 advanceOrFinish`）与点击补全（`:199-203`）都走同一分支
  ⇒ **末页从不进入 `WAIT_CLICK`**，`:31` 的"WAIT_CLICK --点击且末页-->"分支**不可达**；`:206` 与 `:219-224` 的注释是对的。
- **需要改成什么（逐处）**：
  - `NpcDialogueScreen.java:28`：`显示完 ⇒ WAIT_CLICK` → `显示完（中间页）⇒ WAIT_CLICK；显示完（末页）⇒ SHOWING_OPTIONS`
  - `NpcDialogueScreen.java:29`：`点击 ⇒ 本页立刻全显示 ⇒ WAIT_CLICK` → 同上去掉无条件性（改为"⇒ 同上，按是否末页分流"）
  - `NpcDialogueScreen.java:31`：整行删除（`WAIT_CLICK` 不存在末页分支）；`:30` 保留（正确）
  - `docs/NPC系统总设计.md:587`：出口列 `打完 → WAIT_CLICK` → `打完（中间页）→ WAIT_CLICK；打完（末页）→ SHOWING_OPTIONS`
  - `docs/NPC系统总设计.md:588`：出口列 `点击 → 下一页；已是最后一页 → SHOWING_OPTIONS` → `点击 → 下一页（`WAIT_CLICK` 恒非末页）`
  - `docs/plans/2026-09-20-npc-dialogue-design.md:141-142`：`→ 显示箭头 → WAIT_CLICK` 两句都加"（中间页）"限定，末页另写 `→ SHOWING_OPTIONS`
  - `docs/plans/2026-09-20-npc-dialogue-design.md:146`：`点击 && 是最后一页 → …` 标注为"已废止：末页不进 WAIT_CLICK（见 `advanceOrFinish`）"
  - B 的引用 `:30-31` 里 **`:30` 是正确的**，真正错的是 `:28/:29/:31`，这点要更正。
- **重定级**：**P3 + D2**（三处文字与实现不符；`docs/NPC系统总设计.md` 是本次交付物 ⇒ D2）
- **建议修法是否有效**：有效（只改文字，不动代码）；B 关于"照错文档改会引入 bug"的影响判断也成立。

### B-M5 F11 / 改 GUI scale 重启打字机

- **裁决**：**成立**
- **我的核实（调用链逐段）**：`Minecraft.java:1328-1334`（`resizeDisplay` → `:1332 this.screen.resize(...)`）
  → `Screen.java:463-467`（`resize` → `:466 repositionElements()`）
  → `Screen.java:459-461` → `Screen.java:350-358`（`rebuildWidgets`：`:351 clearWidgets()` → `:354 init()`）
  → `NpcDialogueScreen.java:156-163`（非 `SHOWING_OPTIONS` ⇒ `:161 loadPage()`）
  → `:166-172`（`:170 revealed = 0`、`:171 state = TYPING`）。B 的链路完全正确，
  且 `init()` **确实**重置了 `revealed`（不是只重排宽度）。
- **重定级**：**P2**（观感缺陷，可由 F11 / 拖窗稳定复现，但需"打字途中"这一时机；当前页仅 1~2 行时重播 1 秒内）
- **建议修法是否有效**：**有效但有残留**。`private boolean loaded` 方案能保住打字进度，但 `lines` 仍是**旧宽度**下排的版，
  新宽度下可能越界不换行 ⇒ 必须配一个 `rewrap()`（B 自己也提了）；`loaded` 需在 `loadPage()` 末尾置 true
  （翻页时由 `:208 loadPage()` 显式调用，不受影响，这点 B 的方案成立）。

### B-M6 文档 §5.5 两处与源码不符

- **裁决**：**成立**（**确认上级的核实**）
- **我的核实**：
  - `NpcDialogueScreen.java:82-83` `GOLD = 0xFFE8B84B` / `GOLD_BRIGHT = 0xFFFFD873`；
    实际用途：`:317-319` 装饰线 + 两端菱形用 `GOLD`；`:353` 箭头外框 `GOLD`、`:354` 内部三角 `GOLD_BRIGHT`。
    选项按钮悬停是 `NpcDialogueOptionButton.java:28` `NORMAL_RGB = 0x1A1F26` / `:30` `HOVER_RGB = 0xC8A05A`，
    在 `:89-90 FastColor.ARGB32.lerp(this.hover, …)` 插值。⇒ 文档 `:618-619`"（选项按钮悬停时在两档金色间插值）"**错**。
  - `TEXT_TOP` 方向：源码 `:48-54` 明写"刻意锚定**顶部**……只往下长"，文档 `:598` 写"正文起始（**自下而上**排版）"⇒ **方向说反，值对**。
- **文档应改成**：
  - `docs/NPC系统总设计.md:598` → `正文**首行顶部**（多行只向下生长；不要再按"中轴/自下而上"理解）`
  - `docs/NPC系统总设计.md:618-619` → `GOLD/GOLD_BRIGHT：装饰线与继续箭头用（GOLD 用于线与外框、GOLD_BRIGHT 用于内部三角）；
    选项按钮悬停底色由 NpcDialogueOptionButton.NORMAL_RGB(0x1A1F26) → HOVER_RGB(0xC8A05A) 每 tick 逼近插值`
  - 顺带按 B-M6#3 补 `renderBackground` 留空还漏了 `renderMenuBackground`（`Screen.java:383`）与 NeoForge
    `ScreenEvent.BackgroundRendered`（`Screen.java:384`，事件类 `ScreenEvent.java:241`）。
- **重定级**：**P3 + D2**（`TEXT_TOP` 方向项属 D1 邻域：照错文档去调参会把正文往反方向改，但不会"改坏"模型 ⇒ 记 D2）
- **建议修法是否有效**：B 给的两句替换措辞**有效**。

### B 的 14 条 Minor

| 条目 | 裁决 | 我的证据 | 重定级 | 修法 |
|---|---|---|---|---|
| m1 `renderBackground` 留空漏发 `ScreenEvent.BackgroundRendered` | 成立 | `Screen.java:377-385`，`:384` 是默认实现里的最后一件事；本类覆写为空（`NpcDialogueScreen.java:261-264`） | **P2** | 有效：补 `NeoForge.EVENT_BUS.post(new ScreenEvent.BackgroundRendered(this, guiGraphics))`（类在 `ScreenEvent.java:241`），或注释声明"刻意不发" |
| m2 每帧新建 `Component`/`StringBuilder` | 成立 | `:339` 每帧每行 `Component.literal(shown)`；`MutableComponent.java:100-109` 缓存是实例级；`:411-426` 每帧新建 `StringBuilder` | **P4**（当前 1~3 行，量级可忽略） | 有效（按行缓存 String） |
| m3 末尾孤立 `§` 多计 1 个可见字符 | 成立 | `countVisible` `:395-400` / `prefixByVisibleChars` `:416-423` 在 `i+1 < length` 不成立时走 else 计 1；原版 `StringDecomposer.java:106-109` 是 `break`（不渲染） | **P2**（多耗 1 tick，无可见错误） | 有效 |
| m4 代理对按 UTF-16 char 计数、可能被拆 | 成立（机制） | `wrap`/`countVisible`/`prefixByVisibleChars` 全部按 `charAt` 逐单元；`StringDecomposer.java:118-135` 对孤立 high surrogate 输出 `65533`（渲染成 �） | **P2**（需 emoji/增补平面字符） | 有效 |
| m5 选项文案无宽度适配/截断 | 成立 | 按钮宽 = `0.22·width`（`:61,238`），文字起点 = x+7+9+5 = 左边 21px（按钮 `:44-46,72-75`）；`en_us.json:150` = `"Leave"`，短 | **P2**（仅长译文） | 有效（`AbstractWidget.renderScrollingString`，`AbstractWidget.java:101-132`） |
| m6 `OPTION_GAP` 未使用 | 成立（数值错，见下） | grep 全 `src` 只有 `:69` 一处；`showOptions()` `:235-243` 只加一颗按钮 | **P4** | 有效（删掉或写成循环） |
| m7 覆写 `@Deprecated` 的 2 参 `onClick` | 成立 | `AbstractWidget.java:135-138`（`@Deprecated` + 2 参）、`:163` 调 **3 参**、`:28` 实现 `IAbstractWidgetExtension`；`IAbstractWidgetExtension.java:35-37` 3 参默认实现转发到 2 参 | **P4**（行为当前正确，只多一条编译警告） | 有效：改成 `onClick(double,double,int)` |
| m8 `updateWidgetNarration` 只播 TITLE | 成立 | `AbstractWidget.java:352` 抽象、`:354-363` 的 `defaultButtonNarrationText` 会加 `narration.button.usage.focused/hovered`，且 TITLE 用 `createNarrationMessage()`（`:91-97` = `gui.narrate.button` 包装）；本类 `:126-128` 用裸 `getMessage()` | **P2**（无障碍降级） | 有效：改调 `defaultButtonNarrationText(o)` |
| m9 `onClose()` 覆写冗余 | 成立 | 默认 `Screen.java:224-226` = `popGuiLayer()`；`ClientHooks.java:239-243`（栈空 ⇒ `setScreen(null)`）；`Minecraft.java:1041` 的 `clearGuiLayers` 会把整栈弹掉（`ClientHooks.java:220-223`） | **P4**（当前非压栈打开，二者等价） | 有效（删覆写或注明刻意） |
| m10 `nameKey` 缺译文时显示原始键名 | 成立 | `:144-148` 只判 `Optional` 是否存在；`TranslatableContents.java:112` `language.getOrDefault(key)`，`Language.java:137-138` `getOrDefault(id) = getOrDefault(id, id)` ⇒ 显示键本身 | **P2**（观感） | 有效：`payload.nameKey().filter(k -> Language.getInstance().has(k))`。**注意**：文档 §5.7:647 那行讲的是**兜底键**缺失（显示 `entity.minecraft.xxx`），与此是**两个不同症状**，文档该补的是这一行 |
| m11 玩家死亡不自动关闭（`isPauseScreen=false` 派生） | 成立 | `Minecraft.java:1825-1833`：`screen != null` 走第一分支（只处理睡醒），死亡分支 `:1829` 是 `else if` ⇒ 只在 `screen == null` 时可达；`setScreen(null)` 内部才在 `:1033-1038` 改开 `DeathScreen` | **P2**（观感/操作路径） | 有效（`tick()` 里查 `isDeadOrDying`），B 关于"不必为它改架构"的取舍判断也合理 |
| m12 比例常量与像素常量混用，超宽屏按钮拉长 | 成立（机制） | `:59-63,68` 比例 vs GUI 像素；按钮内部图标/内边距/文字全是固定像素（按钮 `:44-46`） | **P4**（未实机，观感） | 有效（加宽度封顶） |
| m13 渲染顺序注释里的渐变起点是旧值 0.72 | 成立 | `:277-278` 写"渐变从 0.72 屏高开始"，`GRADIENT_START` 在 `:77` = `0.62F` | **P3 + D2**（注释过期） | 有效 |
| m14 `@OnlyIn(Dist.CLIENT)` 重复标注 | 成立 | 类上标（`:39`）、静态方法 `open` 上又标（`:131`） | **P4**（无任何影响） | 有效 |

**B-m6 的算式错误（必须更正）**：B 写"选项上界是 `OPTION_BOTTOM·h`，只有 ~0.073·h 可用高度（= 0.860−0.787）"——
`TEXT_TOP=0.860` 在 `OPTION_BOTTOM=0.787` **下方**（y 向下增大），正文根本不是选项上方的边界；
选项是**向上排**的（`:62` 注释"最下一颗选项的底边，向上依次排"），真正的上界是装饰线 `RULE_Y=0.822`。
⇒ 可用高度 = `(0.822−0.787)·h = 0.035·h` ≈ **9.5px @h=270 / 12.6px @h=360**，
连第二颗 20px 按钮都放不下（更别说 +8px gap）。**结论（只够 1 颗）比 B 说的还强，但 B 的数字与锚点用错了**，
照它的算式写进注释会把后来人引到错误的约束方向上。

---

## 合并后的去重清单

| 编号 | 一句话 | 级别 | 涉及文件（:反例见各条裁决） |
|---|---|---|---|
| V1 | 线载荷对单页长度与页数**完全无上限**，超限在编码期抛异常且 `isSkippable()=false` ⇒ 命中该实体的玩家被踢下线；修法必须在加载期拦截，且页数上限要按字节预算倒推 | **P0** | `NpcDialogueEntry.java:73,89-92,103,153-157`、`NpcDialogueOpenPayload.java:62-64`、`NpcDialogueHandler.java:71`、`NpcDialogueLoader.java:99-113` |
| V2 | 正文无纵向适配：h=270 第 3 行压箭头、第 4 行部分出屏；h=360 第 4 行压箭头、第 6 行出屏（无 clamp/裁剪） | **P1** | `NpcDialogueScreen.java:55,57,328-343` |
| V3 | "空手右键无原版行为"是错的（村民交易、上马、上船、旋转展示框、骑猪）；据此配置村民/马的对话会被原版界面顶掉，且 §5.7 的排障指引方向相反 | **P3+D2**（文档/注释）／派生 **P1**（配置村民后对话不可用） | `NpcDialogueHandler.java:22-26`、`NpcDialogueEntry.java:51-53`、总设计 `463-465,543,643,651` |
| V4 | `§` 颜色在自动换行续行丢失（每行独立 `Component.literal`） | **P2** | `NpcDialogueScreen.java:339,365-389` |
| V5 | 长页的客户端真实代价 = `wrap()` 的 Θ(n·L) 冗余测量 + **每帧重绘全部已揭示行**（32767 字页约 780 行/帧，其中绝大多数在屏外）；B 的 O(n²) 量级不成立 | **P2**（真·线载荷上限下）/ 算法冗余 **P4** | `NpcDialogueScreen.java:365-389,328-343` |
| V6 | F11 / 改 GUI scale / 拖窗会 `init()→loadPage()` 重置 `revealed`，当前页打字机从头重放 | **P2** | `NpcDialogueScreen.java:156-163,166-172` |
| V7 | `Page.SOUND_STREAM_CODEC` 往返不一致（`Optional.empty()` ↔ `minecraft:`），且 `sound` **确实上线**并带着这个错值到达客户端 | **P2 + D2** | `NpcDialogueEntry.java:89-92,94-105`、`NpcDialogueOpenPayload.java:44,64` |
| V8 | 玩家死亡时对话屏不自动关闭（`isPauseScreen=false` 的 `else if` 派生） | **P2** | `NpcDialogueScreen.java:267-270` |
| V9 | 选项文案无宽度适配/截断（长译文飘到渐隐区外） | **P2** | `NpcDialogueScreen.java:238-242`、`NpcDialogueOptionButton.java:72-75` |
| V10 | `updateWidgetNarration` 丢 `USAGE` 与 `gui.narrate.button` 包装（无障碍降级） | **P2** | `NpcDialogueOptionButton.java:126-128` |
| V11 | `nameKey` 存在但译文缺失时直接显示键名（文档未列此症状） | **P2** | `NpcDialogueScreen.java:144-148` |
| V12 | 覆写 `renderBackground` 为空导致 NeoForge `ScreenEvent.BackgroundRendered` 从不派发 | **P2** | `NpcDialogueScreen.java:261-264` |
| V13 | 末尾孤立 `§` 计入 1 个可见字符（与原版 `break` 差 1） | **P2** | `NpcDialogueScreen.java:392-403,411-426` |
| V14 | 代理对被拆到两行/两次揭示，瞬时渲染 � | **P2** | `NpcDialogueScreen.java:365-389,392-426` |
| V15 | 状态机注释（`:28,29,31`）与实现不符；§5.5:587-588、plan §3.4:141-146 同错 | **P3+D2** | `NpcDialogueScreen.java:26-34`、总设计 `585-589`、plan `138-152` |
| V16 | `GOLD`/`GOLD_BRIGHT` 被文档说成"选项悬停插值"，实为装饰线/箭头色 | **P3+D2** | 总设计 `618-619` vs 源码 `82-83,317-319,353-354`、按钮 `28,30,89-90` |
| V17 | `TEXT_TOP` 被文档说成"自下而上"，源码注释正相反 | **P3+D2** | 总设计 `598` vs 源码 `48-55` |
| V18 | 日志 `files.size()` 是"Gson 解析成功数"而非扫描数；"唯一观测点"的说法与同文档 §5.7 自相矛盾 | **P3+D2** | `NpcDialogueLoader.java:114-119`、总设计 `496-503,644` |
| V19 | "如何关闭一条内置对话"没有任何文档：手段（同名覆盖 + `"pages": []`）**存在且有效**，但会打 ERROR | **P3+D3** | `NpcDialogueLoader.java:100-104`、总设计 §5.1/§5.3/§7:710 |
| V20 | 渲染顺序注释里渐变起点是旧值 0.72（常量 0.62） | **P3+D2** | `NpcDialogueScreen.java:277-278` vs `:77` |
| V21 | `OPTION_GAP` 声明未用；多选项的真实上界是 `RULE_Y` 而非正文（可用高度仅 0.035·h，连 1 颗都放不下） | **P4** | `NpcDialogueScreen.java:62,69,235-243` |
| V22 | 覆写 `@Deprecated` 的 2 参 `onClick`（行为正确，编译警告） | **P4** | `NpcDialogueOptionButton.java:120-123` |
| V23 | `onClose()` 覆写冗余；`setScreen(null)` 会清空整个 GUI 层栈 | **P4** | `NpcDialogueScreen.java:245-250` |
| V24 | 每帧新建 `Component`/`StringBuilder` 于渲染热路径 | **P4** | `NpcDialogueScreen.java:333-342,411-426` |
| V25 | 比例常量与像素常量混用，超宽屏按钮被拉长 | **P4** | `NpcDialogueScreen.java:59-63,68` |
| V26 | 方法上重复 `@OnlyIn(Dist.CLIENT)` | **P4** | `NpcDialogueScreen.java:39,131` |
| V27 | `StructureEffectEntry` 编码方向裸 `getKey`（可返回 null，`MapEncoder.comap` 无保护）—— 当前不可达 | **P4** | `StructureEffectEntry.java:20-26` |
| V28 | `Config.NpcDialogue.enabled` 文案是"界面"语义，实为"服务端是否受理右键" | **P4+D3** | `Config.java:264-268`、`en_us.json:82-83` |
| V29 | "只认主手"的**理由**与机制不符（副手事件几乎总会派发；保证只开一次的是 `MAIN_HAND` 过滤本身） | **P4+D3** | `NpcDialogueHandler.java:22-23`、总设计 `540-541` |
| V30 | `.mapStream(buf -> (ByteBuf) buf)` 冗余（含多余强转） | **P4** | `NpcDialogueOpenPayload.java:67` |
| V31 | 重复绑定胜者的排序键是 `ResourceLocation` 的 **path 优先**序，文档措辞易读成 namespace 优先 | **P4+D3** | `NpcDialogueLoader.java:85-87`、`ResourceLocation.java:180-187`、总设计 `493-494` |
| V32 | `sound` 不做注册表/`sounds.json` 校验，示例里的 id 在 `sounds.json` 中不存在（今天无害） | **P4+D3** | `NpcDialogueEntry.java:74`、`iron_golem.json:8`、`sounds.json`（仅 3 条 portal） |
| V33 | `ifSuccess` 内的 `return` 易被误读为"跳出文件循环"（实为仅结束本条 lambda） | **P4** | `NpcDialogueLoader.java:99-110`、`DataResult.java:184-187` |

---

## 审查者报告中的错误

1. **A-M4 的主结论是错的（本次最大的问题）**。A 说"整合包作者**没有任何办法**从数据包侧关掉模组自带的某一条对话"，
   并给出"同名文件覆盖不了"作为第一条论据 —— 而这条论据自身描述的正是"被 pack 顺序覆盖"的影子替换机制，
   与它推出的"模组原条目仍然生效"直接矛盾。正确结论：**同名覆盖有效，覆盖成 `"pages": []` 即关闭该条**（证据链见上文 A-M4）。
   连带错误：A 把 `NpcDialogueLoader.java:105-109` 的重复绑定 WARNING 与
   `SimpleJsonResourceReloadListener.java:47-49` 的 `IllegalStateException` 混为一谈——后者在 `FileToIdConverter.json`
   下**不可达**（`fileToId` 单射），而且它**不在** `:50` 的 catch 列表里。
2. **A 把三处纯文档/注释问题定成了 Major**（M2 往返不一致"M1/M2/M3/M4 都是 Critical/Major"）。
   M2 今天零运行时影响（`sound` 无人消费），M3 是日志口径，M4 主体不成立 —— 按运行时影响应分别落到 P2/P3/P3。
   A 的 C1 定 Critical 是**唯一合理**的严重级。
3. **A-C1 的修法不完整（但方向对）**：只校验 `pages[].text`/`name` 与页数，**治不了真正被渲染的字符串**
   （`Component.translatable(text).getString()`，来源是 lang），也**没考虑总字节预算**（帧上限 2MB/8MB），
   仅"64 页"未必安全。A 关于"加载期是唯一有效层""第 2 层不能单独用"的判断是对的。
4. **A-M2 的定性过重**：机制正确，但既没说清 `sound` **确实在线上**（B 的越界#4 因此也是错的），
   也没指出当前**零影响**；称其为 Bug 会让人以为线上正在产生错误数据。
5. **A 的"文档里的原版行号引用全部准确"只抽查了 4 处**（我复核了它列的 15 条中的大部分：`SimplePreparableReloadListener`、
   `Player.java:1094-1114`、`Villager`/`AbstractHorse`/`IronGolem`、`ResourceLocation.compareTo`、`Codec.java:238-252`、
   `DataResult`、`MapEncoder` 均准确）；但 A 把 `Player.java:1095` 的机制说成"只在事件被取消时才跳过"，
   更精确的写法是 `CommonHooks.java:800` 返回 `evt.isCanceled() ? getCancellationResult() : null`，
   `Player.java:1095` 判的是 `cancelResult != null` —— 结论等价，引用应当照抄。
6. **B-M1 的量级错 2~3 个数量级**：`current` 在超宽时立即 flush，被测字符串长度上界是一行容量 L 而非整页长度 n
   ⇒ Θ(n·L) 而非 O(n²)。B 的"3 万字页 ≈ 5×10⁸ 次字符级操作 / 数秒~数十秒 / 无法 ESC"没有代码依据
   （真实值 ≈ 6.9×10⁵ 字符访问、数十毫秒级）。B 还漏掉了真正的每帧成本（把全页已揭示行都画一遍）。
7. **B 的修法 #3（`list(64)`）单独使用无效甚至有害**：`writeCount` 在编码期抛 `EncoderException`
   （`ByteBufCodecs.java:343-349`）⇒ 仍是同一条断线路径。B 没提这一点，而 A 提了。
8. **B-m6 的可用高度算式用错了锚点**（用 `TEXT_TOP` 当上界，实际选项向上排、上界是 `RULE_Y`）：
   0.073·h → 应为 0.035·h。结论（只够 1 颗）反而更强，但数字与方向都错。
9. **B 的越界#4 错**："`sound` 根本没上线，`NpcDialogueOpenPayload` 不含 sound" ——
   payload 传的是 `List<Page>`，`Page.STREAM_CODEC` 第二个字段就是 sound（`NpcDialogueEntry.java:102-105`），
   它**在线**。这条错误同时掩盖了 A-M2 的真实传播范围。
10. **B 的 M4 行号小错**：它把 `NpcDialogueScreen.java:30-31` 一起标成错的，实际 `:30` 正确，错的是 `:28`/`:29`/`:31`。
11. **B 的"未发现 Critical 级问题 / 无连接中止路径"只在客户端屏的范围内成立**：B 自己也把线格式缺上限列为越界发现，
    但结论段的"无连接中止路径"容易被读成整个功能都安全 —— 实际存在一条服务端侧必然断线的路径（V1）。
12. **两份报告的严重级阈值不一致**（A 用 Critical/Major/Minor 且把文档失真放进 Major；
    B 的 Major 里混着 P2 与 P4），且都没有把"文档改动是否会改坏东西"单独标记 —— 本报告已按统一规则重定级并补 D 标记。

---

## 我无法静态确认的

| # | 待确认点 | 需要的验证动作 |
|---|---|---|
| 1 | A-M1 的界面胜负（村民：最终留下交易界面还是对话界面） | 为 `minecraft:villager` 配一条对话，空手右键看客户端屏幕。我的报文顺序推理指向"交易界面覆盖对话"，但未实机 |
| 2 | V1 的实机表现（超长单页时玩家看到的断线文案、是否只影响该玩家） | 造一个 `text` > 32767 字符的数据文件，右键触发；看服务端 `Error sending packet` 与客户端断线提示 |
| 3 | `wrap()` 的真实墙钟耗时 | 用 3000/8000/32767 字的测试页各测一次进页耗时（需调试命令或临时数据文件）。我的 Θ(n·L) 与数十毫秒是**解析+估算**，不是实测 |
| 4 | V2 的实际观感（正文与箭头、快捷栏/血条、`nameScale=1.5` 时名字与装饰线是否贴住） | 实机在 GUI scale 4（480×270）与 3（640×360）各截一张 3~5 行长文案的图 |
| 5 | V4 的实际表现（`§c` 开头、不写 `§r` 的 120 字页第 2 行是否变白） | 实机看（机制已从 `StringDecomposer`/`StringSplitter` 对照确认，只差肉眼） |
| 6 | V7 一旦接配音的后果（`Optional.of(minecraft:"")` 传给 `level.playSound` 会静默失败还是抛） | 不在本次范围；接配音时再验 |
| 7 | `onClick(double,double,int)` 覆写与删掉 `.mapStream(...)` 后能否编译 | 需在有 `gradlew` 的空闲窗口跑 `compileJava`（本次按要求未运行构建；我只核了 `AbstractWidget.java:28` 实现 `IAbstractWidgetExtension`、`:163` 调 3 参，类型上可行） |
| 8 | `StructureEffectEntry` 的编码方向在 `src` 之外（数据生成脚本、配置导出工具）是否被调用 | 我 grep 的范围仅 `src/**`；若将来有导出工具，V27 会从 P4 升级 |
| 9 | `/reload` 之后客户端屏上正在显示的旧对话是否会失效 | `NpcDialogueHandler` 无状态、屏只持快照（B 已验证 §12/§13），理论上无影响；未实机 |
