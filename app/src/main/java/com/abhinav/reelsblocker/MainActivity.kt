package com.abhinav.reelsblocker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.abhinav.reelsblocker.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: BlockerPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = BlockerPreferences(this)

        binding.switchBlocker.isChecked = preferences.isBlockerEnabled
        binding.switchBlocker.setOnCheckedChangeListener { _, checked ->
            preferences.isBlockerEnabled = checked
            refreshStatus()
            if (checked && !AccessibilityStatus.isReelsBlockerEnabled(this)) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.hint_enable_service),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }

        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val serviceOn = AccessibilityStatus.isReelsBlockerEnabled(this)
        val blockerOn = preferences.isBlockerEnabled
        binding.status.text = when {
            !serviceOn -> getString(R.string.status_service_off)
            !blockerOn -> getString(R.string.status_blocker_paused)
            else -> getString(R.string.status_service_on)
        }
    }
}
