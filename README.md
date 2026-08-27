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
