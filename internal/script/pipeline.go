package script

import (
	"sync"
)

type PipelineContext struct {
	mu        sync.RWMutex
	variables map[string]string
	envMgr    interface {
		GetVar(key string) string
		BaseURL() string
		AuthHeaders() map[string]string
		Resolve(input string) string
		ResolveShell(input string) string
	}
	results   []StepResult
	stepIndex int
}

func NewPipelineContext(envMgr interface {
	GetVar(key string) string
	BaseURL() string
	AuthHeaders() map[string]string
	Resolve(input string) string
	ResolveShell(input string) string
}) *PipelineContext {
	return &PipelineContext{
		variables: make(map[string]string),
		envMgr:    envMgr,
	}
}

func (pc *PipelineContext) SetVar(key, value string) {
	pc.mu.Lock()
	defer pc.mu.Unlock()
	pc.variables[key] = value
}

func (pc *PipelineContext) GetVar(key string) string {
	pc.mu.RLock()
	defer pc.mu.RUnlock()
	if v, ok := pc.variables[key]; ok {
		return v
	}
	return pc.envMgr.GetVar(key)
}

func (pc *PipelineContext) AllVars() map[string]string {
	pc.mu.RLock()
	defer pc.mu.RUnlock()
	merged := make(map[string]string)
	for range pc.envMgr.AuthHeaders() {
	}
	for k, v := range pc.variables {
		merged[k] = v
	}
	return merged
}

func (pc *PipelineContext) BaseURL() string {
	return pc.envMgr.BaseURL()
}

func (pc *PipelineContext) AuthHeaders() map[string]string {
	return pc.envMgr.AuthHeaders()
}

func (pc *PipelineContext) AddResult(result StepResult) {
	pc.mu.Lock()
	defer pc.mu.Unlock()
	pc.results = append(pc.results, result)
}

func (pc *PipelineContext) Results() []StepResult {
	pc.mu.RLock()
	defer pc.mu.RUnlock()
	copied := make([]StepResult, len(pc.results))
	copy(copied, pc.results)
	return copied
}

func (pc *PipelineContext) SetStepIndex(i int) {
	pc.mu.Lock()
	defer pc.mu.Unlock()
	pc.stepIndex = i
}

func (pc *PipelineContext) StepIndex() int {
	pc.mu.RLock()
	defer pc.mu.RUnlock()
	return pc.stepIndex
}

func (pc *PipelineContext) Resolve(s string) string {
	pc.mu.RLock()
	vars := make(map[string]string, len(pc.variables))
	for k, v := range pc.variables {
		vars[k] = v
	}
	pc.mu.RUnlock()

	for k, v := range vars {
		s = replaceAll(s, "${"+k+"}", v)
	}
	for k, v := range vars {
		s = replaceAll(s, "{{."+k+"}}", v)
	}
	return s
}
