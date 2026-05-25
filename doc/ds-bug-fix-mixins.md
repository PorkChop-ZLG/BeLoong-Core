# DS Bug Fix Mixin 技术文档

## 概述

修复了两个 龙之生存 本体的BUG：
1. 稳定悬停漂移修复（ClientFlightHandlerMixin）
2. 发光效果龙身体隐形修复（DragonItemRenderLayerMixin + OutlineBufferSourceAccessor）

---

## Mixin 框架基础

Mixin 是一个允许模组修改 Minecraft 和第三方模组代码的框架，无需直接修改源代码。
BeLoong-Core 使用以下 Mixin 技术：

| 注解 | 用途 | 备注 |
|------|------|------|
| `@Mixin` | 声明要注入的目标类 | 可用 `.class` 或字符串指定内部类 |
| `@Accessor` | 访问目标类的私有字段，生成 getter 方法 | 必须定义为 interface |
| `@Shadow` | 在 mixin 类中映射目标类的私有字段/方法 | 用于 Inject 中读写目标类成员 |
| `@Inject` | 在目标方法执行前/后插入代码 | `@At("HEAD")` 开头，`@At("TAIL")` 末尾 |
| `@ModifyArgs` | 在目标方法调用前拦截并修改参数 | 比 Inject 更精确，针对方法调用点 |
| `@Unique` | 标记此成员是 mixin 自己新增的，目标类不存在 | 用于自定义字段和辅助方法 |
| `remap = false` | 方法名不需要映射转换 | 非 Mojang 混淆类（如 Dragon Survival）必须设为 false |

### remap 参数说明

Minecraft 原版代码经过混淆（obfuscation），在开发环境中使用映射名（如 `tick`），在运行时是混淆名（如 `m_8119_`）。
Mixin 框架通过映射表自动转换。但 Dragon Survival 和 GeckoLib 等第三方模组不使用 Mojang 映射，
所以必须设置 `remap = false`，否则 Mixin 会因找不到方法名而崩溃。

### accessor 命名空间（ds_bug_fix$ 前缀）

多个模组可能同时 mixin 到同一个类。如果一个模组的 accessor 叫 `getTeamR()`，另一个也叫 `getTeamR()`，
Mixin 会因方法名冲突而错误合并或报错。使用 `ds_bug_fix$` 前缀作为命名空间可避免此问题。

---

## Bug 1：稳定悬停漂移

### 问题描述

龙之生存的"稳定悬停"功能（`ServerFlightHandler.stableHover`）本意是让龙在空中静止不动。
但实际上悬停时龙会持续漂移：
- **生存模式**：龙缓慢水平漂移
- **创造模式**：龙缓慢向上飘升

### 根因分析

龙之生存的 `ClientFlightHandler.flightControl()` 方法负责每帧计算龙的飞行加速度（ax, ay, az）。
即使玩家没有任何方向键输入，且处于稳定悬停状态，该方法仍可能为 ax/az/ay 赋予非零值。

### 修复方案

使用 `@Inject` 在 `flightControl()` 方法末尾（TAIL）注入代码：

1. 检查 `Config.FIX_STABLE_HOVER` 开关
2. 获取当前玩家，验证是龙形态且可飞行
3. 判断是否处于"稳定悬停"状态（无跳跃、无潜行、无冲刺、无滑翔）
4. 判断是否无移动输入（WASD 均为零）
5. 若条件满足：清零 ax、az（水平加速度）
6. 若为创造模式：额外清零 ay，并将玩家垂直速度归零

### 为什么创造模式需要额外处理？

生存模式有重力，ay 清零后重力自然将龙拉回原位。创造模式没有重力且玩家可以自由飞行，
仅清零 ay 不足以阻止上飘——龙会保持已有的垂直动量持续上升。
因此需要 `player.setDeltaMovement(delta.x, 0, delta.z)` 直接清除垂直速度。

---

## Bug 2：发光效果导致龙身体隐形

### 问题描述

当龙口中含有物品，且龙自身带有 Glowing 发光效果时，龙的身体模型会完全透明化。

### 根因分析：OutlineBufferSource 的机制

Minecraft 使用 `OutlineBufferSource` 来渲染发光效果：

1. 渲染流程分为两个通道：
   - **普通通道**：渲染实体的正常外观（纹理、颜色）→ 写入 `bufferSource`
   - **发光通道**：渲染实体的发光轮廓 → 写入 `outlineBufferSource`
2. 两个通道的数据分别收集完毕后，在后处理阶段合成最终画面

问题出在龙口含物品的渲染环节——当龙自身带有 Glowing 效果时，Minecraft 会将龙的渲染包装在OutlineBufferSource 中：

1. 物品在龙手上的渲染是通过 `DragonItemRenderLayer.renderStackForBone()` 完成的
2. 此方法调用 GeckoLib 的 `BlockAndItemGeoLayer.renderStackForBone()` 来实际绘制物品
3. 传给 GeckoLib 的第 5 个参数是当前的 `MultiBufferSource`（渲染缓冲区）
4. 如果当前处于发光渲染状态，这个 buffer 就是 `OutlineBufferSource`
5. 物品的发光数据被写入了龙的 `outlineBufferSource`
6. 导致龙的发光通道数据被物品数据污染，龙身体无法正确渲染

简单类比：物品在龙的"发光画板"上画画，把龙本来的发光图案覆盖了，导致龙在发光通道中"消失"。

### 修复方案

核心思路：给物品创建一个**临时孤立的 Outline 缓冲区**，物品的发光数据写到这个独立缓冲区，
渲染完后立即刷出，龙的主 OutlineBufferSource 不受任何影响。

使用 `@ModifyArgs` + `@Inject` 组合：

**第一步（@ModifyArgs，在 renderStackForBone 调用前）：**
1. 检查 `Config.FIX_GLOWING_OUTLINE` 开关
2. 确认当前 buffer 是 `OutlineBufferSource`（否则说明不在发光渲染中，无需处理）
3. 通过 `OutlineBufferSourceAccessor` 读取原始 outlineBufferSource 中的正常缓冲区和队伍颜色
4. 创建独立 Outline 缓冲区：`MultiBufferSource.immediate(new ByteBufferBuilder(1536))`
5. 构造包装的 `MultiBufferSource`，将 args(4) 替换为：
   - 发光类型（`rt.isOutline()`）→ 写入独立的 itemOutlineBuf
   - 普通类型 + 有发光变体 → 通过 `VertexMultiConsumer` 同时写入 normalBuf 和 itemOutlineBuf
   - 纯普通类型 → 直接写入 normalBuf
6. 使用 `colorReplacing()` 强制发光顶点使用队伍颜色

**第二步（@Inject TAIL，在 renderStackForBone 结束后）：**
1. 调用 `itemOutlineBuf.endBatch()` 将所有缓存发光数据刷到屏幕
2. 将 `itemOutlineBuf` 置为 null，为下一帧做准备

### colorReplacing 包装器

发光轮廓的颜色应该与队伍颜色一致，而非物品纹理色。`colorReplacing()` 返回一个 VertexConsumer 包装器：
- `addVertex(x,y,z)` → 正常添加顶点，但覆盖颜色为队伍颜色
- `setColor(r,g,b,a)` → 忽略外部传入的颜色
- `setUv(u,v)` → 正常转发
- `setUv1/2`、`setNormal` → 忽略（发光轮廓不需要这些数据；被忽略的数据在 `VertexMultiConsumer` 的另一路（normal consumer）中正确处理）

---

## 配置系统

### ModConfigSpec 工作流程

1. 使用 `ModConfigSpec.Builder` 声明配置项（键名、类型、默认值、注释）
2. 调用 `BUILDER.build()` 生成不可变的 `ModConfigSpec` 对象
3. 在模组主类中通过 `container.registerConfig(ModConfig.Type.COMMON, Config.SPEC)` 注册
4. NeoForge 自动处理：TOML 文件生成、启动时读取、配置界面显示、修改保存

### 可配置项

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `fixGlowingItemInvisibility` | 布尔 | `true` | 修复龙口含物品且自身带发光效果时身体变透明 |
| `fixStableHover` | 布尔 | `true` | 修复稳定悬停漂移 |

---

## Mixin 配置注册

`beloong.mixins.json` 需要在 `neoforge.mods.toml` 中显式声明：

```toml
[[mixins]]
config = "beloong.mixins.json"
```

NeoForge MDG 2.x 不会自动发现 mixin 配置——必须在 mod 元数据中显式注册。
此外，三方 mixin 不需要 `refmap`，因为 class 直接引用不需要运行时混淆映射。

---

## 文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `src/main/resources/beloong.mixins.json` | 新建 | Mixin 配置，注册三个仅客户端 mixin |
| `src/main/java/.../mixin/OutlineBufferSourceAccessor.java` | 新建 | 访问 OutlineBufferSource 的 6 个私有字段 |
| `src/main/java/.../mixin/ClientFlightHandlerMixin.java` | 新建 | 修复稳定悬停漂移 |
| `src/main/java/.../mixin/DragonItemRenderLayerMixin.java` | 新建 | 修复龙口含物品且带发光效果时身体变透明 |
| `src/main/java/.../Config.java` | 修改 | 移除示例条目，添加两个修复开关 |
| `src/main/java/.../BeLoongCore.java` | 修改 | 清理旧配置引用 |
| `src/main/templates/META-INF/neoforge.mods.toml` | 修改 | 注册 mixin 配置 |
