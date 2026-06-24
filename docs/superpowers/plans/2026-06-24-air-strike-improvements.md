# Air Strike 改进实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 改进 AirStrikeEffect：碰撞箱数据驱动、爪牙武器叠加、属性加成、自定义伤害类型与死亡消息。

**Architecture:** 修改 1 个 Java 类（AirStrikeEffect），1 个 ability JSON（air_strike.json），新建 1 个 damage_type JSON，修改 2 个翻译文件。

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge, Dragon Survival API

---

### Task 1: 新建伤害类型 JSON

**Files:**
- Create: `src/main/resources/data/beloong/damage_type/air_strike.json`

- [ ] **Step 1: 创建伤害类型定义文件**

```json
{
  "message_id": "beloong.air_strike",
  "exhaustion": 0.1,
  "scaling": "when_caused_by_living_non_player",
  "effects": "hurt",
  "death_message_type": "default"
}
```

- [ ] **Step 2: 验证文件路径正确**

运行: `ls src/main/resources/data/beloong/damage_type/air_strike.json`
预期: 文件存在

---

### Task 2: 更新翻译文件

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 在 zh_cn.json 末尾添加死亡消息**

在最后的 `"tooltip.beloong.treasure_value"` 行之前插入（注意末尾逗号）：

```json
"death.attack.beloong.air_strike": "%s遭受到了可爱星星的撞击",
"death.attack.beloong.air_strike.player": "%s在试图逃离%s时遭受到了可爱星星的撞击",
```

完整修改 —— 在 zh_cn.json 中，当前最后一行是：
```json
"tooltip.beloong.treasure_value": "财宝价值：%s"
```
改为：
```json
"death.attack.beloong.air_strike": "%s遭受到了可爱星星的撞击",
"death.attack.beloong.air_strike.player": "%s在试图逃离%s时遭受到了可爱星星的撞击",
"tooltip.beloong.treasure_value": "财宝价值：%s"
```

- [ ] **Step 2: 在 en_us.json 末尾添加死亡消息**

在 en_us.json 中，当前最后一行是：
```json
"tooltip.beloong.treasure_value": "Treasure Value: %s"
```
改为：
```json
"death.attack.beloong.air_strike": "%s was struck by a cute star",
"death.attack.beloong.air_strike.player": "%s was struck by a cute star whilst trying to escape %s",
"tooltip.beloong.treasure_value": "Treasure Value: %s"
```

---

### Task 3: 更新 ability JSON 添加 collision_size 字段

**Files:**
- Modify: `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/air_strike.json`

- [ ] **Step 1: 在 effect JSON 中添加 collision_size**

在 `air_strike.json` 中，`"min_speed": 0.5` 之后添加 `collision_size` 字段：

当前片段：
```json
              "min_speed": 0.5
```
改为：
```json
              "min_speed": 0.5,
              "collision_size": {
                "type": "minecraft:linear",
                "base": 1.0,
                "per_level_above_first": 0.0
              }
```

---

### Task 4: 重写 AirStrikeEffect.java

**Files:**
- Modify: `src/main/java/com/zonlong/beloong/ability/AirStrikeEffect.java`

- [ ] **Step 1: 替换全部 import 区域**

将现有 import 块替换为：

```java
package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.network.flight.SyncWingsSpread;
import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
```

- [ ] **Step 2: 替换 Record 定义、常量、Codec**

```java
public record AirStrikeEffect(
        LevelBasedValue baseDamage,
        LevelBasedValue speedFactor,
        LevelBasedValue collisionSize,
        LevelBasedValue minSpeed
) implements AbilityEntityEffect {

    static final ResourceKey<DamageType> AIR_STRIKE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "air_strike")
    );

    /** 同时支持纯数字和 LevelBasedValue 对象格式 */
    private static final Codec<LevelBasedValue> FLEXIBLE_LBV = Codec.either(
            LevelBasedValue.CODEC,
            Codec.DOUBLE
    ).xmap(
            either -> either.map(lbv -> lbv, d -> LevelBasedValue.constant((float)(double)d)),
            Either::left
    );

    public static final MapCodec<AirStrikeEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FLEXIBLE_LBV.fieldOf("base_damage").forGetter(AirStrikeEffect::baseDamage),
            FLEXIBLE_LBV.fieldOf("speed_factor").forGetter(AirStrikeEffect::speedFactor),
            FLEXIBLE_LBV.fieldOf("collision_size").forGetter(AirStrikeEffect::collisionSize),
            FLEXIBLE_LBV.fieldOf("min_speed").forGetter(AirStrikeEffect::minSpeed)
    ).apply(instance, AirStrikeEffect::new));
```

- [ ] **Step 3: 替换 apply() 方法**

```java
    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        if (!(target instanceof ServerPlayer player)) {
            return;
        }

        if (!ServerFlightHandler.isGliding(player)) {
            return;
        }

        int level = ability.level();
        double speed = player.getDeltaMovement().length();
        if (speed < minSpeed.calculate(level)) {
            return;
        }

        // ── 伤害计算 ──
        float base = baseDamage.calculate(level);

        // SWORD 爪牙槽武器攻击力，空槽时 = 0
        double weaponDamage = 0;
        var sword = ClawInventoryData.getData(player).getSword();
        if (!sword.isEmpty()) {
            for (ItemAttributeModifiers.Entry entry : sword.getAttributeModifiers().modifiers()) {
                if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                    weaponDamage += entry.modifier().amount();
                }
            }
        }

        // dragon_ability_damage 属性，不存在时自动返回默认值 1.0
        double abilityScale = player.getAttributeValue(DSAttributes.DRAGON_ABILITY_DAMAGE);

        float damage = (float) ((base + weaponDamage) * speed * speedFactor.calculate(level) * abilityScale);

        // actionbar 显示
        player.displayClientMessage(
                Component.translatable("dragon_ability.beloong.air_strike.actionbar",
                        String.format("%.1f", speed * 20 * 3.6),
                        String.format("%.1f", damage)),
                true);

        // ── 碰撞扫描 ──
        double size = collisionSize.calculate(level);
        List<LivingEntity> hitEntities = player.level().getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(size),
                e -> e != player && e.isAlive() && e.isPickable()
        );

        if (hitEntities.isEmpty()) {
            return;
        }

        // ── 施加伤害 ──
        Holder<DamageType> damageType = dragon.serverLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(AIR_STRIKE);

        boolean dealtDamage = false;
        for (LivingEntity hitTarget : hitEntities) {
            if (hitTarget.hurt(new DamageSource(damageType, dragon), damage)) {
                dealtDamage = true;
            }
        }

        if (dealtDamage) {
            FlightData.getData(player).areWingsSpread = false;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new SyncWingsSpread(player.getId(), false));
        }
    }
```

- [ ] **Step 4: 替换 getDescription() 方法**

```java
    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        int level = ability.level();
        return List.of(
                Component.translatable("dragon_ability.beloong.air_strike.dynamic_desc",
                        String.format("%.1f", baseDamage.calculate(level)),
                        String.format("%.1f", speedFactor.calculate(level)),
                        String.format("%.1f", collisionSize.calculate(level)),
                        String.format("%.1f", minSpeed.calculate(level)))
        );
    }
```

- [ ] **Step 5: 保留 entityCodec() 方法不变**

无需修改，保持原样：

```java
    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
```

---

### Task 5: 更新翻译文件中的描述文本

**Files:**
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

- [ ] **Step 1: 更新 zh_cn.json 的 dynamic_desc**

当前：
```json
"dragon_ability.beloong.air_strike.dynamic_desc": "基础伤害：§2%s§r§7\n速度系数：§2%s§r§7\n最低触发速度：§2%s§r§7§r\n\n伤害=基础伤害+速度×系数§r",
```
改为（4 个参数，加碰撞箱，更新公式）：
```json
"dragon_ability.beloong.air_strike.dynamic_desc": "基础伤害：§2%s§r§7\n速度系数：§2%s§r§7\n碰撞箱：§2%s§r§7\n最低触发速度：§2%s§r§7§r\n\n伤害=(基础伤害+武器攻击力)×速度×系数×龙息伤害属性§r",
```

- [ ] **Step 2: 更新 en_us.json 的 dynamic_desc**

当前：
```json
"dragon_ability.beloong.air_strike.dynamic_desc": "■ Base Damage: §c%s§r§7\nSpeed Factor: §c%s§r§7\nMin Speed: §c%s§r\n\n■ Damage = Base + Speed×Factor§r",
```
改为（4 个参数，加碰撞箱，更新公式）：
```json
"dragon_ability.beloong.air_strike.dynamic_desc": "■ Base Damage: §c%s§r§7\nSpeed Factor: §c%s§r§7\nCollision Size: §c%s§r§7\nMin Speed: §c%s§r\n\n■ Damage = (Base + WeaponATK)×Speed×Factor×AbilityDamage§r",
```

---

### Task 6: 编译验证

- [ ] **Step 1: 运行 Gradle 编译**

```bash
cd e:/Minecraft/BeLoong-Core && ./gradlew build
```
预期: BUILD SUCCESSFUL，无编译错误

---

### Task 7: 提交

- [ ] **Step 1: 暂存并提交所有改动**

```bash
cd e:/Minecraft/BeLoong-Core
git add src/main/java/com/zonlong/beloong/ability/AirStrikeEffect.java
git add src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/air_strike.json
git add src/main/resources/data/beloong/damage_type/air_strike.json
git add src/main/resources/assets/beloong/lang/zh_cn.json
git add src/main/resources/assets/beloong/lang/en_us.json
git add docs/superpowers/specs/2026-06-24-air-strike-improvements-design.md
git add docs/superpowers/plans/2026-06-24-air-strike-improvements.md
git commit -m "$(cat <<'EOF'
feat: 改进龙击长空 — 碰撞箱数据驱动、爪牙武器叠加、属性加成、自定义伤害类型

- 碰撞箱改用 LevelBasedValue 数据驱动（collision_size）
- 叠加 SWORD 爪牙槽武器 ATTACK_DAMAGE 到基础伤害
- 伤害乘以 dragon_ability_damage 属性
- 使用 beloong:air_strike 自定义伤害类型与死亡消息
EOF
)"
```
