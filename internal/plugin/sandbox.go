package plugin

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/dop251/goja"
	"github.com/dop251/goja_nodejs/console"
	"github.com/dop251/goja_nodejs/require"

	"github.com/solocoder/knowledgebase/internal/models"
)

type SandboxConfig struct {
	Timeout     time.Duration
	MemoryLimit int64
	PluginPath  string
}

type Sandbox struct {
	vm       *goja.Runtime
	cfg      SandboxConfig
	plugin   *models.Plugin
	api      *PluginAPI
	mu       sync.Mutex
	ctx      context.Context
	cancel   context.CancelFunc
	running  bool
	eventBus *EventBus
}

type EventBus struct {
	listeners map[string][]goja.Callable
	mu        sync.RWMutex
}

func NewEventBus() *EventBus {
	return &EventBus{
		listeners: make(map[string][]goja.Callable),
	}
}

func (eb *EventBus) On(event string, callback goja.Callable) {
	eb.mu.Lock()
	defer eb.mu.Unlock()
	eb.listeners[event] = append(eb.listeners[event], callback)
}

func (eb *EventBus) Emit(vm *goja.Runtime, event string, args ...interface{}) []goja.Value {
	eb.mu.RLock()
	callbacks := make([]goja.Callable, len(eb.listeners[event]))
	copy(callbacks, eb.listeners[event])
	eb.mu.RUnlock()

	var results []goja.Value
	for _, cb := range callbacks {
		jsArgs := make([]goja.Value, len(args))
		for i, arg := range args {
			jsArgs[i] = vm.ToValue(arg)
		}
		result, err := cb(goja.Undefined(), jsArgs...)
		if err == nil {
			results = append(results, result)
		}
	}
	return results
}

func NewSandbox(plugin *models.Plugin, cfg SandboxConfig, api *PluginAPI) *Sandbox {
	if cfg.Timeout == 0 {
		cfg.Timeout = 5 * time.Second
	}
	if cfg.MemoryLimit == 0 {
		cfg.MemoryLimit = 64 * 1024 * 1024
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Sandbox{
		cfg:      cfg,
		plugin:   plugin,
		api:      api,
		ctx:      ctx,
		cancel:   cancel,
		eventBus: NewEventBus(),
	}
}

func (s *Sandbox) Init() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.vm = goja.New()

	new(require.Registry).Enable(s.vm)
	console.Enable(s.vm)

	s.setupSandboxRestrictions()
	s.exposeAPI()

	return nil
}

func (s *Sandbox) setupSandboxRestrictions() {
	vm := s.vm

	vm.Set("os", nil)
	vm.Set("syscall", nil)
	vm.Set("net", nil)
	vm.Set("http", nil)
	vm.Set("child_process", nil)
	vm.Set("fs", nil)
	vm.Set("process", nil)

	vm.GlobalObject().Delete("eval")

	global := vm.GlobalObject()
	set := func(name string, val interface{}) {
		global.Set(name, val)
	}

	pluginDir := s.plugin.Path

	readFile := func(call goja.FunctionCall) goja.Value {
		filename := call.Argument(0).String()
		if strings.Contains(filename, "..") || filepath.IsAbs(filename) {
			panic(vm.ToValue("access denied: cannot read files outside plugin directory"))
		}
		fullPath := filepath.Join(pluginDir, filename)
		rel, err := filepath.Rel(pluginDir, fullPath)
		if err != nil || strings.HasPrefix(rel, "..") {
			panic(vm.ToValue("access denied: cannot read files outside plugin directory"))
		}
		data, err := os.ReadFile(fullPath)
		if err != nil {
			panic(vm.ToValue(err.Error()))
		}
		return vm.ToValue(string(data))
	}
	set("__readPluginFile", readFile)

	fileExists := func(call goja.FunctionCall) goja.Value {
		filename := call.Argument(0).String()
		if strings.Contains(filename, "..") || filepath.IsAbs(filename) {
			return vm.ToValue(false)
		}
		fullPath := filepath.Join(pluginDir, filename)
		rel, err := filepath.Rel(pluginDir, fullPath)
		if err != nil || strings.HasPrefix(rel, "..") {
			return vm.ToValue(false)
		}
		_, err = os.Stat(fullPath)
		return vm.ToValue(err == nil)
	}
	set("__fileExists", fileExists)

	writeFile := func(call goja.FunctionCall) goja.Value {
		filename := call.Argument(0).String()
		content := call.Argument(1).String()
		if strings.Contains(filename, "..") || filepath.IsAbs(filename) {
			panic(vm.ToValue("access denied: cannot write files outside plugin directory"))
		}
		fullPath := filepath.Join(pluginDir, filename)
		rel, err := filepath.Rel(pluginDir, fullPath)
		if err != nil || strings.HasPrefix(rel, "..") {
			panic(vm.ToValue("access denied: cannot write files outside plugin directory"))
		}
		if !strings.HasPrefix(filepath.Base(fullPath), ".") {
			panic(vm.ToValue("access denied: can only write to plugin directory"))
		}
		err = os.WriteFile(fullPath, []byte(content), 0644)
		if err != nil {
			panic(vm.ToValue(err.Error()))
		}
		return goja.Undefined()
	}
	set("__writePluginFile", writeFile)
}

func (s *Sandbox) exposeAPI() {
	vm := s.vm
	api := s.api

	appObj := vm.NewObject()

	appObj.Set("registerCommand", func(call goja.FunctionCall) goja.Value {
		name := call.Argument(0).String()
		callback, ok := goja.AssertFunction(call.Argument(1))
		if !ok {
			panic(vm.ToValue("callback must be a function"))
		}
		api.RegisterCommand(s.plugin.ID, name, callback, vm)
		return goja.Undefined()
	})

	appObj.Set("registerView", func(call goja.FunctionCall) goja.Value {
		viewID := call.Argument(0).String()
		opts := call.Argument(1).ToObject(vm)
		api.RegisterView(s.plugin.ID, viewID, opts, vm)
		return goja.Undefined()
	})

	appObj.Set("getNote", func(call goja.FunctionCall) goja.Value {
		path := call.Argument(0).String()
		note, err := api.GetNote(path)
		if err != nil {
			panic(vm.ToValue(err.Error()))
		}
		return vm.ToValue(note)
	})

	appObj.Set("searchNotes", func(call goja.FunctionCall) goja.Value {
		query := call.Argument(0).String()
		results, err := api.SearchNotes(query)
		if err != nil {
			panic(vm.ToValue(err.Error()))
		}
		return vm.ToValue(results)
	})

	appObj.Set("getTags", func(call goja.FunctionCall) goja.Value {
		tags, err := api.GetTags()
		if err != nil {
			panic(vm.ToValue(err.Error()))
		}
		return vm.ToValue(tags)
	})

	appObj.Set("sendNotification", func(call goja.FunctionCall) goja.Value {
		title := call.Argument(0).String()
		message := call.Argument(1).String()
		api.SendNotification(s.plugin.ID, title, message)
		return goja.Undefined()
	})

	appObj.Set("getSetting", func(call goja.FunctionCall) goja.Value {
		key := call.Argument(0).String()
		defaultVal := call.Argument(1)
		val, err := api.GetSetting(s.plugin.ID, key)
		if err != nil {
			if defaultVal != nil {
				return defaultVal
			}
			panic(vm.ToValue(err.Error()))
		}
		return vm.ToValue(val)
	})

	appObj.Set("setSetting", func(call goja.FunctionCall) goja.Value {
		key := call.Argument(0).String()
		value := call.Argument(1).Export()
		err := api.SetSetting(s.plugin.ID, key, value)
		if err != nil {
			panic(vm.ToValue(err.Error()))
		}
		return goja.Undefined()
	})

	appObj.Set("on", func(call goja.FunctionCall) goja.Value {
		event := call.Argument(0).String()
		callback, ok := goja.AssertFunction(call.Argument(1))
		if !ok {
			panic(vm.ToValue("callback must be a function"))
		}
		s.eventBus.On(event, callback)
		return goja.Undefined()
	})

	appObj.Set("emit", func(call goja.FunctionCall) goja.Value {
		event := call.Argument(0).String()
		var args []interface{}
		for i := 1; i < len(call.Arguments); i++ {
			args = append(args, call.Argument(i).Export())
		}
		s.eventBus.Emit(s.vm, event, args...)
		return goja.Undefined()
	})

	appObj.Set("id", s.plugin.ID)
	appObj.Set("name", s.plugin.Name)
	appObj.Set("version", s.plugin.Version)

	vm.Set("app", appObj)
}

func (s *Sandbox) Run(script string) (goja.Value, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.vm == nil {
		return nil, errors.New("sandbox not initialized")
	}

	done := make(chan struct{})
	var result goja.Value
	var runErr error

	go func() {
		defer close(done)
		defer func() {
			if r := recover(); r != nil {
				runErr = fmt.Errorf("plugin panic: %v", r)
			}
		}()

		val, err := s.vm.RunString(script)
		result = val
		runErr = err
	}()

	select {
	case <-done:
		return result, runErr
	case <-time.After(s.cfg.Timeout):
		s.vm.Interrupt("execution timeout")
		return nil, fmt.Errorf("script execution timed out after %v", s.cfg.Timeout)
	case <-s.ctx.Done():
		s.vm.Interrupt("execution cancelled")
		return nil, errors.New("execution cancelled")
	}
}

func (s *Sandbox) RunFile(filename string) (goja.Value, error) {
	data, err := os.ReadFile(filename)
	if err != nil {
		return nil, fmt.Errorf("failed to read file: %w", err)
	}
	return s.Run(string(data))
}

func (s *Sandbox) CallFunction(name string, args ...interface{}) (goja.Value, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.vm == nil {
		return nil, errors.New("sandbox not initialized")
	}

	fn, ok := goja.AssertFunction(s.vm.Get(name))
	if !ok {
		return nil, fmt.Errorf("function %s not found", name)
	}

	jsArgs := make([]goja.Value, len(args))
	for i, arg := range args {
		jsArgs[i] = s.vm.ToValue(arg)
	}

	done := make(chan struct{})
	var result goja.Value
	var callErr error

	go func() {
		defer close(done)
		defer func() {
			if r := recover(); r != nil {
				callErr = fmt.Errorf("plugin panic: %v", r)
			}
		}()
		val, err := fn(goja.Undefined(), jsArgs...)
		result = val
		callErr = err
	}()

	select {
	case <-done:
		return result, callErr
	case <-time.After(s.cfg.Timeout):
		s.vm.Interrupt("execution timeout")
		return nil, fmt.Errorf("function call timed out after %v", s.cfg.Timeout)
	case <-s.ctx.Done():
		s.vm.Interrupt("execution cancelled")
		return nil, errors.New("execution cancelled")
	}
}

func (s *Sandbox) TriggerEvent(event string, data interface{}) []goja.Value {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.vm == nil {
		return nil
	}

	return s.eventBus.Emit(s.vm, event, data)
}

func (s *Sandbox) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

func (s *Sandbox) Destroy() {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.cancel()

	if s.vm != nil {
		s.vm.Interrupt("destroy")
		s.vm = nil
	}

	s.running = false
}
