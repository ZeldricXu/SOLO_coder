package plugin

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/storage"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type PluginManager struct {
	cfg       *config.PluginConfig
	plugins   map[string]*PluginInstance
	mu        sync.RWMutex
	db        *gorm.DB
}

type PluginInstance struct {
	Plugin   *models.Plugin
	Client   StagePluginClient
	cmd      *exec.Cmd
	address  string
	lastUsed time.Time
}

type PluginRegistry struct {
	Plugins []PluginRegistryEntry `json:"plugins"`
}

type PluginRegistryEntry struct {
	Name       string `json:"name"`
	Version    string `json:"version"`
	Type       string `json:"type"`
	BinaryPath string `json:"binary_path"`
}

func NewPluginManager(cfg *config.PluginConfig) (*PluginManager, error) {
	if err := os.MkdirAll(cfg.Dir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create plugin dir: %w", err)
	}

	pm := &PluginManager{
		cfg:     cfg,
		plugins: make(map[string]*PluginInstance),
		db:      storage.GetDB(),
	}

	if err := pm.loadRegistry(); err != nil {
		logger.Warn("failed to load plugin registry", zap.Error(err))
	}

	return pm, nil
}

func (pm *PluginManager) loadRegistry() error {
	data, err := os.ReadFile(pm.cfg.RegistryFile)
	if err != nil {
		if os.IsNotExist(err) {
			return pm.saveRegistry()
		}
		return err
	}

	var registry PluginRegistry
	if err := json.Unmarshal(data, &registry); err != nil {
		return err
	}

	for _, entry := range registry.Plugins {
		if err := pm.registerFromRegistry(entry); err != nil {
			logger.Error("failed to register plugin from registry",
				zap.String("name", entry.Name),
				zap.Error(err))
		}
	}

	return nil
}

func (pm *PluginManager) saveRegistry() error {
	registry := PluginRegistry{}

	pm.mu.RLock()
	for _, inst := range pm.plugins {
		registry.Plugins = append(registry.Plugins, PluginRegistryEntry{
			Name:       inst.Plugin.Name,
			Version:    inst.Plugin.Version,
			Type:       string(inst.Plugin.Type),
			BinaryPath: inst.Plugin.BinaryPath,
		})
	}
	pm.mu.RUnlock()

	data, err := json.MarshalIndent(registry, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(pm.cfg.RegistryFile, data, 0644)
}

func (pm *PluginManager) registerFromRegistry(entry PluginRegistryEntry) error {
	var plugin models.Plugin
	err := pm.db.Where("name = ? AND version = ?", entry.Name, entry.Version).First(&plugin).Error
	if err == gorm.ErrRecordNotFound {
		plugin = models.Plugin{
			ID:         types.ID(NewPluginID()),
			Name:       entry.Name,
			Version:    entry.Version,
			Type:       types.StageType(entry.Type),
			BinaryPath: entry.BinaryPath,
			Status:     types.PluginStatusActive,
		}
		if err := pm.db.Create(&plugin).Error; err != nil {
			return err
		}
	}

	return nil
}

func (pm *PluginManager) Register(ctx context.Context, plugin *models.Plugin) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	key := fmt.Sprintf("%s@%s", plugin.Name, plugin.Version)
	if _, exists := pm.plugins[key]; exists {
		return fmt.Errorf("plugin %s already registered", key)
	}

	if err := pm.db.Create(plugin).Error; err != nil {
		return err
	}

	pm.plugins[key] = &PluginInstance{
		Plugin:   plugin,
		lastUsed: time.Now(),
	}

	return pm.saveRegistry()
}

func (pm *PluginManager) GetPlugin(ctx context.Context, name, version string) (*models.Plugin, error) {
	var plugin models.Plugin
	err := pm.db.Where("name = ? AND version = ?", name, version).First(&plugin).Error
	if err != nil {
		return nil, err
	}
	return &plugin, nil
}

func (pm *PluginManager) GetClient(ctx context.Context, name, version string) (StagePluginClient, error) {
	key := fmt.Sprintf("%s@%s", name, version)

	pm.mu.RLock()
	inst, exists := pm.plugins[key]
	pm.mu.RUnlock()

	if !exists {
		var plugin models.Plugin
		err := pm.db.Where("name = ? AND version = ?", name, version).First(&plugin).Error
		if err != nil {
			return nil, fmt.Errorf("plugin not found: %w", err)
		}

		pm.mu.Lock()
		inst = &PluginInstance{
			Plugin:   &plugin,
			lastUsed: time.Now(),
		}
		pm.plugins[key] = inst
		pm.mu.Unlock()
	}

	if inst.Client == nil {
		if err := pm.startPlugin(inst); err != nil {
			return nil, err
		}
	}

	inst.lastUsed = time.Now()
	return inst.Client, nil
}

func (pm *PluginManager) startPlugin(inst *PluginInstance) error {
	port := findAvailablePort()
	inst.address = fmt.Sprintf("localhost:%d", port)

	cmd := exec.Command(inst.Plugin.BinaryPath,
		"--grpc-address", inst.address,
		"--plugin-name", inst.Plugin.Name,
		"--plugin-version", inst.Plugin.Version,
	)

	cmd.SysProcAttr = &syscall.SysProcAttr{
		Setpgid: true,
	}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return err
	}

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("failed to start plugin: %w", err)
	}

	inst.cmd = cmd

	go func() {
		buf := make([]byte, 1024)
		for {
			n, err := stdout.Read(buf)
			if n > 0 {
				logger.Debug("plugin stdout",
					zap.String("plugin", inst.Plugin.Name),
					zap.String("output", string(buf[:n])))
			}
			if err != nil {
				break
			}
		}
	}()

	go func() {
		buf := make([]byte, 1024)
		for {
			n, err := stderr.Read(buf)
			if n > 0 {
				logger.Error("plugin stderr",
					zap.String("plugin", inst.Plugin.Name),
					zap.String("output", string(buf[:n])))
			}
			if err != nil {
				break
			}
		}
	}()

	var client StagePluginClient
	var connectErr error
	for i := 0; i < 30; i++ {
		client, connectErr = NewStagePluginClient(inst.address)
		if connectErr == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	if connectErr != nil {
		pm.stopPlugin(inst)
		return fmt.Errorf("failed to connect to plugin: %w", connectErr)
	}

	inst.Client = client
	logger.Info("plugin started successfully",
		zap.String("name", inst.Plugin.Name),
		zap.String("version", inst.Plugin.Version),
		zap.String("address", inst.address),
		zap.Int("pid", cmd.Process.Pid))

	go pm.monitorPlugin(inst)

	return nil
}

func (pm *PluginManager) stopPlugin(inst *PluginInstance) {
	if inst.Client != nil {
		inst.Client.Close()
		inst.Client = nil
	}

	if inst.cmd != nil && inst.cmd.Process != nil {
		if err := syscall.Kill(-inst.cmd.Process.Pid, syscall.SIGTERM); err != nil {
			logger.Warn("failed to terminate plugin process",
				zap.String("plugin", inst.Plugin.Name),
				zap.Error(err))

			time.AfterFunc(5*time.Second, func() {
				syscall.Kill(-inst.cmd.Process.Pid, syscall.SIGKILL)
			})
		}
		inst.cmd.Wait()
		inst.cmd = nil
	}
}

func (pm *PluginManager) monitorPlugin(inst *PluginInstance) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)

		if inst.Client == nil {
			cancel()
			return
		}

		health, err := inst.Client.HealthCheck(ctx, inst.Plugin.Name)
		cancel()

		if err != nil || !health.Healthy {
			logger.Warn("plugin health check failed, restarting",
				zap.String("plugin", inst.Plugin.Name),
				zap.Error(err))

			pm.mu.Lock()
			pm.stopPlugin(inst)
			if err := pm.startPlugin(inst); err != nil {
				logger.Error("failed to restart plugin",
					zap.String("plugin", inst.Plugin.Name),
					zap.Error(err))
			}
			pm.mu.Unlock()
		}

		if time.Since(inst.lastUsed) > 10*time.Minute {
			logger.Info("plugin idle timeout, stopping",
				zap.String("plugin", inst.Plugin.Name))

			pm.mu.Lock()
			pm.stopPlugin(inst)
			pm.mu.Unlock()
			return
		}
	}
}

func (pm *PluginManager) Unregister(ctx context.Context, name, version string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	key := fmt.Sprintf("%s@%s", name, version)
	inst, exists := pm.plugins[key]
	if !exists {
		return fmt.Errorf("plugin %s not found", key)
	}

	pm.stopPlugin(inst)
	delete(pm.plugins, key)

	if err := pm.db.Where("name = ? AND version = ?", name, version).Delete(&models.Plugin{}).Error; err != nil {
		return err
	}

	return pm.saveRegistry()
}

func (pm *PluginManager) List(ctx context.Context, pluginType *types.StageType) ([]*models.Plugin, error) {
	var plugins []*models.Plugin
	query := pm.db
	if pluginType != nil {
		query = query.Where("type = ?", *pluginType)
	}
	err := query.Find(&plugins).Error
	return plugins, err
}

func (pm *PluginManager) Install(ctx context.Context, name, version, binaryPath string, pluginType types.StageType) (*models.Plugin, error) {
	if _, err := os.Stat(binaryPath); err != nil {
		return nil, fmt.Errorf("binary not found: %w", err)
	}

	targetPath := filepath.Join(pm.cfg.Dir, fmt.Sprintf("%s-%s", name, version))
	if err := copyFile(binaryPath, targetPath); err != nil {
		return nil, fmt.Errorf("failed to copy binary: %w", err)
	}

	if err := os.Chmod(targetPath, 0755); err != nil {
		return nil, fmt.Errorf("failed to chmod binary: %w", err)
	}

	plugin := &models.Plugin{
		ID:         types.ID(NewPluginID()),
		Name:       name,
		Version:    version,
		Type:       pluginType,
		BinaryPath: targetPath,
		Status:     types.PluginStatusActive,
	}

	if err := pm.Register(ctx, plugin); err != nil {
		return nil, err
	}

	return plugin, nil
}

func (pm *PluginManager) Shutdown() {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	for key, inst := range pm.plugins {
		logger.Info("stopping plugin", zap.String("plugin", key))
		pm.stopPlugin(inst)
	}
}

func findAvailablePort() int {
	for port := 50051; port < 51051; port++ {
		addr := fmt.Sprintf("localhost:%d", port)
		ln, err := net.Listen("tcp", addr)
		if err == nil {
			ln.Close()
			return port
		}
	}
	return 50051
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0644)
}

func NewPluginID() string {
	return "plg_" + time.Now().Format("20060102") + "_" + randomString(8)
}

func randomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
	}
	return string(b)
}
