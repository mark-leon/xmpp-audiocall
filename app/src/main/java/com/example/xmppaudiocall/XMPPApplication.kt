package com.example.xmppaudiocall

import android.app.Application


class XMPPApplication : Application() {

    lateinit var xmppManager: XMPPCallManager

    override fun onCreate() {
        super.onCreate()
    }

    override fun onTerminate() {
        super.onTerminate()
        if (::xmppManager.isInitialized) {
            xmppManager.disconnect()
        }
    }
}