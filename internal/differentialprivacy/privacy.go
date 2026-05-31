package differentialprivacy

import (
	"crypto/rand"
	"fmt"
	"math"
	"math/big"
	"sync"
	"time"

	"go.uber.org/zap"

	"session316/internal/logger"
	"session316/internal/models"
	"session316/pkg/errors"
	"session316/pkg/utils"
)

type PrivacyMechanism string

const (
	MechanismLaplace  PrivacyMechanism = "laplace"
	MechanismGaussian PrivacyMechanism = "gaussian"
	MechanismExponential PrivacyMechanism = "exponential"
)

type BudgetAccount struct {
	AccountID   string    `json:"account_id"`
	EntityID    string    `json:"entity_id"`
	Epsilon     float64   `json:"epsilon"`
	Delta       float64   `json:"delta"`
	UsedEpsilon float64   `json:"used_epsilon"`
	UsedDelta   float64   `json:"used_delta"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
	Status      string    `json:"status"`
}

type PrivacyConfig struct {
	DefaultEpsilon float64
	DefaultDelta   float64
	MaxEpsilon     float64
	MaxDelta       float64
	Sensitivity    float64
	Mechanism      PrivacyMechanism
	Enabled        bool
}

type NoiseParameters struct {
	Epsilon     float64
	Delta       float64
	Sensitivity float64
	Mechanism   PrivacyMechanism
}

type PrivacyManager struct {
	mu           sync.RWMutex
	accounts     map[string]*BudgetAccount
	config       *PrivacyConfig
	entityID     string
	enabled      bool
}

var (
	globalManager *PrivacyManager
	managerOnce   sync.Once
)

func NewPrivacyManager(config *PrivacyConfig, entityID string) *PrivacyManager {
	if config == nil {
		config = &PrivacyConfig{
			DefaultEpsilon: 1.0,
			DefaultDelta:   1e-5,
			MaxEpsilon:     10.0,
			MaxDelta:       1e-3,
			Sensitivity:    1.0,
			Mechanism:      MechanismLaplace,
			Enabled:        true,
		}
	}

	pm := &PrivacyManager{
		accounts: make(map[string]*BudgetAccount),
		config:   config,
		entityID: entityID,
		enabled:  config.Enabled,
	}

	logger.Info("PrivacyManager initialized",
		zap.String("entity_id", entityID),
		zap.Float64("default_epsilon", config.DefaultEpsilon),
		zap.Float64("default_delta", config.DefaultDelta),
		zap.String("mechanism", string(config.Mechanism)),
		zap.Bool("enabled", config.Enabled),
	)

	return pm
}

func GetGlobalManager(config *PrivacyConfig) *PrivacyManager {
	managerOnce.Do(func() {
		globalManager = NewPrivacyManager(config, utils.GenerateEntityID())
	})
	return globalManager
}

func (pm *PrivacyManager) CreateAccount(entityID string, epsilon, delta float64) (*BudgetAccount, error) {
	if epsilon <= 0 || delta <= 0 {
		return nil, errors.ValidationError("epsilon/delta", "必须大于0")
	}
	if epsilon > pm.config.MaxEpsilon {
		return nil, errors.ValidationError("epsilon",
			fmt.Sprintf("不能超过最大值 %.4f", pm.config.MaxEpsilon))
	}
	if delta > pm.config.MaxDelta {
		return nil, errors.ValidationError("delta",
			fmt.Sprintf("不能超过最大值 %.6f", pm.config.MaxDelta))
	}

	pm.mu.Lock()
	defer pm.mu.Unlock()

	accountID := utils.GenerateID("acc")
	now := time.Now()

	account := &BudgetAccount{
		AccountID:   accountID,
		EntityID:    entityID,
		Epsilon:     epsilon,
		Delta:       delta,
		UsedEpsilon: 0,
		UsedDelta:   0,
		CreatedAt:   now,
		UpdatedAt:   now,
		Status:      models.StatusActive,
	}

	pm.accounts[accountID] = account
	pm.accounts[entityID] = account

	logger.Info("Privacy budget account created",
		zap.String("account_id", accountID),
		zap.String("entity_id", entityID),
		zap.Float64("epsilon", epsilon),
		zap.Float64("delta", delta),
	)

	return account, nil
}

func (pm *PrivacyManager) GetAccount(accountID string) (*BudgetAccount, error) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	account, exists := pm.accounts[accountID]
	if !exists {
		return nil, errors.NotFoundError("隐私预算账户", accountID)
	}
	return account, nil
}

func (pm *PrivacyManager) GetAccountByEntity(entityID string) (*BudgetAccount, error) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	account, exists := pm.accounts[entityID]
	if !exists {
		return nil, errors.NotFoundError("实体隐私账户", entityID)
	}
	return account, nil
}

func (pm *PrivacyManager) CheckBudget(accountID string, epsilon, delta float64) (bool, error) {
	if !pm.enabled {
		return true, nil
	}

	account, err := pm.GetAccount(accountID)
	if err != nil {
		return false, err
	}

	if account.Status != models.StatusActive {
		return false, errors.NewWithDetails(
			errors.ErrCodePrivacyBudget,
			"账户状态异常",
			fmt.Sprintf("账户 %s 当前状态: %s", accountID, account.Status),
		)
	}

	remainingEpsilon := account.Epsilon - account.UsedEpsilon
	remainingDelta := account.Delta - account.UsedDelta

	if remainingEpsilon < epsilon {
		logger.Warn("隐私预算不足 (epsilon)",
			zap.String("account_id", accountID),
			zap.Float64("requested", epsilon),
			zap.Float64("remaining", remainingEpsilon),
		)
		return false, nil
	}

	if remainingDelta < delta {
		logger.Warn("隐私预算不足 (delta)",
			zap.String("account_id", accountID),
			zap.Float64("requested", delta),
			zap.Float64("remaining", remainingDelta),
		)
		return false, nil
	}

	return true, nil
}

func (pm *PrivacyManager) ConsumeBudget(accountID string, epsilon, delta float64) error {
	if !pm.enabled {
		return nil
	}

	ok, err := pm.CheckBudget(accountID, epsilon, delta)
	if err != nil {
		return err
	}
	if !ok {
		return errors.NewWithDetails(
			errors.ErrCodePrivacyBudget,
			"隐私预算不足",
			fmt.Sprintf("账户 %s 预算不足，请求: epsilon=%.4f, delta=%.6f", accountID, epsilon, delta),
		)
	}

	pm.mu.Lock()
	defer pm.mu.Unlock()

	account := pm.accounts[accountID]
	account.UsedEpsilon += epsilon
	account.UsedDelta += delta
	account.UpdatedAt = time.Now()

	logger.Info("隐私预算已消耗",
		zap.String("account_id", accountID),
		zap.Float64("consumed_epsilon", epsilon),
		zap.Float64("consumed_delta", delta),
		zap.Float64("remaining_epsilon", account.Epsilon-account.UsedEpsilon),
		zap.Float64("remaining_delta", account.Delta-account.UsedDelta),
	)

	if account.UsedEpsilon >= account.Epsilon || account.UsedDelta >= account.Delta {
		account.Status = models.StatusInactive
		logger.Warn("隐私预算已耗尽，账户已停用",
			zap.String("account_id", accountID),
		)
	}

	return nil
}

func (pm *PrivacyManager) ResetBudget(accountID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	account, exists := pm.accounts[accountID]
	if !exists {
		return errors.NotFoundError("隐私预算账户", accountID)
	}

	account.UsedEpsilon = 0
	account.UsedDelta = 0
	account.Status = models.StatusActive
	account.UpdatedAt = time.Now()

	logger.Info("隐私预算已重置",
		zap.String("account_id", accountID),
	)

	return nil
}

func (pm *PrivacyManager) UpdateBudget(accountID string, newEpsilon, newDelta float64) error {
	if newEpsilon <= 0 || newDelta <= 0 {
		return errors.ValidationError("epsilon/delta", "必须大于0")
	}

	pm.mu.Lock()
	defer pm.mu.Unlock()

	account, exists := pm.accounts[accountID]
	if !exists {
		return errors.NotFoundError("隐私预算账户", accountID)
	}

	account.Epsilon = newEpsilon
	account.Delta = newDelta
	account.UpdatedAt = time.Now()

	if account.UsedEpsilon < account.Epsilon && account.UsedDelta < account.Delta {
		account.Status = models.StatusActive
	}

	logger.Info("隐私预算已更新",
		zap.String("account_id", accountID),
		zap.Float64("new_epsilon", newEpsilon),
		zap.Float64("new_delta", newDelta),
	)

	return nil
}

func (pm *PrivacyManager) ListAccounts() []*BudgetAccount {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	accounts := make([]*BudgetAccount, 0, len(pm.accounts))
	seen := make(map[string]bool)

	for _, account := range pm.accounts {
		if !seen[account.AccountID] {
			seen[account.AccountID] = true
			accounts = append(accounts, account)
		}
	}

	return accounts
}

func sampleUniform() (float64, error) {
	max := big.NewInt(1 << 53)
	n, err := rand.Int(rand.Reader, max)
	if err != nil {
		return 0, err
	}
	return float64(n.Int64()) / float64(1<<53), nil
}

func sampleStandardNormal() (float64, error) {
	u1, err := sampleUniform()
	if err != nil {
		return 0, err
	}
	u2, err := sampleUniform()
	if err != nil {
		return 0, err
	}

	return math.Sqrt(-2*math.Log(u1)) * math.Cos(2*math.Pi*u2), nil
}

func AddLaplaceNoise(value, sensitivity, epsilon float64) (float64, error) {
	if epsilon <= 0 {
		return 0, errors.ValidationError("epsilon", "必须大于0")
	}
	if sensitivity <= 0 {
		return 0, errors.ValidationError("sensitivity", "必须大于0")
	}

	scale := sensitivity / epsilon

	u, err := sampleUniform()
	if err != nil {
		return 0, errors.Wrap(err, errors.ErrCodeInternal, "采样均匀分布失败")
	}

	u = u - 0.5

	var noise float64
	if u < 0 {
		noise = scale * math.Log(1+2*u)
	} else {
		noise = -scale * math.Log(1-2*u)
	}

	result := value + noise

	logger.Debug("Laplace噪声已添加",
		zap.Float64("original_value", value),
		zap.Float64("noise", noise),
		zap.Float64("result", result),
		zap.Float64("scale", scale),
	)

	return result, nil
}

func AddGaussianNoise(value, sensitivity, epsilon, delta float64) (float64, error) {
	if epsilon <= 0 || delta <= 0 {
		return 0, errors.ValidationError("epsilon/delta", "必须大于0")
	}
	if sensitivity <= 0 {
		return 0, errors.ValidationError("sensitivity", "必须大于0")
	}
	if epsilon >= 1 {
		return 0, errors.ValidationError("epsilon", "Gaussian机制要求epsilon < 1")
	}

	sigma := sensitivity * math.Sqrt(2*math.Log(1.25/delta)) / epsilon

	z, err := sampleStandardNormal()
	if err != nil {
		return 0, errors.Wrap(err, errors.ErrCodeInternal, "采样正态分布失败")
	}

	noise := sigma * z
	result := value + noise

	logger.Debug("Gaussian噪声已添加",
		zap.Float64("original_value", value),
		zap.Float64("noise", noise),
		zap.Float64("result", result),
		zap.Float64("sigma", sigma),
	)

	return result, nil
}

func AddExponentialNoise(value, sensitivity, epsilon float64) (float64, error) {
	if epsilon <= 0 {
		return 0, errors.ValidationError("epsilon", "必须大于0")
	}
	if sensitivity <= 0 {
		return 0, errors.ValidationError("sensitivity", "必须大于0")
	}

	lambda := epsilon / (2 * sensitivity)

	u, err := sampleUniform()
	if err != nil {
		return 0, errors.Wrap(err, errors.ErrCodeInternal, "采样均匀分布失败")
	}

	noise := -math.Log(u) / lambda

	sign, err := sampleUniform()
	if err != nil {
		return 0, errors.Wrap(err, errors.ErrCodeInternal, "采样符号失败")
	}
	if sign < 0.5 {
		noise = -noise
	}

	result := value + noise

	logger.Debug("Exponential噪声已添加",
		zap.Float64("original_value", value),
		zap.Float64("noise", noise),
		zap.Float64("result", result),
		zap.Float64("lambda", lambda),
	)

	return result, nil
}

func AddNoise(value float64, params *NoiseParameters) (float64, error) {
	if params == nil {
		return 0, errors.ValidationError("params", "噪声参数不能为空")
	}

	if params.Sensitivity <= 0 {
		params.Sensitivity = 1.0
	}

	switch params.Mechanism {
	case MechanismLaplace:
		return AddLaplaceNoise(value, params.Sensitivity, params.Epsilon)
	case MechanismGaussian:
		return AddGaussianNoise(value, params.Sensitivity, params.Epsilon, params.Delta)
	case MechanismExponential:
		return AddExponentialNoise(value, params.Sensitivity, params.Epsilon)
	default:
		return 0, errors.ValidationError("mechanism",
			fmt.Sprintf("不支持的隐私机制: %s", params.Mechanism))
	}
}

func (pm *PrivacyManager) AddNoiseWithBudget(accountID string, value float64, params *NoiseParameters) (float64, error) {
	if params == nil {
		params = &NoiseParameters{
			Epsilon:     pm.config.DefaultEpsilon,
			Delta:       pm.config.DefaultDelta,
			Sensitivity: pm.config.Sensitivity,
			Mechanism:   pm.config.Mechanism,
		}
	}

	if err := pm.ConsumeBudget(accountID, params.Epsilon, params.Delta); err != nil {
		return 0, err
	}

	result, err := AddNoise(value, params)
	if err != nil {
		return 0, err
	}

	return result, nil
}

func (pm *PrivacyManager) AddNoiseToFloat64Slice(accountID string, values []float64, params *NoiseParameters) ([]float64, error) {
	if len(values) == 0 {
		return values, nil
	}

	if params == nil {
		params = &NoiseParameters{
			Epsilon:     pm.config.DefaultEpsilon,
			Delta:       pm.config.DefaultDelta,
			Sensitivity: pm.config.Sensitivity,
			Mechanism:   pm.config.Mechanism,
		}
	}

	totalEpsilon := params.Epsilon * float64(len(values))
	totalDelta := params.Delta * float64(len(values))

	if err := pm.ConsumeBudget(accountID, totalEpsilon, totalDelta); err != nil {
		return nil, err
	}

	result := make([]float64, len(values))
	for i, v := range values {
		noisy, err := AddNoise(v, params)
		if err != nil {
			return nil, err
		}
		result[i] = noisy
	}

	logger.Info("已向切片添加隐私噪声",
		zap.String("account_id", accountID),
		zap.Int("count", len(values)),
		zap.String("mechanism", string(params.Mechanism)),
	)

	return result, nil
}

func (pm *PrivacyManager) AddNoiseToMetrics(accountID string, metrics *models.Metrics, params *NoiseParameters) (*models.Metrics, error) {
	if metrics == nil {
		return nil, errors.ValidationError("metrics", "指标数据不能为空")
	}

	if params == nil {
		params = &NoiseParameters{
			Epsilon:     pm.config.DefaultEpsilon / 3,
			Delta:       pm.config.DefaultDelta / 3,
			Sensitivity: pm.config.Sensitivity,
			Mechanism:   pm.config.Mechanism,
		}
	}

	totalEpsilon := params.Epsilon * 3
	totalDelta := params.Delta * 3

	if err := pm.ConsumeBudget(accountID, totalEpsilon, totalDelta); err != nil {
		return nil, err
	}

	throughput, err := AddNoise(float64(metrics.Throughput), params)
	if err != nil {
		return nil, err
	}

	latency, err := AddNoise(float64(metrics.LatencyP99), params)
	if err != nil {
		return nil, err
	}

	errorRate, err := AddNoise(metrics.ErrorRate, params)
	if err != nil {
		return nil, err
	}

	result := &models.Metrics{
		Throughput: int(math.Round(math.Max(0, throughput))),
		LatencyP99: int(math.Round(math.Max(0, latency))),
		ErrorRate:  math.Max(0, math.Min(1, errorRate)),
	}

	logger.Info("已向Metrics添加隐私噪声",
		zap.String("account_id", accountID),
		zap.Int("original_throughput", metrics.Throughput),
		zap.Int("noisy_throughput", result.Throughput),
	)

	return result, nil
}

func (pm *PrivacyManager) GetBudgetUsage(accountID string) (float64, float64, error) {
	account, err := pm.GetAccount(accountID)
	if err != nil {
		return 0, 0, err
	}

	epsilonUsage := account.UsedEpsilon / account.Epsilon
	deltaUsage := account.UsedDelta / account.Delta

	return epsilonUsage, deltaUsage, nil
}

func (pm *PrivacyManager) DisableAccount(accountID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	account, exists := pm.accounts[accountID]
	if !exists {
		return errors.NotFoundError("隐私预算账户", accountID)
	}

	account.Status = models.StatusInactive
	account.UpdatedAt = time.Now()

	logger.Info("隐私预算账户已停用",
		zap.String("account_id", accountID),
	)

	return nil
}

func (pm *PrivacyManager) EnableAccount(accountID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	account, exists := pm.accounts[accountID]
	if !exists {
		return errors.NotFoundError("隐私预算账户", accountID)
	}

	if account.UsedEpsilon >= account.Epsilon || account.UsedDelta >= account.Delta {
		return errors.NewWithDetails(
			errors.ErrCodePrivacyBudget,
			"无法启用账户",
			"隐私预算已耗尽，请先重置预算",
		)
	}

	account.Status = models.StatusActive
	account.UpdatedAt = time.Now()

	logger.Info("隐私预算账户已启用",
		zap.String("account_id", accountID),
	)

	return nil
}

func (pm *PrivacyManager) DeleteAccount(accountID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	account, exists := pm.accounts[accountID]
	if !exists {
		return errors.NotFoundError("隐私预算账户", accountID)
	}

	delete(pm.accounts, accountID)
	delete(pm.accounts, account.EntityID)

	logger.Info("隐私预算账户已删除",
		zap.String("account_id", accountID),
		zap.String("entity_id", account.EntityID),
	)

	return nil
}

func (pm *PrivacyManager) GetConfig() *PrivacyConfig {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	return &PrivacyConfig{
		DefaultEpsilon: pm.config.DefaultEpsilon,
		DefaultDelta:   pm.config.DefaultDelta,
		MaxEpsilon:     pm.config.MaxEpsilon,
		MaxDelta:       pm.config.MaxDelta,
		Sensitivity:    pm.config.Sensitivity,
		Mechanism:      pm.config.Mechanism,
		Enabled:        pm.config.Enabled,
	}
}

func (pm *PrivacyManager) UpdateConfig(newConfig *PrivacyConfig) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	pm.config = newConfig
	pm.enabled = newConfig.Enabled

	logger.Info("隐私管理器配置已更新",
		zap.Bool("enabled", newConfig.Enabled),
		zap.String("mechanism", string(newConfig.Mechanism)),
	)
}

func (pm *PrivacyManager) IsEnabled() bool {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	return pm.enabled
}

func (pm *PrivacyManager) SetEnabled(enabled bool) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	pm.enabled = enabled
	pm.config.Enabled = enabled

	logger.Info("隐私管理器状态已更新",
		zap.Bool("enabled", enabled),
	)
}

func CalibrateNoise(mechanism PrivacyMechanism, sensitivity, epsilon, delta float64) (float64, error) {
	switch mechanism {
	case MechanismLaplace:
		return sensitivity / epsilon, nil
	case MechanismGaussian:
		if epsilon >= 1 {
			return 0, errors.ValidationError("epsilon", "Gaussian机制要求epsilon < 1")
		}
		return sensitivity * math.Sqrt(2*math.Log(1.25/delta)) / epsilon, nil
	case MechanismExponential:
		return 2 * sensitivity / epsilon, nil
	default:
		return 0, errors.ValidationError("mechanism",
			fmt.Sprintf("不支持的隐私机制: %s", mechanism))
	}
}

func ComputeAdvancedComposition(k int, epsilon, delta float64) (float64, float64) {
	composedEpsilon := epsilon * math.Sqrt(2*float64(k)*math.Log(1/delta))
	composedDelta := delta * float64(k)
	return composedEpsilon, composedDelta
}

func ComputeBasicComposition(k int, epsilon, delta float64) (float64, float64) {
	return epsilon * float64(k), delta * float64(k)
}

func (a *BudgetAccount) ToEntity() *models.Entity {
	return &models.Entity{
		ID:     a.AccountID,
		Type:   "privacy_account",
		Status: a.Status,
		Attributes: map[string]interface{}{
			"entity_id":     a.EntityID,
			"epsilon":       a.Epsilon,
			"delta":         a.Delta,
			"used_epsilon":  a.UsedEpsilon,
			"used_delta":    a.UsedDelta,
			"remaining_epsilon": a.Epsilon - a.UsedEpsilon,
			"remaining_delta":   a.Delta - a.UsedDelta,
		},
		CreatedAt: a.CreatedAt,
		UpdatedAt: a.UpdatedAt,
	}
}
