package dev.sixik.density_compiler.utils.stack;

import java.util.Deque;
import java.util.LinkedList;

public class SimpleStackMachine implements StackMachine {

    private Deque<String> list = new LinkedList<>();

    @Override
    public void pushStack(String name) {
        list.add(name);
    }

    @Override
    public String popStack() {
        return list.removeLast();
    }

    @Override
    public void printDebug() {
        System.out.println("---Stack Machine Start---");
        for (String string : list) {
            System.out.println(string);
        }
        System.out.println("---Stack Machine End---");
    }
}
