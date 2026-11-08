package com.zerosynk.app.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.design.Design
import com.zerosynk.app.net.PairingCtl
import com.zerosynk.app.service.SyncService
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun ZeroSynkRoot(scanFlow: SharedFlow<String>) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var screen by rememberSaveable { mutableStateOf(if (prefs.paired) "main" else "onboarding") }
    val scan by scanFlow.collectAsState(initial = null)

    LaunchedEffect(scan) {
        val raw = scan ?: return@LaunchedEffect
        val payload = PairingCtl.parseUri(raw) ?: return@LaunchedEffect
        if (screen != "onboarding") PairingCtl.reset()
        PairingCtl.pairWithUri(context, payload)
    }

    LaunchedEffect(Unit) {
        if (prefs.paired && !SyncService.isConnected()) {
            SyncService.start(context)
        }
    }

    when (screen) {
        "onboarding" -> {
            OnboardingScreen(onPaired = { screen = "main" })
        }
        else -> {
            MainScreen(onRepaired = { screen = "onboarding" })
        }
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

/** Folder glyph — material-icons-core has no Folder, so it's drawn here (24dp grid). */
private val FolderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Folder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color(0xFF000000))) {
            moveTo(3f, 6f)
            lineTo(9f, 6f)
            lineTo(11f, 8f)
            lineTo(21f, 8f)
            lineTo(21f, 19f)
            lineTo(3f, 19f)
            close()
        }
    }.build()
}

@Composable
private fun MainScreen(onRepaired: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(
        NavItem("Главная", Icons.Default.Home),
        NavItem("Файлы", FolderIcon),
        NavItem("Настройки", Icons.Default.Settings),
    )

    // Overlay layout: content fills the whole screen and the dock floats on top,
    // so there is no reserved black band behind the pill (iOS-style).
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            when (tab) {
                0 -> HomeScreen()
                1 -> FilesScreen()
                else -> SettingsScreen(onRepaired = onRepaired)
            }
        }
        FloatingDock(
            items = items,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
        )
    }
}

@Composable
private fun FloatingDock(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val design by Design.flow.collectAsState()
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(Design.dockColor(design)))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                DockIcon(
                    icon = item.icon,
                    label = item.label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun DockIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bgAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = "dockBg",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1f,
        animationSpec = tween(200),
        label = "dockIcon",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) Color.White else Color(0xFF8E8E93),
        animationSpec = tween(200),
        label = "dockTint",
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f * bgAlpha + 0.001f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier
                .size(24.dp)
                .scale(iconScale),
        )
    }
}
