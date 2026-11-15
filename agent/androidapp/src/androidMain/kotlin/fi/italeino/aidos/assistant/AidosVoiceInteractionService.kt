package fi.italeino.aidos.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import fi.italeino.aidos.MainActivity

/**
 * System Assistant Service (RFC-0111 §1.2).
 * Invoked via power button / corner swipe when Aidos is set as the device's default assistant.
 */
class AidosVoiceInteractionService : VoiceInteractionService()

class AidosVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AidosVoiceInteractionSession(this)
    }
}

class AidosVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_ASSISTANT, true)
        }
        context.startActivity(launchIntent)
        hide()
    }

    companion object {
        const val EXTRA_OPEN_ASSISTANT = "fi.italeino.aidos.OPEN_ASSISTANT"
    }
}
