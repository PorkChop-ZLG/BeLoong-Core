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



### 天灾维度纯 BWG 群系化 + 结构白名单

将 `beloong:disaster`（天灾）维度限制为配置命名空间内的群系（默认 BWG + RU，零原版群系），海洋/河流/洞穴由 RU 群系补足；同时只保留白名单内的结构集（默认 `beloong:disaster_set`，即灾难 Boss 竞技场），要塞/矿井/村庄等原版与标签残留结构在该维度不再生成。主世界不受影响。

实现要点：天灾维度在 TerraBlender 初始化处装回一张按命名空间/精确 ID 过滤的参数表（优先复用主世界经 Lithostitched 合并后的参数表，无需复制 BWG/RU 数据，升级自动跟随），生物群系选择退化为最近邻匹配——BWG 陆地群系与 RU 水体/洞穴群系在各自气候区间互不挤占。老区块已固化的群系/结构不回填，需新区块验证。

配置位于 `beloong-server.toml`：

```toml
[disaster_biomes]
enabled = true
allowedNamespaces = ["biomeswevegone", "regions_unexplored"]
allowedBiomes = []                        # 额外精确 ID（如没有 RU 时填原版水/洞穴群系）
structureSpacingOverride = -1             # 结构间距覆写，-1 = 保持结构集原值
structureSeparationOverride = -1          # 结构间隔覆写，-1 = 保持；必须小于 spacing
structureFrequencyOverride = -1.0         # 结构出现频率 0.0~1.0，-1 = 保持原值
```

- **结构集不裁剪(写死)**：天灾维度交由原版“结构群系标签 ∩ 维度群系集”判定——与结构罗盘等按标签定位的模组使用同一条规则，罗盘显示与实际生成完全一致。
- `allowedNamespaces`：允许进入天灾维度的群系模组命名空间。
- `allowedBiomes`：额外按精确 ID 放行的群系，与命名空间规则取并集。
- `structureSetWhitelist`：天灾维度允许生成的结构集；`beloong:disaster_set` 必须保留，否则 Boss 竞技场不会生成。
- `structureSpacingOverride` / `structureSeparationOverride` / `structureFrequencyOverride`：生成概率与密度覆写，仅对随机点状放置（`random_spread`）的结构集生效。

详细机制见 `docs/plans/2026-09-10-disaster-biomes-design.md`。


 
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
