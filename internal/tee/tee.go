package tee

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"sync"
	"time"

	"go.uber.org/zap"

	"session316/internal/logger"
	"session316/internal/models"
	"session316/pkg/errors"
	"session316/pkg/utils"
)

const (
	EnclaveTypeSGX    = "sgx"
	EnclaveTypeSEV    = "sev"
	EnclaveTypeTrustZone = "trustzone"

	EnclaveStatusCreated    = "created"
	EnclaveStatusRunning    = "running"
	EnclaveStatusPaused     = "paused"
	EnclaveStatusDestroyed  = "destroyed"
	EnclaveStatusError      = "error"

	TokenTypeAccess  = "access"
	TokenTypeRefresh = "refresh"

	defaultTokenTTL      = 15 * time.Minute
	defaultEnclaveTTL    = 24 * time.Hour
	maxEnclaves          = 100
)

type EnclaveConfig struct {
	Type        string                 `json:"type"`
	MemorySize  int64                  `json:"memory_size"`
	ThreadCount int                    `json:"thread_count"`
	ImagePath   string                 `json:"image_path"`
	Parameters  map[string]interface{} `json:"parameters"`
	Labels      map[string]string      `json:"labels"`
	TTL         time.Duration          `json:"ttl"`
}

type EnclaveInfo struct {
	ID          string                 `json:"id"`
	Type        string                 `json:"type"`
	Status      string                 `json:"status"`
	MemorySize  int64                  `json:"memory_size"`
	ThreadCount int                    `json:"thread_count"`
	Labels      map[string]string      `json:"labels"`
	CreatedAt   time.Time              `json:"created_at"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	ExpiresAt   time.Time              `json:"expires_at"`
	Attributes  map[string]interface{} `json:"attributes,omitempty"`
}

type AttestationRequest struct {
	EnclaveID   string            `json:"enclave_id"`
	Nonce       string            `json:"nonce"`
	UserData    []byte            `json:"user_data,omitempty"`
	Challenge   []byte            `json:"challenge,omitempty"`
}

type AttestationReport struct {
	EnclaveID    string    `json:"enclave_id"`
	Timestamp    time.Time `json:"timestamp"`
	Nonce        string    `json:"nonce"`
	Measurement  string    `json:"measurement"`
	Signature    string    `json:"signature"`
	PublicKey    string    `json:"public_key"`
	UserData     string    `json:"user_data,omitempty"`
	Quote        string    `json:"quote,omitempty"`
	Status       string    `json:"status"`
}

type AuthToken struct {
	Token       string    `json:"token"`
	Type        string    `json:"type"`
	EnclaveID   string    `json:"enclave_id"`
	IssuedAt    time.Time `json:"issued_at"`
	ExpiresAt   time.Time `json:"expires_at"`
	Signature   string    `json:"signature"`
}

type SecureExecutionRequest struct {
	EnclaveID   string                 `json:"enclave_id"`
	Function    string                 `json:"function"`
	Arguments   map[string]interface{} `json:"arguments"`
	Encrypted   bool                   `json:"encrypted"`
	Token       string                 `json:"token"`
}

type SecureExecutionResult struct {
	RequestID   string                 `json:"request_id"`
	EnclaveID   string                 `json:"enclave_id"`
	Status      string                 `json:"status"`
	Result      interface{}            `json:"result,omitempty"`
	Error       string                 `json:"error,omitempty"`
	Signature   string                 `json:"signature,omitempty"`
	ExecutedAt  time.Time              `json:"executed_at"`
	DurationMs  int64                  `json:"duration_ms"`
}

type Enclave struct {
	ID          string
	Config      EnclaveConfig
	Status      string
	CreatedAt   time.Time
	StartedAt   *time.Time
	ExpiresAt   time.Time
	PrivateKey  *rsa.PrivateKey
	PublicKey   *rsa.PublicKey
	Measurement []byte
	mu          sync.RWMutex
}

type EnclaveManager struct {
	enclaves     map[string]*Enclave
	tokens       map[string]*AuthToken
	mu           sync.RWMutex
	rootKey      *rsa.PrivateKey
	cleanupCtx   context.Context
	cleanupCancel context.CancelFunc
	cleanupStarted bool
}

var (
	instance *EnclaveManager
	once     sync.Once
)

func NewEnclaveManager() (*EnclaveManager, error) {
	var err error
	once.Do(func() {
		var rootPriv *rsa.PrivateKey
		var rootPub *rsa.PublicKey
		rootPriv, rootPub, err = utils.GenerateRSAKeyPair()
		if err != nil {
			return
		}

		cleanupCtx, cleanupCancel := context.WithCancel(context.Background())

		instance = &EnclaveManager{
			enclaves:      make(map[string]*Enclave),
			tokens:        make(map[string]*AuthToken),
			rootKey:       rootPriv,
			cleanupCtx:    cleanupCtx,
			cleanupCancel: cleanupCancel,
			cleanupStarted: false,
		}

		logger.Info("TEE EnclaveManager initialized",
			zap.String("root_key_fingerprint", utils.HashSHA256([]byte(fmt.Sprintf("%x", rootPub.N)))),
		)
	})
	return instance, err
}

func (m *EnclaveManager) StartAutoCleanup(interval time.Duration) {
	m.mu.Lock()
	if m.cleanupStarted {
		m.mu.Unlock()
		logger.Info("TEE auto cleanup already started")
		return
	}
	m.cleanupStarted = true
	m.mu.Unlock()

	if interval <= 0 {
		interval = time.Hour
	}

	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		logger.Info("TEE auto cleanup job started", zap.Duration("interval", interval))

		for {
			select {
			case <-m.cleanupCtx.Done():
				logger.Info("TEE auto cleanup job stopped")
				return
			case <-ticker.C:
				m.CleanupExpired()
			}
		}
	}()
}

func (m *EnclaveManager) Shutdown() {
	m.cleanupCancel()
}

func GetEnclaveManager() *EnclaveManager {
	if instance == nil {
		_, _ = NewEnclaveManager()
	}
	return instance
}

func (m *EnclaveManager) CreateEnclave(ctx context.Context, config *EnclaveConfig) (*EnclaveInfo, error) {
	if config == nil {
		return nil, errors.ValidationError("config", "配置不能为空")
	}

	if config.Type != EnclaveTypeSGX && config.Type != EnclaveTypeSEV && config.Type != EnclaveTypeTrustZone {
		return nil, errors.ValidationError("type", fmt.Sprintf("不支持的enclave类型: %s", config.Type))
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if len(m.enclaves) >= maxEnclaves {
		return nil, errors.NewWithDetails(
			errors.ErrCodeResourceExhausted,
			"enclave数量已达上限",
			fmt.Sprintf("当前enclave数量: %d, 最大限制: %d", len(m.enclaves), maxEnclaves),
		)
	}

	enclaveID := utils.GenerateID("enc")
	privKey, pubKey, err := utils.GenerateRSAKeyPair()
	if err != nil {
		logger.Error("Failed to generate enclave key pair",
			zap.String("enclave_id", enclaveID),
			zap.Error(err),
		)
		return nil, errors.Wrap(err, errors.ErrCodeEncryption, "生成enclave密钥对失败")
	}

	measurement := utils.HashSHA256([]byte(enclaveID + config.ImagePath + time.Now().String()))
	measurementBytes, _ := base64.StdEncoding.DecodeString(measurement)

	ttl := config.TTL
	if ttl <= 0 {
		ttl = defaultEnclaveTTL
	}

	enclave := &Enclave{
		ID:          enclaveID,
		Config:      *config,
		Status:      EnclaveStatusCreated,
		CreatedAt:   time.Now(),
		ExpiresAt:   time.Now().Add(ttl),
		PrivateKey:  privKey,
		PublicKey:   pubKey,
		Measurement: measurementBytes,
	}

	m.enclaves[enclaveID] = enclave

	if !m.cleanupStarted {
		go m.StartAutoCleanup(30 * time.Minute)
	}

	logger.Info("Enclave created",
		zap.String("enclave_id", enclaveID),
		zap.String("type", config.Type),
		zap.Int64("memory_size", config.MemorySize),
		zap.Time("expires_at", enclave.ExpiresAt),
	)

	return m.enclaveToInfo(enclave), nil
}

func (m *EnclaveManager) DestroyEnclave(ctx context.Context, enclaveID string) error {
	if enclaveID == "" {
		return errors.ValidationError("enclave_id", "enclave ID不能为空")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	enclave, exists := m.enclaves[enclaveID]
	if !exists {
		return errors.NotFoundError("enclave", enclaveID)
	}

	enclave.mu.Lock()
	enclave.Status = EnclaveStatusDestroyed

	if enclave.PrivateKey != nil {
		zeroizeRSAPrivateKey(enclave.PrivateKey)
		enclave.PrivateKey = nil
	}
	enclave.PublicKey = nil
	enclave.Measurement = nil

	enclave.mu.Unlock()

	for tokenID, token := range m.tokens {
		if token.EnclaveID == enclaveID {
			delete(m.tokens, tokenID)
		}
	}

	delete(m.enclaves, enclaveID)

	logger.Info("Enclave destroyed",
		zap.String("enclave_id", enclaveID),
		zap.Duration("lifetime", time.Since(enclave.CreatedAt)),
	)

	return nil
}

func zeroizeRSAPrivateKey(key *rsa.PrivateKey) {
	if key == nil {
		return
	}

	zeroizeBigInt(key.D)
	for i := range key.Primes {
		zeroizeBigInt(key.Primes[i])
	}
	key.Precomputed = rsa.PrecomputedValues{}
	key.Primes = nil
}

func zeroizeBigInt(n *big.Int) {
	if n == nil {
		return
	}
	bits := n.Bits()
	for i := range bits {
		bits[i] = 0
	}
}

func (m *EnclaveManager) StartEnclave(ctx context.Context, enclaveID string) (*EnclaveInfo, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	enclave, exists := m.enclaves[enclaveID]
	if !exists {
		return nil, errors.NotFoundError("enclave", enclaveID)
	}

	enclave.mu.Lock()
	defer enclave.mu.Unlock()

	if enclave.Status == EnclaveStatusDestroyed {
		return nil, errors.NewWithDetails(
			errors.ErrCodeConflict,
			"enclave已销毁",
			fmt.Sprintf("enclave %s 已销毁，无法启动", enclaveID),
		)
	}

	if enclave.Status == EnclaveStatusRunning {
		return m.enclaveToInfo(enclave), nil
	}

	now := time.Now()
	enclave.Status = EnclaveStatusRunning
	enclave.StartedAt = &now

	logger.Info("Enclave started",
		zap.String("enclave_id", enclaveID),
		zap.Time("started_at", now),
	)

	return m.enclaveToInfo(enclave), nil
}

func (m *EnclaveManager) RemoteAttestation(ctx context.Context, req *AttestationRequest) (*AttestationReport, error) {
	if req == nil {
		return nil, errors.ValidationError("request", "证明请求不能为空")
	}
	if req.EnclaveID == "" {
		return nil, errors.ValidationError("enclave_id", "enclave ID不能为空")
	}
	if req.Nonce == "" {
		return nil, errors.ValidationError("nonce", "nonce不能为空")
	}

	m.mu.RLock()
	enclave, exists := m.enclaves[req.EnclaveID]
	m.mu.RUnlock()

	if !exists {
		return nil, errors.NotFoundError("enclave", req.EnclaveID)
	}

	enclave.mu.RLock()
	defer enclave.mu.RUnlock()

	if enclave.Status != EnclaveStatusRunning {
		return nil, errors.NewWithDetails(
			errors.ErrCodeAttestation,
			"enclave状态不正确",
			fmt.Sprintf("enclave状态: %s, 需要: running", enclave.Status),
		)
	}

	measurement := base64.StdEncoding.EncodeToString(enclave.Measurement)
	pubKeyStr, err := utils.EncodePublicKey(enclave.PublicKey)
	if err != nil {
		logger.Error("Failed to encode public key",
			zap.String("enclave_id", req.EnclaveID),
			zap.Error(err),
		)
		return nil, errors.Wrap(err, errors.ErrCodeAttestation, "编码公钥失败")
	}

	reportData := map[string]interface{}{
		"enclave_id":   req.EnclaveID,
		"nonce":        req.Nonce,
		"measurement":  measurement,
		"public_key":   pubKeyStr,
		"timestamp":    time.Now().UnixNano(),
	}

	if len(req.UserData) > 0 {
		reportData["user_data"] = base64.StdEncoding.EncodeToString(req.UserData)
	}

	reportJSON, err := json.Marshal(reportData)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "序列化证明报告失败")
	}

	signature, err := utils.RSAEncrypt(reportJSON, &m.rootKey.PublicKey)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeEncryption, "签名证明报告失败")
	}

	quote := m.generateQuote(enclave, req.Nonce, req.Challenge)

	report := &AttestationReport{
		EnclaveID:   req.EnclaveID,
		Timestamp:   time.Now(),
		Nonce:       req.Nonce,
		Measurement: measurement,
		Signature:   signature,
		PublicKey:   pubKeyStr,
		Quote:       quote,
		Status:      "verified",
	}

	if len(req.UserData) > 0 {
		report.UserData = base64.StdEncoding.EncodeToString(req.UserData)
	}

	logger.Info("Remote attestation completed",
		zap.String("enclave_id", req.EnclaveID),
		zap.String("status", report.Status),
	)

	return report, nil
}

func (m *EnclaveManager) VerifyAttestation(report *AttestationReport) (bool, error) {
	if report == nil {
		return false, errors.ValidationError("report", "证明报告不能为空")
	}

	reportJSON, err := json.Marshal(map[string]interface{}{
		"enclave_id":  report.EnclaveID,
		"nonce":       report.Nonce,
		"measurement": report.Measurement,
		"public_key":  report.PublicKey,
		"timestamp":   report.Timestamp.UnixNano(),
	})
	if err != nil {
		return false, errors.Wrap(err, errors.ErrCodeInternal, "序列化报告失败")
	}

	decrypted, err := utils.RSADecrypt(report.Signature, m.rootKey)
	if err != nil {
		logger.Error("Failed to verify attestation signature", zap.Error(err))
		return false, errors.Wrap(err, errors.ErrCodeAttestation, "验签证明报告失败")
	}

	if string(decrypted) != string(reportJSON) {
		return false, errors.New(errors.ErrCodeAttestation, "证明报告签名无效")
	}

	if report.Status != "verified" {
		return false, errors.New(errors.ErrCodeAttestation, "证明报告状态无效")
	}

	return true, nil
}

func (m *EnclaveManager) GenerateToken(enclaveID string, tokenType string) (*AuthToken, error) {
	if enclaveID == "" {
		return nil, errors.ValidationError("enclave_id", "enclave ID不能为空")
	}

	m.mu.RLock()
	enclave, exists := m.enclaves[enclaveID]
	m.mu.RUnlock()

	if !exists {
		return nil, errors.NotFoundError("enclave", enclaveID)
	}

	if enclave.Status != EnclaveStatusRunning {
		return nil, errors.NewWithDetails(
			errors.ErrCodeUnauthorized,
			"enclave未运行",
			fmt.Sprintf("enclave状态: %s", enclave.Status),
		)
	}

	tokenID := utils.GenerateShortID()
	ttl := defaultTokenTTL
	if tokenType == TokenTypeRefresh {
		ttl = 24 * time.Hour
	}

	now := time.Now()
	expiresAt := now.Add(ttl)

	tokenData := map[string]interface{}{
		"token_id":   tokenID,
		"enclave_id": enclaveID,
		"type":       tokenType,
		"issued_at":  now.UnixNano(),
		"expires_at": expiresAt.UnixNano(),
	}

	tokenJSON, err := json.Marshal(tokenData)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "序列化令牌失败")
	}

	tokenStr := base64.StdEncoding.EncodeToString(tokenJSON)
	signature := utils.HashSHA256([]byte(tokenStr + m.rootKey.PublicKey.N.String()))

	token := &AuthToken{
		Token:      tokenStr,
		Type:       tokenType,
		EnclaveID:  enclaveID,
		IssuedAt:   now,
		ExpiresAt:  expiresAt,
		Signature:  signature,
	}

	m.mu.Lock()
	m.tokens[tokenID] = token
	m.mu.Unlock()

	logger.Info("Auth token generated",
		zap.String("token_id", tokenID),
		zap.String("enclave_id", enclaveID),
		zap.String("type", tokenType),
		zap.Time("expires_at", expiresAt),
	)

	return token, nil
}

func (m *EnclaveManager) ValidateToken(tokenStr string, enclaveID string) (*AuthToken, error) {
	if tokenStr == "" {
		return nil, errors.ValidationError("token", "令牌不能为空")
	}

	tokenDataBytes, err := base64.StdEncoding.DecodeString(tokenStr)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeUnauthorized, "无效的令牌格式")
	}

	var tokenData map[string]interface{}
	if err := json.Unmarshal(tokenDataBytes, &tokenData); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeUnauthorized, "解析令牌失败")
	}

	tokenID, ok := tokenData["token_id"].(string)
	if !ok {
		return nil, errors.New(errors.ErrCodeUnauthorized, "令牌缺少token_id")
	}

	m.mu.RLock()
	token, exists := m.tokens[tokenID]
	m.mu.RUnlock()

	if !exists {
		return nil, errors.New(errors.ErrCodeUnauthorized, "令牌不存在或已失效")
	}

	if enclaveID != "" && token.EnclaveID != enclaveID {
		return nil, errors.New(errors.ErrCodeForbidden, "令牌不属于当前enclave")
	}

	decrypted, err := utils.RSADecrypt(token.Signature, m.rootKey)
	if err != nil || string(decrypted) != tokenStr {
		return nil, errors.New(errors.ErrCodeUnauthorized, "令牌签名无效")
	}

	if time.Now().After(token.ExpiresAt) {
		m.mu.Lock()
		delete(m.tokens, tokenID)
		m.mu.Unlock()
		return nil, errors.New(errors.ErrCodeUnauthorized, "令牌已过期")
	}

	return token, nil
}

func (m *EnclaveManager) RevokeToken(tokenID string) error {
	if tokenID == "" {
		return errors.ValidationError("token_id", "token ID不能为空")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.tokens[tokenID]; !exists {
		return errors.NotFoundError("token", tokenID)
	}

	delete(m.tokens, tokenID)

	logger.Info("Token revoked", zap.String("token_id", tokenID))
	return nil
}

func (m *EnclaveManager) SecureExecute(ctx context.Context, req *SecureExecutionRequest) (*SecureExecutionResult, error) {
	if req == nil {
		return nil, errors.ValidationError("request", "执行请求不能为空")
	}
	if req.EnclaveID == "" {
		return nil, errors.ValidationError("enclave_id", "enclave ID不能为空")
	}
	if req.Function == "" {
		return nil, errors.ValidationError("function", "函数名不能为空")
	}

	if _, err := m.ValidateToken(req.Token, req.EnclaveID); err != nil {
		return nil, err
	}

	m.mu.RLock()
	enclave, exists := m.enclaves[req.EnclaveID]
	m.mu.RUnlock()

	if !exists {
		return nil, errors.NotFoundError("enclave", req.EnclaveID)
	}

	enclave.mu.RLock()
	defer enclave.mu.RUnlock()

	if enclave.Status != EnclaveStatusRunning {
		return nil, errors.NewWithDetails(
			errors.ErrCodeConflict,
			"enclave未运行",
			fmt.Sprintf("enclave状态: %s", enclave.Status),
		)
	}

	requestID := utils.GenerateTraceID()
	startTime := time.Now()

	logger.Info("Secure execution started",
		zap.String("request_id", requestID),
		zap.String("enclave_id", req.EnclaveID),
		zap.String("function", req.Function),
	)

	var arguments map[string]interface{}
	if req.Encrypted {
		arguments = make(map[string]interface{})
		for k, v := range req.Arguments {
			if strVal, ok := v.(string); ok {
				decrypted, err := utils.RSADecrypt(strVal, enclave.PrivateKey)
				if err != nil {
					return &SecureExecutionResult{
						RequestID:  requestID,
						EnclaveID:  req.EnclaveID,
						Status:     models.StatusFailed,
						Error:      "参数解密失败: " + err.Error(),
						ExecutedAt: startTime,
						DurationMs: time.Since(startTime).Milliseconds(),
					}, nil
				}
				arguments[k] = string(decrypted)
			} else {
				arguments[k] = v
			}
		}
	} else {
		arguments = req.Arguments
	}

	result := m.executeInEnclave(enclave, req.Function, arguments)

	duration := time.Since(startTime)

	resultData := map[string]interface{}{
		"request_id": requestID,
		"function":   req.Function,
		"result":     result,
		"timestamp":  time.Now().UnixNano(),
	}

	resultJSON, _ := json.Marshal(resultData)
	signature := utils.HashSHA256(resultJSON)

	execResult := &SecureExecutionResult{
		RequestID:  requestID,
		EnclaveID:  req.EnclaveID,
		Status:     models.StatusCompleted,
		Result:     result,
		ExecutedAt: startTime,
		DurationMs: duration.Milliseconds(),
		Signature:  signature,
	}

	logger.Info("Secure execution completed",
		zap.String("request_id", requestID),
		zap.String("enclave_id", req.EnclaveID),
		zap.String("function", req.Function),
		zap.Int64("duration_ms", duration.Milliseconds()),
	)

	return execResult, nil
}

func (m *EnclaveManager) GetEnclaveInfo(enclaveID string) (*EnclaveInfo, error) {
	if enclaveID == "" {
		return nil, errors.ValidationError("enclave_id", "enclave ID不能为空")
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	enclave, exists := m.enclaves[enclaveID]
	if !exists {
		return nil, errors.NotFoundError("enclave", enclaveID)
	}

	enclave.mu.RLock()
	defer enclave.mu.RUnlock()

	return m.enclaveToInfo(enclave), nil
}

func (m *EnclaveManager) ListEnclaves() []*EnclaveInfo {
	m.mu.RLock()
	defer m.mu.RUnlock()

	infos := make([]*EnclaveInfo, 0, len(m.enclaves))
	for _, enclave := range m.enclaves {
		enclave.mu.RLock()
		infos = append(infos, m.enclaveToInfo(enclave))
		enclave.mu.RUnlock()
	}
	return infos
}

func (m *EnclaveManager) CleanupExpired() {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	for id, enclave := range m.enclaves {
		if now.After(enclave.ExpiresAt) {
			enclave.mu.Lock()
			enclave.Status = EnclaveStatusDestroyed
			if enclave.PrivateKey != nil {
				zeroizeRSAPrivateKey(enclave.PrivateKey)
				enclave.PrivateKey = nil
			}
			enclave.PublicKey = nil
			enclave.Measurement = nil
			enclave.mu.Unlock()
			delete(m.enclaves, id)
			logger.Info("Expired enclave cleaned up", zap.String("enclave_id", id))
		}
	}

	for tokenID, token := range m.tokens {
		if now.After(token.ExpiresAt) {
			delete(m.tokens, tokenID)
			logger.Info("Expired token cleaned up", zap.String("token_id", tokenID))
		}
	}
}

func (m *EnclaveManager) enclaveToInfo(enclave *Enclave) *EnclaveInfo {
	return &EnclaveInfo{
		ID:          enclave.ID,
		Type:        enclave.Config.Type,
		Status:      enclave.Status,
		MemorySize:  enclave.Config.MemorySize,
		ThreadCount: enclave.Config.ThreadCount,
		Labels:      enclave.Config.Labels,
		CreatedAt:   enclave.CreatedAt,
		StartedAt:   enclave.StartedAt,
		ExpiresAt:   enclave.ExpiresAt,
		Attributes: map[string]interface{}{
			"measurement": base64.StdEncoding.EncodeToString(enclave.Measurement),
		},
	}
}

func (m *EnclaveManager) generateQuote(enclave *Enclave, nonce string, challenge []byte) string {
	quoteData := map[string]interface{}{
		"enclave_id":  enclave.ID,
		"nonce":       nonce,
		"measurement": base64.StdEncoding.EncodeToString(enclave.Measurement),
		"timestamp":   time.Now().UnixNano(),
	}

	if len(challenge) > 0 {
		quoteData["challenge"] = base64.StdEncoding.EncodeToString(challenge)
	}

	quoteJSON, _ := json.Marshal(quoteData)
	return base64.StdEncoding.EncodeToString(quoteJSON)
}

func (m *EnclaveManager) executeInEnclave(enclave *Enclave, function string, args map[string]interface{}) interface{} {
	switch function {
	case "ping":
		return map[string]interface{}{
			"status":    "ok",
			"timestamp": time.Now().UnixNano(),
		}
	case "echo":
		return map[string]interface{}{
			"input": args,
		}
	case "hash":
		if data, ok := args["data"].(string); ok {
			return map[string]interface{}{
				"algorithm": "sha256",
				"hash":      utils.HashSHA256([]byte(data)),
			}
		}
		return map[string]interface{}{
			"error": "missing 'data' argument",
		}
	case "encrypt":
		if data, ok := args["data"].(string); ok {
			key := utils.GenerateAESKey()
			encrypted, _ := utils.AESEncrypt([]byte(data), key)
			return map[string]interface{}{
				"encrypted":   encrypted,
				"key_base64":  base64.StdEncoding.EncodeToString(key),
			}
		}
		return map[string]interface{}{
			"error": "missing 'data' argument",
		}
	default:
		return map[string]interface{}{
			"error":      fmt.Sprintf("unknown function: %s", function),
			"available":  []string{"ping", "echo", "hash", "encrypt"},
		}
	}
}

func StartCleanupJob(ctx context.Context, interval time.Duration) {
	if interval <= 0 {
		interval = time.Hour
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	logger.Info("TEE cleanup job started", zap.Duration("interval", interval))

	for {
		select {
		case <-ctx.Done():
			logger.Info("TEE cleanup job stopped")
			return
		case <-ticker.C:
			GetEnclaveManager().CleanupExpired()
		}
	}
}
