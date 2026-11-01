package com.example.circlesearch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.Toast

// Registers the app as a selectable "Digital assistant app". Power-button long-press and the
// assist gesture then start a session, which immediately hands off to the Circle service.
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

private class AssistSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        hide() // drop the assistant window so it is not in the screenshot
        val service = CircleService.instance
        if (service != null) {
            service.trigger(400)
        } else {
            Toast.makeText(ctx, "Turn on the Circle to Search accessibility service first", Toast.LENGTH_LONG).show()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

// Stub: some Android versions refuse to list a voice-interaction service without one.
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, cb: Callback?) { cb?.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(cb: Callback?) {}
    override fun onStopListening(cb: Callback?) {}
}
