//
//  NATClassifier.m
//  RNTestLibrary
//
//  Created by Alex Shepard on 3/13/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

@import UIKit;
@import Vision;
@import CoreML;

#import "NATClassifier.h"
#import "NATTaxonomy.h"
#import "NATPrediction.h"

@interface NATClassifier ()
@property NSString *modelPath;
@property VNCoreMLModel *visionModel;
@property NATTaxonomy *taxonomy;
@property NSArray *requests;
@end

@implementation NATClassifier

- (instancetype)initWithModelFile:(NSString *)modelPath taxonmyFile:(NSString *)taxonomyPath {
    if (self = [super init]) {
        self.modelPath = modelPath;
        self.taxonomy = [[NATTaxonomy alloc] initWithTaxonomyFile:taxonomyPath];
        
        // default prediction threshold
        self.threshold = .80;
        
        [self setupVision];
    }
    
    return self;
}

- (NSArray *)latestBestBranch {
    NSMutableArray *array = [NSMutableArray array];
    for (NATPrediction *prediction in [self.taxonomy latestBestBranch]) {
        [array addObject:[prediction asDict]];
    }
    return [NSArray arrayWithArray:array];
}

- (void)setupVision {
    NSURL *modelUrl = [NSURL fileURLWithPath:self.modelPath];
    NSAssert(modelUrl, @"no url for optimized model");
    
    NSError *loadError = nil;
    MLModel *model = [MLModel modelWithContentsOfURL:modelUrl
                                               error:&loadError];
    NSAssert(loadError == nil, @"error loading model: %@", loadError.localizedDescription);
    NSAssert(model, @"unable to make model");
    
    NSError *modelError = nil;
    VNCoreMLModel *visionModel = [VNCoreMLModel modelForMLModel:model
                                                          error:&modelError];
    NSAssert(modelError == nil, @"error making vision model: %@", modelError.localizedDescription);
    NSAssert(visionModel, @"unable to make vision model");
    self.visionModel = visionModel;
    
    
    VNCoreMLRequest *objectRec = [[VNCoreMLRequest alloc] initWithModel:visionModel];
    
    
    VNRequestCompletionHandler handler = ^(VNRequest * _Nonnull request, NSError * _Nullable error) {
        VNCoreMLFeatureValueObservation *firstResult = request.results.firstObject;
        MLFeatureValue *firstFV = firstResult.featureValue;
        MLMultiArray *mm = firstFV.multiArrayValue;
        NATPrediction *topPrediction = [self.taxonomy inflateTopPredictionFromClassification:mm
                                                                         confidenceThreshold:self.threshold];
        
        NSLog(@"threshold is %f, score is %f", self.threshold, topPrediction.score);
        
        [self.delegate topClassificationResult:[topPrediction asDict]];
    };
    
    VNCoreMLRequest *objectRecognition = [[VNCoreMLRequest alloc] initWithModel:visionModel
                                                              completionHandler:handler];
    objectRecognition.imageCropAndScaleOption = VNImageCropAndScaleOptionCenterCrop;
    self.requests = @[objectRecognition];
}

- (void)classifyFrame:(CVImageBufferRef)pixelBuf orientation:(CGImagePropertyOrientation)exifOrientation {
    VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCVPixelBuffer:pixelBuf
                                                                              orientation:exifOrientation
                                                                                  options:@{}];
    NSError *requestError = nil;
    [handler performRequests:self.requests
                       error:&requestError];
    NSAssert(requestError == nil, @"got a request error: %@", requestError.localizedDescription);
}

@end
