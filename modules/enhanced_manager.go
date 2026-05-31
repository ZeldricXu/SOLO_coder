package modules

import (
	"context"
	"depguard/dynamicconfig"
	"depguard/modules/docindex"
	"depguard/modules/featureflags"
	"depguard/modules/qualitygate"
	"depguard/logger"
	"go.uber.org/zap"
	"sync"
	"time"
)

type EnhancedModulesManager struct {
	DocIndex    *docindex.EnhancedService
	QualityGate *qualitygate.EnhancedService
	FeatureFlags *featureflags.EnhancedService
	configManager *dynamicconfig.Manager
	initialized  bool
	mu           sync.RWMutex
}

var (
	enhancedManagerInstance *EnhancedModulesManager
	enhancedManagerOnce     sync.Once
)

func GetEnhancedManager() *EnhancedModulesManager {
	enhancedManagerOnce.Do(func() {
		enhancedManagerInstance = &EnhancedModulesManager{
			configManager: dynamicconfig.GetManager(),
			initialized:   false,
		}
		enhancedManagerInstance.initialize()
	})
	return enhancedManagerInstance
}

func (m *EnhancedModulesManager) initialize() {
	m.DocIndex = docindex.NewEnhancedService()
	m.QualityGate = qualitygate.NewEnhancedService()
	m.FeatureFlags = featureflags.NewEnhancedService()
	m.initialized = true

	logger.Get().Info("EnhancedModulesManager initialized",
		zap.Bool("doc_index_enabled", m.DocIndex != nil),
		zap.Bool("quality_gate_enabled", m.QualityGate != nil),
		zap.Bool("feature_flags_enabled", m.FeatureFlags != nil))
}

func (m *EnhancedModulesManager) Shutdown() {
	if !m.initialized {
		return
	}

	if m.FeatureFlags != nil && m.FeatureFlags.IsStarted() {
		m.FeatureFlags.Stop()
	}

	if m.DocIndex != nil {
		m.DocIndex.Stop()
	}

	m.initialized = false
	logger.Get().Info("EnhancedModulesManager shutdown completed")
}

func (m *EnhancedModulesManager) GetDocIndexStatus() map[string]interface{} {
	if m.DocIndex == nil {
		return nil
	}

	status := map[string]interface{}{
		"initialized": m.DocIndex.IsInitialized(),
		"current_scenario": string(m.DocIndex.GetCurrentScenario()),
	}

	if config, ok := m.DocIndex.GetCurrentConfig(); ok {
		status["search_strategy"] = string(config.SearchStrategy)
		status["index_mode"] = string(config.IndexMode)
		status["index_batch_size"] = config.IndexBatchSize
		status["realtime_indexing"] = config.EnableRealtimeIndexing
		status["fuzzy_search_enabled"] = config.EnableFuzzySearch
	}

	return status
}

func (m *EnhancedModulesManager) GetQualityGateStatus() map[string]interface{} {
	if m.QualityGate == nil {
		return nil
	}

	status := map[string]interface{}{
		"initialized": m.QualityGate.IsInitialized(),
		"strategies":  m.QualityGate.GetCurrentStrategies(),
	}

	analysisCount := 0
	if strategies := m.QualityGate.GetAnalysisStrategies(); strategies != nil {
		analysisCount = len(strategies)
	}
	status["analysis_strategies_count"] = analysisCount

	qualityCount := 0
	if strategies := m.QualityGate.GetQualityStrategies(); strategies != nil {
		qualityCount = len(strategies)
	}
	status["quality_strategies_count"] = qualityCount

	gateCount := 0
	if strategies := m.QualityGate.GetGateStrategies(); strategies != nil {
		gateCount = len(strategies)
	}
	status["gate_strategies_count"] = gateCount

	return status
}

func (m *EnhancedModulesManager) GetFeatureFlagsStatus() map[string]interface{} {
	if m.FeatureFlags == nil {
		return nil
	}

	return map[string]interface{}{
		"started": m.FeatureFlags.IsStarted(),
	}
}

func (m *EnhancedModulesManager) GetAllStatus() map[string]interface{} {
	return map[string]interface{}{
		"doc_index":    m.GetDocIndexStatus(),
		"quality_gate": m.GetQualityGateStatus(),
		"feature_flags": m.GetFeatureFlagsStatus(),
		"config_manager": map[string]interface{}{
			"scenario": string(m.configManager.GetCurrentScenario()),
		},
	}
}

func (m *EnhancedModulesManager) SwitchScenario(ctx context.Context, scenario dynamicconfig.ConfigScenario) error {
	return m.configManager.SwitchScenario(ctx, scenario)
}

func (m *EnhancedModulesManager) HotUpdateConfig(ctx context.Context, updates []*dynamicconfig.DynamicConfig) error {
	return m.configManager.HotUpdate(ctx, updates)
}

func (m *EnhancedModulesManager) SwitchQualityAnalysisStrategy(name string) error {
	if m.QualityGate == nil {
		return nil
	}
	return m.QualityGate.SwitchAnalysisStrategy(name)
}

func (m *EnhancedModulesManager) SwitchQualityStrategy(name string) error {
	if m.QualityGate == nil {
		return nil
	}
	return m.QualityGate.SwitchQualityStrategy(name)
}

func (m *EnhancedModulesManager) SwitchGateStrategy(name string) error {
	if m.QualityGate == nil {
		return nil
	}
	return m.QualityGate.SwitchGateStrategy(name)
}

func (m *EnhancedModulesManager) RegisterQualityAnalysisStrategy(strategy qualitygate.AnalysisStrategy) error {
	if m.QualityGate == nil {
		return nil
	}
	return m.QualityGate.RegisterAnalysisStrategy(strategy)
}

func (m *EnhancedModulesManager) GetConfigManager() *dynamicconfig.Manager {
	return m.configManager
}

func (m *EnhancedModulesManager) IsInitialized() bool {
	return m.initialized
}

type FeatureFlagAsyncResult struct {
	OperationID string
	Status      string
	Error       error
	Result      interface{}
}

func (m *EnhancedModulesManager) CreateFeatureFlagAsync(ctx context.Context, flag *featureflags.FeatureFlag, callback func(*featureflags.AsyncOperation)) (string, error) {
	if m.FeatureFlags == nil {
		return "", nil
	}
	return m.FeatureFlags.CreateFlagAsync(ctx, flag, callback)
}

func (m *EnhancedModulesManager) WaitForFeatureFlagOperation(ctx context.Context, opID string, timeout time.Duration) (*FeatureFlagAsyncResult, error) {
	if m.FeatureFlags == nil {
		return nil, nil
	}

	op, err := m.FeatureFlags.WaitForOperation(ctx, opID, timeout)
	if err != nil {
		return nil, err
	}

	return &FeatureFlagAsyncResult{
		OperationID: op.ID,
		Status:      op.Status,
		Error:       op.Error,
		Result:      op.Result,
	}, nil
}

func (m *EnhancedModulesManager) GetFeatureFlagOperation(opID string) (*FeatureFlagAsyncResult, bool) {
	if m.FeatureFlags == nil {
		return nil, false
	}

	op, exists := m.FeatureFlags.GetOperation(opID)
	if !exists {
		return nil, false
	}

	return &FeatureFlagAsyncResult{
		OperationID: op.ID,
		Status:      op.Status,
		Error:       op.Error,
		Result:      op.Result,
	}, true
}

func (m *EnhancedModulesManager) RegisterFeatureFlagCallback(opType featureflags.AsyncOperationType, callback func(*featureflags.AsyncOperation)) {
	if m.FeatureFlags == nil {
		return
	}
	m.FeatureFlags.RegisterCallback(opType, callback)
}
