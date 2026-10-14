# 龙宫禁用铁魔法施法（plan）

**日期：** 2026-10-13
**设计：** `docs/plans/2026-10-13-loong-palace-spell-ban-design.md`
**状态：** **已完成并实机验收**（2026-10-14）

## 前置事实（已核实，勿重复调研）

- 参考源 `D:\Minecraft\开源模组参考文件\irons-spells-n-spellbooks` 的 `mod_version` = **`1.21.1-3.16.3`**，
  与本项目 `build.gradle:300` 的依赖**同版**；关键签名另经**真实 jar** `irons_spellbooks-1.21.1-3.16.3.jar` javap 核对。
- 钩子：`SpellPreCastEvent`（可取消），post 于 `AbstractSpell#attemptInitiateCast`（`:281`），
  **在扣蓝/冷却/卷轴消耗之前**，且该方法 `level.isClientSide → return false`（只在服务端跑）。
- 铁魔法**没有**任何"按维度/按法术禁用"的数据或配置机制（`SpellFilter` 只服务于战利品/交易；`ServerConfigs` 只有冒险模式开关）。

## Task 1：新增处理器

**新建** `src/main/java/com/zonlong/beloong/compat/ironsspellbooks/LoongPalaceSpellBanHandler.java`

- 常量 `BANNED_SOURCES = EnumSet.of(SPELLBOOK, SCROLL, SWORD)`。
- 构造即注册（与 `BeLoongCore` 注册块的邻居同风格）：`NeoForge.EVENT_BUS.register(new LoongPalaceSpellBanHandler())`。
- `@SubscribeEvent onSpellPreCast(SpellPreCastEvent)`：
  总开关 → `LoongPalaceProtectionPolicy.isLoongPalace(player.level())` → `BANNED_SOURCES.contains(getCastSource())`
  → `!hasBypass(player)` ⇒ `event.setCanceled(true)` + 提示。
- `@SubscribeEvent onTravelToDimension(EntityTravelToDimensionEvent)`：
  两端任一为龙宫 ⇒ `Utils.serverSideCancelCast(serverPlayer, false)`（内部已判 `isCasting`）。
- `FakePlayer` 不发提示（沿用既有 `notifyPlayer` 先例）。

**判据：** 编译通过；`/cast`（`COMMAND`）不被拦；非龙宫维度不受影响。

## Task 2：语言键（§五 要求中英同步，值内自带 `§c`，沿用 `message.beloong.*` 习惯）

两个文件都插在 `message.beloong.flight_banned` 之后、`message.beloong.loong_palace_portal.*` 之前（字典序）：

```json
"message.beloong.loong_palace.spell_banned": "§c龙宫之内禁止施法！",
"message.beloong.loong_palace.spell_banned": "§cSpellcasting is forbidden in the Loong Palace!",
```

## Task 3：注册

**修改** `BeLoongCore.java`：紧接 `DeadKingAdvancementHandler` 之后加一行
`NeoForge.EVENT_BUS.register(new LoongPalaceSpellBanHandler());`（铁魔法是 `type="required"`，无需 `isLoaded` 判断）+ import。

## Task 4：验证

```powershell
# ① 语言门（中英键集/占位符/颜色码一致性）
python tools\check_lang_parity.py
# ② 构建 + 产物复核
.\gradlew.bat build --rerun-tasks --no-build-cache
# ③ 产物内类存在（解析，不要字符串匹配）
& "$env:JAVA_HOME\bin\javap.exe" -p -cp build\libs\beloong-<版本>.jar com.zonlong.beloong.compat.ironsspellbooks.LoongPalaceSpellBanHandler
# ④ 警告数不得超出基线（ParameterListAccessor ×2 / PossibleBiomesFilterMixin ×1）
```

> 纯 Java 逻辑（无 mixin、无数据包）⇒ 不需要 `runClient` 应用期验证，但**必须**实机验收。

## Task 5：实机验收清单

| # | 场景 | 期望值 |
|---|---|---|
| 1 | 龙宫内，手持**法术书/法杖**右键 | 施不出 + 提示；**蓝量、冷却都不变** |
| 2 | 龙宫内，**快捷施法**（按键） | 同上 |
| 3 | 龙宫内，用**剑**施法 | 同上 |
| 4 | 龙宫内，用**卷轴** | 施不出；**卷轴不消耗** |
| 5 | 龙宫内，**`/cast` 指令**（含 mcfunction 龙技能） | **正常施放**（放行） |
| 6 | **龙宫外**以上各项 | 全部正常（无回归） |
| 7 | 龙宫外起手 → **立刻传送进龙宫** | 施法被**打断**，法术不生效 |
| 8 | 龙宫内起手（若被放行的 source）→ **传送出龙宫** | 施法被**打断** |
| 9 | 有 FTB `bypass_protection` 的账号在龙宫内施法 | **正常**（放行） |
| 10 | 关掉 `enabled` 总开关后在龙宫内施法 | **正常**（总开关生效） |
| 11 | 连续施法型（CONTINUOUS）在龙宫内起手 | 被拦（不进施法状态） |

**旋钮：** `beloong-server.toml → [loong_palace.environment_protection] enabled`（关掉即整体失效）。

## 已知取舍（不在本轮范围）

- **怪物/NPC 施法**不受本门约束（`SpellPreCastEvent extends PlayerEvent`）⇒ 要覆盖需 mixin。
- **"禁止施法" ≠ "禁止被法术破坏"**：已存在的法术效果（火、黑洞等）仍会作用，由既有 12 道门部分兜住。
- `CastSource` 无法区分"法术书右键"与"快捷施法"，只能一起禁。

## 接口约定（留给未来联动模组）

代码级为龙技能施放铁魔法时，请用 **`CastSource.COMMAND`** 或自注册 source —— 黑名单之外一律放行。

## 实施回填（2026-10-14 收尾）

**执行状态：全部完成并经实机验收。**

| Task | 结果 |
|---|---|
| Task 1 处理器 | ✅ `compat/ironsspellbooks/LoongPalaceSpellBanHandler`（黑名单 `SPELLBOOK/SCROLL/SWORD` + 跨维度打断） |
| Task 2 语言键 | ✅ `message.beloong.loong_palace.spell_banned`（zh/en 各 1 键） |
| Task 3 注册 | ✅ `BeLoongCore` 紧接 `DeadKingAdvancementHandler` |
| Task 4 验证 | ✅ 语言门 `✅ 通过`（exit 0，328 = 328 键）；构建 `BUILD SUCCESSFUL`、警告 = 基线 3 条；产物 javap 出两个事件处理器 |
| Task 5 实机验收 | ✅ **用户实测通过**（"功能正常"） |

**计划之外真实发生的事**

1. **注册风格改成邻居的写法**：原计划用静态 `register()`（照 `LoongPalaceProtectionHandler`），
   但 `BeLoongCore` 注册块的主流是 `NeoForge.EVENT_BUS.register(new X())`，且铁魔法是**必选依赖**、
   无需条件注册 ⇒ 改成构造即注册，删掉了自造的 `register()` 与随之无用的 `NeoForge` import。
2. **`Utils.serverSideCancelCast` 的 1 参重载会解引用 `getCastingSpell()`**（未施法时可能 NPE），
   而 2 参重载只是转调 `CancelCastPacket#cancelCast` —— 后者**内部自判 `isCasting()`**
   ⇒ 改用 `serverSideCancelCast(player, false)`：不必自己判状态、无 NPE 风险、且不因打断而强加冷却。
3. **铁魔法自己跨维度时并不打断施法**（`ServerPlayerEvents:741` 只做两件事：拦召唤物跨维度、
   传送时清掉施法者的召唤物）⇒ 印证边界①必须由我们补；顺带确认"玩家召唤物在龙宫残留破坏"
   不是主要问题（传送时会被清）。
4. **语言门 `tools/check_lang_parity.py` 在 GBK 控制台下会崩**：打印 `✅` 时抛 `UnicodeEncodeError`，
   **通过也退出 1**。绕法 `$env:PYTHONIOENCODING='utf-8'`（之后 exit 0）。
   ⚠️ 以后跑这个门务必带该环境变量，否则会把"通过"误判成失败。
5. **收尾前的日志体检**（用户要求）另发现若干**三方**噪声与内容问题 —— 见
   `docs/reviews/2026-10-14-run-logs-health-check.md`；其中唯一一条与我们的内容相关的
   （DS 两个动画解析失败）已定位**不是我们的资源包**（与 DS jar 原件逐通道一致）。
