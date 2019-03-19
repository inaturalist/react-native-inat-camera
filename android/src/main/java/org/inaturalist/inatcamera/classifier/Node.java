package org.inaturalist.inatcamera.classifier;

import java.util.ArrayList;
import java.util.List;

public class Node {
    public String key;

    public String classId;

    public String name;

    public String parentKey;

    public int rank;

    public String leafId;

    public transient Node parent;

    public transient List<Node> children = new ArrayList<>();

    public String toString() {
        return String.format("%s: %s (p = %s)", key, name, parent != null ? parent.key : "N/A");
    }

    // Initialize the node from a CSV line
    public Node(String line) {
        String[] parts = line.trim().split(",", 6);

        this.parentKey = parts[0];
        this.key = parts[1];
        this.classId = parts[2];
        this.rank = Integer.parseInt(parts[3]);
        this.leafId = parts[4];
        this.name = parts[5];
    }

    public Node() {
    }

    public void addChild(Node child) {
        children.add(child);
        child.parent = this;
    }

}

