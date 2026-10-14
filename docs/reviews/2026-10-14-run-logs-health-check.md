# 运行日志体检（龙宫禁铁魔法验收后）

**日期：** 2026-10-14
**范围：** `run/logs/latest.log` + `debug.log`（本次会话 16:26:50–16:32:50）及其轮转件 `debug-1.log.gz`（10-13 23:09–23:18）
**触发：** 用户要求"检查日志看看有没有其他问题"

---

## 1. 结论

- **本模组本次会话零 WARN / 零 ERROR**（`com.zonlong.beloong` 命中 0 条）。
- **本次会话没有新的崩溃报告**。
- 其余全部是**三方**噪声或内容缺口；其中唯一与"我们的内容"相关的（DS 两个动画解析失败）**已定位不是我们的资源包**。

## 2. 方法（含一个必须避开的坑）

⚠️ 统计 ERROR/FATAL **必须用 `\[[^\]]*/(ERROR|FATAL)\]`**（级别写在 `[线程/LEVEL]` 里）；
用 `\[(ERROR|FATAL)\]` 会**误得 0 条**。本次第一遍就踩了，第二遍按正确口径重算。
（该纪律已写在 `AGENTS.md` §二，属于"知道但仍会踩"的一类。）

另外：`tools/check_lang_parity.py` 在 **GBK 控制台**下会在打印 `✅` 时抛 `UnicodeEncodeError`，
**通过也退出 1** ⇒ 跑语言门前先 `$env:PYTHONIOENCODING='utf-8'`。

## 3. 补做的一项旧验证：建筑之杖 Mixin 的应用期

本次会话日志里**没有** wands 的 `Mixing` 行 —— 这不是问题：**Mixin 在目标类首次加载时才应用**，
而本次会话没碰建筑之杖。该行在被轮转的 `debug-1.log.gz`（10-13 23:11:40）里：

```
Mixing wands.ClaimDispatchMixin from beloong.mixins.json into net.nicguzzo.compat.claims.ClaimDispatch
beloong.mixins.json:wands.ClaimDispatchMixin from mod beloong->@Inject::beloong$loongPalaceAsClaimed(...)V
    does use it's CallbackInfoReturnable
```

⇒ 注入点正确挂上、`CallbackInfoReturnable` 被识别 ✓（此前遗留的"应用期未验证"至此闭环）

## 4. 崩溃报告：唯一一份是**已知且已修**的

`run/crash-reports/crash-2026-10-13_16.54.16-server.txt`：

```
Description: Ticking entity
java.lang.NullPointerException: Cannot invoke "WrappedGoal.isRunning()" because "wrappedgoal" is null
  at GoalSelector.tickRunningGoals(GoalSelector.java:121)
  … at DarkDoppelgangerEntity.tick(DarkDoppelgangerEntity.java:477)
```

即 `build.gradle:324-331` 记录、后由 `darkdoppelganger.DarkDoppelgangerPhaseGoalRebuildMixin` 修掉的
那个问题。报告时间**在修复之前**，之后至今**未复发** ⇒ 修复有效。

## 5. 三方问题清单（按量排序）

| 量 | 来源 | 现象 | 性质 / 影响 | 建议 |
|---|---|---|---|---|
| 148 + 148 | FTB Chunks `EntityIcons` / FTB Library `EntityIconLoader` | `Missing entity icon settings for <实体>`（irons_spellbooks / block_factorys_bosses / cataclysm / `minecraft:creaking` …） | 纯日志噪声，无功能影响 | 忽略；要消只能整合包补地图图标配置 |
| 26 | `yuushya` 的配方 | `Unknown recipe_serializer: create:mixing` / `create:splashing` | **未装 Create** ⇒ 这 26 条配方加载失败（其余内容正常） | 整合包侧：装 Create 或删这些配方 |
| 18 | `cataclysm` | `Missing subtitle translation{key='…sub'}` | 字幕显示原始键名，纯表现 | 上游内容缺口 |
| 4 | `beyonddimensions` 战利品表 | 引用不存在的物品（`beyonddimensions:*_pathway`） | 上游数据缺口（依赖 Create/RS） | 上游 |
| 3 | `beyonddimensions` 贴图 | `Invalid path in pack: …widget/漏斗禁止.png` | **路径含非 ASCII** ⇒ 贴图被忽略 | 上游 bug，可上报 |
| 3 | Iris / Moonlight 联网检查 | `SSLHandshakeException … PKIX path building failed` | 本机证书链不可验证 ⇒ 更新检查/公告拉取失败 | 无影响，忽略 |
| 1 | NeoForge | spawn-placement 提示 | 已知无害（`AGENTS.md` §二 有记） | 忽略 |

## 6. 唯一与我们内容相关的项：已排除是我们的锅

`GeckoLib ERROR: Unable to parse animation: mass_buff / flapping_wings_standing`
（同时还有 `attack_single` / `supernova`，属其它模组）。

- 这两个名字确实出现在**我们的资源包** `DragonSurvival-Mod\assets\dragonsurvival\animations\dragon_*.json`；
- 但把这两个动画块与 **DS jar 原件逐通道对比**：骨骼数相同（85 / 74）、**通道差异 0**、顶层字段一致
  ⇒ **我们的覆盖块与 DS 原件等价，不是我们改坏的** ⇒ 属 **DS 自带内容 / GeckoLib 4.7 兼容问题**；
- 影响：这两段动画（群体增益、站立扇翅）不播放。
- **建议不动作**：在资源包里改等于替上游维护 bug；要跟进则上报 DS / GeckoLib。
- 顺带澄清：满屏的 `Found animation file with improper file name format`
  （`dragonsurvival:animations/dragon_*.json`、`irons_spellbooks:…casting_animations.json`、
  `darkdoppelganger:…doppel_casting_animations.json`）是**建议性警告**，多个模组都这样命名 ⇒ 不是问题。

另有一条 DS DEBUG：`Failed to get skin information for custom stage star_dragon:stardust …
Falling back to using adult stage` —— 查源码确认**与网络无关**（不是上面的 SSL），
而是该自定义阶段不在 DS 的 `validStages` 里 ⇒ **star_dragon 附属的数据缺口**（星尘阶段用了成年皮肤），非本仓库问题。

## 7. 未能验证

- GeckoLib 具体在 `mass_buff` / `flapping_wings_standing` 的**哪个字段**解析失败：这两个块与原件等价，
  问题在 85 / 74 根骨骼的关键帧内部，需要逐关键帧比对（本次未做，价值低）。
- 上述三方问题的**上游修复状态**（未去查各模组的 issue）。
