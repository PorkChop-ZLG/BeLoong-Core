# DS Block Effects FTB Chunks 兼容技术文档

## 概述

龙之生存（Dragon Survival）的龙息技能可以执行破坏性方块操作（挖掘、爆炸、点燃、转换等），
这些操作能绕过 FTB Chunks 的领地保护。本功能通过 Mixin 拦截 6 个 `AbilityBlockEffect` 实现类
和 `DragonDestructionHandler` 中的连锁破坏逻辑，确保已认领区块内的所有方块破坏行为被阻止。

## 架构设计

### 为什么每个 Effect 单独 Mixin？

`AbilityBlockEffect` 是接口，6 个实现类各自独立。使用 `@Mixin` 注解分别混入每个实现类，
而非在统一入口拦截，原因：

1. **精确性** — 每个 `@Inject(method = "apply")` 直接对应接口方法，签名明确
2. **安全性** — 某个 Effect 变更不影响其他拦截
3. **可维护性** — 一目了然哪些 Effect 被覆盖，方便增删

### ClaimProtectionHelper 统一入口

所有 Mixin 共享一个静态工具类 `ClaimProtectionHelper`，避免重复代码：

```java
public static boolean isClaimed(Entity actor, BlockPos pos) {
    // 1. 空值检查
    // 2. FTB Chunks 未安装 → 放行
    // 3. 配置开关关闭 → 放行
    // 4. API Manager 不可用 → 放行
    // 5. shouldPreventInteraction(CHECK 策略) → 返回结果
}
```

`ProtectionPolicy.CHECK` 策略表示：只要区块被认领，无论操作者是否有团队权限，一律拦截。

## 拦截的 Effect 清单

| Effect 类 | 破坏行为 | Mixin 文件 |
|-----------|----------|-----------|
| `BlockBreakEffect` | 破坏方块（挖掘） | `BlockBreakEffectMixin.java` |
| `BlockConversionEffect` | 转换方块（如龙息石化） | `BlockConversionEffectMixin.java` |
| `ExplodeBlockEffect` | 爆炸破坏 | `ExplodeBlockEffectMixin.java` |
| `FireEffect` | 点燃方块 | `FireEffectMixin.java` |
| `BlockHarvestEffect` | 收获方块（如右键收割） | `BlockHarvestEffectMixin.java` |
| `BonemealEffect` | 催熟作物 | `BonemealEffectMixin.java` |

此外，`DragonDestructionHandlerMixin` 拦截两种非 Effect 路径的破坏：
- **连锁挖掘**（multi-mining）— `destroyBlocksInRadius`
- **大型龙碰撞破坏**（trample）— `checkAndDestroyCollidingBlocks`

## Mixin 注入点

所有 6 个 Effect Mixin 使用相同的注入模式：

```java
@Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
        BlockPos position, Direction direction, CallbackInfo ci) {
    if (ClaimProtectionHelper.isClaimed(dragon, position)) {
        ci.cancel();  // 取消 effect 执行
    }
}
```

- `at = @At("HEAD")` — 在 `apply()` 方法开头注入
- `cancellable = true` — 允许取消方法执行
- `remap = false` — DS 类不使用 Mojang 映射，方法名无需转换

## 配置项

| 键名 | 类型 | 默认值 | 配置类型 | 说明 |
|------|------|--------|----------|------|
| `ds_ftbchunks_compat` | 布尔 | `true` | COMMON | FTB Chunks 领地保护兼容开关 |

COMMON 类型意味着配置在客户端和服务端都加载，服务端会同步配置至客户端。

## Mixin 配置注册

### beloong.mixins.json 结构

```json
{
  "refmap": "beloong.refmap.json",
  "client": [ /* 3 个纯客户端 mixin */ ],
  "mixins":  [ /* 7 个通用 mixin（含 6 个 Effect + DragonDestructionHandler） */ ]
}
```

### 环境侧说明

| JSON 键 | 加载时机 | 适用场景 |
|---------|---------|---------|
| `client` | 物理客户端（含单机） | UI、渲染相关 |
| `mixins` | 客户端 + 专用服务器 | 游戏逻辑，两端都需要 |
| `server` | 仅专用服务器 | 仅服务器端逻辑 |

**关键踩坑记录**：最初将 Effect Mixin 放在 `server` 段，导致单机模式下完全不加载。
原因是单机模式中游戏进程是 CLIENT 环境，`server` 段仅对专用服务器生效。

### refmap 配置

`beloong.mixins.json` 必须包含 `"refmap": "beloong.refmap.json"`，且 `build.gradle` 需配置 Mixin AP：

```groovy
annotationProcessor "net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7"
```

NeoForge 1.21.1 使用 Mojang 映射，方法名在开发环境和运行时完全一致。
因此 refmap 内容只需空占位 `{"mappings": {}}`，但文件**必须存在**，否则 Mixin 框架静默跳过所有 `@Inject`。

## neoforge.mods.toml 注册

```toml
[[mixins]]
config = "beloong.mixins.json"
```

NeoForge MDG 2.x 不会自动发现 mixin 配置，必须在 mod 元数据中显式声明。

## 故障排除

| 现象 | 可能原因 | 检查点 |
|------|---------|--------|
| Mixin 完全不生效 | refmap 缺失或无法读取 | 日志中是否有 beloong.refmap.json 的警告 |
| Mixin 构建无错误但不加载 | Mixin AP 未运行 | `./gradlew dependencies --configuration annotationProcessor` |
| 单机不生效，服务器正常 | JSON 中误用 `server` 段 | 应使用 `mixins` 段 |
| 特定 Effect 不被拦截 | 该 Effect 是否在 `mixins` 列表中 | 检查 `beloong.mixins.json` |

## 文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `src/main/java/.../mixin/BlockBreakEffectMixin.java` | 新建 | 拦截破坏方块 |
| `src/main/java/.../mixin/BlockConversionEffectMixin.java` | 新建 | 拦截转换方块 |
| `src/main/java/.../mixin/ExplodeBlockEffectMixin.java` | 新建 | 拦截爆炸破坏 |
| `src/main/java/.../mixin/FireEffectMixin.java` | 新建 | 拦截点燃方块 |
| `src/main/java/.../mixin/BlockHarvestEffectMixin.java` | 新建 | 拦截收获方块 |
| `src/main/java/.../mixin/BonemealEffectMixin.java` | 新建 | 拦截催熟作物 |
| `src/main/java/.../mixin/DragonDestructionHandlerMixin.java` | 修改 | 重构为使用 ClaimProtectionHelper |
| `src/main/java/.../util/ClaimProtectionHelper.java` | 新建 | FTB Chunks 领地检查统一入口 |
| `src/main/java/.../Config.java` | 修改 | 新增 ds_ftbchunks_compat (COMMON 类型) |
| `src/main/java/.../BeLoongCore.java` | 修改 | 注册 COMMON_SPEC |
| `src/main/resources/beloong.mixins.json` | 修改 | 新增 6 个 Effect Mixin + refmap |
| `src/main/resources/beloong.refmap.json` | 新建 | Mixin 引用映射表（空占位） |
| `build.gradle` | 修改 | 添加 Mixin AP annotationProcessor |
| `src/main/resources/assets/beloong/lang/zh_cn.json` | 修改 | 中文配置翻译 |
| `src/main/resources/assets/beloong/lang/en_us.json` | 修改 | 英文配置翻译 |
