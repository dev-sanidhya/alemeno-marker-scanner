import { NativeModules } from 'react-native';

const { MarkerDetector: Native } = NativeModules;

export const MarkerDetector = {
  /**
   * Process a photo at imagePath through the OpenCV Marker 1 detector.
   * Returns base64-encoded JPEG string of the extracted 300x300 marker,
   * or null if no valid marker was found in the image.
   */
  detectMarker(imagePath: string): Promise<string | null> {
    return Native.detectMarker(imagePath);
  },
};
