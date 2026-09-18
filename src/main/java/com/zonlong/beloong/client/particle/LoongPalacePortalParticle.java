package com.zonlong.beloong.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.PortalParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 龙宫传送门的门粒子。
 * <p>
 * <b>与下界门粒子的唯一区别是配色</b>（贴图完全相同，都是原版 {@code minecraft:generic_0..7}）：
 * <pre>
 * 原版 {@code PortalParticle}： r = f*0.9, g = f*0.3, b = f          → 暖橙红
 * 本类（复刻天境）：            r = g = b = f，再 r *= 0.2, g *= 0.2 → 冷蓝青、整体更暗
 * </pre>
 * 其中 {@code f = random.nextFloat() * 0.6F + 0.4F}（0.4~1.0）。
 * <p>
 * 运动、生命周期、渲染类型（{@code PARTICLE_SHEET_OPAQUE}）与尺寸全部继承原版
 * {@link PortalParticle}，不自行实现——这样"飘移 + 拖尾"的手感与原版一致，
 * 差异只在颜色，正是"换色的门"应有的样子。
 * <p>
 * 参考：天境 {@code AetherPortalParticle}（1.21.1 分支，已获授权参考）。
 */
@OnlyIn(Dist.CLIENT)
public class LoongPalacePortalParticle extends PortalParticle {

    protected LoongPalacePortalParticle(ClientLevel level, double x, double y, double z,
                                        double xSpeed, double ySpeed, double zSpeed) {
        super(level, x, y, z, xSpeed, ySpeed, zSpeed);
        float brightness = this.random.nextFloat() * 0.6F + 0.4F;
        this.rCol = brightness;
        this.gCol = brightness;
        this.bCol = brightness;
        this.rCol *= 0.2F;
        this.gCol *= 0.2F;
    }

    /** 渲染工厂：由 {@code RegisterParticleProvidersEvent} 的 {@code registerSpriteSet} 绑定。 */
    public record Factory(SpriteSet spriteSet) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            LoongPalacePortalParticle particle =
                    new LoongPalacePortalParticle(level, x, y, z, xSpeed, ySpeed, zSpeed);
            particle.pickSprite(this.spriteSet);
            return particle;
        }
    }
}
