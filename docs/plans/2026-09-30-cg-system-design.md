# 过场动画系统（CG 系统）设计文档

**日期**：2026-09-30
**状态**：**已批准**（用户逐节确认 §1 架构、§2 组件、§3 编排、§4 数据流、§5 错误处理、§6 验证策略）
**选定方案**：**方案 A** —— 服务端一次性烘制静态过场 + fdlib 纯静态播放

---

## 一、问题陈述

本模组需要**电影级过场播放**能力：一条指令就能让一个实体播一段指定动画，同时接管玩家相机、隐藏 HUD，
按编排好的镜头运动，播完自动归还。

**第一条 CG**：末的登场（`mo_entrance`）—— 播放末的 `descend`（下降）动画，相机先在低处对准高空的身体、
再缓慢降为平视、然后停住，动画结束即退出。

**给谁用**：整合包作者 / 服主 —— 用指令驱动，用于剧情演出。内容写死在 Java 里（不做数据驱动）。

**用户给定的五条硬需求**：
1. fdlib 从可选依赖改为**必选**；
2. 新增指令 `/beloong cg <target> play <cg名>`，`<target>` **只能是单个实体**；
3. 用 `<target>` 实体的位置来计算偏移与相机位置；
4. 每条 CG 一个 Java 类，**写死**，不数据驱动；
5. 第一条 = 末的登场。前提：玩家与末在同一水平面、相距 8 格、玩家看向末。

**四条用户裁定**（本轮 brainstorming 产出）：

| 议题 | 裁定 |
|---|---|
| 谁观看 | **只有执行指令的玩家**。其他人照常游戏 |
| 相机机位锚点 | **完全由末决定**：`末.pos + 末.forward × 8 + 眼高` |
| pitch 曲线来源 | **写死一条缓动曲线**，不参考动画的实际高度 |
| 开场处理 | **一开始就对准高处**（+44.3° → +59.8°），不加黑场 |
| 8 格边界的朝向风险 | **接受**，靠"执行者站在末面前"这个操作前提 |

---

## 二、已核实的既有事实（全部附行号）

### 2.1 fdlib 侧（**1.0.9**，与本项目依赖的 jar 同版本）

> **版本已核实**：`fdlib-1271749-7844741.jar`（Gradle 缓存）= `【前置】fdlib-1.0.9-1.21.1.jar`（整合包 mods 目录），
> 两者同为 973,335 字节；开箱列内容确认 `com/finderfeed/fdlib/systems/cutscenes/` 下 **19 个 `.class` 齐全**
> （22 个 zip 条目 = 19 个 class + 3 个目录条目），
> 且 `fdlib.mixins.json` 含 `KeyboardInputMixin` / `LocalPlayerMixin` / `MouseHandlerMixin`。
> 参考源码 `D:\Minecraft\开源模组参考文件\FDLib` 的 `mod_version=1.0.9`，与实际运行版本一致。

| 事实 | 依据 |
|---|---|
| 有**公开静态**入口：`startCutsceneForPlayer(ServerPlayer, CutsceneData)` | `FDLibCalls.java:40-42`（同类还有 `startCutsceneForPlayers` / `moveCutsceneCameraForPlayer` / `stopCutsceneForPlayer`） |
| `CutsceneData` 的可调项：`cameraPositions` / `time` / `moveCurveType` / `timeEasing` / `lookEasing` / `stopMode` / `nextCutscene` / `addScreenEffect` | `CutsceneData.java:18-101` |
| `CameraPos` 有 `(pos, yaw,pitch,roll)` 与 `(pos, lookDirection)` 两种构造；后者内部 `.normalize()` 并调 `yRotFromVector`/`xRotFromVector` | `CameraPos.java:35-41` |
| ⚠️ `CameraPos(pos, lookDirection)` 对**接近 `(0, ±1, 0)`** 的方向会异常（源码注释明说）；pitch 被 clamp 到 ±90 | `CameraPos.java:30,34` |
| **关键点只能等距**：第 i 个关键点落在 `t = i/(n−1) × 总时长` | `NormalLookProcessor.java:23-27`（`globalPercent = p × (positions.size()−1)`）、`LinearCameraMotion.java:27-31` 同构 |
| `timeEasing` 作用于**全局百分比**、`lookEasing` 作用于**段内** | `NormalLookProcessor.java:21,27` |
| look 逻辑**写死**：`CutsceneExecutor` 构造器硬编码 `new NormalLookProcessor()`，private 且无 setter ⇒ **无法接管、无法自动看向移动实体** | `CutsceneExecutor.java:24-32` |
| 位置每 tick 离散采样（`partialTick` 传 0） | `CutsceneExecutor.java:49-51,79-82` |
| 但靠维护 `xo/yo/zo` 交给原版 `Entity.getPosition(partialTick)` 做帧间插值 | `CutsceneExecutor.java:36-38` |
| 朝向**每帧插值**（传真实 `partialTick`） | `CutsceneCameraHandler.java:144` |
| ⚠️ **客户端入口零校验**：`data.getCameraPositions().getFirst()` 无判空 | `CutsceneCameraHandler.java:175`（空列表 ⇒ 客户端抛 `NoSuchElementException`；且 `LinearCameraMotion`/`CatmullRomCameraMotion` 每 tick 还会再抛一次） |
| ⚠️ **只有 `AUTOMATIC` 会自行归还相机**：`PLAYER`/`UNSTOPPABLE` 在 `hasEnded()` 后什么都不做 | `CutsceneCameraHandler.java:79-88` |
| 过场期间的"电影模式"：锁移动/锁视角/吞攻击键/隐藏全部 GUI 层/不画手/不画方块高亮/强制第一人称 | `CutsceneCameraHandler.java:104-151` |
| fdlib 自带急救命令（`/fdlib fix cutscene` ⇒ `stopCutsceneForPlayer`） | `FDCommandsRegistry.java:103-120` |
| 过场**不加载区块** | `CutsceneData.java:42`（源码注释） |

### 2.2 本模组侧

| 事实 | 依据 |
|---|---|
| **已在无守卫地调用 fdlib** —— `FDLibCalls.sendScreenEffect` + `FDScreenEffects.SCREEN_COLOR` + `ScreenColorData` | `item/effect/DawnLightEffect.java:6-8,91-94` |
| `mods.toml` 已把 `fdbosses` 声明为 `required`，而 fdbosses 自身 requires fdlib | `neoforge.mods.toml:97-102` |
| 故"把 fdlib 设为必选"是**把现状写进声明**，不是引入新依赖 | — |
| `build.gradle` 现状：fdlib 同时是 `compileOnly` + `localRuntime` | `build.gradle:189-190` |
| 命令注册点：`onRegisterCommands` 目前只有一行 | `BeLoongCore.java:213-216` |
| 命令形状先例：`/beloong <子命令> <targets> <动作>`，`hasPermission(2)`，`word()` + `SuggestionProvider` 而非逐项 `literal` | `NpcCommand.java:87-146` |
| **表情系统已实现"播一条一次性动画、播完自动回落"** —— 非循环动画播满 `animation.length()` tick 后置 `emoteDone`，`main` 控制器接管回状态动画 | `NpcEntity.java:1245-1287` |
| `setEmote` 走 `entityData.set(..., force = true)` ⇒ **同一条动画可以重播** | `NpcEntity.java:709-719` |
| 客户端收到同步通知时清 `emoteSeen`/`emoteDone`/`emoteStartTick` ⇒ 重播能重新计时 | `NpcEntity.java:730-738` |
| `hasActiveEmote()` **会查资产**：动画名查不到时返回 false ⇒ `main` 不让位 ⇒ 状态动画照旧（优雅降级） | `NpcEntity.java:1304-1318` |
| 资源缺失/名字拼错一律**英文 WARN + 每名一次** | `EmoteAnimationLookup.java:82-126` |
| 动画过渡 5 tick（GeckoLib 会把硬切混成 0.25 秒的平滑位移） | `NpcEntity.java:133-135` |
| `MoRenderer` 的模型缩放 = **0.80**（依据 `32 / 39.82`，让末与原版玩家等高） | `MoRenderer.java:51-69` |
| 辅助类风格先例：`public final` + 私有构造 + 静态方法 + 失败留**每 id 一次**英文 WARN + javadoc 引用设计文档 | `NpcDialogueStage.java:39-119` |

### 2.3 末的资产侧

| 事实 | 依据 |
|---|---|
| `descend` 在 `mo.extra.animation.json`（同文件另有 `sit` / `dance`） | 该文件 |
| `animation_length = 6` 秒 ⇒ **120 tick**（GeckoLib 按 `animation_length × 20` 折算） | `NpcEntity.java:1275` |
| **`Root` 骨骼位移全程恒定为 `(0, 0.332, 0)` ⇒ 实体本身一动不动** | 该文件 |
| 全部动效在 `AllBody` 骨骼上；`AllBody` 的 pivot 是 `(0, 18.7, 0)` | `mo.extra.animation.json` + `mo.geo.json` |
| 单位换算：`模型单位 × (1/16) × MODEL_SCALE(0.80)` = **× 0.05 = 格** | `MoRenderer.java:63` |
| **交叉验证**：同文件其它动画的骨骼位移幅度只有 5–37 单位（`sit` 18.6 / `dance` 9.7 / `attack` 36.6），**只有 `descend` 是 288** ⇒ 它是刻意做的"从高空飞入" | 实测全部 8 条动画 |

**`descend` 的时间/高度曲线**（`h` = 相对末脚底的体高）：

| 时刻 | `AllBody` Y 偏移 | 体高 h | 仰角（相机在 8 格外、眼高 1.62） |
|---|---|---|---|
| t = 0 | +169.86 单位 | 9.43 格 | **+44.3°** |
| t ≈ 34（1.7067 s） | **+288.50**（极值） | 15.36 格 | **+59.8°** |
| t ≈ 68（3.4267 s） | −12.79 | 0.30 格 | −9.4° |
| t = 112.5（5.6267 s，最后一个关键帧） | −0.63 | 0.90 格 | −5.1° |

⇒ **峰值在 34 tick、落地在 68 tick**。这两个 tick 与 Y 轴正负号约定无关（取的是绝对值极值），
且"descend"这个命名 + 用户描述共同锁定"正 Y = 上"。
<p>

> ⚠️ 口径澄清（2026-09-30 审查 I/M-4 修正）：动画总长是 **120 tick = 6.0 s**；
> `5.6267 s` 是 `descend` 的**最后一个关键帧**时刻（= 112.53 tick），资产把 −0.631 从那里保持到 6.0 s。
> 本行的 −0.63 / 0.90 格 / −5.1° 三个数本身没问题，只是时刻标注此前写错成了 120 tick。

### 2.4 可引用的项目既有教训

| 教训 | 依据 |
|---|---|
| 跨模组联动**优先用对方的 public 静态入口**，不要 mixin、不要绕命令层 | `memory/learned-patterns.md:1381` |
| **对方零校验时，预检必须由我们做** | `memory/learned-patterns.md:1390` |
| **编译期依赖 = 最硬的兼容性检验** | `memory/learned-patterns.md:1395` |
| 代码审查范围：**只审 `src/`** | `memory/learned-patterns.md:1357` |
| 批次执行：脚本锚点基于实际内容、每段退出码都纳入判断、失败绝不提交 | `memory/learned-patterns.md:1397` |
| JSON 里写注释不要带引号 | `memory/learned-patterns.md:1442` |

---

## 三、设计

### 3.1 架构（§1）

**一句话**：**CG 是「一次性编排」** —— 服务端在一个 tick 内把镜头轨迹烘成世界坐标、顺带触发动画、
发出两个包，然后彻底撒手；两条时间轴分别由 **fdlib**（相机）与 **GeckoLib**（动画）自行推进，
我们**不持有任何运行期状态**。

```
服务端（触发一次，然后不再参与）
  CgCommand  ──►  CgRegistry.byName(name)  ──►  CgAnimation.build(ctx)
                                                     │
                         ┌───────────────────────────┴───────────────────────────┐
                         ▼                                                       ▼
                 CutsceneData（世界坐标）                              target.setEmote("descend")
                         │                                                       │
                         └──────────► FDLibCalls.startCutsceneForPlayer(viewer, data)
                                                     │
客户端（我们的代码零参与）                            ▼
                         fdlib CutsceneCameraHandler ── 接管相机、锁输入、隐藏 HUD
                         GeckoLib emote 控制器 ──────── 播 descend、播完自动回落 idle
```

**七条承重决定**

- **D1 依赖升级**：`build.gradle` 的 fdlib `compileOnly` → `implementation`（保留 `localRuntime`，
  与同区块 qliphoth / cataclysm 等约 20 处写法一致）；`neoforge.mods.toml` 新增
  `modId="fdlib" type="required" versionRange="[1.0.9,)"`。
  依据：`DawnLightEffect:91-94` 已在**无守卫**调用 fdlib，`mods.toml` 已 required `fdbosses`
  ⇒ 这是消除"声明与行为"的矛盾（`learned-patterns.md:1395` 把编译期依赖当作最硬的兼容性检验）。

- **D2 只发给执行者**：`FDLibCalls.startCutsceneForPlayer(viewer, data)`。其他人不受任何影响。

- **D3 `StopMode.AUTOMATIC`**：`CutsceneCameraHandler.tickEvent:79-88` 里**只有 `AUTOMATIC`
  会在 `hasEnded()` 后 `stopCutscene()`** —— `PLAYER`/`UNSTOPPABLE` 只走 `nextCutscene == null` 分支、
  永不归还相机 ⇒ "动画结束后退出"这条需求**唯一可行**的选项。

- **D4 轨迹全在服务端烘成世界坐标**：因为 `descend` 的 `Root` 骨骼位移恒定（**实体整段静止**），
  静态烘制零精度损失；同时绕开 fdlib "look 逻辑写死、不能自动看向目标"的缺口
  （`CutsceneExecutor.java:24-32`）。

- **D5 CG 不碰动画播放逻辑**，只调 `NpcEntity.setEmote(name)`：表情系统已实现"播一条一次性动画 →
  播满 `animation.length()` → `emoteDone` → `main` 接管回状态动画"（`NpcEntity:1245-1287`）
  ⇒ "动画结束后回落"是**现成行为**，零新增动画代码。

- **D6 `<target>` 用 `EntityArgument.entity()`（单数），且必须是 `NpcEntity`**：只有它有 `setEmote`；
  选中别的实体时明确报错，不静默（照 `NpcCommand.fail()` 口径）。

- **D7 英文 ID = `mo_entrance`**：对照 FDBosses 的 `CHESED_APPEAR` / `GEBURAH_APPEAR` 命名系；
  `entrance` 比 `appear` 更贴合中文"登场"，且与动画名 `descend` 解耦（换动画不必改 CG 名）。

### 3.2 组件（§2）

| # | 文件 | 类型 | 职责 |
|---|---|---|---|
| C1 | `build.gradle` | 改 | fdlib `compileOnly` → `implementation` |
| C2 | `src/main/templates/META-INF/neoforge.mods.toml` | 改 | 新增 fdlib required 依赖 |
| C3 | `cg/CgAnimation.java` | 新 | **抽象配方**：`name()` / `animationName()` / `build(CgContext)` + **`final play(...)`** |
| C4 | `cg/CgContext.java` | 新 | **只读上下文 + 三个数学原语**（`ahead` / `sightLine` / `pitchCurve`） |
| C5 | `cg/CgRegistry.java` | 新 | `name → CgAnimation` 唯一映射（`LinkedHashMap` 保序）+ 命令补全 |
| C6 | `cg/instances/MoEntrance.java` | 新 | `mo_entrance`：全部数值为具名常量 |
| C7 | `command/CgCommand.java` | 新 | `/beloong cg <target> play <name>` |
| C8 | `BeLoongCore.java` | 改 | `onRegisterCommands` 加一行 |
| C9 | `lang/{zh_cn,en_us}.json` | 改 | **246 → 250**（4 条命令反馈） |
| C10 | `docs/plans/` + `docs/reviews/` | 新 | 本设计文档 + 实施计划（+ 后续审查） |
| C11 | `memory/{decisions-log,learned-patterns}.md` | 改 | 追加本轮条目 |
| C12 | `memory/project-context.md` | 改 | 顺带修正过期事实 |
| C13 | `D:\Minecraft\tools\YSMParser\cg_invariants.py` | 新 | 静态不变量脚本（与既有 `stage_invariants.py` 同处） |

**`CgAnimation` 的形状**

```java
public abstract class CgAnimation {
    public abstract String name();            // 指令名，如 mo_entrance
    public abstract String animationName();   // 交给表情系统；空串 = 这条 CG 不动画
    protected abstract CutsceneData build(CgContext ctx);   // 纯函数，无副作用

    /** 唯一的副作用出口 —— final，子类不许改写。 */
    public final int play(ServerPlayer viewer, Entity target) { ... }
}
```

`play` 定为 `final` 的理由与 `NpcEntity.switchState` 那条注释同源：**副作用集中在一处**，
否则 N 个 CG 类会各写一份略有差异的发包与触发逻辑。

**`CgContext` 的三个原语**

```java
public record CgContext(ServerPlayer viewer, Entity target, Vec3 anchor, Vec3 forward) {

    /** 从锚点沿 target 朝向前方 blocks 格（水平）。 */
    public Vec3 ahead(double blocks);

    /** 视线方向：水平朝向 aimPoint、仰角 elevationDeg 度（正 = 上看）的单位向量。 */
    public static Vec3 sightLine(Vec3 camPos, Vec3 aimPoint, double elevationDeg);

    /** 机位不动、仰角按给定函数走：每 sampleStep 采样一次，产出位置全同的 CameraPos 列表。 */
    public static List<CameraPos> pitchCurve(Vec3 camPos, Vec3 aimPoint,
            int totalTicks, int sampleStep, DoubleUnaryOperator elevationDegAtTick);
}
```

`sightLine` 返回**单位向量**而不是角度，是刻意的：`CameraPos(Vec3, Vec3)` 会调 fdlib 自己的
`yRotFromVector`/`xRotFromVector` 做转换 ⇒ **我们不经手 MC 的 yaw/pitch 正负号约定**，
也就没有踩错符号的空间。`elevationDeg > 0 = 上看` 是我们自己的定义，与 MC 无关。

`CgRegistry` 的写法照 `NpcDialogueStage` 的最新先例：`public final` + 私有构造 +
静态方法 + 失败留**每名一次**英文 WARN（服务端主线程单线程访问，普通 `HashSet` 够用）。
`CgContext` 则是一个 **record**（字段不可变、规范构造器 public），只有"静态数学方法"这一半照同一风格
—— 它没有自己的集合与 WARN，因为它是纯函数式的几何工具。
（口径修正：2026-09-30 审查 M-5 —— 原文把两者都描述成"`public final` + 私有构造"，对 `CgContext` 不成立。）

### 3.3 `mo_entrance` 的编排（§3）

**时间轴（总长 120 tick）**

| 段 | tick | 仰角 | 缓动 | 语义 |
|---|---|---|---|---|
| ① 抬升 | 0 → **34** | **+44.3° → +59.8°** | `easeInOut` | 体高 9.43 → 峰值 15.36 格 |
| ② 下降 | 34 → **68** | **+59.8° → 0.0°** | `easeInOut` | 体高 15.36 → 落地 0.30 格 |
| ③ 保持 | 68 → **120** | **0.0°**（恒定） | — | 体高小幅沉降 0.30 → 0.90 格；镜头"停止住" |

两个断点**从资产量出**：`34` = 峰值时刻，`68` = 落地时刻 ⇒ 镜头"降到平视"与末"落地"**同 tick**。

**相机几何**

```
P = 末.pos + 末.forward × 8 + (0, 1.62, 0)      ← 整段恒定不动
注视点 = (末.x, 末.y + h(t), 末.z)              ← 只在算仰角时用；运行期不读资产
水平朝向 = P → 末（即 −forward），整段不变；只有仰角在动
```

`末.forward`（`Entity.getForward()`）是水平单位向量，仍做一次防御性归一化。

**仰角曲线（写死的全部内容）**

```java
private static final int    APEX_TICK       = 34;    // 1.7067 s —— 动画峰值
private static final double PITCH_START_DEG = 44.3;  // 对准 t=0 体高 9.43 格
private static final double PITCH_APEX_DEG  = 59.8;  // 对准峰值体高 15.36 格
private static final int    LANDING_TICK    = 68;    // 3.4267 s —— 动画落地
private static final double PITCH_LEVEL_DEG = 0.0;   // 平视（用户指定）

private static double elevationAtTick(int tick) {
    if (tick <= APEX_TICK) {
        double p = tick / (double) APEX_TICK;
        return PITCH_START_DEG + (PITCH_APEX_DEG - PITCH_START_DEG) * FDEasings.easeInOut((float) p);
    }
    if (tick <= LANDING_TICK) {
        double p = (tick - APEX_TICK) / (double) (LANDING_TICK - APEX_TICK);
        return PITCH_APEX_DEG + (PITCH_LEVEL_DEG - PITCH_APEX_DEG) * FDEasings.easeInOut((float) p);
    }
    return PITCH_LEVEL_DEG;
}
```

缓动源用 fdlib 的 `FDEasings.easeInOut`（`util/rendering/FDEasings.java:37-43`）—— 正是 FDBosses
自己过场用的那条，整包观感一致。

**「缓慢」的旋钮 = `LANDING_TICK`**：想更慢就往后推（如 96），代价是镜头降到平视会**晚于**末落地。
默认 68 = 与动画同步。

**⚠️ 一个刻意的取舍**：物理上降到平视的那一刻体高恰好穿过眼高，再往下镜头本该变成 −9.4° 的微俯视；
但用户选的是"写死缓动曲线、直到平视就停住"，故曲线在 **0° 截断并保持**。
**代价**：末落地后最后 52 tick 里镜头水平视线落在 1.62 格高（约末的胸口/头部），
而不是跟着落到脚边 —— 观感正常，只是不再是"精确对准身体"。

**关键点采样：为什么是每 2 tick 一个**

fdlib 的关键点**只能等距**（`NormalLookProcessor.java:23-27`），所以断点 34 / 68 要求采样步长能整除：

| 采样步长 | 关键点数 | 34 / 68 落在采样点 | 包体积 |
|---|---|---|---|
| **2 tick**（选定） | **61** | ✅ 都是（索引 17 / 34） | ~5 KB |
| 4 tick | 31 | ❌ 峰值差 2 tick | ~2.5 KB |

选 2 tick：断点精确、消除"峰值为什么差一格"的疑问，而 ~5 KB 每条 CG 只发一次。

**由此推出两个必须设为 `LINEAR` 的参数**（否则点会被非等距映射、曲线失准）：

- `timeEasing = LINEAR` —— 保证"关键点 i ↔ tick i×2"
- `lookEasing = LINEAR` —— 保证段内是直线插值

⇒ **缓动必须烘进采样值里**（即上面那个 `easeInOut`），不能交给 fdlib 的 `EasingType`。
这是"写死缓动曲线"在 fdlib 框架下的必然落地方式。

**fdlib 参数总表**

| 项 | 值 | 理由 |
|---|---|---|
| `time(120)` | 120 | = 动画长度，也是退出时刻 |
| `stopMode(AUTOMATIC)` | — | 唯一会自行归还相机的模式（D3） |
| `moveCurveType(LINEAR)` | — | 机位恒定；`LinearCameraMotion` 走 `getListValueOrBoundaries`（永不返回 null），比 CATMULLROM 少一个 null 边界风险 |
| `timeEasing` / `lookEasing` | `LINEAR` | 见上 |
| `cameraPositions` | 61 个，**位置全同** | `CgContext.pitchCurve(...)` 生成 |
| 屏幕特效 | **不加** | 用户选的"开场就对准高处"不需要黑场；YAGNI |

**常量一览（全部可手调，集中在 `MoEntrance`）**

| 常量 | 值 | 来源 |
|---|---|---|
| `DURATION_TICKS` | 120 | `descend` 的 `animation_length 6 × 20` |
| `VIEW_DISTANCE` | 8.0 | 用户给定 |
| `VIEW_EYE_HEIGHT` | 1.62 | 原版玩家站立眼高 |
| `PITCH_START_DEG` | 44.3 | §2.3 推导 |
| `PITCH_APEX_DEG` | 59.8 | §2.3 推导 |
| `PITCH_LEVEL_DEG` | 0.0 | 用户指定 |
| `APEX_TICK` | 34 | 动画峰值 1.7067 s |
| `LANDING_TICK` | 68 | 动画落地 3.4267 s |
| `SAMPLE_STEP` | 2 | 断点精确 |
| `ANIMATION_NAME` | `"descend"` | `mo.extra.animation.json` |
| `NAME` | `"mo_entrance"` | D7 |

### 3.4 数据流（§4）

```
[服务端 · 单 tick 内全部完成]
  /beloong cg <target> play mo_entrance
        │
        ▼  CgCommand：权限 + 目标类型 + CG 名
  CgRegistry.byName("mo_entrance")  ──miss──►  报错（列出可用名）· 不播放
        │ hit
        ▼
  CgContext.of(viewer, target)          // anchor = target.position()
        │                                // forward = target.getForward() 水平归一
        ▼
  MoEntrance.build(ctx)                 // 纯函数：61 × CameraPos（位置全同）
        │
        ▼
  CgAnimation.play 的预检                // ★ 列表非空 / forward 非零
        │
        ├──────► target.setEmote("descend")
        │            │ 原版实体数据通道（force 发包）
        │            ▼
        │        客户端 DATA_EMOTE = "descend"
        │            │
        │            ▼ GeckoLib emote 控制器（客户端每帧谓词）
        │        播 descend；满 120 tick ⇒ emoteDone ⇒ main 接管 ⇒ 回 idle
        │
        └──────► FDLibCalls.startCutsceneForPlayer(viewer, data)
                     │ 模组自定义通道（fdlib StartCutscenePacket）
                     ▼
                 客户端 fdlib CutsceneCameraHandler
                     ├─ 建/复用 ClientCameraEntity，setCameraEntity
                     ├─ 锁输入 / 锁视角 / 吞攻击键 / 隐藏 HUD+手+高亮
                     ├─ 每 tick：camera.setPos(固定机位)
                     └─ 每帧：ViewportEvent.ComputeCameraAngles 覆写仰角
                                  │
                        currentTime ≥ 120 ⇒ hasEnded
                                  │
                        AUTOMATIC ⇒ stopCutscene() ⇒ 归还相机、清累积鼠标增量
```

**两条下发路径走两套不同的网络通道**（原版实体数据 vs 模组自定义载荷），因此到达顺序不保证 ⇒
可能差 1–2 tick。120 tick 的时长上不可察觉（**已知的无害不精确，不修**）。

**状态归属**

| 状态 | 存在哪 | 生命周期 |
|---|---|---|
| 相机轨迹（61 个关键点） | 客户端 fdlib `CutsceneExecutor.data` | 过场期间；结束即随对象丢弃 |
| 过场进度 `currentTime` | 客户端 `CutsceneExecutor` | 同上 |
| 相机实体 | 客户端 `ClientCameraEntity`（**从不 `addFreshEntity`**，只是 `Minecraft.cameraEntity` 的指向） | 过场期间 |
| 要播哪条动画 | **双端** `DATA_EMOTE` 同步数据 | 直到下次 `state`/`move`/`attack`/`reset`/`play`；**不落盘** |
| 动画计时起点 / 完成标志 | **仅客户端** `NpcEntity` 瞬态字段 | 直到动画播完 |
| **本模组新增的持久状态** | **无** | — |
| **本模组新增的网络包** | **无** | — |
| **本模组新增的服务端 tick 逻辑** | **无** | — |

**⇒ 服务端发完两个包就彻底撒手，不需要知道 CG 何时结束** —— 因为没有需要清理的东西。
这是项目既有那条「用原版系统当状态存储，自己只做只读闸门」（`learned-patterns.md:1408`）的姊妹结构：
那边把状态交给 `advancement`，这边把两条时间轴分别交给 **fdlib** 与 **GeckoLib**。

---

## 四、错误处理（§5）

| 情况 | 行为 | 理由 |
|---|---|---|
| CG 名不存在 | 命令**报错**（附可用名列表）· 不播放 | fail-closed；同 `NpcDialogueStage` 对未知进度 id 的口径，`CgRegistry` 打**每名一次**英文 WARN |
| target 不是 `NpcEntity` | 命令报错 · 不播放 | 没有 `setEmote`；照 `NpcCommand.fail()` 的"明确报错、不静默" |
| `forward` 水平长度 ≈ 0 | 命令报错 + 英文 WARN · **不发包** | 否则归一化出 NaN ⇒ 坏镜头。活实体几乎不可能触发，但**静默播放坏镜头比报错更糟** |
| `build` 抛任何异常 | 捕获 → 命令报错 + 英文 WARN · 不发包 | 照 `EmoteAnimationLookup` catch `Throwable` 的先例：宁可退化成"没播放"，也不让 CG 的 bug 崩掉命令分发 |
| **构建出的 `CameraPos` 列表为空** | 命令报错 + 英文 WARN · **不发包、不触发动画** | ★ `CutsceneCameraHandler.java:175` 的 `getFirst()` **无判空**；空列表会让客户端收包即抛 `NoSuchElementException`，且每 tick 再抛一次。这是 `learned-patterns.md:1390` 的直接应用 |
| `descend` 在资产里不存在 | **CG 照常播放**（相机照转），末保持 idle | 资产是客户端数据、服务端无从校验。`EmoteAnimationLookup` 已打英文 WARN；`hasActiveEmote()` 查不到 ⇒ 返回 false ⇒ `main` 不让位 ⇒ 状态动画照旧（**刻意的优雅降级**，非静默失败） |
| 执行者不在 target 附近 | 照常播放 | 机位由末决定 ⇒ 与玩家位置无关（D-anchor 的性质，非疏漏） |
| 玩家在 CG 中被推动 / 掉虚空 | 相机不受影响 | 轨迹已烘成世界坐标 |
| target 在 CG 中被 `/kill` | **CG 照常跑完**；末消失，镜头轨迹不变 | 世界坐标烘制的附带好处：CG 不依赖目标存活 |
| 玩家在 CG 中登出 | fdlib 的 `onLogoff` 自行清空其状态 | 对方的责任 |
| 玩家在 CG 中死亡 / 重生 | **不特判**，6 秒跑完 | 目标无敌、CG 极短；加特判的复杂度大于收益 |
| 重复执行同一指令 | **干净地从头重播** | fdlib `startCutscene` 复用已有相机实体 + 新建 executor（计时归零）；`setEmote` 走 `force=true` ⇒ 客户端清 `emoteSeen`/`emoteDone` ⇒ 动画重新计时 |
| 中途强行中断 | `/fdlib fix cutscene`（fdlib 自带） | 不必自己写一个 |
| 玩家原本是第三人称 | fdlib 强制切第一人称，**结束后不还原** | fdlib 的行为，不改它 |
| CG 结束后玩家朝向与原视角不一致 | 硬切回玩家视角 | 用户前提（玩家就在那 8 格处看着末）下无缝；偏离前提时会跳一下。**这是选"机位由末决定"换来的已知性质** |

**概括**：所有失败都**要么在命令层明确报错、要么优雅降级**，没有一条会静默产生坏状态；
而 fdlib 那侧唯一的崩溃面（空轨迹列表）被 `play` 的预检提前堵住。

---

## 五、验证策略（§6）

本项目**没有 test 源集**（验证 = 构建 + 静态不变量 + 实机）。

**静态**

1. `.\gradlew.bat build --console=plain` ⇒ BUILD SUCCESSFUL
2. **`cg_invariants.py`**（放 `D:\Minecraft\tools\YSMParser\`，与既有 `stage_invariants.py` 同处）三条断言：
   - `MoEntrance.DURATION_TICKS == descend.animation_length × 20`
   - `descend` 确实存在于 `mo.extra.animation.json`
   - `descend` 里 `AllBody` 的**峰值 Y 偏移 ≈ +288.5 单位**（容差 1）⇒ 钉住 `PITCH_APEX_DEG`
3. 语言键两语言集合一致（**250 / 250**）
4. `mods.toml` 里 fdlib 条目存在且 `type="required"`

> 第 3 条断言与阶段闸门那条"ChatBox 的进度 id 必须与 `mo.json` 的 `end_advancement` 字符串一致"
> 是同一类做法：**硬编码常量与外部资产之间必须有机器可查的一致性** —— 否则重导出资产后镜头会静默失准。

**实机清单（用户执行）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 站到末面前 8 格（末面朝你）· `/beloong cg @e[type=beloong:mo,limit=1] play mo_entrance` | 相机被接管、HUD/手/准星全部消失 |
| 2 | 观察开头 | 相机**起始就是仰视**，末在高处 |
| 3 | 约 1.7 秒处 | 相机仰角到达最高（约对准末的峰值高度） |
| 4 | 之后约 1.7 秒 | 相机**缓慢降到平视**，与末落地大致同时 |
| 5 | 最后约 2.6 秒 | 相机**停住不动** |
| 6 | 第 6 秒 | 末动画播完回 idle；相机**归还**、HUD 恢复 |
| 7 | 反例 A：CG 名敲错 | 命令**报错**并列出可用名；画面无变化 |
| 8 | 反例 B：目标选非 NPC 实体 | 命令报错；画面无变化 |
| 9 | 反例 C：连按两次同一条指令 | **干净地从头重播**，不出现两个镜头打架 |
| 10 | 反例 D：中途 `/fdlib fix cutscene` | 相机立即归还 |
| 11 | 查日志 | 无 `NoSuchElementException`、无 "List of camera positions cannot be empty" |

**不做**：不新增 test 源集；不为 CG 写单元测试（项目无此基础设施）。

---

## 六、非目标

- **数据驱动**（用户要求 4：写死 Java 类）。将来若真要数据包化，`CgAnimation.build` 就是天然的替换点。
- **多人同步观看**（用户裁定：只有执行者）。将来要广播，只需把 `startCutsceneForPlayer` 换成
  `startCutsceneForPlayers` 并决定半径。
- **CG 中途响应事件 / 动态跟随移动实体**（方案 B 的领域）。本轮的 `CutsceneData` 产出物形状
  保留了这条缝：将来可以在不改 CG 类的前提下，外面套一层 tick 调度器。
- **跳过 / 快进**：`AUTOMATIC` 模式下没有跳过键（`PLAYER` 模式永不自动归还相机，见 D3）。
  将来要跳过需要自己实现（例如听一个按键 + `stopCutsceneForPlayer`）。
- **CG 列表 UI / 存档持久化 / 每玩家进度**。
- **屏幕特效、震动、冲击帧**：fdlib 都有现成能力，本轮 YAGNI，不加。
- **修正 fdlib 的副作用**（强制切第一人称不还原、归位是硬切）—— 需要 mixin，与
  `learned-patterns.md:1381` 相悖，不做。

---

## 七、下一步

调用 `planning` 技能，把本设计拆成可执行任务清单（批次划分 + 每任务的验证命令 + 实机验收清单）。
