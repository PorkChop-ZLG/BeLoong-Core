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
