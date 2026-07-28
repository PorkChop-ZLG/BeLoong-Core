# Ice and Fire CE 龙实体 NBT 技术文档

> **核对版本**：Ice and Fire Community Edition `018d56a78207a955827a5bf9df0775c2005cb556`，Minecraft 1.21.1，NeoForge 21.1.x。  
> **主要源码**：`DragonBaseEntity`、`IceDragonEntity`、`IafDragonColors`、`HomePosition`、`ChainData`、`MiscData`，以及 Minecraft 的 `LivingEntity`、`TamableAnimal`。  
> 本文区分“自然/仅 ID 生成的默认值”和“带自定义实体 NBT 时缺省字段的读取值”。两者不能混为一谈。

## 一、最重要的生成规则

### 1.1 实体 ID

```text
iceandfire:fire_dragon
iceandfire:ice_dragon
iceandfire:lightning_dragon
```

### 1.2 `finalizeSpawn` 是否执行

Minecraft 1.21.1 的试炼刷怪笼只在实体 NBT **恰好只有**字符串 `id` 一个键时请求调用 `finalizeSpawn`。NeoForge 21.1.219 保留这个条件：它总会发布 `FinalizeSpawnEvent`，但只有该条件为真且事件未取消时才调用实体方法。

Ice and Fire 的龙在 `finalizeSpawn` 中执行：

- 随机性别；
- 随机 `1..80` 天年龄；
- 随机选择该龙种的合法变种；
- `Hunger=50`；
- `AttackDecision=true`；
- 按年龄治疗。

因此：

| `entity` NBT | 结果 |
|---|---|
| `{id:"iceandfire:ice_dragon"}` | 执行随机初始化，适合普通随机龙 |
| `{id:"iceandfire:ice_dragon",AgeTicks:...}` | 不执行随机初始化，所有需要的字段必须自行提供 |

### 1.3 自定义龙的最小安全字段

```snbt
{id:"iceandfire:ice_dragon",AgeTicks:1800000,Gender:0b,Variant:"sapphire",Hunger:50,AgingDisabled:1b,AttackDecision:1b,Health:20.0f,active_effects:[{id:"minecraft:instant_health",amplifier:10,duration:1,show_particles:0b}]}
```

- `AgeTicks`、`Gender`、`Variant` 决定目标龙。
- `Hunger` 必须显式给出，否则自定义加载路径读成 `0`。
- `AgingDisabled:1b` 仅用于固定年龄；希望继续自然成长时改为 `0b`。
- `AttackDecision:1b` 恢复自然生成时的地面攻击决策初值。
- `Health` 在父类读取阶段受当时最大生命值限制；短暂瞬间治疗会在年龄属性刷新后补满生命。

## 二、身份、年龄与变种

### 2.1 `Gender`

| 项目 | 值 |
|---|---|
| 类型 | Boolean/Byte SNBT |
| `1b` | 雄性 |
| `0b` | 雌性 |

性别控制雄性外观层、交配条件，以及 Stage 4+ 雌性尸体是否掉蛋。源码中的尸体掉蛋和繁殖蛋颜色均从该龙种的四种颜色中随机选取，不保证与母龙 `Variant` 相同。

### 2.2 `Variant`

值是没有命名空间、没有尾随下划线的小写字符串：

| 龙种 | 合法值 |
|---|---|
| 火龙 | `red`、`green`、`bronze`、`gray` |
| 冰龙 | `blue`、`white`、`sapphire`、`silver` |
| 雷龙 | `electric`、`amethyst`、`copper`、`black` |

`white_`、`sapphire_`、`silver_` 均是错误值。`DragonColor.getById` 使用 `iceandfire:<Variant>` 查询注册表，非法值可能在渲染、刷鳞或掉落路径触发空值问题，不只是“显示错误贴图”。

### 2.3 `AgeTicks`

| 项目 | 值 |
|---|---|
| 类型 | Int |
| 换算 | `AgeInDays = clamp(AgeTicks / 24000, 0, 年龄上限)` |
| 野生上限 | 128 天 |
| 驯服上限 | 配置 `maxTamedDragonAge`，默认 128 天 |

Java 整数除法会截断小数天。阶段计算如下：

| Stage | 天数 | 最小 `AgeTicks` |
|---:|---:|---:|
| 1 | 0..24 | `0` |
| 2 | 25..49 | `600000` |
| 3 | 50..74 | `1200000` |
| 4 | 75..99 | `1800000` |
| 5 | 100+ | `2400000` |

自然成长时每 tick 将 `AgeTicks` 加 1；每满 24000 tick 刷新一次年龄属性。

### 2.4 `AgingDisabled`

- `1b`：停止每 tick 增龄，`growDragon` 也直接返回。
- `0b`：正常成长。
- `sickly_dragon_meal` 设为 true；普通 `dragon_meal` 会先清除它再增长一天。

## 三、持久化的龙专属字段

下表来自 `DragonBaseEntity.addAdditionalSaveData/readAdditionalSaveData`。表中的“自定义缺省值”指实体 NBT 已抑制 `finalizeSpawn` 且没有写该键时的实际读取结果。

| 字段 | 类型 | 自定义缺省值 | 含义 |
|---|---:|---:|---|
| `Hunger` | Int | `0` | 经 setter 限制到 `0..100` |
| `AgeTicks` | Int | `0` | 年龄 tick |
| `Gender` | Boolean | `false` | `true` 雄、`false` 雌 |
| `Variant` | String | `""` | 必须使用对应龙种的合法颜色 |
| `Sleeping` | Boolean | `false` | 龙专属睡眠状态 |
| `TamedDragon` | Boolean | `false` | IAF 对驯服位的再次持久化 |
| `FireBreathing` | Boolean | `false` | 正在吐息 |
| `AttackDecision` | Boolean | `false` | `usingGroundAttack`，不是通用“是否攻击”开关 |
| `Hovering` | Boolean | `false` | 悬停状态 |
| `Flying` | Boolean | `false` | 飞行状态 |
| `DeathStage` | Int | `0` | 尸体搜刮进度 |
| `ModelDead` | Boolean | `false` | IAF 尸体模型状态 |
| `DeadProg` | Float | `0.0f` | 尸体/自定义姿态渲染进度 |
| `Tackle` | Boolean | `false` | 空中冲撞状态 |
| `HasHomePosition` | Boolean | `false` | 是否使用家园位置 |
| `CustomPose` | String | `""` | 自定义姿态名 |
| `AgingDisabled` | Boolean | `false` | 停止成长 |
| `Command` | Int | `0` | `0` 站立、`1` 坐下、`2` 护送/跟随 |
| `Items` | List | 空 | 龙自己的 5 槽物品栏 |
| `CrystalBound` | Boolean | `false` | 是否绑定召唤水晶 |
| `BrushedTime` | Int | `0` | 已刷取鳞片次数 |

### 3.1 `Sleeping` 与 `Sitting`

二者不是同一字段：

- `Sleeping` 是龙的睡眠同步数据，`DragonBaseEntity.setInSittingPose` 被重写为修改这个字段。
- `Sitting` 来自 `TamableAnimal`，保存的是 `orderedToSit`。
- `Command=1` 在读取时调用 `setCommand`，会把 `orderedToSit` 设为 true。

为避免互相矛盾，持久坐下建议使用 `Command:1` 并同时写 `Sitting:1b`；普通战斗龙使用 `Command:0,Sitting:0b,Sleeping:0b`。

### 3.2 `CustomPose`

`setCustomPose` 每次读取都会把 `modelDeadProgress` 设置为 `20.0f`，即使字符串为空。由于 NBT 读取顺序先读 `DeadProg`，再调用 `setCustomPose`，最终 `DeadProg` 可能被覆盖为 20。旧文档称“只有非空姿态才固定动画”并不符合该方法本身的实现。

### 3.3 尸体字段

可搜刮阶段上限是：

```text
lastDeathStage = min(AgeInDays / 5, 25)
```

- 玻璃瓶取血要求 `DeathStage < lastDeathStage / 2`。
- 空手在 `DeathStage == lastDeathStage / 2 - 1` 时掉心，Stage 4+ 雌龙另掉随机同种龙蛋。
- `DeathStage >= lastDeathStage - 1` 时掉头骨并移除实体。
- 其他空手阶段掉随机骨/鳞片类物品。

不要给试炼战斗龙设置 `ModelDead:1b`。

## 四、冰龙额外字段

只有 `IceDragonEntity` 额外保存：

| 字段 | 类型 | 含义 |
|---|---:|---|
| `Swimming` | Boolean | 当前游泳状态 |
| `SwimmingTicks` | Int | 连续游泳计时 |

冰龙进入水中后会自动切换游泳。游泳超过 4000 tick，或目标与冰龙不处于相同水中状态时，成年冰龙可能跳出水面进入悬停。火龙和雷龙不读取这两个键。

## 五、驯服、主人和指令

### 5.1 正确主人字段是 `Owner`

Minecraft 1.21.1 `TamableAnimal` 保存的键名为 `Owner`，不是 `OwnerUUID`：

```snbt
Owner:[I;123,456,789,101112]
```

游戏中的 UUID 整数数组应通过实际玩家 UUID 生成，不要照抄示例数字。

### 5.2 试炼刷怪笼中生成驯服龙

IAF 的读取顺序是：

1. `TamableAnimal` 读取 `Owner`，存在合法 UUID 时设置原版驯服位。
2. `DragonBaseEntity` 随后读取 `TamedDragon`，再次覆盖驯服位。

所以稳定的有主龙必须同时包含：

```snbt
Owner:[I;...],TamedDragon:1b
```

只写 `Owner` 会被缺省的 `TamedDragon:false` 取消驯服；只写 `TamedDragon:1b` 会得到没有主人的驯服状态。试炼敌对龙应两者都不写，并显式保持 `TamedDragon:0b` 也可。

### 5.3 `Command`

| 值 | 显示文本 | 读取副作用 |
|---:|---|---|
| `0` | stand | `orderedToSit=false` |
| `1` | sit | `orderedToSit=true` |
| `2` | escort | `orderedToSit=false`，相关 AI 跟随主人 |

龙杖普通右键按 `0 -> 1 -> 2 -> 0` 循环；潜行右键设置或移除家园。

## 六、家园字段

```snbt
HasHomePosition:1b,HomeAreaX:100,HomeAreaY:64,HomeAreaZ:-20,HomeDimension:"minecraft:overworld"
```

| 字段 | 类型 | 说明 |
|---|---:|---|
| `HasHomePosition` | Boolean | 是否读取并应用家园 |
| `HomeAreaX/Y/Z` | Int | 家园坐标 |
| `HomeDimension` | String | 维度 ID；缺省时读取为当前维度 |

当前 `DragonBaseEntity` 还有一个实现限制：只有 `HasHomePosition=true` 且 X、Y、Z **三个值都不为 0** 时才构造 `HomePosition`。因此任何坐标轴恰好为 0 的家园都不会从 NBT 恢复，这是源码行为，不是 SNBT 语法限制。

## 七、龙物品栏与标准装备

### 7.1 `Items`：龙专属 5 槽容器

`Items` 通过 `ItemStack.OPTIONAL_CODEC.listOf()` 保存，列表索引与龙装备更新逻辑对应：

| 索引 | 用途 |
|---:|---|
| 0 | 旗帜/副手槽 |
| 1 | 头部龙铠 |
| 2 | 胸部龙铠 |
| 3 | 腿部龙铠 |
| 4 | 足部龙铠 |

空列表即可表示全部为空，不要求强行写五个 `{}`。

### 7.2 `HandItems`/`ArmorItems`

这些是 `Mob` 的标准装备，不是龙 GUI 的 `Items`：

- `HandItems` 顺序：主手、副手。
- `ArmorItems` 顺序：脚、腿、胸、头。
- 1.21.1 物品栈使用小写 `id`、`count`、`components`。

## 八、属性与生命值

### 8.1 IAF 动态基础属性

读取龙 NBT 的最后阶段会执行 `refreshDirtyAttributes()`：

```text
age = min(AgeInDays, 125)
最大生命 base = round(minHealth + (maxHealth - minHealth) / 125 * age)
攻击伤害 base = round(minDamage + (maxDamage - minDamage) / 125 * age)
移动速度 base = minSpeed + (maxSpeed - minSpeed) / 125 * age
护甲 base = minArmor + (maxArmor - minArmor) / 125 * AgeInDays
```

默认配置中 `maxHealth=500`、`attackDamage=17`；三种龙构造参数相同。实际值会随服务端配置变化，文档不应把某个存档导出的数值当成固定默认值。

### 8.2 为什么直接写 `attributes[].base` 无法强化龙

Minecraft 父类先读取 `attributes` 和 `Health`，随后 IAF 按 `AgeTicks` 重设最大生命、攻击、速度和护甲的 base。因此：

```snbt
attributes:[{id:"minecraft:generic.attack_damage",base:100.0d}]
```

会在同一次 NBT 加载中被 IAF 覆盖。

### 8.3 持久强化应使用 modifier

IAF 重写 base，但保留自定义永久 modifier（护甲仅专门移除 ID 为 `iceandfire:armor_modifier` 的自身铠甲 modifier）。示例：

```snbt
attributes:[{id:"minecraft:generic.max_health",base:20.0d,modifiers:[{id:"belong:trial_dragon_health",amount:500.0d,operation:"add_value"}]},{id:"minecraft:generic.attack_damage",base:1.0d,modifiers:[{id:"belong:trial_dragon_damage",amount:20.0d,operation:"add_value"}]}]
```

合法 operation 为 `add_value`、`add_multiplied_base`、`add_multiplied_total`。modifier ID 必须唯一并使用自己的命名空间。需要满血生成时，还要安排加载后的治疗；本文推荐 1 tick、隐藏粒子的瞬间治疗效果。

## 九、NeoForge attachments

实体附件位于顶层 `neoforge:attachments`。IAF 注册了：

```snbt
"neoforge:attachments":{"iceandfire:chain_data":{chainedTo:[]},"iceandfire:misc_data":{loveTicks:0,lungeTicks:0,targetedByScepters:[]}}
```

| Attachment | 字段 | Codec |
|---|---|---|
| `iceandfire:chain_data` | `chainedTo` | UUID 列表 |
| `iceandfire:misc_data` | `loveTicks`、`lungeTicks`、`targetedByScepters` | 两个 Int + UUID 列表 |

这些 Codec 的字段不是 optional；一旦手工写某个 attachment，就应写齐该对象的所有字段。`UUIDUtil.AUTHLIB_CODEC` 首选 UUID 字符串形式，例如 `chainedTo:["123e4567-e89b-12d3-a456-426614174000"]`；旧文档把多个 UUID 描述成单个 `[I;...]` 是错误的。

没有链或权杖需求时，试炼刷怪笼无需写 attachments。

## 十、并非持久化 NBT 的同步字段

以下字段存在于运行时同步数据或普通成员中，但 `DragonBaseEntity` 没有把它们按同名键保存：

| 名称 | 说明 |
|---|---|
| `ControlState` | 骑乘控制位域：上升、下降、攻击、吐息、下坐骑；不是可配置持久字段 |
| `DragonPitch` | 同步俯仰值，没有 `DragonPitch` NBT 写入 |
| `flyTicks`、`hoverTicks` 等 | 普通运行时计时器，不按同名 NBT 持久化 |

不要把同步字段列表直接当作实体 NBT 字段列表。

## 十一、试炼刷怪笼模板

### 11.1 随机自然冰龙

```snbt
spawn_potentials:[{data:{entity:{id:"iceandfire:ice_dragon"}},weight:1}]
```

会执行 `finalizeSpawn`，得到随机 1..80 天、随机性别、随机冰龙颜色、50 饥饿。

### 11.2 固定 100 天银色雄性冰龙

```snbt
spawn_potentials:[{data:{entity:{id:"iceandfire:ice_dragon",AgeTicks:2400000,Gender:1b,Variant:"silver",Hunger:50,AgingDisabled:1b,AttackDecision:1b,Health:20.0f,active_effects:[{id:"minecraft:instant_health",amplifier:10,duration:1,show_particles:0b}]}},weight:1}]
```

### 11.3 三种龙加权随机

```snbt
spawn_potentials:[{data:{entity:{id:"iceandfire:fire_dragon"}},weight:1},{data:{entity:{id:"iceandfire:ice_dragon"}},weight:1},{data:{entity:{id:"iceandfire:lightning_dragon"}},weight:1}]
```

每个条目都只有 `id`，所以被选中的龙会完成其随机初始化。

## 十二、源码依据与已修正错误

核对的 IAF 源码：

- `com.iafenvoy.iceandfire.entity.DragonBaseEntity`
- `com.iafenvoy.iceandfire.entity.IceDragonEntity`
- `com.iafenvoy.iceandfire.registry.IafDragonColors`
- `com.iafenvoy.iceandfire.entity.util.HomePosition`
- `com.iafenvoy.iceandfire.data.component.ChainData`
- `com.iafenvoy.iceandfire.data.component.MiscData`

核对的 Minecraft/NeoForge 源码：

- `net.minecraft.world.entity.LivingEntity`
- `net.minecraft.world.entity.TamableAnimal`
- `net.minecraft.world.level.block.entity.trialspawner.TrialSpawner`
- NeoForge `net.neoforged.neoforge.event.EventHooks`

本次重点修正：变种尾随下划线、`OwnerUUID` 键名、attachments UUID 表示、`CustomPose` 副作用、属性 base 覆盖、试炼刷怪笼 `finalizeSpawn` 条件、缺省 `Hunger/Variant` 风险，以及把非持久同步字段误列为 NBT 的问题。
