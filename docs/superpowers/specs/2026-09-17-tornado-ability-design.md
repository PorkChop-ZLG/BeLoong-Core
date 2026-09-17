# Design: 龙卷风（Tornado）龙技能

## Summary

为 Dragon Survival 新增一个主动龙技能 `dragonsurvival:tornado`（显示名「龙卷风」）：向准星方向发射一个自定义实体 `beloong:tornado`，该实体匀速直线飞行，持续把周围敌人吸向中心，并**每 tick 结算一次伤害且无视攻击冷却**。模型复用灾变（L_Ender's Cataclysm）`Sandstorm_Projectile_Model` 的四层方盒几何，贴图重上色为青色。

技能只产出本体，**不修改任何 species tag**——由整合包数据包决定分配给哪些龙种（与现有 `air_strike` / `tp_loong_palace` 一致）。

> ## 勘误（实施后修订，2026-09-17）
>
> 本文档定稿后有几处被后续审查与**用户裁决**改动。正文各处仍保留原始表述，**以下为权威口径**：
>
> 1. **伤害字段改名**：`damage_per_tick` → **`damage_per_hit`**，数值重标定为 **2.0 / 3.0 / 4.0 / 5.0 / 6.0**（`linear base 2.0, per_level_above_first 1.0`）。
> 2. **伤害频率**：由「每 tick 结算」改为**每 4 刻结算 1 次、每次 4 倍伤害**（用户裁决）。DPS 与旧方案**逐级相同**（L1 = 10/秒，L5 = 30/秒）——「无视攻击冷却」靠的是 `bypasses_cooldown` tag，与结算频率无关；改频率是为了消除每 tick 一次的受伤音效与 `ClientboundDamageEventPacket`（10 只生物约 200 次/秒）。**吸引仍然是每 tick**，只有伤害被分批。
> 3. **客户端位移**：§2 中「平滑由原版 `partialTick` 插值（`xOld`→`getX()`）提供」的说法**是错的** —— 位置包是硬吸附，且在 `setOldPosAndRot()` **之前**被处理，故 `xOld == getX()`，插值等于没做。现改为**客户端每 tick 外推一次位移 + 服务端每 tick 发布速度（`hurtMarked`）**；该外推不会震荡，因为服务端的权威位置恰好等于上一 tick 外推后的结果。
> 4. **`exhaustion`**：`0.1` → **`0.02`**（按新频率表达「≈0.1/秒」的原意图）。
> 5. **`experience_cost.values[0]`**：`1.0` → **`0.0`**，否则生存模式 0 经验下授予的技能不可用（`MIN_LEVEL = 0`，`isUsable()` 要求 `level > 0`）。
> 6. **技能图标**：Task 6 生成的 6 张为**占位图**，用户将自行绘制替换；JSON 路径无需改动。
> 7. **移动方式改为反弹（试玩反馈）**：用户实测后反馈「速度偏快、穿透方块手感不好」，故把「穿透方块」改为**撞方块镜面反弹**（`Entity#move` 逐轴碰撞检测 + 该轴速度取反，不衰减）；**生物仍可穿透**（否则无法把敌人吸到身上），也不设弹跳次数上限。这**推翻了决策 2 与决策 10 中关于穿墙的表述**。
>    - `speed` 与吸引力/半径经过多轮试玩下调与上调：`speed` **0.45 → 0.30 → 0.22 → 0.15**；`pull_strength` **0.35 → 0.45**；`pull_radius` 基准 **8 → 12**；`damage_radius` 基准 **4 → 6**。
>    - **本文档正文中的具体数值（数值表、JSON 片段、示例命令）都是设计初稿，已不代表当前值。**
>      **`data/dragonsurvival/dragonsurvival/dragon_ability/tornado.json` 是数值的唯一权威来源**，且这些值仍在试玩调参中（纯数据包字段，`/reload` 即可生效）。
> 8. **消散改为缓慢缩小（试玩反馈）**：寿命一到整根消失太突兀，改为**最后 20 刻（1 秒）线性缩放到 0**，服务端同时销毁，两端对齐。实现前提是客户端要知道剩余寿命，故**新增唯一一个同步字段 `DATA_INITIAL_LIFE`（生成时的初始寿命，一生只同步一次，跨区块重载时再同步一次）**，客户端用 `tickCount` 倒推。这**修订了 §2 中「本实体不同步任何自定义数据」「渲染器不得读取实体数值字段」的绝对表述** —— 现在渲染器可以读 `getRemainingLife()`，但伤害/半径/引力四个玩法字段仍然不同步、仍不得读取。**不做透明度渐隐**：贴图 alpha 二值，渐隐必须换成半透明 RenderType，会重新引入排序问题。
## 已确认的决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 技能获取 | 只做技能本体，不加 species tag（同 `air_strike`） |
| 2 | 移动与消亡 | **撞方块镜面反弹**（不衰减、不限次数）、**穿透生物**；寿命耗尽时先缩小再消散（见勘误 7 / 8）|
| 3 | 影响目标 | 除施法者 / 队友 / 宠物 / 召唤物外的所有活体；**创造模式玩家免疫** |
| 4 | 数值 | 见下表；等级上限 5，`experience_points` 升级 |
| 5 | 贴图色调 | 青色，色相 **187°** |
| 6 | 模型尺寸 | **3x** 缩放（约 2.8 格宽 × 3.28 格高） |
| 7 | 模型朝向 | **严格照抄灾变**：窄底宽顶（`storm` 8 宽贴地，`storm4` 30 宽在顶） |
| 8 | 发射高度 | **照抄灾变**：`眼高 − 0.5` 发射，模型底面锚在实体位置 |
| 9 | 击退 | 伤害类型加入 `no_knockback`，不被击退（避免和吸引互相顶） |
| 10 | 视线检测 | 不做。龙卷风**本身不再穿墙**（改为撞墙反弹，见决策 2），但吸引/伤害的 AoE 仍不做视线检测 |

### 数值表

| 字段 | L1 | L5 | 缩放 |
|---|---|---|---|
| `damage_per_tick` | 0.5 | 1.5 | `linear base 0.5, per_level 0.25` |
| `lifetime` | 100 | 160 | `linear base 100, per_level 15` |
| `pull_radius` | 12 | 16 | `linear base 12, per_level 1`（初稿 8→12，试玩后上调）|
| `damage_radius` | 6 | 8 | `linear base 6, per_level 0.5`（初稿 4→6，试玩后上调）|
| `speed` | 0.15 | 0.15 | 固定，**仍在试玩调参**（0.45→0.30→0.22→0.15）|
| `pull_strength` | 0.45 | 0.45 | 固定（初稿 0.35，试玩后上调）|

施法：`cast_time 20` / `cooldown 200` / `initial_mana_cost 2`。

## 已验证的底层事实

以下都从本机反编译的 1.21.1 源码读出来，不是推测。路径
`…\dabrdragonsurvivalcompat\前置源代码\DragonSurvival\build\neoForm\neoFormJoined1.21.1-20240808.144430\steps\unzipSources\unpacked\`。

| 事实 | 证据 |
|---|---|
| `bypasses_cooldown` 让每 tick 都走全额伤害分支 | `LivingEntity.hurt` 第 1190 行 `if ((float)this.invulnerableTime > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN))`，否则进 else 分支调 `actuallyHurt(source, amount)` |
| `no_knockback` 能压掉击退 | `LivingEntity.hurt` 第 1241 行 `if (!source.is(DamageTypeTags.NO_KNOCKBACK)) { … this.knockback(0.4F, d0, d1); }` |
| 两个 tag 常量都存在 | `DamageTypeTags.BYPASSES_COOLDOWN = create("bypasses_cooldown")`、`NO_KNOCKBACK = create("no_knockback")` |
| 模型坐标 y 向下增大、y=24 是地面 | `LivingEntityRenderer` 在 `scale(-1,-1,1)` 后 `translate(0,-1.501,0)`；交叉验证 `Deepling_Model`：root y=24、leg y→4、head y→−7（头比腿小 = 在上方） |
| 灾变龙卷风是窄底宽顶 | `Sandstorm_Projectile_Model`：`storm` 枢轴 y=20 → 盒子 16…24（贴地，8 宽）；`storm4` 枢轴 y=−7 → 盒子 −11…−3（最高，30 宽） |
| `Projectile.tick()` 会调 `super.tick()` | `Projectile.java` 第 122 行 → `Entity.tick()` → `baseTick()`，`tickCount` 正常自增 |
| **非 LivingEntity 的 `lerpTo` 是硬吸附** | `Entity.lerpTo` 第 2202 行 = `setPos` + `setRot`；`LivingEntity.lerpTo` 第 2971 行才做插值。故客户端**不能**自己跑位移 |
| 位置包每 tick 都会发 | `ServerEntity.sendChanges()`：`this.tickCount % this.updateInterval == 0 \|\| entity.hasImpulse \|\| getEntityData().isDirty()`，配 `updateInterval(1)` 即每 tick |
| `Projectile.isPickable()` 默认 false | `return this.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE)` |
| `Entity.hurtMarked` 是 public 字段 | `Entity.java`：`public boolean hurtMarked;` |
| `target_type: self` 时 `targeting_mode` 被忽略 | `AbilityTargeting.EntityTargeting.matches()` 只校验 `targetConditions`，不读 `targetingMode`；故 `air_strike.json` 的 `all_except_self` 不是 bug |
| 灾变贴图 alpha 是二值的 | 128×128 共 16384 像素：13354 个 alpha=0，3030 个 alpha=255，**没有半透明** |
| `EntityType.Builder` 签名 | `of(EntityFactory, MobCategory)` / `sized(w,h)` / `clientTrackingRange(int)` / `updateInterval(int)` / `noSummon()` / `fireImmune()` / `build(String)` |
| `Projectile` 只有一个构造函数 | `protected Projectile(EntityType<? extends Projectile>, Level)`，没有 `(type, owner, level)` 重载 |
| `Entity.defineSynchedData` 是抽象的，`Projectile` 未实现 | 子类必须实现，否则编译不过 |
| **1.21.1 的 `Model` 不含 `ModelPart`** | `net.minecraft.client.model.Model` 只有 `Model(Function<ResourceLocation, RenderType>)`、`renderType(...)`、抽象 5 参 `renderToBuffer(...)`、final 4 参 `renderToBuffer(...)`。`EntityModel` 也没有 `(ModelPart)` 构造函数。故 `TornadoModel` **不继承**它们，只持有 `ModelPart` 并自实现 `renderToBuffer` |
| `ModelPart.render` 有 4 参重载 | `render(PoseStack, VertexConsumer, int packedLight, int packedOverlay)` |
| 读 DS 召唤物附件要用 `getExistingDataOrNull` | `AttachmentHolder.getData` 在缺失时会**创建并写入**默认值（`SummonData` 是 serializable，会给每个被扫到的生物写 NBT）；`getExistingDataOrNull(AttachmentType)` 才是只读。`DSDataAttachments.SUMMON` 是 `DeferredHolder`，需 `.get()` |

## Components

### 1. ModEntities.java（新增）

**路径:** `src/main/java/com/zonlong/beloong/registry/ModEntities.java`

```java
public static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(Registries.ENTITY_TYPE, BeLoongCore.MODID);

public static final DeferredHolder<EntityType<?>, EntityType<TornadoEntity>> TORNADO =
        ENTITIES.register("tornado", () -> EntityType.Builder
                .<TornadoEntity>of(TornadoEntity::new, MobCategory.MISC)
                .sized(2.8F, 3.3F)          // 与 3x 视觉包围盒一致
                .clientTrackingRange(10)
                .updateInterval(1)          // 每 tick 同步位置，飞得才顺
                .fireImmune()
                .build("beloong:tornado"));
```

不调用 `noSummon()`，保留 `/summon` 便于调试（NBT 未给数值时寿命为 0，会立刻消散，属预期）。

### 2. TornadoEntity.java（新增）

**路径:** `src/main/java/com/zonlong/beloong/entity/TornadoEntity.java`

`extends net.minecraft.world.entity.projectile.Projectile`。字段：`damagePerTick / pullRadius / damageRadius / pullStrength / life`，全部由构造函数或 NBT 赋值，**不进 `SynchedEntityData`**（客户端渲染只需要位置和 `tickCount`，原版自动同步）。

**tick() 顺序（严格按此序，位移只在服务端）：**

```
1. super.tick()                                    // Projectile.tick 会 gameEvent + checkLeftOwner
2. if (level().isClientSide) return;               // 客户端什么都不做，位置完全由同步包决定
3. if (--life <= 0) { discard(); return; }
4. advance()                                       // 仅服务端：逐轴碰撞 + 镜面反弹
5. if (getY() < level().getMinBuildHeight() - 8) { discard(); return; }
6. applyPullAndDamage();
```

> **为什么客户端绝对不能自己跑位移**：`Entity.lerpTo`（第 2202 行）是
> `this.setPos(x, y, z); this.setRot(yRot, xRot);` —— 对**非 LivingEntity** 是**硬吸附**；
> 只有 `LivingEntity.lerpTo`（第 2971 行）才存 `lerpX/lerpY/lerpZ/lerpSteps` 做插值。
> `TornadoEntity extends Projectile`（不是 LivingEntity），所以客户端每 tick 会被位置包硬吸附。
> 若客户端不做同样的碰撞位移，服务端已经反弹回来时它还在沿直线外推 → 视觉上穿墙。
> （**勘误后**：客户端与服务端都调用 `advance()`，即同样的逐轴碰撞 + 反弹。）
> 客户端渲染的平滑由原版的 `partialTick` 插值（`xOld`→`getX()`）提供，不需要我们插手。

构造函数两个（`Projectile` **只有** `protected Projectile(EntityType<? extends Projectile>, Level)` 一个构造函数，没有 `(type, owner, level)` 版本，所以 owner 只能自己 `setOwner`）：

```java
public TornadoEntity(EntityType<TornadoEntity> type, Level level)      // EntityFactory + 反序列化
public TornadoEntity(EntityType<TornadoEntity> type, Level level,      // 技能发射
                     LivingEntity owner, float damagePerTick, double pullRadius,
                     double damageRadius, double pullStrength, int lifetime)
```

**必须实现 `defineSynchedData`**：`Entity` 把它声明为 `protected abstract`，而 `Projectile` 没有实现它——不写就编译不过。给个空实现即可（本实体不同步任何自定义数据）。

`addAdditionalSaveData` / `readAdditionalSaveData` 必须先 `super(...)`（`Projectile` 在里面存 `Owner` / `LeftOwner` / `HasBeenShot`）。

`applyPullAndDamage()`：

```java
double r = pullRadius;
AABB area = new AABB(getX()-r, getY()-1.0, getZ()-r,
                     getX()+r, getY()+4.0, getZ()+r);
for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, area, this::canAffect)) {
    double d = Math.sqrt(e.distanceToSqr(this));   // 3D 距离
    if (d <= pullRadius)   pull(e);
    if (d <= damageRadius) hurt(e);
}
```

上下范围固定 `[y−1.0, y+4.0]`（实体悬空约 1.1 格时覆盖到地面）。

**吸引（阻尼 + 近中心限速，避免越吸越快然后穿过中心抖动）：**

```java
Vec3 toCenter = new Vec3(getX()-e.getX(), 0, getZ()-e.getZ());
double d = toCenter.length();
if (d > 0.05) {
    Vec3 dir = toCenter.scale(1.0/d);
    Vec3 v = e.getDeltaMovement();
    Vec3 horiz = new Vec3(v.x, 0, v.z).scale(0.82).add(dir.scale(pullStrength));
    double maxSpeed = pullStrength * 3.0 * Math.min(1.0, d / 2.0);
    if (horiz.length() > maxSpeed) horiz = horiz.normalize().scale(maxSpeed);
    double vy = Mth.clamp(v.y * 0.85 + 0.055, -0.6, 0.45);
    e.setDeltaMovement(horiz.x, vy, horiz.z);
    e.hurtMarked = true;      // 必须：否则服务端设的速度不会下发，客户端会原地漂移
}
```

**伤害：**

```java
Holder<DamageType> type = level().registryAccess()
        .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(TORNADO_DAMAGE);
e.hurt(new DamageSource(type, this, getOwner()), damagePerTick);
```

靠 `bypasses_cooldown` 实现每 tick 全额，**不 hack `invulnerableTime`**。

**canAffect(LivingEntity e)：** 全部为 false 才纳入
1. `e == getOwner()`
2. `getOwner() != null && getOwner().isAlliedTo(e)` — 注意 `Entity.isAlliedTo` 只看记分板队伍
3. `e instanceof OwnableEntity own && getOwner() != null && getOwner().getUUID().equals(own.getOwnerUUID())`
4. `getOwner() != null && e.getExistingDataOrNull(DSDataAttachments.SUMMON.get()) instanceof SummonData s && s.isOwner(getOwner())` — DS 召唤物。**必须用 `getExistingDataOrNull`**：`getData` 在附件缺失时会创建并挂上默认 `SummonData`，而它是 serializable 的，会把 NBT 写进每一个被扫到的生物
5. `e instanceof Player p && p.isCreative()`
6. `!e.isAlive() || e.isSpectator()`

**序列化：** `addAdditionalSaveData` / `readAdditionalSaveData` 存 `damage_per_tick / pull_radius / damage_radius / pull_strength / life`，避免跨区块卸载后数值归零。

**其他覆写：** `getAddEntityPacket` 用默认即可；`isPickable()` 返回 false（与 `Projectile` 默认一致，显式写清意图）；`getPickRadius()` 不必覆写。

### 3. TornadoEffect.java（新增）

**路径:** `src/main/java/com/zonlong/beloong/ability/TornadoEffect.java`

与 `AirStrikeEffect` 同构：`record implements AbilityEntityEffect`，6 个 `LevelBasedValue` 字段，复用其 `FLEXIBLE_LBV` codec（`Codec.either(LevelBasedValue.CODEC, Codec.DOUBLE)`）让 JSON 既能写数字也能写对象。

`apply(ServerPlayer dragon, DragonAbilityInstance ability, Entity target)`：
1. `int level = ability.level()`
2. 计算 6 个数值；伤害再乘 `dragon.getAttributeValue(DSAttributes.DRAGON_ABILITY_DAMAGE)`
3. 发射位置 `Vec3 pos = new Vec3(dragon.getX() + look.x, dragon.getEyeY() - 0.5, dragon.getZ() + look.z)`，`look = dragon.getLookAngle()`
   （灾变原版把生成点偏到玩家侧面，那是它的 bug，不复刻）
4. `TornadoEntity t = new TornadoEntity(level, dragon, 各数值)`
5. `t.setPos(pos)`；`t.setDeltaMovement(look.scale(speed))`（**不调 `shootFromRotation`**，避免额外的散布/仰角处理）
6. `dragon.serverLevel().addFreshEntity(t)`

`getDescription` 返回 `dragon_ability.beloong.tornado.dynamic_desc`，照 `AirStrikeEffect` 用 `Math.max(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS, ability.level())` 兜住 0 级。

### 4. 注册（修改 3 个文件）

- `BeLoongCore.java`：构造函数加 `ModEntities.register(modEventBus);`
- `ability/AbilityEffectRegistry.java`：在现有 `RegisterEvent` 里加
  ```java
  event.register(AbilityEntityEffect.REGISTRY_KEY, BeLoongCore.res("tornado"), () -> TornadoEffect.CODEC);
  ```
- `BeLoongCoreClient.java`：加两个订阅方法
  ```java
  @SubscribeEvent
  static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
      event.registerEntityRenderer(ModEntities.TORNADO.get(), TornadoRenderer::new);
  }

  @SubscribeEvent
  static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
      event.registerLayerDefinition(TornadoModel.LAYER, TornadoModel::createBodyLayer);
  }
  ```

## 渲染

### 5. TornadoModel.java（新增）

**路径:** `src/main/java/com/zonlong/beloong/client/model/TornadoModel.java`

**不继承 `net.minecraft.client.model.Model` / `EntityModel`**——1.21.1 的 `Model` 只有 `renderType` 与抽象 5 参 `renderToBuffer`，既不持有 `ModelPart` 也没有 `(ModelPart)` 构造函数。这里就是一个普通类，持有 `ModelPart`，自己实现 `renderToBuffer`，RenderType 由渲染器直接给。

```java
public class TornadoModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tornado"), "main");

    private final ModelPart root;
    private final ModelPart storm, storm2, storm3, storm4;

    public TornadoModel(ModelPart root) {
        this.root = root;
        this.storm  = root.getChild("storm");
        this.storm2 = this.storm.getChild("storm2");
        this.storm3 = this.storm2.getChild("storm3");
        this.storm4 = this.storm3.getChild("storm4");
    }

    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay) {
        this.root.render(pose, buffer, light, overlay);   // ModelPart 的 4 参重载
    }
    // createBodyLayer() 与 setupAnim() 见下
}
```

public static LayerDefinition createBodyLayer() {
    MeshDefinition mesh = new MeshDefinition();
    PartDefinition root = mesh.getRoot();
    // 枢轴与贴图偏移逐个照抄灾变 Sandstorm_Projectile_Model
    PartDefinition storm  = root.addOrReplaceChild("storm",
        CubeListBuilder.create().texOffs(65, 72).addBox(-4, -4, -4, 8, 8, 8),     PartPose.offset(0, 20, 0));
    PartDefinition storm2 = storm .addOrReplaceChild("storm2",
        CubeListBuilder.create().texOffs(0, 72).addBox(-8, -4, -8, 16, 8, 16),    PartPose.offset(0, -9, 0));
    PartDefinition storm3 = storm2.addOrReplaceChild("storm3",
        CubeListBuilder.create().texOffs(0, 39).addBox(-12, -4, -12, 24, 8, 24),  PartPose.offset(0, -9, 0));
    PartDefinition storm4 = storm3.addOrReplaceChild("storm4",
        CubeListBuilder.create().texOffs(0, 0).addBox(-15, -4, -15, 30, 8, 30),   PartPose.offset(0, -9, 0));
    return LayerDefinition.create(mesh, 128, 128);
}
```

动画：vanilla `ModelPart.yRot` 会被父级累加，所以要写**差值**才能得到灾变的绝对转速（1.0 / 0.5 / 0.3 / 0.6 rad·tick⁻¹）：

```java
float a = ageInTicks;
storm .yRot =  1.0F * a;
storm2.yRot = -0.5F * a;    // 绝对 0.5a
storm3.yRot = -0.2F * a;    // 绝对 0.3a
storm4.yRot =  0.3F * a;    // 绝对 0.6a
```

灾变另有 `chainFlap` 链式摆动，用 `Mth.sin(a * 0.25F + 层相位) * 0.1F` 赋给各层 `xRot` 近似。`a` 取 `entity.tickCount % 3600` 避免长时间累加导致浮点精度下降。

### 6. TornadoRenderer.java（新增）

**路径:** `src/main/java/com/zonlong/beloong/client/TornadoRenderer.java`

```java
private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "textures/entity/tornado.png");
private static final float MODEL_SCALE = 3.0F;

public TornadoRenderer(EntityRendererProvider.Context ctx) {
    super(ctx);
    this.model = new TornadoModel(ctx.bakeLayer(TornadoModel.LAYER));
    this.shadowRadius = 0.0F;
}

@Override
public void render(TornadoEntity e, float yaw, float partial, PoseStack pose,
                   MultiBufferSource buf, int light) {
    pose.pushPose();
    // 【已修正】单位：模型空间 1 单位 = pose 栈 1/16 单位
    // （ModelPart#translateAndRotate:149 与 Cube#compile:358-360 都做 /16）。
    // 所以模型 y=24 的地面平面到达 pose 栈时是 1.5，平移量必须是 -1.5，而不是 -24。
    // 该平移是缩放无关的：v_world_y = S * (v_pose_y + t_y)，底面 v_pose_y = 1.5，
    // t_y = -1.5 对任意 S 都把它归零——所以灾变原版的 -1.5 在 3x 下照抄即可。
    pose.scale(-0.5F * MODEL_SCALE, -0.5F * MODEL_SCALE, 0.5F * MODEL_SCALE);
    pose.translate(0.0F, -MODEL_ROOT_Y / 16.0F, 0.0F);   // MODEL_ROOT_Y = 24.0F（模型空间的地面 y）
    this.model.setupAnim(e, e.tickCount + partial);
    this.model.renderToBuffer(pose,
            buf.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)),
            light, OverlayTexture.NO_OVERLAY);
    pose.popPose();
    super.render(e, yaw, partial, pose, buf, light);
}
```

> **勘误（实测修正）**：本节初稿写的是 `pose.translate(0.0F, -24.0F, 0.0F)`，并把理由写成「灾变的 -1.5 在 3x 下会漂移，所以改成按模型单位平移」。**这个理由是错的，代码也因此错了 16 倍**：`-24` 会把龙卷风放到实体上方 **33.75 格**处（实测 `v_world_y ∈ [33.75, 37.03]`），而 `-1.5` 得到 `[0.00, 3.28]`，正好等于本 spec 声称的「约 3.28 格高、底面锚在实体位置」。是实施子代理用 `ModelPart`/`Cube` 的一手源码发现的，控制器已独立复核三种方式。教训：**模型空间与 pose 栈空间差 16 倍，跨过这条边界时必须显式写单位换算**。

- **`entityCutoutNoCull`**：贴图 alpha 二值，cutout 保镂空且**不进半透明排序队列**；`NoCull` 必须带，风柱空心需要看到内壁。
- **锚点后果（需知晓）**：底面锚在实体位置 + `眼高 − 0.5` 发射，站立玩家（眼高≈1.62）时底面约在 **1.12 格**、顶面约 **4.40 格**——整根沙尘柱是悬空的。若要改成贴地，只需给 renderer 加一个 `MODEL_Y_OFFSET` 常数并调整第 3 步的发射高度。

### 7. 贴图与图标（新增资源）

| 文件 | 说明 |
|---|---|
| `assets/beloong/textures/entity/tornado.png` | 128×128，由 `tools/recolor_tornado_texture.py` 从灾变原图重上色（色相 187°，保留二值 alpha、保留全部颗粒） |
| `assets/dragonsurvival/textures/gui/sprites/abilities/beloong/tornado_0..5.png` | 32×32 技能图标 6 张（0–5 级），脚本生成青色漏斗剪影；级别越高条纹越密、青色越亮。图标用**宽底窄顶**漏斗（识别度优先，图标不是模型截图） |
| `tools/recolor_tornado_texture.py` | 一次性工具，与仓库已有的 `tools/extract_banner_texture.py` 惯例一致；打 jar 时不受影响 |

图标会在落盘前先渲出来给用户过目。

## 数据包

### 8. dragon_ability/tornado.json（新增）

**路径:** `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tornado.json`

```json
{
  "actions": [
    {
      "target_selection": {
        "applied_effects": {
          "entity_effect": [
            {
              "effect_type": "beloong:tornado",
              "damage_per_tick": { "type": "minecraft:linear", "base": 0.5,  "per_level_above_first": 0.25 },
              "lifetime":        { "type": "minecraft:linear", "base": 100,  "per_level_above_first": 15 },
              "pull_radius":     { "type": "minecraft:linear", "base": 8,    "per_level_above_first": 1 },
              "damage_radius":   { "type": "minecraft:linear", "base": 4,    "per_level_above_first": 0.5 },
              "speed": 0.30,
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
    "sound": { "end": "minecraft:entity.breeze.wind_burst" }
  },
  "icon": {
    "texture_entries": [
      { "from_level": 0, "texture_resource": "dragonsurvival:abilities/beloong/tornado_0" },
      { "from_level": 1, "texture_resource": "dragonsurvival:abilities/beloong/tornado_1" },
      { "from_level": 2, "texture_resource": "dragonsurvival:abilities/beloong/tornado_2" },
      { "from_level": 3, "texture_resource": "dragonsurvival:abilities/beloong/tornado_3" },
      { "from_level": 4, "texture_resource": "dragonsurvival:abilities/beloong/tornado_4" },
      { "from_level": 5, "texture_resource": "dragonsurvival:abilities/beloong/tornado_5" }
    ]
  },
  "upgrade": {
    "maximum_level": 5,
    "upgrade_type": "dragonsurvival:experience_points",
    "experience_cost": {
      "type": "minecraft:lookup",
      "fallback": { "type": "minecraft:linear", "base": 100, "per_level_above_first": 100 },
      "values": [1.0, 10.0, 100.0, 1000.0, 10000.0]
    }
  }
}
```

`targeting_mode` 是必填字段（`TargetingMode.CODEC.fieldOf`）。写 `"all"` 与 DS 自带 `fire_ball.json` 一致；`target_type: self` 时它不参与判定。

技能名与描述走 lang，不硬编码：

- `dragon_ability.dragonsurvival.tornado` = 「龙卷风」
- `dragon_ability.dragonsurvival.tornado.desc`
- `dragon_ability.beloong.tornado.dynamic_desc`（`getDescription` 用，4 个 `%s`：每 tick 伤害、寿命、吸引半径、伤害半径）

### 9. 伤害类型与 tag（新增 3 个文件）

`data/beloong/damage_type/tornado.json`：
```json
{
  "message_id": "beloong.tornado",
  "exhaustion": 0.1,
  "scaling": "when_caused_by_living_non_player",
  "effects": "hurt",
  "death_message_type": "default"
}
```

`data/minecraft/tags/damage_type/bypasses_cooldown.json`：
```json
{ "values": ["beloong:tornado"] }
```

`data/minecraft/tags/damage_type/no_knockback.json`：
```json
{ "values": ["beloong:tornado"] }
```

tag 在数据包之间是**合并**的，所以这两个文件只会往原版 tag 里追加 `beloong:tornado`，不会覆盖原版内容。

### 10. 语言文件（修改 2 个）

`assets/beloong/lang/zh_cn.json` + `en_us.json` 追加：

| key | zh_cn | en_us |
|---|---|---|
| `dragon_ability.dragonsurvival.tornado` | 龙卷风 | Tornado |
| `dragon_ability.dragonsurvival.tornado.desc` | 描述（含冷却/法力/机制说明） | ditto |
| `dragon_ability.beloong.tornado.dynamic_desc` | 每 tick 伤害 / 寿命 / 吸引半径 / 伤害半径 | ditto |
| `death.attack.beloong.tornado` | %s被龙卷风撕碎 | %s was shredded by a tornado |
| `death.attack.beloong.tornado.player` | %s在试图逃离%s时被龙卷风撕碎 | %s was shredded by a tornado whilst trying to escape %s |

## 边界情况

| 情况 | 处理 |
|---|---|
| 飞进未加载区块 | 实体随区块卸载；`readAdditionalSaveData` 恢复数值，`life` 继续走 |
| 施法者死亡 / 登出 | `getOwner()` 返回 null → `canAffect` 与 `DamageSource` 均判空；`new DamageSource(type, this, null)` 合法 |
| 目标在吸引过程中死亡 | `isAlive()` 已过滤，下一 tick 自然移除 |
| 掉进虚空 | `getY() < getMinBuildHeight() - 8` → discard |
| 连续放两个 | 两个独立实体各自结算，伤害叠加（可接受） |
| 末影人瞬移 | 每 tick 重算，会被反复拉回（预期行为） |
| 拉起的实体落地摔伤 | 不特殊处理，属预期副作用 |
| `/summon beloong:tornado` | 数值全为 0 → 寿命 0，下一 tick 消散（预期） |

## 验证

1. **编译**：`.\gradlew.bat build` 通过。
2. **注册表加载**：`.\gradlew.bat runClient`，然后检查 `run\client\logs\latest.log` 里
   `Failed to parse` 与 `Registry loading errors` **零命中**（沿用本项目既有的数据包验证手法）。
3. **游戏内实测清单**：
   - 用 `/dragon ability grant` 或临时给 species tag 后，按技能键释放 → 龙卷风向准星方向飞出
   - 外观：青色、4 层差速自转、约 2.8 格宽 / 3.28 格高、窄底宽顶、悬空
   - 吸引：`/summon minecraft:zombie ~5 ~ ~` 后释放 → 僵尸被持续拉向中心
   - 每 tick 伤害：僵尸血条连续下降；带甲僵尸护甲耐久快速消耗
   - 无击退：被命中的实体不会被推飞
   - 无视冷却：伤害数字/血条不是每 0.5 秒一跳
   - 友军免疫：驯服的狼、同队玩家、DS 召唤物不被吸不被伤害
   - 创造模式玩家免疫
   - 视线：龙卷风**本身撞墙反弹、不再穿墙**；但**吸引/伤害的 AoE 仍不做视线检测** —— 墙后 4 格内的僵尸照样会被吸（刻意保留）
4. **渲染**：按 `minecraft-rendering-pitfalls` 清单走一遍；额外在 `/tp 3000 70 3000` 处复现一次（实体渲染走标准管线，风险低但仍要测）。

## 明确不做（Out of scope）

- 不修改任何 `dragon_species` / species ability tag——技能分配交给整合包数据包
- 不做自定义网络包（客户端渲染所需信息原版已同步）
- 不做龙卷风的碰撞、破坏方块、音效循环、粒子拖尾
- 不修 `data/beloong/tags/damage_type/air_strike.json`——它定义了一个没人引用的孤儿 tag `#beloong:air_strike`（疑似路径写错，本意可能是 `minecraft:bypasses_cooldown`），但这与本次需求无关，仅记录
