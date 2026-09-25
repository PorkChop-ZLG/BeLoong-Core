# NPC 对话改为服务端权威的 data 数据驱动 实施计划

**Date:** 2026-09-25
**Status:** ✅ **已完成（`d7849e7` + `a85dd19`），实机验收通过（2026-09-25）** —— 明细见 §六 审查记录
**Design:** [2026-09-25-npc-dialogue-data-driven-design.md](./2026-09-25-npc-dialogue-data-driven-design.md)
**Approach:** 方案 **B1** —— 数据放 `data/`、服务端 reload listener 加载、右键在**服务端**受理、命中时把**该玩家要看的这一条**按需下发
**验证模型:** 本项目**无测试源集**（`gradlew build` 输出 `compileTestJava NO-SOURCE` / `test NO-SOURCE`），故**不引入测试框架**。每个任务的验证 = `gradlew build --console=plain` + 静态探针（rg）+ 实机清单（§四）。

---

## 〇、实现期修订（相对设计文档 §5.1：数据包**形状**）

设计文档 §5.1 写的是 `NpcDialogueOpenPayload(NpcDialogueEntry entry, int entityId)`。
实现前核实了一个会导致**连接被中止**的坑，因此修订为「客户端渲染所需的**最小事实**」：

```java
record NpcDialogueOpenPayload(
        Optional<String> nameKey,   // 说话人名（翻译键）；可选
        String fallbackNameKey,     // 实体类型名（EntityType#getDescriptionId），永远可用
        List<Page> pages,           // 页列表
        int entityId                // 目标实体网络 id（优先取命名牌自定义名）
)
```

**两条理由，都是"解码期不能失败"：**

1. **`StreamCodec` 解码失败无法优雅降级。** 解码阶段抛异常会中止连接（不是"丢一个包"）。
   而 `EntityType` 在客户端只能做注册表反查 —— 这是整条链路上**唯一可失败的一步**。
   把它从**解码期**挪到 `handleClient`，失败时丢包 + `LOGGER.error` 即可。
2. 兜底名改用 **`EntityType#getDescriptionId()`**（一个普通字符串，如 `entity.minecraft.iron_golem`），
   客户端**完全不需要注册表**。

附带结果（都比原设计更简单）：线格式**全函数、永不抛**；`trigger` **不需要上线**
（只有服务端受理判定用它）；客户端不再需要 `EntityType`。
`NpcDialogueScreen` 的构造签名相应改为 `(Component speakerName, List<Page> pages)` —— 状态机与渲染代码
仍**零改动**（只改 1 个字段 + 3 处 `entry.pages()` 访问）。

**已核实的 API**（NFRT 合并源 `sourcesAndCompiledWithNeoForge_*.jar`；与设计文档 §三 同一份）：

| API | 签名 | 出处 |
|---|---|---|
| `ByteBufCodecs.optional` | `static <B extends ByteBuf, V> StreamCodec<B, Optional<V>> optional(StreamCodec<B, V>)` | `ByteBufCodecs.java` |
| `ByteBufCodecs.list()` | `static <B, V> StreamCodec.CodecOperation<B, V, List<V>> list()` | 同上（项目已用） |
| `ResourceLocation.STREAM_CODEC` | `public static final StreamCodec<ByteBuf, ResourceLocation>` | `ResourceLocation.java` |
| `EntityType#getDescriptionId()` | `public String getDescriptionId()` | `EntityType.java` |
| `StreamCodec.composite` | 1–6 参重载；首参 `StreamCodec<? super B, T1>` | `StreamCodec.java` |
| `StreamCodec#mapStream` | `default <O extends ByteBuf> StreamCodec<O, V> mapStream(Function<O, ? extends B>)` | 同上 |
| `PacketDistributor.sendToPlayer` | `static void sendToPlayer(ServerPlayer, CustomPacketPayload…)` | `PacketDistributor.java:50` |

---

## 一、全局约束（每个任务都适用）

1. **T1–T8 必须落在同一个提交里。** 中间态是**运行时静默坏**：loader 已改成服务端注册而数据还在
   `assets/`（或反之）⇒ 右键**毫无反应且不报任何错**，正是首版 R0 记录的最难排查的失效模式。
   宁可提交大一点，也不留"编译过、跑起来哑"的中间态。
2. **不修改 `src/main/resources/beloong.mixins.json`** —— 本次零 mixin。
3. **语言文件不动**：文案仍在 `assets/beloong/lang/*.json`，本次**零新增语言键**
   （`beloong.configuration.npcDialogueEnabled` 与分组键 `beloong.configuration.npc_dialogue` 都已存在，
   服务端分组复用同一个键）。
4. **不新增依赖**（`build.gradle` 不动）。
5. **提交格式**：`feat(dialogue): …`（中文正文，对齐既有风格）。**不主动 `git push`**。
6. 新写的 Javadoc 要说明**为什么**，并对本次**被推翻的旧结论**（首版 R3、审查 S3）留痕。

---

## 二、实施步骤

### T1 给 `Page` 加线格式

**文件：** 修改 `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueEntry.java`

**要点：** 只给**嵌套的 `Page`** 加 `STREAM_CODEC`。**`NpcDialogueEntry` 本身不加剧场**（见 §〇：它不再是线载荷）。

```java
    public record Page(String text, Optional<ResourceLocation> sound) {
        public static final Codec<Page> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("text").forGetter(Page::text),
                ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(Page::sound)
        ).apply(instance, Page::new));

        /**
         * 线格式（网络下发用），与 {@link #CODEC} 语义一致、只是载体不同。
         * <p>
         * 本 codec 是**全函数**（两个字段都能无条件解出）—— 这是刻意的：
         * {@code StreamCodec} 解码失败会中止连接，所以线载荷里不能有需要查注册表的字段，
         * 详见 {@link NpcDialogueOpenPayload} 的类注释。
         */
        public static final StreamCodec<ByteBuf, Page> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Page::text,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), Page::sound,
                Page::new);
    }
```

**新增 import：** `io.netty.buffer.ByteBuf`、`net.minecraft.network.codec.ByteBufCodecs`、`net.minecraft.network.codec.StreamCodec`。

**验证：** `.\gradlew.bat build --console=plain` → `BUILD SUCCESSFUL`

---

### T2 新增 `NpcDialogueOpenPayload`

**文件：** 新增 `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueOpenPayload.java`

```java
package com.zonlong.beloong.dialogue;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.NpcDialogueScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Optional;

/**
 * 「打开 NPC 对话」网络包（服务端 → 单个玩家），只在该玩家右键命中时发送。
 * <p>
 * <b>为什么载荷是"最小事实"而不是 {@link NpcDialogueEntry} 本体</b>：
 * {@code StreamCodec} 解码失败**无法优雅降级** —— NeoForge 在解码阶段抛异常会中止连接。
 * 而条目里的 {@code EntityType} 在客户端只能做注册表反查，是整条链路上**唯一可失败的一步**。
 * 因此线载荷里不放 {@code EntityType}：
 * <ul>
 *   <li>兜底名改成 {@link net.minecraft.world.entity.EntityType#getDescriptionId()}（普通字符串）；</li>
 *   <li>需要实体本体的场合由 {@link #entityId()} 在客户端按网络 id 找（找不到就退到兜底名）；</li>
 *   <li>{@code trigger} 上线没有意义 —— 触发判定只在服务端做。</li>
 * </ul>
 * 结果：线格式**全函数、永不抛**。
 */
public record NpcDialogueOpenPayload(
        Optional<String> nameKey,
        String fallbackNameKey,
        List<NpcDialogueEntry.Page> pages,
        int entityId
) implements CustomPacketPayload {

    public static final Type<NpcDialogueOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "npc_dialogue_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcDialogueOpenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), NpcDialogueOpenPayload::nameKey,
                    ByteBufCodecs.STRING_UTF8, NpcDialogueOpenPayload::fallbackNameKey,
                    NpcDialogueEntry.Page.STREAM_CODEC.apply(ByteBufCodecs.list()), NpcDialogueOpenPayload::pages,
                    ByteBufCodecs.VAR_INT, NpcDialogueOpenPayload::entityId,
                    NpcDialogueOpenPayload::new
            ).mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理器。默认在主线程执行（与 {@code TreasureSyncPayload} 同款）。 */
    public static void handleClient(NpcDialogueOpenPayload payload, IPayloadContext context) {
        NpcDialogueScreen.open(payload);
    }
}
```

**注意：**
- `mapStream(buf -> (ByteBuf) buf)` 是**项目已验证的写法**（`TreasureSyncPayload:48`）——
  `StreamCodec.composite` 的每个分量都按 `ByteBuf` 组装，最后整体抬到 `RegistryFriendlyByteBuf`。
- 若 `ByteBufCodecs.optional(...)` 在 composite 里类型推断失败，显式写见证：
  `ByteBufCodecs.<ByteBuf, String>optional(ByteBufCodecs.STRING_UTF8)`。
- `handleClient` 引用客户端类 `NpcDialogueScreen` —— 与 `TreasureSyncPayload.handleClient` 引用
  `ClientTreasureCache` **完全同款**：专用服务器上该方法永不被调用，故 `Screen` 类不会被加载。

**验证：** `.\gradlew.bat build --console=plain` → `BUILD SUCCESSFUL`

---

### T3 服务端受理：`dialogue/NpcDialogueHandler`（迁入 + 反转侧判定）

**文件：**
- 新增 `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java`
- **删除** `src/main/java/com/zonlong/beloong/client/NpcDialogueHandler.java`

```java
package com.zonlong.beloong.dialogue;

import com.zonlong.beloong.Config;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC 对话的右键受理（**纯服务端**）。
 * <p>
 * 首版在客户端受理（当时 v1 无副作用）。改到服务端的原因见设计文档 §一：
 * 服务器权威，且将来按进度判据筛选内容必须在服务端做。
 * <p>
 * <b>为什么只认主手</b>：原版一次右键通常只派发主手事件（副手仅在主手 PASS 时才轮到），
 * 限定主手可保证"一次右键最多打开一次对话"。
 * <p>
 * <b>刻意不取消事件</b>（首版 D4 不变）：空手右键本无原版行为，取消没有收益，
 * 却会抢掉将来第三方模组的交互。
 * <p>
 * 由 {@code BeLoongCore} 构造时注册到游戏总线。
 */
public class NpcDialogueHandler {

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!Config.NpcDialogue.enabled.get()) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Player player = event.getEntity();
        // 事件两侧都会派发（首版 §8.10）：本功能只受理服务端那一次。
        if (player.level().isClientSide()) {
            return;
        }

        Entity target = event.getTarget();
        NpcDialogueEntry entry = NpcDialogueLoader.INSTANCE.get(target.getType());
        if (entry == null) {
            return;
        }

        // 触发方式由数据文件决定（首版 D3）：EMPTY_HAND 要求主手为空
        // （保住"手持铁锭右键铁傀儡 = 修血"的原版语义）；ANY 则不看手持。
        if (entry.trigger() == NpcDialogueEntry.Trigger.EMPTY_HAND && !player.getMainHandItem().isEmpty()) {
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        PacketDistributor.sendToPlayer(serverPlayer, new NpcDialogueOpenPayload(
                entry.name(),
                target.getType().getDescriptionId(),
                entry.pages(),
                target.getId()));
    }
}
```

**注意：** 该类**不得**放在 `client/` 包、**不得**加 `@OnlyIn(Dist.CLIENT)`（专服必须加载它）。
**距离校验刻意不手写**（设计 §D29）：原版服务端在交互包处理里已有校验。

**验证：**
```
.\gradlew.bat build --console=plain
rg -n "class NpcDialogueHandler" src/main/java/com/zonlong/beloong/dialogue/   # 期望 1 处
Test-Path src/main/java/com/zonlong/beloong/client/NpcDialogueHandler.java     # 期望 False
```

---

### T4 `NpcDialogueLoader`：注释重写（`volatile` 一条**已作废**）

> ⚠️ **本条已被实现期核实作废**：`apply` 走 gameExecutor、与服务端主线程上的 `get()` **同线程**，
> **不需要** `volatile`。详见 §六 审查记录 **I-1**。下面的原计划文本保留以便追溯，**不要照抄**。

**文件：** 修改 `src/main/java/com/zonlong/beloong/dialogue/NpcDialogueLoader.java`

**改动 1 —— 类注释整段重写**（旧注释解释的是"为什么放 `assets/`"，现在是反的）：

```java
/**
 * NPC 对话数据加载器（**服务端**）。
 * <p>
 * 从 {@code data/beloong/beloong/npc_dialogue/*.json} 读取，随模组 jar 分发，
 * 由 {@code AddReloadListenerEvent} 注册 —— **启动与 `/reload` 都会重新加载**。
 * <p>
 * <b>为什么目录字符串仍然是 {@code "beloong/npc_dialogue"}</b>：它是 **PackType 相对**的，
 * 不是绝对路径。服务端资源管理器以 {@code PackType.SERVER_DATA} 构造
 * （{@code MinecraftServer.java:1511}），客户端以 {@code CLIENT_RESOURCES} 构造
 * （{@code Minecraft.java:491}），路径解析再按 pack type 加目录前缀
 * （{@code FallbackResourceManager} → {@code PackResources.listResources(packType, …)}）。
 * ⇒ **同一个字符串，在服务端监听器上读 `data/`，在客户端监听器上读 `assets/`。**
 * 首版把本 loader 注册在客户端（{@code RegisterClientReloadListenersEvent}）却把数据放在
 * {@code assets/} —— 那不是必须的，只是当时"纯客户端"这一取舍的产物（首版 R3）。
 * <p>
 * <b>数据流</b>：服务端加载 → 玩家右键命中时由 {@code NpcDialogueHandler} 把**那一条**
 * 通过 {@code NpcDialogueOpenPayload} 下发给该玩家。**客户端不持有全表**，
 * 因此没有客户端缓存、也没有登录全量同步。
 * <p>
 * <b>失败隔离</b>：单个文件解析失败只丢弃该文件并打错误日志，绝不中断其余文件的加载。
 * 反面教材见 MCA Conversations 的 {@code DATAPACK.md}。
 * <p>
 * <b>重复绑定</b>：同一实体类型被多个文件绑定时，按 {@link ResourceLocation} 排序后
 * **后处理者胜**并打 warning（保证结果不依赖文件系统的枚举顺序）。
 */
```

**改动 2 —— `entries` 加 `volatile`**：

```java
    /**
     * 解析后的对话表。
     * <p>
     * <b>{@code volatile} 是必需的</b>：{@code apply} 在**重载工作线程**写、{@code get()}
     * 在**服务端主线程**读。首版审查的 S3 曾判"不需要 volatile"，理由是
     * "两者都在客户端主线程" —— 本次搬到服务端**推翻了这个前提**，勿按旧理由回改。
     */
    private volatile Map<EntityType<?>, NpcDialogueEntry> entries = Map.of();
```

**其余逻辑（`apply` / `get` / 日志行）不动** —— 那行"扫描文件数 + 装载条数"日志继续保留，
它现在是"**服务端能否读到 `data/` 树**"的运行时观测点。

**验证：** `.\gradlew.bat build --console=plain`；`rg -n "volatile" …/NpcDialogueLoader.java` → **不得**出现在字段声明上（见 §六 I-1）

---

### T5 `NpcDialogueScreen`：构造签名 + 名字回退链

**文件：** 修改 `src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java`

**改动 1 —— 字段**（原 `private final NpcDialogueEntry entry;`）：

```java
    private final Component speakerName;
    private final List<NpcDialogueEntry.Page> pages;
```

**改动 2 —— 构造器**（原 `public NpcDialogueScreen(NpcDialogueEntry entry, Entity speaker)`）：

```java
    public NpcDialogueScreen(Component speakerName, List<NpcDialogueEntry.Page> pages) {
        super(Component.empty());
        this.speakerName = speakerName;
        this.pages = pages;
    }
```

**改动 3 —— 新增静态打开入口**（名字回退链的唯一实现处）：

```java
    /**
     * 由 {@code NpcDialogueOpenPayload} 调用：把"服务端发来的最小事实"变成一个屏幕。
     * <p>
     * 名字回退链：数据文件指定的翻译键 → 实体自身的显示名（能拿到实体时，含命名牌自定义名）
     * → 实体类型名（{@code EntityType#getDescriptionId()}，服务端随包发来的兜底键）。
     * 原实现只有前两级，且假设实体一定在客户端存在；现在实体可能未加载，故补第三级。
     */
    @OnlyIn(Dist.CLIENT)
    public static void open(NpcDialogueOpenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity speaker = minecraft.level == null ? null : minecraft.level.getEntity(payload.entityId());

        // 显式写出 <Component>：translatable 返回 MutableComponent，
        // 与 getDisplayName / orElseGet 的 Component 不同，不写见证会让 map/orElseGet 推断失败。
        Component name = payload.nameKey()
                .<Component>map(Component::translatable)
                .orElseGet(() -> speaker != null
                        ? speaker.getDisplayName()
                        : Component.translatable(payload.fallbackNameKey()));

        minecraft.setScreen(new NpcDialogueScreen(name, payload.pages()));
    }
```

**改动 4 —— 两处 `entry.pages()` 访问**：
- `:135` `this.entry.pages().get(this.pageIndex).text()` → `this.pages.get(this.pageIndex).text()`
- `:195` `this.entry.pages().size()` → `this.pages.size()`

**新增 import：** `net.minecraft.client.Minecraft`、`com.zonlong.beloong.dialogue.NpcDialogueOpenPayload`；
`Entity` 与 `NpcDialogueEntry` 的 import 保留（`NpcDialogueEntry.Page` 仍要）。
**`@OnlyIn(Dist.CLIENT)` 已在类上**，静态方法无需重复标注（写了也无害）。

**状态机与全部渲染代码零改动。**

**验证：** `.\gradlew.bat build --console=plain`；
`rg -n "entry\.pages\(\)" …/client/NpcDialogueScreen.java` → 期望**无输出**

---

### T6 注册点切换（`BeLoongCore` / `BeLoongCoreClient`）

**文件：** 修改 `src/main/java/com/zonlong/beloong/BeLoongCore.java`

1. `addServerReloadListeners` 里加一行（与既有 4 个 loader 并列）：
```java
        event.addListener(NpcDialogueLoader.INSTANCE);   // NPC 对话（服务端权威，data/ 树）
```
2. 构造函数里注册服务端受理器（放在其他 `NeoForge.EVENT_BUS.register(...)` 之间）：
```java
        NeoForge.EVENT_BUS.register(new NpcDialogueHandler());   // NPC 对话：服务端受理右键
```
3. **把 payload 注册从内联 lambda 抽成私有方法**（现在有两条，内联 lambda 会开始难读）：
```java
        modEventBus.addListener(this::registerPayloads);
```
```java
    /** 网络包注册（play 阶段、服务端 → 客户端）。 */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID);
        registrar.playToClient(
                TreasureSyncPayload.TYPE,
                TreasureSyncPayload.STREAM_CODEC,
                TreasureSyncPayload::handleClient);
        // NPC 对话：不登录同步，只在玩家右键命中时下发那一条
        registrar.playToClient(
                NpcDialogueOpenPayload.TYPE,
                NpcDialogueOpenPayload.STREAM_CODEC,
                NpcDialogueOpenPayload::handleClient);
    }
```
（`PayloadRegistrar` 的 import：`net.neoforged.neoforge.network.registration.PayloadRegistrar`。）

**文件：** 修改 `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`

1. **删** `NeoForge.EVENT_BUS.register(new NpcDialogueHandler());` 及其 import。
2. **整段删** `registerClientReloadListeners` 方法（它只注册了对话 loader）及
   `RegisterClientReloadListenersEvent` / `NpcDialogueLoader` 的 import。

**验证：**
```
.\gradlew.bat build --console=plain
rg -n "RegisterClientReloadListenersEvent|NpcDialogueLoader|NpcDialogueHandler" src/main/java/com/zonlong/beloong/BeLoongCoreClient.java   # 期望无输出
rg -n "addListener\(NpcDialogueLoader|NpcDialogueOpenPayload" src/main/java/com/zonlong/beloong/BeLoongCore.java                            # 期望各 1 处以上
```

---

### T7 `Config`：`enabled` 迁到 `SERVER_SPEC`

**文件：** 修改 `src/main/java/com/zonlong/beloong/Config.java`

**现状：** `CLIENT_BUILDER` 的 `static {}` 块（`:46-71`）里 push `"npc_dialogue"` 并定义
`enabled` / `charsPerTick` / `nameScale` 三项。`SERVER_BUILDER` 声明在 `:154`，
服务端配置的 `static {}` 块在 `:256`。

**改法：**

1. `NpcDialogue` 静态类里 `enabled` 的注释改为（**同一个字段，换宿主**）：
```java
        /** 总开关（默认启用）。**服务端配置** —— 服务端据此决定是否受理右键。 */
        public static ModConfigSpec.BooleanValue enabled;
```
2. 从客户端 `static {}` 块里**删掉** `enabled` 的 `.define(...)`，其余两项保留。
3. 在**服务端** `static {}` 块（`:256` 起）内新增该组的定义 —— 注意**必须成对 push/pop**：
```java
        // ========== npc_dialogue ==========
        // 触发判定在服务端，所以"要不要受理右键"是服务端决定（首版 D19 的"给第三方模组让位"
        // 本就是整合包/服务端级诉求，不是玩家偏好）。纯渲染参数仍在 CLIENT_SPEC。
        SERVER_BUILDER.push("npc_dialogue");

        NpcDialogue.enabled = SERVER_BUILDER
                .comment("Enable the simple NPC dialogue screen",
                        "启用简易 NPC 对话界面")
                .translation("beloong.configuration.npcDialogueEnabled")
                .define("enabled", true);

        SERVER_BUILDER.pop(); // npc_dialogue
```
4. 更新 `:30` 那段类注释（现在写的是"触发判定与渲染都发生在客户端，故放 CLIENT_SPEC"，已不成立）。

**注意（静态初始化顺序）：** `SERVER_BUILDER` 在 `:154` 才被声明，所以 `enabled` 的赋值**必须在
`:256` 之后的服务端 `static {}` 块里**，不能留在 `:46` 的客户端块 —— 否则 `SERVER_BUILDER` 还是 null。

**验证：**
```
.\gradlew.bat build --console=plain
rg -n "npc_dialogue" src/main/java/com/zonlong/beloong/Config.java     # 期望：两处 push（一客户端一服务端）
```
> 运行时：服务端配置落在 `saves/<world>/serverconfig/beloong-server.toml`（**每个世界一份**）。

---

### T8 数据搬迁（`git mv`，文件内容一字不改）

**文件：**
```
git mv src/main/resources/assets/beloong/beloong/npc_dialogue/iron_golem.json \
       src/main/resources/data/beloong/beloong/npc_dialogue/iron_golem.json
```
（Windows 下用 `Move-Item` 亦可，但 `git mv` 能保住 rename 记录。）
搬迁后 `assets/beloong/beloong/` 应为空目录 —— git 不跟踪空目录，无需手动删。

**验证：**
```
.\gradlew.bat build --console=plain
jar tf build/libs/beloong-0.9.6.jar | Select-String npc_dialogue   # 期望：data/beloong/beloong/npc_dialogue/iron_golem.json
Test-Path src/main/resources/assets/beloong/beloong/npc_dialogue    # 期望 False
```

---

### T9 原子提交

T1–T8 一起提交（`.gitignore` 排除的 `memory/`、`build/`、`run/` 不入库）：

```
git add -A
git commit -m "feat(dialogue): NPC 对话改为服务端权威的 data 数据驱动（方案 B1）" \
           -m "数据由 assets/beloong/beloong/npc_dialogue/ 迁到 data/ 同路径；注册点由
RegisterClientReloadListenersEvent 换成 AddReloadListenerEvent；右键改在服务端受理，命中后把
该玩家要看的这一条按需下发（NpcDialogueOpenPayload）。客户端不再持有全表，无缓存、无全量同步。
[enabled] 由 CLIENT_SPEC 迁到 SERVER_SPEC；[charsPerTick]/[nameScale] 仍留客户端。" \
           -m "线载荷刻意只承载「客户端渲染所需的最小事实」（名字键/实体类型名键/页/实体网络 id），
不放 EntityType —— StreamCodec 解码失败会中止连接，而注册表反查是唯一可失败的一步，故把它
移到 handleClient。trigger 不上线（只有服务端判定用它）。" \
           -m "另注：NpcDialogueLoader.entries 维持不加 volatile —— 实现期一度误判为「apply 在重载线程、
get 在服务端主线程」而加了它，经核实 apply 走 gameExecutor（服务端主线程），与 get 同线程，
首版审查 S3 的结论依然成立。"
```

---

## 三、静态探针清单（提交前一次跑完）

```powershell
cd D:\Minecraft\BeLoong-Core-NPC
.\gradlew.bat build --console=plain

# 1. 客户端已完全不参与对话数据/触发
rg -n "RegisterClientReloadListenersEvent|NpcDialogueLoader|NpcDialogueHandler" src/main/java/com/zonlong/beloong/BeLoongCoreClient.java
#    期望：无输出

# 2. 服务端注册齐了
rg -n "NpcDialogueLoader|NpcDialogueHandler|NpcDialogueOpenPayload" src/main/java/com/zonlong/beloong/BeLoongCore.java
#    期望：3 处（listener / handler / payload）

# 3. handler 不在 client 包、且无 @OnlyIn
Test-Path src/main/java/com/zonlong/beloong/client/NpcDialogueHandler.java        # False
rg -n "OnlyIn" src/main/java/com/zonlong/beloong/dialogue/NpcDialogueHandler.java # 无输出

# 4. 不得给 entries 加 volatile（见 §六 I-1：apply 与 get 同处服务端主线程）
rg -n "volatile" src/main/java/com/zonlong/beloong/dialogue/NpcDialogueLoader.java
#    期望：只出现在 javadoc 文本里，字段声明上无该修饰符

# 5. 屏幕不再直接摸 entry
rg -n "entry\.pages\(\)" src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java  # 无输出

# 6. 零 mixin、零新依赖
git diff --stat HEAD -- src/main/resources/beloong.mixins.json build.gradle   # 期望：无输出

# 7. 数据在 data/ 树
Test-Path src/main/resources/data/beloong/beloong/npc_dialogue/iron_golem.json  # True
Test-Path src/main/resources/assets/beloong/beloong/npc_dialogue                # False

# 8. 产物正确
jar tf build/libs/beloong-0.9.6.jar | Select-String "npc_dialogue"
```

---

## 四、实机验收清单（需用户执行）

| # | 步骤 | 预期 | 证明什么 |
|---|---|---|---|
| 1 | 看服务端日志 | `reloaded npc dialogues: 1 file(s) scanned, 1 dialogue(s) loaded` | 服务端读到了 `data/` 树 |
| 2 | **把 json 放进世界数据包** `saves/<world>/datapacks/x/data/beloong/beloong/npc_dialogue/iron_golem.json`，删掉 jar 里那份，空手右键铁傀儡 | 仍然弹窗 | **决定性验收**：改前客户端读不到 `data/`，这一步**必失败** |
| 3 | 给 json 加第三页 → `/reload` → 右键（**不退出重进**） | 出现第三页 | 服务端数据热重载生效（改前对 `/reload` 完全无感） |
| 4 | 世界数据包放同名文件覆盖 jar | 覆盖生效 | 数据包覆盖语义 |
| 5 | 手持铁锭右键（缺省 `empty_hand`） | 不触发，走原版修血 | 触发语义未回归 |
| 6 | 对未配置实体（牛）空手右键 | 无反应 | — |
| 7 | `saves/<world>/serverconfig/beloong-server.toml` 里 `enabled=false` | 右键无反应 | 新配置归属生效、且按世界生效 |
| 8 | 模组菜单 → 服务端配置组 | 出现「简易 NPC 对话」且显示中文名 | 分组语言键复用正确 |
| 9 | **联机**：两名玩家，一人右键 | 只有右键者弹窗 | 下发是点对点的 |
| 10 | 打字机 / 点击补全 / 翻页箭头 / 末页弹选项 / 悬停金色过渡 / ESC / 世界不模糊 | 与改前逐项一致 | 回归（屏幕状态机未改，应零偏差） |
| 11 | **给实体命名牌改名后再右键** | 屏幕上显示自定义名（而非"铁傀儡"） | 名字回退第 2 级仍生效（首版 R6 语义未丢） |
| 12 | 读旧存档 | 正常 | 对话无存档数据，不需要修复 |

---

## 六、审查记录（2026-09-25）

独立代码审查：**无 Critical，3 个 Important、4 个 Suggestion**，全部处置完毕。
最值得记的是：**3 个 Important 全是"注释/文档里的断言不成立"，没有一个是行为缺陷**
（代码本身审查判定可提交）。

| # | 级别 | 内容 | 处置 |
|---|---|---|---|
| **I-1** | Important | 本计划 T4 与设计文档 D32/R8 断言"`apply` 在重载工作线程、`get()` 在服务端主线程 ⇒ 跨线程 ⇒ 需要 `volatile`"。**该前提是错的**：`SimplePreparableReloadListener#reload` 的 `apply` 走 **gameExecutor**（只有 `prepare` 在后台线程），而 `MinecraftServer.java:1512-1519` 传给 `loadResources` 的 gameExecutor 就是服务端自己（继承 `BlockableEventLoop`）⇒ 与主线程上的 `get()` **同线程** | 字段改回**非** `volatile`；按事实重写注释；D32/R8 就地标注修正；`memory/decisions-log.md` 记下真实教训 |
| **I-2** | Important | 设计文档宣称线载荷"**全函数、永不抛**"，但 `Page.STREAM_CODEC` 用的 `ResourceLocation.STREAM_CODEC` 内部是 `parse()`，**畸形输入会抛** ⇒ 该不变量是假的。而"解码失败会中止连接"正是本次请 `EntityType` 下线载荷的理由，所以这条必须是真的 | 改用 `ResourceLocation.tryParse`（返回 null 而不抛）构造 `SOUND_STREAM_CODEC`；并复核其余字段（`optional`/`list`/`STRING_UTF8`/`VAR_INT`）均无抛点 |
| **I-3** | Important | `NpcDialogueEntry` 类注释仍是旧架构口径（"客户端侧 loader 解析、不需要网络同步"） | 改为服务端加载 + 按需下发 |
| S-4 | Suggestion | 现为**服务端配置**的 `enabled` 在 `isClientSide()` 早退**之前**被读 | 服务端早退提到读配置之前 |
| S-5 | Suggestion | `open()` 未防 `pages` 为空 ⇒ `loadPage()` 里 `get(0)` 越界（今天不可达，但该方法按设计是"永不抛"边界） | 加空页早退 |
| S-6 | Suggestion | `mapStream(buf -> (ByteBuf) buf)` 其实**不是必需的**（`composite` 首参为 `? super B`） | 保留以与 `TreasureSyncPayload` 统一，注释改为如实说明 |
| S-7 | Suggestion | 语言文件分组提示语仍写"（纯客户端功能）" | 改为"数据由服务端下发；下方两项只影响本地观感" |

审查同时核实为**正确**的部分（供后续参照）：专服安全（客户端类只出现在 `handleClient` 方法体内
⇒ 惰性解析、专服不加载 `Screen` 一系）、协议（`playToClient` 的 `? super RegistryFriendlyByteBuf`
相容、handler 默认主线程、`Level#getEntity` 对未知/负数 id 返回 null 不抛）、侧判定反转正确、
配置静态初始化顺序安全、行为无回归（名字回退链保住命名牌语义、全部调参常量与渲染路径未被触碰）、
loader 的严格性与重复绑定规则未变、注释里引用的两处原版行号准确。

---

## 五、回填（实现并验收后）

1. 设计文档 `2026-09-25-npc-dialogue-data-driven-design.md` 追加「实现期修订」小节，
   记录 §〇 的线载荷形状修订与实测结论。
2. `memory/project-context.md` 子系统 9：把"正在变更"改为"已完成"的口径，并记下 `data/` 路径。
3. `memory/decisions-log.md`：补一条"StreamCodec 解码失败会中止连接 ⇒ 线载荷里不放需要查注册表的字段"
   的通用教训（这是本次唯一的新坑，与"构造函数标志位"同源：**失败时机决定架构**）。
4. 旧文档 `2026-09-20-npc-dialogue-design.md` 的 R4 已指向本次；无需再改。
