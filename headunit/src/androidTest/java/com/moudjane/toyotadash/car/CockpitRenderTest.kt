package com.moudjane.toyotadash.car

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import com.moudjane.toyotadash.core.dash.DashValues
import com.moudjane.toyotadash.core.dash.DemoDrive
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Not a behavioural test: renders the Android Auto cockpit ([CockpitRenderer]) to PNG files so
 * the look can be reviewed without the Desktop Head Unit. Output lands in the app's external
 * files dir as cockpit_<tick>.png (then `adb pull` to view).
 */
class CockpitRenderTest {

    @Test
    fun renderCockpitFrames() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = CockpitRenderer(ctx)
        val dir = ctx.filesDir

        for (target in listOf(40, 120, 200, 300)) {
            // Feed DashHub the run-up ticks so the energy graph has a real last-minute history.
            DashHub.clear()
            for (tick in maxOf(1, target - (DashHub.HISTORY - 1))..target) {
                val f = DemoDrive.frame(tick)
                DashHub.update(DashValues.from(f.standard, f.toyota, 14.1f, f.driveSelect))
            }
            val values = DashHub.values.value ?: continue
            val bmp = Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(bmp), values)
            FileOutputStream(File(dir, "cockpit_$target.png")).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
        }
    }
}
