package com.zonlong.beloong.mixin.yuushya;

import com.yuushya.Yuushya;
import com.zonlong.beloong.BeLoongCore;
import dev.architectury.event.Event;
import dev.architectury.event.events.common.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 屏蔽《方块小镇》（Yuushya Townscape）**每次玩家进入存档**都会弹出的那条长"声明"消息。
 *
 * <p><b>要屏蔽的是什么</b>：该模组自 2.3.0 起，在 {@code com.yuushya.Yuushya.init()} 里注册了
 * 一个 {@code PlayerEvent.PLAYER_JOIN} 监听器（字节码：{@code init()} 偏移 45-53 处的
 * {@code getstatic PlayerEvent.PLAYER_JOIN} + {@code invokedynamic} 生成监听器 +
 * {@code invokeinterface Event.register(Object)V}）。监听器本体是编译器生成的
 * {@code lambda$init$4(ServerPlayer)}：拼 10 段 {@code declaration.*} 文案（免费/反盗版声明，
 * 含可点击的官网链接）后 {@code ServerPlayer.sendSystemMessage(...)} 发出去。
 * 没有配置开关、也没有"只发一次"的判断 ⇒ 每人每次进世界都会收到。
 *
 * <p><b>为什么在注册点拦截，而不是拦消息本体</b>：
 * <ul>
 *   <li>直接 {@code @Inject(HEAD, cancellable)} 到 {@code lambda$init$4} 需要写死<b>编译器生成的
 *       lambda 名</b>（名字里的序号会随上游任何代码改动而漂移）⇒ 上游一更新就静默失效；</li>
 *   <li>拦截 {@code Event.register} 的调用点只依赖两件稳定事实：方法名 {@code init()V} 与
 *       architectury 的接口方法 {@code Event.register(Object)V}，并用
 *       {@code listener instanceof PlayerEvent.PlayerJoin} 精确区分"要丢的那个监听器"。</li>
 * </ul>
 *
 * <p><b>同一方法里有两处 {@code Event.register}</b>（先是 {@code LifecycleEvent.SERVER_STARTED}，
 * 后是 {@code PlayerEvent.PLAYER_JOIN}）：Mixin 的 {@code @Redirect} 默认会包裹方法内
 * <b>所有</b>候选指令（其 {@code allow()} javadoc 原文："Injection points are in general expected
 * to match every candidate instruction in the target method"），因此两处都会被重定向，
 * <b>非 PlayerJoin 的监听器必须原样转交</b>（{@code event.register(listener)}），
 * 否则会把该模组的服务端启动逻辑（碰撞文件重载）一起干掉。
 * 这里刻意<b>不写</b> {@code allow = 2}：上限一旦被设死，上游将来多出一处 {@code register}
 * 就会从"无害的多包一层"变成启动期硬崩（{@code InjectionError}）。
 *
 * <p><b>可选依赖处理</b>：{@code @Pseudo} + {@code require = 0} —— 未安装《方块小镇》时本 Mixin
 * 整体跳过（不崩、无日志）；上游若改了注册方式导致注入点匹配不到，也只会静默失效
 * （表现为"声明又出现了"），不会影响游戏启动。这也是为什么加了一条
 * <b>进程内只打一次</b>的 INFO 锚点：启动日志里出现
 * {@code yuushya-declaration: dropped the PlayerJoin declaration listener at registration time}
 * 就说明拦截生效；反之说明没匹配上。
 *
 * <p><b>拦截时机（重要）</b>：不是"进世界的那一刻"，而是<b>更早</b> ——
 * <ol>
 *   <li>Mixin 在 {@code com.yuushya.Yuushya} <b>类加载</b>时就把 {@code init()} 里的
 *       {@code Event.register} 调用点改写成了本 handler；</li>
 *   <li>loader 入口（{@code com.yuushya.neoforge.YuushyaNeoForge}）在<b>模组构造阶段</b>调用
 *       {@code Yuushya.init()}，此时本 handler 顶替原调用并直接返回 ⇒
 *       <b>那个 PlayerJoin 监听器从未被注册进事件总线</b>；</li>
 *   <li>于是玩家之后无论进多少次世界，事件总线里根本没有它 —— 消息<b>既不构造也不发送</b>
 *       （不是"发出来再吞掉"）；本模组的锚点日志也因此在<b>游戏启动时</b>出现，而不是进世界时。</li>
 * </ol>
 * 也就是说：每局游戏只发生一次拦截（注册期），之后零开销、无 per-join 判断。
 *
 * <p>Mowzie / 首领崛起之外的第三个"可选依赖"目标；该模组依赖通过 {@code local-repo/}
 * 优先本地解析（体积约 27 MB，见 build.gradle）。
 */
@Pseudo
@Mixin(value = Yuushya.class, remap = false)
public abstract class YuushyaJoinDeclarationMixin {

    /** 锚点日志是否已打印（进程内一次）。 */
    @Unique
    private static boolean beloong$declarationSkipLogged = false;

    /**
     * 把 {@code Yuushya.init()} 里的 {@code Event.register(...)} 调用重定向到这里：
     * 命中"进存档声明"的监听器就丢弃，其余原样注册。
     *
     * @param event    事件对象（原调用点的方法接收者，类型为 architectury 的 {@code Event}）
     * @param listener 原调用点传入的监听器
     */
    @Redirect(
            method = "init()V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/architectury/event/Event;register(Ljava/lang/Object;)V"
            ),
            require = 0,
            remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void beloong$skipJoinDeclaration(Event event, Object listener) {
        if (listener instanceof PlayerEvent.PlayerJoin) {
            if (!beloong$declarationSkipLogged) {
                beloong$declarationSkipLogged = true;
                BeLoongCore.LOGGER.info(
                        "[BeLoong] yuushya-declaration: dropped the PlayerJoin declaration listener at registration time");
            }
            return;
        }

        // 其它注册（LifecycleEvent.SERVER_STARTED 等）必须原样转交
        event.register(listener);
    }
}
