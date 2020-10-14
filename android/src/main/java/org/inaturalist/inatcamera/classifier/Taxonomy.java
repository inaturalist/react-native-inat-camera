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
import java.util.Collections;
import com.google.gson.JsonObject;
import timber.log.*;

/** Taxonomy data structure */
public class Taxonomy {
    private static final String TAG = "Taxonomy";

    List<Node> mNodes;
    Map<String, Node> mNodeByKey;
    List<Node> mLeaves; // this is a convenience array for testing
    Node mLifeNode;
    Float mThreadshold; // Minimum threshold, below which scores will get reset to zero

    private Integer mFilterByTaxonId = null; // If null -> no filter by taxon ID defined
    private boolean mNegativeFilter = false;

    public void setFilterByTaxonId(Integer taxonId) {
        Timber.tag(TAG).d("setFilterByTaoxnId: " + taxonId);
        mFilterByTaxonId = taxonId;
    }

    public Integer getFilterByTaxonId() {
        return mFilterByTaxonId;
    }

    public void setNegativeFilter(boolean negative) {
        Timber.tag(TAG).d("setNegativeFilter: " + negative);
        mNegativeFilter = negative;
    }

    public boolean getNegativeFilter() {
        return mNegativeFilter;
    }

    public void setThreshold(Float threshold) {
        mThreadshold = threshold;
    }

    Taxonomy(InputStream is) {
        // Read the taxonomy CSV file into a list of nodes

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            reader.readLine(); // Skip the first line (header line)

            mNodes = new ArrayList<>();
            mLeaves = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null; ) {
                Node node = new Node(line);
                mNodes.add(node);
                if ((node.leafId != null) && (node.leafId.length() > 0)) {
                    mLeaves.add(node);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Convert list of nodes into a structure with parents and children
        mNodeByKey = new HashMap<>();

        mLifeNode = createLifeNode();

        for (Node node: mNodes) {
            mNodeByKey.put(node.key, node);
        }

        List<Node> lifeList = new ArrayList<Node>();
        lifeList.add(mLifeNode);

        for (Node node: mNodes) {
            if ((node.parentKey != null) && (node.parentKey.length() > 0)) {
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
        node.name = "Life";
        node.parent = null;
        return node;
    }

    public int getModelSize() {
        return mLeaves.size();
    }

    public List<Prediction> predict(Map<Integer, Object> outputs, List<JsonObject> frequencyResults) {
        // Get raw predictions
        float[] results = ((float[][]) outputs.get(0))[0];

        Map<String, Float> scores = aggregateScores(results);
        if (frequencyResults != null) {
            calculateFrequencyScores(scores, frequencyResults);
        }
        List<Prediction> bestBranch = buildBestBranchFromScores(scores);
        
        return bestBranch;
    }

    /** Adds frequency scores - based on: https://github.com/inaturalist/iNaturalistAPI/blob/main/lib/controllers/v1/computervision_controller.js#L242 */
    private void calculateFrequencyScores(Map<String, Float> scores, List<JsonObject> frequencyResults) {
        int sumScore = 0;
        Map<String, Float> frequencyMap = new HashMap<>();

        for (JsonObject frequencyResult : frequencyResults) {
            sumScore += frequencyResult.get("c").getAsInt();
            frequencyMap.put(frequencyResult.get("i").getAsString(), frequencyResult.get("c").getAsFloat());
        }

        for (String taxonId : scores.keySet()) {
            float score = scores.get(taxonId);

            if (frequencyMap.containsKey(taxonId)) {
                // Vision results with relevant frequency scores get a boost
                float frequencyScore = (frequencyMap.get(taxonId) / sumScore) * 20;

                if (score > 0) {
                    // Timber.tag(TAG).d(String.format("%s: Freq score: %f; prev: %f; count: %f / %d", taxonId, frequencyScore, score, frequencyMap.get(taxonId), sumScore));
                    score += frequencyScore;
                    scores.put(taxonId, score > 1f ? 1f : score);
                }
            } else {
                // Everything else uses the raw vision score - do nothing
            }
        }

        // Add any results not from vision
        for (String taxonId : frequencyMap.keySet()) {
            if (!scores.containsKey(taxonId)) {
                scores.put(taxonId, (frequencyMap.get(taxonId) / sumScore) * 0.75f);
            }
        }
    }


    /** Aggregates scores for nodes, including non-leaf nodes (so each non-leaf node has a score of the sum of all its dependents) */
    private Map<String, Float> aggregateScores(float[] results) {
        return aggregateScores(results, mLifeNode);
    }

    /** Following: https://github.com/inaturalist/inatVisionAPI/blob/multiclass/inferrers/multi_class_inferrer.py#L136 */
    private Map<String, Float> aggregateScores(float[] results, Node currentNode) {
        Map<String, Float> allScores = new HashMap<>();

        if (currentNode.children.size() > 0) {
            // we'll populate this and return it

            for (Node child : currentNode.children) {
                Map<String, Float> childScores = aggregateScores(results, child);
                allScores.putAll(childScores);
            }

            float thisScore = 0.0f;
            for (Node child : currentNode.children) {
                thisScore += allScores.get(child.key);
            }

            allScores.put(currentNode.key, thisScore);

        } else {
            // base case, no children
            boolean resetScore = false;

            if (mFilterByTaxonId != null) {
                // Filter

                // Reset current prediction score if:
                // A) Negative filter + prediction does contain taxon ID as ancestor
                // B) Non-negative filter + prediction does not contain taxon ID as ancestor
                boolean containsAncestor = hasAncestor(currentNode, mFilterByTaxonId.toString());
                resetScore = (containsAncestor && mNegativeFilter) || (!containsAncestor && !mNegativeFilter);
            }

            float score = results[Integer.valueOf(currentNode.leafId)];

            if ((mThreadshold != null) && (!resetScore)) {
                resetScore = score < mThreadshold;
            }

            allScores.put(currentNode.key, resetScore ? 0.0f : score);
        }

        return allScores;
    }

    /** Returns whether or not this taxon node has an ancestor with a specified taxon ID */
    private boolean hasAncestor(Node node, String taxonId) {
        if (node.key.equals(taxonId)) {
            return true;
        } else if (node.parent != null) {
            // Climb up the tree
            return hasAncestor(node.parent, taxonId);
        } else {
            // Reach to life node (root node) without finding that taxon ID
            return false;
        }
    }


    /** Finds the best branch from all result scores */
    private List<Prediction> buildBestBranchFromScores(Map<String, Float> scores) {
        List<Prediction> bestBranch = new ArrayList<>();

        // Start from life
        Node currentNode = mLifeNode;

        float lifeScore = scores.get(currentNode.key);
        Prediction lifePrediction = new Prediction(currentNode, lifeScore);
        bestBranch.add(lifePrediction);

        List<Node> currentNodeChildren = currentNode.children;

        // loop while the last current node (the previous best child node) has more children
        while (currentNodeChildren.size() > 0) {
            // find the best child of the current node
            Node bestChild = null;
            float bestChildScore = -1;
            for (Node child : currentNodeChildren) {
                float childScore = scores.get(child.key);
                if (childScore > bestChildScore) {
                    bestChildScore = childScore;
                    bestChild = child;
                }
            }

            if (bestChild != null) {
                Prediction bestChildPrediction = new Prediction(bestChild, bestChildScore);
                bestBranch.add(bestChildPrediction);
            }

            currentNode = bestChild;
            currentNodeChildren = currentNode.children;
        }

        return bestBranch;
    }

}

