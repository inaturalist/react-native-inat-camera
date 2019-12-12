//
//  RCTINatCameraViewManager.m
//  RCTINatCameraView
//
//  Created by Alex Shepard on 3/15/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

#import <React/RCTBridge.h>
#import <React/RCTUIManager.h>

#import "RCTINatCameraViewManager.h"
#import "NATCameraView.h"

@interface RCTINatCameraViewManager () <NATCameraDelegate>
@property (assign) NATCameraView *cameraView;
@end

@implementation RCTINatCameraViewManager

RCT_EXPORT_MODULE()
RCT_EXPORT_VIEW_PROPERTY(onTaxaDetected, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onCameraError, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onCameraPermissionMissing, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onClassifierError, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onDeviceNotSupported, RCTDirectEventBlock)

RCT_EXPORT_VIEW_PROPERTY(confidenceThreshold, float)
RCT_EXPORT_VIEW_PROPERTY(taxaDetectionInterval, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(taxonomyPath, NSString)
RCT_EXPORT_VIEW_PROPERTY(modelPath, NSString)

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

RCT_REMAP_METHOD(resumePreview,
                 resumePreviewWithHandle:(NSInteger)handle)
{
    if (self.cameraView) {
        [self.cameraView resumePreview];
    }
}


- (UIView *)view {
    NATCameraView *cameraView = [[NATCameraView alloc] initWithDelegate:self];
    self.cameraView = cameraView;    
    
    return cameraView;
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

- (void)cameraView:(NATCameraView *)cameraView cameraError:(NSString *)errorString {
    if (!cameraView.onCameraError) {
        return;
    }
    
    cameraView.onCameraError(@{
                               @"error": errorString,
                               });
}

- (void)cameraViewDeviceNotSupported:(NATCameraView *)cameraView {
    if (!cameraView.onDeviceNotSupported) {
        return;
    }
    
    cameraView.onDeviceNotSupported(@{ });
}

- (void)cameraView:(NATCameraView *)cameraView onClassifierError:(NSString *)errorString {
    if (!cameraView.onClassifierError) {
        return;
    }
    
    cameraView.onClassifierError(@{
                                   @"error": errorString,
                                   });
}

#pragma mark Helper

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
