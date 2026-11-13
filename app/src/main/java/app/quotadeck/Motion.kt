package app.quotadeck

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

// Compose animation clocks follow the system animator duration scale (including zero).
@Composable fun GentleValue(text: String, style: TextStyle, color: Color = androidx.compose.ui.graphics.Color.Unspecified) {
 Crossfade(targetState=text,animationSpec=tween(180),label="amount") { value ->
  Text(value,style=style,color=color,maxLines=1,softWrap=false)
 }
}

@Composable fun RefreshGlyph(loading: Boolean) {
 if(loading) {
  val transition=rememberInfiniteTransition(label="refresh")
  val rotation by transition.animateFloat(0f,360f,infiniteRepeatable(tween(1000,easing=LinearEasing)),label="rotation")
  LineIcon("刷新",Modifier.graphicsLayer { rotationZ=rotation })
 } else LineIcon("刷新")
}
