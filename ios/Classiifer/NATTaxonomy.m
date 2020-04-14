//
//  NATTaxonomy.m
//  RNTestLibrary
//
//  Created by Alex Shepard on 3/13/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

#import "NATTaxonomy.h"
#import "NATNode.h"
#import "NATPrediction.h"

@interface NATTaxonomy ()
@property NSArray *nodes;
@property NSDictionary *nodesByTaxonId;
@property NSDictionary *nodesByLeafId;
@property NSDictionary *nodesByRank;
@property NSMutableArray *counted;
@property NSArray *sortedRanks;

@property NATNode *life;
@end

@implementation NATTaxonomy

- (instancetype)initWithTaxonomyFile:(NSString *)taxaFile {
    if (self = [super init]) {
        NSAssert(taxaFile, @"taxa file required");
        
        NSFileManager *fm = [NSFileManager defaultManager];
        NSAssert([fm fileExistsAtPath:taxaFile], @"taxa file %@ does exist.", taxaFile);
        NSAssert([fm isReadableFileAtPath:taxaFile], @"taxa file %@ not readable", taxaFile);
        
        NSError *readError = nil;
        NSData *taxonomyData = [NSData dataWithContentsOfFile:taxaFile
                                                      options:0
                                                        error:&readError];
        NSAssert(readError == nil, @"error reading from %@: %@", taxaFile, readError.localizedDescription);
        NSAssert(taxonomyData, @"failed to get data from %@", taxaFile);

        NSError *jsonError = nil;
        NSArray *taxa = [NSJSONSerialization JSONObjectWithData:taxonomyData
                                                        options:0
                                                          error:&jsonError];
        NSAssert(jsonError == nil, @"error getting json from %@: %@", taxaFile, jsonError.localizedDescription);
        NSAssert(taxa, @"failed to get json from %@", taxaFile);
        NSAssert(taxa.count > 0, @"failed to get list of json from %@", taxaFile);
        
        self.linneanPredictionsOnly = YES;
        
        self.life =  [[NATNode alloc] init];
        self.life.taxonId = @(48460);
        self.life.rank = @(100);
        self.life.name = @"Life";
        
        // extract nodes from taxa json object
        NSMutableArray *allNodes = [NSMutableArray arrayWithCapacity:taxa.count];
        for (NSDictionary *taxonDict in taxa) {
            NATNode *node = [[NATNode alloc] initWithDictionary:taxonDict];
            [allNodes addObject:node];
        }
        self.nodes = [NSArray arrayWithArray:allNodes];
        
        // make lookup helper dicts
        NSMutableDictionary *allNodesByTaxonId = [NSMutableDictionary dictionaryWithCapacity:taxa.count];
        NSMutableDictionary *allNodesByLeafId = [NSMutableDictionary dictionaryWithCapacity:taxa.count];
        NSMutableDictionary *allNodesByRank = [NSMutableDictionary dictionaryWithCapacity:taxa.count];
        for (NATNode *node in self.nodes) {
            allNodesByTaxonId[node.taxonId] = node;
            if (node.leafId) {
                allNodesByLeafId[node.leafId] = node;
            }
            if (allNodesByRank[node.rank]) {
                [allNodesByRank[node.rank] addObject:node];
            } else {
                allNodesByRank[node.rank] = [NSMutableArray arrayWithObject:node];
            }
        }
        self.nodesByTaxonId = [NSDictionary dictionaryWithDictionary:allNodesByTaxonId];
        self.nodesByLeafId = [NSDictionary dictionaryWithDictionary:allNodesByLeafId];
        NSMutableDictionary *mutableNodesByRank = [[NSDictionary dictionaryWithDictionary:allNodesByRank] mutableCopy];
        mutableNodesByRank[@(100)] = @[ self.life ];
        self.nodesByRank = [NSDictionary dictionaryWithDictionary:mutableNodesByRank];

        // helper sorted list of ranks in the taxonomy
        NSArray *ranks = self.nodesByRank.allKeys;
        self.sortedRanks = [ranks sortedArrayUsingComparator:^NSComparisonResult(NSNumber *rank1, NSNumber *rank2) {
            return [rank1 compare:rank2];
        }];
        
        // build parentage
        for (NATNode *node in self.nodes) {
            if (node.parentTaxonId) {
                NATNode *parent = self.nodesByTaxonId[node.parentTaxonId];
                if (parent) {
                    node.parent = parent;
                    [parent addChild:node];
                }
            } else {
                node.parent = self.life;
                [self.life addChild:node];
            }
        }
    }
    
    return self;
}

- (void)dealloc {    
    self.life = nil;
    
    self.nodes = nil;
    self.nodesByTaxonId = nil;
    self.nodesByLeafId = nil;
    self.nodesByRank = nil;
    self.sortedRanks = nil;
    self.counted = nil;
}

- (NSArray *)inflateTopBranchFromClassification:(MLMultiArray *)classification {
    NSDictionary *scores = [self aggregateScores:classification];
    return [self buildBestBranchFromScores:scores];
}

- (NATPrediction *)inflateTopPredictionFromClassification:(MLMultiArray *)classification confidenceThreshold:(float)threshold {
    NSDictionary *scores = [self aggregateScores:classification];
    NSArray *bestBranch = [self buildBestBranchFromScores:scores];
    
    for (NATPrediction *prediction in [bestBranch reverseObjectEnumerator]) {
        if (self.linneanPredictionsOnly) {
            // only KPCOFGS ranks qualify as "top" predictions
            // in the iNat taxonomy, KPCOFGS ranks are 70,60,50,40,30,20,10
            if (prediction.rank % 10 != 0) { continue; }
        }
        
        if (prediction.score > threshold) {
            return prediction;
        }
    }
    
    return nil;
}

- (NSDictionary *)aggregateScores:(MLMultiArray *)classification {
    NSMutableDictionary *scores = [NSMutableDictionary dictionary];
    
    // all leaf nodes need to be processed before we can aggregate scores
    // up the taxonomy tree
    
    // process leaf nodes first
    for (NATNode *node in self.nodes) {
        if (node.leafId) {
            // this is a leaf
            NSNumber *score = [classification objectAtIndexedSubscript:node.leafId.integerValue];
            scores[node.taxonId] = score;
        }
    }
    
    // now we can aggregate scores for non-leaf nodes
    // need to work from the bottom up
    for (NSNumber *rank in self.sortedRanks) {
        NSArray *rankNodes = [self.nodesByRank objectForKey:rank];
        for (NATNode *node in rankNodes) {
            if (!node.leafId) {
                // this is not a leaf node, so its score is the aggregate of all its children's scores
                float aggregateScore = 0.0f;
                for (NATNode *child in node.children) {
                    float childScore = [scores[child.taxonId] floatValue];
                    aggregateScore += childScore;
                }
                scores[node.taxonId] = @(aggregateScore);
            }
        }
    }
    
    return scores;
}

- (NSArray *)buildBestBranchFromScores:(NSDictionary *)allScoresDict {
    NSMutableArray *bestBranch = [NSMutableArray array];

    // start from life
    NATNode *currentNode = self.life;
    // always life
    NATPrediction *lifePrediction = [[NATPrediction alloc] initWithNode:currentNode score:1.0f];
    [bestBranch addObject:lifePrediction];
    
    while (currentNode.children.count > 0) {
        // find the best child of the current node
        NATNode *bestChild = nil;
        float bestScore = -1;
        for (NATNode *child in [currentNode children]) {
            float childScore = [allScoresDict[child.taxonId] floatValue];
            if (childScore > bestScore) {
                bestScore = childScore;
                bestChild = child;
            }
        }
        
        // add the prediction for this best child to the branch
        NATPrediction *bestChildPrediction = [[NATPrediction alloc] initWithNode:bestChild
                                                                           score:bestScore];
        [bestBranch addObject:bestChildPrediction];
        
        // redo the loop, looking for the best sub-child
        // among this best child
        currentNode = bestChild;
    }
    
    return [NSArray arrayWithArray:bestBranch];
}

@end
