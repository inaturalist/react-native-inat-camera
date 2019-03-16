//
//  NATCameraManager.m
//  NATInatCamera
//
//  Created by Alex Shepard on 3/15/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

#import "NATCameraManager.h"
#import "NATCameraView.h"

@implementation NATCameraManager

RCT_EXPORT_MODULE()
RCT_EXPORT_VIEW_PROPERTY(onTaxaDetected, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(confidenceThreshold, float)

- (UIView *)view
{
    NSBundle *bundle = [NSBundle mainBundle];
    NSString *modelPath = [bundle pathForResource:@"optimized_model" ofType:@".mlmodelc"];
    NSString *taxonomyPath = [bundle pathForResource:@"taxonomy" ofType:@".json"];
    
    NATCameraView *camera = [[NATCameraView alloc] initWithModelFile:modelPath
                                                        taxonomyFile:taxonomyPath];
    
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
    } else {
        return @"";
    }
}

@end
