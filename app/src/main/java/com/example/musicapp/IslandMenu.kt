package com.example.musicapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of the island quick menu. [tint] = null uses the normal text colour. */
class MenuAction(
    val label: String,
    val glyph: Glyph,
    val tint: Color? = null,
    val onClick: () -> Unit
)

/**
 * iOS style context menu: a tall frosted gray panel, label on the left and icon on the right,
 * hairline dividers between rows and a thicker gap between groups.
 * The app behind it is blurred by the caller, so the glass shows through.
 */
@Composable
fun IslandContextMenu(
    groups: List<List<MenuAction>>,
    p: Palette,
    modifier: Modifier = Modifier
) {
    val dark = p.dark
    val panel = if (dark) Color(0xFF2C2C2E).copy(alpha = 0.66f) else Color(0xFFE8E8ED).copy(alpha = 0.72f)
    val rim = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.70f)
    val line = if (dark) Color.White.copy(alpha = 0.14f) else Color(0xFF3C3C43).copy(alpha = 0.22f)
    val gap = if (dark) Color.Black.copy(alpha = 0.35f) else Color(0xFF787880).copy(alpha = 0.22f)
    val fg = if (dark) Color.White else Color(0xFF1C1C1E)
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier
            .width(240.dp)
            .clip(shape)
            .background(panel, shape)
            .border(0.5.dp, rim, shape)
    ) {
        groups.forEachIndexed { gi, group ->
            if (gi > 0) Box(Modifier.fillMaxWidth().height(6.dp).background(gap))
            group.forEachIndexed { i, action ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(line))
                val c = action.tint ?: fg
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { action.onClick() }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(action.label, color = c, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    GlyphIcon(action.glyph, c, Modifier.size(20.dp))
                }
            }
        }
    }
}
