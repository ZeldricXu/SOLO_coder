package collector

import (
	"context"
	"sync"

	"github.com/datateam/loganalyzer/internal/models"
)

type Collector interface {
	Start(ctx context.Context) error
	Stop() error
	Name() string
	Source() models.LogSource
	Output() <-chan *models.LogEvent
}

type BaseCollector struct {
	name     string
	source   models.LogSource
	output   chan *models.LogEvent
	wg       sync.WaitGroup
	stopCh   chan struct{}
	mu       sync.RWMutex
	running  bool
}

func NewBaseCollector(name string, source models.LogSource, bufferSize int) *BaseCollector {
	return &BaseCollector{
		name:    name,
		source:  source,
		output:  make(chan *models.LogEvent, bufferSize),
		stopCh:  make(chan struct{}),
		running: false,
	}
}

func (b *BaseCollector) Name() string {
	return b.name
}

func (b *BaseCollector) Source() models.LogSource {
	return b.source
}

func (b *BaseCollector) Output() <-chan *models.LogEvent {
	return b.output
}

func (b *BaseCollector) IsRunning() bool {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.running
}

func (b *BaseCollector) SetRunning(running bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.running = running
}

func (b *BaseCollector) Emit(event *models.LogEvent) {
	if event == nil {
		return
	}
	select {
	case b.output <- event:
	case <-b.stopCh:
	}
}

func (b *BaseCollector) Stop() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if !b.running {
		return nil
	}

	close(b.stopCh)
	b.wg.Wait()
	close(b.output)
	b.running = false
	return nil
}

type Manager struct {
	collectors []Collector
	output     chan *models.LogEvent
	mu         sync.RWMutex
	wg         sync.WaitGroup
	stopped    bool
	stopMu     sync.Mutex
	stopCh     chan struct{}
}

func NewManager(bufferSize int) *Manager {
	return &Manager{
		collectors: make([]Collector, 0),
		output:     make(chan *models.LogEvent, bufferSize),
		stopCh:     make(chan struct{}),
	}
}

func (m *Manager) AddCollector(c Collector) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.collectors = append(m.collectors, c)
}

func (m *Manager) Start(ctx context.Context) error {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, c := range m.collectors {
		m.wg.Add(1)
		go func(col Collector) {
			defer m.wg.Done()

			if err := col.Start(ctx); err != nil {
				return
			}

			for event := range col.Output() {
				select {
				case m.output <- event:
				case <-ctx.Done():
					return
				case <-m.stopCh:
					return
				}
			}
		}(c)
	}

	return nil
}

func (m *Manager) Stop() {
	m.stopMu.Lock()
	if m.stopped {
		m.stopMu.Unlock()
		return
	}
	m.stopped = true
	close(m.stopCh)
	m.stopMu.Unlock()

	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, c := range m.collectors {
		_ = c.Stop()
	}

	m.wg.Wait()
	close(m.output)
}

func (m *Manager) Output() <-chan *models.LogEvent {
	return m.output
}

func (m *Manager) Collectors() []Collector {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.collectors
}
