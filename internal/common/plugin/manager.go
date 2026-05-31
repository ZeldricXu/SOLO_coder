package plugin

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/session147/internal/common/logger"
	"go.uber.org/zap"
)

type PluginState int

const (
	PluginStateDisabled PluginState = iota
	PluginStateEnabled
	PluginStateRunning
	PluginStateError
)

type Plugin interface {
	ID() string
	Name() string
	Version() string
	Description() string
	Init(ctx context.Context, config map[string]interface{}) error
	Start(ctx context.Context) error
	Stop(ctx context.Context) error
	State() PluginState
}

type PluginHook string

const (
	HookBeforeTxBuild    PluginHook = "before_tx_build"
	HookAfterTxBuild     PluginHook = "after_tx_build"
	HookBeforeTxSign     PluginHook = "before_tx_sign"
	HookAfterTxSign      PluginHook = "after_tx_sign"
	HookBeforeTxBroadcast PluginHook = "before_tx_broadcast"
	HookAfterTxBroadcast  PluginHook = "after_tx_broadcast"
	HookTxConfirmed      PluginHook = "tx_confirmed"
	HookTxFailed         PluginHook = "tx_failed"
	HookGasOptimization  PluginHook = "gas_optimization"
)

type HookContext struct {
	Context     context.Context
	PluginID    string
	Hook        PluginHook
	Data        map[string]interface{}
	StopPropagation bool
}

type HookHandler func(ctx *HookContext) error

type HookPlugin interface {
	Plugin
	GetHooks() map[PluginHook]HookHandler
}

type PluginManager struct {
	plugins       map[string]Plugin
	hooks         map[PluginHook][]HookPlugin
	pluginConfigs map[string]map[string]interface{}
	mu            sync.RWMutex
	lifecycleHooks map[string]LifecycleHook
}

type LifecycleHook interface {
	OnPluginInit(plugin Plugin) error
	OnPluginStart(plugin Plugin) error
	OnPluginStop(plugin Plugin) error
}

func NewPluginManager() *PluginManager {
	return &PluginManager{
		plugins:       make(map[string]Plugin),
		hooks:         make(map[PluginHook][]HookPlugin),
		pluginConfigs: make(map[string]map[string]interface{}),
		lifecycleHooks: make(map[string]LifecycleHook),
	}
}

func (pm *PluginManager) Register(plugin Plugin, config map[string]interface{}) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	id := plugin.ID()
	if _, exists := pm.plugins[id]; exists {
		return fmt.Errorf("plugin %s already registered", id)
	}

	pm.plugins[id] = plugin
	pm.pluginConfigs[id] = config

	if hookPlugin, ok := plugin.(HookPlugin); ok {
		for hook := range hookPlugin.GetHooks() {
			pm.hooks[hook] = append(pm.hooks[hook], hookPlugin)
		}
	}

	logger.Info("plugin registered",
		zap.String("plugin_id", id),
		zap.String("name", plugin.Name()),
		zap.String("version", plugin.Version()))

	return nil
}

func (pm *PluginManager) Unregister(pluginID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	plugin, exists := pm.plugins[pluginID]
	if !exists {
		return fmt.Errorf("plugin %s not found", pluginID)
	}

	_ = plugin.Stop(context.Background())
	delete(pm.plugins, pluginID)
	delete(pm.pluginConfigs, pluginID)

	for hook, plugins := range pm.hooks {
		newPlugins := make([]HookPlugin, 0)
		for _, p := range plugins {
			if p.ID() != pluginID {
				newPlugins = append(newPlugins, p)
			}
		}
		pm.hooks[hook] = newPlugins
	}

	logger.Info("plugin unregistered", zap.String("plugin_id", pluginID))
	return nil
}

func (pm *PluginManager) InitAll(ctx context.Context) error {
	pm.mu.RLock()
	plugins := make([]Plugin, 0, len(pm.plugins))
	for _, p := range pm.plugins {
		plugins = append(plugins, p)
	}
	pm.mu.RUnlock()

	for _, plugin := range plugins {
		config := pm.pluginConfigs[plugin.ID()]
		if err := plugin.Init(ctx, config); err != nil {
			logger.Error("plugin init failed",
				zap.String("plugin_id", plugin.ID()),
				zap.Error(err))
			return err
		}

		if hook, ok := pm.lifecycleHooks["init"]; ok {
			_ = hook.OnPluginInit(plugin)
		}
	}

	return nil
}

func (pm *PluginManager) StartAll(ctx context.Context) error {
	pm.mu.RLock()
	plugins := make([]Plugin, 0, len(pm.plugins))
	for _, p := range pm.plugins {
		plugins = append(plugins, p)
	}
	pm.mu.RUnlock()

	for _, plugin := range plugins {
		if err := plugin.Start(ctx); err != nil {
			logger.Error("plugin start failed",
				zap.String("plugin_id", plugin.ID()),
				zap.Error(err))
			return err
		}

		if hook, ok := pm.lifecycleHooks["start"]; ok {
			_ = hook.OnPluginStart(plugin)
		}
	}

	return nil
}

func (pm *PluginManager) StopAll(ctx context.Context) error {
	pm.mu.RLock()
	plugins := make([]Plugin, 0, len(pm.plugins))
	for _, p := range pm.plugins {
		plugins = append(plugins, p)
	}
	pm.mu.RUnlock()

	for _, plugin := range plugins {
		if err := plugin.Stop(ctx); err != nil {
			logger.Error("plugin stop failed",
				zap.String("plugin_id", plugin.ID()),
				zap.Error(err))
		}

		if hook, ok := pm.lifecycleHooks["stop"]; ok {
			_ = hook.OnPluginStop(plugin)
		}
	}

	return nil
}

func (pm *PluginManager) Enable(pluginID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	plugin, exists := pm.plugins[pluginID]
	if !exists {
		return fmt.Errorf("plugin %s not found", pluginID)
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Second*10)
	defer cancel()

	return plugin.Start(ctx)
}

func (pm *PluginManager) Disable(pluginID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	plugin, exists := pm.plugins[pluginID]
	if !exists {
		return fmt.Errorf("plugin %s not found", pluginID)
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Second*10)
	defer cancel()

	return plugin.Stop(ctx)
}

func (pm *PluginManager) ExecuteHook(ctx context.Context, hook PluginHook, data map[string]interface{}) (*HookContext, error) {
	pm.mu.RLock()
	hookPlugins, exists := pm.hooks[hook]
	pm.mu.RUnlock()

	if !exists {
		return &HookContext{
			Context: ctx,
			Hook:    hook,
			Data:    data,
		}, nil
	}

	hookCtx := &HookContext{
		Context:     ctx,
		Hook:        hook,
		Data:        data,
		StopPropagation: false,
	}

	for _, plugin := range hookPlugins {
		if plugin.State() != PluginStateRunning {
			continue
		}

		hooks := plugin.GetHooks()
		if handler, ok := hooks[hook]; ok {
			hookCtx.PluginID = plugin.ID()
			start := time.Now()

			if err := handler(hookCtx); err != nil {
				logger.Error("hook execution failed",
					zap.String("plugin_id", plugin.ID()),
					zap.String("hook", string(hook)),
					zap.Error(err))
			}

			logger.Debug("hook executed",
				zap.String("plugin_id", plugin.ID()),
				zap.String("hook", string(hook)),
				zap.Duration("duration", time.Since(start)))

			if hookCtx.StopPropagation {
				break
			}
		}
	}

	return hookCtx, nil
}

func (pm *PluginManager) GetPlugin(pluginID string) (Plugin, error) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	plugin, exists := pm.plugins[pluginID]
	if !exists {
		return nil, fmt.Errorf("plugin %s not found", pluginID)
	}
	return plugin, nil
}

func (pm *PluginManager) ListPlugins() []PluginInfo {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	infos := make([]PluginInfo, 0, len(pm.plugins))
	for _, p := range pm.plugins {
		infos = append(infos, PluginInfo{
			ID:          p.ID(),
			Name:        p.Name(),
			Version:     p.Version(),
			Description: p.Description(),
			State:       p.State(),
		})
	}
	return infos
}

type PluginInfo struct {
	ID          string      `json:"id"`
	Name        string      `json:"name"`
	Version     string      `json:"version"`
	Description string      `json:"description"`
	State       PluginState `json:"state"`
}

type BasePlugin struct {
	id          string
	name        string
	version     string
	description string
	state       PluginState
	config      map[string]interface{}
	mu          sync.Mutex
}

func (p *BasePlugin) ID() string          { return p.id }
func (p *BasePlugin) Name() string        { return p.name }
func (p *BasePlugin) Version() string     { return p.version }
func (p *BasePlugin) Description() string { return p.description }
func (p *BasePlugin) State() PluginState  { return p.state }
func (p *BasePlugin) SetState(state PluginState) { p.state = state }

func (p *BasePlugin) BaseInit(ctx context.Context, config map[string]interface{}) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.config = config
	p.state = PluginStateEnabled
	return nil
}

func (p *BasePlugin) BaseStart(ctx context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.state = PluginStateRunning
	return nil
}

func (p *BasePlugin) BaseStop(ctx context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.state = PluginStateEnabled
	return nil
}
