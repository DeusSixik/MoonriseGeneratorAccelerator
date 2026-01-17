package dev.sixik.asm.handlers;

import java.util.Map;

public interface AsmCtxCachedVariablesHandler extends AsmCtxHandler {

    Map<String, Integer> getCachedVariables();

    default void putCachedVariable(String key, int iVar) {
        getCachedVariables().put(key, iVar);
    }

    default int getCachedVariable(String key) {
        return getCachedVariables().getOrDefault(key, -1);
    }

    default boolean containsCachedVariable(String key) {
        return getCachedVariables().containsKey(key);
    }

    default int getOrCreateCachedVariable(String key, int iVar) {
        return getCachedVariables().computeIfAbsent(key, s -> iVar);
    }
}
