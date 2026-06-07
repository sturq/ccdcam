package at.sturq.ccdcam

import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Renders activity_main.xml at multiple device configurations to PNG.
 * CI uploads the snapshots as an artifact so we can verify the layout
 * looks right on phones, foldables and tablets WITHOUT installing on
 * a physical device for each iteration.
 */
class LayoutSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "Theme.CCDCam",
    )

    private fun inflate(): View {
        val themed = ContextThemeWrapper(paparazzi.context, R.style.Theme_CCDCam)
        return paparazzi.layoutInflater.cloneInContext(themed)
            .inflate(R.layout.activity_main, null, false)
    }

    @Test fun phone_portrait() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5)
        paparazzi.snapshot(inflate(), "phone-portrait")
    }

    @Test fun phone_tall() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6_PRO)
        paparazzi.snapshot(inflate(), "phone-tall")
    }

    @Test fun foldable_open() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_C)
        paparazzi.snapshot(inflate(), "tablet")
    }
}
