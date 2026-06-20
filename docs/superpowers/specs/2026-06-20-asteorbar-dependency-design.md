# AsteorBar 可选依赖添加

## 目标

将 AsteorBar 模组添加为 BeLoong Core 的可选依赖，用于 Mixin 注入修复。

## 变更

在 `build.gradle` 的 `dependencies` 块中添加：

```gradle
// AsteorBar — 可选依赖（Mixin 注入修复）
compileOnly "curse.maven:asteorbar-959237:7121014"
localRuntime "curse.maven:asteorbar-959237:7121014"
```

- **compileOnly**：编译期可用，Mixin 可引用其类符号生成 refmap
- **localRuntime**：开发环境运行时可用，但不发布为传递依赖

放置位置：与其他类似可选依赖并列（如 F-Sweep、Beyond Dimensions 附近）。

## 影响范围

- 仅 `build.gradle` 一个文件
- 不影响任何现有功能
- AsteorBar 未安装时不影响模组运行
