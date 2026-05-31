package logpipeline

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type LogProcessor interface {
	Process(ctx context.Context, entry *common.LogEntry) (*common.LogEntry, error)
	Name() string
}

type LogFilter interface {
	Filter(ctx context.Context, entry *common.LogEntry) (bool, error)
	Name() string
}

type LogRouter interface {
	Route(ctx context.Context, entry *common.LogEntry) ([]string, error)
	Name() string
}

type LogOutput interface {
	Write(ctx context.Context, entry *common.LogEntry) error
	Name() string
	Close() error
}

type LogCollector interface {
	Collect(ctx context.Context) (<-chan *common.LogEntry, error)
	Name() string
	Close() error
}

type ConfigChangeListener func(oldConfig, newConfig *common.PipelineConfig)

type FileCollector struct {
	path       string
	outputChan chan *common.LogEntry
	stopChan   chan struct{}
	running    bool
	mu         sync.Mutex
}

func NewFileCollector(path string) *FileCollector {
	return &FileCollector{
		path:     path,
		stopChan: make(chan struct{}),
	}
}

func (c *FileCollector) Name() string {
	return "file_collector"
}

func (c *FileCollector) Collect(ctx context.Context) (<-chan *common.LogEntry, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.running {
		return c.outputChan, nil
	}

	c.outputChan = make(chan *common.LogEntry, 1000)
	c.running = true

	go func() {
		defer close(c.outputChan)
		defer func() {
			c.mu.Lock()
			c.running = false
			c.mu.Unlock()
		}()

		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-c.stopChan:
				return
			case <-ticker.C:
				entry := &common.LogEntry{
					ID:        common.NewID(),
					Timestamp: time.Now(),
					Level:     "info",
					Message:   fmt.Sprintf("Collected log from %s at %s", c.path, time.Now()),
					TraceID:   common.GenerateTraceID(),
					Service:   "file-collector",
					Fields: map[string]interface{}{
						"source": c.path,
						"line":   time.Now().Unix(),
					},
				}
				select {
				case c.outputChan <- entry:
				default:
				}
			}
		}
	}()

	logger.Info("File collector started", map[string]interface{}{"path": c.path})
	return c.outputChan, nil
}

func (c *FileCollector) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running {
		close(c.stopChan)
	}
	return nil
}

type JSONParser struct{}

func NewJSONParser() *JSONParser {
	return &JSONParser{}
}

func (p *JSONParser) Name() string {
	return "json_parser"
}

func (p *JSONParser) Process(ctx context.Context, entry *common.LogEntry) (*common.LogEntry, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	var data map[string]interface{}
	if err := json.Unmarshal([]byte(entry.Message), &data); err == nil {
		for k, v := range data {
			if k == "level" {
				if level, ok := v.(string); ok {
					entry.Level = strings.ToLower(level)
				}
			} else if k == "msg" || k == "message" {
				if msg, ok := v.(string); ok {
					entry.Message = msg
				}
			} else if k == "trace_id" || k == "traceId" {
				if tid, ok := v.(string); ok {
					entry.TraceID = tid
				}
			} else if k == "service" {
				if svc, ok := v.(string); ok {
					entry.Service = svc
				}
			} else {
				entry.Fields[k] = v
			}
		}
	}

	return entry, nil
}

type RegexParser struct {
	pattern *regexp.Regexp
}

func NewRegexParser(pattern string) (*RegexParser, error) {
	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil, err
	}
	return &RegexParser{pattern: re}, nil
}

func (p *RegexParser) Name() string {
	return "regex_parser"
}

func (p *RegexParser) Process(ctx context.Context, entry *common.LogEntry) (*common.LogEntry, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	matches := p.pattern.FindStringSubmatch(entry.Message)
	if len(matches) > 1 {
		names := p.pattern.SubexpNames()
		for i, name := range names {
			if i > 0 && name != "" && i < len(matches) {
				entry.Fields[name] = matches[i]
			}
		}
	}

	return entry, nil
}

type LevelFilter struct {
	minLevel logger.Level
}

func NewLevelFilter(level logger.Level) *LevelFilter {
	return &LevelFilter{minLevel: level}
}

func (f *LevelFilter) Name() string {
	return "level_filter"
}

func (f *LevelFilter) Filter(ctx context.Context, entry *common.LogEntry) (bool, error) {
	select {
	case <-ctx.Done():
		return false, ctx.Err()
	default:
	}

	level := parseLevel(entry.Level)
	return level >= f.minLevel, nil
}

func parseLevel(level string) logger.Level {
	switch strings.ToLower(level) {
	case "debug":
		return logger.DebugLevel
	case "info":
		return logger.InfoLevel
	case "warn", "warning":
		return logger.WarnLevel
	case "error":
		return logger.ErrorLevel
	case "fatal", "panic":
		return logger.FatalLevel
	default:
		return logger.InfoLevel
	}
}

type FieldFilter struct {
	field    string
	expected interface{}
	operator string
}

func NewFieldFilter(field string, operator string, expected interface{}) *FieldFilter {
	return &FieldFilter{
		field:    field,
		operator: operator,
		expected: expected,
	}
}

func (f *FieldFilter) Name() string {
	return "field_filter"
}

func (f *FieldFilter) Filter(ctx context.Context, entry *common.LogEntry) (bool, error) {
	select {
	case <-ctx.Done():
		return false, ctx.Err()
	default:
	}

	var value interface{}
	if entry.Fields != nil {
		value = entry.Fields[f.field]
	}

	switch f.operator {
	case "exists":
		return value != nil, nil
	case "not_exists":
		return value == nil, nil
	case "equals", "==":
		return fmt.Sprintf("%v", value) == fmt.Sprintf("%v", f.expected), nil
	case "not_equals", "!=":
		return fmt.Sprintf("%v", value) != fmt.Sprintf("%v", f.expected), nil
	case "contains":
		strVal := fmt.Sprintf("%v", value)
		strExp := fmt.Sprintf("%v", f.expected)
		return strings.Contains(strVal, strExp), nil
	case "regex":
		strVal := fmt.Sprintf("%v", value)
		strExp := fmt.Sprintf("%v", f.expected)
		matched, _ := regexp.MatchString(strExp, strVal)
		return matched, nil
	}

	return true, nil
}

type PatternRouter struct {
	routes map[string]*regexp.Regexp
}

func NewPatternRouter(routes map[string]string) (*PatternRouter, error) {
	pr := &PatternRouter{
		routes: make(map[string]*regexp.Regexp),
	}
	for name, pattern := range routes {
		re, err := regexp.Compile(pattern)
		if err != nil {
			return nil, err
		}
		pr.routes[name] = re
	}
	return pr, nil
}

func (r *PatternRouter) Name() string {
	return "pattern_router"
}

func (r *PatternRouter) Route(ctx context.Context, entry *common.LogEntry) ([]string, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	var destinations []string
	for name, pattern := range r.routes {
		if pattern.MatchString(entry.Message) {
			destinations = append(destinations, name)
		}
	}

	if len(destinations) == 0 {
		destinations = append(destinations, "default")
	}

	return destinations, nil
}

type LevelRouter struct{}

func NewLevelRouter() *LevelRouter {
	return &LevelRouter{}
}

func (r *LevelRouter) Name() string {
	return "level_router"
}

func (r *LevelRouter) Route(ctx context.Context, entry *common.LogEntry) ([]string, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	level := strings.ToLower(entry.Level)
	switch level {
	case "error", "fatal", "panic":
		return []string{"error", "default"}, nil
	case "warn", "warning":
		return []string{"warn", "default"}, nil
	default:
		return []string{"default"}, nil
	}
}

type ConsoleOutput struct{}

func NewConsoleOutput() *ConsoleOutput {
	return &ConsoleOutput{}
}

func (o *ConsoleOutput) Name() string {
	return "console_output"
}

func (o *ConsoleOutput) Write(ctx context.Context, entry *common.LogEntry) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	fmt.Println(string(data))
	return nil
}

func (o *ConsoleOutput) Close() error {
	return nil
}

type FileOutput struct {
	path string
	mu   sync.Mutex
}

func NewFileOutput(path string) *FileOutput {
	return &FileOutput{path: path}
}

func (o *FileOutput) Name() string {
	return "file_output"
}

func (o *FileOutput) Write(ctx context.Context, entry *common.LogEntry) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	o.mu.Lock()
	defer o.mu.Unlock()

	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}

	f, err := os.OpenFile(o.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	defer f.Close()

	_, err = f.Write(append(data, '\n'))
	return err
}

func (o *FileOutput) Close() error {
	return nil
}

type ConfigWatcher struct {
	configPath string
	interval   time.Duration
	lastMod    time.Time
	config     *common.PipelineConfig
	listeners  []ConfigChangeListener
	mu         sync.RWMutex
	stopChan   chan struct{}
	running    bool
}

func NewConfigWatcher(configPath string, interval time.Duration) *ConfigWatcher {
	if interval <= 0 {
		interval = 5 * time.Second
	}
	return &ConfigWatcher{
		configPath: configPath,
		interval:   interval,
		stopChan:   make(chan struct{}),
	}
}

func (w *ConfigWatcher) AddListener(listener ConfigChangeListener) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.listeners = append(w.listeners, listener)
}

func (w *ConfigWatcher) LoadConfig() (*common.PipelineConfig, error) {
	data, err := os.ReadFile(w.configPath)
	if err != nil {
		return nil, err
	}

	var config common.PipelineConfig
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, err
	}

	return &config, nil
}

func (w *ConfigWatcher) Start() error {
	config, err := w.LoadConfig()
	if err != nil {
		if !os.IsNotExist(err) {
			return err
		}
		config = &common.PipelineConfig{}
	}

	w.mu.Lock()
	w.config = config
	w.running = true
	w.mu.Unlock()

	info, err := os.Stat(w.configPath)
	if err == nil {
		w.lastMod = info.ModTime()
	}

	go w.watchLoop()

	logger.Info("Config watcher started", map[string]interface{}{
		"path":     w.configPath,
		"interval": w.interval.String(),
	})
	return nil
}

func (w *ConfigWatcher) watchLoop() {
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()

	for {
		select {
		case <-w.stopChan:
			return
		case <-ticker.C:
			w.checkConfigChange()
		}
	}
}

func (w *ConfigWatcher) checkConfigChange() {
	info, err := os.Stat(w.configPath)
	if err != nil {
		if os.IsNotExist(err) {
			return
		}
		logger.Warn("Config file stat error", map[string]interface{}{
			"path":  w.configPath,
			"error": err.Error(),
		})
		return
	}

	if !info.ModTime().After(w.lastMod) {
		return
	}

	newConfig, err := w.LoadConfig()
	if err != nil {
		logger.Warn("Failed to load updated config", map[string]interface{}{
			"path":  w.configPath,
			"error": err.Error(),
		})
		return
	}

	w.mu.RLock()
	oldConfig := w.config
	listeners := make([]ConfigChangeListener, len(w.listeners))
	copy(listeners, w.listeners)
	w.mu.RUnlock()

	w.mu.Lock()
	w.config = newConfig
	w.lastMod = info.ModTime()
	w.mu.Unlock()

	logger.Info("Config file changed, notifying listeners", map[string]interface{}{
		"path": w.configPath,
	})

	for _, listener := range listeners {
		func() {
			defer func() {
				if r := recover(); r != nil {
					logger.Error("Config listener panic", map[string]interface{}{
						"error": r,
					})
				}
			}()
			listener(oldConfig, newConfig)
		}()
	}
}

func (w *ConfigWatcher) GetConfig() *common.PipelineConfig {
	w.mu.RLock()
	defer w.mu.RUnlock()
	return w.config
}

func (w *ConfigWatcher) Stop() {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.running {
		close(w.stopChan)
		w.running = false
	}
}

type Pipeline struct {
	collectors   []LogCollector
	processors   []LogProcessor
	filters      []LogFilter
	routers      []LogRouter
	outputs      map[string]LogOutput
	inputChan    chan *common.LogEntry
	outputChan   chan *common.LogEntry
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
	running      bool
	mu           sync.Mutex
	configWatcher *ConfigWatcher
	configPath   string
	configReload bool
}

func NewPipeline() *Pipeline {
	ctx, cancel := context.WithCancel(context.Background())
	return &Pipeline{
		collectors: make([]LogCollector, 0),
		processors: make([]LogProcessor, 0),
		filters:    make([]LogFilter, 0),
		routers:    make([]LogRouter, 0),
		outputs:    make(map[string]LogOutput),
		inputChan:  make(chan *common.LogEntry, 10000),
		outputChan: make(chan *common.LogEntry, 10000),
		ctx:        ctx,
		cancel:     cancel,
	}
}

func NewPipelineWithHotReload(configPath string, watchInterval time.Duration) *Pipeline {
	p := NewPipeline()
	p.configPath = configPath
	p.configReload = true
	p.configWatcher = NewConfigWatcher(configPath, watchInterval)

	p.configWatcher.AddListener(func(oldConfig, newConfig *common.PipelineConfig) {
		p.reloadConfig(newConfig)
	})

	return p
}

func (p *Pipeline) reloadConfig(newConfig *common.PipelineConfig) {
	p.mu.Lock()
	defer p.mu.Unlock()

	logger.Info("Reloading pipeline configuration", map[string]interface{}{
		"collectors": len(newConfig.Collectors),
		"processors": len(newConfig.Processors),
		"filters":    len(newConfig.Filters),
		"routers":    len(newConfig.Routers),
		"outputs":    len(newConfig.Outputs),
	})

	newProcessors := make([]LogProcessor, 0, len(newConfig.Processors))
	for _, pc := range newConfig.Processors {
		processor, err := p.buildProcessor(pc)
		if err != nil {
			logger.Warn("Failed to build processor from config", map[string]interface{}{
				"type":  pc.Type,
				"error": err.Error(),
			})
			continue
		}
		newProcessors = append(newProcessors, processor)
	}

	newFilters := make([]LogFilter, 0, len(newConfig.Filters))
	for _, fc := range newConfig.Filters {
		filter, err := p.buildFilter(fc)
		if err != nil {
			logger.Warn("Failed to build filter from config", map[string]interface{}{
				"type":  fc.Type,
				"error": err.Error(),
			})
			continue
		}
		newFilters = append(newFilters, filter)
	}

	newRouters := make([]LogRouter, 0, len(newConfig.Routers))
	for _, rc := range newConfig.Routers {
		router, err := p.buildRouter(rc)
		if err != nil {
			logger.Warn("Failed to build router from config", map[string]interface{}{
				"type":  rc.Type,
				"error": err.Error(),
			})
			continue
		}
		newRouters = append(newRouters, router)
	}

	newOutputs := make(map[string]LogOutput)
	for _, oc := range newConfig.Outputs {
		output, err := p.buildOutput(oc)
		if err != nil {
			logger.Warn("Failed to build output from config", map[string]interface{}{
				"name":  oc.Name,
				"type":  oc.Type,
				"error": err.Error(),
			})
			continue
		}
		newOutputs[oc.Name] = output
	}

	oldOutputs := p.outputs
	p.processors = newProcessors
	p.filters = newFilters
	p.routers = newRouters
	p.outputs = newOutputs

	for name, output := range oldOutputs {
		if _, exists := newOutputs[name]; !exists {
			output.Close()
		}
	}

	logger.Info("Pipeline configuration reloaded successfully", map[string]interface{}{
		"processors": len(newProcessors),
		"filters":    len(newFilters),
		"routers":    len(newRouters),
		"outputs":    len(newOutputs),
	})
}

func (p *Pipeline) buildProcessor(config common.ProcessorConfig) (LogProcessor, error) {
	switch config.Type {
	case "json":
		return NewJSONParser(), nil
	case "regex":
		pattern, _ := config.Params["pattern"].(string)
		if pattern == "" {
			return nil, fmt.Errorf("regex processor requires 'pattern' param")
		}
		return NewRegexParser(pattern)
	default:
		return nil, fmt.Errorf("unknown processor type: %s", config.Type)
	}
}

func (p *Pipeline) buildFilter(config common.FilterConfig) (LogFilter, error) {
	switch config.Type {
	case "level":
		levelStr, _ := config.Params["level"].(string)
		if levelStr == "" {
			levelStr = "info"
		}
		level := parseLevel(levelStr)
		return NewLevelFilter(level), nil
	case "field":
		field, _ := config.Params["field"].(string)
		operator, _ := config.Params["operator"].(string)
		expected := config.Params["expected"]
		if field == "" {
			return nil, fmt.Errorf("field filter requires 'field' param")
		}
		return NewFieldFilter(field, operator, expected), nil
	default:
		return nil, fmt.Errorf("unknown filter type: %s", config.Type)
	}
}

func (p *Pipeline) buildRouter(config common.RouterConfig) (LogRouter, error) {
	switch config.Type {
	case "level":
		return NewLevelRouter(), nil
	case "pattern":
		routes := make(map[string]string)
		if routesParam, ok := config.Params["routes"]; ok {
			if routesMap, ok := routesParam.(map[string]interface{}); ok {
				for k, v := range routesMap {
					if strVal, ok := v.(string); ok {
						routes[k] = strVal
					}
				}
			}
		}
		if len(routes) == 0 {
			return nil, fmt.Errorf("pattern router requires 'routes' param")
		}
		return NewPatternRouter(routes)
	default:
		return nil, fmt.Errorf("unknown router type: %s", config.Type)
	}
}

func (p *Pipeline) buildOutput(config common.OutputConfig) (LogOutput, error) {
	switch config.Type {
	case "console":
		return NewConsoleOutput(), nil
	case "file":
		path, _ := config.Params["path"].(string)
		if path == "" {
			return nil, fmt.Errorf("file output requires 'path' param")
		}
		return NewFileOutput(path), nil
	default:
		return nil, fmt.Errorf("unknown output type: %s", config.Type)
	}
}

func (p *Pipeline) AddCollector(collector LogCollector) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.collectors = append(p.collectors, collector)
	logger.Info("Added log collector", map[string]interface{}{"name": collector.Name()})
}

func (p *Pipeline) AddProcessor(processor LogProcessor) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.processors = append(p.processors, processor)
	logger.Info("Added log processor", map[string]interface{}{"name": processor.Name()})
}

func (p *Pipeline) AddFilter(filter LogFilter) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.filters = append(p.filters, filter)
	logger.Info("Added log filter", map[string]interface{}{"name": filter.Name()})
}

func (p *Pipeline) AddRouter(router LogRouter) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.routers = append(p.routers, router)
	logger.Info("Added log router", map[string]interface{}{"name": router.Name()})
}

func (p *Pipeline) AddOutput(name string, output LogOutput) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.outputs[name] = output
	logger.Info("Added log output", map[string]interface{}{"name": name, "output": output.Name()})
}

func (p *Pipeline) Start() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.running {
		return nil
	}

	p.running = true

	if p.configReload && p.configWatcher != nil {
		if err := p.configWatcher.Start(); err != nil {
			logger.Warn("Config watcher failed to start", map[string]interface{}{
				"error": err.Error(),
			})
		}

		if config := p.configWatcher.GetConfig(); config != nil {
			p.mu.Unlock()
			p.reloadConfig(config)
			p.mu.Lock()
		}
	}

	for _, collector := range p.collectors {
		ch, err := collector.Collect(p.ctx)
		if err != nil {
			logger.Error("Failed to start collector", map[string]interface{}{
				"name":  collector.Name(),
				"error": err.Error(),
			})
			continue
		}

		p.wg.Add(1)
		go func(c LogCollector, in <-chan *common.LogEntry) {
			defer p.wg.Done()
			for entry := range in {
				select {
				case <-p.ctx.Done():
					return
				case p.inputChan <- entry:
				}
			}
		}(collector, ch)
	}

	for i := 0; i < 5; i++ {
		p.wg.Add(1)
		go p.processLoop(i)
	}

	for i := 0; i < 3; i++ {
		p.wg.Add(1)
		go p.outputLoop(i)
	}

	logger.Info("Log pipeline started", map[string]interface{}{
		"collectors":    len(p.collectors),
		"processors":    len(p.processors),
		"filters":       len(p.filters),
		"routers":       len(p.routers),
		"outputs":       len(p.outputs),
		"hot_reload":    p.configReload,
	})

	return nil
}

func (p *Pipeline) processLoop(id int) {
	defer p.wg.Done()

	for {
		select {
		case <-p.ctx.Done():
			return
		case entry := <-p.inputChan:
			processed := entry
			var err error

			p.mu.Lock()
			processors := make([]LogProcessor, len(p.processors))
			copy(processors, p.processors)
			filters := make([]LogFilter, len(p.filters))
			copy(filters, p.filters)
			p.mu.Unlock()

			for _, processor := range processors {
				processed, err = processor.Process(p.ctx, processed)
				if err != nil {
					logger.Warn("Processor failed", map[string]interface{}{
						"processor": processor.Name(),
						"error":     err.Error(),
					})
					break
				}
			}

			filtered := true
			for _, filter := range filters {
				pass, err := filter.Filter(p.ctx, processed)
				if err != nil {
					logger.Warn("Filter failed", map[string]interface{}{
						"filter": filter.Name(),
						"error":  err.Error(),
					})
					filtered = false
					break
				}
				if !pass {
					filtered = false
					break
				}
			}

			if filtered {
				select {
				case <-p.ctx.Done():
					return
				case p.outputChan <- processed:
				}
			}
		}
	}
}

func (p *Pipeline) outputLoop(id int) {
	defer p.wg.Done()

	for {
		select {
		case <-p.ctx.Done():
			return
		case entry := <-p.outputChan:
			p.mu.Lock()
			routers := make([]LogRouter, len(p.routers))
			copy(routers, p.routers)
			outputs := make(map[string]LogOutput, len(p.outputs))
			for k, v := range p.outputs {
				outputs[k] = v
			}
			p.mu.Unlock()

			destinations := []string{"default"}
			for _, router := range routers {
				dests, err := router.Route(p.ctx, entry)
				if err != nil {
					logger.Warn("Router failed", map[string]interface{}{
						"router": router.Name(),
						"error":  err.Error(),
					})
					continue
				}
				if len(dests) > 0 {
					destinations = dests
					break
				}
			}

			for _, dest := range destinations {
				if output, exists := outputs[dest]; exists {
					if err := output.Write(p.ctx, entry); err != nil {
						logger.Warn("Output failed", map[string]interface{}{
							"output": output.Name(),
							"dest":   dest,
							"error":  err.Error(),
						})
					}
				}
			}
		}
	}
}

func (p *Pipeline) Stop() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.running {
		return nil
	}

	if p.configWatcher != nil {
		p.configWatcher.Stop()
	}

	p.cancel()

	for _, collector := range p.collectors {
		collector.Close()
	}

	p.wg.Wait()

	for _, output := range p.outputs {
		output.Close()
	}

	p.running = false
	logger.Info("Log pipeline stopped")
	return nil
}

func (p *Pipeline) Ingest(entry *common.LogEntry) error {
	select {
	case <-p.ctx.Done():
		return p.ctx.Err()
	case p.inputChan <- entry:
		return nil
	default:
		return fmt.Errorf("input channel full")
	}
}

func (p *Pipeline) GetConfig() *common.PipelineConfig {
	if p.configWatcher != nil {
		return p.configWatcher.GetConfig()
	}
	return nil
}

func WritePipelineConfig(path string, config *common.PipelineConfig) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}

	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(path, data, 0644)
}
