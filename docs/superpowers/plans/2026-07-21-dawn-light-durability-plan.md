# 黎明曙光耐久度改造 — 实施计划

**目标:** 将黎明曙光从堆叠消耗品改为耐久度物品（10耐久/3分钟冷却/双持可用）
**架构:** 单文件改动 `DawnLightEffect.java`，无新增文件、无新增依赖
**方案:** A — 纯耐久度方案

---

### Task 1: 修改 DawnLightEffect 物品属性与逻辑

**文件:**
- 修改: `src/main/java/com/zonlong/beloong/item/effect/DawnLightEffect.java`

**Steps:**

1. 新增 import `net.minecraft.world.entity.EquipmentSlot`
2. 修改 `Item.Properties`: `.stacksTo(16)` → `.stacksTo(1).durability(10)`
3. 修改 `COOLDOWN_TICKS`: `200` → `3600`（3 分钟 = 3600 ticks）
4. 修改物品消耗逻辑: `stack.consume(1, player)` → 
   ```java
   EquipmentSlot slot = hand == InteractionHand.MAIN_HAND
           ? EquipmentSlot.MAINHAND
           : EquipmentSlot.OFFHAND;
   stack.hurtAndBreak(1, player, slot);
   ```

**验证:** `./gradlew build` — BUILD SUCCESSFUL
