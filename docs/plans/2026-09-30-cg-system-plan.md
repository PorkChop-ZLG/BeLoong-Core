# 过场动画系统（CG 系统）实施计划

**Goal:** 让本模组具备"用一条指令播放电影级过场"的能力，并交付第一条 CG —— 末的登场 `mo_entrance`。
**Architecture:** 设计文档见 `docs/plans/2026-09-30-cg-system-design.md`（§1 架构 / §2 组件 / §3 编排 / §4 数据流 / §5 错误处理 / §6 验证）。
**Approach:** **方案 A** —— 服务端在**一个 tick 内**把镜头轨迹烘成世界坐标、触发动画、发两个包后彻底撒手；
两条时间轴分别交给 **fdlib**（相机）与 **GeckoLib 表情系统**（动画）。
**⇒ 零 mixin、零新网络包、零服务端 tick 逻辑、零新增持久状态。**

> ⚠️ **与技能默认流程的偏离（同上一轮）**：
> ① 本技能默认 TDD，但本项目**没有测试源集**（`Task :test NO-SOURCE`）。
> 每任务的验证 = **构建 + 脚本对账 + 实机清单**，并给出确切命令。
> ② 本技能建议"每任务 2–5 分钟"，但本项目既有的计划模板（阶段闸门 T1–T8）按**组件**切分，
> 每个任务 = 一个类的落成。本计划沿用项目惯例，**不为了凑时长把"写一个类"拆成三份**；
> 每个任务仍保证"单一职责 + 有确切验证命令"。

> ⚠️ **采纳的既有教训**（写进文末"执行纪律"）：
> ① 改文件前先读一遍当前内容，锚点必须基于**实际内容**；
> ② 每一段脚本的退出码都纳入判断，任一段失败**绝不继续、绝不提交**；
> ③ JSON 里写"注释"**不要带引号**（会让整个文件非法）；
> ④ 结构性工具的"空结果"不足以支撑断言（`glob` 曾返回假空）。

---

## 提交批次（3 个原子提交）

| 批次 | 含任务 | 内容 | 结束时可否实机 |
|---|---|---|---|
| ① | T1–T7 | 依赖升级 + CG 框架 + 命令 + `mo_entrance` + 语言键 | ✅ **端到端可跑** |
| ② | T8–T10 | 不变量脚本 + 实机验收 + 按标定调常量 | — |
| ③ | T11 | 设计文档与 memory 回填 | — |

**任务依赖顺序**（编译器强制，不能调换）：
```
CgContext ─► CgAnimation ─► MoEntrance ─► CgRegistry ─► CgCommand ─► BeLoongCore
```

---

## 执行状态

| 批次 | 任务 | 状态 | 提交 |
|---|---|---|---|
| ① | T1–T7 | ✅ **已完成** | `8292a77` |
| ② | T8–T10 | ⏳ 待执行 | — |
| ③ | T11 | ⏳ 待执行 | — |

**批次 ① 的验证证据**
- `.\gradlew.bat build` exit 0（全程零新增编译警告；既有的 3 条是 Mixin 注解处理器的 obfuscation mapping 警告）。
- jar 内 `META-INF/neoforge.mods.toml` 含 `modId="fdlib"` + `type="required"`。
- `cg_lang_keys.py` PASS：两语言各 250 键、集合完全一致、4 条新键非空；语言文件 diff **仅 +4 行**（未重排）。

**独立代码审查**（独立上下文，只审 `src/`）：**0 Critical / 3 Important / 5 Minor，全部已修**。
三条 Important 全在项目头号缺陷面"注释与事实脱节"上，其中 I-1 有**真实功能后果**
（`Entity#getForward()` 含俯仰 ⇒ 末飞行过后可能被误判"朝向退化"而拒播 CG）。详见 `8292a77` 的提交信息。

**尚未做**：实机验收（T9）—— 需用户执行；T10 依赖其实机反馈。

---

## T1：依赖升级 —— fdlib 转必选

**Files:**
- Modify: `build.gradle`（第 189 行）
- Modify: `src/main/templates/META-INF/neoforge.mods.toml`（在 `fdbosses` 条目之后）

**Steps:**
1. **先读一遍这两处的当前内容**（锚点基于实际，不基于本计划的记忆）：
   `build.gradle` 第 186–190 行、`mods.toml` 第 97–102 行。
2. `build.gradle`：`compileOnly "curse.maven:fdlib-1271749:7844741"` → `implementation ...`。
   **保留**紧随其后的 `localRuntime` 行（与同区块 qliphoth / cataclysm 等约 20 处写法一致）。
   补一行中文注释说明"为何必选"（`DawnLightEffect` 已在无守卫调用它；fdbosses 已 required 且它 requires fdlib）。
3. `mods.toml`：新增一个 `[[dependencies.${mod_id}]]` 块，`modId="fdlib"` / `type="required"` /
   `versionRange="[1.0.9,)"` / `ordering="AFTER"` / `side="BOTH"`，并按文件既有风格补中文注释
   （注明 1.0.9 是实际核对过的版本、以及"过场系统需要它"这一理由）。
4. **立刻单独构建一次** —— 本任务是批次 ① 里唯一改构建配置的，先隔离验证它。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain   # BUILD SUCCESSFUL
# 并确认产出的 jar 里 mods.toml 含 fdlib required：
$jar = Get-ChildItem build/libs/*.jar | Select-Object -First 1
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
$e=$z.Entries | Where-Object FullName -eq 'META-INF/neoforge.mods.toml'
$r=New-Object System.IO.StreamReader($e.Open()); $t=$r.ReadToEnd(); $r.Close(); $z.Dispose()
$t -split "`n" | Select-String -Pattern 'fdlib' -Context 2,4
```

---

## T2：`CgContext` —— 只读上下文 + 三个数学原语

**Files:**
- Create: `src/main/java/com/zonlong/beloong/cg/CgContext.java`

**Steps:**
1. `public record CgContext(ServerPlayer viewer, Entity target, Vec3 anchor, Vec3 forward)`。
2. 静态工厂 `of(viewer, target)`：`anchor = target.position()`；
   `forward = target.getForward()` 取水平分量后归一化。
3. 三个原语：
   - `Vec3 ahead(double blocks)` → `anchor.add(forward.scale(blocks))`
   - `static Vec3 sightLine(Vec3 camPos, Vec3 aimPoint, double elevationDeg)` → **单位向量**；
     水平分量来自 `camPos → aimPoint` 的水平部分，竖直分量 `sin(elevationDeg)`、水平乘 `cos(...)`。
     **返回向量而不是角度** —— 交给 `CameraPos(Vec3, Vec3)` 让 fdlib 自己分解，我们不经手 MC 的 yaw/pitch 正负号约定。
   - `static List<CameraPos> pitchCurve(Vec3 camPos, Vec3 aimPoint, int totalTicks, int sampleStep, DoubleUnaryOperator elevationDegAtTick)`
     → 从 tick 0 到 `totalTicks` 每 `sampleStep` 采样一次，产出**位置全同**的 `CameraPos` 列表。
4. javadoc 照 `NpcDialogueStage` 的口径：**先写"为什么需要它"**，并交叉指向设计文档路径。
   特别要写清"为什么返回向量而不是角度""为什么关键点必须等距 ⇒ 缓动烘进采样值"。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat compileJava --console=plain   # BUILD SUCCESSFUL
```

---

## T3：`CgAnimation` —— 抽象配方 + 唯一的副作用出口

**Files:**
- Create: `src/main/java/com/zonlong/beloong/cg/CgAnimation.java`

**Steps:**
1. `public abstract class CgAnimation`，三个抽象成员：
   `String name()` / `String animationName()` / `protected CutsceneData build(CgContext ctx)`。
2. `public final int play(ServerPlayer viewer, Entity target)` —— **final**，子类不许改写。顺序：
   1. `ctx = CgContext.of(viewer, target)`；
   2. `forward` 水平长度 `< 1e-6` ⇒ 英文 WARN + 返回 0（**不发包**）；
   3. `try { data = build(ctx) } catch (Throwable t)` ⇒ 英文 WARN + 返回 0（照 `EmoteAnimationLookup` catch `Throwable` 的先例）；
   4. `data.getCameraPositions()` 为空 ⇒ 英文 WARN + 返回 0（**★ 关键预检**，理由见类注释）；
   5. `target.setEmote(animationName())`（`animationName()` 为空则跳过）；
   6. `FDLibCalls.startCutsceneForPlayer(viewer, data)`；返回 1。
3. javadoc 必须写明**四条预检各自的理由**，尤其第 4 条：
   `CutsceneCameraHandler.java:175` 的 `getCameraPositions().getFirst()` **无判空**
   ⇒ 空列表会让客户端收包即抛 `NoSuchElementException`，且每 tick 再抛一次。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat compileJava --console=plain
```

---

## T4：`MoEntrance` —— 第一条 CG

**Files:**
- Create: `src/main/java/com/zonlong/beloong/cg/instances/MoEntrance.java`

**Steps:**
1. 全部数值写成**具名常量**（设计文档 §3.3 的表）：`NAME="mo_entrance"` / `ANIMATION_NAME="descend"` /
   `DURATION_TICKS=120` / `VIEW_DISTANCE=8.0` / `VIEW_EYE_HEIGHT=1.62` /
   `PITCH_START_DEG=44.3` / `PITCH_APEX_DEG=59.8` / `PITCH_LEVEL_DEG=0.0` /
   `APEX_TICK=34` / `LANDING_TICK=68` / `SAMPLE_STEP=2`。
2. `build(ctx)`：
   ```java
   Vec3 camPos = ctx.ahead(VIEW_DISTANCE).add(0, VIEW_EYE_HEIGHT, 0);
   double hAtApex = ...;   // 只用于注释里说明常量怎么来的，不在运行期读资产
   return CutsceneData.create()
           .time(DURATION_TICKS)
           .stopMode(CutsceneData.StopMode.AUTOMATIC)
           .moveCurveType(CurveType.LINEAR)
           .timeEasing(EasingType.LINEAR)
           .lookEasing(EasingType.LINEAR)
           .addCameraPos(...)   // ← 或一次性 addAll
   ```
   ⚠️ **必须 `LINEAR` 的三处**（`timeEasing` / `lookEasing` / `moveCurveType`），理由写进注释：
   关键点只能等距，非线性映射会让断点错位 + 每个 2-tick 小段起涟漪。
3. `elevationAtTick(int)` 三段式，缓动用 `FDEasings.easeInOut`。
4. 类 javadoc 必须载明**三个仰角是怎么算出来的**（含 `模型单位 ×(1/16)× MODEL_SCALE(0.80) = ×0.05 = 格`、
   `AllBody` pivot 18.7 单位、峰值 +288.5 单位）—— 否则后人无从判断这些魔数是否还成立。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat compileJava --console=plain
```

---

## T5：`CgRegistry` —— 写死的 CG 清单

**Files:**
- Create: `src/main/java/com/zonlong/beloong/cg/CgRegistry.java`

**Steps:**
1. `public final class` + 私有构造；`private static final Map<String, CgAnimation> BY_NAME = new LinkedHashMap<>();`
   `LinkedHashMap` 保序 ⇒ 补全顺序与源码一致。
2. `static { register(new MoEntrance()); }` —— 以后每条 CG 在这里加一行。
3. `Optional<CgAnimation> byName(String)`：miss 时按**每个名字只报一次**打英文 WARN
   （普通 `HashSet` 即可 —— 只在服务端主线程调用，同 `NpcDialogueStage` 的取舍，不是 `EmoteAnimationLookup` 的 `ConcurrentHashMap`）。
4. `Collection<String> names()` 供 Brigadier 补全。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat compileJava --console=plain
```

---

## T6：`CgCommand` + 注册

**Files:**
- Create: `src/main/java/com/zonlong/beloong/command/CgCommand.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（`onRegisterCommands`，第 213–216 行）

**Steps:**
1. **先读 `NpcCommand.register`（第 90–146 行）确认当前形状**，照它写：
   `Commands.literal("beloong").requires(hasPermission(2)).then(Commands.literal("cg")...)`。
2. 指令面**严格按用户给定的签名**，不加 `stop` 子命令：
   ```
   /beloong cg <target> play <name>
   ```
   - `target` 用 `EntityArgument.entity()`（**单数**）
   - `name` 用 `StringArgumentType.word()` + `suggests` 读 `CgRegistry.names()`
     （**不是**逐条 `Commands.literal` —— 加 CG 不用改指令树，同 `NpcCommand.STATE_SUGGESTIONS` 的理由）
3. 处理器三条分支：
   - `CgRegistry.byName(name)` miss ⇒ `beloong.command.cg.unknown`（附可用名列表），返回 0
   - target 不是 `NpcEntity` ⇒ `beloong.command.cg.not_npc`，返回 0
   - 否则 `cg.play((ServerPlayer) source.getEntity(), target)`；返回 1 ⇒ `beloong.command.cg.play`；返回 0 ⇒ `beloong.command.cg.failed`
   - 命令源不是玩家 ⇒ 明确报错（CG 是给玩家看的），不静默
4. `BeLoongCore.onRegisterCommands` 加一行 `CgCommand.register(event.getDispatcher());`。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain   # BUILD SUCCESSFUL
```

---

## T7：语言键（4 条 × 2 语言，246 → 250）

**Files:**
- Create: `D:\Minecraft\tools\YSMParser\cg_lang_keys.py`（新增键的脚本，**不手改大 JSON**）
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. **先读一遍两份文件的当前键数**（实测 246 / 246）与末尾格式，锚点基于实际。
2. 用脚本新增 4 条（**不要手改大 JSON**），键名：
   `beloong.command.cg.play` / `.unknown` / `.not_npc` / `.failed`
   中文文案带 `%s`/`%s` 占位（数量与代码里的 `Component.translatable` 参数一一对应）；
   英文文案同结构。**文案里不要出现未转义的双引号**（教训 ③）。
3. 脚本每步都要 `json.loads` 校验 + 检查退出码。

**Verification:**
```powershell
python D:\Minecraft\tools\YSMParser\cg_lang_keys.py; if ($LASTEXITCODE -ne 0) { throw 'T7 failed' }
# 脚本断言：两语言键数均为 250、集合完全一致、4 个新键都存在且非空
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain
```

---

## T8：`cg_invariants.py` —— 硬编码常量与外部资产的机器对账

**Files:**
- Create: `D:\Minecraft\tools\YSMParser\cg_invariants.py`

**Steps:**
三条断言（**全 PASS 才继续**）：
1. `MoEntrance.DURATION_TICKS == descend.animation_length × 20`（从 Java 源码正则取常量，从 JSON 取长度）
2. `descend` 确实存在于 `src/main/resources/assets/beloong/animations/mo.extra.animation.json`
3. `descend` 里 `AllBody` 的**峰值 Y 偏移 ≈ +288.5 单位**（容差 1.0）⇒ 钉住 `PITCH_APEX_DEG`

**理由**：与阶段闸门那条"ChatBox 的进度 id 必须与 `mo.json` 的 `end_advancement` 字符串一致"同构 ——
**硬编码常量与外部资产之间必须有机器可查的一致性**，否则重导出资产后镜头会**静默失准**。

**Verification:**
```powershell
python D:\Minecraft\tools\YSMParser\cg_invariants.py; if ($LASTEXITCODE -ne 0) { throw 'T8 failed' }
```

---

## T9：实机验收（用户执行）

**Steps:** 启动游戏，按下列清单逐项确认。**任一项不符 ⇒ 记录现象与 # 号，进 T10。**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 站到末面前 8 格（末面朝你）· `/beloong cg @e[type=beloong:mo,limit=1] play mo_entrance` | 相机被接管、HUD/手/准星/方块高亮全部消失 |
| 2 | 观察开头 | 相机**起始就是仰视**，末在高处 |
| 3 | 约 1.7 秒处 | 仰角到达最高，末大致在画面中央 |
| 4 | 之后约 1.7 秒 | 相机**缓慢降到平视**，与末落地大致同时 |
| 5 | 最后约 2.6 秒 | 相机**停住不动** |
| 6 | 第 6 秒 | 末动画播完回 idle；相机**归还**、HUD 恢复 |
| 7 | 反例 A：CG 名敲错（如 `mo_entrace`） | 命令**报错**并列出可用名；画面无变化 |
| 8 | 反例 B：目标选非 NPC（如僵尸） | 命令报错；画面无变化 |
| 9 | 反例 C：连按两次同一条指令 | **干净地从头重播**，不出现两个镜头打架 |
| 10 | 反例 D：中途 `/fdlib fix cutscene` | 相机立即归还 |
| 11 | 观察**玩家自己的身体**是否出现在画面里 | 记录结果（fdlib 强制 `isControlledCamera()` 为 true 的设计意图是"不画自己"，但未实机确认过） |
| 12 | 查 `run/logs/latest.log` | 无 `NoSuchElementException`、无 `List of camera positions cannot be empty` |

---

## T10：按实机标定结果调常量（条件任务）

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/cg/instances/MoEntrance.java`（**只改常量**）

**Steps:** 照下表定位，一次改一个常量、重跑 T9 的第 2–5 项。

| 症状 | 调哪个常量 | 方向 |
|---|---|---|
| 开头镜头**太高**（末偏在画面下方） | `PITCH_START_DEG` | 调**小** |
| 开头镜头**太低**（末偏在画面上方/出画） | `PITCH_START_DEG` | 调**大** |
| 峰值处末不在画面中央 | `PITCH_APEX_DEG` | 末偏上 ⇒ 调小；偏下 ⇒ 调大 |
| 镜头降到平视**太早**（末还在空中） | `LANDING_TICK` | 调**大** |
| 镜头降到平视**太晚**（末已落地） | `LANDING_TICK` | 调**小** |
| 抬升段太急 / 太缓 | `elevationAtTick` 的缓动函数 | `easeInOut` → `easeOut`（更急）/ `easeIn`（更缓） |
| 机位离末太远 / 太近 | `VIEW_DISTANCE` | 直接改格数 |
| 整体节奏太快 / 太慢 | ⚠️ **不要改 `DURATION_TICKS`** | 它必须与 `descend` 的 `animation_length` 同步（T8 断言 1 守着）；要改节奏只能改动画资产 |

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain
python D:\Minecraft\tools\YSMParser\cg_invariants.py; if ($LASTEXITCODE -ne 0) { throw 'T10 failed' }
# 然后重跑 T9 的第 2–5 项
```

---

## T11：文档与 memory 回填（项目硬要求）

**Files:**
- Modify: `docs/plans/2026-09-30-cg-system-design.md`（新增"§八 实施期修订"一节）
- Modify: `memory/decisions-log.md`（追加"已实施"条目）
- Modify: `memory/learned-patterns.md`（若实施期有新教训）

**Steps:**
1. 设计文档补一节，记录**与计划的偏离**、**实机标定后的最终常量值**、以及**遗留项**（T9 #11 的观察结果）。
2. `decisions-log.md` 追加一条：提交号、实测标定值、与设计的差异。
3. 实施期若踩到新坑，追加 `learned-patterns.md`；**没有就不写**（不凑数）。
4. `memory/project-context.md` 的过期事实（版本 / 分支 / 文件数 / 语言键数 / 子系统 12–13）**已在 2026-09-30
   的 brainstorming 阶段顺带修正**，此处只需复核一次并补上"CG 已实施"的状态翻转。

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain
python D:\Minecraft\tools\YSMParser\cg_invariants.py; if ($LASTEXITCODE -ne 0) { throw 'T11 failed' }
```

---

## 风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| **fdlib 过场在本整合包不可用** | 功能完全不成立 | T9 第 1–6 项就是冒烟测试，**批次 ① 一结束立刻验**；失败则整个方案需重议（回退见下） |
| 我推导的仰角与实际观感不符 | 镜头偏高/偏低 | T10 是**显式的标定任务**，并给了"常量→观感"对照表；这是把"你的眼睛"当成验收仪器 |
| 依赖升级导致加载失败 | 游戏起不来 | fdlib 由 `fdbosses`（已 required）传递而来 ⇒ 缺席时 fdbosses **先**失败；T1 改完立刻单独构建，T9 启动即验证 |
| 语言键两语言写岔 | 界面显示原始键名 | T7 脚本对账 + 断言两集合一致 |
| 61 个关键点的包过大 | 网络抖动 | 实测约 5 KB，每条 CG 只发一次；异常时把 `SAMPLE_STEP` 改 4（点数减半，峰值差 2 tick） |
| 重复触发 / 中途打断 | 镜头状态混乱 | 设计 §5 已定：重复 = 干净重播；打断用 `/fdlib fix cutscene` |
| 玩家自己的身体出现在画面里 | 观感失败 | T9 #11 专门观察；若确实出现，需另一轮设计（覆写渲染或给玩家隐身），**本轮不做** |

**回退**：CG 全部是**新增**文件（`cg/` 包 4 个 + `CgCommand` 1 个 + 脚本 1 个），删掉即回到现状；
唯一非新增改动是 4 处极小改动（`build.gradle` 一行、`mods.toml` 一块、`BeLoongCore` 一行、两份语言 JSON 各 4 键）。
⇒ 整体 `git revert` 即可，**无残留状态**（设计上零落盘、零新增持久字段）。

---

## 执行纪律（既有教训，本计划强制遵守）

1. **改文件前先读一遍它的当前内容** —— 锚点必须基于**实际内容**，不能基于记忆或本计划的转述。
   （T1 / T6 / T7 都显式要求了这一步。）
2. **每一段脚本的退出码都要纳入判断**：
   `python xxx.py; if ($LASTEXITCODE -ne 0) { throw 'Txx failed' }` —— 失败绝不继续、绝不提交。
3. **JSON 里写"注释"不要带引号**（未转义的双引号会让整个文件非法；用「」或干脆不带）。
4. **结构性工具的"空结果"不足以支撑断言** —— `glob` 说"没有"时，用
   `Get-ChildItem -Recurse -Filter` 复核一次再下结论（曾因此差点"从零设计"）。
5. **任一段失败 ⇒ 不提交该批次**，先修好再提交。
6. **T1 改完立刻单独构建** —— 批次 ① 里唯一改构建配置的任务，先隔离诊断。

---

## 下一步

本计划交用户批准；批准后进入执行（`task-management` 顺序推进，逐任务验证，逐批次提交）。
