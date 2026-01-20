package dev.sixik.density_compiler.utils.stack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HtmlTreeStackMachine implements StackMachine {

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
        current = newNode;
    }

    @Override
    public String popStack() {
        if (current == root) return "root";
        String name = current.name;
        current = current.parent;
        return name;
    }

    public void exportToHtml(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>")
                .append("body { font-family: sans-serif; background: #1e1e1e; color: #d4d4d4; }")
                .append(".tree { list-style-type: none; } .tree ul { margin-left: 20px; list-style-type: none; border-left: 1px solid #444; }")
                .append("li { margin: 5px 0; cursor: pointer; } .node:hover { color: #569cd6; }")
                .append(".collapsed ul { display: none; } .toggle::before { content: '▼ '; font-size: 10px; }")
                .append(".collapsed > .toggle::before { content: '▶ '; }")
                .append("</style></head><body><h3>Call Tree Report</h3><ul class='tree'>");

        for (Node child : root.children) {
            buildHtml(sb, child);
        }

        sb.append("</ul><script>")
                .append("document.querySelectorAll('.toggle').forEach(el => el.onclick = (e) => { e.stopPropagation(); el.parentElement.classList.toggle('collapsed'); });")
                .append("</script></body></html>");

        Files.writeString(path, sb.toString());
    }

    private void buildHtml(StringBuilder sb, Node node) {
        boolean hasChildren = !node.children.isEmpty();
        sb.append("<li class='").append(hasChildren ? "parent" : "").append("'>");
        if (hasChildren) sb.append("<span class='toggle'></span>");
        sb.append("<span class='node'>").append(node.name.replace("<", "&lt;")).append("</span>");

        if (hasChildren) {
            sb.append("<ul>");
            for (Node child : node.children) buildHtml(sb, child);
            sb.append("</ul>");
        }
        sb.append("</li>");
    }

    @Override public void printDebug() {
        try {
            exportToHtml(Path.of("Crash.html"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
