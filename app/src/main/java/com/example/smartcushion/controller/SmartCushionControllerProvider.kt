package com.example.smartcushion.controller

import android.content.Context

object SmartCushionControllerProvider {
    @Volatile
    private var controller: SmartCushionController? = null

    fun get(context: Context): SmartCushionController =
        controller ?: synchronized(this) {
            controller ?: SmartCushionController(context.applicationContext).also {
                controller = it
            }
        }
}
