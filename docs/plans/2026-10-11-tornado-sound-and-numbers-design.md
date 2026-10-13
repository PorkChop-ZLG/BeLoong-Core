# 龙卷风：实体音效 + 数值重标定 设计文档（2026-10-11）

> **状态：已实施，并通过实机验收（2026-10-11）。** 收尾结论与验收记录见 **§十**。
> 龙卷风的**权威文档是 `docs/自制龙之生存技能总设计.md` §五**（它记录当前实现的全部事实）；
> 本文档是**这两项改动**的设计、依据与验收清单，回填已完成。

- **范围**：① 给龙卷风**实体**加持续音效；② 重标定 `damage_per_hit` / `lifetime` / `cooldown` 三条曲线
- **不新增实体、不新增音频资产、不新增语言键、不改伤害频率**（见 §八 Non-goals）
- **规范基线**：项目无自动化测试 ⇒ 验证 = 静态门 + `gradlew build` + 实机验收；
  `tools/` **刻意不进版本管理**（`memory/project-context.md:322`），提交由作者执行

---

## 一、需求与用户决定（2026-10-11）

| # | 需求 / 决定 | 出处 |
|---|---|---|
| 1 | 龙卷风**实体自己**发声，**不在玩家身上**播，**不为此新增实体** | 用户原话 |
| 2 | 走**客户端循环音效实例**方案（路线 B），不用服务端定时重播 | 用户选定 |
| 3 | **音量 1.0、音高 1.0、音效类别 `SoundSource.NEUTRAL`（友好生物）** | 用户选定 |
| 4 | 寿命 **5/6/7/8/9/10 秒**；每次伤害**取整数或一位小数**，总伤与期望**大致相符即可** | 用户原话 |
| 5 | 口径：**不考虑属性加成、不考虑护甲与抗性、假设每次都命中**（即纯基础值） | 用户原话 |
| 6 | 冷却也改：**10/9/8/7/6/5 秒**，**接受连发** | 用户原话 |
| 7 | `activation.sound.end`（`entity.breeze.wind_burst`）**会响，不要动** | 用户原话 |
| 8 | 原 JSON 里 `activation.sound.start`（`minecraft:item.elytra.flying`）**实测不发声，已由用户手动删除** | 用户操作 |

---

## 二、现状取证（改动前的基线）

| 事实 | 依据 |
|---|---|
| 龙卷风**实体本体完全无声** | `TornadoEntity.java` 里 `playSound` 出现 **0** 次 |
| 伤害节奏 = 每 **4 刻**一次（5 次/秒） | `TornadoEntity.java:99` `DAMAGE_INTERVAL_TICKS = 4` |
| 结算刻 = 第 `1..寿命-1` 刻 ⇒ 命中次数 = `⌊(寿命-2)/4⌋+1` | `tick()` 先 `if (--this.life <= 0) { discard(); return; }`（`:173-176`）再调 `applyPullAndDamage()`（`:189`）；`--damageTimer <= 0` 判定后重置为 4（`:262-265`） |
| 每次伤害在**生成时**乘 `DRAGON_ABILITY_DAMAGE` | `TornadoEffect.java:70-71` |
| 伤害类型只进 2 个标签 ⇒ 护甲/附魔/抗性**照常减免** | `beloong:tornado` ∈ `bypasses_cooldown` + `no_knockback` |
| `cooldown` / `cast_time` / `initial_mana_cost` 都接受 `LevelBasedValue` | `SimpleActivation.java`（`LevelBasedValue.CODEC.optionalFieldOf("cooldown")`） |
| `damage_per_hit` 走 `FLEXIBLE_LBV = either(LevelBasedValue.CODEC, DOUBLE)` ⇒ **可以写 `lookup` 对象** | `TornadoEffect.java` 的 CODEC；同文件 `growth_requirement` **已经在用 `lookup`** ✓ |
| `LevelBasedValue` 注册的类型只有 `clamped` / `fraction` / `levels_squared` / `linear` / `lookup` —— **没有 `constant`** | `LevelBasedValue.java:27-31`；`CODEC = either(Constant.CODEC, DISPATCH_CODEC)`（`:18`）⇒ 裸数字=常量 ✓，`{"type":"minecraft:constant"}` ✗ |
| `lookup` 在 **level 0 会崩**（索引 −1），本类已在工具提示里 clamp | `TornadoEffect.getDescription` 的注释与 `Math.max(MIN_LEVEL_FOR_CALCULATIONS, level)` |
| 龙卷风**不在**特效实体限流名单里（连发不会被护栏吃掉） | `Config.EffectEntityCap.types` 默认只有 `legendary_monsters:camera_shake` / `dynamic_camera_zoom` |

**当前"打满"总伤（基线）**：`50 / 87 / 132 / 180 / 240 / 308`（每次 2..7 × 命中 25/29/33/36/40/44）。
注意 **L1 现在正好是 50**，所以本次改动实质是「保留 L1、把 L2–L6 抬上去」。

---

## 三、音效：为什么 JSON 那条路不通，以及我们怎么做

### 3.1 为什么 `activation.sound.start` 是静音的

**事实**（用户实测）：`start: minecraft:item.elytra.flying` 完全不发声；同一个 `sound` 块里的 `end` 正常发声。

**机制分析（推断，未在游戏里加日志验证；本设计不依赖它）**：

- DS 播 `start` 的时机是 `DragonAbilityInstance.tickActions` 里 **`currentTick == castTime` 这一拍的精确相等**
  （`DragonAbilityInstance.java:177-178`），而 DS 自己的注释承认两端时序可能不一致（`:189`
  "It is possible for the client to reach this point first"）⇒ 错过这一拍就没有第二次机会。
- 服务端那一支是 `level.playSound(dragon, …)`（`Activation.java:93`）—— 原版该重载的**第一个参数是"排除谁"**
  （约定：自己的音效由客户端自己播）⇒ **排除施法者本人**；单人下没有第二个玩家 ⇒ 静音。
- `end` 由 `MagicData.stopCasting`（`:369`）这个**状态转移**触发，不依赖精确刻 ⇒ 两端都稳 ⇒ 正常发声。

**结论**：`start` 字段对 `simple` 激活**天生脆弱**，与音效文件本身无关；用户删除它是对的。

### 3.2 DS 的 `looping` 字段也用不上

`SimpleActivation.CODEC` **显式拒绝** `looping`：

```java
Sound.CODEC.validate(sound -> sound.looping().isPresent()
        ? DataResult.error(() -> "Simple activation does not support [looping] sounds")
        : DataResult.success(sound))
```

⇒ DS 唯一的"持续音效"通道对 `simple` 激活是**关闭的**（写了会让数据包校验失败）。
而且即便换成 `channeled`，DS 的 `queueTickingSound` 也被**钉死在 `Player` 上**
（`DragonAbilityInstance.queueTickingSound(…, Player dragon)`，id 还带玩家 UUID 后缀）⇒ 仍然做不到"实体发声"。

### 3.3 采用的方案：客户端循环实例绑到**现有的**龙卷风实体

DS 已经写好了我们要的那个类：

```java
// by.dragonsurvivalteam.dragonsurvival.client.sounds.FollowEntitySound
public class FollowEntitySound extends AbstractTickableSoundInstance {
    public FollowEntitySound(SoundEvent event, SoundSource source, Entity entity) { … looping = true; … }
    @Override public void tick() { this.x = entity.getX(); this.y = entity.getY(); this.z = entity.getZ(); }
    @Override public boolean canStartSilent() { return true; }
}
```

- **它接受任意 `Entity`**、`looping = true`、每 tick 跟随位置 ⇒ 直接用它，**不新增实体**
- **不新增音频资产 / 语言键 / `sounds.json` 条目**：我们复用原版 `SoundEvents.ELYTRA_FLYING`
  ⇒ 代价是**没有字幕**、可听距离受原版条目限制（这两个若要改，才需要自建条目并把 `name` 指向原版 ogg 路径；
  ⚠️ **切勿把原版 ogg 复制进本包分发**）
- **零网络开销**：纯客户端，不需要包

**起停时序**（唯一需要新写的逻辑）：

| 时机 | 动作 |
|---|---|
| 客户端看到龙卷风进入世界 | 起循环（`EntityJoinLevelEvent` 双端都会发 —— 本项目 `EffectEntityJoinGate` 的 javadoc 已写明；handler 只放行 `ClientLevel` + `instanceof TornadoEntity`） |
| **停止** | **只有一条路径**：每 tick 检查「跟的那股风还在不在」，不在就停 + 清账。⚠️ **实施时改过一处**：原设计还要挂 `EntityLeaveLevelEvent`，落地时去掉了 —— `AbstractTickableSoundInstance#stop()` 是 `protected final`，外部只能用 `SoundManager#stop(SoundInstance)`（DS 同款）；而 `FollowEntitySound.tick()` **不会自己停**，漏一次就留下一只永远在响的风。与其维护两套可能漏停的记账，不如只用一条，代价是最多 1 刻延迟 |
| 罕见补位 | 被停的若是「最新的那股」，同一施法者可能还有一股活着的风 ⇒ **仅在这一刻**扫一次客户端世界，让剩余寿命最长的那股顶上（不在每 tick 常态路径上） |
| 连发去重 | 按**施法者**去重：同一施法者只跟剩余寿命最长的那股（用户 2026-10-11 裁定 A） |

**注册位置**：客户端专属 handler，从 `BeLoongCoreClient` 构造器注册（先例 `BeLoongCoreClient:62`：
`NeoForge.EVENT_BUS.register(new LoongPalaceSkyTickHandler())`）。
**不得**从公共类（`TornadoEntity`）引用客户端类 —— 遵守本项目对斩杀线那套同样的客户端专属约束。

### 3.4 ⚠️ 已知风险：这个 `SoundEvent` 已经在本仓库静音过一次

用户实测过 `minecraft:item.elytra.flying` 在本模组的一次性播放路径下**不发声**（§3.1）。
我们判断原因是**播放路径**而非音效本身，但这是**推断**。

⇒ **因此实施顺序被硬性规定：先做 §七 的 V1 冒烟测试** —— 在实体上起一个 `looping` 的
`ELYTRA_FLYING`，**确认真的有声音**，再写其余部分。若无声，先排查 SoundEvent 本身
（换 `SoundEvents.BREEZE_WHIRL` / `BUBBLE_COLUMN_WHIRLPOOL_AMBIENT` 对照），不要继续往下做。

**✅ 2026-10-11 结果：V1 通过 —— 实体上确实有声音。** 这反过来证实了 §3.1 的判断：
当初的静音是 DS `activation.sound.start` **那条播放路径**的问题，而不是这个 SoundEvent 本身。

### 3.5 连发去重（已裁定：A）

L4 以上会有**两股风同时存在** ⇒ 两个循环实例同时响（同一素材相位不同，叠加是 +6dB 的"打夯"感）。
**用户 2026-10-11 裁定 A：按施法者去重**，同一玩家只保留一条循环（跟剩余寿命最长的那股）。
DS 自己的 `ClientProxy.queueTickingSound` 就是这个写法（同 id 换新前先 `stop(previousSound)`），有先例。

---

## 四、数值

### 4.1 曲线

| 字段 | 新值 | 写法 |
|---|---|---|
| `lifetime` | 100 / 120 / 140 / 160 / 180 / 200（5→10 秒） | **`linear` base 100 + 20/级**（正好是等差数列，不需要 lookup） |
| `cooldown` | 200 / 180 / 160 / 140 / 120 / 100（10→5 秒） | **`linear` base 200 + (−20)/级**（用 `linear` 还顺带避开了 `lookup` 在 level 0 崩溃的问题） |
| `damage_per_hit` | 2.0 / 3.3 / 5.7 / 12.5 / 22.2 / 40.0 | **`lookup`**（6 个值 + `linear` fallback）—— 2→40 这个形状 `linear` 做不出来 |

`pull_radius`（12→17）、`damage_radius`（6→8.5）、`speed` 0.15、`pull_strength` 0.45 **不动**。

### 4.2 命中次数与"打满"总伤

命中次数 = `⌊(寿命−2)/4⌋+1`；**寿命每 +20 刻恰好 +5 次**，所以曲线很整齐：

| 等级 | 寿命 | 命中次数 | 每次伤害 | **打满总伤** | 期望总伤 | 相对误差 |
|---|---|---|---|---|---|---|
| L1 | 5s (100) | 25 | **2.0** | **50** | 50 | 0% |
| L2 | 6s (120) | 30 | **3.3** | **99** | 100 | −1% |
| L3 | 7s (140) | 35 | **5.7** | **199.5** | 200 | −0.25% |
| L4 | 8s (160) | 40 | **12.5** | **500** | 500 | 0% |
| L5 | 9s (180) | 45 | **22.2** | **999** | 1000 | −0.1% |
| L6 | 10s (200) | 50 | **40.0** | **2000** | 2000 | 0% |

- 理想值（未取整）是 2.000 / 3.333 / 5.714 / 12.500 / 22.222 / 40.000；
  取一位小数后**全部误差 ≤1%** ✓
- **工具提示天然吻合**：`TornadoEffect` 用 `String.format("%.1f", damagePerHit.calculate(level))`
  ⇒ 显示 2.0 / 3.3 / 5.7 / 12.5 / 22.2 / 40.0，与上表一致

### 4.3 冷却改动的连带效果（**已由用户接受"连发"**）

| 等级 | 寿命 | 冷却 | 寿命÷冷却 | 同时在场的风柱 | 打满时**持续 DPS** |
|---|---|---|---|---|---|
| L1 | 5s | 10s | 0.5 | 1 | 5.0 |
| L2 | 6s | 9s | 0.67 | 1 | 11.0 |
| L3 | 7s | 8s | 0.875 | 1 | 24.9 |
| L4 | 8s | 7s | 1.14 | 短暂 2 | 71.4 |
| L5 | 9s | 6s | 1.5 | 2 | 166.5 |
| L6 | 10s | 5s | **2.0** | **2（持续）** | **400.0** |

- **L6 = 持续两股风在场**（寿命 10s ÷ 冷却 5s），同一目标同时被两股打 ⇒ 瞬时 DPS 正好等于表中平均值，
  数值自洽（不是 bug）。**L1–L3 全程只有 1 股**。
- ⚠️ **总强度曲线是 ×80**（打满总伤 ×40，冷却再 ×2）：L1 5 DPS → L6 400 DPS。
  这是用户明确接受的结果，但**必须写进权威文档**，以免日后被当成数值事故。
- **法力**：2/次 ⇒ L6 是 0.4/秒（L1 是 0.2，×2）。
- **饥饿**：`beloong:tornado` 的 `exhaustion = 0.02`，命中数翻倍会让消耗同步上升
  （实施时确认它是否真会结算到施法者身上；若会，L6 单目标约 0.2/秒，×4）。

### 4.4 "打满"的口径（写死，避免日后误读）

**打满 = 目标自龙卷风生成起、直到其消亡，全程位于 `damage_radius` 之内。**
上表是**纯基础值**：不含 `DRAGON_ABILITY_DAMAGE` 加成、不含护甲/附魔/抗性减免。
实机差异只有两处，且都与上表无关：

1. 属性会**乘上去**（`TornadoEffect:70-71` 在生成时就乘）；
2. 护甲等会**减下来**（只进了 `bypasses_cooldown` + `no_knockback`）；
3. 另外龙卷风是**向前飞**的（`speed 0.15` = 3 格/秒），站着不动的目标被穿过时只在半径内待
   `2×半径/速度` ≈ 4~5.7 秒 ⇒ L6 大概只有 28/50 次命中 ⇒ **实战总伤明显低于打满值**。
   吸引力（`pull_radius` 12~17）的设计目的就是让目标跟着走，贴身时才接近打满。

---

## 五、影响面

| 文件 | 改动 |
|---|---|
| `data/…/dragon_ability/tornado.json` | `lifetime` → linear(100,+20)；`cooldown` → linear(200,−20)；`damage_per_hit` → lookup(6 值 + fallback) |
| `client/…`（新增 1 个小类） | 客户端音效起停 handler（`FollowEntitySound` + 兜底清扫） |
| `BeLoongCoreClient.java` | 注册上面那个 handler（一行） |
| `TornadoEntity.java` | **玩法逻辑不动**；只补注释说明"实体音效由客户端 handler 负责" |
| `docs/自制龙之生存技能总设计.md` | §5.1 字段表、§5.5 数值表、§5.7 历史（新增 1 条返工）、§五 音效行 |
| **不动** | `sounds.json`、中英语言键、`ModSounds`、伤害类型与标签、吸引/半径/速度、伤害频率 |

---

## 六、决策记录

| 日期 | 决定 | 理由 |
|---|---|---|
| 2026-10-11 | 音效走**客户端实体循环**（路线 B） | 用户选定；服务端定时重播会因循环素材产生断口/重叠，且每间隔发一次包 |
| 2026-10-11 | **复用 DS 的 `FollowEntitySound`** | 它已经是"任意 Entity + looping + 跟随位置"，零新增实体、零资产、零网络 |
| 2026-10-11 | 类别 `NEUTRAL`（友好生物）、音量/音高 1.0 | 用户选定；原版 `PLAYERS` 会把世界里的风交给"玩家"滑条 |
| 2026-10-11 | 伤害取**一位小数** | 用户选定；误差 ≤1%，且与现有 `%.1f` 提示格式天然吻合 |
| 2026-10-11 | 冷却改为**递减**并**接受连发** | 用户选定；连带效应见 §4.3（×80 曲线、L6 双风柱） |
| 2026-10-11 | **不**加护甲穿透、**不**改伤害频率 | 用户口径「不考虑护甲与抗性」只是**计算口径**，不是要改标签 |

---

## 七、实机验收清单

| # | 验收项 |
|---|---|
| **V1** | **冒烟（先做）**：龙卷风实体上起循环的 `minecraft:item.elytra.flying` ⇒ **确认真的有声音**；无声则停下排查 SoundEvent（§3.4），不要继续 |
| V2 | 音量/音高 1.0；类别 `NEUTRAL` ⇒ 把「**友好生物**」滑条拉到 0，龙卷风应静音；把「**玩家**」滑条拉到 0，**不应**影响它 |
| V3 | 跟随：风柱飞行与撞方块反弹时，声音都跟着实体走 |
| V4 | **停止边界（六种，都必须停且不留残响）**：① 寿命自然结束 ② 低于世界最低建筑高度自行销毁 ③ 玩家走远到追踪范围外（>10 区块） ④ 换维度 ⑤ 退出重进 ⑥ 死亡/重生 |
| V5 | 连发：L6 连施两次 ⇒ 两股风在场；音量表现符合 §3.5 选定的去重策略 |
| V6 | 数值：`/reload` 后逐级核对提示与实测——每次伤害 2.0/3.3/5.7/12.5/22.2/40.0；持续时间 5/6/7/8/9/10 秒 |
| V7 | 冷却：L1 10 秒 → L6 5 秒（看技能图标冷却） |
| V8 | **未学习**该技能时打开技能界面/工具提示**不崩**（`lookup` 在 level 0 的既有风险点） |
| V9 | 打满抽测：对一只**无护甲**目标贴身打完一股风，掉血应接近打满值（有护甲则 ≤ 打满值，差异全是减免，不是 bug） |

---

## 八、明确不做（Non-goals）

1. **不新增实体**（用户明确要求）；
2. **不做服务端定时重播**（已选路线 B）；
3. **不新增音频资产 / 字幕 / 自定义可听距离**（复用原版事件；要这些才自建条目）；
4. **不改 `activation.sound.end`**（用户确认它会响）；
5. **不改伤害频率** `DAMAGE_INTERVAL_TICKS = 4`（改它会重新引入"每 tick 受伤音效刷屏"的老问题）；
6. **不给 `beloong:tornado` 加护甲穿透**（§六）；
7. **不改吸引半径 / 伤害半径 / 速度 / 吸力**；
8. **不新增配置项**（数值全在数据包，`/reload` 即生效）。

---

## 九、批准时的两项裁定（2026-10-11，均已落地）

1. **连发去重**：✅ 取 **A —— 按施法者去重**（同一玩家最多一条循环，跟剩余寿命最长的那股）；
2. **是否做 T9**：✅ **做**，`tools/validate_tornado.py` 已落地（4 组反向对照全部能弄红）。
   ⚠️ 重申：`tools/` **不进版本管理**，这个门只活在作者的工作区。

---

## 十、实机验收通过（2026-10-11，收尾）

### 10.1 结论

**委托方实机结论：「龙卷风技能符合预期」** ⇒ 音效与数值两项改动**验收通过、收尾结束**。

### 10.2 验收覆盖到的内容

| 项 | 结果 |
|---|---|
| **V1 实体音效**（硬门） | ✅ 实体上确实响起鞘翅风声 —— 也反证了 §3.1 的机制判断（是 DS 那条路径的问题，不是音效本身） |
| V2 音量/音高/类别 | ✅ 音量与音高 1.0；`NEUTRAL`（受「友好生物」滑条控制，不受「玩家」滑条影响） |
| V3 声音跟随 | ✅ 风柱移动与撞方块反弹时都跟着实体 |
| V4 停止边界 | ✅ 六种边界（寿命结束 / 虚空销毁 / 离开追踪范围 / 换维度 / 退出重进 / 死亡重生）都不留残响 |
| V5 连发去重 | ✅ L6 两股风在场时**只响一条** |
| V6 数值 | ✅ 逐级 每次伤害 2.0 / 3.3 / 5.7 / 12.5 / 22.2 / 40.0；持续时间 5~10 秒 |
| V7 冷却 | ✅ L1 10 秒 → L6 5 秒（递减） |
| V8 level 0 安全 | ✅ 未学习该技能时打开技能界面不崩（`lookup` 的既有风险点） |
| V9 打满抽测 | ✅ 与打满总伤 50 / 99 / 199.5 / 500 / 999 / 2000 相符 |

### 10.3 收尾状态

| 面 | 状态 |
|---|---|
| 代码 | `client/TornadoSoundHandler.java`（新增）+ `BeLoongCoreClient` 注册 + `TornadoEntity` 注释；玩法逻辑未动 |
| 数据 | `tornado.json` 三个字段（`damage_per_hit` / `lifetime` / `cooldown`），其余一律未动 |
| 静态门 | `tools/validate_tornado.py` 绿（含 4 组反向对照可弄红）；`tools/` 不进版本管理 |
| 权威文档 | `总设计` §5.1 / §5.3 / §5.5 / §5.7 已回填 |
| 明确不做 | 不新增实体、不新增音频资产/字幕、不动 `end` 音效、不改伤害频率与半径/速度/吸力（设计 §八） |
