# 建筑之杖 × 龙宫保护：认领语义对等（plan）

**日期：** 2026-10-13
**设计：** `docs/plans/2026-10-13-wands-loong-palace-claim-parity-design.md`
**状态：** **已完成并实机验收**（2026-10-13）

## 前置（已完成）

- [x] 克隆参考源：`D:\Minecraft\开源模组参考文件\wands`（main = v3.2.1；**注入点一律以 3.0.5 的 jar 为准**）
- [x] `build.gradle`：`compileOnly` + `localRuntime "curse.maven:building-wands-363392:8207219"`（= 3.0.5，465,238 字节）
- [x] `neoforge.mods.toml`：`modId="wands"` / `type="optional"` / `versionRange="[3.0.5,)"` / `ordering="AFTER"` / `side="BOTH"`

## Task 1：新增对等判定

**修改** `src/main/java/com/zonlong/beloong/compat/ftbchunks/LoongPalaceProtectionPolicy.java`

- 新增 `shouldTreatAsClaimed(LevelAccessor level, @Nullable Entity actor)`：
  `ModList.get().isLoaded("ftbchunks")` 为假 ⇒ `false`；否则 `shouldPrevent(level, actor)`。
- 需要 `import net.neoforged.fml.ModList;`

**判据：** 非龙宫维度恒 `false`；龙宫 + 无 bypass ⇒ `true`；龙宫 + 有 bypass ⇒ `false`。

## Task 2：Mixin

**新建** `src/main/java/com/zonlong/beloong/mixin/wands/ClaimDispatchMixin.java`

```java
@Pseudo
@Mixin(ClaimDispatch.class)
public class ClaimDispatchMixin {
    @Inject(method = "claimCanInteract", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void beloong$loongPalaceAsClaimed(
            ServerLevel level, BlockPos pos, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (LoongPalaceProtectionPolicy.shouldTreatAsClaimed(level, player)) {
            cir.setReturnValue(false);
        }
    }
}
```

硬约束（§四）：目标方法是 `static` 且 `package-private` 可见 ⇒ 处理器必须 **`private static`**，
且**必须以 `CallbackInfoReturnable<Boolean>` 结尾**（即使不使用也不能省）。
`@Pseudo` + `require = 0` ⇒ 模组缺席/改名时整体跳过。

## Task 3：注册

**修改** `src/main/resources/beloong.mixins.json`：在 `mixins` 数组加 `"wands.ClaimDispatchMixin"`
（**不加**到 `client` 数组 —— 目标是双端公共类）。

## Task 4：验证

```powershell
# ① 编译 + 打包
.\gradlew.bat build --rerun-tasks --no-build-cache
# ② 注入点核对（锚点必须仍存在，否则 require=0 会静默失效）
$jar = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\curse.maven\building-wands-363392\8207219\*\building-wands-363392-8207219.jar"
& "$env:JAVA_HOME\bin\javap.exe" -p -cp (Get-Item $jar).FullName net.nicguzzo.compat.claims.ClaimDispatch
# ③ 确认 mixin 真的挂上（成功侧）：run\logs\debug.log 里找
#    Mixing wands.ClaimDispatchMixin from beloong.mixins.json into net.nicguzzo.compat.claims.ClaimDispatch
# ④ 产物复核：jar 内含 mixin/wands/ClaimDispatchMixin.class，且 META-INF/neoforge.mods.toml 里有 modId="wands"
```

## Task 5：实机验收清单

| # | 场景 | 期望值 | 判据 |
|---|---|---|---|
| 1 | 龙宫内，无 bypass 账号，建筑之杖放方块 | **被拦 + 红字 `wands.message.claimed_chunk`** | 方块不变；提示出现 |
| 2 | 龙宫内，同上，用建筑之杖**挖/替换**模式 | 同上，被拦 | 方块不变 |
| 3 | 龙宫内，右键箱子/门/按钮等**交互** | **正常** | 交互生效（要求 3） |
| 4 | **主世界**未认领处，同一账号放方块 | **正常** | 建筑之杖照常工作（确认我们没干预非龙宫） |
| 5 | 有 FTB `bypass_protection` 的账号在龙宫放方块 | **正常** | 管理员/建造者仍可施工 |
| 6 | 龙宫内的原版手动破坏/放置 | 仍被拦（现有 12 道门） | 与本次改动无关，回归确认 |
| 7 | 未装建筑之杖时启动 | 正常，无 `Mixin apply failed` | 启动日志无异常 |

**旋钮：** 无（不新增配置项）。要临时关掉整套龙宫保护，用 `[loong_palace.environment_protection] enabled`
—— ⚠️ 但本改动**不读**该开关（与既有玩家编辑保护一致，见 design §6.3/§9）。

## 计划外/待裁定

- design §9 的两处文档漂移（`enabled` 语义、FTB 缺席时的实际行为）需单独裁定后回填 `龙宫维度总设计.md`。

## 实施回填（2026-10-13 收尾）

**执行状态：全部完成，并经实机验收。**

| Task | 结果 |
|---|---|
| 前置（参考源 / Gradle 依赖 / toml 声明） | ✅ jar = 465,238 B，与 `D:\Downloads\BuildingWands-neoforge-MC1.21.1-3.0.5.jar` **同大小** ⇒ 确认文件号 8207219 就是 3.0.5 |
| Task 1 对等判定 | ✅ `LoongPalaceProtectionPolicy#shouldTreatAsClaimed` |
| Task 2 Mixin | ✅ `mixin/wands/ClaimDispatchMixin`；锚点签名**逐字**核对：`public static boolean claimCanInteract(ServerLevel, BlockPos, Player)` |
| Task 3 注册 | ✅ `beloong.mixins.json` 共 48 条（进 `mixins`，**非** `client`） |
| Task 4 验证 | ✅ `build --rerun-tasks --no-build-cache` → BUILD SUCCESSFUL；产物含 mixin class、打包后的 toml 含 `modId="wands"` + `versionRange="[3.0.5,)"` |
| Task 5 实机验收 | ✅ **用户实测通过** —— "建筑之杖会尊重龙宫的保护了" |

**计划之外真实发生的事**

1. **注入点不是原先设想的 `Wand#place_block`，而是更靠外的 `ClaimDispatch#claimCanInteract`。**
   核对后发现 `place_block` 的**第一步**就是这道门，而门**全 jar 只有一个调用者** ⇒ 注入这一层覆盖面
   完全相同，但语义更贴（"报称已被认领"），且不依赖 `place_block` 内部后续改写。
2. **`WandScreen` 曾被误判为 `place_block` 的客户端调用者。** 那是我扫 jar 时**子串**命中了翻译键
   `screen.wands.replace_blocks`。改用 `javap` 看字节码后确认：`place_block` 里是对 `level` 的
   **硬 `checkcast` 到 `ServerLevel`、无 `instanceof` 门** ⇒ 它**只可能在服务端跑**，故 mixin 无需判 side。
   （教训：字符串命中 ≠ 符号引用，必须 `javap` 复核。）
3. **`util/ClaimProtectionHelper#isClaimed` 看似是现成入口，但不能直接用**：它会对**所有**位置套用
   FTB 标准判定（`ALWAYS_BLOCK` 恒 `CHECK`），而建筑之杖用的是认 `ftbchunks:edit_whitelist` 白名单的
   `Protection.EDIT_BLOCK` ⇒ 照搬会让本模组**在非龙宫维度比建筑之杖更严**。故新判据**只对龙宫生效**。
4. **`enabled` 总开关没有接进新判据**，与既有 `shouldPreventEdit` 保持一致（见 design §9 漂移 A，待裁定）。
5. **`--rerun-tasks --no-build-cache` 的构建耗时 12m 10s**（会连带重跑 NeoForge 整条管线），不是卡住；
   另外 `build/libs` 里同时躺着 0.9.8~0.10.4 多个 jar，**取产物必须指名版本**。
