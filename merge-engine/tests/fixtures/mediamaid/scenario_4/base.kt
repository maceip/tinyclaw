package ai.musicconverter.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import ai.musicconverter.R
import com.google.common.collect.ImmutableList

/**
 * Custom notification provider that overrides:
 * - Small icon: branded play-in-circle instead of generic app icon
 * - Color palette: forced aluminum-silver tint (effective pre-API 33;
 *   on 33+ the system derives color from artwork)
 * - Button ordering: delegates to super, which places custom commands
 *   (like Convert) in overflow slots after the standard transport controls
 */
@OptIn(UnstableApi::class)
class CustomMediaNotificationProvider(
    context: Context
) : DefaultMediaNotificationProvider(context) {

    init {
        setSmallIcon(R.drawable.ic_notification)
    }

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory
    ): IntArray {
        // Aluminum-inspired tint for the notification chrome (pre-API 33).
        // On API 33+ this is ignored; the system extracts color from artwork.
        builder.setColor(0xFF808890.toInt())
        builder.setColorized(true)

        return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
    }
}
