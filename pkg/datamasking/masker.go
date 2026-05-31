package datamasking

import (
	"context"
	"fmt"
	"strings"
	"sync"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type MaskingStrategy string

const (
	FullMask      MaskingStrategy = "full"
	PartialMask   MaskingStrategy = "partial"
	HashMask      MaskingStrategy = "hash"
	ReplaceMask   MaskingStrategy = "replace"
	TruncateMask  MaskingStrategy = "truncate"
	RoundMask     MaskingStrategy = "round"
)

type DefaultDataMasker struct {
	rules  map[string]*interfaces.MaskingRule
	logger *zap.Logger
	mu     sync.RWMutex
}

func NewDefaultDataMasker() *DefaultDataMasker {
	m := &DefaultDataMasker{
		rules:  make(map[string]*interfaces.MaskingRule),
		logger: utils.GetLogger(),
	}
	m.loadDefaultRules()
	return m
}

func (m *DefaultDataMasker) loadDefaultRules() {
	defaultRules := []*interfaces.MaskingRule{
		{
			FieldName:       "phone",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "operator"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      3,
			KeepSuffix:      4,
		},
		{
			FieldName:       "id_card",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      6,
			KeepSuffix:      4,
		},
		{
			FieldName:       "email",
			Sensitivity:     "medium",
			AllowedRoles:    []string{"admin", "operator", "analyst"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      2,
			KeepSuffix:      0,
		},
		{
			FieldName:       "bank_card",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      4,
			KeepSuffix:      4,
		},
		{
			FieldName:       "address",
			Sensitivity:     "medium",
			AllowedRoles:    []string{"admin", "operator"},
			MaskingStrategy: string(TruncateMask),
			MaskChar:        "*",
			KeepPrefix:      6,
			KeepSuffix:      0,
		},
		{
			FieldName:       "name",
			Sensitivity:     "low",
			AllowedRoles:    []string{"admin", "operator", "analyst", "viewer"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      1,
			KeepSuffix:      0,
		},
		{
			FieldName:       "password",
			Sensitivity:     "high",
			AllowedRoles:    []string{},
			MaskingStrategy: string(FullMask),
			MaskChar:        "*",
			KeepPrefix:      0,
			KeepSuffix:      0,
		},
		{
			FieldName:       "salary",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "hr_manager"},
			MaskingStrategy: string(RoundMask),
			MaskChar:        "*",
			KeepPrefix:      0,
			KeepSuffix:      0,
		},
	}

	for _, rule := range defaultRules {
		m.rules[rule.FieldName] = rule
	}
}

func (m *DefaultDataMasker) RegisterRule(rule interfaces.MaskingRule) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.rules[rule.FieldName] = &rule
	m.logger.Info("Masking rule registered", zap.String("field", rule.FieldName))
}

func (m *DefaultDataMasker) RemoveRule(fieldName string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.rules, fieldName)
	m.logger.Info("Masking rule removed", zap.String("field", fieldName))
}

func (m *DefaultDataMasker) Mask(ctx context.Context, data map[string]interface{}, userRoles []string) (map[string]interface{}, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make(map[string]interface{}, len(data))

	for field, value := range data {
		rule, hasRule := m.rules[field]

		if !hasRule {
			result[field] = value
			continue
		}

		if m.hasPermission(rule.AllowedRoles, userRoles) {
			result[field] = value
			m.logger.Debug("Field access allowed",
				zap.String("field", field),
				zap.Strings("user_roles", userRoles),
			)
			continue
		}

		maskedValue := m.applyMasking(value, rule)
		result[field] = maskedValue

		m.logger.Debug("Field masked",
			zap.String("field", field),
			zap.String("strategy", rule.MaskingStrategy),
			zap.Strings("allowed_roles", rule.AllowedRoles),
			zap.Strings("user_roles", userRoles),
		)
	}

	return result, nil
}

func (m *DefaultDataMasker) MaskBatch(ctx context.Context, data []map[string]interface{}, userRoles []string) ([]map[string]interface{}, error) {
	result := make([]map[string]interface{}, len(data))
	var err error

	for i, item := range data {
		result[i], err = m.Mask(ctx, item, userRoles)
		if err != nil {
			return nil, err
		}
	}

	return result, nil
}

func (m *DefaultDataMasker) hasPermission(allowedRoles, userRoles []string) bool {
	if len(allowedRoles) == 0 {
		return false
	}

	for _, userRole := range userRoles {
		for _, allowed := range allowedRoles {
			if userRole == allowed {
				return true
			}
		}
	}

	return false
}

func (m *DefaultDataMasker) applyMasking(value interface{}, rule *interfaces.MaskingRule) interface{} {
	strValue := fmt.Sprintf("%v", value)

	switch MaskingStrategy(rule.MaskingStrategy) {
	case FullMask:
		return m.maskFull(strValue, rule.MaskChar)
	case PartialMask:
		return m.maskPartial(strValue, rule.MaskChar, rule.KeepPrefix, rule.KeepSuffix)
	case HashMask:
		return m.maskHash(strValue)
	case ReplaceMask:
		return rule.MaskChar
	case TruncateMask:
		return m.maskTruncate(strValue, rule.KeepPrefix)
	case RoundMask:
		return m.maskRound(value)
	default:
		return m.maskPartial(strValue, rule.MaskChar, rule.KeepPrefix, rule.KeepSuffix)
	}
}

func (m *DefaultDataMasker) maskFull(value, maskChar string) string {
	if value == "" {
		return value
	}
	return strings.Repeat(maskChar, len(value))
}

func (m *DefaultDataMasker) maskPartial(value, maskChar string, keepPrefix, keepSuffix int) string {
	if value == "" {
		return value
	}

	runes := []rune(value)
	length := len(runes)

	if keepPrefix >= length || keepSuffix >= length {
		return value
	}

	if keepPrefix+keepSuffix >= length {
		return m.maskFull(value, maskChar)
	}

	prefix := string(runes[:keepPrefix])
	suffix := ""
	if keepSuffix > 0 {
		suffix = string(runes[length-keepSuffix:])
	}

	maskLength := length - keepPrefix - keepSuffix
	masked := strings.Repeat(maskChar, maskLength)

	return prefix + masked + suffix
}

func (m *DefaultDataMasker) maskHash(value string) string {
	return utils.CalculateStringHash(value)[:16]
}

func (m *DefaultDataMasker) maskTruncate(value string, keepPrefix int) string {
	if value == "" {
		return value
	}

	runes := []rune(value)
	if keepPrefix >= len(runes) {
		return value
	}

	return string(runes[:keepPrefix]) + "..."
}

func (m *DefaultDataMasker) maskRound(value interface{}) interface{} {
	switch v := value.(type) {
	case float64:
		return mathRound(v, -2)
	case float32:
		return float32(mathRound(float64(v), -2))
	case int:
		return int(mathRound(float64(v), -2))
	case int64:
		return int64(mathRound(float64(v), -2))
	default:
		strValue := fmt.Sprintf("%v", value)
		return "***"
	}
}

func mathRound(x float64, n int) float64 {
	if n >= 0 {
		return x
	}

	multiplier := mathPow(10, float64(-n))
	return mathFloatRound(x*multiplier) / multiplier
}

func mathPow(x, y float64) float64 {
	result := 1.0
	for i := 0; i < int(y); i++ {
		result *= x
	}
	return result
}

func mathFloatRound(x float64) float64 {
	if x >= 0 {
		return float64(int(x + 0.5))
	}
	return float64(int(x - 0.5))
}

func (m *DefaultDataMasker) GetRule(fieldName string) (*interfaces.MaskingRule, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	rule, exists := m.rules[fieldName]
	if !exists {
		return nil, false
	}

	result := *rule
	return &result, true
}

func (m *DefaultDataMasker) GetAllRules() map[string]interfaces.MaskingRule {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make(map[string]interfaces.MaskingRule, len(m.rules))
	for k, v := range m.rules {
		result[k] = *v
	}
	return result
}

func (m *DefaultDataMasker) GetMaskableFields() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	fields := make([]string, 0, len(m.rules))
	for field := range m.rules {
		fields = append(fields, field)
	}
	return fields
}

func (m *DefaultDataMasker) CanAccess(fieldName string, userRoles []string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()

	rule, exists := m.rules[fieldName]
	if !exists {
		return true
	}

	return m.hasPermission(rule.AllowedRoles, userRoles)
}

type PresetBuilder struct {
	masker *DefaultDataMasker
}

func NewPresetBuilder(masker *DefaultDataMasker) *PresetBuilder {
	return &PresetBuilder{masker: masker}
}

func (b *PresetBuilder) WithPIIPreset() *PresetBuilder {
	piiRules := []*interfaces.MaskingRule{
		{
			FieldName:       "ssn",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "compliance"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      0,
			KeepSuffix:      4,
		},
		{
			FieldName:       "passport",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "compliance"},
			MaskingStrategy: string(HashMask),
			MaskChar:        "*",
		},
		{
			FieldName:       "driver_license",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "compliance"},
			MaskingStrategy: string(HashMask),
			MaskChar:        "*",
		},
	}

	for _, rule := range piiRules {
		b.masker.RegisterRule(*rule)
	}

	return b
}

func (b *PresetBuilder) WithFinancialPreset() *PresetBuilder {
	financialRules := []*interfaces.MaskingRule{
		{
			FieldName:       "credit_card",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "finance"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      4,
			KeepSuffix:      4,
		},
		{
			FieldName:       "account_number",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "finance"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      2,
			KeepSuffix:      2,
		},
		{
			FieldName:       "tax_id",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "finance", "compliance"},
			MaskingStrategy: string(HashMask),
			MaskChar:        "*",
		},
	}

	for _, rule := range financialRules {
		b.masker.RegisterRule(*rule)
	}

	return b
}

func (b *PresetBuilder) WithHealthcarePreset() *PresetBuilder {
	healthRules := []*interfaces.MaskingRule{
		{
			FieldName:       "medical_record",
			Sensitivity:     "high",
			AllowedRoles:    []string{"admin", "doctor"},
			MaskingStrategy: string(FullMask),
			MaskChar:        "*",
		},
		{
			FieldName:       "insurance_id",
			Sensitivity:     "medium",
			AllowedRoles:    []string{"admin", "doctor", "insurance"},
			MaskingStrategy: string(PartialMask),
			MaskChar:        "*",
			KeepPrefix:      3,
			KeepSuffix:      2,
		},
	}

	for _, rule := range healthRules {
		b.masker.RegisterRule(*rule)
	}

	return b
}

func (b *PresetBuilder) Build() *DefaultDataMasker {
	return b.masker
}
