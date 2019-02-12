package org.inaturalist.inatcamera.classifier;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class Node {
    @SerializedName("k")
    public String key;

    @SerializedName("c")
    public String classId;

    @SerializedName("n")
    public String name;

    @SerializedName("p")
    public String parentKey;

    @SerializedName("r")
    public int rank;

    @SerializedName("l")
    public String leafId;

    public transient Node parent;

    private transient List<Node> children = new ArrayList<>();

    public void addChild(Node child) {
        children.add(child);
        child.parent = this;
    }

}

