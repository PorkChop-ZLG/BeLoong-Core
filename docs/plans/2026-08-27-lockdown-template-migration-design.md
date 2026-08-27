# LockDown 旧存档模板维度自动迁移设计

**日期:** 2026-08-27
**状态:** Approved
**方案:** Approach A — BeLoong Core 在服务端启动早期执行整体覆盖式维度同步

## Problem Statement

当前整合包使用 LockDown 的“固定指定维度”模式（`pin_dimensions_enabled` + `pinned_dimensions`）。该模式只在**新建存档**时通过客户端 `CreateWorldScreen` Mixin 复制模板维度数据。

对于已经存在的旧存档（例如未安装 LockDown 时创建的存档），启动时不会触发复制，导致 `beloong:loong_palace` 等固定维度没有模板数据，进入后是虚空。

目标：玩家将旧存档更新到新版本时，BeLoong Core 能自动检测存档版本，并在服务器/单人存档启动早期把模板中的固定维度整体覆盖进旧存档。

## Design

### Architecture

在 BeLoong Core 中新增服务端组件 `LockdownTemplateMigration`，监听 `ServerAboutToStartEvent`（已通过 NeoForge 源码确认该事件在 `loadLevel()` 之前触发，因此安全）。

组件仅当 LockDown 已加载时注册。LockDown 保持第三方原样，不做修改。

### Components

1. `Config.java`
   - 在 `COMMON_SPEC` 中新增 `template_update` 配置节：
     - `enabled`：总开关，默认 `true`
     - `templateVersion`：模板版本号，默认 `1`，由整合包作者手动更新
   - 使用 COMMON 配置，确保玩家在进入存档前即可修改。

2. `com.zonlong.beloong.compat.lockdown.TemplateVersionStore`
   - 负责读写存档版本文件 `<存档>/data/beloong_template_version.json`。
   - 文件不存在或损坏时视为“无版本”。

3. `com.zonlong.beloong.compat.lockdown.LockdownTemplateMigration`
   - 核心迁移逻辑。
   - 读取 LockDown 的 `Config.templateDirectory`、`Config.pinDimensionsEnabled`、`Config.pinnedDimensions`。
   - 使用 Minecraft 官方 `DimensionType.getStorageFolder` 解析维度路径。
   - 对 `minecraft:overworld` 特殊处理：只复制 `data`、`entities`、`poi`、`region` 四个子目录。

4. `BeLoongCore.java`
   - 在构造函数中检测：
     ```java
     if (ModList.get().isLoaded("lockdown")) {
         NeoForge.EVENT_BUS.register(new LockdownTemplateMigration());
     }
     ```

### Data Flow

1. `ServerAboutToStartEvent` 触发，此时世界尚未加载。
2. 获取存档目录：
   ```java
   Path saveDir = server.getWorldPath(LevelResource.ROOT);
   ```
3. 获取模板目录：
   ```java
   Path templateDir = server.getServerDirectory().resolve(Config.templateDirectory.get());
   ```
4. 检查 LockDown 是否启用 `pin_dimensions_enabled`，未启用则跳过。
5. 读取存档版本 `stored`，与 `Config.TemplateUpdate.templateVersion.get()` 比较。
6. 若一致，结束。
7. 若不一致，遍历 `pinnedDimensions`：
   - 用 `DimensionType.getStorageFolder` 解析模板源路径和存档目标路径；
   - 删除目标维度目录（overworld 只删四个子目录）；
   - 从模板复制到存档。
8. 所有维度复制成功后，写入版本文件：
   ```json
   { "version": 1 }
   ```

### Error Handling

- LockDown 未安装：不注册组件，BeLoong Core 正常加载。
- LockDown 未启用固定维度模式：跳过，不写版本文件。
- 模板目录缺失/为空：记录 warn，不写版本文件，下次启动重试。
- 版本文件缺失/损坏：视为版本不一致，触发复制。
- 某个维度在模板中不存在：记录 warn，跳过该维度；其他维度继续复制。
- 复制发生 IO 异常：记录 error，不写版本文件，下次启动重试。
- overworld 四个子目录全部缺失：视为该维度复制失败。
- 版本文件写入失败：记录 error；地图已复制但版本未更新，下次启动会再次复制（安全冗余）。

## Decisions Made

- 方案：BeLoong Core 内实现，不修改 LockDown。
- 覆盖策略：版本不一致时整体覆盖为新模板（用户已确认可接受破坏性更新）。
- 版本粒度：全局单一版本号。
- 版本号来源：BeLoong Core COMMON 配置 `templateVersion`，由整合包作者手动设置。
- 触发时机：`ServerAboutToStartEvent`，早于 `loadLevel()`。
- 配置位置：COMMON 配置，便于进入存档前修改。

## Non-Goals

- 不修改 LockDown 源码。
- 不替代 LockDown 的完整功能。
- 不做每维度独立版本号。
- 不做“仅补缺失、不覆盖”的模式。

## Next Steps

1. 在 `Config.java` 中添加 COMMON 配置节。
2. 新增 `TemplateVersionStore`。
3. 新增 `LockdownTemplateMigration`。
4. 在 `BeLoongCore.java` 中条件注册。
5. 按测试策略手动验证。
