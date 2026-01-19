package dev.sixik.density_compiler.utils.stack;

import java.util.ArrayList;
import java.util.List;

public class TreeStackMachine implements StackMachine {

    private static class Node {
        final String name;
        final Node parent;
        final List<Node> children = new ArrayList<>();

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private final Node root = new Node("Root", null);
    private Node current = root;

    @Override
    public void pushStack(String name) {
        Node newNode = new Node(name, current);
        current.children.add(newNode);
        current = newNode; // Спускаемся глубже
    }

    @Override
    public String popStack() {
        if (current == root) throw new IllegalStateException("Stack is empty");
        String name = current.name;
        current = current.parent; // Поднимаемся выше, но дерево сохраняется
        return name;
    }

    @Override
    public void printDebug() {
        StringBuilder sb = new StringBuilder("\n--- Call Tree Start ---\n");
        for (Node child : root.children) {
            buildTreeString(sb, child, "", true);
        }
        sb.append("--- Call Tree End ---");
        System.out.println(sb);
    }

    private void buildTreeString(StringBuilder sb, Node node, String prefix, boolean isLast) {
        sb.append(prefix)
                .append(isLast ? "└── " : "├── ")
                .append(node.name)
                .append("\n");

        for (int i = 0; i < node.children.size(); i++) {
            buildTreeString(sb, node.children.get(i),
                    prefix + (isLast ? "    " : "│   "),
                    i == node.children.size() - 1);
        }
    }
}
