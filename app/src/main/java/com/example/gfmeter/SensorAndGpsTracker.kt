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
import kotlin.math.abs
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

    // Kalibroitu painovoimavektori (puhelimen koordinaatistossa)
    private var gravityBaseline: FloatArray? = null
    private var lastRawAccel = FloatArray(3)
    private var prevRawAccel = FloatArray(3)
    private var hasPrevSample = false
    private var ignorePeakUntilNanos: Long = 0L

    // Automaattisen kalibroinnin vakaustarkkailu
    private var steadySinceNanos: Long = 0L
    private var steadySum = FloatArray(3)
    private var steadyCount = 0

    // Alipäästösuodatetut arvot (m/s^2)
    private var filteredX = 0f
    private var filteredY = 0f
    private val smoothingAlpha = 0.18f

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
                    200L,
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
     * Manuaalinen pikanollaus nykyiseen asentoon.
     */
    fun calibrateZero() {
        val rawLen = length(lastRawAccel)
        if (rawLen in 7.5f..12.0f) {
            gravityBaseline = lastRawAccel.copyOf()
            filteredX = 0f
            filteredY = 0f
            steadyCount = 0
            steadySinceNanos = 0L
            ignorePeakUntilNanos = SystemClock.elapsedRealtimeNanos() + 450_000_000L
            _state.value = _state.value.copy(
                screenAccelX = 0f,
                screenAccelY = 0f,
                magnitudeMps2 = 0f,
                isCalibrated = true
            )
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
     * Nollaa 0–100 km/h ajastimen uutta kiihtyvyyskoetta varten.
     */
    fun resetSprint() {
        stationarySinceNanos = 0L
        sprintStartNanos = 0L
        val current = _state.value
        val nextState = if (current.speedKmh < 2.0f) {
            SprintState.READY
        } else {
            SprintState.WAITING_FOR_STOP
        }
        _state.value = current.copy(
            sprintState = nextState,
            sprintTimeSec = 0f
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val raw = event.values.copyOf()
        val rawLen = length(raw)

        // Hylätään epärealistiset pudotus-/iskupiikit (> 3.5 g)
        if (rawLen > 35.0f || rawLen < 1.0f) return

        val jitter = if (hasPrevSample) {
            sqrt(
                (raw[0] - prevRawAccel[0]) * (raw[0] - prevRawAccel[0]) +
                    (raw[1] - prevRawAccel[1]) * (raw[1] - prevRawAccel[1]) +
                    (raw[2] - prevRawAccel[2]) * (raw[2] - prevRawAccel[2])
            )
        } else {
            0f
        }

        prevRawAccel = raw.copyOf()
        lastRawAccel = raw.copyOf()
        hasPrevSample = true

        val current = _state.value
        val isGpsStopped = !current.hasGpsFix || current.speedKmh < 2.0f
        val isPhysicallySteady = (rawLen in 8.3f..11.3f) && (jitter < 0.35f) && isGpsStopped

        // AUTOMAATTINEN KALIBROINTI:
        // Kun puhelin/auto on vakaasti paikallaan, kerätään keskiarvo ja päivitetään nollakohta.
        if (isPhysicallySteady) {
            if (steadySinceNanos == 0L) {
                steadySinceNanos = nowNanos
                steadySum = floatArrayOf(raw[0], raw[1], raw[2])
                steadyCount = 1
            } else {
                steadySum[0] += raw[0]
                steadySum[1] += raw[1]
                steadySum[2] += raw[2]
                steadyCount++

                val steadyDurationMs = (nowNanos - steadySinceNanos) / 1_000_000L

                // Alkukalibrointi 350 ms vakauden jälkeen tai uudelleenkalibrointi 1500 ms jälkeen
                if ((gravityBaseline == null && steadyDurationMs >= 350L) ||
                    (gravityBaseline != null && steadyDurationMs >= 1500L)
                ) {
                    val avgGravity = floatArrayOf(
                        steadySum[0] / steadyCount,
                        steadySum[1] / steadyCount,
                        steadySum[2] / steadyCount
                    )
                    val prevBase = gravityBaseline
                    val baseShift = if (prevBase != null) {
                        sqrt(
                            (avgGravity[0] - prevBase[0]) * (avgGravity[0] - prevBase[0]) +
                                (avgGravity[1] - prevBase[1]) * (avgGravity[1] - prevBase[1]) +
                                (avgGravity[2] - prevBase[2]) * (avgGravity[2] - prevBase[2])
                        )
                    } else {
                        99f
                    }

                    gravityBaseline = avgGravity
                    // Jos telineen kulma oli muuttunut selvästi, nollataan suodatetut arvot heti
                    if (baseShift > 0.6f) {
                        filteredX = 0f
                        filteredY = 0f
                        ignorePeakUntilNanos = nowNanos + 400_000_000L
                    }
                    _state.value = _state.value.copy(isCalibrated = true)

                    // Nollataan laskuri seuraavaa automaattista tarkistusta varten
                    steadySinceNanos = nowNanos
                    steadySum = floatArrayOf(raw[0], raw[1], raw[2])
                    steadyCount = 1
                }
            }
        } else {
            steadySinceNanos = 0L
            steadyCount = 0
        }

        // Hätävarakalibrointi, jos sovellus avattiin kesken ajon eikä auto ole vielä pysähtynyt
        if (gravityBaseline == null) {
            val norm = normalize(raw) ?: return
            gravityBaseline = floatArrayOf(norm[0] * 9.80665f, norm[1] * 9.80665f, norm[2] * 9.80665f)
            ignorePeakUntilNanos = nowNanos + 500_000_000L
        }

        val g0 = gravityBaseline ?: return
        val gNorm = normalize(g0) ?: return

        // Dynaaminen kiihtyvyys (ilman painovoimaa)
        val dynAccel = floatArrayOf(
            raw[0] - g0[0],
            raw[1] - g0[1],
            raw[2] - g0[2]
        )

        // Näytön oikea reuna puhelimen koordinaatistossa
        val screenRightRaw = getScreenRightVectorInPhoneCoords()

        // Projisoidaan vaakatasoon (kohtisuoraan painovoimaa vastaan)
        val dotRightUp = dot(screenRightRaw, gNorm)
        val horizRightRaw = floatArrayOf(
            screenRightRaw[0] - dotRightUp * gNorm[0],
            screenRightRaw[1] - dotRightUp * gNorm[1],
            screenRightRaw[2] - dotRightUp * gNorm[2]
        )
        val eRight = normalize(horizRightRaw) ?: return

        // Eteenpäin (tuulilasiin päin) osoittava vaakasuora yksikkövektori
        val eForward = cross(gNorm, eRight)

        // Auton kiihtyvyyskomponentit:
        // accelRight > 0 kun auto kääntyy/kiihtyy oikealle
        // accelForward > 0 kun auto kiihdyttää eteenpäin
        val accelRight = dot(dynAccel, eRight)
        val accelForward = dot(dynAccel, eForward)

        // G-voiman (inertiaali) suunta näytöllä:
        // - Käännös oikealle (accelRight > 0) -> nuoli VASEN (-X)
        // - Käännös vasemmalle (accelRight < 0) -> nuoli OIKEA (+X)
        // - Kiihdytys eteenpäin (accelForward > 0) -> nuoli ALAS (+Y)
        // - Jarrutus (accelForward < 0) -> nuoli YLÖS (-Y)
        val targetScreenX = -accelRight
        val targetScreenY = accelForward

        filteredX += smoothingAlpha * (targetScreenX - filteredX)
        filteredY += smoothingAlpha * (targetScreenY - filteredY)

        // Kuollut alue paikallaan ollessa (poistaa mikrovärinän)
        val rawMag = sqrt(filteredX * filteredX + filteredY * filteredY)
        val mag = if (rawMag < 0.35f) 0f else rawMag

        val latest = _state.value

        // Päivitetään huippuarvo (Max G) vain kalibroidussa tilassa
        val newMaxMag = if (
            latest.isCalibrated &&
            nowNanos > ignorePeakUntilNanos &&
            mag > latest.maxMagnitudeMps2
        ) {
            mag
        } else {
            latest.maxMagnitudeMps2
        }

        // 0–100 km/h ajastimen päivitys
        var updatedSprintState = latest.sprintState
        var updatedSprintTime = latest.sprintTimeSec

        when (latest.sprintState) {
            SprintState.READY -> {
                // Käynnistetään kello heti kun auto lähtee kiihdyttämään eteenpäin
                if (accelForward > 1.35f) {
                    updatedSprintState = SprintState.RUNNING
                    sprintStartNanos = nowNanos
                    updatedSprintTime = 0f
                }
            }

            SprintState.RUNNING -> {
                val elapsedSec = ((nowNanos - sprintStartNanos) / 1_000_000_000.0).toFloat()
                if (elapsedSec > 2.0f && latest.speedKmh < 3.0f) {
                    updatedSprintState = SprintState.READY
                    updatedSprintTime = 0f
                } else {
                    updatedSprintTime = elapsedSec
                }
            }

            else -> Unit
        }

        _state.value = latest.copy(
            screenAccelX = if (mag == 0f) 0f else filteredX,
            screenAccelY = if (mag == 0f) 0f else filteredY,
            magnitudeMps2 = mag,
            maxMagnitudeMps2 = newMaxMag,
            sprintState = updatedSprintState,
            sprintTimeSec = updatedSprintTime
        )
    }

    override fun onLocationChanged(location: Location) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val rawSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
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
                        updatedSprintState = SprintState.READY
                        updatedSprintTime = 0f
                    }
                } else {
                    stationarySinceNanos = 0L
                }
            }

            SprintState.READY -> {
                if (speedKmh >= 2.5f) {
                    updatedSprintState = SprintState.RUNNING
                    sprintStartNanos = if (prevGpsNanos > 0L) prevGpsNanos else nowNanos
                    updatedSprintTime = ((nowNanos - sprintStartNanos) / 1_000_000_000.0).toFloat()
                }
            }

            SprintState.RUNNING -> {
                if (speedKmh >= 100.0f) {
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
                    updatedSprintState = SprintState.READY
                    updatedSprintTime = 0f
                }
            }

            SprintState.FINISHED -> Unit
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

    /**
     * Palauttaa näytön oikealle osoittavan yksikkövektorin puhelimen anturikoordinaatistossa:
     * - ROTATION_90 (normaali vaakataso, latausportti oikealla): -Y
     * - ROTATION_270 (käänteinen vaakataso, latausportti vasemmalla): +Y
     */
    private fun getScreenRightVectorInPhoneCoords(): FloatArray {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }

        return when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(0f, 1f, 0f)
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
