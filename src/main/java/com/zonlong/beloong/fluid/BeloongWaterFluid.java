package com.zonlong.beloong.fluid;

import com.zonlong.beloong.item.ModItems;
import com.zonlong.beloong.registry.ModBlocks;
import com.zonlong.beloong.registry.ModFluids;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import net.neoforged.neoforge.fluids.FluidType;

public abstract class BeloongWaterFluid extends WaterFluid {

    @Override
    public FluidType getFluidType() {
        return ModFluids.BELOONG_WATER_TYPE.get();
    }

    @Override
    public Fluid getFlowing() {
        return ModFluids.FLOWING_BELOONG_WATER.get();
    }

    @Override
    public Fluid getSource() {
        return ModFluids.BELOONG_WATER.get();
    }

    @Override
    public Item getBucket() {
        return ModItems.BELOONG_WATER_BUCKET.get();
    }

    @Override
    public BlockState createLegacyBlock(FluidState state) {
        return ModBlocks.BELOONG_WATER.get().defaultBlockState()
                .setValue(LiquidBlock.LEVEL, getLegacyLevel(state));
    }

    @Override
    public boolean isSame(Fluid fluid) {
        return fluid == ModFluids.BELOONG_WATER.get()
                || fluid == ModFluids.FLOWING_BELOONG_WATER.get();
    }

    public static final class Flowing extends BeloongWaterFluid {

        @Override
        protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getAmount(FluidState state) {
            return state.getValue(LEVEL);
        }

        @Override
        public boolean isSource(FluidState state) {
            return false;
        }
    }

    public static final class Source extends BeloongWaterFluid {

        @Override
        public int getAmount(FluidState state) {
            return 8;
        }

        @Override
        public boolean isSource(FluidState state) {
            return true;
        }
    }
}
