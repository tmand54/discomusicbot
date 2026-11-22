package app.snapstack.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class FrameOpsTest {

    @Test fun roiRoundTripsForEveryRotation() {
        val sw = 1920; val sh = 1440
        for (rotation in listOf(0, 90, 180, 270)) {
            val (uw, uh) = if (rotation % 180 == 0) sw to sh else sh to sw
            val upright = IntRect(uw / 10, uh / 8, uw * 9 / 10, uh * 7 / 8)
            val sensor = RoiMapper.uprightToSensor(upright, sw, sh, rotation)
            assertTrue("in bounds @ $rotation: $sensor", sensor.left >= 0 && sensor.top >= 0 && sensor.right <= sw && sensor.bottom <= sh)
            assertEquals("size @ $rotation", upright.width * upright.height, sensor.width * sensor.height)
            assertEquals("round trip @ $rotation", upright, RoiMapper.sensorToUpright(sensor, sw, sh, rotation))
        }
    }

    @Test fun rotation90MapsTheTopOfTheCardToTheSensorsLeftEdge() {
        // Portrait phone, landscape sensor: the top strip of the upright image is the sensor's left strip.
        val s = RoiMapper.uprightToSensor(IntRect(0, 0, 1440, 100), 1920, 1440, 90)
        assertEquals(IntRect(0, 0, 100, 1440), s)
    }

    @Test fun cropIsEvenClampedAndFallsBackToFullFrame() {
        val c = RoiMapper.cropFor(Box(101f, 151f, 1301f, 1801f), 1920, 1440, 90)
        assertTrue(c.left % 2 == 0 && c.top % 2 == 0 && c.width % 2 == 0 && c.height % 2 == 0)
        assertTrue(c.right <= 1920 && c.bottom <= 1440)
        assertEquals(IntRect(0, 0, 1920, 1440), RoiMapper.cropFor(null, 1920, 1440, 90))
        assertEquals(IntRect(0, 0, 1920, 1440), RoiMapper.cropFor(Box(10f, 10f, 20f, 20f), 1920, 1440, 90))
    }

    private fun image(w: Int, h: Int, sharp: Boolean): ByteBuffer {
        val rnd = Random(7)
        val a = ByteArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (sharp) (if (((x / 3) + (y / 5)) % 2 == 0) 230 else 20).toByte() // crisp text-like edges
            else (120 + ((x + y) % 9) + rnd.nextInt(3)).toByte() // smooth, low contrast
        }
        return ByteBuffer.wrap(a)
    }

    @Test fun sharpnessSeparatesCrispFromBlurred() {
        val crop = IntRect(0, 0, 400, 300)
        val sharp = Sharpness.score(image(400, 300, true), 400, crop)
        val blurred = Sharpness.score(image(400, 300, false), 400, crop)
        assertTrue("sharp=$sharp blurred=$blurred", sharp > blurred * 4)
    }

    @Test fun blurGateSkipsBlurryFramesButNeverStalls() {
        val gate = BlurGate(ratio = 0.55, maxSkips = 3)
        assertTrue(gate.shouldRead(40.0))
        assertTrue(gate.shouldRead(38.0))
        // Card starts moving: blurry frames are skipped, but at most 3 in a row.
        val reads = (1..8).map { gate.shouldRead(8.0) }
        assertTrue(gate.blurry)
        assertEquals(listOf(false, false, false, true, false, false, false, true), reads)
        assertTrue(gate.shouldRead(39.0))
        assertFalse(gate.blurry)
    }

    @Test fun nv21CropCopiesLumaAndNeutralChroma() {
        val w = 8; val h = 6
        val src = ByteBuffer.wrap(ByteArray(w * h) { it.toByte() })
        val out = Nv21Cropper().crop(src, w, IntRect(2, 2, 6, 6))
        assertEquals(4 * 4 * 3 / 2, out.size)
        assertEquals((2 * w + 2).toByte(), out[0])
        assertEquals((5 * w + 5).toByte(), out[15])
        assertTrue(out.drop(16).all { it == 128.toByte() })
    }

    @Test fun ocrBoxesShiftBackToFullFrame() {
        val f = OcrFrame(listOf(OcrLine("123/193", Box(10f, 20f, 60f, 30f))), 400, 600)
        val g = f.offsetBy(100, 200, 1440, 1920)
        assertEquals(Box(110f, 220f, 160f, 230f), g.lines.single().box)
        assertEquals(1440, g.width); assertEquals(1920, g.height)
    }

    @Test fun perFrameWorkIsCheap() {
        // A 1920×1440 luma plane: crop + sharpness should be a few milliseconds, not tens.
        val w = 1920; val h = 1440
        val buf = image(w, h, true)
        val crop = RoiMapper.cropFor(Box(150f, 200f, 1290f, 1720f), w, h, 90)
        val cropper = Nv21Cropper()
        repeat(5) { cropper.crop(buf, w, crop); Sharpness.score(buf, w, crop) } // warm up
        val t0 = System.nanoTime()
        repeat(20) { cropper.crop(buf, w, crop); Sharpness.score(buf, w, crop) }
        val ms = (System.nanoTime() - t0) / 20 / 1e6
        val share = crop.width * crop.height * 100 / (w * h)
        println("crop ${crop.width}x${crop.height} ($share%% of frame) + sharpness: %.2f ms/frame".format(ms))
        assertTrue("took $ms ms", ms < 25)
    }
}
