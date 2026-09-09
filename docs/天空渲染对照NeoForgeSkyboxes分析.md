# 天空渲染与 NeoForgeSkyboxes 对照分析

> 用途：记录本模组 Dramatic Skys 天空渲染实现与参考模组 NeoForgeSkyboxes 的异同，方便后续快速查阅和修复。

## 一、总体架构对比

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| 天空定义 | JSON 资源包 → 多个独立 `Skybox` 对象 | `DramaticSkyRenderer` 硬编码 9 层 |
| 图层对象 | `AbstractSkybox` / `TexturedSkybox` 实例 | `SkyLayerConfig` 配置对象 |
| 活动图层管理 | `SkyboxManager.activeSkyboxes` | 无 active 列表 |
| 优先级排序 | `Skybox::getPriority` | 写死顺序 |
| 数据驱动 | 是 | 否 |

对应类：

- NeoForgeSkyboxes：
  - `dev.hoshno.neoforgeskyboxes.SkyboxManager`
  - `dev.hoshno.neoforgeskyboxes.skyboxes.AbstractSkybox`
  - `dev.hoshno.neoforgeskyboxes.skyboxes.textured.TexturedSkybox`
- BeLoong Core：
  - `com.zonlong.beloong.client.sky.DramaticSkyRenderer`
  - `com.zonlong.beloong.client.sky.SkyLayerConfig`
  - `com.zonlong.beloong.client.sky.SkyAlphaSource`

---

## 二、Alpha 更新模型

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| fadeAlpha | `Fade` 时间段计算 | `SkyAlphaSource` 时间函数 |
| conditionAlpha | 条件激活平滑状态 | 目前没有 |
| 最终 alpha | `fadeAlpha * conditionAlpha` | `CURRENT_ALPHAS` 直接趋近 target |
| lastTime | `AbstractSkybox.lastTime` | `DramaticSkyRenderer.lastDayTime` |
| 时间跳变 | `unexpectedTransitionDuration` 驱动 conditionAlpha | 只改变 current alpha 步长 |
| transitionIn/Out | 每个 skybox 可配置 | 全局常量 |

对应类：

- NeoForgeSkyboxes：
  - `AbstractSkybox.updateAlpha()`
  - `Properties.getTransitionInDuration()`
  - `Properties.getTransitionOutDuration()`
  - `FabricSkyBoxesConfig.GeneralSettings.unexpectedTransitionDuration`
  - `Utils.calculateConditionAlphaValue()`
- BeLoong Core：
  - `DramaticSkyRenderer.tick()`
  - `DramaticSkyRenderer.moveTowards()`
  - `DramaticSkyRenderer.alphaFor()`

> 核心差异：参考模组把“时间亮度”和“图层激活状态”拆成两个因子；本模组目前只存在一个总 alpha，导致时间跳变时仍然可能瞬间切换。

---

## 三、图层激活 / 退出

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| 是否加入/移出 active 列表 | 是 | 否 |
| 激活过渡 | `conditionAlpha` 0 → 1 | 无 |
| 退场过渡 | `conditionAlpha` 1 → 0 | 无 |
| 条件判断 | `Conditions`（天气/维度/生物群系等） | 无 |

对应类：

- NeoForgeSkyboxes：
  - `SkyboxManager.onEndTick()`
  - `AbstractSkybox.checkConditions()`
  - `AbstractSkybox.checkWeather()`
  - `Conditions`
- BeLoong Core：
  - `LoongPalaceSkyTickHandler`
  - `DramaticSkyRenderer`

---

## 四、Fade 配置

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| Fade 数据对象 | `Fade` | `SkyAlphaSource` + 硬编码函数 |
| 时间区间 | `startFadeIn/endFadeIn/startFadeOut/endFadeOut` | day/night/sunset/sunrise 阈值公式 |
| 跨天循环 | `Utils.calculateFadeAlphaValue()` 支持 | 未完整支持 |
| 可配置 | JSON 可配置 | 需改 Java |

---

## 五、旋转

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| Rotation 对象 | `Rotation` | `SkyRotation` |
| 轴/静态/时间偏移 | 均支持 | 均支持 |
| 计算 | `Utils.calculateRotation()` | `SkyRotation.calculateTimeRotation()` |
| 应用 | `TexturedSkybox.render()` | `DramaticSkyRenderer.render()` |
| 装饰旋转 | `Decorations.rotation` | `SkyDecorationsRenderer` 使用 vanilla 式旋转 |

---

## 六、混合模式

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| Blend 对象 | `Blend` / `Blender` | `SkyBlendMode` |
| 支持模式 | add/subtract/multiply/screen/replace/alpha/burn/dodge/disable/decorations/custom | alpha/add/screen |
| 实现方式 | `RenderSystem.blendFunc` + `blendEquation` | `RenderSystem.blendFuncSeparate` |

---

## 七、面映射 / UV

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| 映射方式 | 基础 quad + 每面旋转 | 24 顶点 UV 表 |
| 类 | `SingleSpriteSquareTexturedSkybox` | `CubeAtlasSkyRenderer` |
| 接缝处理 | 旋转矩阵 | 精确 UV 表 |

---

## 八、太阳 / 月亮 / 装饰

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| 绘制方法 | `AbstractSkybox.renderDecorations()` | `SkyDecorationsRenderer` |
| 太阳贴图 | `Decorations.sunTexture` | `beloong:textures/environment/sun.png` |
| 月亮贴图 | `Decorations.moonTexture` | `beloong:textures/environment/moon_phases.png` |
| 星星 | vanilla `starsBuffer` | `stars.png` 图层 |
| 装饰旋转 | `Decorations.rotation` | vanilla 式旋转 |
| 是否受 alpha 控制 | 是 | 目前不完整 |

---

## 九、天气 / 条件

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| 条件系统 | `Conditions` | 无 |
| weather 检查 | `AbstractSkybox.checkWeather()` | 客户端直接隐藏雨雪 |
| rainAlpha | 有 | 无 |
| 视觉禁用天气 | 条件机制 | `LoongPalaceSkyEffects` 硬编码 |

---

## 十、渲染挂载点

| 项目 | NeoForgeSkyboxes | BeLoong Core |
|---|---|---|
| 渲染入口 | Mixin `LevelRenderer.renderSky` | `DimensionSpecialEffects.renderSky()` |
| 雾 | Mixin `FogRenderer` | `ViewportEvent.RenderFog` |
| 云 | 未重点处理 | `LoongPalaceSkyEffects.renderClouds()` |
| Mixin 使用 | 是 | 否 |

---

## 十一、关键结论

### 11.1 此前尝试的问题

尝试 `fadeAlpha × conditionAlpha` 后仍然硬切，问题出在：

- `conditionTarget = fadeAlpha > 0 ? 1 : 0` 将“时间亮度”和“激活状态”错误绑定；
- 当旧图层（如 Day）的 `fadeAlpha` 跳变为 0 时：
  ```java
  alpha = fadeAlpha * conditionAlpha = 0 * conditionAlpha = 0
  ```
- 因此旧图层会被瞬间清零，出现“新图层慢慢淡入、旧图层立刻消失”的硬切。

### 11.2 真正需要的行为

- 每个图层保存 **显示中的 alpha**；
- 目标值为时间 `fadeAlpha`；
- 显示 alpha 从旧值**逐渐趋近**目标值；
- 旧图层不应因为 `fadeAlpha` 变为 0 而被瞬间乘掉；
- `conditionAlpha` 如果保留，应只作为真正的“激活条件”因子，不能在退出时因 `fadeAlpha = 0` 直接裁剪。

### 11.3 修复方向

- 移除“用 `fadeAlpha × conditionAlpha` 作为最终 alpha”的错误做法；
- 使用单一 `DISPLAY_ALPHAS`：
  ```java
  DISPLAY_ALPHAS[i] = moveTowards(DISPLAY_ALPHAS[i], fadeAlpha, duration);
  ```
- 时间跳变使用更长的 `UNEXPECTED_TRANSITION_TICKS`；
- 最终渲染：
  ```java
  float alpha = DISPLAY_ALPHAS[i];
  ```
