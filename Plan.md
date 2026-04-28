# Alemeno Marker Scanner - Plan

## Current State
App is complete and submitted. Detection algorithm is fully implemented and fixed.

## Tech Stack
- React Native 0.85.2 (New Architecture forced on)
- react-native-vision-camera v5.0.7 + react-native-nitro-modules + react-native-nitro-image
- OpenCV 4.9.0 (Maven Central)
- Kotlin native module (ReactContextBaseJavaModule)
- Gradle 8.14.1 (downgraded from 9.3.1 - IBM_SEMERU constant removed in 9.x breaks RN plugin)
- Java via Android Studio JBR (Java 21)

## Detection Algorithm

Marker 1: 140x140mm square, thick black border, 20x20mm orientation dot in one inner corner.

Pipeline:
1. Photo -> Bitmap -> RGBA Mat -> Grayscale
2. Downsample to max 1500px
3. Gaussian blur 5x5
4. Adaptive threshold inverted (11px block, C=2)
5. Morphological close 3x3
6. findContours RETR_EXTERNAL
7. Per contour: area filter (0.5-70%), approxPolyDP (epsilon=0.04), convexity, aspect ratio 0.6-1.7
8. perspectiveWarp to 300x300
9. verifyMarker1Structure
10. Extract from full-res, correctOrientation, encode JPEG base64

## Key Fix Applied (verifyMarker1Structure)

**Problem:** Original code used absolute thresholds:
- Required interior center to be white (mean > 150) - broke for animal face images
- Required exactly 1 corner < 90 AND exactly 3 corners > 155 - broke when image had dark content
- Size guard (checkBeyondDot) checked 22px into interior and hit dark image content

**Fix:** Relative comparison approach:
- Border strips: mean < 140 (tolerates screen black ~20-80 through camera)
- Find darkest corner (the dot)
- dotMean must be < 110 AND secondDarkest - dotMean >= 30
- Removed size guard entirely
- approxPolyDP epsilon: 0.02 -> 0.04 (more tolerance for screen moiré)

## Decisions
- VisionCamera v5 required because v4 is broken on RN 0.77+
- nitro-modules and nitro-image are required peer deps for VisionCamera v5
- Gradle 8.14.1 specifically (not 9.x)
- JAVA_HOME must point to Android Studio JBR: `/c/Program Files/Android/Android Studio/jbr`

## Next Steps
- None. Assignment submitted.
