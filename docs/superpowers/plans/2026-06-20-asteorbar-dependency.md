# AsteorBar 可选依赖添加 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 AsteorBar 添加为 BeLoong Core 的可选依赖（compileOnly + localRuntime）

**Architecture:** 在 `build.gradle` 的 `dependencies` 块中添加两条依赖声明，与现有可选依赖（F-Sweep、Beyond Dimensions）并列

**Tech Stack:** Gradle / NeoForgeMDK / CurseMaven

---

### Task 1: 添加 AsteorBar 依赖

**Files:**
- Modify: `build.gradle:178-180`

- [ ] **Step 1: 在 F-Sweep 依赖块之后插入 AsteorBar 依赖**

在 `build.gradle` 中 F-Sweep 的 `localRuntime` 行之后插入：

```gradle

    // AsteorBar — 可选依赖（Mixin 注入修复）
    compileOnly "curse.maven:asteorbar-959237:7121014"
    localRuntime "curse.maven:asteorbar-959237:7121014"
```

位置：第180行（`localRuntime "curse.maven:f-sweep-1469993:7855396"`）之后，第181行空白行之前。

- [ ] **Step 2: 验证 Gradle 配置正确**

运行：
```bash
./gradlew dependencies --configuration compileClasspath 2>&1 | grep -i asteorbar
```
预期：输出包含 `asteorbar` 相关行，确认 compileOnly 生效。

- [ ] **Step 3: 提交**

```bash
git add build.gradle
git commit -m "build: add AsteorBar as optional dependency (compileOnly + localRuntime)"
```
