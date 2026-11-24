package com.tempgmail.app

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Material You dynamic color (Android 12+) + app-wide init. */
class TempGmailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
