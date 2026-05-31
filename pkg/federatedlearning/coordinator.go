package federatedlearning

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type AggregationStrategy string

const (
	FedAvg        AggregationStrategy = "fedavg"
	WeightedAvg   AggregationStrategy = "weighted_avg"
	Median        AggregationStrategy = "median"
	TrimmedMean   AggregationStrategy = "trimmed_mean"
)

type GlobalModel struct {
	ModelID    string
	Version    int
	Weights    []float64
	UpdateTime int64
	Metadata   map[string]interface{}
}

type Client struct {
	ClientID      string
	Status        string
	LastHeartbeat int64
	CurrentTask   string
	Address       string
	Capabilities  map[string]interface{}
	SampleCount   int
}

type TaskState struct {
	TaskID        string
	ModelID       string
	Config        map[string]interface{}
	ClientIDs     []string
	Gradients     map[string]*interfaces.Gradient
	Status        string
	StartedAt     int64
	CompletedAt   int64
	RequiredClients int
}

type DefaultFederatedCoordinator struct {
	clients       map[string]*Client
	models        map[string]*GlobalModel
	tasks         map[string]*TaskState
	strategy      AggregationStrategy
	logger        *zap.Logger
	mu            sync.RWMutex
	encryptionKey string
}

func NewDefaultFederatedCoordinator(strategy AggregationStrategy) *DefaultFederatedCoordinator {
	return &DefaultFederatedCoordinator{
		clients:       make(map[string]*Client),
		models:        make(map[string]*GlobalModel),
		tasks:         make(map[string]*TaskState),
		strategy:      strategy,
		logger:        utils.GetLogger(),
		encryptionKey: utils.GenerateID("key"),
	}
}

func (c *DefaultFederatedCoordinator) RegisterClient(clientID, address string, capabilities map[string]interface{}, sampleCount int) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.clients[clientID] = &Client{
		ClientID:      clientID,
		Status:        "idle",
		LastHeartbeat: time.Now().Unix(),
		Address:       address,
		Capabilities:  capabilities,
		SampleCount:   sampleCount,
	}

	c.logger.Info("Client registered",
		zap.String("client_id", clientID),
		zap.String("address", address),
		zap.Int("sample_count", sampleCount),
	)
}

func (c *DefaultFederatedCoordinator) UpdateClientHeartbeat(clientID string) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	client, exists := c.clients[clientID]
	if !exists {
		return utils.ErrNotFound
	}

	client.LastHeartbeat = time.Now().Unix()
	return nil
}

func (c *DefaultFederatedCoordinator) GetClientStatus(ctx context.Context, clientID string) (*interfaces.ClientStatus, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	client, exists := c.clients[clientID]
	if !exists {
		return nil, utils.ErrNotFound
	}

	return &interfaces.ClientStatus{
		ClientID:      client.ClientID,
		Status:        client.Status,
		LastHeartbeat: client.LastHeartbeat,
		CurrentTask:   client.CurrentTask,
	}, nil
}

func (c *DefaultFederatedCoordinator) CreateModel(modelID string, initialWeights []float64) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.models[modelID] = &GlobalModel{
		ModelID:    modelID,
		Version:    0,
		Weights:    initialWeights,
		UpdateTime: time.Now().Unix(),
		Metadata:   make(map[string]interface{}),
	}

	c.logger.Info("Global model created",
		zap.String("model_id", modelID),
		zap.Int("weight_count", len(initialWeights)),
	)
}

func (c *DefaultFederatedCoordinator) GetModel(modelID string) (*GlobalModel, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	model, exists := c.models[modelID]
	if !exists {
		return nil, utils.ErrNotFound
	}
	return model, nil
}

func (c *DefaultFederatedCoordinator) DistributeTask(ctx context.Context, task *interfaces.FLTask) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, exists := c.tasks[task.TaskID]; exists {
		return utils.ErrAlreadyExists
	}

	taskState := &TaskState{
		TaskID:        task.TaskID,
		ModelID:       task.ModelID,
		Config:        task.Config,
		ClientIDs:     task.ClientIDs,
		Gradients:     make(map[string]*interfaces.Gradient),
		Status:        "distributed",
		StartedAt:     time.Now().Unix(),
		RequiredClients: len(task.ClientIDs),
	}

	for _, clientID := range task.ClientIDs {
		if client, exists := c.clients[clientID]; exists {
			client.Status = "training"
			client.CurrentTask = task.TaskID
		}
	}

	c.tasks[task.TaskID] = taskState

	c.logger.Info("FL task distributed",
		zap.String("task_id", task.TaskID),
		zap.String("model_id", task.ModelID),
		zap.Int("client_count", len(task.ClientIDs)),
	)

	return nil
}

func (c *DefaultFederatedCoordinator) SubmitGradient(gradient *interfaces.Gradient) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	task, exists := c.tasks[gradient.TaskID]
	if !exists {
		return utils.ErrNotFound
	}

	if !c.verifyGradientSignature(gradient) {
		return utils.ErrInvalidSignature
	}

	task.Gradients[gradient.ClientID] = gradient
	c.logger.Info("Gradient received",
		zap.String("task_id", gradient.TaskID),
		zap.String("client_id", gradient.ClientID),
		zap.Int("weight_count", len(gradient.Weights)),
	)

	if len(task.Gradients) >= task.RequiredClients {
		go c.aggregateAndUpdate(task)
	}

	return nil
}

func (c *DefaultFederatedCoordinator) verifyGradientSignature(gradient *interfaces.Gradient) bool {
	data := fmt.Sprintf("%s:%s:%v", gradient.ClientID, gradient.TaskID, gradient.Weights)
	expectedHash := utils.CalculateStringHash(data + c.encryptionKey)
	return gradient.Nonce == expectedHash[:16]
}

func (c *DefaultFederatedCoordinator) signGradient(gradient *interfaces.Gradient) {
	data := fmt.Sprintf("%s:%s:%v", gradient.ClientID, gradient.TaskID, gradient.Weights)
	hash := utils.CalculateStringHash(data + c.encryptionKey)
	gradient.Nonce = hash[:16]
}

func (c *DefaultFederatedCoordinator) AggregateGradients(ctx context.Context, gradients []*interfaces.Gradient) (*interfaces.ModelUpdate, error) {
	if len(gradients) == 0 {
		return nil, fmt.Errorf("no gradients to aggregate")
	}

	weightLen := len(gradients[0].Weights)
	for _, g := range gradients {
		if len(g.Weights) != weightLen {
			return nil, fmt.Errorf("gradient weight length mismatch")
		}
	}

	var aggregatedWeights []float64

	switch c.strategy {
	case FedAvg:
		aggregatedWeights = c.federatedAveraging(gradients, weightLen)
	case WeightedAvg:
		aggregatedWeights = c.weightedAveraging(gradients, weightLen)
	case Median:
		aggregatedWeights = c.coordinateMedian(gradients, weightLen)
	case TrimmedMean:
		aggregatedWeights = c.trimmedMean(gradients, weightLen, 0.1)
	default:
		aggregatedWeights = c.federatedAveraging(gradients, weightLen)
	}

	return &interfaces.ModelUpdate{
		Weights:    aggregatedWeights,
		UpdateTime: time.Now().Unix(),
	}, nil
}

func (c *DefaultFederatedCoordinator) federatedAveraging(gradients []*interfaces.Gradient, weightLen int) []float64 {
	result := make([]float64, weightLen)
	n := float64(len(gradients))

	for i := 0; i < weightLen; i++ {
		var sum float64
		for _, g := range gradients {
			sum += g.Weights[i]
		}
		result[i] = sum / n
	}

	return result
}

func (c *DefaultFederatedCoordinator) weightedAveraging(gradients []*interfaces.Gradient, weightLen int) []float64 {
	result := make([]float64, weightLen)
	var totalSamples int

	for _, g := range gradients {
		if client, ok := c.clients[g.ClientID]; ok {
			totalSamples += client.SampleCount
		} else {
			totalSamples++
		}
	}

	if totalSamples == 0 {
		return c.federatedAveraging(gradients, weightLen)
	}

	for i := 0; i < weightLen; i++ {
		var sum float64
		for _, g := range gradients {
			weight := 1.0
			if client, ok := c.clients[g.ClientID]; ok {
				weight = float64(client.SampleCount) / float64(totalSamples)
			}
			sum += g.Weights[i] * weight
		}
		result[i] = sum
	}

	return result
}

func (c *DefaultFederatedCoordinator) coordinateMedian(gradients []*interfaces.Gradient, weightLen int) []float64 {
	result := make([]float64, weightLen)

	for i := 0; i < weightLen; i++ {
		values := make([]float64, len(gradients))
		for j, g := range gradients {
			values[j] = g.Weights[i]
		}

		for j := 0; j < len(values)-1; j++ {
			for k := j + 1; k < len(values); k++ {
				if values[j] > values[k] {
					values[j], values[k] = values[k], values[j]
				}
			}
		}

		mid := len(values) / 2
		if len(values)%2 == 0 {
			result[i] = (values[mid-1] + values[mid]) / 2
		} else {
			result[i] = values[mid]
		}
	}

	return result
}

func (c *DefaultFederatedCoordinator) trimmedMean(gradients []*interfaces.Gradient, weightLen int, trimRatio float64) []float64 {
	result := make([]float64, weightLen)
	n := len(gradients)
	trimCount := int(math.Floor(float64(n) * trimRatio))

	for i := 0; i < weightLen; i++ {
		values := make([]float64, n)
		for j, g := range gradients {
			values[j] = g.Weights[i]
		}

		for j := 0; j < n-1; j++ {
			for k := j + 1; k < n; k++ {
				if values[j] > values[k] {
					values[j], values[k] = values[k], values[j]
				}
			}
		}

		var sum float64
		count := 0
		for j := trimCount; j < n-trimCount; j++ {
			sum += values[j]
			count++
		}
		result[i] = sum / float64(count)
	}

	return result
}

func (c *DefaultFederatedCoordinator) UpdateGlobalModel(ctx context.Context, update *interfaces.ModelUpdate) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	model, exists := c.models[update.ModelID]
	if !exists {
		return utils.ErrNotFound
	}

	model.Weights = update.Weights
	model.Version++
	model.UpdateTime = time.Now().Unix()

	c.logger.Info("Global model updated",
		zap.String("model_id", update.ModelID),
		zap.Int("new_version", model.Version),
		zap.Int("weight_count", len(update.Weights)),
	)

	return nil
}

func (c *DefaultFederatedCoordinator) aggregateAndUpdate(task *TaskState) {
	task.Status = "aggregating"

	gradients := make([]*interfaces.Gradient, 0, len(task.Gradients))
	for _, g := range task.Gradients {
		gradients = append(gradients, g)
	}

	ctx := context.Background()
	update, err := c.AggregateGradients(ctx, gradients)
	if err != nil {
		c.logger.Error("Gradient aggregation failed",
			zap.String("task_id", task.TaskID),
			zap.Error(err),
		)
		task.Status = "failed"
		return
	}

	update.ModelID = task.ModelID
	if err := c.UpdateGlobalModel(ctx, update); err != nil {
		c.logger.Error("Global model update failed",
			zap.String("task_id", task.TaskID),
			zap.Error(err),
		)
		task.Status = "failed"
		return
	}

	task.Status = "completed"
	task.CompletedAt = time.Now().Unix()

	for _, clientID := range task.ClientIDs {
		if client, exists := c.clients[clientID]; exists {
			client.Status = "idle"
			client.CurrentTask = ""
		}
	}

	c.logger.Info("FL task completed",
		zap.String("task_id", task.TaskID),
		zap.String("model_id", task.ModelID),
		zap.Int("client_count", len(task.ClientIDs)),
	)
}

func (c *DefaultFederatedCoordinator) GetTaskStatus(taskID string) (string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	task, exists := c.tasks[taskID]
	if !exists {
		return "", utils.ErrNotFound
	}

	return task.Status, nil
}

func (c *DefaultFederatedCoordinator) AddDifferentialPrivacy(weights []float64, epsilon, delta, sensitivity float64) []float64 {
	result := make([]float64, len(weights))
	sigma := sensitivity * math.Sqrt(2*math.Log(1.25/delta)) / epsilon

	for i, w := range weights {
		noise := math.NaN()
		for math.IsNaN(noise) || math.IsInf(noise, 0) {
			u1 := 0.0
			for u1 == 0 {
				u1 = randFloat()
			}
			u2 := randFloat()
			z := math.Sqrt(-2*math.Log(u1)) * math.Cos(2*math.Pi*u2)
			noise = z * sigma
		}
		result[i] = w + noise
	}

	return result
}

func randFloat() float64 {
	hash := sha256.Sum256([]byte(time.Now().String()))
	return float64(hex.EncodeToString(hash[:])[0]) / 255.0
}
