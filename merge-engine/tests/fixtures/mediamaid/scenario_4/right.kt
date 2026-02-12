package ai.musicconverter.service

import android.content.Context
import android.graphics.Bitmap
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
 *   on 33+ the system derives color from artwork via the retro skin bitmap)
 * - Large icon: retro player skin bitmap rendered with current track metadata
 * - Button ordering: delegates to super, which places custom commands
 *   (like Convert) in overflow slots after the standard transport controls
 */
@OptIn(UnstableApi::class)
class CustomMediaNotificationProvider(
    private val appContext: Context
) : DefaultMediaNotificationProvider(appContext) {

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
        // On API 33+ the system extracts color from the retro artwork bitmap.
        builder.setColor(0xFF808890.toInt())
        builder.setColorized(true)

        // Render the retro player skin as the notification's large icon.
        // This gives the media notification / lock screen the classic chrome look.
        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString() ?: ""
        val artist = metadata.artist?.toString() ?: ""
        val skinBitmap: Bitmap = RetroPlayerArtworkProvider.render(
            appContext,
            title = title,
            artist = artist,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L)
        )
        builder.setLargeIcon(skinBitmap)

        return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
    }
}
