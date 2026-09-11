# 天灾维度第二阶段：21 项白名单逐项接管表

**Date:** 2026-09-11
**Status:** **已定案 — 采用方案 B**（自制 5 个群系）
**范围:** 仅"用自制群系取代原版群系"。结构隔离不在本模组范围内，见总设计 §1.3。

> 权威设计见 [`docs/天灾维度总设计.md`](../天灾维度总设计.md) §10。
> 机制分桶的取证见
> [`docs/reviews/2026-09-11-disaster-region-tree-probe.md`](../reviews/2026-09-11-disaster-region-tree-probe.md)。

---

## 〇、定案（方案 B）

**自制 5 个 `beloong:` 群系**，覆盖 14 个原版项、385 个参数点；
**其余 7 项（426 点）交给 BWG**。最终 BWG 群系覆盖 **7208 / 7593 点（94.9%）**。

### 自制的 5 个群系

| 自制群系 | 取代 | 参数点 | 温度 |
|---|---|---|---|
| `beloong:frozen_ocean` | #1 `frozen_ocean`、#2 `deep_frozen_ocean` | 4 | `0.0` + `temperature_modifier: frozen` |
| `beloong:ocean` | #3 `cold_ocean`、#4 `deep_cold_ocean`、#5 `ocean`、#6 `deep_ocean` | 8 | `0.5` |
| `beloong:river` | #10 `river`、#11 `frozen_river` | 18 | `0.5`（不拆冻河，见 §5.3） |
| `beloong:caves` | #12 `lush_caves`、#13 `dripstone_caves`、#14 `deep_dark` | 3 | 任意 |
| `beloong:windswept` | #15 `windswept_hills`、#16 `windswept_gravelly_hills`、#17 `windswept_forest` | 352 | `0.2` |

### 交给 BWG 的 7 项

| 原版项 | 参数点 | BWG 目标 | 依据 |
|---|---|---|---|
| #7 `lukewarm_ocean` | 2 | `biomeswevegone:lush_stacks` | 同格（`OCEANS_BWG` 暖列） |
| #8 `deep_lukewarm_ocean` | 2 | `biomeswevegone:lush_stacks` | 同格 |
| #9 `warm_ocean` | 4 | `biomeswevegone:dead_sea` | 同格（`OCEANS_BWG` 热列） |
| #20 `stony_shore` | 12 | `biomeswevegone:basalt_barrera` | 岩质海岸，语义贴合 |
| #21 `windswept_savanna` | 216 | **`biomeswevegone:araucaria_savanna`** | 南洋杉稀树草原，气候数值与原版吻合（`has_precipitation: false`、`downfall: 0.0`）；且与已有的 `savanna → baobab_savanna` 区分开 |
| #18 `stony_peaks` | 80 | **`biomeswevegone:red_rock_peaks`** | **有 BWG 地表规则**（保住裸岩观感）。`crag_gardens` 虽然温度更接近、BWG 自己也用它填这一格，但**没有地表规则**，地表会掉成草/土 |
| #19 `snowy_beach` | 110 | **`biomeswevegone:dacite_shore`** | 规则产出 `WHITE_SAND` + `WHITE_DACITE`（`BWGOverworldSurfaceRules.java:177-182`），与沙质冷海岸吻合 |

> **#7–9 与今天的行为一致**：BWG 的 region 树本来就在暖/热海洋列放 `lush_stacks` / `dead_sea`，
> 只是 index 0 认领的列仍是原版 `lukewarm_ocean` / `warm_ocean`。改映射表即可**消除这种混合**。
>
> **合并的代价（已接受）**：`beloong:ocean` 覆盖 4 个原版海洋，海底材质与刷怪无法再按原版粒度区分；
> `beloong:caves` 覆盖 3 个洞穴，音乐、雾色、地表规则只能选一套；
> `beloong:river` 只做 1 个，冻河不再结冰。

### 群系标签：不挂任何原版标签（已定案）

自制群系**不加入** `is_ocean` / `is_river` / `is_beach` / `is_hill` / `is_mountain` /
`is_savanna` / `is_overworld` 等任何原版群系标签，也**不加入**任何 `has_structure/*`。
**优先保证结构隔离**（整合包的目标）。详见第 §六 节的可补偿性分析。

---

## 一、总览

21 项占 **811 个参数点**（原版参数空间共 7593 点）。按「BWG 有没有可用的东西」分成四组：

| 组 | 项数 | 参数点 | 项 | 结论 |
|---|---|---|---|---|
| **A. BWG 的数组在这些格子里本来就是 BWG 群系** | 3 | 8 | #7–9 | ✅ 直接交给 BWG |
| **B. BWG 结构上完全无覆盖** | 11 | 33 | #1–6、#10–14 | ❌ 必须自制（但可合并成 3–4 个群系） |
| **C. BWG 有群系，语义尚可，但不在该格** | 2 | 228 | #20–21 | ✅ 建议交给 BWG |
| **D. BWG 有群系，语义偏差较大** | 5 | 542 | #15–19 | ⚠️ 需你拍板 |
| **合计** | **21** | **811** | | |

> **"必须自制"与"自制几个群系"是两件事**：一个自制群系可以覆盖多个原版群系的位置。
> B 组的 11 项、33 个参数点，**只需 3–4 个自制群系**即可全部覆盖。

---

## 二、逐项表

「桶」= 该群系由哪棵树供给（决定接管时要不要新增注入路径）：
**I0** = index 0 兜底树（现有两条注入路径够用）；**RT** = BWG region 树（需要第三条注入路径）。

| # | 原版群系 | 参数点 | 桶 | 组 | BWG 候选 | 建议 |
|---|---|---|---|---|---|---|
| 1 | `frozen_ocean` | 2 | I0 | B | ❌ | 自制 |
| 2 | `deep_frozen_ocean` | 2 | I0 | B | ❌ | 自制（与 #1 合并） |
| 3 | `cold_ocean` | 2 | I0 | B | ❌ | 自制（与 #5 合并） |
| 4 | `deep_cold_ocean` | 2 | I0 | B | ❌ | 自制（与 #5 合并） |
| 5 | `ocean` | 2 | I0 | B | ❌ | 自制 |
| 6 | `deep_ocean` | 2 | I0 | B | ❌ | 自制（与 #5 合并） |
| 7 | `lukewarm_ocean` | 2 | I0 | A | ✅ `lush_stacks` | **交给 BWG** |
| 8 | `deep_lukewarm_ocean` | 2 | I0 | A | ✅ `lush_stacks` | **交给 BWG** |
| 9 | `warm_ocean` | 4 | I0 | A | ✅ `dead_sea` | **交给 BWG** |
| 10 | `river` | 8 | RT | B | ❌ | 自制 |
| 11 | `frozen_river` | 10 | RT | B | ❌ | 自制（与 #10 合并） |
| 12 | `lush_caves` | 1 | RT | B | ❌ | 自制 |
| 13 | `dripstone_caves` | 1 | RT | B | ❌ | 自制（与 #12 合并） |
| 14 | `deep_dark` | 1 | RT | B | ❌ | 自制（与 #12 合并） |
| 15 | `windswept_hills` | 114 | I0 | D | — | **自制**（并入 `beloong:windswept`） |
| 16 | `windswept_gravelly_hills` | 96 | I0 | D | — | **自制**（并入 `beloong:windswept`） |
| 17 | `windswept_forest` | 142 | I0 | D | — | **自制**（并入 `beloong:windswept`） |
| 18 | `stony_peaks` | 80 | I0 | D | — | **交给 BWG** → `red_rock_peaks` |
| 19 | `snowy_beach` | 110 | I0 | D | — | **交给 BWG** → `dacite_shore` |
| 20 | `stony_shore` | 12 | RT | C | ✅ `basalt_barrera` | **交给 BWG** |
| 21 | `windswept_savanna` | 216 | RT | C | ✅ `firecracker_chaparral` / `araucaria_savanna` | **交给 BWG** |

### 各组说明

**A 组（#7–9，8 点）—— BWG 的数组在这些格子里本来就是 BWG 群系**

`OCEANS_BWG`（`BWGBiomeSelectors.java:12-15`，2×5）列索引即温度带：

```
{frozen, cold, neutral, lukewarm, hot}
{  DP  ,  DP ,   DP   , LUSH_STACKS, DEAD_SEA}   ← 深海洋行
{  DP  ,  DP ,   DP   , LUSH_STACKS, DEAD_SEA}   ← 浅海洋行
```

`DP` = `DEFERRED_PLACEHOLDER`。所以**暖/热两列 BWG 有海洋群系，寒/冷/中性三列没有**。

> **实测补充**：取证显示 BWG 的 region 树里确实有 `lush_stacks(4)` 与 `dead_sea(4)`。
> 也就是说**今天**天灾维度的暖/热海洋已经是"部分 BWG、部分原版"的混合——
> BWG 认领的列出 `lush_stacks`/`dead_sea`，index 0 认领的列出白名单的 `lukewarm_ocean`/`warm_ocean`。
> 把 #7–9 交给 BWG 反而**消除**了这种不一致。

**B 组（#1–6、#10–14，33 点）—— BWG 结构上完全无覆盖，必须自制**

- 海洋寒/冷/中性三列（占海洋 60%）BWG 主动留空
- BWG 源码内 `river` 零命中，也不声明 `minecraft:is_river`
- BWG 不注册任何 `depth > 0` 的群系

**C 组（#20–21，228 点）—— BWG 有群系可用，语义尚可**

这两项在总设计 4.6 节被列为「刻意保守」。按"自制越少越好"重估：语义差距可接受，
而它们合计占 228 点，收进自制不划算。

**D 组（#15–19，542 点）—— BWG 有群系，但语义偏差较大，需要你拍板**

| 项 | 交给 BWG 的代价 | 自制的代价 |
|---|---|---|
| #15–17 碎裂地形 | 失去"碎裂丘陵"地貌；`shattered_glacier` 是**冰川**、`howling_peaks` 是**雪峰**，与"露石头/砂砾的破碎丘陵"观感不同 | 需自制 1 个群系 + 1 套地表规则（石头/砂砾） |
| #18 裸岩峰 | `red_rock_peaks` 是红岩峰（**有地表规则**），与"暖带裸岩峰"有颜色/温度带偏差 | 需自制 1 个群系 + 方解石地表规则 |
| #19 冰海滩 | `dacite_shore`（英安岩海岸）/ `basalt_barrera`（玄武岩荒原海岸）都**不是冰的**；冰带出现火山岩海岸观感冲突 | 需自制 1 个群系 + 沙质地表规则 |

---

## 三、方案对比（供追溯）

**定案为方案 B**（见第〇节）。另两档记录在此，供将来若要调整粒度时参考：

| 方案 | 自制群系数 | 交给 BWG | 自制群系 |
|---|---|---|---|
| A. 最小 | 3 | 18 项、770 点 | 海洋 1 + 河流 1 + 洞穴 1 |
| **B. 定案** | **5** | **7 项、426 点** | 海洋 2 + 河流 1 + 洞穴 1 + 碎裂 1 |
| C. 保观感 | 8 | 7 项、426 点 | 海洋 2 + 河流 2 + 洞穴 1 + 碎裂 1 + 裸岩峰 1 + 冰海滩 1 |

- **A** 的代价：碎裂地形会变成 BWG 的冰川/雪峰、裸岩峰会变成峭壁花园、冰海滩会变成火山岩海岸。BWG 占 99.5%。
- **C** 相比 B 多自制裸岩峰与冰海滩两项，观感更接近原版，但地表规则与地物的维护量更大。


---

## 四、若自制的复刻清单

### 4.1 所有自制群系都必须处理

| 项 | 要求 | 依据 |
|---|---|---|
| **地表规则** | **必须**注册 `beloong:` 命名空间规则，否则掉到默认草/土/石 | 原版 `surface_rule` 按具体群系 ID 分支：`SurfaceRuleData.java` 内 30 余处 `isBiome(...)` |
| **不得挂原版结构标签** | **绝不**加入任何 `has_structure/*`、`is_overworld`、`stronghold_biased_to` | 那是"复制品不继承原版标签"这一免费副产品的来源（总设计 §1.3） |
| **温度** | 决定降水形态与结冰 | `Biome.java:139-173`：`getTemperature(pos) >= 0.15F` 即不结冰 |
| **水体** | **无需任何处理**，自动有水 | `NoiseBasedChunkGenerator.java:71` |

### 4.2 按类别

**海洋（`beloong:frozen_ocean` / `beloong:ocean`）**

| 复刻项 | 内容 |
|---|---|
| 温度 | 冻海 `0.0` + `temperature_modifier: frozen`；其余 `0.5` |
| 地表规则 | 冻海需要冰分支（对应 `SurfaceRuleData.java:69`）；暖/温海海底要沙（`:289`）；冷/中性海海底默认砂砾 |
| 地物 | 海草（`seagrass_*`）、海带（`kelp_*`）；冻海另加 `iceberg_packed` / `iceberg_blue` / `blue_ice`（**数据驱动，可照抄**）与 `freeze_top_layer` |
| 标签 | `is_ocean`、`is_deep_ocean`（**这是原版结构标签，按 §4.1 不加**；但 `is_ocean` 也影响 `water_on_map_outlines`、`plays_underwater_music`、溺尸生成等——需要单独决定要不要加这些**非结构**用途的标签） |
| 刷怪 | `water_ambient`、`water_creature`、`underground_water_creature`、`monster` |
| **无法复刻** | 原版冰海的**大冰架**——硬编码在 `SurfaceSystem.java:158-160`、`:232-275`，按群系身份判定 |

**河流（`beloong:river`，可再拆冻河）**

| 复刻项 | 内容 |
|---|---|
| 温度 | `0.5`（若要冻河结冰则拆出 `0.0` 的第二项） |
| 地表规则 | 无原版专属规则 → 默认即可 |
| 地物 | `seagrass_river`；`freeze_top_layer` |
| 标签 | `is_river`（**结构用途不加**；但 `more_frequent_drowned_spawns`、`reduce_water_ambient_spawns`、`water_on_map_outlines` 是独立标签，需单独决定） |

**洞穴（`beloong:caves`）**

| 复刻项 | 内容 |
|---|---|
| 温度 | 任意（不影响任何机制） |
| 地表规则 | `dripstone_caves` 在原版有一条"强制石头"规则（`SurfaceRuleData.java:88`）；若并入同一个自制群系则**无法按原版粒度区分**——这是合并的代价 |
| 地物 | 发光地衣、洞穴藤蔓、孢子花、杜鹃树、滴水石簇、sculk 系列 |
| 音乐 | `music.overworld.{lush_caves,dripstone_caves,deep_dark}` —— 合并后只能选一个 |
| 刷怪 | `lush_caves` 有 `axolotls` 与 `water_ambient: tropical_fish`；`deep_dark` **刷怪表全空** |
| 标签 | NeoForge 的 `is_cave` / `is_underground`（**非原版结构标签，可以加**） |

**碎裂地形 / 裸岩峰 / 冰海滩（若选方案 C）**

| 复刻项 | 内容 |
|---|---|
| 地表规则 | `windswept_hills` → 石头（噪声阈值 1.0，`SurfaceRuleData.java:85`）；`windswept_gravelly_hills` → 砂砾（`:119`）；`windswept_savanna` → 石头（阈值 1.75，`:117`）；`stony_peaks` → 方解石（`:78`）；`stony_shore` → 砂砾（`:82`）；`snowy_beach` → 沙（`:74`）。**这是这些群系的身份所在，必须逐条复刻** |
| 地物 | `windswept_*` 共用 `trees_windswept_hills`；`stony_peaks` 的地物表只有 `glow_lichen`（40 项，最少）；`snowy_beach`/`stony_shore` 无树 |
| 注意 | `windswept_gravelly_hills` 与原版 `windswept_hills` 的 **JSON 地物表完全相同**——它的砂砾外观**只**来自地表规则 |

---

## 五、余下的技术性待定项

### 5.1 ~~交给 BWG 的三项目标群系~~ —— **已定案**

`windswept_savanna → araucaria_savanna`；`stony_peaks → red_rock_peaks`；
`snowy_beach → dacite_shore`。见第〇节。

### 5.2 映射表是"按群系 ID"的 1:1 映射，无法按参数点细分

`DisasterBiomeMapping.substitute(ResourceLocation)` 只接收群系 ID，因此**同一个原版群系的
所有参数点只能指向同一个目标**。这影响两项：

- `stony_peaks` 的原版参数是**整个 WARM 峰行**（5 个湿度格，共 80 点），
  而 BWG 只 deferred 了其中两格（`[3][0]` `[3][1]`），另外三格本来是
  `HOWLING_PEAKS` / `CRAG_GARDENS`。1:1 映射只能给整行选一个目标。
- 海洋的深/浅两行同理。

**若将来需要按参数点细分**，`DisasterBiomeSubstitution.filter` 里已经拿得到
`entry.getFirst()`（`Climate.ParameterPoint`），可以把映射表扩展成
"群系 ID + 参数条件"的形式。**本次不做**——先按 1:1 落地。

### 5.3 ~~`beloong:river` 要不要拆成冻河 + 河~~ —— **已定案：不拆**

不拆则 `frozen_river` 的 10 个参数点不再结冰。方案 B 定案为 5 个自制群系，不做冻河。

---

## 六、风险与注意

1. **region 树桶的 7 项（#10–14、#20–21）无论换成自制还是 BWG，都需要第三条注入路径。**
   只改 `DisasterBiomeMapping` 会得到"一半新、一半原版"的空间混合。
   建议注入点：`BWGTerraBlenderRegion.addBiomes`，`@ModifyVariable(argsOnly = true)`
   包裹传入的 `Consumer`（详见总设计 §10.6）。
2. **同一处注入顺带修掉第一阶段的 5 项泄漏**（`badlands` / `eroded_badlands` /
   `wooded_badlands` / `mushroom_fields` / `beach`）。
3. **验证必须补一条针对 region 树的观测**——总设计 §4.8 的方法只看 index 0 兜底树，
   这是 5 项泄漏长期未被发现的原因。
4. **~~双用途标签~~ —— 已定案：一个都不挂。** 见下方 §6.1 的可补偿性分析。
5. **不要在自定义群系里加入 `is_overworld`**——要塞的结构条件是
   `has_structure/stronghold → #is_overworld`。
6. **本机 `run/config/biomeswevegone/world_generation.json` 中 `eroded_borealis` 为 `true`**，
   与总设计「BWG 默认禁用」的假设不同。映射表不使用它，故不影响正确性，但该前提需另行复核。

### 6.1 不挂标签的实际代价（已定案的后果清单）

自制群系**不加入任何原版群系标签**，优先保证结构隔离。代价分两类：

**可以用群系自身数据补偿的（不受影响）：**

| 效果 | 补偿方式 |
|---|---|
| 刷怪（含溺尸更频繁、减少水生环境生物） | 直接写 `spawners` 的条目与权重 |
| 背景音乐、环境音、附加音 | `BiomeSpecialEffects.music` / `ambient_sound` / `additions_sound` |
| 雾色、水色、水雾色、天空色、草/叶色、环境粒子 | `BiomeSpecialEffects` 各字段 |

**纯标签驱动、无法用群系数据补偿的（会真实丢失）：**

| 效果 | 消费者 | 丢失后表现 |
|---|---|---|
| `water_on_map_outlines` | `MapItem.java:228` | 地图上不显示海洋/河流轮廓 |
| `plays_underwater_music` | `Minecraft.java:2593` | 在水下不放原版水下音乐 |
| `spawns_cold_variant_frogs` / `spawns_warm_variant_frogs` | `Frog.java:275/277` | 该区域生成的青蛙不切换变种 |
| `spawns_snow_foxes` / `spawns_white_rabbits` | `Fox.java:1534` / `Rabbit.java:390` | 寒冷区不生成雪狐/白兔变种 |
| `polar_bears_spawn_on_alternate_blocks` | `PolarBear.java:112` | 北极熊不在替代方块上生成 |
| `snow_golem_melts` | `SnowGolem.java:94` | 雪傀儡在 `windswept_savanna` 位置不再融化 |
| `produces_corals_from_bonemeal` | `BoneMealItem.java:114` | 对暖海用水骨粉不长珊瑚 |
| `allows_tropical_fish_spawns_at_any_height` | `TropicalFish.java:213` | 热带鱼只按默认高度规则生成 |

> 这些标签**同时**是 `has_structure/*` 的来源（如 `is_ocean` 喂
> `has_structure/shipwreck`、`ruined_portal_ocean`；`is_river` 喂 `has_structure/mineshaft`），
> 无法只取其中一半。**若将来觉得某些效果不能丢，需要回头找整合包商量由哪一侧承担。**

**第三类：原版结构标签**（`has_structure/*`）——**明确不加**，见总设计 §1.3。

### 6.2 ⚠️ 选 BWG 目标群系时，必须先核对它有没有地表规则

**这是本次选型踩到的坑，也是今后新增映射条目时的必查项。**

BWG **只给 55 个群系中的 42 个**写了地表规则（`BWGOverworldSurfaceRules.makeRules()`，
`BWGOverworldSurfaceRules.java:434-480`）。而 `NamespacedSurfaceRuleSource` 的分发逻辑是：

```java
if (biome.is(key -> this.rules.containsKey(namespace)))   // biomeswevegone: → 命中
    state = this.rules.get(namespace).tryApply(x, y, z);  // 跑 BWG 的规则序列
if (state == null)                                        // 序列里没有该群系的分支 → null
    state = this.baseRule.tryApply(x, y, z);              // 落到原版规则
```

原版规则按 `minecraft:` 群系 ID 分支，对 `biomeswevegone:` 群系**一条都不命中** →
掉到最终默认值（**草方块 / 泥土**）。

**⇒ 把一个地表本身就是其身份的群系换成"没有 BWG 地表规则"的群系，那个身份会直接丢失。**

**已核对的三个目标：**

| 目标 | 在 `makeRules()` 里？ | 结论 |
|---|---|---|
| `dacite_shore` | ✅ `:450`（规则体见 `:177-182`，产出 `WHITE_SAND` + `WHITE_DACITE`） | 可用 |
| `basalt_barrera` | ✅ `:439` | 可用 |
| `lush_stacks` / `dead_sea` | ✅ `:462` / `:451`（`dead_sea` 规则体见 `:184-188`，产出 `STONE`/`GRAVEL`） | 可用 |
| `red_rock_peaks` | ✅ `:469` | 可用 |
| `araucaria_savanna` | ❌ **没有** | 可接受——原版 `windswept_savanna` 的规则（噪声 1.75 以上露石头）影响很小 |
| ~~`crag_gardens`~~ | ❌ **没有** | **已弃用**——`stony_peaks` 的身份就是方解石/石头地表，改用无规则的群系会变成草坡 |

> 附带发现：`SHATTERED_GLACIER` 在 BWG 源码里是**被注释掉的**
> （`BWGOverworldSurfaceRules.java:473 //SHATTERED_GLACIER,`）——它同样没有地表规则。
> 这佐证了"碎裂地形必须自制"的决定。

**无法为 BWG 命名空间补规则**：`SurfaceRuleManager.addSurfaceRules` 是
`map.put(namespace, rules)`，对 `biomeswevegone` 调用会**整份覆盖 BWG 的规则集**。
所以遇到没有规则的 BWG 群系，只有两个选择：换一个有的，或改为自制。
