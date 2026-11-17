package dev.anilbeesetti.nextplayer.core.common.extensions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 是否已获得「所有文件访问权限」。
 * Android 11 (R) 及以上用 MANAGE_EXTERNAL_STORAGE 判断，更低版本回退到传统存储读取权限。
 */
fun Context.hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
} else {
    checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

/**
 * 跳转到系统的「所有文件访问权限」授予页面。
 * Android 11 以下没有这个权限，直接忽略。
 */
fun Context.openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val appIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:$packageName"),
    )
    val intent = if (appIntent.resolveActivity(packageManager) != null) {
        appIntent
    } else {
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // 部分设备（如部分电视盒子）没有该设置页，忽略即可
    }
}
