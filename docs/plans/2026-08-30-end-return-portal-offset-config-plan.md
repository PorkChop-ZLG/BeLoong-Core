# 自定义返回传送门偏移可配置化实施计划

**Goal:** 将 `CustomEndPortalAppearance` 中的硬编码偏移改为可在 `dragon_summon` 配置节中调整，默认值为当前硬编码 `-7 / -1 / -7`。
**Architecture:** 在 `Config.DragonSummon` 中新增 `offsetX/offsetY/offsetZ` 三个 `IntValue`，`CustomEndPortalAppearance` 读取这三个配置值计算结构原点。
**Approach:** 方案 A（三个整数配置项）。

---

## Task 1: 在 Config.DragonSummon 中新增偏移配置

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/Config.java`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`

**Steps:**
1. 在 `Config.DragonSummon` 类中新增字段：
   ```java
   public static ModConfigSpec.IntValue offsetX;
   public static ModConfigSpec.IntValue offsetY;
   public static ModConfigSpec.IntValue offsetZ;
   ```
2. 在 `dragon_summon` 配置节内、`summonBlock` 之后新增三个配置项：
   ```java
   DragonSummon.offsetX = SERVER_BUILDER
           .comment("Custom return portal offset X",
                   "自定义返回传送门偏移 X")
           .translation("beloong.configuration.dragonSummonOffsetX")
           .defineInRange("offsetX", -7, -1000000, 1000000);
   DragonSummon.offsetY = SERVER_BUILDER
           .comment("Custom return portal offset Y",
                   "自定义返回传送门偏移 Y")
           .translation("beloong.configuration.dragonSummonOffsetY")
           .defineInRange("offsetY", -1, -1000000, 1000000);
   DragonSummon.offsetZ = SERVER_BUILDER
           .comment("Custom return portal offset Z",
                   "自定义返回传送门偏移 Z")
           .translation("beloong.configuration.dragonSummonOffsetZ")
           .defineInRange("offsetZ", -7, -1000000, 1000000);
   ```
3. 在 `en_us.json` 和 `zh_cn.json` 中新增三个翻译 key：
   - `beloong.configuration.dragonSummonOffsetX`
   - `beloong.configuration.dragonSummonOffsetY`
   - `beloong.configuration.dragonSummonOffsetZ`

**Verification:**
- `./gradlew build` 编译通过。
- 检查生成的 `beloong-server.toml` 中 `dragon_summon` 下出现 `offsetX = -7`、`offsetY = -1`、`offsetZ = -7`。

---

## Task 2: 修改 CustomEndPortalAppearance 读取配置偏移

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/compat/betterendisland/CustomEndPortalAppearance.java`

**Steps:**
1. 增加 import：
   ```java
   import com.zonlong.beloong.Config;
   ```
2. 将：
   ```java
   BlockPos origin = portalLocation.offset(-7, -1, -7);
   ```
   改为：
   ```java
   BlockPos origin = portalLocation.offset(
           Config.DragonSummon.offsetX.get(),
           Config.DragonSummon.offsetY.get(),
           Config.DragonSummon.offsetZ.get()
   );
   ```
3. 更新类注释，说明偏移现在来自 `dragon_summon` 配置。

**Verification:**
- `./gradlew build` 编译通过。
- 游戏内修改 `dragon_summon.offsetY` 后，自定义返回传送门会按新 Y 偏移放置。

---

## Task 3: 手动运行验证

**Files:**
- Test: 无新增测试文件，使用游戏内手动验证

**Steps:**
1. `./gradlew runClient`
2. 默认配置下击杀末影龙/触发复活，确认外观位置与当前硬编码 `-7 / -1 / -7` 一致。
3. 修改 `beloong-server.toml` 中 `dragon_summon.offsetY`（例如改为 `0`），重启/重载后确认结构整体下移/上移符合预期。
4. 恢复默认值，确认无回归。

**Verification:**
- 手动观察结构位置随配置变化。
