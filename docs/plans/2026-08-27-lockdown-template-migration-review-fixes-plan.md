# LockDown 模板迁移审查修复实施计划

**Goal:** 将 LockDown 旧存档模板迁移限制为只操作 `<save>/dimensions/` 下的自定义维度，并修复路径安全与部分成功问题。

**Architecture:** 直接修改 `LockdownTemplateMigration`，移除主世界处理，增加 `dimensions/` 过滤和路径安全校验。

**Approach:** 方案 A（直接修改现有类）。设计文档：`docs/plans/2026-08-27-lockdown-template-migration-review-fixes-design.md`

---

### Task 1: 修改 `LockdownTemplateMigration.java`

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/compat/lockdown/LockdownTemplateMigration.java`

**Steps:**
1. 删除 `OVERWORLD_DIRECTORIES` 常量和 `copyOverworldDirectories()` 方法。
2. 新增 `isSafeTemplatePath(Path templateDir, Path saveDir)`：
   - 规范化绝对路径；
   - `template.equals(save)` 返回 false；
   - `template.startsWith(save) || save.startsWith(template)` 返回 false；
   - 不安全时 log error 并中止迁移。
3. 新增 `isUnderDimensionsFolder(Path saveDir, ResourceKey<Level> dimensionKey)`：
   - 用 `DimensionType.getStorageFolder(dimensionKey, saveDir)` 得到目标路径；
   - 判断是否以 `<saveDir>/dimensions/` 开头。
4. 修改 `copyPinnedDimension`：
   - 目标路径不在 `dimensions/` 下 → warn + 跳过（返回 true）；
   - 在 `dimensions/` 下 → 删除目标目录后整体复制模板目录；
   - 复制失败 → 返回 false。
5. 在 `onServerAboutToStart` 中、版本检查之前调用 `isSafeTemplatePath`，不安全则直接 return。
6. 保持“不备份”的删除/复制顺序。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 2: 构建验证

**Files:**
- 无代码修改。

**Steps:**
1. 运行 `./gradlew build`。
2. 确认构建成功。

**Verification:**
- `BUILD SUCCESSFUL`。

---

### Task 3: 使用 `run/template` 手动验证

**Files:**
- 无代码修改。

**Steps:**
1. 确认 `D:\Minecraft\BeLoong-Core\run\template` 存在且包含 `dimensions/beloong/loong_palace` 等模板数据。
2. 启动服务器/单人存档，验证：

| 场景 | 预期 |
|------|------|
| 存档版本缺失/不一致 | 只复制 `dimensions/` 下的自定义维度 |
| `pinnedDimensions` 含原版维度 | 跳过并警告，不影响其他维度迁移 |
| 模板目录等于/嵌套于存档目录 | 中止迁移，不写版本号 |
| 自定义维度复制失败 | 不写版本号，下次启动重试 |
| 全部成功 | 写入版本标记 |

**Verification:**
- 手动测试清单全部通过。

---

## 提交策略

- Task 1、2 作为一个提交：
  - `fix(beloong): restrict LockDown template migration to dimensions folder`
- Task 3 为验证步骤，不单独提交。

## 非目标

- 不备份旧维度目录。
- 不处理主世界/下界/末地。
- 不修改 LockDown。
- 不引入迁移白名单配置。
