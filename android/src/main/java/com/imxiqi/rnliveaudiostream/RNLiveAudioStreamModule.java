package com.imxiqi.rnliveaudiostream;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder.AudioSource;
import android.media.AudioManager;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;

import android.content.Context;
import android.media.AudioDeviceInfo;
import com.facebook.react.bridge.Callback;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import java.lang.Math;

public class RNLiveAudioStreamModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;
    private final AudioManager audioManager;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;
    private int audioSource;

    private AudioRecord recorder;
    private AudioTrack audioTrack;
    private int bufferSize;
    private boolean isRecording;

    private float gainFactor = 10.0f;

    public RNLiveAudioStreamModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        audioManager = (AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public String getName() {
        return "RNLiveAudioStream";
    }

    @ReactMethod
    public void init(ReadableMap options) {
        sampleRateInHz = 44100;
        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate");
        }

        channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (options.hasKey("channels")) {
            if (options.getInt("channels") == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
        }

        audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        if (options.hasKey("bitsPerSample")) {
            if (options.getInt("bitsPerSample") == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT;
            }
        }

        audioSource = AudioSource.VOICE_RECOGNITION;
        if (options.hasKey("audioSource")) {
            audioSource = options.getInt("audioSource");
        }

        isRecording = false;

        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);

        if (options.hasKey("bufferSize")) {
            bufferSize = Math.max(bufferSize, options.getInt("bufferSize"));
        }

        int recordingBufferSize = bufferSize * 3;
        recorder = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize);

        int playbackChannelConfig = (channelConfig == AudioFormat.CHANNEL_IN_MONO) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRateInHz, playbackChannelConfig, audioFormat, bufferSize, AudioTrack.MODE_STREAM);
        audioTrack.setVolume(AudioTrack.getMaxVolume());
    }

    @ReactMethod
    public void start() {
        isRecording = true;

        // Set up the audio routing to ensure the device microphone is used
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
        }

        recorder.startRecording();
        audioTrack.play();

        Thread recordingThread = new Thread(new Runnable() {
            public void run() {
                try {
                    int bytesRead;
                    int count = 0;
                    byte[] buffer = new byte[bufferSize];

                    while (isRecording) {
                        if (!isExternalAudioOutputConnected()) {
                            isRecording = false;
                            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                .emit("onAudioRouteChange", "unplugged");
                            break;
                        }
                        bytesRead = recorder.read(buffer, 0, buffer.length);

                        // skip first 2 buffers to eliminate "click sound"
                        if (bytesRead > 0 && ++count > 2) {
                            // Apply gain factor to increase volume
                            for (int i = 0; i < bytesRead; i += 2) {
                                short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                                sample = (short) Math.min(Math.max(sample * gainFactor, Short.MIN_VALUE), Short.MAX_VALUE);
                                buffer[i] = (byte) (sample & 0xFF);
                                buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
                            }
                            audioTrack.write(buffer, 0, bytesRead);
                        }
                    }
                    recorder.stop();
                    audioTrack.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        recordingThread.start();
    }

    @ReactMethod
    public void stop(Promise promise) {
        isRecording = false;
        promise.resolve(null);
    }

    public boolean isExternalAudioOutputConnected() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                    return true;
                }
            }
        }
        return false;
    }

    @ReactMethod
    public void isExternalAudioOutputConnected(Promise promise) {
        promise.resolve(isExternalAudioOutputConnected());
    }
}
