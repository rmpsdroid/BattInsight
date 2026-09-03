package com.rmpsdroid.batterydiagnostics.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rmpsdroid.batterydiagnostics.R

/**
 * Placeholder foundation screen.
 *
 * The Compose-versus-Views decision is deliberately NOT being made here. Phase 0 section K
 * placed it at Phase 8, after the domain and session engine are stable, on the grounds that
 * the UI toolkit matters far less than the session model. This activity exists only so the
 * project builds, installs and launches; it is expected to be deleted.
 *
 * No styling, no theming, no feature UI.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
