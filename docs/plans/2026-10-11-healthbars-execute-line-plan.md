# Health Bars 斩杀线覆盖层 实施计划

**目标：** 让已学斩杀的龙在用准星指向怪物时，Health Bars 左上角血条上出现橙色半透明 `0→斩杀线` 区段与右端竖线，位置等于该龙当前等级对应的真实斩杀线。
**架构：** 一个客户端 Mixin（`@Pseudo` + `require=0`）注入 Health Bars 的私有 GUI 血条方法，把几何与实体转发给一个纯客户端 helper；斩杀线从 mob effect 的 amplifier 读，**零新增网络通道**。
**选定方案：** 方案 A（注入 `GuiRenderingHandler.renderHealthBar` 的 `HealthBarRenderHelper.renderHealthBar` 调用**之后**，不是 TAIL）
**实施策略：** 门先行（本仓库无测试套件 ⇒ 静态门脚本就是 RED/GREEN 的那道门）
**设计文档：** `docs/plans/2026-10-11-healthbars-execute-line-design.md`（已批准）

> **关于 TDD 的本地化**：本仓库**没有测试套件**（无 `src/test`），项目既有的验证口径是
> 「`gradlew build` + 静态探针脚本 + 实机验收」。因此每个任务的 **RED = 静态门对应断言失败**，
> **GREEN = 该断言通过**；行为正确性由最后的实机验收覆盖。

---

## Task 1：静态门脚本（**先让它红**）

**Files:**
- Create: `tools/validate_execute_line.py`
- 参考既有写法：`tools/validate_execute.py`（同一套 `check(ok, msg)` + 末尾 `FAILED n check(s)` / `sys.exit(1)`）

**Steps:**
1. 写脚本，实现 4 条断言（`build` 不在此脚本内跑，见 Task 5）：
   - ① `beloong.mixins.json` 的 **`client`** 数组含 `healthbars.GuiRenderingHandlerMixin`
   - ② `src/main/java/com/zonlong/beloong/mixin/healthbars/GuiRenderingHandlerMixin.java` 存在，
        且含 `@Pseudo`、含 `require = 0`、含目标方法名 `renderHealthBar`
   - ③ `compat/healthbars/ExecuteLineOverlay` **只被该 mixin 引用**：
        全仓扫 `src/main/java/**/*.java`，统计引用 `ExecuteLineOverlay` 的文件，
        期望**恰好 1 个**（就是这个 mixin）。**这条防的是「双端类引用客户端类」导致专用服务端
        `NoClassDefFoundError`（设计文档 §6.3 的坑）**
   - ④ 中英语言文件各含 4 个配置键：
        `beloong.configuration.execute_line`、`.tooltip`、
        `beloong.configuration.executeLineEnabled`、`.tooltip`
2. 运行它。

**Verification:**
```
python tools\validate_execute_line.py   → 期望 exit 1，且失败行精确指出「mixin 不存在 / client 列表缺条目 / 键缺失」
```

---

## Task 2：`ExecuteLineOverlay` —— 常量与几何

**Files:**
- Create: `src/main/java/com/zonlong/beloong/compat/healthbars/ExecuteLineOverlay.java`

**Steps:**
1. 常量（设计文档 §6.2，**全部写死为代码常量**）：
   `FILL_ARGB = 0x66FF8028`、`LINE_ARGB = 0xFFFF8028`、`BAR_HEIGHT = 5`、`LINE_WIDTH = 2`、`LINE_OVERHANG = 2`
2. 几何纯函数（便于日后静态核对，且把 `clamp` 收在一处）：
   ```java
   static int lineX(int barLeft, int barWidth, float pct) {
       int clamped = (int) Math.round(barWidth * Mth.clamp(pct, 0.0F, 1.0F));
       return Mth.clamp(barLeft + clamped, barLeft, barLeft + barWidth);
   }
   ```
3. 主入口（**此时先不接 mixin**，也先不读配置）：
   ```java
   public static void draw(GuiGraphics gg, int barLeft, int barTop, int barWidth, LivingEntity entity)
   ```
   内部：`entity.getEffect(ModMobEffects.EXECUTE_THRESHOLD)` → `null` 早退 →
   `ExecuteThresholdEffect.thresholdFraction(fx.getAmplifier())` → 算 `lineX` →
   `gg.fill(...)` 两次（填充 + 竖线）。
4. 类注释写清三件事：为什么只能被客户端引用、常量各自改动后的后果、
   `barLeft = 注入点的 posX`（含「两次整除相消」的推导来源）。

**Verification:**
```
.\gradlew.bat compileJava --rerun-tasks --no-build-cache     → BUILD SUCCESSFUL
python tools\validate_execute_line.py                        → 仍红（mixin 还没接），但 ②③ 的失败原因应只剩“mixin 不存在”
```

---

## Task 3：Mixin 类 + 注册到 `client` 列表

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/healthbars/GuiRenderingHandlerMixin.java`
- Modify: `src/main/resources/beloong.mixins.json`（**`client`** 数组追加一条）

**Steps:**
1. 照设计文档 §五 的注解码写类：`@Pseudo` + `@Mixin(value = GuiRenderingHandler.class, remap = false)`，
   `@Inject(method = "renderHealthBar(<8 参描述符>)V", at = @At(value = "INVOKE", target = "...HealthBarRenderHelper;renderHealthBar(...)V", shift = At.Shift.AFTER), require = 0, remap = false)`。
2. 方法体只做转发：`ExecuteLineOverlay.draw(gg, posX.intValue(), posY.intValue(), barWidth, entity);`
3. **锚点日志**：仿既有 `HealthTrackerHandlerMixin#beloong$anchorLogged`，
   第一次真正绘制时 `LOGGER.info("[BeLoong] healthbars: execute line overlay active")`（进程内一次）。
4. `beloong.mixins.json` 的 `client` 数组追加 `"healthbars.GuiRenderingHandlerMixin"`（与既有
   `"healthbars.HealthTrackerHandlerMixin"` 同列；**不要放进 `mixins`**——那是双端列表）。
5. 类注释写清：为什么注 `INVOKE + shift AFTER` 而不是 TAIL（不盖伤害飘字）、
   为什么目标是私有方法、失效模式是什么。

**Verification:**
```
.\gradlew.bat compileJava --rerun-tasks --no-build-cache   → BUILD SUCCESSFUL（refmap 生成成功，无 “Unable to locate” 新增警告）
python tools\validate_execute_line.py                      → ①②③ 转绿，仅 ④ 仍红
```

---

## Task 4：客户端配置 + 中英翻译

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`（CLIENT_SPEC 内新增 `[execute_line]` 段）
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Steps:**
1. 在 `Config` 的客户端 `static { ... }` 块里 `push("execute_line")` / `pop()`，
   定义 `public static final ModConfigSpec.BooleanValue EXECUTE_LINE_ENABLED`，
   带 `.comment(...)`（英文 + 中文各一行，照 `npc_dialogue` 的写法）与
   `.translation("beloong.configuration.executeLineEnabled")`，`define("enabled", true)`。
   **⚠️ 不要在 Config 里引用 `ExecuteLineOverlay`**（设计文档 §6.3）。
2. `ExecuteLineOverlay.draw` 开头加 `if (!Config.EXECUTE_LINE_ENABLED.get()) return;`
3. 中英语言文件各加 4 个键（值照项目语气，中英一一对应）：
   - `beloong.configuration.execute_line` = 「斩杀线显示」/ "Execute Line"
   - `beloong.configuration.execute_line.tooltip` = 段说明
   - `beloong.configuration.executeLineEnabled` = 「启用斩杀线显示」/ "Show Execute Line"
   - `beloong.configuration.executeLineEnabled.tooltip` = 值说明
4. 注意语言文件里**不能出现裸 `%`**（本项目既有踩坑；本组文案无占位符，天然安全）。

**Verification:**
```
python tools\validate_execute_line.py   → ALL CHECKS PASSED（exit 0）
python tools\check_lang_parity.py       → 中英键集合一致（若脚本存在）
.\gradlew.bat compileJava --rerun-tasks --no-build-cache   → BUILD SUCCESSFUL
```

---

## Task 5：全量验证（回归 + 打包复核）

**Files:** 无改动，只跑门与构建

**Steps:**
1. 跑既有门确保**没有回归**：`python tools\validate_execute.py` → 仍 `ALL CHECKS PASSED`
2. 跑新门：`python tools\validate_execute_line.py` → `ALL CHECKS PASSED`
3. 全量构建：`.\gradlew.bat build --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`，
   警告数与改动前一致（既有 3 条 mixin 映射警告，**不得新增**）
4. **从成品 jar 里复核**（不能只看源码）：解出 `beloong.mixins.json`，
   确认 `client` 数组含新条目；确认 `lang/*.json` 含 4 个新键

**Verification:**
```
上面四条命令逐条贴出输出与 exit code（证据块）
```

---

## Task 6：交实机验收 + 回填实施结果

**Files:**
- Modify: `docs/plans/2026-10-11-healthbars-execute-line-plan.md`（追加 §实施结果）
- Modify: `memory/decisions-log.md`（把 2026-10-11 那条从「未实施」改为实施状态）

**Steps:**
1. 把设计文档 §九-9.2 的 6 条实机验收清单交给用户（需在能跑客户端的机器上做）
2. 用户回报后，把结论回填到本计划 §实施结果（照项目惯例：计划文档末尾记实施与验收结果）
3. **在实机结果出来前，不要声称「功能完成」**——静态门只证明接线正确，不证明画面正确

**Verification:**
```
用户回报的实机结果（尤其是「竖线在 L1=5% / L16=20% 两处位置」与「打半血区段不缩水」）
```

---

## 风险与回退

| 风险 | 触发条件 | 回退 |
|---|---|---|
| 注入点失效（遮罩不显示） | 上游改私有方法名/签名 | 静态门 ② 会红；锚点日志不打印；按设计文档 §十二 重新核对 |
| 遮罩错位 | 上游改条高 ≠ 5 | 调 `BAR_HEIGHT`；记入设计文档 §十二 |
| 专用服务端崩 | 有人从双端类引用了 overlay | 静态门 ③ 会红（正是为此而设） |
| 颜色/α 不合观感 | 实机 | 改 `FILL_ARGB` / `LINE_ARGB`（用户已裁定不暴露到配置，需重新构建） |

**整体回退**：删 `mixin/healthbars/GuiRenderingHandlerMixin.java`、`compat/healthbars/ExecuteLineOverlay.java`、
`tools/validate_execute_line.py`，并撤销 `beloong.mixins.json` / `Config.java` / 两个语言文件的对应条目。

---

## 下一步

计划获批后，按 Phase 5 转入执行（本项目任务数 < 10 ⇒ `task-management` 顺序执行）。

---

## 实施结果（2026-10-11）

**状态：6 个任务全部完成；静态验证全绿；实机验收待用户执行。**

| # | 任务 | 结果 |
|---|---|---|
| 1 | 静态门脚本 | 建成；首跑 **exit 1 / 5 条失败**（RED 符合预期） |
| 2 | `ExecuteLineOverlay` | 建成；`compileJava` 通过 |
| 3 | Mixin + 注册 | 建成；描述符由**实际 jar** `javap` 取得；警告数 **3 → 3（无新增）** |
| 4 | 配置 + 中英翻译 | 建成；键数 322 → **326**，中英集合与顺序一致 |
| 5 | 全量验证 | 两个门全绿；`build` **BUILD SUCCESSFUL**（3 警告不变）；**jar 内复核**：client 列表含新条目、双端列表不含、中英各 4 键、两个 class 已打包 |
| 6 | 实机验收 | **待用户执行**（清单见设计文档 §9.2） |

### 证据块

```
python tools\validate_execute_line.py   -> ALL CHECKS PASSED  (exit 0)
python tools\validate_execute.py        -> ALL CHECKS PASSED  (exit 0，无回归)
.\gradlew.bat build --rerun-tasks --no-build-cache
                                        -> BUILD SUCCESSFUL (exit 0)，3 个警告（与改动前一致）
jar beloong-0.10.4.jar                  -> client 列表含 healthbars.GuiRenderingHandlerMixin；
                                           中英各 326 键含 4 个新键；
                                           GuiRenderingHandlerMixin.class / ExecuteLineOverlay.class 均在
```

### 实施后审查与修正

按 `task-management` 的 Batch Review 做了一轮只读对抗性审查（含对实际依赖 jar 的 `javap` 字节码核对）。
**无 Critical**。修正项（详见设计文档 §十四）：

| 编号 | 问题 | 处置 |
|---|---|---|
| I-1 | 几何 off-by-one：条左真值是 `posX - 1`，本文档与代码都按 `posX` 实现 | **已修**：`draw` 参数改名 `anchorX`，内部 `anchorX - 1`；设计文档 §二 / §6.1 同步 |
| I-2 | 新静态门被 `.gitignore:49` 的 `tools/` 吞掉，普通 `git add .` 带不走 | **需人工**：提交时 `git add -f tools/validate_execute_line.py`（未擅改 .gitignore 语义，理由见设计文档 §14.4） |
| I-3 | 门断言②可被 javadoc 里的 `@Pseudo` / `require = 0` 提及单独满足 | **已修**：改对剥注释后的源码断言，并新增两条描述符锁定 |
| I-4 | 门断言④键表硬编码，`Config.java` 打错字测不到 | **已修**：改为从 `Config.java` 解析段名与键名 |
| S-x | 剥注释函数方向性错误 + 会吞真引用；缺 `barWidth <= 0` 早退；文档公式/配色数字/归因不一致 | **已修** |

**两处假阴性的反向对照实测**：删掉真 `@Pseudo`、改坏真 `require = 0`、给
`Config.translation(...)` 打错字 —— 三种改坏**都被门抓住**（exit 1 且命中对应断言），
还原后文件哈希不变、门复绿。

### 提交提示（本项目的提交由用户做）

工作区待提交：2 个新 Java 文件、1 个新门脚本（**必须 `git add -f`**）、2 个计划文档，
外加 `Config.java` / `beloong.mixins.json` / 中英语言文件的增量。

---

## 第二轮：实机验收失败 → 根因 → 修复（2026-10-11）

**Task 6 的实机验收结果：完全没有任何渲染**（两处血条都没有、成长 150/300 都一样），
但斩杀效果本身照常触发。⇒ 第一轮的静态门全绿**并不能**证明功能可用 —— 这正是计划里
「实机结果出来前不声称功能完成」那条警告所预言的。

**根因**：第一版把「客户端能免费读到生物的 amplifier」当成事实，而它是**错的**。
原版**不把生物的 `MobEffectInstance` 同步给旁观者**（只同步粒子外观），
所以客户端 `getEffect(...)` 恒为 `null`，`draw` 每次都在第二个 `return false` 退出。
设计文档 §三 原文与 `ExecuteThresholdEffect` 的类注释都写着这个错误前提，**均已更正**。

**修复**（用户裁定方案 A）：由服务端补发一条**最小**自定义包（`entityId + amplifier`，
比原版那个包还小），客户端缓存后读取。新增 4 个文件、改 4 个文件，详见设计文档 §十五。

| 项 | 结果 |
|---|---|
| `compileJava` | BUILD SUCCESSFUL；期间修正一处 `remap` 问题（NeoForge 给原版效果生命周期方法加过参数，必须 `remap = false`） |
| 门（8 组断言） | `ALL CHECKS PASSED`；新增第 ⑤–⑧ 组，**第 ⑧ 组是本次 bug 的回归闸**（禁止 overlay 再出现 `getEffect(`） |
| 反向对照 | 6 条（退回读 effect / 原版 mixin 放回根目录 / `@At` 漏写 `remap` / 同步 mixin 未注册 / 包未注册 / 双端类引用客户端缓存）**全部被抓住**，还原后哈希不变 |
| 命名规范 | 新原版 mixin 按项目规范落在 `mixin/minecraft/`、条目写 `"minecraft.…"`、6 处 `remap = false`（用户 2026-10-11 裁定；首版不合规已迁移） |
| 回归门 | `validate_execute.py` `ALL CHECKS PASSED` |
| `build` | BUILD SUCCESSFUL，**3 个警告**（与改动前一致，无新增） |
| jar 复核 | `client` 列表含 healthbars mixin、`mixins` 列表含同步 mixin、5 个新 class 均已打包 |

**仍未验证**：修复后的**实机表现**。需复测：斩杀线是否出现、竖线是否在 5%（成长 150）/
20%（成长 300）、打半血区段是否不缩水、伤害飘字是否仍清晰、无标记的怪是否一点橙色都没有。
**在拿到实机结果前，这一功能仍不算完成。**

---

## 第三轮：Boss 挂不上标记 → 两道通用闸门（2026-10-11）

**复测结果**：斩杀线渲染**已正常** ✓（第一轮的根因修复有效）。但发现 **Boss 挂不上斩杀效果**
（原版凋灵、灾变 Boss、传奇怪物的三个 Boss），而小 Boss 与普通生物（铁傀儡/监守者）正常。

**根因**：全包只有两类闸门 —— `canBeAffected` 覆写（灾变的 `cataclysm:effective_for_bosses` 白名单）
与 `addEffect` 覆写（凋灵 `WitherBoss:529-532`、传奇怪物 `IAnimatedBoss:225-227`，都是无条件 false）。

**修复**（用户裁定「不许给每个 Boss 模组写 mixin —— 太多，覆盖不全」）：

| # | 内容 | 覆盖 |
|---|---|---|
| ① | `MobEffectEvent.Applicable` @ `EventPriority.LOWEST` → `Result.APPLY` | 一切 `canBeAffected` 实现 |
| ② | `addEffect` 被拒时回退 `forceAddEffect`，并**自补 DS applier** | 一切 `addEffect` 覆写 |
| ③ | 新增 `data/minecraft/tags/damage_type/bypasses_invulnerability.json` | 伤害上限 + 全部无敌帧 |
| ⑤ | 两道闸门都失败时打 `WARN`；走兜底成功时打 `DEBUG` | 可诊断性 |

| 项 | 结果 |
|---|---|
| 门 | `validate_execute_line.py` 新增**第 ⑨ 组**（两道杠杆 + 数据文件 + 自补 applier），`ALL CHECKS PASSED` |
| **回归门抓到旧决定** | `validate_execute.py` 的 `[5]` 原本断言「**不得**存在 `bypasses_invulnerability`」—— 这证明标签缺席是**刻意设计**（我在讨论中误称为"遗漏"，已在设计文档 §16.4 更正）。用户知情并接受全部代价后，该不变量已**翻转为相反一条**，注释里记录了谁/何时/为何，**没有静默删除** |
| 反向对照 | 5 条全部被抓住（删 ① 杠杆 / 删 ② 兜底 / 删自补 applier / 写反正常路径与兜底关系 / 拿掉数据文件），还原后哈希不变、两门复绿 |
| `build` / jar | BUILD SUCCESSFUL，**3 个警告不变**；数据文件已打包 |

**未做（第 4 项）**：Health Bars 对**非原版多部件实体**的拾取盲区（`Cm_Part_Entity` 不是
`LivingEntity`，而 `PickEntityHandler` 只解析原版 `EnderDragonPart`）—— 那会让灾变多部件 Boss
连 GUI 血条都没有。**用户将先实机确认**，故本轮未动。

---

## 第四轮：斩杀被目标挡下 → 停止每 tick 重试（2026-10-11）

**复测结果**：凋灵 / 末影龙 / 灾变焰魔 / 下界合金巨兽**都能斩且能渲染线** ✓。两个例外：
**Mowzie 钢铁守护者**（打不出伤害 + **每 tick 重试**、声音吵）与**传奇怪物 云筑魔像**（完全斩不了）。

**根因**：这两个 Boss 的 `hurt` 里写了**不看任何标签的无条件 `return false`** ——
钢铁守护者的正面/非破防窗口（`EntityWroughtnaut.hurt:194-207`，它的 `BYPASSES_INVULNERABILITY`
判断在「伤害源无实体」的 else 分支里，够不到），云筑魔像的垂直 4 格反 cheese 与 10 tick 金身
（`Cloud_GolemEntity.hurt:1181/1184`）。**伤害类型标签 / forceAddEffect / Result.APPLY 都进不到
目标 `hurt` 内部** ⇒ 无通用手段可强行造成伤害。

用户裁定：**承认部分 Boss 挡得住斩杀（e 不做）**，但必须修掉「每 tick 重试」。

| # | 内容 | 结果 |
|---|---|---|
| a | **无论伤害是否造成**都消耗标记 + 进冷却 | `ExecuteThresholdEffect#execute` |
| b | 冷却期间**不再挂线** | `ExecuteMarkHandler#onLivingDamagePost` |
| c | 忽略**自己的**斩杀伤害触发的 `LivingDamageEvent.Post`（按伤害类型） | 同上 |
| d | 区分「真斩杀」/「被挡下」：refused 提示 + `INFO` 日志，不谎报 | 中英语言 + 中英各 +1 键 |

**⚠️ 又一处决定反转**：`ExecuteCooldown` 的类注释原本写「冷却期间**仍然可以**继续给敌人挂斩杀线」，
(b) 把它反过来；注释已改写并记录谁/何时/为何（与第三轮 `bypasses_invulnerability` 同一处理方式）。

| 项 | 结果 |
|---|---|
| 门 | `validate_execute.py` 新增**第 ⑩ 组**（a/b/c/d 四条不变量），两门 `ALL CHECKS PASSED` |
| 反向对照 | 5 条全部被抓住（退回旧守卫 / 取消冷却拦截 / 取消自伤过滤 / 不区分被挡下 / 英文缺键），还原后哈希不变 |
| `build` | BUILD SUCCESSFUL，**3 个警告不变** |
| 语言键 | 中英各 **326 → 327**，集合一致 |

**仍未做**：第三轮的第 4 项（Health Bars 多部件拾取盲区）与本周期的 e（不给这两个模组写 mixin）——
均按用户裁定保留现状。

---

## 第五轮：撤回 `bypasses_invulnerability`（2026-10-11）

**用户裁定**（实测后）：
> 那个标签类似 kill 的效果，会让部分 Boss 的动画失效，所以改回去。

**为什么它「类似 kill」**：该标签不是一个「针对某 Boss 伤害上限的开关」，而是**一堆互不相关的无敌
判定共同读取的同一个标签** —— 凋灵出生无敌（`WitherBoss:483`）、灾变伤害上限
（`IABoss_monster:137-140`）、**灾变 11 处 Boss 转阶段无敌帧**、不死图腾
（`LivingEntity:1306-1308`）、`setInvulnerable`（`Entity:2683`）、`ArmorStand`/`ItemFrame`/`Ghast`/
`Warden`/创造模式玩家。列入它等于对所有这些读取方**同时**宣布「`beloong:execute` 是 `/kill` 级伤害」；
用户的「动画失效」正是其中「灾变 Boss 无敌帧」那一项的直接后果。

**而且初版设计文档早就写明**（`2026-10-09-execute-passive-design.md:192`）：
「**刻意不加** `minecraft:bypasses_invulnerability`：那是 `/kill` 级别的口子」——
**用户的实测与初版预判一字不差**，本轮撤回即回到那条原本就写明的决定。

| # | 改动 | 结果 |
|---|---|---|
| 1 | 删除 `data/minecraft/tags/damage_type/bypasses_invulnerability.json` | jar 内已消失；同目录其余 8 个标签完好 |
| 2 | `validate_execute.py [5]` 回到「**不得存在**」，注释保留**完整往返** | `ALL CHECKS PASSED` |
| 3 | `validate_execute_line.py` 新增 `[9b]` 断言「不得存在」+ 理由 | `ALL CHECKS PASSED` |
| 4 | `ExecuteThresholdEffect` 注释补上「试过、已撤回」的完整理由（避免将来有人再加） | BUILD SUCCESSFUL，3 个警告不变 |

**撤回后的行为**：靠伤害钳制 / 无敌帧过日子的 Boss 会再次挡下斩杀，但**因为第四轮的 (a) 已落地**，
被挡下时仍会**消耗标记 + 进冷却 + 显示「斩杀被挡下」+ 留日志** ⇒ 不会退化成每 tick 重试 ✓
即从「打不死但无限重试」变成「打不死，但会明确告知并被冷却拦住」。

**将来若仍想让这些 Boss 可斩**：不要再用这个全局标签，走**针对具体钳制点的 mixin**
（只对 `beloong:execute` 放行；本仓库已有先例 `AnnihilationPursuerDamageCapMixin`）。
**当前决定：不做。**
