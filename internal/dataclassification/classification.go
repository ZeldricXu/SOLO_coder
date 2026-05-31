package dataclassification

import (
	"context"
	"encoding/json"
	"regexp"
	"sort"
	"strings"
	"sync"

	"go.uber.org/zap"

	"session316/internal/logger"
	"session316/internal/models"
	"session316/pkg/errors"
	"session316/pkg/utils"
)

type ClassificationLevel string

const (
	LevelPublic    ClassificationLevel = "public"
	LevelInternal  ClassificationLevel = "internal"
	LevelSensitive ClassificationLevel = "sensitive"
	LevelConfidential ClassificationLevel = "confidential"
)

type SensitivePattern struct {
	Name        string
	Description string
	Regex       *regexp.Regexp
	Level       ClassificationLevel
	Severity    int
}

type ScanResult struct {
	FieldName   string
	PatternName string
	Matches     []string
	Level       ClassificationLevel
	Severity    int
}

type ClassificationResult struct {
	DataID      string
	OverallLevel ClassificationLevel
	Scans       []ScanResult
	AppliedPolicy string
	Timestamp   int64
}

type PolicyAction string

const (
	ActionEncrypt   PolicyAction = "encrypt"
	ActionMask      PolicyAction = "mask"
	ActionBlock     PolicyAction = "block"
	ActionAudit     PolicyAction = "audit"
	ActionAllow     PolicyAction = "allow"
)

type Policy struct {
	ID         string
	Name       string
	Level      ClassificationLevel
	Actions    []PolicyAction
	Enabled    bool
	Parameters map[string]interface{}
}

type DataClassificationManager struct {
	mu         sync.RWMutex
	patterns   map[string]*SensitivePattern
	policies   map[string]*Policy
	levelWeights map[ClassificationLevel]int
}

var (
	managerInstance *DataClassificationManager
	managerOnce     sync.Once
)

func defaultPatterns() []*SensitivePattern {
	return []*SensitivePattern{
		{
			Name:        "chinese_mobile",
			Description: "中国手机号码",
			Regex:       regexp.MustCompile(`1[3-9]\d{9}`),
			Level:       LevelSensitive,
			Severity:    70,
		},
		{
			Name:        "chinese_id_card",
			Description: "中国居民身份证号",
			Regex:       regexp.MustCompile(`[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`),
			Level:       LevelConfidential,
			Severity:    95,
		},
		{
			Name:        "bank_card",
			Description: "银行卡号",
			Regex:       regexp.MustCompile(`\d{16,19}`),
			Level:       LevelConfidential,
			Severity:    90,
		},
		{
			Name:        "email",
			Description: "电子邮箱",
			Regex:       regexp.MustCompile(`[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`),
			Level:       LevelSensitive,
			Severity:    50,
		},
		{
			Name:        "ipv4_address",
			Description: "IPv4地址",
			Regex:       regexp.MustCompile(`\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b`),
			Level:       LevelInternal,
			Severity:    30,
		},
		{
			Name:        "chinese_passport",
			Description: "中国护照号",
			Regex:       regexp.MustCompile(`[EeGg]\d{8}`),
			Level:       LevelConfidential,
			Severity:    85,
		},
		{
			Name:        "driver_license",
			Description: "驾驶证号",
			Regex:       regexp.MustCompile(`[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{4}`),
			Level:       LevelSensitive,
			Severity:    80,
		},
		{
			Name:        "social_security",
			Description: "社会保障号",
			Regex:       regexp.MustCompile(`\d{3}-\d{2}-\d{4}`),
			Level:       LevelConfidential,
			Severity:    92,
		},
		{
			Name:        "credit_card",
			Description: "信用卡号",
			Regex:       regexp.MustCompile(`(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})`),
			Level:       LevelConfidential,
			Severity:    95,
		},
		{
			Name:        "api_key",
			Description: "API密钥",
			Regex:       regexp.MustCompile(`(api[_-]?key|sk[_-]|pk[_-])[a-zA-Z0-9]{16,}`),
			Level:       LevelConfidential,
			Severity:    100,
		},
		{
			Name:        "password_hash",
			Description: "密码哈希",
			Regex:       regexp.MustCompile(`\$(2a|2b|2y|pbkdf2|argon2[i,d,id])\$[a-zA-Z0-9$./,]{20,}`),
			Level:       LevelConfidential,
			Severity:    100,
		},
		{
			Name:        "chinese_license_plate",
			Description: "中国车牌号",
			Regex:       regexp.MustCompile(`[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-Z][A-HJ-NP-Z0-9]{5,6}`),
			Level:       LevelSensitive,
			Severity:    40,
		},
	}
}

func defaultPolicies() []*Policy {
	return []*Policy{
		{
			ID:      "policy_public",
			Name:    "公开数据策略",
			Level:   LevelPublic,
			Actions: []PolicyAction{ActionAllow, ActionAudit},
			Enabled: true,
			Parameters: map[string]interface{}{
				"audit_level": "low",
			},
		},
		{
			ID:      "policy_internal",
			Name:    "内部数据策略",
			Level:   LevelInternal,
			Actions: []PolicyAction{ActionAllow, ActionAudit},
			Enabled: true,
			Parameters: map[string]interface{}{
				"audit_level": "medium",
				"require_role": "internal",
			},
		},
		{
			ID:      "policy_sensitive",
			Name:    "敏感数据策略",
			Level:   LevelSensitive,
			Actions: []PolicyAction{ActionMask, ActionEncrypt, ActionAudit},
			Enabled: true,
			Parameters: map[string]interface{}{
				"audit_level": "high",
				"mask_fields": []string{"chinese_mobile", "email", "driver_license"},
				"encryption_algorithm": "AES-256-GCM",
			},
		},
		{
			ID:      "policy_confidential",
			Name:    "机密数据策略",
			Level:   LevelConfidential,
			Actions: []PolicyAction{ActionBlock, ActionEncrypt, ActionAudit},
			Enabled: true,
			Parameters: map[string]interface{}{
				"audit_level": "critical",
				"encryption_algorithm": "RSA-2048+AES-256",
				"require_role": "admin",
			},
		},
	}
}

func GetManager() *DataClassificationManager {
	managerOnce.Do(func() {
		managerInstance = &DataClassificationManager{
			patterns: make(map[string]*SensitivePattern),
			policies: make(map[string]*Policy),
			levelWeights: map[ClassificationLevel]int{
				LevelPublic:       0,
				LevelInternal:     10,
				LevelSensitive:    50,
				LevelConfidential: 100,
			},
		}

		for _, p := range defaultPatterns() {
			managerInstance.patterns[p.Name] = p
		}

		for _, policy := range defaultPolicies() {
			managerInstance.policies[policy.ID] = policy
		}

		logger.Info("DataClassificationManager initialized",
			zap.Int("patterns_count", len(managerInstance.patterns)),
			zap.Int("policies_count", len(managerInstance.policies)),
		)
	})
	return managerInstance
}

func (m *DataClassificationManager) AddPattern(pattern *SensitivePattern) error {
	if pattern == nil {
		return errors.ValidationError("pattern", "cannot be nil")
	}
	if pattern.Name == "" {
		return errors.ValidationError("pattern.name", "cannot be empty")
	}
	if pattern.Regex == nil {
		return errors.ValidationError("pattern.regex", "cannot be nil")
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	m.patterns[pattern.Name] = pattern

	logger.Info("Pattern added", zap.String("name", pattern.Name), zap.String("level", string(pattern.Level)))
	return nil
}

func (m *DataClassificationManager) RemovePattern(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.patterns, name)
	logger.Info("Pattern removed", zap.String("name", name))
}

func (m *DataClassificationManager) AddPolicy(policy *Policy) error {
	if policy == nil {
		return errors.ValidationError("policy", "cannot be nil")
	}
	if policy.ID == "" {
		return errors.ValidationError("policy.id", "cannot be empty")
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	m.policies[policy.ID] = policy

	logger.Info("Policy added", zap.String("id", policy.ID), zap.String("level", string(policy.Level)))
	return nil
}

func (m *DataClassificationManager) RemovePolicy(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.policies, id)
	logger.Info("Policy removed", zap.String("id", id))
}

func (m *DataClassificationManager) GetPatterns() []*SensitivePattern {
	m.mu.RLock()
	defer m.mu.RUnlock()
	patterns := make([]*SensitivePattern, 0, len(m.patterns))
	for _, p := range m.patterns {
		patterns = append(patterns, p)
	}
	return patterns
}

func (m *DataClassificationManager) GetPolicies() []*Policy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	policies := make([]*Policy, 0, len(m.policies))
	for _, p := range m.policies {
		policies = append(policies, p)
	}
	return policies
}

func (m *DataClassificationManager) ScanData(ctx context.Context, data interface{}) ([]ScanResult, error) {
	logger.Debug("Starting data scan")

	var scanResults []ScanResult

	switch v := data.(type) {
	case map[string]interface{}:
		results, err := m.scanMap(v, "")
		if err != nil {
			return nil, errors.Wrap(err, errors.ErrCodeInternal, "scan map failed")
		}
		scanResults = results
	case string:
		results := m.scanString(v, "raw_data")
		scanResults = results
	case []byte:
		results := m.scanString(string(v), "raw_data")
		scanResults = results
	case models.Entity:
		results, err := m.scanMap(v.Attributes, "entity.attributes")
		if err != nil {
			return nil, errors.Wrap(err, errors.ErrCodeInternal, "scan entity failed")
		}
		scanResults = results
	default:
		jsonData, err := json.Marshal(data)
		if err != nil {
			return nil, errors.Wrap(err, errors.ErrCodeValidation, "failed to marshal data for scanning")
		}
		var mapData map[string]interface{}
		if err := json.Unmarshal(jsonData, &mapData); err == nil {
			results, err := m.scanMap(mapData, "")
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeInternal, "scan converted map failed")
			}
			scanResults = results
		} else {
			results := m.scanString(string(jsonData), "raw_data")
			scanResults = results
		}
	}

	logger.Info("Data scan completed",
		zap.Int("scan_result_count", len(scanResults)),
	)

	return scanResults, nil
}

func (m *DataClassificationManager) scanMap(data map[string]interface{}, prefix string) ([]ScanResult, error) {
	var results []ScanResult

	for key, value := range data {
		fieldName := key
		if prefix != "" {
			fieldName = prefix + "." + key
		}

		switch v := value.(type) {
		case string:
			scanResults := m.scanString(v, fieldName)
			results = append(results, scanResults...)
		case map[string]interface{}:
			nestedResults, err := m.scanMap(v, fieldName)
			if err != nil {
				return nil, err
			}
			results = append(results, nestedResults...)
		case []interface{}:
			for i, item := range v {
				itemFieldName := fieldName + "[" + string(rune(i)) + "]"
				switch itemV := item.(type) {
				case string:
					scanResults := m.scanString(itemV, itemFieldName)
					results = append(results, scanResults...)
				case map[string]interface{}:
					nestedResults, err := m.scanMap(itemV, itemFieldName)
					if err != nil {
						return nil, err
					}
					results = append(results, nestedResults...)
				}
			}
		}
	}

	return results, nil
}

func (m *DataClassificationManager) scanString(data string, fieldName string) []ScanResult {
	var results []ScanResult

	m.mu.RLock()
	patterns := make([]*SensitivePattern, 0, len(m.patterns))
	for _, p := range m.patterns {
		patterns = append(patterns, p)
	}
	m.mu.RUnlock()

	for _, pattern := range patterns {
		matches := pattern.Regex.FindAllString(data, -1)
		if len(matches) > 0 {
			results = append(results, ScanResult{
				FieldName:   fieldName,
				PatternName: pattern.Name,
				Matches:     matches,
				Level:       pattern.Level,
				Severity:    pattern.Severity,
			})

			logger.Debug("Sensitive pattern matched",
				zap.String("field", fieldName),
				zap.String("pattern", pattern.Name),
				zap.Int("matches", len(matches)),
				zap.String("level", string(pattern.Level)),
			)
		}
	}

	return results
}

func (m *DataClassificationManager) Classify(scans []ScanResult) ClassificationLevel {
	if len(scans) == 0 {
		return LevelPublic
	}

	maxWeight := 0
	for _, scan := range scans {
		if weight, ok := m.levelWeights[scan.Level]; ok && weight > maxWeight {
			maxWeight = weight
		}
	}

	var level ClassificationLevel
	for l, w := range m.levelWeights {
		if w == maxWeight {
			level = l
			break
		}
	}

	logger.Debug("Data classified",
		zap.String("level", string(level)),
		zap.Int("scan_count", len(scans)),
	)

	return level
}

func (m *DataClassificationManager) ApplyPolicy(ctx context.Context, data interface{}, result ClassificationResult) (interface{}, error) {
	logger.Info("Applying policy",
		zap.String("data_id", result.DataID),
		zap.String("level", string(result.OverallLevel)),
	)

	m.mu.RLock()
	var targetPolicy *Policy
	for _, p := range m.policies {
		if p.Level == result.OverallLevel && p.Enabled {
			targetPolicy = p
			break
		}
	}
	m.mu.RUnlock()

	if targetPolicy == nil {
		logger.Warn("No policy found for level, using default",
			zap.String("level", string(result.OverallLevel)),
		)
		return data, nil
	}

	result.AppliedPolicy = targetPolicy.ID

	var processedData = data

	for _, action := range targetPolicy.Actions {
		switch action {
		case ActionEncrypt:
			encrypted, err := m.applyEncryption(ctx, processedData, result, targetPolicy)
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeEncryption, "apply encryption failed")
			}
			processedData = encrypted
			logger.Info("Encryption applied", zap.String("policy", targetPolicy.ID))

		case ActionMask:
			masked, err := m.applyMask(processedData, result, targetPolicy)
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeInternal, "apply mask failed")
			}
			processedData = masked
			logger.Info("Mask applied", zap.String("policy", targetPolicy.ID))

		case ActionBlock:
			blocked := m.applyBlock(processedData, result, targetPolicy)
			processedData = blocked
			logger.Info("Block applied", zap.String("policy", targetPolicy.ID))

		case ActionAudit:
			m.applyAudit(ctx, processedData, result, targetPolicy)
			logger.Info("Audit applied", zap.String("policy", targetPolicy.ID))

		case ActionAllow:
			logger.Debug("Allow action, no modification",
				zap.String("policy", targetPolicy.ID),
			)
		}
	}

	return processedData, nil
}

func (m *DataClassificationManager) applyEncryption(ctx context.Context, data interface{}, result ClassificationResult, policy *Policy) (interface{}, error) {
	if policy == nil || !policy.Enabled {
		return data, nil
	}

	mapData, ok := data.(map[string]interface{})
	if !ok {
		return data, nil
	}

	algorithm, _ := policy.Parameters["encryption_algorithm"].(string)
	logger.Debug("Using encryption algorithm", zap.String("algorithm", algorithm))

	aesKey := utils.GenerateAESKey()

	for _, scan := range result.Scans {
		parts := strings.Split(scan.FieldName, ".")
		if err := encryptField(mapData, parts, aesKey); err != nil {
			logger.Error("Failed to encrypt field",
				zap.String("field", scan.FieldName),
				zap.Error(err),
			)
			return nil, err
		}
	}

	return mapData, nil
}

func encryptField(data map[string]interface{}, path []string, key []byte) error {
	if len(path) == 0 {
		return nil
	}

	if len(path) == 1 {
		if val, ok := data[path[0]]; ok {
			if strVal, ok := val.(string); ok {
				encrypted, err := utils.AESEncrypt([]byte(strVal), key)
				if err != nil {
					return err
				}
				data[path[0]] = encrypted
			}
		}
		return nil
	}

	if nested, ok := data[path[0]].(map[string]interface{}); ok {
		return encryptField(nested, path[1:], key)
	}

	return nil
}

func (m *DataClassificationManager) applyMask(data interface{}, result ClassificationResult, policy *Policy) (interface{}, error) {
	mapData, ok := data.(map[string]interface{})
	if !ok {
		return data, nil
	}

	maskPatterns, _ := policy.Parameters["mask_fields"].([]string)
	maskSet := make(map[string]bool)
	for _, p := range maskPatterns {
		maskSet[p] = true
	}

	for _, scan := range result.Scans {
		if len(maskSet) > 0 && !maskSet[scan.PatternName] {
			continue
		}

		parts := strings.Split(scan.FieldName, ".")
		if err := maskField(mapData, parts, scan.PatternName); err != nil {
			logger.Error("Failed to mask field",
				zap.String("field", scan.FieldName),
				zap.Error(err),
			)
			return nil, err
		}
	}

	return mapData, nil
}

func maskField(data map[string]interface{}, path []string, patternName string) error {
	if len(path) == 0 {
		return nil
	}

	if len(path) == 1 {
		if val, ok := data[path[0]]; ok {
			if strVal, ok := val.(string); ok {
				data[path[0]] = maskValue(strVal, patternName)
			}
		}
		return nil
	}

	if nested, ok := data[path[0]].(map[string]interface{}); ok {
		return maskField(nested, path[1:], patternName)
	}

	return nil
}

func maskValue(value string, patternName string) string {
	if len(value) <= 4 {
		return strings.Repeat("*", len(value))
	}

	switch patternName {
	case "chinese_mobile":
		if len(value) == 11 {
			return value[:3] + "****" + value[7:]
		}
	case "email":
		if idx := strings.Index(value, "@"); idx > 0 {
			username := value[:idx]
			domain := value[idx:]
			if len(username) > 2 {
				return username[:2] + "****" + domain
			}
			return "****" + domain
		}
	case "chinese_id_card":
		if len(value) == 18 {
			return value[:6] + "********" + value[14:]
		}
	case "bank_card", "credit_card":
		if len(value) >= 16 {
			return value[:4] + "********" + value[len(value)-4:]
		}
	}

	prefixLen := len(value) / 4
	suffixLen := len(value) / 4
	return value[:prefixLen] + strings.Repeat("*", len(value)-prefixLen-suffixLen) + value[len(value)-suffixLen:]
}

func (m *DataClassificationManager) applyBlock(data interface{}, result ClassificationResult, policy *Policy) interface{} {
	mapData, ok := data.(map[string]interface{})
	if !ok {
		return map[string]interface{}{"blocked": true, "reason": "Data contains confidential information"}
	}

	for _, scan := range result.Scans {
		parts := strings.Split(scan.FieldName, ".")
		blockField(mapData, parts)
	}

	return mapData
}

func blockField(data map[string]interface{}, path []string) {
	if len(path) == 0 {
		return
	}

	if len(path) == 1 {
		if _, ok := data[path[0]]; ok {
			data[path[0]] = "[BLOCKED - CONFIDENTIAL]"
		}
		return
	}

	if nested, ok := data[path[0]].(map[string]interface{}); ok {
		blockField(nested, path[1:])
	}
}

func (m *DataClassificationManager) applyAudit(ctx context.Context, data interface{}, result ClassificationResult, policy *Policy) {
	auditLevel, _ := policy.Parameters["audit_level"].(string)

	logFields := []zap.Field{
		zap.String("data_id", result.DataID),
		zap.String("classification_level", string(result.OverallLevel)),
		zap.String("policy_id", policy.ID),
		zap.String("audit_level", auditLevel),
		zap.Int("scan_results", len(result.Scans)),
		zap.Int64("timestamp", result.Timestamp),
	}

	for _, scan := range result.Scans {
		logFields = append(logFields,
			zap.String("pattern_"+scan.PatternName, scan.FieldName),
			zap.Int(scan.PatternName+"_matches", len(scan.Matches)),
		)
	}

	switch auditLevel {
	case "critical":
		logger.Error("AUDIT - Critical data access", logFields...)
	case "high":
		logger.Warn("AUDIT - Sensitive data access", logFields...)
	case "medium":
		logger.Info("AUDIT - Internal data access", logFields...)
	default:
		logger.Debug("AUDIT - Public data access", logFields...)
	}
}

func (m *DataClassificationManager) Process(ctx context.Context, data interface{}) (*ClassificationResult, interface{}, error) {
	dataID := utils.GenerateID("data")
	logger.Info("Starting classification process", zap.String("data_id", dataID))

	scans, err := m.ScanData(ctx, data)
	if err != nil {
		return nil, nil, errors.Wrap(err, errors.ErrCodeInternal, "data scan failed")
	}

	level := m.Classify(scans)

	result := &ClassificationResult{
		DataID:       dataID,
		OverallLevel: level,
		Scans:        scans,
		Timestamp:    utils.GenerateTimestamp(),
	}

	processedData, err := m.ApplyPolicy(ctx, data, *result)
	if err != nil {
		return nil, nil, errors.Wrap(err, errors.ErrCodeInternal, "policy application failed")
	}

	logger.Info("Classification process completed",
		zap.String("data_id", dataID),
		zap.String("final_level", string(level)),
		zap.String("applied_policy", result.AppliedPolicy),
	)

	return result, processedData, nil
}

func (r *ClassificationResult) Summary() map[string]interface{} {
	levelCounts := make(map[ClassificationLevel]int)
	patternCounts := make(map[string]int)
	totalMatches := 0

	for _, scan := range r.Scans {
		levelCounts[scan.Level]++
		patternCounts[scan.PatternName] += len(scan.Matches)
		totalMatches += len(scan.Matches)
	}

	return map[string]interface{}{
		"data_id":        r.DataID,
		"overall_level":  string(r.OverallLevel),
		"scan_count":     len(r.Scans),
		"total_matches":  totalMatches,
		"applied_policy": r.AppliedPolicy,
		"timestamp":      r.Timestamp,
		"level_breakdown": levelCounts,
		"pattern_breakdown": patternCounts,
	}
}

func (l ClassificationLevel) String() string {
	return string(l)
}

func (m *DataClassificationManager) GetLevelPriority() []ClassificationLevel {
	levels := make([]ClassificationLevel, 0, len(m.levelWeights))
	for l := range m.levelWeights {
		levels = append(levels, l)
	}

	sort.Slice(levels, func(i, j int) bool {
		return m.levelWeights[levels[i]] < m.levelWeights[levels[j]]
	})

	return levels
}

func init() {
	_ = GetManager()
}
