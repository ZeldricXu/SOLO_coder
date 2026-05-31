package processor

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"loglevelplatform/internal/modules/log_pipeline/models"
	"loglevelplatform/pkg/utils"
)

type ProcessorRegistry interface {
	Add(stage models.PipelineStage, processor models.LogProcessor)
	Get(stage models.PipelineStage) []models.LogProcessor
}

type processorRegistry struct {
	mu         sync.RWMutex
	processors map[models.PipelineStage][]models.LogProcessor
}

func NewProcessorRegistry() ProcessorRegistry {
	return &processorRegistry{
		processors: make(map[models.PipelineStage][]models.LogProcessor),
	}
}

func (r *processorRegistry) Add(stage models.PipelineStage, processor models.LogProcessor) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.processors[stage] = append(r.processors[stage], processor)
}

func (r *processorRegistry) Get(stage models.PipelineStage) []models.LogProcessor {
	r.mu.RLock()
	defer r.mu.RUnlock()
	procs := r.processors[stage]
	result := make([]models.LogProcessor, len(procs))
	copy(result, procs)
	return result
}

type LogProcessorChain interface {
	Process(entry *models.LogEntry) (*models.LogEntry, error)
}

type processorChain struct {
	registry ProcessorRegistry
	stages   []models.PipelineStage
}

func NewProcessorChain(registry ProcessorRegistry) LogProcessorChain {
	return &processorChain{
		registry: registry,
		stages: []models.PipelineStage{
			models.StageCollect,
			models.StageParse,
			models.StageFilter,
			models.StageEnrich,
			models.StageRoute,
		},
	}
}

func (c *processorChain) Process(entry *models.LogEntry) (*models.LogEntry, error) {
	var err error

	for _, stage := range c.stages {
		processors := c.registry.Get(stage)
		for _, proc := range processors {
			entry, err = proc(entry)
			if err != nil {
				return nil, fmt.Errorf("stage %s: %w", stage, err)
			}
			if entry == nil {
				return nil, nil
			}
		}
	}

	return entry, nil
}

func ParseJSONProcessor(entry *models.LogEntry) (*models.LogEntry, error) {
	if entry.Raw == "" {
		return entry, nil
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(entry.Raw), &parsed); err != nil {
		return entry, nil
	}

	extractStringField(parsed, "level", &entry.Level)
	extractStringField(parsed, "message", &entry.Message)
	extractStringField(parsed, "service", &entry.Service)
	extractStringField(parsed, "trace_id", &entry.TraceID)

	entry.Fields = parsed
	return entry, nil
}

var (
	logPatterns     []*regexp.Regexp
	patternsOnce    sync.Once
)

func initPatterns() {
	logPatterns = []*regexp.Regexp{
		regexp.MustCompile(`^(?P<time>\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})\s+(?P<level>[A-Z]+)\s+(?P<message>.*)$`),
		regexp.MustCompile(`^\[(?P<level>[A-Z]+)\]\s+(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+(?P<message>.*)$`),
	}
}

func ParseRegexProcessor(entry *models.LogEntry) (*models.LogEntry, error) {
	if entry.Raw == "" || entry.Message != "" {
		return entry, nil
	}

	patternsOnce.Do(initPatterns)

	for _, re := range logPatterns {
		matches := re.FindStringSubmatch(entry.Raw)
		if matches == nil {
			continue
		}

		names := re.SubexpNames()
		for i, name := range names {
			if i == 0 || name == "" {
				continue
			}
			switch name {
			case "level":
				entry.Level = strings.ToLower(matches[i])
			case "message":
				entry.Message = matches[i]
			}
		}

		if entry.Message != "" {
			break
		}
	}

	return entry, nil
}

func EnrichDefaultsProcessor(entry *models.LogEntry) (*models.LogEntry, error) {
	if entry.ID == "" {
		entry.ID = utils.NewID("log")
	}
	if entry.Timestamp == 0 {
		entry.Timestamp = time.Now().UnixNano() / 1e6
	}
	if entry.Level == "" {
		entry.Level = "info"
	}
	if entry.Host == "" {
		entry.Host = "localhost"
	}
	if entry.Tags == nil {
		entry.Tags = make(map[string]string)
	}
	entry.Tags["processed_at"] = time.Now().Format(time.RFC3339)

	return entry, nil
}

func RegisterDefaultProcessors(registry ProcessorRegistry) {
	registry.Add(models.StageParse, ParseJSONProcessor)
	registry.Add(models.StageParse, ParseRegexProcessor)
	registry.Add(models.StageEnrich, EnrichDefaultsProcessor)
}

func extractStringField(data map[string]interface{}, key string, target *string) {
	if val, ok := data[key].(string); ok && val != "" {
		*target = val
	}
}
