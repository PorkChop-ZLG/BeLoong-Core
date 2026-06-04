# 灾变结构生成高度修复 — 尊重原版 start_height 配置

## 概述

灾变模组（Cataclysm）的四个自定义结构（Burning Arena、Ruined Citadel、Sunken City、Cursed Pyramid）在 Java 代码中硬编码了 Y 轴生成高度，导致数据包 `worldgen/structure` JSON 中定义的 `start_height`、`project_start_to_heightmap` 等字段完全无效。

本次修复首批覆盖 **Burning Arena** 和 **Ruined Citadel**，通过 Mixin 替换结构的 CODEC（使其正确解析 JSON 字段）并重写 `findGenerationPoint`（使用解析到的值替代硬编码 Y）。

## 根因分析

### 双层缺陷

**第一层 — CODEC 不解析 `start_height`：**

灾变结构使用 `Structure.simpleCodec(ClassName::new)` 创建 CODEC，底层仅读取 `Structure.StructureSettings` 的四个字段（biomes、spawn_overrides、step、terrain_adaptation）。`start_height` 等字段在 JSON 解析阶段即被丢弃。

对比原版 `JigsawStructure`，它显式定义 CODEC 读取 `HeightProvider startHeight`：

```java
// 原版 JigsawStructure — 正确
HeightProvider.CODEC.fieldOf("start_height").forGetter(p -> p.startHeight)

// 灾变 Burning_Arena_Structure — 问题
simpleCodec(Burning_Arena_Structure::new)  // start_height 被丢弃
```

**第二层 — Java 代码硬编码 Y：**

即使 `start_height` 能传入，`findGenerationPoint` 和 `generatePieces` 也直接无视：

| 结构 | `findGenerationPoint` | `generatePieces` |
|------|----------------------|-------------------|
| Burning Arena | `new BlockPos(_, 21, _)` | `new BlockPos(_, 21, _)` |
| Ruined Citadel | `onTopOfChunkCenter(WORLD_SURFACE)` | `new BlockPos(_, 53, _)` |

### 数据流断裂示意

```
structure JSON (KubeJS)           Java Code
┌──────────────────────┐         ┌────────────────────────┐
│ "start_height": {    │   CODEC │ findGenerationPoint()  │
│   uniform: 100~200   │ ╳ 丢弃  │   BlockPos(_, 21, _)   │
│ }                    │         │ generatePieces()       │
│ "terrain_adaptation":│  ────→  │   BlockPos(_, 21, _)   │
│   "none"             │         └────────────────────────┘
└──────────────────────┘
```

## 新建文件

```
src/main/java/com/zonlong/beloong/mixin/
├── BurningArenaStructureMixin.java    # CODEC 替换 + findGenerationPoint 重写
└── RuinedCitadelStructureMixin.java   # 同上
```

## 修改文件

- **Config.java** — 在服务端配置新增 `cataclysm_fix` 节，添加 `fixCataclysmStructureHeight` 布尔开关（默认 `true`）
- **beloong.mixins.json** — 添加两个新 Mixin 到 `mixins` 列表

## Mixin 架构

每个 Mixin 包含三层：

```
┌─────────────────────────────────────────────────┐
│ Layer 1: CODEC 替换 (@Mutable @Final + static{})│
│   ├─ @Unique 注入三个字段：                       │
│   │   beloong$startHeight (HeightProvider)       │
│   │   beloong$projectStartToHeightmap (Optional) │
│   │   beloong$liquidSettings (LiquidSettings)    │
│   └─ 重建 CODEC 使其解析 JSON 的对应字段          │
├─────────────────────────────────────────────────┤
│ Layer 2: findGenerationPoint 重写                │
│   ├─ @Inject HEAD, cancellable                  │
│   ├─ 检查配置开关 fixCataclysmStructureHeight     │
│   ├─ 用 this.beloong$startHeight.sample() 算 Y   │
│   └─ 模仿 JigsawStructure.findGenerationPoint    │
├─────────────────────────────────────────────────┤
│ Layer 3: generatePieces 绕过                     │
│   ├─ generatePieces 是 private static，无法访问   │
│   │   实例字段 beloong$startHeight               │
│   └─ 在 GenerationStub lambda 中直接调用          │
│       public static start() 方法来组装模板        │
└─────────────────────────────────────────────────┘
```

## 核心代码逻辑

### CODEC 替换（以 Burning Arena 为例）

Mixin 的 `static {}` 块中用自定义 `RecordCodecBuilder` 覆盖 `CODEC` 静态字段，额外解析三个字段。`apply` 阶段先调用原构造函数 `new Burning_Arena_Structure(settings)` 创建实例，再通过强制转换写入 `@Unique` 字段。

### findGenerationPoint（统一模式）

```java
@Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
private void onFindGenerationPoint(GenerationContext context,
        CallbackInfoReturnable<Optional<GenerationStub>> cir) {
    if (!Config.fixCataclysmStructureHeight.get()) return; // 开关关闭则透传

    ChunkPos cp = context.chunkPos();
    int y = this.beloong$startHeight.sample(
        context.random(),
        new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor())
    );
    BlockPos pos = new BlockPos(cp.getMinBlockX(), y, cp.getMinBlockZ());

    cir.setReturnValue(Optional.of(new GenerationStub(pos, builder -> {
        Rotation rot = Rotation.getRandom(context.random());
        // 直接调用 public static start() 而非 private static generatePieces()
        Burning_Arena_Structure.start(context.structureTemplateManager(), pos, rot, builder, context.random());
    })));
}
```

Ruined Citadel 的逻辑完全相同，仅 `start()` 调用目标不同。

## 配置设计

```toml
# 服务端配置 (beloong-server.toml)
[cataclysm_fix]
fixCataclysmStructureHeight = true
```

- 类型：`BooleanValue`
- 默认值：`true`
- 位置：服务端配置（`SERVER_BUILDER`），因为结构生成发生在服务端
- 关闭时 Mixin 注入点不做任何操作，原方法完整执行

## 测试验证

1. 使用 KubeJS 覆盖 `burning_arena.json` 和 `ruined_citadel.json`，设置 `start_height` 为 `uniform(100, 200)` absolute
2. 创建新世界，使用 `/locate structure cataclysm:burning_arena` 定位结构
3. 传送后确认结构在 Y≈100~200 范围内生成（而非 Y=21 或 Y=53）
4. 关闭配置开关后重启，确认结构回到原始硬编码 Y 值
5. JSON 设置 `project_start_to_heightmap: "WORLD_SURFACE_WG"` 时确认结构贴合地表

## 不纳入本次范围

- **Sunken City** — 硬编码 Y=19，原理相同但延后实现
- **Cursed Pyramid** — 继承 `CataclysmStructure`，`generatePieces` 是实例方法（不同于 private static），需要不同的 Mixin 策略
- **CataclysmJigsawStructure** — Jigsaw 类型结构（ancient_factory、soul_black_smith），已有自己的 CODEC，需单独评估
