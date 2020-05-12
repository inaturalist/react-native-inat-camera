package org.inaturalist.inatcamera.classifier;

public class Prediction {
    public Node node;
    public Double probability;
    public Integer rank;

    public Prediction(Node n, double p) {
        node = n;
        probability = p;
        rank = n.rank;
    }
}

