package org.inaturalist.native_camera.nativecameraandroid.classifier;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Taxonmy data structure */
public class Taxonomy {
    private final static int TASK_COUNT = 7;

    Collection<Node> mNodes;
    Map<String, Node> mNodeByKey;
    Node mRootNode;

    Taxonomy(InputStream is) {
        // Read the taxonomy JSON file into a list of nodes
        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.create();

        Type nodeCollectionType = new TypeToken<Collection<Node>>(){}.getType();
        mNodes = gson.fromJson(new JsonReader(new InputStreamReader(is)), nodeCollectionType);

        // Convert list of nodes into a structure with parents and children
        mNodeByKey = new HashMap<>();

        for (Node node: mNodes) {
            if ((node.parentKey != null) && (mNodeByKey.containsKey(node.parentKey))) {
                Node parent = mNodeByKey.get(node.parentKey);
                parent.addChild(node);
            } else {
                mRootNode = node;
            }

            mNodeByKey.put(node.key, node);
        }
    }

    public Collection<Prediction> predict(Map<Integer, Object> outputs) {
        // Get raw predictions
        Collection<Prediction> predictions = doPredictions(outputs);

        // Calculate total score and rank
        double rollingProbability = 1.0;

        int rank = 0;
        for (Prediction prediction : predictions) {
            rollingProbability *= prediction.probability;
            prediction.score = rollingProbability;
            prediction.rank = rank;
            rank++;
        }

        ArrayList<Prediction> sortedPredictions = new ArrayList<>(predictions);

        Collections.sort(sortedPredictions, new Comparator<Prediction>() {
            @Override
            public int compare(Prediction p1, Prediction p2) {
                return Integer.valueOf(p1.rank).compareTo(p2.rank);
            }
        });

        return sortedPredictions;
    }

    /** Performs the prediction process, according to a list of outputs from the TFLite model */
    private Collection<Prediction> doPredictions(Map<Integer, Object> outputs) {

        Collection<Prediction> predictions = new ArrayList<>();
        Node currentNode = mRootNode;

        for (int taskIndex = 0; taskIndex < TASK_COUNT; taskIndex++) {
            float[][] probabilities = (float[][]) outputs.get(taskIndex);

            Prediction prediction = currentNode.doPrediction(probabilities[0]);
            predictions.add(prediction);

            currentNode = prediction.node;
        }

        return predictions;
    }
}

