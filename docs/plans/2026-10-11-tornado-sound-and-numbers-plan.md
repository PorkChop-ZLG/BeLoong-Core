# 龙卷风：实体音效 + 数值重标定 实施计划（2026-10-11）

> **状态：待批准。未改任何代码。** 配套设计文档：`docs/plans/2026-10-11-tornado-sound-and-numbers-design.md`
> （下称「设计」，引用其 §号）

- **目标**：① 龙卷风**实体自己**发出持续的鞘翅滑翔音；② 重标定 `lifetime` / `cooldown` / `damage_per_hit`
- **交付物**：1 个新增客户端类 + 2 个已跟踪文件的小改 + `tornado.json` 三个字段 + 权威文档回填
- **非目标**：见设计 §八（不新增实体 / 不新增音频资产 / 不改伤害频率 / 不加护甲穿透 / 不改半径速度）

### 约束（项目既定）

| 约束 | 出处 |
|---|---|
| **无自动化测试** ⇒ 验证 = 静态检查 + `gradlew build` + **实机验收** | `memory/project-context.md` |
| `tools/` **刻意不进版本管理** ⇒ 新增的校验脚本只活在工作区，**不需要 `git add -f`** | `memory/project-context.md:322` |
| **提交由作者执行**，智能体只 `git add` 暂存或不提交 | 项目惯例 |
| 客户端专属类**不得**被公共类引用（服务端会在常量池里撞见它） | 斩杀线那轮确立的规矩 |

---

## 一、任务表

| # | 任务 | 依赖 | 判据（GREEN） | 证据块要求 |
|---|---|---|---|---|
| **T1** | **音效可用性冒烟（硬门）**：在龙卷风实体上起一个 `looping` 的 `SoundEvents.ELYTRA_FLYING`，进游戏听 | — | **真的有声音**（设计 §3.4/V1） | 一句实机结论 + 若非该音效则记录实际用的候选 |
| **T2** | 客户端音效 handler：`Map<实体 id, FollowEntitySound>`，`EntityJoinLevelEvent` 起、`EntityLeaveLevelEvent` 停 + 每 tick 兜底清扫（`isRemoved()` / `isStopped()`）；按设计 §3.5 决定是否按施法者去重 | T1 | `build` 通过；jar 内含新类；`TornadoEntity` 里**不出现**任何客户端类型引用 | 静态检查输出 + jar 条目清单 |
| **T3** | 注册 handler 到 `BeLoongCoreClient`（一行，先例 `:62`） | T2 | 同上 | 同上 |
| **T4** | `tornado.json` 三个字段：`lifetime` → linear(100,+20)；`cooldown` → linear(200,−20)；`damage_per_hit` → `lookup`(2.0/3.3/5.7/12.5/22.2/40.0 + linear fallback) | — | JSON 可解析；**逐级复算**得到命中 25/30/35/40/45/50、打满总伤 50/99/199.5/500/999/2000（设计 §4.2） | 一张复算表（命令行算出，不靠手推） |
| **T5** | 静态校验 + 构建 + jar 复核 | T2–T4 | `gradlew build --rerun-tasks --no-build-cache` **BUILD SUCCESSFUL**；jar 内 `tornado.json` 为新值；新类已打包 | 命令 + 输出尾部 |
| **T6** | 回填权威文档 `docs/自制龙之生存技能总设计.md` | T4 | 见 §五 清单全部改到 | diff 摘要 |
| **T7** | 本计划追加「实施结果」小节（项目惯例） | T6 | 与 design 文档一致 | — |
| **T8** | 实机验收 设计 §七 V1–V9 | T5 | 全部通过 | 作者结论 |
| **T9**（可选） | `tools/validate_tornado.py`：把设计 §4.2 那张表钉成断言 | T4 | 门变绿；**并做一次反向对照**（把某个值改坏 ⇒ 门必须变红） | 门输出 + 变异对照 |

**T9 的两条纪律**（来自斩杀那轮的教训，`memory/learned-patterns.md`）：

1. 断言要写「**不允许出现什么**」，不能只比两处下标的先后 —— 只比顺序的门对"提前 return 短路"是假阴性；
2. 每加一条断言都要配一次**最小变异实测**，并记录"变异是什么 + 期望红/绿"。

---

## 二、RED / GREEN 怎么定（本项目口径）

因为没有自动化测试，**RED = 对应的静态检查/复算失败**，**GREEN = 该检查通过**，行为正确性由 T8 的实机验收覆盖。
每个任务交付时必须给**证据块**（命令 + 输出尾部），不接受"应该没问题"。

---

## 三、风险与回退

| # | 风险 | 触发信号 | 回退 |
|---|---|---|---|
| R1 | `minecraft:item.elytra.flying` 在我们自己的播放路径下**同样无声** | T1 冒烟听不到 | 换对照音效（`SoundEvents.BREEZE_WHIRL` / `BUBBLE_COLUMN_WHIRLPOOL_AMBIENT`）先确认"是我们的路径问题还是音效问题"，再决定；**不要在 T1 未过时继续 T2 之后的工作** |
| R2 | 漏停 ⇒ 留下**永久残响** | V4 六种边界任一出现"风没了声音还在" | 兜底清扫是必做项（T2），不是可选项；必要时把清扫频率提到每 tick |
| R3 | `lookup` 在 **level 0** 崩溃 | V8 打开技能界面崩 | 本类 `getDescription` 已 clamp（`TornadoEffect` 注释有记录）；若仍有第二条 level-0 路径，改用 `clamped(linear)` 包一层 |
| R4 | 连发时双风柱音量翻倍/打夯 | V5 | 按设计 §3.5 切到"按施法者去重" |
| R5 | 试玩后觉得 **×80 强度跨度**过大 | T8 反馈 | 只需改 `damage_per_hit` 或 `cooldown` 曲线，**不动架构** |
| R6 | DS 升级时 `FollowEntitySound` 改名/改签名 | 编译失败 | 编译期就会暴露（不会静默）；届时照抄它 25 行自建一个本地类 |

---

## 四、提交拆分建议（作者执行）

| 提交 | 内容 |
|---|---|
| C1 | 音效：新增客户端 handler + `BeLoongCoreClient` 注册 + `TornadoEntity` 注释 |
| C2 | 数值：`tornado.json` 三个字段 |
| C3 | 文档：`总设计` 回填 + 本计划/设计的「实施结果」 |

⚠️ `tools/validate_tornado.py`（若做 T9）**不进任何提交** —— `tools/` 刻意不进版本管理。

---

## 五、权威文档回填清单（T6 必须逐项改到）

`docs/自制龙之生存技能总设计.md`：

| 位置 | 改什么 |
|---|---|
| §5.1 数据定义表 | `damage_per_hit` 行（linear → lookup 6 值）、`lifetime` 行（+15 → +20）、新增 `cooldown` 行说明（原来是裸数字 200，现在随等级递减） |
| §五 音效行 | 删「起始 `item.elytra.flying`」，写「施法者身上**没有**音效（该字段对 `simple` 激活不生效，2026-10-11 删除）；结束 `entity.breeze.wind_burst`；**实体本体**由客户端 `FollowEntitySound` 循环播放 `item.elytra.flying`（类别 NEUTRAL，音量/音高 1.0）」 |
| §5.5 各等级数值 | **整表替换**为新表（每次伤害/命中次数/打满总伤/寿命/DPS/冷却/同时在场风柱数），并把 DPS 列明确写成「**打满时的持续 DPS**」 |
| §5.7 设计理由与历史 | 追加两条返工：⑨ 实体音效（为什么 JSON 的 `sound` 字段对本技能无效）；⑩ 数值重标定 + 冷却递减 + **接受连发**（含 ×80 与 L6 双风柱的说明） |
| §5.2 / §5.3 | 若新增客户端 handler，需在「实体」小节补一句"音效由谁负责"，避免下次审查误判为漏做 |

---

## 六、批准与裁定（2026-10-11）

用户「确认」⇒ 按推荐执行：**① 按施法者去重**（同一玩家最多一条循环）；**② 做 T9**（已落地）。
`tools/validate_tornado.py` **不进版本控制**（`tools/` 既定政策），只活在作者的工作区。

---

## 七、实施结果（2026-10-11）

| # | 任务 | 状态 | 证据 |
|---|---|---|---|
| T1 | 音效可用性冒烟 | ⏳ **待作者实机**（唯一硬门；智能体无法进游戏） | — |
| T2 | 客户端 handler | ✅ 落地 | `client/TornadoSoundHandler.java`；`TornadoEntity` 内**无**任何客户端类型引用 |
| T3 | 注册 | ✅ 落地 | `BeLoongCoreClient` 构造器一行（+ import） |
| T4 | 三条曲线 | ✅ 落地 | `tornado.json`：`lookup [2.0, 3.3, 5.7, 12.5, 22.2, 40.0]`、`lifetime` +20/级、`cooldown` −20/级 |
| T5 | 构建 + jar | ✅ 通过 | `BUILD SUCCESSFUL`（3 警告不变）；jar 内新类已打包；jar 内 JSON **解析**核对通过 |
| T6 | 权威文档回填 | ✅ 落地 | `总设计` §5.1 表 / §5.3 音效条 / §5.5 数值表 / §5.7 ⑨⑩ |
| T7 | 本小节 | ✅ | — |
| T8 | 实机验收 V1–V9 | ⏳ **待作者** | — |
| T9 | `tools/validate_tornado.py` | ✅ 落地 + 4 组反向对照 | 门 `ALL CHECKS PASSED`；4 组变异**全部变红** |

**实施中改过设计一处**（已同步回设计文档 §3.3）：原设计还要挂 `EntityLeaveLevelEvent` 作为停止钩子，
落地时**去掉**，只用「每 tick 兜底清扫」一条路径 —— `AbstractTickableSoundInstance#stop()` 是
`protected final`，外部只能用 `SoundManager#stop(SoundInstance)`（DS 同款）；而 `FollowEntitySound.tick()`
**不会自己停**，两套记账只会多一个漏停口子。代价：最多 1 刻延迟。

**实施中踩到的坑（记录以免重复）**：handler 的构造器一度写成 `private` ⇒ `BeLoongCoreClient`（**不同包**）
`new` 不到，`compileJava` 直接失败。**默认构造器只有在类被声明为 `public` 时才是 `public`**，
所以这里必须显式写 `public TornadoSoundHandler() {}`。

### 证据块

```
四个静态门       validate_execute.py / validate_execute_line.py / validate_tornado.py -> ALL CHECKS PASSED
                 check_lang_parity.py -> ✅ 通过
build            gradlew build --rerun-tasks --no-build-cache -> BUILD SUCCESSFUL，3 个警告（不变）
jar 复核         client/TornadoSoundHandler.class 已打包
                 data/.../tornado.json 解析：damage_per_hit.type = minecraft:lookup
                 values = 2 / 3.3 / 5.7 / 12.5 / 22.2 / 40，lifetime 100 / +20，cooldown 200 / −20
                 sound.start 不存在；sound.end 未动
反向对照（4 组）  ① damage 改回等差 2/4/6/8/10/12       -> 门变红 ✓
                 ② cooldown 退回恒定 200                -> 门变红 ✓
                 ③ TornadoEntity 里加一句 playSound      -> 门变红 ✓
                 ④ 停止路径再加 EntityLeaveLevelEvent    -> 门变红 ✓
                 还原后文件哈希不变、门复绿 ✓
```

**下一步只有一件事**：作者按设计文档 §七 跑 V1–V9。**V1 是硬门** —— 若实体上听不到声音，
说明问题在音效本身而非播放路径，T2 之后的结论都要重来。
