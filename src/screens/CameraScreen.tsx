import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  Camera,
  useCameraDevice,
  useCameraPermission,
} from 'react-native-vision-camera';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { RootStackParamList } from '../navigation/AppNavigator';
import { MarkerDetector } from '../native/MarkerDetector';

type Props = NativeStackScreenProps<RootStackParamList, 'Camera'>;

const TARGET_COUNT = 20;
// Interval between capture attempts in ms. Processing runs on a background
// thread so if the previous frame is still being analysed we skip this tick.
const CAPTURE_INTERVAL_MS = 250;

export function CameraScreen({ navigation }: Props) {
  const { hasPermission, requestPermission } = useCameraPermission();
  const device = useCameraDevice('back');
  const cameraRef = useRef<Camera>(null);

  const [count, setCount] = useState(0);
  const [isScanning, setIsScanning] = useState(false);
  const [statusText, setStatusText] = useState('Point camera at Marker 1 and tap Start');

  // Refs avoid stale closures inside the setInterval callback
  const collectedRef = useRef<string[]>([]);
  const isBusyRef = useRef(false);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const isScanningRef = useRef(false);

  useEffect(() => {
    if (!hasPermission) {
      requestPermission().catch(() => {
        Alert.alert('Permission required', 'Camera access is needed to scan markers.');
      });
    }
  }, [hasPermission, requestPermission]);

  const stopScanning = useCallback(() => {
    isScanningRef.current = false;
    setIsScanning(false);
    if (intervalRef.current !== null) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    isBusyRef.current = false;
  }, []);

  const captureAndProcess = useCallback(async () => {
    if (isBusyRef.current || !isScanningRef.current) return;
    if (!cameraRef.current) return;

    isBusyRef.current = true;
    try {
      const photo = await cameraRef.current.takePhoto({
        qualityPrioritization: 'quality',
        flash: 'off',
        enableShutterSound: false,
      });

      // VisionCamera returns a bare path on Android; strip file:// if present
      const path = photo.path.startsWith('file://')
        ? photo.path.slice(7)
        : photo.path;

      const b64 = await MarkerDetector.detectMarker(path);

      if (b64 && isScanningRef.current) {
        collectedRef.current = [...collectedRef.current, b64];
        const n = collectedRef.current.length;
        setCount(n);
        setStatusText(`Captured ${n} / ${TARGET_COUNT} frames`);

        if (n >= TARGET_COUNT) {
          stopScanning();
          navigation.navigate('Results', { markers: collectedRef.current });
        }
      }
    } catch {
      // Silently ignore individual frame errors and continue scanning
    } finally {
      isBusyRef.current = false;
    }
  }, [navigation, stopScanning]);

  const startScanning = useCallback(() => {
    collectedRef.current = [];
    isBusyRef.current = false;
    setCount(0);
    setStatusText('Scanning...');
    isScanningRef.current = true;
    setIsScanning(true);
    intervalRef.current = setInterval(captureAndProcess, CAPTURE_INTERVAL_MS);
  }, [captureAndProcess]);

  // Clean up interval on unmount
  useEffect(() => {
    return () => {
      if (intervalRef.current !== null) clearInterval(intervalRef.current);
    };
  }, []);

  // --- Permission not yet granted ---
  if (!hasPermission) {
    return (
      <View style={styles.center}>
        <Text style={styles.infoText}>Camera permission is required.</Text>
        <TouchableOpacity style={styles.button} onPress={requestPermission}>
          <Text style={styles.buttonText}>Grant Permission</Text>
        </TouchableOpacity>
      </View>
    );
  }

  // --- No camera device ---
  if (!device) {
    return (
      <View style={styles.center}>
        <Text style={styles.infoText}>No back camera found on this device.</Text>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      {/* Live camera preview fills the screen */}
      <Camera
        ref={cameraRef}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive
        photo
        photoQualityBalance="quality"
      />

      {/* Semi-transparent top bar */}
      <View style={styles.topBar}>
        <Text style={styles.appTitle}>Marker 1 Scanner</Text>
        <Text style={styles.counterBadge}>
          {count} / {TARGET_COUNT}
        </Text>
      </View>

      {/* Reticle: guides user to centre the marker */}
      <View style={styles.reticleWrapper} pointerEvents="none">
        <View style={styles.reticle}>
          <View style={[styles.corner, styles.cornerTL]} />
          <View style={[styles.corner, styles.cornerTR]} />
          <View style={[styles.corner, styles.cornerBL]} />
          <View style={[styles.corner, styles.cornerBR]} />
        </View>
      </View>

      {/* Bottom controls */}
      <View style={styles.bottomBar}>
        <Text style={styles.statusText}>{statusText}</Text>

        {/* Progress bar */}
        <View style={styles.progressBg}>
          <View style={[styles.progressFill, { width: `${(count / TARGET_COUNT) * 100}%` }]} />
        </View>

        <TouchableOpacity
          style={[styles.button, isScanning && styles.buttonStop]}
          onPress={isScanning ? stopScanning : startScanning}
          activeOpacity={0.8}
        >
          <Text style={styles.buttonText}>{isScanning ? 'Stop' : 'Start Scan'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const RETICLE_SIZE = 260;
const CORNER_LEN = 28;
const CORNER_THICK = 4;
const CORNER_COLOR = '#00E5FF';

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000',
  },
  center: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#111',
    padding: 24,
    gap: 16,
  },
  infoText: {
    color: '#fff',
    fontSize: 16,
    textAlign: 'center',
  },

  // Top bar
  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    paddingTop: 48,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  appTitle: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  counterBadge: {
    color: CORNER_COLOR,
    fontSize: 16,
    fontWeight: '700',
  },

  // Reticle
  reticleWrapper: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
  },
  reticle: {
    width: RETICLE_SIZE,
    height: RETICLE_SIZE,
    position: 'relative',
  },
  corner: {
    position: 'absolute',
    width: CORNER_LEN,
    height: CORNER_LEN,
    borderColor: CORNER_COLOR,
  },
  cornerTL: {
    top: 0,
    left: 0,
    borderTopWidth: CORNER_THICK,
    borderLeftWidth: CORNER_THICK,
  },
  cornerTR: {
    top: 0,
    right: 0,
    borderTopWidth: CORNER_THICK,
    borderRightWidth: CORNER_THICK,
  },
  cornerBL: {
    bottom: 0,
    left: 0,
    borderBottomWidth: CORNER_THICK,
    borderLeftWidth: CORNER_THICK,
  },
  cornerBR: {
    bottom: 0,
    right: 0,
    borderBottomWidth: CORNER_THICK,
    borderRightWidth: CORNER_THICK,
  },

  // Bottom bar
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    alignItems: 'center',
    paddingHorizontal: 24,
    paddingBottom: 40,
    paddingTop: 16,
    gap: 12,
    backgroundColor: 'rgba(0,0,0,0.60)',
  },
  statusText: {
    color: '#eee',
    fontSize: 14,
    textAlign: 'center',
  },
  progressBg: {
    width: '100%',
    height: 4,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: 2,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    backgroundColor: CORNER_COLOR,
    borderRadius: 2,
  },
  button: {
    backgroundColor: CORNER_COLOR,
    paddingHorizontal: 40,
    paddingVertical: 14,
    borderRadius: 30,
    minWidth: 160,
    alignItems: 'center',
  },
  buttonStop: {
    backgroundColor: '#FF4444',
  },
  buttonText: {
    color: '#000',
    fontSize: 16,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
});
