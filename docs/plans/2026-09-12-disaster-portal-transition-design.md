# 天灾传送门过渡体验（视角扭曲 + 专用加载背景）设计文档

**Date:** 2026-09-12
**Status:** Approved（用户确认：① 天灾门贴图铺满 + 轻压暗；② 进入/离开双向注册；③ 保留压暗层）
**Approach:** B = A（原版 `CONFUSION` 局部过渡）+ 专用维度过渡界面（NeoForge API，无 Mixin）

## Problem Statement

天灾传送门目前"穿门无声无息"：进门没有任何过渡反馈，换维度时客户端只显示**通用**的"加载地形中"
（`ReceivingLevelScreen` 的 `OTHER` 分支：模糊后处理 + 半透明菜单底图）。
用户要求复刻下界传送门的体验：**进门时的视角扭曲过渡**，并把"加载地形中"的背景换成**天灾门主题**。

## Research（原版机制，已逐行核实）

### 视角扭曲过渡（下界门同款）

| 环节 | 位置 | 说明 |
|---|---|---|
| 方块声明 | `NetherPortalBlock:229-231` | `Portal#getLocalTransition()` 返回 `CONFUSION`（默认 `NONE`） |
| **客户端登记** | `NetherPortalBlock:107-111` | `entityInside` **两侧都** `entity.setAsInsidePortal(...)`；`handlePortal()` 内含 `ServerLevel` 判断，客户端不传送 |
| 每 tick 驱动 | `LocalPlayer:728-729 / 892-893` | 非 `ReceivingLevelScreen` 时读 `portalProcess.getPortalLocalTransition()` |
| 强度累积 | `LocalPlayer:904-934` | 门内 `spinningEffectIntensity += 0.0125`（上限 1）、自动关容器、播放 `PORTAL_TRIGGER` 环境音；离开 `-0.05`；反胃药水共用该强度 |
| 镜头扭曲 | `GameRenderer:1257-1269` | `f3 = lerp(...) × screenEffectScale²`，绕 `(0, √2/2, √2/2)` 旋转投影矩阵 + 沿 X `scale(1/f4,1,1)` |
| 全屏叠色 | `Gui:290-295 / 1355-1375` | **硬编码**取 `getParticleIcon(Blocks.NETHER_PORTAL.defaultBlockState())` 铺满屏，alpha 随强度 |
| 跨维度连续 | `ClientPacketListener:1228-1229` | 把旧 `LocalPlayer` 的强度复制给新实例 |

### "加载地形中"界面的背景

`ReceivingLevelScreen:56-69` 按 `Reason` 三分支：

| Reason | 背景实现 |
|---|---|
| `NETHER_PORTAL` | `blit(0,0,-90,guiWidth(),guiHeight(), 下界门粒子贴图)` —— **把方块模型 `particle` 贴图拉伸铺满** |
| `END_PORTAL` | `fillRenderType(RenderType.endPortal(), …)` —— 末地门 shader 铺满 |
| `OTHER` | `renderPanorama` + **`renderBlurredBackground`**（`Screen:387-392` → `GameRenderer.processBlurEffect:355-361` → `PostChain`，资源 `minecraft:shaders/post/blur.json`，uniform `Radius = menuBackgroundBlurriness`）+ `renderMenuBackground`（`textures/gui/inworld_menu_background.png` 32×32 平铺） |

`Reason` 由 `ClientPacketListener.determineLevelLoadingReason:1237-1248` 决定：仅**下界/末地**特殊化，
**其余一律 `OTHER`** ⇒ 进天灾维度今天只能拿到通用模糊背景。

### 自定义维度过渡界面的官方入口（NeoForge 21.1.236 已确认存在）

`RegisterDimensionTransitionScreenEvent`（mod 总线、仅客户端；`registerIncomingEffect` /
`registerOutgoingEffect` / `registerConditionalEffect`，优先级 conditional > to > from）
配合 `DimensionTransitionScreenManager.getScreen(toDim, fromDim)`（兜底 `ReceivingLevelScreen::new`）。
工厂签名：`ReceivingLevelScreen create(BooleanSupplier levelReceived, ReceivingLevelScreen.Reason reason)`。

## Design

### Architecture

复用原版/NeoForge 两条既有机制，**不写 Mixin、不改任何传送逻辑、不改服务端行为**：

1. 天灾门方块声明 `CONFUSION`，并在**客户端也登记** `portalProcess`（下界门同构）⇒ 获得扭曲 + 环境音 + 自动关容器；
2. 用 `RegisterDimensionTransitionScreenEvent` 注册自定义 `ReceivingLevelScreen` 子类 ⇒ 把"加载地形中"背景换成天灾门贴图。

### Components

| # | 文件 | 改动 |
|---|---|---|
| 1 | `block/DisasterPortalBlock.java` | ①`entityInside` 增加**客户端分支**（仅 `setAsInsidePortal`；服务端分支一行不改）；②新增 `getLocalTransition() → CONFUSION`；③新增公开常量 `DISASTER_LEVEL`（`ResourceKey<Level>`）并让 `disasterLevel()` 复用它（单一事实来源） |
| 2 | `client/DisasterPortalTransitionScreen.java` | **新增**：`@OnlyIn(Dist.CLIENT) extends ReceivingLevelScreen`；构造 `(BooleanSupplier, Reason)`；重写 `renderBackground`：`Reason.OTHER` 时按**贴图原始尺寸 256×256 平铺**（`blit(..., -90, 0, 0, width, height, 256, 256)`，UV 基准取 256 ⇒ uv>1 由 GL_REPEAT 拼接，1 贴图像素 = 1 GUI 像素）+ `fill(0,0,w,h, 0x40000000)` 压暗；非 `OTHER` 时回退 `super`（防止该界面被下界/末地过渡复用） |
| 3 | `BeLoongCoreClient.java` | 新增 mod-总线订阅：`registerIncomingEffect(DISASTER_LEVEL, …)` + `registerOutgoingEffect(DISASTER_LEVEL, …)`（进出天灾维度同款背景，与 `determineLevelLoadingReason` 的双向判定对称） |

### Data Flow

```
接触门方块
 ├─ 客户端：entityInside → setAsInsidePortal → portalProcess
 │    → LocalPlayer.tick 每 tick 见 CONFUSION → spinningEffectIntensity ↑
 │       → 镜头扭曲 + 全屏紫幕 + PORTAL_TRIGGER 环境音
 ├─ 服务端：完全保持现状（冷却 → stopRiding → 登记 → 票据预热 → getPortalDestination → changeDimension）
 └─ 维度切换瞬间：Minecraft.setLevel → DimensionTransitionScreenManager.getScreen(to,from)
       → 命中注册 → DisasterPortalTransitionScreen（背景 = 天灾门贴图 + 压暗）
       → levelReceived 为真即关闭；spinningEffectIntensity 复制到新 LocalPlayer 后渐出
```

### Error Handling / 边界

| 情形 | 行为 |
|---|---|
| `Reason` 为 `NETHER_PORTAL`/`END_PORTAL`（例如从下界穿门进天灾） | 回退 `super.renderBackground`，保留原版该维度的专用背景，不被我们覆盖 |
| 玩家在门内开着容器界面 | 原版行为：自动关闭（`LocalPlayer:912-917`） |
| 加载屏显示期间 | `LocalPlayer:728` 不处理扭曲 ⇒ 扭曲不会叠在加载屏上 |
| 客户端登记对所有实体生效 | 与原版一致；`handlePortal()` 含 `ServerLevel` 判断 ⇒ 客户端不会传送 |
| 专用服务器 | 新类仅在 `Dist.CLIENT` 加载（现有 `@EventBusSubscriber(value = Dist.CLIENT)` 保证）；方块改动为数据无关的纯客户端分支 |
| 贴图缺失/异常尺寸 | `blit` 的 UV 基准取**贴图原始尺寸**（`TEXTURE_WIDTH/HEIGHT = 256`，必须与 PNG 一致）；`textureWidth/Height` 传原始尺寸 ⇒ uv>1 由 GL_REPEAT 平铺，传屏幕尺寸会被整张拉伸而发糊。贴图缺失时客户端会记 missing texture（不影响功能） |

## Decisions Made

1. **选 B（A + 专用背景）**：只做 A 的话"加载地形中"仍是通用模糊底；只做 B 则没有扭曲。
2. **背景 = `disaster_portal.png` 按原始尺寸平铺 + 轻压暗**，而非 `RenderType.endPortal()`：与"我们自己的门"观感一致，且不引入 end-portal 管线（与 Iris 兼容改造的既有取舍一致）。
   **平铺而非拉伸**：该贴图是"四方连续"设计（256×256，POT）；`GuiGraphics#blit` 的 UV = `(uOffset+uWidth)/textureWidth`，把 `textureWidth/Height` 传成**贴图原始尺寸**即可让 uv>1、由 GL_REPEAT 天然拼接（与原版 `Screen#renderMenuBackgroundTexture` 的菜单底图同款做法）。若传屏幕尺寸，整张贴图被拉伸到全屏，画面明显发糊。
3. **进入/离开双向注册**：与 `determineLevelLoadingReason` 的双向判定对称，避免"回程没有主题背景"。
4. **保留压暗层 `0x40000000`**：保证"加载地形中"白字可读。
5. **不做 C（Mixin `Gui.renderPortalOverlay` 换叠色）**：全屏叠色仍是原版硬编码的下界门紫图，用户已确认接受；避免对 `Gui` 的 Mixin。
6. **进门倒计时最终仍为 `0`（2026-09-12 曾试 `80`，2026-09-13 实机回归后撤销）**：本设计保留
   `getPortalTransitionTime() == 0`。后续审查轮一度改成 `80`（= 原版下界门默认
   `playersNetherPortalDefaultDelay`），想让 `LocalPlayer` 的扭曲（每 tick `+0.0125`，
   `LocalPlayer.java:925`）在换维度前涨满、避免"加载屏立刻接管"。
   **实机证明代价不可接受**：`run/logs/debug-2.log` 连续 4 次 `waited=81 tick` —— 落点**已就绪**时
   也要白等约 4 秒；而落点未就绪时（`latest.log` 的 `waited=130`）它并不能让传送提前。故回到 `0`。
   <br>**本设计的观感目标因此改为"条件性成立"**：落点立即就绪时扭曲几乎不可见（由本设计的专用加载屏
   承担视觉），只有落点未就绪（本维度常态，生成需 6–6.5 s）时玩家才会在门里停留足够久、扭曲涨满。
   同时把落点等待上限由 200 提到 600 tick（见 Decisions 7）。审查依据见
   [`docs/reviews/2026-09-12-post-0.9.1-code-review.md`](../reviews/2026-09-12-post-0.9.1-code-review.md)。
7. **落点等待上限 200 → 600 tick（2026-09-13 实机回归）**：上限自进门起算；本维度地形生成实测
   122–130 tick（约 6–6.5 s），200 只剩约 1.5 倍余量、稍慢即"传送失败"，600（≈30 s）给出约 4.6 倍余量。

## Non-Goals

- 不改任何传送逻辑（冷却、落点、票据、超时、`DimensionTransition`）
  （**例外**：2026-09-12/13 的后续两轮把 `getPortalTransitionTime()` 由 `0` 试改 `80` 后回调至 `0`，
  并把 `DESTINATION_WAIT_TIMEOUT_TICKS` 由 `200` 提到 `600`，见 Decisions 6/7——
  前者属"门的过渡节奏"，后者是纯等待预算，都不改变"不阻塞主线程"的机制）
- 不改服务端行为与报文；不新增自定义渲染管线/着色器
- 不做 Mixin；不替换原版全屏紫幕贴图
- 不改 `ReceivingLevelScreen` 的 `levelReceived`/30 s 超时语义

## Verification Strategy

1. `gradlew.bat build` 通过（客户端类同包，服务端专用环境不受影响）
2. 实机：进门 → 扭曲渐入 + `PORTAL_TRIGGER` 环境音（**落点未就绪时会明显涨满**；落点立即就绪时
   加载屏会马上接管，扭曲基本看不到——这是 2026-09-13 与"接触即传送"交换后的既定取舍）；出门 → 渐出
3. 实机：穿门瞬间"加载地形中"背景 = 天灾门贴图 + 压暗（不再是模糊菜单底）；**上行（天灾 → 主世界）同样生效**
4. 回归：传送时机为"接触即开始"（日志应出现 `waited=1` 起的小值，而非固定 `waited=81`）；
   落点长时间未就绪时不会在 200 tick 就放弃（阈值已为 600）；无客户端崩溃；从下界/末地穿门时仍是原版专用背景

## Implementation Order

1. `DisasterPortalBlock`（客户端分支 + `CONFUSION` + `DISASTER_LEVEL`）
2. `DisasterPortalTransitionScreen`（新）
3. `BeLoongCoreClient` 注册
4. 构建 + 静态核查
5. 文档同步（总设计 §3.6/§八/§九、复盘文档决定表、`memory/decisions-log.md`）
