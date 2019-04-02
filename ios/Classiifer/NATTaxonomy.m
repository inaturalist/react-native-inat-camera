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
@property NSMutableArray *counted;

@property NATNode *life;
@property NSMutableArray *speciesNodes;
@property NSMutableArray *genusNodes;
@property NSMutableArray *familyNodes;
@property NSMutableArray *orderNodes;
@property NSMutableArray *classNodes;
@property NSMutableArray *phylumNodes;
@property NSMutableArray *kingdomNodes;
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

        self.life =  [[NATNode alloc] init];
        self.life.taxonId = @(48460);
        self.life.rank = @(100);
        self.life.classId = @(0);
        self.life.name = @"Life";

        self.speciesNodes = [NSMutableArray array];
        self.genusNodes = [NSMutableArray array];
        self.familyNodes = [NSMutableArray array];
        self.orderNodes = [NSMutableArray array];
        self.classNodes = [NSMutableArray array];
        self.phylumNodes = [NSMutableArray array];
        self.kingdomNodes = [NSMutableArray array];

        NSMutableArray *allNodes = [NSMutableArray arrayWithCapacity:taxa.count];
        for (NSDictionary *taxonDict in taxa) {
            NATNode *node = [[NATNode alloc] initWithDictionary:taxonDict];
            if (node.rank.integerValue == 10) {
                [self.speciesNodes addObject:node];
            } else if (node.rank.integerValue == 20) {
                [self.genusNodes addObject:node];
            } else if (node.rank.integerValue == 30) {
                [self.familyNodes addObject:node];
            } else if (node.rank.integerValue == 40) {
                [self.orderNodes addObject:node];
            } else if (node.rank.integerValue == 50) {
                [self.classNodes addObject:node];
            } else if (node.rank.integerValue == 60) {
                [self.phylumNodes addObject:node];
            } else if (node.rank.integerValue == 70) {
                [self.kingdomNodes addObject:node];
            }
            
            [allNodes addObject:node];
        }
        self.nodes = [NSArray arrayWithArray:allNodes];
        
        NSMutableDictionary *allNodesByTaxonId = [NSMutableDictionary dictionaryWithCapacity:taxa.count];
        NSMutableDictionary *allNodesByLeafId = [NSMutableDictionary dictionaryWithCapacity:taxa.count];
        for (NATNode *node in self.nodes) {
            allNodesByTaxonId[node.taxonId] = node;
            if (node.leafId) {
                allNodesByLeafId[node.leafId] = node;
            }
        }
        self.nodesByTaxonId = [NSDictionary dictionaryWithDictionary:allNodesByTaxonId];
        self.nodesByLeafId = [NSDictionary dictionaryWithDictionary:allNodesByLeafId];
        
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
    self.counted = nil;
    
    self.speciesNodes = nil;
    self.genusNodes = nil;
    self.familyNodes = nil;
    self.orderNodes = nil;
    self.classNodes = nil;
    self.phylumNodes = nil;
    self.kingdomNodes = nil;
}

- (NSArray *)inflateTopBranchFromClassification:(MLMultiArray *)classification {
    NSDictionary *scores = [self aggregateScores:classification];
    return [self buildBestBranchFromScores:scores];
}

- (NATPrediction *)inflateTopPredictionFromClassification:(MLMultiArray *)classification confidenceThreshold:(float)threshold {
    NSDictionary *scores = [self aggregateScores:classification];
    NSArray *bestBranch = [self buildBestBranchFromScores:scores];
        
    for (NATPrediction *prediction in [bestBranch reverseObjectEnumerator]) {
        if (prediction.score > threshold) {
            return prediction;
        }
    }
    
    return nil;
}

- (NSDictionary *)aggregateScores:(MLMultiArray *)classification {
    NSMutableDictionary *scores = [NSMutableDictionary dictionary];
    
    // rank 10: no children
    for (NATNode *node in self.speciesNodes) {
        NSNumber *score = [classification objectAtIndexedSubscript:node.leafId.integerValue];
        scores[node.taxonId] = score;
    }
    
    // children with children
    NSArray *ranks = @[
                       self.genusNodes, self.familyNodes, self.orderNodes,
                       self.classNodes, self.phylumNodes, self.kingdomNodes,
                       ];
    
    // work from the bottom up
    for (NSArray *rankNodes in ranks) {
        for (NATNode *node in rankNodes) {
            if (node.leafId) {
                // this is a leaf node, take its score from the classification output
                NSNumber *score = [classification objectAtIndexedSubscript:node.leafId.integerValue];
                scores[node.taxonId] = score;
            } else {
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
