# 《NPC 系统总设计》与源码一致性审计

> 审计对象：`docs/NPC系统总设计.md`（997 行，2026-09-25 新增，HEAD 提交 `24bb701`）
> 审查基线：`24bb701`（分支 `NPC`，工作树干净）
> 审查方式：只读静态核对。逐条对照 `src/main/java`、`src/main/resources` 与 Minecraft 1.21.1 + NeoForge 21.1.236 补丁后源码
> （`sourcesAndCompiledWithNeoForge_ec9b86340cfa34d24e99524bcb732bfdd44c7f42_output.jar`，`SharedConstants.VERSION_STRING = "1.21.1"`）。
> 未运行游戏、未跑 gradle、未修改任何源码/文档。

## 结论

文档整体可信度**高**：§八 的 12 个行数、4 个资产大小、226 键与键集合一致性，§4.1 的骨骼/立方体/动画统计（145 骨骼、340 立方体、96 动画、`run` 缺 `Drip1-3`），属性默认值（1000/1.0/100.0/0.3）、全部布局常量、配置默认与范围、命令 op 等级，以及 §九 抽查的 15 条决策，逐项实测**全部命中**；抽查 32 处（原版/NeoForge 28 处 + GeckoLib 4 处）行号引用中，**原版 28 处有 25 处精确正确**（含 `Entity.java:2681-2686` 的 `!isCreativePlayer()`、`Mob.java:749` 的 `checkDespawn` 闸门、`bypasses_invulnerability` 标签恰好只含 `/kill` 与虚空），GeckoLib 4 处因缺 4.9.2 源码无法核行号。

最需要修的四类：① **三处原版行号指错**（`LivingEntity.java:1084` 实为 `1143`、`Mob.java:437` 实为 `461`、`Entity.java:1759` 实为 `1854`），而这三行正是"为什么语义必须写成覆写方法"这一核心论断的**唯一证据**，且同样的错号已被复制进 `NpcEntity.java` 的类注释；② **§5.5 状态机表把最后一页的出口写错**——源码里末页从 `TYPING` **直接**进 `SHOWING_OPTIONS`，从不经过 `WAIT_CLICK`；③ **§3.3 "lookTime 40~80 tick"** 与 `Goal#adjustedTickDelay` 的减半规则不符，实际是 **20~40 tick**；④ **贴图尺寸写错**：文档两处（§4.1 与 §八）与 `DihuangLoongModel` 注释都写 256×256，实测 PNG 头 IHDR 是 **512×512**。

另有一处真实的资源缺陷（非文档笔误）：样例数据 `iron_golem.json` 的 `sound` 值是 `beloong:dialogue.iron_golem.1`，而 `sounds.json` 里只有 3 条传送门事件，**该声音事件未定义**（详见 C6）。

## 一致性问题（Critical）

### C1. 最后一页的动画状态机出口写错了 — `docs/NPC系统总设计.md:587`

- **文档原文**（§5.5 三段状态机表）：
  > `TYPING` | 打字机逐字显示当前页（每 tick `charsPerTick` 字） | 打完 → `WAIT_CLICK`
  > `WAIT_CLICK` | 显示"继续"箭头，等玩家点击 | 点击 → 下一页；已是最后一页 → `SHOWING_OPTIONS`
- **实际情况**：末页**不经过** `WAIT_CLICK`。`TYPING` 显示完（自然播完或中途点击）一律走 `advanceOrFinish()`：非末页 → `WAIT_CLICK`，**末页 → 直接 `showOptions()`**。因此 `WAIT_CLICK` 状态下"已是最后一页"这一分支**不存在**（`WAIT_CLICK` 只可能由非末页进入）。
- **证据**：`src/main/java/com/zonlong/beloong/client/NpcDialogueScreen.java:226-232`（`advanceOrFinish`）、`:202`（`TYPING` 点击也调 `advanceOrFinish`）、`:205-209`（`WAIT_CLICK` 点击固定 `pageIndex++`）、`:206` 注释"只有中间页会进入这里（最后一页在 advanceOrFinish 里直接弹选项）"。
- **建议修法**：把两行改为
  - `TYPING` → 打完：**非末页 → `WAIT_CLICK`；末页 → `SHOWING_OPTIONS`**（"文字播完还要再点一次才出选项是多余的"，见 `NpcDialogueScreen.java:219-225`）；
  - `WAIT_CLICK` → 点击 → 下一页（`WAIT_CLICK` 只由非末页进入，"末页"分支不存在）。

### C2. `lookTime` 实际是 20~40 tick，不是 40~80 — `docs/NPC系统总设计.md:195`

- **文档原文**：`probability 给 1.0：原版默认 0.02 会让 NPC 平均 2.5 秒才看玩家一眼。lookTime 40~80 tick 一轮、到期立刻重启 ⇒ 实际表现为持续跟随。`
- **实际情况**：`LookAtPlayerGoal.start()` 写的是 `adjustedTickDelay(40 + random.nextInt(40))`，而 `Goal#adjustedTickDelay` 对**不要求每 tick 更新**的 goal 取 `Mth.positiveCeilDiv(x, 2)`；`LookAtPlayerGoal` 未覆写 `requiresUpdateEveryTick()`（默认 false）⇒ `lookTime ∈ [ceil(40/2), ceil(79/2)] = [20, 40]` tick（1~2 秒）。
  "原版默认 0.02 ⇒ 平均 2.5 秒"与"probability=1.0 ⇒ 到期立刻重启"两句是对的（`canUse()` 里 `nextFloat() >= 1.0F` 恒假）。
- **证据**：`LookAtPlayerGoal.java:93`（`this.lookTime = this.adjustedTickDelay(40 + this.mob.getRandom().nextInt(40));`）、`Goal.java:25-27`（`requiresUpdateEveryTick()` 返回 false）、`Goal.java:46-52`（`reducedTickDelay` = `positiveCeilDiv(adjustment, 2)`）；`LookAtPlayerGoal.java` 全文 110 行内无 `requiresUpdateEveryTick` 覆写。
- **建议修法**：改为"`lookTime` 20~40 tick 一轮（`adjustedTickDelay` 会把 40~80 减半，因为该 goal 不要求每 tick 更新）、到期立刻重启 ⇒ 实际表现为持续跟随"。

### C3. 三处原版行号指错，且是核心论断的唯一证据 — `docs/NPC系统总设计.md:161`、`:166-167`

- **文档原文**：
  - `:161` `| **无敌** | \`isInvulnerableTo(source)\` 只放行 \`BYPASSES_INVULNERABILITY\` | \`LivingEntity#hurt\` 的第一道闸门（\`LivingEntity.java:1084\`） |`
  - `:166-167` `\`load()\` 会把 \`Invulnerable\`（\`Entity.java:1759\`）、\`PersistenceRequired\`（\`Mob.java:437\`）从标签读回`
- **实际情况**：
  | 文档引用 | 该行实际内容 | 正确位置 |
  |---|---|---|
  | `LivingEntity.java:1084` | `serverplayer.connection.send(new ClientboundRemoveMobEffectPacket(...))`（在 `onEffectRemoved` 里） | **`LivingEntity.java:1143`**：`if (this.isInvulnerableTo(source)) {`，即 `hurt()` 的第一道闸门 |
  | `Mob.java:437` | `compound.putString("DeathLootTable", ...)`（**写出**） | **`Mob.java:461`**：`this.persistenceRequired = compound.getBoolean("PersistenceRequired");` |
  | `Entity.java:1759` | 空行（`addAdditionalSaveData` 内） | **`Entity.java:1854`**：`this.invulnerable = compound.getBoolean("Invulnerable");`（写出侧是 `:1752`） |
- **证据**：上表逐行实测（命令见文末"核对方法"），`LivingEntity.java` 全文 3774 行、`Mob.java` 1618 行、`Entity.java` 3914 行。
- **建议修法**：改为 `LivingEntity.java:1143`、`Mob.java:461`、`Entity.java:1854`。**并同步修正源码类注释** `NpcEntity.java:46-47`（`Entity.java:1759`/`Mob.java:437`）与 `NpcEntity.java:161/178`（`LivingEntity.java:1084`）——同一错误在文档与代码注释里各出现一次，两处都要改，否则下次核对仍然对不上。

### C4. `DihuangLoongModel` 并没有"提取 renderLayers 与 root 骨骼" — `docs/NPC系统总设计.md:374-378`

- **文档原文**（§4.2）：`\`DihuangLoongModel\` 做三件事：1. 声明三条资源路径（geo / animation / texture）；2. 提取模型自身的 \`renderLayers\` 与 root 骨骼；3. 在 \`applyMolangQueries\` 里注册两个全局 Molang 变量供动画资产使用。`
- **实际情况**：该类**只有** 3 个资源路径 getter（`getModelResource` / `getTextureResource` / `getAnimationResource`）与 `applyMolangQueries`（内含一次 `super` 调用），外加两个常量。全文件 118 行中不存在 `renderLayers`、`rootBone`、`getBoneResetTime` 之类的代码。
- **证据**：通读 `src/main/java/com/zonlong/beloong/client/model/DihuangLoongModel.java` 全文 118 行，除 3 个资源 getter、`applyMolangQueries`、`HEAD_YAW_SIGN` 与三个 `ResourceLocation` 常量外没有其它成员；`renderLayers`、`rootBone`、`getBoneResetTime` 等符号 0 命中。
- **建议修法**：删掉第 2 条，或改写成事实——"只声明三条资源路径；模型骨骼层级由 GeckoLib 从 `.geo.json` 自行加载，本类不介入"。

### C5. `MeleeAttackGoal` 的创造/旁观排除条件不在 `canUse()` 里 — `docs/NPC系统总设计.md:310`、`:313`

- **文档原文**：`目标死亡、或目标变成创造/旁观玩家时 \`MeleeAttackGoal.canUse()\` 返回 false，但若该 goal **从未启动过**，它的 \`stop()\` 就不会被调用 …… \`customServerAiStep\` 用与 \`canUse()\` 一致的排除条件（\`EntitySelector.NO_CREATIVE_OR_SPECTATOR\`）把这种残留清掉。`
- **实际情况**：`MeleeAttackGoal.canUse()`（`MeleeAttackGoal.java:35-60`）只检查 `target == null`、`!isAlive()` 与寻路，**没有任何创造/旁观判断**。创造/旁观排除出现在另外两处：`canContinueToUse()` 的 `:74`（`!livingentity.isSpectator() && !((Player)livingentity).isCreative()`）与 `stop()` 的 `:89`（`EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(...)`）。`NpcEntity.customServerAiStep` 的排除条件其实与 `stop()`/`canContinueToUse()` 一致，而不是与 `canUse()` 一致。
- **证据**：`MeleeAttackGoal.java:35-60`、`:63-76`、`:87-95`；`EntitySelector.java:31`（`NO_CREATIVE_OR_SPECTATOR` 定义）；`NpcEntity.java:259-267`。文档"若该 goal 从未启动过，`stop()` 就不会被调用"的推理本身**正确**。
- **建议修法**：把"`MeleeAttackGoal.canUse()` 返回 false"改为"目标死亡时 `canUse()`/`canContinueToUse()` 返回 false；目标变成创造/旁观玩家时 `canContinueToUse()`（`:74`）与 `stop()`（`:89`）会把目标放掉"；把"与 `canUse()` 一致的排除条件"改为"与 `stop()` 同一谓词 `EntitySelector.NO_CREATIVE_OR_SPECTATOR`"。

### C6. `iron_golem.json` 引用的声音事件在 `sounds.json` 中不存在 — `docs/NPC系统总设计.md:445`

- **文档原文**（§5.2 数据格式样例，与磁盘文件逐字一致）：`{ "text": "beloong.dialogue.iron_golem.p1", "sound": "beloong:dialogue.iron_golem.1" }`
- **实际情况**：`assets/beloong/sounds.json` **只定义了 3 条事件**：`block.beloong.loong_palace_portal.ambient` / `.travel` / `.trigger`。不存在 `beloong:dialogue.iron_golem.1`（也不存在任何 `beloong.dialogue.*` 声音事件）。当前"只解析不播放"所以无症状；一旦接线播放（决策 41 的"将来加一行播放调用"），`SoundEvent` 反查会落空——`level.playSound` 走 `Holder` 直接静默，`SoundEvent.createVariableRangeEvent` 则会拿到一个未注册的 `ResourceLocation`，表现为**永远没声音且不报错**（与文档 §5.7 最忌讳的"右键毫无反应且不报错"同类）。
- **证据**：`src/main/resources/assets/beloong/sounds.json`（547 B，全文 3 个键）；`src/main/resources/data/beloong/beloong/npc_dialogue/iron_golem.json:8`。
- **建议修法**：二选一，并回写文档 §5.2 的样例——① 在 `sounds.json` 里补上 `"beloong.dialogue.iron_golem.1"`（哪怕先指向占位 `sounds: []` 之外的真实文件）；② 把样例里的 `sound` 改成注释掉的"预留字段"示例，并在 §5.2/§10.2 注明"当前样例引用了尚未定义的事件，接线前必须补 `sounds.json`"。

## 一致性问题（Major）

### M1. `TEXT_TOP` 的排版方向写反了 — `docs/NPC系统总设计.md:598`

- **文档原文**：`| \`TEXT_TOP\` | 0.860 | 正文起始（**自下而上**排版） |`
- **实际情况**：正文**锚定首行顶部、向下生长**。源码注释明确写了反面教训："多行正文若按中轴对齐会**向上生长**，第一行直接压到装饰线上……锚定顶部后，无论几行都只往下长。"
- **证据**：`NpcDialogueScreen.java:48-55`、`:328-343`（`y = height * TEXT_TOP`，每行 `y += lineHeight`）。
- **建议修法**：改为"正文**首行顶部**（自上而下增长，避免多行时压到装饰线）"。

### M2. §3.9 / 类注释对 `createMobAttributes()` 的属性清单严重不全 — `docs/NPC系统总设计.md:343-345`

- **文档原文**：`\`createMobAttributes()\` 只含 MAX_HEALTH / KNOCKBACK_RESISTANCE / MOVEMENT_SPEED / ARMOR / ARMOR_TOUGHNESS，没有攻击力（原版要 \`Monster.createMonsterAttributes()\` 才加）。`
- **实际情况**：链是 `Mob.createMobAttributes()` = `LivingEntity.createLivingAttributes().add(FOLLOW_RANGE, 16.0)`，而 `createLivingAttributes()` 有 **18 项原版属性 + 2 项 NeoForge 属性**：MAX_HEALTH、KNOCKBACK_RESISTANCE、MOVEMENT_SPEED、ARMOR、ARMOR_TOUGHNESS、MAX_ABSORPTION、STEP_HEIGHT、SCALE、GRAVITY、SAFE_FALL_DISTANCE、FALL_DAMAGE_MULTIPLIER、JUMP_STRENGTH、OXYGEN_BONUS、BURNING_TIME、EXPLOSION_KNOCKBACK_RESISTANCE、WATER_MOVEMENT_EFFICIENCY、MOVEMENT_EFFICIENCY、ATTACK_KNOCKBACK、`neoforge:swim_speed`、`neoforge:nametag_distance`。合计 `createMobAttributes()` 有 **21 项**。"没有 ATTACK_DAMAGE"与"原版要 `Monster.createMonsterAttributes()` 才加"这两句**正确**。
- **证据**：`Mob.java:159-161`；`LivingEntity.java:321-343`；`Monster.java:127-129`（`return Mob.createMobAttributes().add(Attributes.ATTACK_DAMAGE);`）。
- **建议修法**：把属性枚举删掉，只保留结论："`createMobAttributes()`（= `LivingEntity.createLivingAttributes()` + FOLLOW_RANGE，共 21 项）**不含** ATTACK_DAMAGE；原版要 `Monster.createMonsterAttributes()` 才加。"同步修 `NpcEntity.java:137-139`。

### M3. "头停下约 11 tick 后允许量递减到 0" 把两个时刻混为一谈 — `docs/NPC系统总设计.md:221-222`

- **文档原文**：`头停下约 11 tick 后，\`rotateHeadTowardsFront\` 的允许量**递减到 0**，身体被**逐步收到与头完全对齐**`
- **实际情况**：`headStableTime > 10` 才**开始**调用 `rotateHeadTowardsFront()`（即第 11 tick），此时允许量是 `getMaxHeadYRot() * (1 - clamp(i/10,0,1))`，`i = headStableTime - 10`。允许量**降到 0 是在 `headStableTime = 20`**（头停下后约 20 tick ≈ 1 秒），不是 11 tick。
- **证据**：`BodyRotationControl.java:26-35`（15° 阈值 / `headStableTime > 10`）、`:48-53`（`i = headStableTime - 10`；`f = clamp(i/10,0,1)`；`f1 = maxHeadYRot * (1-f)`）。§10.1 的"允许量会递减到 0，身体最终与头完全对齐"是对的。
- **建议修法**：改为"头停下 11 tick 后**开始**递减（此时允许量 67.5°），再过 10 tick（共约 20 tick）减到 0 ⇒ 身体被逐步收到与头完全对齐"。

### M4. §八 的"对话系统 655 行"无法用本文行数表复现 — `docs/NPC系统总设计.md:738`

- **文档原文**：`> **比例值得注意**：基类 373 行、对话系统 655 行，而具体的 NPC 只有 49 行。`
- **实际情况**：按同表实测值，`dialogue` 包 4 文件 = 164+127+77+85 = **453** 行；再加界面 2 文件（460+147）= **1060** 行。**655 与两者都不符**（也不是任何 3~4 个文件行数的组合：最接近的 460+147+47 = 654）。
- **证据**：`docs/NPC系统总设计.md:725-736` 的行数表（12 项已逐项实测，全部正确）+ 本次实测复核。
- **建议修法**：改为"基类 373 行、对话系统（`dialogue` 包 453 行 + 界面 607 行 = 1060 行）、具体 NPC 49 行"。

### M5. §十一 声称"T10–T12 见 §10.2"，但 §10.2 里没有 T10 — `docs/NPC系统总设计.md:975` 对 `:928-934`

- **文档原文**：`| \`docs/plans/2026-09-25-npc-vanilla-ai-plan.md\` | 上述设计的实施计划（T1–T12） | **T1–T9 已完成并验证；T10–T12 见 §10.2** |`
- **实际情况**：§10.2 的表格只有 **T11**（迁到 `mobInteract`）与 **T12**（地黄龙接对话）两行，没有 T10。旧计划里 **T10 = "R9 标定：模型 y 偏移"**，其结论实际写在 §10.1 的 R9 行（"实测无偏移"），但没有 `T10` 这个编号，读者按图索骥会找不到。
- **证据**：`docs/NPC总设计.md:928-934`（§10.2 只有 T11/T12）、`:921`（§10.1 R9）；`docs/plans/2026-09-25-npc-vanilla-ai-plan.md:71`（`### T10 R9 标定：模型 y 偏移`）、`:80`（T11）、`:89`（T12）。
- **建议修法**：§10.1 的 R9 行加注 `（= 旧计划 T10，已结案）`，或把 §十一 的表述改为"T1–T9 已完成并验证；T10 见 §10.1（R9）；T11–T12 见 §10.2"。

### M6. 贴图尺寸 256×256 是错的，实际 512×512 — `docs/NPC系统总设计.md:364`、`:747`

- **文档原文**：
  - `:364`（§4.1 资产表）`| \`assets/beloong/textures/entity/dihuang_loong.png\` | 153 KB | 256×256 |`
  - `:747`（§八 资源包表）`| \`assets/beloong/textures/entity/dihuang_loong.png\` | 153 KB（256×256） |`
- **实际情况**：PNG 头的 IHDR 为 **512×512**（宽/高字节 0x00000200 各一）。文件大小 156351 B 与 512×512 相符。同一错误也写在源码注释 `DihuangLoongModel.java:23`（"`textures/entity/dihuang_loong.png` —— 256×256 贴图"）。
- **证据**：`[System.IO.File]::ReadAllBytes(png)` 后取偏移 16-19（宽）与 20-23（高），两处均为 `0x00000200` = 512；PNG 签名与 IHDR 位置已校验。
- **建议修法**：文档两处与 `DihuangLoongModel.java:23` 一并改为"512×512"。**注意**：文档 §八 的这张表其余 3 项（196 KB / 477 KB / 297 B）实测全部正确，只有尺寸这一列错。

## 一致性问题（Minor）

### m1. 地黄龙行数三处写法不一致 — `docs/NPC系统总设计.md:355` / `:783` / `:934`，表见 `:726`

- **文档**：`:355` "**不到 50 行**"、`:726` "49"、`:783`（决策 1）"地黄龙因此只有 **50 行**"、`:934` "'基类能否只剩 **50 行**'的证据"。
- **实际**：`DihuangLoongEntity.java` = **49 行**。"不到 50 行"成立，两处"50 行"不成立。
- **建议**：统一为"49 行（不到 50 行）"。

### m2. "原版 goal 也是这么用的：……给 1.25 ……给 2.0" 属过度概括 — `docs/NPC系统总设计.md:260-261`

- **实际**：`MeleeAttackGoal` 常见 1.0，但也有 1.2（Dolphin、AbstractSkeleton）/1.4（Rabbit）；`FollowParentGoal` 有 1.0/1.1/1.25；`TemptGoal` 有 1.0/1.1/1.2/1.25/1.4；`PanicGoal` 有 0.5/1.2/1.25/1.4/1.65/2.0。
- **证据**（示例）：`IronGolem.java:66`（1.0）、`Dolphin.java:156`（1.2）、`Rabbit.java:357`（1.4）、`Cow.java:47`（FollowParent 1.25）、`Chicken.java:60`（1.1）、`Llama.java:126`（1.0）、`Strider.java:161`（Panic 1.65）、`WanderingTrader.java:90`（Panic 0.5）。
- **建议**：改为"原版 goal 各自挑一个档位交给寻路（常见取值：`MeleeAttackGoal` 1.0、`FollowParentGoal`/`TemptGoal` 1.1~1.25、`PanicGoal` 1.25~2.0）"。

### m3. `doStuckDetection` 只在速度 < 1.0 时平方 — `docs/NPC系统总设计.md:257-258`

- **实际**：`float f = this.mob.getSpeed() >= 1.0F ? this.mob.getSpeed() : this.mob.getSpeed() * this.mob.getSpeed();`
- **证据**：`PathNavigation.java:312`（引用行号本身**正确**）。
- **建议**：补一句"（`getSpeed() >= 1.0` 时直接取值，否则平方——因为默认移速刻度都小于 1）"。

### m4. §5.4 受理流程图漏了一步 — `docs/NPC系统总设计.md:522-530`

- **实际**：代码在 `trigger` 判定之后还有一步 `if (!(player instanceof ServerPlayer serverPlayer)) return;` 才发包。
- **证据**：`NpcDialogueHandler.java:67-75`。
- **建议**：在流程图末尾补 `→ player instanceof ServerPlayer ? 继续 : return`（不影响结论，但流程图自称是完整触发链）。

### m5. "三个公开方法"与列出的 5 个签名易混 — `docs/NPC系统总设计.md:297-306`

- **实际**：API 代码块列了 5 个 `public` 方法，紧跟的正文说"三个公开方法**都只在服务端生效**"。真正带 `level().isClientSide()` 守卫的是 `walkTo`/`attack`/`stopAction`；`isAttackCommandActive()` 与 `clearAttackCommand()` 是 public 且**没有**守卫（它们是给 `NpcAttackGoal` 用的）。
- **证据**：`NpcEntity.java:280-330`。
- **建议**：改为"三个**外部驱动**方法（`walkTo`/`attack`/`stopAction`）都只在服务端生效；下面两个查询/清理方法仅供 `NpcAttackGoal` 使用，不含守卫"。另可补一句"`clearAttackCommand()` 顺带 `getNavigation().stop()`"（文档只说了 `clearMotionCommands` 的语义）。

### m6. 未提"空 `pages` 数组被整文件丢弃并报错" — `docs/NPC系统总设计.md:451-457`

- **实际**：`pages` 为空数组时 JSON 解析是**成功**的，是加载器在 `ifSuccess` 里单独拦截：打印 `npc dialogue file '...' declares no pages, ignored` 并跳过注册。
- **证据**：`NpcDialogueLoader.java:99-104`；`NpcDialogueEntry.java:39`（javadoc 提到了，文档正文没有）。
- **建议**：在 §5.2 字段表 `pages[].text` 行后补一行"`pages` 为空 ⇒ 整文件丢弃并打 ERROR（§5.7 失败模式表也宜补一行）"。

### m7. §5.5 只描述了屏幕，完全没描述选项按钮的视觉规格 — `docs/NPC系统总设计.md:581`

- **实际**：`NpcDialogueOptionButton` 有一整套硬编码视觉常量：`NORMAL_RGB=0x1A1F26`、`HOVER_RGB=0xC8A05A`、`BASE_ALPHA=0xC0`、`FADE_START=0.55`（前 55% 实心、向右线性淡出到透明）、`HOVER_STEP=0.25`（≈4 tick/200ms 插值）、`PADDING_LEFT=7`/`ICON_SIZE=9`/`ICON_GAP=5`、左侧 9×9 手绘"门 + 向外箭头"图标、继承 `AbstractWidget` 而非 `Button` 的理由（自绘外观 + 白拿点击/旁白）。
- **证据**：`NpcDialogueOptionButton.java:14-49`、`:88-118`、`:136-146`。
- **建议**：在 §5.5 补一小节（"选项按钮的视觉规格"），否则维护者调按钮外观时不知道该动哪个文件。

### m8. `yBodyRot` 的初值其实来自生成包 — `docs/NPC系统总设计.md:224-225`

- **文档**："`yBodyRot` **不参与网络同步**（只同步 `yRot`/`yHeadRot`）"——结论**正确**；但客户端 `yBodyRot` 的**初值**是 `ClientboundAddEntityPacket` 给的：`LivingEntity#recreateFromPacket` 里 `this.yBodyRot = packet.getYHeadRot()`，之后才由双端各自的本 tick 逻辑推进。
- **证据**：`LivingEntity.java:3710-3720`。
- **建议**（可并入正文）："`yBodyRot` 不进逐 tick 同步包；客户端只在实体生成时用 `ClientboundAddEntityPacket` 的 head yaw 初始化一次，此后自己算。"

## 建议（Nit）

1. **§4.3 注册点表只覆盖实体/属性/渲染器**，对话系统的三个注册点（`AddReloadListenerEvent`、`RegisterPayloadHandlersEvent`、`NeoForge.EVENT_BUS.register(new NpcDialogueHandler())`）散落在 §5.3/§5.4 的正文里。建议在该表加三行，让"删/改这个系统要动哪几处"一目了然（这是范本《天灾维度总设计》§2 那种"硬编码点"清单的价值所在）。
2. **格式与范本对齐良好**：状态块（`> 状态：**现行实现**（基于 \`hash\`「…」…）`）、编号章节、`## 九、设计决策记录`、"阅读顺序建议"块、文末相关文档表都在。两点差异是有正当理由的、建议保留并各加一句说明：① 范本 §十一 是"已废弃文档（已删除）"，本文是"历史文档（保留）"——因用户要求"旧文档一律保留"（决策 54）；② 本文多了 `## 附：本文与旧文档的编号对照`，范本没有，但正好补上"旧编号不续用"的断层，建议保留。
3. **§九 决策 52、53 不可静态核实**（"最初诊断与根因不同""实机验收是唯一终审"），属于历史陈述，本次审计不作判定；若要保持"结论必须带证据"的一致性，可给它们挂上对应的旧文档/提交号。
4. **`§3.3` 的 goal 代码块是伪代码**（`addGoal(...)` 而非 `this.goalSelector.addGoal(...)`），与 §3.9/§3.7 的真代码块风格不一。可在块首标一句"伪代码，见 `NpcEntity#registerGoals`"。
5. **§5.6 配置表未写分组键**（`beloong.configuration.npc_dialogue`），只在 §七 提了一句。建议在表头加一行"分组键：`beloong.configuration.npc_dialogue`（客户端/服务端复用同一键）"。

## 原版行号核对表

| 文档/注释中的引用 | 实际位置 | 是否正确 |
|---|---|---|
| `LivingEntity.java:1084`（`hurt` 第一道闸门）— 文档 §3.2 表、`NpcEntity.java:178` | `LivingEntity.java:1143`：`if (this.isInvulnerableTo(source))` | ❌ 偏差 59 行（应为 1143） |
| `Entity.java:1759`（`load()` 读回 `Invulnerable`）— 文档 §3.2、`NpcEntity.java:46` | `Entity.java:1854`：`this.invulnerable = compound.getBoolean("Invulnerable")`（写出侧 1752） | ❌ 偏差 95 行（应为 1854） |
| `Mob.java:437`（`load()` 读回 `PersistenceRequired`）— 文档 §3.2、`NpcEntity.java:47` | `Mob.java:461`：`this.persistenceRequired = compound.getBoolean("PersistenceRequired")` | ❌ 偏差 24 行（应为 461） |
| `Entity.java:2681-2686`（`isInvulnerableTo` 放行创造玩家） | `Entity.java:2681-2686`，`:2683` 含 `&& !source.isCreativePlayer()` | ✅ |
| `LivingEntity.java:2219-2220`（`travel` 被 `isControlledByLocalInstance()` 包住） | `LivingEntity.java:2219-2220`；`:2344` 才闭合，**整个方法体确实被包住** | ✅ |
| `Mob.java:736`（`requiresCustomPersistence`） | `Mob.java:736-738` | ✅ |
| `Mob.java:749`（`checkDespawn` 闸门 `!isPersistenceRequired() && !requiresCustomPersistence()`） | `Mob.java:749` 完全一致 | ✅ |
| `Mob.java:377-381`（`tickHeadTurn` 覆写、不调 super） | `Mob.java:377-381` | ✅ |
| `Mob.java:557-560`（`setSpeed` 同时写 `zza`） | `Mob.java:557-560`（`super.setSpeed(speed); this.setZza(speed);`） | ✅ |
| `Mob.java:1420`（`isEffectiveAi` = `!isNoAi()`） | `Mob.java:1419-1422` | ✅ |
| `Entity.java:3215-3217`（`isControlledByLocalInstance` → `isEffectiveAi`）— 仅类注释 | `Entity.java:3215-3217`；`Entity.isEffectiveAi` 在 `:3219-3220`（只有 `!level.isClientSide`），`!isNoAi()` 那半句在 `Mob.java:1420` | ✅（两处合起来成立，单看 `3215-3217` 不等于"`isEffectiveAi` = `!isNoAi`"） |
| `Player.java:231`（玩家默认移速 0.1） | `Player.java:231`：`.add(Attributes.MOVEMENT_SPEED, 0.1F)` | ✅ |
| `PathNavigation#doStuckDetection:312`（显式平方速度） | `PathNavigation.java:312`（`getSpeed() >= 1.0F ? ... : getSpeed()*getSpeed()`） | ✅（仅 <1.0 时平方，见 m3） |
| `MinecraftServer.java:1511`（服务端资源管理器用 `SERVER_DATA`） | `MinecraftServer.java:1511`：`new MultiPackResourceManager(PackType.SERVER_DATA, ...)` | ✅ |
| `MinecraftServer.java:1512-1519`（把 `(this.executor, this)` 传给 `loadResources`） | `MinecraftServer.java:1512-1519` 完全一致 | ✅ |
| `Minecraft.java:491`（客户端资源管理器用 `CLIENT_RESOURCES`） | `Minecraft.java:491`：`new ReloadableResourceManager(PackType.CLIENT_RESOURCES)` | ✅ |
| `MobEffect#addAttributeModifiers:166-174` | `MobEffect.java:166-174`（方法体 166~174） | ✅ |
| `AbstractFish:45` / `Axolotl:423` / `Raider:248` / `EnderMan:434`（`requiresCustomPersistence` 先例） | `AbstractFish.java:45` / `Axolotl.java:423` / `Raider.java:248` / `EnderMan.java:434` | ✅ 全部正确 |
| `Warden:555` / `Bat:85` / `Parrot:392`（`isPushable` 先例） | `monster/warden/Warden.java:555` / `ambient/Bat.java:85` / `animal/Parrot.java:392` | ✅ 全部正确 |
| `Phantom:63` / `Armadillo:392` / `Camel:635` / `Shulker:146`（`createBodyControl` 先例） | `monster/Phantom.java:63` / `animal/armadillo/Armadillo.java:392` / `animal/camel/Camel.java:635` / `monster/Shulker.java:146` | ✅ 全部正确 |
| `GeoEntityRenderer.java:266-268` / `AnimationController.java:747-761` / `MolangQueries.java:148-150,286` / `ModelProperties.java:35`（GeckoLib） | 缓存中**只有 GeckoLib 4.7.5.1 / 4.7.7 的 sources**，项目实际用的是 curse 坐标 `8350073`（编译产物，无源码，无法核行号）。在 4.7.7 上**语义**吻合：`isMoving` 阈值 `0.015f`（`GeoRenderer.java:118`）、状态在 `GeoEntityRenderer.java:262` 构造、未注册查询 `VARIABLES.computeIfAbsent(..., new Variable(key, 0))`（`MolangQueries.java:149`）、`HEAD_Y_ROTATION` 取绝对 `getViewYRot`（`:286`） | ⚠️ 未能核实（缺 4.9.2 源码）；语义与 4.7.7 一致 |

> 说明：`MobCategory.MISC = ("misc", -1, true, true, 128)`（文档 §4.3）实测 `MobCategory.java:15` 完全一致，属数值核对表条目。
>
> 说明：本表共 21 行、覆盖 32 处引用。3 处错误行号与 3 处正确位置（`LivingEntity.java:1143`、`Mob.java:461`、`Entity.java:1854`）在缓存中**四个** 1.21.1 源码 jar 上逐一复核，位置完全一致，因此不存在"换 jar 就对得上"的可能。

## 数值核对表

| 断言（文档位置） | 文档值 | 实测值 | 是否一致 |
|---|---|---|---|
| `MAX_HEALTH`（§3.9 / §4） | 1000.0D | `NpcEntity.java:156` = 1000.0D | ✅ |
| `KNOCKBACK_RESISTANCE` | 1.0D | `:157` = 1.0D | ✅ |
| `ATTACK_DAMAGE` | 100.0D | `:158` = 100.0D | ✅ |
| `MOVEMENT_SPEED` | 0.3D | `:159` = 0.3D | ✅ |
| `facePlayerDistance()` | 8.0F | `NpcEntity.java:129` = 8.0F，`protected` | ✅ |
| `animationTransitionTicks()` | 5 | `:118` = 5 | ✅ |
| `OPTION_HEIGHT` / `OPTION_GAP` | 20 / 8 | `NpcDialogueScreen.java:68-69` = 20 / 8 | ✅ |
| `NAME_Y` | 0.790 | `:45` = 0.790F | ✅ |
| `RULE_Y` | 0.822 | `:47` = 0.822F | ✅ |
| `RULE_HALF_WIDTH` | 0.165 | `:65` = 0.165F | ✅ |
| `TEXT_TOP` | 0.860 | `:55` = 0.860F | ✅（但"自下而上"表述错，见 M1） |
| `ARROW_Y` | 0.964 | `:57` = 0.964F | ✅ |
| `OPTION_LEFT` / `OPTION_WIDTH` | 0.661 / 0.22 | `:59` / `:61` | ✅ |
| `OPTION_BOTTOM` | 0.787 | `:63` = 0.787F | ✅ |
| `TEXT_MAX_WIDTH` | 0.80 | `:72` = 0.80F | ✅ |
| `LINE_GAP` | 2 | `:74` = 2 | ✅ |
| `GRADIENT_START` | 0.62 | `:77` = 0.62F | ✅（注意 `NpcDialogueScreen.java:278` 注释误写 0.72，见"越界发现"） |
| `GRADIENT_BOTTOM` | `0xE6000000` | `:79` = 0xE6000000 | ✅ |
| `charsPerTick` 默认 / 范围 | 1 / 1–20 | `Config.java:60` `defineInRange("charsPerTick", 1, 1, 20)`，CLIENT_SPEC | ✅ |
| `nameScale` 默认 / 范围 | 1.5 / 1.0–2.0 | `Config.java:69` `defineInRange("nameScale", 1.5D, 1.0D, 2.0D)`，CLIENT_SPEC | ✅ |
| `enabled` 默认 / 端 | true / SERVER | `Config.java:264-268`，`SERVER_SPEC` | ✅ |
| `HEAD_YAW_SIGN` | -1 | `DihuangLoongModel.java:52` = -1.0F | ✅ |
| 命令 op 等级 | `hasPermission(2)` | `NpcCommand.java:41` | ✅ |
| `NpcEntity.java` 行数（§八） | 373 | 373 | ✅ |
| `DihuangLoongEntity.java` | 49 | 49 | ✅（§九#1/§10.2 写"50 行"，见 m1） |
| `NpcAttackGoal.java` | 47 | 47 | ✅ |
| `NpcDialogueEntry.java` | 164 | 164 | ✅ |
| `NpcDialogueLoader.java` | 127 | 127 | ✅ |
| `NpcDialogueHandler.java` | 77 | 77 | ✅ |
| `NpcDialogueOpenPayload.java` | 85 | 85 | ✅ |
| `NpcDialogueScreen.java` | 460 | 460 | ✅ |
| `NpcDialogueOptionButton.java` | 147 | 147 | ✅ |
| `DihuangLoongRenderer.java` | 27 | 27 | ✅ |
| `DihuangLoongModel.java` | 118 | 118 | ✅ |
| `NpcCommand.java` | 121 | 121 | ✅ |
| `dihuang_loong.geo.json` | 196 KB | 200443 B = 195.7 KB | ✅ |
| `dihuang_loong.animation.json` | 477 KB | 488150 B = 476.7 KB | ✅ |
| `dihuang_loong.png` | 153 KB（256×256） | 156351 B = 152.7 KB；**PNG IHDR = 512×512** | ❌ 尺寸错（见 M6），大小正确 |
| `iron_golem.json` | 297 B | 297 B | ✅ |
| `zh_cn` / `en_us` 键数 | 各 226、键集合一致 | 226 / 226，差集为空 | ✅ |
| geo 骨骼 / 立方体 / 根骨骼 | 145 / 340 / `Magic`+`Dragon` | 145 / 340 / Magic, Dragon | ✅ |
| 动画总数 | 96 | 96 | ✅ |
| `idle` / `walk` 骨骼覆盖 | 32 骨骼/4.75s、63 骨骼/1.375s、0 缺失 | 32/4.75、63/1.375，差集为空 | ✅ |
| `run` 缺骨骼 | 77 骨骼/1s，缺 `Drip1-3` | 77/1.0，缺失恰为 Drip1、Drip2、Drip3 | ✅ |
| `identifier` | `geometry.unknown` | `geometry.unknown` | ✅ |
| 僵尸/铁傀儡/村民移速 | 0.23 / 0.25 / 0.5 | `Zombie.java:123` 0.23F、`IronGolem.java:92` 0.25、`Villager.java:270` 0.5 | ✅ |
| `MobCategory.MISC` 元组 | `("misc", -1, true, true, 128)` | `MobCategory.java:15` | ✅ |
| `bypasses_invulnerability` 仅两项 | `out_of_world` + `generic_kill` | 原始 tag JSON 恰为这两项，NeoForge 未扩展 | ✅ |
| `dihuang_loong` 碰撞箱等 | `sized(1.5F,2.5F)`、`clientTrackingRange(10)`、`fireImmune()` | `ModEntities.java:68-73` | ✅ |
| 对话系统行数（§八 注） | 655 | `dialogue` 包 453 / 含界面 1060 | ❌ 见 M4 |

## 已验证正确的部分

以下是我**主动核实过**并确认无误的点（每条附证据），不属于"没查"：

1. **§八 全部 12 个行数、4 个资产大小**：`(Get-Content $f).Count` 与 `Get-Item .Length` 逐项实测一致（见数值核对表；唯一例外是同一张表里的贴图**尺寸**列，见 M6）。
2. **语言键**：`zh_cn`/`en_us` 各 226 键（`ConvertFrom-Json` 后 `PSObject.Properties.Name`），`Compare-Object` 差集为空；代码侧引用的 9 个键（`npcDialogueEnabled/CharsPerTick/NameScale`、`dialogue.option.leave`、`command.npc.{walk,attack,stop,no_targets,not_living}`）、数据文件引用的 3 个键（`dialogue.iron_golem.{name,p1,p2}`）、隐式键（`entity.beloong.dihuang_loong` 来自 `descriptionId`、`configuration.npc_dialogue` 来自 `push("npc_dialogue")`）与 3 个 `.tooltip` 键**全部存在**；**未发现死键**。
3. **§4.1 资产统计**：geo 145 骨骼/340 立方体/根骨骼 `Magic`+`Dragon`/`identifier=geometry.unknown`；动画 96 个；`idle` 32 骨骼 4.75s、`walk` 63 骨骼 1.375s 与 geo 差集为空；`run` 77 骨骼 1s 且缺 `Drip1/Drip2/Drip3`——**与文档逐字吻合**（用 `ConvertFrom-Json` 做集合差集，未整份读文件）。
4. **§3.3 三个 goal 与优先级**：`NpcEntity.java:243-247` = `FloatGoal(0)` / `NpcAttackGoal(3)` / `LookAtPlayerGoal(5, Player.class, facePlayerDistance(), 1.0F)`；`MeleeAttackGoal.java:31` 确实 `setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))`，`LookAtPlayerGoal.java:38` 占 `Flag.LOOK` ⇒ "低优先级者被 `lockedFlags` 永久挡死"的推理成立（`GoalSelector` 仲裁机制未逐行读，但 Flag 冲突事实已核实）。
5. **§3.4 身朝链条**：`LookControl.java:76-80` 的 `clampHeadRotationToBody` 仅在 `!getNavigation().isDone()` 时生效；头部转速来自 `getHeadRotSpeed()`（`Mob.java:838-840` = 10）；`getMaxHeadYRot()`（`Mob.java:825-827` = 75）；`BodyRotationControl.java:26` 的 15° 阈值与 `:41` 的 `getMaxHeadYRot()` 夹取；`Mob.java:377-381` 覆写 `tickHeadTurn` 且不调 super；`LivingEntity.java:2525` 是唯一调用点 ⇒ 双端都跑。
6. **§3.4 第 3 条（yBodyRot 不同步）**：全量检索 `yBodyRot =` 赋值点，逐 tick 路径只有 `BodyRotationControl`（`:20/:41/:52`）与少数原版覆写者；网络侧唯一入口是 `LivingEntity.java:3717`（`recreateFromPacket`，即生成包）⇒ "不参与网络同步、客户端自己算"成立。
7. **§3.5 的移速模型**：`Player.java:231` = 0.1F；`Mob.java:557-560` = `setSpeed` 同时写 `zza`；`PathNavigation.java:312` 平方速度；僵尸/铁傀儡/村民 0.23/0.25/0.5 全部实测一致。
8. **§3.6 动画三档**：`NpcEntity.java:358-366` 谓词与文档表格逐字一致（`!isMoving()` → idle；`getAttributeValue > getAttributeBaseValue` → run；else walk），控制器名 `"main"`、过渡 `animationTransitionTicks()`；`RawAnimation` 确实在 `registerControllers` 里构建（`:355-357`）。
9. **§3.7 API 与兜底**：`walkTo` 固定档位 1.0 且在服务端守卫下清攻击指令；`attack(null)` 取消；`stopAction` 清全部；`clearMotionCommands` 只 `getNavigation().stop()`（`:316-318`）；`customServerAiStep` 的 `target == null || !isAlive() || !NO_CREATIVE_OR_SPECTATOR.test(target)` 与 `NpcAttackGoal.stop()→clearAttackCommand()` 并存的分工与文档一致。
10. **§3.2 语义三条**：`isInvulnerableTo` 表达式为 `!source.is(BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source)`；`isPushable()` 恒 false；`requiresCustomPersistence()` 恒 true；构造函数里同时 `setInvulnerable(true)` + `setPersistenceRequired()`。
11. **`bypasses_invulnerability` 内容**：原始 tag JSON 恰为 `minecraft:out_of_world` + `minecraft:generic_kill`，NeoForge 的 `data/**/tags/damage_type` 未扩展该标签 ⇒ "只放行 /kill 与虚空"成立。
12. **§4.2 Molang**：两个变量名与表达式口径（`Mth.wrapDegrees(headYaw-bodyYaw) * HEAD_YAW_SIGN`、`Mth.lerp(partialTick, xRotO, getXRot())`，均用 `rotLerp`/`lerp` 插值）与 `DihuangLoongModel.java:110-116` 一致；`MathParser.setVariable` + 惰性 supplier 的写法与注释一致。
13. **§4.3 注册点**：`ModEntities.java:67-73`（`beloong:dihuang_loong`、`MobCategory.MISC`、`sized(1.5F,2.5F)`、`clientTrackingRange(10)`、`fireImmune()`、无刷怪蛋/无自然生成）；`ModAttributes.java:96-99`（`EntityAttributeCreationEvent` → `DihuangLoongEntity.createAttributes().build()`）；`BeLoongCoreClient.java:91`（`registerEntityRenderer(ModEntities.DIHUANG_LOONG.get(), DihuangLoongRenderer::new)`）。`DihuangLoongRenderer` 确为最小实现（只有构造函数）。
14. **§5.3 加载**：`NpcDialogueLoader extends SimpleJsonResourceReloadListener`，目录串 `"beloong/npc_dialogue"`（`super(new Gson(), ...)`），注册于 `BeLoongCore.java:189-194` 的 `addServerReloadListeners`；服务端资源管理器 `PackType.SERVER_DATA`（`MinecraftServer.java:1511`）与客户端 `CLIENT_RESOURCES`（`Minecraft.java:491`）⇒ "同一字符串读不同树"成立；`ifError`/`ifSuccess`（非 `resultOrPartial`）、按 `ResourceLocation` 排序后"后处理者胜 + WARN"、扫描数/装载数 INFO 日志，逐条与 `NpcDialogueLoader.java:85-119` 一致。
15. **§5.3 不加 volatile 的论证**：`SimplePreparableReloadListener.java:17-19` 确为 `supplyAsync(prepare, backgroundExecutor)` … `thenAcceptAsync(apply, gameExecutor)`；`MinecraftServer.java:1512-1519` 确把 `this.executor, this` 传给 `ReloadableServerResources.loadResources`；`NpcDialogueLoader.java:73` 的 `entries` 无 `volatile`，且 `apply` **不碰** 被 `prepare` 使用的状态 ⇒ 结论成立。
16. **§5.4 受理链**：`NpcDialogueHandler.java:37-75` 的顺序（`isClientSide` → `enabled` → 主手 → 查表 → `EMPTY_HAND` 判手 → `instanceof ServerPlayer` → 发包）与文档一致，且 `isClientSide` 确实排在读配置之前（只有 `instanceof ServerPlayer` 一步文档未画，见 m4）；"原版服务端已做交互距离校验"成立（`ServerGamePacketListenerImpl.java:1594` 的 `player.canInteractWithEntity(aabb, 1.0)`）；"副手仅在主手 PASS 时才轮到"成立（`Minecraft.java:1720-1751` 遍历两手、`consumesAction()` 时 `return`）。
17. **§5.4 载荷与"线格式永不抛"**：`NpcDialogueOpenPayload` 字段为 `Optional<String> nameKey` / `String fallbackNameKey` / `List<Page> pages` / `int entityId`，**不含 `EntityType`**；`sound` 用 `ByteBufCodecs.STRING_UTF8.map(… tryParse …)`（非 `ResourceLocation#STREAM_CODEC`）；`trigger` 不上线。与文档 §5.4 表格逐条一致。
18. **§5.4 "客户端处理器默认在主线程"**：NeoForge 21.1.236 `PayloadRegistrar.java:29` = `private HandlerThread thread = HandlerThread.MAIN;`，`:44` 的三参 `playToClient` 用它 ⇒ 成立；`BeLoongCore.java:151-158` 注册两个包都不指定线程，与 `TreasureSyncPayload` 同款。
19. **§5.5 除状态机外的布局/渲染**：全部常量、`WHITE/GOLD/GOLD_BRIGHT` 取值、`LEAVE_KEY = "beloong.dialogue.option.leave"` 硬编码、三级名字回退、`renderBackground` 空实现、`isPauseScreen()=false`、`§` 代码对不切断、"选项最后渲染"的顺序（`:281-288`）均与源码一致。
20. **§5.6 / §七 配置**：三项的端与默认/范围全部正确（见数值核对表），分组键两端同为 `push("npc_dialogue")` ⇒ 复用同一 `beloong.configuration.npc_dialogue`（+`.tooltip`）键，模组菜单分组名一致（`Config.java:50`、`:262`）。
21. **§六 调试命令**：`/beloong npc walk <targets> <pos>` / `attack <targets> <victim>` / `stop <targets>` 三条与 `NpcCommand.java:40-61` 一致；`requires(hasPermission(2))`；`npcsIn` 过滤 `NpcEntity`；非 NPC 目标报 `beloong.command.npc.no_targets`，非生物目标报 `...not_living`；无 `run`/`turn` 子命令。
22. **§九 决策抽查 15 条**（#4、#5、#8、#10、#14、#16、#17、#19、#21、#25、#27、#30、#40、#44、#45、#47、#54）：除上面已单列的措辞问题外，**所述"现状"与源码一致**。其中 #19"`turn` 能力与配套符号已整类删除"经 grep 确认：`maxTurnPerTick`、`NpcTurnGoal`、`turnTo`、`setFacing` 在 `src/` 中出现 **0 次**（`RandomLookAroundGoal` 仅剩 `NpcEntity.java:229` 的一句"刻意没有"注释）。
23. **§十一 历史文档表**：表内 11 份文件**全部存在于磁盘**，且 `docs/plans/` 中 NPC 相关文档**恰好就是这 11 份**（无漏列、无幽灵条目）。
24. **文末引用的 4 个文档路径**（`docs/天灾维度总设计.md`、`龙宫维度总设计.md`、`龙宫天空渲染总设计.md`、`自制龙之生存技能总设计.md`）均存在。
25. **状态块引用的 4 个提交号**：`5cf140e`（撤销 RandomLookAroundGoal）、`0945a16`（撤销 tickHeadTurn 覆写）、`dc0d763`（通用基类）、`d7849e7`（对话迁到 data）在仓库中**全部存在且提交信息与文档描述相符**。
26. **§10.4 无测试集**：`src/test` 不存在（`Get-ChildItem` 无匹配）⇒ 成立。
27. **§5.5 顺带核实**：`NpcDialogueScreen.open()` 对空 `pages` 有防御性 return（`:135-137`），与"线载荷无副作用"的定位一致。

## 文档遗漏的内容

（源码里有、文档完全没提或只提了名字的）

1. **空 `pages` 的专门处理**：`NpcDialogueLoader.java:99-104` 会拒绝 `pages: []` 并打 ERROR（见 m6）。
2. **`NpcDialogueOptionButton` 的全部视觉规格**：配色、横向渐隐、悬停插值、9×9 手绘图标、为什么继承 `AbstractWidget`（见 m7）。文档只给了它 147 行这个数字。
3. **`NpcDialogueScreen` 的其余视觉常量**：`ARROW_RADIUS=7` / `ARROW_TRI_WIDTH=7` / `ARROW_TRI_HEIGHT=4`、箭头 `sin(tickCount*0.15)*2` 的上下浮动、`fillDiamondOutline`/`fillTriangleDown` 手绘（不引贴图）的策略、`init()` 在 `SHOWING_OPTIONS` 态下重建选项（窗口尺寸变化时的修复）。
4. **`applyMolangQueries` 的插入时机与原因**：文档说了"用 `applyMolangQueries` 而不是手动改骨骼"，但没说"此刻 `state.getController()` 为 null，必须用惰性 supplier"这一实现约束，也没说 Molang 变量是**全局静态**的、每实体每帧都要重设。
5. **`DihuangLoongModel` 的几何事实**：`applyMolangQueries` 是**模型侧**（`GeoModel`）而非实体侧，以及"不抽模型基类"的裁定（文档 §2.1 只说"通用 NPC 基类"，未说明模型侧不抽象）。
6. **对话系统的注册点清单**：payload 注册（`BeLoongCore#registerPayloads` 里 `playToClient`）、事件总线注册（`NeoForge.EVENT_BUS.register(new NpcDialogueHandler())`，`BeLoongCore.java:118`）只在散文里出现过一次，没有与 §4.3 对称的"注册点/删除点"表。
7. **`NpcDialogueOpenPayload` 的 `mapStream` 说明**：源码注释专门解释了"不是必需的、只为与 `TreasureSyncPayload` 风格统一"，文档未收录（属可选补充）。
8. **`NpcAttackGoal` 的 20 tick 限流**：源码注释指出"下令后最多可能等 1 秒才起步"，这是使用 API 时容易被误判为 bug 的点，文档 §3.7/§六 未提。
9. **`NpcEntity` 的 `getAnimatableInstanceCache` / `cache` 字段**（GeckoLib 必需件），§2.3 包结构未列（不重要，仅登记）。

## 越界发现

> 不在本次审计范围内（不是文档的问题），只报告、未修改。

1. **`NpcDialogueScreen.java:278` 注释与代码不符**：注释写"渐变从 **0.72** 屏高开始"，而 `GRADIENT_START = 0.62F`（`:77`）。上方的 `OPTION_BOTTOM = 0.787` 也提示 0.62 才是对的。建议把注释改成 0.62。
2. **`NpcDialogueLoader.java:91` 注释疑似笔误**：写的是"日志还谎称该文件解析**失败**"，按上下文应为"谎称解析**成功**"（文档 §5.3 的对应段落写的是"谎称解析成功"，是对的）。
3. **`NpcEntity.java:178` / `:46-47` 原版行号错误**（与 C3 同一批），修改文档时必须一并改源码注释，否则文档与代码注释仍互不印证。
4. **`DihuangLoongModel.java:97-98` 引用 `GeoEntityRenderer.java:268` 说"才 new 出 state"**：在可得的 GeckoLib 4.7.7 源码里该构造在 `:262`（`withController` 更晚）。4.9.2 源码不在缓存中，无法判定；若 4.9.2 未变，这个行号也需要改。
5. **GeckoLib 版本与源码可得性**：`build.gradle:139` 用的是 curse 坐标 `geckolib-388172:8350073`，其 `META-INF/neoforge.mods.toml` 声明 `version="4.9.2"`，jar 内 **0 个 `.java`**（编译产物，无 sources）；本地缓存里只有 `software.bernie.geckolib:geckolib-neoforge-1.21.1:4.7.5.1/4.7.7` 的 sources。凡文档/注释引用 GeckoLib 行号处（`GeoEntityRenderer.java:266-268`、`AnimationController.java:747-761`、`MolangQueries.java:148-150/286`、`ModelProperties.java:35`），**本次无法逐行核实**；建议在文档里注明所依据的 GeckoLib 版本，或改用带 sources 的坐标，便于下次核对。
6. **`iron_golem.json` 的声音事件缺失**（已计入 C6，此处登记为资源侧问题）：接配音前必须补 `sounds.json`。

---

## 核对方法（可复现）

```powershell
# 1) 原版源码：定位 1.21.1 补丁后源码 jar，并按行打印
Get-ChildItem "$env:USERPROFILE\.gradle\caches\neoformruntime\intermediate_results" -Filter 'sourcesAndCompiledWithNeoForge_*.jar'
#    用 System.IO.Compression.ZipFile 打开，读 net/minecraft/... 条目后按行号切片
#    （本例用 sourcesAndCompiledWithNeoForge_ec9b86340cfa34d24e99524bcb732bfdd44c7f42_output.jar；
#      四个 jar 的 SharedConstants.VERSION_STRING 均为 "1.21.1"，且抽查的 3 处关键行号（1143/461/1854）在四个 jar 上一致）

# 2) 行数 / 大小 / PNG 尺寸
(Get-Content <file>).Count ;  (Get-Item <file>).Length
$b=[System.IO.File]::ReadAllBytes(<png>)
$w=[int]$b[16]*16777216+[int]$b[17]*65536+[int]$b[18]*256+[int]$b[19]   # 512
$h=[int]$b[20]*16777216+[int]$b[21]*65536+[int]$b[22]*256+[int]$b[23]   # 512

# 3) 语言键
(Get-Content zh_cn.json -Raw | ConvertFrom-Json).PSObject.Properties.Name
Compare-Object $zh $en

# 4) 骨骼差集
$geo = (Get-Content geo/dihuang_loong.geo.json -Raw | ConvertFrom-Json).'minecraft:geometry'[0].bones.name
$anim = (Get-Content animations/dihuang_loong.animation.json -Raw | ConvertFrom-Json).animations.run.bones.PSObject.Properties.Name
$anim | Where-Object { $_ -notin $geo }        # → Drip1, Drip2, Drip3

# 5) 死符号探针
Select-String -Path (Get-ChildItem -Recurse -Filter *.java src\main\java).FullName `
  -Pattern 'maxTurnPerTick|NpcTurnGoal|turnTo|setFacing'   # → 0 命中
```
