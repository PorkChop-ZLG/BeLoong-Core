# 天灾传送门 — 配置驱动槽位系统重构

## 问题

`Config.DisasterPortal.eyeItems` 定义了 12 种眼球物品，但 `DisasterPortalFrame.useItemOn()` 使用 `ModBlocks.FULL_ID_TO_EYE_KEY`（编译期硬编码 `static final` Map）做校验，配置文件从未被读取 —— 改配置不生效。

## 方案

将 EyeType 枚举从"语义化短键"改为"通用数字槽位"，使枚举与具体模组物品解耦。配置列表通过**位置索引**映射到槽位编号。

### EyeType 枚举

```
EMPTY("0")
SLOT_1("1")  ~  SLOT_12("12")
```

- `getSerializedName()` 返回 `"0"` ~ `"12"`
- `fromKey("3")` → `SLOT_3`

### 配置映射

`eyeItems` 列表的第 0 项 → 槽位 `"1"`，第 1 项 → 槽位 `"2"`，……第 11 项 → 槽位 `"12"`。

```java
// ModBlocks 新增工具方法
public static int getEyeSlot(ItemStack stack) {
    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    List<? extends String> items = Config.DisasterPortal.eyeItems.get();
    int idx = items.indexOf(id);
    return idx >= 0 ? idx + 1 : 0;
}
```

### 交互流程

```
玩家手持物品右键框架
  → getEyeSlot(stack) 查配置列表
  → 返回 0：PASS（不是有效眼球）
  → 返回 1~12：EyeType.fromKey(String.valueOf(slot))
  → 去重检查（同槽位已存在则拒绝）
  → 嵌入：HAS_EYE=true, EYE_TYPE=对应值
  → BlockEntity 存 String.valueOf(slot)
  → 12 帧全满 → 激活传送门
```

### BlockEntity 数据

- `eyeId` 字段从完整物品 ID（`"cataclysm:mech_eye"`）改为槽位编号（`"1"`~`"12"`）
- 空框架存 `"0"`

## 改动清单

| 文件 | 改动 |
|------|------|
| `block/EyeType.java` | 枚举值改为 SLOT_0~12，序列化名 "0"~"12" |
| `registry/ModBlocks.java` | 删除 EYE_KEYS/EYE_KEY_TO_FULL_ID/FULL_ID_TO_EYE_KEY/EYE_TYPE_VALUES；新增 getEyeSlot() |
| `block/DisasterPortalFrame.java` | useItemOn() 改用 getEyeSlot() + EyeType.fromKey()；删除对 ModBlocks 硬编码 Map 的引用 |
| `block/DisasterPortalFrameEntity.java` | eyeId 默认值改为 "0"；isEmpty() 检查 "0"；注释更新 |
| `blockstates/disaster_portal_frame.json` | eye_type 值改为 "0"~"12" |
| `models/block/disaster_portal_frame_*.json` (13 个) | 重命名为 `_0` ~ `_12`；内部纹理引用更新 |
| `textures/block/disaster_portal_frame_top_*.png` (12 个) | 重命名为 `_1` ~ `_12` |
| `textures/block/disaster_portal_frame_eye_*.png` (12 个) | 重命名为 `_1` ~ `_12` |

纹理文件名格式：
- `disaster_portal_frame_top_1.png` ~ `disaster_portal_frame_top_12.png`
- `disaster_portal_frame_eye_1.png` ~ `disaster_portal_frame_eye_12.png`

模型 JSON 内部纹理引用格式：
```json
{
  "parent": "minecraft:block/end_portal_frame_filled",
  "textures": {
    "particle": "beloong:block/disaster_portal_frame_side",
    "bottom": "beloong:block/disaster_portal_frame_bottom",
    "top": "beloong:block/disaster_portal_frame_top_1",
    "side": "beloong:block/disaster_portal_frame_side",
    "eye": "beloong:block/disaster_portal_frame_eye_1"
  }
}
```

## 兼容性说明

- **旧存档**：EyeType 枚举值变更后，旧 BlockState 的 `eye_type` 字符串不匹配，已放置的框架会回退为 EMPTY。需要提醒用户在更新前拆除已放置的传送门框架。
- **配置文件**：默认值保持不变（12 种眼球物品 ID），用户只需知道"第 N 个配置项对应第 N 种纹理"。
