declare module "react-native-i2l-voice" {
  export interface IAudioRecord {
    init: (options: Options) => void
    start: () => void
    stop: () => Promise<string>
    on: (event: "onAudioRouteChange", callback: (status: string) => void) => void
    isExternalAudioOutputConnected: () => Promise<boolean>
  }

  export interface Options {
    sampleRate: number
    /**
     * - `1 | 2`
     */
    channels: number
    /**
     * - `8 | 16`
     */
    bitsPerSample: number
    /**
     * - `6`
     */
    audioSource?: number
    wavFile: string
    bufferSize?: number
  }

  const AudioRecord: IAudioRecord

  export default AudioRecord;
}
