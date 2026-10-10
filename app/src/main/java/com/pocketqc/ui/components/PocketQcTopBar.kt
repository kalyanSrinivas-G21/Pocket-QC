package com.pocketqc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.R
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite

@Composable
fun PocketQcBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_arrow_back),
            contentDescription = "Navigate Back",
            tint = TextWhite,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun PocketQcTopBar(
    title: String,
    subtitle: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    annotatedTitle: AnnotatedString? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            PocketQcBackButton(onClick = onNavigateBack)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (annotatedTitle != null) {
                    Text(
                        text = annotatedTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextGrey,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions()
            }
        }
    }
}
