# 龙之宫殿维度 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建纯虚空维度 `beloong:loong_palace`，主世界环境，通过命令进入。

**Architecture:** 纯数据包实现——2 个 JSON 文件，分别定义维度类型（环境规则）和维度定义（引用类型 + 虚空生成器），复用 `minecraft:the_void` 生物群系。

**Tech Stack:** Minecraft 1.21.1 数据包 JSON，NeoForge 21.1 自带数据包加载，无需 Java 代码。

---

### Task 1: 创建维度类型文件

**Files:**
- Create: `src/main/resources/data/beloong/dimension_type/loong_palace.json`

- [ ] **Step 1: 写入维度类型 JSON**

```json
{
  "ultrawarm": false,
  "natural": true,
  "coordinate_scale": 1.0,
  "has_skylight": true,
  "has_ceiling": false,
  "ambient_light": 0.0,
  "monster_spawn_light_level": 0,
  "monster_spawn_block_light_limit": 0,
  "piglin_safe": false,
  "bed_works": true,
  "respawn_anchor_works": false,
  "has_raids": true,
  "logical_height": 384,
  "min_y": -64,
  "height": 384,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "effects": "minecraft:overworld"
}
```

**说明：** 模拟主世界环境——阳光、蓝天、可睡觉、水正常。`natural` 设为 true 让游戏视其为主世界类维度。

- [ ] **Step 2: 验证文件路径**

确认目录存在：
```bash
ls -la src/main/resources/data/beloong/
```

如果 `dimension_type` 目录不存在则创建。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/data/beloong/dimension_type/loong_palace.json
git commit -m "feat: 添加 loong_palace 维度类型定义"
```

---

### Task 2: 创建维度定义文件

**Files:**
- Create: `src/main/resources/data/beloong/dimension/loong_palace.json`

- [ ] **Step 1: 写入维度定义 JSON**

```json
{
  "type": "beloong:loong_palace",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "layers": [],
      "biome": "minecraft:the_void",
      "lakes": false,
      "features": false,
      "structure_overrides": []
    }
  }
}
```

**说明：** `type` 引用 Task 1 的维度类型。`generator` 使用超平坦生成器，`layers` 空数组 = 纯虚空。禁用 features 和 lakes 防止任何意外生成。

- [ ] **Step 2: 验证文件路径**

确认目录存在：
```bash
ls -la src/main/resources/data/beloong/
```

如果 `dimension` 目录不存在则创建。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/data/beloong/dimension/loong_palace.json
git commit -m "feat: 添加 loong_palace 维度定义"
```

---

### Task 3: 游戏内验证

- [ ] **Step 1: 构建模组**

```bash
./gradlew build
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 2: 启动游戏并测试**

进入世界后执行：
```
/execute in beloong:loong_palace run tp ~ ~ ~
```

- [ ] **Step 3: 验证要点**

检查以下行为：
1. 玩家出现在虚空世界中（脚下无方块）
2. 天空为蓝色（主世界效果）
3. 可以放置和破坏方块（确认区块正常加载）
4. 可以用床睡觉
5. `/execute in minecraft:overworld run tp ~ ~ ~` 可正常返回主世界

- [ ] **Step 4: 提交（如有修正）**

```bash
git add -A
git commit -m "fix: 维度文件修正"
```
