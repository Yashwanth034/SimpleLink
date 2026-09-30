package com.simplelink.app

import android.app.Application
import com.simplelink.app.session.SessionController

class SimpleLinkApp : Application() {
    lateinit var sessionController: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        sessionController = SessionController(this)
    }
}
