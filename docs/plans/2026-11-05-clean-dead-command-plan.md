# `/beloong cleandead` —— 实施计划

**日期：** 2026-11-05
**设计：** `2026-11-05-clean-dead-command-design.md`
**产物：** `build/libs/beloong-<版本>.jar`（版本以 `gradle.properties` 为准）

## Task 1｜判定工具上提到 `util`

- `compat/ironsspellbooks/CustomDeathSequence` → **`util/CustomDeathSequence`**（命令与霜噬修复共用）。
- 更新 `mixin/ironsspellbooks/FrostbiteDeathSequenceMixin` 的 import；删掉空的 `compat/ironsspellbooks/`。
- 验收：`compileJava` 通过、0 警告。

## Task 2｜命令本体

- 新文件 `command/CleanDeadCommand.java`：
  - `/beloong cleandead`（`executes` → 只列）与 `/beloong cleandead confirm`（执行）；
  - 根节点 `.requires(source -> source.hasPermission(2))`；
  - `scan()` 遍历 `getAllLevels()` × `getAllEntities()`，收集快照（id/坐标/维度/deathTime/是否自管死亡）；
  - `confirm`：Tier 1 `deathTime = 0`，Tier 2 `remove(KILLED)`；先收集后动手（避免边遍历边删）。
- 在 `BeLoongCore.onRegisterCommands` 注册（含 import）。
- 验收：`compileJava` 通过、0 警告。

## Task 3｜语言键

- `zh_cn.json` / `en_us.json` 各加 8 条 `beloong.command.cleandead.*`（header / entry_arm / entry_remove /
  more / confirm_hint / confirm_hover / done / none），插在 `essence` 段之前。
- 验收：`python tools\check_lang_parity.py` 通过（键集 + 占位符 + 颜色码）。

## Task 4｜构建与产物复核

- `build --rerun-tasks --no-build-cache --no-configuration-cache --offline` → 0 警告；
- 解析 jar：`CleanDeadCommand.class` 在包内、`beloong.mixins.json` 未受影响、两个 lang 文件键数一致。

## Task 5｜端到端自测（本机，已完成）

用 `--quickPlaySingleplayer` 进一个**测试存档副本**，借 KubeJS 在玩家登录后把玩家传到尸体处并延迟执行两条命令。
实测日志：

```
[BeLoong] cleandead(listed): bosses_of_mass_destruction:void_blossom @ 738 96 209 (dim=minecraft:overworld, deathTime=5949) -> rearm
[BeLoong] cleandead(listed): … @ 759 94 230 … deathTime=5894 -> rearm
[BeLoong] cleandead(listed): … @ 697 96 230 … deathTime=5741 -> rearm
[BeLoong] cleandead(applied): … ×3 -> rearm
[BeLoong] cleandead: armed=3 removed=0 by=Server
```

⇒ 命令在**真实存档实体**上：跨维度扫描 OK、Tier 1 判定 OK、执行 OK、日志 OK。

## Task 6｜实机验收（委托方）

| # | 场景 | 期望 | 判据 |
|---|---|---|---|
| T1 | 在有假死尸体的老存档里执行 `/beloong cleandead` | 列出命中清单 + 金色可点击确认 | 聊天框出现清单；`latest.log` 有 `cleandead(listed)` |
| T2 | 点确认或执行 `… confirm` | 虚空之花约 3.5 秒后消失，**并补发 1000 经验** | 日志 `cleandead: armed=N removed=0`；尸体消失；地面出现经验 |
| T3 | 未知模组的假死实体 | 被直接移除，清单里标 `→ 直接移除` | 日志 `removed=N` |
| T4 | 控制台执行 | 与玩家执行等价（不报"必须以玩家身份执行"） | 控制台输出清单 |
| T5 | 无假死实体的存档 | 提示"没有发现假死实体" | 无日志刷屏 |

## Task 7｜收尾

- 删除临时测试设施：`run/kubejs/server_scripts/zz-cleandead-test.js`、`run/cleandead-test/`、
  `run/saves/cleandead-test/`、`build.gradle` 里 `-PquickPlay` 的临时钩子；
- 设计文档状态行改「已实施并实机验收」；本计划回填执行状态；
- `memory/decisions-log.md` 追加一条。

## 计划之外（实施期实际发生）

- 先用 `runServer` 跑端到端，**失败**：`NoClassDefFoundError: org/lwjgl/Version` —— 本项目的 dev 专用服务端
  起不来（客户端专属依赖在 classpath 上）。改走 `runClient --quickPlaySingleplayer`。
- 第一版数据包 harness 无效：`minecraft:load` 函数在区块加载前执行，且函数源是
  `withSuppressedOutput()`，看不到任何输出；改为 KubeJS `PlayerEvents.loggedIn` + 传送玩家（顺带加载区块）+ 延迟执行。
- `check_lang_parity.py` 在本机 GBK 控制台下会因 `✅` 抛 `UnicodeEncodeError`（既有问题）；
  已给它加 `sys.stdout.reconfigure(errors='replace')`，退出码恢复正常。


---

## 2026-11-05 修订（rev 1）：改为「只移除」+ 铁魔法修复加配置开关

> **本节取代** Task 2（动作设计）里"归零重放结算"的描述。其余（判定、范围、权限、输出）不变。

1. **命令不再重放死亡结算**（委托方裁定）：`deathTime = 0` 会让该模组把被跳过的
   `== 1 / == 50` 等结算重新跑一遍 ⇒ 额外补发经验/战利品/爆炸，属于凭空造物。
   现在**一律 `remove(RemovalReason.KILLED)`**：`die()` 早在假死那一刻跑完，战利品与死亡事件该发的都发过。
   代价（如实登记）：祸乱鬼魅尸体会留下自己放的 `minecraft:light` 方块（`/setblock <pos> air` 可清），
   下界铁掌不会再爆一次、也不掉远古残骸。
   ⇒ `util/CustomDeathSequence` 不再被本命令使用，改由霜噬修复的 Mixin 独占（保留在 `util/`）。

2. **霜噬修复变成 COMMON 配置项**（Task 3 起新增）：`frostbite_death_fix.enabled`（默认 `true`），
   键 `beloong.configuration.frostbiteDeathFixEnabled`（中英同步）。
   关闭后完全恢复上游行为 —— 用于**复现假死**、以及验证本命令的清理效果。
   消费方：`mixin/ironsspellbooks/FrostbiteDeathSequenceMixin`（每次死亡读一次开关）。


---

## 收尾（2026-11-06 回填）

| 项 | 结果 |
|---|---|
| Task 1–4（工具/命令/语言/构建） | ✅ |
| Task 5 端到端自测（本机） | ✅ 真实存档副本上 `listed/applied` 各 3 条、`armed=3 removed=0`；随后该世界 `void_blossom` 由 3 具变 0 具 |
| Task 6 实机验收 | ✅ 委托方：「清理指令生效」 |
| 命令动作 | ✅ 已按裁定改为**只移除**（不重放奖励），rev 1 早已回填 |
| `confirm` 前置 | ✅ 委托方裁定**保持现状**（不强制先列清单），理由见设计文档 rev 2 |
| 测试设施 | ✅ KubeJS 脚本、两个世界副本、`build.gradle` 的 `-PquickPlay` 钩子均已删除/还原 |

**计划之外真实发生的事**：
1. 首次 `runServer` 端到端失败（`NoClassDefFoundError: org/lwjgl/Version`，本项目 dev 专用服务端起不来）
   ⇒ 改走 `runClient --quickPlaySingleplayer` + KubeJS 传送玩家。
2. 第一版数据包 harness 无效：`minecraft:load` 太早（区块未加载）、函数源 `withSuppressedOutput()` 看不到输出。
3. 差一点对着"给专用服务端准备的世界副本"去核对结果（`quickPlaySingleplayer <id>` 找的是 `saves/<id>`）。
4. 铁魔法修复开关后来由另一会话改成 flat 键 `fixFrostbiteDeath`，与本文档 rev 1 写的键名不一致
   （见设计文档 rev 2）。
