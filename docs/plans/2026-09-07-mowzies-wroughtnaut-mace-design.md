# 钢铁守护者原版重锤伤害兼容（Mowzie's Mobs）设计文档

**Date:** 2026-09-07
**Status:** Draft
**Scope:** 仅在 BeLoong-Core 内实现，不修改 Mowzie's Mobs 源码

## Problem Statement

Mowzie's Mobs 的 Boss `mowziesmobs:ferrous_wroughtnaut`（钢铁守护者）默认有一套伤害免疫机制：

- 未进入脆弱窗口时，几乎所有伤害都会被它的 `hurt()` 直接 `return false`。
- 只有竖劈攻击动画的特定 tick 内，且攻击者位于其背后时，才能造成伤害。

需求是为 BeLoong-Core 增加一条类似 Grottol“特殊工具可破防”的兼容机制：

1. 玩家使用原版重锤 `minecraft:mace` 时，可以对钢铁守护者直接造成伤害。
2. 与 Grottol 不同，重锤不是一击必杀，而是按正常伤害计算。
3. 仅在钢铁守护者“已激活且处于战斗中”时生效。
4. 完全无视方向（正面/背面、是否处于脆弱窗口都无所谓）。
5. 不需要打断钢铁守护者当前动作。
6. 提供配置开关，默认启用，配置风格参考 BeLoong-Core 现有模组兼容配置。
7. 只在 BeLoong-Core 中通过 Mixin 实现，不修改 Mowzie's Mobs 源代码。

## 前提与依赖状态

已确认 Mowzie's Mobs 在整合包中为**必选依赖**，使用 NeoForge 1.21.1 / MC 1.21.1 版本。

BeLoong-Core 的依赖修改已完成：

```gradle
// build.gradle
// Mowzie's Mobs — 必选依赖（钢铁守护者重锤伤害）
implementation "curse.maven:mowzies-mobs-250498:7760267"
```

`neoforge.mods.toml` 中新增必选依赖：

```toml
[[dependencies.${mod_id}]]
    modId="mowziesmobs"
    type="required"
    versionRange="[1.8.2,)"
    ordering="AFTER"
    side="BOTH"
```

由于 Mowzie's Mobs 是必选依赖，Mixin 不需要 `@Pseudo` 或条件加载；缺少 Mowzie 时 NeoForge 会直接阻止 BeLoong-Core 加载。

## 参考的 1.21.1 反编译结果

已将 1.21.1 关键类反编译到 `D:\Minecraft\MowziesMobs-Public\decompiled-1.21.1`，确认：

- `EntityWroughtnaut` 继承 `MowzieLLibraryEntity`。
- `EntityWroughtnaut.hurt(DamageSource, float)` 仍然通过提前 `return false` 实现免伤。
- `EntityWroughtnaut` 有公开字段 `vulnerable`。
- 有 `isActive()` 和 `getTarget()` 可用于“已激活且战斗中”判定。
- `MowzieLLibraryEntity.hurt(DamageSource, float)` 是正常的父类伤害入口，调用它可绕过钢铁守护者自身 `hurt()` 的免伤逻辑。

## Minecraft / NeoForge 1.21.1 API 核对

已通过本机 NeoForge 1.21.1 mapped jar 核对以下 API，设计不依赖未经验证的假设：

- `net.minecraft.world.item.Items.MACE` 存在，可直接用于判断主手物品。
- `DamageSource` 字段语义：
  - `directEntity`：直接造成伤害的实体（近战玩家 = 玩家本体；箭 = 箭实体）。
  - `causingEntity`：间接责任实体（箭的射出者；普通近战与 direct 相同）。
  - `getDirectEntity()` 返回 `directEntity`，`getEntity()` 返回 `causingEntity`。
- `DamageSources.playerAttack(Player)` 创建时 `directEntity == causingEntity == player`，且伤害类型为 `DamageTypes.PLAYER_ATTACK`。因此用 `source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() instanceof Player` 可以精确识别玩家近战，且不会把箭/弹射物或其他 `directEntity=Player` 的伤害误判为“玩家手持重锤攻击”。
- `LivingEntity.hurt()` 内的 NeoForge 伤害事件（`CommonHooks.onEntityIncomingDamage` / `LivingIncomingDamageEvent`）位于原版 `LivingEntity.hurt()` 方法体内。Mowzie 的 `EntityWroughtnaut.hurt()` 在免伤路径中直接 `return false`，不会进入 `super.hurt()`，因此**不能只依赖 NeoForge 伤害事件**，必须 Mixin 到 `EntityWroughtnaut#hurt`。
- BeLoong-Core 已有“Mixin 类继承目标父类”的先例：`AltarOfAmethystMixin extends BaseEntityBlock`，因此设计中的 `EntityWroughtnautMaceMixin extends MowzieLLibraryEntity` 写法与项目现有 Mixin 风格一致。

## Design

### Architecture

```
BeLoong-Core
├── build.gradle
│   └── implementation "curse.maven:mowzies-mobs-250498:7760267"
├── src/main/templates/META-INF/neoforge.mods.toml
│   └── 必选依赖 mowziesmobs [1.8.2,)
├── src/main/java/com/zonlong/beloong/
│   ├── Config.java
│   │   └── COMMON 配置：enableMowzieMaceDamage = true
│   ├── compat/mowziesmobs/
│   │   └── MowzieMobsCompat.java       # 重锤伤害判定 helper
│   └── mixin/mowziesmobs/
│       └── EntityWroughtnautMaceMixin.java  # Mixin 进 EntityWroughtnaut#hurt
├── src/main/resources/beloong.mixins.json
│   └── "mixins": 新增 "mowziesmobs.EntityWroughtnautMaceMixin"
└── src/main/resources/assets/beloong/lang/
    ├── en_us.json
    └── zh_cn.json
```

### Components

#### 1. 配置开关

在 `Config.java` 的 `COMMON_BUILDER` 顶部新增：

```java
/** Mowzie's Mobs 钢铁守护者：允许原版重锤伤害（默认启用） */
public static final ModConfigSpec.BooleanValue ENABLE_MOWZIE_MACE_DAMAGE = COMMON_BUILDER
        .comment("Enable Ferrous Wroughtnaut damage by vanilla mace",
                "允许使用原版重锤对已激活的钢铁守护者造成伤害")
        .define("enableMowzieMaceDamage", true);
```

- 配置类型：COMMON（与现有 `ds_ftbchunks_compat`、`bd_ftbchunks_compat` 等模组兼容开关一致）。
- 默认值：`true`。
- 配置文件名：`beloong-common.toml`。

语言文件新增：

`en_us.json`：

```json
"beloong.configuration.enableMowzieMaceDamage": "Mowzie Mace Damage",
"beloong.configuration.enableMowzieMaceDamage.tooltip": "Allow the vanilla mace to damage an active Ferrous Wroughtnaut that is in combat."
```

`zh_cn.json`：

```json
"beloong.configuration.enableMowzieMaceDamage": "允许重锤伤害钢铁守护者",
"beloong.configuration.enableMowzieMaceDamage.tooltip": "钢铁守护者已激活且处于战斗中时，允许使用原版重锤直接造成伤害。"
```

#### 2. MowzieMobsCompat helper

新增 `com.zonlong.beloong.compat.mowziesmobs.MowzieMobsCompat`：

```java
public final class MowzieMobsCompat {
    private MowzieMobsCompat() {}

    public static boolean isMaceAttack(DamageSource source) {
        // 只接受原版玩家近战伤害类型，避免爆炸等其他 directEntity=Player 的伤害误判
        if (!source.is(DamageTypes.PLAYER_ATTACK)) {
            return false;
        }

        if (source.getDirectEntity() instanceof Player player) {
            return player.getMainHandItem().is(Items.MACE);
        }

        return false;
    }
}
```

判定说明：

- 使用 `source.is(DamageTypes.PLAYER_ATTACK)` 限定为原版玩家近战攻击。
- 使用 `getDirectEntity()` 确保是玩家本体近战，而不是箭/弹射物。
- 只判断主手物品为 `minecraft:mace`。
- 不要求攻击者必须是 Boss 当前目标（多人协助输出也允许）。

#### 3. EntityWroughtnautMaceMixin

新增 `com.zonlong.beloong.mixin.mowziesmobs.EntityWroughtnautMaceMixin`：

```java
@Mixin(EntityWroughtnaut.class)
public abstract class EntityWroughtnautMaceMixin extends MowzieLLibraryEntity {

    protected EntityWroughtnautMaceMixin(
            EntityType<? extends MowzieEntity> type,
            Level level
    ) {
        super(type, level);
    }

    @Inject(
            method = "hurt",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void beloong$allowMaceDamage(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Config.ENABLE_MOWZIE_MACE_DAMAGE.get()) {
            return;
        }

        // 仅“已激活且有目标”时生效
        if (!isActive() || getTarget() == null) {
            return;
        }

        // 只有玩家手持原版重锤的近战攻击才绕过免疫
        if (!MowzieMobsCompat.isMaceAttack(source)) {
            return;
        }

        // 正常伤害，不秒杀、不打断动作、不判断方向
        cir.setReturnValue(super.hurt(source, amount));
    }
}
```

核心逻辑：

- 条件全部满足时，调用父类 `MowzieLLibraryEntity#hurt`，即“正常受伤流程”。
- 不设置 `vulnerable`。
- 不调用 `setAnimation(NO_ANIMATION)`，因此不会打断当前攻击动作。
- 不判断 `entitySource` 相对角度，因此正面/背面均有效。
- 不改变 `amount`，保留原版重锤的伤害/坠落加成计算结果。

#### 4. Mixin 注册

在 `src/main/resources/beloong.mixins.json` 的 `"mixins"` 数组中新增：

```json
"mowziesmobs.EntityWroughtnautMaceMixin"
```

放在 `"mixins"` 而不是 `"client"`，因为 `hurt()` 属于双端/服务端实体逻辑。

### Data Flow

```
玩家使用重锤近战攻击钢铁守护者
  → EntityWroughtnaut.hurt(source, amount) 被调用
  → Mixin HEAD 注入点
      ├─ 配置关闭？ → 回到原版 Mowzie 免伤逻辑
      ├─ 未激活或无目标？ → 回到原版 Mowzie 免伤逻辑
      ├─ 不是玩家手持 mace 的近战？ → 回到原版 Mowzie 免伤逻辑
      └─ 全部通过 → cir.setReturnValue(super.hurt(source, amount))
            → MowzieLLibraryEntity.hurt 正常伤害流程
            → 正常扣血 / 死亡判定
```

### Edge Cases / Error Handling

- 配置关闭：完全保持 Mowzie 原版行为。
- Boss 未激活 / 没有目标：重锤无效，保持原版免伤。
- Boss 已激活且有目标：
  - 重锤从正面、背面、侧面攻击均有效。
  - 无论是否处于 `vulnerable` 窗口均有效。
  - 不打断竖劈、 stomp 等当前动画。
- 非重锤武器：仍按 Mowzie 原版机制（平时免伤、竖劈后打背）。
- 弹射物 / 非玩家来源：不满足 `isMaceAttack`，按原版机制处理。
- 多人环境：攻击者不要求是 Boss 当前目标，只要 Boss 处于战斗状态即可。
- Mowzie's Mobs 缺失：因为已改为必选依赖，NeoForge 加载阶段会直接报缺失，不需要运行时软兼容。
- Mowzie 后续版本升级：若 `EntityWroughtnaut.hurt` 签名或父类结构变化，需要同步更新 Mixin。

## Decisions Made

1. 在 BeLoong-Core 内通过 Mixin 实现，不修改 Mowzie's Mobs 源码。
2. Mowzie's Mobs 改为**必选依赖**。
   - build.gradle：`implementation "curse.maven:mowzies-mobs-250498:7760267"`
   - mods.toml：`type="required"`，版本范围 `[1.8.2,)`
3. 配置开关：
   - 字段：`ENABLE_MOWZIE_MACE_DAMAGE`
   - key：`enableMowzieMaceDamage`
   - 位置：COMMON 配置
   - 默认：`true`
4. “已激活/战斗中”判定：
   - `isActive() == true`
   - `getTarget() != null`
5. 不要求攻击者必须是 Boss 当前目标。
6. 重锤造成**正常伤害**，不是一击必杀。
7. 完全无视方向与 vulnerable 窗口。
8. 不打断动作。
9. 使用 HEAD 注入 + 调用父类 `super.hurt`，而不是逐个拦截原方法的 `return false`，避免脆弱且维护困难。
10. 不添加 `@Pseudo`，因为 Mowzie 已为必选依赖。

## Non-Goals

- 不修改 Mowzie's Mobs 源码。
- 不改变 Grottol 原有机制。
- 不使重锤一击必杀。
- 不改变钢铁守护者对其他武器/伤害来源的免疫逻辑。
- 不新增战利品、进度或特殊掉落。
- 不新增客户端表现。
- 不修改原版重锤伤害公式。

## Next Steps

1. 在 `Config.java` 添加 `ENABLE_MOWZIE_MACE_DAMAGE`。
2. 在 `en_us.json` / `zh_cn.json` 添加翻译 key。
3. 新增 `MowzieMobsCompat`。
4. 新增 `EntityWroughtnautMaceMixin`。
5. 在 `beloong.mixins.json` 注册 Mixin。
6. 在开发环境验证：
   - 未激活 Boss：重锤无伤害。
   - 已激活且有目标：重锤正常伤害。
   - 非重锤武器：维持原版免伤。
   - 配置关闭：重锤不生效。
   - 重锤伤害不打断当前攻击动画。
