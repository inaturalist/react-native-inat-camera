package org.inaturalist.native_camera.nativecameraandroid.classifier;

import android.util.Log;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class Node {
    @SerializedName("k")
    public String key;

    @SerializedName("c")
    public int classId;

    @SerializedName("n")
    public String name;

    @SerializedName("p")
    public String parentKey;

    public transient Node parent;

    private transient List<Node> children = new ArrayList<>();

    public void addChild(Node child) {
        children.add(child);
        child.parent = this;
    }

    public Prediction doPrediction(float[] probabilities) {
        assert(children.size() > 0);

        if (children.size() == 1) {
            return new Prediction(children.get(0), 1.0);

        } else {

            Node currentSelectedChild = null;
            double selectedChildProbability = -1;

            // Find child with highest probability
            for (Node child : children) {
                double childProbability = probabilities[child.classId];
                if (childProbability > selectedChildProbability) {
                    currentSelectedChild = child;
                    selectedChildProbability = childProbability;
                }
            }

            assert(currentSelectedChild != null);

            // Return child node with highest probability
            return new Prediction(currentSelectedChild, selectedChildProbability);
        }
    }
}

