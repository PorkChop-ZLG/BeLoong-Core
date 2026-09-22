# 地黄龙 NPC（本模组第一个生物 NPC）设计文档

**日期：** 2026-09-21
**状态：** 待批准
**分支：** `NPC`
**采用方案：** `PathfinderMob` + GeckoLib 4.9.2（`GeoEntity`），**无 AI、无敌、站在地上、不缩放**；零 mixin、零网络包、零新依赖
**参考实现：** BWG `PumpkinWarden`（同平台 1.21.1 + NeoForge + 同版 GeckoLib，本地有源码）+ Dragon Survival `DragonEntity`/`DragonRenderer`/`DragonModel`（本模组**必需依赖**）
**资产：** `D:\Minecraft\Models\Geckolib\dihuang_loong_npc\`（用户提供，**完整移植**）

---

## 一、问题陈述

本模组此前**没有任何生物 NPC**（唯一的自定义实体 `beloong:tornado` 用的是原版手写模型，与 GeckoLib 无关）。
现在要制作第一个：**地黄龙 NPC**，使用用户提供的 GeckoLib 模型三件套。

**用户已裁定的六条**（2026-09-21）：

| # | 裁定 |
|---|---|
| 1 | **站在地上**（不给无重力） |
| 2 | 碰撞箱 **1.5 × 2.5**（我建议的起始值） |
| 3 | **不做缩放**，按模型默认尺寸渲染 |
| 4 | **完整移植**三个资产文件，动画文件整个放上去（后续按需改） |
| 5 | **不要刷怪蛋、不要自然生成** |
| 6 | **先不接**对话系统 |

## 二、目标与非目标

### 目标

1. 新增实体 `beloong:dihuang_loong`（显示名「地黄龙」），可用 `/summon` 生成。
2. 用 GeckoLib 渲染用户提供的模型（geo + 贴图 + 完整动画文件）。
3. **无 AI**：不接受任何寻路/目标/移动驱动。
4. **无敌**：绝大多数伤害无效。
5. 站在地上、不消失。
6. 三个资产文件**原样**放入模组资源，不改动内容。

### 非目标

- ❌ 不做刷怪蛋、不做自然生成/刷怪规则（裁定 5）
- ❌ 不接 NPC 对话系统（裁定 6）
- ❌ 不做缩放、不做多形态/多贴图切换
- ❌ 不做 AI（寻路、目标、攻击、繁殖）
- ❌ 不做服务端逻辑与网络包；**不改任何 mixin**
- ❌ v1 不实现"头部跟随"（见 §7.3 的 Molang 说明）
- ❌ 不追求动画与模型的 100% 契合（原动画文件来自更大的模型，有已知骨骼缺口，见 §3.3）

## 三、资产与量测（事实口径）

### 3.1 三个资产文件

| 文件 | 内容 | 落点 |
|---|---|---|
| `dihuang_loong.geo.json` | 格式 `1.12.0`；**145 骨骼 / 340 立方体**；根骨骼 2 根（`Magic`、`Dragon`）；贴图 256×256；`visible_bounds` 12×5；`identifier = geometry.unknown` | `assets/beloong/geo/dihuang_loong.geo.json` |
| `dihuang_loong.animation.json` | 格式 `1.8.0`；**约 100 个动画**，含两套（`idle`… 与 `idle2`…） | `assets/beloong/animations/dihuang_loong.animation.json` |
| `dihuang_loong.png` | 256×256 贴图 | `assets/beloong/textures/entity/dihuang_loong.png` |

### 3.2 模型尺寸（用于定碰撞箱）

朴素包围盒（累加立方体 `origin`/`size`，**未计骨骼旋转**，仅作量级参考）：约 **2.0 宽 × 2.85 高 × 9.0 长**（方块）。
最低点 `y = -14.04` 单位（≈ -0.88 格）——说明**模型原点不在脚底**，实机可能需要微调 y 偏移。
形态符合"长条龙"（`visible_bounds` 声明 12 宽也印证了这一点）。

### 3.3 动画与模型的匹配度（逐动画核对）

| 动画 | 引用骨骼 | 缺失骨骼 |
|---|---|---|
| `idle` / `walk` / `fly` / `animation` | 32 / 63 / 65 / 45 | **0 ✓** |
| `run` | 77 | 3（`Drip1-3`） |
| `sit` | 62 | 5（`WhiskerLeft/Right`、`Drip1-3`） |
| `jump` | 104 | **31**（`Mustache*`、`Whisker*`…） |
| `idle2` / `walk2` / `animation2` | 1 / 1 / 1 | 0（但只动 1 根骨骼，基本是废动作） |

**缺失骨骼不会崩**：GeckoLib 的原文注释是"By default, GeckoLib will just gracefully ignore a missing bone"
（`GeoModel#shouldCrashOnMissingBone()`，默认 `false`，见 `GeoModel.java:86-90`）。⇒ 风险只有观感，没有崩溃。

**谱系**：该动画文件源自「龙之生存」的自定义龙种（`DiHuang-Loong` 项目里存在 `assets/dragonsurvival/geo/dihuang_loong.geo.json`，134 骨骼、`visible_bounds` 31×9），所以混入了那套模型的骨骼。这也是为什么 `jump` 缺 31 根。

## 四、架构

### 4.1 变更清单

```
新增  src/main/java/com/zonlong/beloong/entity/DihuangLoongEntity.java
      实体：PathfinderMob + GeoEntity；无 AI、无敌、不消失

新增  src/main/java/com/zonlong/beloong/client/model/DihuangLoongModel.java
      GeoModel 子类：三个资源路径

新增  src/main/java/com/zonlong/beloong/client/DihuangLoongRenderer.java
      GeoEntityRenderer 子类

改动  src/main/java/com/zonlong/beloong/registry/ModEntities.java
      +1 个 EntityType（MobCategory.MISC / sized(1.5F, 2.5F) / fireImmune）

改动  src/main/java/com/zonlong/beloong/BeLoongCoreClient.java
      +1 行注册渲染器（既有 registerRenderers 内）

改动  src/main/java/com/zonlong/beloong/registry/ModAttributes.java
      +1 个 EntityAttributeCreationEvent 处理方法（**双端类** —— 属性是服务端权威的，
      放客户端类会导致专用服务器上的实体没有属性表；见 §4.3 实现期修订 R7）

新增  src/main/resources/assets/beloong/geo/dihuang_loong.geo.json
新增  src/main/resources/assets/beloong/animations/dihuang_loong.animation.json
新增  src/main/resources/assets/beloong/textures/entity/dihuang_loong.png
改动  src/main/resources/assets/beloong/lang/{zh_cn,en_us}.json
      +1 个键 entity.beloong.dihuang_loong（按字母序插入）
```

**不碰** `beloong.mixins.json`、`build.gradle`（GeckoLib 已是必需依赖，`build.gradle:139`）。

### 4.2 决策记录

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D1** | 实体基类 | **`PathfinderMob`** | 与 BWG `PumpkinWarden` 同款。**不用 `LivingEntity`**：DS 的 `DragonEntity extends LivingEntity`（`:63`）是因为它是"玩家的龙形态"、天然没有 AI 概念，而不是"为了实现无 AI 才这么选"；`LivingEntity` 是抽象类，还要自己实现 `getArmorSlots`/`getItemBySlot`/`setItemSlot`/`getMainArm` 四个样板方法 |
| **D2** | 无 AI | **不写 `registerGoals()`** + **覆写 `isNoAi()` 恒为 true**（构造里另设一次） | `Mob#isEffectiveAi()` 返回 `super.isEffectiveAi() && !isNoAi()`（`Mob.java:1362`）⇒ goal 选择器完全不跑。**用覆写而非仅 `setNoAi`**：该标志会被 `/summon` 的 `load()` 覆盖（修订 R8） |
| **D3** | 无敌 | **覆写 `isInvulnerableTo()`**：只放行 `BYPASSES_INVULNERABILITY`；构造里另设 `setInvulnerable(true)` | `LivingEntity#hurt` 的**第一道闸门**就是 `isInvulnerableTo`（`LivingEntity.java:1084`）。放行的只有 `out_of_world` 与 `generic_kill` ⇒ `/kill` 与虚空仍可移除（刻意留的管理后路）。**不依赖 `invulnerable` 字段**的理由见修订 R8；参考实现：传奇怪物的休眠怪覆写 `hurt()` 按状态判定（`Frostbitten_GolemEntity:528-532`） |
| **D4** | 永不消失 | **`MobCategory.MISC`** + **覆写 `isPersistenceRequired()` 恒为 true** | `Mob#checkDespawn`（`Mob.java:703-716`）只看 `persistenceRequired` / `requiresCustomPersistence()`，**不看** `MobCategory.isPersistent()` ⇒ 真正的闸门是前者（初版写成"MISC 本身 persistent 即可"是不准确的，见修订 R8）。MISC 的价值在 `max=-1`：不占刷怪上限 |
| **D5** | 站在地上 | **不调用 `setNoGravity`**，让重力正常作用 | 用户裁定 1 |
| **D6** | 碰撞箱 | `.sized(1.5F, 2.5F)` | 用户裁定 2；长条龙（约 9 格长）无法用轴对齐方块贴合，取身体主体段 |
| **D7** | 缩放 | **不缩放**（不加 `withScale`、不在 `preRender` 里 `scale`） | 用户裁定 3 |
| **D8** | 资产移植 | 三个文件**原样**拷入，**不改内容**（含完整动画文件） | 用户裁定 4；`identifier: geometry.unknown` 无需修正（见 §7.4） |
| **D9** | 实体 id / 显示名 | `beloong:dihuang_loong` / 「地黄龙」 | 与模型文件名一致 |
| **D10** | v1 动画 | 主控制器仅循环 **`idle`** | 无 AI ⇒ 站位不动，唯一需要的就是待机。动画文件已完整放入，将来改动画只改 Java 里的名字，**不需要换文件** |
| **D11** | 刷怪蛋 / 自然生成 | **都不做** | 用户裁定 5 |
| **D12** | 对话系统 | **不接** | 用户裁定 6 |
| **D13** | 属性 | `EntityAttributeCreationEvent` 注册 `MAX_HEALTH = 1000` + `KNOCKBACK_RESISTANCE = 1.0` | 血量由用户裁定为 **1000**（原版 `generic.max_health` 的属性上限是 1024，不会夹断）。无敌之下血量只是设定值，留足余量便于将来做"解除无敌/多阶段"的演出；`Mob` 必须有属性表，否则是 0 血怪状 |
| **D14** | 渲染器注册点 | `BeLoongCoreClient#registerRenderers`（既有 `EntityRenderersEvent.RegisterRenderers` 先例，那里已注册 tornado） | 项目既有范式 |
| **D15** | 包位置 | 实体 → `entity/`；模型 → `client/model/`；渲染器 → `client/` | 与既有 `entity/TornadoEntity`、`client/TornadoRenderer`、`client/model/TornadoModel` 的分工逐字一致 |

### 4.3 实现期修订（2026-09-21）

**R7 —— 属性注册的落点：从客户端类改到双端类（本设计初版的一处真 bug）。**

初版把 `EntityAttributeCreationEvent` 的订阅写在 `BeLoongCoreClient` 里。实现时发现这是错的：
**属性是服务端权威的**，而 `BeLoongCoreClient` 带 `dist = Dist.CLIENT` ——
**专用服务器根本不会加载这个类**，地黄龙在服务器上会没有属性表。

已改放 `registry/ModAttributes`：它是双端加载的"属性注册中心"，且**已有同类先例**
（`EntityAttributeModificationEvent` 就在那里）。教训：*凡是"服务端也要用"的注册，
落点必须先问一句"这个类在专用服务器上会被加载吗"*。

**实现期新增的一处小决策**：覆写 `isPushable()` 返回 `false`。原设计只写了"站在地上"，
没提"可被推动"，但站桩 NPC 被玩家挤着走与该意图冲突，故一并关闭；
击退另由 `KNOCKBACK_RESISTANCE = 1.0` 兜住（爆炸/攻击都推不动）。

**R8 —— 构造函数设的实体标志位会被 `/summon` 的 `load()` 覆盖（实机发现的真 bug，2026-09-21）。**

**现象**：`/summon` 出来的地黄龙**能被打**。
**根因**是创建顺序 —— `EntityType.create()`（构造函数运行，三个标志被设为正确值）→
紧接着 `entity.load(召唤标签)`，而 `load()` 会从标签逐个读回这些字段，**标签里没有对应键时即被覆盖为 false**：

| 字段 | 覆盖位置 | 召唤后 |
|---|---|---|
| `invulnerable` | `Entity.java:1759` | false |
| `persistenceRequired` | `Mob.java:437` | false |
| `noAi` | `Mob.java:486` | false |

⇒ 初版的 **D2 / D3 / D4 三条同时失效**（只有"不注册 goal"那一半还生效，所以外观上它仍然不动，
掩盖了问题）。另有一个能佐证的现象：`Entity.java:1664` 在**保存时**会写出 `Invulnerable`，
于是"刚召唤出来能被打、存档重进后反而无敌"。

**修法**：把这三条语义改成**覆写 getter**（`isInvulnerableTo` / `isNoAi` / `isPersistenceRequired`），
与任何加载路径无关；构造函数里的 `setXxx` 保留，仅用于让字段本身与保存出的 NBT 保持一致。

**参考实现**：传奇怪物（Legendary Monsters）的休眠怪一律**覆写 `hurt()` 按状态判定**
（`Frostbitten_GolemEntity:528-532` 等十余处），同样不依赖 `invulnerable` 字段；
它们只在**运行期动态状态**（伏击、传送）上使用 `setInvulnerable`。

**通用教训**（已记入 `memory/decisions-log.md`）：*实体构造函数里设的标志位不是持久的；
凡是"这个实体永远如此"的语义，必须用**行为覆写**表达。*

## 五、组件

| 组件 | 职责 | 关键 API（出处见 §7） |
|---|---|---|
| `DihuangLoongEntity` | `PathfinderMob implements GeoEntity`；无 AI、无敌、不消失；提供 `AnimatableInstanceCache` 与一个主控制器 | `GeckoLibUtil.createInstanceCache(this)`；`registerControllers(AnimatableManager.ControllerRegistrar)`；`getAnimatableInstanceCache()` |
| `DihuangLoongModel` | `GeoModel<DihuangLoongEntity>`，返回三个资源路径 | `getModelResource` / `getTextureResource` / `getAnimationResource` |
| `DihuangLoongRenderer` | `GeoEntityRenderer<DihuangLoongEntity>` | 构造 `(EntityRendererProvider.Context, GeoModel<T>)` |
| `ModEntities`（改） | 注册 `EntityType` | `EntityType.Builder.of(factory, MobCategory.MISC).sized(1.5F,2.5F).clientTrackingRange(10).fireImmune().build("beloong:dihuang_loong")` |
| `BeLoongCoreClient`（改） | 注册渲染器 + 属性 | `EntityRenderersEvent.RegisterRenderers`（既有）；`EntityAttributeCreationEvent`（mod 总线，新增 static 订阅方法） |

## 六、数据流与边界

- **生成**：`/summon beloong:dihuang_loong`（无刷怪蛋、无自然生成）。
- **双端**：实体本体双端存在（服务端权威）；GeckoLib 的**动画与渲染只在客户端**；本功能**不发任何自定义网络包**（GeckoLib 内部有它自己的同步，与我们无关）。
- **存档兼容**：新增实体类型对旧存档安全（不写入任何自定义数据、不需要数据修复）。
- **与其他模组**：不与 DS 的龙种冲突（不同实体类型）；因为无敌 + 无 AI，不会被其他模组的 AI/仇恨逻辑影响行为（但仍可能被索敌 —— 只是打不动它）。
- **移除方式**：`/kill`（`BYPASSES_INVULNERABILITY`）或创造模式攻击。

## 七、已核实的技术前提（附出处）

> GeckoLib 源码：`D:\Minecraft\开源模组参考文件\Geckolib`（changelog 头部 **v4.9.2**）。
> **关键**：本项目实际解析到的 GeckoLib 版本也是 **4.9.2**（CurseForge file `8350073`，`build.gradle:139`），
> 因此本地源码就是准确版本的 API，无需担心版本漂移。
> 原版源码取自本机反编译产物 `decompile_4eaa4bb73e8ecf66b41d931147114443211226c1_output.jar`。

| # | 结论 | 出处 |
|---|---|---|
| 7.1 | 实体只需实现 2 个方法：`registerControllers`、`getAnimatableInstanceCache`；缓存用 `GeckoLibUtil.createInstanceCache(this)` | `GeoEntity.java`、`GeoAnimatable.java:35/44`；范例 `PumpkinWarden.java:87/237/244` |
| 7.2 | 控制器构造：`new AnimationController<>(this, "controller", 0, this::predicate)`；predicate 签名 `PlayState predicate(AnimationState<E>)`，用 `event.setAndContinue(RawAnimation)` / `event.isMoving()` | `AnimationController.java:116`；`PumpkinWarden.java:238/258-272` |
| 7.3 | **`query.head_yaw` / `query.head_pitch` 不是 GeckoLib 内置查询**。动画文件里用到它们（`head_yaw` ×210、`head_pitch` ×47）与 `math.clamp`（×257），**没有 DS 专有变量**。未知查询会被**自动注册为 0**，不报错不告警 ⇒ 头部不跟随，安全 | `MolangQueries.java:148-150`；内置名只有 `query.head_x_rotation`/`head_y_rotation`（`:64-65`）。DS 是**自己**用 `MathParser.setVariable("query.head_yaw", …)` 定义的（`DragonModel.java:66-71`）—— 将来要头部跟随照此办理 |
| 7.4 | `identifier: geometry.unknown` **无害**：GeckoLib 按 `getModelResource` 的**文件路径**取模型，`identifier` 只是可空元数据（默认 null 也合法） | `ModelProperties.java:18/35`；`GeometryTree.fromModel` 只消费骨骼与 `modelProperties`（`:15/48`） |
| 7.5 | 缺失骨骼**优雅忽略**，不崩 | `GeoModel.java:86-90`（`shouldCrashOnMissingBone()` 默认 false） |
| 7.6 | `GeoModel` 的三个资源方法：1 参版本在 4.9 标了 `@Deprecated` **但仍是抽象方法**，必须实现（2 参重载是可选覆写） | `GeoModel.java:47-73` |
| 7.7 | 渲染器构造 `(EntityRendererProvider.Context, GeoModel<T>)`；缩放可用 `withScale(...)` 或覆写 `preRender` | `GeoEntityRenderer.java:51/122/131`；`PumpkinWardenRenderer.java:25-33` |
| 7.8 | 属性注册走 mod 总线 `EntityAttributeCreationEvent` | DS `DSEntities.java:134`；BWG `BiomesWeveGoneNeoForge.java:30` |
| 7.9 | `MobCategory.MISC = ("misc", -1, true, true, 128)`：不占刷怪上限、持久 | `MobCategory.java` |
| 7.10 | `setNoAi` / `isNoAi` 存在，且 `isEffectiveAi()` 因此为 false；NoAI 存 NBT | `Mob.java:1365/1380/1362/425-426` |
| 7.11 | `setInvulnerable(true)` 会存 NBT，`isInvulnerableTo` 据此判定 | `Entity.java:2529/2518/1759` |

## 八、风险与未知

| # | 风险 | 评估与对策 |
|---|---|---|
| **R1** | **模型原点不在脚底**（最低点 y ≈ -0.88 格） | **中**：可能出现"陷进地面"或"悬空"。对策：先按原样接，实机看偏移量，必要时在**渲染器 `preRender` 里加平移**（不改模型文件、不改碰撞箱） |
| **R2** | 长条龙 vs 方块碰撞箱 | **已接受**：1.5×2.5 是身体主体段；9 格长的尾巴不会成为碰撞体 |
| **R3** | 动画骨骼缺口（`jump` 缺 31 根等） | **低**：优雅忽略；且 v1 只用 `idle`（0 缺失） |
| **R4** | 头部不跟随（`query.head_yaw` 求值为 0） | **低**：对站桩 NPC 无影响；需要时用 `MathParser.setVariable` 补 |
| **R5** | 145 骨骼 / 340 立方体的渲染开销 | **低**：GeckoLib 有烘焙缓存；单只无压力。若要放十几只需实测 |
| **R6** | 模型朝向（是否面向 +Z） | **低**：若朝向反了，在渲染器里 `poseStack.mulPose(Axis.YP.rotationDegrees(180))` 即可 |

## 九、测试策略

### 一级（实现方执行）

1. `.\gradlew.bat build` 通过。
2. 三个资产确实进了产物 jar（`jar tf` 查 `assets/beloong/{geo,animations,textures/entity}`）。
3. 静态探针：`DihuangLoongEntity` 含 `setNoAi(true)`、`setInvulnerable(true)`、`setPersistenceRequired()`；**不含** `registerGoals` 覆写。
4. 静态探针：`beloong.mixins.json` **未被修改**；`build.gradle` 未新增依赖。

### 二级（需实机）

| # | 步骤 | 预期 |
|---|---|---|
| 1 | `/summon beloong:dihuang_loong` | 模型出现、贴图正常、**播放 `idle` 动画** |
| 2 | 观察位置 | 站在地面上（若陷入/悬空 → R1，记下偏移量） |
| 3 | 等待数分钟 | **不消失**、不移动、不转向任何目标 |
| 4 | 用剑/箭攻击 | **无伤害反馈**（无敌） |
| 5 | `/kill @e[type=beloong:dihuang_loong]` | 能被移除（留的管理后路） |
| 6 | 退出重进存档 | 仍然存在、仍然无敌（NoAI/Invulnerable 存 NBT） |
| 7 | 转身绕它一圈 | 模型**不**跟着看你（`query.head_yaw` = 0，符合 §7.3） |
| 8 | 观察朝向 | 面向合理（若反了 → R6） |

## 十、后续（不在本次范围）

1. **接对话系统**：加一份 `assets/beloong/beloong/npc_dialogue/dihuang_loong.json` + 语言键即可（裁定 6 推迟）。
2. **头部跟随**：在 `DihuangLoongModel` 覆写 `applyMolangQueries`，照 DS 的写法注册 `query.head_yaw`/`query.head_pitch`。
3. **多形态/多贴图**（按生长阶段或变种切换资源）：`GeoModel` 的资源方法本就是按实体返回 `ResourceLocation`，天然支持。
4. **动画状态机**：现在挂 `idle`；将来若要"受击/施法/对话"动画，加 `triggerableAnim` + 从服务端 `triggerAnim`。
5. **缩放/巨兽感**：`renderer.withScale(...)` 或 `preRender` 里 `scale`（裁定 3 推迟）。

## 十一、来源

| 类别 | 出处 |
|---|---|
| 模型资产 | `D:\Minecraft\Models\Geckolib\dihuang_loong_npc\`（用户提供） |
| GeckoLib 源码（**4.9.2**，与本项目解析版本一致） | `D:\Minecraft\开源模组参考文件\Geckolib\` |
| 主要参考实现（同平台同版本） | BWG `Oh-The-Biomes-Weve-Gone\Common\...\pumpkinwarden\PumpkinWarden.java`、`...\client\renderer\entity\pumpkinwarden\{PumpkinWardenRenderer,PumpkinWardenModel}.java`、`...\world\entity\BWGEntityType.java` |
| 第二参考实现（**本模组必需依赖**） | DS `DragonEntity.java`（`:63/123/134/847`）、`DragonRenderer.java`（`:54/106`）、`DragonModel.java`（`:37/51-71`）、`DSEntities.java`（`:43`）、`AnimationTickTimer.java` |
| 原版行为 | 本机反编译产物 `decompile_4eaa4bb73e8ecf66b41d931147114443211226c1_output.jar` → `MobCategory` / `Mob` / `Entity` |
| 谱系背景 | `D:\Minecraft\DiHuang-Loong\src\main\resources\assets\dragonsurvival\geo\dihuang_loong.geo.json`（134 骨骼，DS 龙种版） |
