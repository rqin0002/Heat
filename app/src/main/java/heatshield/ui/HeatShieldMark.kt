package heatshield.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import heatshield.R

/**
 * The launcher icon drawn in-app: its foreground layer on the launcher's fixed teal background, in both themes. Decorative.
 * The palette spec leaves the launcher colours for later, so the mark keeps the teal rather than the Action blue.
 */
@Composable
fun HeatShieldMark(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(percent = 25))
        .background(colorResource(R.color.ic_launcher_background)).testTag("heatshield_mark")) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}
