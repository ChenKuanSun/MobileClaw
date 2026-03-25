package ai.affiora.mobileclaw.plugins

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.tools.AndroidTool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A plugin extends MobileClaw with additional tools and skills.
 */
interface MobileClawPlugin {
    /** Unique identifier for this plugin. */
    val id: String

    /** Human-readable name. */
    val name: String

    /** Short description of what this plugin does. */
    val description: String

    /** Semantic version string (e.g. "1.0.0"). */
    val version: String

    /** Returns the tools this plugin provides to the AI agent. */
    fun getTools(): List<AndroidTool>

    /** Returns the skill definitions this plugin provides. */
    fun getSkills(): List<SkillDefinition>

    /** Called when the plugin is loaded. Use for initialization. */
    suspend fun onLoad(context: Context)

    /** Called when the plugin is unloaded. Use for cleanup. */
    suspend fun onUnload()
}

/**
 * A skill is a prompt template that can be activated to give the agent
 * domain-specific instructions or capabilities.
 */
data class SkillDefinition(
    /** Unique identifier for this skill. */
    val id: String,

    /** Human-readable name. */
    val name: String,

    /** The system prompt content or instructions for this skill. */
    val content: String,
)

/**
 * Manages the lifecycle of all loaded plugins and provides a unified
 * view of their tools and skills.
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _plugins = MutableStateFlow<List<MobileClawPlugin>>(emptyList())
    val plugins: StateFlow<List<MobileClawPlugin>> = _plugins.asStateFlow()

    private val loadedPlugins = mutableMapOf<String, MobileClawPlugin>()

    /**
     * Load and initialize a plugin. If a plugin with the same ID is already loaded,
     * it will be unloaded first.
     */
    suspend fun loadPlugin(plugin: MobileClawPlugin) {
        // Unload existing plugin with same ID if present
        if (loadedPlugins.containsKey(plugin.id)) {
            unloadPlugin(plugin.id)
        }

        try {
            plugin.onLoad(context)
            loadedPlugins[plugin.id] = plugin
            _plugins.value = loadedPlugins.values.toList()
            Log.i(TAG, "Loaded plugin: ${plugin.name} (${plugin.id}) v${plugin.version}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin: ${plugin.id}", e)
        }
    }

    /**
     * Unload a plugin by its ID. Calls onUnload() for cleanup.
     */
    suspend fun unloadPlugin(pluginId: String) {
        val plugin = loadedPlugins.remove(pluginId) ?: return

        try {
            plugin.onUnload()
            Log.i(TAG, "Unloaded plugin: ${plugin.name} (${plugin.id})")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading plugin: ${plugin.id}", e)
        }

        _plugins.value = loadedPlugins.values.toList()
    }

    /**
     * Returns all tools from all loaded plugins, merged into a single list.
     */
    fun getTools(): List<AndroidTool> {
        return loadedPlugins.values.flatMap { it.getTools() }
    }

    /**
     * Returns all skills from all loaded plugins, merged into a single list.
     */
    fun getSkills(): List<SkillDefinition> {
        return loadedPlugins.values.flatMap { it.getSkills() }
    }

    /**
     * Find a loaded plugin by its ID.
     */
    fun getPlugin(pluginId: String): MobileClawPlugin? {
        return loadedPlugins[pluginId]
    }

    /**
     * Check if a plugin with the given ID is loaded.
     */
    fun isLoaded(pluginId: String): Boolean {
        return loadedPlugins.containsKey(pluginId)
    }

    /**
     * Unload all plugins. Call during app shutdown.
     */
    suspend fun unloadAll() {
        val pluginIds = loadedPlugins.keys.toList()
        for (id in pluginIds) {
            unloadPlugin(id)
        }
    }

    companion object {
        private const val TAG = "PluginManager"
    }
}
