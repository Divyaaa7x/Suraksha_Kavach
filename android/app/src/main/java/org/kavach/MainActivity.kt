package org.kavach

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import org.kavach.ui.AppScreen
import org.kavach.ui.KavachViewModel

class MainActivity : ComponentActivity() {

    private val vm: KavachViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val locationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { result ->
                        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm.requestFix(this@MainActivity)
                        else vm.showMessage("Precise location permission is needed to add a GNSS position to a field report.")
                    }
                    val nearbyPermissions = buildList {
                        if (Build.VERSION.SDK_INT >= 31) addAll(listOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        )) else add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }.toTypedArray()
                    val nearbyLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { result ->
                        if (nearbyPermissions.all { result[it] == true || checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                            vm.startNearby(this@MainActivity, "kavach-" + (1000..9999).random())
                        } else vm.showMessage("Nearby permissions were not granted. Allow nearby devices to exchange reports.")
                    }
                    AppScreen(
                        vm,
                        onRequestLocation = {
                            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                                vm.requestFix(this@MainActivity)
                            else locationLauncher.launch(arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ))
                        },
                        onStartNearby = {
                            if (nearbyPermissions.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
                                vm.startNearby(this@MainActivity, "kavach-" + (1000..9999).random())
                            else nearbyLauncher.launch(nearbyPermissions)
                        },
                    )
                }
            }
        }
    }
}
