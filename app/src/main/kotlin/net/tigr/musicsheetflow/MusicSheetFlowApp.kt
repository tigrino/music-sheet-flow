package net.tigr.musicsheetflow

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MusicSheetFlowApp : Application() {

    companion object {
        init {
            System.loadLibrary("musicsheetflow_native")
        }
    }

    override fun onCreate() {
        super.onCreate()
    }
}
