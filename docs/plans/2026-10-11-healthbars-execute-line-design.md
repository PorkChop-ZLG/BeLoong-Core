# Health Bars 斩杀线覆盖层设计（`beloong:execute`）

> **文档性质：** 「在 Health Bars 的左上角血条上显示真实斩杀线」这一特性的权威设计说明。
> **事实口径：** 所有上游事实都标注 `文件:行号`，来源为 Health Bars 官方源码树
> `D:\Minecraft\开源模组参考文件\HealthBars-1.21.1`（多加载器工程，共享代码在 `Common/`）
> 与本地反编译源 `build/review-src/neoforge-21.1.236/`。
> **状态：** 已获用户批准（2026-10-11）
> **选定方案：** 方案 A —— 注入 `GuiRenderingHandler` 的 GUI 专属私有方法
> **更新日期：** 2026-10-11

---

## 一、需求

| # | 需求 | 落实 |
|---|---|---|
| 1 | 联动 Health Bars 模组 | 它是既有可选依赖，本次只新增一个客户端 Mixin |
| 2 | 生物被挂上斩杀效果时，在**屏幕左上角那条血条**里显示**真实斩杀线** | 只影响 `GuiRenderingHandler` 的准星血条；世界血条不动 |
| 3 | 表现为**橙色半透明填充** `0→斩杀线` 区段 + 右端**一条竖直的线**标记真正的斩杀线 | 见 §六 常量 |
| 4 | Health Bars 是**纯客户端**模组（服务端不装），且**不得引入新的网络同步** | 见 §三：斩杀线走原版 mob effect 同步，零新包 |
| 5 | 区段**始终画满固定的 `0→斩杀线`**，与当前血量无关 | 用户 2026-10-11 裁定（不做「已跌破」变色/闪烁） |

**成功判据**：给一条已学斩杀的龙准星指向怪物时，血条上出现橙色区段与竖线，竖线位置等于该龙当前等级对应的斩杀线百分比（L1 = 5% … L16 = 20%）。

---

## 二、事实依据（读上游源码得到的硬事实）

| 事实 | 证据 |
|---|---|
| modId 是 `healthbars`，版本 21.1.0，包名 `fuzs.healthbars` | `neoforge.mods.toml`；`gradle.properties` 的 `modVersion=21.1.0`、`modMavenGroup=fuzs.healthbars` |
| 它是**纯客户端**模组 | `gradle.properties` 的 `modForgeDisplayTest=IGNORE_ALL_VERSION`；本项目 `mods.toml` 已声明 `optional` + `side=CLIENT` |
| 本项目**已经**依赖它 | `build.gradle:316` `implementation "curse.maven:health-bars-1367734:7122333"`；前置 Puzzles Lib 在 `:322` |
| 已有同目标的先例 Mixin | `mixin/healthbars/HealthTrackerHandlerMixin.java`（`@Pseudo` + `require=0`），且它注册在 **`client`** 列表 |
| 左上角那条血条的入口 | `GuiRenderingHandler.onAfterRenderGui`（`GuiRenderingHandler.java:41`），条件：`PickEntityHandler.getCrosshairPickEntity()` 命中 `LivingEntity`（`:48`）——即**准星指着怪物**时 |
| 真正的绘制在**私有**方法里 | `GuiRenderingHandler.java:178` `private static void renderHealthBar(GuiGraphics, MutableInt posX, MutableInt posY, float, Font, HealthTracker, LivingEntity, int barWidth)`；唯一调用点 `:108` |
| 条宽 | `HealthBarHelper.getBarWidth` → `getBarWidthByScale(clamp(scale,1,4))`，而 `getBarWidthByScale = 45×scale + 2` ⇒ **47 / 92 / 137 / 182** |
| **条高恒为 5** | `HealthBarRenderHelper.java:54` `guiGraphics.blitSprite(resourceLocation, posX, posY, (int)(width*percentage), 5)` |
| 传入的是**中心 X**，内部再减回 | `GuiRenderingHandler.java:180` 传 `posX.intValue() - 1 + barWidth / 2`；`HealthBarRenderHelper.java:53` 再 `posX -= (width / 2)` ⇒ `(P − 1 + W/2) − W/2` 两次整除**精确相消** ⇒ **条左 = `posX - 1`**（⚠️ 那个 `−1` 不会消失；2026-10-11 审查修正，见 §十四） |
| 每层 blit 都把 pose 往前推 | `HealthBarRenderHelper.java:55` `guiGraphics.pose().translate(0.0F, 0.0F, 0.03F)` |
| 半透明混合**已经开着** | `GuiRenderingHandler.java:69-73` `enableBlend()` + `blendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ZERO, ONE)`，且直到 `:123` 才 `disableBlend()` |
| 飘字画在条之后 | `GuiRenderingHandler.java:183-188`（`drawDamageNumber`），随后 `:189` `flush()` |
| 客户端能直接读到斩杀线 | `ClientPacketListener.handleUpdateMobEffect`（`:1668-1679`）用 `packet.getEffectAmplifier()` 建出 `MobEffectInstance` 并挂到实体上；`LivingEntity.getEffect(Holder<MobEffect>)` 可直接查 |
| 斩杀线由 amplifier 编码 | `ExecuteThresholdEffect.thresholdFraction(amp) = (amp + 1) × 0.005`（`ExecuteThresholdEffect.java:76-78`）；该设计**本来就是**为了客户端消费（见该类注释 `:28-32`） |
| 世界血条与 GUI 血条**共用**同一个绘制函数 | `InLevelRenderingHandler.java:110` 与 `GuiRenderingHandler.java:180` 都调 `HealthBarRenderHelper.renderHealthBar` |

---

## 三、为什么不需要新的网络通道（需求 4）

斩杀线是**每个目标各自不同**的量（取决于施斩杀者的技能等级），但它已经被编码进 **mob effect 的 amplifier**，而 amplifier 是原版 `ClientboundUpdateMobEffectPacket` 的一个字段 ⇒ 只要实体被客户端追踪，客户端就能拿到：

```java
MobEffectInstance fx = entity.getEffect(ModMobEffects.EXECUTE_THRESHOLD);   // 原版同步链
if (fx == null) return;                                                     // 没挂斩杀线 → 不画
float pct = ExecuteThresholdEffect.thresholdFraction(fx.getAmplifier());    // 0.05 .. 0.20
```

**⇒ 零新增 payload、零 `AttachmentType.sync`、零自定义包。** 这也正是当初把百分比塞进 amplifier 而不是放进附件的理由（附件只存服务端）。

---

## 四、方案选型

| | 方案 A（**已选**） | 方案 B | 方案 C |
|---|---|---|---|
| 注入点 | `GuiRenderingHandler.renderHealthBar`（私有）INVOKE 之后 | `HealthBarRenderHelper.renderHealthBar`（public）TAIL | 不碰 Health Bars，自己在 `RegisterGuiLayersEvent` 画一层 |
| 优点 | 参数已含全部几何与实体；**只被 GUI 路径调用** ⇒ 天然不误伤世界血条；不必复算锚点/立绘/配置 | 目标是 public，签名更稳定 | 与 Health Bars 零耦合 |
| 缺点 | 目标是私有方法，上游改名则静默失效 | 世界/GUI 共用 ⇒ 需 hack 区分（GUI 传真实 `posX`、世界路径传 `posX=0`），或接受世界血条也被画 | 要复刻准星拾取、可见性、锚点、`offsetWidth/Height`、立绘 42px、`barWidth` 推算……**持续漂移** |
| 复杂度 / 风险 | 低 / 低 | 低 / 中 | 高 / 高 |

**选 A 的理由**：它是唯一同时满足「几何自动跟随上游」「不误伤世界血条」「不复制上游逻辑」的方案；代价是一个**已知且可接受**的失效模式（上游改私有方法名 ⇒ 遮罩静默消失，见 §八）。

---

## 五、架构

```
mixin/healthbars/GuiRenderingHandlerMixin.java     ← 薄注入层，只做转发（无几何/颜色逻辑）
compat/healthbars/ExecuteLineOverlay.java          ← 全部逻辑：读 effect → 算像素 → 画
src/main/resources/beloong.mixins.json             ← client 列表追加一条（与既有 healthbars 同列）
Config.java                                        ← CLIENT_SPEC 新增 [execute_line] enabled
assets/beloong/lang/{zh_cn,en_us}.json             ← 该配置段的翻译键
```

**注入点**：

```java
@Pseudo
@Mixin(value = fuzs.healthbars.client.handler.GuiRenderingHandler.class, remap = false)
public abstract class GuiRenderingHandlerMixin {
    @Inject(
        method = "renderHealthBar(Lnet/minecraft/client/gui/GuiGraphics;"
               + "Lorg/apache/commons/lang3/mutable/MutableInt;"
               + "Lorg/apache/commons/lang3/mutable/MutableInt;F"
               + "Lnet/minecraft/client/gui/Font;"
               + "Lfuzs/healthbars/client/helper/HealthTracker;"
               + "Lnet/minecraft/world/entity/LivingEntity;I)V",
        at = @At(value = "INVOKE",
                 target = "Lfuzs/healthbars/client/helper/HealthBarRenderHelper;renderHealthBar"
                        + "(Lnet/minecraft/client/gui/GuiGraphics;IIFLfuzs/healthbars/client/helper/HealthTracker;"
                        + "Lnet/minecraft/world/entity/LivingEntity;ILfuzs/healthbars/config/ClientConfig$BarColors;)V",
                 shift = At.Shift.AFTER),
        require = 0, remap = false)
    private static void beloong$drawExecuteLine(GuiGraphics gg, MutableInt posX, MutableInt posY, float partialTick,
                                                Font font, HealthTracker tracker, LivingEntity entity, int barWidth,
                                                CallbackInfo ci) {
        ExecuteLineOverlay.draw(gg, posX.intValue(), posY.intValue(), barWidth, entity);
    }
}
```

**为什么 NOT `at = TAIL`**：该方法顺序是「画条 → 画飘字 → flush」。TAIL 会让橙色盖住伤害飘字；**INVOKE + shift AFTER** 把遮罩夹在「条之上、飘字之下」。此时 pose 已累积 `+0.03×N` 的 z 位移，遮罩天然压在条上面。

**纯客户端安全链**：
1. 注册在 `beloong.mixins.json` 的 **`client`** 数组 ⇒ Mixin 只在客户端应用，专用服务端**不加载该类**；
2. `@Pseudo` ⇒ 未装 Health Bars 时整体跳过；
3. 注入点 `require = 0` ⇒ 上游改私有方法名/签名时静默失效，不硬崩；
4. `ExecuteLineOverlay` **只被该 mixin 引用** ⇒ 服务端永远不会加载它（静态门 §九-③ 守这条）。

---

## 六、组件与常量

### 6.1 几何

```java
int   barLeft = posX - 1;                               // 见 §二：左边界比上游 posX 小 1
int   barTop  = posY;
int   barW    = barWidth;
int   lineX   = Mth.clamp(barLeft + Math.round(barW * pct), barLeft, barLeft + barW);
// 填充
gg.fill(barLeft, barTop, lineX, barTop + BAR_HEIGHT, FILL_ARGB);
// 竖线（上下各出头）；右缘用 lineLeft + LINE_WIDTH，宽度精确等于 LINE_WIDTH
int   lineLeft = lineX - LINE_WIDTH / 2;
gg.fill(lineLeft, barTop - LINE_OVERHANG,
        lineLeft + LINE_WIDTH, barTop + BAR_HEIGHT + LINE_OVERHANG, LINE_ARGB);
```

### 6.2 常量（色相由参考图反向标定）

对标定方法：同一条竖线分别落在「天空」与「血条」两种底色上成像，解 `fg = α·S + (1−α)·bg`
⇒ **α ≈ 1.00、原色 ≈ RGB(255,128,36)**（三点采样 (255,124,44)/(255,128,35)/(240,125,62)，
受 webp 重编码影响有抖动）；常量实际取 `0xFF8028` = RGB(255,128,40)，只差蓝通道 4（不可见）。

| 常量 | 值 | 依据 / 改动后果 |
|---|---|---|
| `FILL_ARGB` | `0x66FF8028` | α = 0x66 = 40%。**这是最需要实测微调的一个**：调大 → 血条越被盖住；调小 → 越看不出区段 |
| `LINE_ARGB` | `0xFFFF8028` | 不透明（参考图实测即不透明）。它是「真正的斩杀线」的唯一权威标记，必须锐利 |
| `BAR_HEIGHT` | `5` | 上游 `HealthBarRenderHelper.java:54` 的 `blitSprite(..., 5)` |
| `LINE_WIDTH` | `2` | 参考图竖线 6px ÷ 缩放(≈2.4) ≈ 2.5 |
| `LINE_OVERHANG` | `2` | 参考图竖线上下各出头 ≈4.5px ÷ 2.4 ≈ 1.9 |

### 6.3 配置（用户裁定：**只要 `enabled`**）

```java
// ⚠️ 配置值定义在 Config 上，**不要**直接写到 ExecuteLineOverlay 的静态字段里。
// 原因：Config 的 static 块在**双端**都会执行；若在那里触碰 ExecuteLineOverlay，专用服务端
// 会加载一个引用了 net.minecraft.client.gui.GuiGraphics 的类 ⇒ NoClassDefFoundError。
// 所以 Config 只持有 BooleanValue，客户端专属的 ExecuteLineOverlay 在绘制时读它。
CLIENT_BUILDER.push("execute_line");
public static final ModConfigSpec.BooleanValue EXECUTE_LINE_ENABLED = CLIENT_BUILDER
        .comment("Draw the execute threshold zone on the Health Bars GUI bar",
                 "在 Health Bars 的左上角血条上绘制斩杀线区段")
        .translation("beloong.configuration.executeLineEnabled")
        .define("enabled", true);
CLIENT_BUILDER.pop();
```

客户端侧读法（`ExecuteLineOverlay` 内，**唯一**被允许读该值的地方）：

```java
if (!Config.EXECUTE_LINE_ENABLED.get()) return;   // 关掉就不画
```

**必须同时补中英翻译键**（项目硬性规则，否则配置界面显示原始键名）：
`beloong.configuration.execute_line{,.tooltip}`、`beloong.configuration.executeLineEnabled{,.tooltip}`。
值的键名带**显式** `.translation(...)` 前缀——扁平名 `enabled` 会与其他段撞键。

### 6.4 锚点日志

仿既有 `HealthTrackerHandlerMixin` 的 `beloong$anchorLogged`：**遮罩首次成功绘制时**打印一次
`[BeLoong] healthbars: execute line overlay active`
⇒ 把「注入点静默失效」与「这次没怪挂斩杀线」在日志里区分开。

---

## 七、数据流

```
每帧 / 准星指着生物
GuiRenderingHandler.onAfterRenderGui                    (:41)
  └─ 上游算好锚点 / 立绘偏移 / barWidth / posY
     └─ renderHealthBar(...)                            (:178 ← 注入点所在)
          ├─ HealthBarRenderHelper.renderHealthBar      画条（白底 → 色条背景 → 凹槽 → 色条前景）
          ├─ ▶ ExecuteLineOverlay.draw(...)            我们：读 effect → 算 → fill + 竖线
          └─ drawDamageNumber(...)                      飘字（压在我们之上）
```

---

## 八、失败模式与边界

| 情形 | 表现 | 机制 / 处理 |
|---|---|---|
| 未装 Health Bars | 无任何效果 | `@Pseudo` ⇒ mixin 整体跳过，helper 不被加载 |
| Health Bars 配置关了 GUI 血条 | 我们也不画 | 上游 `:43-46` 直接 return ⇒ 免费获得 |
| 上游改了该私有方法名/签名 | **遮罩消失** | `require = 0` 静默不注入；**锚点日志不打印**，据此定位 |
| 上游把条高改成 ≠ 5 | 遮罩高度/位置偏移 | 常量 `BAR_HEIGHT`；**日志不会提示** ⇒ 见 §十二 升级必查项 |
| 实体没挂斩杀线 | 不画 | `getEffect == null` 早退 |
| amplifier 异常（过大/为 0） | 线跑到条外 | `pct` 与 `lineX` 双重 `Mth.clamp` |
| 条宽极端（47 / 92 / 137 / 182） | — | `lineX` 随 `barWidth` 线性伸缩，公式与宽度无关；`barWidth <= 0` 提前返回（今日不可达，留作防御） |
| 专用服务端 | 不加载 | mixin 在 `client` 列表 |
| 该实体在客户端不可见 | 上游的 `EntityVisibilityHelper` 已 return | 免费获得 |

---

## 九、验收

### 9.1 静态门（智能体自己跑）

1. `beloong.mixins.json` 的 **`client`** 数组含 `healthbars.GuiRenderingHandlerMixin`；
2. 该类上有 `@Pseudo`，且注入点 `require = 0`；
3. `compat/healthbars/ExecuteLineOverlay` **只被该 mixin 引用**（防服务端误加载）。
   这条尤其防 §6.3 那个坑：任何人（包括将来的自己）若从 `Config` 或其它双端类里引用 overlay，
   静态门会立刻变红——否则症状是**专用服务端启动时 `NoClassDefFoundError`**。
4. `beloong.configuration.executeLineEnabled{,.tooltip}` 与 `execute_line{,.tooltip}` 中英**齐备**；
5. `.\gradlew.bat build` 通过。

### 9.2 实机验收（需在能跑客户端的机器上做）

1. 给一条**成长 150** 的远古龙装上斩杀，砍监守者 → 左上角血条出现橙色区段，竖线在 **5%** 处；
2. 成长拉到 **300**（L16）→ 竖线移到 **20%**（500 血条上 ≈ 100 血的位置）；
3. **把怪打到半血：橙色区段宽度不变**（这是「始终画满固定区段」的判据）；
4. 伤害飘字仍清晰，未被橙色糊住；
5. 砍一只**没挂**斩杀线的怪 → 一点橙色都没有；
6. 卸掉 Health Bars 启动 → 客户端正常、日志无异常；专用服务端启动 → 无 `NoClassDefFoundError`。

---

## 十、决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | 注入 `GuiRenderingHandler` 的**私有** GUI 方法，而非 public 的 `HealthBarRenderHelper` | 后者世界/GUI 共用，需 hack 区分；前者参数已含全部几何且天然 GUI 专属 |
| D2 | 注入在 `HealthBarRenderHelper.renderHealthBar` 调用**之后**、而非 TAIL | 避免橙色盖住伤害飘字 |
| D3 | 橙色是**半透明填充**，不是参考图的空心描边 | 参考图的描边只是**标注区域**（用户 2026-10-11 澄清） |
| D4 | 区段**始终画满固定的 `0→斩杀线`**，不做「已跌破」变色/闪烁 | 用户裁定：遮罩只表达「斩杀线在哪」，与当前血量无关 |
| D5 | 斩杀线从 **mob effect amplifier** 读，不新增任何同步 | amplifier 已被原版同步；这也是当初把它编码进 amplifier 的目的 |
| D6 | 配置**只暴露 `enabled`**，颜色/线宽/出头/α 全部是代码常量 | 用户裁定「只要 enabled（最简）」 |
| D7 | 竖线取**不透明**、填充取 **40% α** | 参考图竖线实测不透明；它是权威标记需锐利，而填充要能看见血条本身 |
| D8 | 照抄既有 `HealthTrackerHandlerMixin` 的 `@Pseudo` + `require = 0` + 锚点日志三件套 | 与项目对可选客户端模组的一贯处理一致 |

---

## 十一、明确不做（Non-Goals）

- **生物头顶的世界血条**（需求 2 明确只要「屏幕左上角那条」）；
- 任何服务端行为、任何新的网络同步；
- 「已跌破斩杀线」的变色/闪烁（D4）；
- AsteorBar 的血条联动（需求 9 已于 2026-10-09 由用户撤销，本次不复活）；
- 修改 Health Bars 本身的源码。

---

## 十二、升级必查项

**Health Bars 升级时必须核对这两处**（改了会静默失效或错位，不会硬崩）：

1. `GuiRenderingHandler.renderHealthBar` 的**方法名与 8 参签名**——变了则注入失效、遮罩消失；
2. `HealthBarRenderHelper` 里 `blitSprite(..., **5**)` 的**条高常量**——变了则 `BAR_HEIGHT` 需同步，
   否则遮罩会错位（这是本项目对「第三方内部常量」的一贯风险，与灾变 / 星系龙的注入点同理）。

---

## 十三、下一步

交 `planning` 技能产出实施计划（`docs/plans/2026-10-11-healthbars-execute-line-plan.md`）。

---

## 十四、实施后的对抗性审查修正（2026-10-11）

实施完成后对整批改动做了一轮只读对抗性审查（含 `javap` 对实际依赖 jar 的字节码核对）。**无 Critical**；
注入描述符、客户端安全链、Config、层序等 8 项被逐条验证为正确。以下是被修正的问题：

### 14.1 几何 off-by-one（Important，已修）

本文档 §二 原写「条左 = 注入点的 `posX`」，**是错的**：真值是 `posX - 1`。
两次整除相消本身没错，**被漏掉的是那个 `−1`**。反证：`W ∈ {47,91,92,137,182}` 逐个代入
`(P − 1 + W/2) − W/2` **恒等于 `P − 1`**。

后果：填充左缘留 1px、竖线恒偏右 1px（`W=137` 时 5% 处应 6.85px 却画在 7px），
与成功判据「竖线位置 = 真实斩杀线」系统性不符；且 `lineX` 的 clamp 区间与条实际占位错开 1px。

修法：`ExecuteLineOverlay.draw` 的参数由 `barLeft` 改名 `anchorX`，内部 `barLeft = anchorX - 1`
（把几何留在 overlay、保住「mixin 不含几何」的分层）。同步修正 §二 与 §6.1。

### 14.2 静态门的两处假阴性（Important，已修）

- **断言②可被注释单独满足**：原先对**未剥注释**的源码做子串匹配，而 mixin 的 javadoc 里恰好写了
  `{@code @Pseudo}` 与 `require = 0` ⇒ 删掉真注解门照样全绿。现改为对**剥注释后**的源码断言，
  并新增两条锁定 **8 参方法描述符**与 **INVOKE 目标描述符**（两串均由实际 jar 的 `javap` 取得）。
- **断言④测不到它声称的失效模式**：键表原先硬编码在脚本里，`Config.java` 的
  `.translation(...)` 打错字时门仍绿。现改为**从 `Config.java` 源码解析** `push(...)` 段名与
  `.translation(...)` 键名，再断言这两者 + `.tooltip` 在中英齐备。

剥注释函数也一并收紧：只剥**行首**注释（原先连行尾 `//` 一起剥，会把「字符串字面量里含 `//`」
之后的真实引用整段吞掉——而对「禁止被引用」的检查，**漏报才是不安全的方向**）。
两处假阴性均已用**反向对照**实测：改坏 → 期望 FAIL 且命中对应断言 → 还原后哈希不变、门复绿。

### 14.3 其它（已修）

- `draw` 增加 `barWidth <= 0` 早退（今日不可达，防上游将来改条宽公式）。
- 竖线右缘的文档公式改为与实现一致的 `lineLeft + LINE_WIDTH`（原 `lineX ± LINE_WIDTH/2`
  在奇数宽度时会少 1px；`LINE_WIDTH=2` 时无差异）。
- 配色数字对齐：反解原色 `RGB(255,128,36)`，常量取整为 `0xFF8028` = `RGB(255,128,40)`。
- 半透明生效的**归因**更正：来自 `RenderType.gui()` 自带的 `TRANSLUCENT_TRANSPARENCY`，
  而非调用点的 blend 设置。

### 14.4 未修、需人工处置（Important）

`tools/` 在 `.gitignore:49` 被整目录忽略 ⇒ 新增的 `tools/validate_execute_line.py`
**不会**被普通 `git add .` 带走（既有 6 个 `tools/*.py` 是历史 `git add -f` 进来的）。
本文档不擅自改 `.gitignore` 语义（能生效的 `tools/*` + 反包含形式会让 `tools/` 下 22 个
spark 转储与 `__pycache__` 冒到 `git status`）。
⇒ **提交时必须 `git add -f tools/validate_execute_line.py`**。
