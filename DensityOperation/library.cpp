#include "library.h"

#include <cstring>

#include "jni.h"
#include "src/structs/noise/Noises.h"

extern "C" {

    JNIEXPORT jlong JNICALL Java_dev_sixik_density_1interpreter_DensityVM_createPerlinNoise(
    JNIEnv *env,
    jclass clazz,
    jint firstOctave,
    jdouble lowestFreqValueFactor,
    jdouble lowestFreqInputFactor,
    jdouble maxValue,
    jdoubleArray amplitudes,
    jdoubleArray noiseLevels_xo,
    jdoubleArray noiseLevels_yo,
    jdoubleArray noiseLevels_zo,
    jbyteArray noiseLevels_p
    ) {
        Density::PerlinNoise* noise = new Density::PerlinNoise();

        noise->firstOctave = firstOctave;
        noise->lowestFreqValueFactor = lowestFreqValueFactor;
        noise->lowestFreqInputFactor = lowestFreqInputFactor;
        noise->maxValue = maxValue;

        jsize ampsLen = env->GetArrayLength(amplitudes);
        jdouble* ampsData = env->GetDoubleArrayElements(amplitudes, nullptr);
        noise->amplitudes.assign(ampsData, ampsData + ampsLen);
        env->ReleaseDoubleArrayElements(amplitudes, ampsData, JNI_ABORT);

        jsize octavesCount = env->GetArrayLength(noiseLevels_xo);
        noise->noiseLevels.reserve(octavesCount);

        jdouble* xoData = env->GetDoubleArrayElements(noiseLevels_xo, nullptr);
        jdouble* yoData = env->GetDoubleArrayElements(noiseLevels_yo, nullptr);
        jdouble* zoData = env->GetDoubleArrayElements(noiseLevels_zo, nullptr);
        jbyte* pData    = env->GetByteArrayElements(noiseLevels_p, nullptr);

        for (int i = 0; i < octavesCount; ++i) {
            Density::NoiseOctaveData octave;

            octave.xo = xoData[i];
            octave.yo = yoData[i];
            octave.zo = zoData[i];

            // Копируем 256 байт для этой октавы
            // Смещение в большом массиве pData = i * 256
            std::memcpy(octave.p, &pData[i * 256], 256);

            noise->noiseLevels.push_back(octave);
        }

        env->ReleaseDoubleArrayElements(noiseLevels_xo, xoData, JNI_ABORT);
        env->ReleaseDoubleArrayElements(noiseLevels_yo, yoData, JNI_ABORT);
        env->ReleaseDoubleArrayElements(noiseLevels_zo, zoData, JNI_ABORT);
        env->ReleaseByteArrayElements(noiseLevels_p, pData, JNI_ABORT);

        return reinterpret_cast<jlong>(noise);
    }

    JNIEXPORT jdouble JNICALL Java_dev_sixik_density_1interpreter_DensityVM_noise(
        JNIEnv *env,
        jclass clazz,
        jlong noise_ptr,
        jdouble d,
        jdouble e,
        jdouble f,
        jdouble g,
        jdouble h
    ) {
        const auto data = reinterpret_cast<Density::ImprovedNoise*>(noise_ptr);
        return data->noise(d, e, f, g, h);
    }

    JNIEXPORT jlong JNICALL Java_dev_sixik_density_1interpreter_DensityVM_createNativeImprovedNoise(
        JNIEnv *env,
        jclass clazz,
        jdouble xo,
        jdouble yo,
        jdouble zo,
        jbyteArray p
    ) {
        // 1. Аллоцируем память в C++
        auto* noise_data = new Density::ImprovedNoise();
        noise_data->xo = xo;
        noise_data->yo = yo;
        noise_data->zo = zo;

        // 2. Копируем данные из Java-массива в наш ImprovedNoiseData
        jbyte* cP = env->GetByteArrayElements(p, nullptr);
        std::memcpy(noise_data->p, cP, 256);

        // 3. ОБЯЗАТЕЛЬНО освобождаем JNI-ресурс
        env->ReleaseByteArrayElements(p, cP, JNI_ABORT); // JNI_ABORT, т.к. мы не меняли массив

        return reinterpret_cast<jlong>(noise_data);
    }

    JNIEXPORT jlong JNICALL Java_dev_sixik_density_interpreter_DensityVM_createNoiseContext(
        JNIEnv* env,
        jintArray jProgram,
        jdoubleArray jConstants,
        jintArray jMeta,
        jdoubleArray jParams,
        jbyteArray jPerms,
        jint maxCacheId
    ) {
        return 0L;
    }
}
