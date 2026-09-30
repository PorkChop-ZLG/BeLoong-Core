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
| ② | T8–T10 | ✅ **已完成**（含 v2 / v3 两轮规格修订） | `e50adb0`、`6b133d4` |
| ③ | T11 | 🔄 收尾中（文档 §八 + memory 回填 + 收尾审查） | 本次提交 |

**批次 ① 的验证证据**
- `.\gradlew.bat build` exit 0（全程零新增编译警告；既有的 3 条是 Mixin 注解处理器的 obfuscation mapping 警告）。
- jar 内 `META-INF/neoforge.mods.toml` 含 `modId="fdlib"` + `type="required"`。
- `cg_lang_keys.py` PASS：两语言各 250 键、集合完全一致、4 条新键非空；语言文件 diff **仅 +4 行**（未重排）。

**批次 ① 的独立代码审查**（独立上下文，只审 `src/`）：**0 Critical / 3 Important / 5 Minor，全部已修**。
三条 Important 全在项目头号缺陷面"注释与事实脱节"上，其中 I-1 有**真实功能后果**
（`Entity#getForward()` 含俯仰 ⇒ 末飞行过后可能被误判"朝向退化"而拒播 CG）。详见 `8292a77` 的提交信息。

**批次 ② 的实机验收（T9）**：✅ **用户已确认"效果符合要求"**（2026-09-30，v3 数值）。
T10 的"按反馈调常量"因此**未触发**（无需调参即通过）。

**收尾审查**（批次 ③，覆盖三个提交）：见 `docs/reviews/2026-09-30-cg-system-code-review.md`。

---

## ⚠️ v2 / v3 规格修订（2026-09-30，用户实机后给出）

用户实测后把 `mo_entrance` 的编排**整体换掉**，并新增"观察者隐身"；同日第二轮（v3）又调整了时间安排与隐身时长。
**权威规格见设计文档 §3.3**，本节只列出"本计划的哪些部分因此失效"：

| 任务 | 原规格 | v2 后 |
|---|---|---|
| **T2** 的 `CgContext` | 第三个原语是 `static pitchCurve(camPos, aimPoint, ticks, step, elevationFn)`（机位固定、只转视角） | 改成**实例方法** `track(ticks, step, cameraAtFn, elevationFn)` —— 机位与仰角**都**随时间变化 |
| **T4** 的 `MoEntrance` 常量 | `PITCH_START_DEG=44.3` / `PITCH_APEX_DEG=59.8` / `APEX_TICK=34` / `LANDING_TICK=68` / `VIEW_DISTANCE=8.0` / `SAMPLE_STEP=2` | 全部替换为分段时间轴、四个用户给定的仰角（62.25/24.75/46.0/0）、`VIEW_DISTANCE_FAR=8.0` + `VIEW_DISTANCE_NEAR=3.0`、`SAMPLE_STEP=1` |
| **T3** 的 `CgAnimation` | `play` = 预检 → 触发动画 → 发包 | 增加一步：**给观察者上隐身**（可选钩子 `viewerInvisibilityTicks/Amplifier`，默认 0） |
| **T8** 的断言 | 三条：时长 / 动画存在 / **从资产重算 `PITCH_APEX_DEG`** | **改为"需求 ↔ 实现"对账**；"从资产重算仰角"与"峰值 ≈ +288.5"**已删除**（见下方 v3 的条数） |
| **T9** 的实机清单 | 3 段式（对准峰值 → 降平视 → 停） | 分段式 + 推近 + 隐身，共 15 项 |
| **T10** 的对照表 | 按"峰值/落地"调 | 按"各段 + 缓动 + 推近距离 + 隐身时长"调 |

**v3 第二轮修订**（同日，用户看了 v2 数字后）：

| 项 | v2 | **v3** |
|---|---|---|
| 编排 | 七段；`−46°` 只保持到 1.6s，1.6→1.75s 甩到平视，1.75→2.2s 才推近 | **六段**；`−46°` **保持到 2.2s**；**2.2→2.5s 同时**做"下拉到平视 + 推近到 3 格"；2.5s 后静止 |
| 断点常量 | `T_FALL_START=32` / `T_FALL_END=35` / `T_PUSH_END=44` | **`T_SNAP_START=44` / `T_SNAP_END=50`**（`T_FALL_*` 全部删除） |
| 秒 → tick | `1.72s = 34.4` **不是整数**，需用户在 34/35 里裁定 | **全部是整数**（0.25/0.8/1.2/2.2/2.5 → 5/16/24/44/50），取整问题消失 |
| 第⑤段缓动 | 仰角 `linear` + 距离 `easeInOut`（**不同窗口**） | 仍是仰角 `linear` + 距离 `easeInOut`，但改成**同一个 6 tick 窗口** |
| 末的出画 | **刻意出画约 0.8 秒**（用户裁定接受） | **基本消除** —— 只在 ~2.5s 前后擦到画面边缘（见设计文档 §3.3 的表） |
| 隐身时长 | 100 tick（5 秒） | **130 tick** = `DURATION_TICKS + 10`（用户原本要"与动画同长"，实测考量后选择留 10 tick 余量） |
| T8 断言 | 7 组 15 条 | **7 组 14 条**（少一条：`T_FALL_END == 35` 那条随断点一起删除） |

**没有变的**：方案 A 的整体架构、D1–D7、`CgRegistry`、`CgCommand`、语言键、`DURATION_TICKS = 120`
（仍与 `descend` 同步）、`StopMode.AUTOMATIC`、零持久状态 / 零新网络包、`SAMPLE_STEP = 1`（121 点）。
**新增的**：**D8 观察者隐身**（设计文档 §1）。

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

> ⚠️ **已被 v2 修订**（见文首「v2 规格修订」）：第三个原语由 `pitchCurve` 改为 **`track`**。
> 本节正文保留为当时的设计记录。

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

> ⚠️ **已被 v2 修订**（见文首「v2 规格修订」）：`play` 增加一步"给观察者上隐身"。
> 本节正文保留为当时的设计记录。

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

> ⚠️ **已被 v2 修订**（见文首「v2 规格修订」）：本节的常量表已全部作废，权威规格见设计文档 §3.3。
> 本节正文保留为当时的设计记录。

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

## T8：`cg_invariants.py` —— **需求**与**实现**的机器对账（v2 已重写）

> **⚠️ v2 规格变更（2026-09-30）**：仰角不再从资产推导（用户直接给角度）。
> 因此本任务初版的三条断言里，
> "从 `mo.geo.json` + `MOdel_SCALE` 重算 `PITCH_APEX_DEG`" 与 "`AllBody` 峰值 ≈ +288.5"
> **已删除** —— 新规格下没有任何东西依赖它们，留着只会变成必然失败的噪声。
> 现在守的是 **"用户口述的需求表 ↔ 代码常量"**（需求写在脚本里，实现写在 `MoEntrance.java` 里）。
> 权威规格见设计文档 §3.3。

**Files:**
- Create: `D:\Minecraft\tools\YSMParser\cg_invariants.py`

**Steps:** 七组断言（共 14 条，**全 PASS 才继续**）：
1. **A1** `MoEntrance.DURATION_TICKS == descend.animation_length × 20`
2. **A2** `descend` 确实存在于 `mo.extra.animation.json`
3. **A3** 五个"整秒"断点 == `round(秒 × 20)`（0.25/0.8/1.2/2.2/2.5 → 5/16/24/44/50）
4. **A4** 四个仰角常量 == **游戏内 `xRot` 取负**（−62.25 / −24.75 / −46.00 / 0）
5. **A5** 断点链单调递增且不越界
6. **A6** 观察者隐身 == `DURATION_TICKS` + 10（脚本会**递归解析** `DURATION_TICKS + 10` 这种表达式）
7. **A7** 采样密度 —— **刻意不是** `duration % step == 0`（那对 1/2/3/4/40 全通过，是空断言），
   而是"第⑤段那个 6 tick 的窗口至少采到 3 个点"

**理由**：与阶段闸门那条"ChatBox 的进度 id 必须与 `mo.json` 的 `end_advancement` 字符串一致"同构 ——
**"需求"与"实现"之间必须有机器可查的一致性**。
v1 防的是"资产重导出后镜头静默失准"，v2 防的是"改了常量却与需求脱钩"。

**Verification:**
```powershell
python D:\Minecraft\tools\YSMParser\cg_invariants.py; if ($LASTEXITCODE -ne 0) { throw 'T8 failed' }
```

---

## T9：实机验收（用户执行）—— **v3 清单**

**Steps:** 启动游戏，按下列清单逐项确认。**任一项不符 ⇒ 记录现象与 # 号，进 T10。**

| # | 操作 | 期望 |
|---|---|---|
| 1 | **脱甲、清空双手**，站到末面前 8 格（末面朝你）· `/beloong cg @e[type=beloong:mo,limit=1] play mo_entrance` | 相机被接管；HUD／手／准星／方块高亮全消失；**身体模型看不见了**；**无隐身粒子**（开头 1~3 帧可能仍可见身体） |
| 2 | 第 0 秒 | 相机在 8 格外**大幅仰视**（`xRot ≈ −62.25`） |
| 3 | 0.25 秒 | 仰角甩到约 −24.75°，并**保持到 0.8 秒** |
| 4 | 0.8 → 1.2 秒 | 仰角**平滑爬升**到约 −46° |
| 5 | **1.2 → 2.2 秒** | **保持** −46°（这段最长，末在这段时间从峰值往下走） |
| 6 | **2.2 → 2.5 秒** | 仰角**快速下拉**到平视（≈6 tick，约 7.7°/tick 匀速） |
| 7 | **2.2 → 2.5 秒** | **同一窗口里**相机**平滑推近**到距末 3 格 |
| 8 | 2.5 → 6.0 秒 | 相机与视角**全程静止**；约 2.5 秒末擦到画面边缘，随即俯冲进画面并落地（**v3 基本不再出画**） |
| 9 | 第 6 秒 | 末动画播完回 idle；相机**归还**、HUD 恢复 |
| 10 | 第 **6.5** 秒 | 隐身自行结束（= CG 时长 + 10 tick 余量） |
| 11 | 反例 A：CG 名敲错（如 `mo_entrace`） | 命令**报错**并列出可用名；画面无变化 |
| 12 | 反例 B：目标选非 NPC（如僵尸） | 命令报错；画面无变化 |
| 13 | 反例 C：连按两次同一条指令 | **干净地从头重播**，不出现两个镜头打架 |
| 14 | 反例 D：中途 `/fdlib fix cutscene` | 相机立即归还（**隐身会继续走完它的 6.5 秒** —— 效果独立于 CG 生命周期） |
| 15 | 查 `run/logs/latest.log` | 无 `NoSuchElementException`、无 `List of camera positions cannot be empty`；另有 INFO 行记录 anchor / forward / 首个机位 / key 数 |

---

## T10：按实机标定结果调常量（条件任务）—— **v3 对照表**

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/cg/instances/MoEntrance.java`（**只改常量**）

**Steps:** 照下表定位，一次改一个常量、重跑 T9。

| 症状 | 调哪个常量 | 方向 |
|---|---|---|
| 某一段整体偏高 | 该段的 `PITCH_*_DEG` | 调**小** |
| ⚠️ **只有"开场头几帧"偏高** | **先别调 `PITCH_START_DEG`** | 那是 fdlib 相机实体的眼高（`0.2×0.85`）+ 原版 `Camera` 平滑眼高的**过渡**，不是轨迹错。见 `VIEW_EYE_HEIGHT` 的注释 |
| 某一段整体偏低 | 该段的 `PITCH_*_DEG` | 调**大** |
| 某段过渡太急 | 该段缓动 | `easeInOut` → `easeIn`；或 `easeOut` → `linear` |
| 某段过渡太缓 | 该段缓动 | `easeInOut` → `easeOut`（前段更急）/ 或缩短该段 tick |
| **第⑤段（下拉+推近）太快 / 太慢** | `T_SNAP_START`（44）/ `T_SNAP_END`（50） | 拉开两者 = 更慢；缩短 = 更快。<br>⚠️ 下拉与推近**共用**这个窗口，改它会同时改两者的速度 |
| 下拉想与推近**用同一条曲线** | 第⑤段的仰角缓动 | 把 `elevationAt` 里的纯 `lerp` 换成 `FDEasings.easeInOut`，与距离一致 |
| 推近太快 / 太慢（单独） | `distanceAt` 的缓动 | `easeInOut` → `easeIn`/`easeOut`（不影响下拉速度） |
| 近距太远 / 太近 | `VIEW_DISTANCE_NEAR`（3.0） | 直接改格数 |
| 前段太远 / 太近 | `VIEW_DISTANCE_FAR`（8.0） | 直接改格数 |
| 某段时长不对 | 对应的 `T_*` 断点 | 秒 × 20（T8 断言 A3 守着它们与需求一致） |
| 隐身太长 / 太短 | `INVISIBILITY_TICKS`（现在是 `DURATION_TICKS + 10`） | 改那个 `+ 10` 的余量；T8 断言 A6 守着这个关系 |
| 整体节奏太快 / 太慢 | ⚠️ **不要改 `DURATION_TICKS`** | 它必须与 `descend` 的 `animation_length` 同步（T8 断言 A1 守着）；要改节奏只能改动画资产 |

**Verification:**
```powershell
$env:JAVA_HOME='D:\Java\jdk-21.0.11'; .\gradlew.bat build --console=plain
python D:\Minecraft\tools\YSMParser\cg_invariants.py; if ($LASTEXITCODE -ne 0) { throw 'T10 failed' }
# 然后重跑 T9
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
| 我推导的仰角与实际观感不符 | 镜头偏高/偏低 | ~~v1 的"从资产推导"~~ —— **v2 仰角是用户直接给的**，风险变为"缓动是否合意"（见 T10 对照表） |
| 依赖升级导致加载失败 | 游戏起不来 | fdlib 由 `fdbosses`（已 required）传递而来 ⇒ 缺席时 fdbosses **先**失败；T1 改完立刻单独构建，T9 启动即验证 |
| 语言键两语言写岔 | 界面显示原始键名 | T7 脚本对账 + 断言两集合一致 |
| ~~61 个关键点的包过大~~ → **v2：121 个** | 网络抖动 | 实测约 **10 KB**，每条 CG 只发一次（客户端自定义载荷上限 1 MiB）。异常时把 `SAMPLE_STEP` 改 2（点数减半，两条快速运动会有可见折线） |
| 重复触发 / 中途打断 | 镜头状态混乱 | 设计 §5 已定：重复 = 干净重播；打断用 `/fdlib fix cutscene` |
| 玩家自己的身体出现在画面里 | 观感失败 | **v2/v3 已处理**：给了观察者 **130 tick（6.5 秒）** 隐身（D8）。残留风险两条：① 开头 1~3 帧仍可见（渲染开关走另一个包，见 `CgAnimation.play` 第 ⑤ 步）；② **隐身不含盔甲/手持物层** ⇒ 验收要脱甲 |

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
