# BeLoong-Core 代码审查缺陷清单（PR #10「龙卷风」龙技能）

> **审查日期：** 2026-09-18
> **审查范围：** PR #10 `feat(tornado): 新增「龙卷风」龙技能`，已合并为 `068b9c0`（20 文件 / +3081 −1）
> **对照源码：** NeoForge 21.1.236 反编译源（`build/review-src/neoforge-21.1.236/`）· Dragon Survival 2.0.67（参考源码树 + 依赖 jar）
> **说明：** 本清单只列**缺陷**，不含背景、方法学与已核验无误的部分。
> **分道原始报告：** `build/review-src/pr10/lane-L1.md`（服务端实体）· `lane-L2.md`（客户端渲染）· `lane-L3.md`（技能与数据）· `lane-L4.md`（文档与工具）

**共 23 条：严重 0 · 中等 4 · 轻微 19。**

---

## 处置记录

> **2026-09-18 —— 第 1 条已解决（含一次按反馈返工）。**
> 技能图标缺口由用户提供美术后处理入库，最终交付：
> `tornado_0.png`（**黑白**，底为浅灰 `rgb(205,205,205)`）与 `tornado_1.png`（全彩，底为 `#ADD8E6` 淡蓝），
> 均为 32×32、完全不透明，写入
> `src/main/resources/assets/dragonsurvival/textures/gui/sprites/abilities/beloong/`；
> `tornado.json` 的 `icon.texture_entries` 只引用这两个文件（`from_level` 2..6 由 `tornado_1` 承接）。
> **几何**：风口实体框 **30×32（占画布 94% × 100%）** —— 上下顶满、左右比自然宽度拉长约 9%，与仓库既有图标的满幅风格一致。
> **素材**：`preview/ChatGPT Image 2026年9月13日 19_43_22.png`。
>
> 返工原因记录（避免后人重踩）：初版按 `alpha > 0` 的包围盒（1133×1187）缩放，而该盒被边缘
> **alpha < 16（约 6% 不透明度）的微弱丝状像素**撑大，导致实体只占 24×25（75% × 78%）、上下明显没顶满。
> 改用「每行/列 alpha ≥ 16 的像素数 ≥ 2」判定的**实体框**（783×909，纵横比 0.861）后问题消失。
> 缩放全程在**预乘 alpha** 下进行（源图 alpha 为 0–255 连续，直接缩放会在半透明边缘平均出黑晕）。
>
> **复核**：三份龙技能 JSON 的图标引用全部解析成功；`gradlew build` 通过；两个 PNG 已进 jar（1737 B / 2586 B）。
> **其余 22 条仍待处置。**

---

## 一、严重（0 条）

**无。** 未发现会导致崩溃、数据损坏、服务端启动失败、存档破坏或可被玩家利用的缺陷。

被特别核对过、确认不成立的三处高危猜想：

- **两个原版伤害标签不会被清空**。`data/minecraft/tags/damage_type/{bypasses_cooldown,no_knockback}.json` 未写 `"replace"` ⇒ 默认 `false`（`TagFile.java:12`），而 `TagLoader.java:49-55` 遍历的是**资源栈**、只在 `replace == true` 时 `clear()` ⇒ 结果是原版内容与 `beloong:tornado` 的并集。**不是**经典事故。
- **伤害结算周期确实是 4 刻 / 5 次每秒**（`TornadoEntity.java:262-265` 自减再判定、命中重置为 4，逐 tick 推演命中于 1,5,9,…,97）。`6b5c3ed` 的修复有效。
- **合并解决本身无缺陷**。`git diff 111ed36 master` 为空；`BeLoongCoreClient.java` 中分支残留的 `disasterPortalShader` / `RegisterShadersEvent` / `DefaultVertexFormat` / `onRegisterShaders` 已全部不存在，天灾传送门的 `RenderType.entitySolid` 标准管线版本被保留，龙卷风的 3 个 import + `registerEntityRenderer` + `registerLayerDefinitions` 齐备；`BeLoongCore.java` 的 `ModEntities` 注册与 master 侧 `ModSounds`/`ModParticles`/`ModCriteria` 均未被误删。

---

## 二、中等（4 条）

### 1. 技能 JSON 引用的 6 张图标贴图在合并后不存在，技能图标必然显示为缺失贴图

- **位置：** `src/main/resources/data/dragonsurvival/dragonsurvival/dragon_ability/tornado.json:61-82`；`docs/superpowers/specs/2026-09-17-tornado-ability-design.md:18,333`；`docs/superpowers/plans/2026-09-17-tornado-ability.md:1109,1365,1400`
- **问题：** `icon.texture_entries` 逐级引用 `dragonsurvival:abilities/beloong/tornado_0` … `tornado_5`，但 PR 的最后一个提交 `67b2155`「移除 6 张占位技能图标（由作者另行提供）」把这 6 个 PNG 删掉了，**且未 revert、也未同步改 JSON**。实测：`assets/dragonsurvival/textures/gui/sprites/abilities/beloong/` 下只有 `air_strike_0..5.png` 与 `tp_loong_palace_0..1.png`；把仓库内全部 3 份龙技能 JSON 的 `texture_resource` 逐条查存在性，**只有 `tornado.json` 的 6 条全部悬空**，另两份全部解析成功。
- **后果：** 不崩客户端——`TextureAtlas.getSprite` 是 `getOrDefault(name, missingSprite)`（`TextureAtlas.java:141-147`），缩放元数据也有 `GuiMetadataSection.DEFAULT` 兜底。但技能图标在**快捷栏**（`MagicHUD.java:314`）、**施法条**（`:464`）、**技能界面按钮**（`AbilityButton.java:248,253`）、**提示框**（`AbilityAndPenaltyRenderTooltip…:201`）四处显示为原版缺失贴图方块；`plan:1400` 的验收条目「图标是青色龙卷风」永远不可能通过。
- **修法：** 三选一 ——（a）补入 6 张（跑 `tools/make_tornado_icons.py` 生成占位图，或由作者提供成品）；（b）在成品到位前把 `texture_resource` 临时指向已存在的贴图（如 `dragonsurvival:abilities/beloong/air_strike_0`）。**注意不能"删掉 icon 段走默认图标"**：`icon` 是必填字段（`DragonAbility.java:61` 的 `LevelBasedResource.CODEC.fieldOf("icon")`）。另需同步 spec §7 表、plan Task 6 Step 8 与勘误 6（它们都称图标已交付）。
- **修法 (a) 可行性已实测：** 用该脚本重新生成的 6 个产物与 `67b2155` 删除的 6 个 git blob **哈希逐一相同**（6/6），即一条命令即可精确恢复占位图状态，零美术成本。

### 2. plan Step 9 的 L2 期望值是初稿值，且要求把 `experience_cost` 改回 `1.0`

- **位置：** `docs/superpowers/plans/2026-09-17-tornado-ability.md:1464-1467,1470`
- **问题：** 该步写「每刻伤害 `0.75`（L1 是 0.5）／吸引半径 `9.0`（L1 是 8）／伤害半径 `4.5`（L1 是 4）」，并在验证结束时要求「把 `experience_cost` 改回计划里的原值 `[1.0, 10.0, 100.0, 1000.0, 10000.0]`，再 `/reload`」。而 `tornado.json:9-28` 的实际值是 `base 2.0 +1.0`（L2 = 3.0）、吸引 13.0、伤害 6.5；同一份文档的勘误 item 2 还明确记着「`values[0]` 由 `1.0` 改为 **`0.0`**，否则生存模式 0 经验下授予的技能不可用」。
- **后果：** 照 Step 9 走一遍会得到「等级缩放坏了」的错误结论；最后一步更是把**用户已裁定过的修复主动回滚**——0 经验新手拿到技能后不可用。
- **修法：** L2 期望值改为 `3.0 / 115 / 13.0 / 6.5`；末句改成「改回 `[0.0, 10.0, 100.0, 1000.0, 10000.0]`」。

### 3. Task 7 验收仍写「穿过地形与生物时不被阻挡」，与最终「撞方块镜面反弹」相反

- **位置：** `docs/superpowers/plans/2026-09-17-tornado-ability.md:1416`；同源残留 `docs/superpowers/specs/2026-09-17-tornado-ability-design.md:484`
- **问题：** 勘误 item 3 只作废了 **Task 2** 的「不调用 `move()`，所以不与方块碰撞」，**没有覆盖 Task 7 的验收清单**。而实体实现是 `advance()` 里 `this.move(MoverType.SELF, delta)` + 逐轴速度取反（`TornadoEntity.java:207-231`），spec 勘误 7 也明确「撞方块镜面反弹」。
- **后果：** 按 Task 7 验收的人会把**正确行为**（撞墙反弹）判为缺陷，反向"修"回穿墙——而穿墙正是用户试玩后要求改掉的手感问题。
- **修法：** Task 7 Step 3 改为「撞到地形时镜面反弹、不衰减；生物可穿透」；spec:484 的 out-of-scope 条目补一句「不含实体碰撞/伤害」。

### 4. 图标生成脚本留在仓库，且计划仍引导把它直接写进正式贴图目录（无备份、无存在性检查）

- **位置：** `tools/make_tornado_icons.py:59-67`；`docs/superpowers/plans/2026-09-17-tornado-ability.md:1196-1206`
- **问题：** 该脚本的产物（6 张占位图）已从交付中删除（见第 1 条），但脚本仍在仓库里，且 plan Task 6 Step 2 给出的命令是**直接写进正式 sprite 目录**（`plan:1200` 的目标是 `src\main\resources\assets\dragonsurvival\textures\gui\sprites\abilities\beloong`），Step 3 还要求「若有异议…**重跑 Step 2**，直到用户确认」。脚本本身 `:59-67` 只接一个输出目录参数，`os.makedirs(..., exist_ok=True)` 后无条件 `save()`，**无存在性检查、无备份、无 `--force`、无确认**。而勘误 6 说这 6 个文件名将由**用户手绘**替换。
- **后果：** 用户手绘好 `tornado_0..5.png` 之后，任何人（或任何照 plan 执行 Task 6 的 agent）跑一次 Step 2，就会把这 6 张美术**无声覆盖**成占位漏斗图——实测该脚本产物与 `67b2155` 删除的 6 个 blob 哈希逐一相同，即覆盖结果就是当初那批占位图。项目对这类脚本已有明确约定要求覆盖前备份——`.gitignore:54-55` 专门忽略了 `**/.backup-before-convert/`，`memory/learned-patterns.md:570-585` 记录过 2026-09-14 的同类真实事故并据此给脚本加了自动备份。本脚本未遵守该约定。
- **修法：** 优先**删除** `tools/make_tornado_icons.py`（其产物已不属于交付）；若必须保留，加 `--force` 开关 + 覆盖前备份到 `.backup-before-convert/` + 在 docstring 与 `tools/README.md` 写明覆盖风险，并把 plan Task 6 Step 2/3 与勘误 6 改成「占位图已移除，图标待用户手绘，勿重跑旧脚本」。

---

## 三、轻微（19 条）

### 5. `ANIM_MODULO = 3600` 不是 2π 的整数倍，回绕那一帧四层各自突跳；且该取模在寿命上限下毫无必要

- **位置：** `src/main/java/com/zonlong/beloong/client/TornadoRenderer.java:46,82`；`client/model/TornadoModel.java:91-100`
- **问题：** `ageInTicks = (entity.tickCount + partialTick) % ANIM_MODULO`，而 `3600 / 2π ≈ 572.96` 不是整数。四层绝对角速度 1.0 / 0.5 / 0.3 / 0.6 rad·tick⁻¹ 互不相同，回绕瞬间模 2π 后分别突跳约 **15° / 172° / 39° / 79°**；摆动项 `sin(ageInTicks * 0.25)` 同时归零。注释声称取模是为了「避免长时间累加导致浮点精度下降」，但寿命上限只有 **160 刻**（`tornado.json:14-18` 的 `base 100 + 15/级`，`maximum_level: 5`），精度根本不成问题。
- **后果：** 正常玩法不可达（160 刻 ≪ 3600）。可达路径只有把寿命调到 > 3600 刻：`/summon beloong:tornado {Life:100000}`——**正是本 PR 计划自带的目视验证命令**（`plan:931`），长看会观察到一次层间错位。任何把该实体寿命调大的数据包/整合也会命中。
- **修法：** 推荐直接删掉取模；若保留，必须取 40π 的整数倍（使 1.0/0.5/0.3/0.6×M 与 0.25×M 全部是 2π 的整数倍）。

### 6. 消散缩小没有用 `partialTick` 插值，实际是 20 级台阶

- **位置：** `src/main/java/com/zonlong/beloong/client/TornadoRenderer.java:69-73`
- **问题：** `getRemainingLife()` 返回 `int`，`shrink = remainingLife / DESPAWN_TICKS` 每秒只有 20 个离散取值、每刻跳 5%（约 0.16 格），跳变时刻与渲染帧不对齐。同一方法里的自转已经用了 `partialTick`，只有缩放没有。
- **后果：** 仅观感——1 秒的消散过程呈 20 级台阶收缩，近距离能看出跳格。不涉及崩溃、同步或存档。
- **修法：** `float life = entity.getRemainingLife() - partialTick;` 再 `shrink = Mth.clamp(life / DESPAWN_TICKS, 0.0F, 1.0F)`。

### 7. `damageTimer` 未持久化，跨区块复载 / 存档重进后第一个 tick 必定额外命中一次

- **位置：** `src/main/java/com/zonlong/beloong/entity/TornadoEntity.java:119,262-265,372-376`
- **问题：** `addAdditionalSaveData` 存了 `DamagePerHit / PullRadius / DamageRadius / PullStrength / Life` 五项，**没有 `DamageTimer`**；`readAdditionalSaveData` 也不重置它。字段恒为默认值 `0`，复载后第一个 tick `--this.damageTimer <= 0` 立即为真 ⇒ 立刻结算一次。
- **后果：** 区块卸载再复载或存档重进时比正常节奏多打一次（单次 2~6 点，视等级）。量级很小但可稳定复现，与 `:94-97`「此后间隔恒为本值」的声明不符。
- **修法：** 存档一并存取 `damageTimer`；或在 `readAdditionalSaveData` 末尾显式 `this.damageTimer = DAMAGE_INTERVAL_TICKS;`。

### 8. 主人掉线后 `getOwner()` 返回 null，友军/宠物/召唤物豁免**全部**失效

- **位置：** `src/main/java/com/zonlong/beloong/entity/TornadoEntity.java:348-351,331`
- **问题：** `canAffect` 在 `owner == null` 时直接 `return true`，跳过队友、原版宠物、DS 召唤物的全部过滤；`hurtTarget` 的 `new DamageSource(type, this, this.getOwner())` 间接实体同时变成 null。依据 `Projectile.java:52-63`：`cachedOwner.isRemoved()` 后回退 `serverlevel.getEntity(ownerUUID)`，解析不到就返回 `null`；施法者是玩家（`TornadoEffect.java:85-87` 传 `dragon`），掉线即 `isRemoved()`。
- **后果：** 施法者掉线后残留的龙卷风（寿命最长 160 刻）会对自己队友、自己的驯服宠物、自己召唤的 DS 召唤物全额结算伤害并强行改写其速度；伤害归因同时退化为无主（击杀进度与掉落归属丢失）。多人服上「掉线前放的风咬了队友的狗」可复现。spec 明文要求友军免疫（`specs/…-design.md:30`）。
- **修法：** `owner == null` 时保守处理直接 `return false`；或把队友/宠物过滤改为基于 `ownerUUID`（不依赖实体是否加载）。

### 9. 两处注释把「重置成 `N-1`」的因果方向写反

- **位置：** `src/main/java/com/zonlong/beloong/entity/TornadoEntity.java:96-97` 与 `:260-261`
- **问题：** 两处都声称「命中后重置成 `N-1` 会让间隔变成 5 刻（4 次/秒、DPS 低 20%）」。按当前代码形状（`:262-265` 先 `--timer` 再判定、命中后重置）逐 tick 推演：重置为 `N=4` → 命中 tick `1,5,9,…` 间隔 **4**；重置为 `N-1=3` → 命中 tick `1,4,7,…` 间隔 **3**。**重置成 `N-1` 会让间隔缩短到 3 刻（更快、DPS 高 33%），不是变慢到 5 刻。**
- **后果：** 纯注释错误，不影响运行。但这是本项目头号缺陷类（声明与事实脱节），且 `:96-97` 就在常量 `DAMAGE_INTERVAL_TICKS` 的 javadoc 里、更容易被当作权威读；照该方向调参会把伤害改快 33% 而非变慢。
- **修法：** 两处都改为「重置为 `N` 得到间隔 `N`；重置为 `N-1` 会变成间隔 `N-1`（更快）」。

### 10. 两个原版伤害标签省略 `"replace": false`，与本仓库既有约定不一致

- **位置：** `src/main/resources/data/minecraft/tags/damage_type/bypasses_cooldown.json:1-5`、`.../no_knockback.json:1-5`
- **问题：** 两个文件只写了 `{"values": ["beloong:tornado"]}`。当前语义**没有错误**（缺省即 `false` ⇒ 只追加不清空），但本仓库修改原版标签的 3 个既有文件都显式写了 `"replace": false`（`data/minecraft/tags/block/dragon_immune.json:2`、`needs_netherite_tool.json:2`、`mineable/pickaxe.json:2`），`memory/learned-patterns.md:1016` 也把这写成了约定。
- **后果：** 现无错误行为。风险是语义隐式依赖原版默认值：后来者若照抄其他模组常见的 `"replace": true`，会**静默**清空 `minecraft:no_knockback` / `minecraft:bypasses_cooldown` 的全部原版条目，且不报错。
- **修法：** 两个文件各补 `"replace": false`。**不要**改成「放进 `data/beloong/tags/…` 再用 `#tag` 引用」——原版直接判的是 `minecraft:` 命名空间的这两个标签（`LivingEntity.java:1190`、`:1241`），模组自建标签无法间接塞进原版判定。

### 11. 文档里的数据包验证门禁检查的日志路径不存在，门禁恒为空验证

- **位置：** `docs/superpowers/specs/2026-09-17-tornado-ability-design.md:466`；`docs/superpowers/plans/2026-09-17-tornado-ability.md:41,133,1356`
- **问题：** 四处要求 `Select-String -Path "run\client\logs\latest.log" -Pattern "Failed to parse|Registry loading errors"`，零命中即通过；`plan:41` 还把它写成「数据包 JSON 验证不能只做语法检查」的**唯一**门禁。但 `run\` 下只有 `run\logs\latest.log`，**不存在 `run\client` 目录**；`build.gradle:80-88` 的 `runs { client { client() } }` 没有设 `gameDirectory`（`:94` 那行 `gameDirectory = project.file('run-data')` 是注释），运行目录就是 `run/`。全仓库只有这 4 处（全部在本 PR 文档里）用 `run\client\logs`。
- **后果：** 路径不存在时 `Select-String` 报的是「找不到路径」，操作者极易把「没有命中行」当成通过 ⇒ 声称拦住了注册表加载错误的门禁实际什么都没检查。
- **修法：** 四处统一改为 `run\logs\latest.log`。

### 12. `/summon` 验证命令仍在用已废弃的 NBT 键 `DamagePerTick`，照抄只会得到 0 伤害

- **位置：** `docs/superpowers/plans/2026-09-17-tornado-ability.md:509,532,931`；`docs/superpowers/specs/2026-09-17-tornado-ability-design.md:104,188`
- **问题：** 文档给的调试命令是 `{…,DamagePerTick:0.5f,…}`，而实体实际读写的是 `DamagePerHit`（`TornadoEntity.java:372,382`）；`DamagePerTick` 在整个 `src/` 下**零命中**。`spec:188` 还把存盘键列成小写下划线的 `damage_per_tick / pull_radius / …`，实际全是 PascalCase。
- **后果：** 键名不存在时原版静默忽略该 NBT ⇒ 龙卷风伤害恒为 0 ⇒ `plan:537` 期望的「僵尸血条连续下降」不会发生，实施者会误判为 `bypasses_cooldown` 没生效而去改伤害 tag（Task 1 的产物），是一次典型的假故障排查。
- **修法：** 三处命令改为 `DamagePerHit`；`spec:104` 的字段名与 `spec:188` 的键名清单同步更正。

### 13. spec 数值表与 §8 JSON 片段仍是初稿：残留 `damage_per_tick`，照抄会解析失败

- **位置：** `docs/superpowers/specs/2026-09-17-tornado-ability-design.md:43,353-358`；`docs/superpowers/plans/2026-09-17-tornado-ability.md:967,1021-1242`
- **问题：** 勘误 1/2/5 已把 `damage_per_tick` 改名为 `damage_per_hit` 并重标定为 2.0…6.0，但 spec 数值表仍留一行 `damage_per_tick | 0.5 | 1.5 | per_level 0.25`，§8 的 JSON 片段仍是 `0.5/0.25`、`pull_radius base 8`、`damage_radius base 4`、`speed 0.30`、`pull_strength 0.35`、`values[0] 1.0`；plan 的 Task 5 Interfaces 与 Task 6 片段同样未更新。
- **后果：** 同一张数值表里另外 4 行都已写成最终值并注明「试玩后上调」，只有伤害一行停留在初稿 ⇒ 读者会把它当现值。更实际的风险是 `damage_per_hit` 在 `TornadoEffect.java:58` 是**必填**字段（`fieldOf` 而非 `optionalFieldOf`），整合包作者以 spec §8 为模板复制会直接报 `No key damage_per_hit` 而加载失败。勘误的措辞只覆盖「数值」，没点明字段改名与必填键缺失的后果。
- **修法：** 数值表伤害行改为 `damage_per_hit | 2.0 | 6.0 | linear base 2.0, per_level 1.0`；把 §8 与 plan 的 JSON 片段整体替换为当前 `tornado.json` 内容，或删掉片段只留路径。

### 14. spec 声称 AoE 上下范围固定 `[y−1, y+4]`，实际上探是 `max(4, 半径)`（L1 即 12 格）

- **位置：** `docs/superpowers/specs/2026-09-17-tornado-ability-design.md:142-151,477`；`src/main/java/com/zonlong/beloong/entity/TornadoEntity.java:272-274`
- **问题：** spec 的伪代码与结论都写「上下范围固定 `[y−1.0, y+4.0]`」「墙后 **4 格**内的僵尸照样会被吸」。实际代码是 `getY() + Math.max(AREA_UP, radius)`，而 `radius = max(pullRadius, damageRadius)` ⇒ 上探 **12 格**（L5 为 16 格）。数字 4 只对应 damage_radius 的初稿值。
- **后果：** 按「上探 4 格」评估垂直覆盖面（例如悬停 8 格的飞行怪会不会被吸）会得出错误结论。
- **修法：** 改成「下探 1 格、上探 `max(4, 半径)`」；`spec:477` 的「4 格」改为「吸引半径内（L1 为 12 格）」。

### 15. plan 勘误块内部自相矛盾：`speed` 同时被写成 0.30 与 0.15

- **位置：** `docs/superpowers/plans/2026-09-17-tornado-ability.md:26` vs `:27`
- **问题：** 勘误 item 3 写「`speed` 由 `0.45` 下调至 **`0.30`**」（陈述为当前结论），item 5 又写「`speed` 经 0.45→0.30→0.22→0.15 多轮试玩调整…权威来源是 JSON」。JSON 是 `0.15`（`tornado.json:29`）。顺带该勘误的条目编号顺序是 1、2、3、**5、4**。
- **后果：** 只看 item 3 会以 0.30 为准（且 item 3 同时作废了 Task 2 的两处验证，可信度显得更高）；同一区块内两个相反的当前值会让后续调参失去基线。
- **修法：** item 3 的 0.30 改为「速度经多轮下调，最终见 item 5 / JSON 的 0.15」，并理顺编号。

### 16. spec 与 plan 引用的一手来源路径在本机都不存在

- **位置：** `docs/superpowers/specs/2026-09-17-tornado-ability-design.md:54-55`；`docs/superpowers/plans/2026-09-17-tornado-ability.md:638`
- **问题：** spec 自称「以下都从本机反编译的 1.21.1 源码读出来，不是推测」，给的是 `…\dabrdragonsurvivalcompat\前置源代码\…\steps\unzipSources\unpacked\`；plan Task 3 Step 2 的重上色命令硬编码源图 `D:\wdsjlzscsj\源代码\…\koboleton\sandstorm.png`。两条路径在本机均不存在（`dir /s /b /ad` 全零命中、`Test-Path` 为 False）。
- **后果：** plan Task 3 Step 2 的命令照抄必然失败（源图找不到）；spec「已验证的底层事实」表里的行号引用也无法复核。实际可用的一手来源是 `D:\Minecraft\开源模组参考文件\Cataclysm\src\main\resources\assets\cataclysm\textures\entity\koboleton\sandstorm.png`（以此重跑 `recolor_tornado_texture.py`，产物与提交的 `tornado.png` **SHA256 逐字节相同**）与 gradle 的 neoform 缓存 jar。
- **修法：** 两处改成上述真实路径，或写「见 A 表来源」。

### 17. plan 的 Tech Stack 写的 Dragon Survival 版本与实际依赖 jar 不符

- **位置：** `docs/superpowers/plans/2026-09-17-tornado-ability.md:9`；`build.gradle:137`
- **问题：** plan 写「Dragon Survival **2.0.69** API」，而 `build.gradle:137` 锁定的 `curse.maven:dragons-survival-420799:8726322` 该 jar 内 `META-INF/neoforge.mods.toml` 的 `version = "2.0.67"`。同一行的 Minecraft 1.21.1 / NeoForge 21.1.236 / Parchment 2024.11.17 / ModDevGradle 2.0.141 均与 `gradle.properties` 一致。
- **后果：** 按 2.0.69 核对 API 契约（如 `AbilityEntityEffect` 的签名）可能对不上，排错时会怀疑错对象。
- **修法：** 写成 `2.0.67`，或直接写 curse file id `8726322`（更能保证可复现）。

### 18. spec 的验证清单给了一个不存在的命令 `/dragon ability grant`

- **位置：** `docs/superpowers/specs/2026-09-17-tornado-ability-design.md:469`
- **问题：** spec 写「用 `/dragon ability grant` 或临时给 species tag 后，按技能键释放」；plan Task 7 Step 2 已更正为「命令是 `/dragon-ability`（**一个**带连字符的指令名），不是 `/dragon ability`」，但 spec 侧没同步。DS 源码实为 `Commands.literal("dragon-ability")` + `Commands.literal("add")`（`DragonAbilityCommand.java:43,51`）。
- **后果：** 照 spec 验证的人会先撞一条「未知命令」，再去找 species tag 绕路。
- **修法：** 改成 `/dragon-ability add @s dragonsurvival:tornado`。

### 19. spec §10 的 lang 键表漏列实际交付的 `entity.beloong.tornado`

- **位置：** `docs/superpowers/specs/2026-09-17-tornado-ability-design.md:442-448`；`assets/beloong/lang/zh_cn.json:207`、`en_us.json:207`
- **问题：** §10 把语言文件列成 5 个键，实际每个文件追加了 **6** 个（多 `entity.beloong.tornado`，plan Task 6 Step 5 里有、spec 侧漏了）；`plan:56-57` 的 Task 1 Files 又写「追加 2 个键」，与实际 5+1 分批的最终形态也不一致。
- **后果：** 审阅者按 spec 核对语言键时会以为多了一个无用键，或反过来以为 `entity.beloong.tornado` 没做。
- **修法：** §10 表补一行 `entity.beloong.tornado | 龙卷风 | Tornado`，并把 Task 1/Task 6 的分批数量写清。

### 20. `.gitignore` 的 `tools/` 忽略了本 PR 交付的两个脚本，文档却把它们写成普通交付物

- **位置：** `.gitignore:49`；`docs/superpowers/plans/2026-09-17-tornado-ability.md:554,1110`；`docs/superpowers/specs/2026-09-17-tornado-ability-design.md:334`
- **问题：** `git check-ignore -v --no-index tools/make_tornado_icons.py` 命中 `.gitignore:49:tools/`，而 `git ls-files` 显示这两个脚本是**已跟踪**状态 —— 即它们只能靠 `git add -f` 落地。文档却把它们写成普通交付物，没有任何 `-f` 提示（`memory/project-context.md:153` 也记着「`tools/` is gitignored」）。
- **后果：** 新克隆/新分支上照 plan 添加工具脚本时，`git add tools/xxx.py` 会被**静默跳过**，脚本不会进 PR；后续开发者也会误以为「`tools/` 下的脚本都不入库」。
- **修法：** 给两个脚本加白名单（`!tools/make_tornado_icons.py`、`!tools/recolor_tornado_texture.py`），或把工具挪到不被忽略的目录，并在文件清单处注明忽略规则。

### 21. `registerLayerDefinitions` 的 javadoc 引用了上游并不存在的异常文案

- **位置：** `src/main/java/com/zonlong/beloong/BeLoongCoreClient.java:96`
- **问题：** 注释写 `{@code IllegalArgumentException: Model layer ... not registered}`；上游实际抛的是 `new IllegalArgumentException("No model for layer " + modelLayerLocation)`（`EntityModelSet.java:18`，实际打印如 `No model for layer beloong:tornado@main`）。
- **后果：** 仅排障成本——照注释文案去搜日志或上游源码会一无所获。
- **修法：** 改成与上游一致的文案，或不引具体字符串。

### 22. 计划里的具名常量 `MODEL_UNITS_PER_POSE_UNIT` 在实现中被内联成魔数 `16.0F`

- **位置：** `src/main/java/com/zonlong/beloong/client/TornadoRenderer.java:80`；对照 `docs/superpowers/plans/2026-09-17-tornado-ability.md:844,865`
- **问题：** 计划给的代码声明了 `private static final float MODEL_UNITS_PER_POSE_UNIT = 16.0F;` 并在 `translate` 中使用；交付版删掉了该常量，直接写 `-MODEL_ROOT_Y / 16.0F`。
- **后果：** 数值当前正确（`-24/16 = -1.5`，世界 y∈[0, 3.28]），只是把最易再犯之处的自我说明抹掉了。`spec:323` 明确把这 16 倍边界记为历史 bug 的根因并写下教训「跨过这条边界时必须显式写单位换算」——读者从 `16.0F` 看不出这是「模型单位 / pose 单位」，后续改动容易再写成 `-24`。
- **修法：** 恢复 `MODEL_UNITS_PER_POSE_UNIT` 具名常量并代入 `translate`。

### 23. `shadowRadius = 0.0F` 是冗余赋值，且「有意不投影」这一取舍没有任何注释

- **位置：** `src/main/java/com/zonlong/beloong/client/TornadoRenderer.java:61`
- **问题：** `EntityRenderer.shadowRadius` 声明处不带初值（`EntityRenderer.java:31` `protected float shadowRadius;`），默认就是 `0.0F`，因此这行不改变任何行为；而 `EntityRenderDispatcher` 以 `f > 0.0F` 为门（`:168-174`），等价于「本实体永不渲染地面阴影」。
- **后果：** 无功能影响。但风柱底面锚在实体位置、实体又生成在「眼高 − 0.5」（`TornadoEffect.java:80-83`），站立玩家时底面约 1.12 格、整根悬空（`spec:326` 已记载），再叠加无地面投影，落地参照完全依赖背景；这行是文件里唯一体现该取舍的地方，却看不出是「有意关掉」还是「忘了设」。
- **修法：** 保留该行并加注释写明「有意不投影」，或删掉它并在类 javadoc 里说明。

---

## 四、已核验无误、无需再查的横切面

（列出以免重复怀疑；详细证据见四份分道报告）

- **无自定义着色器、无直接 GL 调用、无 `RenderType.create`** ⇒ 不重蹈天灾传送门自定义着色器与 Iris 不兼容的覆辙；`render` 内无 `RenderSystem` 状态泄漏、无每帧分配，`pushPose`/`popPose` 成对。
- **模型层注册的总线与时机正确**：`EntityRenderersEvent` 实现 `IModBusEvent`，`@EventBusSubscriber` 按事件类型自动选总线；上游先发 `RegisterLayerDefinitions` 再发 `RegisterRenderers`（`ClientHooks.java:1027-1028`），`context.bakeLayer` 不会抛异常。
- **客户端专用性成立**：两个新类只被 `dist = Dist.CLIENT` 的 `BeLoongCoreClient` 引用，专用服务器不加载；实体类型走公共注册，两端都有。
- **贴图与几何自洽**：`tornado.png` 实测 128×128、alpha 严格二值（3030 不透明 / 13354 全透明，合计 16384）、均值色相 187.0°；四层 UV 全部落在 128×128 内且互不重叠；世界尺寸约 2.81×3.28 与碰撞箱 `sized(2.8F, 3.3F)` 吻合；`recolor_tornado_texture.py` 重跑产物与提交的 PNG **SHA256 逐字节相同**（`affeef30…`）。
- **技能接入正确**：`apply` 只在服务端（`AbilityEntityEffect.java:33` 取 `ServerPlayer`）、一次施法恰好生成 1 个实体；冷却 200 刻 / 法力 2 由 Dragon Survival 自行扣除；`tornado.json` 的 schema 逐字段对照 DS 反序列化类全部正确（含 `experience_cost` 与 `maximum_level: 5` 配对、`values[0] = 0.0` 是必需而非笔误、`icon.from_level` 0..5 无空洞）。
- **数据与文案一致**：伤害类型 JSON 五个字段合法；`message_id` 与 `death.attack.beloong.tornado` / `.player` 逐字对上；中英各 210 键、键集合完全一致、无重复键、`%s` 数量与顺序与传参一致。
- **注册链命名自洽**：`beloong:tornado`（实体 / 技能效果 / 伤害类型）与 `dragonsurvival:tornado`（能力）全部能解析，未发现拼写不一致导致的静默失效；唯一悬空引用即第 1 条的 6 张贴图。
- **本 PR 未新增任何 mixin**（`git diff 30d7b2d master --name-only` 中无 mixin 文件）。
