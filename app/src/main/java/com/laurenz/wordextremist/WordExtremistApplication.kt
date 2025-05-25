package com.laurenz.wordextremist

import android.app.Application
import android.util.Log
import com.laurenz.wordextremist.network.ApiClient // Import your ApiClient

class WordExtremistApplication : Application() {
    override fun onCreate() {
        super.onCreate() // Always call the superclass's onCreate first

        Log.i("MyApplication", "onCreate called - Initializing ApiClient...")
        ApiClient.initialize(this) // 'this' refers to the Application context
        Log.i("MyApplication", "ApiClient initialization attempted from MyApplication.")
    }
}