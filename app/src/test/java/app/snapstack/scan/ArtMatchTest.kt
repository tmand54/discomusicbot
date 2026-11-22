package app.snapstack.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ArtMatchTest {

    /** Minimal RGB raster (the Android unit-test classpath has no java.awt). */
    class Raster(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
        operator fun get(x: Int, y: Int) = px[y * w + x]
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in maxOf(0, y0) until minOf(h, y1)) for (x in maxOf(0, x0) until minOf(w, x1)) px[y * w + x] = c
        }
        fun oval(cx: Int, cy: Int, rx: Int, ry: Int, c: Int) {
            for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
                if (x !in 0 until w || y !in 0 until h) continue
                val dx = (x - cx).toDouble() / rx; val dy = (y - cy).toDouble() / ry
                if (dx * dx + dy * dy <= 1) px[y * w + x] = c
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val i = ((h / 60f).toInt() % 6 + 6) % 6; val f = h / 60f - (h / 60f).toInt()
        val p = v * (1 - s); val q = v * (1 - f * s); val t = v * (1 - (1 - f) * s)
        val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
        return rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    /** A made-up "artwork": coloured blobs in an art window, on a card frame, driven by a seed. */
    private fun art(seed: Int, hueShift: Float = 0f): Raster {
        val rnd = Random(seed)
        val r = Raster(126, 176)
        r.fill(0, 0, 126, 176, rgb(200, 180, 60))
        r.fill(8, 20, 118, 100, rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        repeat(6) {
            r.oval(18 + rnd.nextInt(90), 30 + rnd.nextInt(60), 10 + rnd.nextInt(15), 10 + rnd.nextInt(15),
                hsv((rnd.nextFloat() * 360 + hueShift) % 360, 0.8f, 0.5f + rnd.nextFloat() * 0.5f))
        }
        r.fill(8, 108, 118, 168, rgb(240, 240, 230))
        return r
    }

    /** What the phone sees: card slightly off-size in the guide, tilted, shifted, darker, noisy, on a table. */
    private fun photo(card: Raster, scale: Double, angleDeg: Double, dx: Int, gain: Double, seed: Int = 1): Raster {
        val w = (card.w * ArtMatch.CAMERA_EXPAND).toInt(); val h = (card.h * ArtMatch.CAMERA_EXPAND).toInt()
        val out = Raster(w, h)
        val a = Math.toRadians(angleDeg); val ca = cos(a); val sa = sin(a)
        val rnd = Random(seed)
        for (y in 0 until h) for (x in 0 until w) {
            // Inverse map: output pixel → card pixel.
            val ox = x - w / 2.0 - dx; val oy = y - h / 2.0
            val cx = (ca * ox + sa * oy) / scale + card.w / 2.0
            val cy = (-sa * ox + ca * oy) / scale + card.h / 2.0
            val p = if (cx >= 0 && cy >= 0 && cx < card.w - 1 && cy < card.h - 1) card[cx.toInt(), cy.toInt()] else rgb(70, 55, 45)
            fun ch(v: Int) = (v * gain + 15 + rnd.nextInt(-12, 13)).toInt()
            out.px[y * w + x] = rgb(ch((p shr 16) and 0xFF), ch((p shr 8) and 0xFF), ch(p and 0xFF))
        }
        return out
    }

    private fun thumb(img: Raster, cols: Int = 36, rows: Int = 50) = CardThumb.sample(cols, rows) { fx, fy ->
        var r = 0; var g = 0; var b = 0; var n = 0
        for (j in -1..1) for (i in -1..1) {
            val x = ((fx + i * 0.3f / cols) * img.w).toInt().coerceIn(0, img.w - 1)
            val y = ((fy + j * 0.3f / rows) * img.h).toInt().coerceIn(0, img.h - 1)
            val p = img[x, y]
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        rgb(r / n, g / n, b / n)
    }

    private fun rankPhoto(photo: Raster, refs: Map<String, Raster>) =
        ArtMatch.rank(ArtMatch.cameraVariants(thumb(photo)), refs.map { (k, v) -> k to ArtMatch.descriptor(thumb(v)) })

    @Test fun picksTheRightArtworkDespiteLightingFramingAndNoise() {
        val refs = (1..8).associate { "art$it" to art(it) }
        for ((i, target) in listOf("art3", "art6", "art8").withIndex()) {
            val ranked = rankPhoto(photo(refs.getValue(target), scale = 0.95 + i * 0.04, angleDeg = 2.0 - i * 2, dx = i * 2 - 2, gain = 0.65 + i * 0.2), refs)
            assertEquals(target, ranked.first().first)
            assertTrue("decisive for $target: ${ranked.take(3)}", ArtMatch.decisive(ranked))
        }
    }

    @Test fun tellsColourVariantsApart() {
        // Same layout, different colours — like red / blue / gold DON!! cards.
        val refs = mapOf("red" to art(42, 0f), "blue" to art(42, 200f), "green" to art(42, 110f))
        val ranked = rankPhoto(photo(refs.getValue("blue"), 0.97, 1.5, 1, 0.8), refs)
        assertEquals("blue", ranked.first().first)
    }

    @Test fun flatOrMissingPicturesDontCrash() {
        val flat = CardThumb.sample(36, 50) { _, _ -> 0x808080 }
        val d = ArtMatch.descriptor(flat)
        assertTrue(d.all { it == 0f })
        assertEquals(0f, ArtMatch.rank(ArtMatch.cameraVariants(flat), listOf("x" to d)).single().second)
        assertTrue(!ArtMatch.decisive(emptyList<Pair<String, Float>>()))
    }

    /** Loads a PNG through the desktop JVM's ImageIO by reflection (not on the Android compile classpath). */
    private fun loadPng(url: String): Raster {
        val io = Class.forName("javax.imageio.ImageIO")
        val img = io.getMethod("read", URL::class.java).invoke(null, URL(url))
        val cls = img.javaClass
        val w = cls.getMethod("getWidth").invoke(img) as Int
        val h = cls.getMethod("getHeight").invoke(img) as Int
        val getRgb = cls.getMethod("getRGB", Int::class.java, Int::class.java)
        val r = Raster(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = getRgb.invoke(img, x, y) as Int
            r.px[y * w + x] = if ((p ushr 24) < 128) rgb(70, 55, 45) else p and 0xFFFFFF
        }
        return r
    }

    /** Real catalog scans: the three Charizard ex printings in Pokémon 151 (+ Venusaur ex decoys). LIVE_API=1 */
    @Test fun realCardsSameNameDifferentArt() {
        assumeTrue(System.getenv("LIVE_API") == "1")
        val ids = listOf("006", "183", "199", "003", "182", "198")
        val refs = ids.associateWith { loadPng("https://assets.tcgdex.net/en/sv/sv03.5/$it/low.png") }
        var correct = 0
        val trials = mutableListOf<String>()
        for ((i, target) in listOf("006", "183", "199").withIndex()) for (k in 0..2) {
            val p = photo(refs.getValue(target), scale = 0.92 + 0.05 * k, angleDeg = -3.0 + 3 * k, dx = (k - 1) * 3, gain = 0.6 + 0.2 * k, seed = i * 10 + k)
            val ranked = rankPhoto(p, refs)
            trials += "$target→${ranked.first().first} (%.2f vs %.2f%s)".format(ranked[0].second, ranked[1].second, if (ArtMatch.decisive(ranked)) ", decisive" else "")
            if (ranked.first().first == target) correct++
        }
        println("real-card artwork matching: $correct/9 correct — $trials")
        assertTrue("$correct/9 — $trials", correct >= 8)
    }
}
