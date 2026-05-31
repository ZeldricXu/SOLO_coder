package prompt

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"sort"
	"strings"
	"sync"
	"text/template"
	"time"

	"github.com/google/uuid"
	"github.com/solocoder/logrotate/internal/domain"
)

type VariableType string

const (
	TypeString  VariableType = "string"
	TypeNumber  VariableType = "number"
	TypeBoolean VariableType = "boolean"
	TypeArray   VariableType = "array"
	TypeObject  VariableType = "object"
)

type VariableDef struct {
	Name        string      `json:"name"`
	Type        VariableType `json:"type"`
	Required    bool        `json:"required"`
	Default     interface{} `json:"default"`
	Description string      `json:"description"`
}

type Prompt struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Content     string                 `json:"content"`
	Version     string                 `json:"version"`
	Description string                 `json:"description"`
	Variables   []VariableDef          `json:"variables"`
	Tags        []string               `json:"tags"`
	CreatedBy   string                 `json:"created_by"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	Metadata    map[string]interface{} `json:"metadata"`
}

type ExperimentStatus string

const (
	ExpDraft     ExperimentStatus = "draft"
	ExpRunning   ExperimentStatus = "running"
	ExpPaused    ExperimentStatus = "paused"
	ExpCompleted ExperimentStatus = "completed"
	ExpCancelled ExperimentStatus = "cancelled"
)

type ExperimentMetric struct {
	Name        string  `json:"name"`
	ValueA      float64 `json:"value_a"`
	ValueB      float64 `json:"value_b"`
	Improvement float64 `json:"improvement"`
	Confidence  float64 `json:"confidence"`
}

type Experiment struct {
	ID            string                 `json:"id"`
	Name          string                 `json:"name"`
	Description   string                 `json:"description"`
	PromptAID     string                 `json:"prompt_a_id"`
	PromptBID     string                 `json:"prompt_b_id"`
	TrafficSplit  float64                `json:"traffic_split"`
	Status        ExperimentStatus       `json:"status"`
	Metrics       []ExperimentMetric     `json:"metrics"`
	TrafficCountA int64                  `json:"traffic_count_a"`
	TrafficCountB int64                  `json:"traffic_count_b"`
	StartDate     time.Time              `json:"start_date"`
	EndDate       *time.Time             `json:"end_date"`
	CreatedAt     time.Time              `json:"created_at"`
	CreatedBy     string                 `json:"created_by"`
	Metadata      map[string]interface{} `json:"metadata"`
}

type EvaluationResult struct {
	PromptID      string                 `json:"prompt_id"`
	ExperimentID  string                 `json:"experiment_id"`
	Variant       string                 `json:"variant"`
	Input         map[string]interface{} `json:"input"`
	Output        string                 `json:"output"`
	LatencyMs     int64                  `json:"latency_ms"`
	TokensUsed    int                    `json:"tokens_used"`
	QualityScore  float64                `json:"quality_score"`
	RelevanceScore float64               `json:"relevance_score"`
	Timestamp     time.Time              `json:"timestamp"`
	Metadata      map[string]interface{} `json:"metadata"`
}

type Manager struct {
	mu            sync.RWMutex
	prompts       map[string]*Prompt
	promptHistory map[string][]*Prompt
	experiments   map[string]*Experiment
	evaluations   map[string][]*EvaluationResult
}

func NewManager() *Manager {
	return &Manager{
		prompts:       make(map[string]*Prompt),
		promptHistory: make(map[string][]*Prompt),
		experiments:   make(map[string]*Experiment),
		evaluations:   make(map[string][]*EvaluationResult),
	}
}

func (m *Manager) CreatePrompt(name, content, createdBy string, variables []VariableDef, tags []string, description string) (*Prompt, error) {
	if name == "" {
		return nil, errors.New("name is required")
	}
	if content == "" {
		return nil, errors.New("content is required")
	}

	now := time.Now()
	prompt := &Prompt{
		ID:          uuid.New().String(),
		Name:        name,
		Content:     content,
		Version:     "1.0.0",
		Description: description,
		Variables:   variables,
		Tags:        tags,
		CreatedBy:   createdBy,
		CreatedAt:   now,
		UpdatedAt:   now,
		Metadata:    make(map[string]interface{}),
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.prompts[prompt.ID] = prompt
	m.promptHistory[prompt.ID] = []*Prompt{prompt}

	return prompt, nil
}

func (m *Manager) GetPrompt(promptID string) (*Prompt, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	p, ok := m.prompts[promptID]
	return p, ok
}

func (m *Manager) UpdatePrompt(promptID, content, updatedBy string, variables []VariableDef, tags []string, description string) (*Prompt, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	prompt, ok := m.prompts[promptID]
	if !ok {
		return nil, fmt.Errorf("prompt not found: %s", promptID)
	}

	oldVersion := prompt.Version
	versionParts := strings.Split(oldVersion, ".")
	if len(versionParts) == 3 {
		if major, err := fmt.Sscanf(oldVersion, "%d.%d.%d", &0, &0, &0); err == nil {
			var major, minor, patch int
			fmt.Sscanf(oldVersion, "%d.%d.%d", &major, &minor, &patch)
			patch++
			prompt.Version = fmt.Sprintf("%d.%d.%d", major, minor, patch)
		}
	}

	prompt.Content = content
	prompt.Description = description
	prompt.Variables = variables
	prompt.Tags = tags
	prompt.UpdatedAt = time.Now()
	prompt.Metadata["updated_by"] = updatedBy

	historyCopy := *prompt
	m.promptHistory[promptID] = append(m.promptHistory[promptID], &historyCopy)

	return prompt, nil
}

func (m *Manager) ListPrompts(nameFilter, tagFilter string) []*Prompt {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var prompts []*Prompt
	for _, p := range m.prompts {
		if nameFilter != "" && !strings.Contains(strings.ToLower(p.Name), strings.ToLower(nameFilter)) {
			continue
		}
		if tagFilter != "" {
			found := false
			for _, t := range p.Tags {
				if strings.Contains(strings.ToLower(t), strings.ToLower(tagFilter)) {
					found = true
					break
				}
			}
			if !found {
				continue
			}
		}
		prompts = append(prompts, p)
	}

	sort.Slice(prompts, func(i, j int) bool {
		return prompts[i].UpdatedAt.After(prompts[j].UpdatedAt)
	})

	return prompts
}

func (m *Manager) GetPromptHistory(promptID string) []*Prompt {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return m.promptHistory[promptID]
}

func (m *Manager) DeletePrompt(promptID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.prompts[promptID]; !ok {
		return fmt.Errorf("prompt not found: %s", promptID)
	}

	delete(m.prompts, promptID)
	delete(m.promptHistory, promptID)

	return nil
}

func (m *Manager) RenderPrompt(ctx context.Context, promptID string, variables map[string]interface{}) (string, error) {
	prompt, ok := m.GetPrompt(promptID)
	if !ok {
		return "", fmt.Errorf("prompt not found: %s", promptID)
	}

	if err := m.validateVariables(prompt, variables); err != nil {
		return "", err
	}

	tmpl, err := template.New("prompt").Option("missingkey=error").Parse(prompt.Content)
	if err != nil {
		return "", fmt.Errorf("parse template: %w", err)
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, variables); err != nil {
		return "", fmt.Errorf("execute template: %w", err)
	}

	return buf.String(), nil
}

func (m *Manager) validateVariables(prompt *Prompt, variables map[string]interface{}) error {
	for _, vd := range prompt.Variables {
		if vd.Required {
			if _, ok := variables[vd.Name]; !ok {
				return fmt.Errorf("required variable missing: %s", vd.Name)
			}
		}
	}
	return nil
}

func (m *Manager) CreateExperiment(name, description, promptAID, promptBID string, trafficSplit float64, createdBy string) (*Experiment, error) {
	if trafficSplit <= 0 || trafficSplit >= 1 {
		return nil, errors.New("traffic split must be between 0 and 1")
	}

	if _, ok := m.GetPrompt(promptAID); !ok {
		return nil, fmt.Errorf("prompt A not found: %s", promptAID)
	}
	if _, ok := m.GetPrompt(promptBID); !ok {
		return nil, fmt.Errorf("prompt B not found: %s", promptBID)
	}

	now := time.Now()
	exp := &Experiment{
		ID:           uuid.New().String(),
		Name:         name,
		Description:  description,
		PromptAID:    promptAID,
		PromptBID:    promptBID,
		TrafficSplit: trafficSplit,
		Status:       ExpDraft,
		StartDate:    now,
		CreatedAt:    now,
		CreatedBy:    createdBy,
		Metadata:     make(map[string]interface{}),
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.experiments[exp.ID] = exp
	return exp, nil
}

func (m *Manager) GetExperiment(experimentID string) (*Experiment, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	e, ok := m.experiments[experimentID]
	return e, ok
}

func (m *Manager) StartExperiment(experimentID string) (*Experiment, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	exp, ok := m.experiments[experimentID]
	if !ok {
		return nil, fmt.Errorf("experiment not found: %s", experimentID)
	}

	if exp.Status != ExpDraft {
		return nil, fmt.Errorf("can only start draft experiments")
	}

	exp.Status = ExpRunning
	now := time.Now()
	exp.StartDate = now

	return exp, nil
}

func (m *Manager) PauseExperiment(experimentID string) (*Experiment, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	exp, ok := m.experiments[experimentID]
	if !ok {
		return nil, fmt.Errorf("experiment not found: %s", experimentID)
	}

	if exp.Status != ExpRunning {
		return nil, fmt.Errorf("can only pause running experiments")
	}

	exp.Status = ExpPaused
	return exp, nil
}

func (m *Manager) EndExperiment(experimentID string) (*Experiment, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	exp, ok := m.experiments[experimentID]
	if !ok {
		return nil, fmt.Errorf("experiment not found: %s", experimentID)
	}

	if exp.Status != ExpRunning && exp.Status != ExpPaused {
		return nil, fmt.Errorf("can only end running or paused experiments")
	}

	exp.Status = ExpCompleted
	now := time.Now()
	exp.EndDate = &now

	return exp, nil
}

func (m *Manager) GetExperimentVariant(experimentID string) (string, *Prompt, error) {
	exp, ok := m.GetExperiment(experimentID)
	if !ok {
		return "", nil, fmt.Errorf("experiment not found: %s", experimentID)
	}

	if exp.Status != ExpRunning {
		return "", nil, fmt.Errorf("experiment is not running")
	}

	rand.Seed(time.Now().UnixNano())
	r := rand.Float64()

	var variant string
	var promptID string

	if r < exp.TrafficSplit {
		variant = "A"
		promptID = exp.PromptAID
		m.mu.Lock()
		exp.TrafficCountA++
		m.mu.Unlock()
	} else {
		variant = "B"
		promptID = exp.PromptBID
		m.mu.Lock()
		exp.TrafficCountB++
		m.mu.Unlock()
	}

	prompt, _ := m.GetPrompt(promptID)
	return variant, prompt, nil
}

func (m *Manager) RecordEvaluation(experimentID, variant string, input map[string]interface{}, output string, latencyMs int64, tokensUsed int, qualityScore, relevanceScore float64) (*EvaluationResult, error) {
	exp, ok := m.GetExperiment(experimentID)
	if !ok {
		return nil, fmt.Errorf("experiment not found: %s", experimentID)
	}

	eval := &EvaluationResult{
		PromptID:       "",
		ExperimentID:   experimentID,
		Variant:        variant,
		Input:          input,
		Output:         output,
		LatencyMs:      latencyMs,
		TokensUsed:     tokensUsed,
		QualityScore:   qualityScore,
		RelevanceScore: relevanceScore,
		Timestamp:      time.Now(),
		Metadata:       make(map[string]interface{}),
	}

	if variant == "A" {
		eval.PromptID = exp.PromptAID
	} else {
		eval.PromptID = exp.PromptBID
	}

	m.mu.Lock()
	m.evaluations[experimentID] = append(m.evaluations[experimentID], eval)
	m.mu.Unlock()

	m.updateExperimentMetrics(experimentID)

	return eval, nil
}

func (m *Manager) updateExperimentMetrics(experimentID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	exp, ok := m.experiments[experimentID]
	if !ok {
		return
	}

	evals := m.evaluations[experimentID]

	var aQuality, aLatency, aRelevance, bQuality, bLatency, bRelevance float64
	var aCount, bCount int64

	for _, e := range evals {
		if e.Variant == "A" {
			aQuality += e.QualityScore
			aLatency += float64(e.LatencyMs)
			aRelevance += e.RelevanceScore
			aCount++
		} else {
			bQuality += e.QualityScore
			bLatency += float64(e.LatencyMs)
			bRelevance += e.RelevanceScore
			bCount++
		}
	}

	if aCount > 0 && bCount > 0 {
		avgAQuality := aQuality / float64(aCount)
		avgBQuality := bQuality / float64(bCount)

		avgALatency := aLatency / float64(aCount)
		avgBLatency := bLatency / float64(bCount)

		avgARelevance := aRelevance / float64(aCount)
		avgBRelevance := bRelevance / float64(bCount)

		exp.Metrics = []ExperimentMetric{
			{
				Name:        "quality_score",
				ValueA:      avgAQuality,
				ValueB:      avgBQuality,
				Improvement: (avgBQuality - avgAQuality) / avgAQuality * 100,
				Confidence:  0.95,
			},
			{
				Name:        "latency_ms",
				ValueA:      avgALatency,
				ValueB:      avgBLatency,
				Improvement: (avgALatency - avgBLatency) / avgALatency * 100,
				Confidence:  0.95,
			},
			{
				Name:        "relevance_score",
				ValueA:      avgARelevance,
				ValueB:      avgBRelevance,
				Improvement: (avgBRelevance - avgARelevance) / avgARelevance * 100,
				Confidence:  0.95,
			},
		}
	}
}

func (m *Manager) ListExperiments(statusFilter ExperimentStatus) []*Experiment {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var exps []*Experiment
	for _, e := range m.experiments {
		if statusFilter != "" && e.Status != statusFilter {
			continue
		}
		exps = append(exps, e)
	}

	sort.Slice(exps, func(i, j int) bool {
		return exps[i].CreatedAt.After(exps[j].CreatedAt)
	})

	return exps
}

func (m *Manager) GetExperimentEvaluations(experimentID string) []*EvaluationResult {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return m.evaluations[experimentID]
}

func (m *Manager) ExportPrompt(promptID string) ([]byte, error) {
	prompt, ok := m.GetPrompt(promptID)
	if !ok {
		return nil, fmt.Errorf("prompt not found: %s", promptID)
	}
	return json.MarshalIndent(prompt, "", "  ")
}

func (m *Manager) ImportPrompt(data []byte) (*Prompt, error) {
	var prompt Prompt
	if err := json.Unmarshal(data, &prompt); err != nil {
		return nil, fmt.Errorf("parse prompt: %w", err)
	}

	prompt.ID = uuid.New().String()
	prompt.CreatedAt = time.Now()
	prompt.UpdatedAt = time.Now()
	prompt.Version = "1.0.0"

	m.mu.Lock()
	m.prompts[prompt.ID] = &prompt
	m.promptHistory[prompt.ID] = []*Prompt{&prompt}
	m.mu.Unlock()

	return &prompt, nil
}
