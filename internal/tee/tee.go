package tee

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/apishield/apishield/internal/core/ports"
	"github.com/google/uuid"
)

var (
	ErrEnclaveNotFound     = errors.New("enclave not found")
	ErrEnclaveExists       = errors.New("enclave already exists")
	ErrEnclaveDestroyed    = errors.New("enclave is destroyed")
	ErrInvalidEnclaveType  = errors.New("invalid enclave type")
	ErrAttestationFailed   = errors.New("remote attestation failed")
	ErrExecutionTimeout    = errors.New("secure execution timeout")
	ErrInvalidConfig       = errors.New("invalid enclave configuration")
)

type teeService struct {
	enclaves      map[string]*enclaveState
	eventBus      ports.EventPublisher
	logger        ports.Logger
	crypto        ports.CryptoProvider
	mu            sync.RWMutex
}

type enclaveState struct {
	info       *ports.EnclaveInfo
	config     *ports.EnclaveConfig
	privateKey *ecdsa.PrivateKey
	createdAt  time.Time
	attested   bool
}

func NewTeeService(eventBus ports.EventPublisher, logger ports.Logger, crypto ports.CryptoProvider) ports.TEEPort {
	return &teeService{
		enclaves: make(map[string]*enclaveState),
		eventBus: eventBus,
		logger:   logger,
		crypto:   crypto,
	}
}

func (s *teeService) CreateEnclave(ctx context.Context, config *ports.EnclaveConfig) (*ports.EnclaveInfo, error) {
	if err := s.validateConfig(config); err != nil {
		return nil, err
	}

	s.mu.Lock()
	if _, exists := s.enclaves[config.EnclaveID]; exists {
		s.mu.Unlock()
		return nil, ErrEnclaveExists
	}

	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		s.mu.Unlock()
		return nil, fmt.Errorf("failed to generate enclave key pair: %w", err)
	}

	publicKeyBytes, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		s.mu.Unlock()
		return nil, fmt.Errorf("failed to marshal public key: %w", err)
	}

	enclaveID := config.EnclaveID
	if enclaveID == "" {
		enclaveID = uuid.New().String()
	}

	now := time.Now()
	info := &ports.EnclaveInfo{
		EnclaveID:  enclaveID,
		Type:       config.Type,
		Status:     ports.EnclaveStatusCreated,
		MemorySize: config.MemorySize,
		CPUNum:     config.CPUNum,
		Endpoint:   fmt.Sprintf("enclave://%s", enclaveID),
		PublicKey:  publicKeyBytes,
		CreatedAt:  now,
		UpdatedAt:  now,
		Labels:     config.Labels,
	}

	s.enclaves[enclaveID] = &enclaveState{
		info:       info,
		config:     config,
		privateKey: privateKey,
		createdAt:  now,
		attested:   false,
	}
	s.mu.Unlock()

	info.Status = ports.EnclaveStatusRunning
	info.UpdatedAt = time.Now()

	s.publishEvent(ctx, "enclave.created", enclaveID, map[string]interface{}{
		"type":        config.Type,
		"memory_size": config.MemorySize,
		"cpu_num":     config.CPUNum,
	})

	s.logger.Info(ctx, "Enclave created successfully", map[string]any{
		"enclave_id": enclaveID,
		"type":       config.Type,
	})

	return info, nil
}

func (s *teeService) DestroyEnclave(ctx context.Context, enclaveID string) error {
	s.mu.Lock()
	state, exists := s.enclaves[enclaveID]
	if !exists {
		s.mu.Unlock()
		return ErrEnclaveNotFound
	}

	if state.info.Status == ports.EnclaveStatusDestroyed {
		s.mu.Unlock()
		return ErrEnclaveDestroyed
	}

	state.info.Status = ports.EnclaveStatusDestroyed
	state.info.UpdatedAt = time.Now()
	s.mu.Unlock()

	s.publishEvent(ctx, "enclave.destroyed", enclaveID, map[string]interface{}{
		"type":      state.config.Type,
		"duration":  time.Since(state.createdAt).Seconds(),
	})

	s.logger.Info(ctx, "Enclave destroyed", map[string]any{
		"enclave_id": enclaveID,
	})

	return nil
}

func (s *teeService) ListEnclaves(ctx context.Context) ([]*ports.EnclaveInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*ports.EnclaveInfo, 0, len(s.enclaves))
	for _, state := range s.enclaves {
		infoCopy := *state.info
		result = append(result, &infoCopy)
	}

	return result, nil
}

func (s *teeService) GetEnclave(ctx context.Context, enclaveID string) (*ports.EnclaveInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	state, exists := s.enclaves[enclaveID]
	if !exists {
		return nil, ErrEnclaveNotFound
	}

	infoCopy := *state.info
	return &infoCopy, nil
}

func (s *teeService) RemoteAttestation(ctx context.Context, req *ports.AttestationRequest) (*ports.AttestationReport, error) {
	if req == nil {
		return nil, errors.New("attestation request is nil")
	}

	s.mu.RLock()
	state, exists := s.enclaves[req.EnclaveID]
	s.mu.RUnlock()

	if !exists {
		return nil, ErrEnclaveNotFound
	}

	if state.info.Status == ports.EnclaveStatusDestroyed {
		return nil, ErrEnclaveDestroyed
	}

	measurements := s.generateMeasurements(state)
	quote := s.generateQuote(req, state, measurements)

	signature, err := s.crypto.Sign(ctx, quote, "attestation_key")
	if err != nil {
		return nil, fmt.Errorf("failed to sign attestation quote: %w", err)
	}

	report := &ports.AttestationReport{
		EnclaveID:    req.EnclaveID,
		Quote:        quote,
		Signature:    signature,
		PublicKey:    state.info.PublicKey,
		Measurements: measurements,
		Timestamp:    time.Now(),
		Valid:        true,
	}

	s.mu.Lock()
	state.attested = true
	state.info.Status = ports.EnclaveStatusAttested
	state.info.UpdatedAt = time.Now()
	s.mu.Unlock()

	s.publishEvent(ctx, "enclave.attested", req.EnclaveID, map[string]interface{}{
		"nonce": req.Nonce,
		"valid": report.Valid,
	})

	s.logger.Info(ctx, "Remote attestation completed", map[string]any{
		"enclave_id": req.EnclaveID,
		"valid":      report.Valid,
	})

	return report, nil
}

func (s *teeService) VerifyAttestation(ctx context.Context, report *ports.AttestationReport) (bool, error) {
	if report == nil {
		return false, errors.New("attestation report is nil")
	}

	s.mu.RLock()
	state, exists := s.enclaves[report.EnclaveID]
	s.mu.RUnlock()

	if !exists {
		return false, ErrEnclaveNotFound
	}

	valid, err := s.crypto.Verify(ctx, report.Quote, report.Signature, "attestation_key")
	if err != nil {
		return false, fmt.Errorf("signature verification failed: %w", err)
	}

	if !valid {
		return false, ErrAttestationFailed
	}

	if time.Since(report.Timestamp) > 1*time.Hour {
		return false, errors.New("attestation report expired")
	}

	return state.attested && valid, nil
}

func (s *teeService) SecureExecute(ctx context.Context, req *ports.SecureExecutionRequest) (*ports.SecureExecutionResult, error) {
	if req == nil {
		return nil, errors.New("execution request is nil")
	}

	s.mu.RLock()
	state, exists := s.enclaves[req.EnclaveID]
	s.mu.RUnlock()

	if !exists {
		return nil, ErrEnclaveNotFound
	}

	if state.info.Status == ports.EnclaveStatusDestroyed {
		return nil, ErrEnclaveDestroyed
	}

	if !state.attested {
		return nil, errors.New("enclave not attested, cannot execute")
	}

	execCtx, cancel := context.WithTimeout(ctx, req.Timeout)
	defer cancel()

	startTime := time.Now()

	var payload []byte
	var err error

	if req.Encrypted {
		payload, err = s.crypto.Decrypt(ctx, req.Payload, req.EnclaveID)
		if err != nil {
			return &ports.SecureExecutionResult{
				Success:  false,
				ErrorMsg: fmt.Sprintf("decryption failed: %v", err),
				ExecTime: time.Since(startTime),
			}, nil
		}
	} else {
		payload = req.Payload
	}

	result, err := s.executeFunction(execCtx, state, req.Function, payload)
	execTime := time.Since(startTime)

	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return &ports.SecureExecutionResult{
				Success:  false,
				ErrorMsg: ErrExecutionTimeout.Error(),
				ExecTime: execTime,
			}, nil
		}
		return &ports.SecureExecutionResult{
			Success:  false,
			ErrorMsg: err.Error(),
			ExecTime: execTime,
		}, nil
	}

	signature, err := s.signResult(state, result)
	if err != nil {
		return &ports.SecureExecutionResult{
			Success:  false,
			ErrorMsg: fmt.Sprintf("failed to sign result: %v", err),
			ExecTime: execTime,
		}, nil
	}

	s.publishEvent(ctx, "enclave.executed", req.EnclaveID, map[string]interface{}{
		"function": req.Function,
		"duration": execTime.Seconds(),
		"success":  true,
	})

	s.logger.Debug(ctx, "Secure execution completed", map[string]any{
		"enclave_id": req.EnclaveID,
		"function":   req.Function,
		"duration_ms": execTime.Milliseconds(),
	})

	return &ports.SecureExecutionResult{
		Result:    result,
		Signature: signature,
		ExecTime:  execTime,
		Success:   true,
	}, nil
}

func (s *teeService) validateConfig(config *ports.EnclaveConfig) error {
	if config == nil {
		return ErrInvalidConfig
	}

	switch config.Type {
	case ports.EnclaveTypeSGX, ports.EnclaveTypeSEV, ports.EnclaveTypeTrustZone, ports.EnclaveTypeHSM:
	default:
		return fmt.Errorf("%w: %s", ErrInvalidEnclaveType, config.Type)
	}

	if config.MemorySize <= 0 {
		return fmt.Errorf("%w: memory size must be positive", ErrInvalidConfig)
	}

	if config.CPUNum <= 0 {
		return fmt.Errorf("%w: CPU num must be positive", ErrInvalidConfig)
	}

	return nil
}

func (s *teeService) generateMeasurements(state *enclaveState) map[string]string {
	hash := sha256.New()
	hash.Write([]byte(state.info.EnclaveID))
	hash.Write([]byte(state.config.ImagePath))
	hash.Write(state.info.PublicKey)

	measurements := make(map[string]string)
	measurements["enclave_hash"] = hex.EncodeToString(hash.Sum(nil))
	measurements["memory_hash"] = hex.EncodeToString(sha256.Sum256([]byte(fmt.Sprintf("%d", state.config.MemorySize))))
	measurements["code_hash"] = hex.EncodeToString(sha256.Sum256([]byte(state.config.ImagePath)))
	measurements["timestamp"] = state.createdAt.Format(time.RFC3339)

	return measurements
}

func (s *teeService) generateQuote(req *ports.AttestationRequest, state *enclaveState, measurements map[string]string) []byte {
	hash := sha256.New()
	hash.Write([]byte(req.Nonce))
	hash.Write(req.Challenge)
	hash.Write([]byte(state.info.EnclaveID))
	for k, v := range measurements {
		hash.Write([]byte(k))
		hash.Write([]byte(v))
	}
	return hash.Sum(nil)
}

func (s *teeService) executeFunction(ctx context.Context, state *enclaveState, function string, payload []byte) ([]byte, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	hash := sha256.New()
	hash.Write([]byte(function))
	hash.Write(payload)
	hash.Write([]byte(state.info.EnclaveID))
	hash.Write([]byte(time.Now().String()))

	return []byte(hex.EncodeToString(hash.Sum(nil))), nil
}

func (s *teeService) signResult(state *enclaveState, result []byte) ([]byte, error) {
	hash := sha256.Sum256(result)
	signature, err := ecdsa.SignASN1(rand.Reader, state.privateKey, hash[:])
	if err != nil {
		return nil, err
	}
	return signature, nil
}

func (s *teeService) publishEvent(ctx context.Context, eventType, aggregateID string, payload map[string]interface{}) {
	if s.eventBus == nil {
		return
	}

	event := ports.Event{
		ID:        uuid.New(),
		Type:      eventType,
		Source:    "tee-service",
		Payload:   payload,
		Timestamp: time.Now().Unix(),
	}

	if err := s.eventBus.Publish(ctx, event); err != nil {
		s.logger.Warn(ctx, "Failed to publish domain event", map[string]any{
			"event_type": eventType,
			"error":      err.Error(),
		})
	}
}
