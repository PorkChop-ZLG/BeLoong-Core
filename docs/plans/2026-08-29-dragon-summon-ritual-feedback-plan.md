# 末影龙召唤仪式粒子与音效反馈实施计划

**Date:** 2026-08-29
**Status:** Ready for implementation
**Depends on:** `docs/plans/2026-08-29-dragon-summon-ritual-feedback-design.md`

## 目标

在现有末影龙手动召唤仪式上增加：
1. 空位提醒粒子。
2. 单个方块放置成功反馈。
3. 仪式完成反馈。
4. 保持即时转化，不增加延迟。

## 实施步骤

### 1. 扩展 `DragonSummonHelper`

新增方法：

```java
// 单个方块放置成功：信标选择音 + 村民喜悦绿色粒子
static void playSingleBlockPlacedEffects(ServerLevel level, BlockPos pos)

// 仪式完成：信标激活音 + 龙息粒子
static void playRitualCompleteEffects(ServerLevel level, EndDragonFight fight)

// 空位提醒：向指定玩家发送 END_ROD 粒子
static void sendEmptySlotParticle(ServerLevel level, ServerPlayer player, BlockPos pos)
```

- 单块：`level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 1.0F)` + `ParticleTypes.HAPPY_VILLAGER`
- 完成：`level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F)` + 4 个位置 `ParticleTypes.DRAGON_BREATH`
- 空位：`level.sendParticles(player, ParticleTypes.END_ROD, false, x+0.5, y+0.5, z+0.5, 5, 0.3, 0.3, 0.3, 0)`

### 2. 扩展 `DragonSummonHandler`

#### A. `onBlockPlace`

- 在确认“正确方块 + 仪式位置”后：
  - 如果 4 个位置**未全部放满**：
    - 调用 `DragonSummonHelper.playSingleBlockPlacedEffects(serverLevel, event.getPos())`
    - 直接返回
  - 如果 4 个位置**全部放满**：
    - 调用 `DragonSummonHelper.playRitualCompleteEffects(serverLevel, fight)`
    - 继续执行现有即时转化逻辑

#### B. 新增 `onServerTick`

- 监听 `ServerTickEvent.Post`
- 每 20 tick 执行一次（`server.getTickCount() % 20 == 0`）
- 获取末地 `ServerLevel`
- 获取 `EndDragonFight`
- 获取 4 个仪式位置
- 筛选距离仪式中心 32 格内的玩家
- 对每个空位调用 `DragonSummonHelper.sendEmptySlotParticle(...)`

### 3. 构建验证

- `./gradlew build`
- 游戏内验证：
  - 进入末地后 4 个空位有提醒粒子
  - 放置 1~3 个正确方块时播放信标选择音 + 绿色粒子
  - 放置第 4 个时播放信标激活音 + 龙息粒子，并立即转化为末地水晶
  - 转化无延迟

## 风险点

- 粒子发送 API 签名可能因版本有差异，以编译/运行结果为准。
- 空位粒子每 20 tick 发送，范围限制在 32 格内，性能风险低。
