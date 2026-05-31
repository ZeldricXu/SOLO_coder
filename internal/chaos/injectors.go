package chaos

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type Injector interface {
	Inject(ctx context.Context, scope *common.InjectionScope, params map[string]interface{}) error
	Rollback(ctx context.Context, runID string) error
	Type() string
}

type DelayInjector struct {
	mu        sync.Mutex
	running   map[string]context.CancelFunc
}

func NewDelayInjector() *DelayInjector {
	return &DelayInjector{
		running: make(map[string]context.CancelFunc),
	}
}

func (i *DelayInjector) Type() string { return "network_delay" }

func (i *DelayInjector) Inject(ctx context.Context, scope *common.InjectionScope, params map[string]interface{}) error {
	delayMs, ok := params["delay_ms"].(int)
	if !ok {
		delayMs = 100
	}
	jitterMs, _ := params["jitter_ms"].(int)

	runID := fmt.Sprintf("delay-%d", time.Now().UnixNano())

	common.Info("delay injection started",
		zap.String("run_id", runID),
		zap.Int("delay_ms", delayMs),
		zap.Int("jitter_ms", jitterMs),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *DelayInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		common.Info("delay injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}

type FailureInjector struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
}

func NewFailureInjector() *FailureInjector {
	return &FailureInjector{
		running: make(map[string]context.CancelFunc),
	}
}

func (i *FailureInjector) Type() string { return "network_failure" }

func (i *FailureInjector) Inject(ctx context.Context, scope *common.InjectionScope, params map[string]interface{}) error {
	rate, ok := params["failure_rate"].(float64)
	if !ok {
		rate = 0.1
	}
	errorCode, _ := params["error_code"].(int)
	if errorCode == 0 {
		errorCode = 500
	}

	runID := fmt.Sprintf("failure-%d", time.Now().UnixNano())

	common.Info("failure injection started",
		zap.String("run_id", runID),
		zap.Float64("failure_rate", rate),
		zap.Int("error_code", errorCode),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *FailureInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		common.Info("failure injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}

type CPUStressInjector struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
}

func NewCPUStressInjector() *CPUStressInjector {
	return &CPUStressInjector{
		running: make(map[string]context.CancelFunc),
	}
}

func (i *CPUStressInjector) Type() string { return "cpu_stress" }

func (i *CPUStressInjector) Inject(ctx context.Context, scope *common.InjectionScope, params map[string]interface{}) error {
	load, ok := params["load_percent"].(int)
	if !ok {
		load = 80
	}

	runID := fmt.Sprintf("cpu-%d", time.Now().UnixNano())
	ctx, cancel := context.WithCancel(ctx)

	i.mu.Lock()
	i.running[runID] = cancel
	i.mu.Unlock()

	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				common.Info("cpu stress stopped",
					zap.String("run_id", runID),
				)
				return
			case <-ticker.C:
			}
		}
	}()

	common.Info("cpu stress injection started",
		zap.String("run_id", runID),
		zap.Int("load_percent", load),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *CPUStressInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		common.Info("cpu stress injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}

type MemoryStressInjector struct {
	mu        sync.Mutex
	running   map[string]context.CancelFunc
	allocations map[string][][]byte
}

func NewMemoryStressInjector() *MemoryStressInjector {
	return &MemoryStressInjector{
		running:     make(map[string]context.CancelFunc),
		allocations: make(map[string][][]byte),
	}
}

func (i *MemoryStressInjector) Type() string { return "memory_stress" }

func (i *MemoryStressInjector) Inject(ctx context.Context, scope *common.InjectionScope, params map[string]interface{}) error {
	sizeMB, ok := params["size_mb"].(int)
	if !ok {
		sizeMB = 512
	}

	runID := fmt.Sprintf("mem-%d", time.Now().UnixNano())
	ctx, cancel := context.WithCancel(ctx)

	allocs := make([][]byte, 0, sizeMB)
	for j := 0; j < sizeMB; j++ {
		allocs = append(allocs, make([]byte, 1024*1024))
	}

	i.mu.Lock()
	i.running[runID] = cancel
	i.allocations[runID] = allocs
	i.mu.Unlock()

	go func() {
		<-ctx.Done()
		i.mu.Lock()
		delete(i.allocations, runID)
		i.mu.Unlock()
	}()

	common.Info("memory stress injection started",
		zap.String("run_id", runID),
		zap.Int("size_mb", sizeMB),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *MemoryStressInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		common.Info("memory stress injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}

type DiskFailureInjector struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
}

func NewDiskFailureInjector() *DiskFailureInjector {
	return &DiskFailureInjector{
		running: make(map[string]context.CancelFunc),
	}
}

func (i *DiskFailureInjector) Type() string { return "disk_failure" }

func (i *DiskFailureInjector) Inject(ctx context.Context, scope *common.InjectionScope, params map[string]interface{}) error {
	mode, _ := params["mode"].(string)
	if mode == "" {
		mode = "read_only"
	}

	runID := fmt.Sprintf("disk-%d", time.Now().UnixNano())

	common.Info("disk failure injection started",
		zap.String("run_id", runID),
		zap.String("mode", mode),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *DiskFailureInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		common.Info("disk failure injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}

type InjectorFactory struct {
	injectors map[string]Injector
}

func NewInjectorFactory() *InjectorFactory {
	f := &InjectorFactory{
		injectors: make(map[string]Injector),
	}
	f.register(NewDelayInjector())
	f.register(NewFailureInjector())
	f.register(NewCPUStressInjector())
	f.register(NewMemoryStressInjector())
	f.register(NewDiskFailureInjector())
	return f
}

func (f *InjectorFactory) register(inj Injector) {
	f.injectors[inj.Type()] = inj
}

func (f *InjectorFactory) Get(injectorType string) (Injector, error) {
	inj, exists := f.injectors[injectorType]
	if !exists {
		return nil, common.NewValidationError("unsupported injector type", injectorType)
	}
	return inj, nil
}

func (f *InjectorFactory) ListTypes() []string {
	types := make([]string, 0, len(f.injectors))
	for t := range f.injectors {
		types = append(types, t)
	}
	return types
}
