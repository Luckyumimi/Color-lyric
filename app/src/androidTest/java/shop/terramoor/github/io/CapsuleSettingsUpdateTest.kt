package shop.terramoor.github.io

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleSettingsUpdate
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricTintMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CapsuleSettingsUpdateTest {
    @Test
    fun committedModeSwitchAndSliderValuesReachTheReceiverWithoutRemoteCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updates = LinkedBlockingQueue<CapsuleSettings>()
        val receiver = CapsuleSettingsUpdate.register(context, context.packageName, Handler(Looper.getMainLooper())) {
            updates.add(it)
        }
        try {
            val texture = CapsuleSettings(lyricTintEnabled = true, lyricTintMode = LyricTintMode.TEXTURE)
                .withTuning(CapsuleTuning.entries.associateWith { it.default })
            val gradient = texture.copy(lyricTintMode = LyricTintMode.GRADIENT)
                .withTuning(mapOf(CapsuleTuning.FIRST_ROW_OPACITY to 75))
            for (settings in listOf(texture, gradient)) {
                CapsuleSettingsUpdate.publish(context, settings, targetPackage = context.packageName)
                assertEquals(settings, updates.poll(3, TimeUnit.SECONDS))
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
