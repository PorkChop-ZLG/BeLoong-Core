# 设计文档：让湮灭猎影（Annihilation Pursuer）遵循传奇怪物通用 MiniBoss 伤害上限

> 状态：已完成，用户确认功能正常
> 日期：2026-09-07
> 范围：仅修改化龙核心（BeLoong Core），不修改传奇怪物（Legendary Monsters）源码

## 1. 背景与问题

在传奇怪物模组中，精英怪/小 Boss 共用配置项：

- 配置路径：`Mob Settings > General Settings > MiniBoss DamageCap`
- 默认值：`21`
- 控制逻辑：`IAnimatedMiniBoss.damageCap()` 返回 `ModConfig.MOB_CONFIG.MiniBossDamageCap.get()`，并在 `hurt()` 中通过 `Math.min(damageCap(), pAmount)` 限制单次受到的伤害。

但 `legendary_monsters:annihilation_pursuer`（湮灭猎影）存在例外：

- `AnnihilationPursuerEntity extends IAnimatedMiniBoss`
- 它重写了 `damageCap()`，并直接返回硬编码的 `21`
- 因此玩家修改 `MiniBoss DamageCap` 对该生物不生效，其伤害上限永远是 21。

已通过实际 CurseForge 文件 `legendary-monsters-944035:8715533` 的字节码确认：

```text
public double damageCap();
  Code:
     0: ldc2_w        // double 21.0d
     3: dreturn
```

## 2. 修复目标

让湮灭猎影像其它精英怪一样，动态读取传奇怪物自己的 `MiniBoss DamageCap` 配置。

即：修复后，修改传奇怪物配置文件中的 `MiniBoss DamageCap`，可以同时影响湮灭猎影的受伤上限。

## 3. 约束

1. 只能在化龙核心项目内修改。
2. 不修改传奇怪物源码/JAR。
3. 将传奇怪物设为化龙核心的可选兼容：
   - Gradle 依赖：`compileOnly` + `localRuntime "curse.maven:legendary-monsters-944035:8715533"`
   - `neoforge.mods.toml` 声明 optional 依赖，不写 versionRange（避免实际 JAR version=1.21.1 导致校验失败）
4. 新增 GeckoLib 必选依赖：`implementation "curse.maven:geckolib-388172:8350073"`
5. 需要符合 NeoForge / Mixin 在非 Minecraft 类上的既有写法，避免写错。

## 4. 技术事实核对

### 4.1 传奇怪物侧

| 项目 | 内容 |
|---|---|
| 模组 ID | `legendary_monsters` |
| 目标类 | `net.miauczel.legendary_monsters.entity.AnimatedMonster.Mobs.SpaceStation.Flameborn.AnnihilationPursuer.AnnihilationPursuerEntity` |
| 目标方法 | `public double damageCap()` |
| 继承关系 | `AnnihilationPursuerEntity extends IAnimatedMiniBoss` |
| 共享配置 | `ModConfig.MOB_CONFIG.MiniBossDamageCap`，类型为 `ModConfigSpec.IntValue` |
| 基类实现 | `IAnimatedMiniBoss.damageCap()` 返回 `ModConfig.MOB_CONFIG.MiniBossDamageCap.get()` |

### 4.2 NeoForge 侧核对

已阅读 NeoForge `ModConfigSpec` 源码（`neoforge-21.1.236-sources.jar`）：

- `ConfigValue<T>.get()` 为取值入口
- `IntValue extends ConfigValue<Integer>`
- `DoubleValue extends ConfigValue<Double>`

因此从 Mixin 中调用：

```java
ModConfig.MOB_CONFIG.MiniBossDamageCap.get()
```

会返回 `Integer`，可作为 `double` 使用。

### 4.3 为什么不需要改 Minecraft 代码

本修复不注入任何 Minecraft 类，而是注入传奇怪物自己的 `AnnihilationPursuerEntity#damageCap()`。该方法是普通 Java 方法，不涉及混淆映射，因此沿用项目内对第三方模组类的标准写法：

```java
@Mixin(value = AnnihilationPursuerEntity.class, remap = false)
```

并在注入点使用 `remap = false`。

## 5. 修复方案

采用 Mixin 在 `AnnihilationPursuerEntity#damageCap()` 的 `HEAD` 处取消原逻辑，直接返回共享的 `MiniBossDamageCap` 配置值。

### 5.1 修改文件清单

| 文件 | 改动 |
|---|---|
| `build.gradle` | 更新 GeckoLib 为 `8350073`；传奇怪物改为可选依赖 |
| `src/main/templates/META-INF/neoforge.mods.toml` | 新增 legendary_monsters optional 依赖声明 |
| `src/main/java/com/zonlong/beloong/mixin/legendarymonsters/AnnihilationPursuerDamageCapMixin.java` | 新增 Mixin（@Pseudo + require=0） |
| `src/main/resources/beloong.mixins.json` | 注册新 Mixin |

### 5.2 `build.gradle`

在 `dependencies` 中：

- GeckoLib 更新为：

```groovy
implementation "curse.maven:geckolib-388172:8350073"
```

- 传奇怪物改为可选依赖：

```groovy
compileOnly "curse.maven:legendary-monsters-944035:8715533"
localRuntime "curse.maven:legendary-monsters-944035:8715533"
```

### 5.3 `neoforge.mods.toml`

在 optional 依赖区新增：

```toml
[[dependencies.${mod_id}]]
    modId="legendary_monsters"
    type="optional"
    ordering="AFTER"
    side="BOTH"
```

说明：

- `type="optional"`：传奇怪物缺失时化龙核心仍可启动。
- 不写 `versionRange`：实际 LM JAR 的 `[[mods]] version` 为 `1.21.1`，写 `[2.1.15,)` 会导致 NeoForge 误判版本不满足；作为可选兼容依赖，不做版本约束最稳妥。
- `ordering="AFTER"`：若安装了传奇怪物，保证其先加载，Mixin 可稳定命中其类。

### 5.4 新增 Mixin 类

文件路径：

```text
src/main/java/com/zonlong/beloong/mixin/legendarymonsters/AnnihilationPursuerDamageCapMixin.java
```

设计代码：

```java
package com.zonlong.beloong.mixin.legendarymonsters;

import net.miauczel.legendary_monsters.config.ModConfig;
import net.miauczel.legendary_monsters.entity.AnimatedMonster.Mobs.SpaceStation.Flameborn.AnnihilationPursuer.AnnihilationPursuerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复湮灭猎影不读取通用 MiniBoss 伤害上限的问题。
 *
 * <p>原版 LM 中 {@link AnnihilationPursuerEntity#damageCap()} 硬编码返回 21，
 * 导致修改 {@code MiniBoss DamageCap} 对它无效。本 Mixin 在 HEAD 直接返回
 * {@link ModConfig.MOB_CONFIG#MiniBossDamageCap} 的当前配置值，使其与其它
 * IAnimatedMiniBoss 行为一致。</p>
 *
 * <p>传奇怪物为可选依赖：使用 {@code @Pseudo} + {@code require = 0}，
 * 未安装传奇怪物时本 Mixin 自动跳过，不影响化龙核心加载。</p>
 */
@Pseudo
@Mixin(value = AnnihilationPursuerEntity.class, remap = false)
public abstract class AnnihilationPursuerDamageCapMixin {

    @Inject(method = "damageCap", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void beloong$useSharedMiniBossDamageCap(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue((double) ModConfig.MOB_CONFIG.MiniBossDamageCap.get());
    }
}
```

要点：

- 使用 `@Inject` + `cancellable = true`，不覆盖整个方法体，降低对后续 LM 版本的侵入性。
- 目标方法返回 `double`，回调使用 `CallbackInfoReturnable<Double>`。
- 不新增化龙核心自己的配置项；修改入口仍然是传奇怪物的 `MiniBoss DamageCap`。
- 由于传奇怪物是可选依赖，使用 `@Pseudo` + `require = 0`；未安装传奇怪物时 Mixin 自动跳过，不影响化龙核心加载。若已安装但方法签名变化，`beloong.mixins.json` 的 `defaultRequire: 1` 会被 `require = 0` 覆盖，因此不会因该可选兼容点导致启动崩溃。

### 5.5 `beloong.mixins.json`

在 `"mixins"` 数组中新增：

```json
"legendarymonsters.AnnihilationPursuerDamageCapMixin"
```

该 Mixin 不涉及客户端渲染，因此放在通用 `mixins` 列表，不放 `client`。

## 6. 测试计划

1. 编译验证：
   ```bash
   ./gradlew build
   ```
2. 启动游戏/服务端，确认 Mixin 应用成功，日志无 `Mixin apply failed`。
3. 行为验证：
   - 将传奇怪物配置 `MiniBoss DamageCap` 改为较小值，例如 `5`。
   - 生成湮灭猎影，用高伤害攻击它。
   - 预期单次伤害被限制为 `5`，与其它小 Boss 一致。
4. 回归验证：
   - 将配置恢复默认 `21`，确认湮灭猎影仍按默认值工作。
   - 抽查其它 `IAnimatedMiniBoss`（如 Resurrected Knight、Ancient Guardian）不受影响。
5. 依赖验证：
   - 移除传奇怪物后启动，游戏应正常加载（Mixin 自动跳过）。
   - 安装传奇怪物后，修复逻辑应生效。

## 7. 风险与备选

| 风险 | 应对 |
|---|---|
| 传奇怪物后续版本修改 `damageCap` 签名/删除该方法 | 使用 `@Pseudo` + `require = 0`，不会导致启动崩溃；但修复会静默失效，需在升级 LM 后重新适配 |
| LM 的 `MiniBossDamageCap` 配置路径变化 | 依赖其公开静态配置类；若路径变化同样会静默失效，需重新编译适配 |
| 实际 LM JAR 的 `neoforge.mods.toml` 中 `version` 为 `1.21.1` | 已通过改为 optional 且不写 `versionRange` 规避版本校验失败 |
| 若以后希望独立控制湮灭猎影上限 | 本方案刻意不引入化龙核心配置；如需独立控制可另加配置项并在此 Mixin 中优先读取 |

## 8. 非目标

- 不修改传奇怪物源码/JAR。
- 不新增湮灭猎影的独立伤害上限配置。
- 不改变其它精英怪/ Boss 的伤害上限逻辑。
- 不处理传奇怪物“造成伤害”相关的 `Annihilation Pursuer Damage Multiplier`，本次只修复“受到伤害上限”。
