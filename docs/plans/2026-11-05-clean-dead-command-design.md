# `/beloong cleandead` —— 清理存档里的假死实体（设计）

**日期：** 2026-11-05
**状态：** 实施中（代码与端到端自测已过，待委托方实机验收）
**范围：** 只改模组侧（`BeLoong-Core`）；不改第三方、不加配置项、默认不改变任何常规行为

---

## 一、为什么需要它

假死尸体的血量是 0，而原版 `@e` **自带 `Entity::isAlive` 谓词**：

- `EntitySelectorParser.java:228-233` 的 `@e` 分支 `yield true`；
- `:260-262` 随即 `this.predicates.add(Entity::isAlive)`；
- `LivingEntity.java:1632-1634` 的 `isAlive()` 要求 `getHealth() > 0`。

⇒ `/kill`、`/data`、`/tp`、`/execute as` **全部选不中**它们。这不是指令写错，是命令层没有入口。

本项目已定位的一种来源（2026-11-05 霜噬假死）：铁魔法 `FrostbiteEffect:35` 在
"击杀者带 `frostbitten_strikes` 且死者完全冻结"时把 `deathTime` 写成 **1000**，
而祸乱鬼魅 / 永恒星光的收尾是 `deathTime == N` 的**精确相等** ⇒ 永不命中 ⇒ 尸体永久留场。
**源头**已由 `mixin/ironsspellbooks/FrostbiteDeathSequenceMixin` 修掉（新存档不再产生）；
本命令处理的是**已经卡住的老存档**——这是模组侧唯一能提供的入口。

> 已有 `NaNny` 处理的是另一类"假死"（血量被写成 `NaN`），它只在事件触发时介入、没有扫描能力，
> 对"血量 0 + `deathTime` 被外部改写"完全无效。本命令**不重复实现** NaN 相关逻辑。

## 二、命令

```
/beloong cleandead            只扫描并在聊天框列出清单 + 提示确认（默认不给破坏性行为）
/beloong cleandead confirm    重新扫描并执行
```

- **无状态**：`confirm` 重新扫描，不复用 `list` 的结果（不占内存，也不会误伤"列出后才出现"的实体）。
- **权限** `hasPermission(2)`（与 `/beloong essence` 等一致）；**控制台可用**（本命令不依赖玩家）。
- ⚠️ `/beloong` 根字面量由多条命令共享，Brigadier 合并子树**不带走** `requires` 谓词
  ⇒ 每个注册点都必须重写权限等级（见 `EssenceCommand` 的同类说明）。

## 三、判定

```
LivingEntity && !(Player) && !isRemoved() && getHealth() <= 0 && deathTime >= 400
```

400 tick（20 秒）是"已经卡住"的安全余量：原版死亡动画 20；祸乱鬼魅 50/70；永恒星光 70/75/100；
灾变收尾走 `>=`（≤ 约 123）；末影龙 200 ⇒ **正常死亡不可能超过 400**，
而实测的假死尸体停在四位数（5949 / 5741 / 5894）。

NaN 型假死（血量 `NaN`）不在此列——由 `NaNny` 负责，不重复实现。

## 四、动作：按 `tickDeath()` 是否被覆写分流

| 层 | 判据 | 动作 | 结果 |
|---|---|---|---|
| **Tier 1** | 自己声明了 `tickDeath()` | `entity.deathTime = 0` | 下一 tick 起由**它自己的**死亡结算重放（经验 / 战利品 / 方块清理 / 移除都完整） |
| **Tier 2** | 未覆写（原版收尾却仍卡着） | `entity.remove(RemovalReason.KILLED)` | 直接移除；`die()` 早已跑过，不会重复掉战利品，但会丢该模组"死亡 tick 里给的东西" |

**为什么归零就能重放**：这类尸体的 `deathTime` 是在 `die()` 内部被外部改写跳过去的，
所有 `== N` 的死亡结算**一次都没跑过**；把计时器拨回 0，等于让它从头把整套结算跑一遍。
（早期方案是"按每个模组的阈值写 `阈值−1`"，需要维护一张 per-mod 阈值表；
归零后**零维护**，且能顺带把被跳过的经验补回来。）

判据复用 `util/CustomDeathSequence.ownsDeathSequence(Class)`（沿继承链找 `tickDeath` 的声明类），
与 modid 无关 ⇒ 未来新装的模组自动覆盖。该工具同时被霜噬修复的 Mixin 使用。

## 五、输出

- 头部：命中总数 + 将交还几具 + 将移除几具；
- 逐条：`注册 id @ x y z（维度，deathTime=N）→ 处置方式`，最多列 20 条，其余只进服务端日志；
- 确认入口：金色可点击文本（`ClickEvent.runCommand`）+ hover 说明；
- 执行后：`已清理：交还 N 具，移除 M 具`；
- 服务端日志：`[BeLoong] cleandead(listed|applied): …` 逐条 + 汇总 `armed=… removed=… by=…`。
- 翻译键：`beloong.command.cleandead.*`（中英同步，`check_lang_parity.py` 通过）。

## 六、范围与性能

- **所有维度**：`source.getServer().getAllLevels()` × `level.getAllEntities()`，只在执行时遍历一次；
- **不注册任何 tick 监听**，平时零开销（这正是"用命令而不是定时检测"的原因）；
- ⚠️ 只覆盖**已加载**实体：远处未加载区块里的尸体扫不到（可先用 `/forceload` 或
  `tools/purge_fake_dead.py` 离线处理）。

## 七、边界与已知取舍

- 不处理 NaN 型假死（`NaNny` 已覆盖）。
- Tier 2 直接移除会丢奖励，清单里逐条标注。
- 不做自动检测、不做定时兜底、不加配置项（委托方裁定：默认生效、不常驻）。
- **不提供 `force` 变体**（委托方裁定）：能交还的交还，交还不了才移除。

## 八、升级必查项

- 铁魔法若改了 `FrostbiteEffect` 的方法名/描述符，`FrostbiteDeathSequenceMixin`（`require = 0`）会静默失效；
  复核 `javap -p -c` 里 `PUTFIELD LivingEntity.deathTime` 是否还在。
- `CustomDeathSequence` 的判据依赖"覆写 `tickDeath` ⇒ 自有死亡计时器"这一约定；
  若某模组覆写了 `tickDeath` 却用原版 `>= 20` 收尾，本命令只会让它按自己节奏消失（无害）。
- 若某模组的死亡演出超过 400 tick，本命令会把"正在死亡"的实体误判为卡死 ⇒
  靠 `list` 预览把关（阈值常量集中在 `CleanDeadCommand.STUCK_DEATH_TIME`，改这一处即可）。


---

## 2026-11-05 修订（rev 1）：改为「只移除」+ 铁魔法修复加配置开关

> **本节取代** §四「动作」与 §六 里"归零重放结算"的全部描述。其余（判定、范围、权限、输出）不变。

1. **命令不再重放死亡结算**（委托方裁定）：`deathTime = 0` 会让该模组把被跳过的
   `== 1 / == 50` 等结算重新跑一遍 ⇒ 额外补发经验/战利品/爆炸，属于凭空造物。
   现在**一律 `remove(RemovalReason.KILLED)`**：`die()` 早在假死那一刻跑完，战利品与死亡事件该发的都发过。
   代价（如实登记）：祸乱鬼魅尸体会留下自己放的 `minecraft:light` 方块（`/setblock <pos> air` 可清），
   下界铁掌不会再爆一次、也不掉远古残骸。
   ⇒ `util/CustomDeathSequence` 不再被本命令使用，改由霜噬修复的 Mixin 独占（保留在 `util/`）。

2. **霜噬修复变成 COMMON 配置项**：`frostbite_death_fix.enabled`（默认 `true`），
   键 `beloong.configuration.frostbiteDeathFixEnabled`（中英同步）。
   关闭后完全恢复上游行为 —— 用于**复现假死**、以及验证本命令的清理效果。
   消费方：`mixin/ironsspellbooks/FrostbiteDeathSequenceMixin`（每次死亡读一次开关）。
