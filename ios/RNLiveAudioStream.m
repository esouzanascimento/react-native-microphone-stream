#import "RNLiveAudioStream.h"
#import <AVFoundation/AVFoundation.h>

@interface RNLiveAudioStream () {
    // We keep the C-struct for Core Audio state
    AQRecordState _recordState;
    
    // We add Objective-C objects to manage the buffer queue safely
    NSLock *_bufferLock;
    NSMutableArray *_reusableOutputBuffers;
    float _gainFactor;
}
@end

@implementation RNLiveAudioStream

RCT_EXPORT_MODULE();

- (instancetype)init {
    self = [super init];
    if (self) {
        // Initialize the lock and the buffer pool
        _bufferLock = [[NSLock alloc] init];
        _reusableOutputBuffers = [[NSMutableArray alloc] init];

        _gainFactor = 5.0f; // or 1.0f for no initial gain

        // We still register for route changes
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(handleAudioRouteChange:)
                                                     name:AVAudioSessionRouteChangeNotification
                                                   object:nil];
    }
    return self;
}

RCT_EXPORT_METHOD(init:(NSDictionary *)options) {
    RCTLogInfo(@"[RNLiveAudioStream] init");
    _recordState.mDataFormat.mSampleRate = options[@"sampleRate"] == nil ? 44100 : [options[@"sampleRate"] doubleValue];
    _recordState.mDataFormat.mBitsPerChannel = options[@"bitsPerSample"] == nil ? 16 : [options[@"bitsPerSample"] unsignedIntValue];
    _recordState.mDataFormat.mChannelsPerFrame = options[@"channels"] == nil ? 1 : [options[@"channels"] unsignedIntValue];
    _recordState.mDataFormat.mBytesPerPacket = (_recordState.mDataFormat.mBitsPerChannel / 8) * _recordState.mDataFormat.mChannelsPerFrame;
    _recordState.mDataFormat.mBytesPerFrame = _recordState.mDataFormat.mBytesPerPacket;
    _recordState.mDataFormat.mFramesPerPacket = 1;
    _recordState.mDataFormat.mReserved = 0;
    _recordState.mDataFormat.mFormatID = kAudioFormatLinearPCM;
    _recordState.mDataFormat.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
    _recordState.bufferByteSize = options[@"bufferSize"] == nil ? 2048 : [options[@"bufferSize"] unsignedIntValue];
    _recordState.mSelf = (__bridge void *)self;
}

RCT_EXPORT_METHOD(start) {
    RCTLogInfo(@"[RNLiveAudioStream] start");
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    NSError *error = nil;
    BOOL success;

    if (@available(iOS 10.0, *)) {
        // Set the audio session category and options
        success = [audioSession setCategory:AVAudioSessionCategoryPlayAndRecord
                                       mode:AVAudioSessionModeDefault
                                    options:AVAudioSessionCategoryOptionDefaultToSpeaker |
                                             AVAudioSessionCategoryOptionAllowBluetoothA2DP
                                      error:&error];
    } else {
        success = [audioSession setCategory:AVAudioSessionCategoryPlayAndRecord withOptions:AVAudioSessionCategoryOptionDefaultToSpeaker error:&error];
        success = [audioSession setMode:AVAudioSessionModeDefault error:&error] && success;
    }
    if (!success || error != nil) {
        RCTLog(@"[RNLiveAudioStream] Problem setting up AVAudioSession category and mode. Error: %@", error);
        return;
    }

    NSTimeInterval preferredBufferDuration = 0.013; // Or calculate it: (double)_recordState.bufferByteSize / (double)_recordState.mDataFormat.mBytesPerFrame / _recordState.mDataFormat.mSampleRate;
    [audioSession setPreferredIOBufferDuration:preferredBufferDuration error:&error];
    if (error != nil) {
        RCTLog(@"[RNLiveAudioStream] Problem setting preferred buffer duration. Error: %@", error);
        // This is not a fatal error, so we can continue.
    }

    [audioSession setActive:YES error:&error];
    if (error) {
        RCTLog(@"[RNLiveAudioStream] Problem activating audio session. Error: %@", error);
        return;
    }

    _recordState.mIsRunning = true;

    OSStatus inputStatus = AudioQueueNewInput(&_recordState.mDataFormat, HandleInputBuffer, &_recordState, NULL, NULL, 0, &_recordState.mInputQueue);
    if (inputStatus != 0) {
        RCTLog(@"[RNLiveAudioStream] Record Failed. Cannot initialize AudioQueueNewInput. status: %i", (int)inputStatus);
        return;
    }

    OSStatus outputStatus = AudioQueueNewOutput(&_recordState.mDataFormat, HandleOutputBuffer, &_recordState, NULL, NULL, 0, &_recordState.mOutputQueue);
    if (outputStatus != 0) {
        RCTLog(@"[RNLiveAudioStream] Playback Failed. Cannot initialize AudioQueueNewOutput. status: %i", (int)outputStatus);
        return;
    }

    // Prepare the buffer pool before starting
    [_bufferLock lock];
    [_reusableOutputBuffers removeAllObjects];
    [_bufferLock unlock];

    for (int i = 0; i < kNumberBuffers; i++) {
        // Allocate INPUT buffers and enqueue them as before
        AudioQueueAllocateBuffer(_recordState.mInputQueue, _recordState.bufferByteSize, &_recordState.mInputBuffers[i]);
        AudioQueueEnqueueBuffer(_recordState.mInputQueue, _recordState.mInputBuffers[i], 0, NULL);

        // Allocate OUTPUT buffers
        OSStatus outputBufferStatus = AudioQueueAllocateBuffer(_recordState.mOutputQueue, _recordState.bufferByteSize, &_recordState.mOutputBuffers[i]);
        if (outputBufferStatus == 0) {
            // Add the newly allocated, ready-to-use output buffer to our reusable pool
            [_bufferLock lock];
            // We wrap the C pointer in an NSValue object to store it in the array
            [_reusableOutputBuffers addObject:[NSValue valueWithPointer:_recordState.mOutputBuffers[i]]];
            [_bufferLock unlock];
        } else {
             RCTLog(@"[RNLiveAudioStream] Output Buffer allocation failed. status: %i", (int)outputBufferStatus);
        }
    }
    
    // Set the mSelf pointer so the C functions can call back to our instance methods
    _recordState.mSelf = (__bridge void *)self;
    
    AudioQueueStart(_recordState.mInputQueue, NULL);
    AudioQueueStart(_recordState.mOutputQueue, NULL);
}

RCT_EXPORT_METHOD(stop) {
    RCTLogInfo(@"[RNLiveAudioStream] stop");
    if (_recordState.mIsRunning) {
        _recordState.mIsRunning = false;
        AudioQueueStop(_recordState.mInputQueue, true);
        AudioQueueStop(_recordState.mOutputQueue, true);
        for (int i = 0; i < kNumberBuffers; i++) {
            AudioQueueFreeBuffer(_recordState.mInputQueue, _recordState.mInputBuffers[i]);
            AudioQueueFreeBuffer(_recordState.mOutputQueue, _recordState.mOutputBuffers[i]);
        }
        AudioQueueDispose(_recordState.mInputQueue, true);
        AudioQueueDispose(_recordState.mOutputQueue, true);
    }
}

RCT_EXPORT_METHOD(isExternalAudioOutputConnected:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    NSArray *outputs = audioSession.currentRoute.outputs;
    BOOL isConnected = NO;

    for (AVAudioSessionPortDescription *output in outputs) {
        if (output.portType != AVAudioSessionPortBuiltInSpeaker &&
            output.portType != AVAudioSessionPortBuiltInReceiver) {
            isConnected = YES;
            break;
        }
    }

    resolve(@(isConnected));
}

void HandleInputBuffer(void *inUserData,
                       AudioQueueRef inAQ,
                       AudioQueueBufferRef inBuffer,
                       const AudioTimeStamp *inStartTime,
                       UInt32 inNumPackets,
                       const AudioStreamPacketDescription *inPacketDesc) {
    AQRecordState* pRecordState = (AQRecordState *)inUserData;

    if (!pRecordState->mIsRunning) {
        return;
    }

    // Get a reference to the Objective-C instance
    RNLiveAudioStream *streamer = (__bridge RNLiveAudioStream*)pRecordState->mSelf;
    if (!streamer) return;

    AudioQueueBufferRef outputBuffer = NULL;

    [streamer->_bufferLock lock];
    if (streamer->_reusableOutputBuffers.count > 0) {
        // Get the first available buffer from the pool
        NSValue *bufferValue = [streamer->_reusableOutputBuffers firstObject];
        outputBuffer = [bufferValue pointerValue];
        // Remove it from the pool so it's not used elsewhere
        [streamer->_reusableOutputBuffers removeObjectAtIndex:0];
    }
    [streamer->_bufferLock unlock];

    if (outputBuffer) {
        // Copy the recorded data into the output buffer
        // Get pointers to the input and output sample buffers (assuming 16-bit audio)
        int16_t *inputSamples = (int16_t *)inBuffer->mAudioData;
        int16_t *outputSamples = (int16_t *)outputBuffer->mAudioData;

        // Calculate the number of samples
        long numberOfSamples = inBuffer->mAudioDataByteSize / sizeof(int16_t);

        // Get the gain factor from our Objective-C instance
        float gain = streamer->_gainFactor;

        // Apply the gain to each sample
        for (int i = 0; i < numberOfSamples; i++) {
            // Multiply sample by gain factor
            float amplifiedSample = (float)inputSamples[i] * gain;

            // Clamp the value to the valid range for a 16-bit integer to prevent overflow
            if (amplifiedSample > INT16_MAX) {
                amplifiedSample = INT16_MAX;
            } else if (amplifiedSample < INT16_MIN) {
                amplifiedSample = INT16_MIN;
            }
            
            // Store the result in the output buffer
            outputSamples[i] = (int16_t)amplifiedSample;
        }

        outputBuffer->mAudioDataByteSize = inBuffer->mAudioDataByteSize;

        // Enqueue the buffer for playback
        OSStatus enqueueStatus = AudioQueueEnqueueBuffer(pRecordState->mOutputQueue, outputBuffer, 0, NULL);
        if (enqueueStatus != 0) {
            RCTLog(@"[RNLiveAudioStream] Output Buffer enqueue failed. status: %i", (int)enqueueStatus);
            // If enqueue fails, we must return the buffer to the pool to prevent it from being lost
            [streamer->_bufferLock lock];
            [streamer->_reusableOutputBuffers addObject:[NSValue valueWithPointer:outputBuffer]];
            [streamer->_bufferLock unlock];
        }
    } else {
        // This is a buffer underrun. It means the output queue is consuming buffers faster
        // than the input is providing them. This can happen if the CPU is too busy.
        // It can cause an audio glitch.
        RCTLog(@"[RNLiveAudioStream] No available output buffers. Dropping audio frame.");
    }

    // Re-enqueue the input buffer to continue recording
    AudioQueueEnqueueBuffer(pRecordState->mInputQueue, inBuffer, 0, NULL);
}

void HandleOutputBuffer(void *inUserData,
                        AudioQueueRef inAQ,
                        AudioQueueBufferRef inBuffer) {
    AQRecordState* pRecordState = (AQRecordState *)inUserData;
    
    // Get a reference to the Objective-C instance
    RNLiveAudioStream *streamer = (__bridge RNLiveAudioStream*)pRecordState->mSelf;
    if (!streamer) return;
    
    [streamer->_bufferLock lock];
    // Wrap the pointer and add it back to the end of the array for reuse
    [streamer->_reusableOutputBuffers addObject:[NSValue valueWithPointer:inBuffer]];
    [streamer->_bufferLock unlock];
}

- (void)handleAudioRouteChange:(NSNotification *)notification {
    NSDictionary *userInfo = notification.userInfo;
    AVAudioSessionRouteChangeReason reason = [userInfo[AVAudioSessionRouteChangeReasonKey] unsignedIntegerValue];

    if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
        [self sendEventWithName:@"onAudioRouteChange" body:@{@"status": @"unplugged"}];
    }
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"data", @"onAudioRouteChange"];
}

- (void)dealloc {
    RCTLogInfo(@"[RNLiveAudioStream] dealloc");
    AudioQueueDispose(_recordState.mInputQueue, true);
    AudioQueueDispose(_recordState.mOutputQueue, true);
}

@end
