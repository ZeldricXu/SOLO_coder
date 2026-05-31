package federated

import (
	"context"
	"crypto/rand"
	"encoding/gob"
	"math/big"
	"sync"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type FLCoordinator struct {
	models     map[string]*domain.FLModel
	tasks      map[string]*domain.FLTask
	clients    map[string]bool
	gradients  map[string][][]float64
	mu         sync.RWMutex
	logger     domain.Logger
	clock      domain.Clock
	encryptor  domain.Encryptor
}

type CoordinatorConfig struct {
	Logger domain.Logger
}

func NewFLCoordinator(cfg CoordinatorConfig) *FLCoordinator {
	return &FLCoordinator{
		models:    make(map[string]*domain.FLModel),
		tasks:     make(map[string]*domain.FLTask),
		clients:   make(map[string]bool),
		gradients: make(map[string][][]float64),
		logger:    cfg.Logger,
		clock:     utils.NewRealClock(),
	}
}

func (c *FLCoordinator) RegisterClient(clientID string) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.clients[clientID] = true
	c.logger.Info("client registered", "client_id", clientID)
	return nil
}

func (c *FLCoordinator) DistributeTask(ctx context.Context, model *domain.FLModel, clientID string) (*domain.FLTask, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.clients[clientID] {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("client not registered: %s", clientID))
	}

	if _, exists := c.models[model.ID]; !exists {
		c.models[model.ID] = model
	}

	task := &domain.FLTask{
		ID:        utils.NewTaskID(),
		ModelID:   model.ID,
		ClientID:  clientID,
		Status:    "running",
		StartTime: c.clock.Now(),
	}

	c.tasks[task.ID] = task
	c.logger.Info("task distributed", "task_id", task.ID, "model_id", model.ID, "client_id", clientID)

	return task, nil
}

func (c *FLCoordinator) AggregateGradients(ctx context.Context, taskID string, gradient []float64) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	task, exists := c.tasks[taskID]
	if !exists {
		return apperr.NewNotFoundError(fmt.Sprintf("task not found: %s", taskID))
	}

	if task.Status == "completed" {
		return apperr.NewConflictError("task already completed")
	}

	encryptedGradient, err := c.encryptGradient(gradient)
	if err != nil {
		return err
	}

	task.Gradient = encryptedGradient
	task.Status = "completed"
	now := c.clock.Now()
	task.EndTime = &now

	c.gradients[task.ModelID] = append(c.gradients[task.ModelID], gradient)
	c.logger.Info("gradient aggregated", "task_id", taskID, "client_id", task.ClientID, "gradient_size", len(gradient))

	return nil
}

func (c *FLCoordinator) UpdateGlobalModel(ctx context.Context, modelID string) (*domain.FLModel, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	model, exists := c.models[modelID]
	if !exists {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("model not found: %s", modelID))
	}

	gradients, exists := c.gradients[modelID]
	if !exists || len(gradients) == 0 {
		return nil, apperr.NewValidationError("no gradients available for aggregation", "need at least one client gradient")
	}

	avgGradient := c.averageGradients(gradients)

	if len(model.Parameters) == 0 {
		model.Parameters = make([]float64, len(avgGradient))
	}

	if len(model.Parameters) != len(avgGradient) {
		return nil, apperr.NewValidationError("gradient dimension mismatch",
			fmt.Sprintf("expected %d, got %d", len(model.Parameters), len(avgGradient)))
	}

	learningRate := 0.01
	for i := range model.Parameters {
		model.Parameters[i] -= learningRate * avgGradient[i]
	}

	model.Version++
	model.Round++
	model.LastUpdated = c.clock.Now()

	delete(c.gradients, modelID)

	c.logger.Info("global model updated", "model_id", modelID, "version", model.Version, "round", model.Round)
	return model, nil
}

func (c *FLCoordinator) GetGlobalModel(ctx context.Context, modelID string) (*domain.FLModel, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	model, exists := c.models[modelID]
	if !exists {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("model not found: %s", modelID))
	}

	result := *model
	result.Parameters = make([]float64, len(model.Parameters))
	copy(result.Parameters, model.Parameters)

	return &result, nil
}

func (c *FLCoordinator) averageGradients(gradients [][]float64) []float64 {
	if len(gradients) == 0 {
		return nil
	}

	dim := len(gradients[0])
	avg := make([]float64, dim)

	for _, g := range gradients {
		for i := 0; i < dim && i < len(g); i++ {
			avg[i] += g[i]
		}
	}

	n := float64(len(gradients))
	for i := range avg {
		avg[i] /= n
	}

	return avg
}

func (c *FLCoordinator) encryptGradient(gradient []float64) ([]float64, error) {
	encrypted := make([]float64, len(gradient))
	copy(encrypted, gradient)

	for i := range encrypted {
		noise, _ := rand.Int(rand.Reader, big.NewInt(1000))
		encrypted[i] += float64(noise.Int64()) / 1000000.0
	}

	return encrypted, nil
}

func (c *FLCoordinator) CreateModel(name string, dimensions int) *domain.FLModel {
	c.mu.Lock()
	defer c.mu.Unlock()

	model := &domain.FLModel{
		ID:          utils.NewModelID(),
		Name:        name,
		Version:     1,
		Parameters:  make([]float64, dimensions),
		Round:       0,
		LastUpdated: c.clock.Now(),
	}

	c.models[model.ID] = model
	c.logger.Info("model created", "model_id", model.ID, "name", name, "dimensions", dimensions)
	return model
}

func (c *FLCoordinator) ListModels() []domain.FLModel {
	c.mu.RLock()
	defer c.mu.RUnlock()

	models := make([]domain.FLModel, 0, len(c.models))
	for _, m := range c.models {
		models = append(models, *m)
	}
	return models
}

func (c *FLCoordinator) ListTasks(modelID string) []domain.FLTask {
	c.mu.RLock()
	defer c.mu.RUnlock()

	var tasks []domain.FLTask
	for _, t := range c.tasks {
		if t.ModelID == modelID {
			tasks = append(tasks, *t)
		}
	}
	return tasks
}

func (c *FLCoordinator) ListClients() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	clients := make([]string, 0, len(c.clients))
	for c := range c.clients {
		clients = append(clients, c)
	}
	return clients
}

func (c *FLCoordinator) GetTask(taskID string) (*domain.FLTask, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	task, exists := c.tasks[taskID]
	if !exists {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("task not found: %s", taskID))
	}

	result := *task
	if task.Gradient != nil {
		result.Gradient = make([]float64, len(task.Gradient))
		copy(result.Gradient, task.Gradient)
	}

	return &result, nil
}

type AggregationStrategy string

const (
	AggregateFedAvg  AggregationStrategy = "fedavg"
	AggregateFedProx AggregationStrategy = "fedprox"
	AggregateScaffold AggregationStrategy = "scaffold"
)

func (c *FLCoordinator) SetAggregationStrategy(strategy AggregationStrategy) {
}

type FLConfig struct {
	MinClients    int
	MaxClients    int
	MaxWaitTime   time.Duration
	LearningRate  float64
	Strategy      AggregationStrategy
}

func (c *FLCoordinator) StartTrainingRound(ctx context.Context, modelID string, config FLConfig) error {
	model, err := c.GetGlobalModel(ctx, modelID)
	if err != nil {
		return err
	}

	clients := c.ListClients()
	if len(clients) < config.MinClients {
		return apperr.NewValidationError("not enough clients",
			fmt.Sprintf("need %d, have %d", config.MinClients, len(clients)))
	}

	selectedClients := clients[:min(len(clients), config.MaxClients)]
	for _, clientID := range selectedClients {
		_, err := c.DistributeTask(ctx, model, clientID)
		if err != nil {
			c.logger.Error("failed to distribute task", "client_id", clientID, "error", err)
		}
	}

	go c.waitForGradients(ctx, modelID, len(selectedClients), config.MaxWaitTime)

	return nil
}

func (c *FLCoordinator) waitForGradients(ctx context.Context, modelID string, expected int, timeout time.Duration) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	start := c.clock.Now()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			c.mu.RLock()
			collected := len(c.gradients[modelID])
			c.mu.RUnlock()

			if collected >= expected || c.clock.Now().Sub(start) > timeout {
				if collected > 0 {
					_, err := c.UpdateGlobalModel(ctx, modelID)
					if err != nil {
						c.logger.Error("failed to update global model", "error", err)
					}
				}
				return
			}
		}
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

type ClientStatus struct {
	ClientID    string
	LastSeen    time.Time
	ActiveTasks int
	TotalTasks  int
}

func (c *FLCoordinator) GetClientStatus(clientID string) (*ClientStatus, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if !c.clients[clientID] {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("client not found: %s", clientID))
	}

	active := 0
	total := 0
	for _, t := range c.tasks {
		if t.ClientID == clientID {
			total++
			if t.Status == "running" {
				active++
			}
		}
	}

	return &ClientStatus{
		ClientID:    clientID,
		LastSeen:    c.clock.Now(),
		ActiveTasks: active,
		TotalTasks:  total,
	}, nil
}

func init() {
	gob.Register(&domain.FLModel{})
	gob.Register(&domain.FLTask{})
}
