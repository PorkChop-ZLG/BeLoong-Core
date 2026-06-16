# 天灾传送门自定义贴图渲染 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将天灾传送门方块的渲染贴图从原版星空贴图替换为自定义贴图，保留完整的旋转隧道着色器效果。

**Architecture:** 照搬原版末地传送门的完整渲染管线：创建自定义着色器程序（复用原版 .vsh/.fsh），通过 RegisterShadersEvent 注册，用自定义 RenderType 绑定新贴图。RenderType 通过 `RenderStateShard.ShaderStateShard` 引用注册的着色器，通过 `MultiTextureStateShard` 将两个 sampler 都指向自定义贴图。

**Tech Stack:** NeoForge 21.1.219, Minecraft 1.21.1, GLSL 150, Java 21

---

### 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| Create | `src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.json` | 着色器程序定义 |
| Create | `src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.vsh` | 顶点着色器（照搬原版） |
| Create | `src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.fsh` | 片元着色器（照搬原版） |
| Create | `src/main/resources/assets/beloong/textures/entity/disaster_portal.png` | 自定义贴图 |
| Modify | `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java` | 注册着色器 |
| Modify | `src/main/java/com/zonlong/beloong/client/DisasterPortalRenderer.java` | 使用自定义 RenderType |

---

### Task 1: 创建着色器程序定义 JSON

**Files:**
- Create: `src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.json`

- [ ] **Step 1: 编写着色器程序定义 JSON**

内容与原版 `rendertype_end_portal.json` 完全一致，仅 sampler 和 uniform 声明。

```json
{
    "vertex": "rendertype_disaster_portal",
    "fragment": "rendertype_disaster_portal",
    "samplers": [
        { "name": "Sampler0" },
        { "name": "Sampler1" }
    ],
    "uniforms": [
        { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
        { "name": "ProjMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
        { "name": "GameTime", "type": "float", "count": 1, "values": [ 0.0 ] },
        { "name": "EndPortalLayers", "type": "int", "count": 1, "values": [ 15 ] }
    ]
}
```

- [ ] **Step 2: 验证目录结构存在**

Run: `ls "e:\Minecraft\BeLoong-Core\src\main\resources\assets\beloong"`
Expected: 列出 `lang`, `textures` 等现有目录。确认 `shaders/core/` 的父目录存在。

- [ ] **Step 3: Commit**

```bash
git -C "e:\Minecraft\BeLoong-Core" add src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.json
git -C "e:\Minecraft\BeLoong-Core" commit -m "feat: 添加天灾传送门着色器程序定义"
```

---

### Task 2: 创建顶点着色器（照搬原版）

**Files:**
- Create: `src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.vsh`

- [ ] **Step 1: 编写顶点着色器**

内容完全复制原版 `assets/minecraft/shaders/core/rendertype_end_portal.vsh`：

```glsl
#version 150

#moj_import <projection.glsl>

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 texProj0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texProj0 = projection_from_position(gl_Position);
}
```

- [ ] **Step 2: Commit**

```bash
git -C "e:\Minecraft\BeLoong-Core" add src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.vsh
git -C "e:\Minecraft\BeLoong-Core" commit -m "feat: 添加天灾传送门顶点着色器（照搬原版 end_portal）"
```

---

### Task 3: 创建片元着色器（照搬原版）

**Files:**
- Create: `src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.fsh`

- [ ] **Step 1: 编写片元着色器**

内容完全复制原版 `assets/minecraft/shaders/core/rendertype_end_portal.fsh`：

```glsl
#version 150

#moj_import <matrix.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform float GameTime;
uniform int EndPortalLayers;

in vec4 texProj0;

const vec3[] COLORS = vec3[](
    vec3(0.022087, 0.098399, 0.110818),
    vec3(0.011892, 0.095924, 0.089485),
    vec3(0.027636, 0.101689, 0.100326),
    vec3(0.046564, 0.109883, 0.114838),
    vec3(0.064901, 0.117696, 0.097189),
    vec3(0.063761, 0.086895, 0.123646),
    vec3(0.084817, 0.111994, 0.166380),
    vec3(0.097489, 0.154120, 0.091064),
    vec3(0.106152, 0.131144, 0.195191),
    vec3(0.097721, 0.110188, 0.187229),
    vec3(0.133516, 0.138278, 0.148582),
    vec3(0.070006, 0.243332, 0.235792),
    vec3(0.196766, 0.142899, 0.214696),
    vec3(0.047281, 0.315338, 0.321970),
    vec3(0.204675, 0.390010, 0.302066),
    vec3(0.080955, 0.314821, 0.661491)
);

const mat4 SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

mat4 end_portal_layer(float layer) {
    mat4 translate = mat4(
        1.0, 0.0, 0.0, 17.0 / layer,
        0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (GameTime * 1.5),
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    mat2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));

    mat2 scale = mat2((4.5 - layer / 4.0) * 2.0);

    return mat4(scale * rotate) * translate * SCALE_TRANSLATE;
}

out vec4 fragColor;

void main() {
    vec3 color = textureProj(Sampler0, texProj0).rgb * COLORS[0];
    for (int i = 0; i < EndPortalLayers; i++) {
        color += textureProj(Sampler1, texProj0 * end_portal_layer(float(i + 1))).rgb * COLORS[i];
    }
    fragColor = vec4(color, 1.0);
}
```

- [ ] **Step 2: Commit**

```bash
git -C "e:\Minecraft\BeLoong-Core" add src/main/resources/assets/beloong/shaders/core/rendertype_disaster_portal.fsh
git -C "e:\Minecraft\BeLoong-Core" commit -m "feat: 添加天灾传送门片元着色器（照搬原版 end_portal）"
```

---

### Task 4: 注册自定义着色器

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`

- [ ] **Step 1: 添加 import 和静态字段**

在 `BeLoongCoreClient.java` 中，在现有 import 之后添加：

```java
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
```

在类体内部，添加静态字段存储着色器实例：

```java
/** 天灾传送门自定义着色器实例。由 RegisterShadersEvent 回调设置。 */
@Nullable
static ShaderInstance disasterPortalShader;
```

- [ ] **Step 2: 添加 RegisterShadersEvent 订阅方法**

在类中添加 `onRegisterShaders` 方法：

```java
@SubscribeEvent
static void onRegisterShaders(RegisterShadersEvent event) {
    event.registerShader(
            new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "rendertype_disaster_portal"),
                    DefaultVertexFormat.POSITION),
            shader -> disasterPortalShader = shader);
}
```

- [ ] **Step 3: Commit**

```bash
git -C "e:\Minecraft\BeLoong-Core" add src/main/java/com/zonlong/beloong/BeLoongCoreClient.java
git -C "e:\Minecraft\BeLoong-Core" commit -m "feat: 注册天灾传送门自定义着色器"
```

---

### Task 5: 创建自定义 RenderType 并更新渲染器

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/client/DisasterPortalRenderer.java`

- [ ] **Step 1: 修改 import 和添加自定义 RenderType 静态字段**

替换文件中现有的 import 区域。在 `import org.joml.Matrix4f;` 之后添加：

```java
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zonlong.beloong.BeLoongCoreClient;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;
```

在类体内部，`renderType()` 方法之前，添加：

```java
/** 自定义贴图路径：Sampler0 和 Sampler1 共用同一贴图。 */
private static final ResourceLocation DISASTER_PORTAL_LOCATION =
        ResourceLocation.fromNamespaceAndPath("beloong", "textures/entity/disaster_portal");

/**
 * 自定义 {@link RenderType}，照搬原版 {@code RenderType.endPortal()} 的结构：
 * <ul>
 *   <li>Shader: 自定义注册的 {@code rendertype_disaster_portal} 着色器程序</li>
 *   <li>Texture: 自定义贴图（Sampler0 + Sampler1 指向同一张图）</li>
 *   <li>其余渲染状态与原版 END_PORTAL 完全一致</li>
 * </ul>
 * <p>
 * 使用 lazy 初始化：ShaderInstance 由 {@code RegisterShadersEvent} 回调设置，
 * 发生在渲染首帧之前，因此首次渲染时 shader 已就绪。
 */
private static final Supplier<RenderType> DISASTER_PORTAL = () -> RenderType.create(
        "disaster_portal",
        DefaultVertexFormat.POSITION,
        VertexFormat.Mode.QUADS,
        1536,
        false,
        false,
        RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(
                        () -> BeLoongCoreClient.disasterPortalShader))
                .setTextureState(
                        RenderStateShard.MultiTextureStateShard.builder()
                                .add(DISASTER_PORTAL_LOCATION, false, false)
                                .add(DISASTER_PORTAL_LOCATION, false, false)
                                .build())
                .createCompositeState(false)
);

/** 缓存的 RenderType 实例，避免每次调用 renderType() 都重新创建。 */
private static RenderType disasterPortalRenderType;
```

- [ ] **Step 2: 修改 renderType() 方法**

将现有的 `renderType()` 方法替换为：

```java
/**
 * 获取渲染类型。
 * <p>
 * 使用自定义 {@link RenderType}，绑定天灾传送门专用着色器
 * 和自定义贴图（{@code textures/entity/disaster_portal.png}），
 * 产生旋转隧道视觉效果。
 * <p>
 * 首次调用时通过 {@link #DISASTER_PORTAL} lambda 创建 RenderType 实例并缓存，
 * 后续调用直接返回缓存实例。
 */
protected RenderType renderType() {
    if (disasterPortalRenderType == null) {
        disasterPortalRenderType = DISASTER_PORTAL.get();
    }
    return disasterPortalRenderType;
}
```

- [ ] **Step 3: 删除旧的 getOffsetUp/getOffsetDown** — 这两个方法是原版辅助方法，保持不变。确认不作修改。

- [ ] **Step 4: 删除不需要的 import**

确认以下旧 import 不再被引用后移除（如果 import 了 `RenderType.endPortal` 静态方法目前仅在注释中提及，import 本身在 `import net.minecraft.client.renderer.RenderType;` 行中保留 — 仍然需要 `RenderType` 类本身）。

- [ ] **Step 5: Commit**

```bash
git -C "e:\Minecraft\BeLoong-Core" add src/main/java/com/zonlong/beloong/client/DisasterPortalRenderer.java
git -C "e:\Minecraft\BeLoong-Core" commit -m "feat: 天灾传送门使用自定义 RenderType + 自定义贴图"
```

---

### Task 6: 构建验证

- [ ] **Step 1: 执行构建**

Run: `cd "e:\Minecraft\BeLoong-Core" && ./gradlew build 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 验证构建产物包含新文件**

Run: `jar tf "e:\Minecraft\BeLoong-Core\build\libs\beloong-*.jar" 2>/dev/null | grep -E "disaster_portal\.(json|vsh|fsh|png)|shaders/core" | head -10`
Expected: 列出 3 个着色器文件（不含贴图 — 贴图需手动放置）

- [ ] **Step 3: Commit 构建结果（如有文件变更）**

---

**注意：** 自定义贴图文件 `disaster_portal.png` 需手动放入 `src/main/resources/assets/beloong/textures/entity/` 目录。本计划不包含贴图的绘制，仅预留文件路径。当前可用纯色图片测试着色器管线通路。
