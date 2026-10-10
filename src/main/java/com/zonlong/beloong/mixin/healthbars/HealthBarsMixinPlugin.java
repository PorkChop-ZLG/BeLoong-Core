package com.zonlong.beloong.mixin.healthbars;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Enables the optional hook whenever Health Bars is installed, without a version gate. */
public final class HealthBarsMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthBarsMixinPlugin.class);
    private boolean enabled;

    @Override
    public void onLoad(String mixinPackage) {
        LoadingModList mods = LoadingModList.get();
        enabled = mods != null && mods.getMods().stream()
                .anyMatch(mod -> "healthbars".equals(mod.getModId()));
        if (enabled) {
            LOGGER.info("[BeLoong] Growth health-number compatibility enabled for Health Bars");
        } else if (mods == null) {
            LOGGER.warn("[BeLoong] Growth health-number compatibility disabled: mod metadata unavailable");
        }
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return enabled; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
