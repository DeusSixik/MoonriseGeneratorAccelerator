package dev.sixik.generator_accelerator.common.noise_native;

public interface RandomSeedGetter {

    long bts$getSeed();

    default long bts$getSeedLo() {
        return bts$getSeed();
    }

    default long bts$getSeedHi() {
        return bts$getSeed();
    }
}
