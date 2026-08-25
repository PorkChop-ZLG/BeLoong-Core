# 龙盔甲默认回退隐藏配置实施计划

**Goal:** 新增客户端配置，开启后隐藏龙之生存中未专门绘制贴图的盔甲，不再回退到通用默认盔甲。

**Architecture:** 新增 `DragonArmorRenderLayerMixin`，在 DS `generateArmorTextureResourceLocation` RETURN 处拦截通用默认贴图，返回不存在的贴图位置，使 `hasResource` 跳过该槽位。

**Approach:** 方案 A。设计文档：`docs/plans/2026-08-14-dragon-armor-fallback-config-design.md`

---

### Task 1: 新增客户端配置

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

**Steps:**
1. 在客户端配置区新增：
   - `HIDE_UNDEVELOPED_DRAGON_ARMOR`
   - key：`hideUndevelopedDragonArmor`
   - 默认值：`true`
2. 添加中文注释。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 2: 新增 `DragonArmorRenderLayerMixin`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/mixin/dragonsurvival/DragonArmorRenderLayerMixin.java`

**Steps:**
1. 创建 Mixin 类。
2. `@Mixin(value = DragonArmorRenderLayer.class, remap = false)`。
3. 注入 `generateArmorTextureResourceLocation` 的 RETURN。
4. 配置开启且返回值为通用默认盔甲贴图时，返回不存在的贴图位置。
5. 添加 `isGenericArmorFallback` 辅助方法。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 3: 注册 Mixin

**Files:**
- Modify: `src/main/resources/beloong.mixins.json`

**Steps:**
1. 在 `client` 列表加入 `"dragonsurvival.DragonArmorRenderLayerMixin"`。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 4: 构建验证

**Files:**
- 无代码修改。

**Steps:**
1. 运行 `./gradlew compileJava`。
2. 确认编译通过、无新增警告。
3. 运行 `git diff --stat` 确认改动文件符合预期。

**Verification:**
- `./gradlew compileJava` 无错误、无新增警告。

---

### Task 5: 游戏内手动验证

**Files:**
- 无代码修改。

**Steps:**
1. 启动游戏客户端。
2. 验证：

| 场景 | 预期 |
|------|------|
| 配置开启，装备无 DS 专用贴图的模组盔甲 | 龙模型不显示该盔甲 |
| 配置开启，装备 DS 光明/黑暗龙盔甲 | 正常显示专属外观 |
| 配置开启，装备有材料专属贴图的盔甲 | 正常显示材料外观 |
| 配置关闭，装备无 DS 专用贴图的模组盔甲 | 恢复通用默认盔甲 |
| 配置切换后重新装备 | 显示/隐藏正确刷新 |
| Curios / 非盔甲物品 | 不受影响 |

**Verification:**
- 手动测试清单全部通过。

---

## 提交策略

- Task 1-3 可以作为一个原子提交：
  - `feat(dragon-survival): add config to hide generic dragon armor fallback`
- Task 4、5 为验证步骤，不单独提交。

## 非目标

- 不修改龙之生存源码。
- 不改变物品专属/材料专属贴图显示逻辑。
- 不处理 Curios 隐藏逻辑。
- 不处理配置切换后缓存立即刷新的问题。
