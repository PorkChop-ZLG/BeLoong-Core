# 简易 NPC 对话系统实施计划

**Date:** 2026-09-20
**Status:** Ready for implementation
**Design:** [2026-09-20-npc-dialogue-design.md](./2026-09-20-npc-dialogue-design.md)
**Approach:** 方案 A —— 客户端读 jar 内数据包（**零网络包、零服务端逻辑、零 mixin**）
**验证模型:** 本项目**无测试源集**（`gradlew build` 输出 `compileTestJava NO-SOURCE` / `test NO-SOURCE`），故**不引入测试框架**。每个任务的验证 = `gradlew build` + 静态探针 + 实机清单（§12）

---

## 〇、方案对比（为什么是方案 A）

三条路线的唯一差异是**数据从哪来**：

| | A（**选定**） | B | C |
|---|---|---|---|
| 机制 | 客户端 `RegisterClientReloadListenersEvent` 注册 `SimpleJsonResourceReloadListener`，直接读 jar 内 `data/beloong/beloong/npc_dialogue/*.json` | 服务端 `AddReloadListenerEvent` 读，登录时全量下发（复用 `TreasureSyncPayload` + `ClientTreasureCache` 范式） | A + B 并存（客户端读默认值，服务端下发覆盖层） |
| 新增活动部件 | 2 个 loader 类 | +1 个 payload、+1 个客户端缓存、+服务端 loader | 两者的全部 + 合并逻辑 |
| 网络包 | **0** | 1（登录）+ 重载时再发 | 1 |
| 依赖前提 | 客户端能读 jar 内 `data/`（**已有先例，见下**） | 无 | 无 |
| 能支持"存档数据包覆盖" | ❌（客户端看不到世界数据包） | ✅ | ✅ |
| 任务数 | 13 | 15 | 18 |
| 风险 | **低** | 低 | 中（复杂度换来的收益为 0） |

**选 A 的三条理由：**

1. **v1 没有副作用** —— 唯一的选项是「离开」，整条链路可以在客户端闭环，方案 B 的 payload 与缓存是纯粹的多余复杂度。
2. **R0 已有先例**：Dragon Survival（本模组的**必选依赖**）正是这样做的 ——
   `DragonSurvivalClient.java:140-143` 用 `event.registerReloadListener(...)` 注册，
   `DragonPartLoader.java:28-34` 是 `SimpleJsonResourceReloadListener` 子类。
   机制上 `SimpleJsonResourceReloadListener` 走 `PackType.SERVER_DATA` 且**不区分物理侧**，模组 jar 与客户端资源包都在客户端的 pack repository 里。
3. **接缝已在设计里留好**（§11）：将来若真要"存档数据包覆盖"，补方案 B 即可，届时触发层与渲染层一行不动。

> **运行时兜底**：任务 3 会用一行日志确认客户端确实读到了文件。若意外为 0 条，按 §13 的 B 方案补丁执行（不需要重新规划）。

---

## 一、全局约束（每个任务都适用）

1. **不修改 `src/main/resources/beloong.mixins.json`** —— 本功能零 mixin，一旦这个文件出现改动就是走错路了。
2. **布局常量集中在 `NpcDialogueScreen` 顶部**，全部 `private static final`，便于试玩调参时一处改（取值见设计文档 §3.1）。
3. **语言文件按字母序插入**（现有 `zh_cn.json` 210 行严格有序，`en_us.json` 同步），**不得追加到末尾**。
4. **JSON 解析失败必须隔离单个文件**（`resultOrPartial` + `LOGGER.error`），绝不 `getOrThrow` —— 反例见 MCA Conversations 的 `DATAPACK.md`（严格解析会炸掉整个数据包重载）。
5. **提交策略**：每个任务完成后本地提交，格式对齐 `feature/tornado-ability` 分支的既有风格（`feat(dialogue): ...` / `fix(dialogue): ...` / `docs(dialogue): ...`，中文正文）。**不主动 `git push`**，等用户确认。

---

## 二、实施步骤

### 1. 数据模型 `NpcDialogueEntry`

**文件：** 新增 `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueEntry.java`

**职责：** record + 全部 Codec；`Trigger` 枚举（`empty_hand` / `any`，缺省 `EMPTY_HAND`）。

**骨架：**

```java
public record NpcDialogueEntry(
        EntityType<?> entity,
        Trigger trigger,
        Optional<String> name,
        List<Page> pages
) {
    public enum Trigger { EMPTY_HAND, ANY }

    public record Page(String text, Optional<ResourceLocation> sound) {
        public static final Codec<Page> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("text").forGetter(Page::text),
                ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(Page::sound)
        ).apply(i, Page::new));
    }

    private static final Codec<EntityType<?>> ENTITY_CODEC = ResourceLocation.CODEC.comapFlatMap(
            loc -> BuiltInRegistries.ENTITY_TYPE.getOptional(loc)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown entity type: " + loc)),
            BuiltInRegistries.ENTITY_TYPE::getKey);

    // 用 comapFlatMap 而非 xmap：未知取值要变成 DataResult.error（隔离该文件），
    // 不能用 xmap 里抛异常（异常会穿透 codec 框架）。与 StructureEffectEntry:21-25 同款。
    private static final Codec<Trigger> TRIGGER_CODEC = Codec.STRING.comapFlatMap(
            s -> switch (s) {
                case "empty_hand" -> DataResult.success(Trigger.EMPTY_HAND);
                case "any" -> DataResult.success(Trigger.ANY);
                default -> DataResult.error(() -> "Unknown trigger: " + s + " (expected empty_hand|any)");
            },
            t -> t == Trigger.ANY ? "any" : "empty_hand");

    public static final Codec<NpcDialogueEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            ENTITY_CODEC.fieldOf("entity").forGetter(NpcDialogueEntry::entity),
            TRIGGER_CODEC.optionalFieldOf("trigger", Trigger.EMPTY_HAND).forGetter(NpcDialogueEntry::trigger),
            Codec.STRING.optionalFieldOf("name").forGetter(NpcDialogueEntry::name),
            Codec.list(Page.CODEC).fieldOf("pages").forGetter(NpcDialogueEntry::pages)
    ).apply(i, NpcDialogueEntry::new));

    /** 供日志使用：本条目绑定的实体 id。 */
    public ResourceLocation entityId() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity);
    }
}
```

**验证：** `cd D:\Minecraft\BeLoong-Core-NPC; .\gradlew.bat build --console=plain` → `BUILD SUCCESSFUL`

---

### 2. 客户端加载器 `NpcDialogueLoader`

**文件：** 新增 `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueLoader.java`

**职责：** 客户端 reload listener；产出 `Map<EntityType<?>, NpcDialogueEntry>`；提供查询。

**骨架：**

```java
public class NpcDialogueLoader extends SimpleJsonResourceReloadListener {   // 目录约定与既有 4 个 loader 一致
    public static final NpcDialogueLoader INSTANCE = new NpcDialogueLoader();
    private Map<EntityType<?>, NpcDialogueEntry> entries = Map.of();

    private NpcDialogueLoader() { super(new Gson(), "beloong/npc_dialogue"); }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> files,
                         @NotNull ResourceManager manager, @NotNull ProfilerFiller profiler) {
        Map<EntityType<?>, NpcDialogueEntry> newMap = new HashMap<>();

        // 排序 ⇒ 重复绑定的"后处理者胜"是确定行为，不依赖文件系统枚举顺序（设计 D20）
        for (var file : files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            NpcDialogueEntry.CODEC.parse(JsonOps.INSTANCE, file.getValue())
                    .resultOrPartial(err -> BeLoongCore.LOGGER.error(
                            "Failed to parse npc dialogue file '{}': {}", file.getKey(), err))
                    .ifPresent(entry -> {
                        if (entry.pages().isEmpty()) {
                            BeLoongCore.LOGGER.error(
                                    "npc dialogue file '{}' has no pages, ignored", file.getKey());
                            return;
                        }
                        NpcDialogueEntry prev = newMap.put(entry.entity(), entry);
                        if (prev != null) {
                            BeLoongCore.LOGGER.warn(
                                    "duplicate npc dialogue for entity {}: '{}' overrides an earlier file",
                                    entry.entityId(), file.getKey());
                        }
                    });
        }

        this.entries = Map.copyOf(newMap);
        // 这行日志同时是任务 3 的 R0 运行时确认点
        BeLoongCore.LOGGER.info("[BeLoong] reloaded npc dialogues: {} entrie(s)", entries.size());
    }

    @Nullable
    public NpcDialogueEntry get(EntityType<?> type) { return entries.get(type); }
}
```

**验证：** `.\gradlew.bat build --console=plain` → `BUILD SUCCESSFUL`

---

### 3. 注册客户端重载监听器（+ R0 运行时确认）

**文件：** 修改 `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`（照本类既有 mod 总线注册同构加一个 static 方法）

**步骤：**

```java
/** 注册 NPC 对话数据加载器。数据随模组 jar 分发，客户端自行读取 ⇒ 不需要网络同步。 */
@SubscribeEvent
static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
    event.registerReloadListener(NpcDialogueLoader.INSTANCE);
}
```
（`import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;`）

**验证：**

1. `.\gradlew.bat build --console=plain` → `BUILD SUCCESSFUL`
2. **实机（R0 确认点）**：启动客户端进入世界（任务 11 之后才有数据文件；在此之前日志应打印 `0 entrie(s)`，打印出数字本身就证明监听器被调用了）

---

### 4. 配置项 `[npc_dialogue]`

**文件：** 修改 `src/main/java/com/zonlong/beloong/Config.java`

**归属决定：** 放 **`CLIENT_SPEC`**，不放 `SERVER_SPEC`。理由：整个功能是客户端的（渲染 + 触发都在客户端），`charsPerTick` / `nameScale` 是纯本地渲染口味；放进 SERVER 会给人"服务端能远程控制对话框外观"的错误暗示。

**步骤：** 在 CLIENT 段（`CLIENT_SPEC = CLIENT_BUILDER.build();` 之前）插入分组：

```java
// ========== npc_dialogue ==========
CLIENT_BUILDER.push("npc_dialogue");

NpcDialogue.enabled = CLIENT_BUILDER
        .comment("Enable the simple NPC dialogue screen",
                "启用简易 NPC 对话界面")
        .translation("beloong.configuration.npcDialogueEnabled")
        .define("enabled", true);

NpcDialogue.charsPerTick = CLIENT_BUILDER
        .comment("Typewriter speed in characters per tick (1 = default, ~20 chars/sec)",
                "打字机速度（字/tick），默认 1 ≈ 每秒 20 字")
        .translation("beloong.configuration.npcDialogueCharsPerTick")
        .defineInRange("charsPerTick", 1, 1, 20);

NpcDialogue.nameScale = CLIENT_BUILDER
        .comment("Speaker-name font scale. 1.0 = same size as body text (crispest);",
                "1.5 = as in the reference screenshots (non-integer scaling of the bitmap font",
                "makes some strokes 1px and others 2px)",
                "说话人名字的字号倍数。1.0 = 与正文同号（最清晰）；1.5 = 对齐参考图",
                "（位图字体非整数缩放会让部分笔画 1px、部分 2px）")
        .translation("beloong.configuration.npcDialogueNameScale")
        .defineInRange("nameScale", 1.5D, 1.0D, 2.0D);

CLIENT_BUILDER.pop(); // npc_dialogue
```

并在 `CLIENT_SPEC` 之后声明嵌套类（与 SERVER 段的 `DragonSummon` 同构）：

```java
/** 简易 NPC 对话（纯客户端）。 */
public static final class NpcDialogue {
    public static ModConfigSpec.BooleanValue enabled;
    public static ModConfigSpec.IntValue charsPerTick;
    public static ModConfigSpec.DoubleValue nameScale;
}
```

**语言文件（6 键 × 2 语言）：** `beloong.configuration.npcDialogueEnabled` + `.tooltip`、`npcDialogueCharsPerTick` + `.tooltip`、`npcDialogueNameScale` + `.tooltip`，**按字母序插入**。

**验证：**

```powershell
.\gradlew.bat build --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\Config.java -Pattern 'npc_dialogue|nameScale'
```
实机：模组菜单的配置界面里能看到 `npc_dialogue` 分组与三项，且显示中文名（而非原始键名）。

---

### 5. `NpcDialogueScreen` 骨架（能开、能关、世界不糊）

**文件：** 新增 `src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java`

**职责：** 屏幕类；此任务只做骨架 + 三条硬性覆写，渲染留到任务 6~9。

**骨架：**

```java
@OnlyIn(Dist.CLIENT)
public class NpcDialogueScreen extends Screen {
    // ===== 布局常量（全部集中在顶部，试玩调参只改这里；取值见设计文档 §3.1）=====
    private static final float NAME_Y = 0.796F;    // 名字中轴
    private static final float RULE_Y = 0.831F;    // 装饰线
    private static final float TEXT_Y = 0.858F;    // 正文中轴
    private static final float ARROW_Y = 0.964F;   // 继续箭头
    private static final float OPTION_LEFT = 0.661F, OPTION_BOTTOM = 0.787F;
    private static final int OPTION_H = 26, OPTION_GAP = 22;
    private static final float RULE_W = 0.33F;     // 装饰线宽（占屏宽）
    private static final int GRADIENT_TOP = 0x00000000, GRADIENT_BOTTOM = 0xC0000000;
    private static final float GRADIENT_START = 0.72F;

    private final NpcDialogueEntry entry;
    private final Entity speaker;
    // ...页面/揭示进度/状态字段见任务 6

    public NpcDialogueScreen(NpcDialogueEntry entry, Entity speaker) {
        super(Component.empty());     // 标题不需要；名字是自绘的（居中、金色、可缩放）
        this.entry = entry;
        this.speaker = speaker;
    }

    /**
     * 故意留空。
     * <p>原版 {@code Screen#renderBackground} 会调用 {@code renderBlurredBackground}
     * （把整个世界糊掉）+ {@code renderMenuBackground}（盖一层暗底贴图），
     * 与本功能"世界清晰、只有底部渐变压暗"的要求完全冲突。
     * 见设计文档 §8.2 / 决策 D13。
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    /** 用户裁定：不暂停，保持沉浸感（设计 D14）。 */
    @Override
    public boolean isPauseScreen() { return false; }
}
```

**验证：**

```powershell
.\gradlew.bat build --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\client\NpcDialogueScreen.java -Pattern 'renderBackground|isPauseScreen'
```
（此任务结束时屏幕上还是空白的——入口在任务 10，正式观感在任务 6~9。）

---

### 6. 文本层：预排版 + 打字机 + 点击状态机

**文件：** 同任务 5

**步骤：**

1. **取原文**：`String raw = Component.translatable(page.text()).getString();`
   —— `getString()` 已按当前语言解析，且 `§` 代码原样保留。
2. **先按 `\n` 切段**，再对每段做**按宽度贪心换行**，产出 `List<String> lines`（原始字符串，含 `§`）并缓存。
   —— 贪心时用 `font.width(Component.literal(候选))` 量宽；`§` 与其后一个字符作为**原子**处理。
3. **揭示**：维护 `revealed`（**可见字符数**，不含 `§` 对）。渲染时按 `revealed` 预算逐行取前缀：

```java
/** 取 raw 的前 n 个"可见字符"（§ + 代码字符成对跳过，不计入 n 且不切断）。 */
private static String prefixByVisibleChars(String raw, int n) {
    StringBuilder sb = new StringBuilder();
    int shown = 0;
    for (int i = 0; i < raw.length() && shown < n; ) {
        char c = raw.charAt(i);
        if (c == '\u00a7' && i + 1 < raw.length()) {   // § 代码：整对复制，不计数
            sb.append(c).append(raw.charAt(i + 1));
            i += 2;
        } else {
            sb.append(c);
            i++;
            shown++;
        }
    }
    return sb.toString();
}
```

4. **状态机**（设计 §3.4）：

```java
private enum State { TYPING, WAIT_CLICK, SHOWING_OPTIONS }
```

- `tick()`：`if (state == TYPING) { revealed += Config.NpcDialogue.charsPerTick.get(); if (revealed >= visibleTotal) { revealed = visibleTotal; state = WAIT_CLICK; } }`
- `mouseClicked(...)`：
  - `TYPING` → `revealed = visibleTotal; state = WAIT_CLICK;`（补全当前页）
  - `WAIT_CLICK` → 非最后一页：`pageIndex++; revealed = 0; state = TYPING;` / 是最后一页：`state = SHOWING_OPTIONS`（并创建选项按钮）
  - `SHOWING_OPTIONS` → 交给按钮处理（任务 9）
- 切页时重算 `lines` / `visibleTotal`（任务 2 的预排版按页缓存）。

**验证：** `.\gradlew.bat build --console=plain`；实机（任务 11 之后）：逐字出现、点击补全、再点翻页。

---

### 7. 名字 + 装饰线

**文件：** 同任务 5

**步骤：**

1. 名字文本：`entry.name().map(Component::translatable).orElse(speaker.getDisplayName())`
   —— 缺省用实体显示名（玩家用命名牌改过的铁傀儡会显示自定义名，这是**预期行为**，见设计 R6）。
2. **金色来自语言文件的 `§`**（示例给 `§6`），**代码不写死颜色**：用 `guiGraphics.drawString(font, component, x, y, 0xFFFFFFFF, true)` — 传 `0xFFFFFFFF` 时 `§` 代码仍会覆盖颜色。
3. 名字缩放围绕屏幕中轴：

```java
pose.pushPose();
pose.translate(this.width / 2.0F, nameY, 0.0F);
pose.scale(scale, scale, 1.0F);
guiGraphics.drawCenteredString(this.font, name, 0, 0, 0xFFFFFFFF);   // 居中点在 (0,0)
pose.popPose();
```

4. 装饰线：居中、宽 `RULE_W * width`、1px 高；**两端各一个小菱形端饰**（`fill` 逐行拼菱形，4~7 行）。金色建议取参考图的暖金（起始值 `0xE8B84B`，待试玩调）。

**验证：** `.\gradlew.bat build --console=plain`；实机：名字居中金色、下方一条金线带两端菱形、正文在其下方。

---

### 8. 继续箭头

**文件：** 同任务 5

**步骤：** 画**菱形外框 + 内部向下的亮金三角**（`fill` 拼像素，约 14×14），居中于 `ARROW_Y`；上下浮动 ±2px：

```java
int bob = (int) Math.round(Math.sin(tickCount * 0.15) * 2.0);   // 周期约 20 tick 的往返
```

**可见性规则（设计 D15）：** 只在 `state == WAIT_CLICK` 时绘制；进入 `SHOWING_OPTIONS` 后隐藏（参考5 是选项态，图中无箭头）。

**验证：** `.\gradlew.bat build --console=plain`；实机：文字显示完后箭头出现并轻微浮动，进入选项态后消失。

---

### 9. 选项按钮 `NpcDialogueOptionButton` + 选项态收尾

**文件：** 新增 `src/main/java/com/zonlong/beloong/client/NpcDialogueOptionButton.java`；修改 `NpcDialogueScreen.java`

**职责：** 自绘胶囊按钮（继承 `AbstractWidget`，白拿 hover / 点击 / 键盘导航）。

**骨架要点：**

```java
public class NpcDialogueOptionButton extends AbstractWidget {
    private static final int NORMAL_BG = 0xA0000000;   // 深黑半透明
    private static final int HOVER_BG  = 0xC0C8A05A;   // 半透明暖金（起始值）
    private float hover;                               // 0..1，每 tick 靠拢 0.25（≈200ms）

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1) 记录目标值：isHovered() ? 1 : 0
        // 2) hover = Mth.approach(hover, target, 0.25F)   —— 在 tick() 里推进更稳
        int bg = FastColor.ARGB32.lerp(hover, NORMAL_BG, HOVER_BG);   // FastColor.java:80
        // 3) 全圆角胶囊：中间矩形 + 左右各 13 行递减 fill（半径 = OPTION_H / 2）
        // 4) 左侧自绘"离开"图标（fill 拼门形/箭头），距左缘 6px
        // 5) 文字白色，drawString(..., true)
    }
}
```

**布局（设计 §3.1）：** 左缘固定 `x = OPTION_LEFT * width`；**宽度按文字宽自适应**（图标 + padding + 文字宽），因此右边缘天然参差（与参考图一致）；最下一颗**底边** `y = OPTION_BOTTOM * height`，向上依次排，间距 `OPTION_GAP`。

**v1 只有一个选项：** 「离开」，label 为翻译键 `beloong.dialogue.option.leave`，**硬编码不放进 JSON**（设计 D17）。点击 → `this.onClose()`。

**验证：** `.\gradlew.bat build --console=plain`；实机：最后一页显示完后右侧弹出「离开」，hover 时底色**平滑**转金（非瞬变），点击关闭。

---

### 10. 右键入口 `NpcDialogueHandler` + `openDialogue` 接缝

**文件：** 新增 `src/main/java/com/zonlong/beloong/client/NpcDialogueHandler.java`；修改 `BeLoongCoreClient.java`（构造函数里注册游戏总线）

**骨架：**

```java
@OnlyIn(Dist.CLIENT)
public class NpcDialogueHandler {

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!Config.NpcDialogue.enabled.get()) return;
        if (!event.getLevel().isClientSide()) return;              // 仅客户端受理（设计 D2）
        if (!(event.getEntity() instanceof Player player)) return;

        NpcDialogueEntry entry = NpcDialogueLoader.INSTANCE.get(event.getTarget().getType());
        if (entry == null) return;

        // 触发方式由数据驱动（设计 D3）：EMPTY_HAND 需主手为空；ANY 不看手持。不做潜行判断。
        if (entry.trigger() == NpcDialogueEntry.Trigger.EMPTY_HAND
                && !player.getMainHandItem().isEmpty()) return;

        openDialogue(player, event.getTarget(), entry);
        // 注意：不 setCanceled（设计 D4）—— 原版空手右键铁傀儡无行为，取消只会多抢第三方交互
    }

    /**
     * 唯一的"打开对话"入口 —— 将来接入第三方对话框模组时**只改这一个方法**（设计 §11）。
     */
    public static void openDialogue(Player player, Entity speaker, NpcDialogueEntry entry) {
        Minecraft.getInstance().setScreen(new NpcDialogueScreen(entry, speaker));
    }
}
```

`BeLoongCoreClient` 构造函数里加一行（与既有 `LoongPalaceSkyTickHandler` 同构）：
```java
NeoForge.EVENT_BUS.register(new NpcDialogueHandler());
```

**验证：**

```powershell
.\gradlew.bat build --console=plain
Select-String -Path src\main\java\com\zonlong\beloong\client\NpcDialogueHandler.java -Pattern 'isClientSide|EMPTY_HAND|setCanceled'
```
（最后一项应**无匹配** —— 确认没有取消事件。）

---

### 11. 示例数据 `iron_golem.json` + 语言文件对话键

**文件：**
- 新增 `src/main/resources/data/beloong/beloong/npc_dialogue/iron_golem.json`
- 修改 `src/main/resources/assets/beloong/lang/zh_cn.json`、`en_us.json`

**内容：**

```jsonc
{
  "entity": "minecraft:iron_golem",
  "trigger": "empty_hand",
  "name": "beloong.dialogue.iron_golem.name",
  "pages": [
    { "text": "beloong.dialogue.iron_golem.p1", "sound": "beloong:dialogue.iron_golem.1" },
    { "text": "beloong.dialogue.iron_golem.p2" }
  ]
}
```

语言键（**按字母序插入**，`§` 写 `\u00a7`）：
`beloong.dialogue.iron_golem.name` / `.p1` / `.p2`、`beloong.dialogue.option.leave`。
※ `sound` 字段本任务只做**数据通路**（解析但 v1 不播放，设计 D18）；示例里保留它，用于证明字段被正确解析。

**验证：**

```powershell
.\gradlew.bat build --console=plain
& "D:\Java\jdk-21.0.11\bin\jar.exe" tf build\libs\beloong-0.9.6.jar | Select-String 'npc_dialogue'
```
→ 应打印出 `data/beloong/beloong/npc_dialogue/iron_golem.json`
（文件名里的版本号跟随 `gradle.properties`，如已变动请按实际产物名替换。）

---

### 12. 实机验收（**由用户执行**）

按设计文档 §10 的 14 项二级清单逐条走。**重点确认：**

| 关键项 | 期望 |
|---|---|
| 世界是否清晰 | **不模糊、不变暗**，只有底部约 28% 渐变压暗（若糊了 = `renderBackground` 没覆写成空） |
| 空手右键铁傀儡 | 弹出对话 |
| 手持铁锭右键铁傀儡 | **不弹**，走原版回血（示例数据为缺省 `empty_hand`） |
| 把 `trigger` 改成 `any` 后 F3+T | 手持铁锭也弹（原版回血被吃掉，属预期） |
| 名字 / 装饰线 / 正文 | 居中；名字金色且比正文大一号；金线在名字与正文之间、两端有菱形端饰 |
| 箭头 | 文字显示完后出现、轻微浮动；进选项态后消失 |
| 「离开」hover | 底色**平滑**转金；点击关闭 |
| ESC | 任意时刻可关闭 |

**反馈方式：** 截图 + 指出偏差项。**所有间距/字号都是起始值**（设计 R5），收到反馈后我改 `NpcDialogueScreen` 顶部的常量即可，属分钟级改动。

---

### 13. 收尾

1. 回填设计文档 §13（实机验证 / 实现后审查 / 最终调参取值）。
2. 提交：`feat(dialogue): 简易 NPC 对话系统（数据驱动 + 原神式 UI）`，**不 push**。

---

## 三、风险与回退

| # | 风险 | 处置 |
|---|---|---|
| R0 | 客户端读不到 jar 内 `data/`（日志显示 0 条且文件确实在 jar 里） | 切 **方案 B**：把 `NpcDialogueLoader` 改为服务端 `AddReloadListenerEvent` 注册，加 `NpcDialogueSyncPayload`（照 `TreasureSyncPayload` 抄）+ 客户端 `ClientNpcDialogueCache`；**触发层与渲染层一行不动**。仅任务 2/3 需重做 |
| R1 | 名字 1.5× 缩放笔画参差 | 已是配置项：设 `nameScale = 1.0`（最清晰）或 `2.0`（清晰但偏大） |
| R2 | 存档数据包覆盖客户端不可见 | 已知限制；需要时走方案 B |
| R3 | 选项右边缘参差 | 参考图原生行为，非缺陷；如需整齐改为固定宽度 |
| R4 | 起始布局值需要调 | 常量集中在 `NpcDialogueScreen` 顶部；任务 12 的反馈回路负责收敛 |

---

## 四、不做什么（对齐设计文档 §2 非目标）

- ❌ 对话树 / 分支 / 条件 / 进度存储
- ❌ 服务端逻辑与网络包（除非 R0 触发方案 B）
- ❌ 任何 mixin
- ❌ 铁傀儡以外的实体（数据格式支持，但不交付）
- ❌ 第三方模组接入（只留 `openDialogue` 接缝）
- ❌ 头像 / 立绘 / 背景图 / 打字音效；`sound` 只解析不播放
- ❌ 实例级区分（NBT / 自定义名）
- ❌ 潜行判断
