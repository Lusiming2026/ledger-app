package com.ledger.android.ui

import android.app.Application
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ledger.android.LedgerApplication
import com.ledger.android.ui.account.AccountEditScreen
import com.ledger.android.ui.account.AccountListScreen
import com.ledger.android.ui.account.ReconcileScreen
import com.ledger.android.ui.budget.BudgetScreen
import com.ledger.android.ui.category.CategoryEditScreen
import com.ledger.android.ui.category.CategoryListScreen
import com.ledger.android.ui.data.DataScreen
import com.ledger.android.ui.home.HomeScreen
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.record.RecordScreen
import com.ledger.android.ui.report.ReportScreen
import com.ledger.android.ui.settings.AboutScreen
import com.ledger.android.ui.settings.AppearanceScreen
import com.ledger.android.ui.settings.SettingsScreen
import com.ledger.android.ui.theme.LedgerTheme
import com.ledger.android.ui.transactions.TransactionListScreen
import com.ledger.domain.model.CategoryKind
import com.ledger.domain.model.TransactionType
import kotlinx.coroutines.flow.StateFlow

/**
 * 路由表。参数一律走 query（不用 path segment），这样「编辑 / 新增」可以共用一条路由。
 */
object Routes {

    const val HOME = "home"
    const val REPORT = "report"
    const val SETTINGS = "settings"

    /** P03/P04：`txId` 为空 = 新增 */
    const val RECORD_PATTERN = "record?txId={txId}"
    const val RECORD_ADD = "record"
    fun recordEdit(transactionId: String) = "record?txId=$transactionId"

    /** P12：区间 + 可选分类/类型筛选 */
    const val TRANSACTIONS_PATTERN = "transactions?from={from}&to={to}&categoryId={categoryId}&type={type}"
    fun transactions(from: Long, to: Long, categoryId: String?, type: String?): String =
        "transactions?from=$from&to=$to&categoryId=${categoryId.orEmpty()}&type=${type.orEmpty()}"

    const val CATEGORIES = "categories"

    /** P06：`kind` 只在新增时用得上（编辑时类型不可改） */
    const val CATEGORY_EDIT_PATTERN = "category_edit?categoryId={categoryId}&kind={kind}"
    fun categoryCreate(kind: CategoryKind) = "category_edit?kind=${kind.dbValue}"
    fun categoryEdit(categoryId: String) = "category_edit?categoryId=$categoryId"

    const val ACCOUNTS = "accounts"

    const val ACCOUNT_EDIT_PATTERN = "account_edit?accountId={accountId}"
    const val ACCOUNT_CREATE = "account_edit"
    fun accountEdit(accountId: String) = "account_edit?accountId=$accountId"

    const val RECONCILE_PATTERN = "reconcile?accountId={accountId}"
    fun reconcile(accountId: String) = "reconcile?accountId=$accountId"

    const val BUDGET = "budget"
    const val DATA = "data"
    const val APPEARANCE = "appearance"
    const val ABOUT = "about"

    /** 只有这三个是底部 Tab 的顶级页面，其它页面隐藏底栏。 */
    val TAB_ROUTES = setOf(HOME, REPORT, SETTINGS)
}

private sealed interface StartupState {
    data object Loading : StartupState
    data object Ready : StartupState
    data class Failed(val reason: String) : StartupState
}

/**
 * 应用外壳（PRD §4.1 信息架构：启动页 → 三 Tab 主界面 + 若干全局页）。
 *
 * @param recordRequest T27 桌面快捷方式「记一笔」的请求流；`true` 时冷启动直达 P03
 */
@Composable
fun LedgerApp(
    recordRequest: StateFlow<Boolean>,
    onRecordRequestHandled: () -> Unit,
) {
    val appUiState = rememberAppUiState()
    val application = LocalContext.current.applicationContext as Application
    val ledgerApp = application as LedgerApplication

    var startup by remember { mutableStateOf<StartupState>(StartupState.Loading) }
    // 用 retryToken 让「重试」按钮能重新触发同一个 LaunchedEffect
    var retryToken by remember { mutableStateOf(0) }

    LaunchedEffect(retryToken) {
        startup = StartupState.Loading
        startup = try {
            ledgerApp.startup.await()
            StartupState.Ready
        } catch (t: Throwable) {
            StartupState.Failed(
                ledgerApp.nativeLoadError?.message
                    ?: t.message
                    ?: t::class.simpleName
                    ?: "未知错误",
            )
        }
    }

    LedgerTheme(mode = appUiState.themeMode) {
        CompositionLocalProvider(LocalAppUiState provides appUiState) {
            when (val current = startup) {
                StartupState.Loading -> SplashScreen()

                is StartupState.Failed -> SplashScreen(
                    failedReason = current.reason,
                    onRetry = { retryToken += 1 },
                )

                StartupState.Ready -> {
                    // 文件选择器的桥只注册一次，与当前停留在哪个页面无关
                    FilePickerBridgeEffect()
                    MainShell(
                        recordRequest = recordRequest,
                        onRecordRequestHandled = onRecordRequestHandled,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainShell(
    recordRequest: StateFlow<Boolean>,
    onRecordRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.TAB_ROUTES

    // 桌面快捷方式：冷启动或已在运行时，都直接跳到记账页
    LaunchedEffect(Unit) {
        recordRequest.collect { requested ->
            if (requested) {
                navController.navigate(Routes.RECORD_ADD) { launchSingleTop = true }
                onRecordRequestHandled()
            }
        }
    }

    Scaffold(
        // 顶栏与底栏各自处理系统栏内边距（TopAppBar / NavigationBar 自带 inset 逻辑），
        // 外层不再重复消费，避免出现双倍留白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                LedgerBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route -> navController.navigateToTab(route) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            // -------------------------------------------------- Tab1 首页 P02
            composable(Routes.HOME) {
                HomeScreen(
                    onRecord = { navController.navigate(Routes.RECORD_ADD) },
                    onOpenAllTransactions = {
                        navController.navigate(
                            Routes.transactions(0L, Long.MAX_VALUE, null, null),
                        )
                    },
                    onOpenBudget = { navController.navigate(Routes.BUDGET) },
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenReports = { navController.navigateToTab(Routes.REPORT) },
                    onOpenTransaction = { id -> navController.navigate(Routes.recordEdit(id)) },
                )
            }

            // -------------------------------------------------- Tab2 报表 P11
            composable(Routes.REPORT) {
                ReportScreen(
                    onOpenTransactions = { from, to, categoryId, type ->
                        navController.navigate(
                            Routes.transactions(
                                from = from,
                                to = to,
                                categoryId = categoryId,
                                type = type,
                            ),
                        )
                    },
                )
            }

            // -------------------------------------------------- Tab3 设置
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenBudget = { navController.navigate(Routes.BUDGET) },
                    onOpenData = { navController.navigate(Routes.DATA) },
                    onOpenAppearance = { navController.navigate(Routes.APPEARANCE) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
            }

            // -------------------------------------------------- P03 / P04 记账
            composable(
                route = Routes.RECORD_PATTERN,
                arguments = listOf(
                    navArgument("txId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                RecordScreen(
                    transactionId = entry.arguments?.getString("txId")?.takeIf { it.isNotBlank() },
                    onDone = { navController.popBackStack() },
                )
            }

            // -------------------------------------------------- P12 明细
            composable(
                route = Routes.TRANSACTIONS_PATTERN,
                arguments = listOf(
                    navArgument("from") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument("to") {
                        type = NavType.LongType
                        defaultValue = Long.MAX_VALUE
                    },
                    navArgument("categoryId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("type") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val args = entry.arguments
                TransactionListScreen(
                    fromMillis = args?.getLong("from") ?: 0L,
                    toMillis = args?.getLong("to") ?: Long.MAX_VALUE,
                    categoryId = args?.getString("categoryId")?.takeIf { it.isNotBlank() },
                    type = args?.getString("type")?.takeIf { it.isNotBlank() }
                        ?.let { TransactionType.fromDbOrNull(it) },
                    onBack = { navController.popBackStack() },
                    onOpenTransaction = { id -> navController.navigate(Routes.recordEdit(id)) },
                )
            }

            // -------------------------------------------------- P05 / P06 分类
            composable(Routes.CATEGORIES) {
                CategoryListScreen(
                    onBack = { navController.popBackStack() },
                    onCreateCategory = { kind ->
                        navController.navigate(Routes.categoryCreate(kind))
                    },
                    onEditCategory = { id -> navController.navigate(Routes.categoryEdit(id)) },
                )
            }

            composable(
                route = Routes.CATEGORY_EDIT_PATTERN,
                arguments = listOf(
                    navArgument("categoryId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("kind") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val kind = entry.arguments?.getString("kind")?.takeIf { it.isNotBlank() }
                    ?.let { CategoryKind.fromDb(it) }
                CategoryEditScreen(
                    categoryId = entry.arguments?.getString("categoryId")?.takeIf { it.isNotBlank() },
                    onCreateForKind = kind,
                    onBack = { navController.popBackStack() },
                )
            }

            // -------------------------------------------------- P07 / P08 / P09 账户
            composable(Routes.ACCOUNTS) {
                AccountListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAccount = { id -> navController.navigate(Routes.reconcile(id)) },
                    onCreateAccount = { navController.navigate(Routes.ACCOUNT_CREATE) },
                    onEditAccount = { id -> navController.navigate(Routes.accountEdit(id)) },
                )
            }

            composable(
                route = Routes.ACCOUNT_EDIT_PATTERN,
                arguments = listOf(
                    navArgument("accountId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                AccountEditScreen(
                    accountId = entry.arguments?.getString("accountId")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.RECONCILE_PATTERN,
                arguments = listOf(
                    navArgument("accountId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                if (accountId.isBlank()) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    ReconcileScreen(
                        accountId = accountId,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // -------------------------------------------------- P10 预算
            composable(Routes.BUDGET) {
                BudgetScreen(onBack = { navController.popBackStack() })
            }

            // -------------------------------------------------- P13 数据
            composable(Routes.DATA) {
                DataScreen(onBack = { navController.popBackStack() })
            }

            // -------------------------------------------------- P14 外观
            composable(Routes.APPEARANCE) {
                AppearanceScreen(onBack = { navController.popBackStack() })
            }

            // -------------------------------------------------- P15 关于
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * 切 Tab：回到起始目的地并保存/恢复各 Tab 的滚动位置，避免反复堆栈。
 * 已在目标 Tab 上时直接返回，不重复 navigate。
 */
private fun NavHostController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LedgerBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val items = listOf(
        BottomItem(Routes.HOME, "首页", LedgerIcons.HomeIcon),
        BottomItem(Routes.REPORT, "报表", LedgerIcons.Report),
        BottomItem(Routes.SETTINGS, "设置", LedgerIcons.Settings),
    )
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { onSelect(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)
