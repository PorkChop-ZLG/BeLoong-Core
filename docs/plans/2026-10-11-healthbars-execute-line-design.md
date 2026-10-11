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

## 三、⚠️ 「不需要新的网络通道」是错的（2026-10-11 实机后更正）

**本节原文（已作废）**主张：斩杀线编码在 mob effect 的 amplifier 里，而 amplifier 是原版
`ClientboundUpdateMobEffectPacket` 的一个字段 ⇒ 只要实体被客户端追踪就能直接读到，因此**零新增 payload**。
**这个前提对旁观者是错的**，实机表现为「完全没有任何渲染」。

事实（反编译源逐条核对）：

| 位置 | 事实 |
|---|---|
| `ServerEntity`（同步被追踪实体） | **整个文件 `Effect` 出现 0 次** —— 它只同步位置/朝向/头部朝向/实体数据/装备/牵绳/速度 |
| `ChunkMap` | 不碰 effect |
| `ClientboundUpdateMobEffectPacket` 全树发送点 | **只有 3 处**：给**乘客**（`LivingEntity:1057`）、给**效果持有者本人**（`ServerPlayer:1493/1505`）、登录与骑乘时（`PlayerList:535` ← 仅 `ServerPlayer:530/2104` 调用）。**没有一处是「发给正在追踪该生物的玩家」** |
| `LivingEntity:165-173` 的同步字段 | 与效果相关的**只有 2 个**：`DATA_EFFECT_PARTICLES`（`List<ParticleOptions>`，仅**粒子外观**）与 `DATA_EFFECT_AMBIENCE_ID`（一个聚合布尔）。**都不含效果身份、不含 amplifier** |
| `LivingEntity:828-835` | 客户端拿这两个字段只做一件事：**撒粒子** |

⇒ 客户端对一只怪调 `entity.getEffect(...)` **永远是 `null`**。

**现在的实现**：由服务端补发一条自己的包（`ExecuteMarkSyncPayload`，只带 `entityId + amplifier` ——
比原版那个包还小），客户端存进缓存后读取。即：**数据本来就在协议里，原版只是不发给旁观者。**

```java
// compat/healthbars/ExecuteLineOverlay.java
int amplifier = ExecuteMarkCache.get(entity.getId());              // 服务端补发的同步包
if (amplifier == ExecuteMarkSyncPayload.NO_MARK) return false;     // 没挂斩杀线 → 不画
float pct = ExecuteThresholdEffect.thresholdFraction(amplifier);   // 0.05 .. 0.20
```

**需求 4 因此被追认为「需要一条最小自定义包」**，由用户 2026-10-11 裁定（方案 A）。完整根因与修复见 §十五。

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

### 9.2 实机验收（**✅ 2026-10-11 已通过**）

> 用户 2026-10-11 的最终结论：「**现在斩杀技能符合预期**」。覆盖范围与已接受的例外见 **§十九**。

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

### 14.4 `tools/` 刻意不进版本库（**已裁定，不是待办**）

`tools/` 在 `.gitignore:49` 被整目录忽略，这是**项目既定政策**，不是遗漏：
`memory/project-context.md:322` 写明「`tools/` is gitignored: Python asset/verification scripts +
Spark profile dumps」，`tools/README-element-essence.md:4` 也记「这些脚本与源图是本机文件」。

**⇒ 2026-10-11 用户裁定：保持现状** —— 不为 `tools/` 反加白名单（那会让 `tools/` 下 22 个
spark 转储与 `__pycache__` 冒进 `git status`），**也不要求 `git add -f`**。
本节的旧措辞（「提交时必须 `git add -f tools/validate_execute_line.py`」）已作废。

**由此产生的唯一实际约束**：验证脚本**不在**提交里，所以
① 它们必须在**工作区**里保留并可重跑（换机器 / 重新克隆后需要自己带过去）；
② 任何「本次验证通过」的结论，其可复现性只依赖工作区状态 + 本文档记录的命令与期望输出。

---

## 十五、实机验收失败 → 根因 → 修复（2026-10-11，第二轮）

### 15.1 现象

用户实机测试：**完全没有任何渲染** —— 左上角 GUI 血条与世界血条都没有斩杀线，成长 150 与 300 都一样；
**但斩杀效果本身照常触发**。截图 `run/screenshots/2026-10-11_16.59.12.png` 里血条满血 500/500、
屏幕上没有任何橙色。

### 15.2 证据链（全部来自该次运行的 `debug.log` 与反编译源）

| # | 证据 | 排除掉的可能 |
|---|---|---|
| 1 | `Mixing healthbars.GuiRenderingHandlerMixin … into …GuiRenderingHandler` + handler 已注入 | mixin 没生效 ❌ |
| 2 | 全程**无**注入点告警、无 `NoClassDefFoundError` | 描述符写错／注入点找不到 ❌ |
| 3 | `run/config/beloong-client.toml` 里 `[execute_line] enabled = true` | 配置读成 false ❌ |
| 4 | 截图里血条在屏幕上 | GUI 路径没被走到 ❌ |
| 5 | **锚点日志一次都没打印** | —— ⇒ `draw` 每次都提前 return |
| 6 | 见 §三 的四条事实 | —— ⇒ **客户端 `getEffect` 恒为 null** |

### 15.3 根因

**上一版把「客户端能免费读到生物的 amplifier」当成了事实，而它是错的。** 客户端对一只怪调
`entity.getEffect(EXECUTE_THRESHOLD)` 永远返回 `null`，于是 `draw` 在第二个 `return false` 退出。
服务端不受影响 ⇒ 斩杀照常触发。这个错误前提的来源是 `ExecuteThresholdEffect` 的类注释
（已于本次一并更正）。

### 15.4 修复（用户裁定：方案 A —— 一条最小自定义包）

| 新增/改动 | 文件 | 作用 |
|---|---|---|
| 新 | `compat/healthbars/ExecuteMarkSyncPayload.java` | 只带 `entityId + amplifier`（`NO_MARK = -1` 表示无标记）；`playToClient` 注册；`handleClient` 写缓存 |
| 新 | `compat/healthbars/ExecuteMarkCache.java` | **客户端专属**缓存 `实体 id → amplifier`；登录时清空（实体 id 会跨连接回收） |
| 新 | `registry/ExecuteMarkSyncHandler.java` | 服务端发送方；`PlayerEvent.StartTracking` 补发（后进入视野的玩家） |
| 新 | `mixin/minecraft/LivingEntityExecuteMarkSyncMixin.java` | 注入原版 `onEffectAdded/Updated/Removed`（**common** 列表，服务端执行）。同目录 `DreadKingRitualTriggerMixin` 是命名规范先例 |
| 改 | `compat/healthbars/ExecuteLineOverlay.java` | 改为读缓存；**不再** `getEffect(...)` |
| 改 | `BeLoongCore.java` | 注册包 + 注册 `StartTracking` 监听 |
| 改 | `BeLoongCoreClient.java` | 登录时清空缓存 |
| 改 | `registry/ExecuteThresholdEffect.java` | 更正那句错误前提 |

**为什么用「注入原版效果生命周期」而不是在业务代码里散落发送**：`LivingEntity:807-811` 表明
**自然到期也走 `onEffectRemoved`**（且只在服务端），所以挂上/刷新/到期/斩杀移除/`/effect clear`/
牛奶/死亡**全部被这三处覆盖**，业务代码一行都不用改。

**为什么同步 mixin 必须 `remap = false`**：这三个方法都带 NeoForge 补的参数
（`onEffectAdded` 第 2 参、`onEffectUpdated` 第 3 参），描述符不在原版混淆表里，
AP 直接报 `Unable to locate obfuscation mapping for @Inject target`（实测三个都报）。
NeoForge 1.21 运行时用官方名，故 `remap = false` 在开发与生产都成立。

### 15.5 门也随之加强（`tools/validate_execute_line.py` 现有 8 组断言）

新增第 ⑤–⑧ 组，其中**第 ⑧ 组是这次 bug 的回归闸**：

| 组 | 守什么 |
|---|---|
| ⑤ | `ExecuteMarkCache` 只允许被 3 个客户端路径引用（overlay / payload 的 handleClient / `BeLoongCoreClient`）—— 双端类引用它会崩专用服务端 |
| ⑥ | 包**真的注册了** `playToClient`；漏注册 = 服务端永远不发 = 功能静默死亡（无任何报错） |
| ⑦ | 同步 mixin 在 **common** `mixins` 列表且钩住三个生命周期方法（放进 `client` 列表 = 服务端不发 = 同样静默死亡） |
| ⑧ | **overlay 不得再出现 `getEffect(` / `MobEffectInstance`** —— 直接钉死这次退回旧写法的可能 |

**反向对照实测**（改坏 → 期望 FAIL → 还原 → 哈希校验）：第 ⑧ 组「退回直接读 effect」、
第 ⑦ 组「原版 mixin 放回 `mixin/` 根目录（违反命名规范）」与「`@At` 上漏写 `remap = false`」、
第 ① 组「同步 mixin 没注册进 common」、第 ⑥ 组「包没注册」、第 ⑤ 组「双端类引用了客户端缓存」
—— **六条全部被抓住**，还原后文件哈希不变、门复绿。

### 15.7 命名规范合规（用户裁定，2026-10-11）

项目规范：**mixin 按「被 mixin 的模组」的命名空间分类并加前缀** —— 原版目标放
`com.zonlong.beloong.mixin.minecraft`、`beloong.mixins.json` 条目写 `"minecraft.XxxMixin"`；
第三方目标放 `mixin/<该模组命名空间>/`。原版目标的注入还必须**在每个 `@Inject` 与其 `@At` 上
显式 `remap = false`**（`@Mixin` 上不写）。出处：`memory/project-context.md:122`、
decisions-log 第 11 条；先例：`mixin/minecraft/DreadKingRitualTriggerMixin.java`。

本功能第一版把新 mixin 放在 `mixin/` 根目录、且只在 `@Mixin` 上写了 `remap = false` ——
**两处都不合规**。根目录那批（`PossibleBiomesFilterMixin` 等）是**历史遗留**，规范是后来才立的，
用户明确**不回溯修改**；但新写的必须遵守。已迁移为
`mixin/minecraft/LivingEntityExecuteMarkSyncMixin` + 条目 `"minecraft.LivingEntityExecuteMarkSyncMixin"`
+ 6 处 `remap = false`，并把这条规范写成静态门第 ⑦ 组的断言（放回根目录或漏写 `@At` 的 `remap` 都会红）。

### 15.6 实机验收：**✅ 已通过**（2026-10-11）

第二轮修复后由用户实机复测，结论「**现在斩杀技能符合预期**」：

- 斩杀线**正常渲染**（第一轮的「完全无渲染」已消除），竖线按成长值落位（150 → 5% / 300 → 20%）；
- 打半血时橙色区段宽度不变；伤害飘字清晰；无标记的怪一点橙色都没有；
- 随后又暴露出 Boss 挂不上标记的问题 —— 那是**独立的第三个原因**，见 §十六。

---

## 十六、Boss 挂不上斩杀标记：两道闸门与通用解法（2026-10-11，第三轮）

### 16.1 现象

实机：**原版凋灵、灾变 Boss、传奇怪物的三个 Boss** 都无法触发斩杀（连文字/音效/粒子都没有
⇒ **不是伤害被挡，而是标记根本没挂上**）；而**铁傀儡、监守者、传奇怪物的小 Boss** 全部正常。

### 16.2 两道闸门（实证）

| 闸门 | 实例 | 机制 |
|---|---|---|
| **`canBeAffected` 覆写** | 灾变 `IABoss_monster.java:302`、`LLibrary_Boss_Monster.java:235` | 白名单标签 `cataclysm:effective_for_bosses`（30 项原版效果 + 4 项灾变效果），**不含 `beloong:execute_threshold`** ⇒ `addEffect` 第一行 `canMobEffectBeApplied` 否决 |
| **`addEffect` 覆写** | 原版凋灵 `WitherBoss.java:529-532`（无条件 `return false`）；传奇怪物 `IAnimatedBoss.java:225-227`（同） | 覆写掉 `addEffect` 本身，**连 `canBeAffected` 都不会被问到** |

**传奇怪物「三个 Boss / 小 Boss」的精确解释**：`IAnimatedBoss`（类）只有 **3 个**实现者
（`Cloud_GolemEntity` / `PossessedPaladinEntity` / `TheObliteratorEntity`）⇒ 正是那三个；
而 `IAnimatedMiniBoss`（类）虽在 `:161-164` 写了 `canBeAffected → false`，但每个小 Boss 实体又把它
覆写回 `return super.canBeAffected(instance)`，且 `IAnimatedMiniBoss` 不覆写 `addEffect`
⇒ 它们走的是 `LivingEntity.addEffect` 正常路径 ⇒ 标记正常挂上。

### 16.3 解法：两个通用杠杆，**零 per-Boss-模组 mixin**

| # | 杠杆 | 覆盖 |
|---|---|---|
| ① | `MobEffectEvent.Applicable` @ `EventPriority.LOWEST` ⇒ `setResult(Result.APPLY)` | **一切** `canBeAffected` 实现 |
| ② | `addEffect` 返回 false 时回退 `LivingEntity.forceAddEffect` | **一切** `addEffect` 覆写 |

① 的依据是原版自己的实现（`MobEffectEvent.Applicable:138-143`）：`Result.APPLY` **直接 `return true`**，
`canBeAffected` 根本不参与；该事件不可取消、所有监听器都执行、结果取**最后一个** `setResult`
⇒ 用 `LOWEST` 跑到最后即可稳赢。官方注释即「Forces the event to apply the mob effect」。

② 的依据是 `LivingEntity.forceAddEffect:1014-1024` **不调用被覆写的 `addEffect`**，直接写
`activeEffects` 并触发 `onEffectAdded`；但它仍走 `canMobEffectBeApplied` ⇒ 被 ① 放行。

**⚠️ ② 必须同时自补「斩杀者」**：`forceAddEffect` **不触发 `MobEffectEvent.Added`**，而 DS 正是在
该事件里把 `effectSource` 写进 effect 实例的（DS `EffectHandler:21-22`）。不补 ⇒ 标记挂上却永不触发
（又一个静默失败）。DS 的接口公开：`AdditionalEffectData.dragonSurvival$setApplier(Entity)`。

**③ 数据侧**：新增 `data/minecraft/tags/damage_type/bypasses_invulnerability.json`（含
`beloong:execute`）⇒ 绕过灾变的 `Math.min(DamageCap(), amount)`（`IABoss_monster:137-140`、
`LLibrary_Boss_Monster:81-84`）与全部 11 处 Boss 无敌帧。**有了 ① 之后
`cataclysm:effective_for_bosses` 那个数据文件不再需要**（`Result.APPLY` 直接短路 `canBeAffected`）。

**⑤ 可诊断性**：两道闸门都失败时打一条 `WARN`（罕见、不刷屏）；走兜底成功时打 `DEBUG`。

### 16.4 ⚠️ 记录更正：「标签缺席」原本是**刻意设计**，不是遗漏

`tools/validate_execute.py` 的 `[5]` 段原本有一条断言，是**当初写下的**：

```python
check(not os.path.isfile(.../bypasses_invulnerability.json),
      "no bypasses_invulnerability (would pierce creative/invulnerable entities)")
```

⇒ 「不加这个标签」是**明确的设计决定**（理由就是会穿透创造模式 / 无敌实体）。
**我在本轮讨论中一度把它说成「遗漏」，那是错的**，在此更正。当初真正没被预见到的，是它的
**后果**：靠钳制伤害或无敌帧过日子的 Boss 会完全无法被斩杀。

用户于 2026-10-11 在**逐条知悉代价**后明确接受（不死图腾失效、出生保护 / 创造模式 /
`setInvulnerable(true)` 被穿透、监守者钻地无敌被穿透、灾变 11 处 Boss 无敌帧被穿透），
因此**不变量已翻转为相反的一条**并记录了谁/何时/为何 —— 没有静默删除这条守卫。

> **⚠️ 2026-10-11(五) 再次反转：该标签已被撤回。** 用户实测发现它「类似 `/kill`」的效果
> **会让部分 Boss 的动画失效**，遂恢复「不得存在」。完整往返记录见 **§十八**。

### 16.5 **已关闭：无需处理**（2026-10-11 实机确认）

原本担心「灾变多部件 Boss 看不到线」：Health Bars 的准星拾取拿到的是
`Cm_Part_Entity extends PartEntity`（**不是 `LivingEntity`**），而 `PickEntityHandler` 只把**原版**
`EnderDragonPart` 解析回 `parentMob` ⇒ 怀疑 `GuiRenderingHandler:48` 的 `instanceof LivingEntity`
不成立、这类生物根本没有 GUI 血条。

**实机结果：线正常渲染** —— 用户 2026-10-11 报告「凋灵、末影龙、灾变焰魔、**下界合金巨兽**都可以斩杀
并且**渲染斩杀线**」。也就是说命中盒拾取在这些 Boss 上实际拿到的是主实体（或其父实体），
那条盲区**没有影响到本功能** ⇒ 原计划的 `PartEntity → parentMob` mixin **不做**。
（仍作为潜在隐患记在 §十二 升级必查项里：若将来某个多部件 Boss 的准星拾取确实落在部件上，
症状会表现为「该 Boss 完全没有血条」而非「只有线没有」。）

### 16.6 验证

```
python tools\validate_execute.py        -> ALL CHECKS PASSED（[5] 的不变量已按裁定翻转）
python tools\validate_execute_line.py   -> ALL CHECKS PASSED（新增第 ⑨ 组：两道杠杆 + 数据文件 + 自补 applier）
.\gradlew.bat build --rerun-tasks --no-build-cache -> BUILD SUCCESSFUL，3 个警告（与改动前一致）
jar 复核                                -> data/minecraft/tags/damage_type/bypasses_invulnerability.json 已打包
反向对照（5 条）                        -> 删 ① 杠杆 / 删 ② 兜底 / 删自补 applier / 写反正常路径与兜底关系 /
                                           拿掉 bypasses_invulnerability.json
                                           —— 全部被门抓住，还原后哈希不变、两门复绿
```

---

## 十七、斩杀被目标挡下：承认它，并停止每 tick 重试（2026-10-11，第四轮）

### 17.1 现象

第三轮之后，凋灵 / 末影龙 / 灾变焰魔 / 下界合金巨兽都能斩且能渲染线。两个例外：

| 例外 | 现象 |
|---|---|
| **Mowzie 钢铁守护者** | 斩杀触发后**造成不了伤害**，且**每 tick 都重试**，声音特别吵 |
| **传奇怪物 云筑魔像** | **完全无法斩杀**（同模组另一个 Boss 正常） |

### 17.2 根因：目标 `hurt` 里的**无条件拒绝**（不看任何标签）

| Boss | 拒绝条件 | 证据 |
|---|---|---|
| **钢铁守护者** | 正面 ±110° 弧内、或不在 `vulnerable` 窗口时 → `playSound(ENTITY_WROUGHT_UNDAMAGED)` + `return false` | `EntityWroughtnaut.hurt:194-207`。**它的 `BYPASSES_INVULNERABILITY` 判断位于「伤害源无实体」的 `else` 分支**（`:204`），而我们的 `ExecuteDamageSource` 带玩家（击杀归因需要）⇒ 永远够不到那个分支 |
| **云筑魔像** | ① 受击后 10 tick 金身 → `return false`；② `isTargetCheesingBoss()` → `return false`；③ `getAttackState() == 21` → `return false` | `Cloud_GolemEntity.hurt:1181 / 1184 / 1239`。第 ② 条的实现是 `IAnimatedMonster:213-222` —— **纯粹按 Y 轴差**：攻击者比它高 4 格或低 4 格即算 "cheese"。它**是飞行 Boss**，龙在空中打它极易命中这条 ⇒ 永久拒绝、且**完全静默**（`:1184` 没有任何音效/消息） |

**为什么标签救不了**：`IAnimatedBoss.hurt:331-336` 本身认标签（`if (BYPASSES_INVULNERABILITY) return super.hurt(...)` ⇒ 跳过 `Math.min(damageCap(), amount)`），但**子类覆写里那些不看标签的无条件 `return false` 跑在父类之前**。伤害类型标签、`forceAddEffect`、`Result.APPLY` 都**进不到目标 `hurt` 内部**。

⇒ **没有通用手段能强行造成伤害**（与「效果挂不上」不同，那个有通用杠杆，见 §十六）。
用户 2026-10-11 裁定：**承认部分 Boss 挡得住斩杀**，不为这两个模组写 mixin（**e 不做**）。

### 17.3 但这暴露了一个真 bug：每 tick 重试

原实现把 `hurt` 返回 false 解释成「目标无敌，别消耗标记、别进冷却」：

```java
if (!victim.hurt(new ExecuteDamageSource(damageType, player), damage)) {
    // 无敌实体 / 已死亡等：不消耗标记，也不进冷却。
    return;
}
```

⇒ 标记永远耗不掉 ⇒ 下一 tick 再来一次 ⇒ **无限斩杀**。钢铁守护者之所以"声音特别吵"，
正因为**它每 tick 都播自己的 `ENTITY_WROUGHT_UNDAMAGED`**（我们的斩杀音效在 `hurt` 之后，
`return false` 时就 return 了，一次都没响过）。

### 17.4 修复（用户裁定 a/b/c/d，e 不做）

| # | 内容 | 位置 |
|---|---|---|
| **a** | **无论伤害是否造成**，都消耗标记 + 进入冷却 | `ExecuteThresholdEffect#execute` |
| **b** | 冷却期间**不再挂线** | `ExecuteMarkHandler#onLivingDamagePost` |
| **c** | 忽略**我们自己的**斩杀伤害触发的 `LivingDamageEvent.Post`（按伤害类型判断） | 同上（`isOurExecuteDamage`） |
| **d** | 区分「真斩杀」与「被挡下」：后者给 `message.beloong.execute.refused` 提示 + 一条 `INFO` 日志，**不再谎报「已斩杀」** | `ExecuteThresholdEffect#execute` + 中英语言文件 |

**为什么必须做 (c)**：`ExecuteDamageSource` 带着斩杀者（为了让击杀统计 / 掉落 / 进度归因正确），
所以**我们自己的斩杀也会触发 `LivingDamageEvent.Post`**，看起来就像「玩家又打了一下」⇒ 自动重新挂线
⇒ 冷却一结束就自动再斩一次。斩杀伤害不是「玩家攻击」，不应据此挂线。

**⚠️ 记录又一次决定反转**：`ExecuteCooldown` 的类注释原本写着
「冷却期间**仍然可以**继续给敌人挂斩杀线」—— (b) 正是把它反过来。该注释已改写并记录
谁/何时/为何（与 §16.4 的 `bypasses_invulnerability` 同一处理方式）。

### 17.5 修复后的语义

- 斩杀**触发过就进冷却**，成败不论；
- 冷却期间**挂不上线**，所以冷却结束后也不会自动重斩（除非玩家再打一下）；
- 被挡下时玩家看到「斩杀被挡下」而不是假的「已斩杀」，并且**日志里留痕**（这类失败此前完全静默）。

### 17.6 验证

```
python tools\validate_execute.py        -> ALL CHECKS PASSED（新增第 ⑩ 组：a/b/c/d 四条不变量）
python tools\validate_execute_line.py   -> ALL CHECKS PASSED
.\gradlew.bat build --rerun-tasks --no-build-cache -> BUILD SUCCESSFUL，3 个警告（与改动前一致）
反向对照（5 条）                        -> 退回旧守卫 / 取消冷却拦截 / 取消自伤过滤 / 不区分被挡下 /
                                           英文缺 refused 键 —— 全部被门抓住，还原后哈希不变、门复绿
```

---

## 十八、撤回 `bypasses_invulnerability`（2026-10-11，第五轮）

### 18.1 决定

**用户实测后撤回** §十六 的 ③（那个数据文件），恢复「不得存在」：
> 那个标签类似 kill 的效果，会让部分 Boss 的动画失效，所以改回去。

### 18.2 为什么它「类似 kill」—— 这个标签是**多方共用**的

`minecraft:bypasses_invulnerability` 不是一个「针对某 Boss 的伤害上限开关」，而是**一堆互不相关的
无敌判定共同读取的同一个标签**（这一步在 §十六当时只被总结为「代价」，没有点明**共用**的含义）：

| 读取方 | 效果 |
|---|---|
| `WitherBoss:483` | 凋灵出生无敌（220 tick）不再挡住它 |
| 灾变 `IABoss_monster:137-140` / `LLibrary_Boss_Monster:81-84` | 伤害上限 `Math.min(DamageCap(), amount)` 被跳过 |
| 灾变 11 处 Boss 无敌帧 | 转阶段 / 特殊动画期间的无敌全部失效 ← **动画失效的直接来源** |
| `LivingEntity:1306-1308` `checkTotemDeathProtection` | 不死图腾不再救人 |
| `Entity:2683` `isInvulnerableTo` | `setInvulnerable(true)` 的实体可被打穿 |
| `ArmorStand:397` / `ItemFrame:145` / `Ghast:79` / `Warden:159` / `Player:925` | 各自的无敌（含创造模式）失效 |

⇒ **列入这个标签，等于对上面所有读取方同时宣布「`beloong:execute` 是 `/kill` 级伤害」**，
而不仅仅是「跳过某个 Boss 的伤害上限」。用户的实测（动画失效）正是这条清单里第 3 项的直接后果。

**而且这一点初版设计文档早就写明**（`2026-10-09-execute-passive-design.md:192`）：
> ⇒ 伤害类型 `beloong:execute` 同时进这 8 个原版标签。**刻意不加** `minecraft:bypasses_invulnerability`：
> 那是 `/kill` 级别的口子，会把创造模式玩家、无敌实体一起打穿。

⇒ 用户的实测与初版预判**一字不差**。本轮撤回即回到那条原本就写明的决定。

### 18.3 撤回后的行为（不会退化成每 tick 重试）

缺了这个标签，靠伤害钳制 / 无敌帧过日子的 Boss 会**再次挡下斩杀**（灾变系的
`Math.min(DamageCap(), amount)` 会把 `999999` 钳成 20~25）—— 但**因为第四轮的 (a) 已落地**，
被挡下时仍然：**消耗标记 + 进入冷却 + 显示「斩杀被挡下」提示 + 留一条 INFO 日志**，
所以不会每 tick 重试 ✓。即：**从「打不死但会无限重试」变成「打不死，但会明确告诉你并被冷却拦住」**。

### 18.4 静态门

- `validate_execute.py` `[5]`：不变量回到 `check(not os.path.isfile(...))`，
  注释保留**完整往返**（初版 → 2026-10-11(三) 允许 → (五) 撤回）与撤回理由。
- `validate_execute_line.py` 新增 `[9b]`：断言该文件**不存在**，并写明「缺了它只是挡下斩杀，
  不会退化成每 tick 重试」。

### 18.5 验证

```
python tools\validate_execute.py        -> ALL CHECKS PASSED（[5] 回到「不得存在」）
python tools\validate_execute_line.py   -> ALL CHECKS PASSED（新增 [9b]）
.\gradlew.bat build --rerun-tasks --no-build-cache -> BUILD SUCCESSFUL，3 个警告（与改动前一致）
jar 复核                                -> bypasses_invulnerability.json 已消失；其余 8 个标签完好
```

### 18.6 如果将来仍想让这些 Boss 可斩

**不要**再用这个全局标签。可行的窄口径做法（第三轮 §16.3 里已列为对照）是
**针对具体钳制点写 mixin**，只对 `beloong:execute` 放行 —— 本仓库已有先例：
`mixin/legendarymonsters/AnnihilationPursuerDamageCapMixin` 就是在 `damageCap()` 上 `@Inject`
改返回值的。代价是每个模组族 1 个 mixin（不是每个 Boss 1 个），且要跟进上游私有逻辑。
**当前决定：不做**（用户已裁定承认部分 Boss 挡得住）。

---

## 十九、实机验收通过（2026-10-11，收尾）

### 19.1 结论

**用户 2026-10-11 实机结论：「现在斩杀技能符合预期」。** 本功能（斩杀线渲染 + 斩杀触发的 Boss 兼容 +
失败不再重试）**验收通过，收尾**。

### 19.2 覆盖到的行为（跨五轮测试累计确认）

| 项 | 结果 |
|---|---|
| 斩杀线渲染 | ✅ 左上角血条出现橙色 `0→斩杀线` 区段 + 竖线；按成长值落位（150 → 5% / 300 → 20%） |
| 打半血区段不缩水 | ✅ |
| 伤害飘字可读 | ✅（合成序上飘字压在我们之上，实测清晰） |
| 无标记的怪 | ✅ 一点橙色都没有 |
| 斩杀触发 | ✅ 普通生物、小 Boss，以及**多数 Boss**：凋灵、末影龙、灾变焰魔、下界合金巨兽、传奇怪物的两个 Boss |
| 被挡下时不重试 | ✅ 消耗标记 + 进冷却 + 提示「斩杀被挡下」+ INFO 日志 |
| 冷却语义 | ✅ 冷却期间挂不上线；冷却结束后不自动重斩 |
| 专用服务端 | ✅ 无 `NoClassDefFoundError`（客户端专属类只被 3 个客户端路径引用，门第 ⑤ 组钉住） |

### 19.3 已接受、不再视为缺陷的例外（用户裁定）

| 例外 | 机制 | 处置 |
|---|---|---|
| **Mowzie 钢铁守护者** | 正面 / 非破防窗口无条件 `return false`（`EntityWroughtnaut.hurt:194-207`），且它的 `BYPASSES_INVULNERABILITY` 判断位于「伤害源无实体」的 `else` 分支 ⇒ 标签够不到 | **挡得住斩杀**；不为它写 mixin |
| **传奇怪物 云筑魔像** | 垂直 4 格反 cheese（`IAnimatedMonster:213-222`，而它是飞行 Boss）+ 受击后 10 tick 金身 + `state == 21`（`Cloud_GolemEntity.hurt:1181/1184/1239`），**都不看标签** | 同上 |
| `bypasses_invulnerability` | 试加后实测**会让部分 Boss 的动画失效**（它是多方共用的 `/kill` 级标签，见 §18.2） | **已撤回**，回到「不得存在」 |

### 19.4 本轮周期内的三次决定反转（均留档，未静默改写）

1. §16.4 —— 「标签缺席」原被误称为遗漏，实为**刻意设计**（初版文档 §5.4 写明「那是 `/kill` 级别的口子」）；
2. §十七 —— `ExecuteCooldown` 的「冷却期间仍可挂线」**反转为不可挂线**；
3. §十八 —— 上面那个标签**加过又撤回**（用户实测与初版预判一字不差）。

### 19.5 遗留（明确不做，非遗漏）

- 不给 Mowzie / 传奇怪物写 mixin 让它们的 Boss 可斩（用户裁定）；
- 不做 Health Bars 的 `PartEntity → parentMob` 拾取补丁（§16.5 实机证明无需）。
  若将来某个多部件 Boss 表现为「**完全没有血条**」，再回头看这一条。

### 19.6 提交状态

改动集中在已跟踪的 8 个文件 + 删除的 1 个数据文件。

**`tools/` 下的两个验证脚本刻意不进版本库**（项目既定政策，2026-10-11 再次确认；
见 §14.4）⇒ 提交**不需要**也不需要 `git add -f`，它们的家在工作区。
代价是「本次验证通过」的可复现性依赖工作区里的脚本 + 本文档记录的命令与期望输出。

---

## 二十、审查 → 修复 → 最终复验（2026-10-11，收尾）

### 20.1 审查

**3 名独立子代理**分片审查（服务端链路 / 客户端与同步 / 数据与门）+ 主审逐条复核，
报告：`docs/reviews/2026-10-11-execute-skill-code-review.md`
（**C×2**、**I×4**（其中一项含 7 条子缺口）、**S×10**；并**更正了审查者的 2 处结论**）。

最有价值的一条是 **C-2**：第 ⑩ 组原本只比较 `hurt` 与 `removeEffect` 的**下标先后**，
对真正的回归（在 `hurt` 之后插入 `if (!damaged) { return; }` = 恢复每 tick 重试）**保持全绿**
—— 而我此前那次「反向对照」之所以通过，是因为它**整行替换**了 `hurt` 调用，强度高于该回归。

### 20.2 修复（用户裁定：C/I 全修、S 除 S-5 外全处理）

| 类别 | 内容 |
|---|---|
| **C-1** | 兜底条件补 `&& victim.getEffect(EXECUTE_THRESHOLD) == null`，弱标记不再覆盖强标记 |
| **C-2** | 第 ⑩ 组改为「`hurt` 到**无条件**移除标记之间不得有 `return`」+ 分析前剥离注释 ⇒ **同一变异由绿转红**（实测） |
| **I-1** | 以 `isDeadOrDying()` 判定成败 ⇒ 被伤害上限钳制的 Boss 不再谎报「斩杀触发」 |
| **新增** | 目标在我方出手前已死（尸体）⇒ 消耗标记、**不进冷却**、静默（用户裁定） |
| **I-2** | 过期数字（`2L + 7` / amp ≤ 39 / `enchantment.level.40`）与过期结论（「冷却期间卡在线上等斩」） |
| **I-3 a–g** | 7 组空洞断言补齐（Java 网格常量、四个 `type`、`%s` 个数、字幕键、图标尺寸+灰度、开关接线、`NO_MARK`） |
| **I-4** | `tools/` **刻意不进版本管理** ⇒ 不修代码，改注释与文档（§14.4 / §19.6） |
| **S-1/2/3/6/7/8/9** | 加载态前置、删两个死 API、`.copyOnDeath()`、颜色注释、语言门首次全绿、`if (false)` 短路断言 |
| **S-4/5/10** | **保留 + 在代码里写明理由**（用户裁定；S-5 为刻意允许 >100% 斩杀线） |

### 20.3 最终复验

```
validate_execute.py        -> ALL CHECKS PASSED（11 组 / 76 条断言）
validate_execute_line.py   -> ALL CHECKS PASSED（10 组 / 51 条断言）
check_lang_parity.py       -> ✅ 通过（本会话首次全绿）
build --rerun-tasks --no-build-cache -> BUILD SUCCESSFUL，3 个警告（与改动前一致）
C-2 变异对照               -> 修复前 exit=0（假阴性）/ 修复后 exit=1 FAILED 2 check(s)，还原后复绿
```

**委托方实机复验（最终）**：修复后再次测试，结论「**斩杀技能没有问题了**」
⇒ 本功能（斩杀被动 + 斩杀线渲染 + Boss 兼容 + 失败不重试）**收尾结束**。
