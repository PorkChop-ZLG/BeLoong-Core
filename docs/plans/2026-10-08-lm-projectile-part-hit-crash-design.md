# 传奇怪物投射物"命中部件实体强转崩溃"修复设计

**日期：** 2026-10-08
**状态：** ✅ 已实施（静态与注入点已校验；实机验证清单见 §六）
**范围：** 传奇怪物（Legendary Monsters）2.2.x 投射物 `onHitEntity` 里"命中实体 → `LivingEntity`"的无保护强转
**产物：** `build/libs/beloong-0.10.2.jar`，`sha256 0E94DD52E3AB57C68D1789DAB8DB2317C21C0608D3E19EBF274BBBC9EFEC0675`

---

## 一、现象（两份实机崩溃日志）

两份日志是**同一处代码、不同"被命中实体"**（`Description: Ticking entity` → 服务端线程被 `ClassCastException` 打崩）：

| 日志 | 崩溃时刻 | 被强转的对象 |
|---|---|---|
| `传奇怪物与冰火传说不兼容崩溃.txt` | 2026-10-06 22:00:47（实体 id 37303） | `com.iafenvoy.iceandfire.entity.DragonPartEntity`（冰火 CE 龙部件） |
| `传奇怪物强制转化实体类型崩溃.txt` | 2026-10-08 12:19:07（实体 id 208292） | `io.redspace.ironsspellbooks.entity.spells.ShieldPart`（Iron's 护盾部件） |

共同栈顶：

```
java.lang.ClassCastException: class …DragonPartEntity/ShieldPart cannot be cast to class net.minecraft.world.entity.LivingEntity
  at legendary_monsters/…Projectile.SmallAnnihilationBombEntity.onHitEntity(SmallAnnihilationBombEntity.java:79)
  at minecraft/…projectile.Projectile.onHit(Projectile.java:208)
  at legendary_monsters/…SmallAnnihilationBombEntity.onHit(SmallAnnihilationBombEntity.java:90)
  at minecraft/…ThrowableProjectile.tick(ThrowableProjectile.java:46)
  at legendary_monsters/…SmallAnnihilationBombEntity.tick(SmallAnnihilationBombEntity.java:123)
```

## 二、根因（字节码级）

**2.2.3 线上 jar**（`javap -p -c`）`SmallAnnihilationBombEntity.onHitEntity(EntityHitResult)`：

```
16: aload_1 ; 17: invokevirtual EntityHitResult.getEntity ; 20: astore_2   // target = 命中实体
39-58: 只对 target 检查了 instanceof TamableAnimal                        // 与 LivingEntity 无关
86: aload_2
87: checkcast LivingEntity                                                // ← 无保护强转（崩点）
92: invokestatic MathUtils.entityBasedHpDamage(LivingEntity;F)F
```

**2.1.15 参考源码**（`开源模组参考文件\Legendary-Monsters-1.21.1-NeoForge\…\SmallAnnihilationBombEntity.java:69-83`）此处**没有强转**，只是
`target.hurt(ModDamageTypes.causeAnnihilationDamage(livingOwner, livingOwner), getDamage() * 倍率)`。

⇒ **这是 2.2.x 的回归**：为加"按目标最大生命值加伤"（`MathUtils.entityBasedHpDamage`）而引入 `(LivingEntity) target`。
而"打中部件"在 2.1.15 时代本来是安全的——部件自己的 `hurt` 会把伤害转给本体：

- 冰火 CE `MultipartPartEntity#hurt`（`:222-227`）→ `parent.hurt(source, damage * damageMultiplier)`；
- 原版/NeoForge `PartEntity` 同理（`EnderDragonPart.hurt` → 末影龙）。

**可达性：原版内容即可触发。** NeoForge 补丁源码 `EnderDragonPart extends PartEntity<EnderDragon>`、`isPickable() = true`
⇒ 打中末影龙翅膀/尾巴就崩；此外 LM **自家**部件（`TheObliteratorPart`、`ShulkerMimicPart`、`WitheredAbominationPartEntity`，均为 `PartEntity<…>`）同样能触发
（`SmallAnnihilationBombEntity` 只排除了 `TheObliteratorPart`）。所以这不是"冰火不兼容"，而是 LM 2.2.x 的普遍缺陷。

## 三、分诊：到底有多少处（"全修"的输入）

方法：对 2.2.3 全部 487 个 `entity/**` 顶层类做 `javap -p -c`，解析每个方法的指令流，
找出"**命中实体来源**（`EntityHitResult#getEntity()`，含 `astore_N` 后被 `aload_N` 取回两种形态）被 `checkcast LivingEntity`"的点，
再用**支配性判定**排除已被 `instanceof LivingEntity` 保护的点（守卫形如 `aload_N; instanceof; ifeq/ifne T`，且分支目标跨越该强转）。

结果（先只扫 `onHitEntity`，再放宽到**所有方法**，结论一致）：

| 判定 | 数量 | 说明 |
|---|---|---|
| 初筛含 `checkcast LivingEntity` 的 `onHitEntity` | 25 个类 | 多数是"对 **owner/施法者** 的强转"或已有守卫 |
| **真漏洞**（命中实体、无支配守卫） | **2 处 / 2 个类** | ① `SmallAnnihilationBombEntity` cast@87（= 两份日志的崩点）；② `ChorusEnergyBulletEntity` cast@19（`getEntity` 后**紧接着**强转，连中间变量都没有，2.1.15 源码 `:59` 同样是裸强转） |
| 其余 23 个类 | 0 | 命中实体的强转都有支配性 `instanceof LivingEntity` 守卫（例：`AnnihilationBombEntity` cast@126/140、`SoulJavelinEntity`/`SoulTridentEntity` cast@13、`ThrownPhantomDaggerEntity` cast@130 等） |

## 四、修复方案

**只注入传奇怪物自己的类**（用户裁定：不得改动原版或其它模组）。

| 文件 | 作用 |
|---|---|
| `compat/legendarymonsters/ProjectileHitGuard.java` | 共享判据：命中实体不是 `LivingEntity` ⇒ 返回 `true`（调用方取消），并打英文 ASCII 锚点 |
| `mixin/legendarymonsters/SmallAnnihilationBombEntityOnHitGuardMixin.java` | `@Inject(method = "onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V", at = HEAD, cancellable, remap = false, require = 0)` |
| `mixin/legendarymonsters/ChorusEnergyBulletEntityOnHitGuardMixin.java` | 同上（`chorus_energy_bullet`） |

处理函数（实例方法，与同包 `AnnihilationPursuerDamageCapMixin` 同形）：

```java
if (ProjectileHitGuard.shouldSkipNonLivingHit(result.getEntity(), "small_dimensional_bomb")) {
    ci.cancel();
}
```

**为什么取消整个 `onHitEntity` 是安全的**：原版 `Projectile#onHitEntity` 是**空实现**（补丁源码 `:233-234`），
且异常消失后 `Projectile#onHit` 会继续执行（粒子 + `discard()` + 音效）⇒ 投射物照常爆炸消失，**不会留下哑弹**。

**判据为什么与部件类型无关**：部件有三套互不兼容的 API（NeoForge `PartEntity`、冰火自家 `MultipartPartEntity`、
cerbons `EntityPart`），而 `ShieldPart` 的父实体 `AbstractShieldEntity extends Entity` **根本不是生物**
⇒ "换成父实体"既复杂又救不了它；"命中实体不是生物"这一条对所有部件/非生物实体都成立。

**日志与记账（2026-10-08 第二轮微调）**：只在**服务端**记账并打日志——客户端这次调用本来就会在 LM 的
`onHitEntity` 第二行 `if (level().isClientSide) return;` 立即返回，在渲染线程上做同步日志写入没有收益
（spark 报告已确认无热点，此处只是不做无谓工作）；两端仍然都取消，保持行为一致。
日志同时打出**命中实体的类名**，因为部件实体的注册名通常是**父实体**的类型：

```
[BeLoong] lm-projectile-hit-guard: projectile=small_dimensional_bomb hit=DragonPartEntity(id=iceandfire:ice_dragon) skipped=45
```

只打 `id=` 会把"打中龙翼部件"误读成"打中龙本身"，加上类名后一目了然。锚点按 5 秒节流。

**已接受的代价**：命中部件时不再造成伤害（相比 2.1.15 少一次伤害）——因为 `(LivingEntity) target` 与
`target.hurt(...)` 在同一个表达式里，无法只跳过前者而保留后者（除非复刻 LM 的伤害计算，见下）。

**被否方案**：
1. **换成父实体**（MixinExtras `@ModifyExpressionValue` 改 `getEntity()`）：需分支处理三套部件 API；会**绕过部件的 `damageMultiplier`**（冰火龙翼减伤失效）；对 Iron's 护盾无效（父实体非生物）；语义上等于我们替 LM 决定"伤害转本体"。
2. **跳过 + 补一次基础伤害**（复刻 2.1.15 的 `target.hurt(...)`）：要复刻 `ModDamageTypes` 与 `ModConfig.MOB_CONFIG` 倍率 ⇒ 与上游实现耦合，上游一改就漂移。
3. **让部件不可被投射物命中**（改 `PartEntity#isPickable`/`canBeHitByProjectile`）：影响全服所有投射物（玩家射龙翼直接穿过），越权过大，且违反"只改 LM"的约束。
4. **重定向 `EntityHitResult#getEntity()`**：全局方法，影响一切交互与其它模组，绝不可取。

**按用户裁定**：不加配置开关（默认生效）；不改 `mods.toml`（LM 仍是 optional 依赖，Mixin 保持 `@Pseudo` + `require = 0`）；
不发上游 issue（已有人报过同类问题）。

**已知并接受的残余风险**：LM 为可选依赖且用 `require = 0`，若上游改名/改签名，两个 `@Inject` 会**静默失效**（回到崩溃状态）。
缓解：`[BeLoong] lm-projectile-hit-guard` 锚点只在守卫真正触发时出现，可作为"守卫是否生效"的观测点。

## 五、验证

| 项 | 结果 |
|---|---|
| 注入点校验 | 临时把两个 Mixin 的 `require` 改成 `1` 后 `compileJava --rerun-tasks`：**BUILD SUCCESSFUL** ⇒ Mixin AP 对着 LM 2.2.3 jar 校验通过（名字/描述符/处理函数签名全对）；随后已改回 `require = 0` |
| 目标存在性 | `javap -p` 核对两个目标类都有 `protected void onHitEntity(net.minecraft.world.phys.EntityHitResult)` |
| 产物 | `build/libs/beloong-0.10.2.jar` 内含 3 个新类 + `beloong.mixins.json` 两条登记；`sha256 0E94DD52…` |
| 处理函数字节码 | `EntityHitResult.getEntity()` → `ProjectileHitGuard.shouldSkipNonLivingHit(Entity,String)` → `CallbackInfo.cancel()` |
| 编译告警 | 仅既有 3 条（`PossibleBiomesFilterMixin` / `ParameterListAccessor` 的 obfuscation mapping） |

## 六、实机验证结果（2026-10-08，整合包 `BeLoong 1.4`）

日志 `D:\AAA_testclient\.minecraft\versions\BeLoong 1.4\logs\latest.log`（16:35–16:45）：

```
16:40:18  projectile=small_dimensional_bomb hit=… skipped=1     ← LM 自家 ShulkerMimicPart
16:40:36  … skipped=2     16:41:43  … skipped=6     16:43:15  … skipped=18
16:44:20  … skipped=32    16:44:25  … skipped=45    16:44:43  … skipped=48
```

- **48 次命中 4 类部件实体（LM 幻影模仿者部件 + 冰火 雷/冰/火龙部件），全程零崩溃** ✓
- 原报告的两类触发实体（冰火 `DragonPartEntity`、Iron's `ShieldPart`）均已覆盖 ✓
- 期间两次 `Can't keep up`（2017 ms @16:40:19、2468 ms @16:41:46）经上下文核对均为
  **进世界加载**（`ModernFix: Time from main menu to in-game was 19.58 s`）与
  **首轮多维度存盘**（`Saving chunks for level …` 一串），与守卫无关（守卫每次只做一次 `instanceof`+计数）

**性能验证（客户端 spark 报告 `config\spark\profile-2026-10-08_16.45.38.sparkprofile`，工具 `tools/spark_profile_analyze.py`）**：

| 项 | 结果 |
|---|---|
| 总采样时长 | 240.91 s（遍历校验 ✓） |
| **BeLoong-Core 自身耗时** | **150 ms = 0.062%**（含子调用 1.48 s = 0.614%，**全部是既有 `worldgen.*` 群系重写**） |
| `ProjectileHitGuard` | **无匹配栈帧**（一次都没被采到） |
| 两个 `…OnHitGuardMixin` | **无匹配栈帧** |
| 日志写入热点 | 全报告搜 `log4j / Appender / PrintStream / FileOutputStream` **零命中** |
| 真实工作 Top 3 | `fastutil Int2ObjectOpenHashMap$MapIterator.nextEntry` 2.93 s、`PalettedContainer.get` 2.02 s、`DeferredHolder.value` 1.27 s（均为原版/库） |

⇒ **本修复不引入任何可测量的性能开销**。顺带：`ChunkMap$TrackedEntity.updatePlayer` 仅 310 ms（0.13%），
说明上一个特效实体上限修复也在持续生效（该循环正是原事故栈顶）。

## 七、相关文件

- `src/main/java/com/zonlong/beloong/compat/legendarymonsters/ProjectileHitGuard.java`
- `src/main/java/com/zonlong/beloong/mixin/legendarymonsters/SmallAnnihilationBombEntityOnHitGuardMixin.java`
- `src/main/java/com/zonlong/beloong/mixin/legendarymonsters/ChorusEnergyBulletEntityOnHitGuardMixin.java`
- `src/main/resources/beloong.mixins.json`（新增两条）
- 参考源码：`D:\Minecraft\开源模组参考文件\IceAndFire-CE\…\MultipartPartEntity.java`、`irons-spells-n-spellbooks\…\ShieldPart.java`、`irons-spells-n-spellbooks\…\AbstractShieldEntity.java`、NeoForge 补丁源码 `EnderDragonPart.java`
