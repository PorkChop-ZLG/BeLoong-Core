# ClientFlightHandlerMixin 修复实施计划

**Goal:** 修复 `ClientFlightHandlerMixin` 的 4 个问题：水中/熔岩/地面/滑翔/旋转错误加重力、stableHover 行为与注释不一致、水中沉底、`@Shadow` 构建警告。

**Architecture:** 重构 `ClientFlightHandlerMixin`，使其仅在 DS `stableHover=true`、玩家无操作输入时干预；空中按飞行等级处理稳定悬停/非稳定悬停，水中在 `flightLevel>=1` 时锁定高度；新增 `ClientFlightHandlerAccessor` 替代 `@Shadow`，并在 `beloong.mixins.json` 注册。

**Approach:** 方案 A（统一重构）。设计文档：`docs/plans/2026-08-14-client-flight-handler-mixin-fix-design.md`

---

### Task 1: 新增 `ClientFlightHandlerAccessor`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerAccessor.java`

**Steps:**
1. 创建接口文件。
2. 添加 `@Mixin(value = ClientFlightHandler.class, remap = false)`。
3. 添加三个静态 `@Accessor` setter：
   - `beloong$setAx(double)`
   - `beloong$setAy(double)`
   - `beloong$setAz(double)`
4. 方法体写 `throw new AssertionError()`。

**Verification:**
- 文件存在且可通过 `./gradlew compileJava` 编译。

---

### Task 2: 注册 Accessor 到 mixins 配置

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`

**Steps:**
1. 在 `client` 数组中加入 `"dragonsurvival.ClientFlightHandlerAccessor"`。
2. 保持其他条目不变。

**Verification:**
- `./gradlew compileJava` 通过，且运行时不会因 Accessor 未注册抛 `AssertionError`。

---

### Task 3: 重构 `ClientFlightHandlerMixin`

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ClientFlightHandlerMixin.java`

**Steps:**
1. 删除三个 `@Shadow` 字段。
2. 将类注解改为 `@Mixin(value = ClientFlightHandler.class, remap = false)`。
3. 重写 `fixStableHoverDrift`：
   - 保留配置开关 `Config.FIX_STABLE_HOVER`。
   - 新增 `ServerFlightHandler.stableHover` 判断，为 false 直接返回。
   - 保留玩家/龙/翅膀/飞行能力检查。
   - 新增无 WASD、无跳跃、无潜行输入判断。
   - 水中且 `flightLevel >= 1.0`：清零 `ay` 和垂直速度，锁定当前高度。
   - 空中（`ServerFlightHandler.isFlying` 且非滑翔/旋转）：
     - `flightLevel >= 1.0` 时通过 Accessor 清零 `ax/az`，创造模式清零 `ay` 和垂直速度。
     - `flightLevel < 1.0` 时通过 `setDeltaMovement` 追加一次 `-gravity`。
   - 水中且 `flightLevel < 1.0`：不干预。
4. 更新类 Javadoc，说明当前行为“尊重 DS stableHover，不处理滑翔漂移，水中稳定悬停会锁定高度”。

**Verification:**
- `./gradlew compileJava` 通过。
- 代码中不再出现 `@Shadow`。

---

### Task 4: 构建验证

**Files:**
- 无代码修改。

**Steps:**
1. 运行 `./gradlew compileJava`。
2. 确认编译通过。
3. 确认不再出现 `Unable to locate obfuscation mapping for @Shadow field` 警告。
4. 运行 `git diff --stat` 确认只改动了预期文件。

**Verification:**
- `./gradlew compileJava` 无警告、无错误。

---

### Task 5: 游戏内手动验证

**Files:**
- 无代码修改。

**Steps:**
1. 启动游戏客户端。
2. 验证以下场景：

| 场景 | 预期 |
|------|------|
| `stableHover=true`，`flightLevel>=1`，空中无输入 | 稳定悬停，不漂移 |
| `stableHover=true`，`flightLevel>=1`，创造模式无输入 | 不会自动向上移动 |
| `stableHover=true`，`flightLevel<1`，空中无输入 | 缓慢下坠 |
| `stableHover=true`，`flightLevel<1`，进入水中 | 不再沉底，可正常浮起 |
| `stableHover=true`，`flightLevel>=1`，进入水中无输入 | 锁定当前高度，不缓慢下沉 |
| `stableHover=true`，`flightLevel<1`，滑翔/旋转 | 与修复前一致，Mixin 不干预 |
| `stableHover=false` | Mixin 完全不干预 |

**Verification:**
- 手动测试清单全部通过。

---

## 提交策略

- Task 1、2、3 可以作为一个原子提交：
  - `fix(dragon-survival): rework ClientFlightHandlerMixin hover logic and replace @Shadow with accessor`
- Task 4、5 为验证步骤，不单独提交。

## 非目标

- 不修复滑翔漂移。
- 不修改其他审查问题（`ToggleFlightMixin`、`AirStrikeEffect` 等）。
