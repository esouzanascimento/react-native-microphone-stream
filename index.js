import { NativeModules, NativeEventEmitter } from 'react-native';
const { RNLiveAudioStream } = NativeModules;
const EventEmitter = new NativeEventEmitter(RNLiveAudioStream);

const AudioRecord = {};

AudioRecord.init = options => RNLiveAudioStream.init(options);
AudioRecord.start = () => RNLiveAudioStream.start();
AudioRecord.stop = () => RNLiveAudioStream.stop();
AudioRecord.isExternalAudioOutputConnected = () =>
  new Promise((resolve, reject) => {
    RNLiveAudioStream.isExternalAudioOutputConnected()
      .then(isConnected => resolve(isConnected))
      .catch(error => reject(error));
  });

const eventsMap = {
  onAudioRouteChange: 'onAudioRouteChange'
};

AudioRecord.on = (event, callback) => {
  const nativeEvent = eventsMap[event];
  if (!nativeEvent) {
    throw new Error('Invalid event');
  }
  EventEmitter.removeAllListeners(nativeEvent);
  return EventEmitter.addListener(nativeEvent, callback);
};

export default AudioRecord;
