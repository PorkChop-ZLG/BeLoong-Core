# LockDown 旧存档模板维度自动迁移实施计划

**Goal:** 在 BeLoong Core 中实现旧存档模板维度自动迁移：启动时检测存档版本，版本缺失或不一致时从 LockDown 模板覆盖复制固定维度，并更新版本标记。

**Architecture:** 新增 `TemplateVersionStore` 负责版本文件读写；新增 `LockdownTemplateMigration` 监听 `ServerAboutToStartEvent`，读取 LockDown 配置并执行维度复制；在 `BeLoongCore` 中条件注册。

**Approach:** 方案 A（拆分为两个类，使用 `FileUtils` 复制，使用 `DimensionType.getStorageFolder` 解析路径）。设计文档：`docs/plans/2026-08-27-lockdown-template-migration-design.md`

---

### Task 1: 在 `Config.java` 中添加 COMMON 配置节

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`

**Steps:**
1. 在 `Config` 类中新增内部类：
   ```java
   public static final class TemplateUpdate {
       private TemplateUpdate() {}

       public static ModConfigSpec.BooleanValue enabled;
       public static ModConfigSpec.IntValue templateVersion;
   }
   ```
2. 在 `COMMON_SPEC` 构建之前、`COMMON_BUILDER.build()` 之前加入：
   ```java
   COMMON_BUILDER.push("template_update");
   TemplateUpdate.enabled = COMMON_BUILDER
           .comment("Enable old-save template dimension migration",
                   "是否启用旧存档模板维度自动迁移")
           .define("enabled", true);
   TemplateUpdate.templateVersion = COMMON_BUILDER
           .comment("Template version; bump this to force old saves to be overwritten from the template",
                   "模板版本号；更新地图模板后手动 +1 可触发旧存档覆盖更新")
           .defineInRange("templateVersion", 1, 1, Integer.MAX_VALUE);
   COMMON_BUILDER.pop();
   ```
3. 保持其他配置不变。

**Verification:**
- `./gradlew compileJava` 通过。
- `build.gradle` 不新增依赖。

---

### Task 2: 新增 `TemplateVersionStore`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/compat/lockdown/TemplateVersionStore.java`

**Steps:**
1. 创建包目录 `com/zonlong/beloong/compat/lockdown`。
2. 实现：
   - `static int read(Path saveDir)`：
     - 读取 `saveDir.resolve("data/beloong_template_version.json")`；
     - 文件不存在或解析失败返回 `-1`；
     - 成功返回 JSON 中 `version` 整数值。
   - `static void write(Path saveDir, int version)`：
     - 创建 `data` 目录（如不存在）；
     - 写入 `{"version": N}`；
     - 使用 `Files.writeString` 或 Gson。
3. 文件格式：
   ```json
   { "version": 1 }
   ```
4. 所有 IO/解析异常捕获后按“无版本”或“写入失败”处理。

**Verification:**
- `./gradlew compileJava` 通过。

---

### Task 3: 新增 `LockdownTemplateMigration`

**Files:**
- Create: `src/main/java/com/zonlong/beloong/compat/lockdown/LockdownTemplateMigration.java`

**Steps:**
1. 类上标注 `@SubscribeEvent` 方法：
   ```java
   @SubscribeEvent
   public void onServerAboutToStart(ServerAboutToStartEvent event)
   ```
2. 逻辑：
   - `Config.TemplateUpdate.enabled.get()` 为 false 时直接返回。
   - 读取 LockDown 配置：
     ```java
     if (!com.xfw.lockdown.Config.pinDimensionsEnabled.get()) return;
     List<? extends String> dimensions = com.xfw.lockdown.Config.pinnedDimensions.get();
     String templateDirectory = com.xfw.lockdown.Config.templateDirectory.get();
     ```
   - 计算路径：
     ```java
     Path saveDir = event.getServer().getWorldPath(LevelResource.ROOT).normalize();
     Path templateDir = event.getServer().getServerDirectory()
             .toAbsolutePath().normalize().resolve(templateDirectory);
     ```
   - 模板目录不存在或为空时 log warn 并返回。
   - 版本检测：
     ```java
     int stored = TemplateVersionStore.read(saveDir);
     int expected = Config.TemplateUpdate.templateVersion.get();
     if (stored == expected) return;
     ```
   - 遍历 `dimensions`：
     - 用 `ResourceLocation.tryParse` 解析，非法则跳过并标记失败；
     - 用 `ResourceKey.create(Registries.DIMENSION, rl)` 构造 key；
     - 用 `DimensionType.getStorageFolder(key, templateDir)` 和 `DimensionType.getStorageFolder(key, saveDir)` 获取源/目标路径；
     - 若为 `Level.OVERWORLD`：只复制 `data`、`entities`、`poi`、`region` 四个子目录；全部缺失则标记失败；
     - 其他维度：源目录不存在则标记失败；目标存在则 `FileUtils.deleteDirectory`；然后 `FileUtils.copyDirectory`。
   - 全部成功后才调用 `TemplateVersionStore.write(saveDir, expected)`。
   - 任何失败不写版本文件，并 log error/warn。
3. 使用 `BeLoongCore.LOGGER` 输出日志。

**Verification:**
- `./gradlew compileJava` 通过。
- 类中不出现客户端 `Screen`/`Minecraft` 引用。

---

### Task 4: 在 `BeLoongCore.java` 中条件注册

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`

**Steps:**
1. 添加 import：
   ```java
   import com.zonlong.beloong.compat.lockdown.LockdownTemplateMigration;
   ```
2. 在构造函数其他事件处理器注册之后添加：
   ```java
   if (ModList.get().isLoaded("lockdown")) {
       NeoForge.EVENT_BUS.register(new LockdownTemplateMigration());
   }
   ```
3. 保持 `ModList` 已导入。

**Verification:**
- `./gradlew compileJava` 通过。
- 未安装 LockDown 时启动不加载该类。

---

### Task 5: 在 `neoforge.mods.toml` 中声明 optional 依赖（可选但推荐）

**Files:**
- Modify: `src/main/templates/META-INF/neoforge.mods.toml`

**Steps:**
1. 在现有 `[[dependencies.${mod_id}]]` 列表末尾添加：
   ```toml
   [[dependencies.${mod_id}]]
       modId="lockdown"
       type="optional"
       ordering="AFTER"
       side="BOTH"
   ```
2. 不修改 LockDown。

**Verification:**
- `./gradlew processResources` 或 `./gradlew build` 通过。

---

### Task 6: 构建与手动验证

**Files:**
- 无代码修改。

**Steps:**
1. 运行 `./gradlew build`，确认编译和资源处理通过。
2. 手动验证场景：

| 场景 | 预期 |
|------|------|
| 新存档无版本文件 | 启动后复制固定维度并生成版本文件 |
| 旧存档版本低于配置 | 启动后整体覆盖并更新版本文件 |
| 版本一致 | 不复制、不更新版本文件 |
| LockDown 未安装 | BeLoong Core 正常启动，不崩溃 |
| LockDown 未启用固定维度 | 跳过迁移 |
| 模板目录缺失/为空 | 日志 warn，不写版本文件 |
| 版本文件损坏 | 视为无版本，重新复制并重写 |
| overworld 固定维度 | 只替换 data/entities/poi/region |
| 单人与专用服务器 | 均在 `loadLevel()` 前触发迁移 |

**Verification:**
- 手动测试清单全部通过。

---

## 提交策略

- Task 1、2、3、4、5 可作为一个原子提交：
  - `feat(beloong): add LockDown old-save template dimension migration`
- Task 6 为验证步骤，不单独提交。

## 非目标

- 不修改 LockDown 源码。
- 不替代 LockDown 其他功能。
- 不做每维度独立版本号。
- 不做“仅补缺失、不覆盖”的模式。
