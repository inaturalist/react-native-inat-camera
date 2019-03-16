//
//  NATCameraView.h
//  NATInatCamera
//
//  Created by Alex Shepard on 3/15/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

#import <UIKit/UIKit.h>
#import <React/RCTComponent.h>

@class NATCameraView;

@protocol NATCameraDelegate
- (void)cameraView:(NATCameraView *)cameraView taxaDetected:(NSArray *)taxa;
@end

@interface NATCameraView : UIView

@property (nonatomic, copy) RCTBubblingEventBlock onTaxaDetected;
@property (nonatomic, assign) id <NATCameraDelegate> delegate;
@property (nonatomic, assign) float confidenceThreshold;

- (instancetype)initWithModelFile:(NSString *)modelFile taxonomyFile:(NSString *)taxonomyFile;

@end
