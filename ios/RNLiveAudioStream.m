#import "RNLiveAudioStream.h"
#import <AVFoundation/AVFoundation.h>

@implementation RNLiveAudioStream

RCT_EXPORT_MODULE();

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
    _recordState.mSelf = self;
}

RCT_EXPORT_METHOD(start) {
    RCTLogInfo(@"[RNLiveAudioStream] start");
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    NSError *error = nil;
    BOOL success;

    if (@available(iOS 10.0, *)) {
        success = [audioSession setCategory:AVAudioSessionCategoryPlayAndRecord
                                       mode:AVAudioSessionModeDefault
                                    options:AVAudioSessionCategoryOptionDefaultToSpeaker |
                                             AVAudioSessionCategoryOptionAllowBluetooth |
                                             AVAudioSessionCategoryOptionAllowBluetoothA2DP |
                                             AVAudioSessionCategoryOptionAllowAirPlay
                                      error:&error];
    } else {
        success = [audioSession setCategory:AVAudioSessionCategoryPlayAndRecord withOptions:AVAudioSessionCategoryOptionDefaultToSpeaker error:&error];
        success = [audioSession setMode:AVAudioSessionModeDefault error:&error] && success;
    }
    if (!success || error != nil) {
        RCTLog(@"[RNLiveAudioStream] Problem setting up AVAudioSession category and mode. Error: %@", error);
        return;
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

    for (int i = 0; i < kNumberBuffers; i++) {
        OSStatus inputBufferStatus = AudioQueueAllocateBuffer(_recordState.mInputQueue, _recordState.bufferByteSize, &_recordState.mInputBuffers[i]);
        if (inputBufferStatus != 0) {
            RCTLog(@"[RNLiveAudioStream] Input Buffer allocation failed. status: %i", (int)inputBufferStatus);
        }
        OSStatus enqueueInputStatus = AudioQueueEnqueueBuffer(_recordState.mInputQueue, _recordState.mInputBuffers[i], 0, NULL);
        if (enqueueInputStatus != 0) {
            RCTLog(@"[RNLiveAudioStream] Input Buffer enqueue failed. status: %i", (int)enqueueInputStatus);
        }

        OSStatus outputBufferStatus = AudioQueueAllocateBuffer(_recordState.mOutputQueue, _recordState.bufferByteSize, &_recordState.mOutputBuffers[i]);
        if (outputBufferStatus != 0) {
            RCTLog(@"[RNLiveAudioStream] Output Buffer allocation failed. status: %i", (int)outputBufferStatus);
        }
    }
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

    // Copy data to output buffer and enqueue it
    AudioQueueBufferRef outputBuffer;
    OSStatus status = AudioQueueAllocateBuffer(pRecordState->mOutputQueue, pRecordState->bufferByteSize, &outputBuffer);
    if (status != 0) {
        RCTLog(@"[RNLiveAudioStream] Output Buffer allocation failed in HandleInputBuffer. status: %i", (int)status);
        return;
    }

    memcpy(outputBuffer->mAudioData, inBuffer->mAudioData, inBuffer->mAudioDataByteSize);
    outputBuffer->mAudioDataByteSize = inBuffer->mAudioDataByteSize;

    OSStatus enqueueStatus = AudioQueueEnqueueBuffer(pRecordState->mOutputQueue, outputBuffer, 0, NULL);
    if (enqueueStatus != 0) {
        RCTLog(@"[RNLiveAudioStream] Output Buffer enqueue failed. status: %i", (int)enqueueStatus);
    }

    // Re-enqueue input buffer
    OSStatus inputEnqueueStatus = AudioQueueEnqueueBuffer(pRecordState->mInputQueue, inBuffer, 0, NULL);
    if (inputEnqueueStatus != 0) {
        RCTLog(@"[RNLiveAudioStream] Input Buffer re-enqueue failed. status: %i", (int)inputEnqueueStatus);
    }
}

void HandleOutputBuffer(void *inUserData,
                        AudioQueueRef inAQ,
                        AudioQueueBufferRef inBuffer) {
    // This function can be used to manage the output buffers if necessary
    // For now, we can leave it empty or add logging if needed
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"data"];
}

- (void)dealloc {
    RCTLogInfo(@"[RNLiveAudioStream] dealloc");
    AudioQueueDispose(_recordState.mInputQueue, true);
    AudioQueueDispose(_recordState.mOutputQueue, true);
}

@end
