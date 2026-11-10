package com.garathon.flatdroid.settings

import android.content.Context
import android.net.ConnectivityManager
import com.garathon.flatdroid.setup.SetupController

/**
 * What a download does now: the mobile data setting on a metered connection, and always otherwise.
 * [setting] is the setting when the caller has it loaded already; else it is read from disk.
 */
fun mobileDataPolicy(context: Context, setting: MobileData? = null): MobileData {
    val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
    return if (metered) setting ?: SetupController.settings(context).load()?.mobileData ?: MobileData.ASK else MobileData.ALWAYS
}
