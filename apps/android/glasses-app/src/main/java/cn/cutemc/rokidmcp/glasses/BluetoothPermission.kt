package cn.cutemc.rokidmcp.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal object BluetoothPermission {
    const val requiredPermission: String = Manifest.permission.BLUETOOTH_CONNECT

    fun hasRequiredPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            requiredPermission,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
