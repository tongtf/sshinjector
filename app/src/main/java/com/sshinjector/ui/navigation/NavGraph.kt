package com.sshinjector.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavHostController
import com.sshinjector.ui.screen.dashboard.DashboardScreen
import com.sshinjector.ui.screen.keymanager.KeyManagerScreen
import com.sshinjector.ui.screen.server.ServerEditScreen
import com.sshinjector.ui.screen.server.ServerListScreen
import com.sshinjector.ui.screen.settings.DomainListSettingsScreen
import com.sshinjector.ui.screen.settings.SettingsScreen
import com.sshinjector.ui.screen.whitelist.WhitelistScreen

@Composable
fun AppNavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = modifier
    ) {
        composable("dashboard") {
            DashboardScreen(
                onNavigateToServers = { navController.navigate("servers") },
                onNavigateToWhitelist = { navController.navigate("whitelist") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToKeys = { navController.navigate("keys") }
            )
        }
        composable("servers") {
            ServerListScreen(
                onAddServer = { navController.navigate("server/edit/-1") },
                onEditServer = { id -> navController.navigate("server/edit/$id") },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("server/edit/{serverId}") { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId")?.toLongOrNull() ?: -1L
            ServerEditScreen(
                serverId = serverId,
                onSave = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable("whitelist") {
            WhitelistScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDomainListSettings = { navController.navigate("domain_list_settings") }
            )
        }
        composable("domain_list_settings") {
            DomainListSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("keys") {
            KeyManagerScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}