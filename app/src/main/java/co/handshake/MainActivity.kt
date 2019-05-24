package co.handshake

import android.content.Context
import android.hardware.SensorManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {

    lateinit var shakeDetector: ShakeDetector
    lateinit var sensorManager: SensorManager

    private val shakeListener = ShakeDetector.Listener {

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector(shakeListener)

    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start(sensorManager)
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }
}
