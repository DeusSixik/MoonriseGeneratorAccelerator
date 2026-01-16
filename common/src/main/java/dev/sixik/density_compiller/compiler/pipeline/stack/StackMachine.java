package dev.sixik.density_compiller.compiler.pipeline.stack;

public interface StackMachine {

    void pushStack(String name);

    default void pushStack(Class<?> from, Class<?> to) {
        pushStack(from.getName() + " -> " + to.getName());
    }

    default void pushStack(Class<?> from, String message) {
        pushStack(from.getName() + " -> " + message);
    }

    default void pushStack(String message, Class<?> to) {
        pushStack(message + " -> " + to.getName());
    }

    String popStack();

    void printDebug();
}
