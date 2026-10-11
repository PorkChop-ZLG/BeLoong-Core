# Health Bars「成长被当成治疗飘字」修复实施计划

**Goal:** 让 Health Bars 不再把龙之生存成长造成的等比血量抬升显示成绿色治疗数字，且不改动 Health Bars 的任何内部状态、不影响真实伤害/治疗数字、不影响 AsteorBar 修复。

**Architecture:** 单个客户端 Mixin `mixin/healthbars/HealthTrackerHandlerMixin` 注入 `fuzs.healthbars.client.handler.HealthTrackerHandler#onEndEntityTick(Entity)`：HEAD 处用"**只在最大生命变化的那一 tick + 血量百分比不变（容差 1.0e-4）**"判定等比抬升并置一个当次标志；随后 `@Redirect` 该方法内**唯一一处** `HealthTracker#getLastHealthDelta()`（javap 实测 1 次）在该标志置位时返回 `0` ⇒ Health Bars 自己的 `healthDelta != 0` 不成立 ⇒ 不生成飘字。

**Approach:** 策略 i（brainstorming 已批准的设计 §二，设计文档 `docs/plans/2026-10-11-healthbars-growth-numbers-design.md`）。

**约定与偏离说明（重要）**：
- 本项目**不做自动化测试**（`memory/project-context.md:31`、`user-preferences.md:43`，且 PR #19 引入的 JUnit 已按 C-1 移除）⇒ 本计划**不写单测**，"测试"由 §Verification 的静态门 + 实机验收清单承担。
- 按 `user-preferences.md:20`，智能体**只 `git add` 暂存、不 commit**。
- 唯一旋钮 `BELOONG_RATIO_TOLERANCE` 放在源码常量里（不新增配置项）。

---

## Task 1：新建 Mixin 类

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/healthbars/HealthTrackerHandlerMixin.java`

**Steps:**
1. 建包目录 `com/zonlong/beloong/mixin/healthbars/`，写入类骨架：
   - 类注解：`@Pseudo` + `@Mixin(value = HealthTrackerHandler.class, remap = false)`
   - 常量：`@Unique private static final double BELOONG_RATIO_TOLERANCE = 1.0e-4;`
   - 状态：`@Unique static Map<UUID, Float> beloong$prevHealth / beloong$prevMaxHealth`（`new HashMap<>()`）、`@Unique static boolean beloong$suppressThisTick`、`@Unique static boolean beloong$anchorLogged`
2. 判定注入（HEAD）：
   ```java
   @Inject(method = "onEndEntityTick(Lnet/minecraft/world/entity/Entity;)V",
           at = @At("HEAD"), require = 0, remap = false)
   private static void beloong$evaluateRescale(Entity entity) {
       // 非客户端/非玩家/非龙 ⇒ 清条目、不抑制
       // 非有限值 或 max<=0 ⇒ 清条目、不抑制
       // prev 为空（首次观测）⇒ 不抑制
       // 否则：m != pm && |h/m - ph/pm| <= 容差 ⇒ suppress = true
       // 写回快照
   }
   ```
3. 取值注入（Redirect，target 用 `Lfuzs/healthbars/client/helper/HealthTracker;getLastHealthDelta()I`）：
   ```java
   @Redirect(method = "onEndEntityTick(Lnet/minecraft/world/entity/Entity;)V",
           at = @At(value = "INVOKE",
                    target = "Lfuzs/healthbars/client/helper/HealthTracker;getLastHealthDelta()I"),
           require = 0, remap = false)
   private static int beloong$filterDelta(HealthTracker tracker) {
       boolean suppress = beloong$suppressThisTick;
       beloong$suppressThisTick = false;                       // 当次消费即清零
       if (suppress && !beloong$anchorLogged) {
           beloong$anchorLogged = true;
           BeLoongCore.LOGGER.info("[BeLoong] healthbars: growth-rescale number suppressed (first hit this session)");
       }
       return suppress ? 0 : tracker.getLastHealthDelta();
   }
   ```
4. 类级**中文 javadoc**：症状、根因（`DSModifiers.updateGrowthModifiers:51-64` / `HealthTracker.tick:55-61` / `getLastHealthDelta:37-39` 的 `ceil`）、做法（不变量判定 + 取值处抑制 + 对称化 + 只对龙）、**已知代价与漏网**（AsteorBar 修复写回时的假治疗数字、同 tick 混合变化被吞、包序异常漏一次）、可选依赖处理（`@Pseudo` + `require = 0` + toml 已有 optional）。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat compileJava --offline --no-configuration-cache      # 期望：退出码 0
```

---

## Task 2：注册进 `beloong.mixins.json`

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`（`client` 数组）

**Steps:**
1. 在 `client` 数组末尾追加 `"healthbars.HealthTrackerHandlerMixin"`（与 `asteorbar.AsteorBarHealthFixMixin` 并列；保持现有缩进/逗号风格）。

**Verification:**
```powershell
Select-String -LiteralPath src/main/resources/beloong.mixins.json -Pattern 'healthbars\.HealthTrackerHandlerMixin'   # 期望：命中 1 行
.\gradlew.bat build --offline --no-configuration-cache                                                                # 期望：退出码 0
```

---

## Task 3：静态门与产物核对（新证据口径）

**Files:**
- 无改动（只验证）

**Steps:**
1. 强制重编译取新证据；2. javap 复核注入点唯一性；3. 核对产物。

**Verification:**
```powershell
cd D:\Minecraft\BeLoong-Core
.\gradlew.bat compileJava --rerun-tasks --no-build-cache --offline --no-configuration-cache   # 期望 0（仅既有 3 条告警）

# 注入点唯一性（期望计数 = 1）
$hb = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\curse.maven\health-bars-1367734" -Recurse -File -Filter *.jar | Select-Object -First 1
& "$env:JAVA_HOME\bin\javap.exe" -p -c -cp $hb.FullName fuzs.healthbars.client.handler.HealthTrackerHandler |
  Select-String 'getLastHealthDelta'      # 期望：1 行（偏移 34）

# 产物核对
$jar = Get-ChildItem build\libs\*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1
# 期望：jar 内含 com/zonlong/beloong/mixin/healthbars/HealthTrackerHandlerMixin.class
# 期望：jar 内 beloong.mixins.json 的 client 含该条目
```

---

## Task 4：留痕（审查文档追加一节）

**Files:**
- Modify: `docs/reviews/2026-10-11-health-display-pr19-review.md`（**追加** §十，不改历史章节）

**Steps:**
1. 追加「§十 Health Bars 侧的新修复（2026-10-11）」：说明按口径 A 采用"比例不变性判定 + 取值处抑制"替代 PR 的"采样窗口 + 覆盖私有字段"，指向设计/计划文档与 `memory/decisions-log.md`。
2. 同步 §一 的 C-5 行状态（若需追加指针；保留删除线历史）。

**Verification:**
```powershell
Select-String -LiteralPath docs/reviews/2026-10-11-health-display-pr19-review.md -Pattern '§十|比例不变性'   # 期望：命中
```

---

## Task 5：交付实机验收清单（作者执行）

**Files:**
- 无改动（交付清单）

**Steps:**
1. 把设计文档 §2.5 的 **V1–V9** 抄成可勾选清单发给作者，并注明：
   - 关系对照 **V8**：临时把 `beloong.mixins.json` 里 `"healthbars.HealthTrackerHandlerMixin"` 一行注释掉即可关闭本修复（验证 `+1` 复现）。
   - 锚点日志：首次抑制时应出现 `[BeLoong] healthbars: growth-rescale number suppressed (first hit this session)`；**没出现**说明条件从未命中（而不是"恰好没成长"）。
2. 记录作者的实机结论（通过/不通过 + 现象），若不通过则回到设计文档 §2.4 调 `BELOONG_RATIO_TOLERANCE` 或改判定口径。

**Verification:** 作者在整合包/开发环境实机确认 V1–V9；智能体把结论写回审查文档与 `memory/decisions-log.md`。

---

## 依赖顺序与总览

| 任务 | 依赖 | 产物 |
|---|---|---|
| T1 新建 Mixin | 无 | `mixin/healthbars/HealthTrackerHandlerMixin.java` |
| T2 注册 | T1 | `beloong.mixins.json`（+1 行） |
| T3 静态门 | T2 | 证据块（构建/javap/产物） |
| T4 留痕 | T3 | 审查文档 §十 |
| T5 实机验收 | T4 | 作者结论（V1–V9） |

**回滚方式**：注释掉 `beloong.mixins.json` 里那一行即完全回到现状（无状态、无其它副作用）。
**提交**：全部改动只 `git add` 暂存，提交信息建议 —— `修复Health Bars把龙成长误报为治疗飘字（比例不变性判定，不改第三方状态）`。

## 执行记录（2026-10-11）

- **T1 首轮**：`beloong$evaluateRescale` 误在 `Entity` 上调 `getHealth()`（编译报"找不到符号"）⇒ 改用模式匹配出的 `player` 后编译通过。
- **T1 二次问题（runClient 阶段暴露）**：为消除"未使用参数"把 `@Inject` 的 `CallbackInfo` 删掉 ⇒ mixin 应用失败
  （`InvalidInjectionException: Invalid descriptor`）⇒ NeoForge 判定模组加载失败、客户端无法启动
  （`crash-reports/*-client.txt` 里的 Sodium "broken state" 是连锁现象，真因在 `*-fml.txt`）。**已补回 `CallbackInfo` 并在源码里加注释防再次误删。**
- **对本文档 §Verification 的更正**：Task 3 的静态门（`build` / `compileJava --rerun-tasks` / javap / jar 核对）**无法覆盖 mixin 应用期错误**
  ⇒ **Task 5 的实机 `runClient` 是必需门**，不是可选项；改 Mixin 后必须真正启动一次客户端。
