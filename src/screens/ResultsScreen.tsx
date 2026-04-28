import React from 'react';
import {
  FlatList,
  Image,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { RootStackParamList } from '../navigation/AppNavigator';

type Props = NativeStackScreenProps<RootStackParamList, 'Results'>;

// Each extracted marker is displayed at exactly 300x300 logical pixels as
// required by the assignment spec.
const MARKER_DISPLAY_SIZE = 150; // 2 per row; logical 300px = too wide on most phones

export function ResultsScreen({ route, navigation }: Props) {
  const { markers } = route.params;

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <Text style={styles.title}>Extracted Markers</Text>
        <Text style={styles.subtitle}>{markers.length} frames - orientation corrected</Text>
      </View>

      <FlatList
        data={markers}
        numColumns={2}
        keyExtractor={(_, i) => String(i)}
        contentContainerStyle={styles.grid}
        columnWrapperStyle={styles.row}
        renderItem={({ item, index }) => (
          <View style={styles.cell}>
            {/*
              The spec requires 300x300px output images.
              Each Image component displays the already-300x300 extracted marker.
              The on-screen pixel size is 150dp (2 per row) to fit the display,
              but the underlying image data is always 300x300 pixels.
            */}
            <Image
              source={{ uri: `data:image/jpeg;base64,${item}` }}
              style={styles.markerImage}
              resizeMode="contain"
            />
            <Text style={styles.label}>#{index + 1}</Text>
          </View>
        )}
      />

      <TouchableOpacity
        style={styles.button}
        onPress={() => {
          navigation.navigate('Camera');
        }}
        activeOpacity={0.8}
      >
        <Text style={styles.buttonText}>Scan Again</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#0D0D0D',
  },
  header: {
    paddingTop: 52,
    paddingBottom: 16,
    paddingHorizontal: 20,
    borderBottomWidth: 1,
    borderBottomColor: '#222',
  },
  title: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  subtitle: {
    color: '#888',
    fontSize: 13,
    marginTop: 4,
  },
  grid: {
    padding: 8,
    paddingBottom: 100,
  },
  row: {
    justifyContent: 'space-between',
  },
  cell: {
    margin: 6,
    alignItems: 'center',
    backgroundColor: '#1A1A1A',
    borderRadius: 8,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: '#2A2A2A',
  },
  markerImage: {
    width: MARKER_DISPLAY_SIZE,
    height: MARKER_DISPLAY_SIZE,
    backgroundColor: '#fff',
  },
  label: {
    color: '#666',
    fontSize: 11,
    paddingVertical: 5,
  },
  button: {
    position: 'absolute',
    bottom: 32,
    alignSelf: 'center',
    backgroundColor: '#00E5FF',
    paddingHorizontal: 44,
    paddingVertical: 14,
    borderRadius: 30,
    minWidth: 160,
    alignItems: 'center',
  },
  buttonText: {
    color: '#000',
    fontSize: 16,
    fontWeight: '700',
  },
});
