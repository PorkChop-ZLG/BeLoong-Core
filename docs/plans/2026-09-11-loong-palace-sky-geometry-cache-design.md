# 龙宫天空渲染几何缓存设计（A + B 方案）

> 状态：**已实施**（2026-09-11，编译通过；**待客户端视觉与性能验收**）
> 范围：`beloong:loong_palace` 自定义天空渲染的**几何缓存**优化
> 目标：消除每帧重建并上传静态几何的开销，**零视觉变化**
> 前置分析：[`docs/天空渲染对照NeoForgeSkyboxes分析.md`](../天空渲染对照NeoForgeSkyboxes分析.md)
> 性能证据：`sparkclient` 生成的 `profile-2026-09-11_20.15.51.sparkprofile`（Render thread 373.44 s）
> 本文**只描述方案**。实施计划见配套 [`2026-09-11-loong-palace-sky-geometry-cache-plan.md`](2026-09-11-loong-palace-sky-geometry-cache-plan.md)。

---

## 一、目标与非目标

### 1.1 目标

消除 `LoongPalaceSkyEffects.renderSky` 路径中**每帧重建 `MeshData` 并上传**的开销。

**预期收益：约 0.6–0.8% 的渲染线程时间**（373.44 s 中的约 2.5–3.0 s）。

**硬性要求：画面零变化。** 本设计不含任何观感调整，不需要目视取舍。若验收时发现任何可见差异，即视为实施错误。

### 1.2 非目标（明确排除）

| 项 | 排除理由 |
|---|---|
| 减少图层数 / 预合成夜间层 | 属 C 方案，需要目视验证，且会牺牲逐层调参能力 |
| blend / GL 状态去重 | 属 D 方案，是行为变更，不与本次零视觉变化的纯结构调整混合 |
| 复用原版 `starBuffer` 等缓存缓冲 | 会改变星野观感，且需引入访问器 mixin |
| 引入任何 mixin | 与现行设计「不使用 mixin」的属性冲突 |
| 修改 `SkyAlphaSource` / fade 逻辑 | 与本优化正交 |

---

## 二、设计依据（全部经源码或字节码核实）

以下 10 条是设计成立的基石，逐条给出出处。**任何一条不成立都会导致方案失效**，实施前应复核。

### 2.1 几何与图层无关 ⇒ 9 层可共用一个缓冲

- `SkyLayerConfig` 是 `record(ResourceLocation texture, SkyBlendMode blend, SkyAlphaSource alphaSource, SkyRotation rotation)`，**不含任何几何参数**。
- `CubeAtlasSkyRenderer.render` 的顶点全部来自静态常量表 `FACES`，UV 亦为表内字面量。
- `DramaticSkyRenderer.LAYERS` 的 9 层共用同一次 `CubeAtlasSkyRenderer.render` 调用。

**⇒ 9 层的顶点数据逐字节相同，差异只在 texture / blend / alpha / rotation，而这三者全部在几何之外施加。因此只需要一个缓冲，不是九个。**

这一条把 A 方案的显存从「9 × 552 B」降到「552 B」，也把泄漏面降到 1 个对象。

### 2.2 矩阵是每帧 uniform，不是烘进顶点

`VertexBuffer` 源码（`neoforge-21.1.236-sources.jar`）：

```java
private void _drawWithShader(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
    shader.setDefaultUniforms(this.mode, modelViewMatrix, projectionMatrix, Minecraft.getInstance().getWindow());
    shader.apply();
    this.draw();
    shader.clear();
}
```

**⇒ 传入的两个矩阵就是 shader 的 `ModelViewMat` / `ProjMat`。旋转由每帧 uniform 施加。**

### 2.3 当前路径的整体变换等价于 `P × v`（关键，决定零视觉差异）

当前路径（`BufferUploader` 源码）：

```java
private static void _drawWithShader(MeshData meshData) {
    VertexBuffer vertexbuffer = upload(meshData);
    vertexbuffer.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
}
```

即：顶点在构建期被 `P = poseStack.last().pose()` 烘过（`addVertex(matrix4f, …)`），而 shader 收到的是 **全局** `RenderSystem.getModelViewMatrix()`。

**全 jar 扫描 6316 个 `.java` 条目：`setModelViewMatrix` 的调用点数量为 0。** `RenderSystem.modelViewMatrix` 初始化为 `new Matrix4f()`（单位阵）后再无赋值。

**⇒ 全局 model-view 恒为单位阵，故当前结果为 `I × P × v = P × v`。**

缓存后若传入 `drawWithShader(P, proj, shader)`，结果为 `P × v`。**两者严格相等。**

> 这正是原版的做法：`LevelRenderer` 在 `:1605-1606` 建 `PoseStack` 并 `mulPose(frustumMatrix)`，在 `:1619-1620` 把 `posestack.last().pose()` 显式传给缓存缓冲。与 `DramaticSkyRenderer.render:133-134` 的建栈方式逐行同构。

### 2.4 `upload()` 会自行关闭 `MeshData`（一次性构建不泄漏直接内存）

```java
public void upload(MeshData meshData) {
    try {
        if (this.isInvalid()) break label40;
        RenderSystem.assertOnRenderThread();
        ...
    } catch (Throwable t) { if (meshData != null) meshData.close(); throw t; }
    if (meshData != null) meshData.close();
    return;
    ...
}
```

**⇒ 把 `buildOrThrow()` 的结果直接交给 `upload(...)` 是安全的，两条路径都会 close。**

### 2.5 释放与失效语义

```java
public void close() {
    if (this.vertexBufferId >= 0) { RenderSystem.glDeleteBuffers(this.vertexBufferId); this.vertexBufferId = -1; }
    if (this.indexBufferId  >= 0) { RenderSystem.glDeleteBuffers(this.indexBufferId);  this.indexBufferId  = -1; }
    if (this.arrayObjectId  >= 0) { RenderSystem.glDeleteVertexArrays(this.arrayObjectId); this.arrayObjectId = -1; }
}
public boolean isInvalid() { return this.arrayObjectId == -1; }
```

一个 `VertexBuffer` = 2 个 GL buffer 对象 + 1 个 VAO。`close()` 可重复调用（有 `>= 0` 守卫）。

### 2.6 线程硬断言

| 调用 | 断言 |
|---|---|
| `new VertexBuffer(Usage)` | `RenderSystem.assertOnRenderThread()` |
| `VertexBuffer.upload(MeshData)` | `RenderSystem.assertOnRenderThread()` |
| `RenderSystem.glDeleteBuffers(int)` | `RenderSystem.assertOnRenderThread()` |

**⇒ 创建 / 上传 / 关闭必须在渲染线程。**

### 2.7 原版范式：静态缓存 + 每帧传矩阵

`LevelRenderer`（`neoforge-21.1.236-sources.jar`）：

```java
private void createLightSky() {                       // :619-628
    if (this.skyBuffer != null) { this.skyBuffer.close(); }
    this.skyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
    this.skyBuffer.bind();
    this.skyBuffer.upload(buildSkyDisc(Tesselator.getInstance(), 16.0F));
    VertexBuffer.unbind();
}
// 每帧 :1619-1621
this.skyBuffer.bind();
this.skyBuffer.drawWithShader(posestack.last().pose(), projectionMatrix, shaderinstance);
VertexBuffer.unbind();
```

`Usage.STATIC` = `GL_STATIC_DRAW(35044)`，正合「只上传一次」。

### 2.8 原版的生命周期：一会话一次，不重建、不释放

- `createLightSky` / `createDarkSky` / `createStars` **只在 `LevelRenderer` 构造时调用**（`:261-263`）。
- `allChanged()`（`:716`）**不**重建这三个缓冲。
- `onResourceManagerReload` 只做 `initOutline` / `initTransparency`。
- `close()`（`:488`）只关 `entityEffect` / `transparencyChain`，**不关天空缓冲**。

**⇒ 原版接受「天空缓冲活到 GL 上下文销毁」，由驱动回收。** 这为本次设计提供了「不做 shutdown 清理」的先例依据。

### 2.9 `bind()` / `unbind()` 会调 `BufferUploader.invalidate()`

```java
public void bind()   { BufferUploader.invalidate(); GlStateManager._glBindVertexArray(this.arrayObjectId); }
public static void unbind() { BufferUploader.invalidate(); GlStateManager._glBindVertexArray(0); }
```

**⇒ 我们自己的缓冲绑定与即时模式缓冲的静态追踪互相解耦，无正确性影响**；只是后续即时模式绘制会多一次 `glBindVertexArray`。原版同样在这种混用下工作。

### 2.10 现有代码已存在的长期 GL 资源

`BufferUploader.bindImmediateBuffer` 使用 `VertexFormat.getImmediateDrawVertexBuffer()`，即**按顶点格式各缓存一个 `VertexBuffer`**，静态字段 `lastImmediateBuffer` 仅记录当前绑定对象。

**⇒ 本次并非引入「长期驻留 GL 资源」这一新类别，只是数量从「若干共享缓冲」变为「共享缓冲 + 我们自己的 1 个」**。

---

## 三、方案设计

### 3.1 A 方案：天空盒几何缓存（单缓冲）

**改动位置：** `com.zonlong.beloong.client.sky.CubeAtlasSkyRenderer`

引入一个懒建的静态缓冲：

```
private static VertexBuffer skyBuffer;   // null = 尚未构建

ensureBuffer():
    if (skyBuffer != null && !skyBuffer.isInvalid()) return;
    skyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
    skyBuffer.bind();
    BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
    for (float[] face : FACES)
        for (int i = 0; i < 4; i++) {
            int o = i * 5;
            bb.addVertex(face[o], face[o+1], face[o+2])     // ← 不带矩阵！
              .setUv(face[o+3], face[o+4]);
        }
    skyBuffer.upload(bb.buildOrThrow());                     // ← upload 会自行 close MeshData
    VertexBuffer.unbind();

render(poseStack, projectionMatrix, texture):
    RenderSystem.setShaderTexture(0, texture);
    ensureBuffer();
    skyBuffer.bind();
    skyBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());
    VertexBuffer.unbind();
```

**关键纪律（本方案唯一的失败模式）：**

> 构建缓存时**必须使用不带矩阵的 `addVertex(x, y, z)` 重载**，存局部坐标。
> 若沿用当前的 `addVertex(matrix4f, x, y, z)`，旋转会被**冻结在首次上传时的角度**，表现为「天空不转」。
> 这是编码错误，不是方案缺陷——code review 时列为必查项。

**附带修正：** 现有 `render` 的 `projectionMatrix` 参数注释写着「保留参数，当前提交方式不直接使用」。缓存后它将**真正被使用**，注释需同步更新。

### 3.2 B 方案：日月几何缓存

**改动位置：** `com.zonlong.beloong.client.sky.SkyDecorationsRenderer`

#### 太阳

UV 为常量，位置为字面量 ⇒ 一个静态缓冲即可，处理方式与 A 完全相同。

#### 月亮

`drawMoon` 的 UV 随 `level.getMoonPhase()` 变化：

```
xCoord = phase % 4;  yCoord = phase / 4 % 2;
startX = xCoord / 4.0F;  startY = yCoord / 2.0F;
endX   = (xCoord + 1) / 4.0F;  endY = (yCoord + 1) / 2.0F;
```

共 **8 种相位**（`moon_phases.png` 为 4×2 图集）。两个可选实现：

| | B1（**推荐**） | B2 |
|---|---|---|
| 做法 | 惰性构建 8 个缓冲，按相位索引取用 | 1 个缓冲，相位变化时对**同一**缓冲再 `upload()` |
| 显存 | 8 × ~92 B ≈ 736 B | ~92 B |
| 每帧成本 | 无分支、无状态 | 一次相位比较 |
| 风险面 | 无 | 需维护 `lastMoonPhase`，漏判会导致月相错位 |
| 依据 | `VertexBuffer.upload` 可重复调用，`uploadVertexBuffer` 复用已有 `vertexBufferId`，**无需 close/新建** | 同左 |

**推荐 B1**：显存代价可忽略，换掉「重传时机」这一整类 bug 面。

**方法签名调整：** `drawSun` / `drawMoon` 目前不接收 `projectionMatrix`，而 `drawWithShader` 需要它 ⇒ 需为这两个私有方法补上该参数（`render` 的公开签名不变）。

---

## 四、生命周期设计（泄漏防线）

### 4.1 核心结论：本缓存**没有失效面**

缓存内容只依赖静态 `FACES` 表与字面量 UV，**不依赖**贴图对象、资源包、维度、群系、世界时间、alpha 状态。因此：

| 事件 | 处理 | 理由 |
|---|---|---|
| 首次渲染 | 懒建（渲染线程） | 需要 GL 上下文就绪 |
| 资源重载 `F3+T` | **不重建** | 几何不含资源信息；UV 是字面量。原版同样不重建（§2.8） |
| 进入 / 离开龙宫 | **不重建** | `DramaticSkyRenderer.reset()` 只清 alpha 与时间状态，与几何无关 |
| 维度切换 | **不重建** | 几何与维度无关 |
| 客户端退出 | 可选 best-effort close；**默认不做** | 原版不做，驱动随上下文销毁回收（§2.8）。若要做，必须在上下文销毁前、渲染线程上 |
| 缓冲被判定 invalid | 重建（防御性） | 正常不会发生；`ensureBuffer()` 里的 `isInvalid()` 检查兜住 |

> **明确禁止：** 不要在 `DramaticSkyRenderer.reset()` 中加入「丢弃缓冲」的钩子。那会把唯一的泄漏入口（重建时不 close / 重复 new）引进来，而收益为零。

### 4.2 泄漏风险清单与对策

| 风险 | 后果 | 对策 |
|---|---|---|
| 在渲染循环里 `new VertexBuffer` | 每帧泄漏 552 B × 60 fps ≈ 33 KB/s（天空盒）；叠加日月更多 | **单例 + 懒建**；禁止在 `render` 内 `new` |
| 重复构建不 close 旧的 | 每次泄漏 2 buffer + 1 VAO | 若将来引入 rebuild 路径，照抄原版 `if (b != null) b.close();`（§2.7） |
| 只丢引用不 close | Java GC 不管理 GL 对象 ⇒ 泄漏到上下文销毁 | 显式 `close()`；或按 §4.1 接受「不做 shutdown 清理」 |
| 在非渲染线程触碰 | `assertOnRenderThread()` 抛异常 | 只在 `renderSky` 路径内操作 |
| 上下文销毁后再 close | 调用到失效上下文 | 默认不做 shutdown 清理 |

**显存增量核算：** 天空盒 24 顶点 × 20 B + 36 索引 × 2 B = **552 B**；太阳 4 × 20 + 6 × 2 = **92 B**；月亮 8 × 92 = **736 B**。**合计约 1.4 KB**，另加 10 个 buffer 对象 + 10 个 VAO 的句柄开销。

---

## 五、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 缓存了已变换顶点 | 旋转冻结在首帧角度 | 只用无矩阵 `addVertex` 重载；review 必查 |
| 在渲染循环内 new | 显存持续增长 | 懒建单例 |
| `bind()` 后未 `unbind()` | 后续绘制使用错误 VAO | 严格照原版三段式 `bind → drawWithShader → unbind` |
| Iris 兼容性 | 贴图追踪错乱 | 保持 `setShaderTexture` 仍在 draw 之前；不改绑定顺序。Iris 的 `_setShaderTexture` / `TextureTracker` 钩子不感知几何来源 |
| 阴影 / Iris 管线下矩阵语义 | 画面偏移 | 已证全局 model-view 恒为单位阵（§2.3），传 `poseStack.last().pose()` 与原行为严格等价 |
| 月相缓冲与相位错配 | 月相画错 | 选 B1（8 缓冲按索引取），消除该状态 |
| 与 `BufferUploader` 混用 | 静态追踪失效但无害 | 已证 `bind/unbind` 内部即调 `invalidate()`（§2.9） |

---

## 六、量化预期

### 6.1 预计消除的开销

上一轮 spark 实测（`profile-2026-09-11_20.15.51`），`LoongPalaceSkyEffects.renderSky` inclusive = **11.04 s / 373.44 s = 2.96%**。其中与「每帧重建 + 上传」直接相关的项：

| 项 | 实测 | 归属 |
|---|---|---|
| `BufferUploader.upload` → `VertexBuffer.upload`（含 2 次 `glBufferData`） | 2.55 s | 天空盒 + 日月 |
| `bindImmediateBuffer` | 468 ms | 同上 |
| `Tesselator.begin` + `MeshData` 分配 | ~120 ms | 同上 |
| **合计** | **≈ 3.1 s** | |

**预计消除其中大部分**（`bind()`/`unbind()` 的 `glBindVertexArray` 成本保留）⇒ **约 2.5–3.0 s，即渲染线程的 0.6–0.8%**。

### 6.2 明确不改变的

- `VertexBuffer.drawWithShader` 的实际绘制（实测 3.52 s）
- GL 状态切换（blend / cull / depthMask，实测约 0.74 s）
- 填充率（全屏 alpha 混合层的 overdraw）
- 旋转矩阵计算（`SkyRotation` 相关，实测约 0.53 s）
- 任何像素输出

### 6.3 收益定位

**这是 0.6–0.8% 级别的优化，不解决任何性能问题。** 天空 2.96% 的占比对「9 层全屏 alpha 混合天空盒 + 日月」属合理范围。

本设计的正当性在于：**它是零风险、零视觉变化、改动集中在两个类里的纯结构调整**，适合在将来因其他原因触碰 `client/sky` 代码时顺带完成。不应为它单独排期。

---

## 七、验收标准

### 7.1 功能与视觉（必须全过）

1. **四个时段旋转正确**：昼 / 夜 / 黄昏 / 黎明各取一个时间点，天空旋转与优化前**逐帧一致**（对照优化前截图）。
2. **月亮 8 个相位**全部与优化前一致。
3. **太阳 / 月亮轨迹**与优化前一致（`SkyRotation.DECORATION_ROTATION` 行为不变）。
4. **边界路径不崩**：入水 / 岩浆 / 细雪 / 失明 / 黑暗时 `shouldRenderSky` 返回 false，缓冲保持已建状态但不渲染。
5. **资源重载 `F3+T` 后天空正常**（验证 §4.1「不重建」判断正确）。
6. **反复进出龙宫、切换维度、退出重进世界** → 无黑屏、无残留、无异常。
7. **Iris 开启与关闭**两种情况下画面一致。

### 7.2 性能（复采 spark 验证）

复采一份 `sparkclient` profile，确认：

- `CubeAtlasSkyRenderer` / `SkyDecorationsRenderer` 子树下 `BufferUploader.upload`、`bindImmediateBuffer`、`Tesselator.begin` **趋近 0**。
- `LoongPalaceSkyEffects.renderSky` inclusive 从 11.04 s 降至**约 8 s**。
- `VertexBuffer.drawWithShader` 的量级**不变**（3.5 s 左右）。

### 7.3 资源正确性

- 开发期临时加计数器（或断点），确认缓冲**各只被创建一次**；验证通过后移除计数器。
- 确认没有 `VertexBuffer` 实例在渲染循环中被反复 new。

---

## 八、改动面清单

| 文件 | 改动性质 |
|---|---|
| `client/sky/CubeAtlasSkyRenderer.java` | 新增静态 `VertexBuffer` + `ensureBuffer()`；`render` 改为缓存路径；更新 `projectionMatrix` 参数注释 |
| `client/sky/SkyDecorationsRenderer.java` | 新增太阳缓冲（1 个）+ 月亮缓冲（8 个，惰性）；`drawSun` / `drawMoon` 补 `projectionMatrix` 参数并改走缓存 |

**不新增文件**（可选：若认为两处重复的「懒建」样板值得抽，可加一个包内小工具类；但从改动面最小化考虑，**建议各自内联**，两处形状不同——单网格 vs 相位数组）。

**不改动：** `DramaticSkyRenderer`、`LoongPalaceSkyEffects`、`SkyLayerConfig`、`SkyBlendMode`、`SkyRotation`、`SkyAlphaSource`、`LoongPalaceSkyTickHandler`。

---

## 九、待确认项

1. **是否保留 shutdown 清理钩子。** 设计默认「不做」，依据 §2.8 原版先例。若要求做，需明确在 GL 上下文销毁前、渲染线程上的触发点（NeoForge 无现成的客户端 GL 关闭事件）。
2. **日月的 alpha 控制。** 对照文档记载「装饰是否受 alpha 控制：目前不完整」。这属既有行为，**不在本次范围内**，但实施时不要无意改动它——缓存不得影响 `RenderSystem.setShaderColor` / blend 的现有顺序。
3. **月亮相位缓冲 vs 重传**（B1 / B2）。本文推荐 B1；若倾向更小显存选 B2，需在计划中补「相位变化检测」的验收项。
