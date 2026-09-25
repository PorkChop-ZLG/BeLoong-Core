# NPC 对话系统（服务端链路）代码审查

> 审查范围：`dialogue/NpcDialogueEntry.java`、`dialogue/NpcDialogueLoader.java`、`dialogue/NpcDialogueHandler.java`、
> `dialogue/NpcDialogueOpenPayload.java`、`BeLoongCore.java`（NPC/对话相关注册段）、`Config.java`（`SERVER_SPEC` 的 `[npc_dialogue]` 段）、
> `data/beloong/beloong/npc_dialogue/iron_golem.json`
> 审查基线：`24bb701`（分支 `NPC`，工作树干净）
> 审查方式：只读静态审查（未运行游戏、未跑构建、未修改任何 `src/**` 或既有文档）
> 原版证据来源：`~/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_0fdaf824…_output.jar`
> （Minecraft 1.21.1 + NeoForge patched；已交叉核对 4 份缓存 jar，`MinecraftServer.java:1511` / `:1519`、`Minecraft.java:491`、
> `SimplePreparableReloadListener.java:19` 四份内容与行号完全一致，故行号引用无版本歧义）；
> DFU `datafixerupper-8.0.16-sources.jar`；NeoForge `neoforge-21.1.236-sources.jar`。

---

## 结论

这套服务端链路的**工程判断质量明显高于平均水平**：四条最容易写错的"载重论点"（`flatXmap` 而非 `xmap`、
`ifError/ifSuccess` 而非 `resultOrPartial`、`entries` 不加 `volatile`、目录字符串是 PackType 相对）
我逐条去原版/DFU 源码核实过，**全部成立**，文档里的行号引用也**逐字准确**。

但有三件事值得先修：

1. **线载荷没有任何长度上限，而编码期异常会直接踢人**（`ByteBufCodecs.list()` 无上限、`STRING_UTF8` 上限 32767 字符，
   超限抛 `EncoderException`/`IllegalArgumentException`，`Packet.isSkippable()` 为 `false` ⇒ 连接中止）。
   更糟的是**这个异常无法在调用点 catch**（编码发生在 netty 线程），所以只能在加载期拦住非法数据。
   同时它证伪了 `NpcDialogueOpenPayload` 类注释与 §5.4 的"线载荷全函数、永不抛"。
2. **"空手右键本无任何原版行为"这个前提是错的**（村民空手右键 = 开交易界面、马 = 上马）。
   于是 `trigger` 缺省 `empty_hand` 并不能"保住原版交互"，且该文件的三处文档/注释（§5.4、§5.7、`Trigger` javadoc）
   给出的排障指引是反向的。
3. **`Page.SOUND_STREAM_CODEC` 往返不一致**：`Optional.empty()` 编码成 `""`，而 `tryParse("")` 返回
   `Optional.of(minecraft:"")` 而不是 `Optional.empty()`。今天不可见（`sound` 只解析不播放），但它推翻的正是
   该 codec"与 JSON 侧语义一致"的自我声明。

---

## 严重问题（Critical）

### C1. 载荷无长度上限，超长 `text` 或超大 `pages` 会在编码期抛异常并踢掉目标玩家 — `NpcDialogueOpenPayload.java:60-67`、`NpcDialogueEntry.java:89-92,103-105,153-158`

- **现象/代码**：
  - `NpcDialogueEntry.java:89-92` 用 `ByteBufCodecs.STRING_UTF8.map(...)` 表示 `sound`；
  - `NpcDialogueOpenPayload.java:64` 用 `NpcDialogueEntry.Page.STREAM_CODEC.apply(ByteBufCodecs.list())` 传 `pages`；
  - `NpcDialogueOpenPayload.java:63`、`:62` 的 `fallbackNameKey` / `nameKey` 也都是 `STRING_UTF8`；
  - JSON 侧同样无上限：`NpcDialogueEntry.java:157` 是 `Codec.list(Page.CODEC)`（`Codec.list` ⇒ `minSize=0, maxSize=Integer.MAX_VALUE`）。
- **证据**（全部来自源码）：
  - `ByteBufCodecs.java:135`：`STRING_UTF8 = stringUtf8(32767)`；
    `Utf8String.java:33-35`：`write()` 在 `string.length() > maxLength` 时 **throw `EncoderException`**（字节数超 `utf8MaxBytes(32767)` 时同样抛，见 `:43-44`）。
  - `ByteBufCodecs.java:384-390`：`list()` → `collection(ArrayList::new, codec)` → `maxSize = Integer.MAX_VALUE`（无上限）；
    带上限的重载 `list(int maxSize)` 存在但**未被使用**。
  - `CompressionEncoder.java:33-34`：未压缩包 > 8388608 字节 ⇒ `IllegalArgumentException("Packet too big …")`；
    `Varint21LengthFieldPrepender.java:21-22`：帧长度超过 21 位 VarInt（> 2097151 字节）⇒ `EncoderException("Packet too large …")`。
  - `Packet.java:17-19`：`isSkippable()` 默认 **`false`**，`ClientboundCustomPayloadPacket` 未覆写；
    `PacketEncoder.java:36-42`：编码异常被记录后 **原样重抛**（非 skippable 分支）；
    `Connection.java:144-166`：`exceptionCaught` 落到 `disconnect(Component.translatable("disconnect.genericReason", "Internal Exception: " + exception))`。
  - 注意 `ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 1048576`（`:36`）**不适用于本包**：
    它只喂给 `DiscardedPayload`（未知 id 的降级 codec，`CustomPacketPayload.java:29-33`），
    已注册的 payload 走 `NetworkRegistry.getCodec(...)`（`CustomPacketPayload.java:32,40`），没有 1MB 兜底。
- **影响**：服务端安装的数据文件里只要有一个 `pages[].text` 超 32767 字符，或 `pages` 大到编码超限，
  **每一次命中该实体右键的玩家都会被踢**（`Internal Exception: …`），而加载期、`/reload` 期一切正常、无任何告警。
  触发条件完全在数据包作者手里，且"把长文直接写进 `text` 而不是翻译键"是很自然的误用（§5.2 只说了应该写翻译键，没有任何校验）。
  这也直接证伪 `NpcDialogueOpenPayload.java:34` 与 `docs/NPC系统总设计.md:564` 的"线格式全函数、永不抛"——
  该不变量对**解码**大致成立（无值相关的失败分支），对**编码**不成立。
- **建议修法**（必须做第 1 层，第 2 层只是兜底）：
  1. **加载期拦截（唯一真正有效的层）**：在 `NpcDialogueEntry.CODEC` 里把
     `Codec.STRING.optionalFieldOf("name")` 与 `Page.CODEC` 的 `text` 换成 `Codec.string(1, 32767)`
     （`Codec.java:486-497` 用 `validate` 返回 `DataResult.error`，能保持"单文件隔离"），
     并在 `NpcDialogueLoader.apply()` 里对 `entry.pages().size() > MAX_PAGES` 走与空 pages 相同的 ERROR+丢弃分支；
  2. **线格式加硬上限**：`ByteBufCodecs.list(MAX_PAGES)`、`ByteBufCodecs.stringUtf8(32767)`（后者已是现值，可显式写出来表明是有意为之）。
     ⚠️ **第 2 层不能单独用**：`ByteBufCodecs.readCount` 超限同样是 `DecoderException`（`:334-341`）⇒
     只加上限只是把"编码期掉线"换成"解码期掉线"，必须由第 1 层保证服务端**根本构造不出**超限载荷。
  3. **不要试图在 `NpcDialogueHandler` 里 try/catch**：`Connection.send` 只做 `channel.writeAndFlush`
     （`Connection.java:363-373`，非事件循环线程时 `eventLoop().execute(...)`），编码在 netty 线程发生，
     异常不会回到调用点。这一点写进注释，免得下一个人去加无效的 try/catch。

---

## 重要问题（Major）

### M1. "不取消事件"的真实后果与三处文档/注释相反：空手右键**有**原版行为，`trigger: any` 是**叠加**而不是"抢走" — `NpcDialogueHandler.java:25-26,60-65`、`NpcDialogueEntry.java:51-53`、`docs/NPC系统总设计.md:463-465,543,643,651`

- **现象/代码**：
  - `NpcDialogueHandler.java:25-26`：「**刻意不取消事件**（首版 D4 不变）：空手右键本无任何原版行为，取消没有收益」；
  - `NpcDialogueEntry.java:51-53`：「代价是该实体的那类原版物品交互会被本功能**抢走**」；
  - `docs/NPC系统总设计.md:543`（同句）、`:464-465`（同句）、`:651`「`trigger: empty_hand` 缺省已经保住了绝大多数原版物品交互」；
  - `docs/NPC系统总设计.md:643` 排障表：「该实体原有交互被抢走 → 该文件的 `trigger` 是不是 `any`；改回 `empty_hand`」。
- **证据**：
  - 不取消 ⇒ 原版交互照跑：`PlayerInteractEvent.EntityInteract` 的 javadoc 明写
    「This event's state affects whether `Entity#interact` and `Item#interactLivingEntity` are called」（`PlayerInteractEvent.java:110-111`），
    事件派发点在 `Player.java:1094`，紧随其后的 `1098`（`entityToInteractOn.interact`）与 `1114`（`itemstack.interactLivingEntity`）**只在事件被取消时才被跳过**（`1095`）。
  - **空手右键有原版行为**：`Villager.mobInteract`（`Villager.java:329-355`）在空手、未潜行、非幼年、无交易中时直接
    `startTrading(player)` → `openTradingScreen(...)`（`:365-368`），即**服务端打开交易界面**；
    `AbstractHorse.mobInteract`（`AbstractHorse.java:708-730`）空手走到 `:728 doPlayerRide(player)` 并返回
    `sidedSuccess`（**消费动作**），即上马。
  - `Mob.interact` 是 final 且依次调用 `checkAndHandleImportantInteractions` → `mobInteract`（`Mob.java:1243-1262`），
    所以"`empty_hand` 就什么都不发生"只对铁傀儡这类 `mobInteract` 返回 `PASS` 的实体成立
    （`IronGolem.java:273-286`：非铁锭即 `PASS`）。
- **影响**：把 `minecraft:villager` 写进数据文件（`trigger` 缺省 `empty_hand`）会**同时**下发本模组的载荷**和**打开交易界面；
  本模组的载荷在 `Player.java:1094` 就发出（早于 `:1098` 的 `startTrading`），两个界面互相顶掉，
  最可能的结果是**对话界面被交易界面立刻覆盖 = 该对话完全看不到**（确切胜负见"无法静态确认"）。
  同理 `minecraft:horse` 会"边骑马边看对话"。而 `trigger: any` 的实际语义是"叠加"而非"抢走"——
  文档给出的排障路径（改回 `empty_hand`）对物品交互**完全无效**。
- **建议修法**：三处文案必须重写，并把"要不要独占"变成显式能力而不是靠"不取消"这个隐式规则：
  - 最小修法：文档/注释改成事实（"不取消 ⇒ 原版交互照旧；空手也可能有原版行为，如村民交易、骑乘"），
    并在 §5.7 排障表补一行"村民/马等对话看不到 → 该实体的原版空手交互与本功能冲突，考虑不为其配置，或按住潜行"。
  - 更好：给 `Trigger` 加第三档（如 `exclusive`），或在数据文件里给一个 `cancel_vanilla` 布尔，
    命中时 `event.setCancellationResult(InteractionResult.SUCCESS)` + `event.setCanceled(true)`（该事件 `ICancellableEvent`，
    `PlayerInteractEvent.java:118,145`）。注意取消会**同时**挡掉第三方模组的 `EntityInteract`，这正是首版 D4 想避免的——
    所以必须是**逐实体显式选择**，与 `trigger` 的现有取舍风格一致。

### M2. `Page.SOUND_STREAM_CODEC` 往返不一致：`Optional.empty()` 与 `""` 混淆 — `NpcDialogueEntry.java:89-92`

- **现象/代码**：
  ```java
  ByteBufCodecs.STRING_UTF8.map(
          s -> Optional.ofNullable(ResourceLocation.tryParse(s)),
          o -> o.map(ResourceLocation::toString).orElse(""));
  ```
- **证据**：
  - 编码侧：`Optional.empty()` → `""`（`orElse("")`）。
  - 解码侧：`ResourceLocation.tryParse("")` → `tryBySeparator("", ':')`：`indexOf(':') == -1` →
    `isValidPath("")` 对空串的 for 循环不执行 ⇒ 返回 **`true`** ⇒
    `new ResourceLocation("minecraft", "")`，即 **非 null**（`ResourceLocation.java:110-125`、`:265-273`）。
  - ⇒ `decode(encode(Optional.empty())) == Optional.of(ResourceLocation("minecraft", ""))` ≠ `Optional.empty()`。
    反向也单向：`encode(Optional.of(minecraft:""))` = `"minecraft:"`，而 `decode("minecraft:")` 又是 `Optional.of(minecraft:"")`
    （`:94-107 bySeparator`：`i == 0` ⇒ `withDefaultNamespace("")`）——所以 `""` 这个"空"值在链路上会被升格成一个真实存在的 RL。
- **影响**：今天不可见（`sound` 只解析不播放，客户端只用到 `pages[].text`），但：
  (a) 它证伪 `NpcDialogueEntry.java:95`「线格式……与 `CODEC` 语义一致」——JSON 侧 `Optional.empty()` 是"无声音"，
      线格式解出来却是"有一个 `minecraft:` 声音"；
  (b) 一旦按注释"实装时只需加一行播放调用"，`Optional.of(minecraft:"")` 会被当成有效声音事件去播（静默失败或抛），
      "无声音"的页反而变成"有一个坏 id 的页"。
  这正是本次审查特意要查的那类"听起来对"的不变量。
- **建议修法**：把解码 lambda 改成显式拒绝空串，并让往返成为恒等：
  ```java
  s -> s.isEmpty() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(s)),
  ```
  或者直接用 `ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8)`（`ByteBufCodecs.java:317-332`，布尔标志，
  天然全函数且往返恒等），再 `.map(o -> o.flatMap(...), ...)`。同时把类注释里的"全函数"限定为
  "**解码侧**不存在值相关的失败分支"，别让它顺带承诺编码侧。

### M3. 加载日志的"扫描数"名不副实，JSON 语法错误会由 **vanilla** logger 报且不计入 — `NpcDialogueLoader.java:114-119`

- **现象/代码**：注释与文档都把这行 INFO 当成"本功能**唯一的运行时观测点**"，
  并用"扫描数恒为 0"作为"数据放错树"的判据：
  ```java
  BeLoongCore.LOGGER.info("[BeLoong] reloaded npc dialogues: {} file(s) scanned, {} dialogue(s) loaded",
          files.size(), entries.size());
  ```
  而 `files` 是 `SimpleJsonResourceReloadListener.prepare()` 的产物。
- **证据**：`SimpleJsonResourceReloadListener.java:44-52`——Gson 解析失败（`JsonParseException`）的文件
  被 `catch` 后**根本不放进 map**，只由**该类自己的** `LOGGER = LogUtils.getLogger()`（`:19`）打一条
  `Couldn't parse data file {} from {}`。⇒ `files.size()` 是"**Gson 解析成功**的文件数"，不是"扫描到的文件数"；
  且这条错误信息的 logger 名不是 `BeLoongCore.LOGGER`，`grep BeLoong` 抓不到。
- **影响**：
  (a) 一个 JSON 少个逗号的文件会**同时**满足"扫描数正常（比预期少 1）"和"没有任何 BeLoong 前缀的报错"，
      与 §5.3/§5.7 承诺的"单文件失败只丢弃该文件并打 ERROR"观感不符（隔离是做到了，可观测性没有）；
  (b) `docs/NPC系统总设计.md:496`「**唯一的运行时观测点**是加载完成后那行 INFO」与 §5.7 同文档内依赖
      `Failed to parse npc dialogue file` 的排障行（`:644` `:647`）自相矛盾；重复绑定的 WARNING（`NpcDialogueLoader.java:106`）
      也是一个观测点。
- **建议修法**：把计数改成名副其实的 `{} data file(s) loaded by Gson`，或在 `prepare()` 覆写里自行扫描目录计数；
  JSON 语法错误建议不要依赖 vanilla 的 logger（可覆写 `scanDirectory` 同级逻辑或至少在文档里写明"语法错误看 `Couldn't parse data file`，
  不是 BeLoong 前缀"）。文档里"唯一观测点"应改为"成功侧的唯一观测点"。

### M4. 数据包无法"取消"一个内置对话，且空 `pages` 的语义没有出口 — `NpcDialogueLoader.java:100-104`、`docs/NPC系统总设计.md:461,493-494,710`

- **现象/代码**：`entry.pages().isEmpty()` 只打 ERROR 并丢弃**该文件**：
  ```java
  if (entry.pages().isEmpty()) { BeLoongCore.LOGGER.error("... declares no pages, ignored", ...); return; }
  ```
- **证据**：从行为看这是"被丢弃"而不是"被注册成空对话"——`return` 只退出 `ifSuccess` 的 lambda，
  `parsed` 里不会留下该实体的条目（`NpcDialogueLoader.java:105-110`）。日志文案 `declares no pages, ignored`
  与行为**一致**，这点没问题。
- **影响**：但由此产生一个真实缺口——整合包作者**没有任何办法**从数据包侧关掉模组自带的某一条对话：
  - 同名文件覆盖不了（`FileToIdConverter` 的文件 id 固定是 `<namespace>:<文件名>`，同名就是同一份资源，被 pack 顺序覆盖，
    而"重复绑定"只发生在**文件名不同**却绑定同一实体的场合，见 §5.3 与 `NpcDialogueLoader.java:105-109`）；
  - 写 `"pages": []` 会被当成错误丢弃，模组原条目**仍然生效**（这是最容易被尝试的写法）；
  - 只剩全局 `enabled=false`（§5.7:650），粒度是全有全无。
  这与 §5.1 宣称的"数据随数据包分发"、§5.2 的"数据包可覆写"（§7:710「对话数据——由数据包覆写」）不完全匹配。
- **建议修法**：给"删除"一个明确语义，二选一：
  (a) `pages: []` 改成"删除该实体的对话"（DEBUG/INFO 级日志，不是 ERROR），并在文档里写明这是唯一的关闭方式；
  (b) 显式加 `"disabled": true` 字段。
  同时把"重复绑定的胜者由**文件名**排序决定"写进文档——现在的措辞容易被读成"由实体 id 决定"。

---

## 次要问题（Minor）

### m1. `apply()` 内 `ifSuccess` 里的 `return` 易被误读为"跳过后续文件" — `NpcDialogueLoader.java:99-110`
- **证据**：`DataResult.ifSuccess(Consumer)` 只是回调（`DataResult.java:184-187`），`return` 只结束该 lambda；
  对后续文件的遍历毫无影响。
- **建议**：写成 `if (!entry.pages().isEmpty()) { ... }` 的嵌套，或在 `:103` 加一行注释"只跳过本条，不跳出文件循环"。

### m2. "只认主手"的**理由**与实际机制不符（结论仍然正确） — `NpcDialogueHandler.java:22-23`、`docs/NPC系统总设计.md:540-541`
- **证据**：副手事件在服务端**经常**会派发，而不是"仅在主手 PASS 时才轮到"这种罕见情况——
  正因为本模组**从不消费动作**，主手结果由原版决定，而 `Minecraft.startUseItem:1720-1751` 在
  `!interactionresult.consumesAction()`（`:1741`）时会继续循环到 `OFF_HAND` 并再发一个
  `ServerboundInteractPacket`（`MultiPlayerGameMode.java:436-440`，`hand` 随包走）。
  ⇒ 一次右键在服务端通常收到 `interactAt(主手) → interact(主手) → interactAt(副手) → interact(副手)`，
  真正保证"只开一次"的是 `NpcDialogueHandler.java:50-52` 的 `MAIN_HAND` 过滤本身。
  **结论（不会重复打开）成立**，只是理由要改写。
- **建议**：注释改成"副手事件在服务端会照常派发，`MAIN_HAND` 过滤是唯一保证"。

### m3. `Config` 里 `enabled` 的文案是"界面"语义，实际是服务端"是否受理右键" — `Config.java:265-268`
- **证据**：`comment("Enable the simple NPC dialogue screen", "启用简易 NPC 对话界面")` +
  `.translation("beloong.configuration.npcDialogueEnabled")`；而它的唯一读取点是
  `NpcDialogueHandler.java:47` 的受理开关（文档 §5.6:628 描述正确："服务端据此决定是否受理右键"）。
- **影响**：整合包服主会以为关掉它只是"不显示界面"，实际是"右键完全无反应"（且无任何日志提示）。
- **建议**：文案改为"服务端是否受理 NPC 对话右键"。

### m4. 重复绑定的胜者口径没写清：排序键是**文件 id**，且 `ResourceLocation` 是 **path 优先** — `NpcDialogueLoader.java:85-87,105-109`
- **证据**：`Map.Entry.comparingByKey()` 用自然序 ⇒ `ResourceLocation.compareTo`，而它是
  **先比 path 再比 namespace**（`ResourceLocation.java:180-187`；namespace 优先的是另一个方法 `compareNamespaced`，`:190-193`）。
  同目录下所有文件的 path 前缀一致（`beloong/npc_dialogue/<名>`），所以实际是"`<名>` 的字典序靠后者胜，namespace 只做并列时的仲裁"。
  排序本身是稳定、确定的（这点没问题），但文档 §5.3:493-494 的"按 `ResourceLocation` 排序后后处理者胜"会被读成 namespace 优先。
- **建议**：文档改成"按**文件名**（`<命名空间>:<文件名>`，path 优先的自然序）靠后者胜"。

### m5. `.mapStream(buf -> (ByteBuf) buf)` 是冗余的，强转也多余 — `NpcDialogueOpenPayload.java:67`
- **证据**：`StreamCodec.mapStream(Function<O, ? extends B>)`（`StreamCodec.java:77-88`）只做缓冲区适配；
  `composite` 的返回类型 `StreamCodec<B, C>` 在赋值目标 `StreamCodec<RegistryFriendlyByteBuf, …>`
  下足以把 `B` 推成 `RegistryFriendlyByteBuf`（`StreamCodec.java:177-206`，首参是 `? super B`）。
  另外 `buf` 本身就是 `RegistryFriendlyByteBuf`（`ByteBuf` 的子类型），`(ByteBuf) buf` 是向上转型，写 `buf -> buf` 亦可。
- **影响**：无功能影响。注释里"带不带 mapStream，线格式完全一致"的判断是对的。
- **建议**：要么删掉 `.mapStream(...)`，要么保留但去掉强转。⚠️ 见"无法静态确认"——删掉后能否编译我没有验证。

### m6. `Page.CODEC` 的 `sound` 不校验声音注册表，且 `iron_golem.json` 里的 id 在 `sounds.json` 中不存在 — `NpcDialogueEntry.java:74`、`iron_golem.json:8`
- **证据**：`ResourceLocation.CODEC.optionalFieldOf("sound")` 只做 RL 合法性校验（`ResourceLocation.java:36-38,127-133`），
  没有 `SoundEvent` 查表；`assets/beloong/sounds.json` 里只有 `block.beloong.loong_palace_portal.*` 三条。
- **影响**：今天无影响（不播放）。但 `NpcDialogueEntry.java:69` 与 §5.2:457 的"实装时只需加一行播放调用"略乐观——
  还需要 `sounds.json` 条目与音频文件；且"畸形 `sound` 会导致**整个文件**被拒"这一点文档没写（`sound` 属于同一 record codec）。
- **建议**：文档补一句"`sound` 值本身不做注册表校验，也不影响加载；写错只会在将来播放时静默失效"。

---

## 建议（Nit）

- **n1.** `NpcDialogueEntry.java:116-117`「内联 lambda 的类型推断失败」——我无法在不编译的前提下证实；
  作为"为什么写成方法引用"的理由略显武断，建议改成中性表述（"通配符返回类型让内联 lambda 可读性更差"）。
- **n2.** `NpcDialogueEntry.java:114` 引用 `StructureEffectEntry:21-25` 是**准确**的
  （`StructureEffectEntry.java:21-25` 正是 `ResourceLocation.CODEC.comapFlatMap(...DataResult.error...)`），
  但两者并不同款：那边用 `comapFlatMap`，其编码方向 `BuiltInRegistries.MOB_EFFECT::getKey` 返回 `null` 时没有保护
  （见"越界发现"）。建议把"同款做法"限定为"同款的**解码**做法"。
- **n3.** `SimplePreparableReloadListener#reload` 的引用漏了中间的 `.thenCompose(stage::wait)`
  （`NpcDialogueLoader.java:58-59`、`docs/NPC系统总设计.md:505-506`）。不影响论证（只是屏障），但引用源码时最好照抄。
- **n4.** `NpcDialogueLoader.java:118` 的日志前缀 `[BeLoong]` 与 `BeLoongCore.LOGGER` 其它行的一致性没有统一约定
  （同类 loader 里是否都带前缀值得统一），纯风格。
- **n5.** 页数/文本长度上限的具体数值建议在文档里给一个推荐值（如 64 页 / 512 字符），
  免得数据包作者踩到 C1 的编码期上限才发现。

---

## 已验证正确的部分

以下每一条我都**主动去源码核实过**，不是"看起来对"：

1. **`flatXmap` 的选择与双向失败语义正确，未知实体类型确实只丢一个文件** — `NpcDialogueEntry.java:119-141`。
   `Codec.java:238-252`：`xmap = Codec.of(comap(from), map(to))`（两个方向都不能失败）、
   `comapFlatMap = Codec.of(comap(from), flatMap(to))`（只有解码能失败，**编码方向是裸 Function**）、
   `flatXmap = Codec.of(flatComap(from), flatMap(to))`（双向都是 `DataResult`）。
   反向也不返回 null：`MapEncoder.java:41-53` 的 `flatComap` 实现会 `prefix.withErrorsFrom(function.apply(input))`，
   返回 null 会 NPE——而 `encodeEntity`（`NpcDialogueEntry.java:135-141`）与 `decodeEntity`（`:122-128`）
   在任何路径上都返回非 null 的 `DataResult`。`DataResult.error` 被加载器 `ifError` 接住并打 ERROR，条目不入表。
2. **A3 的担忧不成立：`Page.CODEC` 的 `ResourceLocation.CODEC` 不会抛** — 这是本次最值得查的一条，答案是"前提错了"。
   `ResourceLocation.CODEC = Codec.STRING.comapFlatMap(ResourceLocation::read, ResourceLocation::toString)`（`:36-38`），
   而 `read` **try/catch 住** `ResourceLocationException` 并返回 `DataResult.error`（`:127-133`）。
   更关键的是整条 JSON 解码路径**没有任何抛点**：`JsonOps` 的 `getStringValue`/`getMap`/`getList`/`getStream`
   全部返回 `DataResult.error`（`JsonOps.java:115-122`、`:225-228`、`:279-288`、`:270-276`），
   且 `JsonOps.INSTANCE` 是 `compressed=false`（`:25`）⇒ 数字也不会被当成字符串（`:117`）。
   顺带：JSON `null` 会被 `JsonOps` 映射成字段"缺失"（`:209`、`:219`、`:236`、`:246`），
   必需字段因此得到"缺字段"错误而不是 NPE。⇒ `NpcDialogueLoader.apply()` 对畸形文件**不会抛**，
   "单文件失败只丢该文件"（`NpcDialogueLoader.java:41-43`）成立。
3. **`ifError/ifSuccess` 拒绝 partial 结果的推理正确** — `NpcDialogueLoader.java:88-92`。
   `Codec.list` 确实会产出"partial + error"：`ListCodec.java:74-77`（元素失败累积进 `result`）、
   `:89`（`result.map(...).setPartial(pair)`）。而 `DataResult.Error.ifSuccess` **不调用** consumer
   （`DataResult.java:313-315`），`ifError` 会调用（`:318-320`）⇒ 残件不会被注册、整文件被拒。注释与 §5.3:488-491 完全正确。
4. **`entries` 不加 `volatile` 的线程模型推理成立（已核实）** — `NpcDialogueLoader.java:52-73`。
   `SimplePreparableReloadListener.java:17-19`：`supplyAsync(prepare, backgroundExecutor)` →
   `thenCompose(stage::wait)` → `thenAcceptAsync(apply, gameExecutor)`，只有 `prepare` 在后台线程且它不碰 `entries`
   （`SimpleJsonResourceReloadListener.java:31-35` 只往局部 map 写）。
   服务端 gameExecutor 就是服务端自己：`MinecraftServer.java:1518-1519` 传 `(this.executor, this)`，
   对应形参 `(backgroundExecutor, gameExecutor)`（`ReloadableServerResources.java:104-105`）；
   `MinecraftServer` 是 `ReentrantBlockableEventLoop`（`MinecraftServer.java:170`）⇒ 任务进服务端主线程队列。
   `get()` 的调用点在 `PlayerInteractEvent.EntityInteract` 处理里，也在服务端主线程。⇒ **无跨线程可见性问题**。
5. **`Map.copyOf` 的可见性与 null 约束都不构成风险** — `NpcDialogueLoader.java:113`。
   `Map.copyOf` 先完整建表再返回，`this.entries = …` 是一次引用赋值：读方只可能看到旧的 `Map.of()` 或新的完整不可变表，
   **看不到半成品**。`Map.copyOf` 禁止 null key/value，但由第 1、2 条可知该 codec 不会产出 null 键或 null 值。
6. **`comparingByKey()` 与自然序一致，`put` 的"后处理者胜"语义成立** — `NpcDialogueLoader.java:85-87,105-109`。
   `comparingByKey()` = `Comparator.naturalOrder()` ⇒ `ResourceLocation.compareTo`（`ResourceLocation.java:180-187`）；
   同 key 的 `HashMap.put` 返回旧值，因此"先前的 put 返回非 null ⇒ 检出重复"成立，且胜者由排序唯一确定，不依赖文件系统枚举顺序。
7. **`EntityInteract` 确实**双端**派发，`isClientSide()` 早退不是死代码** — `NpcDialogueHandler.java:39-45`。
   javadoc 明写「fired on both sides」（`PlayerInteractEvent.java:106`）；派发点 `Player.java:1094` 是 common 代码；
   客户端路径 `MultiPlayerGameMode.interact`（`MultiPlayerGameMode.java:436-440`）在发完包后
   `return … player.interactOn(target, hand)`，即客户端也会走同一条 common 路径。
8. **"一次右键最多打开一次对话"成立** — `NpcDialogueHandler.java:50-52`、`:71-75`。
   `ServerboundInteractPacket` 每个包只带一个 hand（`ServerGamePacketListenerImpl.java:1617-1628`），
   服务端每个包最多触发一次 `EntityInteract`，副手那次被 `MAIN_HAND` 过滤掉。
   另外**按住右键不会反复重开**：`Minecraft.handleKeybinds()` 只在 `overlay == null && screen == null` 时调用
   （`Minecraft.java:1847-1849`），而本包会 `setScreen`，所以第二次 tick 起就不再走 `startUseItem`。
9. **`isClientSide()` 排在读配置之前，这条顺序既必要也充分** — `NpcDialogueHandler.java:43-49`。
   物理客户端上 `player.level()` 是 `ClientLevel` ⇒ `isClientSide()` 为真 ⇒ **在** `Config.NpcDialogue.enabled.get()`
   （第 47 行）之前返回，不会碰到未同步的服务端配置；专用服务器/内置服务端上该值已随 `SERVER_SPEC` 加载。
10. **"不手写距离校验"的理由成立** — `NpcDialogueHandler.java:28-29`。
    `ServerGamePacketListenerImpl.handleInteract` 先查世界边界（`:1589`），再 `player.canInteractWithEntity(aabb, 1.0)`（`:1594`），
    后者是真实距离判定：`Player.java:2297-2300` 用 `Attributes.ENTITY_INTERACTION_RANGE`（`:2290`）算 `d0` 再比 `distanceToSqr`。
11. **载荷形状的理由（不放 `EntityType`）成立** — `NpcDialogueOpenPayload.java:23-34`。
    客户端反查 `EntityType` 确实需要一个注册表查表；改发 `EntityType#getDescriptionId()` 字符串 +
    网络 id 后，解码侧不存在"查不到"的分支。
12. **`handleClient` 的线程、方向、类加载都正确** — `NpcDialogueOpenPayload.java:60-84`、`BeLoongCore.java:155-158`。
    `PayloadRegistrar` 默认 `thread = MAIN`（`PayloadRegistrar.java:29`），注册时自动包 `MainThreadPayloadHandler`
    （`:166-168`）⇒ 直接 `setScreen` 安全；`playToClient` 声明 `PLAY + CLIENTBOUND`（`:44-46`），与"服务端→单个玩家"一致；
    未调 `optional()` **不是问题**——payload 的双端都有本模组注册（`BeLoongCore.java:151-158`），
    且同一份注册里 `TreasureSyncPayload` 同样非 optional，语义一致。
    `handleClient` 里对 `NpcDialogueScreen`（`@OnlyIn(Dist.CLIENT)`）的引用只出现在方法体内，
    方法引用（`:158`）只需要 common 侧的签名 ⇒ 专用服务器上不会触发该类加载。
13. **`entityId` 找不到实体时的降级链完整** — `NpcDialogueScreen.java:132-150`：
    先挡空 `pages`（`:135-137`），再 `minecraft.level == null ? null : level.getEntity(id)`（`:140`），
    取名时 `nameKey` → 实体显示名 → `fallbackNameKey`（`:144-148`）。服务端侧目标被移除也不会走到派发：
    `handleInteract` 在 `entity == null` 时直接 `return`（`ServerGamePacketListenerImpl.java:1588`）。
14. **文档里的原版行号引用全部准确（抽查 ≥3 处，且跨 4 份缓存 jar 一致）** —
    `MinecraftServer.java:1511` = `new MultiPackResourceManager(PackType.SERVER_DATA, …)`（§5.3:477）✓；
    `Minecraft.java:491` = `new ReloadableResourceManager(PackType.CLIENT_RESOURCES)`（§5.3:478）✓；
    `MinecraftServer.java:1512-1519` 把 `(this.executor, this)` 传给 `loadResources`（§5.3:508-509）✓；
    `FallbackResourceManager` → `PackResources.listResources(packType, …)`（§5.3:479）✓
    （`FallbackResourceManager.java:170`、`PathPackResources.java:76` `this.root.resolve(packType.getDirectory())`、
    `PackType.java:4-5` = `assets`/`data`）。
15. **其他被核实的文档事实**：`StructureEffectEntry:21-25` 引用准确（见 n2）；`iron_golem.json` 大小 297 B 与 §8:753 一致；
    「分组标题语言键两处复用」（§7:703）在 `zh_cn.json` 里确有 `beloong.configuration.npc_dialogue` 与 `.tooltip`；
    配置分宿（§5.6）与 `Config.java:50-71` / `:262-270` 完全对应；`AddReloadListenerEvent.addListener(PreparableReloadListener)`
    确实存在（`AddReloadListenerEvent.java:44`），`ReloadableServerResources.java:114` 每次加载都会 post 它 ⇒
    `BeLoongCore.java:194` 的注册方式与"启动 + `/reload` 都重载"一致。

---

## 无法静态确认的项

| 点 | 需要什么验证动作 |
|---|---|
| C1 的实机表现：超长 `text` 时玩家看到的确切断线文案、是否连带影响同服其他玩家 | 造一个 `text` 长度 > 32767 的数据文件，右键触发，看服务端日志与客户端断线提示（预期：仅该玩家掉线） |
| M1 的界面胜负：村民场景下最终留下的是交易界面还是对话界面 | 为 `minecraft:villager` 配一条对话，空手右键，看客户端实际屏幕（预期交易界面覆盖对话） |
| m5 删掉 `.mapStream(...)` 后能否编译 | 在有 `gradlew` 的空闲窗口跑一次 `compileJava`（本次按要求未运行构建） |
| n1 内联 lambda 是否真的推断失败 | 同上，编译验证 |
| 首次加载失败是否会中断服务端启动 | `MinecraftServer.java:297` 的 `resources` 来自 `worldStem`，而首次加载走 `WorldLoader.java:49` 的同一条链；其失败能否中断启动取决于调用方 `join()`，我未逐行确认 |
| `Page.CODEC` 对 `"pages": [null]` 的最终错误文案 | 造该 JSON，看日志中的 `Failed to parse npc dialogue file …` 消息（预期为"Not a JSON object: null"，不崩） |

---

## 越界发现

> 按约定只报告、不修改。均不在本次审查范围内。

1. `StructureEffectEntry.java:25`：编码方向用了**裸 Function** `BuiltInRegistries.MOB_EFFECT::getKey`
   配 `comapFlatMap`。对未注册的 `MobEffect` 实例 `getKey` 返回 `null`，
   而 `MapEncoder.comap`（`MapEncoder.java:22-38`）会把 null 直接交给下游 `encode`。
   这正是 `NpcDialogueEntry.java:130-141` 明确用 `flatXmap` + 显式 `DataResult.error` 规避的那个坑；
   `StructureEffectEntry` 那边没有保护。今天大概不可达（`effect` 只能由注册表解码得来），但它是同族代码里的不一致。
2. `Config.NpcDialogue.enabled` 的 `comment`/`translation` 文案（`Config.java:265-267`）与服务端语义不符，
   见 m3；`beloong.configuration.npcDialogueEnabled` 这个键名/文案在语言文件里同样是"界面"措辞。
3. `NpcDialogueScreen`（客户端，未在范围内）在 `open()` 里没有对 `payload.pages().size()` 做上限校验，
   与 C1 同源；即便服务端将来加了上限，客户端作为"永不抛边界"也值得加一条防御（`:135` 已经为空 pages 做了同类防御）。
