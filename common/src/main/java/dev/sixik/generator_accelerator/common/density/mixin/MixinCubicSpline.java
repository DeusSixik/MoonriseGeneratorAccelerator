package dev.sixik.generator_accelerator.common.density.mixin;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sixik.generator_accelerator.common.density.density.CompactSpline;
import dev.sixik.generator_accelerator.common.density.density.Point;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.ToFloatFunction;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.stream.IntStream;

@Mixin(CubicSpline.class)
public interface MixinCubicSpline<C, I extends ToFloatFunction<C>> {

//    /**
//     * @author Sixik
//     * @reason
//     */
//    @Overwrite
//    static <C, I extends ToFloatFunction<C>> Codec<CubicSpline<C, I>> codec(Codec<I> codec) {
//        MutableObject<Codec<CubicSpline<C, I>>> mutableObject = new MutableObject<>();
//        Codec<Point<C, I>> codec2 = RecordCodecBuilder.create(instance -> instance
//                .group(Codec.FLOAT.fieldOf("location")
//                        .forGetter(Point::location),
//                        Codec.lazyInitialized(mutableObject::getValue).fieldOf("value").forGetter(Point::value),
//                        (Codec.FLOAT.fieldOf("derivative"))
//                                .forGetter(Point::derivative))
//                .apply(instance, Point::new));
//
//
//        Codec<CompactSpline<C, I>> codec3 = RecordCodecBuilder.create(instance ->
//                instance.group(codec.fieldOf("coordinate").forGetter(CompactSpline::coordinate),
//                        (ExtraCodecs.nonEmptyList(codec2.listOf()).fieldOf("points"))
//                                .forGetter(multipoint -> IntStream.range(0, multipoint.locations().length).mapToObj(i ->
//                                        new Point<>(multipoint.locations()[i], multipoint.values()[i], multipoint.derivatives()[i])).toList())).apply(instance, (toFloatFunction, list) -> {
//            float[] fs = new float[list.size()];
//            ImmutableList.Builder<CubicSpline<C, I>> builder = ImmutableList.builder();
//            float[] gs = new float[list.size()];
//            for (int i = 0; i < list.size(); ++i) {
//                Point<C, I> lv = list.get(i);
//                fs[i] = lv.location();
//                builder.add(lv.value());
//                gs[i] = lv.derivative();
//            }
//            return CompactSpline.createCompact(toFloatFunction, fs, builder.build(), gs);
//        }));
//        mutableObject.setValue(Codec.either(Codec.FLOAT, codec3)
//                .xmap(either ->
//                (either.map(CubicSpline.Constant::new, multipoint -> multipoint)), cubicSpline -> {
//            Either<Float, CompactSpline<C, I>> either;
//            if (cubicSpline instanceof CubicSpline.Constant) {
//                CubicSpline.Constant<C, I> constant = (CubicSpline.Constant<C, I>) cubicSpline;
//                either = Either.left(constant.value());
//            } else {
//                either = Either.right((CompactSpline)cubicSpline);
//            }
//            return either;
//        }));
//        return mutableObject.getValue();
//    }

}
