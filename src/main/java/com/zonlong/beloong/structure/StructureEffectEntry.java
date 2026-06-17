package com.zonlong.beloong.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

import java.util.Optional;

public record StructureEffectEntry(
        Holder<MobEffect> effect,
        int amplifier,
        int duration,
        boolean showParticles,
        Optional<ResourceLocation> advancement
) {
    public static final Codec<StructureEffectEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.comapFlatMap(
                    loc -> BuiltInRegistries.MOB_EFFECT.getOptional(loc)
                            .map(DataResult::success)
                            .orElseGet(() -> DataResult.error(() -> "Unknown mob effect: " + loc)),
                    BuiltInRegistries.MOB_EFFECT::getKey
            ).fieldOf("effect").forGetter(StructureEffectEntry::effect),
            Codec.INT.optionalFieldOf("amplifier", 0).forGetter(StructureEffectEntry::amplifier),
            Codec.INT.fieldOf("duration").forGetter(StructureEffectEntry::duration),
            Codec.BOOL.optionalFieldOf("show_particles", false).forGetter(StructureEffectEntry::showParticles),
            ResourceLocation.CODEC.optionalFieldOf("advancement").forGetter(StructureEffectEntry::advancement)
    ).apply(instance, StructureEffectEntry::new));
}
