package com.toneup.app.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.toneup.app.data.repository.ReviewRepository
import com.toneup.app.ui.feature.bank.BankTab
import com.toneup.app.ui.feature.mine.MineTab
import com.toneup.app.ui.feature.review.ReviewTab
import com.toneup.app.ui.feature.stats.StatsTab
import com.toneup.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-RV-05：复习 Tab 角标数量 */
@HiltViewModel
class ReviewBadgeViewModel @Inject constructor(
    private val reviewRepository: ReviewRepository
) : ViewModel() {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    init {
        refresh()
    }

    fun refresh(limit: Int = 100) {
        viewModelScope.launch(Dispatchers.IO) {
            _count.value = runCatching { reviewRepository.today(limit).total }.getOrDefault(0)
        }
    }
}

private enum class MainTab(val route: String, val label: String, val icon: ImageVector) {
    BANK(Routes.TAB_BANK, "题库", Icons.Filled.Book),
    REVIEW(Routes.TAB_REVIEW, "复习", Icons.Filled.Refresh),
    STATS(Routes.TAB_STATS, "统计", Icons.Filled.BarChart),
    MINE(Routes.TAB_MINE, "我的", Icons.Filled.Person)
}

/** 底部四 Tab 宿主：Tab 切换不重建状态（saveState/restoreState） */
@Composable
fun MainScaffold(rootNavController: NavHostController) {
    val tabNavController = rememberNavController()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = {
                            tabNavController.navigate(tab.route) {
                                popUpTo(tabNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                            if (selectedTabIndex != index) selectedTabIndex = index
                        },
                        icon = {
                            if (tab == MainTab.REVIEW) {
                                val badgeVm: ReviewBadgeViewModel = hiltViewModel()
                                val count by badgeVm.count.collectAsStateWithLifecycle()
                                IconWithBadge(icon = tab.icon, badgeCount = count)
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = tabNavController,
            startDestination = Routes.TAB_BANK,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.TAB_BANK) {
                BankTab(rootNavController = rootNavController)
            }
            composable(Routes.TAB_REVIEW) {
                ReviewTab(rootNavController = rootNavController)
            }
            composable(Routes.TAB_STATS) {
                StatsTab()
            }
            composable(Routes.TAB_MINE) {
                MineTab(
                    rootNavController = rootNavController,
                    onLoggedOut = {
                        rootNavController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun IconWithBadge(icon: ImageVector, badgeCount: Int) {
    if (badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge { Text(badgeCount.coerceAtMost(99).toString()) }
            }
        ) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}
