# 构建缓慢的定位与修复：删除本地 jar 仓库 `local-repo/`

**日期：** 2026-10-15 取证 / 2026-10-16 结论确认
**症状：** 冷构建（首次构建、改过 Gradle 设置或版本号之后）耗时高达 **10 分钟**；用户怀疑是"每次重新下载方块小镇（yuushya）"。
**结论：** 删除 `build.gradle` 里的本地 Maven 仓库声明 `maven { url = uri("local-repo") }` 与 `local-repo/` 目录后，
**首次构建降到约 3 分钟**（且那 3 分钟里还包含一次 44.7 MB 的 Maven 下载）⇒ **用户实测确认本地 jar 仓库就是根源**。

---

## 1. 量化基线（改前，本机实测）

| 情形 | 耗时 | 备注 |
|---|---|---|
| 配置缓存**命中** | **1.279 s** | `--profile`：Total 1.279s / Configuring 0s / Task Execution 0.025s |
| **冷配置**（`--no-configuration-cache --dry-run`，不执行任务） | **42.2 s** | Startup 25.07 + Settings and buildSrc 10.57 + Configuring Projects 3.82 |
| **全量 up-to-date 检查**（`--no-configuration-cache`，热 daemon） | **31.2 s** | 其中 **`:compileJava` 25.26 s**，而它就是 **`:compileClasspath` 解析/指纹 24.91 s** |
| 配置缓存**未命中**（真实构建） | **6 m 39 s** | `5 actionable tasks: 5 up-to-date` —— **一个任务都没执行、零下载** |
| `clean build --dry-run` 首跑（**新 key**，不执行任务） | **4 m 42 s** | 紧接着同样命令第二次：**2.2 s**（命中） |

⇒ 关键判据：**未命中的那 6m39s 里没有任何任务被执行、也没有任何下载** ⇒ 成本落在
"配置/任务图计算 + 输入指纹化 + 配置缓存写入"这一段，与编译、与网络无关。

## 2. 排除项（都不是原因）

| 曾被怀疑 | 实测结论 |
|---|---|
| **"每次重新下载 yuushya"** | **不是**。HTTP HEAD：jar **200** / `-sources.jar` **404** / `-javadoc.jar` **404**；`build --info` 全程 `Downloading`/`cursemaven`/`yuushya` 命中 **0** 条；当时 jar 由 `local-repo` 提供 ⇒ IDEA 显示"正在下载 方块小镇"是 `idea { module { downloadSources/downloadJavadoc = true } }` 在探测 sources/javadoc 的 **404**，不是拉 27 MB 的 jar |
| `--rerun-tasks` / `--no-build-cache` | **不是**。实测这两个 flag 仍 `Reusing configuration cache`（1.5 s） |
| 内存不足 / JVM 被杀 | **不是**。31.7 GB 内存、14.2 GB 可用、事件日志无 JVM 崩溃（当时的 `-1` 退出码是**外部集中停 daemon** 所致：`gradlew --status` 显示 8 个 daemon 全 `STOPPED (by user or operating system)`） |
| 工程树整体太大 | **不成立**。NPC 克隆的树更大（766 MB vs 574 MB）、`run/` 也更大（547 MB vs 214 MB），却只要约 1 分钟 |
| ModDevGradle 的 `createMinecraftArtifacts` | 单任务仅 **0.717 s**（up-to-date），不是大户 |

## 3. 对照：主分支 vs NPC 克隆（同为该项目的一条并行分支）

| 项 | 主分支 | NPC 克隆 |
|---|---|---|
| 依赖声明 | 58 条（curse 53） | 47 条（curse 43） |
| `local-repo` 兜底仓库 | **有**（93 MB / 8 文件） | **没有** |
| `build/review-src/`（解压的原版源码树） | **7,104 文件** | 无 |
| 工程树文件数 | **12,081** | 3,604 |
| 配置缓存 key 数（`.gradle/configuration-cache` 条目） | **85** | 10 |
| `org.gradle.jvmargs` | `-Xmx12G`（近两次 `gradle.properties` 提交里刚从 `-Xmx8G` 改过） | `-Xmx8G` |
| 首次构建 | **约 10 分钟** | 约 1 分钟 |

⇒ 结构性差异里**唯一与"依赖解析路径"有关**的就是 `local-repo`（以及它带来的 4 个本地件）。

## 4. 修复与结果

**改动**（2026-10-15，本仓库）：
- `build.gradle`：删除 `repositories` 里排在最前的 `maven { url = uri("local-repo") }` 及其注释；
  同步改掉 yuushya / health-bars 两处"优先本地"的注释。
- 删除 `local-repo/` 目录（8 文件 / 93 MB）。
- 文档：`AGENTS.md` §三（无源码树依赖的 jar 现从 Gradle 缓存取）、§十一（不再使用本地仓库 + 不要再加回来 +
  本机 cursemaven 吞吐事实）；`.gitignore` 去掉 `local-repo/` 段；`docs/首领崛起-冥界骑士-调研.md` 两处；
  `mixin/yuushya/YuushyaJoinDeclarationMixin` 的 javadoc。

**结果（用户实测）**：首次构建 **约 3 分钟**（含一次 44.7 MB 的 `legendary-monsters-944035:8902342` 下载），
此前最高 **10 分钟** ⇒ 问题解决。

**一次性的下载成本**：本机 cursemaven 吞吐实测 **≈ 0.2 MB/s**（28 MB 用了 217 秒）⇒
`yuushya` 28 MB / `legendary-monsters` 45 MB / `bossesrise` 24 MB 首次解析需几分钟，下完即进
`~/.gradle/caches/modules-2`，此后不再下载。

## 5. 未能验证

- **`local-repo` 为何会拖慢构建：未取证。** 只有"删掉即变快"的实测事实。两个候选（**均未验证**）：
  ① 未加 `content { includeGroup … }` 过滤的本地仓库会被**逐模块探测**；
  ② 本地仓库位于**工程树内**，其内容可能被纳入配置缓存的**输入指纹化**（删除后该部分成本消失）。
- 其它后备杠杆的收益均未实测：清 `build/review-src`（7,104 文件）、Windows Defender 排除
  `D:\Minecraft` 与 `%USERPROFILE%\.gradle`、`gradlew --stop` 收拢 daemon、给 `jitpack.io` 加 `content` 过滤。

## 6. 复查命令（下次再遇到"构建慢"时按此三步）

```powershell
# ① 判定是"命中"还是"未命中"（命中≈1 秒；未命中才会分钟级）
.\gradlew.bat build --profile --console=plain
Get-ChildItem build\reports\profile -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
# ② 看配置缓存为何失效（Gradle 会直接写明是哪个输入变了）
.\gradlew.bat build --info --console=plain 2>&1 | Select-String 'Calculating task graph|Reusing configuration cache'
# ③ 看指纹化大户（谁在 up-to-date 检查上花时间）
.\gradlew.bat build --no-configuration-cache --profile --console=plain
```

⚠️ 统计构建日志里的 `ERROR/FATAL` 要用 `[线程/LEVEL]` 口径（`\[(ERROR|FATAL)\]` 会误得 0 条）。
⚠️ 客户端（`runClient`）在跑时会占住配置缓存锁 ⇒ 此时构建报
`Timeout waiting to lock Configuration Cache … Owner PID`，关掉客户端再构建，或按 §二 加 `--no-configuration-cache`。
