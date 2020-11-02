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
    Map<String, String> mTaxonMapping;

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

    Taxonomy(InputStream taxonomyStream) {
        this(taxonomyStream, null);
    }

    Taxonomy(InputStream taxonomyStream, InputStream mappingStream) {
        readTaxonomyFile(taxonomyStream);
        readMappingFile(mappingStream);
    }

    private void readMappingFile(InputStream is) {
        // Read mapping CSV file - matches between vision results taxon ID => new taxon ID (if swapping) or null (if removed).
        mTaxonMapping = new HashMap<>();

        if (is == null) return;

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            reader.readLine(); // Skip the first line (header line)

            for (String line; (line = reader.readLine()) != null; ) {
                String[] parts = line.trim().split(",");
                if (parts.length < 2) continue;

                mTaxonMapping.put(parts[0], parts[1].length() > 0 ? parts[1] : null);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void readTaxonomyFile(InputStream is) {
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

        // Calculate aggregates scores for all taxa
        Map<String, Float> visionScores = aggregateScores(results);
        // Find the common ancestor (highest scoring node that is still above the threshold)
        Node commonAncestor = findCommonAncestor(visionScores, mLifeNode);
        // Reset all other scores (that are not under the common ancestor branch
        resetNonCommonAncestor(visionScores, commonAncestor);

        Map<String, Float> frequencyScores = new HashMap<>();
        Map<String, Float> combinedScores = new HashMap<>();

        if (frequencyResults != null) {
            calculateFrequencyScores(visionScores, frequencyScores, frequencyResults, combinedScores);
        } else {
            // No frequency results - just use vision scores as-is
            combinedScores = visionScores;
        }

        List<Prediction> bestBranch = buildBestBranchFromScores(combinedScores);

        // Add original vision and frequency data for each prediction
        for (Prediction prediction : bestBranch) {
            Node node = prediction.node;
            String taxonId = node.key;

            if (frequencyScores.containsKey(taxonId)) {
                prediction.frequencyResult = frequencyScores.get(taxonId);
            }
            if (visionScores.containsKey(taxonId)) {
                prediction.visionResult = visionScores.get(taxonId);
            }
        }
        
        return bestBranch;
    }

    /** Adds frequency scores - based on: https://github.com/inaturalist/iNaturalistAPI/blob/main/lib/controllers/v1/computervision_controller.js#L242 */
    private void calculateFrequencyScores(Map<String, Float> visionScores, Map<String, Float> frequencyScores, List<JsonObject> frequencyResults, Map<String, Float> combinedScores) {
        int sumScore = 0;
        Map<String, Float> frequencyMap = new HashMap<>();

        for (JsonObject frequencyResult : frequencyResults) {
            sumScore += frequencyResult.get("c").getAsInt();
            frequencyMap.put(frequencyResult.get("i").getAsString(), frequencyResult.get("c").getAsFloat());
        }

        for (String taxonId : visionScores.keySet()) {
            float score = visionScores.get(taxonId);

            if (frequencyMap.containsKey(taxonId)) {
                // Vision results with relevant frequency scores get a boost
                float frequencyScore = (frequencyMap.get(taxonId) / sumScore);
                frequencyScores.put(taxonId, frequencyScore); // Save original frequency score

                if (score > 0) {
                    // Timber.tag(TAG).d(String.format("%s: Freq score: %f; prev: %f; count: %f / %d", taxonId, frequencyScore, score, frequencyMap.get(taxonId), sumScore));
                    score += frequencyScore * 20;
                    combinedScores.put(taxonId, score > 1f ? 1f : score);
                } else {
                    combinedScores.put(taxonId, score);
                }
            } else {
                // Everything else uses the raw vision score
                combinedScores.put(taxonId, score);
            }
        }

        // Add any results not from vision
        for (String taxonId : frequencyMap.keySet()) {
            if (!visionScores.containsKey(taxonId)) {
                combinedScores.put(taxonId, (frequencyMap.get(taxonId) / sumScore) * 0.75f);
            }
        }
    }

    /** Reset all other scores (that are not under the common ancestor branch */
    private void resetNonCommonAncestor(Map<String, Float> scores, Node currentNode) {
        Node parent = currentNode.parent;

        if (parent == null) return;

        if (parent.children != null) {
            // Reset the siblings of the current node, and the children of each sibling
            for (Node child : parent.children) {
                // Don't reset current node
                if (child == currentNode) continue;

                // Reset this sibling and all children (direct and indirect) of the current sibling
                resetAllChildren(scores, child);
            }
        }

        if (parent == mLifeNode) {
            // Reach top of the life tree - we're done
            return;
        }

        // Go on up
        resetNonCommonAncestor(scores, parent);
    }

    /** Resets the score for the current node and all of its direct/indirect children */
    private void resetAllChildren(Map<String, Float> scores, Node currentNode) {
        scores.put(currentNode.key, 0f);

        for (Node child : currentNode.children) {
            scores.put(child.key, 0f);
            resetAllChildren(scores, child);
        }
    }

    /** Find the common ancestor - at each branch level, choose the highest-scoring branch -
     * continue down the tree every time, until reaching a branch that is below the threshold */
    private Node findCommonAncestor(Map<String, Float> scores, Node currentNode) {
        Node maxChild = null;
        Float maxScore = 0f;

        // Find direct child that has the highest score (and still above the threshold)
        for (Node child : currentNode.children) {
            Float score = scores.get(child.key);
            if ((score >= mThreadshold) && (score > maxScore)) {
                maxScore = score;
                maxChild = child;
            }
        }

        if (maxChild == null) {
            // None of the children is above the thresholds - return current node
            return currentNode;
        }

        // Find the lowest class child that is above the threshold
        return findCommonAncestor(scores, maxChild);
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
                Float score = allScores.get(child.key);
                if (score != null) thisScore += score;
            }

            allScores.put(currentNode.key, thisScore);

        } else {
            // base case, no children
            boolean resetScore = false;
            float score = results[Integer.valueOf(currentNode.leafId)];
            String newTaxonId = mTaxonMapping.containsKey(currentNode.key) ?
                    // Taxon swap
                    mTaxonMapping.get(currentNode.key) :
                    // No swap made
                    currentNode.key;

            if (newTaxonId == null) {
                // Taxon was dropped (inactive) - reset score
                resetScore = true;

            } else if (mFilterByTaxonId != null) {
                // Filter

                // Reset current prediction score if:
                // A) Negative filter + prediction does contain taxon ID as ancestor
                // B) Non-negative filter + prediction does not contain taxon ID as ancestor
                boolean containsAncestor = hasAncestor(currentNode, mFilterByTaxonId.toString());
                resetScore = (containsAncestor && mNegativeFilter) || (!containsAncestor && !mNegativeFilter);
            }

            allScores.put(newTaxonId != null ? newTaxonId : currentNode.key, resetScore ? 0.0f : score);
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

