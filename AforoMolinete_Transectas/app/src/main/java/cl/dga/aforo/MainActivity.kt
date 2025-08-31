
package cl.dga.aforo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.dga.aforo.export.Exporter

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBtPerms()
        vm.refreshPaired()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    TransectaScreen(vm, onExport = {
                        val exporter = Exporter(this)
                        exporter.exportCsv(vm.transecta.value, vm.calib.value)
                        exporter.exportPdf(vm.transecta.value, vm.calib.value)
                    })
                }
            }
        }
    }

    private fun requestBtPerms() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permLauncher.launch(perms.toTypedArray())
    }
}
