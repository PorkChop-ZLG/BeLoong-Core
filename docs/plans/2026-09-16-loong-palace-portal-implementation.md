# 龙宫传送门（荧石框架 + 注水激活）Implementation Plan

**Goal:** 实现 `beloong:loong_palace_portal`：荧石框架内注水点亮，走进即传送（仅主世界 ↔ 龙宫、仅龙玩家），
落点全部复用 `teleport` 包既有 API，门面资产从天境 jar 复制。

**Architecture:** 四个新类（形状探测 / 门方块 / 注水激活 / 失败提示）+ 一个音效注册类；
门方块只做"登记 + 选目标 + 调 API"，形状探测独立成件（点亮时门方块尚不存在）。
形状探测复刻天境 `AetherPortalShape`；门面**继承原版 `minecraft:block/nether_portal_{ns,ew}`** + 天境动态贴图。

**Approach:** 按"依赖顺序 + 每类一个可验证节点"切成 6 个任务：形状 → 音效 → 门方块 → 激活与提示 → 接线与资产 → 静态验证。
每任务以 `gradlew.bat build` + 针对性静态检查收口；行为验证统一走实机（用户执行）。

**依据文档:** [`docs/plans/2026-09-16-loong-palace-portal-design.md`](2026-09-16-loong-palace-portal-design.md)（Approved）

**本仓库的验证现实**：无测试源集（`compileTestJava NO-SOURCE`），无 TDD 红绿循环；
`git commit` 由用户执行。

**规划期发现的一处收口（纳入 T1）**：龙宫维度 `ResourceKey<Level>` 现在有 **3 处各自定义**
（`TpLoongPalaceEffect.LOONG_PALACE_LEVEL`、`DimensionTransportHandler.LOONG_PALACE_ID`、本门还要一处）。
统一为 `TeleportTarget.LOONG_PALACE_LEVEL`（`teleport` 包本就因 `toLoongPalace` 认识龙宫）。

---

### Task 1: `LoongPalacePortalShape` —— 框架探测与点亮

**Files:**
- Create: `src/main/java/com/zonlong/beloong/block/LoongPalacePortalShape.java`
- Modify: `src/main/java/com/zonlong/beloong/teleport/TeleportTarget.java`（+`LOONG_PALACE_LEVEL` 常量）
- Modify: `src/main/java/com/zonlong/beloong/ability/TpLoongPalaceEffect.java`（改为引用该常量）
- Modify: `src/main/java/com/zonlong/beloong/transport/DimensionTransportHandler.java`（改为引用该常量）

**Steps:**
1. `TeleportTarget` 新增：
   ```java
   /** 龙宫维度（编译期常量）。本包因 toLoongPalace 而认识龙宫，故常量收口在此，供所有调用方复用。 */
   public static final ResourceKey<Level> LOONG_PALACE_LEVEL = ResourceKey.create(
           Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("beloong", "loong_palace"));
   ```
   `toLoongPalace` 的 javadoc 相应改写（不再说"维度 ID 由调用方给"）。
2. `TpLoongPalaceEffect` 删除自己的 `LOONG_PALACE_LEVEL`，改用 `TeleportTarget.LOONG_PALACE_LEVEL`；
   `DimensionTransportHandler` 删除 `LOONG_PALACE_ID`，改用 `TeleportTarget.LOONG_PALACE_LEVEL.location()`。
   **行为不变**（两处原来就是同一个 ID）。
3. 新建**包内可见**（`final class`，无 `public`）的 `LoongPalacePortalShape`，复刻天境
   `AetherPortalShape`（`The-Aether/.../block/portal/AetherPortalShape.java`）：
   - 字段：`level / axis / rightDir / numPortalBlocks / @Nullable bottomLeft / height / width`
   - `static Optional<LoongPalacePortalShape> findEmpty(LevelAccessor level, BlockPos pos, Direction.Axis axis)`
     → `findPortalShape(level, pos, shape -> shape.isValid() && shape.numPortalBlocks == 0, axis)`
   - `static Optional<LoongPalacePortalShape> findPortalShape(..., Predicate<LoongPalacePortalShape>, axis)`
     → 先按给定轴试，失败再按另一水平轴试
   - `private static final BlockBehaviour.StatePredicate FRAME = (state, level, pos) ->
        state.is(ModBlockTags.LOONG_PALACE_PORTAL_FRAME);`
   - `private static boolean isEmpty(BlockState state)` → **空气 ∨ 水 ∨ 本门方块**
   - `calculateBottomLeft / calculateWidth / calculateHeight / hasTopFrame / getDistanceUntilTop / getDistanceUntilEdgeAboveFrame`
     （宽 2–21、高 3–21，与天境逐字同构）
   - `public boolean isValid()` / `public boolean isComplete()` / `public void createPortalBlocks()`
     → `setBlock(pos, 门方块.defaultBlockState().setValue(AXIS, axis), 2 | 16)`
4. **`ModBlockTags`**：新建 `src/main/java/com/zonlong/beloong/registry/ModBlockTags.java`，只放一个常量：
   `public static final TagKey<Block> LOONG_PALACE_PORTAL_FRAME = BlockTags.create(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace_portal_frame"));`
   （项目此前无 block tag 常量类；天灾门用的是 `data/minecraft/tags/block/*.json` 直接写，没有 Java 常量）

**调用方注意（收口后必须同步）**：`TeleportTarget.LOONG_PALACE_LEVEL` 是**新引入的公共常量**，
三处调用方都要改为引用它，避免继续各写一份。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"          # 期望 0
# 龙宫维度常量只剩一处定义
(Get-ChildItem src -Recurse -File -Include *.java | Select-String -Pattern 'fromNamespaceAndPath\("beloong", "loong_palace"\)').Count   # 期望 0
(Get-ChildItem src -Recurse -File -Include *.java | Select-String -Pattern 'LOONG_PALACE_LEVEL|LOONG_PALACE_ID').Count              # 期望 ≥4（1 定义 + 3 引用）
```

---

### Task 2: `ModSounds` + 音效资源

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ModSounds.java`
- Create: `src/main/resources/assets/beloong/sounds.json`
- Create: `src/main/resources/assets/beloong/sounds/portal/{portal,trigger,travel}.ogg`（从天境 jar 复制）
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（`ModSounds.register(modEventBus)`）

**Steps:**
1. 建 `ModSounds`，模式照抄项目既有注册类（`DeferredRegister` + `static register(IEventBus)`）：
   ```java
   public static final DeferredRegister<SoundEvent> SOUNDS =
           DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, BeLoongCore.MODID);
   // 键名规则：命名空间 beloong + 方块 ID loong_palace_portal ⇒ block.beloong.loong_palace_portal.*
   // （与天境的 block.aether_portal.* 同构；天境 modid 即 aether）
   public static final DeferredHolder<SoundEvent, SoundEvent> LOONG_PALACE_PORTAL_AMBIENT =
           register("block.beloong.loong_palace_portal.ambient");
   // + TRIGGER / TRAVEL
   ```
   ⚠️ 1.21.1 的 `SoundEvent` 是**注册表对象**，`register` 需用 `SoundEvent.createVariableRangeEvent(ResourceLocation)`
   或 `createFixedRangeEvent(...)`；**不要**直接 `new SoundEvent(...)`（构造器不可见/需 holder）。
2. `sounds.json`（条目照抄天境 `assets/aether/sounds.json` 的三条，只改名与路径）：
   ```json
   {
     "block.beloong.loong_palace_portal.ambient": { "subtitle": "subtitles.beloong.block.loong_palace_portal.ambient",
       "sounds": [ { "name": "beloong:portal/portal", "attenuation_distance": 10 } ] },
     "block.beloong.loong_palace_portal.travel": { "sounds": [ "beloong:portal/travel" ] },
     "block.beloong.loong_palace_portal.trigger": { "subtitle": "subtitles.beloong.block.loong_palace_portal.trigger",
       "sounds": [ "beloong:portal/trigger" ] }
   }
   ```
3. 从 `D:\Minecraft\开源模组参考文件\The-Aether\aether-1.21.1-1.5.10-neoforge.jar` 解出
   `assets/aether/sounds/portal/{portal,trigger,travel}.ogg` → `src/main/resources/assets/beloong/sounds/portal/`。
4. `BeLoongCore` 构造函数里加 `ModSounds.register(modEventBus);`（放在 `ModBlocks.register` 之后）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
# 三个 ogg 都在，且非空
Get-ChildItem src\main\resources\assets\beloong\sounds\portal | Select-Object Name, Length
# sounds.json 合法且三条键名正确
(Get-Content src\main\resources\assets\beloong\sounds.json -Raw | ConvertFrom-Json).PSObject.Properties.Name
```

---

### Task 3: `LoongPalacePortalBlock` —— 门方块

**Files:**
- Create: `src/main/java/com/zonlong/beloong/block/LoongPalacePortalBlock.java`

**Steps:**
1. `public class LoongPalacePortalBlock extends Block implements Portal`，`AXIS = BlockStateProperties.HORIZONTAL_AXIS`；
   构造器属性照抄天境：`.noCollission().strength(-1.0F, 3600000.0F).lightLevel(s -> 11)
   .sound(SoundType.GLASS).pushReaction(PushReaction.BLOCK).forceSolidOn()`
   （天境用 `randomTicks()`，**不抄**——原版下界门也没有），`registerDefaultState(... AXIS, Direction.Axis.X)`。
2. `createBlockStateDefinition` 加 `AXIS`；`getShape` 按轴向返回两个常量碰撞箱
   （X：`Block.box(0,0,6,16,16,10)`；Z：`Block.box(6,0,0,10,16,16)`，与下界门逐字相同）。
3. `entityInside`：
   ```
   客户端 → if (entity.canUsePortal(false)) entity.setAsInsidePortal(this, pos); return;
   服务端 → 仅 ServerPlayer；冷却期（TeleportCooldown.isOnCooldown）→ refreshVanillaToOutlastNbt + return
            骑乘 → stopRiding；setAsInsidePortal
            失败预检（供提示）：维度不允许 / 非龙 → LoongPalacePortalNotifier.notify(...)
   ```
4. `getPortalTransitionTime` → `0`（提为 `public static final int PORTAL_TRANSITION_TICKS = 0`，与天灾门同名同值）。
5. `getLocalTransition` → `Portal.Transition.CONFUSION`。
6. `getPortalDestination`：按设计 §3.3 的三道闸（非玩家 / 非龙 / 维度不允许 / `toTarget` 返回 null），
   目标维度：龙宫 → `Level.OVERWORLD`；否则 → `TeleportTarget.LOONG_PALACE_LEVEL`；
   用 `TeleportTarget.toLoongPalace(lp)` 与 `new TeleportTarget.Spawn(overworld)`；
   `post` 回调 = `fallDistance = 0` + `TeleportCooldown.mark` + 播 travel 音效（`level.playSound(null, pos, ModSounds.LOONG_PALACE_PORTAL_TRAVEL.get(), SoundSource.BLOCKS, 1.0F, 1.0F)`）。
7. `updateShape`：照抄天境逻辑（形状不完整 → `Blocks.AIR.defaultBlockState()`）。
8. `animateTick`（`@OnlyIn(Dist.CLIENT)`）：低频播放 ambient（`random.nextInt(100) == 0`）+ 4 个
   `ParticleTypes.PORTAL` 粒子，位置/速度照抄天境那 20 行（把自定义粒子换成原版粒子）。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
# 门方块不重复实现落点/冷却：只允许出现这些 API 调用
$f='src\main\java\com\zonlong\beloong\block\LoongPalacePortalBlock.java'
Select-String -LiteralPath $f -Pattern 'DimensionTeleport\.|TeleportTarget\.|TeleportCooldown\.|CoordinateLanding\.'
# 无阻塞 API
(Select-String -LiteralPath $f -Pattern 'getChunk\(|managedBlock|\.join\(\)').Count    # 期望 0
```

---

### Task 4: 注水激活 + 失败提示

**Files:**
- Create: `src/main/java/com/zonlong/beloong/block/LoongPalacePortalActivation.java`
- Create: `src/main/java/com/zonlong/beloong/block/LoongPalacePortalNotifier.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（注册激活监听器）

**Steps:**
1. `LoongPalacePortalNotifier`（`final`，静态方法）：
   ```java
   private static final long THROTTLE_TICKS = 20;
   private static final Map<UUID, Long> NEXT_ALLOWED = new HashMap<>();
   public static void notify(ServerPlayer player, String messageKey, Failure reason)   // Failure = enum {NON_DRAGON, WRONG_DIMENSION, TELEPORT_FAILED}
   public static void clear(UUID player)                                               // 登出清理
   ```
   - 节流：`long now = player.level().getGameTime(); if (now < NEXT_ALLOWED.getOrDefault(uuid, 0L)) return; NEXT_ALLOWED.put(uuid, now + THROTTLE_TICKS);`
   - 发送：`player.displayClientMessage(Component.translatable(messageKey), true)`（actionbar）
   - 日志分级：`NON_DRAGON` / `WRONG_DIMENSION` → `LOGGER.debug`；`TELEPORT_FAILED` → `LOGGER.error`
   - `clear` 挂到 `PlayerEvent.PlayerLoggedOutEvent`（写在门方块或激活类里均可，二选一，别重复注册）
2. `LoongPalacePortalActivation`（`@EventBusSubscriber` 或 `NeoForge.EVENT_BUS.register(new ...)`，与项目既有
   `DimensionTransportHandler` 同风格，即 `BeLoongCore` 里 `register(new ...)`）：
   ```java
   @SubscribeEvent public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
       if (!(event.getLevel() instanceof ServerLevel level)) return;
       if (!isAllowedDimension(level)) return;                       // 仅 overworld / loong_palace
       BlockPos pos = event.getPos();
       if (!level.getFluidState(pos).is(Fluids.WATER)) return;        // 任意水（含流动水）
       LoongPalacePortalShape.findEmpty(level, pos, Direction.Axis.X).ifPresent(shape -> {
           shape.createPortalBlocks();
           level.playSound(null, pos, ModSounds.LOONG_PALACE_PORTAL_TRIGGER.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
       });
       // 刻意不 event.setCanceled(true)：避免吞掉原版水/荧石的后续结算
   }
   ```
3. `BeLoongCore` 构造函数加 `NeoForge.EVENT_BUS.register(new LoongPalacePortalActivation());`。

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
# 激活类必须判维度（两层防线的第一层）
Select-String -LiteralPath 'src\main\java\com\zonlong\beloong\block\LoongPalacePortalActivation.java' -Pattern 'isAllowedDimension|LOONG_PALACE_LEVEL|Level.OVERWORLD'
# 不得出现 setCanceled（设计 D4）
(Select-String -LiteralPath 'src\main\java\com\zonlong\beloong\block\LoongPalacePortalActivation.java' -Pattern 'setCanceled').Count   # 期望 0
```

---

### Task 5: 注册、物品、数据包 tag、语言

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/registry/ModBlocks.java`（+1 方块）
- Modify: `src/main/java/com/zonlong/beloong/item/ModItems.java`（+1 BlockItem）
- Modify: `src/main/java/com/zonlong/beloong/item/ModCreativeModeTabs.java`（+1 accept）
- Create: `src/main/resources/data/beloong/tags/block/loong_palace_portal_frame.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json` / `en_us.json`

**Steps:**
1. `ModBlocks`：`public static final DeferredBlock<LoongPalacePortalBlock> LOONG_PALACE_PORTAL =
   BLOCKS.register("loong_palace_portal", LoongPalacePortalBlock::new);`（记得 `register(IEventBus)` 已覆盖 BLOCKS）。
2. `ModItems`：照 `DISASTER_PORTAL_BLOCK` 的写法注册 `LOONG_PALACE_PORTAL`（`BlockItem`）。
3. `ModCreativeModeTabs`：在 `DISASTER_PORTAL_BLOCK` 之后 `output.accept(ModItems.LOONG_PALACE_PORTAL);`。
4. `data/beloong/tags/block/loong_palace_portal_frame.json`：
   `{ "replace": false, "values": [ "minecraft:glowstone" ] }`
5. 语言键（中英各 6 条，**键集合必须一致**）：
   | 键 | 中 | 英 |
   |---|---|---|
   | `block.beloong.loong_palace_portal` | 龙宫传送门 | Loong Palace Portal |
   | `message.beloong.loong_palace_portal.not_a_dragon` | §c只有龙族才能使用龙宫传送门 | §cOnly dragons can use the Loong Palace portal |
   | `message.beloong.loong_palace_portal.wrong_dimension` | §c龙宫传送门只连接主世界与龙宫 | §cThe Loong Palace portal only links the Overworld and the Loong Palace |
   | `message.beloong.loong_palace_portal.teleport_failed` | §c传送失败，请稍后再试 | §cTeleport failed, please try again later |
   | `subtitles.beloong.block.loong_palace_portal.ambient` | 传送门低鸣 | Portal hums |
   | `subtitles.beloong.block.loong_palace_portal.trigger` | 传送门开启 | Portal opens |

**Verification:**
```powershell
.\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"
# 中英键集合一致
$zh=(Get-Content src\main\resources\assets\beloong\lang\zh_cn.json -Raw -Encoding UTF8 | ConvertFrom-Json).PSObject.Properties.Name
$en=(Get-Content src\main\resources\assets\beloong\lang\en_us.json -Raw -Encoding UTF8 | ConvertFrom-Json).PSObject.Properties.Name
"zh=$($zh.Count) en=$($en.Count)"; Compare-Object $zh $en      # 期望空
# tag 合法
Get-Content src\main\resources\data\beloong\tags\block\loong_palace_portal_frame.json -Raw | ConvertFrom-Json
```

---

### Task 6: 门面资产（模型 / blockstate / 贴图）+ 静态验证套

**Files:**
- Create: `src/main/resources/assets/beloong/blockstates/loong_palace_portal.json`
- Create: `src/main/resources/assets/beloong/models/block/loong_palace_portal_ns.json` / `_ew.json`
- Create: `src/main/resources/assets/beloong/models/item/loong_palace_portal.json`
- Create: `src/main/resources/assets/beloong/textures/block/loong_palace_portal.png` / `.png.mcmeta`

**Steps:**
1. 从天境 jar 解出并改写（只改命名空间与路径）：
   - `blockstates/aether_portal.json` → `axis=x → beloong:block/loong_palace_portal_ns`、`axis=z → ..._ew`
   - `models/block/aether_portal_{ns,ew}.json` → **`parent` 仍是 `minecraft:block/nether_portal_{ns,ew}`**，
     `render_type: translucent`，`portal`/`particle` 贴图 → `beloong:block/loong_palace_portal`
   - `textures/block/miscellaneous/aether_portal.png`（**16×512**）+ 其 `.mcmeta`（`{"animation":{"frametime":2}}`）
     → `textures/block/loong_palace_portal.png` / `.png.mcmeta`
   - **物品模型自建**（天境没有门物品）：`{ "parent": "minecraft:item/generated", "textures": { "layer0": "beloong:block/loong_palace_portal" } }`
2. 静态验证套（设计 §7）：
   ```powershell
   .\gradlew.bat build --console=plain -q ; "EXIT=$LASTEXITCODE"        # 期望 0
   # ① 新增类内无阻塞 API
   Get-ChildItem src\main\java\com\zonlong\beloong\block -Filter 'LoongPalace*.java' |
     Select-String -Pattern 'getChunk\(|managedBlock|\.join\(\)'      # 期望仅 javadoc/0
   # ② 门面资产父模型正确
   Select-String -Path src\main\resources\assets\beloong\models\block\loong_palace_portal_*.json -Pattern 'nether_portal'
   # ③ 贴图 16x512
   Add-Type -AssemblyName System.Drawing
   $i=[System.Drawing.Image]::FromFile((Resolve-Path src\main\resources\assets\beloong\textures\block\loong_palace_portal.png)); "$($i.Width)x$($i.Height)"; $i.Dispose()
   # ④ 语言键一致（同 T5）
   # ⑤ 门方块只经 API 落点/冷却（同 T3）
   ```
3. `git status --porcelain` 核对改动清单与预期一致（只应有本轮的 4 个新类 + 3 个注册文件 + 资产 + lang + tag）。

**Verification:** 上述 ①–⑤ 逐条判读；全部满足才算通过。

---

## 实机验收（Task 6 之后，由用户执行；判据见设计文档 §7）

| 编号 | 判据 |
|---|---|
| V1 | 荧石搭框 + 倒水 → 门出现（框内水被替换）；拆任一块荧石 → 整扇门消失 |
| V2 | **非龙玩家**（人形态）进门：不传送 + actionbar「只有龙族…」；变龙后可传送 |
| V3 | 龙玩家：主世界 → 龙宫落在配置固定坐标；龙宫 → 主世界落在出生点附近（扩散） |
| V4 | 下界/末地搭框倒水：**不点亮** |
| V5 | 门面 32 帧动画正常；环境音可闻；点亮与传送各播一次音效 |
| V6 | 连续进出：60 tick 冷却生效；无卡顿、无 `Thread Dump:`、无 timeout 日志 |
| V7 | 创造物品栏可见「龙宫传送门」，放置形态正确 |

实例日志按 **GBK** 解码。

---

## 建议提交点

| 提交 | 内容 | 建议信息 |
|---|---|---|
| ① | Task 1（含维度常量收口） | `refactor(teleport): 龙宫维度常量收口到 TeleportTarget` |
| ② | Task 2–5 | `feat(portal): 龙宫传送门（荧石框架 + 注水激活）` |
| ③ | Task 6 | `assets(portal): 龙宫门面模型与音效资源（取自天境）` |

## Task 依赖与顺序

```
T1 形状(含常量收口) ─┐
T2 音效 ────────────┼─→ T3 门方块 ─→ T4 激活+提示 ─→ T5 注册/物品/tag/lang ─→ T6 资产+静态验证 ─→ 实机
                    │
              （T3 同时依赖 T1/T2；T4 依赖 T1/T2/T3）
```

## 风险与回退

| 风险 | 影响 | 回退 |
|---|---|---|
| `SoundEvent` 注册写法不对（1.21.1 需 `createVariableRangeEvent`） | 编译失败或音效不响 | T2 的 `build` + 实机 V5 立刻暴露 |
| 形状探测抄错（宽高判定/`bottomLeft` 计算） | 门点不亮或点错位置 | 与天境源码逐段对照；实机 V1 判据明确 |
| `isEmpty` 忘了算水 | **注水永远点不亮**（本功能的核心） | T1 步骤 3 明确列出；实机 V1 直接暴露 |
| 门面资产父模型/贴图路径写错 | 门显示为紫黑格或缺失模型 | T6 的 ②③ 检查 + 实机 V5 |
| 未取消邻居事件导致重复点亮 | 无害（`createPortalBlocks` 幂等：已是门方块的格被再设一遍） | 若真有问题，再评估是否 `setCanceled` |

## Non-Goals（与设计文档 §8 一致）

不生成返回门/落地平台、不做落点挤出、不做自定义 HUD 扭曲与专用过渡界面、不给技能加龙检查、
不追溯旧存档、不改既有文档。
