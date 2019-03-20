//
//  NATNode.m
//  RNTestLibrary
//
//  Created by Alex Shepard on 3/13/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

@import Foundation;

@interface NATNode : NSObject

@property NSNumber *taxonId;
@property NSNumber *classId;
@property NSString *name;
@property NSNumber *rank;
@property NSNumber *leafId;
@property NSNumber *parentTaxonId;

@property NATNode *parent;
@property NSMutableArray <NATNode *> *children;

- (instancetype)initWithDictionary:(NSDictionary *)dict;
- (void)addChild:(NATNode *)child;
- (NSDictionary *)asDict;

+ (NATNode *)life;

@end
