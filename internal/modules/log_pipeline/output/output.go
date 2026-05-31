package output

import (
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"loglevelplatform/internal/modules/log_pipeline/models"
)

type OutputRouter interface {
	AddRule(rule models.RouterRule)
	AddOutput(name string, output models.LogOutput)
	Route(entry *models.LogEntry)
	RuleCount() int
	OutputCount() int
	Close()
}

type outputJob struct {
	output models.LogOutput
	entry  *models.LogEntry
}

type outputRouter struct {
	mu          sync.RWMutex
	rules       []models.RouterRule
	outputs     map[string]models.LogOutput
	workerCount int
	jobQueue    chan outputJob
	wg          sync.WaitGroup
	stopOnce    sync.Once
	stopped     atomic.Bool
}

func NewOutputRouter(workerCount, queueSize int) OutputRouter {
	if workerCount <= 0 {
		workerCount = 4
	}
	if queueSize <= 0 {
		queueSize = 10000
	}

	router := &outputRouter{
		rules:       make([]models.RouterRule, 0),
		outputs:     make(map[string]models.LogOutput),
		workerCount: workerCount,
		jobQueue:    make(chan outputJob, queueSize),
	}

	router.startWorkers()
	router.registerDefaultOutputs()
	return router
}

func (r *outputRouter) startWorkers() {
	for i := 0; i < r.workerCount; i++ {
		r.wg.Add(1)
		go r.worker(i)
	}
}

func (r *outputRouter) worker(id int) {
	defer r.wg.Done()
	for job := range r.jobQueue {
		_ = job.output(job.entry)
	}
}

func (r *outputRouter) AddRule(rule models.RouterRule) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rules = append(r.rules, rule)
}

func (r *outputRouter) AddOutput(name string, output models.LogOutput) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.outputs[name] = output
}

func (r *outputRouter) Route(entry *models.LogEntry) {
	if r.stopped.Load() {
		return
	}

	r.mu.RLock()
	rules := r.rules
	outputs := r.outputs
	r.mu.RUnlock()

	targets := r.getTargetOutputs(entry, rules, outputs)

	for _, outputName := range targets {
		if output, exists := outputs[outputName]; exists {
			select {
			case r.jobQueue <- outputJob{output: output, entry: entry}:
			default:
			}
		}
	}
}

func (r *outputRouter) getTargetOutputs(
	entry *models.LogEntry,
	rules []models.RouterRule,
	outputs map[string]models.LogOutput,
) []string {
	if len(rules) == 0 {
		return r.getAllOutputNames(outputs)
	}

	targets := make([]string, 0, len(outputs))
	for _, rule := range rules {
		if r.matchRule(entry, rule.Match) {
			targets = append(targets, rule.Outputs...)
		}
	}
	return uniqueStrings(targets)
}

func (r *outputRouter) getAllOutputNames(outputs map[string]models.LogOutput) []string {
	names := make([]string, 0, len(outputs))
	for name := range outputs {
		names = append(names, name)
	}
	return names
}

func (r *outputRouter) matchRule(entry *models.LogEntry, match map[string]interface{}) bool {
	for field, expected := range match {
		value := getFieldValue(entry, field)
		if value != expected {
			return false
		}
	}
	return true
}

func (r *outputRouter) RuleCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.rules)
}

func (r *outputRouter) OutputCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.outputs)
}

func (r *outputRouter) Close() {
	r.stopOnce.Do(func() {
		r.stopped.Store(true)
		close(r.jobQueue)

		done := make(chan struct{})
		go func() {
			r.wg.Wait()
			close(done)
		}()

		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
	})
}

func (r *outputRouter) registerDefaultOutputs() {
	r.AddOutput("stdout", StdoutOutput)
}

func StdoutOutput(entry *models.LogEntry) error {
	data, _ := json.Marshal(entry)
	fmt.Println(string(data))
	return nil
}

func getFieldValue(entry *models.LogEntry, field string) interface{} {
	switch field {
	case "level":
		return entry.Level
	case "message":
		return entry.Message
	case "service":
		return entry.Service
	case "host":
		return entry.Host
	case "trace_id":
		return entry.TraceID
	default:
		if entry.Fields != nil {
			if val, ok := entry.Fields[field]; ok {
				return val
			}
		}
		if entry.Tags != nil {
			if val, ok := entry.Tags[field]; ok {
				return val
			}
		}
	}
	return nil
}

func uniqueStrings(s []string) []string {
	seen := make(map[string]struct{}, len(s))
	result := make([]string, 0, len(s))
	for _, v := range s {
		if _, exists := seen[v]; !exists {
			seen[v] = struct{}{}
			result = append(result, v)
		}
	}
	return result
}
