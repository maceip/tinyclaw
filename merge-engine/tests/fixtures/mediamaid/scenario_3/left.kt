package ai.musicconverter.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Arrangement
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class MusicWidget : GlanceAppWidget() {

    override suspend fun provideContent(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFFD4D4D6)) // Aluminum Neutral
                .cornerRadius(16.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        "MEDIAMAID",
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = ColorProvider(Color(0xFF555555)),
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        "Queue: Idle",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = ColorProvider(Color(0xFF222222)),
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                
                // Status dot
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .background(Color(0xFF888888))
                        .cornerRadius(4.dp)
                ) {}
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // Calculator style progress
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFFFAF6E8))
                    .cornerRadius(4.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "IDLE",
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(Color(0xFF222222))
                    )
                )
            }
            
            Spacer(modifier = GlanceModifier.height(8.dp))
            
            // Transport controls row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Widget buttons are usually simple Image or Text clickable in Glance
                // For practice, we'll just layout the shapes
                WidgetButton("<<")
                WidgetButton("PLAY", isPrimary = true)
                WidgetButton(">>")
            }
        }
    }
    
    @Composable
    private fun WidgetButton(label: String, isPrimary: Boolean = false) {
        Box(
            modifier = GlanceModifier
                .width(if (isPrimary) 60.dp else 44.dp)
                .height(32.dp)
                .background(if (isPrimary) Color(0xFFBCBCC0) else Color(0xFFDEDEE0))
                .cornerRadius(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(Color(0xFF333333))
                )
            )
        }
    }
}

class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidget()
}
