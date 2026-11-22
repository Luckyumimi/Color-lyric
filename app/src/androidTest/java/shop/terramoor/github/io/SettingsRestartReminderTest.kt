package shop.terramoor.github.io

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.SettingsRestartReminder

@RunWith(AndroidJUnit4::class)
class SettingsRestartReminderTest {
    @Test fun pendingReminderSurvivesAppRestartButNotDeviceReboot() = withPreferences { prefs ->
        val initial = CapsuleSettings.read(prefs)
        val changed = initial.copy(showCover = !initial.showCover)
        val reminder = SettingsRestartReminder(prefs, 4)
        reminder.startTuning()
        reminder.settingsCommitted(initial, changed)
        assertFalse(reminder.visible.value)
        reminder.finishTuning()
        assertTrue(reminder.visible.value)
        assertTrue(SettingsRestartReminder(prefs, 4).visible.value)
        assertFalse(SettingsRestartReminder(prefs, 5).visible.value)

        val appliedRevision = reminder.revision
        reminder.settingsCommitted(changed, changed.copy(showWaveform = !changed.showWaveform))
        reminder.systemUiRestarted(appliedRevision)
        assertTrue(reminder.visible.value)
        reminder.systemUiRestarted(reminder.revision)
        assertFalse(reminder.visible.value)
        assertFalse(SettingsRestartReminder(prefs, 4).visible.value)
    }

    @Test fun restoringOneSettingKeepsReminderUntilAllChangesAreUndone() = withPreferences { prefs ->
        val initial = CapsuleSettings.read(prefs)
        val coverChanged = initial.copy(showCover = !initial.showCover)
        val widthChanged = initial.withTuning(mapOf(CapsuleTuning.ISLAND_WIDTH to 174))
        val bothChanged = widthChanged.copy(showCover = coverChanged.showCover)
        val reminder = SettingsRestartReminder(prefs, 4)
        reminder.settingsCommitted(initial, coverChanged)
        reminder.settingsCommitted(coverChanged, bothChanged)
        reminder.settingsCommitted(bothChanged, widthChanged)
        assertTrue(reminder.visible.value)

        val resumed = SettingsRestartReminder(prefs, 4)
        resumed.startTuning()
        resumed.settingsCommitted(widthChanged, initial)
        assertTrue(resumed.visible.value)
        resumed.finishTuning()
        assertFalse(resumed.visible.value)
        assertFalse(SettingsRestartReminder(prefs, 4).visible.value)
    }

    @Test fun reconnectClearsAnAlreadyRestoredConfiguration() = withPreferences { prefs ->
        val initial = CapsuleSettings.read(prefs)
        val reminder = SettingsRestartReminder(prefs, 4)
        reminder.settingsCommitted(initial, initial)
        assertFalse(reminder.visible.value)
        reminder.settingsCommitted(initial, initial.copy(cameraFade = !initial.cameraFade))

        val resumed = SettingsRestartReminder(prefs, 4)
        assertTrue(resumed.visible.value)
        resumed.reconcile(initial)
        assertFalse(resumed.visible.value)
    }

    private fun withPreferences(test: (SharedPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "restart-reminder-test"
        context.deleteSharedPreferences(name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            test(prefs)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
