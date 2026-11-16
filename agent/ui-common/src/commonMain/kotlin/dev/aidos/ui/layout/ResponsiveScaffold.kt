package dev.aidos.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class NavigationDestination(
    val id: String,
    val label: String,
    val icon: @Composable () -> Unit,
    val badge: (@Composable () -> Unit)? = null,
)

enum class ScaffoldNavigationType {
    BOTTOM_NAVIGATION,
    NAVIGATION_RAIL,
    PERMANENT_DRAWER,
}

fun navigationTypeForFormFactor(formFactor: FormFactor): ScaffoldNavigationType = when (formFactor) {
    FormFactor.HANDHELD_COMPACT -> ScaffoldNavigationType.BOTTOM_NAVIGATION
    FormFactor.HANDHELD_EXPANDED -> ScaffoldNavigationType.NAVIGATION_RAIL
    FormFactor.DESKTOP_DOCKED -> ScaffoldNavigationType.PERMANENT_DRAWER
}

@Composable
fun ResponsiveScaffold(
    destinations: List<NavigationDestination>,
    destinationsFor: ((FormFactor) -> List<NavigationDestination>)? = null,
    selectedDestinationId: String,
    onDestinationSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    overlayContent: (@Composable () -> Unit)? = null,
    content: @Composable (FormFactor) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val formFactor = computeFormFactor(widthDp)
        val navType = navigationTypeForFormFactor(formFactor)
        // RFC-0124: a form factor may offer a different set of primary views (the phone drops the canvas).
        val destinations = destinationsFor?.invoke(formFactor) ?: destinations

        Box(modifier = Modifier.fillMaxSize()) {
            when (navType) {
                ScaffoldNavigationType.BOTTOM_NAVIGATION -> {
                    Scaffold(
                        topBar = { topBar?.invoke() },
                        bottomBar = {
                            NavigationBar {
                                destinations.forEach { item ->
                                    NavigationBarItem(
                                        selected = item.id == selectedDestinationId,
                                        onClick = { onDestinationSelected(item.id) },
                                        icon = item.icon,
                                        label = { Text(item.label) },
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            content(formFactor)
                        }
                    }
                }

                ScaffoldNavigationType.NAVIGATION_RAIL -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(modifier = Modifier.fillMaxHeight()) {
                            destinations.forEach { item ->
                                NavigationRailItem(
                                    selected = item.id == selectedDestinationId,
                                    onClick = { onDestinationSelected(item.id) },
                                    icon = item.icon,
                                    label = { Text(item.label) },
                                )
                            }
                        }
                        Scaffold(
                            topBar = { topBar?.invoke() },
                            modifier = Modifier.weight(1f)
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                content(formFactor)
                            }
                        }
                    }
                }

                ScaffoldNavigationType.PERMANENT_DRAWER -> {
                    PermanentNavigationDrawer(
                        drawerContent = {
                            PermanentDrawerSheet(modifier = Modifier.width(280.dp).fillMaxHeight()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    topBar?.invoke()
                                    destinations.forEach { item ->
                                        NavigationDrawerItem(
                                            selected = item.id == selectedDestinationId,
                                            onClick = { onDestinationSelected(item.id) },
                                            icon = item.icon,
                                            label = { Text(item.label) },
                                            badge = item.badge,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            content(formFactor)
                        }
                    }
                }
            }

            // Floating overlay layer (Multi-Agent Pill Overlay)
            overlayContent?.invoke()
        }
    }
}
