# 龙卷风（Tornado）龙技能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 BeLoong Core 里新增一个主动龙技能「龙卷风」——向准星方向发射一个青色自定义实体，该实体匀速直线飞行、持续吸引周围敌人，并每 tick 结算一次无视攻击冷却的伤害。

**Architecture:** 三层：①数据包层（`dragon_ability` JSON + 伤害类型 + 两个原版伤害 tag）声明技能与伤害语义；②服务端层（`TornadoEffect` 自定义 `AbilityEntityEffect` → `TornadoEntity extends Projectile`）承载全部玩法逻辑，位移/吸引/伤害只在服务端跑；③客户端层（`TornadoModel` + `TornadoRenderer`）只负责渲染，位置完全由原版同步包驱动。模型几何与贴图偏移逐个照抄灾变 `Sandstorm_Projectile_Model`，贴图重上色为青色。

**Tech Stack:** Java 21 · Minecraft 1.21.1 · NeoForge 21.1.236 · Parchment 2024.11.17 · Dragon Survival 2.0.69 API · ModDevGradle 2.0.141 · Python 3 + Pillow（仅用于一次性贴图/图标生成，不进 jar）

**Spec:** `docs/superpowers/specs/2026-09-17-tornado-ability-design.md`

> ## 勘误（实施后修订，2026-09-17）
>
> 本计划执行期间有几处偏离计划正文的改动，均已在独立审查后合并。**正文中所有相冲突的表述以本节为准**：
>
> 1. **Task 4 渲染器锚点平移量**：计划原文的 `-MODEL_ROOT_Y`（= −24）**错了 16 倍**。模型空间 1 单位 = pose 栈 1/16 单位（`ModelPart.translateAndRotate:149` 与 `Cube.compile:358-360` 都做 /16），实际交付为 `-MODEL_ROOT_Y / 16.0F`（= −1.5）。文档勘误提交 `df45772`。
> 2. **Task 6 之后的最终修复波**（提交 `49f3459`）：
>    - 伤害字段 `damage_per_tick` 端到端改名 **`damage_per_hit`**，数值重标定为 **2.0 / 3.0 / 4.0 / 5.0 / 6.0**（`linear base 2.0, per_level 1.0`）。
>    - 伤害频率由「每 tick 结算」改为**每 4 刻结算 1 次、每次 4 倍伤害**（用户裁决）。DPS 逐级不变；**吸引仍是每 tick**。
>    - 客户端改为**每 tick 外推一次位移**，服务端每 tick `hurtMarked = true` 发布速度。本计划原文中「客户端不自己跑位移」与「平滑由 `partialTick` 插值提供」的说法**均已作废**（后者理由是错的）。
>    - `experience_cost.values[0]` 由 `1.0` 改为 **`0.0`**。
>    - `exhaustion` 由 `0.1` 改为 **`0.02`**。
>    - `TornadoEffect` 的 self-only 契约已补文档；候选盒改用 `Math.max(pullRadius, damageRadius)`。
>
> 3. **移动方式改为反弹**（用户试玩反馈）：`speed` 由 `0.45` 下调至 **`0.30`**；「匀速直线穿透方块」改为**撞方块镜面反弹**（`Entity#move` 逐轴碰撞检测 + 该轴速度取反，不衰减；不设弹跳上限），**生物仍可穿透**；客户端与服务端**都**执行该碰撞位移。这推翻了正文中 Task 2 的「不调用 move()，所以不与方块碰撞」。
>
> 4. **消散缩小**（用户试玩反馈）：改为寿命最后 20 刻线性缩放到 0，替代「寿命一到整根消失」。为此新增唯一的同步字段 `DATA_INITIAL_LIFE`（生成时初始寿命，一生一次），渲染器据此倒推。**这修订了 Task 2/4 中「本实体不同步任何自定义数据」「渲染器不得读取实体数值字段」的绝对表述** —— 现在只允许读 `getRemainingLife()`，四个玩法字段仍不同步、仍不得读取。
>
> 因此正文 Task 2/4/5/6 中出现的 `damage_per_tick`、`每 tick 结算伤害`、`客户端不跑位移`、「`partialTick` 插值提供平滑」、「穿透方块」、以及「不同步任何自定义数据」等表述，一律以本节为准。

## Global Constraints

- 模组 ID `beloong`，Java 包根 `com.zonlong.beloong`，主类 `BeLoongCore`。
- 目标版本固定 **Minecraft 1.21.1 / NeoForge 21.1.236 / Java 21**，不要引用其它版本的 API。
- ~~**本仓库不是 git 仓库**（根目录无 `.git`）。因此本计划**没有 commit 步骤**~~ **【已作废，见上方勘误】** 执行期间已 `git init` 并建立特性分支，每个任务都产出提交；「编译 + 落盘检查」仍作为每任务的检查点保留。
- Windows 下构建命令一律 `.\gradlew.bat`（不是 `./gradlew`）。**若遇到权限不足，先向用户请求权限**，不要改 `GRADLE_USER_HOME` 或换目录绕路。
- 新写入的 `.java` 与 `.json` 一律 **UTF-8**（`build.gradle` 已设 `options.encoding = 'UTF-8'`）。中文注释保留。
- 面向玩家的文本**不硬编码**，一律走 `assets/beloong/lang/{zh_cn,en_us}.json`。
- 数据包 JSON 的验证**不能只做语法检查**：必须跑一次 `runClient` 后 grep `run\client\logs\latest.log` 里的 `Failed to parse` 与 `Registry loading errors`，零命中才算通过。
- 开发期验证策略：`runClient` 启动一次后可以一直开着。**纯数据包改动**（`data/**`、`assets/**/lang`）用游戏内 `/reload` 即可生效；**Java 改动必须关掉重启**，NeoForge 开发环境不做热替换。
- 不修改任何 `dragon_species` 或 species ability tag —— 技能分配交给整合包数据包。
- 伤害数值一律先乘 `DSAttributes.DRAGON_ABILITY_DAMAGE`，与现有 `AirStrikeEffect` 保持一致。

---

### Task 1: 伤害类型与伤害标签

建立「龙卷风伤害」这个数据层语义：自定义伤害类型 + 两个原版 tag 让它无视攻击冷却且不产生击退。

**Files:**
- Create: `src/main/resources/data/beloong/damage_type/tornado.json`
- Create: `src/main/resources/data/minecraft/tags/damage_type/bypasses_cooldown.json`
- Create: `src/main/resources/data/minecraft/tags/damage_type/no_knockback.json`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`（末尾追加 2 个键）
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`（末尾追加 2 个键）

**Interfaces:**
- Consumes: 无
- Produces: 伤害类型 ID `beloong:tornado`；死亡消息键 `death.attack.beloong.tornado` 与 `death.attack.beloong.tornado.player`

- [ ] **Step 1: 新建伤害类型定义**

写入 `src/main/resources/data/beloong/damage_type/tornado.json`：

```json
{
  "message_id": "beloong.tornado",
  "exhaustion": 0.1,
  "scaling": "when_caused_by_living_non_player",
  "effects": "hurt",
  "death_message_type": "default"
}
```

- [ ] **Step 2: 让该伤害类型无视攻击冷却**

新建 `src/main/resources/data/minecraft/tags/damage_type/bypasses_cooldown.json`：

```json
{
  "values": [
    "beloong:tornado"
  ]
}
```

数据包之间 tag 是**合并**的，所以这个文件只会往原版 tag 里追加一个值，不会覆盖原版内容。

- [ ] **Step 3: 让该伤害类型不产生击退**

新建 `src/main/resources/data/minecraft/tags/damage_type/no_knockback.json`：

```json
{
  "values": [
    "beloong:tornado"
  ]
}
```

- [ ] **Step 4: 追加死亡消息翻译**

先读 `src/main/resources/assets/beloong/lang/zh_cn.json`，找到最后一行（`"tooltip.beloong.treasure_value": ...` 那一行），在它**之前**插入下面两行（注意行尾逗号）：

```json
  "death.attack.beloong.tornado": "%s被龙卷风撕碎",
  "death.attack.beloong.tornado.player": "%s在试图逃离%s时被龙卷风撕碎",
```

同样地在 `src/main/resources/assets/beloong/lang/en_us.json` 的对应位置插入：

```json
  "death.attack.beloong.tornado": "%s was shredded by a tornado",
  "death.attack.beloong.tornado.player": "%s was shredded by a tornado whilst trying to escape %s",
```

- [ ] **Step 5: 校验 JSON 语法**

Run:
```powershell
python -c "import json,glob; [json.load(open(f,encoding='utf-8')) for f in ['src/main/resources/data/beloong/damage_type/tornado.json','src/main/resources/data/minecraft/tags/damage_type/bypasses_cooldown.json','src/main/resources/data/minecraft/tags/damage_type/no_knockback.json','src/main/resources/assets/beloong/lang/zh_cn.json','src/main/resources/assets/beloong/lang/en_us.json']]; print('JSON OK')"
```
Expected: `JSON OK`（任何一个文件语法错都会抛异常）

- [ ] **Step 6: 验证注册表加载（需要用户跑游戏）**

Run: `.\gradlew.bat runClient`

进游戏后在日志里检查：
```powershell
Select-String -Path "run\client\logs\latest.log" -Pattern "Failed to parse|Registry loading errors"
```
Expected: **零命中**。若有命中，按报错指出的字段名修正对应 JSON。

- [ ] **Step 7: 落盘检查（代替 commit）**

Run: `Get-ChildItem src/main/resources/data/minecraft/tags/damage_type/, src/main/resources/data/beloong/damage_type/ | Select-Object Name`
Expected: 三个新文件都在

---

### Task 2: 龙卷风实体（注册 + 移动 + 吸引 + 伤害）

服务端玩法核心。做完这个任务，实体已经能通过 `/summon` 完整工作，客户端还看不见（渲染在 Task 4）。

**Files:**
- Create: `src/main/java/com/zonlong/beloong/registry/ModEntities.java`
- Create: `src/main/java/com/zonlong/beloong/entity/TornadoEntity.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCore.java`（构造函数注册区）

**Interfaces:**
- Consumes: 伤害类型 `beloong:tornado`（Task 1）
- Produces:
  - `ModEntities.ENTITIES` : `DeferredRegister<EntityType<?>>`
  - `ModEntities.TORNADO` : `DeferredHolder<EntityType<?>, EntityType<TornadoEntity>>`
  - `ModEntities.register(IEventBus)`
  - `TornadoEntity.TORNADO_DAMAGE` : `ResourceKey<DamageType>`
  - `TornadoEntity(EntityType<TornadoEntity> type, Level level)`
  - `TornadoEntity(EntityType<TornadoEntity> type, Level level, LivingEntity owner, float damagePerTick, double pullRadius, double damageRadius, double pullStrength, int lifetime)`
  - 实体 ID `beloong:tornado`

- [ ] **Step 1: 新建实体注册类**

写入 `src/main/java/com/zonlong/beloong/registry/ModEntities.java`：

```java
package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.entity.TornadoEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 化龙核心模组的实体注册中心。
 * <p>
 * 注册的实体：
 * <ul>
 *   <li>{@link #TORNADO} — 龙卷风（{@code beloong:tornado}），由龙技能「龙卷风」发射</li>
 * </ul>
 *
 * @see BeLoongCore
 * @see TornadoEntity
 */
public final class ModEntities {

    private ModEntities() {}

    /** 实体类型延迟注册器 */
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BeLoongCore.MODID);

    /**
     * 龙卷风实体。
     * <p>
     * 碰撞箱设为 3x 模型的视觉包围盒（约 2.8 格宽 × 3.3 格高）；
     * {@code updateInterval(1)} 让服务端每 tick 都下发位置包——因为本实体是
     * {@link net.minecraft.world.entity.projectile.Projectile} 的子类，
     * 客户端侧 {@code Entity#lerpTo} 是硬吸附而非插值，客户端不自己跑位移。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<TornadoEntity>> TORNADO =
            ENTITIES.register("tornado", () -> EntityType.Builder
                    .<TornadoEntity>of(TornadoEntity::new, MobCategory.MISC)
                    .sized(2.8F, 3.3F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .build("beloong:tornado"));

    /** 将实体注册到 Mod 事件总线。在 {@link BeLoongCore} 构造函数中调用。 */
    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
```

- [ ] **Step 2: 新建龙卷风实体**

写入 `src/main/java/com/zonlong/beloong/entity/TornadoEntity.java`：

```java
package com.zonlong.beloong.entity;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.DSDataAttachments;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.SummonData;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 龙卷风实体：匀速直线飞行，持续把周围敌人吸向中心，并每 tick 结算一次伤害。
 * <p>
 * <b>关键设计：位移与玩法逻辑只在服务端跑。</b>
 * 本类继承 {@link Projectile}（不是 {@code LivingEntity}），因此客户端侧的
 * {@code Entity#lerpTo} 是硬吸附（{@code setPos}）而不是插值——客户端位置完全由
 * 服务端的 {@code ClientboundMoveEntityPacket} 决定。若客户端再自己
 * {@code setPos(position().add(getDeltaMovement()))}，两者会互相覆盖，表现为抖动 + 速度翻倍。
 * 渲染所需的平滑由原版 {@code partialTick} 插值（{@code xOld} → {@code getX()}）提供。
 * <p>
 * <b>「每 tick 伤害、无视攻击冷却」靠伤害类型 tag 实现</b>，不是 hack {@code invulnerableTime}：
 * {@code minecraft:bypasses_cooldown} 让 {@code LivingEntity#hurt} 跳过
 * {@code invulnerableTime > 10.0F} 的差额分支，每 tick 都走全额伤害；
 * {@code minecraft:no_knockback} 则压掉击退，避免和吸引力互相顶。
 */
public class TornadoEntity extends Projectile {

    /** 自定义伤害类型 {@code beloong:tornado}。 */
    public static final ResourceKey<DamageType> TORNADO_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tornado"));

    /** 水平阻尼：每 tick 保留多少已有水平速度，防止越吸越快 */
    private static final double PULL_DAMPING = 0.82D;
    /** 近中心限速系数：允许的最大水平速度 = pullStrength * 该系数 * min(1, 距离/2) */
    private static final double PULL_SPEED_FACTOR = 3.0D;
    /** 每 tick 附加的上卷速度 */
    private static final double PULL_LIFT = 0.055D;
    /** 上卷速度下限 */
    private static final double LIFT_MIN = -0.6D;
    /** 上卷速度上限 */
    private static final double LIFT_MAX = 0.45D;
    /** 候选收集范围相对实体位置的下探距离（实体悬空约 1.1 格，下探 1 格可覆盖到地面） */
    private static final double AREA_DOWN = 1.0D;
    /** 候选收集范围相对实体位置的上探距离 */
    private static final double AREA_UP = 4.0D;
    /** 低于世界最低建筑高度这么多格就自行销毁 */
    private static final int VOID_MARGIN = 8;

    private float damagePerTick;
    private double pullRadius;
    private double damageRadius;
    private double pullStrength;
    private int life;

    /** EntityFactory 与反序列化用。 */
    public TornadoEntity(EntityType<TornadoEntity> type, Level level) {
        super(type, level);
    }

    /** 技能发射用。 */
    public TornadoEntity(EntityType<TornadoEntity> type, Level level, LivingEntity owner,
                         float damagePerTick, double pullRadius, double damageRadius,
                         double pullStrength, int lifetime) {
        this(type, level);
        this.setOwner(owner);
        this.damagePerTick = damagePerTick;
        this.pullRadius = pullRadius;
        this.damageRadius = damageRadius;
        this.pullStrength = pullStrength;
        this.life = lifetime;
    }

    /**
     * 本实体不同步任何自定义数据：客户端渲染只需要位置与 {@code tickCount}，原版自动同步。
     * <p>
     * 必须实现——{@code Entity} 把它声明为 {@code protected abstract}，而 {@link Projectile} 没有实现。
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // 无自定义同步数据
    }

    @Override
    public void tick() {
        super.tick();

        // 客户端什么都不做：位置完全由服务端的位置包决定（见类注释）
        if (this.level().isClientSide()) {
            return;
        }

        if (--this.life <= 0) {
            this.discard();
            return;
        }

        // 匀速直线穿透：不调用 move()，所以不与方块碰撞
        this.setPos(this.position().add(this.getDeltaMovement()));

        if (this.getY() < this.level().getMinBuildHeight() - VOID_MARGIN) {
            this.discard();
            return;
        }

        this.applyPullAndDamage();
    }

    /** 收集范围内的候选目标，按 3D 距离分别决定吸引与伤害。 */
    private void applyPullAndDamage() {
        double radius = this.pullRadius;
        AABB area = new AABB(
                this.getX() - radius, this.getY() - AREA_DOWN, this.getZ() - radius,
                this.getX() + radius, this.getY() + AREA_UP, this.getZ() + radius);

        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, area, this::canAffect)) {
            double distance = Math.sqrt(target.distanceToSqr(this));
            if (distance <= this.pullRadius) {
                this.pull(target);
            }
            if (distance <= this.damageRadius) {
                this.hurtTarget(target);
            }
        }
    }

    /**
     * 把目标向中心拉。
     * <p>
     * 朴素的「每 tick 朝中心加力」会越吸越快，然后高速穿过中心来回抖动。
     * 这里用<b>阻尼 + 近中心限速</b>：先保留 82% 的既有水平速度，再加一个恒定牵引，
     * 最后按「离中心越近、允许速度越小」封顶。
     */
    private void pull(LivingEntity target) {
        Vec3 toCenter = new Vec3(this.getX() - target.getX(), 0.0D, this.getZ() - target.getZ());
        double distance = toCenter.length();
        if (distance <= 0.05D) {
            return;
        }

        Vec3 direction = toCenter.scale(1.0D / distance);
        Vec3 current = target.getDeltaMovement();

        Vec3 horizontal = new Vec3(current.x, 0.0D, current.z)
                .scale(PULL_DAMPING)
                .add(direction.scale(this.pullStrength));

        double maxSpeed = this.pullStrength * PULL_SPEED_FACTOR * Math.min(1.0D, distance / 2.0D);
        if (horizontal.length() > maxSpeed) {
            horizontal = horizontal.normalize().scale(maxSpeed);
        }

        double vertical = Mth.clamp(current.y * 0.85D + PULL_LIFT, LIFT_MIN, LIFT_MAX);
        target.setDeltaMovement(horizontal.x, vertical, horizontal.z);
        // 必须：把服务端设的速度下发给客户端，否则客户端不会应用这个速度
        target.hurtMarked = true;
    }

    private void hurtTarget(LivingEntity target) {
        Holder<DamageType> type = this.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(TORNADO_DAMAGE);
        // 直接实体 = 龙卷风，间接实体 = 施法者
        target.hurt(new DamageSource(type, this, this.getOwner()), this.damagePerTick);
    }

    /**
     * 判断目标是否应该被吸引/伤害。
     * <p>
     * 排除：施法者本人、同队成员、主人的宠物、Dragon Survival 召唤物、创造模式玩家、
     * 已死亡或旁观者。
     */
    private boolean canAffect(LivingEntity target) {
        if (!target.isAlive() || target.isSpectator()) {
            return false;
        }
        if (target instanceof Player player && player.isCreative()) {
            return false;
        }

        Entity owner = this.getOwner();
        if (owner == null) {
            return true;
        }
        if (target == owner) {
            return false;
        }
        // Entity#isAlliedTo 只看记分板队伍
        if (owner.isAlliedTo(target)) {
            return false;
        }
        // 原版宠物
        if (target instanceof OwnableEntity ownable && owner.getUUID().equals(ownable.getOwnerUUID())) {
            return false;
        }
        // Dragon Survival 召唤物。必须用 getExistingDataOrNull：getData 在附件缺失时会
        // 创建并挂上默认 SummonData，而它是 serializable 的，会给每个被扫到的生物写 NBT。
        SummonData summon = target.getExistingDataOrNull(DSDataAttachments.SUMMON.get());
        return summon == null || !summon.isOwner(owner);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putFloat("DamagePerTick", this.damagePerTick);
        compound.putDouble("PullRadius", this.pullRadius);
        compound.putDouble("DamageRadius", this.damageRadius);
        compound.putDouble("PullStrength", this.pullStrength);
        compound.putInt("Life", this.life);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.damagePerTick = compound.getFloat("DamagePerTick");
        this.pullRadius = compound.getDouble("PullRadius");
        this.damageRadius = compound.getDouble("DamageRadius");
        this.pullStrength = compound.getDouble("PullStrength");
        this.life = compound.getInt("Life");
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        double size = this.getBoundingBox().getSize() * 4.0D;
        if (Double.isNaN(size)) {
            size = 4.0D;
        }
        size *= 64.0D;
        return distanceSqr < size * size;
    }
}
```

- [ ] **Step 3: 接到主类**

在 `src/main/java/com/zonlong/beloong/BeLoongCore.java` 里：

1. 在 import 区加一行（放在其它 `com.zonlong.beloong.registry.*` import 旁边）：

```java
import com.zonlong.beloong.registry.ModEntities;
```

2. 在构造函数 `// === 注册阶段 ===` 区块里，`ModBlocks.register(modEventBus);` 那行**之后**插入：

```java
        ModEntities.register(modEventBus);           // 实体
```

- [ ] **Step 4: 编译**

Run: `.\gradlew.bat build`

Expected: `BUILD SUCCESSFUL`。若报 `defineSynchedData` 未实现、`Projectile` 构造函数不存在、或 `getExistingDataOrNull` 找不到，说明 API 与 spec 记录的不符，回来核对 spec 的「已验证的底层事实」表。

- [ ] **Step 5: 注册表加载 + 实体存在性（需要用户跑游戏）**

Run: `.\gradlew.bat runClient`

进游戏后（需要 OP / 作弊），在**平坦空地**上执行：

```
/summon beloong:tornado ~ ~1 ~ {Life:200,Motion:[0.45d,0.0d,0.0d],DamagePerTick:0.0f,PullRadius:0.0D,DamageRadius:0.0D,PullStrength:0.0D}
```

Expected:
- Tab 补全能补出 `beloong:tornado`（补不出来 = 实体没注册上）
- 命令不报错，实体不可见是正常的（渲染在 Task 4）
- **它在动**：立刻连续执行两次
  ```
  /data get entity @e[type=beloong:tornado,limit=1] Pos
  ```
  两次的 `Pos` 应相差约 `0.45`（相隔 1 tick 时），且 X 递增 —— 这验证了 `Motion` 被 `readAdditionalSaveData` 之外的原版路径正确加载、且服务端位移逻辑生效
- **寿命生效**：换成 `{Life:40}` 重新 summon，约 2 秒后
  ```
  /data get entity @e[type=beloong:tornado,limit=1] Pos
  ```
  应报 `No entity was found`

- [ ] **Step 6: 吸引与伤害（需要用户跑游戏）**

在同一世界里：

```
/summon minecraft:zombie ~5 ~ ~ {NoAI:1b}
/summon beloong:tornado ~ ~1 ~ {Life:200,DamagePerTick:0.5f,PullRadius:8.0D,DamageRadius:8.0D,PullStrength:0.35D}
```

Expected:
- 僵尸被持续拉向龙卷风中心（约 1 秒内明显位移）
- 僵尸血条连续下降，**不是每 0.5 秒跳一次**（后者说明 `bypasses_cooldown` 没生效）
- 僵尸身体持续闪红，但不被推飞（后者说明 `no_knockback` 没生效）

再验证友军免疫：`/summon minecraft:wolf ~3 ~ ~ {Owner:<你的UUID>,Tame:1b}`（或用骨头驯服一只），重复上面步骤，狼不应被拉也不应掉血。

- [ ] **Step 7: 落盘检查（代替 commit）**

Run: `Get-ChildItem src/main/java/com/zonlong/beloong/entity, src/main/java/com/zonlong/beloong/registry | Select-Object Name`
Expected: `TornadoEntity.java` 与 `ModEntities.java` 都在

---

### Task 3: 青色龙卷风贴图

把灾变原图重上色为青色（色相 187°），保留二值 alpha 与全部颗粒。

**Files:**
- Create: `tools/recolor_tornado_texture.py`
- Create: `src/main/resources/assets/beloong/textures/entity/tornado.png`

**Interfaces:**
- Consumes: 无
- Produces: 贴图资源 `beloong:textures/entity/tornado.png`（128×128，供 Task 4 的渲染器使用）

- [ ] **Step 1: 写重上色脚本**

写入 `tools/recolor_tornado_texture.py`：

```python
# -*- coding: utf-8 -*-
"""把灾变 cataclysm:textures/entity/koboleton/sandstorm.png 重上色为青色，
输出 beloong:textures/entity/tornado.png。

用法：
    python tools/recolor_tornado_texture.py <源贴图> <输出贴图>

源贴图的 alpha 是二值的（只有 0 和 255），逐像素原样保留；
亮度驱动明度，色相固定，暗部饱和度更高以保住颗粒感。
"""
import sys

from PIL import Image

HUE = 187.0     # 目标色相
SAT_HI = 0.90   # 亮部饱和度
SAT_LO = 0.72   # 暗部饱和度
GAMMA = 0.9


def _hsv_to_rgb(h, s, v):
    if s <= 0.0:
        c = int(round(v * 255))
        return c, c, c
    h6 = (h % 1.0) * 6.0
    i = int(h6)
    f = h6 - i
    p = v * (1.0 - s)
    q = v * (1.0 - s * f)
    t = v * (1.0 - s * (1.0 - f))
    i %= 6
    rgb = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
    return tuple(int(round(c * 255)) for c in rgb)


def recolor(src, hue_deg=HUE):
    src = src.convert("RGBA")
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    src_px, dst_px = src.load(), out.load()
    hue = (hue_deg % 360.0) / 360.0
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = src_px[x, y]
            if a == 0:
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            value = min(1.0, lum ** GAMMA)
            sat = SAT_LO + (SAT_HI - SAT_LO) * (1.0 - value)
            rr, gg, bb = _hsv_to_rgb(hue, sat, value)
            dst_px[x, y] = (rr, gg, bb, a)
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        raise SystemExit(2)
    src = Image.open(sys.argv[1]).convert("RGBA")
    if src.size != (128, 128):
        print("警告：源贴图尺寸 %s，期望 (128, 128)" % (src.size,))
    recolor(src).save(sys.argv[2])
    print("已写出 %s" % sys.argv[2])


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 生成贴图**

Run:
```powershell
python tools/recolor_tornado_texture.py "D:\wdsjlzscsj\源代码\new1.20.1-1.21\src\main\resources\assets\cataclysm\textures\entity\koboleton\sandstorm.png" "src\main\resources\assets\beloong\textures\entity\tornado.png"
```
Expected: 打印 `已写出 src\main\resources\assets\beloong\textures\entity\tornado.png`，无尺寸警告

- [ ] **Step 3: 校验输出**

Run:
```powershell
python -c "from PIL import Image; im=Image.open(r'src/main/resources/assets/beloong/textures/entity/tornado.png').convert('RGBA'); d=list(im.getdata()); al=set(p[3] for p in d); op=[p for p in d if p[3]>0]; print('size',im.size); print('alphas',sorted(al)); print('opaque',len(op)); print('r range',min(p[0] for p in op),max(p[0] for p in op)); print('g range',min(p[1] for p in op),max(p[1] for p in op)); print('b range',min(p[2] for p in op),max(p[2] for p in op))"
```
Expected:
- `size (128, 128)`
- `alphas [0, 255]`（二值 alpha 被保留）
- `opaque 3030`（与源贴图相同）
- `g` 与 `b` 明显大于 `r`（青色）；`b` 应接近或等于 255

- [ ] **Step 4: 目视确认（需要用户）**

用图片查看器打开 `src/main/resources/assets/beloong/textures/entity/tornado.png`，确认是**青色、带颗粒、大片镂空**的沙尘纹理，与灾变原图只差颜色。

- [ ] **Step 5: 落盘检查（代替 commit）**

Run: `Get-ChildItem src/main/resources/assets/beloong/textures/entity/ | Select-Object Name, Length`
Expected: `tornado.png` 存在，约 2–4 KB

---

### Task 4: 客户端模型与渲染器

让龙卷风在客户端可见：复刻灾变四层方盒几何，青色贴图，3x 缩放。

**Files:**
- Create: `src/main/java/com/zonlong/beloong/client/model/TornadoModel.java`
- Create: `src/main/java/com/zonlong/beloong/client/TornadoRenderer.java`
- Modify: `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java`

**Interfaces:**
- Consumes: `ModEntities.TORNADO`（Task 2）、`beloong:textures/entity/tornado.png`（Task 3）、`TornadoEntity`
- Produces:
  - `TornadoModel.LAYER` : `ModelLayerLocation`
  - `TornadoModel.createBodyLayer()` : `LayerDefinition`
  - `TornadoModel.renderToBuffer(PoseStack, VertexConsumer, int, int)`
  - `TornadoModel.setupAnim(float ageInTicks)`
  - `TornadoRenderer(EntityRendererProvider.Context)`

- [ ] **Step 1: 新建模型类**

写入 `src/main/java/com/zonlong/beloong/client/model/TornadoModel.java`：

```java
package com.zonlong.beloong.client.model;

import com.zonlong.beloong.BeLoongCore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 龙卷风模型：四个从下往上逐层变宽的方盒，各自以不同角速度绕 Y 轴自转。
 * <p>
 * 全部枢轴与贴图偏移逐个照抄灾变 {@code Sandstorm_Projectile_Model}，
 * 贴图尺寸 128×128 也一致，因此 UV 布局直接对应得上。
 * <p>
 * <b>注意朝向</b>：模型空间 y 向下增大、y=24 是地面，所以 8 宽的 {@code storm}
 * （枢轴 y=20，盒子 16…24）在<b>底部</b>，30 宽的 {@code storm4}（枢轴 y=-7，盒子 -11…-3）
 * 在<b>顶部</b>——灾变原版是「窄底宽顶」的沙尘柱。
 * <p>
 * <b>不继承</b> {@code net.minecraft.client.model.Model}/{@code EntityModel}：
 * 1.21.1 的 {@code Model} 只有 renderType 与抽象 5 参 {@code renderToBuffer}，
 * 既不持有 {@code ModelPart} 也没有 {@code (ModelPart)} 构造函数。
 */
public class TornadoModel {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tornado"), "main");

    /** 各层绝对自转角速度（弧度 / tick），直接取自灾变的差速自转 */
    private static final float SPIN_STORM = 1.0F;
    private static final float SPIN_STORM2 = 0.5F;
    private static final float SPIN_STORM3 = 0.3F;
    private static final float SPIN_STORM4 = 0.6F;
    /** 链式摆动近似（灾变的 chainFlap） */
    private static final float SWAY_SPEED = 0.25F;
    private static final float SWAY_AMOUNT = 0.1F;

    private final ModelPart root;
    private final ModelPart storm;
    private final ModelPart storm2;
    private final ModelPart storm3;
    private final ModelPart storm4;

    public TornadoModel(ModelPart root) {
        this.root = root;
        this.storm = root.getChild("storm");
        this.storm2 = this.storm.getChild("storm2");
        this.storm3 = this.storm2.getChild("storm3");
        this.storm4 = this.storm3.getChild("storm4");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // 枢轴与 texOffs 逐个照抄灾变 Sandstorm_Projectile_Model
        PartDefinition storm = root.addOrReplaceChild("storm",
                CubeListBuilder.create().texOffs(65, 72).addBox(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F),
                PartPose.offset(0.0F, 20.0F, 0.0F));

        PartDefinition storm2 = storm.addOrReplaceChild("storm2",
                CubeListBuilder.create().texOffs(0, 72).addBox(-8.0F, -4.0F, -8.0F, 16.0F, 8.0F, 16.0F),
                PartPose.offset(0.0F, -9.0F, 0.0F));

        PartDefinition storm3 = storm2.addOrReplaceChild("storm3",
                CubeListBuilder.create().texOffs(0, 39).addBox(-12.0F, -4.0F, -12.0F, 24.0F, 8.0F, 24.0F),
                PartPose.offset(0.0F, -9.0F, 0.0F));

        storm3.addOrReplaceChild("storm4",
                CubeListBuilder.create().texOffs(0, 0).addBox(-15.0F, -4.0F, -15.0F, 30.0F, 8.0F, 30.0F),
                PartPose.offset(0.0F, -9.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    /**
     * 更新各层旋转。
     * <p>
     * vanilla {@code ModelPart.yRot} 会被父级累加，所以子层要写<b>差值</b>才能得到
     * 灾变的绝对转速 1.0 / 0.5 / 0.3 / 0.6 rad·tick⁻¹。
     *
     * @param ageInTicks 已对 3600 取模的动画时间，避免长时间累加导致浮点精度下降
     */
    public void setupAnim(float ageInTicks) {
        this.storm.yRot = SPIN_STORM * ageInTicks;
        this.storm2.yRot = (SPIN_STORM2 - SPIN_STORM) * ageInTicks;
        this.storm3.yRot = (SPIN_STORM3 - SPIN_STORM2) * ageInTicks;
        this.storm4.yRot = (SPIN_STORM4 - SPIN_STORM3) * ageInTicks;

        // 链式摆动近似：每层给一点相位偏移的俯仰抖动，避免看起来像四个死板的方盒
        this.storm.xRot = Mth.sin(ageInTicks * SWAY_SPEED) * SWAY_AMOUNT;
        this.storm2.xRot = Mth.sin(ageInTicks * SWAY_SPEED - 0.6F) * SWAY_AMOUNT;
        this.storm3.xRot = Mth.sin(ageInTicks * SWAY_SPEED - 1.2F) * SWAY_AMOUNT;
        this.storm4.xRot = Mth.sin(ageInTicks * SWAY_SPEED - 1.8F) * SWAY_AMOUNT;
    }

    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int packedLight, int packedOverlay) {
        this.root.render(pose, buffer, packedLight, packedOverlay);
    }
}
```

- [ ] **Step 2: 新建渲染器**

写入 `src/main/java/com/zonlong/beloong/client/TornadoRenderer.java`：

```java
package com.zonlong.beloong.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.model.TornadoModel;
import com.zonlong.beloong.entity.TornadoEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 龙卷风渲染器。
 * <p>
 * <b>缩放与锚点</b>：灾变原本是 {@code scale(-0.5,-0.5,0.5)} + {@code translate(0,-1.5,0)}。
 * <b>单位是关键</b>：模型空间 1 单位 = pose 栈 1/16 单位（{@code ModelPart#translateAndRotate} 与
 * {@code Cube#compile} 都对坐标做 /16），所以模型 y=24 的地面平面到达 pose 栈时是 <b>1.5</b>，
 * 平移量必须是 {@code -1.5}。<b>该平移是缩放无关的</b>：{@code v_world_y = S * (v_pose_y + t_y)}，
 * 底面 {@code v_pose_y = 1.5}，{@code t_y = -1.5} 对任意 S 都把它归零——所以灾变原版的 -1.5
 * 在 3x 下照抄即可，不会漂移。
 * <p>
 * <b>勘误</b>：本节初稿曾写 {@code translate(0,-24,0)}，理由是「照抄 -1.5 会被 scale 放大而漂移」。
 * 该理由与代码都是错的：{@code -24} 会把龙卷风放到实体上方 33.75 格（实测 v_world_y ∈ [33.75, 37.03]），
 * 而 {@code -1.5} 得到 [0.00, 3.28]，正是设计文档声称的 3.28 格高、底面锚在实体位置。
 * 由实施子代理以 {@code ModelPart}/{@code Cube} 一手源码发现，控制器三路复核确认。
 * <p>
 * <b>RenderType</b>：{@code entityCutoutNoCull}。贴图 alpha 是二值的（实测 3030 个不透明、
 * 13354 个全透明、无半透明），cutout 既保住镂空又不会进半透明排序队列；{@code NoCull}
 * 必须有——风柱是空心的，需要看到内壁。
 */
public class TornadoRenderer extends EntityRenderer<TornadoEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/tornado.png");

    /** 在灾变原尺寸基础上再放大的倍数 */
    private static final float MODEL_SCALE = 3.0F;
    /** 模型根枢轴 y，即模型空间的地面平面（模型空间单位，使用时需 /16 换算到 pose 栈空间） */
    private static final float MODEL_ROOT_Y = 24.0F;
    /** 模型空间到 pose 栈空间的比例：模型 1 单位 = pose 栈 1/16 单位 */
    private static final float MODEL_UNITS_PER_POSE_UNIT = 16.0F;
    /** 动画时间取模上限，避免长时间累加导致浮点精度下降 */
    private static final float ANIM_MODULO = 3600.0F;

    private final TornadoModel model;

    public TornadoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new TornadoModel(context.bakeLayer(TornadoModel.LAYER));
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(TornadoEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        pose.pushPose();
        // 【勘误后修正】模型空间 1 单位 = pose 栈 1/16 单位（ModelPart#translateAndRotate:149
        // 与 Cube#compile:358-360 都做 /16），所以模型 y=24 的地面平面到达 pose 栈时是 1.5。
        // 该平移缩放无关：v_world_y = S * (v_pose_y + t_y)，底面 v_pose_y = 1.5，
        // t_y = -1.5 对任意 S 都归零 —— 灾变原版的 -1.5 在 3x 下照抄即可。
        pose.scale(-0.5F * MODEL_SCALE, -0.5F * MODEL_SCALE, 0.5F * MODEL_SCALE);
        pose.translate(0.0F, -MODEL_ROOT_Y / MODEL_UNITS_PER_POSE_UNIT, 0.0F);

        float ageInTicks = (entity.tickCount + partialTick) % ANIM_MODULO;
        this.model.setupAnim(ageInTicks);

        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        this.model.renderToBuffer(pose, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);
        pose.popPose();

        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(TornadoEntity entity) {
        return TEXTURE;
    }
}
```

- [ ] **Step 3: 注册渲染器与模型层**

在 `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java` 里：

1. 追加 import（与已有的 `net.neoforged.neoforge.client.event.EntityRenderersEvent` import 合并，不要重复）：

```java
import com.zonlong.beloong.client.TornadoRenderer;
import com.zonlong.beloong.client.model.TornadoModel;
import com.zonlong.beloong.registry.ModEntities;
```

2. 在已有的 `registerRenderers(EntityRenderersEvent.RegisterRenderers event)` 方法**内部**，`registerBlockEntityRenderer(...)` 调用之后追加：

```java
        event.registerEntityRenderer(ModEntities.TORNADO.get(), TornadoRenderer::new);
```

3. 新增一个方法（放在 `registerRenderers` 旁边）：

```java
    /**
     * 注册实体模型层。
     * <p>
     * 必须在 {@link #registerRenderers} 之前或同时完成，否则
     * {@code context.bakeLayer(TornadoModel.LAYER)} 会抛
     * {@code IllegalArgumentException: Model layer ... not registered}。
     */
    @SubscribeEvent
    static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(TornadoModel.LAYER, TornadoModel::createBodyLayer);
    }
```

- [ ] **Step 4: 编译**

Run: `.\gradlew.bat build`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 目视验证（需要用户跑游戏）**

Run: `.\gradlew.bat runClient`（Java 改动必须重启客户端）

进游戏后：

```
/summon beloong:tornado ~ ~2 ~ {Life:100000,DamagePerTick:0.0f,PullRadius:0.0D,DamageRadius:0.0D,PullStrength:0.0D}
```

Expected（逐条核对）：
- 出现一个**青色**的、由下往上逐渐变宽的沙尘柱（窄底宽顶），不是沙黄色，也不是漏斗
- 四层以**不同速度**自转（顶层最快、第三层最慢），有明显层次感
- 尺寸约为一个方块宽的 2.8 倍、高约 3.3 格；底面悬空在实体位置
- 镂空处能透过去看到背景（cutout 生效）
- 绕到侧面看，能看到风柱内壁（NoCull 生效）

- [ ] **Step 6: 远端坐标复现**

在游戏内执行 `/tp 3000 70 3000`，再重复 Step 5 的 `/summon`。

Expected: 外观与 Step 5 完全一致（这条是为了排除绝对坐标相关的渲染 bug，见 `minecraft-rendering-pitfalls`）。

- [ ] **Step 7: 落盘检查（代替 commit）**

Run: `Get-ChildItem src/main/java/com/zonlong/beloong/client -Recurse -File | Select-Object Name`
Expected: `TornadoRenderer.java`、`TornadoModel.java` 都在

---

### Task 5: 技能效果类与注册

把实体接到 Dragon Survival 的能力系统上：自定义 `AbilityEntityEffect` + 注册。

**Files:**
- Create: `src/main/java/com/zonlong/beloong/ability/TornadoEffect.java`
- Modify: `src/main/java/com/zonlong/beloong/ability/AbilityEffectRegistry.java`

**Interfaces:**
- Consumes: `ModEntities.TORNADO`、`TornadoEntity` 构造函数（Task 2）
- Produces:
  - 效果类型 ID `beloong:tornado`
  - `TornadoEffect.CODEC` : `MapCodec<TornadoEffect>`
  - JSON 字段名：`damage_per_tick` / `lifetime` / `pull_radius` / `damage_radius` / `speed` / `pull_strength`（全部 `LevelBasedValue`，可写数字或对象）

- [ ] **Step 1: 新建效果类**

写入 `src/main/java/com/zonlong/beloong/ability/TornadoEffect.java`：

```java
package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zonlong.beloong.entity.TornadoEntity;
import com.zonlong.beloong.registry.ModEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 「龙卷风」技能效果：向施法者准星方向发射一个 {@link TornadoEntity}。
 * <p>
 * 与 {@code AirStrikeEffect} 同构：{@code record implements AbilityEntityEffect} + {@code MapCodec}，
 * 所有数值字段都是 {@link LevelBasedValue}，让 6 个玩法参数全部随技能等级缩放。
 * 伤害在<b>这里</b>乘上 {@code DSAttributes.DRAGON_ABILITY_DAMAGE}，实体本身不碰属性系统。
 */
public record TornadoEffect(
        LevelBasedValue damagePerTick,
        LevelBasedValue lifetime,
        LevelBasedValue pullRadius,
        LevelBasedValue damageRadius,
        LevelBasedValue speed,
        LevelBasedValue pullStrength
) implements AbilityEntityEffect {

    /** 同时支持纯数字和 LevelBasedValue 对象格式（与 AirStrikeEffect 一致） */
    private static final Codec<LevelBasedValue> FLEXIBLE_LBV = Codec.either(
            LevelBasedValue.CODEC,
            Codec.DOUBLE
    ).xmap(
            either -> either.map(lbv -> lbv, d -> LevelBasedValue.constant((float) (double) d)),
            Either::left
    );

    public static final MapCodec<TornadoEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FLEXIBLE_LBV.fieldOf("damage_per_tick").forGetter(TornadoEffect::damagePerTick),
            FLEXIBLE_LBV.fieldOf("lifetime").forGetter(TornadoEffect::lifetime),
            FLEXIBLE_LBV.fieldOf("pull_radius").forGetter(TornadoEffect::pullRadius),
            FLEXIBLE_LBV.fieldOf("damage_radius").forGetter(TornadoEffect::damageRadius),
            FLEXIBLE_LBV.fieldOf("speed").forGetter(TornadoEffect::speed),
            FLEXIBLE_LBV.fieldOf("pull_strength").forGetter(TornadoEffect::pullStrength)
    ).apply(instance, TornadoEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        int level = ability.level();

        double abilityScale = dragon.getAttributeValue(DSAttributes.DRAGON_ABILITY_DAMAGE);
        float damage = (float) (this.damagePerTick.calculate(level) * abilityScale);
        int life = (int) this.lifetime.calculate(level);
        double pullR = this.pullRadius.calculate(level);
        double damageR = this.damageRadius.calculate(level);
        double strength = this.pullStrength.calculate(level);
        float projectileSpeed = this.speed.calculate(level);

        Vec3 look = dragon.getLookAngle();
        // 发射高度照抄灾变：眼高 − 0.5。灾变原版把生成点偏到玩家侧面，那是它的 bug，不复刻。
        Vec3 spawn = new Vec3(
                dragon.getX() + look.x,
                dragon.getEyeY() - 0.5D,
                dragon.getZ() + look.z);

        TornadoEntity tornado = new TornadoEntity(
                ModEntities.TORNADO.get(), dragon.serverLevel(), dragon,
                damage, pullR, damageR, strength, life);
        tornado.setPos(spawn);
        tornado.setDeltaMovement(look.scale(projectileSpeed));
        dragon.serverLevel().addFreshEntity(tornado);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }

    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        // LevelBasedValue.Lookup 不接受 level 0（会索引 values.get(-1) 崩溃），
        // 未学习能力时按 1 级展示数值。
        int level = Math.max(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS, ability.level());
        return List.of(
                Component.translatable("dragon_ability.beloong.tornado.dynamic_desc",
                        String.format("%.1f", this.damagePerTick.calculate(level)),
                        String.format("%d", (int) this.lifetime.calculate(level)),
                        String.format("%.1f", this.pullRadius.calculate(level)),
                        String.format("%.1f", this.damageRadius.calculate(level))));
    }
}
```

- [ ] **Step 2: 注册效果类型**

在 `src/main/java/com/zonlong/beloong/ability/AbilityEffectRegistry.java` 的 `registerEntityEffects` 方法里，`air_strike` 的那次 `event.register(...)` **之后**追加：

```java
            event.register(AbilityEntityEffect.REGISTRY_KEY,
                    ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tornado"),
                    () -> TornadoEffect.CODEC);
```

已有的 import 不需要改动（`AbilityEntityEffect`、`BeLoongCore`、`ResourceLocation`、`RegisterEvent` 都已在用）。

- [ ] **Step 3: 编译**

Run: `.\gradlew.bat build`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 落盘检查（代替 commit）**

Run: `Select-String -Path src/main/java/com/zonlong/beloong/ability/AbilityEffectRegistry.java -Pattern "tornado"`
Expected: 命中 `"tornado"` 那一行

> 本任务的效果类型要到 Task 6 有了 `dragon_ability` JSON 之后才能在游戏里验证，届时一并测。

---

### Task 6: 技能定义、图标与文案

产出 `dragon_ability` JSON、6 张技能图标、技能名与描述文案，打通「按键释放」。

**Files:**
- Create: `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tornado.json`
- Create: `src/main/resources/assets/dragonsurvival/textures/gui/sprites/abilities/beloong/tornado_0.png` … `tornado_5.png`
- Create: `tools/make_tornado_icons.py`
- Modify: `src/main/resources/assets/beloong/lang/zh_cn.json`
- Modify: `src/main/resources/assets/beloong/lang/en_us.json`

**Interfaces:**
- Consumes: 效果类型 `beloong:tornado` 及 6 个字段名（Task 5）
- Produces: 技能 ID `dragonsurvival:tornado`；翻译键 `dragon_ability.dragonsurvival.tornado`、`dragon_ability.dragonsurvival.tornado.desc`、`dragon_ability.beloong.tornado.dynamic_desc`

- [ ] **Step 1: 写图标生成脚本**

写入 `tools/make_tornado_icons.py`：

```python
# -*- coding: utf-8 -*-
"""生成 Dragon Survival 技能图标：32x32 的青色龙卷风剪影，0~5 级共 6 张。

用法：
    python tools/make_tornado_icons.py <输出目录>

图标用「宽底窄顶」的经典漏斗形状（识别度优先——图标是符号，不是模型截图；
模型本身照抄灾变，是窄底宽顶）。级别越高：条纹越密、青色越亮。
"""
import os
import sys

from PIL import Image

SIZE = 32
TOP = 3          # 漏斗顶端所在行
BOTTOM = 29      # 漏斗底端所在行
MIN_HALF = 1.5   # 顶端半宽
MAX_HALF = 12.0  # 底端半宽
CENTER = 15.5    # 水平中心


def _cyan(value):
    """value 0~1 -> 青色。色相 187°。"""
    r = int(round(10 + 60 * value))
    g = int(round(120 + 120 * value))
    b = int(round(150 + 105 * value))
    return max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b))


def make_icon(level):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    # 级别越高，横向条纹越密（镂空感越强，视觉上更"快"）
    period = 5 - min(level, 4) // 2

    for y in range(TOP, BOTTOM + 1):
        t = (y - TOP) / float(BOTTOM - TOP)          # 0 = 顶端，1 = 底端
        half = MIN_HALF + (MAX_HALF - MIN_HALF) * (t ** 1.6)

        # 条纹：让图标有旋转的层次感
        if (y + level) % period == 0:
            continue

        value = 0.45 + 0.35 * t + 0.04 * level
        color = _cyan(min(1.0, value))

        x0 = int(round(CENTER - half))
        x1 = int(round(CENTER + half))
        for x in range(x0, x1 + 1):
            if 0 <= x < SIZE:
                px[x, y] = (*color, 255)

    return img


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    for level in range(6):
        path = os.path.join(out_dir, "tornado_%d.png" % level)
        make_icon(level).save(path)
        print("已写出 %s" % path)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 生成图标**

Run:
```powershell
python tools/make_tornado_icons.py "src\main\resources\assets\dragonsurvival\textures\gui\sprites\abilities\beloong"
```
Expected: 打印 6 行 `已写出 ...tornado_0.png` … `tornado_5.png`

- [ ] **Step 3: 图标目视确认（需要用户拍板）**

把 6 张图交给用户过目。若有异议，调整脚本里的 `TOP` / `BOTTOM` / `MIN_HALF` / `MAX_HALF` / `period` 后重跑 Step 2，直到用户确认。

- [ ] **Step 4: 新建技能定义**

写入 `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tornado.json`：

```json
{
  "actions": [
    {
      "target_selection": {
        "applied_effects": {
          "entity_effect": [
            {
              "effect_type": "beloong:tornado",
              "damage_per_tick": {
                "type": "minecraft:linear",
                "base": 0.5,
                "per_level_above_first": 0.25
              },
              "lifetime": {
                "type": "minecraft:linear",
                "base": 100,
                "per_level_above_first": 15
              },
              "pull_radius": {
                "type": "minecraft:linear",
                "base": 8,
                "per_level_above_first": 1
              },
              "damage_radius": {
                "type": "minecraft:linear",
                "base": 4,
                "per_level_above_first": 0.5
              },
              "speed": 0.45,
              "pull_strength": 0.35
            }
          ],
          "targeting_mode": "all"
        },
        "target_type": "dragonsurvival:self"
      }
    }
  ],
  "activation": {
    "activation_type": "dragonsurvival:simple",
    "animations": {
      "start_and_charging": {
        "animation_key": "spell_charge",
        "layer": "BREATH",
        "locks_neck": false,
        "locks_tail": false,
        "transition_length": 5
      }
    },
    "cast_time": 20.0,
    "cooldown": 200.0,
    "initial_mana_cost": 2.0,
    "sound": {
      "end": "minecraft:entity.breeze.wind_burst"
    }
  },
  "icon": {
    "texture_entries": [
      {
        "from_level": 0,
        "texture_resource": "dragonsurvival:abilities/beloong/tornado_0"
      },
      {
        "from_level": 1,
        "texture_resource": "dragonsurvival:abilities/beloong/tornado_1"
      },
      {
        "from_level": 2,
        "texture_resource": "dragonsurvival:abilities/beloong/tornado_2"
      },
      {
        "from_level": 3,
        "texture_resource": "dragonsurvival:abilities/beloong/tornado_3"
      },
      {
        "from_level": 4,
        "texture_resource": "dragonsurvival:abilities/beloong/tornado_4"
      },
      {
        "from_level": 5,
        "texture_resource": "dragonsurvival:abilities/beloong/tornado_5"
      }
    ]
  },
  "upgrade": {
    "experience_cost": {
      "type": "minecraft:lookup",
      "fallback": {
        "type": "minecraft:linear",
        "base": 100,
        "per_level_above_first": 100
      },
      "values": [
        1.0,
        10.0,
        100.0,
        1000.0,
        10000.0
      ]
    },
    "maximum_level": 5,
    "upgrade_type": "dragonsurvival:experience_points"
  }
}
```

注意：`targeting_mode` 是**必填**字段（`TargetingMode.CODEC.fieldOf`）。写 `"all"` 与 DS 自带 `fire_ball.json` 一致；`target_type: self` 时该字段不参与判定（`EntityTargeting.matches` 只校验 `target_conditions`）。

- [ ] **Step 5: 追加技能文案**

在 `src/main/resources/assets/beloong/lang/zh_cn.json` 里，`"death.attack.beloong.tornado.player"` 那一行**之后**插入：

```json
  "entity.beloong.tornado": "龙卷风",
  "dragon_ability.dragonsurvival.tornado": "龙卷风",
  "dragon_ability.dragonsurvival.tornado.desc": "§7■向准星方向发射一道§b龙卷风§r§7。\n\n■龙卷风会§b吸引§r§7周围的敌人，并§c每刻持续造成伤害§r§7（无视攻击冷却）。\n\n■冷却：§b10秒§r§7｜法力：§b2§r",
  "dragon_ability.beloong.tornado.dynamic_desc": "每刻伤害：§b%s§r§7\n持续时间：§b%s§r§7 刻\n吸引半径：§b%s§r§7 格\n伤害半径：§b%s§r§7 格",
```

在 `en_us.json` 的对应位置插入：

```json
  "entity.beloong.tornado": "Tornado",
  "dragon_ability.dragonsurvival.tornado": "Tornado",
  "dragon_ability.dragonsurvival.tornado.desc": "§7■ Fires a §btornado§r§7 in the direction you are looking.\n\n■ It §bpulls in§r§7 nearby enemies and deals §cdamage every tick§r§7, ignoring attack cooldown.\n\n■ Cooldown: §b10s§r§7 | Mana: §b2§r",
  "dragon_ability.beloong.tornado.dynamic_desc": "Damage per tick: §b%s§r§7\nDuration: §b%s§r§7 ticks\nPull radius: §b%s§r§7 blocks\nDamage radius: §b%s§r§7 blocks",
```

- [ ] **Step 6: 校验 JSON 语法**

Run:
```powershell
python -c "import json; [json.load(open(f,encoding='utf-8')) for f in ['src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tornado.json','src/main/resources/assets/beloong/lang/zh_cn.json','src/main/resources/assets/beloong/lang/en_us.json']]; print('JSON OK')"
```
Expected: `JSON OK`

- [ ] **Step 7: 注册表加载验证（需要用户跑游戏）**

Run: `.\gradlew.bat runClient`

进游戏后检查日志：

```powershell
Select-String -Path "run\client\logs\latest.log" -Pattern "Failed to parse|Registry loading errors"
```
Expected: **零命中**。常见报错与对策：
- `No key fallback in MapLike` → 某个 `minecraft:lookup` 少了 `fallback` 字段
- `Unknown registry element` / `not a registered effect` → 效果类型没注册上，回 Task 5 Step 2

- [ ] **Step 8: 落盘检查（代替 commit）**

Run: `Get-ChildItem src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/, "src/main/resources/assets/dragonsurvival/textures/gui/sprites/abilities/beloong" | Select-Object Name`
Expected: `tornado.json` 与 6 张 `tornado_N.png` 都在

---

### Task 7: 全链路验收

把技能真正发到玩家身上，跑一遍完整清单。

**Files:** 无新增/修改（纯验证）。若发现缺陷，回到对应任务修正。

**Interfaces:**
- Consumes: 前 6 个任务的全部产物
- Produces: 验收结论

- [ ] **Step 1: 编译并启动**

Run: `.\gradlew.bat build` 然后 `.\gradlew.bat runClient`

Expected: `BUILD SUCCESSFUL`，客户端正常进主菜单

- [ ] **Step 2: 授予技能**

由于本技能**不修改 species tag**，用 DS 指令直接授予。

先确认自己是龙（`/dragon-ability` 对非龙玩家会**静默跳过**并只报告处理了 0 个目标）。然后：

```
/dragon-ability add @s dragonsurvival:tornado
```

注意三点：
- 命令是 `/dragon-ability`（**一个** 带连字符的指令名），不是 `/dragon ability`
- 需要权限等级 2（`Commands.LEVEL_GAMEMASTERS`），单机需要开作弊/是 OP
- 参数是 `dragonsurvival:tornado`（能力 ID），Tab 补全应当能补出来

Expected: 反馈消息报告 `1 / 1`；技能出现在技能列表里，图标是青色龙卷风，鼠标悬停能看到描述里的 4 个动态数值

再查初始等级：
```
/dragon-ability query @s dragonsurvival:tornado level
```
Expected: `1`（本技能带 `upgrade`，`MagicData.addAbility` 会以 `DragonAbilityInstance.MIN_LEVEL` 授予，而不是满级）

- [ ] **Step 3: 释放与外观**

对准前方空地，按技能键释放。

Expected:
- 有 1 秒施法动作 + 风声音效
- 一道**青色**沙尘柱从准星方向飞出，匀速直线前进，约 5 秒后消失
- 四层以不同速度自转；窄底宽顶；悬空约 1.1 格
- 穿过地形与生物时不被阻挡

- [ ] **Step 4: 吸引**

```
/summon minecraft:zombie ~ ~ ~8 {NoAI:1b}
```
再朝它释放。

Expected: 僵尸被持续拉向龙卷风中心，最终被卷到风柱里

- [ ] **Step 5: 每 tick 伤害与无视冷却**

对同一只僵尸（`/summon minecraft:zombie ~ ~ ~5 {NoAI:1b,Health:200f}`）释放，观察血条。

Expected:
- 血条**连续**下降，不是每 0.5 秒跳一格
- 僵尸持续闪红
- 穿全套钻石甲的僵尸（`/summon minecraft:zombie ~ ~ ~5 {ArmorItems:[{id:"minecraft:diamond_boots",count:1},{id:"minecraft:diamond_leggings",count:1},{id:"minecraft:diamond_chestplate",count:1},{id:"minecraft:diamond_helmet",count:1}]}`）护甲耐久飞速下降

- [ ] **Step 6: 无击退**

Expected: 被命中的实体只被吸向中心，**不会**被向外推飞

- [ ] **Step 7: 友军与创造模式免疫**

逐条验证：
- 自己站在龙卷风路径上：不掉血、不被吸
- 同队玩家：不掉血、不被吸
- 用骨头驯服的狼：不掉血、不被吸
- DS 召唤物（若该龙种有召唤技能）：不掉血、不被吸
- 切创造模式后站在路径上：不掉血、不被吸

- [ ] **Step 8: 冷却与法力**

Expected: 释放后技能图标进入 10 秒冷却；法力扣 2 点

- [ ] **Step 9: 等级缩放**

没有直接设置等级的命令（`DragonAbilityCommand` 只有 `add` / `remove` / `refresh` / `query`），
只能走正常升级流程：

1. 临时把 `tornado.json` 的 `upgrade.experience_cost.values` 改成全 `0`（`[0.0, 0.0, 0.0, 0.0, 0.0]`），保留 `fallback`；游戏内 `/reload`
2. 在技能界面消耗龙经验把它升到 2 级
3. 用 `/dragon-ability query @s dragonsurvival:tornado level` 确认返回 `2`
4. 释放一次

Expected（L2 数值，与描述里的 4 个数字对照）：
- 每刻伤害 `0.75`（L1 是 0.5）
- 持续时间 `115` 刻（L1 是 100）
- 吸引半径 `9.0` 格（L1 是 8）
- 伤害半径 `4.5` 格（L1 是 4）
- 实机观感：龙卷风存在时间约 5.75 秒（L1 是 5 秒），吸引范围略大

验证完把 `experience_cost` 改回计划里的原值 `[1.0, 10.0, 100.0, 1000.0, 10000.0]`，再 `/reload`。

- [ ] **Step 10: 边界情况抽查**

- 朝天空释放：龙卷风飞远后在寿命结束前一直存在，不崩
- 朝脚下释放：不崩，实体正常生成
- 连续快速释放两次：`/dragon-ability remove @s dragonsurvival:tornado` 然后 `/dragon-ability add @s dragonsurvival:tornado` 可以把冷却清零（`addAbility` 会新建一个 cooldown=0 的实例），连做两次后应看到两个龙卷风各自独立结算
- 释放后立刻 `/kill @s` 或退出重进：不崩，且残留的龙卷风继续飞到寿命结束（`getOwner()` 返回 null 时筛选逻辑要能正确处理）
- 飞到未加载区块边界：不崩（实体被卸载后数值可恢复）

- [ ] **Step 11: 远端坐标复现渲染**

`/tp 3000 70 3000` 后重复 Step 3。

Expected: 外观与 Step 3 一致

- [ ] **Step 12: 最终编译产物**

Run: `.\gradlew.bat build` 然后 `Get-ChildItem build/libs | Select-Object Name, Length`

Expected: 生成 `beloong-<版本>.jar`；用解压工具确认 jar 内含：
- `com/zonlong/beloong/entity/TornadoEntity.class`
- `com/zonlong/beloong/client/TornadoRenderer.class`
- `assets/beloong/textures/entity/tornado.png`
- `data/dragonsurvival/dragonsurvival/dragon_ability/tornado.json`

---

## 附：执行顺序与依赖

```
Task 1 (伤害类型/tag) ──┐
                        ├─> Task 2 (实体) ──┐
Task 3 (青色贴图) ──────┼─> Task 4 (渲染)   ├─> Task 6 (技能定义) ─> Task 7 (验收)
                        └─> Task 5 (效果类) ─┘
```

- Task 1、3 之间无依赖，可以先做。
- Task 4 依赖 Task 2（实体类）与 Task 3（贴图）。
- Task 5 依赖 Task 2。
- Task 6 依赖 Task 5（效果类型）与 Task 3 建立的图标目录约定。
- Task 7 依赖全部。
