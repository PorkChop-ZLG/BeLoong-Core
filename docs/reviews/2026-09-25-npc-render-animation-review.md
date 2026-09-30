# 地黄龙渲染与动画代码审查

> 审查范围：`client/DihuangLoongRenderer.java`、`client/model/DihuangLoongModel.java`、
> `assets/beloong/geo/dihuang_loong.geo.json`、`assets/beloong/animations/dihuang_loong.animation.json`
> （只读确认接口假设：`entity/NpcEntity.java`、`entity/DihuangLoongEntity.java`、`registry/ModEntities.java`、`BeLoongCoreClient.java`）
> 审查基线：`24bb701`（工作树干净，本次未运行 gradle，未改动任何源码/资产）
> 审查方式：只读静态审查（GeckoLib 4.9.2 源码 `D:\Minecraft\开源模组参考文件\Geckolib` @ `0d9d3ea3`；
> 原版源码取 `neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_*.jar`；两个大 JSON 用脚本做结构化统计）

## 结论

代码本身**质量高于预期**：`applyMolangQueries` 的签名、调用时机、`rotLerp` 参数顺序、插值来源、
`wrapDegrees` 的必要性、`HEAD_YAW_SIGN = -1.0F` 的符号方向全部经源码逐条核实**正确**，注释里引用的
GeckoLib 行号也几乎全部准确（`AnimationController.java:747-761`、`GeckoLib` 否定 X/Y 的说法成立）。
真正值得修的是三类**外围**问题：

1. **渲染器"最小实现"留下了两个可观测缺口**：GeoEntityRenderer 直接继承 `EntityRenderer`，
   `shadowRadius` 保持默认 `0.0F` ⇒ **整条龙没有影子**；碰撞箱 1.5×2.5 而模型 9 格长，
   `getBoundingBoxForCulling()` 沿用碰撞箱 ⇒ **视锥剔除会用一个小盒子剔掉大半条龙**（模型头部/尾部会"啪"地消失）。
2. **Molang 变量注册进 GeckoLib 的全局静态表且无清理**（`MolangQueries.VARIABLES`），
   并与本工作区参考模组 Dragon Survival **同名** `query.head_yaw` —— 属于跨模型/跨模组状态污染。
3. **文档与资产对不上三处硬数字**：贴图实际 512×512（三处文档/注释写 256×256）、
   含 `head_yaw` 的动画是 35 个（写 34）、`GeoModel#shouldCrashOnMissingBone()` 在 4.9.2 里**不存在**
   （真实方法名是 `crashIfBoneMissing()`）。

## 资产统计（实测）

统计脚本方式：`ConvertFrom-Json` 后遍历 `minecraft:geometry[0].bones` 与 `animations.*`，
UV 用 `uv + uv_size` 六面端点求并集，骨骼缺失用模型 145 名集合做差集。

| 指标 | 实测值 | 文档/注释声称 | 是否一致 |
|---|---|---|---|
| geo `format_version` | `1.12.0`（GeckoLib 受支持版本） | 1.12.0（计划文档:56） | ✅ |
| animation `format_version` | `1.8.0` | 1.8.0（计划文档:57） | ✅ |
| `identifier` | `geometry.unknown` | 同（注释:17、计划文档:56） | ✅ |
| `texture_width/height`（geo 声明） | 256 / 256 | — | — |
| **贴图文件实际尺寸** | **512 × 512**（156 351 B） | **256×256**（`DihuangLoongModel.java:23`、总设计 §4.1:364 / §8:747、计划文档:56/58） | ❌ |
| 骨骼数 / 立方体数 | 145 / 340 | 145 / 340 | ✅ |
| 根骨骼 | 2：`Magic`、`Dragon` | 2（同） | ✅ |
| 重名骨骼 / 悬空 `parent` | 0 / 0 | — | ✅ |
| `Magic` 子树 | 15 骨骼 / 25 立方体（可见几何体，非空壳） | 文档未提 | — |
| `Dragon` 子树 | 130 骨骼 / 315 立方体 | — | — |
| UV 范围（含 `uv_size` 端点） | u ∈ [0, 256]，v ∈ [0, 256]；越界面 0 个 | — | ✅（与声明 256 自洽） |
| 朴素包围盒（不含骨骼旋转） | 2.01 × 2.85 × **9.02** 格 | 2.0 × 2.85 × 9.0（计划文档:62） | ✅ |
| 动画总数 | **96** | 96（§4.1）／"约 100"（计划文档:57） | ✅／≈ |
| 含 `head_yaw` 的动画数 | **35**（每动画 6 处、分布在 5 根骨骼） | **34**（`DihuangLoongModel.java:75`、计划文档:186） | ❌ |
| `idle` | 32 骨骼 / 4.75 s / `loop:true` / 缺 0 | 32 / 4.75 / 0 缺失 | ✅ |
| `walk` | 63 骨骼 / 1.375 s / `loop:true` / 缺 0 | 63 / 1.375 / 0 缺失 | ✅ |
| `run` | 77 骨骼 / 1.0 s / `loop:true` / 缺 3（`Drip1-3`） | 77 / 1 / Drip1-3 | ✅ |
| 引用缺失骨骼的动画 | 56 个动画；缺失骨骼种类 **37**（`Drip1-4`、`Mustache*`×24、`WhiskerLeft/Right`） | 只提 `run` 的 3 根 | 文档不完整 |
| 从未被任何动画引用的模型骨骼 | 57（含 `Magic2`、`1Frag2`、`2Frag2`、`3Frag2`、`bone8-13`…） | 未提 | — |
| `loop` 标记 | `true` 84 个，**无 `loop` 字段 12 个**，`hold_on_last_frame` 1 个（`qf2`） | 未提 | — |
| `*2` 系列动画 | **46 个**；`idle2`/`walk2`/`animation2`/`sneak_walk2` 只动 `Magic.scale` 一根骨骼 | "1 骨骼 stub"（计划文档:74） | ✅ |
| 关键帧规模 | 3 882 个骨骼-动画块，**78 656** 个轴关键帧项；`particle_effects`/`sound_effects` 均为 0 | 未提 | — |
| 代码实际使用的动画 | `idle` / `walk` / `run`（`NpcEntity.java:355-357`, `:97-114`） | 同 | ✅ |

## 严重问题（Critical）

### C1. `query.head_yaw` / `query.head_pitch` 进入 GeckoLib **全局静态**变量表，且与 Dragon Survival 同名 —— 跨模型/跨实体状态污染 — `DihuangLoongModel.java:114-116`

- **现象/代码**：
  ```java
  MathParser.setVariable("query.head_yaw", () -> (double) (Mth.wrapDegrees(headYaw - bodyYaw) * HEAD_YAW_SIGN));
  MathParser.setVariable("query.head_pitch", () -> (double) headPitch);
  ```
- **证据**：
  - `MathParser.setVariable` → `getVariableFor(name).set(value)`（`MathParser.java:143-145`）；
    `getVariableFor` 写的是 `private static final Map<String, Variable> VARIABLES = new ConcurrentHashMap<>()`
    （`MolangQueries.java:118`、`:148-150`）。**没有按 model / animatable 隔离的作用域**，
    且 `computeIfAbsent` 意味着"所有模型共享同一个 `Variable` 实例"。
  - 表达式在**资源加载期**编译一次（`MathParser.parseJson`/`compileMolang`，`isLikelyVariable:646-651`），
    编译结果直接持有那个全局 `Variable` 引用；之后谁 `set` 谁生效（`Variable.java:17-44`）。
  - 渲染结束只清 `ACTOR`，**不清自定义变量**：`GeoRenderer.defaultRender` 末尾 `doPostRenderCleanup(); MolangQueries.clearActor();`（`GeoRenderer.java:154-155`）。
    所以龙一旦被渲染过，这两个变量就永久停留在"最后一只龙的值"，而不是回到 GeckoLib 的"未注册 = 0"语义。
  - 同名冲突是**实证的**而不是假想：Dragon Survival（本工作区 `开源模组参考文件\DragonSurvival`，
    也正是本资产的谱系来源）在 `DragonModel.applyMolangQueries` 里注册**同名**变量
    （`DragonModel.java:66-70`），且它在 `player == null` 时**直接 return、不重设变量**（`DragonModel.java:57-59`）。
- **影响**（触发条件明确）：
  1. 装了 Dragon Survival 时，**无 owner 的 DS 龙**（NPC 龙）会读到我们地黄龙最后一次渲染的头部角度；
  2. 本模组内：`NpcEntity` 是通用基类，**第二个 NPC** 若用同族资产、又忘了在自己的
    `applyMolangQueries` 里重设，就会静默继承地黄龙的值（而不是 0），且没有任何日志；
  3. 单帧内多只地黄龙**不会**互相污染 —— 每只的 `preAnimationSetup → tickAnimation` 是相邻发生的
    （`AnimationProcessor.java:306-309`、`GeoModel.java:221-224`），值在求值前刚被重设。
    这一点已核实，可以放心（详见"已验证正确的部分"第 6 条）。
  4. 无内存泄漏：lambda 只捕获 `float`/`double` 基本量，不持有 `Entity` 引用。
- **建议修法**（按侵入性从小到大，**本次不做**）：
  1. **加清理**（零资产改动，立刻可做）：覆写 `DihuangLoongRenderer#doPostRenderCleanup()`，
     把两个变量 `set(() -> 0)`，恢复"别人读到 0"的默认语义；
  2. **改命名空间**（需改资产，属后续任务）：把资产里的 `query.head_yaw/head_pitch` 改为
     `query.beloong_head_yaw / query.beloong_head_pitch`（`query.` 前缀下 `b` 开头不会与任何内置冲突，
     也能避开 DS）。改完需**同时**改 Java 侧字符串与 96 个动画 JSON；
  3. 顺带在 `NpcEntity` 里把"子类模型必须自设自己用到的 Molang 变量"写成硬约定（注释 + 后续 review 检查项）。

## 重要问题（Major）

### M1. 地黄龙**完全没有影子** —— `shadowRadius` 默认 `0.0F`，`GeoEntityRenderer` 不设置它 — `DihuangLoongRenderer.java:22-26`

- **现象/代码**：`public class DihuangLoongRenderer extends GeoEntityRenderer<DihuangLoongEntity>`，
  构造器只 `super(context, new DihuangLoongModel());`，没有设置阴影。
- **证据**：
  - 原版字段默认值：`protected float shadowRadius;`（**默认 0.0F**）`EntityRenderer.java:31`；
  - 原版只在半径 > 0 时才画影子：`if (f > 0.0F) { ... renderShadow(...) }`，`f = entityrenderer.getShadowRadius(entity)`
    （`EntityRenderDispatcher.java:168-176`）；
  - 普通生物的影子来自 `LivingEntityRenderer`/`MobRenderer` 构造器（`LivingEntityRenderer.java:37-41` 里 `this.shadowRadius = shadowRadius;`），
    而 `GeoEntityRenderer extends EntityRenderer`（`GeoEntityRenderer.java:51`），**绕过了那条路**；
  - GeckoLib 全仓 `grep shadowRadius|getShadowRadius` **零命中** ⇒ 不会替我们补。
- **影响**：9 格长的巨型生物脚下无任何阴影，视觉上明显"悬浮"；这与总设计 §10.1 R9"实测无偏移（不陷地/不悬空）"
  是两件独立的事（R9 判的是脚底位置，影子缺失不会在那种检查里暴露）。
- **建议修法**：在 `DihuangLoongRenderer` 构造器里 `this.shadowRadius = 2.0F;`（`shadowRadius` 是
  `EntityRenderer` 的 `protected` 字段，子类可直接写），或覆写
  `protected float getShadowRadius(DihuangLoongEntity entity)` 返回与体型匹配的值（如 1.5~2.5）。
  注意 `shadowStrength` 默认已是 1.0F，不需要动。

### M2. 视锥剔除用的是碰撞箱，而碰撞箱只有模型的 1/4 大 — `DihuangLoongRenderer.java`（未覆写）+ `ModEntities.java:70`

- **现象/代码**：渲染器与实体都没有覆写 `getBoundingBoxForCulling()`；碰撞箱 `sized(1.5F, 2.5F)`。
- **证据**：
  - `EntityRenderer.shouldRender`：`AABB aabb = livingEntity.getBoundingBoxForCulling().inflate(0.5);`
    然后做视锥判定（`EntityRenderer.java:52-64`）；
  - `Entity.getBoundingBoxForCulling()` 默认就是 `this.getBoundingBox()`（`Entity.java:3030-3032`）；
  - 实测模型朴素包围盒 **2.01 × 2.85 × 9.02 格**（z 从 −3.8 延伸到 +5.2 格），
    即沿长轴有约 5 格伸出碰撞箱之外。
- **影响**：龙身（尤其头/尾）尚在画面内、但 2.5×3.5 的判定盒已离开视锥时，**整条龙被剔除**
  —— 表现为视线扫过时头尾"啪"地消失 / 贴着屏幕边缘时整体闪没。
- **建议修法**（二者择一）：
  1. 在 `DihuangLoongEntity`（或 `NpcEntity`，若想通用）覆写 `getBoundingBoxForCulling()`，
     返回以实体为中心、覆盖模型实际伸展的 `AABB`（例如 `getBoundingBox().inflate(…).expandTowards(0,0,±5)`）；
  2. 或直接把 `Entity.noCulling = true`（`public boolean noCulling;`，`Entity.java:225`；
     `EntityRenderer.shouldRender:55-56` 会短路成 `true`）—— 代价是永远不剔除，只有一两只时无所谓。

### M3. 贴图实际 512×512，而 geo 声明 256×256、文档/注释说 256×256 —— 且"照文档修"会把模型改坏 — `DihuangLoongModel.java:23`；`docs/NPC系统总设计.md:364,747`

- **现象/代码**：`* {@code textures/entity/dihuang_loong.png} —— 256×256 贴图。`
- **证据**：
  - 实测 PNG 尺寸 **512 × 512**（`System.Drawing.Image` 读出 512×512，156 351 B）；
    geo 的 `description.texture_width/height = 256/256`；
  - GeckoLib **只按 geo 声明的 256 归一化 UV**：`uWidth = (u + uSize) / texWidth; u /= texWidth;`
    （`GeoQuad.java:25-29`），随后这些 [0,1] 坐标直接采样**整张** PNG；
  - 因此只有当 512 文件是"256 版图整体 ×2"时渲染才正确。我做了像素检验：
    2×2 块内相邻像素完全相同的比例只有 **0.6664（横向）/ 0.6674（纵向）**，
    奇偶相位各 ~0.64–0.68 ⇒ **它不是一次 nearest 2× 放大**（nearest 会是 1.00）；
    四个象限都有内容（不透明占比 49%/61%/68%/62%）⇒ 内容是铺满整张 512 的，
    排除了"256 版图原尺寸塞在左上角"这种会直接错位的情形。剩下的可能（平滑重采样放大 / 在 512 画布上按 2× 重绘）静态无法区分。
- **影响**：当前**大概率能正确渲染**（实机也未见贴图错乱报告），但这是个"埋雷"：
  任何看到"文档=256、文件=512"的人最自然的修法是**把 geo 的 `texture_width` 改成 512**，
  那会让所有 UV 分数减半 ⇒ 模型只会采样贴图左上 1/4，整条龙瞬间花掉。
- **建议修法**：
  1. 改注释与两处文档为"**512×512（256 空间的 2× 版）**"，并在 geo 附近补一句
     "geo 的 `texture_width/height` 必须保持 256，不要跟着贴图尺寸改"；
  2. 想彻底消除歧义就重导一张 256×256 贴图（保持版图不变），或按 512 重新导出 geo
     （需要 Blockbench 侧改工程贴图尺寸，本审查不建议在无美术资源时动）。

### M4. 文档 §4.2 第 2 条描述了**不存在**的实现 — `docs/NPC系统总设计.md:377`

- **现象/文档**：`2. 提取模型自身的 renderLayers 与 root 骨骼；`
- **证据**：`DihuangLoongModel.java` 全文 118 行，除三个资源方法外只有 `applyMolangQueries`；
  **没有任何** `renderLayers` / root 骨骼相关代码（`grep` 亦可确认）。
- **影响**：读者会以为模型类做了骨骼/渲染层探查（例如"`Magic` 根是否被处理"），从而漏查多根骨骼问题。
- **建议修法**：删掉该条，或改写为"声明三条资源路径 + 注册两个 Molang 变量"（与代码一致的两件事）。

### M5. 文档/注释数字不符：含 `head_yaw` 的动画是 35 个而不是 34 个 — `DihuangLoongModel.java:75`；`docs/plans/2026-09-21-dihuang-loong-npc-design.md:186`

- **证据**：脚本统计 96 个动画中**有 35 个**的 `bones` 里出现 `head_yaw`；
  字符串出现 210 次 = 35 × 6（每次分布：`Head-Molang` ×1、`Neck-Molang` ×2、`NeckB/C/D-Molang` 各 ×1）。
  javadoc 只列了 `Head-Molang`/`Neck-Molang` 两根骨骼的表达式，实际是**5 根**（代码里的引用本身没错，只是计数与覆盖面不全）。
- **影响**：低（不影响运行），但这是"以源码为准"文档里的硬数字，容易在后续统计脚本里被继续引用。
- **建议修法**：改成 35，并把 `NeckB/C/D-Molang` 一并列出。

### M6. 注释引用了 4.9.2 中**不存在**的方法名 `GeoModel#shouldCrashOnMissingBone()` — `DihuangLoongModel.java:26-27`

- **现象/代码**：`GeckoLib 对缺失骨骼是优雅忽略（{@code GeoModel#shouldCrashOnMissingBone()} 默认 false）`
- **证据**：4.9.2 里该钩子叫 **`crashIfBoneMissing()`**（`GeoModel.java:91-93`，被 `:224` 调用）；
  全仓 grep `shouldCrashOnMissingBone` **零命中**。同名的错误引用也出现在
  `docs/plans/2026-09-21-dihuang-loong-npc-design.md:240`。
  （结论本身是对的："优雅忽略"由 `AnimationController.java:529-534`、`:605-611` 实测确认。）
- **影响**：维护者照注释去找钩子会找不到；想改成"缺失即崩"的人会写错方法名。
- **建议修法**：改为 `crashIfBoneMissing()`。

## 次要问题（Minor）

### m1. `query.head_pitch` 与 GeckoLib 内置 `query.head_x_rotation` 值完全等价 — `DihuangLoongModel.java:112,116`

- **证据**：内置 `HEAD_X_ROTATION`（`MolangQueries.java:285`）取 `animatable.getViewXRot(partialTick)`，
  而原版 `getViewXRot(pt) = pt == 1.0F ? getXRot() : Mth.lerp(pt, xRotO, getXRot())`（`Entity.java:1602-1604`），
  与本行 `Mth.lerp(partialTick, entity.xRotO, entity.getXRot())` 只在 `partialTick == 1.0F` 时有浮点级差异。
- **影响**：多注册一个全局变量（见 C1 的污染面），无功能收益。
- **建议修法**：保留（资产写的是 `head_pitch`，改名要动资产），但在注释里点明"与内置 `query.head_x_rotation` 等价，
  之所以自定义是因为资产用的是另一个名字"；若将来允许改资产，直接用内置名可少一个全局变量。

### m2. javadoc 里"所以只能用**惰性 supplier**"的理由与这两个变量无关 — `DihuangLoongModel.java:92-99`

- **证据**：`AnimationState.getController()` 在 `withController`（`AnimationProcessor.java:88`）之前确实为 null，
  而 `preAnimationSetup`（`:306-309`）更早 —— **引用本身正确**。
  但本方法的两个 supplier **根本不读 controller**，用 supplier 只是 `MathParser.setVariable(String, DoubleSupplier)`
  的签名要求（`MathParser.java:143`）；"null controller"只对 `query.controller_speed` 这类查询有意义
  （`MolangQueries.java:210`）。
- **建议修法**：把这句改成"签名要求 supplier；顺带说明此刻 controller 尚未注入，所以任何读 controller 的查询
  都必须惰性求值"。纯注释澄清，无需改逻辑。

### m3. 用 `Mth.lerp(partialTick, xRotO, getXRot())` 而不复用原版 `getViewXRot/getViewYRot` — `DihuangLoongModel.java:112`

- **证据**：`Entity.java:1602-1611` 提供了语义相同、且在 `partialTick == 1.0F` 时返回精确值的封装。
- **影响**：无实际后果（差异 < 1e-5 度），仅一致性/可读性。
- **建议修法**：`entity.getViewXRot(partialTick)`（yaw 侧没有对应封装可用，因为要的是"头减身"，保持现状即可）。

### m4. `@OnlyIn(Dist.CLIENT)` 在新版 NeoForge 里已不作为裁剪依据 — `DihuangLoongRenderer.java:21`

- **证据**：该类实际只被 `BeLoongCoreClient`（`@EventBusSubscriber(dist = Dist.CLIENT)`，见 `BeLoongCoreClient.java:32-36` 的说明）
  引用，双端隔离已由事件订阅保证。
- **影响**：无害的冗余注解；若被误认为"能阻止服务端加载"会误导。
- **建议修法**：可保留（与仓库既有风格一致），但不必再往新类上扩散。

### m5. `Magic` 子树里 10 根骨骼从未被任何动画驱动（`Magic2`、`1Frag2`、`2Frag2`、`3Frag2`、`bone8-13`），模型里另有 57 根骨骼完全闲置

- **证据**：`Magic` 子树 15 骨骼 / 25 立方体；其中被动画驱动的只有 `Magic`（96 个动画全都驱动它）、
  `1Frag/2Frag/3Frag`（各 26 个）、`bone7`（24 个）。因 `Magic` 是父骨骼、96 个动画都动它，
  所以**这些几何体不会静止失配**，只是作为独立骨骼是死重。
- **影响**：烘焙内存与资产体积（`Magic2`/`*Frag2` 系列明显是"第二套尺寸变体"的残留）。
- **建议修法**：不改资产的前提下，仅在文档里登记"`Magic` 子树 = 常驻可见几何体（25 立方体），
  其中 10 根骨骼无独立动画"。若将来裁剪资产，这是第一优先级的删除对象。

## 建议（Nit）

- **N1（有量化依据，优先）**：96 个动画 / 3 882 骨骼块 / 78 656 轴关键帧**全部**在资源重载时烘焙常驻
  （`GeckoLibCache.loadAnimations` → `loadResources(..., "animations", …)`，`:74-90`），
  而代码只用 3 个（`idle`/`walk`/`run` = 172 个骨骼块，约占 4.4%）。
  按"每个关键帧 1 个 `Keyframe` + 2 个 `MathValue` 节点（Constant/Calculation 树）"粗估，
  这一份文件常驻堆约 **10–20 MB** 量级（估算，非实测）。
  **注意：拆成多个 animation json 没有用** —— `loadResources` 用
  `resourceManager.listResources("animations", name -> name.endsWith(".json"))`
  把**所有**命名空间下的 `animations/**.json` 全量加载（`:116`），所以唯一的裁剪手段是**真的删内容**：
  46 个 `*2` 变体（见下）、37 根缺失骨骼相关动画、以及只动 `Magic.scale` 的 stub，都是可删对象。
  当前只有一只地黄龙，**不建议为此动资产**，登记为待办即可。
- **N2**：`*2` 系列（46 个）核实为"第二套尺寸变体"：`idle2`/`walk2`/`animation2`/`sneak_walk2`
  **只动 `Magic` 的一根 scale 骨骼**，`run2` 是 `Magic.scale` + 3 根缺失的 `Drip*.scale`。
  保留价值 = "将来做第二体型时开箱可用"；若确定不做第二体型，这 46 个是纯死重（占动画数 48%）。
- **N3**：12 个动画没有 `loop` 字段（`jump2`、`fly_spin2`、`fly_land2`、`hugging_sitting2`、
  `tectonic_dash2`、`qf2`(=`hold_on_last_frame`) 等）。当前用的是 `thenLoop`
  （`RawAnimation.java:67-68` → `LoopType.LOOP`），stage 值会**覆盖**资产里的 `loop`
  （`RawAnimation.java:110-116` 的 javadoc 明说 "overriding the default value set in the animation json"），
  所以**不会**因为资产缺 `loop` 而出问题；`idle/walk/run` 三个还都恰好是 `loop:true`，双保险。
  将来若改用 `thenPlay`（= `LoopType.DEFAULT`），循环行为会**回落到资产里的 `loop` 标记**
  （`Animation.java:41`），届时这 12 个就会变成"播一次就停"——值得在注释里留一句。
- **N4**：`HEAD_YAW_SIGN` 的注释可以升级为"推导 + 实机"双证据（推导见"已验证正确的部分"第 5 条），
  并明确写出**它与资产表达式里的前导负号是耦合的**：资产若把
  `"-math.clamp(query.head_yaw*0.355556,…)"` 改成 `"+math.clamp(…)"`，这个常量就必须翻回 `+1.0F`。
  现在注释只说"若将来又反了，翻回 +1.0F"，没有点出"资产改符号也会导致翻车"。
- **N5**：`DihuangLoongRenderer` 的类注释把"将来要加 y 偏移"写在 `preRender` 上，这没问题；
  但可以顺带记一句"GeckoLib 自己已经 `poseStack.translate(0, 0.01f, 0)`
  （`GeoEntityRenderer.java:279`）"，因为 R9 的"不需要偏移"结论是在这 0.01 已经存在的前提下得出的。

## 已验证正确的部分

以下每一条都是**主动去源码/资产核实过**的，不是"看起来没问题"：

1. **`applyMolangQueries` 的签名与调用时机** —— 签名是
   `public void applyMolangQueries(AnimationState<T> animationState, double animTime)`（`GeoModel.java:249`，
   默认空实现）；调用链 `GeoEntityRenderer.actuallyRender`（仅 `!isReRender`，`:263-277`）→
   `GeoModel.handleAnimations`（`:192-227`）→ `AnimationProcessor.preAnimationSetup`（`:306-309`）→
   `MolangQueries.updateActor` **然后**才 `model.applyMolangQueries`。
   即：**每帧、每实体、渲染线程、controller tick 之前、恰好一次**。参数确实是
   `AnimationState` 与 animTime，`@Override` 正确。
2. **`partialTick` 就是帧插值量** —— 来自 `EntityRenderer.render(..., partialTick, ...)`
   （`GeoEntityRenderer.java:197-203` 透传），原版由 `EntityRenderDispatcher` 传入，
   最终进 `new AnimationState<>(..., partialTick, ...)`（`:268`）、由 `AnimationState.getPartialTick()` 读出（`:62-63`）。
3. **`Mth.rotLerp` 参数顺序正确** —— `rotLerp(float delta, float start, float end) = start + delta * wrapDegrees(end - start)`
   （`Mth.java:704-706`）；代码写的是 `rotLerp(partialTick, yBodyRotO, yBodyRot)`（`DihuangLoongModel.java:110-111`），
   与 `GeoEntityRenderer.java:218-220`、原版 `LivingEntityRenderer.java:59-61` 完全同款。
   写反（`current, prev`）会让插值系数作用在反向量上，表现为**每帧朝反方向过冲**（不是抖动，而是"甩头"）。
4. **插值源 `yBodyRotO`/`yHeadRotO` 在客户端确实被更新** ——
   `LivingEntity.tick()` 每 tick 执行 `this.yBodyRotO = this.yBodyRot; this.yHeadRotO = this.yHeadRot; this.yRotO = …; this.xRotO = …;`
   （`LivingEntity.java:504-507`），tick 在客户端同样运行；`yBodyRot` 由客户端自己的
   `BodyRotationControl` 算出（总设计 §3.4 第 3 点的说法经此核实**成立**）。
   ⇒ 不会出现"只传当前值导致 20Hz 阶梯"的问题，`rotLerp` 用法是必要的且正确。
5. **`wrapDegrees` 必要且正确** —— `headYaw`、`bodyYaw` 各在 ±180 内，其差可达 ±360
   （例：头 −179 / 身 +179 ⇒ −358），不 wrap 会得到荒谬的相对角。原版自己也是这么做的
   （`LivingEntityRenderer.java:61` 与 `:88` 的 `f2 = Mth.wrapDegrees(f2)`，`GeoEntityRenderer.java:225` 亦然）。
6. **`HEAD_YAW_SIGN = -1.0F` 方向正确（可静态推导，且与实机结论一致）** —— 推导链条：
   1. 模型放置：`poseStack.mulPose(Axis.YP.rotationDegrees(180f - rotationYaw))`
      （`GeoEntityRenderer.java:380`）+ MC 约定（yaw 0 = +Z 南、90 = −X 西，即朝向向量 `(-sin ψ, cos ψ)`）
      ⇒ **模型本地前方 = −Z**；
   2. 骨骼旋转按 `poseStack.mulPose(Axis.YP.rotation(bone.getRotY()))` 应用（`RenderUtil.java:45-53`）；
      右手系下绕 +Y 正向旋转把 +Z 转向 +X ⇒ 头部 `rotY = +θ` 会让头相对身体**向左**偏，
      即所需的骨骼角是 `rotY = −netHeadYaw`（`netHeadYaw = wrap(yHeadRot − yBodyRot)`，正是原版口径）；
   3. GeckoLib 对 **Molang 表达式**（非常量）的旋转值取负：`startValue = Math.toRadians(startValue); if (axis == X || Y) startValue *= -1;`
      （`AnimationController.java:747-761`）⇒ Molang 值 `E`（度）最终产生 `rotY = −toRadians(E)`；
      常量走的是同一条符号约定，只是提前到加载期（`BakedAnimationsAdapter.java:205-207` 对 X/Y 取负、Z 不取负，
      与 `BakedModelFactory.java:150` 对模型静态旋转的处理一致）⇒ Molang 与 Blockbench 常量**同一套符号**，不存在"两条路反号"的坑；
   4. 资产表达式为 `Head-Molang.rotation = [ …, "-math.clamp(query.head_yaw*0.355556,-32,32)", 0 ]`
      （实测 `idle` 原始串），即 `E = −k·V` ⇒ `rotY = +k·toRadians(V)`；
      需求是 `rotY = 0.3556·(−netHeadYaw)` ⇒ **`V = −netHeadYaw`**，正是 `wrapDegrees(headYaw − bodyYaw) * (−1)`。
   5. 俯仰侧独立自洽：资产写 `+math.clamp(query.head_pitch*0.5, …)`，`E = +0.5·headPitch`
      ⇒ `rotX = −0.5·toRadians(headPitch)`；而 `X` 轴正向 = 抬头、`xRot > 0` = 低头
      ⇒ 低头得到负 `rotX`，方向正确 ⇒ **`query.head_pitch` 不加符号是对的**。
   ⇒ 代码常量、实机结论（`DihuangLoongModel.java:48-50`：`+1.0F` 时左右反了）、推导三者一致。
   唯一前提是"模型本地前方是 −Z"，该项由"实机龙身朝向正常、只有头反向"这一已记录的观察佐证。
7. **GeckoLib 不内置 `query.head_yaw`/`head_pitch`，未注册的查询静默取 0** ——
   内置表（`MolangQueries.java:36-116`）只有 `HEAD_Y_ROTATION`/`HEAD_X_ROTATION`/`BODY_Y_ROTATION`；
   未注册名走 `getVariableFor` 的 `computeIfAbsent(..., key -> new Variable(key, 0))`（`:148-150`），
   且 `isLikelyVariable` 会把它当变量而不是解析失败（`MathParser.java:646-651`）
   ⇒ 注释里"未注册的查询会静默取 0（`MolangQueries.java:148-150`）"**逐字正确**。
   也确认了不能改用内置的 `query.head_y_rotation`：它是**绝对**朝向（`:286` `getViewYRot`），会把整体朝向叠进去（注释的说法正确）。
8. **多根骨骼（145 骨骼 / 2 根根骨骼）完全受支持，`Magic` 不会丢** ——
   `BakedModelFactory.constructGeoModel` 遍历 `geometryTree.topLevelBones().values()` 逐个建 `GeoBone`
   （`:136-140`）；`GeoRenderer.actuallyRender` 渲染时 `for (GeoBone group : model.topLevelBones())`（`:190`）；
   `AnimationProcessor.setActiveModel` 对每个顶层骨骼递归 `registerGeoBone`（`:288-294`）。
   实测 `Magic` 子树 15 骨骼 / **25 立方体**（有实体几何）、被 96 个动画全部驱动 —— 不是空壳、不会丢。
9. **没有悬空/错挂的 cube** —— 145 个骨骼名无重复，所有 `parent` 都能解析到存在的骨骼；
   geo 是 1.12.0 的"扁平 bones 数组 + `parent` 引用"格式（cube 直接嵌在骨骼里，不存在"cube 的 parent 指向"这一概念）。
10. **UV 不越界** —— 用 `uv` 与 `uv + uv_size`（本次模型全部是 per-face UV 对象，含**负 `uv_size`** 的镜像面，
    如 `south.uv_size = [3.90, −0.5]`）求六面端点并集：u、v 均落在 **[0, 256]**，越界面 0 个；
    且没有任何 cube 用 `texture_width/height` 覆盖（覆盖数 0）⇒ 与 `description` 的 256×256 自洽。
11. **三个资源路径与磁盘文件一一对应、大小写正确、大小写敏感也安全** ——
    `geo/dihuang_loong.geo.json`、`animations/dihuang_loong.animation.json`、`textures/entity/dihuang_loong.png`
    均存在；三份文件与源目录 `D:\Minecraft\Models\Geckolib\dihuang_loong_npc\` **SHA256 完全一致**
    ⇒ 类注释里"原样移植、内容未做任何改动"**可验证成立**。
    目录位置也满足 GeckoLib 的硬要求（model 必须在 `geo/`，否则 `GeoModel.java:112-115` 抛异常；
    animation 必须在 `animations/`，否则 `:161-164` 抛异常）。
12. **"惰性 supplier"这一项在 4.9.2 不存在** —— `getModelResource/getTextureResource/getAnimationResource`
    签名返回的是普通 `ResourceLocation`，**没有 supplier 重载**（`GeoModel.java:47-73`）；
    两个"带 renderer"的重载只做转调。因此用 `static final` 常量是正确写法，
    也**不存在**"注册表未就绪时求值"的风险（这三个值不查注册表）。
13. **`identifier: geometry.unknown` 确实无关紧要** —— `ModelProperties:35` 是
    `GsonHelper.getAsString(obj, "identifier", null)`（可空），注释引用的 `ModelProperties.java:35` **准确**；
    模型查找按**文件路径**做 key：`GeckoLibCache.getBakedModels().get(location)`
    （`GeoModel.java:108-124`），缓存键就是资源路径（`GeckoLibCache.java:93-105`）。
14. **缺失骨骼是"真优雅忽略"，既不崩也不打日志** ——
    `AnimationController.java:529-534`（`bone == null` 时 `if (crashWhenCantFindBone) throw …; continue;`）
    与 `:605-611`；默认 `crashIfBoneMissing()` 返回 false（`GeoModel.java:91-93`）。
    ⇒ 文档/注释"优雅忽略、不会崩"**成立**；
    另外实测 `idle`/`walk` 缺失骨骼 **0**、`run` 缺失 3 根（`Drip1-3`），
    也就是说**当前实装的三个动画里，唯一暴露的缺失就是 `run` 的 3 根**，
    其余 56 个动画的 37 种缺失骨骼（`Mustache*`、`Whisker*`、`Drip4`）永远不会被触发。
    （顺带：唯一会打日志+抛栈的是"**动画名**找不到"，见 `AnimationProcessor.java:49-57`；
    那与"骨骼找不到"是两件事，不要混淆。）
15. **渲染器注册正确** —— `BeLoongCoreClient.registerRenderers`（`@SubscribeEvent`，参数
    `EntityRenderersEvent.RegisterRenderers`）里 `event.registerEntityRenderer(ModEntities.DIHUANG_LOONG.get(), DihuangLoongRenderer::new)`
    （`BeLoongCoreClient.java:85-91`），泛型与 `DihuangLoongEntity` 匹配；
    `getTextureLocation` 无需自己实现 —— `GeoEntityRenderer` 已把它转发给 `GeoRenderer.getTextureLocation`
    → `getGeoModel().getTextureResource(...)`（`GeoEntityRenderer.java:106-109`、`GeoRenderer.java:48-50`）。
16. **`thenLoop` 与资产 `loop` 不冲突** —— `idle`/`walk`/`run` 实测都是 `loop: true`；
    `thenLoop` 映射到 `LoopType.LOOP`（`RawAnimation.java:67-68`），而 stage 的循环类型会覆盖资产里的标记
    （`RawAnimation.java:110-116`）；`LoopType.DEFAULT` 才回落到资产值（`Animation.java:41`）。
    另外三个动画的 `animation_length` 与文档声称一致（4.75 / 1.375 / 1.0）。
17. **`preRender` 的注释与代码一致** —— 渲染器**完全没有覆写** `preRender`（文件 27 行，
    只有一个构造器），所以"保持最小实现、不做 y 偏移"是真的；GeckoLib 自身在渲染前
    追加了 `translate(0, 0.01f, 0)`（`GeoEntityRenderer.java:279`），这也是"不需要偏移"结论的隐含前提。
18. **头的驱动链前提成立** —— `LookControl.clampHeadRotationToBody` 只在
    `!this.mob.getNavigation().isDone()`（有寻路）时夹取（`LookControl.java:76-80`），
    所以站桩时头能自由领先身体 ⇒ §3.4 的关键论断正确；
    `Mob.tickHeadTurn` 的行号也准确（`Mob.java:377-381`，覆写且不调 `super`）。
    另实测 `sit` = 62 骨骼 / 6 s、`fly` = 65 / 1.9167、`jump` 缺 31 根，与计划文档表格一致。
19. **`query.` 前缀本身不会与"原版 query 命名空间"冲突** —— GeckoLib 的变量表是一张扁平 map，
    别名替换只处理 `q.` → `query.`（`MolangQueries.java:148-150`、`:160-167`）；
    只要名字不是内置名（`head_yaw`/`head_pitch` 都不是）就不会覆盖内置语义。
    **风险不在前缀，而在"用了一个公共命名，别人也可能用"**（见 C1）。

## 无法静态确认的项

1. **512×512 贴图是否为"256 版图的忠实 2×"** —— 像素检验只能排除"左上角塞 256 版图"和"nearest 2×"两种情况，
   无法区分"平滑重采样放大"与"在 512 画布上按 2× 重绘"。
   **验证动作**：游戏里 `/summon beloong:dihuang_loong`，看一个高辨识度纹理细节（如眼睛、腹部鳞片）
   是否落在骨头的正确位置；或把 PNG 缩到 256×256 后与原图逐像素比（若为纯放大，缩后应与作者原始 256 图一致）。
2. **`HEAD_YAW_SIGN` 的最终观感** —— 推导依赖"模型本地前方 = −Z"这一前提（已由既有实机结论佐证，但没有第二次独立验证）。
   **验证动作**：站桩时让玩家绕着龙走一圈，确认头**先于身体**转向玩家且方向不反；若反，按注释翻常量即可。
   本条也是"任何一次资产替换后必须重测"的项。
3. **`Magic` 子树那 25 个立方体的美术意图** —— 属于"常驻可见几何体"（10 根骨骼无独立动画，跟随 `Magic` 运动）。
   是否是"龙气/法阵碎片"应当常亮，只能实机看。
4. **内存/加载时间的量化值** —— 78 656 轴关键帧 / 3 882 骨骼块的常驻开销是**按对象数推算的 10–20 MB 量级估计**，
   没有实测（本次不跑构建、不进游戏）。
5. **影子与剔除两项修好后的观感** —— 代码层面"当前无影子""当前用小盒子剔除"是确定的，
   但"调多大才合适"（`shadowRadius`、剔除盒膨胀量）需要实机对着 9 格体型调。

## 越界发现

以下**不在本次审查范围**内，且**未做任何修改**，仅登记：

1. `docs/plans/2026-09-21-dihuang-loong-npc-design.md` 是同批数字错误的共同来源：
   `:56`/`:58` "256×256 贴图"、`:240` `shouldCrashOnMissingBone()`（不存在）、
   `:186` "34 个动画"、`:57` "约 100 个动画"（实测 96）。
   历史文档按约定不改，但如果 §十一 的"与本文冲突时以本文与源码为准"要贯彻，
   建议在总设计 §4.1 下方加一行"**已修正**：贴图实为 512×512、`head_yaw` 动画 35 个"。**我没有改任何文档。**
2. `NpcEntity.runAnimationName()` 的 javadoc（`NpcEntity.java:109-110`）与
   `docs/NPC系统总设计.md:932`（§10.2 推迟待办）关于 `run` 缺 `Drip1-3` 的表述**准确**，
   但没提"这 3 根骨骼带的是 `scale` 关键帧"（实测 `run` 里 `Drip1-3` 只有 scale 轨道）
   —— 缺失的效果是"某段鳞片/水滴不再缩放"，比"整块不动"更轻微，可以在文档里写实。
3. `ModEntities` 的碰撞箱 1.5×2.5 与 9 格模型不匹配这件事，除了 M2 的剔除问题外，
   还会影响"玩家能站多近"（推挤/寻路）。这是原文档有意的裁定（§4.3），仅提示 M2 与它同源。
4. 本模组另一个 NPC 基类使用者若要复用这套 Molang 方案，**必须自己成对地
   set + 清理**，否则会踩 C1；建议在 `NpcEntity` 类注释里加一句约定（本次未改代码）。
