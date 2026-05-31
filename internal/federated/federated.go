package federated

import (
	"context"
	"crypto/rsa"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"

	"session316/internal/logger"
	"session316/internal/models"
	apperrors "session316/pkg/errors"
	"session316/pkg/utils"
)

const (
	DefaultMaxRetries     = 3
	DefaultCheckpointInterval = 5 * time.Minute
	DefaultAggregationThreshold = 0.8
)

type TrainingTask struct {
	TaskID       string                 `json:"task_id"`
	ModelID      string                 `json:"model_id"`
	Round        int                    `json:"round"`
	Config       map[string]interface{} `json:"config"`
	Status       string                 `json:"status"`
	Clients      []string               `json:"clients"`
	RequiredClients int                 `json:"required_clients"`
	Gradients    map[string]*Gradient   `json:"-"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
	CompletedAt  *time.Time             `json:"completed_at,omitempty"`
	Error        string                 `json:"error,omitempty"`
}

type Gradient struct {
	TaskID       string    `json:"task_id"`
	ClientID     string    `json:"client_id"`
	Round        int       `json:"round"`
	EncryptedData string   `json:"encrypted_data"`
	DataHash     string    `json:"data_hash"`
	Nonce        []byte    `json:"nonce"`
	Weight       float64   `json:"weight"`
	SubmittedAt  time.Time `json:"submitted_at"`
	Verified     bool      `json:"verified"`
}

type GlobalModel struct {
	ModelID     string                 `json:"model_id"`
	Version     int                    `json:"version"`
	Parameters  map[string]interface{} `json:"parameters"`
	UpdatedAt   time.Time              `json:"updated_at"`
	Checkpoint  string                 `json:"checkpoint,omitempty"`
}

type ClientInfo struct {
	ClientID    string    `json:"client_id"`
	PublicKey   string    `json:"public_key"`
	Status      string    `json:"status"`
	LastSeen    time.Time `json:"last_seen"`
	Capacity    int       `json:"capacity"`
	SuccessCount int      `json:"success_count"`
	FailureCount int      `json:"failure_count"`
}

type Checkpoint struct {
	TaskID     string                 `json:"task_id"`
	Round      int                    `json:"round"`
	ModelState map[string]interface{} `json:"model_state"`
	Gradients  map[string]*Gradient   `json:"gradients"`
	Timestamp  time.Time              `json:"timestamp"`
}

type FederatedManager struct {
	mu              sync.RWMutex
	tasks           map[string]*TrainingTask
	clients         map[string]*ClientInfo
	globalModels    map[string]*GlobalModel
	checkpoints     map[string][]*Checkpoint
	encryptionKey   *rsa.PrivateKey
	aggregationKey  []byte
	maxRetries      int
	checkpointInterval time.Duration
	aggregationThreshold float64
	recoveryCh      chan string
	stopCh          chan struct{}
	wg              sync.WaitGroup
}

func NewFederatedManager() (*FederatedManager, error) {
	privKey, _, err := utils.GenerateRSAKeyPair()
	if err != nil {
		logger.Error("failed to generate RSA key pair", zap.Error(err))
		return nil, apperrors.Wrap(err, apperrors.ErrCodeEncryption, "生成加密密钥对失败")
	}

	aggKey := utils.GenerateAESKey()

	fm := &FederatedManager{
		tasks:              make(map[string]*TrainingTask),
		clients:            make(map[string]*ClientInfo),
		globalModels:       make(map[string]*GlobalModel),
		checkpoints:        make(map[string][]*Checkpoint),
		encryptionKey:      privKey,
		aggregationKey:     aggKey,
		maxRetries:         DefaultMaxRetries,
		checkpointInterval: DefaultCheckpointInterval,
		aggregationThreshold: DefaultAggregationThreshold,
		recoveryCh:         make(chan string, 100),
		stopCh:             make(chan struct{}),
	}

	fm.wg.Add(1)
	go fm.recoveryLoop()

	fm.wg.Add(1)
	go fm.checkpointLoop()

	logger.Info("federated manager initialized successfully")
	return fm, nil
}

func (fm *FederatedManager) Close() {
	close(fm.stopCh)
	fm.wg.Wait()
	logger.Info("federated manager closed")
}

func (fm *FederatedManager) CreateTrainingTask(modelID string, config map[string]interface{}, requiredClients int) (*TrainingTask, error) {
	if modelID == "" {
		return nil, apperrors.ValidationError("model_id", "不能为空")
	}
	if requiredClients <= 0 {
		return nil, apperrors.ValidationError("required_clients", "必须大于0")
	}

	fm.mu.Lock()
	defer fm.mu.Unlock()

	taskID := utils.GenerateID("task")
	now := time.Now()

	task := &TrainingTask{
		TaskID:         taskID,
		ModelID:        modelID,
		Round:          1,
		Config:         config,
		Status:         models.StatusPending,
		RequiredClients: requiredClients,
		Gradients:      make(map[string]*Gradient),
		CreatedAt:      now,
		UpdatedAt:      now,
	}

	fm.tasks[taskID] = task

	logger.Info("training task created",
		zap.String("task_id", taskID),
		zap.String("model_id", modelID),
		zap.Int("required_clients", requiredClients),
	)

	return task, nil
}

func (fm *FederatedManager) RegisterClient(clientID, publicKeyStr string, capacity int) (*ClientInfo, error) {
	if clientID == "" {
		return nil, apperrors.ValidationError("client_id", "不能为空")
	}
	if publicKeyStr == "" {
		return nil, apperrors.ValidationError("public_key", "不能为空")
	}

	if _, err := utils.DecodePublicKey(publicKeyStr); err != nil {
		return nil, apperrors.Wrap(err, apperrors.ErrCodeValidation, "公钥格式无效")
	}

	fm.mu.Lock()
	defer fm.mu.Unlock()

	client := &ClientInfo{
		ClientID:   clientID,
		PublicKey:  publicKeyStr,
		Status:     models.StatusActive,
		LastSeen:   time.Now(),
		Capacity:   capacity,
	}

	fm.clients[clientID] = client

	logger.Info("client registered",
		zap.String("client_id", clientID),
		zap.Int("capacity", capacity),
	)

	return client, nil
}

func (fm *FederatedManager) DistributeTask(taskID string) ([]*TrainingTask, error) {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	task, exists := fm.tasks[taskID]
	if !exists {
		return nil, apperrors.NotFoundError("training_task", taskID)
	}

	if task.Status != models.StatusPending {
		return nil, apperrors.NewWithDetails(apperrors.ErrCodeConflict,
			"任务状态不允许分发",
			fmt.Sprintf("当前状态: %s", task.Status),
		)
	}

	activeClients := fm.getActiveClients(task.RequiredClients)
	if len(activeClients) < task.RequiredClients {
		return nil, apperrors.NewWithDetails(apperrors.ErrCodeResourceExhausted,
			"可用客户端数量不足",
			fmt.Sprintf("需要: %d, 可用: %d", task.RequiredClients, len(activeClients)),
		)
	}

	task.Clients = make([]string, 0, len(activeClients))
	for _, client := range activeClients {
		task.Clients = append(task.Clients, client.ClientID)
		client.LastSeen = time.Now()
	}

	task.Status = models.StatusRunning
	task.UpdatedAt = time.Now()

	clientTasks := make([]*TrainingTask, 0, len(activeClients))
	for _, clientID := range task.Clients {
		clientTask := &TrainingTask{
			TaskID:       task.TaskID,
			ModelID:      task.ModelID,
			Round:        task.Round,
			Config:       task.Config,
			Status:       models.StatusRunning,
			Clients:      []string{clientID},
			RequiredClients: 1,
			CreatedAt:    task.CreatedAt,
			UpdatedAt:    time.Now(),
		}
		clientTasks = append(clientTasks, clientTask)
	}

	logger.Info("task distributed",
		zap.String("task_id", taskID),
		zap.Int("client_count", len(task.Clients)),
		zap.Int("round", task.Round),
	)

	return clientTasks, nil
}

func (fm *FederatedManager) SubmitGradient(taskID, clientID string, gradientData []byte, weight float64) (*Gradient, error) {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	task, exists := fm.tasks[taskID]
	if !exists {
		return nil, apperrors.NotFoundError("training_task", taskID)
	}

	client, exists := fm.clients[clientID]
	if !exists {
		return nil, apperrors.NotFoundError("client", clientID)
	}

	if task.Status != models.StatusRunning {
		return nil, apperrors.NewWithDetails(apperrors.ErrCodeConflict,
			"任务不在运行状态",
			fmt.Sprintf("当前状态: %s", task.Status),
		)
	}

	clientFound := false
	for _, c := range task.Clients {
		if c == clientID {
			clientFound = true
			break
		}
	}
	if !clientFound {
		return nil, apperrors.NewWithDetails(apperrors.ErrCodeForbidden,
			"客户端未被分配此任务",
			fmt.Sprintf("client_id: %s, task_id: %s", clientID, taskID),
		)
	}

	encryptedData, err := utils.AESEncrypt(gradientData, fm.aggregationKey)
	if err != nil {
		logger.Error("failed to encrypt gradient",
			zap.String("task_id", taskID),
			zap.String("client_id", clientID),
			zap.Error(err),
		)
		return nil, apperrors.Wrap(err, apperrors.ErrCodeEncryption, "梯度加密失败")
	}

	dataHash := utils.HashSHA256(gradientData)
	nonce := utils.GenerateNonce(16)

	gradient := &Gradient{
		TaskID:        taskID,
		ClientID:      clientID,
		Round:         task.Round,
		EncryptedData: encryptedData,
		DataHash:      dataHash,
		Nonce:         nonce,
		Weight:        weight,
		SubmittedAt:   time.Now(),
		Verified:      true,
	}

	task.Gradients[clientID] = gradient
	task.UpdatedAt = time.Now()
	client.LastSeen = time.Now()
	client.SuccessCount++

	logger.Info("gradient submitted",
		zap.String("task_id", taskID),
		zap.String("client_id", clientID),
		zap.Int("round", task.Round),
		zap.Float64("weight", weight),
	)

	return gradient, nil
}

func (fm *FederatedManager) AggregateGradients(taskID string) (map[string]interface{}, error) {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	task, exists := fm.tasks[taskID]
	if !exists {
		return nil, apperrors.NotFoundError("training_task", taskID)
	}

	receivedCount := len(task.Gradients)
	requiredCount := task.RequiredClients
	threshold := int(float64(requiredCount) * fm.aggregationThreshold)

	if receivedCount < threshold {
		return nil, apperrors.NewWithDetails(apperrors.ErrCodeResourceExhausted,
			"梯度数量未达到聚合阈值",
			fmt.Sprintf("已接收: %d, 需要: %d, 阈值: %d", receivedCount, requiredCount, threshold),
		)
	}

	aggregatedParams := make(map[string]interface{})
	totalWeight := 0.0

	for _, gradient := range task.Gradients {
		decryptedData, err := utils.AESDecrypt(gradient.EncryptedData, fm.aggregationKey)
		if err != nil {
			logger.Error("failed to decrypt gradient",
				zap.String("task_id", taskID),
				zap.String("client_id", gradient.ClientID),
				zap.Error(err),
			)
			continue
		}

		computedHash := utils.HashSHA256(decryptedData)
		if computedHash != gradient.DataHash {
			logger.Warn("gradient hash verification failed, skipping",
				zap.String("task_id", taskID),
				zap.String("client_id", gradient.ClientID),
			)
			continue
		}

		var clientParams map[string]interface{}
		if err := json.Unmarshal(decryptedData, &clientParams); err != nil {
			logger.Error("failed to unmarshal gradient data",
				zap.String("task_id", taskID),
				zap.String("client_id", gradient.ClientID),
				zap.Error(err),
			)
			continue
		}

		weight := gradient.Weight
		totalWeight += weight

		for key, value := range clientParams {
			if existing, ok := aggregatedParams[key]; ok {
				aggregatedParams[key] = addWeightedValues(existing, value, weight)
			} else {
				aggregatedParams[key] = multiplyValue(value, weight)
			}
		}
	}

	if totalWeight > 0 {
		for key, value := range aggregatedParams {
			aggregatedParams[key] = divideValue(value, totalWeight)
		}
	}

	logger.Info("gradients aggregated successfully",
		zap.String("task_id", taskID),
		zap.Int("received_gradients", receivedCount),
		zap.Int("round", task.Round),
	)

	return aggregatedParams, nil
}

func (fm *FederatedManager) UpdateGlobalModel(modelID string, aggregatedParams map[string]interface{}) (*GlobalModel, error) {
	if modelID == "" {
		return nil, apperrors.ValidationError("model_id", "不能为空")
	}
	if len(aggregatedParams) == 0 {
		return nil, apperrors.ValidationError("aggregated_params", "不能为空")
	}

	fm.mu.Lock()
	defer fm.mu.Unlock()

	model, exists := fm.globalModels[modelID]
	if !exists {
		model = &GlobalModel{
			ModelID:    modelID,
			Version:    0,
			Parameters: make(map[string]interface{}),
			UpdatedAt:  time.Now(),
		}
		fm.globalModels[modelID] = model
	}

	model.Version++
	model.Parameters = aggregatedParams
	model.UpdatedAt = time.Now()

	checkpointData, err := json.Marshal(model)
	if err != nil {
		logger.Warn("failed to marshal model for checkpoint",
			zap.String("model_id", modelID),
			zap.Error(err),
		)
	} else {
		model.Checkpoint = utils.HashSHA256(checkpointData)
	}

	logger.Info("global model updated",
		zap.String("model_id", modelID),
		zap.Int("version", model.Version),
		zap.Int("param_count", len(aggregatedParams)),
	)

	return model, nil
}

func (fm *FederatedManager) CompleteTask(taskID string) (*TrainingTask, error) {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	task, exists := fm.tasks[taskID]
	if !exists {
		return nil, apperrors.NotFoundError("training_task", taskID)
	}

	now := time.Now()
	task.Status = models.StatusCompleted
	task.UpdatedAt = now
	task.CompletedAt = &now

	logger.Info("training task completed",
		zap.String("task_id", taskID),
		zap.String("model_id", task.ModelID),
		zap.Int("round", task.Round),
	)

	return task, nil
}

func (fm *FederatedManager) FailTask(taskID, errMsg string) (*TrainingTask, error) {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	task, exists := fm.tasks[taskID]
	if !exists {
		return nil, apperrors.NotFoundError("training_task", taskID)
	}

	task.Status = models.StatusFailed
	task.Error = errMsg
	task.UpdatedAt = time.Now()

	client, exists := fm.clients[taskID]
	if exists {
		client.FailureCount++
	}

	logger.Error("training task failed",
		zap.String("task_id", taskID),
		zap.String("error", errMsg),
	)

	return task, nil
}

func (fm *FederatedManager) saveCheckpoint(taskID string) {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	task, exists := fm.tasks[taskID]
	if !exists {
		return
	}

	model, modelExists := fm.globalModels[task.ModelID]
	var modelState map[string]interface{}
	if modelExists {
		modelState = model.Parameters
	}

	gradientsCopy := make(map[string]*Gradient, len(task.Gradients))
	for k, v := range task.Gradients {
		g := *v
		gradientsCopy[k] = &g
	}

	checkpoint := &Checkpoint{
		TaskID:     taskID,
		Round:      task.Round,
		ModelState: modelState,
		Gradients:  gradientsCopy,
		Timestamp:  time.Now(),
	}

	fm.checkpoints[taskID] = append(fm.checkpoints[taskID], checkpoint)

	if len(fm.checkpoints[taskID]) > 10 {
		fm.checkpoints[taskID] = fm.checkpoints[taskID][1:]
	}

	logger.Debug("checkpoint saved",
		zap.String("task_id", taskID),
		zap.Int("round", task.Round),
	)
}

func (fm *FederatedManager) RecoverTask(taskID string) (*TrainingTask, error) {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	checkpoints, exists := fm.checkpoints[taskID]
	if !exists || len(checkpoints) == 0 {
		return nil, apperrors.NewWithDetails(apperrors.ErrCodeNotFound,
			"任务无可用检查点",
			fmt.Sprintf("task_id: %s", taskID),
		)
	}

	latest := checkpoints[len(checkpoints)-1]

	task, taskExists := fm.tasks[taskID]
	if !taskExists {
		task = &TrainingTask{
			TaskID:    taskID,
			Status:    models.StatusPending,
			Gradients: make(map[string]*Gradient),
			CreatedAt: latest.Timestamp,
		}
		fm.tasks[taskID] = task
	}

	task.Round = latest.Round
	task.Status = models.StatusPending
	task.Gradients = latest.Gradients
	task.UpdatedAt = time.Now()

	if latest.ModelState != nil {
		model, modelExists := fm.globalModels[task.ModelID]
		if !modelExists {
			model = &GlobalModel{
				ModelID:    task.ModelID,
				Parameters: make(map[string]interface{}),
			}
			fm.globalModels[task.ModelID] = model
		}
		model.Parameters = latest.ModelState
		model.UpdatedAt = time.Now()
	}

	logger.Info("task recovered from checkpoint",
		zap.String("task_id", taskID),
		zap.Int("recovered_round", latest.Round),
	)

	return task, nil
}

func (fm *FederatedManager) GetTask(taskID string) (*TrainingTask, error) {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	task, exists := fm.tasks[taskID]
	if !exists {
		return nil, apperrors.NotFoundError("training_task", taskID)
	}
	return task, nil
}

func (fm *FederatedManager) GetGlobalModel(modelID string) (*GlobalModel, error) {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	model, exists := fm.globalModels[modelID]
	if !exists {
		return nil, apperrors.NotFoundError("global_model", modelID)
	}
	return model, nil
}

func (fm *FederatedManager) getActiveClients(maxCount int) []*ClientInfo {
	active := make([]*ClientInfo, 0, maxCount)
	now := time.Now()

	for _, client := range fm.clients {
		if client.Status == models.StatusActive &&
			now.Sub(client.LastSeen) < 30*time.Minute &&
			len(active) < maxCount {
			active = append(active, client)
		}
	}

	return active
}

func (fm *FederatedManager) recoveryLoop() {
	defer fm.wg.Done()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		select {
		case <-fm.stopCh:
			cancel()
		case <-ctx.Done():
		}
	}()

	for {
		select {
		case <-fm.stopCh:
			logger.Info("recovery loop stopped")
			return
		case taskID := <-fm.recoveryCh:
			for i := 0; i < fm.maxRetries; i++ {
				_, err := fm.RecoverTask(taskID)
				if err == nil {
					logger.Info("task auto-recovered successfully",
						zap.String("task_id", taskID),
						zap.Int("attempt", i+1),
					)
					break
				}
				if i == fm.maxRetries-1 {
					logger.Error("task auto-recovery failed after max retries",
						zap.String("task_id", taskID),
						zap.Error(err),
					)
					_, _ = fm.FailTask(taskID, err.Error())
				}
				select {
				case <-fm.stopCh:
					return
				case <-time.After(time.Duration(i+1) * 5 * time.Second):
				}
			}
		}
	}
}

func (fm *FederatedManager) checkpointLoop() {
	defer fm.wg.Done()

	ticker := time.NewTicker(fm.checkpointInterval)
	defer ticker.Stop()

	for {
		select {
		case <-fm.stopCh:
			logger.Info("checkpoint loop stopped")
			return
		case <-ticker.C:
			fm.mu.RLock()
			for taskID, task := range fm.tasks {
				if task.Status == models.StatusRunning {
					fm.saveCheckpoint(taskID)
				}
			}
			fm.mu.RUnlock()
		}
	}
}

func (fm *FederatedManager) TriggerRecovery(taskID string) {
	select {
	case fm.recoveryCh <- taskID:
		logger.Info("recovery triggered for task", zap.String("task_id", taskID))
	default:
		logger.Warn("recovery channel full, dropping recovery request",
			zap.String("task_id", taskID),
		)
	}
}

func addWeightedValues(a, b interface{}, weight float64) interface{} {
	switch av := a.(type) {
	case float64:
		if bv, ok := b.(float64); ok {
			return av + bv*weight
		}
	case []float64:
		if bv, ok := b.([]float64); ok && len(av) == len(bv) {
			result := make([]float64, len(av))
			for i := range av {
				result[i] = av[i] + bv[i]*weight
			}
			return result
		}
	case map[string]interface{}:
		if bv, ok := b.(map[string]interface{}); ok {
			result := make(map[string]interface{})
			for k, v := range av {
				result[k] = v
			}
			for k, v := range bv {
				if existing, ok := result[k]; ok {
					result[k] = addWeightedValues(existing, v, weight)
				} else {
					result[k] = multiplyValue(v, weight)
				}
			}
			return result
		}
	}
	return a
}

func multiplyValue(v interface{}, weight float64) interface{} {
	switch val := v.(type) {
	case float64:
		return val * weight
	case []float64:
		result := make([]float64, len(val))
		for i, x := range val {
			result[i] = x * weight
		}
		return result
	case map[string]interface{}:
		result := make(map[string]interface{})
		for k, x := range val {
			result[k] = multiplyValue(x, weight)
		}
		return result
	}
	return v
}

func divideValue(v interface{}, divisor float64) interface{} {
	if divisor == 0 {
		return v
	}
	switch val := v.(type) {
	case float64:
		return val / divisor
	case []float64:
		result := make([]float64, len(val))
		for i, x := range val {
			result[i] = x / divisor
		}
		return result
	case map[string]interface{}:
		result := make(map[string]interface{})
		for k, x := range val {
			result[k] = divideValue(x, divisor)
		}
		return result
	}
	return v
}

func (fm *FederatedManager) GetTaskStatus(taskID string) (string, error) {
	task, err := fm.GetTask(taskID)
	if err != nil {
		return "", err
	}
	return task.Status, nil
}

func (fm *FederatedManager) ListTasks(status string) []*TrainingTask {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	result := make([]*TrainingTask, 0)
	for _, task := range fm.tasks {
		if status == "" || task.Status == status {
			result = append(result, task)
		}
	}
	return result
}

func (fm *FederatedManager) Heartbeat(clientID string) error {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	client, exists := fm.clients[clientID]
	if !exists {
		return apperrors.NotFoundError("client", clientID)
	}

	client.LastSeen = time.Now()
	client.Status = models.StatusActive
	return nil
}
