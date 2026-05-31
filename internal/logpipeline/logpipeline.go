package logpipeline

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"regexp"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type LogFilter interface {
	Filter(entry *models.LogEntry) bool
}

type LogRouter interface {
	Route(entry *models.LogEntry) []string
}

type LogOutput interface {
	Write(ctx context.Context, entry *models.LogEntry) error
}

type LevelFilter struct {
	MinLevel string
}

func (f *LevelFilter) Filter(entry *models.LogEntry) bool {
	levels := map[string]int{"debug": 1, "info": 2, "warn": 3, "error": 4, "fatal": 5}
	entryLevel := levels[entry.Level]
	minLevel := levels[f.MinLevel]
	if minLevel == 0 {
		minLevel = 1
	}
	return entryLevel >= minLevel
}

type RegexFilter struct {
	Pattern *regexp.Regexp
	Field   string
}

func (f *RegexFilter) Filter(entry *models.LogEntry) bool {
	var text string
	switch f.Field {
	case "message":
		text = entry.Message
	case "service":
		text = entry.Service
	default:
		if val, ok := entry.Fields[f.Field].(string); ok {
			text = val
		}
	}
	return f.Pattern.MatchString(text)
}

type ServiceRouter struct{}

func (r *ServiceRouter) Route(entry *models.LogEntry) []string {
	if entry.Service == "" {
		return []string{"default"}
	}
	return []string{entry.Service, "default"}
}

type ConsoleOutput struct{}

func (o *ConsoleOutput) Write(ctx context.Context, entry *models.LogEntry) error {
	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	logger.Info("log pipeline output", zap.String("entry", string(data)))
	return nil
}

type LogPipeline struct {
	inputs    chan models.LogEntry
	filters   []LogFilter
	router    LogRouter
	outputs   map[string]LogOutput
	wg        sync.WaitGroup
	stopped   chan struct{}
}

func NewLogPipeline() *LogPipeline {
	return &LogPipeline{
		inputs:  make(chan models.LogEntry, 10000),
		filters: make([]LogFilter, 0),
		router:  &ServiceRouter{},
		outputs: make(map[string]LogOutput),
		stopped: make(chan struct{}),
	}
}

func (lp *LogPipeline) Start() {
	lp.wg.Add(1)
	go lp.processLoop()
	logger.Info("log pipeline started")
}

func (lp *LogPipeline) Stop() {
	close(lp.stopped)
	lp.wg.Wait()
	close(lp.inputs)
	logger.Info("log pipeline stopped")
}

func (lp *LogPipeline) AddFilter(filter LogFilter) {
	lp.filters = append(lp.filters, filter)
}

func (lp *LogPipeline) SetRouter(router LogRouter) {
	lp.router = router
}

func (lp *LogPipeline) AddOutput(name string, output LogOutput) {
	lp.outputs[name] = output
}

func (lp *LogPipeline) Process(entry models.LogEntry) {
	if entry.ID == "" {
		entry.ID = uuid.New().String()
	}
	if entry.Timestamp.IsZero() {
		entry.Timestamp = time.Now()
	}
	select {
	case lp.inputs <- entry:
	default:
		logger.Warn("log pipeline input channel full, dropping log")
	}
}

func (lp *LogPipeline) processLoop() {
	defer lp.wg.Done()
	for {
		select {
		case entry := <-lp.inputs:
			lp.processEntry(&entry)
		case <-lp.stopped:
			return
		}
	}
}

func (lp *LogPipeline) processEntry(entry *models.LogEntry) {
	for _, filter := range lp.filters {
		if !filter.Filter(entry) {
			return
		}
	}
	destinations := []string{"default"}
	if lp.router != nil {
		destinations = lp.router.Route(entry)
	}
	for _, dest := range destinations {
		if output, ok := lp.outputs[dest]; ok {
			if err := output.Write(context.Background(), entry); err != nil {
				logger.Error("log output write failed", zap.String("dest", dest), zap.Error(err))
			}
		}
	}
}

func ParseJSONLog(raw string) (*models.LogEntry, error) {
	var entry models.LogEntry
	if err := json.Unmarshal([]byte(raw), &entry); err != nil {
		return nil, err
	}
	return &entry, nil
}

func ParseTextLog(raw string) *models.LogEntry {
	entry := &models.LogEntry{
		ID:        uuid.New().String(),
		Timestamp: time.Now(),
		Level:     "info",
		Message:   raw,
		Fields:    make(map[string]interface{}),
	}
	re := regexp.MustCompile(`\b(debug|info|warn|error|fatal)\b`)
	if match := re.FindString(raw); match != "" {
		entry.Level = match
	}
	return entry
}

type LogAggregator struct {
	serviceStats map[string]*ServiceLogStats
	mu           sync.RWMutex
}

type ServiceLogStats struct {
	Service    string
	TotalLogs  int64
	ErrorLogs  int64
	WarnLogs   int64
	InfoLogs   int64
	DebugLogs  int64
	LastUpdate time.Time
}

func NewLogAggregator() *LogAggregator {
	return &LogAggregator{
		serviceStats: make(map[string]*ServiceLogStats),
	}
}

func (la *LogAggregator) Process(entry *models.LogEntry) {
	la.mu.Lock()
	defer la.mu.Unlock()
	service := entry.Service
	if service == "" {
		service = "unknown"
	}
	stats, ok := la.serviceStats[service]
	if !ok {
		stats = &ServiceLogStats{Service: service}
		la.serviceStats[service] = stats
	}
	stats.TotalLogs++
	switch entry.Level {
	case "error":
		stats.ErrorLogs++
	case "warn":
		stats.WarnLogs++
	case "info":
		stats.InfoLogs++
	case "debug":
		stats.DebugLogs++
	}
	stats.LastUpdate = time.Now()
}

func (la *LogAggregator) GetStats() []ServiceLogStats {
	la.mu.RLock()
	defer la.mu.RUnlock()
	result := make([]ServiceLogStats, 0, len(la.serviceStats))
	for _, stats := range la.serviceStats {
		result = append(result, *stats)
	}
	return result
}
