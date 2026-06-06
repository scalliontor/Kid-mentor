package com.ctslab.kidmentor

import android.app.Application

/** Applies the saved Light/Dark/System theme before any activity is created. */
class KidMentorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.apply(this)
    }
}
