# 核验报告：渲染/动画与文档一致性

> 核验对象：`docs/reviews/2026-09-25-npc-render-animation-review.md`（D）、
> `docs/reviews/2026-09-25-npc-doc-code-consistency-review.md`（E，渲染/文档部分）
> 核验基线：`24bb701`（`git log -1` = `24bb701ac1712c796f7da82328de72a27cb5ab92`；工作树仅有未跟踪的 review 文档）
> 核验方式：从一手源码/资产独立重新推导；审查者报告仅作待验证输入。未跑 gradle、未改任何源码/资产/既有文档。
> **GeckoLib 版本声明**：项目依赖是 curse 坐标 `geckolib-388172:8350073`，其 `META-INF/neoforge.mods.toml` 声明
> `version="4.9.2"`，jar 内 **0 个 `.java`**、262 个 `.class`。但 **4.9.2 的源码在本工作区可得**：
> `D:\Minecraft\开源模组参考文件\Geckolib` 是 git 检出 `0d9d3ea3`，其 `gradle/libs.versions.toml` 写 `geckolib = "4.9.2"`。
> 本报告凡"文件:行"均出自该 4.9.2 源码树，并用 `javap -p` 对**实际编译产物 4.9.2 jar** 逐一交叉验证了被引用的成员
> （`GeoModel.crashIfBoneMissing()`、`MathParser.setVariable(String,DoubleSupplier)`、
> `MolangQueries.VARIABLES`（`private static final Map`）与 `ACTOR`、`GeoEntityRenderer.doPostRenderCleanup()`）。
> ⇒ **D 声称的"4.9.2 无源码"局限不成立；E 声称"缓存里只有 4.7.5.1/4.7.7 源码、无法核行号"也不成立**（两者都只看了 Gradle 缓存）。
> 仍未核实项已单列于文末。
> 原版源码：`sourcesAndCompiledWithNeoForge_ec9b86340cfa34d24e99524bcb732bfdd44c7f42_output.jar`（1.21.1 + NeoForge 21.1.236）。

## 总体裁决

- **D（渲染/动画）**：严重发现 7 条 —— **机制类 4 条完全成立**（M1、M2、M3、M6），
  **M4/M5 是文档数字/描述问题、成立**，**C1 部分成立**（机制正确、可达性未证实）。
  D 的资产统计表我逐格复算，**只有 `loop` 那一行的两个数字是错的**（见"审查者报告中的错误"）。
  D 的整体可信度高：所有原版行号（`EntityRenderer.java:31/52-70`、`EntityRenderDispatcher.java:168-176`、
  `Entity.java:225/3030-3032`）与 GeckoLib 行号（`GeoQuad.java:26-29`、`AnimationProcessor.java:306-309`、
  `GeoModel.java:91-93/192-227`、`GeoRenderer.java:154-155`、`GeoEntityRenderer.java:51/263-277/324-326`）
  我逐条回源核对，**全部精确命中**。
- **E（文档一致性）**：要求范围内的条目 **全部成立**（C1、C4、C6、M1、M4、M5、M6、m1–m8），
  §八 的 12 个行数、4 个资产大小、226 键我一格不差地复现。**一处判据错误**：C6 说接上播放后"不报错"，
  实际客户端会打一条 `Unable to play unknown soundEvent` 的 WARN（`SoundEngine.java:437`）。
  E 的**系统性偏差是"自认无法核实 GeckoLib"**，导致它把 4 处引用降级为"⚠️未能核实"，其中至少
  `GeoEntityRenderer.java:266-268`、`MolangQueries.java:148-150/286`、`ModelProperties.java:35`、
  `GeoModel` 三个资源方法在 4.9.2 树上是**可核且正确**的（我均已核实）。
- **重定级结果**：无一条达到 P0；P1 两条（无影子、剔除盒），其余全部 P3（纯文档/注释）。
  两位审查者共 **0 条**"按文档去改会改坏东西"的定级意识 —— 只有 D-M3 点出了这个陷阱，我确认为 **D1**。
- **建议采信**：D 的 M1/M2/M3/M4/M5/M6 与 E 的 C1/C4/C6/M1/M4/M5/M6/m1–m8 全部采信（按本报告重定级与修正后）；
  **D-C1 采信其机制描述、丢弃其"跨模组可见污染已发生"的影响推断**；丢弃 D 资产表的 `loop` 行、D-M2 中
  "noCulling = 永远不剔除"的代价描述、D-C1 中"需改 96 个动画 JSON"的范围描述。

## 逐条裁决

### D-C1 `query.head_yaw/head_pitch` 进全局静态表且无清理 —— 跨模组状态污染
- **裁决**：**部分成立**（机制 100% 成立；"可达的可见污染"未证实）
- **我的核实**：
  - (a) **是全局静态，不是每次求值局部**：`MathParser.setVariable(name, supplier)` → `getVariableFor(name).set(value)`
    （`MathParser.java:143-145`）→ `VARIABLES.computeIfAbsent(...)`（`MolangQueries.java:148-150`），
    `VARIABLES` 是 `private static final Map<String, Variable>`（`:118`）；`javap` 确认 `private static final`。
    编译期把名字解析成**同一个** `Variable` 实例（`MathParser.java:432-436` 走 `getVariableFor(string)`），
    运行期 `Variable.get()` 才调用当前 supplier（`Variable.java:17-36`）⇒ 谁最后 `set`，全进程谁生效。
    `VARIABLES` 在 4.9.2 里**没有任何 clear 路径**（全文件只有 `containsKey`/`put`/`computeIfAbsent`）。
  - (b) **清理只清 ACTOR**：`GeoRenderer.defaultRender` 末尾 `doPostRenderCleanup(); MolangQueries.clearActor();`
    （`GeoRenderer.java:154-155`）；`clearActor()` 只把 `ACTOR` 置 null（`MolangQueries.java:181-183`）。
    另：`GeoEntityRenderer.doPostRenderCleanup()` 只 `this.animatable = null;`（`:324-326`）—— **也不碰变量**。
  - (c) **DS 侧前提成立**：`DragonModel.applyMolangQueries` 注册**同名** `query.head_yaw/head_pitch`
    （`DragonModel.java:66-71`），`player == null` 时 `:57-59` **直接 return、不重设**；
    DS 的 5 个动画文件里 `query.head_yaw|head_pitch` 分别出现 1186/1447/1708/1721/1804 次 ⇒ 确实有消费者。
    `DragonEntity.getPlayer()` 是 `@Nullable`（`:460-472`，`playerId == null` 或 `level().getEntity` 取不到即 null）。
  - **但可达性我没证出来（这是我与 D 分歧的核心）**：D 举的例子"无 owner 的 DS 龙（NPC 龙）"未给出处。
    我去查了唯一现成的 null-player 候选 —— 龙皮肤编辑器里的假龙：`FakeClientPlayerUtils.getFakeDragon`
    用匿名子类**覆写了 `getPlayer()` 返回非 null 的 `FakeClientPlayer`**（`:33-62`），且 `DragonEditorScreen`
    渲染前设 `dragon.neckLocked = true`（`:1112-1114`）⇒ DS 会走 `setVariable(...,0)` 分支，**读不到我们的值**。
    ⇒ 只剩"真实 DS 龙仍在渲染、但其 owner 实体已不在客户端 level"这类瞬时/异常态（玩家掉线、跨维度）才可能命中。
  - (d) **同帧多只地黄龙不互相污染，D 的时序判断正确**：`GeoEntityRenderer.actuallyRender` 仅在 `!isReRender` 时
    构造 state 并 `handleAnimations`（`:263-277`）→ `GeoModel.handleAnimations` 先
    `processor.preAnimationSetup(...)`（`:221`）→ `AnimationProcessor.preAnimationSetup` = `updateActor` + `model.applyMolangQueries`
    （`:306-309`）→ 随后才 `tickAnimation`（`:224`）。即**每实体求值前刚刚重设**，`defaultRender` 后段的清理
    不可能影响本实体已完成的求值，也不影响下一个实体（它自己会重设）。
  - (f) 版本问题见文首：**不需要降级为"未核实"**。
- **重定级**：**P2**（机制确定、但触发需要"DS 龙在 owner 缺席状态下被渲染"这类苛刻/异常边界，且后果是别家模型头角错，非崩溃）。
  按规则表若将来实机观察到该状态，则升级为 P0（跨模组污染可见错误）。
- **建议修法是否有效**：**有效，但不是零改动**。
  1. 覆写 `DihuangLoongRenderer#doPostRenderCleanup()` 里 `MathParser.setVariable("query.head_yaw", () -> 0)` /
     `("query.head_pitch", () -> 0)`：**语义正确**（0 正是 `computeIfAbsent` 给未注册变量的默认值），
     且我在时序上验证过**没有"清早了"的副作用**（本实体所有 pass——render layers、`renderFinal` 的命名牌/拴绳——
     都在 `:154` 之前；`reRender()`（`:163-171`）不调清理也不读变量）。
     **必须同时 `super.doPostRenderCleanup()`**：`GeoEntityRenderer:324` 会 `animatable = null`。
     在标准派发路径上 `render()` 也会在 `defaultRender` 返回后置 null（`:197-203`），所以不调 super 不会立刻泄漏；
     但直接调 `defaultRender` 的路径（GeckoLib 文档推荐给自定义渲染入口）就只有这一处清理，**属稳健性要求，不是"当前在漏"**。
  2. D 的"改命名空间需同时改 **96 个动画 JSON**"**范围说错了**：实测只有 **35 个动画**含这两个名字
     （`head_yaw` 210 处 + `head_pitch` 47 处 = 257 处），改 35 个动画即可，不是 96 个。
  3. D 的"`query.` 前缀下 `b` 开头不会与任何内置冲突"是经验说法：4.9.2 内置表是扁平 map，重名即覆盖，
     改名本身确实能同时躲开 DS 与内置语义 —— 结论可用，理由宜写成"避开与任何模组重名"。

### D-M1 整条龙没有影子
- **裁决**：**成立**
- **我的核实**：`protected float shadowRadius;`（`EntityRenderer.java:31`，无初始化 ⇒ **0.0F**）、
  `protected float shadowStrength = 1.0F;`（`:32`）；`EntityRenderDispatcher.java:168-176`
  `if (this.options.entityShadows().get() && this.shouldRenderShadow && !entity.isInvisible()) { float f = entityrenderer.getShadowRadius(entity); if (f > 0.0F) {...renderShadow(..., Math.min(f, 32.0F))} }`
  ⇒ 半径 0 时**不画**。`getShadowRadius` = `protected`（`:216-217`），普通生物走
  `LivingEntityRenderer` 构造器（`:37-40`）；`GeoEntityRenderer extends net.minecraft.client.renderer.entity.EntityRenderer`
  （`:51`，`javap` 同证）⇒ 绕开那条路。Geckolib 全仓 `shadowRadius|getShadowRadius` **0 命中**（我 grep 过全仓）。
  本仓 `shadowRadius` 只有 **1 处**：`client/TornadoRenderer.java:61 this.shadowRadius = 0.0F;`（龙卷风，**故意不投影**）
  ⇒ "地黄龙从未设过"成立，但"全仓零命中"的说法不准确。
- **重定级**：**P1**（观感缺陷、必然触发；不陷地≠有影子，R9 检查确实盖不到）。
- **建议修法是否有效**：**有效但数值缺依据**。`this.shadowRadius = 2.0F;` 能画出来，
  但 2.0 是原版最大惯例（不死马 1.0F）的 2 倍、末影龙的 4 倍 —— 我实测原版惯例：
  末影龙（约 16 格长、最大的原版生物）**0.5F**（`EnderDragonRenderer.java:43`）、玩家/僵尸/骷髅 0.5F、
  蜘蛛 0.8F、船 0.8F、骆驼/羊驼/鱿鱼 0.7F、不死马 **1.0F**（全表见 `LivingEntityRenderer` 子类构造器）。
  建议 **0.7~1.0 起调**（阴影是**以实体原点为心的圆**，再大也不覆盖 9 格龙身，只是为了观感），实机定夺。
  `shadowStrength` 默认 1.0F 确实不用动；覆写 `getShadowRadius(DihuangLoongEntity)` 亦可（protected，可覆写），
  且因为不继承 `LivingEntityRenderer`，**不会**被乘 `entity.getScale()`。

### D-M2 视锥剔除用小盒子
- **裁决**：**成立**
- **我的核实**：`EntityRenderer.shouldRender`（`:52-70`）：先 `livingEntity.shouldRender(...)` 距离门，
  再 `else if (livingEntity.noCulling) return true;`，否则 `AABB aabb = livingEntity.getBoundingBoxForCulling().inflate(0.5); camera.isVisible(aabb)`。
  `Entity.getBoundingBoxForCulling()` 默认 `return this.getBoundingBox();`（`Entity.java:3030-3032`），
  渲染器与实体**都没有覆写**（全仓 grep `getBoundingBoxForCulling|noCulling`：0 命中）；碰撞箱 `sized(1.5F, 2.5F)`
  （`ModEntities.java:70`）。模型朴素包围盒我独立复算（geo 单位 ÷16，含 `inflate`）：
  **32.09×45.64×144.34 单位 = 2.01 × 2.85 × 9.02 格**，z ∈ [−3.82, +5.20] 格 ⇒ 判定盒（1.5+1.0 = 2.5 见方）
  比模型短轴方向少 5 格以上。**D 的数字逐位复现。**
- **重定级**：**P1**（贴屏边缘时头尾"啪"地消失，可达且可见）。
- **建议修法是否有效**：两种都有效，但 **D 对 `noCulling` 的代价描述错了**：
  `noCulling = true` **不是"永远不剔除"** —— 距离门 `Entity.shouldRender` 在它**之前**（`:53`），
  按 `getBoundingBox().getSize()*64*viewScale`（`Entity.java:1692-1699`，本实体约 210 格）仍然生效。
  所以 `noCulling = true` 只关掉**视锥**判定，且原版**自己就这么干**：`EnderDragon.java:104 this.noCulling = true;`
  ⇒ 对 340 立方体的单只巨兽，这是最省事且与原版一致的做法。
  覆写 `getBoundingBoxForCulling()`（放在 `DihuangLoongEntity`，或若想通用放 `NpcEntity`）粒度更细，
  但**注意它只影响视锥判定**（距离门用的是 `getBoundingBox()`），且会同时影响拴绳判定（`:73-77`）。二者择一即可。

### D-M3 贴图 512×512 vs geo 声明 256×256 —— 且"照文档改"会毁模型
- **裁决**：**成立**（并且 D 的破坏性警告**完全正确** —— 这是本次唯一确认的 **D1**）
- **我的核实**：
  - PNG IHDR 实测（自读字节）：签名字节 `89 50 4E 47 0D 0A 1A 0A`、`IHDR`、**width=512 height=512
    bitDepth=8 colorType=6（RGBA）interlace=0**，156351 B。
  - **geo 的声明值（不引用报告）**：`description.texture_width = 256`、`texture_height = 256`（另 `visible_bounds_width = 12`）。
  - **UV 范围（自算）**：340 个立方体共 2040 个面，其中 **353 个面带负 `uv_size`（镜像面）**；
    按每面 `[min(u,u+us), max(u,u+us)]` 求并集 ⇒ **u ∈ [0, 256]、v ∈ [0, 256]，越界面 0 个**，
    与 D 的结论一致（我第一次只取 `u` 与 `u+us` 未排序，得到 u 最小 −16，属我自己的口径错、已修正）。
  - **GeckoLib 按 geo 声明的尺寸归一化**：`BakedModelFactory.java:177` 传 `(float)properties.textureWidth()`
    （= geo 的 `texture_width`，`ModelProperties.java:37-38`）→ `GeoQuad.build` 里
    `float uWidth = (u + uSize) / texWidth; u /= texWidth;`（`GeoQuad.java:26-29`）⇒ **与 PNG 实际像素尺寸无关**。
    ⇒ 现在 [0,256] 的 UV 除以 256 得到 [0,1]，**采样整张 512 画布**，这就是当前能正确渲染的原因；
    **把 geo 的 `texture_width/height` 改成 512 会让所有 UV 分数减半 ⇒ 只采样左上 1/4，整条龙花掉。D 的警告成立。**
  - **旁证（D 没做）**：同一个美术的 DS 版本资产 `DiHuang-Loong\src\main\resources\assets\dragonsurvival\geo\dihuang_loong.geo.json`
    同样声明 `texture_width/height = 256`（UV 0..234），而配套贴图同样是 **512×512** —— 这套"geo 声明 256 / 画布 512"
    是作者一贯且**已出货**的配置，反向印证"不要动 geo"。
  - **我的像素判据（复现 D 的排除法）**：横向相邻像素完全相同的比例 **0.6902**、纵向 **0.6948**；
    偶对齐 2×2 块四像素全同的比例 **0.5430** —— 若为 nearest 2× 放大，这两个指标都应为 **1.0000** ⇒ **排除 nearest 2×**；
    四象限不透明占比 TL 47.5% / TR 55.9% / BL 63.0% / BR 56.2%（总 55.7%）⇒ 内容铺满整张 512，
    **排除"256 版图整块塞在某一角"**（那会让 UV 映射错位）。结论与 D 相同；我的数值与 D 的（0.666/0.667、49/61/68/62%）
    有 1~5 个百分点的差异（口径差异），**不改变结论**。
    "平滑重采样放大"还是"在 512 画布上按 2× 重绘"仍**静态不可区分** —— 但**这一点不影响结论**：
    只要内容铺满画布，按 geo 声明的 256 归一化就是正确的映射。
- **重定级**：**P3 + D1**（当前无运行时影响；但文档的"256"会让后来者去改 geo，属于"按文档改会改坏东西"）。
- **建议修法是否有效**：**有效且必须按 D 的方向**：改注释/文档为"512×512（在 256 空间中绘制的 2× 画布）"，
  并显式加一句"**geo 的 `texture_width/height` 必须保持 256，不要跟着 PNG 改**"。
  D 提的"重导一张 256 贴图"是可选方案，但**当前 4 个引用位置一致（256/256/256 空间 + 512 画布）**
  且 DS 侧同款，无必要动资产。

### D-M4 文档 §4.2 第 2 条描述了不存在的实现（= E-C4，合并）
- **裁决**：**成立**
- **我的核实**：`DihuangLoongModel.java` 全文 118 行，成员只有：`MODEL/TEXTURE/ANIMATION` 三个
  `static final ResourceLocation`、`HEAD_YAW_SIGN`、三个单参 getter（`:57-70`）、
  `applyMolangQueries`（`:101-117`，内含一次 `super` 调用）、类 javadoc。**无** `renderLayers` / `rootBone` / 骨骼相关代码。
  文档 `docs/NPC系统总设计.md:374-378` 第 2 条"提取模型自身的 `renderLayers` 与 root 骨骼"**不存在**。
  背景（供改写用）：`renderLayers` 是**渲染器侧**的容器（`GeoEntityRenderer.java:52 protected final GeoRenderLayersContainer<T> renderLayers`），
  骨骼层级（含 `Magic`/`Dragon` 两根根骨骼）由 GeckoLib 的 `BakedModelFactory.constructGeoModel` 遍历
  `geometryTree.topLevelBones()` 从 `.geo.json` 自行加载（`BakedModelFactory.java:133-141`）—— 模型类两件事都不参与。
- **重定级**：**P3 + D2**
- **建议修法是否有效**：D 的"删掉该条，或改写为'声明三条资源路径 + 注册两个 Molang 变量'"方向正确但**不够准确**。
  我按源码写的准确描述（建议直接替换 `:374-378`）：
  > `DihuangLoongModel` 只做两件事：① 返回三条资源路径（`getModelResource`/`getTextureResource`/`getAnimationResource`，
  > 各返回一个 `static final ResourceLocation`）；② 在 `applyMolangQueries` 里用 `MathParser.setVariable` 注册
  > `query.head_yaw`/`query.head_pitch` 两个**全局** Molang 变量。骨骼层级（含 `Magic`/`Dragon` 两根根骨骼）
  > 由 GeckoLib 从 `.geo.json` 自行加载，渲染层容器在**渲染器**一侧，本类都不介入。

### D-M5 含 `head_yaw` 的动画是 35 个，文档/注释写 34
- **裁决**：**成立**，且**错处比 D 报的多**
- **我的核实**（口径：`ConvertFrom-Json` 后对每个动画的 `bones` 子树整体做 JSON 序列化再用正则匹配，
  **大小写不敏感**，不区分是否含 `head_pitch` —— 两者都测过，命中数相同）：
  96 个动画中 **35 个**含 `query.head_yaw`；`head_yaw` 共 **210 处**（=35×6），
  分布在 **5 根**骨骼：`Head-Molang`、`Neck-Molang`、`NeckB/C/D-Molang`；
  含 `query.head_pitch` 的动画同样是 **35 个**（47 处）。
  错处实测 **5 处**（D 只报了 2 处）：`src/.../DihuangLoongModel.java:75`、
  `docs/plans/2026-09-21-dihuang-loong-npc-design.md:186`、
  `docs/plans/2026-09-25-dihuang-loong-ai-design.md:132`、
  `docs/plans/2026-09-25-npc-base-class-plan.md:660`、
  `docs/plans/2026-09-25-dihuang-loong-ai-plan.md:385`（后三处是历史计划文档里的代码块复制）。
  **`docs/NPC系统总设计.md` 不含这个数字**（§4.2 未给计数）。
- **重定级**：**P3 + D2**
- **建议修法是否有效**：有效（改 35 并补列 `NeckB/C/D-Molang`）。历史文档按约定不改，登记即可。

### D-M6 注释引用了不存在的 `GeoModel#shouldCrashOnMissingBone()`
- **裁决**：**成立**
- **我的核实**：4.9.2 源码 `GeoModel.java:91-93 public boolean crashIfBoneMissing() { return false; }`，
  调用点 `:224`；`javap -p` 在**实际 4.9.2 编译产物**里只看到 `public boolean crashIfBoneMissing();`，
  **没有** `shouldCrashOnMissingBone`。全 Geckolib 仓 grep 该名 **0 命中**（"优雅忽略"本身成立：
  `AnimationController.java` 里 `bone == null` 时按 `crashWhenCantFindBone` 决定抛或 continue）。
- **重定级**：**P3 + D2**
- **建议修法是否有效**：有效。**需要改的文件:行（实测 3 处，D 只报了 2 处）**：
  ① `src/main/java/com/zonlong/beloong/client/model/DihuangLoongModel.java:27`；
  ② `docs/plans/2026-09-21-dihuang-loong-npc-design.md:240`；
  ③ 同文件 `:77`。统一改成 `crashIfBoneMissing()`（顺带把 `:77`/`:240` 的行号 `GeoModel.java:86-90` 改为 `:86-93`）。

### E-C1 状态机表与"末页"行为不符
- **裁决**：**成立**（上级给的两条结论我都独立复现）
- **我的核实**（代码）：
  - `advanceOrFinish()`（`NpcDialogueScreen.java:226-232`）：`pageIndex + 1 < pages.size()` → `WAIT_CLICK`；
    **否则 `showOptions()`**（`:230`）。
  - `TYPING` 自然播完（`tick()` `:178-183`）与中途点击（`mouseClicked` `:199-203`）**都**调 `advanceOrFinish()`。
  - `WAIT_CLICK` 分支（`:205-209`）**只有** `pageIndex++; loadPage();` —— **没有"已是最后一页"判断**。
  - `State.WAIT_CLICK` 全文只被 `:228` 赋值 ⇒ **末页永远不进 `WAIT_CLICK`**；箭头渲染有 `if (this.state != State.WAIT_CLICK) return;`（`:347`）⇒ 箭头只在中间页出现，与 `:219-225` 的 javadoc 自述一致。
  - `docs/NPC系统总设计.md:587`（"打完 → `WAIT_CLICK`"）与 `:588`（"`WAIT_CLICK` … 已是最后一页 → `SHOWING_OPTIONS`"）**均与代码不符**。
- **重定级**：**P3 + D2**（纯文档/注释；代码是正确且经过实机取舍的）。
- **建议修法是否有效**：有效。**需要改的全部位置**：
  1. `docs/NPC系统总设计.md:587` → "打完：**非末页 → `WAIT_CLICK`；末页 → `SHOWING_OPTIONS`**"；
  2. `docs/NPC系统总设计.md:588` → "点击 → 下一页（`WAIT_CLICK` 只由非末页进入，不存在'末页'分支）"；
  3. 类 javadoc `src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java:26-34` 的状态图：
     `:28`（"显示完 ⇒ WAIT_CLICK"）与 `:29`（"点击 ⇒ … ⇒ WAIT_CLICK"）都要标明"**非末页**"，
     `:30-31` 要把"点击且末页 → SHOWING_OPTIONS"从 `WAIT_CLICK` 挪到 `TYPING` 的出口上；
  4. `docs/plans/2026-09-20-npc-dialogue-design.md:141-142`（TYPING 出口）与 `:144-146`
     （`WAIT_CLICK` 下的"是最后一页"分支）同样要改；`:139-149` 是 §3.4 整段状态机。
  **不需要改**的是 `NpcDialogueScreen.java:219-225` 的方法 javadoc（它写的就是"最后一页直接弹出选项"，是对的）。

### E-C4（= D-M4）
见上，合并处理，不重复计数。

### E-C6 `iron_golem.json` 的 `sound` 事件在 `sounds.json` 里不存在
- **裁决**：**成立**（影响判断需修正）
- **我的核实**：`assets/beloong/sounds.json`（547 B）**只有 3 个键**：
  `block.beloong.loong_palace_portal.ambient` / `.travel` / `.trigger`；**没有任何 `beloong.dialogue.*`**。
  `data/beloong/beloong/npc_dialogue/iron_golem.json:8` = `"sound": "beloong:dialogue.iron_golem.1"`。
  解析侧**不会**失败：字段是 `ResourceLocation.CODEC.optionalFieldOf("sound")`（`NpcDialogueEntry.java:71-74`），
  `beloong:dialogue.iron_golem.1` 是合法 `ResourceLocation`；下发走字符串 codec（`:104`）；
  客户端侧全项目**没有任何读取/播放该字段的代码**（dialogue 包 grep `sound` 仅 4 处，全是定义/编解码）。
- **真实影响（我的判据，与 E 不同）**：当前 **P3**（无运行时症状）。
  **将来接上播放时**：`Level#playSound(...SoundEvent...)` 不会校验注册表，服务端照发包；
  **客户端** `SoundEngine.play` 会走到 `weighedsoundevents == null` 分支并打一条
  **`LOGGER.warn(MARKER, "Unable to play unknown soundEvent: {}", location)`**（`SoundEngine.java:433-437`）
  ⇒ **不是"静默且不报错"，而是"没声音 + 一条 WARN 日志"**（对整合包作者可查）。
  所以 E 的说法"永远没声音且不报错"**只对了一半**；严重度：接播放后是**明确的音频缺失（P2）**，当前是 **P3**。
- **建议修法是否有效**：E 的两条（补 `sounds.json` 事件 / 把样例改成预留字段并在 §5.2 注明）都可行；
  我倾向"**补一个占位事件**"，因为决策 41（`docs/NPC系统总设计.md:884`）明确说"将来只需加一行播放调用"，
  那样才真的只需加一行。另需同步 `:933`（§10.2 那一行"只解析不播放"宜补"且样例引用的事件尚未定义"）。

### E-M1 `TEXT_TOP` 的排版方向写反
- **裁决**：**成立**
- **我的核实**：`NpcDialogueScreen.java:48-55` 的注释明写"正文**首行顶部**……锚定顶部后，无论几行都只往下长"；
  渲染侧 `int y = (int)(this.height * TEXT_TOP);`（`:330`）后每行 `y += lineHeight`（`:340`）⇒ **自上而下**。
  文档 `docs/NPC系统总设计.md:598` 写"正文起始（**自下而上**排版）"。
- **重定级**：**P3 + D3**（措辞错误；虽无运行时影响，但会让人"往反方向调参"，故必须改）
- **建议修法是否有效**：有效。建议措辞："正文**首行顶部**（占屏高；锚定顶部后逐行**向下**生长，避免多行时压到装饰线）"。

### E-M6（= D-M3）
合并处理。E 补充的 `DihuangLoongModel.java:23` 我确认无误，且与 `docs/NPC系统总设计.md:364`/`:747`、
`docs/plans/2026-09-21-dihuang-loong-npc-design.md:56`/`:58` 是**同一处错误的 4 个副本**（比 E 报的多一处：plan `:56`）。

### E 的其余文档条目（逐条）
- **§八 各项实测值**：**全部复现，E 正确**。12 个行数我逐个 `(Get-Content).Count`：
  373 / 49 / 47 / 164 / 127 / 77 / 85 / 460 / 147 / 27 / 118 / 121 —— 与 `docs/NPC系统总设计.md:725-736` **逐格一致**；
  4 个大小：geo 200443 B（196 KB）、anim 488150 B（477 KB）、png 156351 B（153 KB）、iron_golem.json 297 B —— 与 `:745-747`、`:753` 一致；
  226/226 键、`Compare-Object` 差集 0 —— 与 `:760` 一致。**唯一错的是贴图尺寸那一列**（见 D-M3）。
- **"对话系统 655 行"无法复现**：**成立**。`dialogue` 包 4 文件 = 164+127+77+85 = **453**；
  加界面两文件（460+147）＝ **1060**；655 与两者都不符（也不是任何自然组合；
  最接近的 460+147+47=654）。文档位置 `docs/NPC系统总设计.md:738`（`> 比例值得注意：…对话系统 655 行…`）。**P3 + D2**。
- **§十 引用了不存在的 T10**：**成立**。`docs/NPC系统总设计.md:975` 写"T1–T9 已完成并验证；**T10–T12 见 §10.2**"，
  而 §10.2（`:928-934`）**只有 T11（`:930`）、T12（`:931`）**；T10 实际在
  `docs/plans/2026-09-25-npc-vanilla-ai-plan.md:71 ### T10 R9 标定：模型 y 偏移`，
  其结论落在 `docs/NPC系统总设计.md:921`（§10.1 R9 行）。**P3 + D2**。
- **地黄龙"50 行 vs 49 行"**：**成立**。`DihuangLoongEntity.java` = **49** 行；
  `docs/NPC系统总设计.md:355`"不到 50 行"（成立）、`:726`"49"（对）、**`:783`"只有 50 行"**、**`:934`"'基类能否只剩 50 行'"**（两处错）。**P3 + D2**。
- **E-m1（行数写法）**：同上，E 正确（`:355`/`:726` OK，`:783`/`:934` 需改）。
- **E-m2（goal 速度过度概括）**：**成立**，我抽验原版：`IronGolem.java:66` Melee 1.0、
  `Dolphin.java:156` Melee **1.2**、`Rabbit.java:357` Melee **1.4**、`Cow.java:47` FollowParent 1.25、
  `Strider.java:161` Panic **1.65** ⇒ 文档 `:260-261`"原版 goal 也是这么用的：`MeleeAttackGoal` 给 1.0、
  `FollowParentGoal`/`TemptGoal` 给 1.25、`PanicGoal` 给 2.0"是把**个例当成惯例**。**P3 + D3**。
- **E-m3（`doStuckDetection` 只在 <1.0 时平方）**：**成立**，`PathNavigation.java:312` 逐字一致
  （引用行号本身正确）；文档 `:257-258` 隐含"总是平方"。**P3 + D3**。
- **E-m4（受理流程图漏一步）**：**成立**，`NpcDialogueHandler.java:67-69` 确有
  `if (!(player instanceof ServerPlayer serverPlayer)) return;`。**P3 + D2**。
- **E-m5（"三个公开方法"与 5 个签名易混）**：**成立**。`NpcEntity.java:280-308` 的
  `walkTo`/`attack`/`stopAction` 都有 `level().isClientSide()` 守卫；`:321` `isAttackCommandActive()`、
  `:326` `clearAttackCommand()` 是 **public 且无守卫**（供 `NpcAttackGoal`）；`clearMotionCommands` 为 **private**（`:316`）。**P3 + D3**。
- **E-m6（空 `pages` 被整文件丢弃）**：**成立**，`NpcDialogueLoader.java:99-104` 在 `ifSuccess` 里
  打印 `npc dialogue file '{}' declares no pages, ignored`（ERROR）并跳过注册；JSON 解析本身成功。**P3 + D2**。
- **E-m7（选项按钮视觉规格未写进文档）**：**成立**，`NpcDialogueOptionButton.java:28-49` 实测常量
  `NORMAL_RGB=0x1A1F26`、`HOVER_RGB=0xC8A05A`、`BASE_ALPHA=0xC0`、`FADE_START=0.55F`、`HOVER_STEP=0.25F`、
  `PADDING_LEFT=7`/`ICON_SIZE=9`/`ICON_GAP=5`，`extends AbstractWidget`（`:25`）及理由（`:16-17`）。**P3 + D2**。
- **E-m8（`yBodyRot` 初值来自生成包）**：**成立**，`LivingEntity.java:3717 this.yBodyRot = packet.getYHeadRot();`
  （随后 `:3719-3720` 同步 `…O`），`recreateFromPacket` 内。文档 `:224-225` 的结论
  （"不参与逐 tick 网络同步"）正确，缺的只是这一句初值说明。**P3 + D2**。

## 资产实测表

| 指标 | 我的实测值 | D 的实测值 | E 的实测值 | 文档值 | 以谁为准 |
|---|---|---|---|---|---|
| 动画总数 | **96** | 96 | 96 | 96（§4.1）/「约 100」(plan:57) | 我/D/E（文档 §4.1 对，plan 是约数） |
| 骨骼数 | **145** | 145 | 145 | 145 | 全部一致 |
| 立方体数 | **340** | 340 | 340 | 340 | 全部一致 |
| 根骨骼 | **2：`Magic`、`Dragon`** | 2 | 2 | 2 | 全部一致 |
| 重名/悬空 parent | **0 / 0** | 0 / 0 | 未报 | — | 我/D |
| 含 `head_yaw` 的动画数 | **35**（210 处＝35×6，5 根骨骼；`head_pitch` 同为 35 个/47 处） | 35 | 未报 | 34（`DihuangLoongModel.java:75`、plan:186 等 5 处） | **我/D**；文档错（§4.2 未给数字） |
| geo 声明 `texture_width/height` | **256 / 256**（`visible_bounds_width=12`） | 256 / 256 | 未报（只说文档写 256） | 文档未写 geo 声明值 | 我/D |
| PNG 实际尺寸 | **512×512**，bitDepth 8，colorType 6，156351 B | 512×512，156351 B | 512×512（IHDR） | 256×256（§4.1:364 / §八:747 / 模型注释:23） | **我/D/E**；文档错 → 归入 D1 |
| UV 范围（含镜像面端点） | **u ∈ [0,256]，v ∈ [0,256]**；353/2040 面带负 `uv_size`；越界 0 | u∈[0,256]，v∈[0,256]，越界 0 | 未报 | 未写 | 我/D |
| `run` 缺失骨骼 | **`Drip1`,`Drip2`,`Drip3`** | 同 | 同 | 同（§4.1:367、§10.2:932） | 全部一致 |
| 引用缺失骨骼的动画数 / 种类数 | **56 / 37**（`Drip1-4`、`Mustache*`×32、`WhiskerLeft/Right`） | 56 / 37 | 未报 | 未写 | 我/D |
| 从未被任何动画引用的模型骨骼 | **57** | 57 | 未报 | 未写 | 我/D |
| `idle`/`walk`/`run` 骨骼数·时长·缺失 | 32/4.75s、63/1.375s、77/1.0s，缺失 0/0/3 | 同 | 同 | 同 | 全部一致 |
| `loop` 字段分布 | **`true`×80、`"hold_on_last_frame"`×4（`xl`,`qf`,`xl4`,`qf2`）、无字段×12** | "true 84、无字段 12、hold 1（qf2）" | 未报 | 未写 | **我**；D 此行两个数字都错 |
| 模型朴素包围盒 | **2.01 × 2.85 × 9.02 格**（原始单位 32.09×45.64×144.34；z ∈ [−3.82,+5.20]） | 2.01×2.85×9.02，z −3.8~+5.2 | 未报 | 约 9 格长（§4.3:409） | 我/D |
| 贴图来源一致性 | geo/anim/png **三份 SHA256 与 `D:\Minecraft\Models\Geckolib\dihuang_loong_npc\` 全同** | 同（三份） | 未报 | "原样移植"（模型注释:14） | 我/D |

## 合并后的去重清单

| 编号 | 一句话 | 级别 | 涉及文件 |
|---|---|---|---|
| R1 | `query.head_yaw/head_pitch` 写进 GeckoLib 全局静态变量表且渲染后不清，与 DS 同名 | **P2** | `DihuangLoongModel.java:114-116`；可选修 `DihuangLoongRenderer.java` |
| R2 | 地黄龙完全没有影子（`shadowRadius` 默认 0） | **P1** | `DihuangLoongRenderer.java:22-26` |
| R3 | 视锥剔除用 1.5×2.5 碰撞箱，模型长 9 格 ⇒ 头尾"啪"地消失 | **P1** | `DihuangLoongEntity.java`（或 `NpcEntity.java`）；`ModEntities.java:70` 是现状 |
| R4 | 贴图是 512×512 而文档/注释写 256×256；**照文档改 geo 会毁模型** | **P3 + D1** | `NPC系统总设计.md:364,747`；`DihuangLoongModel.java:23`；`plan-2026-09-21:56,58` |
| R5 | §4.2 第 2 条"提取 renderLayers 与 root 骨骼"不存在（模型只做两件事） | **P3 + D2** | `NPC系统总设计.md:374-378` |
| R6 | 含 `head_yaw` 的动画是 35 个而非 34 | **P3 + D2** | `DihuangLoongModel.java:75`；4 份 plan 文档（`:186`/ai-design`:132`/base-plan`:660`/ai-plan`:385`） |
| R7 | 注释引用的 `GeoModel#shouldCrashOnMissingBone()` 不存在，真名 `crashIfBoneMissing()` | **P3 + D2** | `DihuangLoongModel.java:27`；`plan-2026-09-21:77,240` |
| R8 | 对话状态机：末页从 `TYPING` 直接 `showOptions()`，从不进 `WAIT_CLICK` | **P3 + D2** | `NPC系统总设计.md:587-588`；`NpcDialogueScreen.java:26-34`；`plan-2026-09-20:141-146` |
| R9 | `iron_golem.json` 的 `sound` 事件未在 `sounds.json` 定义（接线后静音 + WARN） | **P3**（接线后 P2） **+ D2** | `sounds.json`；`iron_golem.json:8`；`NPC系统总设计.md:884,933` |
| R10 | `TEXT_TOP` 写成"自下而上"，实为锚定首行顶部向下生长 | **P3 + D3** | `NPC系统总设计.md:598` |
| R11 | 地黄龙行数"50 行"，实测 49 行 | **P3 + D2** | `NPC系统总设计.md:783,934` |
| R12 | §八"对话系统 655 行"复现不出（453 或 1060） | **P3 + D2** | `NPC系统总设计.md:738` |
| R13 | §十一 说"T10–T12 见 §10.2"，§10.2 只有 T11/T12 | **P3 + D2** | `NPC系统总设计.md:975` 对 `:928-934` |
| R14 | "原版 goal 也是这么用的"把个例当惯例（Melee 有 1.0/1.2/1.4…） | **P3 + D3** | `NPC系统总设计.md:260-261` |
| R15 | `doStuckDetection` 只在速度 <1.0 时平方，文档未写这一半 | **P3 + D3** | `NPC系统总设计.md:257-258` |
| R16 | §5.4 流程图漏 `instanceof ServerPlayer` 一步 | **P3 + D2** | `NPC系统总设计.md:522-530` |
| R17 | "三个公开方法"与列出的 5 个签名易混（两个查询/清理方法无守卫） | **P3 + D3** | `NPC系统总设计.md:297-306` |
| R18 | 未提"空 `pages` 被整文件丢弃并打 ERROR" | **P3 + D2** | `NPC系统总设计.md:451-457` |
| R19 | §5.5 完全没写选项按钮的视觉规格 | **P3 + D2** | `NPC系统总设计.md:581` |
| R20 | `yBodyRot` 客户端初值来自生成包这件事未写 | **P3 + D2** | `NPC系统总设计.md:224-225` |

## 审查者报告中的错误

1. **D 的资产表 `loop` 一行两个数字都错**：D 写"`loop:true` **84** 个，无 `loop` 字段 12 个，
   `hold_on_last_frame` **1** 个（`qf2`）"。实测：`true` **80**、`"hold_on_last_frame"`（字符串值）**4**（`xl`/`qf`/`xl4`/`qf2`）、
   无字段 12。D 把"有 `loop` 字段的动画数（84）"当成了"值为 true 的个数"。
2. **D-M2 对 `noCulling` 代价的描述错误**：写"代价是永远不剔除"。
   `EntityRenderer.shouldRender:53` 的距离门在 `noCulling` 之前，**仍然生效**（约 210 格）。
3. **D-C1 的两个推断越出证据**：① "无 owner 的 DS 龙（NPC 龙）"未给出一手出处，
   而我实测**最可能的 null-player 路径（皮肤编辑器假龙）反而不成立**（`getPlayer()` 被覆写为非 null + `neckLocked=true`）；
   ② "改命名空间需同时改 96 个动画 JSON" —— 实际只需改 **35 个动画**（257 处）。
4. **D 的"GeckoLib 4.9.2 无源码"局限不成立**（推论因此无需降级）。
5. **D-M3 的像素数值与我的实测有 1~5 个百分点差异**（0.666/0.667 对 0.690/0.695；象限占比亦略不同），
   结论不受影响，但数值不宜作为定论引用（口径未公开）。
6. **E 的 C6 影响判据错**："永远没声音且**不报错**" —— 实际客户端会打
   `Unable to play unknown soundEvent` 的 WARN（`SoundEngine.java:433-437`），不是完全静默。
7. **E 系统性偏差**：把 GeckoLib 全部引用降级为"⚠️未能核实"（理由是没有 sources）。
   实际 `D:\Minecraft\开源模组参考文件\Geckolib` 就是 4.9.2 源码树，
   其引用中至少 `GeoEntityRenderer.java:266-268`、`MolangQueries.java:148-150`/`:286`、`ModelProperties.java:35`
   在 4.9.2 上**逐行正确**（E 用 4.7.7 的行号比对，反而把对的判成存疑）。
8. **两位审查者的定级普遍偏高**：D 把 C1 列为 Critical（实为 P2），D-M1/M2/M3/M4/M6 与 E 的 6 条 Critical/Major
   里有 8 条是**纯文档/注释**（P3）。A 里唯一被正确定级的破坏性风险是 D-M3，但 D 没有用"改法危险"的标签表达出来。
9. 小错（不影响结论）：D 说"全仓 `shadowRadius` 零命中"——本仓 `TornadoRenderer.java:61` 有一处（故意 0.0F）。

## 我无法静态确认的

1. **D-C1 是否真的产生可见污染**：需要实机验证"DS 龙在 owner 缺席（掉线/跨维度）时仍在客户端被渲染"这一状态是否存在
   （或在 DS 编辑器之外的某个 GUI/预览里出现 `player == null` 的龙）。静态上我只确认了机制与 DS 侧不重设的事实。
2. **`shadowRadius` / 剔除盒的"到底调多少"**：原版惯例给了上界（≤1.0F）与先例（末影龙 0.5F + `noCulling=true`），
   但最终数值要对着 9 格体型实机看。
3. **512×512 贴图是"平滑重采样放大"还是"在 512 画布上按 2× 重绘"**：像素检验只能排除 nearest 2× 与"版图塞角落"。
   本机不存在 256 版原图可作对照（`DiHuang-Loong` 侧同款贴图**也是 512×512**，无法逐像素比）。
   **这一项不影响"不要改 geo 的 `texture_width`"的结论。**
4. **`HEAD_YAW_SIGN = -1` 的观感结论**：我只核实了 GeckoLib 侧支撑事实
   （`AnimationController.java:747-761` 对非常量且在 X/Y 轴取负；`BakedModelFactory.java:150` 对静态旋转 X/Y 取负、Z 不取负），
   "模型本地前方 = −Z"与实机左右方向仍需实机复验（沿用 D 的结论，未独立重测）。
5. **78 656 个轴关键帧的常驻内存量级**：D 的 10–20 MB 是对象数推算，本次无实测（未跑构建、未进游戏）。
6. **不在本次要求清单内的项我未逐条复核**：D 的 Minor（m1–m5）/Nit（N1–N5）、E 的 C2/C3/C5
   （我顺手复核到 C2 的 `Goal#adjustedTickDelay` 减半**成立**、C3 的三处行号
   `LivingEntity.java:1143`/`Entity.java:1854`/`Mob.java:461` **全部正确**，其余未复核）。
