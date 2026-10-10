package com.zonlong.beloong.mixin.healthbars;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Only enables the optional hook for the Health Bars bytecode checked by this patch. */
public final class HealthBarsMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthBarsMixinPlugin.class);
    private boolean compatible;

    @Override
    public void onLoad(String mixinPackage) {
        LoadingModList mods = LoadingModList.get();
        String version = mods == null ? "unavailable" : mods.getMods().stream()
                .filter(mod -> "healthbars".equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString()).findFirst().orElse("missing");
        compatible = "21.1.0".equals(version);
        if (compatible) {
            LOGGER.info("[BeLoong] Growth health-number compatibility enabled for Health Bars {}", version);
        } else if (!"missing".equals(version)) {
            LOGGER.warn("[BeLoong] Growth health-number compatibility disabled for unverified Health Bars {}", version);
        }
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return compatible; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
