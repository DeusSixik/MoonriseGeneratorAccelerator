package dev.sixik.generator_accelerator.api.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public abstract class GAMixinPlugin implements IMixinConfigPlugin {

    public static List<MixinApplier> MixinAppliers = new ObjectArrayList<>();

    public void create(String modClass, MixinApplier.Param... params) {
        MixinAppliers.add(new MixinApplier(modClass, params));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {

        for (MixinApplier mixinApplier : MixinAppliers) {
            if(mixinApplier.hasMixin(mixinClassName) && !mixinApplier.isModLoaded())
                return false;
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }
}
