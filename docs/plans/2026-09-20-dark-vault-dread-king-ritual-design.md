# 黯影宝库「死王仪式」（Dread King Ritual）设计文档

**日期：** 2026-09-20
**状态：** 已批准（用户确认）
**采用方案：** 方案 C —— mixin 精准注入 `unlock(...)` + 自定义标记实体（**1 个 mixin**）
**基线：** `disaster2` == `master` @ `bbc9f22`（版本 `0.9.6`，135 个 `.java`，39 条 mixin）
**依赖前提：** 龙之生存（`dragonsurvival:dark_vault` / `dark_key`）与铁魔法（`irons_spellbooks:dead_king`）
均已是 `required` 依赖（`build.gradle` 的 `implementation`）

---

## 一、问题陈述

### 需求

1. 玩家在结构 `dragonsurvival:dragon_hunters_castle` 内开启黯影宝库 `dragonsurvival:dark_vault` 时，
   在**宝库顶部**生成一个**玩家看不见的标记实体**。
2. 该实体有生命周期，期间播放音乐 `irons_spellbooks:entity.dead_king.music.intro`。
3. 生命周期结束（= 音乐长度）时，在自身位置召唤**不祥状态**的铁魔法 Boss 死者之王。

### 把设计推向当前形态的四条查证事实

| # | 事实 | 依据 | 对设计的影响 |
|---|---|---|---|
| 1 | **`dark_vault` 在原版结构里并不生成于城堡** —— 我把实际依赖 jar（`dragonsurvival-420799-8726322.jar`）内**全部 119 个结构 NBT** 解压逐一扫描，`dark_vault` 只出现在 `dragonsurvival:treasure_angry_{cave,forest,sea}` 的 **15 个拼图块**里；`dragon_hunters_castle` 的 21 个拼图块里**连一个宝库都没有** | jar 内 `data/dragonsurvival/structure/**` 全量扫描 | ⇒ **用户裁定：整合包会另行把黯影宝库放进城堡**。因此结构 ID 做成**配置项**：在整合包完成这一步之前，本功能是「装了但不触发」的静默状态 |
| 2 | **黯影宝库没有自定义类** —— 它就是原版 `VaultBlock` + `minecraft:vault` 方块实体；钥匙是 `dragonsurvival:dark_key`，战利品表 `dragonsurvival:generic/dark_vault`。龙之生存只用一个 mixin 放开了 key 校验 | `DSBlocks.java:1181-1191`（注册）、`:1143-1149`（默认 NBT）、`VaultBlockEntityServerMixin.java:21-28` | ⇒ 检测点只能落在**原版**宝库状态机上，不存在现成的模组回调 |
| 3 | **「开箱成功」在原版状态机里是一个可识别状态** —— `VaultState.UNLOCKING` 的**唯一**入口是私有方法 `unlock(...)`，而它全类**只有一处**调用：`tryInsertKey()` 的成功分支 | `VaultState.java:74-77`（UNLOCKING → EJECTING）、`VaultBlockEntity.java:289`（唯一调用点）、`:328-341`（`unlock` 定义） | ⇒ 存在一个语义精确的检测点（见决策 D1） |
| 4 | **包私有 API 把「零 mixin 精确判定」这条路堵死了一半** —— `VaultServerData.hasRewardedPlayer(Player)` 与 `getRewardedPlayers()` 都是**包私有**，`canEjectReward(...)` 是 **private**；只有 `VaultBlockEntity.Server.isValidToInsert(VaultConfig, ItemStack)` 是 public static | `VaultServerData.java:56,60`；`VaultBlockEntity.java:353,357` | ⇒ 事件侧**无法**判断「该玩家是否已经开过这个宝库」。而 `hunter_knight` 掉 `dark_key`（`loot_table/entities/hunter_knight.json`）⇒ 玩家**能囤钥匙** ⇒ 零 mixin 方案存在**可刷 Boss** 的漏洞（见「被否决的方案」） |

### 音乐长度的取证

需求量要求「实体生命周期 = 音乐长度」。两路独立取证，结论一致：

| 来源 | 证据 | 值 |
|---|---|---|
| 铁魔法源码常量 | `DeadKingMusicHandler.java:17` `INTRO_LENGTH_MILIS = 17600` | 17.600 s |
| 打包 jar 内 ogg 实测 | 解析 `assets/irons_spellbooks/sounds/dead_king/music/intro.ogg` 的 Ogg 页：采样率 44100、末页 granule 778368（32 个页，末页 type=4 = EOS） | 17.650 s |

⇒ 取 **352 tick**（17.6 s × 20），与铁魔法自己的常量对齐。

---

## 二、目标与非目标

### 目标

1. 在城堡内的黯影宝库被**真正开箱成功**时，于宝库顶部生成标记实体。
2. 标记实体播放 intro 音乐（周围所有玩家可闻），352 tick 后于自身位置召唤**不祥**死者之王。
3. 标记实体**绝不发送到客户端**（玩家看不见）。
4. 生命周期与中断语义可预测、可持久化。
5. 检测精度必须能区分「钥匙被接受」与「钥匙被拒绝」。
6. 仪式召唤的死王死后**不生成灵魂**（D20）—— 避免给后续探索者留下「这里有个可复活的死王」的误导；
   而其他途径（尸体复活）的死王灵魂行为**保持不变**。

### 非目标

- ❌ 不改龙之生存 / 铁魔法任何代码（只用它们的 public API 与方块状态）
- ❌ 不使用药水效果（**这是第一版设计，已被用户改为标记实体**；换来的是不再需要「跨 17.6 秒携带坐标」与处理牛奶/死亡/切维度/重登等边界）
- ❌ 不使用数据包（advancement/function）做检测 —— 理由见「被否决的方案 A」
- ❌ 不做「每个宝库只触发一次」的闸门（**用户裁定：不限制**，每个开启者各召一次）
- ❌ 不强制加载宝库区块（**用户裁定**：倒计时随区块/实体刻范围暂停与恢复）
- ❌ 不抑制铁魔法自己的 Boss 音乐（见第八节「已知行为」）
- ❌ 不处理 `structure` 的标签（tag）形式，只接受单个结构 ID

---

## 三、架构

### 分层

```
[检测层] mixin/minecraft/DreadKingRitualTriggerMixin
             │  在 unlock(...) 被调用的那一行判定「暗影钥匙被真正接受」
             │  产出 (ServerLevel, BlockPos, ServerPlayer)
             ▼
[编排层] dreadking/DreadKingRitualStarter
             │  开关 + 结构判定 + 计算刷怪点
             │  level.addFreshEntity(marker)
             ▼
[执行层] entity/DreadKingRitualMarker
             ├─ t=0    : 一次性广播音乐（落 musicPlayed = NBT）
             ├─ t<352  : tick 倒计时（lifeTicks 落 NBT）
             └─ t=352  : 在自身位置召唤不祥死者之王 → discard()
```

每层只对下一层许诺「一件事」，不窥探对方内部。因此：

- 检测层若将来换成事件实现，编排层与执行层**零改动**；
- 执行层不关心「谁触发的、为什么触发」，只认「我在哪、还剩几 tick」⇒ 可被 `/summon` 单独手测（第七节用例 1）。

### 变更清单

```
新增  src/main/java/com/zonlong/beloong/
      ├── mixin/minecraft/DreadKingRitualTriggerMixin.java  检测层（目标是**原版**类 ⇒ 按项目规范进 minecraft/ 分类）
      ├── dreadking/DreadKingRitualStarter.java              编排层
      └── entity/DreadKingRitualMarker.java                  执行层（entity/ 包已存在：TornadoEntity）

改动  src/main/java/com/zonlong/beloong/registry/ModEntities.java    +1 Holder
改动  src/main/resources/beloong.mixins.json                         mixins 数组 +1 条
改动  src/main/java/com/zonlong/beloong/Config.java                  SERVER_SPEC +[dread_king_ritual] 节
改动  src/main/resources/assets/beloong/lang/zh_cn.json              +1 键
改动  src/main/resources/assets/beloong/lang/en_us.json              +1 键

不动  BeLoongCore.java        —— ModEntities.register(modEventBus) 已在 L94
不动  BeLoongCoreClient.java  —— 标记实体永不发送客户端，不需要渲染器，也不需要模型层
不动  数据包                  —— 不需要任何 advancement / function / jukebox_song
```

### 决策记录

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| **D1** | 检测点 | 注入 `VaultBlockEntity$Server.tryInsertKey` 内 **`unlock(...)` 的 INVOKE 点**（`@At("HEAD")`） | ① `unlock()` 全类只有一处调用，且只在成功分支（`VaultBlockEntity.java:289`）② 三个需要的量 `level / pos / player` **都是 `tryInsertKey` 的形参**，无需 `@Shadow` ③ 被拒绝的路径（无效钥匙 / 已领奖）根本走不到这里 ⇒ 天然排除可刷 Boss 的漏洞 |
| **D2** | 标记实体 | `extends net.minecraft.world.entity.Marker`，`.sized(0.0F, 0.0F).clientTrackingRange(0)` | 白拿四件事：`getAddEntityPacket` **直接 throw** ⇒ 结构上不可能发到客户端；`noPhysics = true`；不可骑乘；`PushReaction.IGNORE`。取值与原版 `EntityType.MARKER` 逐字一致 |
| **D3** | 生命周期 | **352 tick 硬编码常量**，不做配置 | 它是音乐文件的**客观长度**（两路取证见第一节），不是玩法参数。做成配置只会制造「音乐与倒计时不同步」这一种错误用法 |
| **D4** | 音乐播放 | 生成瞬间 `Level.playSound(null, pos, IS_INTRO, SoundSource.RECORDS, musicVolume, 1.0F)` **广播一次**；`musicPlayed` 落 NBT | 在实体生命周期内**只播一次**。若区块重载/服务器重启后重放，会变成「音乐从头发、倒计时从中间接着走」，直接违背「时长 = 音乐长度」 |
| **D5** | 音乐受众与范围 | **周围所有玩家**（唱片机语义）；`musicVolume` 默认 **0.5** ⇒ 可闻半径 **16 格** | 用户裁定。**已知代价**：这一个旋钮同时管响度与送达半径，0.5 把半径从唱片机的 64 格压到 16 格（推导见第五节）。已确认接受 |
| **D6** | 不祥触发方式 | `boss.onOminousTrigger()` **直调** | 它是 `IOminousEntity` 的 public API（`IOminousEntity.java:12`），实现即 `setIsOminous(true)` + 6 项属性 modifier + `setBaseValue(1000)` + 满血（`DeadKingBoss.java:115-126`），与尸体复活路径**同一条码**，且重复调用幂等。**不走**「给玩家挂 Trial Omen」：那条路要求玩家非创造非旁观、24 格内、且只在实体加入那一瞬判定（`ServerPlayerEvents.java:705-738`），边界多且会污染玩家状态 |
| **D7** | 召唤序列 | 除 `setSpawnPos` 外逐字复刻 `DeadKingCorpseEntity.java:75-90` | ⚠️ **本条已被 D20 取代（2026-09-20）**。初版要求「**必须**含 `setSpawnPos(boss.position())`」，理由是 `tickDeath()` 只在 `spawnPos != null` 时生成灵体（`DeadKingBoss.java:706-718`），漏掉会让护命匣复活链静默断裂。用户随后改需求为「仪式死王不留灵魂」，于是**同一个失效模式从缺陷变成了设计意图** ⇒ 改为刻意不调。理由、代价与验证方式全部见 D20 |
| **D8** | 结构判定 | `ServerLevel.structureManager().getStructureWithPieceAt(pos, HolderSet)`，结构 ID 走配置，默认 `dragonsurvival:dragon_hunters_castle` | 与原版 `LocationPredicate` 内部同款语义（piece 级而非包围盒级，`LocationPredicate.java:22,54`）；配置化吸收事实 1 的风险 |
| **D9** | 实体注册位置 | 既有的 `registry/ModEntities.java` 加一个 `DeferredHolder` | 基线上 `beloong:tornado` 已是本项目第一个实体类型，有现成范式与 `register(IEventBus)` 约定可抄 |
| **D10** | 锚点 | 宝库顶 `vaultPos.above()`，含**向上净空扫描兜底** | 死王碰撞箱 **0.9 × 3.5 格**（`EntityRegistry.java:277-281`），城堡室内常有 3 格高的天花板。扫描上限 +8；扫不到则**照旧在宝库顶召唤**并打 WARN —— 忠于「出现在宝库顶部」这一需求，不擅自改位 |
| **D11** | 重复触发 | **不限制**，每个开启者各召一次 | 用户裁定。原版宝库本身按玩家各自发奖（`VaultServerData.rewardedPlayers`），因此同一宝库本就允许被多人各开一次 |
| **D12** | 区块卸载 | **不强制加载**；倒计时随区块/实体刻范围暂停与恢复 | 用户裁定。副作用：音乐已由客户端音效实例独立播放，不受暂停影响 ⇒ 可能形成「无声倒计时」（见第八节） |
| **D13** | 中断语义 | `/kill` 实体 = 取消，不召唤；服务器重启 / 玩家离线 / 切维度**均不影响**（实体不依赖玩家） | 用户确认。`Entity.shouldBeSaved()` 对普通实体返回 true（`Entity.java:3670`）⇒ 实体与 `lifeTicks` 自动随存档持久化 |
| **D14** | 双重 intro | **不处理**，视作设计内的渐强 | 铁魔法在 Boss 被玩家看到时会自己再播一遍同一段 intro（`DeadKingBoss.java:470-474` → `DeadKingMusicHandler.init()` → `addLayer(beginSound)`）。而 **IS 的音量是 1.0 且 `Attenuation.NONE`**（`FadeableSoundInstance.java:18,21`）⇒ 实际听感是「0.5× 的定位低语 → 死王登场 → 同一段音乐 1× 全屏响起」，正是渐强。抑制它需要 mixin 进铁魔法客户端，收益不抵成本 |
| **D15** | 命名前缀 | 全功能统一 `dread_king` 前缀（实体 ID / 类名 / 包名 / Mixin 名 / 配置节 / lang 键） | 用户要求"添加 `dread_king` 前缀用于区分"。只改实体会留下「`[vault_ritual]` 配置配 `DreadKingRitualMarker` 实体」的割裂，故一并统一。**注意是 `dread` 而非铁魔法的 `dead`** |
| **D16** | Mixin 包分类与 remap | 目标是**原版**类的 mixin 放 `mixin/minecraft/`；目标是第三方模组的放 `mixin/<该模组命名空间>/`；`beloong.mixins.json` 的条目写同名前缀（`"minecraft.DreadKingRitualTriggerMixin"`）。原版目标的注入**必须显式 `remap = false`**（`@Inject` 与其 `@At` 都要） | 用户裁定 —— 项目规范：**mixin 哪个模组就用哪个模组的命名空间分类**，便于一眼确认「这条在 mixin 原版」。`remap = false` 则是编译期硬约束：NeoForge 运行时即用 Mojang 官方名（无混淆），dev 命名空间 == 运行时命名空间；留默认 `true` 会让注解处理器**报错**（`Unable to locate obfuscation mapping for @Inject target`）而非警告。项目内先例：`PossibleBiomesFilterMixin`（原版 `BiomeSource`）、`CloneParameterListMixin`（TerraBlender）。现存 `mixin/` 根目录下仍有两条原版目标 mixin 未迁移（`PossibleBiomesFilterMixin` → `BiomeSource`、`ParameterListAccessor` → `Climate.ParameterList`），**本次不顺手重构**，避免把无关改动混进本功能 |
| **D17** | `tick()` 的语义 | **不调** `super.tick()`，也不调 `baseTick()`；只做「倒计时 + 播音 + 召唤」 | 有意继承 `Marker` 的「无环境处理」语义（`Marker.java:20-22`）。收益：免疫 `handlePortal()`（`Entity.java:448`）⇒ 不会被传送门搬走，避免「宝库旁有传送门 ⇒ 仪式在别的维度触发」。代价已逐条排除（见 §五 核对表 #5/#6）。⚠️ **`tickCount` 照常自增**（它在 `ServerLevel.tickNonPassenger`，不在 `baseTick`），但读档归零 ⇒ 仍只能用 `lifeTicks` 计时 |
| **D18** | `tick()` 的异常处理 | 召唤逻辑包在 `try { … } catch (Exception e) { LOGGER.error(坐标/维度) } finally { discard(); }` | `guardEntityTick`（`Level.java:607-621`）：`removeErroringEntities` **默认 `false`** ⇒ **崩整个服务端**；管理员设为 `true` ⇒ **静默 discard、仪式无声消失**。自己兜住后变成「恰好尝试一次 + 留下可诊断的 ERROR 日志」，两种坏结局都被消除 |
| **D19** | 是否再覆写 `broadcastToPlayer` | **不覆写** | `ChunkMap.addEntity` 的 `i != 0` 短路已是结构性硬保证（核对表 #1），而 `broadcastToPlayer` 的唯一调用点（`ChunkMap.updatePlayer:1335`）就在那条已被保证的路径上 ⇒ 再覆写是冗余噪音 |
| **D20** | 仪式死王的灵魂 | 召唤时**刻意不调** `boss.setSpawnPos(...)`，让 `spawnPos` 保持 `null`，从而 `DeadKingBoss.tickDeath()` 不生成 `dead_king_soul` | **用户裁定（2026-09-20，取代 D7）**：仪式召唤的不祥死王死后留下灵魂，会让后续前来探索的玩家误以为这里是陵墓里那个可复活的死王。源码核对确认 `spawnPos` 对 `DeadKingBoss` 的**唯一行为用途**就是灵魂闸门（`:711-716`；其余是存取器与 NBT 存读，`DeadKingCorpseEntity:84` 属尸体路径）⇒ **删一行即可**，零 mixin、零改动铁魔法。语义上也自洽：原版本就用 `spawnPos != null` 表示「这个死王属于某条复活链」，而仪式是一次性遭遇。<br>**代价（已接受）**：仪式死王**不可再战**（无灵魂可右键护命匣）。战利品与进度触发器不读 `spawnPos`，**不受影响**。<br>**风险**：本方案依赖 IS 的这个实现事实；将来 IS 若改换灵魂闸门，「无灵魂」会**静默**退回「有灵魂」⇒ 缓解 = 代码注释显式写明该依赖 + **双侧验收**（用例 7a 仪式死王无灵魂 / 7b 尸体复活死王仍有灵魂） |

### 配置

```toml
# beloong-server.toml
[dread_king_ritual]
enabled = true
structure = "dragonsurvival:dragon_hunters_castle"
musicVolume = 0.5
```

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | bool | `true` | 总开关。关闭时宝库照常开箱，只是没有仪式 |
| `structure` | string | `dragonsurvival:dragon_hunters_castle` | 触发限定结构。**这是事实 1 的保险**：整合包把宝库放进城堡之前，此功能不会触发 |
| `musicVolume` | double (0.0–16.0) | `0.5` | 同时决定响度与可闻半径（半径 = `16 × max(volume,1)` 格）。唱片机约定值是 4.0（64 格） |

---

## 四、组件

| # | 组件 | 职责与关键点 |
|---|---|---|
| **C1** | `DreadKingRitualTriggerMixin` | `@Mixin(VaultBlockEntity.Server.class)`。处理器签名必须**逐字匹配 `tryInsertKey` 的形参表**：`(ServerLevel, BlockPos, BlockState, VaultConfig, VaultServerData, VaultSharedData, Player, ItemStack, CallbackInfo)`。唯一职责：把 `(level, pos, player)` 交给 `DreadKingRitualStarter`。**不含任何判定逻辑** |
| **C2** | `DreadKingRitualStarter` | `static void start(ServerLevel, BlockPos, ServerPlayer)`：开关 → 结构判定 → 计算刷怪点 → 构造并 `addFreshEntity` 标记实体。**唯一持有配置与结构知识的地方** |
| **C3** | `DreadKingRitualMarker` | `extends Marker`。持有 `lifeTicks`（默认 352）、`musicPlayed`，两者**都必须落 NBT**。`tick()`：首 tick 播音 + 置 `musicPlayed`；每 tick `--lifeTicks`；归零则**在 `try/catch/finally` 内**召唤 + `discard()`（D18）。**不调 `super.tick()`/`baseTick()`**（D17）。**召唤逻辑私有，不对外暴露**。召唤时**刻意不调 `boss.setSpawnPos(...)`**（D20）⇒ `spawnPos` 保持 `null`，`tickDeath()` 因而不生成灵魂 |
| **C4** | `ModEntities`（改） | 沿用既有 `DeferredRegister<EntityType<?>> ENTITIES`，照 `TORNADO` 的写法加 `DREAD_KING_RITUAL_MARKER`。**不要** `.updateInterval(1)`（那是龙卷风为投射物同步加的，本实体不发客户端，加了只会徒增负担） |
| **C5** | `beloong.mixins.json`（改） | 加进 `mixins` 数组（**不是** `client`）⇒ 走 `injectors.defaultRequire = 1`，注入点消失即**启动崩溃** |
| **C6** | `Config`（改） | `SERVER_SPEC` 新增 `DreadKingRitual` 静态内部类 + `[dread_king_ritual]` 节，照 `DragonSummon` 的写法 |
| **C7** | lang（改） | `entity.beloong.dread_king_ritual_marker`（中英各一条）。实体永不可见，但注册完整性需要它 |

---

## 五、数据流

### 时序

```
t0  玩家手持 dragonsurvival:dark_key 右键 STATE==ACTIVE 的 dragonsurvival:dark_vault
    │
    └─ VaultBlock.useItemOn                        (VaultBlock.java:47-71)
         └─ VaultBlockEntity$Server.tryInsertKey   (VaultBlockEntity.java:268-295)
              ├ canEjectReward(config, state)                  ✓
              ├ isValidToInsert(config, stack)                 ✓
              ├ !serverData.hasRewardedPlayer(player)          ✓
              └ unlock(level, state, pos, ...)   ← ★ C1 注入点（HEAD）
    │
t0  C1 → C2.start(level, pos, player)
    ├ 配置 enabled == false                        → return（宝库照常开箱，无仪式）
    ├ getStructureWithPieceAt(pos, 配置的结构).isValid() 为假 → return（同上）
    └ 命中：
        ├ spawnPos = 宝库顶（D10 的净空兜底）
        ├ new DreadKingRitualMarker(level) → moveTo(spawnPos) → lifeTicks = 352
        └ level.addFreshEntity(marker)
    │
t0' C3 首个 tick（musicPlayed == false）
    ├ level.playSound(null, getX(), getY(), getZ(),
    │                 SoundRegistry.DEAD_KING_MUSIC_INTRO, SoundSource.RECORDS, musicVolume, 1.0F)
    └ musicPlayed = true（写 NBT）
    │
t0'..t352  每 tick：--lifeTicks
    │
t352   lifeTicks == 0：
    ├ DeadKingBoss boss = new DeadKingBoss(level)
    ├ boss.moveTo(自身位置)
    ├ （**刻意不调** setSpawnPos —— D20：让 spawnPos 保持 null，死后因而不生成灵魂）
    ├ boss.finalizeSpawn(level, level.getCurrentDifficultyAt(boss.getOnPos()), MobSpawnType.TRIGGERED, null)
    ├ boss.setPersistenceRequired()
    ├ level.addFreshEntity(boss)
    ├ boss.onOminousTrigger()                                       ← D6，幂等
    ├ SCULK_SOUL ×80 + DEAD_KING_SPAWN（音量 20）
    └ discard() 自身
```

### 「唱片机语义」与音量耦合的推导

原版唱片机的实际参数（源码级）：

| 环节 | 实现 | 参数 |
|---|---|---|
| 服务端播放 | `JukeboxSongPlayer.play()` → `level.levelEvent(1010, pos, songId)` | 广播半径**固定 64 格**（`ServerLevel.java:1052-1064`） |
| 客户端播放 | `LevelRenderer.playJukeboxSong` → `SimpleSoundInstance.forJukeboxSong(...)` | **`SoundSource.RECORDS`、音量 4.0F、音高 1.0F、`Attenuation.LINEAR`、方块中心、不循环**（`SimpleSoundInstance.java:70-82`） |

我们走的是 `Level.playSound(...)`，服务端与客户端两段都可等价：

| 环节 | 实现 | 与唱片机的差别 |
|---|---|---|
| 服务端广播半径 | `sound.getRange(volume)`（`ServerLevel.java:1006-1016`）；NeoForge 的 `SoundEvent.getRange` = `volume > 1 ? 16×volume : 16`（`SoundEvent.java:58-64`） | volume=4.0 时同为 64 格；**volume=0.5 时降为 16 格** |
| 客户端增益与衰减 | `f1 = max(volume, 1.0) × attenuationDistance`、`f2 = volume × 音源滑块`，`linearAttenuation(f1)`（`SoundEngine.java:447-459`、`Channel.java:142`） | volume=4.0 时与唱片机逐项相同；0.5 时基准增益减半、衰减跨度收缩到 16 格 |

⇒ **D5 的代价是可以逐项算出来的**：`musicVolume = 0.5` 让响度降到唱片机默认的 1/8，同时把可闻半径从 64 格压到 16 格。因为「送达半径」与「衰减跨度」是同一个旋钮的两个侧面，**无法只降响度而保住 64 格覆盖**（NeoForge 的 `createFixedRangeEvent` 只能固定「送达半径」，客户端衰减跨度仍受 `volume` 下限约束）。用户已确认接受。

> 说明：铁魔法自己的 Boss 音乐不受此耦合影响 —— 它走**客户端 `SoundInstance`**，`Attenuation.NONE`（距离无关）+ 音量 1.0。

### 原版 `Marker` 源码核对结论（继承它会继承什么）

核对源：项目留存的 NeoForge 21.1.236 权威源码树 `build/review-src/neoforge-21.1.236/`
（`Marker.java` 全文 67 行，**与上游逐字一致、无任何 NeoForge 补丁**）。

| # | 源码事实 | 对本设计的影响 |
|---|---|---|
| 1 | **「不发客户端」是结构性保证，不是概率性的** —— `ChunkMap.addEntity`：`int i = entitytype.clientTrackingRange() * 16;` 然后 `if (i != 0) { ...创建 TrackedEntity... }`（`ChunkMap.java:1110-1121`） | `clientTrackingRange(0)` ⇒ `i == 0` ⇒ **`ChunkMap` 连 `TrackedEntity` 都不创建**，更不会有 `ServerEntity`、更不可能发包。这比原先设想的「距离 0 够不到」强得多：**与玩家距离、玩家数量、维度全部无关**。⇒ D2 的两个 Builder 参数是**承重结构**，必须原样保留 |
| 2 | `Marker.getAddEntityPacket` 直接 `throw`（`Marker.java:38-41`） | 在事实 1 之下它是一条**不可达**的兜底。真正价值：万一后人改掉 `clientTrackingRange`，服务端会在配对时**响亮崩溃**而不是静默变成可见实体 —— 失败模式朝正确方向倾斜 |
| 3 | **`Marker.tick()` 是空实现，且不调 `Entity.baseTick()`**（`Marker.java:20-22` vs `Entity.java:430-432`） | 我们**有意继承**这一点（D17）：`baseTick()` 含 `handlePortal()`（`Entity.java:448`）⇒ 不跑它就**免疫传送门处理**，正好避免「宝库顶旁边就是传送门 ⇒ 仪式在传送门另一端触发」这一失效模式 |
| 4 | `tickCount++` **不在** `Entity.baseTick()` 里，而在 `ServerLevel.tickNonPassenger`（`ServerLevel.java:769-772`） | 这是一个**常见误归因**。继承 `Marker` 并不会让 `tickCount` 冻结，它照常每 tick 自增。但**仍不能拿它计时**：`Entity.saveWithoutId`（`Entity.java:1737` 起）写的是 `Pos/Motion/Rotation/FallDistance/Fire/Air/OnGround/Invulnerable/PortalCooldown/UUID/CustomName`，**不含 `tickCount`** ⇒ 读档归零。`lifeTicks` 落 NBT 才是唯一合法计时源 |
| 5 | `firstTick` 只在 `baseTick()` 里被清（`Entity.java:490`）⇒ 本实体该字段**永远为 `true`** | 逐条查完全部消费者后确认**无实际危害**：`Entity.java:1292`（水中溅水，仅 `baseTick` 路径可达）、`:1394`（`isInLava()` ⇒ 恒 false，对 `noPhysics` 实体本就是正确答案）、`:2974`（尺寸变大时的位置微调；我们 0×0 尺寸恒定，且 `noPhysics` 已短路）、`LivingEntity` 的几处不适用 |
| 6 | `baseTick()` 还含 `checkBelowWorld()`（`Entity.java:485`）⇒ 永不执行 | 标记实体不会被虚空移除。锚点取自真实方块坐标（必在世界内），**无实际影响**；`discard()` 是唯一移除路径 |
| 7 | **`tick()` 里的异常后果很重** —— `Level.guardEntityTick`（`Level.java:607-621`）捕获后建「Ticking entity」崩溃报告，然后：`NeoForgeConfig.SERVER.removeErroringEntities` 为 `true` ⇒ 记日志 + `entity.discard()`；否则 ⇒ `throw new ReportedException`。**该配置默认是 `false`** | ⚠️ **默认配置下异常会崩掉整个服务端**；管理员若设为 `true` 则变成「静默 discard 出错实体、仪式无声消失」。**两种都不可接受** ⇒ 召唤逻辑必须自己兜异常（D18） |
| 8 | `ServerLevel.shouldDiscardEntity`（`:465-469`）对本实体的求值结果为 **`false`**（非 `Animal`/`WaterAnimal`/`Npc`） | 标记实体不会被「实体清理」路径丢掉 |
| 9 | 是否落盘：`PersistentEntitySectionManager:196` 按 `EntityAccess::shouldBeSaved` 过滤；`Entity.shouldBeSaved()`（`Entity.java:3670-3676`）对非载具实体返回 `true`；`Entity.save` 对非乘客实体返回 `true` | **自动随存档持久化**（D13 成立），无需任何额外处理 |
| 10 | `ServerEntity` 的构造函数**不**调用 `getAddEntityPacket`（只读位置/朝向/`entityData`） | 排除一个可能的灾难性猜疑：即便 `TrackedEntity` 被创建也不会当场抛异常；而在事实 1 之下它根本不会被创建 |
| 11 | NeoForge 在实体 tick 外套了 `EntityTickEvent.Pre/Post`（`ServerLevel.java:775-779`） | 若有第三方模组取消 `EntityTickEvent.Pre`，倒计时会停摆。极端罕见，仅登记为已知相互作用 |
| 12 | `Entity.broadcastToPlayer(ServerPlayer)` 是 public、默认 `true`，**唯一调用点**是 `ChunkMap.updatePlayer:1335` | **已评估并明确否决**再加一层覆写（D19）：事实 1 已是硬保证，再覆写属冗余噪音 |

---

## 六、错误处理与边界

| 场景 | 行为 | 评价 |
|---|---|---|
| 玩家拿**备用**暗影钥匙右键**已开过**的宝库 | **不触发** | `hasRewardedPlayer` 拦在 `unlock()` 之前，注入点看不到该路径。**这是选 mixin 的核心收益**：`hunter_knight` 掉 `dark_key`（`loot_table/entities/hunter_knight.json`）⇒ 玩家能囤钥匙，零 mixin 方案在此会**刷死王** |
| 两名玩家同 tick 开同一宝库 | 只有一人的插入会走到 `unlock()` ⇒ **只触发一次** | 无竞态 |
| 钥匙无效（非暗影钥匙） | 不触发 | 同上，走不到 `unlock()` |
| 宝库处于 `UNLOCKING` / `EJECTING` 状态时被右键 | `useItemOn` 直接 `PASS_TO_DEFAULT_BLOCK_INTERACTION` ⇒ 不触发 | `VaultBlock.java:50` |
| 宝库顶被方块堵住 | 向上扫描净空（上限 +8）；扫不到则**照旧在宝库顶召唤** + WARN | D10。忠于需求，不擅自改位 |
| 区块卸载 / 超出实体刻范围（玩家跑远） | 倒计时**暂停**，玩家回来自动继续 | D12（用户裁定）。副作用见第八节 |
| 服务器重启 | 实体与 `lifeTicks` / `musicPlayed` 随存档持久化 ⇒ 接着走剩余 tick，**不重放音乐** | D13。重放会导致音乐与倒计时错位 |
| 实体被 `/kill` | 不召唤 | D13（用户确认） |
| 玩家离线 / 死亡 / 切维度 | **不影响**实体（它不依赖玩家） | 标记实体架构相对药水效果方案的主要优势 |
| 迟到玩家（音乐广播后才进场） | 听不到音乐 | 与唱片机一致（音效实例只在广播那一刻发给范围内客户端） |
| 龙之生存 / 铁魔法缺席 | 不可能 | 两者都是 `required` 依赖，直接引用，不加 `isLoaded` 守卫（与 `DeadKingAdvancementHandler` 同款口径） |
| **注入点消失**（原版重构 `tryInsertKey`） | **启动即崩** | `beloong.mixins.json` 的 `injectors.defaultRequire = 1`。符合项目「宁可响亮失败也不要静默失效」的取向 |
| 其他模组取消我们的音乐 | 只是没声音，**仪式照常**（倒计时不依赖音乐） | `Level.playSound` 会经过 NeoForge 的 `PlayLevelSoundEvent.AtPosition`（`ServerLevel.java:1000`） |
| 玩家在铁魔法配置里关掉 Boss 音乐 | 铁魔法自己的 intro 不播；**我们的仪式音乐照常** | 我们走服务端音效包，与 IS 的客户端 `MusicManager` 无关 |
| **召唤过程中抛异常** | 自行捕获 → ERROR 日志（含坐标与维度）→ `discard()`，**最多尝试一次** | D18。若不兜：`removeErroringEntities` 默认 `false` ⇒ **服务端崩溃**；设为 `true` ⇒ 实体被静默丢弃、仪式无声消失 |
| 第三方模组取消 `EntityTickEvent.Pre` | 倒计时停摆（实体仍存活） | NeoForge 在 `ServerLevel.java:775-779` 提供的钩子。极端罕见，登记为已知相互作用 |
| 有人误改 `clientTrackingRange`（调大范围，或照抄龙卷风加 `.updateInterval(1)`） | 标记实体被发送 ⇒ `Marker.getAddEntityPacket` **throw** ⇒ **运行期服务端崩溃** | 这是**刻意的响亮失败**：把「不可见实体变得可见」这一静默错误转成崩溃。⇒ T2/T3 的实现注释**必须**写明这两个 Builder 参数是承重结构 |

---

## 七、验收策略

项目无测试套件，验收 = `.\gradlew.bat build` + 静态探针 + 实机运行（沿用项目既定方式）。

### A. 构建与静态探针

1. `.\gradlew.bat build` 通过
2. `beloong.mixins.json` 含新条目，且在 **`mixins`** 数组（不在 `client`）
3. `ModEntities` 含新 Holder，且**未**带 `.updateInterval`
4. 两个 lang 文件都含 `entity.beloong.dread_king_ritual_marker`
5. jar 内含 3 个新 class：`mixin/minecraft/DreadKingRitualTriggerMixin`、`dreadking/DreadKingRitualStarter`、`entity/DreadKingRitualMarker`

### B. 实机用例（每条都要能证伪）

| # | 操作 | 期望 | 这条在验证什么 |
|---|---|---|---|
| 1 | `/summon beloong:dread_king_ritual_marker <城堡内宝库顶>` | 听到音乐；352 tick 后**原地**出现死王 | 执行层可独立验证；音乐时长与倒计时吻合 |
| 2 | 检查死王是否为**不祥**态（环绕 `TRIAL_OMEN` 粒子 / 血量 1000） | 不祥 | `onOminousTrigger()` 真的生效 |
| 3 | 在城堡里用暗影钥匙开黯影宝库 | 同上 | 主链路 |
| 4 | 在**非**城堡处放一个黯影宝库并开启 | **不触发** | 结构配置真的在生效 |
| 5 | 拿**备用**暗影钥匙对**同一已开过**的宝库再右键 | **不触发** | D1 的精度 —— 被否决的零 mixin 方案会在此失败 |
| 6 | 在 16 格外 / 音乐响起后再进场 | 听不到 / 只听到剩余部分 | D5 的半径与唱片机语义 |
| 7a | 打死**仪式召唤**的不祥死王 | **不生成** `irons_spellbooks:dead_king_soul` | D20 的灵魂抑制真的生效 |
| 7b | 打死**尸体复活**途径的死王（普通与不祥各一次） | **仍生成**灵魂，护命匣可右键复活 | **回归闸门**：证明 D20 没有把灵魂全局弄没。若 IS 将来换掉灵魂闸门，7b 会失败而 7a 会「假通过」 |
| 8 | 仪式中途走到区块卸载距离外，再回来 | 倒计时从暂停处继续，无音乐 | D12 的已知行为，确认不是卡死 |
| 9 | `/kill @e[type=beloong:dread_king_ritual_marker]` | 不召唤 | D13 |

### C. 计时取证

用 `/time query gametime`（项目已验证 RCON 可用，见 `memory/decisions-log.md`）在音乐开始与死王出现两处各取一次，差值应为 **352 tick** ± 少量。

---

## 八、已知行为与明确不做的事

| 项 | 内容 | 状态 |
|---|---|---|
| **无声倒计时** | 音乐由客户端音效实例独立播放，不随区块暂停；若玩家在仪式中跑远导致倒计时暂停，回来时音乐可能已放完 | **已知并接受**（D12 + D5 两条决策叠加的必然结果） |
| **双重 intro** | 死王登场时铁魔法会自己再播一遍同一段 intro（1.0 音量、非定位） | **视作渐强，不处理**（D14） |
| **仪式死王不可再战** | 死后不生成灵魂（D20）⇒ 护命匣无处可用 | **用户裁定**。为避免灵魂误导后续探索者；其他途径死王的灵魂行为不变 |
| **一个宝库多人各召一次** | 4 人小队各开一次同一宝库会背靠背出现 4 个不祥死王 | **用户裁定接受**（D11） |
| **整合包尚未把宝库放进城堡** | 在整合包完成这一步之前，本功能装了但不触发 | **已通过配置项吸收**（D8）；验收时需先手动在城堡放一个黯影宝库 |
| 不做结构标签（tag）形式 | `structure` 只接受单个 ID | 需要时再扩 |
| 不做「已触发过」的持久化闸门 | 不记录哪些宝库触发过 | 与 D11 一致 |
| 不做音乐与倒计时的双向绑定 | 倒计时是硬编码常量，不从音乐文件动态读取 | D3。运行时读 ogg 时长是过度设计 |

---

## 九、证据索引（源码级）

| 主题 | 文件:行 |
|---|---|
| `dark_vault` 注册与默认 NBT | `DSBlocks.java:1143-1149, 1181-1191` |
| 龙之生存放开 key 校验 | `VaultBlockEntityServerMixin.java:21-28` |
| 宝库 `useItemOn` 的放行条件 | `VaultBlock.java:47-71` |
| `tryInsertKey` 成功分支与唯一 `unlock` 调用 | `VaultBlockEntity.java:268-295`；`unlock` 定义 `:328-341` |
| `UNLOCKING` 的唯一入口与转移 | `VaultState.java:31-36, 70-93` |
| 包私有 API 约束 | `VaultServerData.java:56, 60`；`VaultBlockEntity.java:353, 357` |
| `ITEM_USED_ON_BLOCK` 判据的触发条件 | `ServerPlayerGameMode.java:367-368` |
| 地点谓词的结构字段 | `LocationPredicate.java:22, 54` |
| 进度奖励无 `effects` 字段 / function 以玩家身份执行 | `AdvancementRewards.java:26, 38, 85` |
| 原版 `Marker` 永不发送客户端 | `Marker.java`（`getAddEntityPacket` throw）；`EntityType.java:496-498` |
| 实体持久化判定 | `Entity.java:3670` |
| 死王碰撞箱 | `EntityRegistry.java:277-281` |
| 不祥触发条件 | `ServerPlayerEvents.java:705-738`；`IOminousEntity.java:12` |
| `onOminousTrigger` 实现 | `DeadKingBoss.java:115-135` |
| 尸体复活参考序列 | `DeadKingCorpseEntity.java:75-90` |
| 灵体生成依赖 `spawnPos` | `DeadKingBoss.java:706-718` |
| 音乐长度常量 | `DeadKingMusicHandler.java:17` |
| 铁魔法自己的 intro 重播链 | `DeadKingBoss.java:470-474`；`DeadKingMusicHandler.java:38, 46-55` |
| 铁魔法音乐的音量与衰减 | `FadeableSoundInstance.java:18, 21` |
| 服务端音效广播半径 | `ServerLevel.java:1006-1016`；`SoundEvent.java:58-64` |
| 唱片机的广播半径与音效参数 | `ServerLevel.java:1052-1064`；`SimpleSoundInstance.java:70-82` |
| 客户端增益与线性衰减 | `SoundEngine.java:447-459`；`Channel.java:142` |
| 暗影钥匙来源 | `loot_table/entities/hunter_knight.json` |
| 项目内范式：实体注册 / 事件注册 / 效果过期 | `registry/ModEntities.java:42-54`；`ability/AbilityEffectRegistry.java:15-28`；`StructureEffectHandler.java:120-131` |
| 原版 `Marker` 全文（与上游一致、无 NeoForge 补丁） | `build/review-src/neoforge-21.1.236/net/minecraft/world/entity/Marker.java:11-67` |
| `clientTrackingRange(0)` ⇒ `ChunkMap` 不创建 TrackedEntity | `ChunkMap.java:1110-1121` |
| `Marker` 不跑 `baseTick()`；`Entity.tick` 的定义 | `Marker.java:20-22`；`Entity.java:430-432` |
| `baseTick()` 内含 `handlePortal` / `checkBelowWorld` / `firstTick = false` | `Entity.java:434-496`（`:448`、`:485`、`:490`） |
| `tickCount++` 的真实位置 | `ServerLevel.java:769-772` |
| 实体 NBT 键清单（**无** `tickCount`） | `Entity.java:1737` 起 |
| `firstTick` 的全部消费者（逐个排除） | `Entity.java:1292, 1394, 2974` |
| 实体 tick 的异常处理 / `removeErroringEntities` 默认值 | `Level.java:607-621`；`NeoForgeConfig.java`（`.define("removeErroringEntities", false)`） |
| 实体清理与落盘的判定 | `ServerLevel.java:465-469`；`PersistentEntitySectionManager.java:196`；`Entity.java:3670-3676` |
| `ServerEntity` 构造不取生成包 | `ServerEntity.java` 构造器 |
| NeoForge 实体 tick 事件钩子 | `ServerLevel.java:775-779` |

---

## 十、实施计划

见 `docs/plans/2026-09-20-dark-vault-dread-king-ritual-plan.md`（由 `planning` 技能产出）。
