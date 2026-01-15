package dev.sixik.asm;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.EnumMap;
import java.util.Map;

public class VariablesManipulator {

    private int lastIndex = -1;
    private int lastOffset = -1;

    private final EnumMap<VariableType, IntSet> variablesIndexContainer =
            Maps.newEnumMap(VariableType.class);

    public final int registerReference(int index) {
        registerVariable(VariableType.REFERENCE, index);
        return index;
    }

    public final int registerInteger(int index) {
        registerVariable(VariableType.INT, index);
        return index;
    }

    public final int registerFloat(int index) {
        registerVariable(VariableType.FLOAT, index);
        return index;
    }

    public final int registerDouble(int index) {
        registerVariable(VariableType.DOUBLE, index);
        return index;
    }

    public final int registerLong(int index) {
        registerVariable(VariableType.LONG, index);
        return index;
    }

    public final int registerArray(int index) {
        registerVariable(VariableType.ARRAY, index);
        return index;
    }

    public final int registerIntArray(int index) {
        registerVariable(VariableType.ARRAY_I, index);
        return index;
    }

    public final int registerDoubleArray(int index) {
        registerVariable(VariableType.ARRAY_D, index);
        return index;
    }

    public final void hasReference(int index) {
        checkVariable(VariableType.REFERENCE, index);
    }

    public final void hasInt(int index) {
        checkVariable(VariableType.INT, index);
    }

    public final void hasFloat(int index) {
        checkVariable(VariableType.FLOAT, index);
    }

    public final void hasDouble(int index) {
        checkVariable(VariableType.DOUBLE, index);
    }

    public final void hasLong(int index) {
        checkVariable(VariableType.LONG, index);
    }

    public final void hasArray(int index) {
        checkVariable(VariableType.ARRAY, index);
    }

    public final void hasIntArray(int index) {
        checkVariable(VariableType.ARRAY_I, index);
    }

    public final void hasDoubleArray(int index) {
        checkVariable(VariableType.ARRAY_D, index);
    }

    public VariableType getVariableType(int index) {
        for (Map.Entry<VariableType, IntSet> entry : variablesIndexContainer.entrySet()) {
            if(entry.getValue().contains(index))
                return entry.getKey();
        }

        throw new RuntimeException("Can't find variable with id '" + index + "'");
    }

    protected void registerVariable(VariableType type, int index) {
        IntSet set = variablesIndexContainer.computeIfAbsent(type, s -> new IntArraySet());
        if (set.contains(index))
            throw new RuntimeException("Index '" + index + "' for variable with type '" + type.name() + "' already created!");

        if (lastIndex != -1 && lastIndex != index - lastOffset)
            throw new RuntimeException("Incorrect variable index '" + index + "' for type '" + type.name() + "' . Need offset '" + (type.offset) + "'");

        set.add(index);
        lastIndex = index;
        lastOffset = type.offset;
    }

    protected void checkVariable(VariableType type, int index) {
        if(!variablesIndexContainer.computeIfAbsent(type, s -> new IntArraySet()).contains(index))
            throw new RuntimeException("Variable with type '" + type.name() + "' for index '" + index + "' not found!");
    }

    public enum VariableType {
        REFERENCE(1),
        DOUBLE(2),
        LONG(2),
        INT(1),
        FLOAT(1),
        ARRAY(1),
        ARRAY_I(1),
        ARRAY_D(1);

        public final int offset;

        VariableType(int offset) {
            this.offset = offset;
        }
    }

    public static boolean canCast(VariableType from, VariableType to) {
        if (from == to) return true;

        if((from == VariableType.ARRAY_I || from == VariableType.ARRAY_D) && isPrimitive(to))
            return true;

        if (isPrimitive(from) != isPrimitive(to)) return false;


        if (isPrimitive(from)) {
            return true;
        }

        if (to == VariableType.REFERENCE) return true;
        if (to == VariableType.ARRAY) return isArray(from);

        if (to == VariableType.ARRAY_I) return isReferenceOrArray(from);
        if (to == VariableType.ARRAY_D) return isReferenceOrArray(from);

        return false;
    }

    public static boolean isPrimitive(VariableType t) {
        return t == VariableType.INT || t == VariableType.FLOAT
                || t == VariableType.LONG || t == VariableType.DOUBLE;
    }

    public static boolean isArray(VariableType t) {
        return t == VariableType.ARRAY || t == VariableType.ARRAY_I || t == VariableType.ARRAY_D;
    }

    public static boolean isReferenceOrArray(VariableType t) {
        return t == VariableType.REFERENCE || isArray(t);
    }
}
