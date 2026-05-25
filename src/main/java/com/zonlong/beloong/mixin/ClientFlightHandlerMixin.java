package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.zonlong.beloong.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复龙之生存模组中"稳定悬停"功能的漂移问题。
 *
 * <h2>修复前的问题</h2>
 * 在龙之生存模组中，龙的"稳定悬停"状态（张开翅膀但无玩家操作）会在特定条件下发生漂移：
 * <ul>
 *   <li><b>生存模式：</b>龙在悬停时会缓慢水平漂移，无法真正停在原地</li>
 *   <li><b>创造模式：</b>龙在悬停时会缓慢向上飘升，高度不断增加</li>
 * </ul>
 * 根源在于：即使玩家没有任何按键输入，龙之生存的飞行控制系统
 * 仍然会为龙分配非零的加速度值（ax, ay, az），导致龙持续移动。
 *
 * <h2>修复原理</h2>
 * 在龙之生存完成飞行控制计算之后，我们注入本代码，
 * 检测当前是否处于"无输入悬停"状态，如果是则将加速度归零。
 *
 * <h2>Mixin 注解详解</h2>
 * <ul>
 *   <li><b>@Mixin：</b>告诉 Mixin 框架"我要注入到 ClientFlightHandler 类中"</li>
 *   <li><b>@Shadow：</b>访问目标类中的私有静态字段。ax/az/ay 是龙之生存定义的三个加速度变量</li>
 *   <li><b>@Inject(method = "flightControl", at = @At("TAIL"))：</b>
 *       在 {@code flightControl()} 方法执行完毕之前（TAIL = 尾部），
 *       调用我们的 {@code fixStableHoverDrift()} 方法</li>
 *   <li><b>remap = false：</b>因为目标类是 Dragon Survival 模组的类（不是 Mojang 的混淆映射类），
 *       方法名不需要经过映射转换</li>
 * </ul>
 *
 * <h2>为什么是 abstract class？</h2>
 * 当 mixin 只添加新方法而不覆写目标类已有方法时，可以使用 abstract class。
 * 如果覆写已有方法，则需要用普通的 class 加上 {@code @Overwrite} 注解。
 *
 * @see Config#FIX_STABLE_HOVER 此修复的开关配置
 */
@Mixin(ClientFlightHandler.class) // 目标类：龙之生存的客户端飞行控制器
public abstract class ClientFlightHandlerMixin {

    /**
     * 龙的 X 轴加速度（水平前后方向）。
     * {@code @Shadow} 表示"这个字段存在于目标类中，请帮我映射过来"。
     * 因为字段是目标类的私有静态字段，所以这里也必须声明为 {@code private static}。
     */
    @Shadow
    private static double ax;

    /**
     * 龙的 Z 轴加速度（水平左右方向）。
     */
    @Shadow
    private static double az;

    /**
     * 龙的 Y 轴加速度（垂直方向，正值 = 上升，负值 = 下降）。
     */
    @Shadow
    private static double ay;

    /**
     * 稳定悬停漂移修复的核心逻辑。
     *
     * <p>这个方法在龙之生存的 {@code flightControl()} 方法末尾被调用。
     * 此时龙之生存已经完成了本帧的飞行物理计算，
     * 我们在这里检查悬停条件并覆盖加速度值。</p>
     *
     * <h3>执行流程：</h3>
     * <ol>
     *   <li>检查配置文件中的修复开关是否开启（关闭则直接返回，不修改任何行为）</li>
     *   <li>获取当前客户端玩家实例</li>
     *   <li>确认玩家当前处于龙形态（不是龙则跳过）</li>
     *   <li>获取龙的飞行数据，确认翅膀已展开且具备飞行能力</li>
     *   <li>读取玩家的键盘输入（WASD、空格、Shift 等）</li>
     *   <li>判断是否满足"稳定悬停"条件：
     *     <ul>
     *       <li>龙之生存的 {@code stableHover} 开关已启用</li>
     *       <li>玩家没有按跳跃键（空格）</li>
     *       <li>玩家没有按潜行键（Shift）</li>
     *       <li>龙没有处于旋转冲刺状态</li>
     *       <li>龙没有处于滑翔状态</li>
     *     </ul>
     *   </li>
     *   <li>判断玩家是否没有任何移动输入（WASD 均为 0）</li>
     *   <li>如果以上条件全部满足，将水平加速度（ax, az）归零</li>
     *   <li>如果玩家是创造模式，额外将垂直加速度（ay）归零，
     *       并将当前垂直速度也设为 0（阻止创造模式的上飘）</li>
     * </ol>
     *
     * <h3>为什么创造模式需要额外处理？</h3>
     * 在创造模式下，Minecraft 的飞行机制与生存模式不同。
     * 创造模式的玩家没有重力，所以即使 ay 设为 0，
     * 龙可能仍会因为已有的垂直速度继续上升。
     * 因此我们额外调用 {@code player.setDeltaMovement(delta.x, 0, delta.z)}
     * 直接将垂直速度清零，确保龙在创造模式下也能完全静止悬停。
     *
     * @param ci Mixin 注入框架传入的回调信息（此方法中未使用，但方法签名必须包含）
     */
    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void fixStableHoverDrift(CallbackInfo ci) {
        // ========== 第1步：检查配置开关 ==========
        // 如果玩家在配置文件中关闭了此修复，直接返回不做任何处理
        if (!Config.FIX_STABLE_HOVER.get()) {
            return;
        }

        // ========== 第2步：获取当前玩家 ==========
        // Minecraft.getInstance() 是获取游戏全局实例的唯一入口
        // .player 是当前客户端控制的玩家（单人 = 本地玩家，多人 = 控制中的角色）
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return; // 玩家实例不存在（如正在加载世界），安全退出
        }

        // ========== 第3-4步：验证龙形态和飞行状态 ==========
        // DragonStateProvider.getOptional(player) 返回一个 Optional 容器：
        //   如果玩家是龙 → 容器里有值，执行 ifPresent 里的 lambda
        //   如果玩家不是龙 → 容器为空，跳过整个 lambda，什么都不做
        DragonStateProvider.getOptional(player).ifPresent(handler -> {
            // handler.isDragon()：检查这个生物实体是否处于"龙的形态"
            // 龙族玩家可能切换人形态/龙形态，只有龙形态才需要修复
            if (!handler.isDragon()) {
                return; // Lambda 中的 return 只退出 lambda，不会退出整个方法
            }

            // FlightData.getData(player)：获取该玩家的飞行状态数据
            FlightData flightData = FlightData.getData(player);
            // isWingsSpread()：龙的翅膀是否展开（收起翅膀时不能飞）
            // hasFlight()：龙是否拥有飞行能力（某些龙类型可能没有）
            if (!flightData.isWingsSpread() || !flightData.hasFlight()) {
                return;
            }

            // ========== 第5步：读取玩家输入 ==========
            // player.input 包含了本帧所有键盘输入的状态
            Input movement = player.input;

            // ========== 第6步：判断"稳定悬停"条件 ==========
            // 只有以下全部条件满足时，才认为龙处于"稳定悬停"：
            boolean shouldHover = ServerFlightHandler.stableHover       // 龙之生存的悬停功能已启用
                    && !movement.jumping                                 // 没有按跳跃键（空格）
                    && !movement.shiftKeyDown                            // 没有按潜行键（Shift）
                    && !ServerFlightHandler.isSpin(player)               // 没有在旋转冲刺
                    && !ServerFlightHandler.isGliding(player);           // 没有在滑翔

            // ========== 第7步：判断是否无移动输入 ==========
            // forwardImpulse：前后移动（W/S 键），正值 = 前，负值 = 后，0 = 没按
            // leftImpulse：左右移动（A/D 键），正值 = 左，负值 = 右，0 = 没按
            // 两个都是 0 才表示"玩家没有任何方向键输入"
            boolean noMoveInput = movement.forwardImpulse == 0
                               && movement.leftImpulse == 0;

            // ========== 第8-9步：执行修复 ==========
            // 只有同时满足"处于悬停状态"且"无移动输入"才修正加速度
            if (shouldHover && noMoveInput) {
                // 将水平加速度归零，阻止水平方向的漂移
                ax = 0.0;
                az = 0.0;

                // 创造模式特殊处理：
                // 创造模式没有重力，龙会保持当前垂直速度一直上升
                // 所以除了归零 ay 之外，还要把垂直速度也清零
                if (player.isCreative()) {
                    ay = 0.0; // 垂直加速度归零
                    Vec3 delta = player.getDeltaMovement(); // 获取当前运动向量
                    // 保留水平的 x, z 分量，但把垂直的 y 分量设为 0
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
            }
        });
    }
}
