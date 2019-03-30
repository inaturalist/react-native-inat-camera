//
//  NATCameraView.m
//  NATInatCamera
//
//  Created by Alex Shepard on 3/15/19.
//  Copyright © 2019 California Academy of Sciences. All rights reserved.
//

@import AVFoundation;
@import Vision;
@import CoreML;

#import "NATCameraView.h"
#import "NATClassifier.h"

@interface NATCameraView () <AVCaptureVideoDataOutputSampleBufferDelegate, NATClassifierDelegate> {
    float _confidenceThreshold;
}
@property AVCaptureVideoPreviewLayer *previewLayer;
@property AVCaptureVideoDataOutput *videoDataOutput;
@property AVCaptureStillImageOutput *stillImageOutput;
@property AVCaptureSession *session;
@property dispatch_queue_t  videoDataOutputQueue;
@property CGSize bufferSize;
@property NSArray *requests;
@property NSDictionary *leafTaxa;
@property NATClassifier *classifier;
@property NSDate *lastPredictionTime;

@property NSString *modelFile;
@property NSString *taxonomyFile;
@end

@implementation NATCameraView

- (void)setConfidenceThreshold:(float)confidenceThreshold {
    _confidenceThreshold = confidenceThreshold;
    if (self.classifier) {
        [self.classifier setThreshold:_confidenceThreshold];
    }
}

- (float)confidenceThreshold {
    return _confidenceThreshold;
}


- (instancetype)initWithModelFile:(NSString *)modelFile taxonomyFile:(NSString *)taxonomyFile delegate:(id<NATCameraDelegate>)delegate {
    if (self = [super initWithFrame:CGRectZero]) {
        self.modelFile = modelFile;
        self.taxonomyFile = taxonomyFile;
        self.delegate = delegate;
    }
    
    return self;
}

- (void)setupClassifier {
    // classifier requires coreml, ios 11
    if (@available(iOS 11.0, *)) {
        self.classifier = [[NATClassifier alloc] initWithModelFile:self.modelFile
                                                       taxonmyFile:self.taxonomyFile];
        self.classifier.delegate = self;
        
        // default detection interval is 1000 ms
        self.taxaDetectionInterval = 1000;
        
        // start predicting right away
        self.lastPredictionTime = [NSDate distantPast];
    } else {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self.delegate cameraViewDeviceNotSupported:self];
        });
    }
}

- (void)setupAVCapture {
    // discover the camera
    NSArray *deviceTypes = @[AVCaptureDeviceTypeBuiltInWideAngleCamera];
    AVCaptureDeviceDiscoverySession *discovery = [AVCaptureDeviceDiscoverySession discoverySessionWithDeviceTypes:deviceTypes
                                                                                                        mediaType:AVMediaTypeVideo
                                                                                                         position:AVCaptureDevicePositionBack];
    if (!discovery || discovery.devices.count < 1) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self.delegate cameraView:self
                          cameraError:@"No Camera Found"];
        });
        return;
    }
    
    // setup a device input from the camera
    NSError *captureDeviceSetupError = nil;
    AVCaptureDeviceInput *input = [[AVCaptureDeviceInput alloc] initWithDevice:discovery.devices.firstObject
                                                                         error:&captureDeviceSetupError];
    if (captureDeviceSetupError) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            NSString *errorString = [NSString stringWithFormat:@"Camera Input Failed: %@",
                                     captureDeviceSetupError.localizedFailureReason];

            [self.delegate cameraView:self
                          cameraError:errorString];
        });
        return;
    }
    if (!input) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self.delegate cameraView:self
                          cameraError:@"Camera Input Failed"];
        });
        return;
    }
    
    // begin capture session configuration
    self.session = [[AVCaptureSession alloc] init];
    [self.session beginConfiguration];
    // Model image size is smaller
    self.session.sessionPreset = AVCaptureSessionPreset640x480;
    
    // add the video input
    if (![self.session canAddInput:input]) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self.delegate cameraView:self
                          cameraError:@"Camera Input Device Adding Failed"];
        });
        [self.session commitConfiguration];
        return;
    }
    [self.session addInput:input];
    
    // add and configure the still output for capture
    self.stillImageOutput = [[AVCaptureStillImageOutput alloc] init];
    if (![self.session canAddOutput:self.stillImageOutput]) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self.delegate cameraView:self
                          cameraError:@"Camera Still Output Device Adding Failed"];
        });
        [self.session commitConfiguration];
        return;
    }
    [self.session addOutput:self.stillImageOutput];
    self.stillImageOutput.outputSettings = @{
                                             AVVideoCodecKey : AVVideoCodecJPEG
                                             };
    [self.stillImageOutput setHighResolutionStillImageOutputEnabled:YES];
    
    // classifier requires coreml, ios 11
    if (@available(iOS 11.0, *)) {
        // add and configure the video output
        self.videoDataOutput = [[AVCaptureVideoDataOutput alloc] init];
        if (![self.session canAddOutput:self.videoDataOutput]) {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                [self.delegate cameraView:self
                              cameraError:@"Camera Video Output Device Adding Failed"];
            });
            [self.session commitConfiguration];
            return;
        }
        [self.session addOutput:self.videoDataOutput];
        self.videoDataOutput.alwaysDiscardsLateVideoFrames = YES;
        self.videoDataOutput.videoSettings = @{
                                               (NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
                                               };
        
        // attach the video output to a dispatch queue for processing
        dispatch_queue_attr_t qos = dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INITIATED, -1);
        self.videoDataOutputQueue = dispatch_queue_create("VideoDataOutput", qos);
        
        [self.videoDataOutput setSampleBufferDelegate:self
                                                queue:self.videoDataOutputQueue];
        
        AVCaptureConnection *captureConnection = [self.videoDataOutput connectionWithMediaType:AVMediaTypeVideo];
        [captureConnection setEnabled:YES];
        
        // extract the size of the video feed
        NSError *configurationError = nil;
        [discovery.devices.firstObject lockForConfiguration:&configurationError];
        if (configurationError) {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                NSString *errString = [NSString stringWithFormat:@"Lock Device for Config Failed: %@",
                                       configurationError.localizedFailureReason];

                [self.delegate cameraView:self
                              cameraError:errString];
            });

            return;
        }
        CMFormatDescriptionRef videoFormatDesc = [[discovery.devices.firstObject activeFormat] formatDescription];
        CMVideoDimensions videoDimensions = CMVideoFormatDescriptionGetDimensions(videoFormatDesc);
        self.bufferSize = CGSizeMake(videoDimensions.width, videoDimensions.height);
        [discovery.devices.firstObject unlockForConfiguration];
    }
    
    [self.session commitConfiguration];
    self.previewLayer = [[AVCaptureVideoPreviewLayer alloc] initWithSession:self.session];
    self.previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill;
    [self.layer addSublayer:self.previewLayer];
    [self.previewLayer setFrame:self.bounds];
    
    [self startCaptureSession];
}



- (void)layoutSubviews {
    self.previewLayer.frame = self.bounds;
}

- (void)startCaptureSession {
    [self.session startRunning];
}

- (void)stopCaptureSession {
    [self.session stopRunning];
}

- (void)teardownAVCapture {
    [self.previewLayer removeFromSuperlayer];
    self.previewLayer = nil;
}

- (AVCaptureVideoOrientation)activeVideoOrientation {
    switch ([[UIDevice currentDevice] orientation]) {
        case UIDeviceOrientationLandscapeLeft:
            return AVCaptureVideoOrientationLandscapeRight;
        case UIDeviceOrientationLandscapeRight:
            return AVCaptureVideoOrientationLandscapeLeft;
        case UIDeviceOrientationPortraitUpsideDown:
            return AVCaptureVideoOrientationPortraitUpsideDown;
        default:
            return AVCaptureVideoOrientationPortrait;
    }
}

- (void)takePictureWithResolver:(RCTPromiseResolveBlock)resolver rejecter:(RCTPromiseRejectBlock)reject {
    AVCaptureConnection *connection = [self.stillImageOutput connectionWithMediaType:AVMediaTypeVideo];

    //[connection setVideoOrientation:AVCaptureVideoOrientationLandscapeLeft];
    [connection setVideoOrientation:[self activeVideoOrientation]];
    
    [self.stillImageOutput captureStillImageAsynchronouslyFromConnection:connection completionHandler: ^(CMSampleBufferRef imageSampleBuffer, NSError *error) {
        
        if (error) {
            reject(@"capture_error", @"There was a capture error", error);
        } else {
            // pause capture
            [[self.previewLayer connection] setEnabled:NO];

            NSData *takenImageData = [AVCaptureStillImageOutput jpegStillImageNSDataRepresentation:imageSampleBuffer];
            UIImage *takenImage = [UIImage imageWithData:takenImageData];
            UIImage *fixedImage = [self fixedOrientation:takenImage];
            NSData *fixedImageData = UIImageJPEGRepresentation(fixedImage, 1.0f);
            
            NSArray *array = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES);
            NSString *cacheDirectory = [array firstObject];
            NSString *fileName = [NSString stringWithFormat:@"%@.jpg", [[NSUUID UUID] UUIDString]];
            NSString *imagePath = [cacheDirectory stringByAppendingPathComponent:fileName];
            NSError *writeError = nil;
            [fixedImageData writeToFile:imagePath options:NSDataWritingAtomic error:&writeError];
            if (writeError) {
                reject(@"write_error", @"There was an error saving photo", writeError);
            }
            
            NSString *imageUrlString = [[NSURL fileURLWithPath:imagePath] absoluteString];
            
            NSMutableDictionary *responseDict = [NSMutableDictionary dictionary];
            responseDict[@"uri"] = imageUrlString;
            if (self.classifier) {
                responseDict[@"predictions"] = [self.classifier latestBestBranch];
            }
            resolver([NSDictionary dictionaryWithDictionary:responseDict]);
        }
    }];
}

- (void)resumePreview {
    [[self.previewLayer connection] setEnabled:YES];
}

/* from https://gist.github.com/schickling/b5d86cb070130f80bb40 */
- (UIImage *)fixedOrientation:(UIImage *)image {
    
    if (image.imageOrientation == UIImageOrientationUp) {
        return image;
    }
    
    CGAffineTransform transform = CGAffineTransformIdentity;
    
    switch (image.imageOrientation) {
        case UIImageOrientationDown:
        case UIImageOrientationDownMirrored:
            transform = CGAffineTransformTranslate(transform, image.size.width, image.size.height);
            transform = CGAffineTransformRotate(transform, M_PI);
            break;
            
        case UIImageOrientationLeft:
        case UIImageOrientationLeftMirrored:
            transform = CGAffineTransformTranslate(transform, image.size.width, 0);
            transform = CGAffineTransformRotate(transform, M_PI_2);
            break;
            
        case UIImageOrientationRight:
        case UIImageOrientationRightMirrored:
            transform = CGAffineTransformTranslate(transform, 0, image.size.height);
            transform = CGAffineTransformRotate(transform, -M_PI_2);
            break;
            
        default: break;
    }
    
    switch (image.imageOrientation) {
        case UIImageOrientationUpMirrored:
        case UIImageOrientationDownMirrored:
            // CORRECTION: Need to assign to transform here
            transform = CGAffineTransformTranslate(transform, image.size.width, 0);
            transform = CGAffineTransformScale(transform, -1, 1);
            break;
            
        case UIImageOrientationLeftMirrored:
        case UIImageOrientationRightMirrored:
            // CORRECTION: Need to assign to transform here
            transform = CGAffineTransformTranslate(transform, image.size.height, 0);
            transform = CGAffineTransformScale(transform, -1, 1);
            break;
            
        default: break;
    }
    
    CGContextRef ctx = CGBitmapContextCreate(nil, image.size.width, image.size.height, CGImageGetBitsPerComponent(image.CGImage), 0, CGImageGetColorSpace(image.CGImage), kCGImageAlphaPremultipliedLast);
    
    CGContextConcatCTM(ctx, transform);
    
    switch (image.imageOrientation) {
        case UIImageOrientationLeft:
        case UIImageOrientationLeftMirrored:
        case UIImageOrientationRight:
        case UIImageOrientationRightMirrored:
            CGContextDrawImage(ctx, CGRectMake(0, 0, image.size.height, image.size.width), image.CGImage);
            break;
            
        default:
            CGContextDrawImage(ctx, CGRectMake(0, 0, image.size.width, image.size.height), image.CGImage);
            break;
    }
    
    CGImageRef cgImage = CGBitmapContextCreateImage(ctx);
    
    return [UIImage imageWithCGImage:cgImage];
}


- (CGImagePropertyOrientation)exifOrientationFromDeviceOrientation {
    UIDeviceOrientation deviceOrientation = UIDevice.currentDevice.orientation;
    
    switch (deviceOrientation) {
        case UIDeviceOrientationPortraitUpsideDown:
            // device oriented vertically, home button on top
            return kCGImagePropertyOrientationLeft;
            break;
        case UIDeviceOrientationLandscapeLeft:
            // device oriented horizontally, home button on the right
            return kCGImagePropertyOrientationUp;
            break;
        case UIDeviceOrientationLandscapeRight:
            // device oriented horizontally, home button on the left
            return kCGImagePropertyOrientationDown;
            break;
        case UIDeviceOrientationPortrait:
            // device oriented vertically, home button on bottom
            return kCGImagePropertyOrientationRight;
            break;
        default:
            return kCGImagePropertyOrientationRight;
            break;
    }
}

#pragma mark - AVCaptureVideoDataOutputSampleBufferDelegate

- (void)captureOutput:(AVCaptureOutput *)output didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer fromConnection:(AVCaptureConnection *)connection {
    
    // only process _n_ frames per second, where
    NSDate *now = [NSDate date];
    NSTimeInterval secsSinceLastPrediction = [now timeIntervalSinceDate:[self lastPredictionTime]];
    // taxaDetectionInteveral is specified in milliseconds
    if ((secsSinceLastPrediction * 1000) > self.taxaDetectionInterval) {
        self.lastPredictionTime = now;
        
        CVImageBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
        if (!pixelBuffer) {
            return;
        }
        
        if (self.classifier) {
            [self.classifier classifyFrame:pixelBuffer
                               orientation: [self exifOrientationFromDeviceOrientation]];
        }
    }
}

#pragma mark - NATClassifierDelegate

- (void)topClassificationResult:(NSDictionary *)topPrediction {
    [self.delegate cameraView:self taxaDetected:@[topPrediction]];
}

- (void)classifierError:(NSString *)errorString {
    // delay this to make sure the RCT event dispatch is setup
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0f * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [self.delegate cameraView:self onClassifierError:errorString];
    });
}

@end
