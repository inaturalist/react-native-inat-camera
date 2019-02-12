package org.inaturalist.inatcamera.classifier;

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
import java.util.List;
import java.util.Map;

/** Taxonmy data structure */
public class Taxonomy {
    private static final String TAG = "Taxonomy";

    // Max number of predictions to return
    private static final int MAX_PREDICTIONS = 30;

    List<Node> mNodes;
    Map<String, Node> mNodeByKey;
    Map<String, Node> mNodeByLeafId;
    Node mRootNode;

    Taxonomy(InputStream is) {
        // Read the taxonomy JSON file into a list of nodes
        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.create();

        Type nodeCollectionType = new TypeToken<Collection<Node>>(){}.getType();
        mNodes = gson.fromJson(new JsonReader(new InputStreamReader(is)), nodeCollectionType);

        // Convert list of nodes into a structure with parents and children
        mNodeByKey = new HashMap<>();
        mNodeByLeafId = new HashMap<>();
        for (Node node: mNodes) {
            mNodeByKey.put(node.key, node);

            if ((node.leafId != null) && (node.leafId.length() > 0)) {
                mNodeByLeafId.put(node.leafId, node);
            }
        }

        for (Node node: mNodes) {
            if ((node.parentKey != null) && (node.parentKey.length() > 0) && (mNodeByKey.containsKey(node.parentKey))) {
                Node parent = mNodeByKey.get(node.parentKey);
                parent.addChild(node);
            } else {
                mRootNode = node;
            }
        }
    }

    public Collection<Prediction> predict(Map<Integer, Object> outputs) {
        // Get raw predictions
        List<Prediction> predictions = doPredictions(outputs);

        // Sort by probabilities
        Collections.sort(predictions, new Comparator<Prediction>() {
            @Override
            public int compare(Prediction p1, Prediction p2) {
                return p2.probability.compareTo(p1.probability);
            }
        });

        // Return only a sub-set of the results
        return predictions.subList(0, MAX_PREDICTIONS);
    }

    /** Performs the prediction process, according to a list of outputs from the TFLite model */
    private List<Prediction> doPredictions(Map<Integer, Object> outputs) {
        float[] results = ((float[][]) outputs.get(0))[0];

        List<Prediction> predictions = new ArrayList<>();

        for (int i = 0; i < results.length; i++) {
            if (!mNodeByLeafId.containsKey(String.valueOf(i))) {
                Log.w(TAG, String.format("Results from model file contains an invalid leaf ID: %d", i));
                continue;
            }
            Prediction prediction = new Prediction(mNodeByLeafId.get(String.valueOf(i)), results[i]);
            prediction.rank = prediction.node.rank;
            predictions.add(prediction);
        }

        return predictions;
    }
}

