package com.alemanomarkerscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

class MarkerDetectorModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private var opencvReady = false

        fun ensureOpenCV() {
            if (!opencvReady) {
                opencvReady = OpenCVLoader.initLocal()
            }
        }
    }

    init {
        ensureOpenCV()
    }

    override fun getName() = "MarkerDetector"

    // Called from JS: detectMarker(imagePath) -> Promise<string | null>
    @ReactMethod
    fun detectMarker(imagePath: String, promise: Promise) {
        Thread {
            try {
                val path = if (imagePath.startsWith("file://")) imagePath.drop(7) else imagePath

                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(path, opts)
                if (bitmap == null) {
                    promise.resolve(null)
                    return@Thread
                }

                // Convert bitmap -> RGBA Mat -> Grayscale Mat
                val rgba = Mat()
                Utils.bitmapToMat(bitmap, rgba)
                bitmap.recycle()

                val gray = Mat()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                rgba.release()

                // Scale down to max 1500px on longest side for detection speed
                val maxDim = max(gray.width(), gray.height()).toDouble()
                val scale = if (maxDim > 1500.0) 1500.0 / maxDim else 1.0

                val workGray = Mat()
                if (scale < 1.0) {
                    val nw = (gray.width() * scale).toInt()
                    val nh = (gray.height() * scale).toInt()
                    Imgproc.resize(gray, workGray, Size(nw.toDouble(), nh.toDouble()))
                } else {
                    gray.copyTo(workGray)
                }

                val b64 = findMarker1(workGray, gray, scale)

                gray.release()
                workGray.release()

                promise.resolve(b64)
            } catch (e: Exception) {
                promise.reject("ERR_DETECTION", e.message ?: "Detection failed")
            }
        }.start()
    }

    // ---------------------------------------------------------------
    // Core detection: finds Marker 1 in workGray (downsampled),
    // then extracts from fullGray at original resolution.
    // Returns base64 JPEG string or null.
    // ---------------------------------------------------------------
    private fun findMarker1(workGray: Mat, fullGray: Mat, scale: Double): String? {
        // 1. Preprocess: blur -> adaptive threshold (inverted: dark regions become white)
        val blurred = Mat()
        Imgproc.GaussianBlur(workGray, blurred, Size(5.0, 5.0), 0.0)

        val binary = Mat()
        Imgproc.adaptiveThreshold(
            blurred, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            11, 2.0
        )
        blurred.release()

        // Morphological close: bridge tiny gaps in the border contour
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
        binary.release()
        kernel.release()

        // 2. Find outermost contours only
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            closed, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        closed.release()
        hierarchy.release()

        val imgArea = workGray.width().toDouble() * workGray.height().toDouble()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            // Must cover between 0.5% and 70% of the image
            if (area < imgArea * 0.005 || area > imgArea * 0.70) continue

            val pts2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(pts2f, true)

            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(pts2f, approx, 0.04 * peri, true)
            pts2f.release()

            // Must approximate to a quadrilateral
            if (approx.rows() != 4) continue

            val pts = approx.toArray()
            val approxInt = MatOfPoint(*pts.map { Point(it.x, it.y) }.toTypedArray())

            // Must be convex
            if (!Imgproc.isContourConvex(approxInt)) continue

            // Must be roughly square (aspect ratio 0.6 to 1.7)
            val br = Imgproc.boundingRect(approxInt)
            val ar = br.width.toDouble() / br.height.toDouble()
            if (ar < 0.60 || ar > 1.70) continue

            // Order corners: TL, TR, BR, BL
            val ordered = orderCorners(pts)

            // Warp working-resolution grayscale to 300x300 for verification
            val warped = perspectiveWarp(workGray, ordered, 300)
            val dotCorner = verifyMarker1Structure(warped)
            warped.release()

            if (dotCorner < 0) continue

            // Valid marker found - extract from FULL resolution
            val fullCorners = ordered.map { Point(it.x / scale, it.y / scale) }.toTypedArray()
            val extracted = perspectiveWarp(fullGray, fullCorners, 300)

            // Rotate so the orientation dot lands at top-left
            val corrected = correctOrientation(extracted, dotCorner)
            extracted.release()

            val b64 = grayToBase64(corrected)
            corrected.release()

            return b64
        }

        return null
    }

    // ---------------------------------------------------------------
    // Verifies that a 300x300 warped grayscale image matches Marker 1:
    //   - Thick solid black border on all 4 sides
    //   - Mostly white interior
    //   - Exactly ONE small black square (~14% of size) at a corner
    //   - Black area does NOT extend beyond the expected dot zone
    //
    // Returns dot corner index (0=TL, 1=TR, 2=BR, 3=BL) or -1 if invalid.
    // ---------------------------------------------------------------
    private fun verifyMarker1Structure(w: Mat): Int {
        val S = 300

        // Marker 1 proportions (original 140x140):
        //   Border width ~13% of total -> 39px in 300px space
        //   Corner dot: 20/140 * 300 = 43px
        val borderW = 38
        val dotSz = 43
        val beyondSz = 22   // pixels just outside the expected dot zone

        val innerStart = borderW
        val dotEnd = borderW + dotSz
        val farStart = S - borderW - dotSz
        val innerEnd = S - borderW

        // --- 1. All 4 border strips must be dark (mean < 110) ---
        val topStrip = w.submat(0, borderW, 0, S)
        val botStrip = w.submat(S - borderW, S, 0, S)
        val lefStrip = w.submat(borderW, S - borderW, 0, borderW)
        val rigStrip = w.submat(borderW, S - borderW, S - borderW, S)

        val tm = Core.mean(topStrip).`val`[0]; topStrip.release()
        val bm = Core.mean(botStrip).`val`[0]; botStrip.release()
        val lm = Core.mean(lefStrip).`val`[0]; lefStrip.release()
        val rm = Core.mean(rigStrip).`val`[0]; rigStrip.release()

        if (tm > 110 || bm > 110 || lm > 110 || rm > 110) return -1

        // --- 2. Center of interior must be mostly white (mean > 150) ---
        val cx0 = dotEnd + 8
        val cx1 = farStart - 8
        if (cx1 > cx0) {
            val center = w.submat(cx0, cx1, cx0, cx1)
            val cm = Core.mean(center).`val`[0]; center.release()
            if (cm < 150) return -1
        }

        // --- 3. Check the 4 inner corner sub-regions for the dot ---
        // Exactly ONE must be dark (<90), the other THREE must be light (>155)
        val tlReg = w.submat(innerStart, dotEnd, innerStart, dotEnd)
        val trReg = w.submat(innerStart, dotEnd, farStart, innerEnd)
        val brReg = w.submat(farStart, innerEnd, farStart, innerEnd)
        val blReg = w.submat(farStart, innerEnd, innerStart, dotEnd)

        val means = doubleArrayOf(
            Core.mean(tlReg).`val`[0].also { tlReg.release() },
            Core.mean(trReg).`val`[0].also { trReg.release() },
            Core.mean(brReg).`val`[0].also { brReg.release() },
            Core.mean(blReg).`val`[0].also { blReg.release() }
        )

        val darkList = means.indices.filter { means[it] < 90 }
        val lightList = means.indices.filter { means[it] > 155 }

        if (darkList.size != 1 || lightList.size != 3) return -1

        val dotIdx = darkList[0]

        // --- 4. Size guard: pixels JUST BEYOND the dot zone must be bright ---
        // Rejects incorrect images where the black fill is larger than the real dot.
        val beyondBright = checkBeyondDot(w, dotIdx, innerStart, dotEnd, farStart, innerEnd, beyondSz)
        if (!beyondBright) return -1

        return dotIdx
    }

    // Checks that the area just outside the expected dot region (in x and y
    // directions) is mostly white. Returns false if that zone is dark.
    private fun checkBeyondDot(
        w: Mat, dotIdx: Int,
        innerStart: Int, dotEnd: Int, farStart: Int, innerEnd: Int,
        beyondSz: Int
    ): Boolean {
        val threshold = 145.0

        fun mean(r0: Int, r1: Int, c0: Int, c1: Int): Double {
            if (r1 <= r0 || c1 <= c0) return 255.0
            val sub = w.submat(r0, r1, c0, c1)
            val m = Core.mean(sub).`val`[0]
            sub.release()
            return m
        }

        return when (dotIdx) {
            0 -> { // TL dot - beyond is to the RIGHT and BELOW the dot
                mean(innerStart, dotEnd, dotEnd, min(dotEnd + beyondSz, farStart)) > threshold &&
                mean(dotEnd, min(dotEnd + beyondSz, farStart), innerStart, dotEnd) > threshold
            }
            1 -> { // TR dot - beyond is to the LEFT and BELOW
                mean(innerStart, dotEnd, max(farStart - beyondSz, dotEnd), farStart) > threshold &&
                mean(dotEnd, min(dotEnd + beyondSz, farStart), farStart, innerEnd) > threshold
            }
            2 -> { // BR dot - beyond is to the LEFT and ABOVE
                mean(farStart, innerEnd, max(farStart - beyondSz, dotEnd), farStart) > threshold &&
                mean(max(farStart - beyondSz, dotEnd), farStart, farStart, innerEnd) > threshold
            }
            3 -> { // BL dot - beyond is to the RIGHT and ABOVE
                mean(farStart, innerEnd, dotEnd, min(dotEnd + beyondSz, farStart)) > threshold &&
                mean(max(farStart - beyondSz, dotEnd), farStart, innerStart, dotEnd) > threshold
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------
    // Rotate the extracted 300x300 so the orientation dot ends up at TL.
    //   dotCorner 0 (TL) -> no rotation
    //   dotCorner 1 (TR) -> 90deg CCW
    //   dotCorner 2 (BR) -> 180deg
    //   dotCorner 3 (BL) -> 90deg CW
    // ---------------------------------------------------------------
    private fun correctOrientation(mat: Mat, dotCorner: Int): Mat {
        val out = Mat()
        when (dotCorner) {
            0 -> mat.copyTo(out)
            1 -> Core.rotate(mat, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            2 -> Core.rotate(mat, out, Core.ROTATE_180)
            3 -> Core.rotate(mat, out, Core.ROTATE_90_CLOCKWISE)
            else -> mat.copyTo(out)
        }
        return out
    }

    // ---------------------------------------------------------------
    // Orders 4 points into [TL, TR, BR, BL] by centroid-relative sums/diffs.
    // ---------------------------------------------------------------
    private fun orderCorners(pts: Array<Point>): Array<Point> {
        val cx = pts.map { it.x }.average()
        val cy = pts.map { it.y }.average()

        // TL: most negative (x-cx) + (y-cy)
        // BR: most positive (x-cx) + (y-cy)
        // TR: most negative (y-cy) - (x-cx)
        // BL: most positive (y-cy) - (x-cx)
        val tl = pts.minByOrNull { (it.x - cx) + (it.y - cy) }!!
        val br = pts.maxByOrNull { (it.x - cx) + (it.y - cy) }!!
        val tr = pts.minByOrNull { (it.y - cy) - (it.x - cx) }!!
        val bl = pts.maxByOrNull { (it.y - cy) - (it.x - cx) }!!

        return arrayOf(tl, tr, br, bl)
    }

    // ---------------------------------------------------------------
    // Perspective-warp src to a square of given size using 4 corners
    // ordered as [TL, TR, BR, BL].
    // ---------------------------------------------------------------
    private fun perspectiveWarp(src: Mat, corners: Array<Point>, size: Int): Mat {
        val s = size.toDouble()
        val srcPts = MatOfPoint2f(*corners)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(s - 1, 0.0),
            Point(s - 1, s - 1),
            Point(0.0, s - 1)
        )
        val M = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, M, Size(s, s))
        M.release()
        return warped
    }

    // ---------------------------------------------------------------
    // Convert grayscale Mat to base64-encoded JPEG string.
    // ---------------------------------------------------------------
    private fun grayToBase64(gray: Mat): String {
        val rgba = Mat()
        Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
        val bmp = Bitmap.createBitmap(rgba.width(), rgba.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()

        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        bmp.recycle()

        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
