package com.tazawa.snapcal

import android.app.Application
import com.tazawa.snapcal.processing.RecoveryScheduler

class SnapCalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RecoveryScheduler.schedule(this)
    }
}
