# 化龙核心（BeLoong Core）

化龙核心，一款由我的世界『化龍』整合包定制，用于整合大量模组和特色玩法实现的综合魔改模组。

## 项目信息

| 属性 | 值 |
|------|-----|
| **模组名** | `化龙核心` |
| **模组ID** | `beloong` |
| **Minecraft** | 1.21.1 |
| **NeoForge** | 21.1.236 |
| **Java** | 21 |
| **许可证** | MIT |


## 功能特性

### 元素魔源

十种元素各一件「元素魔源」，当前为纯材料物品，无任何使用效果，仅提供说明文本。物品 ID 为 `beloong:<元素>_essence`，中英文名见下表：

| 元素 | 物品 ID | 中文名 | 英文名 | 贴图 |
|---|---|---|---|---|
| 金 | `beloong:metal_essence` | 金魔源 | Metal Essence | ✅ |
| 木 | `beloong:wood_essence` | 木魔源 | Wood Essence | ✅ |
| 水 | `beloong:water_essence` | 水魔源 | Water Essence | ✅ |
| 火 | `beloong:fire_essence` | 火魔源 | Fire Essence | ✅ |
| 土 | `beloong:earth_essence` | 土魔源 | Earth Essence | ✅ |
| 风 | `beloong:wind_essence` | 风魔源 | Wind Essence | ✅ |
| 雷 | `beloong:thunder_essence` | 雷魔源 | Thunder Essence | ✅ |
| 冰 | `beloong:ice_essence` | 冰魔源 | Ice Essence | ✅ |
| 光 | `beloong:light_essence` | 光魔源 | Light Essence | ✅ |
| 暗 | `beloong:dark_essence` | 暗魔源 | Dark Essence | ✅ |

上表的顺序即创造模式页签中的展示顺序（五行 → 风雷冰 → 光暗）。

十张贴图均为 16×16 静态图，与整合包内其他物品贴图规格一致。光为太阳（圆盘 + 八向光芒），暗为月亮（受光月牙 + 向外渐亮的冷紫光环，用以表现"发着黑光"）。

设计细节与取名依据见 `docs/plans/2026-09-13-element-essence-design.md`。

### 旧存档模板维度自动迁移

配合 LockDown 的“固定指定维度”模式使用。当存档内的版本标记缺失或与配置中的 `template_update.templateVersion` 不一致时，BeLoong Core 会在服务器启动早期自动用模板世界覆盖固定的维度数据（例如 `beloong:loong_palace`），让旧存档也能更新到新版地图。

配置位于 `beloong-common.toml`：

```toml
[template_update]
enabled = true
templateVersion = 1
```

- `enabled`：是否启用旧存档模板维度自动迁移。
- `templateVersion`：模板版本号；更新地图模板后手动 +1，旧存档会在下次启动时自动覆盖更新。

### 末影龙手动召唤仪式（YUNG's Better End Island 兼容）

配合 YUNG's Better End Island 使用，将末影龙首次召唤与死亡后复活改为手动仪式：

- 禁用 BEI 在中央塔周围自动生成 4 颗召唤水晶；承载水晶的基岩会被替换为强化深板岩，并加入 `minecraft:dragon_immune` 标签，防止被末影龙破坏。
- 玩家需要在原本 4 颗召唤水晶的位置放置配置的特殊方块（默认 `bosses_of_mass_destruction:levitation_block`）。
- 空位会向附近玩家持续发送提醒粒子；每放对一个方块会播放信标选择音并生成村民喜悦绿色粒子。
- 4 个位置全部放满后，播放信标激活音并生成龙息粒子，方块立即转化为末地水晶，随后自动触发 BEI 的首次召唤/复活流程。
- 由于仪式位置不再使用基岩，玩家无法再通过手动放置末地水晶来触发复活。

配置位于 `beloong-server.toml`：

```toml
[dragon_summon]
enabled = true
summonBlock = "bosses_of_mass_destruction:levitation_block"
```

- `enabled`：是否启用手动召唤仪式。
- `summonBlock`：用于召唤末影龙的特殊方块 ID。



## 构建

```bash
./gradlew build
```

编译后的JAR文件位于 `build/libs/` 目录。

## 开发环境配置

1. 克隆仓库
2. 使用 IntelliJ IDEA 或 Eclipse 打开项目
3. 运行 `./gradlew genIntellijRuns`（IntelliJ）或 `./gradlew genEclipseRuns`（Eclipse）
4. 通过生成的运行配置启动游戏

## 项目结构

```
src/main/
├── java/com/zonlong/beloong/
│   ├── BeLoongCore.java          # 主模组类
│   ├── BeLoongCoreClient.java    # 客户端初始化
│   ├── Config.java               # 配置文件
│   └── item/
│       ├── ModItems.java         # 物品注册
│       ├── ModCreativeModeTabs.java  # 创造模式标签页
│       └── effect/
│           └── EternalPorkchopEffect.java  # 永恒猪排实现
├── resources/
│   ├── assets/beloong/
│   │   ├── lang/en_us.json       # 语言文件
│   │   ├── models/item/          # 物品模型
│   │   └── textures/item/        # 物品贴图
│   ├── beloong.mixins.json       # Mixin配置
│   └── logo.png                  # 模组图标
└── templates/META-INF/
    └── neoforge.mods.toml        # 模组元数据
```
