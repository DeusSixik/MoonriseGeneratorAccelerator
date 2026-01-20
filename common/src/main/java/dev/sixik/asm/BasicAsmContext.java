package dev.sixik.asm;

import dev.sixik.asm.handlers.AsmCtxConditionsHandler;
import dev.sixik.asm.handlers.AsmCtxIterationsHandler;
import dev.sixik.asm.handlers.AsmCtxMethodsHandler;
import dev.sixik.asm.handlers.AsmCtxOperationsHandler;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.*;

public class BasicAsmContext implements
        AsmCtxConditionsHandler,
        AsmCtxIterationsHandler,
        AsmCtxMethodsHandler,
        AsmCtxOperationsHandler
{

    protected final Map<String, Integer> variableCache;
    protected final Deque<LoopLabels> loopStack;
    protected final GeneratorAdapter mv;
    protected final int variableIndexOffset;

    public BasicAsmContext(
            GeneratorAdapter mv,
            int variableIndexOffset
    ) {
        this.mv = mv;
        this.variableIndexOffset = variableIndexOffset;
        this.variableCache = new HashMap<>();
        this.loopStack  = new ArrayDeque<>();
    }

    @Override
    public GeneratorAdapter mv() {
        return mv;
    }

    @Override
    public BasicAsmContext ctx() {
        return this;
    }

    @Override
    public Deque<LoopLabels> getLoopStack() {
        return loopStack;
    }

    public int newDoubleVar() {
        return AsmFunctionsWrapper.newDoubleVar(mv);
    }

    public int newDoubleVar(double value) {
        return AsmFunctionsWrapper.newDoubleVar(mv, value);
    }

    public void putDoubleVar(int iVar) {
        AsmFunctionsWrapper.putDoubleVar(mv, iVar);
    }

    public void putDoubleVar(int iVar, double value) {
        AsmFunctionsWrapper.putDoubleVar(mv, iVar, value);
    }

    public void loadDoubleVar(int iVar) {
        AsmFunctionsWrapper.loadDoubleVar(mv, iVar);
    }

    ///////////////////////////////////////////////////////////////////

    public int newIntVar() {
        return AsmFunctionsWrapper.newIntVar(mv);
    }

    public int newIntVar(int value) {
        return AsmFunctionsWrapper.newIntVar(mv, value);
    }

    public void putIntVar(int iVar) {
        AsmFunctionsWrapper.putIntVar(mv, iVar);
    }

    public void putIntVar(int iVar, int value) {
        AsmFunctionsWrapper.putIntVar(mv, iVar, value);
    }

    public void loadIntVar(int ivar) {
        AsmFunctionsWrapper.loadIntVar(mv, ivar);
    }

    ///////////////////////////////////////////////////////////////////

    public int newFloatVar() {
        return AsmFunctionsWrapper.newFloatVar(mv);
    }

    public int newFloatVar(float value) {
        return AsmFunctionsWrapper.newFloatVar(mv, value);
    }

    public void putFloatVar(int iVar) {
        AsmFunctionsWrapper.putFloatVar(mv, iVar);
    }

    public void putFloatVar(int iVar, float value) {
        AsmFunctionsWrapper.putFloatVar(mv, iVar, value);
    }

    public void loadFloatVar(int ivar) {
        AsmFunctionsWrapper.loadFloatVar(mv, ivar);
    }

    ///////////////////////////////////////////////////////////////////

    public int newCachedVariable(String key, Type type) {
        if(variableCache.containsKey(key))
            throw new RuntimeException("Variable with id: " + key + " already created!");
        int variable = mv.newLocal(type);
        mv.storeLocal(variable);
        variableCache.put(key, variable);
        return variable;
    }

    public void readCachedVariable(String key) {
        if(variableCache.containsKey(key))
            throw new NoSuchElementException("Can't find variable with key: " + key);
        mv.loadLocal(variableCache.get(key));
    }

    public void putCachedVariable(String key, int iVar) {
        variableCache.put(key, iVar);
    }

    public int getCachedVariable(String key) {
        return variableCache.getOrDefault(key, -1);
    }

    ///////////////////////////////////////////////////////////////////

    public int newIntArrayVar(Runnable size) {
        return AsmFunctionsWrapper.newIntArrayVar(mv, size);
    }

    public int newIntArrayVar(int size) {
        return AsmFunctionsWrapper.newIntArrayVar(mv, size);
    }    
    
    public void loadIntArrayVar(int iVar) {
        AsmFunctionsWrapper.loadIntArrayVar(mv, iVar);
    }

    public void putIntArrayVar(int iVar) {
        AsmFunctionsWrapper.putIntArrayVar(mv, iVar);
    }

    public void setIntArrayValue(int arrayVarIndex, int index, int value) {
        AsmFunctionsWrapper.setIntArrayValue(mv, arrayVarIndex, index, value);
    }

    public void setIntArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index, Runnable value) {
        AsmFunctionsWrapper.setIntArrayValue(mv, arrayVarIndex, index, value);
    }

    public void getIntArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index) {
        AsmFunctionsWrapper.getIntArrayValue(mv, arrayVarIndex, index);
    }

    public void getIntArrayValue(GeneratorAdapter mv, int arrayVarIndex, int index) {
        AsmFunctionsWrapper.getIntArrayValue(mv, arrayVarIndex, index);
    }

    ///////////////////////////////////////////////////////////////////

    public int newFloatArrayVar(int size) {
        return AsmFunctionsWrapper.newFloatArrayVar(mv, size);
    }

    public int newFloatArrayVar(Runnable size) {
        return AsmFunctionsWrapper.newFloatArrayVar(mv, size);
    }

    public void loadFloatArrayVar(int iVar) {
        AsmFunctionsWrapper.loadFloatArrayVar(mv, iVar);
    }

    public void putFloatArrayVar(int iVar) {
        AsmFunctionsWrapper.putFloatArrayVar(mv, iVar);
    }

    public void setFloatArrayValue(int arrayVarIndex, int index, float value) {
        AsmFunctionsWrapper.setFloatArrayValue(mv, arrayVarIndex, index, value);
    }

    public void setFloatArrayValue(int arrayVarIndex, Runnable index, Runnable value) {
        AsmFunctionsWrapper.setFloatArrayValue(mv, arrayVarIndex, index, value);
    }

    public void getFloatArrayValue(int arrayVarIndex, int index) {
        AsmFunctionsWrapper.getFloatArrayValue(mv, arrayVarIndex, index);
    }

    public void getFloatArrayValue(int arrayVarIndex, Runnable index) {
        AsmFunctionsWrapper.getFloatArrayValue(mv, arrayVarIndex, index);
    }

    ///////////////////////////////////////////////////////////////////

    public void getArrayLength(int arrayVarIndex) {
        AsmFunctionsWrapper.getArrayLength(mv, arrayVarIndex);
    }

    public void iconst(int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, v);
        else mv.visitIntInsn(SIPUSH, v);
    }
}
