package com.build.mythos

import android.app.Application

/** Memasang pencatat crash sedini mungkin, sebelum Activity mana pun dibuat. */
class MythosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
