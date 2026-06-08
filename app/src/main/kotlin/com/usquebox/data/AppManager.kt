package com.usquebox.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean
)

class AppManager(private val context: Context) {

    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val selfPkg = context.packageName

        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != selfPkg }
            .map { ai ->
                AppInfo(
                    packageName = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                    icon = try { pm.getApplicationIcon(ai) } catch (_: Exception) { null },
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(compareBy({ !it.isSystem }, { it.label.lowercase() }))
    }
}
