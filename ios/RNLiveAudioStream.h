#import <AVFoundation/AVFoundation.h>
#import <React/RCTEventEmitter.h>
#import <React/RCTLog.h>

#define kNumberBuffers 3

typedef struct {
    __unsafe_unretained id      mSelf;
    AudioStreamBasicDescription mDataFormat;
    AudioQueueRef               mInputQueue;
    AudioQueueRef               mOutputQueue;
    AudioQueueBufferRef         mInputBuffers[kNumberBuffers];
    AudioQueueBufferRef         mOutputBuffers[kNumberBuffers];
    UInt32                      bufferByteSize;
    SInt64                      mCurrentPacket;
    bool                        mIsRunning;
} AQRecordState;

@interface RNLiveAudioStream: RCTEventEmitter <RCTBridgeModule>
@property (nonatomic, assign) AQRecordState recordState;
- (void)handleAudioRouteChange:(NSNotification *)notification;
@end
