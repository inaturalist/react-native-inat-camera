//
//  RCTINatCameraViewManager.m
//  RCTINatCameraView
//
//  Created by Alex Shepard on 3/15/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

#import "RCTINatCameraViewManager.h"
#import "NATCameraView.h"

@interface RCTINatCameraViewManager () <NATCameraDelegate>
@property (assign) NATCameraView *cameraView;
@end

@implementation RCTINatCameraViewManager

RCT_EXPORT_MODULE()
RCT_EXPORT_VIEW_PROPERTY(onTaxaDetected, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(confidenceThreshold, float)
RCT_EXPORT_VIEW_PROPERTY(taxaDetectionInterval, NSInteger)

RCT_REMAP_METHOD(takePictureAsync,
                 takePictureWithResolver:(RCTPromiseResolveBlock)resolve
                 rejecter:(RCTPromiseRejectBlock)reject)
{
    if (self.cameraView) {
        [self.cameraView takePictureWithResolver:resolve
                                        rejecter:reject];
    } else {
        NSError *error = [NSError errorWithDomain:@"org.inaturalist.react-native-inat-camera"
                                             code:404
                                         userInfo:nil];
        reject(@"no_camera", @"There was no camera", error);
    }
}

- (UIView *)view
{
    NSBundle *bundle = [NSBundle mainBundle];
    NSString *modelPath = [bundle pathForResource:@"optimized_model" ofType:@".mlmodelc"];
    NSString *taxonomyPath = [bundle pathForResource:@"taxonomy" ofType:@".json"];
    
    NATCameraView *camera = [[NATCameraView alloc] initWithModelFile:modelPath
                                                        taxonomyFile:taxonomyPath];
    self.cameraView = camera;

    camera.delegate = self;
    return camera;
}

#pragma mark NATCameraDelegate

- (void)cameraView:(NATCameraView *)cameraView taxaDetected:(NSArray *)taxa {
    if (!cameraView.onTaxaDetected) {
        return;
    }
    
    NSDictionary *prediction = [taxa firstObject];
    NSString *rank = [self rankNameForRankLevel:[[prediction valueForKey:@"rank"] integerValue]];
    
    cameraView.onTaxaDetected(@{
                                rank: taxa
                                });
}

- (NSString *)rankNameForRankLevel:(NSInteger)rankLevel {
    if (rankLevel == 10) {
        return @"species";
    } else if (rankLevel == 20) {
        return @"genus";
    } else if (rankLevel == 30) {
        return @"family";
    } else if (rankLevel == 40) {
        return @"order";
    } else if (rankLevel == 50) {
        return @"class";
    } else if (rankLevel == 60) {
        return @"phylum";
    } else if (rankLevel == 70) {
        return @"kingdom";
    } else if (rankLevel == 100) {
        return @"stateofmatter";
    } else {
        return @"";
    }
}

@end
