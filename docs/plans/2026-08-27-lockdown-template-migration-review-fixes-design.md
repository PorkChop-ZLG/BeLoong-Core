# LockDown 模板迁移审查修复设计

**日期:** 2026-08-27
**状态:** Approved
**方案:** 方案 A — 直接修改 `LockdownTemplateMigration`

## Problem Statement

代码审查发现原实现存在以下问题：

1. 主世界 `data` 目录被纳入覆盖范围，可能破坏存档级数据。
2. 主世界子目录“部分成功”会被误判为整体成功。
3. 未校验模板目录与存档目录的路径关系，配置错误时可能删除存档自身数据。
4. 原设计允许操作整个存档根目录，但实际整合包只使用 LockDown 的 `pinDimensionsEnabled`，只需要更新 `dimensions/` 下的自定义维度。

## Design

### Architecture

直接修改 `com.zonlong.beloong.compat.lockdown.LockdownTemplateMigration`：

- 删除主世界特殊处理逻辑。
- 只处理解析后位于 `<saveDir>/dimensions/` 下的自定义维度。
- 原版维度（overworld/nether/end）或任何不在 `dimensions/` 下的维度：跳过并记录警告，不计为失败。
- 增加模板目录与存档目录的路径关系校验，不安全时中止迁移。

### Components

#### `LockdownTemplateMigration.java`

1. 移除：
   - `OVERWORLD_DIRECTORIES`
   - `copyOverworldDirectories()`

2. 新增：
   - `isSafeTemplatePath(Path templateDir, Path saveDir)`
   - `isUnderDimensionsFolder(Path saveDir, ResourceKey<Level> dimensionKey)`

3. 修改 `copyPinnedDimension`：
   - 解析目标路径；
   - 不在 `dimensions/` 下 → warn + skip；
   - 在 `dimensions/` 下 → 删除目标目录并整体复制模板目录；
   - 复制失败 → 返回 `false`。

### Data Flow

1. `ServerAboutToStartEvent` 触发。
2. 读取配置和 LockDown 配置。
3. 计算 `saveDir` 与 `templateDir`。
4. 执行 `isSafeTemplatePath`，不安全则中止。
5. 检查版本号，一致则结束。
6. 遍历 `pinnedDimensions`：
   - 非 `dimensions/` 路径 → warn + skip；
   - 自定义维度 → delete + copy；
   - 任一失败 → 整体失败。
7. 全部非跳过维度成功 → 写入版本标记。

### Error Handling

- 模板目录与存档目录相同/嵌套 → 中止迁移，不写版本号。
- 原版维度/非 `dimensions/` 维度 → 跳过并警告，不视为失败。
- 模板中缺少自定义维度目录 → 记录 warn，视为失败，不写版本号。
- 复制异常 → 记录 error，视为失败，不写版本号。
- 不新增备份（刻意设计，避免拖慢存档加载）。

## Decisions Made

- 不备份旧维度目录。
- 只操作 `<saveDir>/dimensions/` 文件夹。
- 原版维度跳过并警告。
- 模板目录与存档目录存在嵌套/相等关系时中止迁移。
- 继续使用方案 A 直接修改现有类。

## Non-Goals

- 不修改 LockDown 源码。
- 不处理主世界/下界/末地的数据复制。
- 不增加备份机制。
- 不引入新的迁移白名单配置。

## Next Steps

1. 修改 `LockdownTemplateMigration.java`。
2. 构建验证。
3. 使用 `D:\Minecraft\BeLoong-Core\run\template` 作为模板目录进行手动验证。
