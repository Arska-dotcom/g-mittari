package com.example.gfmeter

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

enum class SprintState {
    WAITING_FOR_STOP, // Auto liikkuu tai GPS ei ole vielä lukittunut
    READY,            // Auto paikallaan -> valmis 0–100 km/h lähtöön
    RUNNING,          // Kiihdytys käynnissä
    FINISHED          // 100 km/h saavutettu, aika lukittu
}

data class TelemetryState(
    val screenAccelX: Float = 0f,      // m/s^2: positiivinen = oikealle näytöllä
    val screenAccelY: Float = 0f,      // m/s^2: positiivinen = alaspäin näytöllä
    val magnitudeMps2: Float = 0f,     // Hetkellinen resultanttikiihtyvyys (m/s^2)
    val maxMagnitudeMps2: Float = 0f,  // Huippuarvo Max G (m/s^2)
    val speedKmh: Float = 0f,          // GPS-nopeus (km/h)
    val hasGpsFix: Boolean = false,
    val isCalibrated: Boolean = false,
    val sprintState: SprintState = SprintState.WAITING_FOR_STOP,
    val sprintTimeSec: Float = 0f      // 0–100 km/h kulunut tai lopullinen aika (s)
)

class SensorAndGpsTracker(private val context: Context) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _state = MutableStateFlow(TelemetryState())
    val state: StateFlow<TelemetryState> = _state.asStateFlow()

    // Kalibroitu painovoimavektori
    private var gravityBaseline: FloatArray? = null
    private var lastRawAccel = FloatArray(3)
    private var ignorePeakUntilNanos: Long = 0L

    // Alipäästösuodatetut arvot (m/s^2)
    private var filteredX = 0f
    private var filteredY = 0f
    private val smoothingAlpha = 0.16f

    // 0–100 km/h ajastimen sisäiset muuttujat
    private var stationarySinceNanos: Long = 0L
    private var sprintStartNanos: Long = 0L
    private var prevGpsSpeedKmh: Float = 0f
    private var prevGpsNanos: Long = 0L

    fun startSensors() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    fun startGps() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    200L, // Päivitysväli 200 ms (5 Hz)
                    0f,
                    this
                )
            }
        } catch (_: SecurityException) {
            // Lupa puuttuu
        }
    }

    fun stopGps() {
        locationManager.removeUpdates(this)
    }

    /**
     * Tallentaa nykyisen asennon nollakohdaksi (painovoimavektori).
     */
    fun calibrateZero() {
        if (length(lastRawAccel) > 1f) {
            gravityBaseline = lastRawAccel.copyOf()
            filteredX = 0f
            filteredY = 0f
            // Ohitetaan 400 ms napin painalluksen aiheuttama tärähdys
            ignorePeakUntilNanos = SystemClock.elapsedRealtimeNanos() + 400_000_000L
            _state.value = _state.value.copy(isCalibrated = true)
        }
    }

    /**
     * Nollaa tallennetun huippu-G-arvon.
     */
    fun resetMaxG() {
        ignorePeakUntilNanos = SystemClock.elapsedRealtimeNanos() + 300_000_000L
        _state.value = _state.value.copy(maxMagnitudeMps2 = 0f)
    }

    /**
     * Nollaa 0–100 km/h ajastimen uutta vetoa varten.
     */
    fun resetSprint() {
        stationarySinceNanos = 0L
        sprintStartNanos = 0L
        val nextState = if (_state.value.hasGpsFix && _state.value.speedKmh < 2.0f) {
            SprintState.READY
        } else {
            SprintState.WAITING_FOR_STOP
        }
        _state.value = _state.value.copy(
            sprintState = nextState,
            sprintTimeSec = 0f
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        lastRawAccel = event.values.copyOf()

        // Automaattinen alkukalibrointi ensimmäisestä näytteestä
        if (gravityBaseline == null && length(lastRawAccel) > 1f) {
            gravityBaseline = lastRawAccel.copyOf()
            ignorePeakUntilNanos = nowNanos + 500_000_000L
            _state.value = _state.value.copy(isCalibrated = true)
        }

        val g0 = gravityBaseline ?: return
        val gNorm = normalize(g0) ?: return

        // Dynaaminen kiihtyvyys (ilman painovoimaa)
        val dynAccel = floatArrayOf(
            lastRawAccel[0] - g0[0],
            lastRawAccel[1] - g0[1],
            lastRawAccel[2] - g0[2]
        )

        val screenRightRaw = getScreenRightVectorInPhoneCoords()
        val dotRightUp = dot(screenRightRaw, gNorm)
        val horizRightRaw = floatArrayOf(
            screenRightRaw[0] - dotRightUp * gNorm[0],
            screenRightRaw[1] - dotRightUp * gNorm[1],
            screenRightRaw[2] - dotRightUp * gNorm[2]
        )
        val eRight = normalize(horizRightRaw) ?: return
        val eForward = cross(gNorm, eRight)

        val accelRight = dot(dynAccel, eRight)
        val accelForward = dot(dynAccel, eForward)

        // Näytön koordinaatit (inertiaali- / G-voimasuunta)
        val targetScreenX = -accelRight
        val targetScreenY = accelForward

        filteredX += smoothingAlpha * (targetScreenX - filteredX)
        filteredY += smoothingAlpha * (targetScreenY - filteredY)

        val mag = sqrt(filteredX * filteredX + filteredY * filteredY)
        val current = _state.value

        // Päivitetään huippuarvo (Max G)
        val newMaxMag = if (nowNanos > ignorePeakUntilNanos && mag > current.maxMagnitudeMps2) {
            mag
        } else {
            current.maxMagnitudeMps2
        }

        // 0–100 km/h ajastimen päivitys kiihtyvyysanturin tahdissa (~50 Hz)
        var updatedSprintState = current.sprintState
        var updatedSprintTime = current.sprintTimeSec

        when (current.sprintState) {
            SprintState.READY -> {
                // Käynnistetään kello välittömästi kun auto lähtee kiihdyttämään eteenpäin (> 1.35 m/s^2)
                if (accelForward > 1.35f) {
                    updatedSprintState = SprintState.RUNNING
                    sprintStartNanos = nowNanos
                    updatedSprintTime = 0f
                }
            }

            SprintState.RUNNING -> {
                val elapsedSec = ((nowNanos - sprintStartNanos) / 1_000_000_000.0).toFloat()
                // Virhelähdön esto: jos 2.0 s kohdalla GPS-nopeus on yhä alle 3 km/h, palataan READY-tilaan
                if (elapsedSec > 2.0f && current.speedKmh < 3.0f) {
                    updatedSprintState = SprintState.READY
                    updatedSprintTime = 0f
                } else {
                    updatedSprintTime = elapsedSec
                }
            }

            else -> Unit
        }

        _state.value = current.copy(
            screenAccelX = filteredX,
            screenAccelY = filteredY,
            magnitudeMps2 = mag,
            maxMagnitudeMps2 = newMaxMag,
            sprintState = updatedSprintState,
            sprintTimeSec = updatedSprintTime
        )
    }

    override fun onLocationChanged(location: Location) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val rawSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        // Suodatetaan GPS:n paikallaanolo-kohina (< 1.2 km/h -> 0.0 km/h)
        val speedKmh = if (rawSpeedKmh < 1.2f) 0f else rawSpeedKmh

        val current = _state.value
        var updatedSprintState = current.sprintState
        var updatedSprintTime = current.sprintTimeSec

        when (current.sprintState) {
            SprintState.WAITING_FOR_STOP -> {
                if (speedKmh < 2.0f) {
                    if (stationarySinceNanos == 0L) {
                        stationarySinceNanos = nowNanos
                    } else if (nowNanos - stationarySinceNanos >= 1_000_000_000L) {
                        // Auto on ollut paikallaan 1.0 sekunnin -> valmis lähtöön
                        updatedSprintState = SprintState.READY
                        updatedSprintTime = 0f
                    }
                } else {
                    stationarySinceNanos = 0L
                }
            }

            SprintState.READY -> {
                if (speedKmh >= 2.5f) {
                    // Varmistuskäynnistys GPS:stä, jos kiihtyvyys oli hyvin loiva
                    updatedSprintState = SprintState.RUNNING
                    sprintStartNanos = if (prevGpsNanos > 0L) prevGpsNanos else nowNanos
                    updatedSprintTime = ((nowNanos - sprintStartNanos) / 1_000_000_000.0).toFloat()
                }
            }

            SprintState.RUNNING -> {
                if (speedKmh >= 100.0f) {
                    // Lineaarinen interpolaatio tarkalle 100.0 km/h ylityshetkelle
                    val exactCrossNanos = if (
                        prevGpsNanos > sprintStartNanos &&
                        speedKmh > prevGpsSpeedKmh &&
                        prevGpsSpeedKmh < 100.0f
                    ) {
                        val fraction = (100.0f - prevGpsSpeedKmh) / (speedKmh - prevGpsSpeedKmh)
                        prevGpsNanos + ((nowNanos - prevGpsNanos) * fraction).toLong()
                    } else {
                        nowNanos
                    }

                    updatedSprintTime = ((exactCrossNanos - sprintStartNanos) / 1_000_000_000.0).toFloat()
                    updatedSprintState = SprintState.FINISHED
                } else if (speedKmh < 1.5f && (nowNanos - sprintStartNanos) > 2_500_000_000L) {
                    // Kiihdytys keskeytettiin ja auto pysähtyi uudelleen
                    updatedSprintState = SprintState.READY
                    updatedSprintTime = 0f
                }
            }

            SprintState.FINISHED -> {
                // Tulos pysyy näytöllä lukittuna, kunnes käyttäjä painaa Nollaa
            }
        }

        prevGpsSpeedKmh = speedKmh
        prevGpsNanos = nowNanos

        _state.value = current.copy(
            speedKmh = speedKmh,
            hasGpsFix = true,
            sprintState = updatedSprintState,
            sprintTimeSec = updatedSprintTime
        )
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            _state.value = _state.value.copy(hasGpsFix = false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun getScreenRightVectorInPhoneCoords(): FloatArray {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }

        return when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(0f, 1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f)
            else -> floatArrayOf(1f, 0f, 0f)
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun length(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalize(v: FloatArray): FloatArray? {
        val len = length(v)
        return if (len > 1e-4f) floatArrayOf(v[0] / len, v[1] / len, v[2] / len) else null
    }
}
