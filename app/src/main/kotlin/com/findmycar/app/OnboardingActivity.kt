package com.findmycar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Friendly onboarding screens shown before scary system permission dialogs.
 *
 * Flow:
 * 1. Location permission explainer → system location dialog
 * 2. Background location explainer → system "Allow all the time" dialog
 * 3. Battery optimization explainer → system battery exemption dialog
 * 4. Done → navigate to MainActivity
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var emoji: TextView
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var nextButton: MaterialButton

    private var currentStep = 0

    private val steps = listOf(
        OnboardingStep(
            emoji = "📍",
            title = "We need your location",
            message = "To remember where you parked, we need access to your location.\n\nDon't worry — we only use it to save your parking spot.",
            buttonText = "Allow Location"
        ),
        OnboardingStep(
            emoji = "🚶",
            title = "Count your steps",
            message = "We use the step counter to know when you've walked away from your car.\n\nThis helps us save the exact parking spot at the right moment.",
            buttonText = "Allow"
        ),
        OnboardingStep(
            emoji = "🔄",
            title = "Location access all the time",
            message = "To detect when you leave your car, the app needs location access all the time — even when the screen is off.\n\nPlease select \"Allow all the time\" on the next screen.",
            buttonText = "Continue"
        ),
        OnboardingStep(
            emoji = "🔋",
            title = "Almost no battery usage",
            message = "This app uses almost no battery.\n\nTo ensure your parking place is recorded, please allow the following request.",
            buttonText = "Allow"
        )
    )

    private val requestFineLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            advanceStep()
        } else {
            advanceStep()
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        advanceStep()
    }

    private val requestActivityRecognition = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        advanceStep()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        emoji = findViewById(R.id.onboardingEmoji)
        title = findViewById(R.id.onboardingTitle)
        message = findViewById(R.id.onboardingMessage)
        nextButton = findViewById(R.id.onboardingNextButton)

        nextButton.setOnClickListener { onNextClicked() }

        // Skip steps where permission is already granted
        skipGrantedSteps()
        if (currentStep >= steps.size) {
            finishOnboarding()
            return
        }
        showStep(currentStep)
    }

    private fun skipGrantedSteps() {
        // 0: Fine location
        if (currentStep == 0 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            currentStep = 1
        }
        // 1: Activity recognition (step counter)
        if (currentStep == 1) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                currentStep = 2
            }
        }
        // 2: Background location ("Allow all the time")
        if (currentStep == 2) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                currentStep = 3
            }
        }
        // 3: Battery optimization
        if (currentStep == 3) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                currentStep = 4
            }
        }
    }

    private fun showStep(step: Int) {
        if (step >= steps.size) {
            finishOnboarding()
            return
        }
        val s = steps[step]
        emoji.text = s.emoji
        title.text = s.title
        message.text = s.message
        nextButton.text = s.buttonText
    }

    private fun onNextClicked() {
        when (currentStep) {
            0 -> requestFineLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            1 -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    requestActivityRecognition.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                } else {
                    advanceStep()
                }
            }
            2 -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    advanceStep()
                }
            }
            3 -> {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {}
                advanceStep()
            }
            else -> finishOnboarding()
        }
    }

    private fun advanceStep() {
        currentStep++
        skipGrantedSteps()
        if (currentStep >= steps.size) {
            finishOnboarding()
        } else {
            showStep(currentStep)
        }
    }

    private fun finishOnboarding() {
        // Mark onboarding as complete
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true)
            .apply()

        // Start the service now that permissions are granted
        ExitDetectionService.start(this)

        // Go to main app
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private data class OnboardingStep(
        val emoji: String,
        val title: String,
        val message: String,
        val buttonText: String
    )
}
