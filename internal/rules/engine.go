package rules

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/common/utils"
	"go.uber.org/zap"
)

type ConditionType string

const (
	ConditionThreshold ConditionType = "threshold"
	ConditionPattern   ConditionType = "pattern"
	ConditionSchedule  ConditionType = "schedule"
	ConditionCustom    ConditionType = "custom"
)

type ActionType string

const (
	ActionAlert   ActionType = "alert"
	ActionCommand ActionType = "command"
	ActionWebhook ActionType = "webhook"
	ActionStore   ActionType = "store"
)

type RuleCondition struct {
	Type       ConditionType           `json:"type"`
	Expression string                  `json:"expression"`
	Params     map[string]interface{}  `json:"params"`
}

type RuleAction struct {
	Type   ActionType             `json:"type"`
	Target string                 `json:"target"`
	Params map[string]interface{} `json:"params"`
}

type Rule struct {
	ID          string            `json:"id"`
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Enabled     bool              `json:"enabled"`
	Condition   RuleCondition     `json:"condition"`
	Actions     []RuleAction      `json:"actions"`
	Strategy    string            `json:"strategy"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type Event struct {
	EventID   string                 `json:"event_id"`
	EventType string                 `json:"event_type"`
	Source    string                 `json:"source"`
	Timestamp time.Time              `json:"timestamp"`
	Payload   map[string]interface{} `json:"payload"`
}

type ActionResult struct {
	ActionID string      `json:"action_id"`
	Type     ActionType  `json:"type"`
	Success  bool        `json:"success"`
	Result   interface{} `json:"result"`
	Error    string      `json:"error,omitempty"`
}

type ExecutionStrategy interface {
	Name() string
	Evaluate(ctx context.Context, condition RuleCondition, event Event) (bool, error)
	Execute(ctx context.Context, actions []RuleAction, event Event) ([]ActionResult, error)
}

type DefaultStrategy struct{}

func (s *DefaultStrategy) Name() string { return "default" }

func (s *DefaultStrategy) Evaluate(ctx context.Context, condition RuleCondition, event Event) (bool, error) {
	switch condition.Type {
	case ConditionThreshold:
		return s.evaluateThreshold(condition, event)
	case ConditionPattern:
		return s.evaluatePattern(condition, event)
	case ConditionSchedule:
		return s.evaluateSchedule(condition, event)
	default:
		return false, fmt.Errorf("unsupported condition type: %s", condition.Type)
	}
}

func (s *DefaultStrategy) evaluateThreshold(condition RuleCondition, event Event) (bool, error) {
	threshold, ok := condition.Params["threshold"].(float64)
	if !ok {
		return false, fmt.Errorf("invalid threshold parameter")
	}
	value, ok := event.Payload["value"].(float64)
	if !ok {
		return false, nil
	}
	operator, _ := condition.Params["operator"].(string)
	switch operator {
	case ">":
		return value > threshold, nil
	case "<":
		return value < threshold, nil
	case ">=":
		return value >= threshold, nil
	case "<=":
		return value <= threshold, nil
	case "==":
		return value == threshold, nil
	default:
		return value > threshold, nil
	}
}

func (s *DefaultStrategy) evaluatePattern(condition RuleCondition, event Event) (bool, error) {
	pattern, _ := condition.Params["pattern"].(string)
	if pattern == "" {
		return false, nil
	}
	eventStr, _ := json.Marshal(event.Payload)
	return len(eventStr) > 0, nil
}

func (s *DefaultStrategy) evaluateSchedule(condition RuleCondition, event Event) (bool, error) {
	return true, nil
}

func (s *DefaultStrategy) Execute(ctx context.Context, actions []RuleAction, event Event) ([]ActionResult, error) {
	results := make([]ActionResult, 0, len(actions))
	for _, action := range actions {
		result := ActionResult{
			ActionID: utils.GenerateID("act"),
			Type:     action.Type,
		}
		switch action.Type {
		case ActionAlert:
			result.Success = true
			result.Result = map[string]interface{}{
				"message": fmt.Sprintf("Alert triggered for event: %s", event.EventID),
				"level":   action.Params["level"],
			}
			logger.Get().Info("Alert action executed", zap.String("event_id", event.EventID))
		case ActionCommand:
			result.Success = true
			result.Result = map[string]interface{}{
				"command": action.Params["command"],
				"executed": true,
			}
		case ActionWebhook:
			result.Success = true
			result.Result = map[string]interface{}{
				"url":         action.Target,
				"http_status": 200,
			}
		case ActionStore:
			result.Success = true
			result.Result = map[string]interface{}{
				"stored": true,
				"key":    event.EventID,
			}
		default:
			result.Success = false
			result.Error = fmt.Sprintf("unsupported action type: %s", action.Type)
		}
		results = append(results, result)
	}
	return results, nil
}

type AggressiveStrategy struct{}

func (s *AggressiveStrategy) Name() string { return "aggressive" }

func (s *AggressiveStrategy) Evaluate(ctx context.Context, condition RuleCondition, event Event) (bool, error) {
	return true, nil
}

func (s *AggressiveStrategy) Execute(ctx context.Context, actions []RuleAction, event Event) ([]ActionResult, error) {
	results := make([]ActionResult, 0, len(actions))
	var wg sync.WaitGroup
	mu := sync.Mutex{}
	for _, action := range actions {
		wg.Add(1)
		go func(a RuleAction) {
			defer wg.Done()
			result := ActionResult{
				ActionID: utils.GenerateID("act"),
				Type:     a.Type,
				Success:  true,
				Result:   map[string]interface{}{"aggressive": true},
			}
			mu.Lock()
			results = append(results, result)
			mu.Unlock()
		}(action)
	}
	wg.Wait()
	return results, nil
}

type ConservativeStrategy struct{}

func (s *ConservativeStrategy) Name() string { return "conservative" }

func (s *ConservativeStrategy) Evaluate(ctx context.Context, condition RuleCondition, event Event) (bool, error) {
	time.Sleep(100 * time.Millisecond)
	return true, nil
}

func (s *ConservativeStrategy) Execute(ctx context.Context, actions []RuleAction, event Event) ([]ActionResult, error) {
	results := make([]ActionResult, 0, len(actions))
	for _, action := range actions {
		result := ActionResult{
			ActionID: utils.GenerateID("act"),
			Type:     action.Type,
			Success:  true,
			Result:   map[string]interface{}{"conservative": true},
		}
		results = append(results, result)
	}
	return results, nil
}

type Engine struct {
	rules         map[string]*Rule
	strategies    map[string]ExecutionStrategy
	defaultStrategy string
	eventQueue    chan Event
	mu            sync.RWMutex
	ctx           context.Context
	cancel        context.CancelFunc
	wg            sync.WaitGroup
}

func NewEngine() *Engine {
	ctx, cancel := context.WithCancel(context.Background())
	engine := &Engine{
		rules:          make(map[string]*Rule),
		strategies:     make(map[string]ExecutionStrategy),
		defaultStrategy: "default",
		eventQueue:     make(chan Event, 1000),
		ctx:            ctx,
		cancel:         cancel,
	}
	engine.RegisterStrategy(&DefaultStrategy{})
	engine.RegisterStrategy(&AggressiveStrategy{})
	engine.RegisterStrategy(&ConservativeStrategy{})
	return engine
}

func (e *Engine) RegisterStrategy(strategy ExecutionStrategy) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.strategies[strategy.Name()] = strategy
	logger.Get().Info("Rule strategy registered", zap.String("strategy", strategy.Name()))
}

func (e *Engine) SetDefaultStrategy(name string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, exists := e.strategies[name]; exists {
		e.defaultStrategy = name
		logger.Get().Info("Default rule strategy changed", zap.String("strategy", name))
	}
}

func (e *Engine) GetStrategy(name string) (ExecutionStrategy, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	strategy, exists := e.strategies[name]
	return strategy, exists
}

func (e *Engine) ListStrategies() []string {
	e.mu.RLock()
	defer e.mu.RUnlock()
	names := make([]string, 0, len(e.strategies))
	for name := range e.strategies {
		names = append(names, name)
	}
	return names
}

func (e *Engine) AddRule(rule *Rule) string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if rule.ID == "" {
		rule.ID = utils.GenerateID("rule")
	}
	rule.CreatedAt = time.Now().UTC()
	rule.UpdatedAt = time.Now().UTC()
	e.rules[rule.ID] = rule
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "rule.created",
		Payload: map[string]interface{}{
			"rule_id": rule.ID,
			"name":    rule.Name,
		},
	})
	logger.Get().Info("Rule added", zap.String("rule_id", rule.ID), zap.String("name", rule.Name))
	return rule.ID
}

func (e *Engine) GetRule(id string) (*Rule, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	rule, exists := e.rules[id]
	return rule, exists
}

func (e *Engine) ListRules() []*Rule {
	e.mu.RLock()
	defer e.mu.RUnlock()
	rules := make([]*Rule, 0, len(e.rules))
	for _, r := range e.rules {
		rules = append(rules, r)
	}
	return rules
}

func (e *Engine) UpdateRule(id string, rule *Rule) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	existing, exists := e.rules[id]
	if !exists {
		return false
	}
	rule.ID = id
	rule.CreatedAt = existing.CreatedAt
	rule.UpdatedAt = time.Now().UTC()
	e.rules[id] = rule
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "rule.updated",
		Payload: map[string]interface{}{"rule_id": id},
	})
	return true
}

func (e *Engine) DeleteRule(id string) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	_, exists := e.rules[id]
	if !exists {
		return false
	}
	delete(e.rules, id)
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "rule.deleted",
		Payload: map[string]interface{}{"rule_id": id},
	})
	return true
}

func (e *Engine) ProcessEvent(event Event) {
	select {
	case e.eventQueue <- event:
	case <-e.ctx.Done():
	}
}

func (e *Engine) Start() {
	e.wg.Add(1)
	go e.processLoop()
	logger.Get().Info("Rule engine started")
}

func (e *Engine) processLoop() {
	defer e.wg.Done()
	for {
		select {
		case event := <-e.eventQueue:
			e.handleEvent(event)
		case <-e.ctx.Done():
			return
		}
	}
}

func (e *Engine) handleEvent(event Event) {
	e.mu.RLock()
	rules := make([]*Rule, 0, len(e.rules))
	for _, r := range e.rules {
		if r.Enabled {
			rules = append(rules, r)
		}
	}
	e.mu.RUnlock()
	for _, rule := range rules {
		strategyName := rule.Strategy
		if strategyName == "" {
			strategyName = e.defaultStrategy
		}
		strategy, exists := e.GetStrategy(strategyName)
		if !exists {
			logger.Get().Warn("Strategy not found, using default",
				zap.String("requested", strategyName),
				zap.String("fallback", e.defaultStrategy))
			strategy, _ = e.GetStrategy(e.defaultStrategy)
		}
		matched, err := strategy.Evaluate(e.ctx, rule.Condition, event)
		if err != nil {
			logger.Get().Error("Condition evaluation failed",
				zap.String("rule_id", rule.ID),
				zap.Error(err))
			continue
		}
		if matched {
			logger.Get().Info("Rule matched",
				zap.String("rule_id", rule.ID),
				zap.String("event_id", event.EventID),
				zap.String("strategy", strategy.Name()))
			results, err := strategy.Execute(e.ctx, rule.Actions, event)
			if err != nil {
				logger.Get().Error("Action execution failed",
					zap.String("rule_id", rule.ID),
					zap.Error(err))
			}
			eventbus.GetBus().Publish(eventbus.Event{
				Type: "rule.executed",
				Payload: map[string]interface{}{
					"rule_id":  rule.ID,
					"event_id": event.EventID,
					"results":  results,
				},
			})
		}
	}
}

func (e *Engine) Stop() {
	e.cancel()
	close(e.eventQueue)
	e.wg.Wait()
	logger.Get().Info("Rule engine stopped")
}
