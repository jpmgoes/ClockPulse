package com.bnyro.clock.navigation

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.clock.presentation.screens.alarm.AlarmScreen
import com.bnyro.clock.presentation.screens.alarm.model.AlarmModel
import com.bnyro.clock.presentation.screens.agenda.AgendaScreen
import com.bnyro.clock.presentation.screens.agenda.model.AgendaModel
import com.bnyro.clock.presentation.screens.clock.ClockScreen
import com.bnyro.clock.presentation.screens.clock.model.ClockModel
import com.bnyro.clock.presentation.screens.settings.model.SettingsModel
import com.bnyro.clock.presentation.screens.stopwatch.StopwatchScreen
import com.bnyro.clock.presentation.screens.stopwatch.model.StopwatchModel
import com.bnyro.clock.presentation.screens.timer.TimerScreen
import com.bnyro.clock.presentation.screens.timer.model.TimerModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeNavContainer(
    onNavigate: (route: String) -> Unit,
    initialTab: HomeRoutes,
    clockModel: ClockModel,
    timerModel: TimerModel,
    stopwatchModel: StopwatchModel,
    alarmModel: AlarmModel,
    settingsModel: SettingsModel
) {
    val orientation = LocalConfiguration.current.orientation
    val coroutineScope = rememberCoroutineScope()

    val filteredRoutes = remember(settingsModel.enabledTabs) {
        homeRoutes.filter { it.route in settingsModel.enabledTabs }
    }


    val initialPageIndex = remember(filteredRoutes) {
        val index = filteredRoutes.indexOfFirst { it.route == initialTab.route }
        if (index != -1) index else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPageIndex
    ) { filteredRoutes.size }
    val agendaModel: AgendaModel = androidx.lifecycle.viewmodel.compose.viewModel()
    Scaffold(
        bottomBar = {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                ExpressiveBottomNavigation(
                    routes = filteredRoutes,
                    selectedIndex = pagerState.currentPage,
                    onSelect = { index ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                )
            }
        }) { pV ->
        Row(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(pV)
                .padding(pV)
        ) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                NavigationRail {
                    filteredRoutes.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            icon = { Icon(item.icon, null) },
                            label = { Text(stringResource(item.stringRes)) })
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                if (pageIndex < filteredRoutes.size) {
                    when (filteredRoutes[pageIndex]) {
                        HomeRoutes.Alarm -> {
                            AlarmScreen(
                                onClickSettings = { onNavigate(NavRoutes.Settings.route) },
                                onAlarm = { alarmId, advanced ->
                        onNavigate("${NavRoutes.AlarmPicker.route}/$alarmId/$advanced")
                    },
                                alarmModel = alarmModel,
                                settingsModel = settingsModel
                            )
                        }

                        HomeRoutes.Agenda -> {
                            AgendaScreen(
                                onClickSettings = { onNavigate(NavRoutes.Settings.route) },
                                agendaModel = agendaModel
                            )
                        }

                        HomeRoutes.Clock -> {
                            ClockScreen(
                                onClickSettings = { onNavigate(NavRoutes.Settings.route) },
                                clockModel = clockModel,
                                settingsModel = settingsModel
                            )
                        }

                        HomeRoutes.Timer -> {
                            TimerScreen(
                                onClickSettings = { onNavigate(NavRoutes.Settings.route) },
                                timerModel = timerModel,
                                settingsModel = settingsModel
                            )
                        }

                        HomeRoutes.Stopwatch -> {
                            StopwatchScreen(
                                onClickSettings = { onNavigate(NavRoutes.Settings.route) },
                                stopwatchModel = stopwatchModel
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressiveBottomNavigation(
    routes: List<HomeRoutes>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            routes.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                val weight by animateFloatAsState(
                    targetValue = if (selected) 2.25f else 1f,
                    animationSpec = spring(),
                    label = "navigationItemWeight"
                )
                val label = stringResource(item.stringRes)
                val itemShape = MaterialTheme.shapes.extraLarge

                Surface(
                    modifier = Modifier
                        .weight(weight)
                        .height(52.dp)
                        .clip(itemShape)
                        .clickable { onSelect(index) },
                    shape = itemShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = if (selected) 14.dp else 0.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = label
                        )
                        AnimatedVisibility(
                            visible = selected,
                            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
