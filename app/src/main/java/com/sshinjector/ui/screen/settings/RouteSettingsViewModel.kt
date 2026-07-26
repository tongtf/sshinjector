package com.sshinjector.ui.screen.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.domain.vpn.tunnel.AppTagEntry
import com.sshinjector.domain.vpn.tunnel.LoadBalancingConfig
import com.sshinjector.domain.vpn.tunnel.RouteConfig
import com.sshinjector.domain.vpn.tunnel.TagTunnelEntry
import com.sshinjector.domain.vpn.tunnel.TunnelManager
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import com.sshinjector.domain.vpn.tunnel.TunnelRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class InstalledAppInfo(
    val packageName: String,
    val name: String,
    val isSystem: Boolean,
)

@HiltViewModel
class RouteSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunnelManager: TunnelManager,
    private val tunnelRouter: TunnelRouter,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val availablePlugins: List<TunnelPlugin>
        get() = _appTags.value.let { tunnelManager.availablePlugins.value }

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _appTags = MutableStateFlow<MutableMap<String, Set<String>>>(mutableMapOf())
    val appTags: StateFlow<Map<String, Set<String>>> = _appTags.asStateFlow()

    private val _tagTunnels = MutableStateFlow<MutableMap<String, String>>(mutableMapOf())
    val tagTunnels: StateFlow<Map<String, String>> = _tagTunnels.asStateFlow()

    private val _defaultTunnel = MutableStateFlow("socks5")
    val defaultTunnel: StateFlow<String> = _defaultTunnel.asStateFlow()

    private val _newTagName = MutableStateFlow("")
    val newTagName: StateFlow<String> = _newTagName.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        loadApps()
        loadSavedConfig()
    }

    private fun loadSavedConfig() {
        viewModelScope.launch {
            val json = settingsDataStore.routeConfig.first()
            if (!json.isNullOrBlank()) {
                try {
                    val obj = JSONObject(json)
                    val appTagsMap = mutableMapOf<String, Set<String>>()
                    val appTagsArr = obj.optJSONArray("appTags")
                    if (appTagsArr != null) {
                        for (i in 0 until appTagsArr.length()) {
                            val entry = appTagsArr.getJSONObject(i)
                            val pkg = entry.getString("packageName")
                            val tags = (0 until entry.getJSONArray("tags").length())
                                .map { entry.getJSONArray("tags").getString(it) }.toSet()
                            appTagsMap[pkg] = tags
                        }
                    }
                    _appTags.value = appTagsMap

                    val tagTunnelsMap = mutableMapOf<String, String>()
                    val tagTunnelsArr = obj.optJSONArray("tagTunnels")
                    if (tagTunnelsArr != null) {
                        for (i in 0 until tagTunnelsArr.length()) {
                            val entry = tagTunnelsArr.getJSONObject(i)
                            tagTunnelsMap[entry.getString("tag")] = entry.getString("primaryTunnelId")
                        }
                    }
                    _tagTunnels.value = tagTunnelsMap
                    _defaultTunnel.value = obj.optString("defaultTunnelId", "socks5")
                } catch (_: Exception) {}
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val flags = PackageManager.ApplicationInfoFlags.of(0)
                pm.getInstalledApplications(flags).map { info ->
                    InstalledAppInfo(
                        packageName = info.packageName,
                        name = pm.getApplicationLabel(info).toString(),
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }.sortedBy { it.name.lowercase() }
            }
            _installedApps.value = apps
        }
    }

    fun setAppTags(packageName: String, tags: Set<String>) {
        val map = _appTags.value.toMutableMap()
        if (tags.isEmpty()) map.remove(packageName) else map[packageName] = tags
        _appTags.value = map
    }

    fun addAppTag(packageName: String, tag: String) {
        val current = _appTags.value[packageName] ?: emptySet()
        setAppTags(packageName, current + tag)
    }

    fun removeAppTag(packageName: String, tag: String) {
        val current = _appTags.value[packageName] ?: return
        val updated = current - tag
        setAppTags(packageName, updated)
    }

    fun setTagTunnel(tag: String, tunnelId: String) {
        val map = _tagTunnels.value.toMutableMap()
        map[tag] = tunnelId
        _tagTunnels.value = map
    }

    fun removeTagTunnel(tag: String) {
        val map = _tagTunnels.value.toMutableMap()
        map.remove(tag)
        _tagTunnels.value = map
    }

    fun setNewTagName(name: String) {
        _newTagName.value = name
    }

    fun setDefaultTunnel(id: String) {
        _defaultTunnel.value = id
    }

    fun save() {
        val appTagsList = _appTags.value.map { (pkg, tags) ->
            JSONObject().apply {
                put("packageName", pkg)
                put("tags", JSONArray(tags.toList()))
            }
        }
        val tagTunnelsList = _tagTunnels.value.map { (tag, tunnelId) ->
            JSONObject().apply {
                put("tag", tag)
                put("primaryTunnelId", tunnelId)
            }
        }
        val json = JSONObject().apply {
            put("appTags", JSONArray(appTagsList))
            put("tagTunnels", JSONArray(tagTunnelsList))
            put("defaultTunnelId", _defaultTunnel.value)
        }.toString()

        viewModelScope.launch {
            settingsDataStore.setRouteConfig(json)
            val config = RouteConfig(
                appTags = _appTags.value.map { (pkg, tags) -> AppTagEntry(pkg, tags) },
                tagTunnels = _tagTunnels.value.map { (tag, tunnelId) ->
                    TagTunnelEntry(tag, tunnelId)
                },
                defaultTunnelId = _defaultTunnel.value,
            )
            tunnelRouter.updateConfig(config)
            _saved.value = true
        }
    }

    fun getTagTunnels(): List<Pair<String, String>> {
        return _tagTunnels.value.map { it.key to it.value }
    }

    fun getAppTagsList(): List<Triple<String, String, Set<String>>> {
        val appNames = _installedApps.value.associate { it.packageName to it.name }
        return _appTags.value.map { (pkg, tags) ->
            Triple(pkg, appNames[pkg] ?: pkg, tags)
        }
    }
}
