package com.pocketqc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite

@Composable
fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    highlightColor: Color = Color.Unspecified
) {
    val resolvedHighlight = if (highlightColor != Color.Unspecified) highlightColor else TextWhite
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Top miniature accent indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (highlightColor != Color.Unspecified) highlightColor.copy(alpha = 0.7f) else Color.Transparent)
        )

        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextGrey,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = value,
            color = resolvedHighlight,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextGrey,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
