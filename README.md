# 化龙核心（BeLoong Core）

**『化龍』整合包**的定制魔改模组：在**模组侧**实现整合包所需的维度、机制、技能与系统。

- 整合包仓库：**<https://github.com/PorkChop-ZLG/BeLoong>**
- 项目约定（给协作者与 AI 智能体）：[`AGENTS.md`](AGENTS.md)

## 项目信息

| 属性 | 值 |
|---|---|
| 模组名 | 化龙核心 |
| 模组 ID | `beloong` |
| Minecraft | 1.21.1 |
| 加载器 | NeoForge（具体版本以 `gradle.properties` 为准） |
| Java | 21 |
| 许可证 | MIT |

## 功能

> **本节只登记已有权威「总设计」文档的子系统**，每条只给一句话与文档链接 ——
> **实现细节一律以链接的文档为准**。其余功能等各自的总设计文档补齐后再登记
> （它们目前只有 `docs/plans/` 下的设计稿）。

### 天灾维度（`beloong:disaster`）

自建维度：BWG 群系替换与自制群系接管、群系剔除规则、与结构的联动。

→ [天灾维度总设计](docs/天灾维度总设计.md)

### 龙宫维度（`beloong:loong_palace`）

自建维度：进出方式与传送 API 契约、环境保护、化龙池水与传送石碑两套专属机制。

→ [龙宫维度总设计](docs/龙宫维度总设计.md)

### 龙宫天空渲染

龙宫维度的自定义天空盒（含缓存优化）、天气视觉禁用与维度特效注册。

→ [龙宫天空渲染总设计](docs/龙宫天空渲染总设计.md)

### 自制龙之生存技能

向 Dragon Survival 注册的 **5 个**自制龙技能：**龙击长空**、**龙卷风**、**龙宫传送**、**斩杀**、**灵愈**。

→ [自制龙之生存技能总设计](docs/自制龙之生存技能总设计.md)

### NPC 系统

通用 NPC 基类、地黄龙 NPC、服务端权威的数据驱动对话与路线、调试命令。

→ [NPC 系统总设计](docs/NPC系统总设计.md)

## 构建

```powershell
.\gradlew.bat build          # Windows
```

```bash
./gradlew build              # macOS / Linux
```

产物：`build/libs/beloong-<版本>.jar`。一律使用**项目自带的 Gradle Wrapper**，本机无需预装 Gradle。

## 开发环境

1. 克隆仓库
2. 用 IntelliJ IDEA 或 Eclipse **以 Gradle 项目导入**（ModDevGradle 会自动生成运行配置）
3. 直接运行 `runClient` / `runServer`（或使用 IDE 生成的运行配置）

## 项目结构（简）

```
src/main/
├── java/com/zonlong/beloong/
│   ├── BeLoongCore.java         # @Mod 入口：注册器、事件、数据重载、命令
│   ├── BeLoongCoreClient.java   # 客户端初始化
│   ├── Config.java              # CLIENT / COMMON / SERVER 三份配置
│   ├── mixin/                   # 按目标模组命名空间分包（minecraft/ dragonsurvival/ cataclysm/ …）
│   ├── client/                  # 渲染器、天空、HUD、模型与客户端专属逻辑
│   ├── compat/                  # 跨模组兼容层
│   ├── registry/                # 注册中心（物品 / 方块 / 实体 / 音效 / 粒子 / 属性 / 效果…）
│   ├── ability/                 # Dragon Survival 技能效果
│   └── …                        # 其余功能包（维度、NPC、对话、路线、财宝、结构、命令、CG…）
├── resources/
│   ├── assets/beloong/          # lang / models / textures / sounds
│   ├── data/beloong/            # 维度、伤害类型、技能定义、数据驱动内容
│   └── beloong.mixins.json
└── templates/META-INF/neoforge.mods.toml
```

> 完整的包职责表见 [`AGENTS.md`](AGENTS.md) 第一节。

## 文档

| 位置 | 内容 |
|---|---|
| `docs/*总设计.md` | 各子系统的**权威文档**（只记录现行实现的事实） |
| `docs/plans/` | 功能的设计稿 + 实施计划（成对出现） |
| `docs/reviews/` | 代码审查与取证报告 |
