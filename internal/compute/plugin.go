package compute

import (
	"context"
	"fmt"
	"plugin"
	"runtime"
	"sync"
	"time"
)

const (
	PluginAPIVersion = "1.0.0"
	PluginSymbolName = "ObjectivePlugin"
)

type ObjectiveFunctionPlugin interface {
	Name() string
	Version() string
	APIVersion() string
	Evaluate(x []float64) float64
	Gradient(x []float64, grad []float64)
	Validate() error
	Close() error
}

type PluginInfo struct {
	Name        string
	Version     string
	APIVersion  string
	Path        string
	Loaded      time.Time
	Validated   bool
}

type PluginLoader struct {
	plugins    map[string]ObjectiveFunctionPlugin
	pluginInfo map[string]PluginInfo
	mu         sync.RWMutex
	timeout    time.Duration
	sandbox    bool
}

type PluginLoadError struct {
	Path    string
	Reason  string
	Err     error
}

func (e *PluginLoadError) Error() string {
	return fmt.Sprintf("plugin load error at %s: %s: %v", e.Path, e.Reason, e.Err)
}

func NewPluginLoader() *PluginLoader {
	return &PluginLoader{
		plugins:    make(map[string]ObjectiveFunctionPlugin),
		pluginInfo: make(map[string]PluginInfo),
		timeout:    30 * time.Second,
		sandbox:    true,
	}
}

func (pl *PluginLoader) SetTimeout(timeout time.Duration) {
	pl.timeout = timeout
}

func (pl *PluginLoader) SetSandbox(enabled bool) {
	pl.sandbox = enabled
}

func (pl *PluginLoader) Load(path string) (ObjectiveFunctionPlugin, error) {
	pl.mu.Lock()
	defer pl.mu.Unlock()

	if existing, ok := pl.plugins[path]; ok {
		return existing, nil
	}

	p, err := plugin.Open(path)
	if err != nil {
		return nil, &PluginLoadError{
			Path:   path,
			Reason: "failed to open plugin",
			Err:    err,
		}
	}

	sym, err := p.Lookup(PluginSymbolName)
	if err != nil {
		return nil, &PluginLoadError{
			Path:   path,
			Reason: fmt.Sprintf("failed to find symbol %s", PluginSymbolName),
			Err:    err,
		}
	}

	pluginImpl, ok := sym.(ObjectiveFunctionPlugin)
	if !ok {
		return nil, &PluginLoadError{
			Path:   path,
			Reason: "symbol does not implement ObjectiveFunctionPlugin interface",
			Err:    fmt.Errorf("expected ObjectiveFunctionPlugin, got %T", sym),
		}
	}

	if err := pl.checkVersion(pluginImpl); err != nil {
		return nil, &PluginLoadError{
			Path:   path,
			Reason: "version check failed",
			Err:    err,
		}
	}

	if err := pl.validatePlugin(pluginImpl); err != nil {
		return nil, &PluginLoadError{
			Path:   path,
			Reason: "plugin validation failed",
			Err:    err,
		}
	}

	pl.plugins[path] = pluginImpl
	pl.pluginInfo[path] = PluginInfo{
		Name:       pluginImpl.Name(),
		Version:    pluginImpl.Version(),
		APIVersion: pluginImpl.APIVersion(),
		Path:       path,
		Loaded:     time.Now(),
		Validated:  true,
	}

	return pluginImpl, nil
}

func (pl *PluginLoader) checkVersion(p ObjectiveFunctionPlugin) error {
	pluginAPI := p.APIVersion()
	if pluginAPI != PluginAPIVersion {
		return fmt.Errorf("incompatible API version: plugin has %s, expected %s",
			pluginAPI, PluginAPIVersion)
	}
	return nil
}

func (pl *PluginLoader) validatePlugin(p ObjectiveFunctionPlugin) error {
	if err := p.Validate(); err != nil {
		return fmt.Errorf("plugin self-validation failed: %w", err)
	}

	testX := []float64{1.0, 2.0, 3.0}
	testGrad := make([]float64, len(testX))

	if pl.sandbox {
		ctx, cancel := context.WithTimeout(context.Background(), pl.timeout)
		defer cancel()

		errChan := make(chan error, 2)

		go func() {
			defer func() {
				if r := recover(); r != nil {
					errChan <- fmt.Errorf("plugin panicked during Evaluate: %v", r)
				}
			}()
			_ = p.Evaluate(testX)
			errChan <- nil
		}()

		go func() {
			defer func() {
				if r := recover(); r != nil {
					errChan <- fmt.Errorf("plugin panicked during Gradient: %v", r)
				}
			}()
			p.Gradient(testX, testGrad)
			errChan <- nil
		}()

		for i := 0; i < 2; i++ {
			select {
			case err := <-errChan:
				if err != nil {
					return err
				}
			case <-ctx.Done():
				return fmt.Errorf("plugin validation timed out after %v", pl.timeout)
			}
		}
	} else {
		_ = p.Evaluate(testX)
		p.Gradient(testX, testGrad)
	}

	return nil
}

func (pl *PluginLoader) Unload(path string) error {
	pl.mu.Lock()
	defer pl.mu.Unlock()

	p, ok := pl.plugins[path]
	if !ok {
		return fmt.Errorf("plugin not loaded: %s", path)
	}

	if err := p.Close(); err != nil {
		return fmt.Errorf("plugin close error: %w", err)
	}

	delete(pl.plugins, path)
	delete(pl.pluginInfo, path)

	return nil
}

func (pl *PluginLoader) Get(path string) (ObjectiveFunctionPlugin, bool) {
	pl.mu.RLock()
	defer pl.mu.RUnlock()

	p, ok := pl.plugins[path]
	return p, ok
}

func (pl *PluginLoader) List() []PluginInfo {
	pl.mu.RLock()
	defer pl.mu.RUnlock()

	infos := make([]PluginInfo, 0, len(pl.pluginInfo))
	for _, info := range pl.pluginInfo {
		infos = append(infos, info)
	}
	return infos
}

func (pl *PluginLoader) CloseAll() error {
	pl.mu.Lock()
	defer pl.mu.Unlock()

	var errs []error
	for path, p := range pl.plugins {
		if err := p.Close(); err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", path, err))
		}
	}

	pl.plugins = make(map[string]ObjectiveFunctionPlugin)
	pl.pluginInfo = make(map[string]PluginInfo)

	if len(errs) > 0 {
		return fmt.Errorf("errors closing plugins: %v", errs)
	}
	return nil
}

type SandboxedPlugin struct {
	plugin  ObjectiveFunctionPlugin
	timeout time.Duration
}

func NewSandboxedPlugin(p ObjectiveFunctionPlugin, timeout time.Duration) *SandboxedPlugin {
	return &SandboxedPlugin{
		plugin:  p,
		timeout: timeout,
	}
}

func (sp *SandboxedPlugin) Name() string {
	return sp.plugin.Name()
}

func (sp *SandboxedPlugin) Version() string {
	return sp.plugin.Version()
}

func (sp *SandboxedPlugin) APIVersion() string {
	return sp.plugin.APIVersion()
}

func (sp *SandboxedPlugin) Evaluate(x []float64) (result float64) {
	ctx, cancel := context.WithTimeout(context.Background(), sp.timeout)
	defer cancel()

	done := make(chan struct{})
	panicked := make(chan interface{}, 1)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				panicked <- r
				close(done)
			}
		}()
		result = sp.plugin.Evaluate(x)
		close(done)
	}()

	select {
	case <-done:
		return result
	case r := <-panicked:
		panic(fmt.Sprintf("plugin panicked: %v", r))
	case <-ctx.Done():
		panic(fmt.Sprintf("plugin Evaluate timed out after %v", sp.timeout))
	}
}

func (sp *SandboxedPlugin) Gradient(x []float64, grad []float64) {
	ctx, cancel := context.WithTimeout(context.Background(), sp.timeout)
	defer cancel()

	done := make(chan struct{})
	panicked := make(chan interface{}, 1)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				panicked <- r
				close(done)
			}
		}()
		sp.plugin.Gradient(x, grad)
		close(done)
	}()

	select {
	case <-done:
		return
	case r := <-panicked:
		panic(fmt.Sprintf("plugin panicked: %v", r))
	case <-ctx.Done():
		panic(fmt.Sprintf("plugin Gradient timed out after %v", sp.timeout))
	}
}

func (sp *SandboxedPlugin) Validate() error {
	return sp.plugin.Validate()
}

func (sp *SandboxedPlugin) Close() error {
	return sp.plugin.Close()
}

type PluginWrapper struct {
	plugin ObjectiveFunctionPlugin
}

func NewPluginWrapper(p ObjectiveFunctionPlugin) *PluginWrapper {
	return &PluginWrapper{plugin: p}
}

func (pw *PluginWrapper) Objective() ObjectiveFunction {
	return func(x []float64) float64 {
		return pw.plugin.Evaluate(x)
	}
}

func (pw *PluginWrapper) Gradient() GradientFunction {
	return func(x []float64, grad []float64) {
		pw.plugin.Gradient(x, grad)
	}
}

func (pw *PluginWrapper) CreateEngine(dim int) *Engine {
	return NewEngine(dim, pw.Objective(), pw.Gradient())
}

func CheckOSSupport() error {
	if runtime.GOOS == "windows" {
		return fmt.Errorf("Go plugins are not supported on Windows")
	}
	return nil
}
