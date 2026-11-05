# 霜噬假死修复 —— 实施计划

**日期：** 2026-11-05
**设计：** `2026-11-05-frostbite-death-sequence-design.md`
**目标产物：** `build/libs/beloong-<版本>.jar`（版本以 `gradle.properties` 为准）

## Task 1｜判定工具 `CustomDeathSequence`

- 新文件 `src/main/java/com/zonlong/beloong/compat/ironsspellbooks/CustomDeathSequence.java`
- `ownsDeathSequence(Class<?>)`：沿继承链找声明 `void tickDeath()` 的类，`!= LivingEntity.class` 即 true；`ClassValue` 缓存；异常返回 false。
- `logRestored(...)`：INFO 锚点，5 s 节流 + 累计计数。
- 验收：`compileJava` 通过；反射逻辑对 `VoidBlossomEntity`（覆写）与 `Zombie`（不覆写）分别返回 true / false。

## Task 2｜Mixin `FrostbiteDeathSequenceMixin`

- 新文件 `src/main/java/com/zonlong/beloong/mixin/ironsspellbooks/FrostbiteDeathSequenceMixin.java`
- `@Mixin(FrostbiteEffect.class, remap = false)`；`@Inject(method = "handleFrostbiteDeathEffects(Lnet/neoforged/neoforge/event/entity/living/LivingDeathEvent;)V", at = @At("TAIL"), require = 0, remap = false)`。
- 逻辑：`deathTime <= 0` 直接返回；`ownsDeathSequence(...)` 为真才置 0 并打锚点。
- 铁魔法是必需依赖 ⇒ 不需要 `@Pseudo`。
- 验收：`compileJava` 通过、0 警告。

## Task 3｜注册

- `src/main/resources/beloong.mixins.json` 的 `mixins` 数组加 `"ironsspellbooks.FrostbiteDeathSequenceMixin"`（放在 `block_factorys_bosses.*` 与 `legendarymonsters.*` 之间）。
- 验收：解析构建产物里的 json，条目存在。

## Task 4｜构建与产物复核

- `.\gradlew.bat build --rerun-tasks --no-build-cache --no-configuration-cache --offline`
- 解析（不字符串匹配）：jar 内 `beloong.mixins.json` 的 `mixins` 条数、新条目、
  `mixin/ironsspellbooks/FrostbiteDeathSequenceMixin.class` 与 `compat/ironsspellbooks/CustomDeathSequence.class` 是否在包内。

## Task 5｜启动门

- `runClient` → `debug.log` 找 `Mixing ironsspellbooks.FrostbiteDeathSequenceMixin … into io.redspace.ironsspellbooks.effect.FrostbiteEffect`；
  `run/crash-reports` 无新增。

## Task 6｜实机验收（委托方）

| # | 场景 | 期望 | 判据 |
|---|---|---|---|
| T1 | 自身霜噬 → 雪球术打虚空之花 → 领域期间击杀 | 冰雕照常出现；虚空之花 **70 tick 后正常消失** | `latest.log` 有 `[BeLoong] frostbite-death: restored custom death sequence for bosses_of_mass_destruction:void_blossom (deathTime 1000 -> 0 ...)`；尸体 NBT `DeathTime` 不再停在 4 位数 |
| T2 | 同上，但**不带霜噬** | 与修复前一致（正常死亡） | 无锚点行；Boss 正常消失 |
| T3 | 普通怪（如僵尸）被霜噬 + 冻结击杀 | 原行为不变：冰雕出现、原尸体立刻消失 | 无 `restored` 锚点（未覆写 `tickDeath` ⇒ 不干预） |
| T4 | 永恒星光 Boss（如 Permafrost）被霜噬 + 冻结击杀（可选） | 正常消失 | 锚点里出现 `eternalstarlight:*` |

## Task 7｜收尾回填

- 设计文档状态行改「已实施并实机验收」；
- 本计划补「执行状态」与"计划之外真实发生的事"；
- `memory/decisions-log.md` 追加一条。

## 计划之外（实施期实际发生）

- 构建时发现 `beloong-0.10.8.jar` 已存在（版本号由委托方/另一会话在 23:09 前升过），
  产物路径按 0.10.8 复核，而非设计文档写作时的 0.10.7。
- 首轮 `runClient` 日志里出现一条**与本次无关的上游竞态**：
  `legendary_monsters` 的 `ModEventBusClientEvents.onClientSetup:34` 在并行 `FMLClientSetupEvent` 里调
  `ItemProperties.register` ⇒ `HashMap.computeIfAbsent` 抛 `ConcurrentModificationException`。
  本次未复现到"跳过 beloong 的 sided setup"（`BeLoong Launch!` 与 `yuushya-declaration` 两行都在），
  但它属传奇怪物 2.2.4 的既有缺陷，若后续有人看到"某模组 client setup 被跳过"，先查这条。
- 存档里既有的三具假死尸体**不会被修复追溯**，仍需
  `/data modify entity @e[type=bosses_of_mass_destruction:void_blossom,limit=1,sort=nearest] DeathTime set value 69s`
  逐只清理（`/kill` 无效：`isDeadOrDying()` 下 `hurt()` 直接返回 false）。


---

## 执行状态（2026-11-06 收尾回填）

| Task | 状态 | 证据 |
|---|---|---|
| 1 判定工具 | ✅ | `util/CustomDeathSequence`（`ClassValue` 缓存 + 反射沿继承链找 `tickDeath`） |
| 2 Mixin | ✅ | `mixin/ironsspellbooks/FrostbiteDeathSequenceMixin`，`debug.log` 有 `Mixing … into …FrostbiteEffect` |
| 3 注册 | ✅ | `beloong.mixins.json` 条目在；jar 解析 `mixins=47` |
| 4 构建 | ✅ | `build --rerun-tasks --no-build-cache` exit 0、javac 警告 0 |
| 5 实机验收 | ✅ | 委托方：霜噬 + 雪球术 + 领域内击杀不再假死 |
| 6 收尾 | ✅ | 本文档 + 设计文档收尾节 + memory 回填 |

**计划之外真实发生的事**：
1. **委托方后来要求把这条修复做成 COMMON 开关**（便于对照测试），默认开。
   由另一会话按项目既有的 flat 风格落地为 `fixFrostbiteDeath`（不是设计初稿里的分节写法）；
   Mixin 每次死亡读一次开关。
2. 判定工具从 `compat/ironspellbooks` **上提到 `util`**（`/beloong cleandead` 原本也要用它；
   后来命令改为"只移除"不再需要它，但它仍是通用判据，位置不动）。
3. 配套的清理命令 `/beloong cleandead` 落地（见 `2026-11-05-clean-dead-command-*`）。
