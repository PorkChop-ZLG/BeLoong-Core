# NPC 表情 / 攻击动画 代码审查报告

**审查基线**：`origin/NPC` .. `HEAD`（25 个提交；23 个文件 = 10 Java / 3 动画 / 1 geo / 1 贴图 / 2 语言 / 4 文档）
**日期**：2026-09-29
**方法**：两路子代理分片审查（① Java 逻辑 ② 资产与验证可信度，均**只读**）+ 主审逐条**自验**引用行号、SHA256 与字节数
## 审查范围（用户 2026-09-29 裁定）

**只审 `src/` 里的代码与资源**（`src/main/java`、`src/main/resources`）。
智能体自己写的**工具与脚本不在范围内** —— 仓库内 `tools/`、仓库外 `D:\Minecraft\tools\` 均不计入发现。
⇒ 凡"某个脚本不在仓库 / 某条断言会假通过"这类条目**不列为本报告的 finding**（那会把注意力从产品代码引开）；
本报告初稿列过 3 条此类条目，已按本裁定删除，并对余下条目**重新编号**。
唯一保留的相关提示：**`src/` 里的注释若引用了范围外的工具**（例如"由探针 X 守着"），
本报告不对其可核查性下判断。

**本次范围内的实际文件**：`NpcEntity` / `NpcState` / `NpcAttackGoal` / `MoEntity` / `DihuangLoongEntity` /
`EmoteAnimationLookup` / `MoModel` / `DihuangLoongModel` / `NpcCommand` / `ModEntities`、两个语言文件、
三个动画 JSON、`geo/mo.geo.json`、`textures/entity/mo.png`。


**结论**：**无 Critical**；**8 Important**（全部是"`src/` 内的叙述与代码/资产不符"，**没有一条是行为缺陷**）；**8 Minor**；**6 项只能实机判定**

> **本文只记录审查结果，不含修复。** 修复时按编号点即可。
> 编号：`I-` = Important，`M-` = Minor，`V-` = 只能实机判定。

---

## 一、已独立复核通过（不再展开）

- **抽稀边界**：7 条被抽稀动画**全部落在各自声明的档位内**（`attack` rot 1.4995/1.5、pos 0.0225/0.03、scale 0.0100/0.01；`fly` 0.5000/0.5；`idle` 0.2994/0.3；`run` 0.5000/0.5；`walk` 0.4996/0.5；`dance` 0.4990/0.5；`sit` 0.2989/0.3）；**0 处**丢通道 / 新增通道 / 残留无效骨骼 / catmullrom 丢失及邻键丢失 / 非静态首尾键变化 / 键数增加；静态通道值逐字节等价 ✓
- **无"整条动画被烘焙丢弃"风险**：三份文件的字符串值均不含会触发 `MathParser` 异常的内容（`EXPRESSION_FORMAT` 与 `FUNCTION_FACTORIES` 由子代理独立从 GeckoLib 源码核对）✓
- **代码侧**：三层控制器注册顺序 `main` < `emote` < `attack` ✓；服务端清表情 / 客户端点播的分工 ✓；`DATA_EMOTE` 不落盘 ✓；`resetToDefault` 确实清四样 ✓；`NpcState` 只剩两态且 `IDLE=0`/`FLYING=1` 未变 ✓；指令面八条与类注释一致 ✓；两语言 235/235 键齐 ✓
- **关键**：`hasActiveEmote()` 的存在性守卫在位 ⇒ 此前那个"两控制器同时 STOP ⇒ 全骨骼吸附初始快照（永久 T-pose）"的 **Critical 没有复发** ✓
- **声明与实际一致**：`mo.geo.json` 271 骨骼 / 858 立方体 / 1 根根骨骼 `Root_Molang` / 464,665 B（"454 KB"✓）；`mo.png` 512×512 / 56,823 B（"55 KB（512×512）"✓）

---

## 二、Critical

**无。**

---

## 三、Important（11）

**I-1｜`MoModel`「仍然对不上的 5 个骨骼」整节已与资产相反。** 抽稀提交已把 `Skirt` / `Wave_1` / `ysmGlowWave_1_1` / `_1_2` / `Eyes` 的通道**全部删除**（现各出现 0 次），而注释仍称它们"被动画引用、但 geo 里不存在"。
位置：`client/model/MoModel.java:72-82`、`:115-119`。

**I-2｜`NpcEntity` 注释"本类只加一项 `DATA_STATE`"，实际定义了两项**（`DATA_STATE` + `DATA_EMOTE`）。
位置：`entity/NpcEntity.java:420`（代码在 `:426` 与 `:430`）。

**I-3｜`EmoteAnimationLookup` 的立项理由写错。** 注释称"名字不存在会让 `AnimationProcessor` 打 `ERROR` + `printStackTrace`"——实际上 `AnimationProcessor.java:49-57` 的 catch **只在该方法抛异常（动画文件缺失）时**才触发；名字不存在时 `GeoModel.getAnimation` 只是返回 `null`。
**结论仍对**（缺名字会让控制器被 `stop()`，在双控制器结构下正是 T-pose 的成因），但**理由是错的**。
位置：`client/model/EmoteAnimationLookup.java:20-22`。

**I-4｜`resetToDefault` 的方法注释漏了第四样（表情）。** 正文仍写"`IDLE`、无移动、无攻击、地面站桩"，且称它是"`state … idle` + `stop` + `attack … stop` **三条**的合并"，而代码实际清**四**样（`:956` 的 `clearEmote()`）。
位置：`entity/NpcEntity.java:938-940`。

**I-5｜`ModEntities` 的"逐字节拷贝"已过期。**
*（主审自跑 SHA256：`docs/models/末2/mo.geo.json` 544,087 B → 仓库内 464,665 B；`mo.png` 68,279 B → 56,823 B，两份均不同。）*
位置：`registry/ModEntities.java:83`。**修法**：改为"取自 … 并经后续修改"。

**I-6｜承重顺序警告写"下面两个 add"，实际是三个**（`main` / `emote` / `attack`）。
位置：`entity/NpcEntity.java:1217`。

**I-7｜`NpcState` 类首段仍以"飞行、坐下、跳舞"举例**，并称它们"要活过存档重登"——坐下/跳舞已不是状态，且表情明确**不落盘**（设计 D5）。
位置：`entity/NpcState.java:16-18`。

**I-8｜地黄龙动画被删 90 条发生在 `c471461「简化动画」`，不是 `e24e28d`。**
基线 488,150 B / 96 条 → `c471461` 59,720 B / 7 条；`e24e28d` 相对其父**只改了 `attack.loop`（`true` → 缺省）**，其余 6 条（`dance`/`fly`/`idle`/`run`/`sit`/`walk`）与基线同名动画**逐字节相同**，`attack` 在基线里**不存在**（新增或改名）。
⇒ 主审此前"477 KB/96 条是 09-25 准确、之后变陈旧"的**归属说法要更正**为"由 `c471461` 一次删除"。

---

## 四、Minor（8）

**M-1｜`emote()` 的注释说"有值时 `main` 就让位"**，漏了"还要求该名字**查得到**"（`hasActiveEmote()` 里的 `find` 检查）。位置：`entity/NpcEntity.java:692`（判定在 `:1304-1316`）。

**M-2｜`resetToDefault` 里清两次**：`clearEmote()` 之后 `setState(IDLE)` 也会清一次 ⇒ 每次 `reset` 多发一个 `force=true` 同步包。位置：`entity/NpcEntity.java:956-957`。

**M-3｜`swing()` 服务端分支每次挥砍都无条件 `clearEmote()`** ⇒ 即使本无表情也会发 `force=true` 包。位置：`entity/NpcEntity.java:684`。

**M-4｜`NpcState.java:28` 的小节标题仍是"移动模式 × 姿态两个正交轴"的框架**，只靠 📌 补丁说明其判据 `isMovementMode()` 已删。

**M-5｜`hasActiveEmote()` 在 `main` 与 `emote` 两个谓词里各查一次** ⇒ 表情生效期间每帧两次哈希查找（与"刻意不复缓存"的设计一致，仅记录）。位置：`entity/NpcEntity.java:1225`、`:1261`。

**M-6｜`visible_bounds_*` 在 GeckoLib 4.9.2 里被解析但零消费者。**
*（主审自验：全源码仅 `loading/json/raw/ModelProperties.java` 的 6 处读取，无任何使用点。）*
⇒ `0104892` 的 `visible_bounds_height 11→27`、`offset [0,0.5,0]→[0,8.5,0]` **实际无效果**；真实剔除由 Java 侧 `MoEntity.CULLING_INFLATE = 7.0D`（`:180`）与 `getBoundingBoxForCulling()`（`:199-200`）决定。另：geo 自身立方体范围约 3.34 × 5.32 × 7.89 格，而声明盒 26 × 27 —— 即便生效也严重超配。

**M-7｜"`descend` 一字未动"只在优化那一步成立。** 相对基线它其实是本区间**新增**的（`dfffc01` 引入 extra 时 `loop="hold_on_last_frame"`、11.2533 s），随后 `0104892` 改成 loop 缺省 + 6 s；优化提交 `a28e865` 相对 `0104892` **深度相等** ✓。它**没有超出声明时长的死键**（最大时间键 5.6267 < 6）✓。

**M-8｜量测口径备忘：量 git 里的大文件/二进制不能用 `git show` + text 解码**（会解码并做 CRLF→LF，`mo.png` 会得出 84,397 B ✗，真值 **46,244 B**）⇒ 一律用 `git cat-file -s`。
顺带得到 25 个提交的**总账**：`mo.animation.json` **9,566,138 B（9.12 MiB）→ 774,125 B**，即 **−92%**（此前口头说过的 2.11 MiB 是区间内的**中间态**）。

---

## 五、只能实机判定（6）

**V-1｜"挥砍清表情"从未被四轮验收覆盖。** 攻击动画包与 `DATA_EMOTE` 同步包在同一服务端 tick 发出，但**客户端是否同一 tick 应用未验** ⇒ 理论上可能出现一帧"攻击动画 + 残留坐姿"的混合姿态。位置：`entity/NpcEntity.java:671-685`、`:1289-1295`。

**V-2｜`attack` 控制器沿用 `animationTransitionTicks()` = 5 tick** ⇒ 攻击动画前 5 tick 是混合进来的。"触发同帧"确定，**混合时长**未验。位置：`entity/NpcEntity.java:1293`。

**V-3｜`onSyncedDataUpdated` 的重置依赖"`assignValues` 对每个条目无条件通知"**（此前仅有静态依据）⇒ 若某版本只通知变化项，"同名重播"会退化成不播。位置：`entity/NpcEntity.java:731-738`。

**V-4｜抽稀容差对应的观感**（数学边界已证，视觉未证；尤其 `sit` 压掉 78%：3,741 → 816 键）。

**V-5｜`descend` 的 3° 档从未被使用**（该条整条未动）⇒ 这一档的取值**没有任何实机依据**。

**V-6｜`dance` 保留率 81%**（6,623 → 5,348 键）之后的观感。

---

## 六、采信说明（可追溯性）

- **主审自验**：I-1（`MoModel` 那节与资产相反）、I-3（GeckoLib 源码机制）、I-5（SHA256）、
  I-8（逐提交字节数）、M-6（全源码搜消费者）、M-7、M-8 的关键数字 ——
  均以**打印行号原文 / SHA256 / `git cat-file -s`** 独立确认。
- **采信子代理证据**：I-2、I-4、I-6、I-7 与 M-1～M-5（子代理给出了行号；主审抽查其中多条属实，
  且其行号引用**零错**）。
- 两路子代理均**只读**；主审亦未在审查期间改动任何文件。

---

## 七、建议的修复优先级

1. **先做 I-1**（`MoModel` 那节与资产相反，最容易被后人当真）与 **I-3**（预检的立项理由写错，
   会误导后来者判断"为什么要有这个类"）；
2. 然后 I-2 / I-4 / I-5 / I-6 / I-7（五处一句话级叙述修正）；**I-8** 只需一句归属更正；
3. M 组可顺手带上（M-2/M-3 各是一行守卫）；M-6 建议顺带决定那个 `visible_bounds` 改动是否有意义；
4. **V 组需要你实机**，其中 V-1 与 V-4 最值得先看。
