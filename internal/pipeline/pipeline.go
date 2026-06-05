package pipeline

import (
	"context"
	"fmt"
	"log"
	"net"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/oschwald/geoip2-golang"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type Rule interface {
	ID() string
	Name() string
	Type() string
	Enabled() bool
	Process(ctx context.Context, event *models.LogEvent) (*models.LogEvent, error)
	Reload(cfg config.PipelineRule) error
}

type BaseRule struct {
	id      string
	name    string
	typ     string
	enabled bool
	order   int
	cfg     map[string]interface{}
}

func (r *BaseRule) ID() string      { return r.id }
func (r *BaseRule) Name() string    { return r.name }
func (r *BaseRule) Type() string    { return r.typ }
func (r *BaseRule) Enabled() bool   { return r.enabled }
func (r *BaseRule) Order() int      { return r.order }

type FilterRule struct {
	BaseRule
	condition *regexp.Regexp
	mode      string
}

type ParseRule struct {
	BaseRule
	regex      *regexp.Regexp
	fieldNames []string
}

type EnrichRule struct {
	BaseRule
	geoReader   *geoip2.Reader
	errorMap    map[string]string
	serviceMap  map[string]string
	enrichType  string
}

type Pipeline struct {
	rules         []Rule
	rulesMu       sync.RWMutex
	cfg           config.PipelineConfig
	input         <-chan *models.LogEvent
	output        chan *models.LogEvent
	wg            sync.WaitGroup
	stopCh        chan struct{}
	hotReloadCh   chan struct{}
	geoIPDBPath   string
	errorCodeMap  map[string]string
}

func NewPipeline(cfg config.PipelineConfig, input <-chan *models.LogEvent, geoIPDBPath string) (*Pipeline, error) {
	if cfg.WorkerCount == 0 {
		cfg.WorkerCount = 4
	}
	if cfg.BufferSize == 0 {
		cfg.BufferSize = 10000
	}

	p := &Pipeline{
		cfg:          cfg,
		input:        input,
		output:       make(chan *models.LogEvent, cfg.BufferSize),
		stopCh:       make(chan struct{}),
		hotReloadCh:  make(chan struct{}),
		geoIPDBPath:  geoIPDBPath,
		errorCodeMap: make(map[string]string),
	}

	if err := p.loadRules(cfg.Rules); err != nil {
		return nil, fmt.Errorf("failed to load rules: %w", err)
	}

	if cfg.HotReload {
		config.RegisterCallback(p.onConfigChange)
	}

	return p, nil
}

func (p *Pipeline) loadRules(cfgRules []config.PipelineRule) error {
	rules := make([]Rule, 0, len(cfgRules))

	for _, cfg := range cfgRules {
		if !cfg.Enabled {
			continue
		}

		rule, err := p.createRule(cfg)
		if err != nil {
			log.Printf("Failed to create rule %s: %v", cfg.ID, err)
			continue
		}
		rules = append(rules, rule)
	}

	sort.Slice(rules, func(i, j int) bool {
		ri := rules[i].(interface{ Order() int }).Order()
		rj := rules[j].(interface{ Order() int }).Order()
		return ri < rj
	})

	p.rulesMu.Lock()
	p.rules = rules
	p.rulesMu.Unlock()

	log.Printf("Loaded %d pipeline rules", len(rules))
	return nil
}

func (p *Pipeline) createRule(cfg config.PipelineRule) (Rule, error) {
	base := BaseRule{
		id:      cfg.ID,
		name:    cfg.Name,
		typ:     cfg.Type,
		enabled: cfg.Enabled,
		order:   cfg.Order,
		cfg:     cfg.Config,
	}

	switch cfg.Type {
	case "filter":
		return p.createFilterRule(base, cfg)
	case "parse":
		return p.createParseRule(base, cfg)
	case "enrich":
		return p.createEnrichRule(base, cfg)
	default:
		return nil, fmt.Errorf("unknown rule type: %s", cfg.Type)
	}
}

func (p *Pipeline) createFilterRule(base BaseRule, cfg config.PipelineRule) (*FilterRule, error) {
	pattern, _ := cfg.Config["pattern"].(string)
	mode, _ := cfg.Config["mode"].(string)
	if mode == "" {
		mode = "include"
	}

	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil, fmt.Errorf("invalid regex pattern: %w", err)
	}

	return &FilterRule{
		BaseRule:  base,
		condition: re,
		mode:      mode,
	}, nil
}

func (p *Pipeline) createParseRule(base BaseRule, cfg config.PipelineRule) (*ParseRule, error) {
	pattern, _ := cfg.Config["pattern"].(string)
	fieldsInterface, _ := cfg.Config["fields"].([]interface{})
	fields := make([]string, len(fieldsInterface))
	for i, f := range fieldsInterface {
		fields[i] = fmt.Sprintf("%v", f)
	}

	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil, fmt.Errorf("invalid regex pattern: %w", err)
	}

	return &ParseRule{
		BaseRule:   base,
		regex:      re,
		fieldNames: fields,
	}, nil
}

func (p *Pipeline) createEnrichRule(base BaseRule, cfg config.PipelineRule) (*EnrichRule, error) {
	enrichType, _ := cfg.Config["type"].(string)

	rule := &EnrichRule{
		BaseRule:   base,
		enrichType: enrichType,
	}

	switch enrichType {
	case "geoip":
		if p.geoIPDBPath != "" {
			reader, err := geoip2.Open(p.geoIPDBPath)
			if err != nil {
				log.Printf("Warning: failed to open GeoIP database: %v", err)
			} else {
				rule.geoReader = reader
			}
		}
	case "error_code":
		errorMap, _ := cfg.Config["error_map"].(map[string]interface{})
		rule.errorMap = make(map[string]string)
		for k, v := range errorMap {
			rule.errorMap[k] = fmt.Sprintf("%v", v)
		}
	case "service_tag":
		serviceMap, _ := cfg.Config["service_map"].(map[string]interface{})
		rule.serviceMap = make(map[string]string)
		for k, v := range serviceMap {
			rule.serviceMap[k] = fmt.Sprintf("%v", v)
		}
	}

	return rule, nil
}

func (r *FilterRule) Process(ctx context.Context, event *models.LogEvent) (*models.LogEvent, error) {
	if !r.enabled {
		return event, nil
	}

	matched := r.condition.MatchString(event.Message) || r.condition.MatchString(event.RawMessage)

	if (r.mode == "include" && !matched) || (r.mode == "exclude" && matched) {
		return nil, nil
	}

	return event, nil
}

func (r *FilterRule) Reload(cfg config.PipelineRule) error {
	pattern, _ := cfg.Config["pattern"].(string)
	re, err := regexp.Compile(pattern)
	if err != nil {
		return err
	}
	r.condition = re
	r.mode, _ = cfg.Config["mode"].(string)
	if r.mode == "" {
		r.mode = "include"
	}
	r.enabled = cfg.Enabled
	return nil
}

func (r *ParseRule) Process(ctx context.Context, event *models.LogEvent) (*models.LogEvent, error) {
	if !r.enabled {
		return event, nil
	}

	message := event.Message
	if message == "" {
		message = event.RawMessage
	}

	matches := r.regex.FindStringSubmatch(message)
	if len(matches) <= 1 {
		return event, nil
	}

	if event.ParsedFields == nil {
		event.ParsedFields = make(map[string]interface{})
	}

	for i, name := range r.fieldNames {
		if i+1 < len(matches) {
			value := matches[i+1]
			event.ParsedFields[name] = value

			switch name {
			case "trace_id", "traceId":
				event.TraceID = value
			case "span_id", "spanId":
				event.SpanID = value
			case "user_id", "userId":
				event.UserID = value
			case "error_code", "err_code":
				event.ErrorCode = value
			case "status_code", "status":
				if code, err := strconv.Atoi(value); err == nil {
					event.StatusCode = code
				}
			case "response_time", "latency":
				if rt, err := strconv.ParseInt(value, 10, 64); err == nil {
					event.ResponseTime = rt
				}
			case "client_ip", "ip":
				event.ClientIP = value
			}
		}
	}

	return event, nil
}

func (r *ParseRule) Reload(cfg config.PipelineRule) error {
	pattern, _ := cfg.Config["pattern"].(string)
	fieldsInterface, _ := cfg.Config["fields"].([]interface{})
	fields := make([]string, len(fieldsInterface))
	for i, f := range fieldsInterface {
		fields[i] = fmt.Sprintf("%v", f)
	}

	re, err := regexp.Compile(pattern)
	if err != nil {
		return err
	}
	r.regex = re
	r.fieldNames = fields
	r.enabled = cfg.Enabled
	return nil
}

func (r *EnrichRule) Process(ctx context.Context, event *models.LogEvent) (*models.LogEvent, error) {
	if !r.enabled {
		return event, nil
	}

	switch r.enrichType {
	case "geoip":
		if r.geoReader != nil && event.ClientIP != "" {
			ip := net.ParseIP(event.ClientIP)
			if ip != nil {
				city, err := r.geoReader.City(ip)
				if err == nil {
					event.GeoLocation = &models.GeoLocation{
						Country:   city.Country.Names["en"],
						City:      city.City.Names["en"],
						Latitude:  city.Location.Latitude,
						Longitude: city.Location.Longitude,
					}
				}
			}
		}
	case "error_code":
		if event.ErrorCode != "" && r.errorMap != nil {
			if desc, ok := r.errorMap[event.ErrorCode]; ok {
				event.ErrorDesc = desc
			}
		}
	case "service_tag":
		if event.ServiceName != "" && r.serviceMap != nil {
			if tag, ok := r.serviceMap[event.ServiceName]; ok {
				event.Tags = append(event.Tags, tag)
			}
		}
	case "level_detect":
		if event.Level == models.LevelUnknown {
			event.Level = detectLevel(event.Message)
		}
	}

	return event, nil
}

func (r *EnrichRule) Reload(cfg config.PipelineRule) error {
	r.enabled = cfg.Enabled
	r.enrichType, _ = cfg.Config["type"].(string)

	if r.enrichType == "error_code" {
		errorMap, _ := cfg.Config["error_map"].(map[string]interface{})
		r.errorMap = make(map[string]string)
		for k, v := range errorMap {
			r.errorMap[k] = fmt.Sprintf("%v", v)
		}
	}
	if r.enrichType == "service_tag" {
		serviceMap, _ := cfg.Config["service_map"].(map[string]interface{})
		r.serviceMap = make(map[string]string)
		for k, v := range serviceMap {
			r.serviceMap[k] = fmt.Sprintf("%v", v)
		}
	}

	return nil
}

func detectLevel(message string) models.LogLevel {
	upper := strings.ToUpper(message)
	if strings.Contains(upper, "FATAL") || strings.Contains(upper, "CRITICAL") {
		return models.LevelFatal
	}
	if strings.Contains(upper, "ERROR") || strings.Contains(upper, "ERR ") {
		return models.LevelError
	}
	if strings.Contains(upper, "WARN") || strings.Contains(upper, "WARNING") {
		return models.LevelWarn
	}
	if strings.Contains(upper, "DEBUG") {
		return models.LevelDebug
	}
	if strings.Contains(upper, "INFO") {
		return models.LevelInfo
	}
	return models.LevelUnknown
}

func (p *Pipeline) Start(ctx context.Context) error {
	for i := 0; i < p.cfg.WorkerCount; i++ {
		p.wg.Add(1)
		go p.worker(ctx, i)
	}

	if p.cfg.HotReload {
		p.wg.Add(1)
		go p.hotReloadWatcher()
	}

	log.Printf("Pipeline started with %d workers", p.cfg.WorkerCount)
	return nil
}

func (p *Pipeline) worker(ctx context.Context, id int) {
	defer p.wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-p.stopCh:
			return
		case event := <-p.input:
			if event == nil {
				continue
			}

			processed, err := p.processEvent(ctx, event)
			if err != nil {
				log.Printf("Pipeline worker %d error: %v", id, err)
				continue
			}
			if processed != nil {
				select {
				case p.output <- processed:
				case <-ctx.Done():
					return
				case <-p.stopCh:
					return
				}
			}
		}
	}
}

func (p *Pipeline) processEvent(ctx context.Context, event *models.LogEvent) (*models.LogEvent, error) {
	p.rulesMu.RLock()
	rules := p.rules
	p.rulesMu.RUnlock()

	var err error
	for _, rule := range rules {
		if !rule.Enabled() {
			continue
		}

		event, err = rule.Process(ctx, event)
		if err != nil {
			return nil, fmt.Errorf("rule %s error: %w", rule.ID(), err)
		}
		if event == nil {
			return nil, nil
		}
	}

	return event, nil
}

func (p *Pipeline) onConfigChange(cfg *config.Config) {
	p.rulesMu.Lock()
	p.cfg = cfg.Pipeline
	p.rulesMu.Unlock()

	select {
	case p.hotReloadCh <- struct{}{}:
	default:
	}
}

func (p *Pipeline) hotReloadWatcher() {
	defer p.wg.Done()

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-p.stopCh:
			return
		case <-p.hotReloadCh:
			if err := p.loadRules(p.cfg.Rules); err != nil {
				log.Printf("Hot reload failed: %v", err)
			}
		case <-ticker.C:
			if p.cfg.ReloadPath != "" {
				if err := p.loadRulesFromFile(p.cfg.ReloadPath); err != nil {
					log.Printf("Hot reload from file failed: %v", err)
				}
			}
		}
	}
}

func (p *Pipeline) loadRulesFromFile(path string) error {
	return nil
}

func (p *Pipeline) Output() <-chan *models.LogEvent {
	return p.output
}

func (p *Pipeline) Stop() {
	close(p.stopCh)
	p.wg.Wait()
	close(p.output)
}

func (p *Pipeline) ReloadRules(rules []config.PipelineRule) error {
	return p.loadRules(rules)
}
