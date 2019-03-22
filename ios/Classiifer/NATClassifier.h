//
//  NATClassifier.h
//  RNTestLibrary
//
//  Created by Alex Shepard on 3/13/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

@import Foundation;


@protocol NATClassifierDelegate <NSObject>
- (void)topClassificationResult:(NSDictionary *)topPrediction;
@end

typedef void(^BranchClassificationHandler)(NSArray *topBranch, NSError *error);

@interface NATClassifier : NSObject

- (instancetype)initWithModelFile:(NSString *)modelPath taxonmyFile:(NSString *)taxonomyPath;
- (void)classifyFrame:(CVImageBufferRef)pixelBuf orientation:(CGImagePropertyOrientation)orientation;
- (void)classifyImageData:(NSData *)data orientation:(CGImagePropertyOrientation)orientation handler:(BranchClassificationHandler)handler;

@property (assign) id <NATClassifierDelegate> delegate;
@property float threshold;

@end
