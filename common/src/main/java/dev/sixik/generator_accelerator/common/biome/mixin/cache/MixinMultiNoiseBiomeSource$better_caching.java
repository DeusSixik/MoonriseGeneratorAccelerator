package dev.sixik.generator_accelerator.common.biome.mixin.cache;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Function;

@Mixin(MultiNoiseBiomeSource.class)
public class MixinMultiNoiseBiomeSource$better_caching {

    @Mutable
    @Shadow
    @Final
    private Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters;

    @Unique private Climate.ParameterList<Holder<Biome>> ga$directParams;
    @Unique
    private Holder<MultiNoiseBiomeSourceParameterList> ga$presetParams;
    @Unique private boolean ga$isDirect;

    /**
     * @author Sixik
     * @reason Intercept codec serialization to use our custom getter
     */
    @Redirect(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/serialization/MapCodec;xmap(Ljava/util/function/Function;Ljava/util/function/Function;)Lcom/mojang/serialization/MapCodec;"
            )
    )
    private static MapCodec<MultiNoiseBiomeSource> ga$redirectCodecXmap(
            MapCodec<Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>>> mapEither,
            Function<Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>>, MultiNoiseBiomeSource> to,
            Function<MultiNoiseBiomeSource, Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>>> from
    ) {
        return mapEither.xmap(to, source -> ((MixinMultiNoiseBiomeSource$better_caching) (Object) source).ga$getEitherForCodec());
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void ga$init(Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> either, CallbackInfo ci) {
        either.ifLeft(left -> {
            this.ga$directParams = left;
            this.ga$isDirect = true;
        }).ifRight(right -> {
            this.ga$presetParams = right;
            this.ga$isDirect = false;
        });

        this.parameters = null;
    }

    /**
     * @author Sixik
     * @reason Redirect to better cache
     */
    @Overwrite
    private Climate.ParameterList<Holder<Biome>> parameters() {
        return (ga$isDirect ? ga$directParams : ga$presetParams.value().parameters());
    }

    /**
     * @author Sixik
     * @reason Zero-Allocation stable check (Bypass Either)
     */
    @Overwrite
    public boolean stable(ResourceKey<MultiNoiseBiomeSourceParameterList> resourceKey) {
        return !this.ga$isDirect && this.ga$presetParams != null && this.ga$presetParams.is(resourceKey);
    }

    @Unique
    public Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> ga$getEitherForCodec() {
        if (this.ga$isDirect) {
            return Either.left(this.ga$directParams);
        } else {
            return Either.right(this.ga$presetParams);
        }
    }
}
