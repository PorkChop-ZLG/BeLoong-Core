# NPC 系统代码审查总报告

> 审查基线：`24bb701`（分支 `NPC`）
> 审查方式：**5 个并行只读审查子代理 + 3 个对抗性核验子代理**，全程未改一行源码
> 审查对象：通用 NPC 基类、地黄龙、NPC 对话系统（服务端+客户端）、调试命令、资产、总设计文档
>
> **本文件是本次审查的唯一入口。** 8 份过程报告（5 审查 + 3 核验）见文末「报告索引」，
> 它们保留全部证据链；本文件只给去重后的结论与修复清单。
> **冲突时以核验报告为准**——原审查报告有 1 条主要结论被推翻、2 条修法被判定无效、
> 多处量级与引用错误，已逐条列在第五节。

---

## 一、总体结论

**代码质量良好。** 需要动代码的只有 **9 条**（1 条 P0 + 8 条 P1），其余全是
文档/注释与代码不符（32 条）或可选优化（18 条）。**没有发现设计错误**——
三层架构（通用基类 / 具体 NPC / 服务端权威对话）与"尽量用原版机制"的取舍，
在核验中逐环节成立。

核验重定级后汇总（三份核验报告去重合并）：

| 级别 | 条数 | 性质 |
|---|---|---|
| **P0** | **1** | 载荷无上限 ⇒ 编码期异常 ⇒ **玩家断线** |
| **P1** | **8** | 可达的功能性/观感缺陷 |
| **P2** | **13** | 需异常输入或苛刻边界，或影响小（含 1 条稳健性、2 条性能） |
| **P3 + D** | **32** | **纯文档/注释与代码不符**，无运行时影响 |
| **P4** | **18** | 风格、命名、可选优化 |

**最值得注意的一件事**：`P3` 占了 32/72。这不是巧合——本项目的约定是
"注释要解释**为什么**并附原版行号"，注释因此承载了大量**可验证的事实断言**，
一旦原版行号或数值写错，错误会通过"照抄注释"扩散（本次已实证：
一个 `256×256` 的错误贴图尺寸从代码注释扩散进总设计文档的两处表格与 4 份计划文档）。
**注释里的数字是需要维护的资产，不是修辞。**

---

## 二、P0（1 条，必须修）

### P0-1 对话载荷无上限 ⇒ 编码期异常 ⇒ 玩家断线

**链路**（核验已端到端复现，全部为 1.21.1 + NeoForge 21.1.236 源码）：

```
数据包覆写 pages（JSON 侧 Codec.list / Codec.STRING 均无上限）
  → 报文携带 List<Page>（NpcDialogueOpenPayload:44,64）
  → ByteBufCodecs.list() 上限 Integer.MAX_VALUE（ByteBufCodecs.java:351-353）
  → 单页键超 32767 字符则 stringUtf8(32767) 抛（ByteBufCodecs.java:135 → Utf8String.java:33-35）
  → ClientboundCustomPayloadPacket 未覆写 isSkippable() ⇒ 默认 false（Packet.java:17-19）
  → PacketEncoder.java:36-42 对非 skippable 包**原样重抛**
  → 编码发生在 netty 线程（Connection.java:363-370）⇒ 调用点 try/catch 抓不到
  → Connection.java:144-182 断线（"Internal Exception"）
```

**触发条件**：单页键 > 32767 字符，或页数总量超缓冲预算（64 × 32767 汉字 ≈ 6.3 MB，已逼近 8 MB）。
触发者是**数据包作者**（含手滑），不是玩家。

**修法必须分两层，缺一不可**：

1. **加载期校验**（`NpcDialogueLoader`）——单页键用 `Codec.string(1, N)`（DFU `Codec.java:486-497`
   走 `validate`，保住"单文件隔离"），页数上限按**字节预算倒推**而不是拍数字（例如 ≤16 页、单键 ≤256 字符）。
2. **客户端 clamp** —— 真正被**渲染**的字符串是 `Component.translatable(text).getString()`，
   来自 `lang` 文件，**不受加载期校验约束**；`NpcDialogueScreen` 必须自己限制
   揭示长度/行数（同时消掉 P2-1 的排版成本）。

> ⚠️ **只加 `list(MAX_PAGES)` 是无效的**：`writeCount` 自己也会抛 `EncoderException`。
> 两层都要做。

---

## 三、P1（8 条，应当修）

### P1-1 `walkTo()` 静默失效（先攻击、再下令行走）

`NpcAttackGoal` 的拆解链无条件 `nav.stop()`：`MeleeAttackGoal.stop():94` 的 `nav.stop()`
**没有条件**（`if` 只包住 `setTarget(null)`），而 `GoalSelector.tick():88-89` 只要
`canContinueToUse()` 为 false 就调 `stop()`。而 `walkTo()` 里的 `setTarget(null)`
（`NpcEntity.java:286`）本身就是 `canContinueToUse()` 返回 false 的充分条件
（`MeleeAttackGoal.java:64-66`）。

⇒ 时序：T 下发路径 → T+1 仲裁停 goal → `nav.stop()` 抹掉路径 → **无人重发**。
间歇性、无报错。

**两种修法**（都已在核验中推演过，**核验报告推荐的两条修法都不成立，见第五节**）：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（精确，推荐）** | 指令世代号：`walkTo()` 递增序号；`NpcAttackGoal.stop()` 覆写，只在"被停的仍是当前世代"时才 `nav.stop()` | 需覆写 `stop()`（`super.stop()` 拦不住），改动稍多 |
| **B（简单）** | `NpcAttackGoal.stop()` 不调 `super.stop()`，自补 `setAggressive(false)` + `clearAttackCommand()`，并把 `nav.stop()` 从 `clearAttackCommand()` 摘掉 | 目标自然死亡时不再自动停步（会沿陈旧路径多走一段） |

### P1-2 `/summon` 出来的 NPC 会被原版敌对生物索敌围打

`Entity#load` 在 `Entity.java:1854` **无守卫**写 `invulnerable`，`/summon` 的 NBT 无该键
⇒ 字段变 false ⇒ `isInvulnerable()` false ⇒ `canBeSeenAsEnemy()` true
（闸门两条：`TargetingConditions.java:73` 的 `canAttack` → `LivingEntity.java:899-901`，
与 `TargetGoal.java:49`）⇒ 僵尸/骷髅/监守者会把 NPC 当合法目标。

伤害仍然打不进（`isInvulnerableTo` 是覆写的，不看该字段），但**行为观感是错的**，
且 `NpcEntity.java:44-48` 那句"构造函数里的 set 只为让字段与 NBT 一致"在 `/summon` 路径上**不成立**。

**修法**：覆写 `readAdditionalSaveData` 重新 `setInvulnerable(true)` ——
关键前提是 `Entity#load` 在 `:1894` **才**调 `readAdditionalSaveData`，所以能盖过 `:1854` 的写入。
**不要**改成覆写 `isInvulnerable()` 恒 true（那会让 `/kill` 与虚空也失效，破坏 P0 之外的唯一管理后路）。

### P1-3 爆炸与流体仍能推动 NPC

`isPushable()` 与 `KNOCKBACK_RESISTANCE` **不冗余**（各挡一条路径），但还有两条缺口：

- 爆炸走 `EXPLOSION_KNOCKBACK_RESISTANCE`（默认 0，`Explosion.java:294/304`）
  ⇒ 补 `.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0D)`；
- 流体流**直接 `setDeltaMovement`**，走 `isPushedByFluid()`（`Entity.java:3358/3391`，默认 true，
  `LivingEntity`/`Mob` 都没覆写）⇒ **必须另加覆写**，只补属性堵不住。

### P1-4 右键交互困难：准星拾取半径是 0.0F

模型实测 **2.01 × 2.85 × 9.02 格**（z 跨度 −3.82~+5.20），碰撞箱只有 1.5 × 2.5。

准星拾取走 `ProjectileUtil.java:68-76`，膨胀量是 `getPickRadius()` = **0.0F**
（`Entity.java:2231-2233`）。⇒ 玩家必须把准星精确压在 1.5×2.5 的核心盒上才能右键，
龙的头尾部分**看着能点、实际点不到**。

**修法**：覆写 `getPickRadius()`（给一个合理膨胀），并考虑同时放宽碰撞箱宽度。

### P1-5 对话正文纵向不适配（GUI scale 4 下 3 行就撞箭头）

`TEXT_TOP = 0.860` 向下生长、`ARROW_Y = 0.964`，无 clamp。核验独立算过：

| 屏高 | 各行 y | 箭头盒 | 后果 |
|---|---|---|---|
| 270（GUI scale 4，**最常见自动档**） | 232 / 243 / 254 / 265 | [251, 269] | 第 3 行压箭头、第 4 行部分出屏 |
| 360 | 309…364 | [338, 356] | 第 4 行压箭头、第 6 行全出屏 |

行容量 = `0.80 × 480 / 9` ≈ **42 汉字/行** ⇒ **2 行（84 字）以内才安全**。
当前 shipped 文案 21/32 字，所以尚未暴露。

**修法**：正文区高度 clamp + 超长时压缩行距/缩小字号，或分页。

### P1-6 为村民/马这类实体配置对话时，界面会被原版交互顶掉

文档与注释写的理由是"空手右键本无任何原版行为"——**这句是错的**。
反例（均已核实）：`Villager.java:329-356`（空手 → `:351` 开交易界面）、
`AbstractHorse.java:708-731`（空手 → `:728` 上马）、`Boat.java:802-813`、
`ItemFrame.java:362-392`（空手旋转）、`Pig.java:135-142`（空手骑乘）。

语义上：**"保住原版物品交互"这句本身没错**（`NpcDialogueHandler:63-65` 主手非空即 return），
错的是"空手无原版行为"这个**理由**。`trigger: any` 也不是"抢走"而是"叠加"。

**修法**：改掉理由的措辞（P3），并**在文档里写明"为这类实体配置对话在本版本无效"**——
因为两个界面会在同一 tick 抢屏（模组载荷在 `Player.java:1094` 内先发、
`startTrading` 后发，同一 netty FIFO 队列）。这条需要一次实机确认先后。

### P1-7 整条龙没有影子

`shadowRadius` 默认 `0.0F`（`EntityRenderer.java:31`），只在 >0 时绘制
（`EntityRenderDispatcher.java:168-176`），`GeoEntityRenderer extends EntityRenderer`
且 GeckoLib 不管这个值。⇒ 一条 9 格长的龙**贴着地面悬浮**。

**修法**：构造器里给一个值。**但 2.0F 没有依据**——原版最大惯例是 1.0F（不死马），
末影龙只有 0.5F（`EnderDragonRenderer:43`）。建议 **0.7~1.0** 起调。

### P1-8 视锥剔除用的是碰撞箱 ⇒ 头尾还在画面内就整体消失

`EntityRenderer.shouldRender` 用 `getBoundingBoxForCulling().inflate(0.5)`
（`EntityRenderer.java:52-70`），而它落到 `Entity.java:3030-3032` 的碰撞箱 = 1.5 × 2.5，
模型实测 9.02 格长。

**修法**（两条，推荐前者）：

- 覆写 `getBoundingBoxForCulling()`，用模型的实际范围。
  **现成的正确数值来源**：geo 里的 `visible_bounds_width: 12` / `visible_bounds_height: 5` /
  `visible_bounds_offset: {0, 1.5, 0}`（作者已标好，见第四节"新发现"）；
- 或 `noCulling = true` —— **注意不是"永不剔除"**：`EntityRenderer:53` 的距离门在前，
  仍会生效（约 210 格）；原版末影龙自己就用这招（`EnderDragon.java:104`）。

---

## 四、P2 与 P3 摘要

### P2（13 条，择要）

| 编号 | 内容 | 备注 |
|---|---|---|
| P2-1 | **每帧重画全部已揭示行**（`NpcDialogueScreen:328-343`），长页 ≈780 行/帧、绝大多数在屏外 | 与 P0-1 第 2 层一起修最省事 |
| P2-2 | 自动换行成本 Θ(n·L)：`current` 超宽即 flush，被测串长上界是**行容量**不是整页 | 核验推翻了原报告的 O(n²)（见第五节） |
| P2-3 | Molang 全局变量未清理（`MathParser` 共享实例、渲染后只 `clearActor`） | 机制成立但**可达性未证实**；修法便宜（覆写 `doPostRenderCleanup` + `super.`），建议顺手做 |
| P2-4 | `sound` 线格式往返不一致：`Optional.empty()` → `""` → 解码成 `Optional.of(minecraft:"")`（`isValidPath("")` 为 true） | latent，今天零影响（不播放），但注释声称"与 CODEC 语义一致"是假的 |
| P2-5 | F11/改 GUI scale 会重启当前页打字机（`resize → rebuildWidgets → init → loadPage`） | 修 `loaded` 标记还需配 rewrap，否则留下旧宽度排版 |
| P2-6 | `§` 颜色码在自动换行续行上丢失（每行独立 `Component.literal`） | shipped 文案下不可见 |
| P2-7 | 选项区在 h=270 下**连一颗 20px 按钮都放不下**（可用高度 `0.035·h ≈ 9.5px`） | 选项向上排、上界是 `RULE_Y` |
| P2-8 | 漏发 `ScreenEvent.BackgroundRendered` | 影响依赖该事件的第三方 |
| P2-9 | 界面未持有 Entity 引用 ⇒ 无悬垂；但 NPC 死亡/NPC 被移除后界面停留 | 观感 |
| P2-10 | `NoAI` 下 `serverAiStep` 整段不跑 ⇒ 攻击指令兜底与 goal 一起休眠 | 全项目 grep **无任何 `setNoAi(` 调用** ⇒ 理论风险 |
| P2-11 | 空手右键的"原版行为"覆盖面（见 P1-6） | 与 P1-6 同源 |
| P2-12 | `MobCategory.MISC.isPersistent` 只影响**自然生成器**，不是"实体持久" | 文档误解 |
| P2-13 | 空 `pages` 被当错误丢弃 ⇒ **没有干净的按条关闭手段**（见第五节 A-M4） | 行为可用但会打 ERROR、措辞误导 |

### P3 + D（32 条，全部是"文档/注释与代码不符"）

**D1（唯一一条"照文档改会把东西改坏"）**

| 项 | 实情 |
|---|---|
| **贴图尺寸 512×512，而 geo 声明 256×256** | 代码是**对的**：GeckoLib 按 geo 声明的 `textureWidth` 归一化 UV（`BakedModelFactory:177` → `GeoQuad:26-29` 的 `u /= texWidth`）。**照文档把 `texture_width` 改成 512 会让 UV 分数减半、只采样左上 1/4、毁掉模型。** 旁证：DiHuang-Loong 的 DS 版 geo 同样声明 256 且配 512 贴图（作者一贯的已出货配置）。**错处 4 个副本**：总设计 `:364`/`:747`、`DihuangLoongModel.java:23`、`plans/2026-09-21-…:56`/`:58`。**修法：只改文档，并在旁边写明"不要动 geo"。** |

**D2（数字/机制描述错，14 条）**

| 项 | 文档/注释 | 实际 |
|---|---|---|
| 末页状态机 | 总设计 `:587-588`、类 javadoc `:26-34`、plan `:139-149` 写"末页打完→WAIT_CLICK→点击才弹选项" | 末页从 `TYPING` **直接** `showOptions()`（`NpcDialogueScreen:226-232`）；箭头的"继续"只出现在中间页 |
| `TEXT_TOP` 方向 | 总设计 `:598` 写"**自下而上**排版" | 锚定**首行顶部、只往下长**（`NpcDialogueScreen:48-55`） |
| `GOLD`/`GOLD_BRIGHT` 归属 | 总设计 `:598` 说成"选项按钮悬停插值" | 是**装饰线与箭头**（`:317-319`、`:353-354`）；按钮用 `NORMAL_RGB 0x1A1F26 → HOVER_RGB 0xC8A05A` |
| `lookTime` | 注释与文档写 40~80 tick | **20~40**（`Goal#adjustedTickDelay` 对 `requiresUpdateEveryTick()==false` 取 `positiveCeilDiv(x,2)`）；只需改 **2 处** |
| 创造/旁观排除位置 | `NpcEntity.java:262` 写"与 `MeleeAttackGoal.canUse()` 保持一致" | 在 `canContinueToUse():74` 与 `stop():89` |
| `createMobAttributes()` 项数 | 总设计 `:343-345` 说只有 5 项 | **21 项**（`LivingEntity.java:321-343` 的 20 项 + `Mob.java:159-161` 的 `FOLLOW_RANGE`）；不含 `ATTACK_DAMAGE` 这一点是对的 |
| 三处原版行号 | `LivingEntity.java:1084` / `Mob.java:437` / `Entity.java:1759` | **1143** / **461** / **1854**；`isInvulnerableTo` 方法体 **2681-2687**（`!isCreativePlayer()` 在 **2683**）。**共污染 8 处**（含 `NpcEntity.java:186`、总设计 `:87`/`:795`） |
| `DihuangLoongModel` 职责 | 总设计 `§4.2` 写"提取 `renderLayers` 与 root 骨骼" | 该类只有 3 个资源 getter + `applyMolangQueries` + 常量；`renderLayers` 在渲染器侧（`GeoEntityRenderer:52`），骨骼由 `BakedModelFactory:133-141` 加载 |
| 含 `head_yaw` 的动画数 | 多份 plan 写 34 | **35 个**（210 处 = 35×6，5 根骨骼）；**错处 5 处**（模型 `:75` + 4 份 plan） |
| GeckoLib 方法名 | 注释引 `shouldCrashOnMissingBone()` | 4.9.2 真名 **`crashIfBoneMissing()`**（`GeoModel:91-93`）；需改 **3 处** |
| "约 11 tick 后递减到 0" | `NpcEntity` 类注释 + 总设计 `:221-222` | **第 11 tick 起递减、第 20 tick 才到 0** |
| `sounds.json` | `iron_golem.json` 的 `sound: beloong:dialogue.iron_golem.1` | **未定义**（`sounds.json` 只有 3 条 `block.beloong.loong_palace_portal.*`）。接线后**会打 WARN**"Unable to play unknown soundEvent"（`SoundEngine:433-437`），不是静默 |
| 加载日志语义 | 总设计 `§5.3` 称打印的是"扫描数" | 是 **Gson 解析成功数**（`SimpleJsonResourceReloadListener:44-52` 的 try/catch 包着解析）；"放错树 → 0"这个主症状仍可检出，但"唯一观测点"的说法与 `§5.7` 自相矛盾 |
| 行数与编号 | "对话系统 655 行"、"§十 的 T10"（悬空）、地黄龙"50 行" | 655 复现不出（453 或 1060）；T10 在 plan 里；`49` 行 |

**D3（措辞，5 条）**：空白选项的文案键硬编码表述、`§5.4` 流程图漏 `instanceof ServerPlayer`、
空 `pages` 处理未写、选项按钮视觉规格全缺、`§八` 行数会随注释漂移（已加说明）。

### P4（18 条）

命名/风格/可选优化。择要：`OPTION_GAP` 声明未用、`onClick` 覆写的是 NeoForge 已 `@Deprecated`
的 2 参重载、`onClose` 覆写冗余（`popGuiLayer` 等价）、narration 未用 `defaultButtonNarrationText`、
每帧 `new Component` 无缓存、emoji 代理对可能被切半、比例/像素单位混用、`@OnlyIn` 重复、
`NpcDialogueHandler` 注释未写明"主线程"前提等。

---

## 五、被推翻的结论与修法（**这一节是核验的主要产出**）

### 5.1 一条主要结论不成立

| 原结论 | 裁决 |
|---|---|
| **A-M4**："数据包**无法**关闭某条内置对话，只剩全局 `enabled`" | **不成立。** 同名文件是**影子替换**：`SimpleJsonResourceReloadListener:38-40` → `FileToIdConverter:30-32` → `MultiPackResourceManager:89-98` → `FallbackResourceManager:154-199`（`:176` 对同一 `ResourceLocation` 反复 `put`，`:163` 按优先级递增 ⇒ 每路径只留胜出者）；世界数据包在 `MinecraftServer:1568-1604` 被 append 到最后。⇒ 把 `data/beloong/beloong/npc_dialogue/iron_golem.json` 覆盖成 `"pages": []` 即可关掉该条。**准确说法**：没有**干净的、有文档的**按条关闭手段（副作用是打一条措辞像"已忽略"的 ERROR）。 |

### 5.2 两条修法被判定无效

| 修法 | 裁决 |
|---|---|
| **C-C1 的修法**：`clearAttackCommand()` 去掉 `getNavigation().stop()` | **无效。** `MeleeAttackGoal.stop():94` 自己无条件 `nav.stop()`，而 `NpcAttackGoal.stop()` 先调 `super.stop()`。删那行只去掉一个重复。 |
| **核验报告推荐的方案 A**：把"清攻击指令"延后到 `customServerAiStep()` | **无效。** 延后一拍只是把"抹掉"推后一拍：`GoalSelector.tick():88` 见 `canContinueToUse()==false` → `:89 stop()` → `:94 nav.stop()`，路径在 **T+1** 被抹、无人重发。原推荐的理由（"届时 `target` 已是 null，`MeleeAttackGoal.stop()` 不跑"）把"`stop()` 不跑"与"`stop()` 内的 `setTarget` 分支不跑"混为一谈——`stop()` 在 `canContinueToUse()` 为 false 时**必然**被调用。<br>⇒ 正确修法见 P1-1（必须让 `stop()` 不再无条件 `nav.stop()`；该报告的"备选 B"才是对的主修法）。 |

### 5.3 量级与引用错误

| 项 | 裁决 |
|---|---|
| **B-M1** 称 `wrap()` 是 O(n²) ⇒ 长页停顿"数秒~数十秒" | **量级错约 700 倍。** `current` 超宽即 flush 并清空（`:381-382`）⇒ 被测串长上界是**行容量 L** ⇒ 实际 Θ(n·L)。32767 字页 ≈ 6.9e5 次字符访问（**数十毫秒**），不是 5e8 次。 |
| **C** 引 `ProjectileUtil:108-110` 的 0.3F 膨胀说明右键拾取 | **引用错。** 准星拾取走 `:68-76`，膨胀量 `getPickRadius()` = **0.0F**（`:108-110` 是弹射物重载）⇒ 结论**比原报告更严重**。 |
| **B 的越界发现**："`Page.sound` 只解析未播放" | **错。** `sound` 确实上线（`NpcDialogueOpenPayload:44,64` + `Page.STREAM_CODEC:102-105`）。 |
| **E-C2** 称 `lookTime` 有 3 处需改 | **多报 1 处**（决策 17 里没有这个数字）。 |
| **E-C3** 的行号核对 | **漏 3 处**污染（`NpcEntity.java:186`、总设计 `:87`/`:795`）。 |
| **A 的越界发现**（`StructureEffectEntry` 裸 `getKey` 配 `comapFlatMap`） | 机制对（`MapEncoder:22-39` 裸传 null）但**不可达**：全 src 无任何 `.encode(`/`encodeStart`。 |
| **D-C1** 的两处越界推断 | ① 可达性**未证实**（举的"无 owner 的 DS 龙"无出处；最可能的 null-player 路径反而免疫）；② "需改 96 个动画"**错**，只需 35 个。 |
| **D** 称全仓 `shadowRadius` 零命中 | **错。** `TornadoRenderer:61` 有 `this.shadowRadius = 0.0F;`（故意的）。 |
| **D** 称"GeckoLib 4.9.2 无源码"／**E** 称"只能看 4.7.x" | **两者都不成立。** `D:\Minecraft\开源模组参考文件\Geckolib` **就是 4.9.2 源码树**（`gradle/libs.versions.toml` 写 `geckolib = "4.9.2"`，git `0d9d3ea3`），并与实际编译产物 jar 用 `javap -p` 交叉验证过。⇒ 相关 GeckoLib 行号**无需降级**。 |
| **D-M2** 对 `noCulling=true` 代价的描述 | **错。** 不是"永不剔除"：`EntityRenderer:53` 的距离门在前，仍生效（约 210 格）；原版末影龙自己就这么干。 |
| **E-C6** 称接线后会"静默无音" | **错。** `SoundEngine:433-437` 会打 WARN。 |
| **D 的资产表 loop 行** | 两个数字都错（实测 `true` 80 / `"hold_on_last_frame"` 4 / 缺 12；D 写 `true` 84 / hold 1）。 |

### 5.4 定级的系统性偏差

**两位文档向的审查者（E、D）普遍定级偏高**：8 条纯文档/注释问题被列为 Critical/Major。
按运行时影响重定级后，渲染/资产域的 P0 = 0、P1 = 2，其余 18 条全为 P3。
**这不是说文档错误不重要**（总设计是本次交付物），而是说
"文档错了"与"代码会崩"需要分开排序，否则修复队列会被文档项淹没。

---

## 六、本次审查新发现的、原报告未提的问题

| 项 | 内容 |
|---|---|
| **原版隐患** | `SimpleJsonResourceReloadListener:47-49` 的 `IllegalStateException("Duplicate data file ignored with ID …")` 是**不可达死代码**（`fileToId` 对 path 单射，map key 又是唯一 `ResourceLocation`），而且它**不在 `:50` 的 catch 列表里**（`IllegalStateException` 不是 `IllegalArgumentException`）——真触发会穿出 `prepare`、**崩掉整次数据重载**。不影响本模组，但值得在文档留一笔。 |
| **现成的修复素材** | geo 里的 `visible_bounds_width: 12` / `visible_bounds_height: 5` / `visible_bounds_offset: {0,1.5,0}` 在 GeckoLib 4.x 里是**死数据**（只被 `ModelProperties:39-46` 解析、全仓无消费者）——但它是 P1-8 剔除盒修复的**现成正确数值来源**。 |
| **性能盲点** | `NpcDialogueScreen:328-343` 每帧把**全部已揭示行**重画一遍（长页 ≈780 行/帧，绝大多数在屏外）。两份客户端报告都漏了这条真成本，只盯着 `wrap()`。 |
| **配置事实** | DiHuang-Loong 的 DS 版 geo 同样声明 256 且配 512 贴图 ⇒ 这是**作者一贯的已出货配置**，不是这张模型的特例。 |
| **文档遗漏（我方）** | 对话绑定的是**原版实体类型** `minecraft:iron_golem`，而 `enabled` 默认 `true` ⇒ **世界上任何一只铁傀儡**（村庄的、刷怪蛋的、别的模组生成的）空手右键都会弹「化龙」对话。作用域是全局的，总设计没写这一点。这些铁傀儡也不受 `NpcEntity` 语义保护（会正常被打死/消失）⇒ "对话中实体消失"是可达路径。 |

---

## 七、建议的修复批次

| 批次 | 内容 | 规模 | 建议 |
|---|---|---|---|
| **1** | **P0-1**（两层）+ P2-1/P2-2（同一根因，顺手） | 小 | **必做**，这是唯一能踢人的问题 |
| **2** | P1-1 walk 失效（方案 A）+ 针对它的实机复现动作 | 小 | **必做**，且**必须实机复现**确认真修好了（首尾两条修法都已翻车） |
| **3** | P1-2 / P1-3 / P1-4（实体语义与交互三连） | 小 | 建议做，互相独立 |
| **4** | P1-7 / P1-8（影子 + 剔除盒） | 极小 | 建议做，观感收益大、风险低 |
| **5** | P1-5 + P2-5/P2-6/P2-7（客户端排版与状态一组） | 中 | 建议做，需要一次 GUI scale 4 的实机验收 |
| **6** | **P3/D 全部 32 条**（文档与注释成对修） | 中 | 建议做。**注意 D1 那条：只改文档、并在旁边写明"不要动 geo"** |
| **7** | P4 18 条 | 小 | 可选 |

> **第 2 批的教训**：本次审查里"诊断正确但修法无效"出现了两次，都栽在
> `MeleeAttackGoal.stop():94` 那个**无条件** `nav.stop()` 上。改完之后
> **必须实机验证**"先 `/beloong npc attack`、再 `/beloong npc walk`"真的走起来了，
> 而不是看代码觉得对。

---

## 八、需要实机/构建才能确认的事项

1. walk 失效（P1-1）修复前后的实机对比；
2. 村民类实体配对话时，交易界面与对话界面谁最终留在屏幕上；
3. P0-1 的真实断线表现（用超大页数/超长键压测）；
4. GUI scale 4（h=270）下的排版观感与截图；
5. `wrap()` 的真实墙钟耗时（核验给的是模型估算，"数十毫秒"）；
6. 512 贴图是否为 256 版图的忠实 2×（需逐像素比对或看纹理细节）；
7. P2-3 Molang 污染的**可达性**（复核者未能证出一条真实路径）；
8. `onClick` 改用 3 参重载、删掉 `mapStream` 能否编译通过（**未跑构建**）；
9. 头随方向/头身分离的二次实机确认（本次审查未运行游戏）。

---

## 九、报告索引

### 过程报告（5 份审查）

| 文件 | 范围 | 行数 |
|---|---|---|
| `docs/reviews/2026-09-25-npc-dialogue-server-review.md` | 对话服务端链路 | 315 |
| `docs/reviews/2026-09-25-npc-dialogue-client-review.md` | 对话客户端界面 | 450 |
| `docs/reviews/2026-09-25-npc-entity-ai-review.md` | 实体与 AI | 527 |
| `docs/reviews/2026-09-25-npc-render-animation-review.md` | 渲染与动画 | 390 |
| `docs/reviews/2026-09-25-npc-doc-code-consistency-review.md` | 文档与源码一致性 | 348 |

### 核验报告（3 份对抗性复核，**冲突时以这三份为准**）

| 文件 | 复核对象 |
|---|---|
| `docs/reviews/2026-09-25-npc-verify-dialogue-payload.md` | 对话服务端 + 客户端界面 |
| `docs/reviews/2026-09-25-npc-verify-entity-ai.md` | 实体与 AI |
| `docs/reviews/2026-09-25-npc-verify-render-doc.md` | 渲染/动画 + 文档一致性 |

### 审查方法

- **5 个审查者并行**（按层/按域切分，互不写同一文件，全程只读）；每个都被告知
  "设计文档里写的理由也要独立验证"；
- **3 个核验者对抗性复核**：不接受报告结论为证据，必须从一手源码重新推导；
  统一重定级（P0–P4 + D1–D3 交付标记）；**专门检验"建议修法是否真的有效"**
  —— 这一步抓出了 2 条无效修法、1 条不成立的主要结论、12 处量级/引用错误；
- 原版行为一律核到 1.21.1 + NeoForge 21.1.236 源码；
- GeckoLib 一律核到 4.9.2（源码树 + `javap` 交叉验证实际 jar）。

**基线状态**：`24bb701` 已确认 `gradlew build` BUILD SUCCESSFUL；
审查与核验全程未运行构建、未改任何源码。

---

> **本次审查未做任何修复。** 修复范围待定，见第七节的批次建议。
