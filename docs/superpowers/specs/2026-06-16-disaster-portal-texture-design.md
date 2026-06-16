# 天灾传送门自定义贴图渲染

## 目标

将天灾传送门方块的渲染贴图从原版末地传送门的星空贴图（`textures/entity/end_portal.png`）替换为自定义贴图（`textures/entity/disaster_portal.png`），同时保留原版着色器的旋转星空隧道视觉效果。

## 原理

原版末地传送门的渲染管线：

1. **方块层**：`RenderShape.INVISIBLE`，不渲染方块模型
2. **BlockEntity 层**：作为渲染器载体，`shouldRenderFace()` 仅对 Y 轴返回 true
3. **渲染器层**：写入四边形顶点，使用 `RenderType.endPortal()` 
4. **着色器层**：片元着色器将同一张贴图采样 16 次（1 次基础层 + 15 次循环），每层经过不同的缩放、旋转、位移和颜色叠加，产生旋转星空隧道效果

两个 sampler 槽位（Sampler0、Sampler1）在 RenderType 层面都绑定到同一张贴图。换贴图的关键是创建一个新的 RenderType，让两个 sampler 指向自定义贴图，而着色器代码完全复用原版。

## 新建文件

| 文件 | 说明 |
|------|------|
| `assets/beloong/shaders/core/rendertype_disaster_portal.json` | 着色器程序定义 |
| `assets/beloong/shaders/core/rendertype_disaster_portal.vsh` | 顶点着色器（照搬原版 `rendertype_end_portal.vsh`） |
| `assets/beloong/shaders/core/rendertype_disaster_portal.fsh` | 片元着色器（照搬原版 `rendertype_end_portal.fsh`） |
| `assets/beloong/textures/entity/disaster_portal.png` | 自定义贴图 |

## 修改文件

### BeLoongCoreClient.java

新增 `RegisterShadersEvent` 订阅方法，注册自定义着色器程序。

### DisasterPortalRenderer.java

将 `renderType()` 的返回值从 `RenderType.endPortal()` 替换为自定义的静态 `RenderType` 实例。该实例通过 `RenderType.create()` 构建，配置与原版 `endPortal()` 一致，仅纹理路径不同。

## 自定义 RenderType 结构

```java
RenderType.create("disaster_portal",
    DefaultVertexFormat.POSITION,
    VertexFormat.Mode.QUADS,
    1536, false, false,
    CompositeState.builder()
        .setShaderState(→ 自定义注册的 shader)
        .setTextureState(→ beloong:textures/entity/disaster_portal.png)
        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
        .setCullState(NO_CULL)
        .setLightmapState(NO_LIGHTMAP)
        .setOverlayState(NO_OVERLAY)
        .setDepthTestState(NO_DEPTH_TEST)
        .setWriteMaskState(COLOR_WRITE)
        .createCompositeState(false)
);
```

## 与原版对照

| 原版 | 本模组 |
|------|--------|
| `minecraft:shaders/core/rendertype_end_portal.json` | `beloong:shaders/core/rendertype_disaster_portal.json` |
| `rendertype_end_portal.vsh` | 照搬，内容不变 |
| `rendertype_end_portal.fsh` | 照搬，内容不变 |
| `textures/entity/end_portal.png` | `textures/entity/disaster_portal.png` |
| `RenderType.endPortal()` | 自定义 `DISASTER_PORTAL_RENDER_TYPE` |

## 着色器文件说明

`.vsh` 和 `.fsh` 内容完全复制原版，不做任何修改。其中 `#moj_import <projection.glsl>` 和 `#moj_import <matrix.glsl>` 这两个引用通过 Minecraft 的资源管理器解析，会自动找到原版 `assets/minecraft/shaders/include/` 下的文件，无需额外处理。

## 不涉及

- DisasterPortalBlockEntity — 不变
- DisasterPortalBlock — 不变
- 传送逻辑 — 不变
- 配置项 — 不变
