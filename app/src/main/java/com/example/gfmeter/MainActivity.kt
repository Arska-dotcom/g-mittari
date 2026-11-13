package com.example.gfmeter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var tracker: SensorAndGpsTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tracker = SensorAndGpsTracker(this)
        val prefs = getSharedPreferences("gfmeter_prefs", Context.MODE_PRIVATE)

        setContent {
            val telemetry by tracker.state.collectAsState()

            var unit by remember {
                val saved = prefs.getString("accel_unit", AccelUnit.G.name)
                mutableStateOf(AccelUnit.valueOf(saved ?: AccelUnit.G.name))
            }

            var hasLocationPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                hasLocationPermission = granted
                if (granted) {
                    tracker.startGps()
                }
            }

            LaunchedEffect(Unit) {
                if (!hasLocationPermission) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                } else {
                    tracker.startGps()
                }
            }

            GForceMeterScreen(
                telemetry = telemetry,
                unit = unit,
                hasLocationPermission = hasLocationPermission,
                onUnitChange = { newUnit ->
                    unit = newUnit
                    prefs.edit().putString("accel_unit", newUnit.name).apply()
                },
                onCalibrate = {
                    tracker.calibrateZero()
                },
                onResetMaxG = {
                    tracker.resetMaxG()
                },
                onResetSprint = {
                    tracker.resetSprint()
                },
                onRequestPermission = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        tracker.startSensors()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            tracker.startGps()
        }
    }

    override fun onPause() {
        super.onPause()
        tracker.stopSensors()
        tracker.stopGps()
    }
}
