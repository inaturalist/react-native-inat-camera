package org.inaturalist.inatcamera.classifier;

public class Prediction {
    public Node node;
    public Double probability;

    public Prediction(Node n, double p) {
        node = n;
        probability = p;
    }
}

