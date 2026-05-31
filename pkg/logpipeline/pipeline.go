package logpipeline

import (
	"context"
	"encoding/json"
	"go.uber.org/zap"
	"metricplatform/internal/models"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

type LogFilter func(entry *models.LogEntry) bool

type LogParser func(entry *models.LogEntry) (*models.LogEntry, error)

type LogRouter func(entry *models.LogEntry) []string

type Pipeline struct {
	input       chan *models.LogEntry
	filters     []LogFilter
	parsers     []LogParser
	router      LogRouter
	outputs     map[string]chan *models.LogEntry
	logger      *zap.Logger
	mu          sync.RWMutex
	wg          sync.WaitGroup
	ctx         context.Context
	cancel      context.CancelFunc
	concurrency int
	sem         chan struct{}
}

func NewPipeline(bufferSize int, concurrency int, logger *zap.Logger) *Pipeline {
	ctx, cancel := context.WithCancel(context.Background())
	return &Pipeline{
		input:       make(chan *models.LogEntry, bufferSize),
		filters:     make([]LogFilter, 0),
		parsers:     make([]LogParser, 0),
		outputs:     make(map[string]chan *models.LogEntry),
		logger:      logger,
		ctx:         ctx,
		cancel:      cancel,
		concurrency: concurrency,
		sem:         make(chan struct{}, concurrency),
	}
}

func (p *Pipeline) AddFilter(filter LogFilter) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.filters = append(p.filters, filter)
	p.logger.Info("Log filter added")
}

func (p *Pipeline) AddParser(parser LogParser) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.parsers = append(p.parsers, parser)
	p.logger.Info("Log parser added")
}

func (p *Pipeline) SetRouter(router LogRouter) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.router = router
	p.logger.Info("Log router set")
}

func (p *Pipeline) AddOutput(name string, bufferSize int) chan *models.LogEntry {
	p.mu.Lock()
	defer p.mu.Unlock()
	ch := make(chan *models.LogEntry, bufferSize)
	p.outputs[name] = ch
	p.logger.Info("Log output added", zap.String("name", name))
	return ch
}

func (p *Pipeline) Process(entry *models.LogEntry) error {
	if entry.ID == "" {
		entry.ID = uuid.New().String()
	}
	if entry.Timestamp.IsZero() {
		entry.Timestamp = time.Now()
	}

	select {
	case p.input <- entry:
		return nil
	default:
		return &PipelineError{Msg: "pipeline input buffer full"}
	}
}

func (p *Pipeline) Start() {
	p.wg.Add(p.concurrency)
	for i := 0; i < p.concurrency; i++ {
		go p.worker()
	}
	p.logger.Info("Log pipeline started", zap.Int("concurrency", p.concurrency))
}

func (p *Pipeline) Stop() {
	p.cancel()
	close(p.input)
	p.wg.Wait()

	p.mu.Lock()
	defer p.mu.Unlock()
	for _, ch := range p.outputs {
		close(ch)
	}
	p.logger.Info("Log pipeline stopped")
}

func (p *Pipeline) worker() {
	defer p.wg.Done()

	for {
		select {
		case <-p.ctx.Done():
			return
		case entry, ok := <-p.input:
			if !ok {
				return
			}
			p.processEntry(entry)
		}
	}
}

func (p *Pipeline) processEntry(entry *models.LogEntry) {
	p.sem <- struct{}{}
	defer func() { <-p.sem }()

	p.mu.RLock()
	filters := p.filters
	parsers := p.parsers
	router := p.router
	outputs := p.outputs
	p.mu.RUnlock()

	for _, filter := range filters {
		if !filter(entry) {
			p.logger.Debug("Log entry filtered out", zap.String("id", entry.ID))
			return
		}
	}

	var err error
	for _, parser := range parsers {
		entry, err = parser(entry)
		if err != nil {
			p.logger.Error("Log parsing failed", zap.Error(err), zap.String("id", entry.ID))
			return
		}
	}

	if router != nil {
		destinations := router(entry)
		for _, dest := range destinations {
			if ch, ok := outputs[dest]; ok {
				select {
				case ch <- entry:
				default:
					p.logger.Warn("Log output buffer full", zap.String("output", dest))
				}
			}
		}
	} else {
		for _, ch := range outputs {
			select {
			case ch <- entry:
			default:
				p.logger.Warn("Log output buffer full")
			}
		}
	}
}

func LevelFilter(minLevel string) LogFilter {
	levels := map[string]int{"debug": 0, "info": 1, "warning": 2, "error": 3, "critical": 4}
	minLevelInt := levels[strings.ToLower(minLevel)]

	return func(entry *models.LogEntry) bool {
		level, ok := levels[strings.ToLower(entry.Level)]
		if !ok {
			return true
		}
		return level >= minLevelInt
	}
}

func SourceFilter(sources []string) LogFilter {
	sourceSet := make(map[string]bool)
	for _, s := range sources {
		sourceSet[s] = true
	}

	return func(entry *models.LogEntry) bool {
		return sourceSet[entry.Source]
	}
}

func RegexFilter(pattern string) (LogFilter, error) {
	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil, err
	}

	return func(entry *models.LogEntry) bool {
		return re.MatchString(entry.Message)
	}, nil
}

func JSONParser() LogParser {
	return func(entry *models.LogEntry) (*models.LogEntry, error) {
		if !strings.HasPrefix(strings.TrimSpace(entry.Message), "{") {
			return entry, nil
		}

		var parsed map[string]interface{}
		if err := json.Unmarshal([]byte(entry.Message), &parsed); err != nil {
			return entry, nil
		}

		if entry.Parsed == nil {
			entry.Parsed = make(map[string]interface{})
		}
		for k, v := range parsed {
			entry.Parsed[k] = v
		}

		return entry, nil
	}
}

func PatternParser(pattern string) (LogParser, error) {
	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil, err
	}

	return func(entry *models.LogEntry) (*models.LogEntry, error) {
		matches := re.FindStringSubmatch(entry.Message)
		if matches == nil {
			return entry, nil
		}

		if entry.Parsed == nil {
			entry.Parsed = make(map[string]interface{})
		}
		for i, name := range re.SubexpNames() {
			if i > 0 && name != "" {
				entry.Parsed[name] = matches[i]
			}
		}

		return entry, nil
	}, nil
}

func ContentRouter(routes map[string]string) LogRouter {
	return func(entry *models.LogEntry) []string {
		var destinations []string
		for pattern, dest := range routes {
			if matched, _ := regexp.MatchString(pattern, entry.Message); matched {
				destinations = append(destinations, dest)
			}
		}
		if len(destinations) == 0 {
			destinations = append(destinations, "default")
		}
		return destinations
	}
}

func LevelRouter() LogRouter {
	return func(entry *models.LogEntry) []string {
		return []string{strings.ToLower(entry.Level)}
	}
}

type PipelineError struct {
	Msg string
}

func (e *PipelineError) Error() string {
	return e.Msg
}

func (p *Pipeline) GetOutputs() map[string]chan *models.LogEntry {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.outputs
}
