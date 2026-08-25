# Dragon Survival 剩余问题修复实施计划

**Goal:** 修复审查报告 3.1.3 - 3.1.8 的剩余重要问题。

**Architecture:** 将 ToggleFlight 门控移到主线程 lambda；保持 AirStrike 行为并修正数据语义；修正财宝扫描边界；补充 DragonState 设计注释；为化龙池水检测增加维度早退和方块坐标缓存。

**Approach:** 按设计文档 `docs/plans/2026-08-14-dragon-survival-remaining-fixes-design.md` 执行。

---

### Task 1: 修改 `ToggleFlightMixin`

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/ToggleFlightMixin.java`

**Steps:**
1. 移除对 `handleServer` 的 `@Inject`。
2. 新增对 `lambda$handleServer$1` 的 `@Inject`。
3. 使用 `CallbackInfoReturnable<ToggleFlight.Result>`。
4. 在门控条件满足时 `cir.setReturnValue(ToggleFlight.Result.NONE)`。
5. 清理不再使用的 import（如 `PacketDistributor`、`ServerPlayer`）。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 2: 更新 `air_strike.json`

**Files:**
- Modify: `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/air_strike.json`

**Steps:**
1. 将 `targeting_mode` 从 `allies_and_self` 改为 `all_except_self`。

**Verification:**
- JSON 格式合法，`./gradlew compileJava` 通过。

---

### Task 3: 修正 `TreasureValueCalculator` 边界

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/treasure/TreasureValueCalculator.java`

**Steps:**
1. 新增 `import net.minecraft.util.Mth;`。
2. 计算 `maxX / maxY / maxZ = Mth.floor(Math.nextDown(...))`。
3. 将 `BlockPos.betweenClosed` 的 max 参数改为计算后的值。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 4: 补充 `DragonStateHandlerMixin` 设计注释

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/DragonStateHandlerMixin.java`

**Steps:**
1. 在类或方法 Javadoc 中说明“新玩家默认禁用大型龙破坏”是刻意设计。
2. 不改变任何行为。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 5: 优化 `BeloongWaterContactHandler`

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/fluid/BeloongWaterContactHandler.java`

**Steps:**
1. 新增字段 `Map<UUID, BlockPos> lastScannedPositions`。
2. 在 `onPlayerTick` 增加维度早退：
   - `BeloongWaterRegionLoader.INSTANCE.getRegions(player.level().dimension()).isEmpty()` 时直接返回。
3. 增加方块坐标变化检查：
   - 当前 `BlockPos` 与缓存相同则返回。
   - 不同则更新缓存并继续扫描。
4. 在 `onDimensionChange` / `onPlayerRespawn` / `onPlayerLogout` 中清理 `lastScannedPositions`。
5. 补充必要 import。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 6: 构建验证

**Files:**
- 无代码修改。

**Steps:**
1. 运行 `./gradlew compileJava`。
2. 确认编译通过、无新增警告。
3. 运行 `git diff --stat` 确认改动文件符合预期。

**Verification:**
- `./gradlew compileJava` 无错误、无新增警告。

---

### Task 7: 游戏内手动验证

**Files:**
- 无代码修改。

**Steps:**
1. 启动游戏客户端。
2. 验证：

| 场景 | 预期 |
|------|------|
| 飞行等级 < 0，按展翅键 | 翅膀不会展开，客户端无提示 |
| 飞行等级正常，按展翅键 | 正常展翅/收翅 |
| 龙击长空命中队友/中立生物 | 仍然会伤害（设计不变） |
| 财宝堆周围价值计算 | 功能正常，不多算边界一层 |
| 新玩家大型龙破坏 | 默认仍为禁用 |
| 龙宫化龙池水中 | 进入/离开正常触发 |
| 龙宫化龙池水中静止 | 不再每 tick 全量扫描 |
| 非龙宫维度 | 完全跳过化龙池水检测 |

**Verification:**
- 手动测试清单全部通过。

---

## 提交策略

- Task 1-5 可以作为一个原子提交：
  - `fix(dragon-survival): address remaining review issues`
- Task 6、7 为验证步骤，不单独提交。

## 非目标

- 不改变龙击长空实际伤害目标。
- 不改变大型龙破坏默认禁用行为。
- 不删除 `ProjectileDamageEffectMixin`。
- 不引入自动化测试框架。
