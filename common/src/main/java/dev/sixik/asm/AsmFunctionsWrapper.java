package dev.sixik.asm;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

public class AsmFunctionsWrapper {

    public static int newDoubleVar(GeneratorAdapter mv) {
        return newDoubleVar(mv, 0.0);
    }

    public static int newDoubleVar(GeneratorAdapter mv, double value) {
        int variable = mv.newLocal(Type.DOUBLE_TYPE);
        mv.push(value);
        mv.storeLocal(variable);
        return variable;
    }

    public static void putDoubleVar(GeneratorAdapter mv, int iVar) {
        mv.storeLocal(iVar);
    }

    public static void putDoubleVar(GeneratorAdapter mv, int iVar, double value) {
        mv.push(value);
        mv.storeLocal(iVar);
    }

    public static void loadDoubleVar(GeneratorAdapter mv, int iVar) {
        mv.loadLocal(iVar, Type.DOUBLE_TYPE);
    }

    ///////////////////////////////////////////////////////////////////

    public static int newIntVar(GeneratorAdapter mv) {
        return newIntVar(mv, 0);
    }

    public static int newIntVar(GeneratorAdapter mv, int value) {
        int variable = mv.newLocal(Type.INT_TYPE);
        mv.push(value);
        mv.storeLocal(variable);
        return variable;
    }

    public static void putIntVar(GeneratorAdapter mv, int iVar) {
        mv.storeLocal(iVar);
    }

    public static void putIntVar(GeneratorAdapter mv, int iVar, int value) {
        mv.push(value);
        mv.storeLocal(iVar);
    }

    public static void loadIntVar(GeneratorAdapter mv, int iVar) {
        mv.loadLocal(iVar, Type.INT_TYPE);
    }

    ///////////////////////////////////////////////////////////////////

    public static int newFloatVar(GeneratorAdapter mv) {
        return newFloatVar(mv, 0f);
    }

    public static int newFloatVar(GeneratorAdapter mv, float value) {
        int variable = mv.newLocal(Type.FLOAT_TYPE);
        mv.push(value);
        mv.storeLocal(variable);
        return variable;
    }

    public static void putFloatVar(GeneratorAdapter mv, int iVar) {
        mv.storeLocal(iVar);
    }

    public static void putFloatVar(GeneratorAdapter mv, int iVar, float value) {
        mv.push(value);
        mv.storeLocal(iVar);
    }

    public static void loadFloatVar(GeneratorAdapter mv, int iVar) {
        mv.loadLocal(iVar, Type.FLOAT_TYPE);
    }

    ///////////////////////////////////////////////////////////////////

    public static int newDoubleArrayVar(GeneratorAdapter mv, int size) {
        mv.push(size);
        mv.newArray(Type.DOUBLE_TYPE);
        int variable = mv.newLocal(Type.getType("[D"));
        mv.storeLocal(variable);
        return variable;
    }

    public static void loadDoubleArrayVar(GeneratorAdapter mv, int iVar) {
        mv.loadLocal(iVar);
    }

    public static void putDoubleArrayVar(GeneratorAdapter mv, int iVar) {
        mv.storeLocal(iVar);
    }

    public static void setDoubleArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index, Runnable setter) {
        mv.loadLocal(arrayVarIndex);
        index.run();
        setter.run();
        mv.arrayStore(Type.DOUBLE_TYPE);
    }

    public static void setDoubleArrayValue(GeneratorAdapter mv, int arrayVarIndex, int index, double value) {
        mv.loadLocal(arrayVarIndex);
        mv.push(index);
        mv.push(value);
        mv.arrayStore(Type.DOUBLE_TYPE);
    }

    public static void getDoubleArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index) {
        mv.loadLocal(arrayVarIndex);
        index.run();
        mv.arrayLoad(Type.DOUBLE_TYPE);
    }

    public static void getDoubleArrayValue(GeneratorAdapter mv, int arrayVarIndex, int index) {
        mv.loadLocal(arrayVarIndex);
        mv.push(index);
        mv.arrayLoad(Type.DOUBLE_TYPE);
    }

    ///////////////////////////////////////////////////////////////////

    public static int newIntArrayVar(GeneratorAdapter mv, Runnable size) {
        size.run();
        mv.newArray(Type.INT_TYPE);
        int variable = mv.newLocal(Type.getType("[I"));
        mv.storeLocal(variable);
        return variable;
    }

    public static int newIntArrayVar(GeneratorAdapter mv, int size) {
        mv.push(size);
        mv.newArray(Type.INT_TYPE);
        int variable = mv.newLocal(Type.getType("[I"));
        mv.storeLocal(variable);
        return variable;
    }

    public static void loadIntArrayVar(GeneratorAdapter mv, int iVar) {
        mv.loadLocal(iVar);
    }

    public static void putIntArrayVar(GeneratorAdapter mv, int iVar) {
        mv.storeLocal(iVar);
    }

    public static void setIntArrayValue(GeneratorAdapter mv, int arrayVarIndex, int index, int value) {
        mv.loadLocal(arrayVarIndex);
        mv.push(index);
        mv.push(value);
        mv.arrayStore(Type.INT_TYPE);
    }

    public static void setIntArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index, Runnable value) {
        mv.loadLocal(arrayVarIndex);
        index.run();
        value.run();
        mv.arrayStore(Type.INT_TYPE);
    }

    public static void getIntArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index) {
        mv.loadLocal(arrayVarIndex);
        index.run();
        mv.arrayLoad(Type.INT_TYPE);
    }

    public static void getIntArrayValue(GeneratorAdapter mv, int arrayVarIndex, int index) {
        mv.loadLocal(arrayVarIndex);
        mv.push(index);
        mv.arrayLoad(Type.INT_TYPE);
    }

    ///////////////////////////////////////////////////////////////////

    public static int newFloatArrayVar(GeneratorAdapter mv, int size) {
        mv.push(size);
        mv.newArray(Type.FLOAT_TYPE);
        int variable = mv.newLocal(Type.getType("[F"));
        mv.storeLocal(variable);
        return variable;
    }

    public static int newFloatArrayVar(GeneratorAdapter mv, Runnable size) {
        size.run();
        mv.newArray(Type.FLOAT_TYPE);
        int variable = mv.newLocal(Type.getType("[F"));
        mv.storeLocal(variable);
        return variable;
    }

    public static void loadFloatArrayVar(GeneratorAdapter mv, int iVar) {
        mv.loadLocal(iVar);
    }

    public static void putFloatArrayVar(GeneratorAdapter mv, int iVar) {
        mv.storeLocal(iVar);
    }

    public static void setFloatArrayValue(GeneratorAdapter mv, int arrayVarIndex, int index, float value) {
        mv.loadLocal(arrayVarIndex);
        mv.push(index);
        mv.push(value);
        mv.arrayStore(Type.FLOAT_TYPE);
    }

    public static void setFloatArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index, Runnable value) {
        mv.loadLocal(arrayVarIndex);
        index.run();
        value.run();
        mv.arrayStore(Type.FLOAT_TYPE);
    }

    public static void getFloatArrayValue(GeneratorAdapter mv, int arrayVarIndex, int index) {
        mv.loadLocal(arrayVarIndex);
        mv.push(index);
        mv.arrayLoad(Type.FLOAT_TYPE);
    }

    public static void getFloatArrayValue(GeneratorAdapter mv, int arrayVarIndex, Runnable index) {
        mv.loadLocal(arrayVarIndex);
        index.run();
        mv.arrayLoad(Type.FLOAT_TYPE);
    }

    ///////////////////////////////////////////////////////////////////

    public static void getArrayLength(GeneratorAdapter mv, int arrayVarIndex) {
        mv.loadLocal(arrayVarIndex);
        mv.arrayLength();
    }
}
