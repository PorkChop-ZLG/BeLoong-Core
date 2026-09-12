# 天灾维度结构排除区递归（StackOverflowError）—— 取证与修复对照

**Date:** 2026-09-12
**Status:** **原因已查明；整合包侧已修复（2026-09-12 19:54:14）；本机复扫 0 环**
**关联文档：** [`../天灾传送门主线程死锁-根因与修复复盘.md`](../天灾传送门主线程死锁-根因与修复复盘.md)
**取证对象：** `D:\BeLoong\.minecraft\versions\BeLoong`（233 mod / 228 个可加载 jar；该目录**本身是 git 仓库**，分支 `dev`）

> **一句话结论**：两个天灾专用结构集**互相**写了 `placement.exclusion_zone.other_set`，
> 构成 2 环 → 结构放置无限递归 → `StackOverflowError`（实测 1024 帧后被 HotSpot 默认上限截断）
> → 区块 future 永不完成 → 主线程在阻塞 `getChunk` 上永久自旋。
> 环上的两个集合是 `beloong:disaster_set_ground` 与 `beloong:disaster_set_underground`。

> ⚠️ **本文有一处自我修正**：初版（同日）曾据"全实例扫描 0 环"断言"环只可能来自运行时"，
> **该结论是错的**——初版扫描恰好跑在修复动作**之后**，测到的是修复后的状态。
> 复盘见 §六，方法学纠正见 §七第 1 条。

---

## 一、时间线（为什么必须锁 revision）

| 时刻 | 事件 |
|---|---|
| 18:42–18:47 | 第一次卡死：18:44:51 `StackOverflowError`，18:45:22 / 18:46:03 / 18:46:44 三份相同转储 |
| 19:47–19:53 | 第二次卡死：19:51:13 `StackOverflowError`（1024 帧），19:51:34 / 19:52:15 / 19:52:56 三份相同转储 |
| **19:54:14** | **整合包侧修复**：两个文件删除 `exclusion_zone`（文件 mtime 证据） |
| 19:48–20:10 | 本机的多次扫描**跨过**了 19:54:14，最终测到的是**修复后**的图 |
| 20:16 之后 | 用实例 git（`HEAD`）取出**修复前**原文，与本机复扫结果对照，确认环 |

**修复前原文没有被丢失**：整合包侧的修复尚未 commit，因此 `HEAD` 里就是出故障的版本——
这是本次取证最关键的抓手（`git -C <实例> show HEAD:<path>`）。

---

## 二、修复对照（核心证据，取自实例 git diff）

### 2.1 `kubejs/data/beloong/worldgen/structure_set/disaster_set_ground.json`

```diff
@@ -3,11 +3,7 @@
     "type": "minecraft:random_spread",
     "spacing": 32,
     "separation": 16,
-    "salt": 20260921,
-    "exclusion_zone": {
-      "other_set": "beloong:disaster_set_underground",
-      "chunk_count": 10
-    }
+    "salt": 20260921
   },
   "structures": [
     { "structure": "dungeons_arise:abandoned_temple", "weight": 3 },
```

### 2.2 `kubejs/data/beloong/worldgen/structure_set/disaster_set_underground.json`

```diff
@@ -3,11 +3,7 @@
     "type": "minecraft:random_spread",
     "spacing": 32,
     "separation": 16,
-    "salt": 20260922,
-    "exclusion_zone": {
-      "other_set": "beloong:disaster_set_ground",
-      "chunk_count": 10
-    }
+    "salt": 20260922
   },
   "structures": [
     { "structure": "dungeons_arise:foundry", "weight": 2 },
```

| 结构集 | 修复前 `placement.exclusion_zone.other_set` | `chunk_count` | 修复后 |
|---|---|---|---|
| `beloong:disaster_set_ground` | `beloong:disaster_set_underground` | 10 | 无 `exclusion_zone` |
| `beloong:disaster_set_underground` | `beloong:disaster_set_ground` | 10 | 无 `exclusion_zone` |

> 两个 `exclusion_zone` 都写在 **`placement` 对象内部**（位置正确）→ 游戏**确实会解析并使用**它们。
> 这正是环被"武装"的前提。

---

## 三、机制链条

### 3.1 为什么直接栈溢出

1.21.1 的 `StructurePlacement.ExclusionZone` 是 `record (Holder<StructureSet> otherSet, int chunkCount)`
（已对**当前安装 jar** 用 `javap` 核验字段形状），而：

```java
// StructurePlacement.ExclusionZone
boolean isPlacementForbidden(state, x, z) {
    return state.hasStructureChunkInRange(this.otherSet, x, z, this.chunkCount);
}
// ChunkGeneratorStructureState
public boolean hasStructureChunkInRange(Holder<StructureSet> set, int x, int z, int range) {
    StructurePlacement p = set.value().placement();
    for (i = x-range .. x+range) for (j = z-range .. z+range)
        if (p.isStructureChunk(this, i, j)) return true;   // ← 又回到该 set 的互斥判定
    return false;
}
```

两个 set **互相指向**时即形成无出口的 4 帧闭环：

```
ExclusionZone.isPlacementForbidden(ground)          → hasStructureChunkInRange(underground)
   → underground.placement.isStructureChunk         → 其 applyInteractionsWithOtherStructures
      → ExclusionZone.isPlacementForbidden(underground) → hasStructureChunkInRange(ground)
         → ground.placement.isStructureChunk        → ……（无限）
```

实测帧直方图（891 帧样本）：`isPlacementForbidden` 222 次、`hasStructureChunkInRange` 222 次、
`applyInteractionsWithOtherStructures` 221 次、`isStructureChunk` 221 次 —— **纯原版 4 帧闭环**，
栈里没有任何 mod 的类。19:51 那次打印到 **1024 帧**即被 HotSpot 默认
`-XX:MaxJavaStackTraceDepth=1024` 截断，所以**入口点与集合名永远不出现在转储里**。

### 3.2 为什么"只有天灾维度炸、主世界完全正常"

`hasStructureChunkInRange` 本身不看群系，但**进入环的入口**要求该结构集出现在当前维度的
`possibleStructureSets` 里（`ChunkGeneratorStructureState` 构造时按"结构能否长在该维度群系里"过滤）。
`beloong:disaster_set_ground/underground` 装的是 DungeonsArise 的天灾专属配置（`kubejs` 里按天灾群系改写），
只在天灾维度满足条件 —— 于是环只在天灾维度被触发，主世界与其它维度完全正常。

### 3.3 为什么最终表现成"服务器主线程永久卡死"

区块生成 worker 抛 `StackOverflowError` 后，异常**从任务中逃逸**（`Util.onThreadException` 仅打印），
该 chunk future **永不完成**；此时模组侧旧实现在 `entityInside` 里同步
`targetLevel.getChunk(...)`，主线程正停在 `managedBlock` 上等这个 future → **永久自旋、无 crash report**。
（该 future 永不完成只是"生成失败"，本不该升级为服务器停摆——见关联复盘文档 §3.1/§八。）

---

## 四、复核方式（修复后，可重复执行）

```powershell
$inst = 'D:\BeLoong\.minecraft\versions\BeLoong'

# 1) 修复动作仍在工作区（未 commit）：应为这两处，且内容不含 exclusion_zone
git -C $inst status --short -- kubejs/data/beloong/worldgen/structure_set

# 2) 取出修复前原文（故障版本，仍在 HEAD）
git -C $inst --no-pager show HEAD:kubejs/data/beloong/worldgen/structure_set/disaster_set_ground.json

# 3) 当前是否还有任何天灾集声明互斥（应只剩 the_end_*/the_nether_* 四个，且都指向原版集合）
Select-String -Path "$inst\kubejs\data\beloong\worldgen\structure_set\*.json" -Pattern 'exclusion_zone'

# 4) 全实例重扫（本仓库脚本）：cycles 必须为 0
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\Minecraft\BeLoong-Core\build\probe\scan-exclusion3.ps1"
```

**本次复扫结果（修复后）**：`structure sets=323`、`tags=14`、`声明互斥=30`、`解析后边=39`、**`环=0`**，
且 `beloong:disaster_set_ground/underground` **不再声明任何互斥**。

---

## 五、当前有效互斥清单（修复后，30 项）

> 判定口径：字段名以 `exclusion_zone` 结尾且位于 `placement` 对象内（三种变体），
> `other_set` 为集合 ID 或结构集标签（标签已递归展开）。来源标注为实际生效的那份文件
> （`kubejs:` 会覆盖 mod jar 内的同名文件）。

| 结构集 | `other_set`（展开后） | 字段 | 来源 |
|---|---|---|---|
| `beloong:the_end_set` | `minecraft:end_cities` | exclusion_zone | kubejs |
| `beloong:the_end_set_air` | `minecraft:end_cities` | exclusion_zone | kubejs |
| `beloong:the_nether_set` | `minecraft:nether_complexes` | exclusion_zone | kubejs |
| `beloong:the_nether_set_small` | `minecraft:nether_complexes` | exclusion_zone | kubejs |
| `betterdeserttemples:desert_temples` | `minecraft:villages` | exclusion_zone | YUNG's Better Desert Temples |
| `betterfortresses:fortress` | `#betterfortresses:fortress_avoid` → `minecraft:nether_complexes` | **enhanced** | kubejs + YUNG's Better Nether Fortresses |
| `betterjungletemples:jungle_temples` | `#betterjungletemples:jungle_temple_avoid` → `minecraft:jungle_temples` | **enhanced** | YUNG's Better Jungle Temples |
| `block_factorys_bosses:underworld_arena` | `minecraft:nether_complexes` | exclusion_zone | kubejs |
| `cataclysm:abandoned_structures` | `#cataclysm:abandoned_structures_avoid` → `minecraft:villages`, `cataclysm:frosted_prison` | **super** | kubejs |
| `cataclysm:acropolis` | `#cataclysm:acropolis_avoid` → `minecraft:ocean_monuments`, `cataclysm:sunken_city`, `cataclysm:cursed_pyramid` | **super** | kubejs |
| `cataclysm:burning_arena` | `#cataclysm:burning_arena_avoid` → `cataclysm:soul_black_smith`, `minecraft:nether_complexes` | **super** | kubejs |
| `cataclysm:cursed_pyramid` | `#cataclysm:cursed_pyramid_avoid` → （空标签） | **super** | kubejs |
| `cataclysm:desert_structures` | `#cataclysm:desert_structures_avoid` → `minecraft:villages`, `cataclysm:cursed_pyramid` | **super** | kubejs |
| `cataclysm:frosted_prison` | `#cataclysm:frosted_prison_avoid` → （空标签） | **super** | kubejs |
| `cataclysm:soul_black_smith` | `#cataclysm:soul_black_smith_avoid` → `minecraft:nether_complexes` | **super** | kubejs |
| `cataclysm:sunken_city` | `#cataclysm:sunken_city_avoid` → `minecraft:ocean_monuments` | **super** | kubejs |
| `dragonsurvival:dragon_hunters_castle_with_all` | `minecraft:villages` | exclusion_zone | kubejs |
| `dragonsurvival:dragon_skeleton_with_all` | `#dragonsurvival:underground_avoid` → `minecraft:strongholds`, `minecraft:ancient_cities`, `minecraft:trail_ruins` | **super/enhanced** | 龙之生存 |
| `dragonsurvival:treasure_angry_with_all` | `#dragonsurvival:underground_avoid_nether` → `minecraft:nether_complexes` | 同上 | kubejs |
| `dragonsurvival:treasure_end_with_all` | `#dragonsurvival:underground_avoid_end` → `minecraft:end_cities` | 同上 | kubejs |
| `dragonsurvival:treasure_friendly_with_all` | `#dragonsurvival:underground_avoid` | 同上 | 龙之生存 |
| `dragonsurvival:treasure_hunters_with_all` | `#dragonsurvival:underground_avoid` | 同上 | 龙之生存 |
| `iceandfire:dragon_cave` | `minecraft:villages` | exclusion_zone | 冰火传说 |
| `iceandfire:dragon_roost` | `minecraft:villages` | exclusion_zone | 冰火传说 |
| `iceandfire_dreadland:church_set` | `iceandfire:dragon_roost` | exclusion_zone | 冰火传说：悚域 |
| `minecraft:pillager_outposts` | `minecraft:villages` | exclusion_zone | **原版**（`BeLoong.jar`） |
| `mowziesmobs:frostmaw_spawns` | `minecraft:villages` | exclusion_zone | kubejs |
| `mowziesmobs:monasteries` | `minecraft:pillager_outposts` | exclusion_zone | Mowzie's Mobs |
| `mowziesmobs:umvuthana_groves` | `minecraft:villages` | exclusion_zone | Mowzie's Mobs |
| `twilightforest:fallen_trunk` | `twilightforest:hollow_tree` | exclusion_zone | 暮色森林 |

### 5.1 顺带发现（与本次卡死无关，建议整合包侧顺手修）

| 项 | 说明 |
|---|---|
| **两处 `exclusion_zone` 放错层级 → 静默失效** | `cataclysm:ruined_citadel` 与 `legendary_monsters:space_station` 把 `exclusion_zone` 写在 **JSON 顶层**（`placement` 的兄弟节点）——**这是 mod 作者自己的写法**（mod jar 内即如此，kubejs 覆盖只是原样保留）。原版 `StructureSet` codec 只读 `placement.exclusion_zone`，顶层同名字段**被忽略**——即这两条互斥**从未生效**。若作者本意是要它们生效，需要移进 `placement` 内（属 mod 侧问题，非整合包配置问题）。 |
| `minecraft:end_city`（单数）疑似不存在 | 上述两处引用 `minecraft:end_city`，而原版集合是 `minecraft:end_cities`（复数）。因字段本身失效，实测日志里没有对应报错，但属于应当修正的数据不一致。 |
| kubejs 覆盖把 `structures` 置空 | `cataclysm:*` / `legendary_monsters:*` 的 kubejs 覆盖文件普遍写成 `"structures": []`；若它们确实覆盖了 mod 自带定义，这些结构集将不再生成任何结构，需确认是否符合预期。 |

---

## 六、初版取证为什么漏掉了这个环（复盘）

| # | 原因 | 具体表现 |
|---|---|---|
| 1 | **测了"移动靶"** | 扫描期间实例正被整合包侧编辑：修复动作发生在 **19:54:14**，而我的全实例扫描（先扫 228 个 jar，最后才扫 `kubejs`）恰好在其后读到这两个文件 → 读到的是**已删除 `exclusion_zone`** 的版本。另一份更早的扫描用错了字段名（`structure`，1.20 及以前的写法），也得出 0 环。 |
| 2 | **没用实例自带的 git** | `D:\BeLoong\.minecraft\versions\BeLoong` 是 git 仓库（分支 `dev`），`HEAD` 里一直是**故障版本**（修复未 commit）。只要先执行 `git status` / `git show HEAD:<path>`，就能立刻对齐时间线并拿到原文。 |
| 3 | **扫描器"同 id 最后写入覆盖"未记录 revision** | 同一个 set id 可能同时存在于 mod jar 与 kubejs；扫描器按扫描顺序取最后一份，却没有把"读到的文件 mtime/哈希"一并记下，于是无法察觉"两次扫描读到的不是同一版数据"。 |
| 4 | **数据口径未覆盖"位置写错"** | 初版按 `placement.*exclusion_zone` 取字段（与游戏一致），于是漏掉 §5.1 那种"写在顶层、对游戏无效"的声明——这条不是漏环的原因，但说明清单需要显式口径说明（本次已补：**生效口径** + 失效项单列）。 |

**方法论结论**：**不能在活着的实例上做"声明图 → 结论"的推理而不锁 revision**；
更不能用一份（可能跑偏了时间线的）数据扫描去否定实机证据。实机转储是硬证据，数据扫描是解释工具。

---

## 七、可复用经验

1. **活实例取证先锁 revision**：`git -C <实例> status/log/show`（若存在仓库）或先记录目标文件的 mtime + SHA256；
   扫描结束后**再核一次** mtime，确认期间没有被改动。
2. **递归类转储会被 `-XX:MaxJavaStackTraceDepth=1024` 截断**：入口点、集合名都不会出现；
   定位只能靠"复现 + 二分"或"数据侧对齐时间线"，不能只读转储。
3. **互斥排查要看三种字段 + 标签展开 + 字段位置**：本包里有 `exclusion_zone` /
   `super_exclusion_zone`（灾变）/ `enhanced_exclusion_zone`（YUNG's API），后两者指向**结构集标签**；
   同时还要检查"字段是否写在 `placement` 内"。
4. **jar-in-jar 必须展开**（228 个 jar 里 40 个含嵌套 jar，共 77 个），否则漏数据。
5. **"只有某个维度炸"的线索很有价值**：环的入口受该维度 `possibleStructureSets`（群系过滤）约束，
   据此可以立刻把嫌疑范围收缩到"该维度专属的结构集"。
6. **互相排除的语义要成对检查**：A 排除 B 是对的，B 排除 A 就成了死循环；
   这类"对称配置"是本次事故的直接形态。

---

## 八、对模组侧的影响与边界

- 模组侧**不改** worldgen、不改结构集、不改天灾维度定义；本次只修"主线程同步等待区块"的写法。
- 修复后的行为边界：即使将来再出现同类环，服务器**不会再卡死**——最坏退化为
  "落点区块 200 tick 内未就绪 → `[DisasterPortal:timeout]` 报错 + 玩家提示"。
- 也就是说：**模组侧把"永久卡死"降级为"可诊断的有界失败"**；worldgen 数据正确性归整合包侧。
