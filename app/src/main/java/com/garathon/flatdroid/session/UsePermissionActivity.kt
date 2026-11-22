package com.garathon.flatdroid.session

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.garathon.flatdroid.R

/**
 * Allow in a microphone or camera question ([UseQuestion]) when Android's permission isn't given
 * yet, or the session's service doesn't hold its type: Android's own dialog, over whatever is on
 * screen, then the answer to the session, sent while Flatdroid is in front so the service can take
 * the type (Android lets it only then). For the screen, every Allow: Android's screen share
 * dialog, whose consent goes to the service with the answer. Shows nothing of its own; refused,
 * the app gets silence or black frames for the session. Not exported: only the question's
 * notification opens it.
 */
class UsePermissionActivity : ComponentActivity() {
    private lateinit var sensor: Sensor

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> done(granted) }

    private val share = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult done(false)
        SessionService.share(this, result.resultCode, data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensor = intent?.getStringExtra(EXTRA_SENSOR)?.let { name -> Sensor.entries.firstOrNull { it.name == name } } ?: return finish()
        // After a rotation the request in flight answers the new screen.
        if (savedInstanceState != null) return
        val permission = SessionService.permission(sensor)
        when {
            permission == null -> getSystemService(MediaProjectionManager::class.java)?.let { share.launch(it.createScreenCaptureIntent()) } ?: done(false)
            SessionService.hasPermission(this, sensor) -> done(true)
            else -> request.launch(permission)
        }
    }

    private fun done(granted: Boolean) {
        SessionService.answer(this, sensor, granted)
        if (!granted) {
            val refused = when (sensor) {
                Sensor.MICROPHONE -> R.string.app_settings_microphone_android_denied
                Sensor.CAMERA -> R.string.app_settings_camera_android_denied
                Sensor.SCREEN -> R.string.app_settings_screen_android_denied
            }
            Toast.makeText(applicationContext, refused, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val EXTRA_SENSOR = "com.garathon.flatdroid.SENSOR"

        fun intent(context: Context, sensor: Sensor): Intent =
            Intent(context, UsePermissionActivity::class.java).putExtra(EXTRA_SENSOR, sensor.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
