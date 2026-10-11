# 斩杀技能 代码审查报告（2026-10-11）

- **审查对象**：斩杀技能（`beloong:execute`）相关代码与资源 —— 12 个 Java 文件（合计 **1419 行**）、能力与伤害类型数据、8 个伤害类型标签、中英语言键、音效 / 图标，以及 3 个工具脚本
- **范围**：已提交状态（`4eb73f4` → `c739527`，审查时工作区干净）
- **方法**：**3 名独立审查者**分片（服务端链路 / 客户端与同步 / 数据与门）+ 主审逐条复核。**关键结论均以源码或实测复现**；与审查者不一致处以复核为准（见 §五）
- **前提**：用户已于 2026-10-11 实机验收通过（「现在斩杀技能符合预期」）。本报告**只审查、未改任何代码**，只谈缺陷与风险
- **规范基线**：mixin 源文件含中文 javadoc **49/52**；`BeLoongCore.LOGGER` 调用点 **96** 处；日志无中文（**0** 处）；**项目无自动化测试**，静态门 + 实机验收是唯一验证手段

## 一、C-x（严重）

1. **兜底路径会把「更强的已有标记」降级，并改掉斩杀者** —— `registry/ExecuteMarkHandler.java:108-110`、`:155`
   `addEffect` 返回 `false` 有两种含义：**被拒**，或**已有更强的同类效果**（`MobEffectInstance.update:144-160`：新实例更弱时两个分支都不置位 ⇒ 返回 `false`）。第二种情况下会走 `forceAddEffect`（`LivingEntity:1014-1024`）**无条件覆盖** ⇒ A 用 L16（amp 39 / 20%）挂上的线，被 B 的 L1（amp 9 / 5%）覆盖，`:155` 还把 applier 换成 B ⇒ **线掉到 5%、斩杀者变成 B**。两条不同等级的龙打同一只怪即可触发。
   **修法**：兜底条件补「标记确实不存在」—— `if (!victim.addEffect(mark, player) && victim.getEffect(ModMobEffects.EXECUTE_THRESHOLD) == null) applyByForce(...)`。

2. **`tools/validate_execute.py` 第 ⑩ 组抓不到它守护的那个回归（主审实测确认）** —— `tools/validate_execute.py:316-325`
   在 `boolean damaged = victim.hurt(...)` 之后插入 `if (!damaged) { return; }`（**正是第四轮修掉的每 tick 重试 bug**），该组 **5 条断言全过、exit 0**（实测：变异 → 跑门 → 还原，文件哈希不变、门复绿）。该组只能抓「删除 / 改名」，抓不到「提前 `return` 短路」。
   ⇒ 附带更正：此前「第 ⑩ 组钉住了 a/b/c/d」的说法**不成立**，当时的反向对照只测了整行替换、没测这个最小变异。
   **修法**：断言 `src[hurt_at:remove_at]` 与 `src[hurt_at:cooldown_at]` 内**不含 `return`**，且 `removeEffect` / `ExecuteCooldown.mark` 的下标都**小于** `if (!damaged)`。

## 二、I-x（应修）

1. **「被挡下」只看 `hurt()` 的布尔 ⇒ 伤害被钳制时仍谎报「斩杀触发」** —— `registry/ExecuteThresholdEffect.java:192,205`
   灾变系 `hurt` 在伤害上限下**返回 `true`**（伤害落地但只有 20~25，`IABoss_monster.java:140`）⇒ `damaged = true` ⇒ 播斩杀音效 + 显示「斩杀触发」，目标却还活着。撤回 `bypasses_invulnerability` 后这**正是它们的常态**，设计文档 §十九 声称的「区分真斩杀 / 被挡下」对这类不成立。
   **修法**：以**结果**判定，例如 `boolean killed = victim.isDeadOrDying();`，`!damaged || !killed` 一并走 refused 分支。

2. **公开 API 的数值 javadoc 与实现矛盾** —— `registry/ExecuteThresholdEffect.java:29`、`registry/ExecuteMarkHandler.java:92-93`
   仍写「`amplifier = L + 4`（L1 → 5 → 3.0%；L15 → 19 → 10.0%）」与「amplifier 最大到 19 / `enchantment.level.20`」—— 那是 5%→20% 改版**之前**的 15 级口径。真值是 `amplifier = 2L + 7`（L1 → 9 → 5.0%，L16 → 39 → 20.0%）。静态门只钉 JSON，**钉不住注释**；照这两处改代码会得到错的斩杀线。
   **修法**：按 `amplifierForThreshold` 与 `execute.json` 重写这两处。（复核：同文件 `:97` 的「L + 4」讲的是**百分比**，**正确，不必改**。）

3. **静态门覆盖面缺口（同一根因：断言停在文本 / JSON 表面，无法失败）** —— 均属 `tools/`
   - a. **0.5% 网格硬编码在门里**（`:84,85,91`），Java 真值 `ExecuteThresholdEffect:68,71`（`THRESHOLD_PER_LEVEL` / `THRESHOLD_STEP_PERCENT`）**从未被读取** ⇒ 把 Java 步长改成 0.25，两门全绿而斩杀线判定偏移。
   - b. **只断言了 `cooldown["type"]`**（`:61`）；`threshold` / `damage` / `mark_duration` 的 `type` 与 `damage["per_level_above_first"]` 无人断言（`:54,73`）⇒ 派发名写错（或改成 `lookup`）两门全绿，只有进游戏加载数据包才炸。
   - c. **两条 actionbar 消息没有 `%s` 个数断言**（`triggered` 仅在 `:177` 的键表里、`refused` 仅在 `:324` 的存在性检查里；`:225-236` 的 arity 只覆盖 `dynamic_desc` / 3 条死亡消息 / 阶段词）⇒ 给 `refused` 多加一个 `%s`，两门全绿、渲染时抛 `MissingFormatArgumentException`（`effect.beloong.execute_threshold.description` 同类）。
   - d. **`sounds.json` 的 subtitle 键无人校验**（两门里 `subtitle` 零命中）⇒ 中英同时删掉 `subtitles.beloong.ability.execute`，门仍全绿、游戏里显示裸键。
   - e. **图标不变量零强制**（`validate_execute.py:96-110` 只查「存在 + PNG magic」）⇒ 不查 64×64（授权的例外尺寸）、不查 `execute_0` 是否为灰度。现状**是对的**（两者 64×64、`execute_0` 平均饱和度 0.0000；`make_execute_icons.py:76-82` 有自检），但**验收门无法失败**。
   - f. **客户端开关未与绘制路径绑定**（`ExecuteLineOverlay.java:103` 读 `Config.EXECUTE_LINE_ENABLED`，而 `validate_execute_line.py:188-208` 只解析键名 + 查译文）⇒ 删掉那次 `get()`，开关空转、两门全绿。
   - g. **`NO_MARK`（`-1`）契约无断言**（定义 `ExecuteMarkSyncPayload.java:42`，消费 `ExecuteMarkCache.java:26,36`、`ExecuteMarkSyncHandler.java:39`）⇒ 改成 `0`（合法 amplifier）会让未挂标记的生物血条上出现一条陈旧的 0.5% 线，无门可查。
   **修法**：a–g 逐条加断言（a 改为从 Java 正则读常量；b 四个 `type` 全断言；c 补 arity；d 遍历 `sounds.json` 逐个 subtitle 查语言文件；e 断言尺寸 + 灰度；f 断言 overlay 里确有该 `get()`；g 断言 `NO_MARK == -1` 且 `ExecuteMarkCache` 按 `< 0` 判定）。

4. **守本功能的门没进版本库** —— `tools/validate_execute_line.py`
   `git check-ignore -v` ⇒ `.gitignore:49:tools/`（整目录忽略），`git ls-files tools/` 里**没有它**；而它承载 **10 组 51 条断言**，其中第 ⑧ 组正是「无渲染」那次 bug 的回归闸。功能已提交三次、门一直只在工作区。
   **处置（2026-10-11 用户裁定）**：⏸ **不修，且不算缺陷** —— `tools/` **刻意不进版本管理**（项目既定政策，`memory/project-context.md:322`）。改为**修正相关注释与文档**：设计文档 §14.4/§19.6、计划文档、本报告均已删去「提交时必须 `git add -f`」的旧指引，改为记录该政策及其代价（验证结论的可复现性依赖工作区脚本）。

## 三、S-x（可选）

- **S-1** `ExecuteLineOverlay.java:103` 的 `Config.EXECUTE_LINE_ENABLED.get()` 无加载态保护 —— 未加载时 `ConfigValue.get()` 抛 `IllegalStateException`，而抛点在**每帧** GUI 路径里。修法一行（`!Config.CLIENT_SPEC.isLoaded() ||`）。⚠️ 但**前一次审查已论证其不可达**（CLIENT spec 在模组初始化期注册，GUI 帧不可能更早；缺配置文件按默认值落盘），故列为防御性而非缺陷。
- **S-2** `ExecuteMarkHandler.java:214` `isMarkableBy` **无任何调用者**（注释说给血条用，血条实际走同步包）⇒ 未接线的公开 API。
- **S-3** `ExecuteAbility.java:100` `entityTargeting` **无调用者** ⇒ 死代码（YAGNI）。
- **S-4** `ExecuteMarkSyncHandler.java:63-66` 的 `StartTracking` 对**每个**进入追踪范围的生物都发一条（未挂标记时发 `NO_MARK`，约 6 字节）—— 可加「无标记就早退」；但另一位审查者指出当前写法**顺带纠正客户端陈旧条目**，取舍留给你。
- **S-5** `ExecuteThresholdEffect.java:93-95` `thresholdFraction` **无上限夹取** ⇒ 外部给超大 amplifier 就得到 >100% 的斩杀线。⚠️ 可达性受闸门 ② 限制（指令施加的效果没有 applier ⇒ 不触发），建议仍夹到设计上限 amp ≤ 39。
- **S-6** `ModAttachments.EXECUTE_READY_AT`（`serialize(Codec.LONG)`）**未声明 `copyOnDeath`** ⇒ 死亡即清冷却，可「自杀重置冷却」。若本包开了 `keepInventory`，代价降低，可提升为 I。
- **S-7** `ExecuteThresholdEffect.java:85-86` 注释写「暗金」，`0x8B0000` 实为**暗红**（139,0,0）；因 `visible` / `showIcon` 全关，纯注释问题。
- **S-8** `tools/check_lang_parity.py` 当前因 **3 个与本功能无关**的键（`beloong.command.route.query_done`、`…query_unloaded`、`dragon_ability.beloong.air_strike.desc`）为红 ⇒ **不能当语言门用**，本功能的语言一致性目前只靠 `validate_execute.py [7]`。
- **S-9** `validate_execute_line.py:176` 能抓「注释掉的 `ExecuteLineOverlay.draw(...)`」（剥注释后不算引用者），但 `if (false) ExecuteLineOverlay.draw(...)` 仍全绿。
- **S-10** `ExecuteMarkCache.java:20` 无逐实体失效：实体在无人追踪时卸载则不触发 `onEffectRemoved` ⇒ 条目残留到下次登录。仅有界内存残留（`Entity.java:154` 的 id 会话内单调、不回收），无正确性影响。

## 四、已知取舍（**不是缺陷**，仅登记）

- 标记搭在 **mob effect** 上 ⇒ 免疫一切效果的生物（原版凋灵 `WitherBoss:529-532`）拒收；
- `MobEffectEvent.Applicable` + `Result.APPLY`（`LOWEST`）与 `forceAddEffect` 兜底是**刻意**穿透两道闸门，作用面已限制在本效果（`ExecuteMarkHandler.java:134` 先做 Holder 比较）；
- `hurt` 被拒也**消耗标记 + 进冷却**（`ExecuteThresholdEffect:192-215`），冷却期间**禁止挂线**（反转了 `ExecuteCooldown` 旧注释，见设计文档 §十七）；
- `isOurExecuteDamage`（`ExecuteMarkHandler.java:59`）阻止自家斩杀伤害重新挂线；
- **`bypasses_invulnerability` 刻意缺席**（两门均已反向钉住）；**不为 Mowzie / 传怪物写 mixin** ⇒ 部分 Boss 永远斩不掉（§十八/§十九已裁定）；
- `damage: 999999`、图标 **64×64**（授权例外）、8 个标签文件省略 `"replace"`（默认 merge，保 vanilla 条目）—— 均正确。

## 五、复核更正与未能验证

**复核更正了审查者的两处结论**：
1. 审查者 A 把 `ExecuteThresholdEffect.java:97` 也列为过期数字 —— **不成立**：那句「斩杀线落在 L + 4」讲的是**百分比**（L1→5%、L16→20%），与 JSON 一致；
2. 审查者 B 把 `Config...get()` 的加载态列为 **I** —— 按前一次审查的取证（已论证不可达）**降为 S-1**。

**未能验证**（未猜）：
- **数据包 codec 级合法性**（`beloong:execute` 的派发名 / 必填键）—— 两门只做 JSON 语法 + 算术推演，须真加载才能验（文档记 2026-10-11 实机加载零错误）；
- 本次构建 jar 的 refmap 与注入点字节码**未重新反编译核对**（沿用此前 `javap` 结论）；
- `MobEffectEvent.Applicable` 若另有模组同样用 `LOWEST` 注册，谁最后生效取决于注册顺序（只确认了该事件不可取消、结果取最后一个 `setResult`）；
- 实机运行：三名审查者均为只读静态审查。

**已核实为正确、无需改动的要点**（择要）：几何 `posX - 1` 的推导对**所有**条宽（含 Boss 加宽）成立、注入点唯一性无歧义、`shift = AFTER` 不盖伤害飘字；`handleClient` 经 `MainThreadPayloadHandler` 在主线程执行 ⇒ 裸 `HashMap` 与渲染线程无竞争；实体 id 会话内单调、重连由登录清空覆盖；mixin 命名空间与 `remap` 规范全部合规；`Config` 字段 → 静态块 → `CLIENT_SPEC.build()` 的行序正确；`onEffectRemoved` 的四个调用点全在服务端且 level 已设；16 级曲线端点（阈值 5.0→20.0、amp 9→39、冷却 200→80 全为整 tick 且严格递减、成长 150→300 与 `maximum_level: 16` 一致）自洽。

## 六、处置（2026-10-11 修复轮）

用户裁定：**修复全部 C-x 与 I-x**；S-x **除 S-5 外全部处理**（S-5 是刻意设计）；并**顺便修好** S-8 涉及的语言键；
**`tools/` 不进版本管理**，改为修正相关注释与文档。

| 编号 | 处置 |
|---|---|
| **C-1** | ✅ 已修：兜底条件改为 `!addEffect(...) && getEffect(...) == null`（`ExecuteMarkHandler`）；门第 ⑩ 组新增断言钉住 |
| **C-2** | ✅ 已修：第 ⑩ 组改为「`hurt` 到**无条件**移除标记之间不得有 `return`」+ 分析前剥离注释；**已用同一变异实测确认现在会变红** |
| **I-1** | ✅ 已修：改用 `isDeadOrDying()` 判定，`!damaged \|\| !killed` 走「被挡下」。**另按用户裁定新增**：目标在我们出手前已死（尸体）⇒ 消耗标记、**不进冷却**、对玩家静默（留 `DEBUG`） |
| **I-2** | ✅ 已修：`ExecuteThresholdEffect:29`（`amplifier = 2L + 7`）、`ExecuteMarkHandler:92-93`（amp ≤ 39 / `enchantment.level.40`）、两处单位示例统一为 5.0%；**另修正**该类 javadoc 里「冷却期间目标卡在线上等着被斩」的过期结论（冷却已反转为不可挂线） |
| **I-3 a–g** | ✅ 全部已加断言：从 Java 读网格常量、四个 `type` + `damage` 斜率、两条 actionbar 与效果描述的 `%s` 个数、`sounds.json` 字幕键、图标 64×64 与 0 级灰度、开关真实被读取、`NO_MARK == -1` |
| **I-4** | ⏸ **已裁定不修**（`tools/` 刻意不进版本管理）⇒ **改注释与文档**：设计文档 §14.4/§19.6、计划文档、本报告均改为该政策表述 |
| S-1 | ✅ 已加 `Config.CLIENT_SPEC.isLoaded()` 前置 |
| S-2 | ✅ 已删（`isMarkableBy` 无调用者） |
| S-3 | ✅ 已删（`entityTargeting` 无调用者，并删除随之未用的 import） |
| S-4 | ⏸ 按用户裁定**保留 + 写明理由**（早退会让「离开视野又回来」的实体留下陈旧线） |
| S-5 | ⏸ **刻意保留**（用户裁定：允许 >100% 斩杀线）⇒ 已在 `thresholdFraction` 写明 |
| S-6 | ✅ 已加 `.copyOnDeath()` |
| S-7 | ✅ 注释改为「暗红 `RGB(139,0,0)`」 |
| S-8 | ✅ 已修：3 个语言键的颜色码对齐（只加色码、未改文案）⇒ `tools/check_lang_parity.py` **首次全绿** |
| S-9 | ✅ 已加「同一行不得被 `if (false)` 短路」断言（文本级启发式，残余局限已在门里注明） |
| S-10 | ⏸ 按用户裁定**保留 + 写明理由**（实体离开追踪范围 ≠ 标记失效） |

**验证**：`validate_execute.py` / `validate_execute_line.py` / `check_lang_parity.py` **三门全绿**
（前两个门现为 **11 组 76 条** / **10 组 51 条**断言）；
`build --rerun-tasks --no-build-cache` BUILD SUCCESSFUL（3 个警告不变）；C-2 的变异对照已重跑。

**最终复验（2026-10-11）**：修复后委托方再次实机测试，结论「**斩杀技能没有问题了**」⇒ **本功能收尾结束。**
