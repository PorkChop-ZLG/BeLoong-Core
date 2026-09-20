# 地狱之门（Hell Gate）设计文档

**日期：** 2026-09-20
**状态：** ✅ 已实现、已实机验收（本文档为落地后的定稿）
**采用方案：** 方案 B —— **完全移植**（不动灾变的类，把封印之门五件套搬进本模组命名空间）
**基线：** `disaster2` @ `def22f1`（版本 `0.9.6`）
**依赖前提：** `cataclysm`（`required`，`[3.26,)`）、`irons_spellbooks`（`required`，钥匙来源）均已存在；
本功能新增 **`lionfishapi`（`required`）** —— 理由见 §六

---

## 一、问题陈述

### 需求（用户原话）

> 在模组 **灾变** 中，有一种特殊的方块，名叫**封印之门**，这扇门需要用物品**怪奇之钥**开启。
> 请你先查询本地的灾变源代码仓库，详细了解封印之门的写法，然后我需要：
> 1. 在当前模组 beloong-core 中**几乎 1:1 复刻**一个封印之门；
> 2. 门的名字叫做**「地狱之门」**，开门的钥匙是铁魔法里的**骸骨钥匙**；
> 3. 其他的内容，比如**物品贴图、方块建模、音效等完全照搬**；
> 4. 如果可以，尝试**直接使用灾变里的现成的类**，从而减少本模组的代码量。

需求澄清阶段由用户裁定的四点：

| 议题 | 用户裁定 |
|---|---|
| 移植方式 | **方案 B：完全移植**（不做 `extends` 继承） |
| 音效 | **注册本模组自己的音效**：`beloong:hell_gate_open`，字幕「地狱之门：敞开」 |
| 开门后的行为 | **完全照搬：只是放行**（不加传送、不加维度逻辑） |
| 注册命名 | `beloong:hell_gate` / 英文名 `Hell Gate` |

### 把设计推向当前形态的六条查证事实

| # | 事实 | 依据 | 对设计的影响 |
|---|---|---|---|
| 1 | **封印之门是「五件套」，全部在灾变内部自洽，没有任何对外扩展点** —— 方块（311 行）、方块实体（144 行）、盒子模型（86 行）、动画定义（80 行）、渲染器（84 行） | `blocks/Door_of_Seal_Block.java`、`blockentities/Door_Of_Seal_BlockEntity.java`、`client/model/block/Door_Of_Seal_Model.java`、`client/animation/Door_Of_Seal_Animation.java`、`client/render/blockentity/Door_Of_Seal_Renderer.java` | ⇒ 只能「整套搬」或「整套继承」，没有「只替换一小块」的接缝 |
| 2 | **灾变的这四个类把注册表引用写死** —— `ModBlocks.DOOR_OF_SEAL`（`setPlacedBy` / `playerWillDestroy` / `BE.tick` 的 `blockstate.is(...)`）、`ModTileentites.DOOR_OF_SEAL`（BE 构造器 / `getTicker`）、`ModItems.STRANGE_KEY`（`useItemOn`）、`ModSounds.DOOR_OF_SEAL_OPEN`（`BE.tick`） | `Door_of_Seal_Block.java:148,242,263`、`Door_Of_Seal_BlockEntity.java:43,102`、`:79`、`ModSounds.java:539` | ⇒ 继承者拿到的是一扇**指向灾变方块/音效/钥匙**的门，不可能「换成我的」 |
| 3 | **蹭灾变的 `BlockEntityType` 在引擎层就被判死** —— `LevelChunk.setBlockState` 对已存在但「类型不接受该状态」的 BE 会 **`removeBlockEntity(pos)` 然后重新 `newBlockEntity`**；`setBlockEntity` 在 `getType().isValid(state)` 为假时**只打一条 warn 就 `return`** | `build/review-src/**/LevelChunk.java:286-294`、`:392-395` | ⇒ 复用灾变的 BE 类型只会得到「门放下去没有实体」的**静默失效**，不是报错。方案 B 不只是「更干净」，是唯一可行 |
| 4 | **客户端渲染器/模型的泛型写死** —— `Door_Of_Seal_Renderer implements BlockEntityRenderer<Door_Of_Seal_BlockEntity>`、`Door_Of_Seal_Model.animate(Door_Of_Seal_BlockEntity, float)` | `Door_Of_Seal_Renderer.java:30`、`Door_Of_Seal_Model.java:72` | ⇒ 渲染侧同样无法借用，只能照抄 |
| 5 | **钥匙判定只是一个 `is(...)`，不消耗** —— `player.getItemInHand(hand).is(ModItems.STRANGE_KEY.get())`；`attemptToRing` 也只置 `LIT`，不动物品栈 | `Door_of_Seal_Block.java:90,113-124` | ⇒ 换钥匙 = 换**一个** `Item` 引用；「不消耗」是要**照搬**的行为 |
| 6 | **灾变的门本身不含任何传送/维度逻辑** —— `OPEN` 只让 `getCollisionShape`/`getBlockSupportShape` 返回 `Shapes.empty()` | `Door_of_Seal_Block.java:165-179` | ⇒ 用户裁定的「只是放行」= 原样照搬，无需增删 |

---

## 二、目标与非目标

### 目标

1. 新增方块 `beloong:hell_gate`（40 格多方块：5 宽 × 8 高），手持 `irons_spellbooks:bone_key`
   右键任意一格即触发开门流程，145 tick 后整扇门碰撞箱清空、放行。
2. 模型几何、动画关键帧、音效文件**逐字节照搬**灾变封印之门；
   贴图先逐字节照搬，**随后按用户追加需求换成下界风格**（换色配方见 **§十一**）。
3. 与灾变封印之门**共存**：两者互不引用、互不干扰（同名注册项一律换成 `beloong`）。
4. 代码量尽量小：能不写的类不写（本功能的「新类」全是照抄件，没有新机制）。

### 非目标

1. 不做传送 —— 「只是放行」（用户裁定）。
2. 不消耗钥匙 —— 照搬原版行为。
3. 不改灾变的任何类、不给灾变打 mixin、不依赖灾变内部实现细节（唯一例外见 §七）。
4. 不注册灾变的 `door_of_seal_part` 那种「只在资产/语言文件里存在的遗留项」。
5. 不新增配置项 —— 本功能无任何可调参数（钥匙、尺寸、时序全部照搬）。

---

## 三、灾变的封印之门：逐项事实（移植的依据）

### 3.1 结构与状态

多方块以**基准格**（`PART=CENTER` 且 `Y_OFFSET=0`）为锚点，沿水平面的**五列** × 竖直**八层**展开，
共 **40 格**。五列的身份由 `FACING` 的顺/逆时针方向决定：

```
        END_LEFT   SIDE_LEFT   CENTER   SIDE_RIGHT   END_RIGHT
        (+2 顺)     (+1 顺)      (0)      (+1 逆)       (+2 逆)
Y_OFFSET 0..7  →  每列 8 格，共 5 × 8 = 40
```

| 状态属性 | 类型 | 取值 | 作用 |
|---|---|---|---|
| `FACING` | `DirectionProperty`（`HorizontalDirectionalBlock.FACING`） | 四水平朝向 | 决定五列的展开方向；也是渲染旋转的依据 |
| `OPEN` | `BooleanProperty` | `true/false` | 全开标志；为 `true` 时碰撞箱与支撑面清空（= 放行） |
| `LIT` | `BooleanProperty` | `true/false` | 已触发；`BE.tick` 只在 `LIT` 时推进计时 |
| `door_part` | `EnumProperty<HellGatePart>` | `side_left` / `side_right` / `end_left` / `end_right` / `center` | 本格在门里的角色（**属性名与序列化名都与灾变一致**） |
| `Y_OFFSET` | `IntegerProperty(0..7)` | 0–7 | 本格相对基准格的层高 |

> 覆盖 `getShape`/`getVisualShape`/`getBlockSupportShape`/`getCollisionShape`/`getOcclusionShape`/
> `isPathfindable`/`rotate`/`mirror`/`getStateForPlacement`/`setPlacedBy`/`playerWillDestroy`，
> 以及 `RenderShape.ENTITYBLOCK_ANIMATED`（方块自身不出模型，几何全交给渲染器）。

### 3.2 开门时序（`HellGateBlockEntity.tick`，逐 tick 与灾变对齐）

| tick | 动作 |
|---|---|
| 0 | `setPlacedBy` 铺满 40 格（全部 `LIT=false`、`OPEN=false`） |
| 任意 | 玩家手持骸骨钥匙右键任意一格 → 该格 `LIT=true` → `blockEvent(id=1)` → `openingAnimationState.start(tickCount)` |
| 1 | `ScreenShake_Entity.ScreenShake(level, Vec3.atCenterOf(pos), 20, 0.05f, 0, 120)` 屏震 |
| 28 | 播 `HELL_GATE_OPEN`（音量 4、音高 1±0.2、`SoundSource.BLOCKS`）+ `level.explode(null, x+0.5, y+1.0, z+0.5, 2.0F, ExplosionInteraction.TRIGGER)`（**不破坏方块**） |
| ≥145 | 基准格与 39 个部件格全部 `${OPEN: true}`（`setBlock` flag 2 + `gameEvent(BLOCK_CHANGE)`）→ 碰撞箱清空 |
| 全开后 | `openingAnimationState.stop()` + `openAnimationState.startIfStopped(tickCount)`（切待机姿） |

`BE` 字段：`animationTicks`（NBT 键就是 `animationTicks`）、`tickCount`，以及两个 `AnimationState`。
灾变原版还留着两个**从未使用**的字段 `animation`、`facing`，本移植一并保留（便于日后逐行对比）。

### 3.3 渲染与动画

- **只在基准格画一次**：渲染器里 `if (PART != CENTER || Y_OFFSET != 0) return;` —— 模型本身含 5×8 格的几何，
  另外 39 格只是状态/碰撞载体。
- 变换：`translate(0.5, 1.501, 0.5)` → `mulPose(dir.getOpposite().getRotation())` → `mulPose(Axis.XP.rotationDegrees(90))`。
- 贴图 `256×256`，`RenderType.entityCutoutNoCull`；`shouldRenderOffScreen()=true`、`getViewDistance()=256`、
  `getRenderBoundingBox` = 3×8×3（**8 格高的门不这么做会被视锥剔除得很突兀**）。
- 模型部件：`roots`（y=24 贴地）/ `left_door` 与 `right_door`（各 40×128×16，旋转点在 x=±40 即门轴）/
  `lock`（y=-24、z=-9）+ 子盒 `cube_r1`（32×32×2，绕 Z 旋转 `0.7854` rad = 45°，贴图偏移 `(0,144)`）。
- 动画：`OPEN` 长度 **7.25 s（= 145 tick，与 `TICK_FULLY_OPEN` 对齐）**；`OPEN_IDLE` 长度 0 且 `looping()`。
  双扇门 2.5→5.25 s 由 ±5° 转到 ±90°、6.0 s 收在 ±95°；锁在 0→1.5 s 抖动、1.5833 s 缩放归零（消失）。
  插值除锁的缩放是 `LINEAR` 外全是 `CATMULLROM`。

### 3.4 资产清单（全部照搬）

| 灾变 | 本模组 | 说明 |
|---|---|---|
| `blockstates/door_of_seal.json` | `blockstates/hell_gate.json` | 单 `""` variant |
| `models/block/door_of_seal.json` | `models/block/hell_gate.json` | `render_type: translucent` + `particle` 指向 **`cataclysm:block/dungeon_block`**（灾变是 required，直接引用其贴图） |
| `models/item/door_of_seal.json` | `models/item/hell_gate.json` | `item/generated` + `layer0` |
| `textures/block/door_of_seal.png` (15680 B) | `textures/block/hell_gate.png` | 先**逐字节复制**，后按下界风格换色（§十一）⇒ 现为 14260 B |
| `textures/item/door_of_seal.png` (277 B) | `textures/item/hell_gate.png` | 同上 ⇒ 现为 317 B |
| `sounds/block/door_of_seal_open.ogg` (114357 B) | `sounds/block/hell_gate_open.ogg` | **逐字节复制**；`sounds.json` 键按本模组规则改为 `block.beloong.hell_gate.open` |
| `lang`：`block.cataclysm.door_of_seal` = 封印之门 / Door of Seal；`door_of_seal_open.sub` = 封印之门：敞开 / Door of Seal opens | `block.beloong.hell_gate` = 地狱之门 / Hell Gate；`subtitles.beloong.block.hell_gate.open` = 地狱之门：敞开 / Hell Gate opens | 字幕沿用灾变的措辞结构 |

---

## 四、移植映射

| 灾变 | 本模组 | 移植方式 |
|---|---|---|
| `Door_of_Seal_Block` | `block/HellGateBlock` | 逐行照抄 + 注册项替换 |
| `Door_Of_Seal_BlockEntity` | `block/HellGateBlockEntity` | 逐行照抄（含未用字段） |
| `Door_Of_Seal_Part`（枚举） | `HellGateBlock.HellGatePart` | 改名，序列化名不变 |
| `Door_Of_Seal_Model` | `client/model/HellGateModel` | 几何数值一字不改 |
| `Door_Of_Seal_Animation` | `client/animation/HellGateAnimation` | 关键帧一字不改 |
| `Door_Of_Seal_Renderer` | `client/HellGateRenderer` | 照抄 + 收敛 4 个等价分支（§七） |
| `ModBlocks.DOOR_OF_SEAL` | `ModBlocks.HELL_GATE` | 属性逐条照搬 |
| `ModTileentites.DOOR_OF_SEAL` | `ModBlocks.HELL_GATE_BLOCK_ENTITY` | 注册 id `beloong:hell_gate_block_entity` |
| `ModItems.DOOR_OF_SEAL`（`BlockItem`） | `ModItems.HELL_GATE` | `fireResistant()` + `Rarity.EPIC` 照搬 |
| `ModItems.STRANGE_KEY`（`cataclysm:strange_key` = 怪奇之钥） | `irons_spellbooks:bone_key`（骸骨钥匙，`ItemRegistry.BONE_KEY`） | **需求 2 的唯一实质改动** |
| `ModSounds.DOOR_OF_SEAL_OPEN`（`cataclysm:door_of_seal_open`） | `ModSounds.HELL_GATE_OPEN`（`beloong:block.beloong.hell_gate.open`） | 需求裁定：注册自己的音效 |
| `ScreenShake_Entity.ScreenShake(...)` | **原样调用灾变的静态方法** | 唯一保留的灾变代码引用（§七） |
| 创造模式获取途径 | 加入本模组「化龙」标签页 | 灾变把门放在自己标签页；本模组照做 |

**命名空间替换规则**：注册项 → `beloong`；包 → `com.zonlong.beloong.*`；
类名 → `HellGate*`；素材路径 → `beloong:textures/...`、`beloong:block/...`；
**其余（属性名、序列化名、NBT 键、数值、时序、关键帧、字节内容）一律不动**。

---

## 五、决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | 走**方案 B 完全移植**，不做 `extends Door_of_Seal_Block` | §一 事实 2/3：写死的注册项 + `LevelChunk` 的类型校验使继承必然静默失效 |
| D2 | 保留 `door_part` 这个**属性名**（不改成 `hell_gate_part`） | 与灾变逐行对比时可 1:1 对照；属性名不进入玩家可见界面 |
| D3 | 五件套全部搬进本命名空间，只在**边缘处**换注册项 | 让「移植件」保持可审计：任何一处改动都能在 diff 里被看见 |
| D4 | 方块属性照搬：`MapColor.METAL` + `noOcclusion` + `dynamicShape` + `strength(-1.0F, 3600000.0F)` + `noLootTable` + `requiresCorrectToolForDrops` + `SoundType.METAL` | 「不可破坏 + 无掉落」是封印之门的设计意图，需求 3 要求照搬 |
| D5 | 物品 `fireResistant()` + `Rarity.EPIC` | 同上，灾变如此 |
| D6 | 钥匙用 `ItemRegistry.BONE_KEY`（铁魔法），**不消耗** | 需求 2 + 事实 5；铁魔法是 required，无需 `isLoaded` 守卫 |
| D7 | 音效**自建**：`beloong:block.beloong.hell_gate.open`，字幕「地狱之门：敞开」；ogg 逐字节复制 | 用户裁定；沿用本模组既有的 `<类型>.<命名空间>.<方块>.<用途>` 键名规则（与 `block.beloong.loong_palace_portal.*` 同构） |
| D8 | `blockstates/hell_gate.json` 与 `models/block/hell_gate.json` 照搬（含指向 `cataclysm:block/dungeon_block` 的 `particle`） | 灾变 required ⇒ 可以引用其贴图；自造一个粒子贴图属于无谓偏离 |
| D9 | 新增 `lionfishapi` 为 `required` 前置 | 本功能的模型/动画基类 `AdvancedEntityModel` 来自它；**灾变的 mods.toml 里并没写这个依赖**（它只声明 `curios`），但灾变 3.26 实际依赖它。缺了它宁可给出清晰的加载错误，也不要运行时 `NoClassDefFoundError` |
| D10 | `compileOnly` 与 `localRuntime` 锁**同一个** LionfishAPI 版本号（`8094835`） | 避免「按 3.1 编译、按 3.0-beta 运行」的偏差 |
| D11 | `mods.toml` 里版本下界写 `[3.0-beta,)` 而非 `[3.0,)` | LionfishAPI 当前发布版就叫 `3.0-beta`，而 `3.0-beta < 3.0`，写 `[3.0,)` 会把编译时的那个版本自己挡在门外 |
| D12 | 复刻**不带传送**，也不加任何「地狱」相关玩法 | 用户裁定「只是放行」；照搬即完成 |
| D13 | 数值常量提取为具名常量（`GATE_HEIGHT`/`TICK_*`），但不改任何取值 | 可读性收益，零行为差异 |
| D14 | 实机验证用**数据包探针**而非手点（见实施计划 §〇之二） | 本项目无测试套件；数据包可以在无人操作的情况下覆盖「40 格状态装配 + 通 LIT + 145 tick 后全开」这条主链 |
| D15 | **贴图追加需求：换成下界风格**（2026-09-20 二轮） | 用户要求：蓝宝石锁→红宝石、石砖主体→下界砖、白雪装饰→血红、**灰色石框不变**；先做视觉核对再动手（§十一） |
| D16 | 换色方法：**同序调色板替换**，目标色一律取自原版材质的真实调色板 | 石砖 7 色 → `nether_bricks` 7 色；雪 4 色 → `nether_wart_block` 上 4 档；蓝宝石 7 色 → 深红→亮红 7 阶；灰框 6 色与宝石高光 `#FFFFFF` 不动。按明度同序对应 ⇒ 原图所有明暗层次保留（§十一） |

---

## 六、需求 4「直接复用灾变现成的类」的答复：**经查证不可能**

这是需求里唯一没能照办的一条，三条硬证据：

1. **写死的注册项**（事实 2）：`Door_of_Seal_Block.setPlacedBy` 用 `ModBlocks.DOOR_OF_SEAL.get()` 铺格、
   `playerWillDestroy` 用 `ModBlocks.DOOR_OF_SEAL.get()` 判归属、`BE.tick` 用 `blockstate.is(ModBlocks.DOOR_OF_SEAL.get())`
   把 40 格一起置 `OPEN`；`getTicker` 绑 `ModTileentites.DOOR_OF_SEAL`；BE 构造器把类型写死为灾变的注册项；
   `useItemOn` 只认 `ModItems.STRANGE_KEY`；`BE.tick` 播的是 `ModSounds.DOOR_OF_SEAL_OPEN`。
   ⇒ 继承者要么改父类（不可能），要么整类覆盖（那就等于重写，且 `extends` 只剩负担）。
2. **蹭 `BlockEntityType` 在引擎层被判死**（事实 3）：`LevelChunk.setBlockState`（`:286-294`）发现
   「已有 BE 但不接受新状态」时会 `removeBlockEntity(pos)` 再 `newBlockEntity`；
   `setBlockEntity`（`:392-395`）在 `getType().isValid(state)` 为假时**只打一条 warn 就 return**。
   灾变的类型只接受 `cataclysm:door_of_seal`，所以我们的格子永远拿不到一个有效 BE
   —— 结果不是崩溃，而是**门放下去没有实体、动画与 145 tick 逻辑全部不走**的静默失效。
3. **客户端同样是写死的泛型**（事实 4）：`Door_Of_Seal_Renderer implements BlockEntityRenderer<Door_Of_Seal_BlockEntity>`，
   `Door_Of_Seal_Model.animate(Door_Of_Seal_BlockEntity, float)`。渲染侧借不过来。

**仍然复用的灾变内容**（不是「完全不用」）：

| 复用什么 | 形式 | 备注 |
|---|---|---|
| 动画关键帧 | **复制**进本命名空间 | 灾变是 `required`，但复制比跨模组引用更抗震 |
| 贴图 / 音效 / 粒子贴图 | 先**逐字节复制**，贴图后按下界风格换色（§十一）；`particle` 直接引用 `cataclysm:block/dungeon_block` | 需求 3 + 追加需求 |
| `ScreenShake_Entity.ScreenShake(Level, Vec3, float, float, int, int)` | **直接调用灾变代码** | 全功能唯一的灾变 API 调用；它内部自带 `if (!world.isClientSide)` 守卫，双端调用都安全（`:114-115`） |

---

## 七、与灾变的刻意偏离（全部有据、全部已在代码 javadoc 里标注）

| # | 偏离 | 灾变原样 | 本模组 | 理由 |
|---|---|---|---|---|
| 1 | 第 28 tick 的**音效**加了 `!level.isClientSide` 守卫 | `level.playSound(...)` 没有守卫，而 BE 双端都会 tick ⇒ 客户端本地播一次、再收服务端广播 ⇒ **同一音效听两遍** | 只在服务端播（客户端由广播收到一次） | 听觉上是纯 bug 修复；若日后要与灾变**逐字节**一致，删掉那个判断即可 |
| 2 | `getAnimationState(String)` 用 `.equals` | 用 `==` 比较字符串引用（靠调用方传字面量、字面量驻留才侥幸成立） | `.equals` | 调用方传字面量时行为完全相同；换成非常量调用时也不会静默返回空 `AnimationState` |
| 3 | 渲染器的**四个 `translate` 分支合并为一句** | `if (NORTH) translate(0.5,1.501,0.5) else if (EAST) translate(0.5F,1.501F,0.5F) ...`（四支字面量完全相同） | `translate(0.5D, 1.501F, 0.5D)` | 四支等价，合并后朝向差异完全由随后的 `mulPose(dir.getOpposite().getRotation())` 承担；行为等价 |

除以上三处，**没有任何其他行为改动**：属性名、序列化名、NBT 键、40 格坐标算法、时序、
爆炸参数、屏震参数、动画关键帧、贴图与音频字节，全部一致。

---

## 八、验收策略

### A. 构建与静态探针（已执行，全部通过）

| 探针 | 命令 / 方式 | 结果 |
|---|---|---|
| 编译与打包 | `.\gradlew.bat compileJava jar` | BUILD SUCCESSFUL（3 条既有 mixin 警告与本次无关） |
| 产物齐全 | zip 内逐项检查 5 个 `.class` + 6 个资产 + `lang`/`sounds.json`/`mods.toml` | 15/15 命中 |
| 资产字节一致 | 比对 jar 内长度 | `hell_gate_open.ogg` 114357 —— **与灾变原件完全相同**；两张贴图已按下界风格换色（§十一），故**不再**与原件相同 |
| 前置声明 | jar 内 `META-INF/neoforge.mods.toml` | 含 `modId="lionfishapi"` + `type="required"` + `versionRange="[3.0-beta,)"` |
| 继承关系 | `javap` | `HellGateBlock extends BaseEntityBlock`、`HellGateModel extends AdvancedEntityModel<Entity>`、`HellGateRenderer implements BlockEntityRenderer<HellGateBlockEntity>` |
| 接线 | `javap -c/-v` 常量池 | `ModBlocks.HELL_GATE_BLOCK_ENTITY`、`ModSounds.HELL_GATE_OPEN`、`ItemRegistry.BONE_KEY`、`ScreenShake_Entity.ScreenShake`、客户端 `BootstrapMethods` 里的 `HellGateRenderer::<init>` |

### B. 数据包实机探针（已执行，全部通过）

在一个**副本存档**里放一个数据包，世界加载时自动：铺 40 格（`lit=false`）→ 逐格断言状态 → 通 `LIT` →
145 tick 后逐格断言 `open=true`。结果（`run/logs/latest.log`）：

```
23:49:13 [Server] [HELLGATE-PROBE] init-start
23:49:20 [Server] [HELLGATE-PROBE] PASS-closed-40     ← 40 格全部 facing/part/y_offset 精确命中
23:49:22 [Server] [HELLGATE-PROBE] light
23:49:37 [Server] [HELLGATE-PROBE] PASS-open-40       ← 全门 OPEN（放行）
23:49:41 [Server] [HELLGATE-PROBE] done
```

这组断言证明了：注册与状态定义有效、**自定义 BE 类型被引擎接受**（全程无 `Block entity type mismatch`
告警）、`getTicker` 接通、`LIT → 145 tick → 40 格 OPEN` 全链路真机跑通、屏震/爆炸/音效调用无异常。
同一次运行中客户端资源加载**没有一条**属于 `beloong` 的模型/贴图/音效告警，`run/crash-reports` 无新文件。

### C. 用户视觉验收（已通过）

用户自行进游戏做了视觉确认（渲染效果、贴图、门的观感）。

### D. 尚未被机器验证的部分（诚实记录）

| 项 | 为什么没验 | 怎么补 |
|---|---|---|
| `setPlacedBy`（放下物品铺满 5×8） | `/setblock` **不会**触发它（1.21.1 里只有 `BlockItem` 调 `setPlacedBy`，见 `BlockItem.java:82`） | 需真人手持物品放置（已由用户视觉验收覆盖） |
| `useItemOn`（骸骨钥匙右键）+ `blockEvent(1)` 启动动画 | 同上，需真人交互 | 真人右键一次即可 |
| `playerWillDestroy`（创造模式破坏整扇门） | 同上 | 真人破坏一次即可 |
| 渲染器是否被真正调用（而非「没崩」） | 无自动化断言手段 | 截图 + 用户视觉验收（已通过） |

---

## 九、证据索引（源码级）

**灾变（只读参考）**：`D:\Minecraft\开源模组参考文件\Cataclysm\src\main\`
- `java/com/github/L_Ender/cataclysm/blocks/Door_of_Seal_Block.java`（311 行）
- `java/com/github/L_Ender/cataclysm/blockentities/Door_Of_Seal_BlockEntity.java`（144 行）
- `java/com/github/L_Ender/cataclysm/client/model/block/Door_Of_Seal_Model.java`（86 行）
- `java/com/github/L_Ender/cataclysm/client/animation/Door_Of_Seal_Animation.java`（80 行）
- `java/com/github/L_Ender/cataclysm/client/render/blockentity/Door_Of_Seal_Renderer.java`（84 行）
- `java/com/github/L_Ender/cataclysm/init/ModBlocks.java:269-277`、`init/ModTileentites.java:49-50`、
  `init/ModItems.java:681-682,1051-1052`、`init/ModSounds.java:539`
- `java/com/github/L_Ender/cataclysm/entity/effect/ScreenShake_Entity.java:114-115`
- `resources/assets/cataclysm/{blockstates,models,textures,sounds.json,lang}`

**引擎（保留源码树）**：`build/review-src/neoforge-21.1.236/`
- `net/minecraft/world/level/chunk/LevelChunk.java:286-294`（BE 类型不符 → 移除重建）、`:392-395`（warn + return）
- `net/minecraft/world/item/BlockItem.java:82`（`setPlacedBy` 的唯一调用点）
- `net/minecraft/core/dispenser/DispenseItemBehavior.java`（确认**没有**通用的 BlockItem 发射行为 ⇒ 发射器无法用来测 `setPlacedBy`）

**本模组（新增/改动）**：见实施计划 §五 的文件清单。

---

## 十、已知行为与明确不做的事

### 已知行为（照搬自灾变，不是缺陷）

1. 钥匙**不消耗**，同一把钥匙可以反复开同一扇已经开了的门（`attemptToRing` 里 `!state.getValue(LIT)` 拦住了重复触发）。
2. 门**不可破坏**（`strength(-1.0F, 3600000.0F)`），创造模式破坏任意一格会把整扇门一起拆掉（`playerWillDestroy`）。
3. 第 28 tick 的爆炸是 `ExplosionInteraction.TRIGGER` —— **不破坏方块、不伤人**，只是视听效果。
4. 门在 5×8 范围内要求**全部可替换**才允许放置（`doesGateFitInDirection`），否则 `getStateForPlacement` 返回 `null`（放不下去）。
5. 无掉落物（`noLootTable()`）⇒ 唯一获取途径是创造模式或命令；本模组把它放进了「化龙」标签页。
6. 门开完只切待机动画，**不会自动关闭**，也没有关门的途径（灾变亦如此）。

### 明确不做

1. 不加传送/维度/传送门逻辑（用户裁定）。
2. 不做「地狱之门 Part」那种只在资产/语言文件里存在的遗留注册项。
3. 不给灾变打 mixin、不反射灾变内部字段。
4. 不新增配置项。
5. 不把灾变的 `DOOR_OF_SEAL` 从本模组的依赖链里去掉（灾变仍是 required 前置）。

---

## 十一、追加需求：贴图换成下界风格（2026-09-20 追加，已实施）

### 用户原话

> 1. 原版的封印之门，中间的锁是蓝宝石，门的主题材质是石砖，并且带有白色的雪作为装饰。请你通过你的视觉能力确认。
> 2. 本模组的地狱之门，应该具有下界风格。请你把锁的蓝宝石画成红宝石，换色就行。
>    然后把门主体的石砖换成下界砖，把白色的雪换成红色的血。框架的灰色石头不用改。

### 11.1 视觉核对结论（先核对，再动手）

原图 256×256，**只有 25 个颜色、无抗锯齿**，因此可以逐色分类。分组与用户的描述逐一对应：

| 分组 | 色数 | 颜色（源图调色板） | 在贴图里的位置 | 与用户描述对照 |
|---|---|---|---|---|
| 石砖主体 | 7 | `#5A595A #636363 #6A6D6A #787678 #7F7F7F #8B898B #9C999C` | 门板正/背面上的顺砖纹 | ✅「门的主题材质是石砖」 |
| 白雪挂饰 | 4 | `#9DA6A7 #BBD3D3 #D4DDDE #E5E4E5` | 挂在砖沿下方的白色挂饰 | ✅「带有白色的雪作为装饰」 |
| 蓝宝石锁 | 7 | `#2C489D #385EB3 #446CCA #5185E0 #74ABFE #8EB8FE #B8D2FF` | 锁盒 `cube_r1`（贴图偏移 `(0,144)`）的两个 32×32 面 | ✅「中间的锁是蓝宝石」 |
| 宝石高光 | 1 | `#FFFFFF`（38 px） | 只在宝石内部当闪点 | —— |
| 灰色石框 / 砖缝 | 6 | `#1F232C #252933 #2D313D #343947 #3E4453 #495065` | 门板侧/顶/底面（门框）与砖缝 | ✅「框架的灰色石头」 |

**关键取证**：把「石砖主体」那 7 个颜色与原版材质逐一比对 ——
**与原版 `minecraft:block/stone_bricks` 的 7 色调色板逐色完全相同、明度序也相同**：

| 原版 `stone_bricks` | 门贴图里的对应色 |
|---|---|
| `#5A595A` `#636363` `#6A6D6A` `#787678` `#7F7F7F` `#8B898B` `#9C999C` | 同左（7/7 命中） |

⇒ 灾变是直接把原版石砖调色板搬进了这张贴图。**这既是「石砖」描述的铁证，也让换色可以做到最干净**：
不需要逐像素画，只要把这 7 色换成「下界砖」的 7 色即可。

### 11.2 换色配方（同序调色板替换）

方法是**按明度同序一一对应**（两组各自按人眼明度升序排列后对齐），因此原图的明暗层次 ——
砖面高光、砖缝阴影、雪的亮面/暗面、宝石的刻面 —— **全部原样保留**，只换材质色。

| 分组 | 目标色 | 取色依据 |
|---|---|---|
| 石砖 → 下界砖 | `#190D10 #211114 #291519 #30181C #38181E #3E1E24 #44242A` | 原版 `minecraft:block/nether_bricks` 的 7 色调色板（明度升序，与原石砖 7 色同序对齐） |
| 雪 → 血 | `#6A0400 #7B0000 #941818 #AC2020` | 原版 `minecraft:block/nether_wart_block` 5 色中的上 4 档（血感；且比下界砖亮 ⇒ 看得出「滴下来」） |
| 蓝宝石 → 红宝石 | `#5E0A08 #730C00 #941400 #A41808 #BD2008 #D42A10 #E62008` | 深红→亮红 7 阶，与蓝宝石 7 色同明度序（亮面留红、不发粉） |
| 宝石高光 | 不变 `#FFFFFF` | 只有 38 px，全在宝石内部，作为宝石闪点保留 |
| 灰色石框 / 砖缝 | **不变** | 用户要求 |

实施工具：`tools/recolor_hell_gate_texture.py`（`tools/` 已被 `.gitignore` 忽略，属本机工具；
脚本同名输出会先备份到 `preview/.backup-before-convert/`）。物品贴图（16×16）用**同一套配方**处理：
它只有 3 个深色 + 4 个石砖灰、**没有**蓝宝石与雪，因此只发生「石砖→下界砖」一种替换。

### 11.3 结果与校验

| 项 | 结果 |
|---|---|
| 尺寸 / 透明通道 | 256×256 与 16×16 均不变；**alpha 通道逐像素完全相同**（包括透明区） |
| 不透明像素数 | 方块 17920 → 17920；物品 160 → 160（**一个不多一个不少**） |
| 实际改色的像素 | 方块 **7776 px**（6284 砖 + 394 雪 + 1098 宝石）；物品 **62 px**（砖） |
| 调色板规模 | 25 → 25、7 → 7（仍是无抗锯齿的纯色图） |
| 未匹配色 | **0**（源图调色板与预期完全一致；脚本对未匹配色会原样输出并告警） |
| 文件大小 | 方块 15680 → 14260 B；物品 277 → 317 B；音效仍 114357 B（**未动**） |
| jar 一致性 | `gradlew jar` 后 jar 内两张贴图与工作区文件 SHA-256 相同 |

### 11.4 已知观感影响（据实记录，不是缺陷）

下界砖（明度 17–46）比原版石砖（89–154）**暗得多**，换完之后：

- 门板：砖面比灰框更暗（原图是反过来的）。但门板本身仍有 7 阶砖缝层次，且**色相分离**
  （红棕 vs 蓝灰）承担了主要辨识，加上鲜红的血痕压在砖沿上，整体读起来仍是「下界砖 + 血」✅；
- 物品图标（16×16）：只有 4 阶砖色，少了砖缝细节 ⇒ **比原来闷一些**，但形状与「深红砖 + 深灰框」
  的关系仍可辨。
- 若日后想更亮/更红：把配方里的「下界砖」7 色换成原版 `red_nether_bricks` 的调色板
  （`#2E0001 … #73171A`）即可，脚本里是一行常量。
- 中央那块「同心方板」用的是**与石砖完全相同的 7 个灰色**，纯调色板替换**无法**把它单独留下 ⇒
  它随砖面一起变成了下界砖色。这与「门主体是下界砖」一致；若要求它保持灰石，只能改为按区域遮罩处理。

### 11.5 追加需求的验收

| # | 用例 | 结果 |
|---|---|---|
| 1 | 视觉核对四类材质（用户要求先确认） | ✅ 见表；石砖一项有「与原版 stone_bricks 逐色相同」的硬证据 |
| 2 | 蓝宝石 → 红宝石 | ✅ 7 色同序替换，宝石闪点保留 |
| 3 | 石砖 → 下界砖 | ✅ 用原版 `nether_bricks` 真实调色板 |
| 4 | 白雪 → 血红 | ✅ 4 色同序替换，亮度高于砖面 |
| 5 | 灰色框架不变 | ✅ 6 色原样（对比图里可见蓝灰框完全没变） |
| 6 | 布局 / 透明区不被破坏 | ✅ alpha 逐像素相同、不透明像素数不变 |
| 7 | 对照图人眼确认 | ✅ `preview/cmp_block_4x.png`、`preview/cmp_lock_7x.png`、`preview/cmp_item_16x.png`（左原版 / 右新版） |

