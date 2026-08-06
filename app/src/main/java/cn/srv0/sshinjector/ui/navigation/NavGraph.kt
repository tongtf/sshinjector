package cn.srv0.sshinjector.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import cn.srv0.sshinjector.ui.screen.dashboard.DashboardScreen
import cn.srv0.sshinjector.ui.screen.keymanager.KeyAddScreen
import cn.srv0.sshinjector.ui.screen.keymanager.KeyManagerScreen
import cn.srv0.sshinjector.ui.screen.server.ServerEditScreen
import cn.srv0.sshinjector.ui.screen.server.ServerListScreen
import cn.srv0.sshinjector.ui.screen.server.ServerWizardScreen
import cn.srv0.sshinjector.ui.screen.settings.DomainListSettingsScreen
import cn.srv0.sshinjector.ui.screen.settings.SettingsScreen
import cn.srv0.sshinjector.ui.screen.whitelist.WhitelistScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = modifier,
    ) {
        composable("dashboard") {
            DashboardScreen(
                onNavigateToServerAdd = { navController.navigate("server/edit/-1") },
                onNavigateToServerEdit = { id -> navController.navigate("server/edit/$id") },
                onNavigateToKeys = { navController.navigate("keys") },
                onNavigateToKeyAdd = { navController.navigate("key/add") },
            )
        }
        composable("servers") {
            ServerListScreen(
                onAddServer = { navController.navigate("server/edit/-1") },
                onAddWizard = { navController.navigate("server/wizard") },
                onEditServer = { id -> navController.navigate("server/edit/$id") },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable("server/edit/{serverId}") { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId")?.toLongOrNull() ?: -1L
            ServerEditScreen(
                serverId = serverId,
                onSave = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
        composable("server/wizard") {
            ServerWizardScreen(
                onFinish = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
        composable("whitelist") {
            WhitelistScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onNavigateToDomainListSettings = { navController.navigate("domain_list_settings") },
                onNavigateToWhitelist = { navController.navigate("whitelist") },
                onNavigateToServerManagement = { navController.navigate("servers") },
                onNavigateToKeyManagement = { navController.navigate("keys") },
            )
        }
        composable("domain_list_settings") {
            DomainListSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("keys") {
            KeyManagerScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("key/add") {
            KeyAddScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
