package com.tinydavid.djiwaypointmission

import android.app.Application
import android.content.Context
import com.secneo.sdk.Helper
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BaseApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Helper.install(this)
    }
}