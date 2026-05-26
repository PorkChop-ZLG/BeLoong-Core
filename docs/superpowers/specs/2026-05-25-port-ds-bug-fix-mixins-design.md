# 将 DS Bug Fix 的 Mixin 移植到 BeLoong-Core

## 概述

将 DS_bug_fix 模组中的 3 个 mixin 类移植到 BeLoong-Core，修复龙之生存的两个 bug：稳定悬停漂移和发光效果导致龙身体隐形。

## 新建文件

```
src/main/java/com/zonlong/beloong/mixin/
├── ClientFlightHandlerMixin.java       # 修复稳定悬停漂移
├── DragonItemRenderLayerMixin.java     # 修复手持物品发光导致龙身体隐形
└── OutlineBufferSourceAccessor.java    # OutlineBufferSource 内部字段的访问器

src/main/resources/
└── beloong.mixins.json                 # Mixin 配置（仅客户端）
```

## 修改文件

- **Config.java** — 清空示例条目（logDirtBlock、magicNumber 等），添加 `FIX_GLOWING_OUTLINE` 和 `FIX_STABLE_HOVER` 两个布尔开关（默认均为 true）。沿用现有的 `ModConfigSpec.Builder` 模式，无需 INSTANCE 字段（mixin 直接引用静态字段）。
- **build.gradle** — 无需修改；NeoForge MDG 2.x 会自动发现并应用 mixin 配置。

## Mixin 移植变更

包名：`com.tangwenjun.ds_bug_fix.Mixin` → `com.zonlong.beloong.mixin`
配置引用：`DSBugFixConfig.INSTANCE.fixXxx` → `Config.FIX_XXX`

其余逻辑原样复制，包括带 `ds_bug_fix$` 前缀的访问器方法名（避免与其他模组的访问器冲突）。

不移植自定义配置界面（`DSBugFixConfigScreen`）和语言文件 —— NeoForge 内置的 `ConfigurationScreen` 会自动处理配置显示。

## 依赖

无新增依赖。Dragon Survival 和 GeckoLib 已在 `build.gradle` 和 `neoforge.mods.toml` 中声明。

## beloong.mixins.json

```json
{
  "required": true,
  "package": "com.zonlong.beloong.mixin",
  "client": [
    "ClientFlightHandlerMixin",
    "DragonItemRenderLayerMixin",
    "OutlineBufferSourceAccessor"
  ],
  "compatibilityLevel": "JAVA_21",
  "injectors": {
    "defaultRequire": 1
  }
}
```

三个 mixin 均为仅客户端，因为它们只涉及渲染和飞行控制 —— 专用服务器上不会运行。
