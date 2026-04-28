# Alemeno Marker Scanner

Android app (React Native) that detects, extracts, and orientation-corrects **Marker 1** from a live camera feed, collecting 20 frames and displaying them at 300x300px.

---

## What It Does

1. Opens the back camera with photo-quality capture
2. Periodically captures frames (every 250ms) while the user holds the marker in frame
3. Runs a custom OpenCV-based detection pipeline on each frame (native Android, Kotlin)
4. If Marker 1 is detected: extracts it, corrects orientation, and adds it to the collection
5. After 20 frames are collected it navigates to the results grid

---

## Detection Algorithm

**Marker 1** is a 140x140mm square with:
- A thick solid black border on all four sides
- A 20x20mm orientation anchor square in exactly one inner corner

### Pipeline (runs in a background thread)

```
Photo JPEG
  -> Bitmap -> RGBA Mat -> Grayscale Mat
  -> Downsample to max 1500px (speed)
  -> Gaussian blur 5x5
  -> Adaptive threshold (inverted, 11px block, C=2)
  -> Morphological close 3x3 (bridge gaps)
  -> findContours RETR_EXTERNAL
  -> For each contour:
       area filter (0.5% - 70% of image)
       approxPolyDP -> must be quadrilateral
       convexity check
       aspect ratio 0.6 - 1.7 (roughly square)
       orderCorners (centroid-relative sums/diffs)
       perspectiveWarp to 300x300
       -> verifyMarker1Structure:
            all 4 border strips dark (mean < 110)
            interior center white (mean > 150)
            exactly 1 of 4 inner corners dark (dot)
            area BEYOND dot zone is white (size guard)
       -> if valid: extract from full-res gray
                    correctOrientation (rotate dot to TL)
                    encode as JPEG base64
```

### Why Adaptive Threshold

Adaptive thresholding handles uneven lighting and shadows far better than a global Otsu threshold, making detection robust across different lighting conditions.

### Orientation Correction

The corner dot's position in the warped image determines the rotation needed:
- TL: no rotation (already canonical)
- TR: 90deg counter-clockwise
- BR: 180deg
- BL: 90deg clockwise

### False-Positive Rejection

The "size guard" check (verifying pixels just BEYOND the dot zone are white) rejects:
- Incorrect images where the black fill is larger than the real dot (e.g. a large quadrant fill)
- The center-position + corner-position check rejects centered dots

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | React Native 0.85 |
| Camera | react-native-vision-camera v4 |
| Computer Vision | OpenCV 4.9.0 (Maven Central) |
| Navigation | @react-navigation/native-stack |
| Native module | Kotlin (ReactContextBaseJavaModule) |

---

## Setup & Build

### Prerequisites

- Node 18+
- JDK 17
- Android SDK (API 24+, build tools 36)
- Android NDK 27.1.12297006
- A physical Android device (recommended) or emulator

### Install

```bash
git clone https://github.com/dev-sanidhya/alemeno-marker-scanner.git
cd alemeno-marker-scanner
npm install
```

### Run (debug)

Connect an Android device or start an emulator, then:

```bash
npx react-native run-android
```

> The first build downloads the OpenCV 4.9.0 AAR from Maven Central (~35MB) and takes a few minutes.

### Release APK

```bash
cd android
./gradlew assembleRelease
```

APK location: `android/app/build/outputs/apk/release/app-release.apk`

---

## Camera Resolution

The app uses `qualityPrioritization: 'quality'` in VisionCamera which selects the highest available photo resolution. Most modern Android phones produce images well above the 2000x2000px minimum specified in the assignment.

---

## Project Structure

```
src/
  native/MarkerDetector.ts          JS bridge to native module
  navigation/AppNavigator.tsx       Stack navigator (Camera -> Results)
  screens/CameraScreen.tsx          Live preview + capture loop
  screens/ResultsScreen.tsx         20-frame grid display

android/app/src/main/java/com/alemanomarkerscanner/
  MarkerDetectorModule.kt           OpenCV detection (Kotlin)
  MarkerDetectorPackage.kt          RN package registration
  MainApplication.kt                App entry + OpenCV init
```
