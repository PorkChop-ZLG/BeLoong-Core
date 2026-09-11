# 龙宫天空渲染几何缓存 实施计划

**Date:** 2026-09-11
**Status:** **已实施**（`compileJava` 与 `build` 均通过）；**视觉与性能验收待客户端完成**
**Design:** `docs/plans/2026-09-11-loong-palace-sky-geometry-cache-design.md`
**Execution:** 见文末「八、执行记录」

---

## 一、前提与约束

### 1.1 范围

**A 方案** —— 天空盒几何缓存为**单个**静态 `VertexBuffer`（9 层共用）。
**B 方案** —— 太阳（1 个）与月亮（8 个相位）几何缓存。

只改两个文件：

| 文件 | 改动 |
|---|---|
| `client/sky/CubeAtlasSkyRenderer.java` | 单缓冲 + 懒建 |
| `client/sky/SkyDecorationsRenderer.java` | 太阳 1 缓冲 + 月亮 8 缓冲 |

### 1.2 硬约束

- [ ] **零视觉变化。** 这是硬要求，不是目标。任何可见差异都判为实施错误。
- [ ] 不新增文件，不引入 mixin。
- [ ] 不改 `LAYERS`、`SkyBlendMode`、`SkyAlphaSource`、`SkyRotation`、`SkyRotation` 的 fade 区间。
- [ ] 不改纹理绑定顺序（`setShaderTexture` 仍在绘制之前），以免影响 Iris 的 `TextureTracker`。
- [ ] 不动 `DramaticSkyRenderer`、`LoongPalaceSkyEffects`、`LoongPalaceSkyTickHandler`。

### 1.3 两个不可违反的实现纪律

> **纪律 A：构建缓存时必须用不带矩阵的 `addVertex(x, y, z)`，存局部坐标。**
> 沿用 `addVertex(matrix4f, …)` 会把旋转冻结在首次上传的角度 → 「天空不转」。
> 这是本方案**唯一**的失败模式，code review 必查。

> **纪律 B：矩阵只在绘制时经 `drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader())` 传入。**
> 用 `RenderSystem.getShader()` 而非自行取 shader，可保证与现行 `BufferUploader` 路径（`_drawWithShader` 内部同样用 `RenderSystem.getShader()`）**逐位一致**。

### 1.4 验证方法（替代 TDD）

本项目无渲染单测，采用「**基线截图对照 + 强制时段 + spark 复采**」三段式：

1. **改动前先存基线**（T0），改动后逐项对照。
2. **强制时段**用命令而非等待：
   ```
   /execute in beloong:loong_palace run time set noon       # 昼
   /execute in beloong:loong_palace run time set midnight   # 夜
   /execute in beloong:loong_palace run time set 12000      # 黄昏（SUNSET fade in 11666-12333）
   /execute in beloong:loong_palace run time set 22500      # 黎明（SUNRISE fade in 22333-22666）
   ```
3. **性能复采**用本项目 `tools/` 下的三个脚本（见 §六）。

---

## 二、任务

### 阶段 0 — 基线（改动前必须完成）

- [ ] 进入 `beloong:loong_palace`，用 §1.4 的四条命令各截一张天空图，存档为对照基线。
- [ ] 额外记录：月亮相位（`/time set midnight` 后连续观察或等待换相位）、太阳/月亮在天空中的位置与朝向。
- [ ] 记录边界路径基线：入水、入岩浆、细雪、失明、黑暗各一张。
- [ ] 可选：采一份改动前的 `sparkclient` profile 存档（用于「改动后增量」对照，而非绝对数值）。
- [ ] 确认基线本身正确：`/execute in beloong:loong_palace run time set noon` 后天空确实切换为白天层。

> 目的：本次要求「零视觉变化」，**没有基线就无法判定**。此阶段不得跳过。

### 阶段 1 — A 方案：天空盒单缓冲

**文件：** `CubeAtlasSkyRenderer.java`

- [ ] 新增静态字段 `private static VertexBuffer skyBuffer;`（`null` 表示未构建）。
- [ ] 新增 `private static void ensureBuffer()`：
  - [ ] 若 `skyBuffer != null && !skyBuffer.isInvalid()` 则直接返回。
  - [ ] `skyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);`
  - [ ] `skyBuffer.bind();`
  - [ ] 用 `Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)` 建 `BufferBuilder`。
  - [ ] 遍历 `FACES`，用 **`addVertex(face[o], face[o+1], face[o+2])`（无矩阵重载）** + `setUv(face[o+3], face[o+4])` 写 24 个顶点。**← 纪律 A**
  - [ ] `skyBuffer.upload(bufferBuilder.buildOrThrow());`（`upload` 会自行关闭 `MeshData`，不要额外 close）
  - [ ] `VertexBuffer.unbind();`
- [ ] 改写 `render(PoseStack, Matrix4f projectionMatrix, ResourceLocation texture)`：
  - [ ] `RenderSystem.setShaderTexture(0, texture);`
  - [ ] `ensureBuffer();`
  - [ ] `skyBuffer.bind();`
  - [ ] `skyBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());`
  - [ ] `VertexBuffer.unbind();`
- [ ] 删除 `render` 内原有的 `Tesselator` / `BufferBuilder` / `BufferUploader` 绘制代码。
- [ ] 更新 `projectionMatrix` 参数注释：由「保留参数，当前提交方式不直接使用」改为「用于 `drawWithShader` 的投影矩阵」。
- [ ] 清理不再使用的 import（`BufferUploader`；`Tesselator` / `BufferBuilder` / `BufferUploader` 中仅 `BufferUploader` 会变成未使用，其余构建缓存仍需要）。

**编译门槛：** `./gradlew compileJava` 通过。

### 阶段 2 — B 方案：太阳缓冲

**文件：** `SkyDecorationsRenderer.java`

- [ ] 新增静态字段 `private static VertexBuffer sunBuffer;`
- [ ] 新增 `private static void ensureSunBuffer()`，结构同 `ensureBuffer()`：4 个顶点用**无矩阵 `addVertex`**。
- [ ] `drawSun` 签名加 `Matrix4f projectionMatrix` 参数；内部改为：
  - [ ] `RenderSystem.setShaderTexture(0, SUN_TEXTURE);`
  - [ ] `ensureSunBuffer();` → `bind()` → `drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader())` → `unbind()`
- [ ] 更新 `render` 中的 `drawSun(...)` 调用点补传 `projectionMatrix`。

### 阶段 3 — B 方案：月亮 8 相位缓冲（B1）

- [ ] 新增静态字段 `private static final VertexBuffer[] MOON_BUFFERS = new VertexBuffer[8];`
- [ ] 新增 `private static void ensureMoonBuffer(int phase)`：
  - [ ] 若 `MOON_BUFFERS[phase] != null && !MOON_BUFFERS[phase].isInvalid()` 则返回。
  - [ ] 用**与现状完全相同**的 UV 公式计算 `startX/startY/endX/endY`：
    ```
    xCoord = phase % 4;  yCoord = phase / 4 % 2;
    startX = xCoord / 4.0F;  startY = yCoord / 2.0F;
    endX = (xCoord + 1) / 4.0F;  endY = (yCoord + 1) / 2.0F;
    ```
  - [ ] 4 个顶点用**无矩阵 `addVertex`**，UV 顺序必须与现状逐字一致（顺序错会导致月相镜像/翻转）。
- [ ] `drawMoon` 签名加 `Matrix4f projectionMatrix`；内部改为按 `level.getMoonPhase()` 取缓冲绘制。
- [ ] 更新 `render` 中的 `drawMoon(...)` 调用点补传 `projectionMatrix`。
- [ ] 清理不再使用的 `BufferUploader` import（如已无其它引用）。

**编译门槛：** `./gradlew compileJava` 通过。

### 阶段 4 — 一致性复核

- [ ] 逐项对照 `render` 的**外部行为**未变：`setShaderTexture` 顺序、`RenderSystem.getShader()` 来源、`enableBlend`/`disableCull`/`depthMask`/`setShaderColor`/`blendEquation`/`defaultBlendFunc` 的调用位置与顺序。
- [ ] 确认**没有**在 `render` / `drawSun` / `drawMoon` 内出现 `new VertexBuffer`（`grep -n "new VertexBuffer"` 应只出现在 `ensure*` 方法里）。
- [ ] 确认**没有**在任何地方调用 `VertexBuffer.close()`（本方案不需要 rebuild 路径，也就不需要 close——**不要**在 `DramaticSkyRenderer.reset()` 里加丢弃缓冲的钩子）。
- [ ] 确认 `ensure*` 方法只在渲染线程被调用（调用链：`LoongPalaceSkyEffects.renderSky` ← `LevelRenderer.renderSky`）。
- [ ] 核对 `git diff` 只触及上述两个文件。

### 阶段 5 — 文档同步

- [ ] 更新 `docs/天空渲染对照NeoForgeSkyboxes分析.md`：在「十、渲染挂载点」附近补一行说明——几何提交方式为静态 `VertexBuffer` 缓存（原版 `LevelRenderer` 同法）。
- [ ] 将设计文档 `2026-09-11-loong-palace-sky-geometry-cache-design.md` 状态由「设计已定稿，尚未实施」改为「已实施」。
- [ ] 若实施中发现设计有偏差，回写设计文档而不是只改代码。

---

## 三、顺序与依赖

```
T0 基线 ──► T1 A 方案 ──► T2 太阳 ──► T3 月亮 ──► T4 复核 ──► T5 文档
                                          │
                                          └──► 阶段 1+2 可先合并验证，阶段 3 单独验证月相
```

- **T0 不可跳过**：没有基线无法判定「零视觉变化」。
- **T1 是高风险点**（纪律 A 的唯一违反点），实施后**立即进游戏看旋转**，不要等 T2/T3 一起测。
- T2 与 T3 相互独立，可任选顺序。
- **性能复采（§5.2）需要 T1–T3 全部完成**，且必须在同一台机器、同一渲染设置下采集，否则与基线不可比。
- T5 文档同步放在最后，以便把实施中的偏差一并回写。

---

## 四、风险与回滚

| 现象 | 最可能原因 | 定位/处置 |
|---|---|---|
| 天空完全不转 / 转得不对 | 违反纪律 A：用了带矩阵的 `addVertex` | 检查 `ensureBuffer` 构建段；应只有 `Matrix4f` 出现在 `drawWithShader` 的参数里 |
| 天空变形 / 偏移 / 缩放错误 | 传了错误的矩阵 | 必须是 `poseStack.last().pose()`；不要传 `modelViewMatrix` 的副本或 `RenderSystem.getModelViewMatrix()`（后者恒为单位阵） |
| 黑屏 / 后续绘制异常 | `bind()` 后漏 `unbind()` | 照原版三段式补齐 |
| 崩溃并报渲染线程断言 | 在非渲染线程触碰缓冲 | 检查调用链；`ensure*` 只能从 `renderSky` 路径进入 |
| 月相镜像 / 错位 | UV 顶点顺序或 `phase` 索引错 | 逐步对照阶段 3 的 UV 公式与顶点顺序，与现状逐字比对 |
| 资源重载 `F3+T` 后天空异常 | 误加了「重载重建」逻辑 | 本方案**不需要**重建；删掉该逻辑（设计 §4.1） |
| 显存持续增长 | 在渲染循环里 `new VertexBuffer` | `grep "new VertexBuffer"` 应只在 `ensure*` 内；开发期可临时加创建计数器验证后移除 |
| 性能无改善 | 该子树下仍有其它 `BufferUploader` 调用路径 | 用 `spark_profile_subtree.py` 查 `CubeAtlasSkyRenderer` 子树，确认 `BufferUploader.upload` / `bindImmediateBuffer` / `Tesselator.begin` 是否真的消失 |

**回滚：** 改动仅限两个文件且相互独立 ⇒ `git checkout -- <两个文件>` 即可完全回退，无数据/存档影响，无注册表变更，无需迁移。

---

## 五、验收标准

### 5.1 视觉（必须全过）

- [ ] 昼 / 夜 / 黄昏 / 黎明四张截图与 T0 基线**逐帧一致**。
- [ ] 月亮 8 个相位全部与基线一致（含朝向与镜像）。
- [ ] 太阳轨迹、月亮轨迹、`DECORATION_ROTATION` 表现与基线一致。
- [ ] 图层过渡（`13333-13666` / `11666-12333` 等区间附近）无闪烁、无硬切变化。
- [ ] 入水 / 岩浆 / 细雪 / 失明 / 黑暗时**不绘制**自定义天空，且不崩：
      `/effect give @s minecraft:blindness 10 0`
- [ ] 资源重载 `F3+T` 后天空正常（验证「不重建」判断正确）。
- [ ] 反复进出龙宫、切换维度、退出重进世界 → 无黑屏、无残留、无异常。
- [ ] Iris 开启与关闭两种情况下画面与各自基线一致。

### 5.2 性能（复采 spark）

采一份新的 `sparkclient` profile，跑 `tools/spark_profile_subtree.py`：

- [ ] `LoongPalaceSkyEffects.renderSky` inclusive 由 **11.04 s 降至约 8 s**。
- [ ] `CubeAtlasSkyRenderer` 子树下 `BufferUploader.upload`、`bindImmediateBuffer`、`Tesselator.begin` **趋近 0**。
- [ ] `SkyDecorationsRenderer` 子树下同上。
- [ ] `VertexBuffer.drawWithShader` 量级**不变**（约 3.5 s）——若它也大幅下降，说明绘制本身被跳过了，属错误。
- [ ] `renderLevel` 总量下降约 3 s。

### 5.3 资源正确性

- [ ] 开发期临时计数器确认：天空盒缓冲创建 **1 次**，太阳 **1 次**，月亮**每个相位至多 1 次**；验证通过后移除计数器。
- [ ] 长时间停留龙宫（≥5 分钟）后显存无增长趋势。

---

## 六、可复用的验证手法（记下来）

### 6.1 本项目 `tools/` 下的 spark 分析三步

```bash
# 1) 解码：线程/自身耗时/inclusive 排名
python tools/spark_profile_decode.py <profile.sparkprofile> --top 25 --threads 15

# 2) 归属方成本：按包名聚合自身耗时 + 关键词追踪（可追加关键词）
python tools/spark_profile_analyze.py <profile> beloong
python tools/spark_profile_analyze.py <profile> bufferuploader nglbufferdata

# 3) 子树下钻：查某方法的耗时花在它调用的什么上面
python tools/spark_profile_subtree.py <profile> "CubeAtlasSkyRenderer"
python tools/spark_profile_subtree.py <profile> "LoongPalaceSkyEffects.renderSky" --depth 6
```

注意：关键词匹配是**小写敏感**的（`kw in key.lower()`），追加关键词时请传小写。

### 6.2 核对原版语义（不靠记忆）

```bash
# 本地已有原版源码 jar
build/moddev/artifacts/neoforge-21.1.236-sources.jar
# 可选：ModDevGradle 也会产出
build/moddev/artifacts/neoforge-21.1.236-client-extra-aka-minecraft-resources.jar

# 只读字节码（断言、调用序列）
javap -p -c -classpath build/moddev/artifacts/neoforge-21.1.236.jar <类名>
```

本次设计的关键结论均由此得出：`VertexBuffer._drawWithShader` 的 uniform 写入、`upload` 自行关闭 `MeshData`、`close` 的释放序列、`glDeleteBuffers` 的线程断言，以及「全 jar 6316 个 `.java` 中 `setModelViewMatrix` 零调用点 ⇒ 全局 model-view 恒为单位阵」。

### 6.3 强制时段

用 `/execute in beloong:loong_palace run time set <时间>`，不要靠等待。四个关键值见 §1.4。

### 6.4 时间戳纪律

spark 采完立刻记下 profile 文件名，并与 `run/logs/latest.log` 的时间戳对齐，避免把旧样本当新结果（本项目此前踩过这个坑）。

---

## 七、备注

- 本计划实现的是 **0.6–0.8% 渲染线程时间**级别的优化，**不解决任何性能问题**。若排期紧张，本计划的正当用途是「将来因其他原因触碰 `client/sky` 时顺带执行」，而非单独排期。
- 设计文档 §九列了三个待确认项（是否保留 shutdown 清理钩子 / 日月 alpha 控制是否需一并处理 / B1 与 B2 取舍）。**本计划按「不做 shutdown 清理」「不动 alpha 控制」「选 B1」编列**；若要改变任一项，先改设计再改本计划。

---

## 八、执行记录（2026-09-11）

### 8.1 已完成

| 阶段 | 内容 | 结果 |
|---|---|---|
| T0 | 基线身份固化 | 分支 `disaster2`，`HEAD = e2a4f0e`。改动前两文件均干净，哈希已记录，随时可 `git checkout -- <文件>` 复原做对照。**视觉基线截图未采集 —— 见 8.2** |
| T1 | A 方案：天空盒单缓冲 | 新增静态 `skyBuffer` + `ensureBuffer()`；`render` 改为 `setShaderTexture → ensureBuffer → bind → drawWithShader → unbind`；构建时使用**不带矩阵**的 `addVertex`；删除 `BufferUploader` import；订正 `projectionMatrix` 注释 |
| T2 | B 方案：太阳缓冲 | `sunBuffer` + `ensureSunBuffer()`；`drawSun` 补 `projectionMatrix` 参数 |
| T3 | B 方案：月亮 8 相位缓冲 | `MOON_BUFFERS[8]` + `ensureMoonBuffer(int)`；UV 公式与顶点顺序与既有实现**逐字一致**；`drawMoon` 补 `projectionMatrix` 参数；`phase` 加 `& 7` 保证数组下标安全 |
| T4 | 一致性复核 | 四条 grep 断言全过（见 8.3）；`git diff --stat` 只触及两个文件（+123/−42）|
| T5 | 文档同步 | 本文与设计文档状态已更新；`docs/天空渲染对照NeoForgeSkyboxes分析.md` 第十节补「几何提交方式」对比行与说明 |

**编译与打包门槛：**

- `./gradlew compileJava` → `BUILD SUCCESSFUL in 27s`
- `./gradlew build` → `BUILD SUCCESSFUL in 4s`（exit 0，已产出 jar）

仅有 3 个既有的 mixin 混淆映射告警（`PossibleBiomesFilterMixin` / `ParameterListAccessor`），与本次改动无关。

### 8.2 尚未完成（全部需客户端配合）

- [ ] **T0 的视觉基线截图**：昼 / 夜 / 黄昏 / 黎明，外加水中 / 岩浆 / 细雪 / 失明 / 黑暗。
- [ ] **§5.1 全部视觉对照项**（这是「零视觉变化」这一硬要求的判定依据）。
- [ ] **§5.2 性能复采**：新采一份 `sparkclient` profile，用 `tools/spark_profile_subtree.py` 验证 `renderSky` inclusive 由 11.04 s 降至约 8 s。
- [ ] **§5.3 资源正确性**：开发期临时计数器确认各缓冲只被创建一次（月亮每个相位至多一次）。
- [ ] Iris 开启 / 关闭两种情况下的画面比对。

> 若需要严格的「改动前 vs 改动后」对照，可在客户端执行：
> `git stash push -- src/main/java/com/zonlong/beloong/client/sky/`（或对两个文件 `git checkout --`），
> 重编译后采样基线，再用 `git stash pop` 恢复。

### 8.3 已执行的静态断言

| 断言 | 结果 |
|---|---|
| `new VertexBuffer` | 仅出现在 `ensureBuffer` / `ensureSunBuffer` / `ensureMoonBuffer`（另加 `MOON_BUFFERS` 的数组声明） |
| `addVertex(matrix4f` | **0 匹配** —— 纪律 A 未被违反 |
| `.close()` | **0 匹配** —— 本方案无 rebuild 路径，符合设计 §4.1 的禁令 |
| `BufferUploader` | client 包下 **0 匹配** —— 绘制路径已完全切换 |

### 8.4 实施中的偏差

无。设计与计划均按原样落地，未发现需要回写设计的偏差。
