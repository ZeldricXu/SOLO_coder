package mpc

import (
	"context"
	"crypto/rand"
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
	ProtocolSPDZ    = "SPDZ"
	ProtocolABY3    = "ABY3"
	ProtocolBGW     = "BGW"
	ProtocolOblivio = "OBLIVIO"

	RoleCoordinator = "coordinator"
	RoleParty       = "party"

	DefaultMaxRetries   = 3
	DefaultRetryDelay   = 5 * time.Second
	DefaultTimeout      = 30 * time.Minute
	DefaultPrimeBitLen  = 256

	StatusInitialized = "initialized"
	StatusRunning     = "running"
	StatusCompleted   = "completed"
	StatusFailed      = "failed"
	StatusRecovering  = "recovering"
)

type shareEntry struct {
	ParticipantID string      `json:"participant_id"`
	Value         interface{} `json:"value"`
}

type Participant struct {
	ID         string
	Address    string
	Index      int
	Status     string
	PublicKey  string
	SymmetricKey []byte
	LastSeen   time.Time
	mu         sync.RWMutex
}

type EncryptedInput struct {
	ParticipantID string
	Ciphertext    string
	Commitment    string
	Nonce         string
	Timestamp     time.Time
}

type DecryptedResult struct {
	ProtocolID string
	Result     interface{}
	Proof      string
	Timestamp  time.Time
}

type ProtocolConfig struct {
	ProtocolType  string
	Prime         *big.Int
	Modulus       *big.Int
	NumParties    int
	Threshold     int
	MaxRetries    int
	RetryDelay    time.Duration
	Timeout       time.Duration
	EnableRecovery bool
}

type ProtocolState struct {
	ProtocolID   string
	Config       ProtocolConfig
	Phase        string
	Round        int
	Participants map[string]*Participant
	Inputs       map[string]EncryptedInput
	Shares       map[string][]byte
	Messages     map[string][]byte
	Checkpoints  []Checkpoint
	Status       string
	Error        *errors.AppError
	StartTime    time.Time
	EndTime      time.Time
	mu           sync.RWMutex
}

type Checkpoint struct {
	Phase      string
	Round      int
	StateHash  string
	Timestamp  time.Time
	Participants []string
}

type MPCManager struct {
	ID               string
	Protocols        map[string]*ProtocolState
	ParticipantPool  map[string]*Participant
	RecoveryQueue    chan string
	ctx              context.Context
	cancel           context.CancelFunc
	wg               sync.WaitGroup
	mu               sync.RWMutex
}

var (
	instance *MPCManager
	once     sync.Once
)

func DefaultProtocolConfig(protocolType string, numParties int) ProtocolConfig {
	prime, _ := rand.Prime(rand.Reader, DefaultPrimeBitLen)
	return ProtocolConfig{
		ProtocolType:   protocolType,
		Prime:          prime,
		Modulus:        new(big.Int).Mul(prime, big.NewInt(2)),
		NumParties:     numParties,
		Threshold:      (numParties - 1) / 2,
		MaxRetries:     DefaultMaxRetries,
		RetryDelay:     DefaultRetryDelay,
		Timeout:        DefaultTimeout,
		EnableRecovery: true,
	}
}

func NewParticipant(id, address string, index int) *Participant {
	symKey := utils.GenerateAESKey()
	return &Participant{
		ID:            id,
		Address:       address,
		Index:         index,
		Status:        models.StatusActive,
		SymmetricKey:  symKey,
		LastSeen:      time.Now(),
	}
}

func (p *Participant) SetPublicKey(pubKey string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, err := utils.DecodePublicKey(pubKey); err != nil {
		return errors.Wrap(err, errors.ErrCodeValidation, "无效的公钥")
	}
	p.PublicKey = pubKey
	return nil
}

func (p *Participant) UpdateStatus(status string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Status = status
	p.LastSeen = time.Now()
}

func (p *Participant) Heartbeat() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.LastSeen = time.Now()
}

func (p *Participant) IsActive(timeout time.Duration) bool {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.Status == models.StatusActive && time.Since(p.LastSeen) < timeout
}

func GetManager() *MPCManager {
	once.Do(func() {
		ctx, cancel := context.WithCancel(context.Background())
		instance = &MPCManager{
			ID:              utils.GenerateID("mpc"),
			Protocols:       make(map[string]*ProtocolState),
			ParticipantPool: make(map[string]*Participant),
			RecoveryQueue:   make(chan string, 100),
			ctx:             ctx,
			cancel:          cancel,
		}
		instance.startRecoveryWorker()
	})
	return instance
}

func (m *MPCManager) RegisterParticipant(p *Participant) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.ParticipantPool[p.ID]; exists {
		return errors.New(errors.ErrCodeConflict, fmt.Sprintf("参与方 %s 已存在", p.ID))
	}
	m.ParticipantPool[p.ID] = p
	logger.Info("MPC参与方注册成功", zap.String("participant_id", p.ID), zap.String("address", p.Address))
	return nil
}

func (m *MPCManager) UnregisterParticipant(participantID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.ParticipantPool, participantID)
	logger.Info("MPC参与方注销", zap.String("participant_id", participantID))
}

func (m *MPCManager) ListAllParticipants() []*Participant {
	m.mu.RLock()
	defer m.mu.RUnlock()
	all := make([]*Participant, 0, len(m.ParticipantPool))
	for _, p := range m.ParticipantPool {
		all = append(all, p)
	}
	return all
}

func (m *MPCManager) GetParticipant(participantID string) (*Participant, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	p, exists := m.ParticipantPool[participantID]
	if !exists {
		return nil, errors.NotFoundError("参与方", participantID)
	}
	return p, nil
}

func (m *MPCManager) ListActiveParticipants() []*Participant {
	m.mu.RLock()
	defer m.mu.RUnlock()
	active := make([]*Participant, 0, len(m.ParticipantPool))
	for _, p := range m.ParticipantPool {
		if p.IsActive(2 * DefaultRetryDelay) {
			active = append(active, p)
		}
	}
	return active
}

func (m *MPCManager) CreateProtocol(config ProtocolConfig, participantIDs []string) (*ProtocolState, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if len(participantIDs) < config.NumParties {
		return nil, errors.ValidationError("participants", fmt.Sprintf("至少需要 %d 个参与方，当前 %d 个", config.NumParties, len(participantIDs)))
	}

	participants := make(map[string]*Participant)
	for _, id := range participantIDs {
		p, exists := m.ParticipantPool[id]
		if !exists {
			return nil, errors.NotFoundError("参与方", id)
		}
		participants[id] = p
	}

	protocolID := utils.GenerateID("proto")
	state := &ProtocolState{
		ProtocolID:   protocolID,
		Config:       config,
		Phase:        models.PhaseInitializing,
		Round:        0,
		Participants: participants,
		Inputs:       make(map[string]EncryptedInput),
		Shares:       make(map[string][]byte),
		Messages:     make(map[string][]byte),
		Status:       StatusInitialized,
		StartTime:    time.Now(),
	}

	m.Protocols[protocolID] = state
	logger.Info("MPC协议创建成功", zap.String("protocol_id", protocolID), zap.String("type", config.ProtocolType), zap.Int("parties", config.NumParties))
	return state, nil
}

func (m *MPCManager) GetProtocol(protocolID string) (*ProtocolState, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	state, exists := m.Protocols[protocolID]
	if !exists {
		return nil, errors.NotFoundError("协议", protocolID)
	}
	return state, nil
}

func (m *MPCManager) startRecoveryWorker() {
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		for {
			select {
			case <-m.ctx.Done():
				logger.Info("MPC恢复协程已停止")
				return
			case protocolID := <-m.RecoveryQueue:
				if err := m.recoverProtocol(protocolID); err != nil {
					logger.Error("MPC协议恢复失败", zap.String("protocol_id", protocolID), zap.Error(err))
				}
			}
		}
	}()
	logger.Info("MPC恢复协程已启动")
}

func (m *MPCManager) recoverProtocol(protocolID string) error {
	state, err := m.GetProtocol(protocolID)
	if err != nil {
		return err
	}

	state.mu.Lock()
	defer state.mu.Unlock()

	if len(state.Checkpoints) == 0 {
		return errors.New(errors.ErrCodeMPCProtocol, "没有可用的检查点用于恢复")
	}

	lastCheckpoint := state.Checkpoints[len(state.Checkpoints)-1]
	state.Phase = lastCheckpoint.Phase
	state.Round = lastCheckpoint.Round
	state.Status = StatusRecovering

	logger.Info("MPC协议开始恢复", zap.String("protocol_id", protocolID), zap.String("phase", lastCheckpoint.Phase), zap.Int("round", lastCheckpoint.Round))

	activeParticipants := make([]string, 0)
	for _, id := range lastCheckpoint.Participants {
		if p, exists := state.Participants[id]; exists && p.IsActive(state.Config.RetryDelay*2) {
			activeParticipants = append(activeParticipants, id)
		}
	}

	if len(activeParticipants) < state.Config.Threshold+1 {
		return errors.New(errors.ErrCodeMPCProtocol, fmt.Sprintf("可用参与方不足，需要 %d 个，当前 %d 个", state.Config.Threshold+1, len(activeParticipants)))
	}

	state.Status = StatusRunning
	logger.Info("MPC协议恢复完成", zap.String("protocol_id", protocolID))
	return nil
}

func (m *MPCManager) Shutdown() {
	m.cancel()
	m.wg.Wait()
	close(m.RecoveryQueue)
	logger.Info("MPC管理器已关闭")
}

func (s *ProtocolState) saveCheckpoint() {
	s.mu.RLock()
	defer s.mu.RUnlock()

	stateData := map[string]interface{}{
		"phase":   s.Phase,
		"round":   s.Round,
		"inputs":  s.Inputs,
		"shares":  s.Shares,
	}
	data, _ := json.Marshal(stateData)
	hash := utils.HashSHA256(data)

	participants := make([]string, 0, len(s.Participants))
	for id := range s.Participants {
		participants = append(participants, id)
	}

	checkpoint := Checkpoint{
		Phase:        s.Phase,
		Round:        s.Round,
		StateHash:    hash,
		Timestamp:    time.Now(),
		Participants: participants,
	}
	s.Checkpoints = append(s.Checkpoints, checkpoint)
	logger.Debug("MPC检查点已保存", zap.String("protocol_id", s.ProtocolID), zap.String("phase", s.Phase), zap.Int("round", s.Round))
}

func EncryptInput(participantID string, input interface{}, symKey []byte) (*EncryptedInput, error) {
	inputBytes, err := json.Marshal(input)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeValidation, "输入序列化失败")
	}

	nonce := utils.GenerateNonce(12)
	ciphertext, err := utils.AESEncrypt(append(nonce, inputBytes...), symKey)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeEncryption, "输入加密失败")
	}

	commitment := utils.HashSHA256(inputBytes)

	logger.Debug("MPC输入已加密", zap.String("participant_id", participantID), zap.Int("input_size", len(inputBytes)))

	return &EncryptedInput{
		ParticipantID: participantID,
		Ciphertext:    ciphertext,
		Commitment:    commitment,
		Nonce:         base64.StdEncoding.EncodeToString(nonce),
		Timestamp:     time.Now(),
	}, nil
}

func DecryptResult(resultShare map[string]interface{}, symKeys map[string][]byte) (*DecryptedResult, error) {
	if len(resultShare) == 0 {
		return nil, errors.New(errors.ErrCodeValidation, "结果分享为空")
	}

	shares := make([]shareEntry, 0, len(resultShare))
	for id, val := range resultShare {
		if symKey, exists := symKeys[id]; exists {
			if valStr, ok := val.(string); ok {
				decrypted, err := utils.AESDecrypt(valStr, symKey)
				if err != nil {
					logger.Warn("结果分片解密失败", zap.String("participant_id", id), zap.Error(err))
					continue
				}
				var entry shareEntry
				if err := json.Unmarshal(decrypted, &entry); err == nil {
					shares = append(shares, entry)
				}
			}
		}
	}

	if len(shares) == 0 {
		return nil, errors.New(errors.ErrCodeDecryption, "没有可解密的有效结果分片")
	}

	combinedResult := combineShares(shares)
	proofBytes, _ := json.Marshal(map[string]interface{}{
		"result":      combinedResult,
		"num_shares":  len(shares),
		"timestamp":   time.Now().Unix(),
	})
	proof := utils.HashSHA256(proofBytes)

	logger.Debug("MPC结果已解密", zap.Int("shares_used", len(shares)))

	return &DecryptedResult{
		Result:    combinedResult,
		Proof:     proof,
		Timestamp: time.Now(),
	}, nil
}

func combineShares(shares []shareEntry) interface{} {
	if len(shares) == 0 {
		return nil
	}

	switch v := shares[0].Value.(type) {
	case float64:
		sum := 0.0
		for _, s := range shares {
			if val, ok := s.Value.(float64); ok {
				sum += val
			}
		}
		return sum / float64(len(shares))
	case []interface{}:
		if len(v) == 0 {
			return []interface{}{}
		}
		result := make([]interface{}, len(v))
		for i := range v {
			elementShares := make([]shareEntry, 0, len(shares))
			for _, s := range shares {
				if arr, ok := s.Value.([]interface{}); ok && i < len(arr) {
					elementShares = append(elementShares, shareEntry{ParticipantID: s.ParticipantID, Value: arr[i]})
				}
			}
			result[i] = combineShares(elementShares)
		}
		return result
	case map[string]interface{}:
		result := make(map[string]interface{})
		for key := range v {
			keyShares := make([]shareEntry, 0, len(shares))
			for _, s := range shares {
				if m, ok := s.Value.(map[string]interface{}); ok {
					keyShares = append(keyShares, shareEntry{ParticipantID: s.ParticipantID, Value: m[key]})
				}
			}
			result[key] = combineShares(keyShares)
		}
		return result
	default:
		return v
	}
}

func (m *MPCManager) ExecuteProtocol(protocolID string, operation string, inputs map[string]interface{}) (*DecryptedResult, error) {
	state, err := m.GetProtocol(protocolID)
	if err != nil {
		return nil, err
	}

	state.mu.Lock()
	if state.Status == StatusRunning {
		state.mu.Unlock()
		return nil, errors.New(errors.ErrCodeConflict, "协议正在运行中")
	}
	state.Status = StatusRunning
	state.Phase = models.PhaseProcessing
	state.mu.Unlock()

	ctx, cancel := context.WithTimeout(m.ctx, state.Config.Timeout)
	defer cancel()

	var result *DecryptedResult
	var execErr error

	for attempt := 0; attempt < state.Config.MaxRetries; attempt++ {
		select {
		case <-ctx.Done():
			state.mu.Lock()
			state.Status = StatusFailed
			state.Error = errors.New(errors.ErrCodeTimeout, "协议执行超时")
			state.mu.Unlock()
			return nil, state.Error
		default:
		}

		result, execErr = m.executeProtocolRound(ctx, state, operation, inputs)
		if execErr == nil {
			state.mu.Lock()
			state.Status = StatusCompleted
			state.Phase = models.PhaseCompleted
			state.EndTime = time.Now()
			state.mu.Unlock()
			logger.Info("MPC协议执行成功", zap.String("protocol_id", protocolID), zap.String("operation", operation), zap.Duration("duration", time.Since(state.StartTime)))
			return result, nil
		}

		logger.Warn("MPC协议执行失败，准备重试", zap.String("protocol_id", protocolID), zap.Int("attempt", attempt+1), zap.Error(execErr))

		if state.Config.EnableRecovery {
			m.RecoveryQueue <- protocolID
			time.Sleep(state.Config.RetryDelay)
		} else {
			break
		}
	}

	state.mu.Lock()
	state.Status = StatusFailed
	state.Phase = models.PhaseFailed
	state.Error = errors.Wrap(execErr, errors.ErrCodeMPCProtocol, fmt.Sprintf("协议执行失败，已重试 %d 次", state.Config.MaxRetries))
	state.EndTime = time.Now()
	state.mu.Unlock()

	logger.Error("MPC协议执行最终失败", zap.String("protocol_id", protocolID), zap.Error(execErr))
	return nil, state.Error
}

func (m *MPCManager) executeProtocolRound(ctx context.Context, state *ProtocolState, operation string, inputs map[string]interface{}) (*DecryptedResult, error) {
	state.mu.Lock()
	state.Phase = models.PhaseProcessing
	state.Round++
	state.saveCheckpoint()
	state.mu.Unlock()

	logger.Debug("MPC协议开始执行轮次", zap.String("protocol_id", state.ProtocolID), zap.String("protocol_type", state.Config.ProtocolType), zap.Int("round", state.Round))

	encryptedInputs := make(map[string]*EncryptedInput)
	for participantID, input := range inputs {
		participant, err := m.GetParticipant(participantID)
		if err != nil {
			return nil, err
		}

		encrypted, err := EncryptInput(participantID, input, participant.SymmetricKey)
		if err != nil {
			return nil, err
		}

		encryptedInputs[participantID] = encrypted

		state.mu.Lock()
		state.Inputs[participantID] = *encrypted
		state.mu.Unlock()
	}

	if err := m.broadcastInputs(ctx, state, encryptedInputs); err != nil {
		return nil, err
	}

	resultShares, err := m.runComputation(ctx, state, operation, encryptedInputs)
	if err != nil {
		return nil, err
	}

	symKeys := make(map[string][]byte)
	for id := range resultShares {
		if p, exists := state.Participants[id]; exists {
			symKeys[id] = p.SymmetricKey
		}
	}

	result, err := DecryptResult(resultShares, symKeys)
	if err != nil {
		return nil, err
	}
	result.ProtocolID = state.ProtocolID

	state.mu.Lock()
	state.saveCheckpoint()
	state.mu.Unlock()

	return result, nil
}

func (m *MPCManager) broadcastInputs(ctx context.Context, state *ProtocolState, inputs map[string]*EncryptedInput) error {
	state.mu.RLock()
	protocolType := state.Config.ProtocolType
	parties := make([]*Participant, 0, len(state.Participants))
	for _, p := range state.Participants {
		parties = append(parties, p)
	}
	state.mu.RUnlock()

	for senderID, input := range inputs {
		for _, receiver := range parties {
			if receiver.ID == senderID {
				continue
			}

			select {
			case <-ctx.Done():
				return errors.New(errors.ErrCodeTimeout, "广播输入超时")
			default:
			}

			if receiver.PublicKey != "" {
				pubKey, err := utils.DecodePublicKey(receiver.PublicKey)
				if err == nil {
					_, err := utils.RSAEncrypt([]byte(input.Ciphertext), pubKey)
					if err != nil {
						logger.Warn("RSA加密输入失败，使用对称加密", zap.String("from", senderID), zap.String("to", receiver.ID), zap.Error(err))
					}
				}
			}

			state.mu.Lock()
			msgKey := fmt.Sprintf("%s->%s", senderID, receiver.ID)
			state.Messages[msgKey] = []byte(input.Ciphertext)
			state.mu.Unlock()
		}
	}

	logger.Debug("MPC输入广播完成", zap.String("protocol_type", protocolType), zap.Int("num_inputs", len(inputs)))
	return nil
}

func (m *MPCManager) runComputation(ctx context.Context, state *ProtocolState, operation string, inputs map[string]*EncryptedInput) (map[string]interface{}, error) {
	state.mu.RLock()
	protocolType := state.Config.ProtocolType
	numParties := state.Config.NumParties
	threshold := state.Config.Threshold
	participants := make([]*Participant, 0, len(state.Participants))
	for _, p := range state.Participants {
		participants = append(participants, p)
	}
	state.mu.RUnlock()

	resultShares := make(map[string]interface{})

	switch protocolType {
	case ProtocolSPDZ:
		shares, err := runSPDZComputation(ctx, operation, inputs, participants, numParties, threshold)
		if err != nil {
			return nil, err
		}
		resultShares = shares
	case ProtocolABY3:
		shares, err := runABY3Computation(ctx, operation, inputs, participants, numParties)
		if err != nil {
			return nil, err
		}
		resultShares = shares
	case ProtocolBGW:
		shares, err := runBGWComputation(ctx, operation, inputs, participants, numParties, threshold)
		if err != nil {
			return nil, err
		}
		resultShares = shares
	default:
		return nil, errors.New(errors.ErrCodeValidation, fmt.Sprintf("不支持的协议类型: %s", protocolType))
	}

	logger.Debug("MPC计算完成", zap.String("protocol_type", protocolType), zap.String("operation", operation), zap.Int("num_shares", len(resultShares)))
	return resultShares, nil
}

func runSPDZComputation(ctx context.Context, operation string, inputs map[string]*EncryptedInput, participants []*Participant, numParties, threshold int) (map[string]interface{}, error) {
	select {
	case <-ctx.Done():
		return nil, errors.New(errors.ErrCodeTimeout, "SPDZ计算超时")
	default:
	}

	plainInputs := make(map[string]interface{})
	for id, enc := range inputs {
		if p, exists := findParticipant(participants, id); exists {
			decrypted, err := utils.AESDecrypt(enc.Ciphertext, p.SymmetricKey)
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeDecryption, "SPDZ解密输入失败")
			}
			var val interface{}
			if err := json.Unmarshal(decrypted[12:], &val); err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeValidation, "SPDZ输入反序列化失败")
			}
			plainInputs[id] = val
		}
	}

	resultValues := computeOperation(operation, plainInputs)

	resultShares := make(map[string]interface{})
	for _, p := range participants {
		share := generateShare(resultValues, p.Index, numParties, threshold)
		shareBytes, _ := json.Marshal(map[string]interface{}{
			"participant_id": p.ID,
			"value":          share,
		})
		encryptedShare, _ := utils.AESEncrypt(shareBytes, p.SymmetricKey)
		resultShares[p.ID] = encryptedShare
	}

	logger.Debug("SPDZ计算完成", zap.String("operation", operation), zap.Int("shares", len(resultShares)))
	return resultShares, nil
}

func runABY3Computation(ctx context.Context, operation string, inputs map[string]*EncryptedInput, participants []*Participant, numParties int) (map[string]interface{}, error) {
	select {
	case <-ctx.Done():
		return nil, errors.New(errors.ErrCodeTimeout, "ABY3计算超时")
	default:
	}

	if numParties < 3 {
		return nil, errors.New(errors.ErrCodeValidation, "ABY3协议至少需要3个参与方")
	}

	plainInputs := make(map[string]interface{})
	for id, enc := range inputs {
		if p, exists := findParticipant(participants, id); exists {
			decrypted, err := utils.AESDecrypt(enc.Ciphertext, p.SymmetricKey)
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeDecryption, "ABY3解密输入失败")
			}
			var val interface{}
			if err := json.Unmarshal(decrypted[12:], &val); err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeValidation, "ABY3输入反序列化失败")
			}
			plainInputs[id] = val
		}
	}

	resultValues := computeOperation(operation, plainInputs)

	resultShares := make(map[string]interface{})
	for _, p := range participants {
		share := generateABY3Share(resultValues, p.Index)
		shareBytes, _ := json.Marshal(map[string]interface{}{
			"participant_id": p.ID,
			"value":          share,
		})
		encryptedShare, _ := utils.AESEncrypt(shareBytes, p.SymmetricKey)
		resultShares[p.ID] = encryptedShare
	}

	logger.Debug("ABY3计算完成", zap.String("operation", operation), zap.Int("shares", len(resultShares)))
	return resultShares, nil
}

func runBGWComputation(ctx context.Context, operation string, inputs map[string]*EncryptedInput, participants []*Participant, numParties, threshold int) (map[string]interface{}, error) {
	select {
	case <-ctx.Done():
		return nil, errors.New(errors.ErrCodeTimeout, "BGW计算超时")
	default:
	}

	plainInputs := make(map[string]interface{})
	for id, enc := range inputs {
		if p, exists := findParticipant(participants, id); exists {
			decrypted, err := utils.AESDecrypt(enc.Ciphertext, p.SymmetricKey)
			if err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeDecryption, "BGW解密输入失败")
			}
			var val interface{}
			if err := json.Unmarshal(decrypted[12:], &val); err != nil {
				return nil, errors.Wrap(err, errors.ErrCodeValidation, "BGW输入反序列化失败")
			}
			plainInputs[id] = val
		}
	}

	resultValues := computeOperation(operation, plainInputs)

	resultShares := make(map[string]interface{})
	for _, p := range participants {
		share := generatePolynomialShare(resultValues, p.Index, numParties, threshold)
		shareBytes, _ := json.Marshal(map[string]interface{}{
			"participant_id": p.ID,
			"value":          share,
		})
		encryptedShare, _ := utils.AESEncrypt(shareBytes, p.SymmetricKey)
		resultShares[p.ID] = encryptedShare
	}

	logger.Debug("BGW计算完成", zap.String("operation", operation), zap.Int("shares", len(resultShares)))
	return resultShares, nil
}

func findParticipant(participants []*Participant, id string) (*Participant, bool) {
	for _, p := range participants {
		if p.ID == id {
			return p, true
		}
	}
	return nil, false
}

func computeOperation(operation string, inputs map[string]interface{}) interface{} {
	values := make([]float64, 0, len(inputs))
	for _, v := range inputs {
		if num, ok := v.(float64); ok {
			values = append(values, num)
		} else if num, ok := v.(int); ok {
			values = append(values, float64(num))
		} else if num, ok := v.(int64); ok {
			values = append(values, float64(num))
		}
	}

	if len(values) == 0 {
		return inputs
	}

	switch operation {
	case "sum", "add":
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum
	case "product", "mul":
		prod := 1.0
		for _, v := range values {
			prod *= v
		}
		return prod
	case "avg", "mean":
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum / float64(len(values))
	case "min":
		min := values[0]
		for _, v := range values[1:] {
			if v < min {
				min = v
			}
		}
		return min
	case "max":
		max := values[0]
		for _, v := range values[1:] {
			if v > max {
				max = v
			}
		}
		return max
	default:
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum
	}
}

func generateShare(value interface{}, index, numParties, threshold int) interface{} {
	switch v := value.(type) {
	case float64:
		coeffs := make([]float64, threshold)
		coeffs[0] = v
		for i := 1; i < threshold; i++ {
			randVal, _ := rand.Int(rand.Reader, big.NewInt(1000000))
			coeffs[i] = float64(randVal.Int64()) / 1000.0
		}

		result := 0.0
		for i, c := range coeffs {
			result += c * float64(powInt(index+1, i))
		}
		return result
	case []interface{}:
		result := make([]interface{}, len(v))
		for i, elem := range v {
			result[i] = generateShare(elem, index, numParties, threshold)
		}
		return result
	case map[string]interface{}:
		result := make(map[string]interface{})
		for key, elem := range v {
			result[key] = generateShare(elem, index, numParties, threshold)
		}
		return result
	default:
		return v
	}
}

func generateABY3Share(value interface{}, index int) interface{} {
	switch v := value.(type) {
	case float64:
		rand1, _ := rand.Int(rand.Reader, big.NewInt(1000000))
		share1 := float64(rand1.Int64()) / 1000.0
		share2 := v - share1
		switch index % 3 {
		case 0:
			return []float64{share1, share2}
		case 1:
			return []float64{share2, share1}
		default:
			return []float64{v - share1, v - share2}
		}
	default:
		return v
	}
}

func generatePolynomialShare(value interface{}, index, numParties, threshold int) interface{} {
	return generateShare(value, index, numParties, threshold)
}

func powInt(base, exp int) int {
	result := 1
	for i := 0; i < exp; i++ {
		result *= base
	}
	return result
}
