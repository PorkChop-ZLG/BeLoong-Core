# 化龙核心（BeLoong Core）

一个用于 BeLoong 整合包的 Minecraft NeoForge 附属模组。

## 项目信息

| 属性 | 值 |
|------|-----|
| **模组ID** | `beloong` |
| **版本** | 0.7.2 |
| **Minecraft** | 1.21.1 |
| **NeoForge** | 21.1.219 |
| **Java** | 21 |
| **许可证** | MIT |


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
