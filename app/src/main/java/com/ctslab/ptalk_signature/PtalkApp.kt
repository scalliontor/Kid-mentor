package com.ctslab.ptalk_signature

import android.app.Application

/** Applies the saved Light/Dark/System theme before any Activity is created. */
class PtalkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.apply(this)
    }
}
