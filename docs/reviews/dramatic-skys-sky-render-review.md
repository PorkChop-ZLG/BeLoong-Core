# BeLoong Core 0.8.2 之后天空渲染代码审查报告

## 审查信息

- 审查对象：`0c327b7`（0.8.2）之后的天空渲染改动
- Git 提交：
  - `a700615` 自定义天空盒，准备阶段
  - `ec30ad5` 完成简易的星空天空盒，但是效果不符合预期
  - `0841845` 全新的天空盒，初步完成昼夜交替
  - `eaa31eb` 改了无数版的天空渲染，依旧无法平滑过渡
  - `647f866` 修复了天空白天黑夜的过渡效果
- 额外包含：
  - 工作区未提交改动：`DramaticSkyRenderer.java` 删除 `renderBaseSky`
- 主要代码文件：
  - `BeLoongCore.java`
  - `BeLoongCoreClient.java`
  - `client/LoongPalaceFogHandler.java`
  - `client/LoongPalaceSkyTickHandler.java`
  - `client/sky/CubeAtlasSkyRenderer.java`
  - `client/sky/DramaticSkyRenderer.java`
  - `client/sky/LoongPalaceSkyEffects.java`
  - `client/sky/SkyAlphaSource.java`
  - `client/sky/SkyBlendMode.java`
  - `client/sky/SkyDecorationsRenderer.java`
  - `client/sky/SkyLayerConfig.java`
  - `client/sky/SkyRotation.java`
  - `data/beloong/dimension_type/loong_palace.json`
- 审查方式：静态阅读 + 对照以下材料
  - 项目文档：
    - `docs/龙宫DramaticSkys完整迁移计划.md`
    - `docs/龙宫DramaticSkys天空迁移计划.md`
    - `docs/天空渲染对照NeoForgeSkyboxes分析.md`
  - 参考模组：`D:\Minecraft\NeoforgeSkyboxes-main`
  - 迁移资源包：`D:\Minecraft\optifine-master\Dramatic Skys`
  - NeoForge / Minecraft 源码：从 Gradle 缓存中的 `neoforge-21.1.236-sources.jar` 与客户端资源提取核对
- 未执行：构建、实际进游戏视觉/性能验证

---

## 结论摘要

整体实现与项目文档的“九层天空 + 平滑过渡 + 客户端隐藏天气”设计基本一致，UV 映射和 fade 公式也与 Celestial 配置吻合，未发现会直接导致崩溃或完全无法渲染的严重问题。

主要问题集中在：

1. 天空渲染的 **draw call 数量明显偏多**，是当前实现中最大的性能风险。
2. 大量 `[SkyDebug]` INFO 日志残留，龙宫维度内几乎每 tick 都会刷日志。
3. 自定义 `renderSky` 没有处理原版“不应绘制天空”的场景（失明/黑暗/水中/岩浆中等）。
4. 代码与 Dramatic Skys 原始 OptiFine/Celestial 配置存在若干偏差，需要确认“以文档为准”还是“以资源包为准”。
5. 少量生命周期、兼容性、代码质量问题。

---

## 一、与文档/参考实现的对照结果

| 检查项 | 结果 | 说明 |
|---|---|---|
| 9 层图层顺序 | ✅ 一致 | 与 `sky.json` / 完整迁移计划一致 |
| 3×2 单图集 UV | ✅ 一致 | 与 Celestial JSON 24 顶点表逐项一致 |
| fade 公式 | ✅ 基本一致 | 与 `variables.json` 在整数 tick 下等价 |
| SCREEN alpha 修复 | ✅ 已实现 | `SkyBlendMode.SCREEN` 将 alpha 写入 RGB |
| 平滑过渡模型 | ✅ 已实现 | display alpha 逐 tick 趋近目标，时间跳变使用长过渡 |
| 客户端隐藏云/雨雪/雨声 | ✅ 符合 | 通过 `DimensionSpecialEffects` 返回 `true` 跳过原版逻辑 |
| 禁用雾 | ⚠️ 有副作用 | 会连同液体/状态雾一起取消，需确认是否有意 |
| 无新增原版 Mixin | ✅ 符合 | 未在 mixins 中新增 Minecraft 类 Mixin |
| 天灾维度不受影响 | ✅ 符合 | 所有处理器都限定 `beloong:loong_palace` |
| 与 Dramatic Skys 原始配置完全一致 | ❌ 不一致 | 混合模式、fade 区间、旋转细节存在差异，见问题 4 |
| 与 NeoForgeSkyboxes 的渲染健壮性一致 | ❌ 不一致 | 未处理 camera obscured / blindness / darkness，见问题 3 |

---

## 二、问题清单

### 🟠 问题 1：每帧 draw call 过多，天空渲染性能风险较高

**位置**

- `CubeAtlasSkyRenderer.java:102-113`
- `DramaticSkyRenderer.java:121-135`

**描述**

`CubeAtlasSkyRenderer.render` 对 6 个面分别执行：

```java
BufferBuilder bufferBuilder = tesselator.begin(...);
// 添加 4 个顶点
BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
```

也就是每个图层 6 次 draw call。`DramaticSkyRenderer` 有 9 个图层，即使部分图层 alpha 为 0 会跳过，在黄昏/黎明等过渡时段仍可能同时绘制 4~6 个图层，每帧约 **24~54 次 draw call**，另外 `SkyDecorationsRenderer` 还有 2 次。

**参考实现**

`NeoforgeSkyboxes` 的 `SingleSpriteSquareTexturedSkybox.renderSkybox` 把 6 个面的 24 个顶点放进同一个 `BufferBuilder`，一次 `BufferUploader.drawWithShader` 完成：

```java
// SingleSpriteSquareTexturedSkybox.java:66-100
BufferBuilder bufferBuilder = Tesselator.getInstance().begin(...);
for (int i = 0; i < 6; ++i) {
    // 只向同一个 buffer 添加顶点
}
BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
```

**建议**

- 在 `CubeAtlasSkyRenderer` 中一次性写入 6 个面，每个图层只提交 1 次。
- 将 `RenderSystem.setShader` / `disableCull` / `depthMask(false)` 等状态提升到图层循环外，避免每层重复设置。
- 如果后续图层数量继续增加，可考虑预构建 `VertexBuffer` 静态网格。

---

### 🟠 问题 2：调试日志严重刷屏

**位置**

- `LoongPalaceSkyTickHandler.java:31-39`
- `DramaticSkyRenderer.java:89-101`
- `DramaticSkyRenderer.java:114`

**描述**

- `LoongPalaceSkyTickHandler` 在 `isLoong == true` 时每个 `LevelTickEvent.Post` 都会打一条 INFO，也就是龙宫维度内约 **20 行/秒**。
- 它还会每 100 次事件在所有维度打日志，包括非龙宫维度。
- `DramaticSkyRenderer.tick` 每 20 tick 打一条，时间跳变时也打。

**建议**

- 删除全部 `[SkyDebug]` 日志，或改为 `LOGGER.debug` 并加统一开关。
- `LoongPalaceSkyTickHandler` 应先判断“是否 client 且是否为龙宫”，不是就直接 `return`，不要把日志和 `eventCounter++` 放在判断之前。

---

### 🟠 问题 3：自定义 `renderSky` 未处理“不应绘制天空”的场景

**位置**

- `LoongPalaceSkyEffects.java:20-34`

**描述**

```java
@Override
public boolean renderSky(...) {
    DramaticSkyRenderer.render(...);
    if (ENABLE_DECORATIONS) {
        SkyDecorationsRenderer.render(...);
    }
    return true;
}
```

`DimensionSpecialEffects.renderSky` 返回 `true` 后，`LevelRenderer` 会直接跳过原版天空逻辑，包括：

- 玩家在水中 / 岩浆 / 细雪中
- 玩家有失明 / 黑暗效果
- 原版 `isFoggy` / boss 雾等情况

参考实现 `NeoforgeSkyboxes` 的 `SkyboxRenderMixin` 在渲染前显式检查：

```java
boolean cameraObscured = FogType == FogType.POWDER_SNOW
        || FogType == FogType.LAVA
        || FogType == FogType.WATER
        || hasBlindnessOrDarkness(camera);
```

**建议**

- 若希望和原版一致：在 `renderSky` 中根据 `camera.getFluidInCamera()` 和玩家效果决定“不绘制任何内容并返回 `true`”，或对特定状态返回 `false` 让原版处理。
- 至少确认这是有意设计；如果是，应在文档中记录。

---

### 🟠 问题 4：与 Dramatic Skys 原始资源包配置存在偏差，需确认权威来源

**位置**

- `DramaticSkyRenderer.java:33-43`
- `DramaticSkyRenderer.java:164-214`
- `SkyRotation.java:16-20`

**描述**

代码符合项目文档《完整迁移计划》的表格，但与资源包原始配置不完全一致：

| 项目 | 当前代码 | Dramatic Skys OptiFine/Celestial |
|---|---|---|
| 日落层 `sun.png` 混合 | `SCREEN` | OptiFine `sky6.properties` / `sky7.properties` 为 `add` |
| 日出层 `sun.png` 混合 | `SCREEN` | OptiFine 为 `add` |
| 日冕层 `sunflare.png` 混合 | `SCREEN` | OptiFine 为 `add` |
| 夜晚/星星 fade 区间 | 12500~23500（Celestial 变量） | OptiFine 约为 12800~22400 |
| 图层旋转速度/轴向 | 统一按 Y/Z 速度 1 | Celestial 中存在 `0.866` 速度、负 Y 轴等细节 |

项目文档中的表格本身写的是 `SCREEN`，所以代码与“文档”一致，但与“资源包原始表现”不一致。

**建议**

- 明确以哪一份作为验收标准：
  - 如果以 `docs/龙宫DramaticSkys完整迁移计划.md` 为准，当前实现可接受，但应在文档中注明“不追求与 OptiFine 原始混合完全一致”。
  - 如果要还原资源包，需要按 `assets/minecraft/optifine/sky/world0/*.properties` 与 Celestial 对象 JSON 重新核对 blend、fade、rotation。

---

### 🟠 问题 5：雾处理范围过大，可能把液体/状态雾也禁用

**位置**

- `LoongPalaceFogHandler.java:25-34`

**描述**

```java
event.setNearPlaneDistance(10000.0F);
event.setFarPlaneDistance(10000.0F);
event.setCanceled(true);
```

该 handler 不检查：

- `event.getMode()`（`FOG_SKY` / `FOG_TERRAIN`）
- `event.getType()`（`NONE` / `WATER` / `LAVA` / `POWDER_SNOW`）

所以在龙宫维度内，水中 / 岩浆 / 细雪雾也会被一并禁用。龙宫存在“化龙池水”区域，如果玩家进入水中会完全失去水雾，视觉上可能不符合预期。

**建议**

- 如果只想禁“环境雾/天空雾”，可只处理 `event.getType() == FogType.NONE`。
- 或明确确认“龙宫所有雾都禁用”是有意设计。

---

### 🟡 问题 6：静态状态缺少生命周期重置

**位置**

- `DramaticSkyRenderer.java:45-50`

**描述**

`DISPLAY_ALPHAS`、`initialized`、`lastDayTime`、`unexpectedTransitionActive` 都是静态字段，不会在：

- 离开龙宫维度
- 切换世界 / 退出存档
- 资源重载

时主动重置。再次进入龙宫时可能沿用旧 alpha，并在短时间内触发 200 tick 的长过渡。

**建议**

- 将渲染状态放到 `LoongPalaceSkyEffects` 的实例字段中，或监听 `ClientLevel` 加载/维度切换事件进行重置。

---

### 🟡 问题 7：每帧存在少量不必要的对象分配与矩阵运算

**位置**

- `SkyRotation.java:64-90`
- `DramaticSkyRenderer.java:117`

**描述**

- `SkyRotation.apply` 每个图层每帧调用 `calculateTimeRotation` 并 `new Vector3f(...)`。
- 当前 9 个图层即使 alpha 为 0，只要经过 `apply` 就会分配；实际只对 alpha > 0 的图层调用，但过渡期仍有多层。
- 所有旋转常量 `axis/static/timeShift` 都为 0，但仍执行多步矩阵乘法。

**建议**

- `calculateTimeRotation` 可返回 3 个 `float` 或复用字段，避免每帧分配。
- 对全 0 的轴/静态旋转直接跳过。
- 这不是当前主要瓶颈，属于低优先级优化。

---

### 🟡 问题 8：与天空无关的改动混入提交

**位置**

- `src/main/java/com/zonlong/beloong/waystoneplacement/WaystonePlacementLoader.java:19`

**描述**

提交 `ec30ad5` 中把 Javadoc：

```java
/**
 * 龙宫预设传送石碑数据加载器。
 */
```

误改成：

```java
/**
 * 龙宫预设传送石碑数据
 */
```

与天空渲染无关，且让注释语义变差。

**建议**

- 恢复为“龙宫预设传送石碑数据加载器。”或完整句号。

---

### 🟡 问题 9：死代码 / 未使用成员

**位置**

- `SkyRotation.java:16`：`SkyRotation.NONE`
- `SkyAlphaSource.java:7`：`ALWAYS`
- `CubeAtlasSkyRenderer.java:83-89`：接收 `Matrix4f` 的 `render` 重载

**描述**

当前主路径没有使用这些成员，保留会增加维护成本。

**建议**

- 删除未使用的 `NONE`、`ALWAYS`、`Matrix4f` 重载；如果后续会用到再添加。

---

### 🟡 问题 10：混合模式未显式设置 blend equation

**位置**

- `SkyBlendMode.java:13-52`

**描述**

`SkyBlendMode.apply` 只调用 `RenderSystem.blendFuncSeparate`，没有调用 `RenderSystem.blendEquation`。NeoForgeSkyboxes 的 `Blend` 在每个模式中都会显式设置 `ADD`：

```java
RenderSystem.blendEquation(Blender.Equation.ADD.value);
```

如果其他模组/渲染路径修改了 blend equation，当前天空层可能使用错误的混合方程。

**建议**

- 在每种混合模式中显式设置加法混合方程，例如 `RenderSystem.blendEquation(GL14.GL_FUNC_ADD)`，或参考 NeoForgeSkyboxes 中 `Blender.Equation.ADD.value` 的做法。

---

### 🟡 问题 11：`LoongPalaceSkyTickHandler` 的计数器存在跨线程风险

**位置**

- `LoongPalaceSkyTickHandler.java:23-39`

**描述**

`eventCounter` 是普通 `static int`，并且在判断是否 `ClientLevel` 之前就执行 `eventCounter++`。单人游戏内建服务器与客户端共用同一个 NeoForge 事件总线时，服务器线程和客户端线程可能同时进入该 handler，造成非原子自增 / 不必要的跨维度日志。

**建议**

- 将 handler 改成：

```java
if (!(event.getLevel() instanceof ClientLevel clientLevel)) return;
if (!LOONG_PALACE.equals(clientLevel.dimension())) return;
DramaticSkyRenderer.tick(clientLevel);
```

删除 `eventCounter` 与日志。

---

### 🟡 问题 12：纹理体积较大，建议关注显存占用

**位置**

- `src/main/resources/assets/beloong/textures/skybox/`
- `src/main/resources/assets/beloong/textures/environment/`

**描述**

从资源包原样复制，包含：

- `day.png` 约 3.5 MB
- `sun.png` 约 2.0 MB
- `night.png` 约 1.4 MB
- `moon_phases.png` 约 4.4 MB

这些是 3072×2048 / 高分辨率图集，解码后显存占用会明显放大。由于只在龙宫使用且原资源包相同，不算新增缺陷，但建议后续评估：

- 是否需要压缩 / 降采样
- 是否在非龙宫维度避免加载
- 当前代码只在 alpha > 0 的图层调用 `setShaderTexture`，这一点是好的。

---

## 三、已确认无明显问题的部分

- 九层渲染顺序正确。
- 六面 UV 与 Celestial 24 顶点表一致。
- fade 公式与 Celestial `variables.json` 等价。
- `SCREEN` 模式 alpha 变淡的修复方向正确。
- `renderClouds` / `renderSnowAndRain` / `tickRain` 返回 `true` 的语义在 `LevelRenderer` 源码中得到确认，确实是跳过原版绘制。
- 未新增 targeting Minecraft 原版的 Mixin。
- 天灾维度没有被这些处理器影响。
- 当前工作区删除 `renderBaseSky` 与文档“纯色基础层已删除”一致。

---

## 四、建议后续动作

1. 先处理问题 1 与问题 2：合并天空盒 draw call、清理调试日志，收益最明显。
2. 与需求方确认问题 3、4、5 的预期行为（是否处理失明/水中、是否严格还原资源包、是否禁用液体雾）。
3. 将 `DramaticSkyRenderer` 的静态状态实例化，避免跨世界残留。
4. 在正式提交前恢复 `WaystonePlacementLoader` 的注释，并删除未使用成员。
5. 如果计划支持 Sodium/Iris，需要单独补兼容层；当前代码没有对应处理，但仓库当前也没有相关依赖。
