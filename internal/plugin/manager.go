package plugin

import (
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/internal/search"
	"github.com/solocoder/knowledgebase/internal/tags"
)

type PluginManager struct {
	cfg         *config.Config
	db          *db.Database
	search      *search.SearchEngine
	tagManager  *tags.TagManager
	registry    *Registry
	api         *PluginAPI
	sandboxes   map[string]*Sandbox
	mu          sync.RWMutex
	initialized bool
}

func NewPluginManager(cfg *config.Config, database *db.Database, searchEngine *search.SearchEngine, tagMgr *tags.TagManager) *PluginManager {
	return &PluginManager{
		cfg:        cfg,
		db:         database,
		search:     searchEngine,
		tagManager: tagMgr,
		sandboxes:  make(map[string]*Sandbox),
	}
}

func (pm *PluginManager) Init() error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if pm.initialized {
		return nil
	}

	pm.registry = NewRegistry(pm.cfg)
	if err := pm.registry.Init(); err != nil {
		return fmt.Errorf("failed to init registry: %w", err)
	}

	pm.api = NewPluginAPI(pm.db, pm.cfg, pm.search, pm.tagManager)

	pm.initialized = true
	return nil
}

func (pm *PluginManager) LoadAllPlugins() error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	plugins := pm.registry.GetInstalledPlugins()
	var errs []error

	for _, plugin := range plugins {
		if !plugin.Enabled {
			continue
		}
		if err := pm.loadPluginLocked(plugin); err != nil {
			errs = append(errs, fmt.Errorf("failed to load plugin %s: %w", plugin.ID, err))
		}
	}

	if len(errs) > 0 {
		return fmt.Errorf("some plugins failed to load: %v", errs)
	}

	pm.TriggerEvent("app:ready", nil)

	return nil
}

func (pm *PluginManager) loadPluginLocked(plugin *models.Plugin) error {
	if _, exists := pm.sandboxes[plugin.ID]; exists {
		return nil
	}

	sandboxConfig := SandboxConfig{
		Timeout:    5 * time.Second,
		PluginPath: plugin.Path,
	}

	sandbox := NewSandbox(plugin, sandboxConfig, pm.api)
	if err := sandbox.Init(); err != nil {
		return fmt.Errorf("failed to init sandbox: %w", err)
	}

	entryPath := pm.registry.GetPluginEntryPath(plugin)
	_, err := sandbox.RunFile(entryPath)
	if err != nil {
		sandbox.Destroy()
		return fmt.Errorf("failed to run plugin entry: %w", err)
	}

	pm.sandboxes[plugin.ID] = sandbox

	sandbox.TriggerEvent("plugin:loaded", map[string]interface{}{
		"id":      plugin.ID,
		"name":    plugin.Name,
		"version": plugin.Version,
	})

	return nil
}

func (pm *PluginManager) LoadPlugin(id string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	plugin, ok := pm.registry.GetInstalledPlugin(id)
	if !ok {
		return fmt.Errorf("plugin not installed: %s", id)
	}

	return pm.loadPluginLocked(plugin)
}

func (pm *PluginManager) UnloadPlugin(id string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	return pm.unloadPluginLocked(id)
}

func (pm *PluginManager) unloadPluginLocked(id string) error {
	sandbox, ok := pm.sandboxes[id]
	if !ok {
		return nil
	}

	sandbox.TriggerEvent("plugin:unload", nil)
	sandbox.Destroy()

	delete(pm.sandboxes, id)

	pm.api.ClearPluginData(id)

	return nil
}

func (pm *PluginManager) InstallPlugin(id string) (*models.Plugin, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	plugin, err := pm.registry.InstallPlugin(id)
	if err != nil {
		return nil, err
	}

	if plugin.Enabled {
		if err := pm.loadPluginLocked(plugin); err != nil {
			pm.registry.UninstallPlugin(id)
			return nil, fmt.Errorf("failed to load installed plugin: %w", err)
		}
	}

	return plugin, nil
}

func (pm *PluginManager) UninstallPlugin(id string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	pm.unloadPluginLocked(id)

	return pm.registry.UninstallPlugin(id)
}

func (pm *PluginManager) EnablePlugin(id string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if err := pm.registry.EnablePlugin(id); err != nil {
		return err
	}

	plugin, _ := pm.registry.GetInstalledPlugin(id)
	return pm.loadPluginLocked(plugin)
}

func (pm *PluginManager) DisablePlugin(id string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if err := pm.unloadPluginLocked(id); err != nil {
		return err
	}

	return pm.registry.DisablePlugin(id)
}

func (pm *PluginManager) GetInstalledPlugins() []*models.Plugin {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return pm.registry.GetInstalledPlugins()
}

func (pm *PluginManager) GetInstalledPlugin(id string) (*models.Plugin, bool) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return pm.registry.GetInstalledPlugin(id)
}

func (pm *PluginManager) GetMarketplacePlugins(category, keyword string) []*MarketPlugin {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return pm.registry.GetMarketplacePlugins(category, keyword)
}

func (pm *PluginManager) GetMarketplacePlugin(id string) (*MarketPlugin, bool) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return pm.registry.GetMarketplacePlugin(id)
}

func (pm *PluginManager) IsPluginLoaded(id string) bool {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	_, ok := pm.sandboxes[id]
	return ok
}

func (pm *PluginManager) CheckForUpdate(id string) (bool, string, error) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return pm.registry.CheckForUpdate(id)
}

func (pm *PluginManager) GetCommands() []*Command {
	return pm.api.GetCommands()
}

func (pm *PluginManager) ExecuteCommand(cmdID string) error {
	return pm.api.ExecuteCommand(cmdID)
}

func (pm *PluginManager) GetViews() []*View {
	return pm.api.GetViews()
}

func (pm *PluginManager) GetNotifications() []*Notification {
	return pm.api.GetNotifications()
}

func (pm *PluginManager) SetNotificationCallback(cb func(*Notification)) {
	pm.api.SetNotificationCallback(cb)
}

func (pm *PluginManager) TriggerEvent(event string, data interface{}) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	for _, sandbox := range pm.sandboxes {
		sandbox.TriggerEvent(event, data)
	}
}

func (pm *PluginManager) TriggerPluginEvent(pluginID, event string, data interface{}) error {
	pm.mu.RLock()
	sandbox, ok := pm.sandboxes[pluginID]
	pm.mu.RUnlock()

	if !ok {
		return fmt.Errorf("plugin not loaded: %s", pluginID)
	}

	sandbox.TriggerEvent(event, data)
	return nil
}

func (pm *PluginManager) GetPluginAPI() *PluginAPI {
	return pm.api
}

func (pm *PluginManager) GetRegistry() *Registry {
	return pm.registry
}

func (pm *PluginManager) Shutdown() error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	for id := range pm.sandboxes {
		pm.unloadPluginLocked(id)
	}

	pm.initialized = false
	return nil
}

var _ = time.Now
