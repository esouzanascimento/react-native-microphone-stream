package com.imxiqi.rnliveaudiostream;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder.AudioSource;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
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

import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

public class RNLiveAudioStreamModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;
    private final AudioManager audioManager;
    private AudioDeviceCallback audioDeviceCallback;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;
    private int audioSource;

    private AudioRecord recorder;
    private AudioTrack audioTrack;
    private int bufferSize;
    private boolean isRecording;

    private BroadcastReceiver scoReceiver;
    private boolean scoConnected = false;
    private static final long SCO_TIMEOUT_MS = 5000L;
    private int cachedScoDeviceId = -1;
    private boolean cachedScoActive = false;
    private boolean scoRequestedInConstructor = false;
    private AudioDeviceInfo bt;

    public RNLiveAudioStreamModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        audioManager = (AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE);

        // Initialize silent buffer for SCO keep-alive
        bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final byte[] silentBuffer = new byte[bufferSize]; // all zeros
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

        int recordingBufferSize = bufferSize;
        recorder = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize);

        int playbackChannelConfig = (channelConfig == AudioFormat.CHANNEL_IN_MONO) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        AudioFormat af = new AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRateInHz)
            .setChannelMask(playbackChannelConfig)
            .build();
        audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(af)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            audioDeviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {}

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    for (AudioDeviceInfo device : removedDevices) {
                        isRecording = false;
                        if (device.isSource()) {
                            Log.d("RNLiveAudioStream", "Audio input device removed: " + device.getProductName());
                            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                .emit("onAudioInputChange", "device_removed");
                            break;
                        } else {
                            Log.d("RNLiveAudioStream", "Audio output device removed: " + device.getProductName());
                            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                .emit("onAudioRouteChange", "unplugged");
                        }
                    }
                }
            };
        }
    }

    private boolean isBluetoothConnectPermissionGranted() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return reactContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @ReactMethod
    public void start() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && audioDeviceCallback != null) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler);
        }

        isRecording = true;

        AudioDeviceInfo btDevice = findBluetoothDevice();
        if (btDevice != null) {
            // For A2DP/BLE: use setPreferredDevice (it works well here)
            boolean ok = audioTrack.setPreferredDevice(btDevice);
            Log.d("RNLiveAudioStream", "setPreferredDevice (A2DP/BLE): " + ok);

            audioManager.setMode(AudioManager.MODE_NORMAL);
        } else {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        recorder.startRecording();
        audioTrack.play();
        startRecordingThread();
    }

    private void createVoiceAudioTrackIfNeeded() {
        // Only rebuild if current attributes are not voice
        // (simple approach: always rebuild when using SCO)
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

        int playbackChannelConfig = (channelConfig == AudioFormat.CHANNEL_IN_MONO) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        AudioFormat af = new AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRateInHz)
            .setChannelMask(playbackChannelConfig)
            .build();

        // release old track safely (if exists)
        try { audioTrack.release(); } catch (Exception e) {}

        audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(af)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build();
    }

    public void startRecordingThread() {
        Thread recordingThread = new Thread(new Runnable() {
            public void run() {
                try {
                    int bytesRead;
                    int count = 0;
                    byte[] buffer = new byte[bufferSize];

                    while (isRecording) {
                        bytesRead = recorder.read(buffer, 0, buffer.length);

                        // skip first 2 buffers to eliminate "click sound"
                        if (bytesRead > 0 && ++count > 2) {
                            // Apply gain factor to increase volume (removed since it also amplify the noise)
                            // for (int i = 0; i < bytesRead; i += 2) {
                            //     short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                            //     sample = (short) Math.min(Math.max(sample * gainFactor, Short.MIN_VALUE), Short.MAX_VALUE);
                            //     buffer[i] = (byte) (sample & 0xFF);
                            //     buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
                            // }
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        }
        isRecording = false;

        // stop recorder/track safely
        try {
            recorder.stop();
        } catch (Exception e) {}

        try {
            audioTrack.stop();
        } catch (Exception e) {}

        // clean up SCO if used
        try {
            if (scoReceiver != null) {
                reactContext.unregisterReceiver(scoReceiver);
                scoReceiver = null;
            }
        } catch (Exception e) {}

        promise.resolve(null);
    }

    private AudioDeviceInfo findBluetoothDevice() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            return null;
        }

        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();

            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                Log.d("AudioDeviceCheck", "Found Classic Bluetooth: " + device.getProductName());
                return device;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    Log.d("AudioDeviceCheck", "Found BLE Headset/Speaker: " + device.getProductName());
                    return device;
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                Log.d("AudioDeviceCheck", "Found BLE Broadcast device: " + device.getProductName());
                return device;
            }
        }

        Log.d("RNLiveAudioStream", "No Bluetooth output device found.");
        return null;
    }

    
    public boolean isExternalAudioOutputConnected() {
        // Modern approach for Android 6.0 (API 23) and above
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int deviceType = device.getType();

                // Check for a list of known external audio device types
                if (deviceType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    deviceType == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    deviceType == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    deviceType == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    deviceType == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    deviceType == AudioDeviceInfo.TYPE_DOCK ||
                    deviceType == AudioDeviceInfo.TYPE_HDMI ||
                    deviceType == AudioDeviceInfo.TYPE_HDMI_ARC ||
                    deviceType == AudioDeviceInfo.TYPE_LINE_ANALOG) {
                    return true;
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        deviceType == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                        return true;
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (deviceType == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                        return true;
                    }
                }
            }
        } else {
            // Fallback for older Android versions (deprecated methods)
            // Note: This won't detect USB audio devices.
            return audioManager.isWiredHeadsetOn() ||
                audioManager.isBluetoothScoOn() ||
                audioManager.isBluetoothA2dpOn();
        }

        return false;
    }

    @ReactMethod
    public void isExternalAudioOutputConnected(Promise promise) {
        promise.resolve(isExternalAudioOutputConnected());
    }

    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
        // cleanup receiver
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && audioDeviceCallback != null) {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        }
            if (scoReceiver != null) {
                reactContext.unregisterReceiver(scoReceiver);
                scoReceiver = null;
            }
        } catch (Exception e) {}
        // stop SCO if we started it
        try {
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);
            cachedScoActive = false;
            cachedScoDeviceId = -1;
        } catch (Exception e) {}
    }
}
