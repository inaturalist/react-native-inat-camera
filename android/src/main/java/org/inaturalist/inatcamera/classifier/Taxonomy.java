package org.inaturalist.inatcamera.classifier;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

        // Read the taxonomy CSV file into a list of nodes

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            reader.readLine(); // Skip the first line (header line)

            mNodes = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null; ) {
                mNodes.add(new Node(line));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

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
        return predictions.subList(0, predictions.size() > MAX_PREDICTIONS ? MAX_PREDICTIONS : predictions.size());
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
            Node node = mNodeByLeafId.get(String.valueOf(i));
            if (!node.key.matches("\\d+(?:\\.\\d+)?")) {
                // Key is not a valid number
                Log.w(TAG, String.format("Results from model file contains a node with an invalid key (non numeric): %s", node.key));
                continue;
            }
            
            Prediction prediction = new Prediction(node, results[i]);
            prediction.rank = prediction.node.rank;
            predictions.add(prediction);
        }

        return predictions;
    }
}

