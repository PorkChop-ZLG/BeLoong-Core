# 龙宫 Dramatic Skys 天空渲染修复设计

**Date:** 2026-09-09
**Status:** Approved
**Approach:** Approach A — 定向重构现有硬编码实现

## Problem Statement

对 `0.8.2` 之后的龙宫自定义天空渲染进行修复。当前实现存在：

1. 天空盒 draw call 过多（每层 6 次 buffer 上传）。
2. 调试日志保留，暂不清理。
3. 自定义 `renderSky` 未处理失明/黑暗/水中/岩浆/细雪等“不应绘制天空”场景。
4. 天空配置与资源包在 NeoForgeSkyboxes 下的 fabricskyboxes 表现不一致。
5. 龙宫被错误禁雾，需要恢复原版雾。
6. 静态 alpha 生命周期问题暂不处理。
7. `SkyRotation` 每帧产生不必要对象分配。
8. 无关 Javadoc 误改不处理。
9. 存在死代码。
10. 混合模式未显式设置 blend equation。
11. `LoongPalaceSkyTickHandler` 有日志/计数器线程风险。
12. 贴图体积暂不处理。

## Design

### Architecture

保留现有硬编码 9 层天空渲染架构，不引入 JSON 数据驱动。在现有类内完成修复：

- `CubeAtlasSkyRenderer`：单层单 draw call，状态提升。
- `DramaticSkyRenderer`：修正图层配置、fade 区间、旋转；保留 `[SkyDebug]`。
- `SkyRotation`：修正 `SUN_ROTATION`，新增 `DECORATION_ROTATION`，减少分配，清理死代码。
- `SkyBlendMode`：显式设置加法混合方程。
- `LoongPalaceSkyEffects`：增加相机遮挡判断。
- `SkyDecorationsRenderer`：太阳/月亮旋转改为 NeoForgeSkyboxes 默认装饰旋转。
- `LoongPalaceSkyTickHandler`：只调用 tick，移除日志与计数器。
- 删除 `LoongPalaceFogHandler` 及注册。

### Components

#### CubeAtlasSkyRenderer

- 将 6 个面的 24 个顶点写入同一个 `BufferBuilder`。
- 每图层只调用一次 `BufferUploader.drawWithShader`。
- shader / cull / depth mask 状态提升到 `DramaticSkyRenderer.render`。
- 删除未使用的 `render(Matrix4f, Matrix4f, ResourceLocation)` 重载。

#### DramaticSkyRenderer

- 9 层配置按 fabricskyboxes 对应文件修正：

| 层 | 混合 | fade 区间 | 旋转 |
|---|---|---|---|
| Stars | ALPHA | 13333-13666 / 22333-22666 | false Z1 |
| MaskMoon | ALPHA | 同上 | false Z1 |
| Mask | ALPHA | 同上 | true Y1 |
| Day | SCREEN | 23666-333 / 11666-12333 | true Y1 |
| Night | SCREEN（原 ADD） | 13333-13666 / 22333-22666 | true Y1 |
| Sunset | SCREEN | 11666-12333 / 13333-13666 | true Y1 |
| Sunrise | SCREEN | 22333-22666 / 23666-333 | true Y1 |
| SunflareSunset | SCREEN | 11666-12333 / 13333-13666 | false Z1 |
| SunflareSunrise | SCREEN | 22333-22666 / 23666-333 | false Z1 |

- fade 改为离散循环区间算法，参考 NeoForgeSkyboxes `Utils.calculateFadeAlphaValue`。
- 保留 `[SkyDebug]`。

#### SkyRotation

- `SUN_ROTATION` 的 `skyboxRotation` 改为 `true`。
- 新增 `DECORATION_ROTATION`：speedZ=1，skyboxRotation=false。
- `apply` 不再分配 `Vector3f`。
- 删除 `NONE`。

#### SkyBlendMode

- 每种模式在 `blendFuncSeparate` 后调用 `RenderSystem.blendEquation(GL14.GL_FUNC_ADD)`。
- 渲染结束恢复 blend equation 为加法。

#### LoongPalaceSkyEffects

- 参考 NeoForgeSkyboxes `SkyboxRenderMixin`：
  - 水中 / 岩浆 / 细雪 / 失明 / 黑暗时不绘制自定义天空。
  - 始终 `return true`，避免原版天空叠加。

#### SkyDecorationsRenderer

- 太阳/月亮旋转改为 `SkyRotation.DECORATION_ROTATION.apply(...)`。
- 不再使用 vanilla 式 Y -90 + X timeOfDay 旋转。

#### LoongPalaceSkyTickHandler

```java
if (!(event.getLevel() instanceof ClientLevel clientLevel)) return;
if (!LOONG_PALACE.equals(clientLevel.dimension())) return;
DramaticSkyRenderer.tick(clientLevel);
```

- 删除 `eventCounter` 与 handler 日志。

#### LoongPalaceFogHandler

- 删除文件，并在 `BeLoongCoreClient` 中移除注册。

### Data Flow

- `LevelTickEvent.Post` → `LoongPalaceSkyTickHandler` → `DramaticSkyRenderer.tick` 更新 display alpha。
- 每帧 `LoongPalaceSkyEffects.renderSky` → 相机遮挡判断 → `DramaticSkyRenderer.render` + `SkyDecorationsRenderer.render`。
- `DramaticSkyRenderer.render` 使用修正后的 fade/rotation 绘制各层；`CubeAtlasSkyRenderer` 每层一次提交。

### Error Handling

- fade 跨 24000 回绕采用循环距离算法，避免负距离。
- 相机遮挡判断只影响龙宫维度。
- RenderSystem 状态在渲染结束恢复。
- 静态 alpha 状态不重置（问题 6 不处理）。

## Decisions Made

- 使用 Approach A，不做 JSON 数据驱动。
- 问题 4 以 fabricskyboxes 中与 Celestial 对应的 9 层 + 太阳/月亮装饰为准。
- Night 混合从 ADD 改为 SCREEN。
- fade 区间改为 fabricskyboxes 离散区间。
- 太阳/月亮旋转改为 NeoForgeSkyboxes 默认 `Decorations.rotation`。
- 彻底移除 `LoongPalaceFogHandler`，恢复原版雾。
- `LoongPalaceSkyTickHandler` 不再打日志，保留 `DramaticSkyRenderer` 的 `[SkyDebug]`。
- 问题 2、6、8、12 不修复。

## Non-Goals

- 不引入数据驱动 JSON 加载。
- 不清理 `DramaticSkyRenderer` 中保留的 `[SkyDebug]`。
- 不处理静态 alpha 生命周期。
- 不处理无关 Javadoc 与贴图体积。
- 不新增 Sodium/Iris 兼容层。

## Next Steps

- 按 `docs/plans/2026-09-09-dramatic-sky-fix-plan.md` 执行修复。
- 更新 `docs/龙宫DramaticSkys完整迁移计划.md` 与 `docs/本会话修改与决策总结.md`。
- 更新 `memory/decisions-log.md` 与 `memory/learned-patterns.md`。
