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
import java.util.Arrays;  


/** Taxonomy data structure */
public class Taxonomy {
    private static final String TAG = "Taxonomy";

    List<Node> mNodes;
    Map<String, Node> mNodeByKey;
    Map<String, Node> mNodeByLeafId;
    Node mLifeNode;

    List<Node> mSpeciesNodes;
    List<Node> mGenusNodes;
    List<Node> mFamilyNodes;
    List<Node> mOrderNodes;
    List<Node> mClassNodes;
    List<Node> mPhylumNodes;
    List<Node> mKingdomNodes;

    Taxonomy(InputStream is) {

        mSpeciesNodes = new ArrayList<>();
        mGenusNodes = new ArrayList<>();
        mFamilyNodes = new ArrayList<>();
        mOrderNodes = new ArrayList<>();
        mClassNodes = new ArrayList<>();
        mPhylumNodes = new ArrayList<>();
        mKingdomNodes = new ArrayList<>();

        // Read the taxonomy CSV file into a list of nodes

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            reader.readLine(); // Skip the first line (header line)

            mNodes = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null; ) {
                Node node = new Node(line);
                mNodes.add(node);

                switch (node.rank) {
                    case 10:
                        mSpeciesNodes.add(node);
                        break;
                    case 20:
                        mGenusNodes.add(node);
                        break;
                    case 30:
                        mFamilyNodes.add(node);
                        break;
                    case 40:
                        mOrderNodes.add(node);
                        break;
                    case 50:
                        mClassNodes.add(node);
                        break;
                    case 60:
                        mPhylumNodes.add(node);
                        break;
                    case 70:
                        mKingdomNodes.add(node);
                        break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Convert list of nodes into a structure with parents and children
        mNodeByKey = new HashMap<>();
        mNodeByLeafId = new HashMap<>();
        
        mLifeNode = createLifeNode();

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
                mLifeNode.addChild(node);
            }
        }

    }

    private Node createLifeNode() {
        Node node = new Node();
        node.key = "48460";
        node.rank = 100;
        node.classId = "";
        node.name = "Life";
        node.parent = null;
        return node;
    }

    public int getModelSize() {
        return mNodeByLeafId.size();
    }

    public List<Prediction> predict(Map<Integer, Object> outputs) {
        // Get raw predictions
        float[] results = ((float[][]) outputs.get(0))[0];

        Map<String, Float> scores = aggregateScores(results);
        List<Prediction> bestBranch = buildBestBranchFromScores(scores);
        
        return bestBranch;
    }


    /** Aggregates scores for nodes, including non-leaf nodes (so each non-leaf node has a score of the sum of all its dependents) */
    private Map<String, Float> aggregateScores(float[] results) {
        List<Prediction> predictions = new ArrayList<>();
        Map<String, Float> scores = new HashMap<>();
        
        // Rank 10: no children
        for (Node node : mSpeciesNodes) {
            float score = results[Integer.valueOf(node.leafId)];
            scores.put(node.key, score);
        }


        // Nodes with children
        List<List<Node>> ranks = Arrays.asList(
            mGenusNodes, mFamilyNodes, mOrderNodes,
            mClassNodes, mPhylumNodes, mKingdomNodes
        );


        // Work from the bottom up
        for (List<Node> rankNodes : ranks) {
            for (Node node : rankNodes) {
                float aggregateScore = 0.0f;
                for (Node child : node.children) {
                    float childScore = scores.get(child.key);
                    aggregateScore += childScore;
                }
                scores.put(node.key, aggregateScore);
            }
        }


        return scores;
    }


    /** Finds the best branch from all result scores */
    private List<Prediction> buildBestBranchFromScores(Map<String, Float> scores) {
        List<Prediction> bestBranch = new ArrayList<>();

        // Start from life
        Node currentNode = mLifeNode;

        // Always life
        Prediction lifePrediction = new Prediction(currentNode, 1.0f);
        bestBranch.add(lifePrediction);


        while (currentNode.children.size() > 0) {
            // Find the best child of the current node
            Node bestChild = null;
            float bestScore = -1;
            for (Node child : currentNode.children) {
                float childScore = scores.get(child.key);
                if (childScore > bestScore) {
                    bestScore = childScore;
                    bestChild = child;
                }
            }

            // Add the prediction for this best child to the branch
            Prediction bestChildPrediction = new Prediction(bestChild, bestScore);
            bestBranch.add(bestChildPrediction);

            // Redo the loop, looking for the best sub-child among this best child
            currentNode = bestChild;
        }

        return bestBranch;
    }

}

