package com.toolbox.smartcleaner.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理器广播接收器
 * 用于 Device Owner 功能，提供执行系统级操作的能力
 * 对外伪装为"系统管理"组件
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin 已启用")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin 已禁用")
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        Log.d(TAG, "Password changed")
    }
}