package application

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type DataProcessorService struct {
	logger         domain.Logger
	rules          atomic.Value
	rulesFile      string
	rulesMu        sync.RWMutex
	version        int64
	history        []RuleVersion
	maxHistory     int
	watcher        *RulesWatcher
	listeners      []func(oldRules, newRules map[string]interface{})
	listenerMu     sync.RWMutex
	validationFunc func(map[string]interface{}) error
}

type RuleVersion struct {
	Version   int64                  `json:"version"`
	Rules     map[string]interface{} `json:"rules"`
	AppliedAt time.Time              `json:"applied_at"`
	ChangedBy string                 `json:"changed_by,omitempty"`
	Reason    string                 `json:"reason,omitempty"`
}

type RulesWatcher struct {
	rulesFile string
	interval  time.Duration
	stopCh    chan struct{}
	processor *DataProcessorService
	lastHash  string
}

type ProcessorConfig struct {
	RulesFile    string
	MaxHistory   int
	PollInterval time.Duration
	AutoReload   bool
}

func DefaultProcessorConfig() ProcessorConfig {
	return ProcessorConfig{
		RulesFile:    "./config/rules.json",
		MaxHistory:   100,
		PollInterval: 5 * time.Second,
		AutoReload:   true,
	}
}

func NewDataProcessorService(logger domain.Logger) *DataProcessorService {
	return NewDataProcessorServiceWithConfig(logger, DefaultProcessorConfig())
}

func NewDataProcessorServiceWithConfig(logger domain.Logger, cfg ProcessorConfig) *DataProcessorService {
	s := &DataProcessorService{
		logger:     logger,
		rulesFile:  cfg.RulesFile,
		history:    []RuleVersion{},
		maxHistory: cfg.MaxHistory,
	}

	s.rules.Store(make(map[string]interface{}))

	_ = s.loadRulesFromFile()

	if cfg.AutoReload {
		s.watcher = &RulesWatcher{
			rulesFile: cfg.RulesFile,
			interval:  cfg.PollInterval,
			stopCh:    make(chan struct{}),
			processor: s,
		}
		go s.watcher.Start()
	}

	return s
}

func (s *DataProcessorService) loadRulesFromFile() error {
	if s.rulesFile == "" {
		return nil
	}

	if _, err := os.Stat(s.rulesFile); os.IsNotExist(err) {
		s.logger.Warn("rules file not found, using empty rules", "file", s.rulesFile)
		return nil
	}

	data, err := os.ReadFile(s.rulesFile)
	if err != nil {
		return fmt.Errorf("read rules file: %w", err)
	}

	var rules map[string]interface{}
	if err := json.Unmarshal(data, &rules); err != nil {
		return fmt.Errorf("parse rules file: %w", err)
	}

	if s.validationFunc != nil {
		if err := s.validationFunc(rules); err != nil {
			return fmt.Errorf("rules validation failed: %w", err)
		}
	}

	s.rulesMu.Lock()
	defer s.rulesMu.Unlock()

	newVersion := atomic.AddInt64(&s.version, 1)
	s.rules.Store(rules)

	s.history = append(s.history, RuleVersion{
		Version:   newVersion,
		Rules:     deepCopyRules(rules),
		AppliedAt: time.Now().UTC(),
	})

	if len(s.history) > s.maxHistory {
		s.history = s.history[len(s.history)-s.maxHistory:]
	}

	s.notifyListeners(nil, rules)

	s.logger.Info("rules loaded", "version", newVersion, "rule_count", len(rules))
	return nil
}

func (s *DataProcessorService) UpdateRules(newRules map[string]interface{}, reason, changedBy string) error {
	if s.validationFunc != nil {
		if err := s.validationFunc(newRules); err != nil {
			return err
		}
	}

	s.rulesMu.Lock()
	defer s.rulesMu.Unlock()

	oldRules := s.rules.Load().(map[string]interface{})

	newVersion := atomic.AddInt64(&s.version, 1)
	s.rules.Store(deepCopyRules(newRules))

	s.history = append(s.history, RuleVersion{
		Version:   newVersion,
		Rules:     deepCopyRules(newRules),
		AppliedAt: time.Now().UTC(),
		ChangedBy: changedBy,
		Reason:    reason,
	})

	if len(s.history) > s.maxHistory {
		s.history = s.history[len(s.history)-s.maxHistory:]
	}

	if s.rulesFile != "" {
		go s.persistRules(newRules)
	}

	s.notifyListeners(oldRules, newRules)

	s.logger.Info("rules updated", "version", newVersion, "reason", reason, "changed_by", changedBy)
	return nil
}

func (s *DataProcessorService) persistRules(rules map[string]interface{}) {
	dir := filepath.Dir(s.rulesFile)
	if err := os.MkdirAll(dir, 0755); err != nil {
		s.logger.Error("failed to create rules directory", "error", err)
		return
	}

	data, err := json.MarshalIndent(rules, "", "  ")
	if err != nil {
		s.logger.Error("failed to marshal rules", "error", err)
		return
	}

	if err := os.WriteFile(s.rulesFile, data, 0644); err != nil {
		s.logger.Error("failed to write rules file", "error", err)
	}
}

func (s *DataProcessorService) GetCurrentRules() map[string]interface{} {
	rules := s.rules.Load().(map[string]interface{})
	return deepCopyRules(rules)
}

func (s *DataProcessorService) GetVersion() int64 {
	return atomic.LoadInt64(&s.version)
}

func (s *DataProcessorService) RollbackToVersion(targetVersion int64) error {
	s.rulesMu.Lock()
	defer s.rulesMu.Unlock()

	var target *RuleVersion
	for i := len(s.history) - 1; i >= 0; i-- {
		if s.history[i].Version == targetVersion {
			target = &s.history[i]
			break
		}
	}

	if target == nil {
		return apperr.NewNotFoundError(fmt.Sprintf("version %d not found in history", targetVersion))
	}

	oldRules := s.rules.Load().(map[string]interface{})

	newVersion := atomic.AddInt64(&s.version, 1)
	s.rules.Store(deepCopyRules(target.Rules))

	s.history = append(s.history, RuleVersion{
		Version:   newVersion,
		Rules:     deepCopyRules(target.Rules),
		AppliedAt: time.Now().UTC(),
		Reason:    fmt.Sprintf("rollback to version %d", targetVersion),
	})

	s.notifyListeners(oldRules, target.Rules)

	s.logger.Info("rules rolled back", "to_version", targetVersion, "new_version", newVersion)
	return nil
}

func (s *DataProcessorService) GetHistory(limit int) []RuleVersion {
	s.rulesMu.RLock()
	defer s.rulesMu.RUnlock()

	if limit <= 0 || limit > len(s.history) {
		limit = len(s.history)
	}

	result := make([]RuleVersion, limit)
	start := len(s.history) - limit
	for i := 0; i < limit; i++ {
		result[i] = s.history[start+i]
	}
	return result
}

func (s *DataProcessorService) AddRulesChangeListener(listener func(oldRules, newRules map[string]interface{})) {
	s.listenerMu.Lock()
	defer s.listenerMu.Unlock()
	s.listeners = append(s.listeners, listener)
}

func (s *DataProcessorService) notifyListeners(oldRules, newRules map[string]interface{}) {
	s.listenerMu.RLock()
	defer s.listenerMu.RUnlock()

	for _, listener := range s.listeners {
		go listener(deepCopyRules(oldRules), deepCopyRules(newRules))
	}
}

func (s *DataProcessorService) SetValidationFunc(fn func(map[string]interface{}) error) {
	s.validationFunc = fn
}

func (w *RulesWatcher) Start() {
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()

	for {
		select {
		case <-w.stopCh:
			return
		case <-ticker.C:
			w.checkAndReload()
		}
	}
}

func (w *RulesWatcher) Stop() {
	close(w.stopCh)
}

func (w *RulesWatcher) checkAndReload() {
	if w.rulesFile == "" {
		return
	}

	data, err := os.ReadFile(w.rulesFile)
	if err != nil {
		return
	}

	hash := utils.HashBytes(data)
	if hash == w.lastHash {
		return
	}

	w.lastHash = hash
	w.processor.logger.Info("rules file changed, reloading")

	if err := w.processor.loadRulesFromFile(); err != nil {
		w.processor.logger.Error("failed to reload rules", "error", err)
	}
}

func deepCopyRules(src map[string]interface{}) map[string]interface{} {
	if src == nil {
		return make(map[string]interface{})
	}
	dst := make(map[string]interface{})
	for k, v := range src {
		dst[k] = deepCopyValue(v)
	}
	return dst
}

func deepCopyValue(v interface{}) interface{} {
	switch val := v.(type) {
	case map[string]interface{}:
		dst := make(map[string]interface{})
		for mk, mv := range val {
			dst[mk] = deepCopyValue(mv)
		}
		return dst
	case []interface{}:
		dst := make([]interface{}, len(val))
		for i, item := range val {
			dst[i] = deepCopyValue(item)
		}
		return dst
	default:
		return v
	}
}

func (s *DataProcessorService) Transform(ctx context.Context, data map[string]interface{}, rules map[string]interface{}) (map[string]interface{}, error) {
	if data == nil {
		return nil, apperr.NewValidationError("data is required", "input data cannot be nil")
	}

	result := make(map[string]interface{})
	for k, v := range data {
		result[k] = v
	}

	activeRules := rules
	if activeRules == nil {
		activeRules = s.GetCurrentRules()
	}

	if len(activeRules) == 0 {
		return result, nil
	}

	for field, ruleRaw := range activeRules {
		rule, ok := ruleRaw.(map[string]interface{})
		if !ok {
			continue
		}

		if action, ok := rule["action"].(string); ok {
			value, exists := result[field]
			if !exists {
				continue
			}

			switch action {
			case "rename":
				if newName, ok := rule["to"].(string); ok {
					delete(result, field)
					result[newName] = value
				}
			case "type_cast":
				if targetType, ok := rule["type"].(string); ok {
					converted, err := s.castType(value, targetType)
					if err == nil {
						result[field] = converted
					}
				}
			case "format_date":
				if format, ok := rule["format"].(string); ok {
					formatted, err := s.formatDate(value, format)
					if err == nil {
						result[field] = formatted
					}
				}
			case "trim":
				if str, ok := value.(string); ok {
					result[field] = strings.TrimSpace(str)
				}
			case "lowercase":
				if str, ok := value.(string); ok {
					result[field] = strings.ToLower(str)
				}
			case "uppercase":
				if str, ok := value.(string); ok {
					result[field] = strings.ToUpper(str)
				}
			case "default":
				if result[field] == nil {
					result[field] = rule["value"]
				}
			case "remove":
				delete(result, field)
			case "keep":
			default:
			}
		}
	}

	s.logger.Debug("data transformed", "input_fields", len(data), "output_fields", len(result), "rules_version", s.GetVersion())
	return result, nil
}

func (s *DataProcessorService) Normalize(ctx context.Context, data map[string]interface{}) (map[string]interface{}, error) {
	if data == nil {
		return nil, apperr.NewValidationError("data is required", "input data cannot be nil")
	}

	result := make(map[string]interface{})

	for k, v := range data {
		normalizedKey := strings.TrimSpace(strings.ToLower(k))
		normalizedValue := s.normalizeValue(v)
		result[normalizedKey] = normalizedValue
	}

	if _, ok := result["created_at"]; !ok {
		result["created_at"] = time.Now().UTC().Format(time.RFC3339)
	}
	if _, ok := result["updated_at"]; !ok {
		result["updated_at"] = time.Now().UTC().Format(time.RFC3339)
	}

	return result, nil
}

func (s *DataProcessorService) Validate(ctx context.Context, data map[string]interface{}) error {
	if data == nil {
		return apperr.NewValidationError("data is required", "input data cannot be nil")
	}

	var violations []string

	if id, ok := data["id"].(string); !ok || id == "" {
		violations = append(violations, "field 'id' is required and must be a non-empty string")
	}

	if status, ok := data["status"].(string); ok {
		validStatuses := map[string]bool{"pending": true, "running": true, "completed": true, "failed": true}
		if !validStatuses[status] {
			violations = append(violations, fmt.Sprintf("invalid status: %s", status))
		}
	}

	rules := s.GetCurrentRules()
	for field, ruleRaw := range rules {
		rule, ok := ruleRaw.(map[string]interface{})
		if !ok {
			continue
		}

		if required, ok := rule["required"].(bool); ok && required {
			if _, exists := data[field]; !exists {
				violations = append(violations, fmt.Sprintf("required field '%s' is missing", field))
			}
		}

		if pattern, ok := rule["pattern"].(string); ok {
			if val, exists := data[field].(string); exists {
				if !matchPattern(val, pattern) {
					violations = append(violations, fmt.Sprintf("field '%s' does not match pattern %s", field, pattern))
				}
			}
		}
	}

	if len(violations) > 0 {
		return apperr.NewValidationError("validation failed", strings.Join(violations, "; "))
	}

	return nil
}

func matchPattern(value, pattern string) bool {
	if pattern == "" {
		return true
	}
	if pattern == "*" {
		return true
	}
	if strings.HasPrefix(pattern, "*") && strings.HasSuffix(pattern, "*") {
		return strings.Contains(value, pattern[1:len(pattern)-1])
	}
	if strings.HasPrefix(pattern, "*") {
		return strings.HasSuffix(value, pattern[1:])
	}
	if strings.HasSuffix(pattern, "*") {
		return strings.HasPrefix(value, pattern[:len(pattern)-1])
	}
	return value == pattern
}

func (s *DataProcessorService) normalizeValue(v interface{}) interface{} {
	switch val := v.(type) {
	case string:
		return strings.TrimSpace(val)
	case map[string]interface{}:
		normalized := make(map[string]interface{})
		for mk, mv := range val {
			normalized[strings.TrimSpace(strings.ToLower(mk))] = s.normalizeValue(mv)
		}
		return normalized
	case []interface{}:
		normalized := make([]interface{}, len(val))
		for i, item := range val {
			normalized[i] = s.normalizeValue(item)
		}
		return normalized
	default:
		return v
	}
}

func (s *DataProcessorService) castType(value interface{}, targetType string) (interface{}, error) {
	switch targetType {
	case "string":
		return fmt.Sprintf("%v", value), nil
	case "int":
		switch v := value.(type) {
		case float64:
			return int(v), nil
		case string:
			return strconv.Atoi(v)
		case int:
			return v, nil
		}
	case "float64":
		switch v := value.(type) {
		case int:
			return float64(v), nil
		case string:
			return strconv.ParseFloat(v, 64)
		case float64:
			return v, nil
		}
	case "bool":
		switch v := value.(type) {
		case bool:
			return v, nil
		case string:
			return strconv.ParseBool(v)
		}
	case "json_string":
		data, err := json.Marshal(value)
		if err != nil {
			return nil, err
		}
		return string(data), nil
	}
	return nil, fmt.Errorf("unsupported type conversion to %s", targetType)
}

func (s *DataProcessorService) formatDate(value interface{}, format string) (string, error) {
	var t time.Time
	var err error

	switch v := value.(type) {
	case string:
		formats := []string{
			time.RFC3339,
			"2006-01-02T15:04:05",
			"2006-01-02 15:04:05",
			"2006-01-02",
		}
		for _, f := range formats {
			if t, err = time.Parse(f, v); err == nil {
				break
			}
		}
		if err != nil {
			return "", fmt.Errorf("unable to parse date: %s", v)
		}
	case time.Time:
		t = v
	default:
		return "", fmt.Errorf("unsupported date type")
	}

	return t.Format(format), nil
}

func (s *DataProcessorService) BatchTransform(ctx context.Context, data []map[string]interface{}) ([]map[string]interface{}, error) {
	results := make([]map[string]interface{}, len(data))
	var firstErr error

	for i, item := range data {
		result, err := s.Transform(ctx, item, nil)
		if err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		results[i] = result
	}

	return results, firstErr
}

func (s *DataProcessorService) Close() {
	if s.watcher != nil {
		s.watcher.Stop()
	}
}

func (s *DataProcessorService) ExportRules() (string, error) {
	rules := s.GetCurrentRules()
	data, err := json.MarshalIndent(rules, "", "  ")
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (s *DataProcessorService) ImportRules(jsonStr string, reason string) error {
	var rules map[string]interface{}
	if err := json.Unmarshal([]byte(jsonStr), &rules); err != nil {
		return err
	}
	return s.UpdateRules(rules, reason, "import")
}

func (s *DataProcessorService) GetRule(field string) (map[string]interface{}, bool) {
	rules := s.GetCurrentRules()
	rule, ok := rules[field]
	if !ok {
		return nil, false
	}
	ruleMap, ok := rule.(map[string]interface{})
	if !ok {
		return nil, false
	}
	return deepCopyRules(ruleMap), true
}

func (s *DataProcessorService) SetRule(field string, rule map[string]interface{}, reason string) error {
	rules := s.GetCurrentRules()
	rules[field] = rule
	return s.UpdateRules(rules, reason, "set_rule")
}

func (s *DataProcessorService) RemoveRule(field string, reason string) error {
	rules := s.GetCurrentRules()
	delete(rules, field)
	return s.UpdateRules(rules, reason, "remove_rule")
}

type RuleDiff struct {
	Field   string
	Action  string
	OldRule map[string]interface{}
	NewRule map[string]interface{}
}

func (s *DataProcessorService) DiffRules(version1, version2 int64) ([]RuleDiff, error) {
	s.rulesMu.RLock()
	defer s.rulesMu.RUnlock()

	var r1, r2 *RuleVersion
	for _, rv := range s.history {
		if rv.Version == version1 {
			r1 = &rv
		}
		if rv.Version == version2 {
			r2 = &rv
		}
	}

	if r1 == nil {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("version %d not found", version1))
	}
	if r2 == nil {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("version %d not found", version2))
	}

	var diffs []RuleDiff
	allFields := make(map[string]bool)
	for k := range r1.Rules {
		allFields[k] = true
	}
	for k := range r2.Rules {
		allFields[k] = true
	}

	for field := range allFields {
		oldVal, oldOk := r1.Rules[field]
		newVal, newOk := r2.Rules[field]

		oldRule, _ := oldVal.(map[string]interface{})
		newRule, _ := newVal.(map[string]interface{})

		switch {
		case oldOk && !newOk:
			diffs = append(diffs, RuleDiff{Field: field, Action: "removed", OldRule: oldRule})
		case !oldOk && newOk:
			diffs = append(diffs, RuleDiff{Field: field, Action: "added", NewRule: newRule})
		default:
			if !rulesEqual(oldRule, newRule) {
				diffs = append(diffs, RuleDiff{Field: field, Action: "modified", OldRule: oldRule, NewRule: newRule})
			}
		}
	}

	return diffs, nil
}

func rulesEqual(a, b map[string]interface{}) bool {
	aj, _ := json.Marshal(a)
	bj, _ := json.Marshal(b)
	return string(aj) == string(bj)
}
