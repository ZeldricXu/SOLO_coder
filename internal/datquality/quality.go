package datquality

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/robfig/cron/v3"
	"gorm.io/gorm"
	"session172/internal/dataaccess"
	applogger "session172/internal/logger"
	"session172/pkg/models"
	"session172/pkg/utils"
)

const (
	defaultMaxResults = 1000
	defaultCronExpr   = "0 * * * *"
)

var (
	ErrRuleNotFound    = fmt.Errorf("quality rule not found")
	ErrPoolUnavailable = fmt.Errorf("database pool not available")
	ErrConnection      = fmt.Errorf("failed to get database connection")
	ErrUnsupportedType = fmt.Errorf("unsupported rule type")
)

type (
	RuleValidator interface {
		Validate(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, result *models.QualityCheckResult) (*models.QualityCheckResult, error)
		Name() string
	}

	BaseValidator struct{}

	NullCheckValidator       struct{ BaseValidator }
	RangeCheckValidator      struct{ BaseValidator }
	RegexCheckValidator      struct{ BaseValidator }
	UniquenessCheckValidator struct{ BaseValidator }
	CustomSQLValidator       struct{ BaseValidator }

	RuleEngine struct {
		mu        sync.RWMutex
		rules     map[string]*models.DataQualityRule
		cron      *cron.Cron
		entries   map[string]cron.EntryID
		results   []*models.QualityCheckResult
		validators map[string]RuleValidator
	}
)

var (
	engineInstance *RuleEngine
	engineOnce     sync.Once
)

func NewRuleEngine() *RuleEngine {
	engineOnce.Do(func() {
		engine := &RuleEngine{
			rules:      make(map[string]*models.DataQualityRule),
			cron:       cron.New(),
			entries:    make(map[string]cron.EntryID),
			results:    make([]*models.QualityCheckResult, 0),
			validators: make(map[string]RuleValidator),
		}
		engine.registerValidators()
		engineInstance = engine
	})
	return engineInstance
}

func (re *RuleEngine) registerValidators() {
	re.validators["null_check"] = &NullCheckValidator{}
	re.validators["range_check"] = &RangeCheckValidator{}
	re.validators["regex_check"] = &RegexCheckValidator{}
	re.validators["uniqueness_check"] = &UniquenessCheckValidator{}
	re.validators["custom_sql"] = &CustomSQLValidator{}
}

func GetEngine() *RuleEngine {
	if engineInstance == nil {
		return NewRuleEngine()
	}
	return engineInstance
}

func (v BaseValidator) Name() string {
	return "base"
}

func (v *NullCheckValidator) Name() string {
	return "null_check"
}

func (v *NullCheckValidator) Validate(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, result *models.QualityCheckResult) (*models.QualityCheckResult, error) {
	totalCount, badCount := v.countTotalAndBad(db, rule,
		fmt.Sprintf("%s IS NULL", rule.Column))

	result.TotalRows = totalCount
	result.BadRows = badCount
	result.ErrorRate = v.calculateErrorRate(totalCount, badCount)
	result.SampleData = v.buildSampleData(map[string]interface{}{
		"table":      rule.Table,
		"column":     rule.Column,
		"null_count": badCount,
	})
	return result, nil
}

func (v *RangeCheckValidator) Name() string {
	return "range_check"
}

func (v *RangeCheckValidator) Validate(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, result *models.QualityCheckResult) (*models.QualityCheckResult, error) {
	minVal, _ := rule.Params["min"].(float64)
	maxVal, _ := rule.Params["max"].(float64)

	var totalCount int64
	db.Table(rule.Table).Count(&totalCount)

	var badCount int64
	query := db.Table(rule.Table)
	if minVal != 0 {
		query = query.Where(fmt.Sprintf("%s < ?", rule.Column), minVal)
	}
	if maxVal != 0 {
		query = query.Or(fmt.Sprintf("%s > ?", rule.Column), maxVal)
	}
	query.Count(&badCount)

	result.TotalRows = totalCount
	result.BadRows = badCount
	result.ErrorRate = v.calculateErrorRate(totalCount, badCount)
	result.SampleData = v.buildSampleData(map[string]interface{}{
		"table":        rule.Table,
		"column":       rule.Column,
		"min":          minVal,
		"max":          maxVal,
		"out_of_range": badCount,
	})
	return result, nil
}

func (v *RegexCheckValidator) Name() string {
	return "regex_check"
}

func (v *RegexCheckValidator) Validate(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, result *models.QualityCheckResult) (*models.QualityCheckResult, error) {
	pattern, _ := rule.Params["pattern"].(string)
	totalCount, badCount := v.countTotalAndBad(db, rule,
		fmt.Sprintf("%s !~ ?", rule.Column), pattern)

	result.TotalRows = totalCount
	result.BadRows = badCount
	result.ErrorRate = v.calculateErrorRate(totalCount, badCount)
	result.SampleData = v.buildSampleData(map[string]interface{}{
		"table":         rule.Table,
		"column":        rule.Column,
		"pattern":       pattern,
		"invalid_count": badCount,
	})
	return result, nil
}

func (v *UniquenessCheckValidator) Name() string {
	return "uniqueness_check"
}

func (v *UniquenessCheckValidator) Validate(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, result *models.QualityCheckResult) (*models.QualityCheckResult, error) {
	var totalCount int64
	db.Table(rule.Table).Count(&totalCount)

	var distinctCount int64
	db.Table(rule.Table).Distinct(rule.Column).Count(&distinctCount)
	badCount := totalCount - distinctCount

	result.TotalRows = totalCount
	result.BadRows = badCount
	result.ErrorRate = v.calculateErrorRate(totalCount, badCount)
	result.SampleData = v.buildSampleData(map[string]interface{}{
		"table":           rule.Table,
		"column":          rule.Column,
		"total":           totalCount,
		"distinct":        distinctCount,
		"duplicate_count": badCount,
	})
	return result, nil
}

func (v *CustomSQLValidator) Name() string {
	return "custom_sql"
}

func (v *CustomSQLValidator) Validate(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, result *models.QualityCheckResult) (*models.QualityCheckResult, error) {
	sql, _ := rule.Params["sql"].(string)
	if sql == "" {
		return result, fmt.Errorf("custom SQL not provided")
	}

	var badCount int64
	db.Raw(sql).Scan(&badCount)

	var totalCount int64
	db.Table(rule.Table).Count(&totalCount)

	result.TotalRows = totalCount
	result.BadRows = badCount
	result.ErrorRate = v.calculateErrorRate(totalCount, badCount)
	result.SampleData = v.buildSampleData(map[string]interface{}{
		"table":    rule.Table,
		"sql":      sql,
		"bad_rows": badCount,
	})
	return result, nil
}

func (v BaseValidator) countTotalAndBad(db *gorm.DB, rule *models.DataQualityRule, condition string, args ...interface{}) (int64, int64) {
	var totalCount int64
	db.Table(rule.Table).Count(&totalCount)

	var badCount int64
	db.Table(rule.Table).Where(condition, args...).Count(&badCount)
	return totalCount, badCount
}

func (v BaseValidator) calculateErrorRate(total, bad int64) float64 {
	if total > 0 {
		return float64(bad) / float64(total)
	}
	return 0
}

func (v BaseValidator) buildSampleData(data map[string]interface{}) json.RawMessage {
	sampleData, _ := json.Marshal(data)
	return sampleData
}

func (re *RuleEngine) AddRule(rule *models.DataQualityRule) error {
	if rule.ID == "" {
		rule.ID = utils.GenerateID("rule")
	}
	if rule.CreatedAt.IsZero() {
		rule.CreatedAt = time.Now()
	}
	rule.UpdatedAt = time.Now()

	re.mu.Lock()
	re.rules[rule.ID] = rule
	re.mu.Unlock()

	if rule.Enabled && rule.CronExpr != "" {
		if err := re.scheduleRule(rule); err != nil {
			applogger.Errorf("Failed to schedule rule %s: %v", rule.ID, err)
		}
	}

	applogger.Infof("Quality rule added: %s", rule.ID)
	return nil
}

func (re *RuleEngine) scheduleRule(rule *models.DataQualityRule) error {
	re.mu.Lock()
	defer re.mu.Unlock()

	if entryID, exists := re.entries[rule.ID]; exists {
		re.cron.Remove(entryID)
	}

	entryID, err := re.cron.AddFunc(rule.CronExpr, func() {
		ctx := context.Background()
		_, err := re.ExecuteRule(ctx, rule.ID)
		if err != nil {
			applogger.Errorf("Rule execution failed %s: %v", rule.ID, err)
		}
	})
	if err != nil {
		return fmt.Errorf("invalid cron expression: %w", err)
	}

	re.entries[rule.ID] = entryID
	return nil
}

func (re *RuleEngine) RemoveRule(ruleID string) {
	re.mu.Lock()
	defer re.mu.Unlock()

	if entryID, exists := re.entries[ruleID]; exists {
		re.cron.Remove(entryID)
		delete(re.entries, ruleID)
	}

	delete(re.rules, ruleID)
	applogger.Infof("Quality rule removed: %s", ruleID)
}

func (re *RuleEngine) GetRule(ruleID string) (*models.DataQualityRule, bool) {
	re.mu.RLock()
	defer re.mu.RUnlock()
	rule, ok := re.rules[ruleID]
	return rule, ok
}

func (re *RuleEngine) GetAllRules() []*models.DataQualityRule {
	re.mu.RLock()
	defer re.mu.RUnlock()
	rules := make([]*models.DataQualityRule, 0, len(re.rules))
	for _, rule := range re.rules {
		rules = append(rules, rule)
	}
	return rules
}

func (re *RuleEngine) ExecuteRule(ctx context.Context, ruleID string) (*models.QualityCheckResult, error) {
	rule, exists := re.GetRule(ruleID)
	if !exists {
		return nil, ErrRuleNotFound
	}

	db, release, err := re.acquireConnection(ctx)
	if err != nil {
		return nil, err
	}
	defer release()

	start := time.Now()
	result := &models.QualityCheckResult{
		ID:        utils.GenerateID("qr"),
		RuleID:    ruleID,
		CheckedAt: time.Now(),
	}

	validator, ok := re.validators[rule.RuleType]
	if !ok {
		result.Status = "failed"
		result.Message = fmt.Sprintf("%s: %s", ErrUnsupportedType.Error(), rule.RuleType)
		return result, ErrUnsupportedType
	}

	result, err = validator.Validate(ctx, db, rule, result)
	result.Duration = time.Since(start)
	re.setResultStatus(result, err)

	re.storeResult(result)
	applogger.Infof("Rule %s executed: %s, error rate: %.4f", ruleID, result.Status, result.ErrorRate)
	return result, nil
}

func (re *RuleEngine) acquireConnection(ctx context.Context) (*gorm.DB, func(), error) {
	pool := dataaccess.GetPool()
	if pool == nil {
		return nil, nil, ErrPoolUnavailable
	}

	db, err := pool.Get(ctx)
	if err != nil {
		return nil, nil, fmt.Errorf("%w: %v", ErrConnection, err)
	}

	release := func() { pool.Put(db) }
	return db, release, nil
}

func (re *RuleEngine) setResultStatus(result *models.QualityCheckResult, err error) {
	if err != nil {
		result.Status = "failed"
		result.Message = err.Error()
		return
	}

	if result.ErrorRate > 0 {
		result.Status = "warning"
		result.Message = fmt.Sprintf("Found %d bad rows out of %d (error rate: %.2f%%)",
			result.BadRows, result.TotalRows, result.ErrorRate*100)
		return
	}

	result.Status = "passed"
	result.Message = "All checks passed"
}

func (re *RuleEngine) storeResult(result *models.QualityCheckResult) {
	re.mu.Lock()
	defer re.mu.Unlock()
	re.results = append(re.results, result)
	if len(re.results) > defaultMaxResults {
		re.results = re.results[1:]
	}
}

func (re *RuleEngine) ExecuteAllRules(ctx context.Context) []*models.QualityCheckResult {
	rules := re.GetAllRules()
	results := make([]*models.QualityCheckResult, 0, len(rules))

	for _, rule := range rules {
		if rule.Enabled {
			result, err := re.ExecuteRule(ctx, rule.ID)
			if err != nil {
				applogger.Errorf("Rule %s execution error: %v", rule.ID, err)
				continue
			}
			results = append(results, result)
		}
	}
	return results
}

func (re *RuleEngine) GetResults(ruleID string, limit int) []*models.QualityCheckResult {
	re.mu.RLock()
	defer re.mu.RUnlock()

	if limit <= 0 || limit > len(re.results) {
		limit = len(re.results)
	}

	results := make([]*models.QualityCheckResult, 0, limit)
	for i := len(re.results) - 1; i >= 0 && len(results) < limit; i-- {
		if re.results[i].RuleID == ruleID || ruleID == "" {
			results = append(results, re.results[i])
		}
	}
	return results
}

func (re *RuleEngine) Start() {
	re.cron.Start()
	applogger.Info("Quality rule engine started")
}

func (re *RuleEngine) Stop() {
	re.cron.Stop()
	applogger.Info("Quality rule engine stopped")
}

func (re *RuleEngine) MarkAnomalousData(ctx context.Context, result *models.QualityCheckResult) error {
	rule, exists := re.GetRule(result.RuleID)
	if !exists {
		return ErrRuleNotFound
	}

	db, release, err := re.acquireConnection(ctx)
	if err != nil {
		return err
	}
	defer release()

	markColumn, _ := rule.Params["mark_column"].(string)
	if markColumn == "" {
		markColumn = "is_anomalous"
	}

	markValue, _ := rule.Params["mark_value"].(bool)
	if !markValue {
		markValue = true
	}

	updateSQL := fmt.Sprintf("UPDATE %s SET %s = ? WHERE %s IS NULL",
		rule.Table, markColumn, rule.Column)

	return db.Exec(updateSQL, markValue).Error
}
