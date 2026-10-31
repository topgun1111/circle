package com.example.circlesearch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible entry point. Use it as the action for the side button (if vivo lets you
 * pick an app or shortcut), as a home-screen shortcut, or via the Quick Settings tile.
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = CircleService.instance
        if (service != null) {
            // Small delay so this activity's transition is gone before the screenshot.
            service.trigger(350)
        } else {
            Toast.makeText(
                this,
                "Turn on the Circle to Search accessibility service first",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
