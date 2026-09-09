# 龙宫 Dramatic Skys 完整迁移计划

## 一、目标

在已完成的第一阶段基础上，将 Dramatic Skys 资源包剩余内容完整迁移到龙宫维度：

1. 月亮遮罩层
2. 太阳本体
3. 月亮本体 / 月相
4. 日出 / 日落天空层
5. 阳光 flare 层
6. 完整图层旋转
7. 天气状态下仍保持晴天外观
8. 龙宫禁用云，保留原版雾
9. 参考 NeoForgeSkyboxes 源码，但不使用 Mixin 修改 Minecraft 原版
10. 完成后等待批准再执行

## 二、已确认决策

| 项目 | 决策 |
|---|---|
| 天气 | 仅客户端视觉禁用：始终晴天外观，隐藏雨雪/雷声/云 |
| 雾 | 保留原版雾，不新增禁雾处理 |
| 太阳/月亮 | 分层贴图与实际太阳月亮贴图分离 |
| 旋转 | 完整移植资源包中的层旋转 |
| Mixin | 不使用任何新增 Mixin 修改 Minecraft 原版 |
| 云 | 客户端通过 `DimensionSpecialEffects.renderClouds` 禁用 |
| 服务器天气 | 保留原版服务器状态，不额外抑制 |

## 三、当前已完成

- 单图集 3×2 Cube UV 渲染
- 昼夜四层：`stars` / `mask` / `day` / `night`
- `dayFade` / `nightFade`
- `alpha` / `add` / `screen` 混合
- `has_skylight: true`，保留原版服务器天气

## 四、资源包待迁移资源

需要从 Dramatic Skys 复制：

```
assets/skybox/
├── mask_moon.png       # 月亮遮罩层
├── sun.png             # 日出/日落天空层
└── sunflare.png        # 阳光 flare 层

assets/minecraft/textures/environment/
├── sun.png             # 实际太阳贴图
└── moon_phases.png     # 月相贴图
```

计划放到模组资源目录：

```
src/main/resources/assets/beloong/textures/
├── skybox/
│   ├── mask_moon.png
│   ├── sun.png
│   └── sunflare.png
└── environment/
    ├── sun.png
    └── moon_phases.png
```

`celestial/moon/*.png` 可选，不强制迁移；优先使用 `moon_phases.png` 实现月相。

## 五、客户端渲染架构

### 5.1 扩展 `DramaticSkyRenderer`

从当前四层扩展为完整配置：

| 图层 | 贴图 | 混合 | Alpha | 旋转 |
|---|---|---|---|---|
| Stars | `stars.png` | alpha | `nightFade` | 绕 Z |
| MaskMoon | `mask_moon.png` | alpha | `nightFade` | 绕 Z |
| Mask | `mask.png` | alpha | `nightFade` | 绕 Y |
| Day | `day.png` | screen | `dayFade` | 绕 Y |
| Night | `night.png` | screen | `nightFade` | 绕 Y |
| Sunset | `sun.png` | screen | `sunsetFade` | 绕 Y |
| Sunrise | `sun.png` | screen | `sunriseFade` | 绕 Y |
| SunflareSunset | `sunflare.png` | screen | `sunsetFade` | 绕 Z |
| SunflareSunrise | `sunflare.png` | screen | `sunriseFade` | 绕 Z |

参考 NeoForgeSkyboxes 实际加载的 fabricskyboxes JSON，使用离散 fade 区间：

- Day：`23666-333 / 11666-12333`
- Night：`13333-13666 / 22333-22666`
- Sunset：`11666-12333 / 13333-13666`
- Sunrise：`22333-22666 / 23666-333`

### 5.2 新增 `SkyLayerConfig` / `SkyRotation` 数据

从 NeoForgeSkyboxes 的 `Rotation` / `Blend` / `Fade` 思路简化出本模组版本：

```
SkyLayerConfig {
    ResourceLocation texture;
    SkyBlendMode blend;
    SkyRotation rotation;
    FadeRange fade;
}
```

`SkyRotation` 支持：

- `rotationSpeedX`
- `rotationSpeedY`
- `rotationSpeedZ`
- `timeShift`
- `skyboxRotation`
- `axis`
- `static`

旋转计算参考 NeoForgeSkyboxes `Utils.calculateRotation`。

### 5.3 太阳 / 月亮本体绘制

新增 `SkyDecorationsRenderer`：

- 在 `renderSky` 完成图层后绘制太阳/月亮。
- 太阳：
  - 使用 `beloong:textures/environment/sun.png`
  - 按 `skyAngle` 旋转绘制 quad。
- 月亮：
  - 使用 `beloong:textures/environment/moon_phases.png`
  - 按 `world.getMoonPhase()` 取对应 UV 区域绘制。
- 月亮遮罩层 `mask_moon.png` 用于配合月亮渲染的遮挡效果。
- 不依赖原版 `LevelRenderer` 的 `starBuffer`，星空由 `stars.png` 图层处理。

### 5.4 天气视觉禁用（客户端）

在 `LoongPalaceSkyEffects` 中增加：

```java
@Override
public boolean renderClouds(...) {
    return true; // 禁用云
}

@Override
public boolean renderSnowAndRain(...) {
    return true; // 禁用雨雪渲染
}

@Override
public boolean tickRain(...) {
    return true; // 禁用雨声/雨粒子
}
```

`renderSky` 始终绘制晴天 Dramatic Skys 图层，不因 `isRaining()/isThundering()` 隐藏。

### 5.5 保留雾（不再禁用）

龙宫保留原版雾逻辑：

- 不注册 `LoongPalaceFogHandler`。
- 不监听 `ViewportEvent.RenderFog`。
- 地形雾、天空雾、水中雾均按原版/维度类型处理。
- 仅继续在客户端隐藏云、雨雪、雨声。

## 六、代码改动清单

### 新增

```
client/sky/
├── SkyLayerConfig.java
├── SkyRotation.java
├── SkyFade.java
├── SkyDecorationsRenderer.java
└── DramaticSkyRenderer.java  # 扩展
```

### 修改

```
client/sky/
├── DramaticSkyRenderer.java
├── SkyBlendMode.java
├── SkyLayer.java
└── LoongPalaceSkyEffects.java
```

- `DramaticSkyRenderer` 从四层扩展为九层并接入旋转。
- `LoongPalaceSkyEffects` 增加云/雨雪/雨声禁用，并绘制太阳月亮。
- 不注册任何雾处理器；龙宫保留原版雾。

### 资源

复制以上四个贴图到模组资源目录。

## 七、不需要的改动

- 不修改 `dimension_type/*.json`（继续 `has_skylight: true`）。
- 不新增 Mixin。
- 不修改天灾维度。
- 不修改服务器天气逻辑。

## 八、实施步骤

1. 复制 `mask_moon.png` / `sun.png` / `sunflare.png` 与 sun/moon 实际贴图到资源目录。
2. 实现 `SkyRotation` / `SkyFade` / `SkyLayerConfig`。
3. 扩展 `DramaticSkyRenderer`：
   - 九层渲染顺序
   - 完整 fade
   - 层旋转
4. 实现 `SkyDecorationsRenderer` 绘制太阳和月相。
5. 接入 `LoongPalaceSkyEffects`：
   - 禁用云
   - 禁用雨雪/雨声
   - 始终晴天视觉
   - 绘制太阳/月亮
6. 不注册 `LoongPalaceFogHandler`，保留原版雾。
7. 编译并进入游戏验证。
8. 更新迁移计划文档为完成状态。

## 九、验证清单

1. 龙宫：
   - 白天/夜晚/日出/日落/flare 图层完整显示。
   - 太阳、月亮、月相显示正确。
   - 星星和图层旋转正确。
2. 天气：
   - 即使下雨/下雪/打雷，龙宫仍显示晴天外观。
   - 无雨雪粒子、雨声、云；保留原版雾。
3. 服务器：
   - 原版天气状态仍存在，但客户端不呈现。
4. 天灾：
   - 保持原版，不受影响。
5. 无 Mixin：
   - 不新增 targeting Minecraft 原版的 Mixin。

## 十、风险与待确认

- 雾保留后需确认龙宫远处地形/天空雾观感是否符合预期；不再取消 `ViewportEvent.RenderFog`。
- 太阳/月亮绘制位置与旋转角度需在游戏内微调。
- 完整天气“视觉禁用”属于客户端表现；服务器仍会运行天气/发送天气包，若未来在意性能，需要额外讨论服务端方案。
- 如果某些雾/云仍由其他模组（Sodium/Iris）渲染，可能还需要与它们交互；当前不处理。

---

> 计划已按 2026-09-09 修复设计更新；实施见 `docs/plans/2026-09-09-dramatic-sky-fix-plan.md`。
