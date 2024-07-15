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

import java.lang.Math;

public class RNLiveAudioStreamModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;
    private int audioSource;

    private AudioRecord recorder;
    private AudioTrack audioTrack;
    private int bufferSize;
    private boolean isRecording;

    private float gainFactor = 15.0f;

    public RNLiveAudioStreamModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
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
        recorder.startRecording();
        audioTrack.play();

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
}
