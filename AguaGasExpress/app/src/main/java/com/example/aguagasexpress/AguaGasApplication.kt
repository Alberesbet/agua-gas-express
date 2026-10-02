package com.example.aguagasexpress

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class AguaGasApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:1071136912572:android:477d843eb2dad93ef38cb9")
                .setApiKey("AIzaSyBYqd8kTRq_cV5suBSWK-qTjldaOJm7h-8")
                .setProjectId("agua-e-gas-express")
                .setGcmSenderId("1071136912572")
                .setStorageBucket("agua-e-gas-express.firebasestorage.app")
                .build()
            FirebaseApp.initializeApp(this, options)
        }
    }
}
