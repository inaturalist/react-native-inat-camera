//
//  NATCameraView.h
//  NATInatCamera
//
//  Created by Alex Shepard on 3/15/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

#import <UIKit/UIKit.h>
#import <React/RCTComponent.h>
#import <React/RCTBridgeModule.h>

@class NATCameraView;

@protocol NATCameraDelegate
- (void)cameraView:(NATCameraView *)cameraView taxaDetected:(NSArray *)taxa;
- (void)cameraView:(NATCameraView *)cameraView cameraError:(NSString *)errorString;
- (void)cameraView:(NATCameraView *)cameraView onClassifierError:(NSString *)errorString;
- (void)cameraViewDeviceNotSupported:(NATCameraView *)cameraView;
@end

@interface NATCameraView : UIView

@property (nonatomic, copy) RCTBubblingEventBlock onTaxaDetected;
@property (nonatomic, copy) RCTDirectEventBlock onCameraError;
@property (nonatomic, copy) RCTDirectEventBlock onCameraPermissionMissing;
@property (nonatomic, copy) RCTDirectEventBlock onClassifierError;
@property (nonatomic, copy) RCTDirectEventBlock onDeviceNotSupported;

@property (nonatomic, weak) id <NATCameraDelegate> delegate;
@property (nonatomic, assign) float confidenceThreshold;
@property (nonatomic, copy) NSString *taxonomyPath;
@property (nonatomic, copy) NSString *modelPath;

// Minimum delay between calls to the onTaxaDetected callback, in ms.
// So if taxaDetectionInterval is 1000, the callback will be called
// at most once per second.
@property (nonatomic, assign) NSInteger taxaDetectionInterval;

- (instancetype)initWithDelegate:(id <NATCameraDelegate>)delegate;

- (void)takePictureWithResolver:(RCTPromiseResolveBlock)resolver
                       rejecter:(RCTPromiseRejectBlock)reject;
- (void)resumePreview;

- (void)setupAVCapture;
- (void)setupClassifier;

@end
