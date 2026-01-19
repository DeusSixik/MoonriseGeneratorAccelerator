package dev.sixik.density_compiler.utils.stack;

public interface StackMachine {

    void pushStack(String name);

    String popStack();

    void printDebug();
}
