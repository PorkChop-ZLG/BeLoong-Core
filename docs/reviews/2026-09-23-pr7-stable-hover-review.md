# PR #7 审查报告：稳定悬停水体修复 + 滑翔跟随视角

> **PR**：`PorkChop-ZLG/BeLoong-Core` #7（分支 `fix/stable-hover-follow-look`，作者 **Tangwenjun910**）
> **Head**：`20d02726d`（2026-09-14 17:25），3 个提交
> **提交**：`74f8017` Fix stable hover in water and make gliding follow look direction → `34c2300` Merge master → `20d0272` Restore LivingEntityStableHoverMixin registration
> **基线**：merge-base = `585f823`（2026-09-10）
> **审查时项目 HEAD**：`disaster2` @ `5afb2aa`（0.9.7）
> **对照 Dragon Survival**：本地源码 `d6baf03b3`（内容 = 2.0.70）、jar `curse.maven:dragons-survival-420799:8871661`（2.0.70）
> 本报告为只读审查；临时 worktree 已删除，项目工作树未改动。

---

## 结论速览

| 问题 | 回答 |
|---|---|
| **能否安全合并？** | ✅ **能**。对 `origin/master` 与 `origin/disaster2` 两个目标分支做 `git merge-tree` 预演均 **exit 0、无冲突**；在临时 worktree 里真实合并后 `gradlew build` **BUILD SUCCESSFUL**（仅 3 条既有警告，无新增） |
| **能否与最新版 DS（2.0.70）兼容？** | ⚠️ **注入层面兼容，语义层面冲突**。所有目标成员在 2.0.70 中均存在（编译通过）；但滑翔分支的"速度硬对齐视线"与 DS 2.0.68/2.0.70 刚修的两条飞行问题**方向相反**（见 P-2） |
| **能否正确实现 PR 的功能？** | ✅ **功能实现到位**，水中稳定悬停与"滑翔跟随视角"都真的做了；但有 1 项机制重复（P-1）与 1 项错误注释未修（P-5） |

**建议**：可以合并，但**不要盲合上线** —— 建议先处理 P-3/P-5（低成本），并按 P-1/P-2 做一次实机验证。

---

## 1. 变更清单

| 文件 | 变更 |
|---|---|
| `mixin/dragonsurvival/ClientFlightHandlerMixin.java` | 改 +43 / −25 |
| `mixin/dragonsurvival/LivingEntityStableHoverMixin.java` | **新增**（70 行） |
| `src/main/resources/beloong.mixins.json` | `client` 列表 +1 行 |

### 1.1 合并洁净度实证

```
$ git merge-tree --write-tree --name-only origin/master pr-7    → exit=0（无冲突）
$ git merge-tree --write-tree --name-only origin/disaster2 pr-7 → exit=0（无冲突）
```

合并结果里两处改动**都在**（互不覆盖）：

```
beloong.mixins.json:7   "dragonsurvival.ClientFlightHandlerMixin",
beloong.mixins.json:8   "dragonsurvival.LivingEntityStableHoverMixin",   ← PR 新增
beloong.mixins.json:48  "minecraft.DreadKingRitualTriggerMixin"          ← HEAD 侧较新的改动，未被 PR 回退
```

（PR 基线较旧，但只动了 `client` 列表里的一行，与 HEAD 的改动不在同一 hunk，故 ort 策略自动合并成功。）

---

## 2. 新 mixin `LivingEntityStableHoverMixin` 的技术核验

### 2.1 注入点正确 —— 已用字节码证实

```java
@Mixin(value = LivingEntity.class, remap = false)
@ModifyVariable(method = "travel", at = @At(value = "STORE", ordinal = 0), remap = false)
private double beloong$disableGravityForStableHover(double gravity)
```

`LivingEntity.travel` 在 **NeoForge 21.1.236 merged jar** 里的开头（`javap -p -c`）：

```
 7: aload_0
 8: invokevirtual #2774   // Method getGravity:()D     ← NeoForge 把硬编码 0.08 换成了 getGravity()
11: dstore_2                                            ← 第一个 double STORE，正是重力局部变量
12: ... getDeltaMovement().y <= 0 → bl
36-53: if (bl && hasEffect(SLOW_FALLING)) d = Math.min(d, 0.01)   ← 第二个 dstore_2（偏移 53）= ordinal 1
54: level().getFluidState(blockPosition())
```

⇒ `STORE ordinal = 0` + 处理器形参类型 `double` 精确命中**重力变量**，PR 的注入意图与实现一致。**语义正确**。
（另有一个很强的旁证：DS 自己的 `mixins/LivingEntityMixin.java:169` 用**逐字节相同**的注入点，且已随 DS 正常发版运行 —— 说明该点在本环境可解析。）

### 2.2 守卫条件完整

`Config.FIX_STABLE_HOVER` → `ServerFlightHandler.stableHover` → 非地面/骑乘/旁观/漂浮 → `isDragon` → 翅膀展开 + `hasFlight` → `flightLevel >= 1` → 非旋转 → **`isInWater() || isFlying()`** → 未按跳跃/下潜。
⇒ 未按依赖时**只对本地龙玩家**生效，不影响原版玩家；放在 `client` 列表也是对的（服务端不需要这段悬停平滑）。
✅ 熔岩被正确排除：`isFlying()` 对 `isInLava()` 返回 false，且 `isInWater()` 为假 ⇒ 走 `return gravity`。

---

## 3. 发现清单（按严重性编号）

### 🟠 P-1 Important — 新 mixin 与 DS 自己的 mixin **撞在同一个注入点**

```java
// DS  dragonsurvival/mixins/LivingEntityMixin.java:169
@ModifyVariable(method = "travel", at = @At(value = "STORE", ordinal = 0))
private double dragonSurvival$handleStableSwim(final double gravity) {
    if ((Object) this instanceof Player player && player.getExistingData(DSDataAttachments.SWIM)
            .map(data -> data.hasStableSwim(player.getMaxHeightFluidType())).orElse(false)) {
        return 0;
    }
    return gravity;
}
```

**目标指令完全相同**（`travel` 的 `double` STORE ordinal 0）。Mixin 对同一节点的多个 `@ModifyVariable` 会**串联**执行，前一个的返回值成为后一个的输入。

**数值结论（与顺序无关）**：DS 侧要么返回常量 `0`、要么返回输入原值；BeLoong 侧同样。因此无论谁先谁后，结果都是「**任一方要求置 0 即为 0**」，不会互相破坏。这一点是好消息。

**但仍是问题**：
1. **机制重复** —— DS 已经用 `hasStableSwim` 实现了"水中取消重力"，PR 用另一套条件（`stableHover + flightLevel>=1`）再实现一遍。两个功能语义重叠、条件不同，日后任一方改动都会影响另一方，且**执行顺序没有显式约定**（两者都没写 `priority`）。
2. DS 的 `@Inject`（`:180`）带 `@Local double gravity` 读取被改后的值。已核验**当前不会互相干扰**：熔岩路径 PR 不置 0，而 DS 只在 `if (isLavaSwimming)` 块（`:261`）里用 `gravity`；水中路径（`:205-229`）不使用 `gravity`。但这是**巧合级的安全**，不是设计保证。

**建议（三选一）**：
- 首选：直接复用 DS 的能力判定，把条件写成「有 `hasStableSwim` **或** 本模组的悬停条件」，避免两套并行；
- 或给 BeLoong 的 mixin 显式 `priority`，把顺序钉死并在注释里写清为什么；
- 或最低限度：在类的 javadoc 里显式记录"本注入点与 `dragonsurvival.mixins.json` 的 `LivingEntityMixin#travel` 同点，串联语义如下"，让后人不必重新推。

### 🟠 P-2 Important — 滑翔分支的速度硬对齐，与 DS 2.0.70 的飞行修复方向相反

```java
// ClientFlightHandlerMixin:135-143（PR 版）
if (ServerFlightHandler.isGliding(player)) {
    Vec3 look = player.getLookAngle();
    double speed = delta.length();          // ← 含 Y 分量的当前速率
    if (speed > 1.0E-5) {
        player.setDeltaMovement(look.scale(speed));   // ← 每 tick 把整条速度矢量重定向到视线
    } else { ... }
}
```

DS 在 2.0.68/2.0.70 里刚刚专门修过这一块：

| DS 提交 | 内容 |
|---|---|
| `f6507602e` | Fixed flight decelleration when switching from gliding to flying |
| `dfdbefcfc` | Fix some bugs with flight regarding **retaining momentum** after gliding |
| `40f44f2a4` / `f1e0d1bd4` | Fix **snapping** happening when transitioning between gliding and flying |

本 PR 每 tick 把速度矢量**硬对齐**视线（`look.scale(speed)`），即：
- **抹掉动量方向**：任何时刻转向都是瞬时的，没有惯性/转弯半径 —— 正是 DS 刚修掉的 "snapping"；
- **绕过 DS 的减速处理**：DS 的滑翔减速逻辑写出的 `deltaMovement` 在 TAIL 处被整体覆盖；
- **180° 反向不损失速率**：俯冲中抬头，速度 `(0,−3,0)` 会立刻变成 `(0,+3,0)`；
- **与 P-1 的零重力叠加** ⇒ 抬头即可**无重力成本地持续爬升**（速率只受 DS 阻力与钳制缓慢衰减）。

**建议**：把"对齐视线"改成带混合系数的插值（例如 `Mth.lerp(k, delta, look.scale(speed))`，`k` 取 0.05–0.2 量级），或只对齐水平分量、保留 DS 的垂直项；并在整合包里做一次手感/平衡实机验证。**这一条是"能否与最新 DS 兼容"的核心答案：不是注入不兼容，而是设计意图相互抵消。**

### 🟡 P-3 Minor — 违反项目既有约定：原版目标放进了 `mixin/dragonsurvival/`

新 mixin `@Mixin(LivingEntity.class)` 打的是**原版**类，DS 代码一行未改（只是读 DS 的状态），却放在 `mixin/dragonsurvival/`、文件名叫 `LivingEntityStableHover…`。

`memory/project-context.md` 明文规定：**mixin 按目标的命名空间分类** —— 原版目标进 `mixin/minecraft/`，第三方进 `mixin/<modid>/`，且 `beloong.mixins.json` 的条目要带同名前缀（如 `minecraft.DreadKingRitualTriggerMixin`）。项目里已有的两处历史违规被明确记为"刻意留待专门重构"。

**建议**：移到 `mixin/minecraft/`，条目录作 `minecraft.LivingEntityStableHoverMixin`。（纯粹是归类与可读性，无功能影响。）

### 🟡 P-4 Minor — `STORE ordinal = 0` 的选择偏脆，有更稳的替代

当前写法依赖「`travel` 里第一个 `double` store 恰好是重力」。这在 21.1.236 成立，但 NeoForge 过去就改过这一行（把硬编码 `0.08` 换成 `getGravity()`）；将来若在其前面插入任何 `double` 局部变量，`ordinal = 0` 就会**静默指向别的变量**（`defaultRequire` 只保证"能找到"，不保证"找对"）。

**建议**：改成对取值本身做拦截，语义更直白也更抗改动：

```java
@ModifyExpressionValue(method = "travel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getGravity()D"),
        remap = false)
private double beloong$disableGravityForStableHover(double gravity) { ... }
```

（注意：改用 `@ModifyExpressionValue` 需要 MixinExtras，NeoForge 自带；DS 自己的 `LivingEntityMixin` 已在用。）若维持 STORE 写法，至少补 `require = 1` 并在注释里写明"这是 travel 的重力局部变量"。

### 🟡 P-5 Minor — 错误注释没有被修掉

`ClientFlightHandlerMixin:150-151` 仍然写着：

```
// 非稳定悬停：追加额外重力模拟 elytra 式下落
// DS 的 stableHover=true 路径仅应用 -gravity；此处追加 -gravity
// 使总重力达到 -(gravity×2)，与 DS 原版 stableHover=false 一致
```

这与 DS 实际算式不符：`stableHover` **不改变 y 项**，它只给 `ay` 设下限（`ClientFlightHandler.java:480-482` `ay = Math.max(ay, gravity * 1.1)`），y 项 `-gravity + ay`（`:524-526`）在 `stableHover` 真/假时完全相同。该注释在上一轮审查中已指出，本 PR 未修正。

**建议**：按 2.0.70 的实际算式重写这段注释。

### 🟡 P-6 Minor — 水中分支会无条件清零垂直速度

```java
if (player.isInWater()) {
    if (flightLevel >= 1.0) {
        if (noMoveInput) { setAx(0); setAz(0); }   // ← 只清水平
        setAy(0.0);
        player.setDeltaMovement(delta.x, 0, delta.z);   // ← 无条件把 y 抹平
    }
    return;
}
```

配合 P-1 的重力清零，水中**上浮只剩按跳跃键一条途径**（按下跳跃会让 `noVerticalInput` 为假而不干预）。这大概率是"稳定悬停"的预期语义，但与"游泳转向"的注释放在一起略矛盾，建议在注释里写明"水中升降需用跳跃/下潜键"。

### ⚪ P-7 Nit — 文件结尾缺换行

diff 末尾是 `\ No newline at end of file`。项目其余文件都带末尾换行，建议补上以免 `git diff` 噪音与行尾检查告警。

---

## 4. PR 做对的地方（值得保留）

1. **根因判断准确**：`flightControl` 挂在 `ClientTickEvent.Pre`，而同 tick 稍后的 `LivingEntity.travel` 会重新施加重力。在水体/悬停场景把重力在**源头**清零，比在事件末尾反复改写速度更干净 —— 这也是 DS 自己 `handleStableSwim` 采用同一手法的原因。
2. **早退条件补全**：新增 `isPassenger()` / `Minecraft.isPaused()` / `LEVITATION` 三个守卫，都是正确的加固。
3. **水中只在无水平输入时清 `ax/az`**：修掉了旧版"游泳中转向被清零"的问题。
4. **`flightLevel < 1` 的追加重力加了 `noMoveInput && !isGliding` 限定**，比旧版更收敛。
5. **`remap = false` 写全**（`@Mixin` 与 `@ModifyVariable` 都写了），符合本项目"原版目标必须 remap=false"的硬约定。
6. **放在 `client` 列表是对的**：只影响本地玩家，服务端无需承担。

---

## 5. 合并前建议清单

| 优先级 | 动作 |
|---|---|
| 1 | **实机验证滑翔手感**：合并后开一次客户端，确认「滑翔跟随视角」是否过于刚性（P-2），必要时改成插值 |
| 2 | 决定 P-1 的处理方式（复用 DS `hasStableSwim` / 显式 `priority` / 至少写清 javadoc） |
| 3 | 把新文件移到 `mixin/minecraft/` 并把 `beloong.mixins.json` 条目改成 `minecraft.LivingEntityStableHoverMixin`（P-3） |
| 4 | 修掉 `ClientFlightHandlerMixin:150-151` 的错误注释（P-5，顺手即可） |
| 5 | 视情况把注入点从 `STORE ordinal=0` 换成 `getGravity()` 的 `@ModifyExpressionValue`（P-4） |
| 6 | 补末尾换行（P-7） |

**不建议**在未做实机验证的情况下直接把 PR 合入发布分支：它的注入是安全的、编译是通的，但 P-2 触及的是玩家能立刻感知的飞行手感与平衡，且与 DS 2.0.70 自己的飞行改动正面冲突。

---

## 附：证据来源

- **PR 分支**：本地 ref `pr-7` = `20d02726d`（`git fetch origin pull/7/head:refs/heads/pr-7`）
- **合并预演**：`git merge-tree --write-tree --name-only <target> pr-7`（git 2.55.0），目标 `origin/master` 与 `origin/disaster2`
- **真实构建**：临时 worktree `D:\Minecraft\pr7-review` 内 `git merge pr-7` → `gradlew.bat build` → `BUILD SUCCESSFUL in 13s`（3 条既有警告，无新增）；worktree 已 `git worktree remove --force` 清理
- **字节码**：`build\moddev\artifacts\neoforge-21.1.236-merged.jar` 的 `net.minecraft.world.entity.LivingEntity#travel`（`javap -p -c`，JDK 21）
- **DS 侧源码**：`开源模组参考文件\DragonSurvival\src\main\java\...\mixins\LivingEntityMixin.java:169-177`、`:180-271`；`client/handlers/ClientFlightHandler.java:480-482`、`:524-526`
- **DS 版本证据**：jar `curse.maven:dragons-survival-420799:8871661` = 2.0.70
