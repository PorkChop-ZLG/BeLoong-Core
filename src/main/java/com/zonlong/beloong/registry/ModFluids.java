package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.fluid.BeloongWaterFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

public final class ModFluids {

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES, BeLoongCore.MODID);
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, BeLoongCore.MODID);

    public static final DeferredHolder<FluidType, FluidType> BELOONG_WATER_TYPE =
            FLUID_TYPES.register("beloong_water", ModFluids::createBeloongWaterType);

    public static final DeferredHolder<Fluid, BeloongWaterFluid.Source> BELOONG_WATER =
            FLUIDS.register("beloong_water", BeloongWaterFluid.Source::new);
    public static final DeferredHolder<Fluid, BeloongWaterFluid.Flowing> FLOWING_BELOONG_WATER =
            FLUIDS.register("flowing_beloong_water", BeloongWaterFluid.Flowing::new);

    private ModFluids() {}

    private static FluidType createBeloongWaterType() {
        return new FluidType(FluidType.Properties.create()
                .descriptionId("block.beloong.beloong_water")
                .fallDistanceModifier(0.0F)
                .canExtinguish(true)
                .canConvertToSource(true)
                .supportsBoating(true)
                .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY)
                .sound(SoundActions.FLUID_VAPORIZE, SoundEvents.FIRE_EXTINGUISH)
                .canHydrate(true)
                .addDripstoneDripping(
                        PointedDripstoneBlock.WATER_TRANSFER_PROBABILITY_PER_RANDOM_TICK,
                        ParticleTypes.DRIPPING_DRIPSTONE_WATER,
                        Blocks.WATER_CAULDRON,
                        SoundEvents.POINTED_DRIPSTONE_DRIP_WATER_INTO_CAULDRON)) {
            @Override
            public boolean canConvertToSource(FluidState state, LevelReader reader, BlockPos pos) {
                if (reader instanceof Level level) {
                    return level.getGameRules().getBoolean(GameRules.RULE_WATER_SOURCE_CONVERSION);
                }
                return super.canConvertToSource(state, reader, pos);
            }

            @Override
            public @Nullable PathType getBlockPathType(
                    FluidState state,
                    BlockGetter level,
                    BlockPos pos,
                    @Nullable Mob mob,
                    boolean canFluidLog) {
                return canFluidLog ? super.getBlockPathType(state, level, pos, mob, true) : null;
            }
        };
    }

    public static void register(IEventBus eventBus) {
        FLUID_TYPES.register(eventBus);
        FLUIDS.register(eventBus);
    }
}
