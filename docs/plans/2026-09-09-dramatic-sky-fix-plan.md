# 龙宫 Dramatic Skys 天空渲染修复计划

**Date:** 2026-09-09
**Status:** Ready for implementation
**Design:** `docs/plans/2026-09-09-dramatic-sky-fix-design.md`

## 实施步骤

### 1. 重构 `CubeAtlasSkyRenderer`：单层单 draw call

- [ ] 将 6 个面的 24 个顶点写入同一个 `BufferBuilder`。
- [ ] 每层只调用一次 `BufferUploader.drawWithShader`。
- [ ] 删除未使用的 `render(Matrix4f, Matrix4f, ResourceLocation)` 重载。
- [ ] 将 shader / cull / depth mask 状态提升到 `DramaticSkyRenderer.render`。

### 2. 修复 `SkyBlendMode`：显式设置加法混合方程

- [ ] 在 `ALPHA` / `ADD` / `SCREEN` 的 `apply` 中追加 `RenderSystem.blendEquation(GL14.GL_FUNC_ADD)`。
- [ ] 在天空渲染结束恢复 blend equation 为加法。

### 3. 修正 `DramaticSkyRenderer` 图层配置

- [ ] Night 混合从 `ADD` 改为 `SCREEN`。
- [ ] 将 fade 改为 fabricskyboxes 离散循环区间：
  - Night 组：`13333-13666 / 22333-22666`
  - Day：`23666-333 / 11666-12333`
  - Sunset：`11666-12333 / 13333-13666`
  - Sunrise：`22333-22666 / 23666-333`
- [x] 删除旧的 `dayFade/nightFade/sunsetFade/sunriseFade` 公式，替换为循环区间算法。
- [x] 移除 `DramaticSkyRenderer` 的全部 `[SkyDebug]` 日志与 `tickLogCounter`。

### 4. 修正 `SkyRotation`

- [ ] `SUN_ROTATION` 的 `skyboxRotation` 改为 `true`。
- [ ] 新增 `DECORATION_ROTATION`：`speedZ=1`、`skyboxRotation=false`。
- [ ] `apply` 不再分配 `Vector3f`，直接使用三个 `float` 旋转角。
- [ ] 删除 `NONE`。

### 5. 增加相机遮挡判断

- [ ] `LoongPalaceSkyEffects.renderSky` 中判断：
  - `FogType.WATER`
  - `FogType.LAVA`
  - `FogType.POWDER_SNOW`
  - 失明 / 黑暗效果
- [ ] 遮挡时不绘制自定义天空，但仍 `return true` 跳过原版天空。

### 6. 修改 `SkyDecorationsRenderer` 旋转

- [ ] 移除 vanilla 式 `Axis.YP(-90)` + `Axis.XP(timeOfDay * 360)`。
- [ ] 改为 `SkyRotation.DECORATION_ROTATION.apply(poseStack, level)`。

### 7. 移除禁雾逻辑

- [ ] 删除 `LoongPalaceFogHandler.java`。
- [ ] 从 `BeLoongCoreClient.java` 删除 import 与注册。

### 8. 清理 `LoongPalaceSkyTickHandler`

- [ ] 删除 `eventCounter` 与所有 handler 日志。
- [ ] 改为：仅当 `ClientLevel` 且为 `beloong:loong_palace` 时调用 `DramaticSkyRenderer.tick`。

### 9. 清理死代码

- [ ] 删除 `SkyAlphaSource.ALWAYS` 与对应 `switch` 分支。
- [ ] 删除 `SkyRotation.NONE`。
- [ ] 删除 `CubeAtlasSkyRenderer` 未使用的重载。

### 10. 更新文档

- [ ] 更新 `docs/龙宫DramaticSkys完整迁移计划.md`：
  - Night 混合 ADD → SCREEN
  - fade 区间改为 fabricskyboxes 离散区间
  - Sunset/Sunrise 旋转说明
  - 删除“禁雾”设计，改为“保留原版雾”
- [ ] 本设计文档与计划文档已保存。

## 验证

### 构建

- [ ] `./gradlew compileJava` 通过。
- [ ] 无残留 `LoongPalaceFogHandler` 引用。
- [ ] 无未使用 import / 死代码。

### 进游戏验证

- [ ] 龙宫白天/夜晚/黄昏/黎明表现符合修正后的 fabricskyboxes 配置。
- [ ] Night 为 SCREEN，不再使用 ADD。
- [ ] fade 跨天回绕平滑。
- [ ] 太阳/月亮旋转符合 NeoForgeSkyboxes 默认装饰旋转。
- [ ] 龙宫恢复原版雾（含水中雾）。
- [ ] 失明/黑暗/水中/岩浆/细雪时不绘制自定义天空。
- [ ] 天灾维度不受影响。
- [x] draw call 明显下降；无 handler 日志刷屏；`[SkyDebug]` 已全部移除。

## 备注

- 问题 8、12 按用户要求不处理；问题 2、6 已在后续隐藏问题修复中处理。
- 若进游戏发现太阳/月亮旋转或 Night SCREEN 视觉异常，优先微调设计中的旋转/fade 参数，不扩展为数据驱动方案。
