# Altar of Amethyst 可挖掘化 实现计划

**目标:** 使 `cataclysm:altar_of_amethyst` 可被下界合金镐挖掘，掉落自身方块，黑曜石级硬度
**架构:** 1 个 Mixin（target `Altar_Of_Amethyst_Block`）+ 1 个 loot table + 2 个标签追加
**方法:** `@ModifyArg` 修改构造参数 + `@Inject` 覆盖 `getLootTable()`，标签驱动工具判定

---

### Task 1: 创建 Loot Table

**文件:**
- Create: `src/main/resources/data/cataclysm/loot_table/blocks/altar_of_amethyst.json`

**步骤:**
1. 创建目录 `data/cataclysm/loot_table/blocks/`
2. 写入 loot table JSON: type=`minecraft:block`, 单池掉落 `cataclysm:altar_of_amethyst`, 含 `survives_explosion` 条件
3. 确认 JSON 语法正确

**验证:** `cat data/cataclysm/loot_table/blocks/altar_of_amethyst.json | python -m json.tool > /dev/null && echo "OK"`

---

### Task 2: 追加方块标签

**文件:**
- Modify: `src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json`
- Modify: `src/main/resources/data/minecraft/tags/block/needs_netherite_tool.json`

**步骤:**
1. 在 `mineable/pickaxe.json` 的 `values` 数组中追加 `"cataclysm:altar_of_amethyst"`
2. 在 `needs_netherite_tool.json` 的 `values` 数组中追加 `"cataclysm:altar_of_amethyst"`
3. 保持 `"replace": false` 不变

**验证:** `grep "cataclysm:altar_of_amethyst" src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json src/main/resources/data/minecraft/tags/block/needs_netherite_tool.json`

---

### Task 3: 创建 Mixin 类

**文件:**
- Create: `src/main/java/com/zonlong/beloong/mixin/cataclysm/AltarOfAmethystMixin.java`

**步骤:**
1. 创建 `AltarOfAmethystMixin` 类
   - Package: `com.zonlong.beloong.mixin.cataclysm`
   - `@Mixin(value = Altar_Of_Amethyst_Block.class, remap = false)`
   - `public abstract class` 继承 `BaseEntityBlock`
   - 构造函数 `protected AltarOfAmethystMixin(Properties props) { super(props); }`
2. 添加 `@ModifyArg` — 修改构造函数的 `super(properties)` 调用
   - `method = "<init>"`, target `INVOKE` of `BaseEntityBlock.<init>(BlockBehaviour$Properties)V`
   - `static` 方法，返回修改后的 Properties: `.strength(50.0F, 3600000.0F).requiresCorrectToolForDrops()`
3. 添加 `@Inject` — 覆盖 `getLootTable()`
   - `at = @At("HEAD")`, `cancellable = true`
   - 返回 `this.builtInRegistryHolder().key()`
4. 添加标准 Javadoc（中文描述 + 生效机制）
5. 所有新增方法使用 `beloong$` 前缀

**验证:** 编译通过 `./gradlew compileJava`（如 Gradle 可用）或确认 IDE 无编译错误

---

### Task 4: 注册 Mixin

**文件:**
- Modify: `src/main/resources/beloong.mixins.json`

**步骤:**
1. 在 `"mixins"` 数组中添加 `"cataclysm.AltarOfAmethystMixin"`
2. 按字母顺序插入（在 `"cataclysm.CursedPyramidStructureMixin"` 之前）

**验证:** `grep "cataclysm.AltarOfAmethystMixin" src/main/resources/beloong.mixins.json`

---

### Task 5: 终验

**步骤:**
1. 确认 5 个文件全部存在且内容正确
2. 确认 `beloong.mixins.json` 注册完整
3. Git status 检查，确保无遗漏
4. 可选：构建项目验证编译

**验证命令:**
```bash
echo "=== Files ===" && \
ls -la src/main/java/com/zonlong/beloong/mixin/cataclysm/AltarOfAmethystMixin.java && \
ls -la src/main/resources/data/cataclysm/loot_table/blocks/altar_of_amethyst.json && \
echo "=== Tags ===" && \
grep "cataclysm:altar_of_amethyst" src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json && \
grep "cataclysm:altar_of_amethyst" src/main/resources/data/minecraft/tags/block/needs_netherite_tool.json && \
echo "=== Mixin JSON ===" && \
grep "AltarOfAmethystMixin" src/main/resources/beloong.mixins.json
```
