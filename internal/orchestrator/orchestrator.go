package orchestrator

import (
	"context"
	"encoding/json"
	"fmt"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/container"
	"model-inference-platform/internal/pkg/database"
	"model-inference-platform/internal/pkg/redis"
	"model-inference-platform/internal/pkg/triton"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

type InstanceStatus string

const (
	InstanceStarting  InstanceStatus = "starting"
	InstanceReady     InstanceStatus = "ready"
	InstanceUnhealthy InstanceStatus = "unhealthy"
	InstanceDraining  InstanceStatus = "draining"
	InstanceStopping  InstanceStatus = "stopping"
	InstanceStopped   InstanceStatus = "stopped"
)

type InferenceInstance struct {
	ID              string         `json:"id"`
	ModelName       string         `json:"model_name"`
	ModelID         string         `json:"model_id"`
	Version         string         `json:"version"`
	Namespace       string         `json:"namespace"`
	Address         string         `json:"address"`
	GRPCPort        int            `json:"grpc_port"`
	HTTPPort        int            `json:"http_port"`
	GPUDeviceID     int            `json:"gpu_device_id"`
	Status          InstanceStatus `json:"status"`
	CurrentLoad     int            `json:"current_load"`
	GPUMemoryMB     int64          `json:"gpu_memory_mb"`
	StartedAt       time.Time      `json:"started_at"`
	LastHeartbeat   time.Time      `json:"last_heartbeat"`
	ActiveRequests  int64          `json:"active_requests"`
	ContainerID     string         `json:"container_id"`
	ContainerName   string         `json:"container_name"`
	RuntimeMode     string         `json:"runtime_mode"`
	tritonClient    triton.TritonClient
}

type Orchestrator struct {
	cfg              config.OrchestratorConfig
	db               database.DB
	redisClient      redis.RedisClient
	containerManager container.ContainerManager
	logger           *zap.Logger

	instances   map[string]*InferenceInstance
	instancesMu sync.RWMutex

	modelQueues   map[string]int64
	queuesMu      sync.RWMutex

	scalingDecisions map[string]time.Time
	scalingMu        sync.Mutex

	stopCh     chan struct{}
	wg         sync.WaitGroup
}

func New(cfg config.OrchestratorConfig, db database.DB, redisClient redis.RedisClient,
	containerManager container.ContainerManager, logger *zap.Logger) *Orchestrator {
	o := &Orchestrator{
		cfg:              cfg,
		db:               db,
		redisClient:      redisClient,
		containerManager: containerManager,
		logger:           logger,
		instances:        make(map[string]*InferenceInstance),
		modelQueues:      make(map[string]int64),
		scalingDecisions: make(map[string]time.Time),
		stopCh:           make(chan struct{}),
	}

	if containerManager != nil {
		containerManager.SetExitHandler(o.handleContainerExit)
	}

	return o
}

func (o *Orchestrator) Start(ctx context.Context) error {
	o.wg.Add(3)
	go o.scalingLoop(ctx)
	go o.healthCheckLoop(ctx)
	go o.queueDepthMonitor(ctx)

	o.logger.Info("Orchestrator started", zap.String("runtime_mode", o.cfg.RuntimeMode))
	return nil
}

func (o *Orchestrator) Stop() {
	close(o.stopCh)
	o.wg.Wait()

	o.instancesMu.Lock()
	for _, inst := range o.instances {
		if inst.tritonClient != nil {
			inst.tritonClient.Close()
		}
	}
	o.instancesMu.Unlock()

	if o.containerManager != nil {
		o.containerManager.Close()
	}

	o.logger.Info("Orchestrator stopped")
}

func (o *Orchestrator) CreateInstance(ctx context.Context, modelName, modelID, version, namespace string, gpuMemoryMB int64) (*InferenceInstance, error) {
	instanceID := uuid.New().String()
	gpuDeviceID := o.allocateGPUDevice()

	labels := map[string]string{
		"model-inference-platform": "true",
	}

	var containerInfo *container.ContainerInfo
	var err error

	if o.containerManager != nil {
		containerInfo, err = o.containerManager.CreateContainer(ctx, modelName, version, namespace, instanceID, gpuDeviceID, labels)
		if err != nil {
			return nil, fmt.Errorf("failed to create container: %w", err)
		}

		if err := o.containerManager.StartContainer(ctx, containerInfo.ID); err != nil {
			o.containerManager.RemoveContainer(ctx, containerInfo.ID)
			return nil, fmt.Errorf("failed to start container: %w", err)
		}
	} else {
		grpcPort := o.cfg.ProcessGRPCPortStart
		httpPort := o.cfg.ProcessHTTPPortStart
		containerInfo = &container.ContainerInfo{
			ID:        fmt.Sprintf("mock-%s", instanceID),
			Name:      fmt.Sprintf("triton-%s-%s", modelName, instanceID[:8]),
			Address:   fmt.Sprintf("localhost:%d", grpcPort),
			GRPCPort:  grpcPort,
			HTTPPort:  httpPort,
			GPUDeviceID: gpuDeviceID,
			Status:    container.ContainerStatusRunning,
		}
	}

	tritonClient, err := triton.NewClient(config.TritonConfig{
		GRPCHost: "localhost",
		GRPCPort: containerInfo.GRPCPort,
	})
	if err != nil {
		if o.containerManager != nil {
			o.containerManager.RemoveContainer(ctx, containerInfo.ID)
		}
		return nil, fmt.Errorf("failed to create triton client: %w", err)
	}

	instance := &InferenceInstance{
		ID:             instanceID,
		ModelName:      modelName,
		ModelID:        modelID,
		Version:        version,
		Namespace:      namespace,
		Address:        containerInfo.Address,
		GRPCPort:       containerInfo.GRPCPort,
		HTTPPort:       containerInfo.HTTPPort,
		GPUDeviceID:    gpuDeviceID,
		Status:         InstanceStarting,
		GPUMemoryMB:    gpuMemoryMB,
		StartedAt:      time.Now(),
		LastHeartbeat:  time.Now(),
		ContainerID:    containerInfo.ID,
		ContainerName:  containerInfo.Name,
		RuntimeMode:    o.cfg.RuntimeMode,
		tritonClient:   tritonClient,
	}

	if err := o.persistInstance(ctx, instance); err != nil {
		tritonClient.Close()
		if o.containerManager != nil {
			o.containerManager.RemoveContainer(ctx, containerInfo.ID)
		}
		return nil, fmt.Errorf("failed to persist instance: %w", err)
	}

	o.instancesMu.Lock()
	o.instances[instanceID] = instance
	o.instancesMu.Unlock()

	go o.syncInstanceToRedis(ctx, instance)
	go o.warmupInstance(ctx, instance)

	return instance, nil
}

func (o *Orchestrator) warmupInstance(ctx context.Context, instance *InferenceInstance) {
	o.logger.Info("Starting model warmup",
		zap.String("instance", instance.ID),
		zap.String("model", instance.ModelName),
		zap.String("version", instance.Version))

	if err := instance.tritonClient.LoadModel(ctx, instance.ModelName, instance.Version); err != nil {
		o.logger.Error("Failed to load model", zap.Error(err))
		o.UpdateInstanceStatus(ctx, instance.ID, InstanceStopped)
		return
	}

	ready, err := instance.tritonClient.IsModelReady(ctx, instance.ModelName, instance.Version)
	if err != nil || !ready {
		o.logger.Error("Model not ready after load", zap.Error(err))
		o.UpdateInstanceStatus(ctx, instance.ID, InstanceStopped)
		return
	}

	o.logger.Info("Performing warmup inference")
	for i := 0; i < 3; i++ {
		_, err := instance.tritonClient.Infer(ctx, instance.ModelName, instance.Version,
			[]*triton.InferenceTensor{
				{
					Name:  "input",
					Shape: []int64{1, 3, 224, 224},
					DType: "FP32",
					Data:  make([]float32, 1*3*224*224),
				},
			}, []string{"output"})
		if err != nil {
			o.logger.Warn("Warmup inference failed", zap.Int("attempt", i+1), zap.Error(err))
		}
		time.Sleep(100 * time.Millisecond)
	}

	o.UpdateInstanceStatus(ctx, instance.ID, InstanceReady)
	o.syncInstanceToRedis(ctx, instance)
	o.logger.Info("Instance warmed up and ready", zap.String("instance", instance.ID))
}

func (o *Orchestrator) UpdateInstanceStatus(ctx context.Context, instanceID string, status InstanceStatus) {
	o.instancesMu.Lock()
	if inst, ok := o.instances[instanceID]; ok {
		inst.Status = status
		inst.LastHeartbeat = time.Now()
	}
	o.instancesMu.Unlock()

	query := `UPDATE inference_instances SET status = $1, last_heartbeat = $2 WHERE id = $3`
	o.db.Exec(ctx, query, string(status), time.Now(), instanceID)
}

func (o *Orchestrator) TerminateInstance(ctx context.Context, instanceID string) error {
	o.instancesMu.RLock()
	instance, ok := o.instances[instanceID]
	o.instancesMu.RUnlock()

	if !ok {
		return fmt.Errorf("instance not found")
	}

	o.UpdateInstanceStatus(ctx, instanceID, InstanceDraining)
	o.removeInstanceFromRedis(ctx, instance)

	o.logger.Info("Draining instance", zap.String("instance", instanceID))
	timeout := time.After(30 * time.Second)
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

drainLoop:
	for {
		select {
		case <-timeout:
			o.logger.Warn("Drain timeout, force stopping", zap.String("instance", instanceID))
			break drainLoop
		case <-ticker.C:
			o.instancesMu.RLock()
			active := o.instances[instanceID].ActiveRequests
			o.instancesMu.RUnlock()
			if active == 0 {
				break drainLoop
			}
		}
	}

	o.UpdateInstanceStatus(ctx, instanceID, InstanceStopping)

	if err := instance.tritonClient.UnloadModel(ctx, instance.ModelName, instance.Version); err != nil {
		o.logger.Warn("Failed to unload model", zap.Error(err))
	}

	if o.containerManager != nil {
		if err := o.containerManager.StopContainer(ctx, instance.ContainerID, 10*time.Second); err != nil {
			o.logger.Warn("Failed to stop container", zap.Error(err))
		}
		if err := o.containerManager.RemoveContainer(ctx, instance.ContainerID); err != nil {
			o.logger.Warn("Failed to remove container", zap.Error(err))
		}
	}

	instance.tritonClient.Close()

	o.UpdateInstanceStatus(ctx, instanceID, InstanceStopped)

	o.instancesMu.Lock()
	delete(o.instances, instanceID)
	o.instancesMu.Unlock()

	o.logger.Info("Instance terminated", zap.String("instance", instanceID))
	return nil
}

func (o *Orchestrator) GetReadyInstances(modelName, version string) []*InferenceInstance {
	o.instancesMu.RLock()
	defer o.instancesMu.RUnlock()

	var instances []*InferenceInstance
	for _, inst := range o.instances {
		if inst.ModelName == modelName && inst.Version == version && inst.Status == InstanceReady {
			instances = append(instances, inst)
		}
	}
	return instances
}

func (o *Orchestrator) GetAllInstances() []*InferenceInstance {
	o.instancesMu.RLock()
	defer o.instancesMu.RUnlock()

	instances := make([]*InferenceInstance, 0, len(o.instances))
	for _, inst := range o.instances {
		instances = append(instances, inst)
	}
	return instances
}

func (o *Orchestrator) scalingLoop(ctx context.Context) {
	defer o.wg.Done()

	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-o.stopCh:
			return
		case <-ticker.C:
			o.evaluateScaling(ctx)
		}
	}
}

func (o *Orchestrator) evaluateScaling(ctx context.Context) {
	modelVersions := o.getActiveModelVersions()

	for _, mv := range modelVersions {
		key := fmt.Sprintf("%s:%s", mv.Name, mv.Version)

		currentInstances := o.GetReadyInstances(mv.Name, mv.Version)
		queueDepth := o.getQueueDepth(mv.Name, mv.Version)
		avgGPUUtil := o.getAverageGPUUtil(mv.Name, mv.Version)

		o.scalingMu.Lock()
		lastDecision, hasDecision := o.scalingDecisions[key]
		o.scalingMu.Unlock()

		instanceCount := len(currentInstances)

		shouldScaleUp := queueDepth > int64(o.cfg.QueueDepthThreshold) ||
			avgGPUUtil > o.cfg.ScaleUpThreshold

		shouldScaleDown := queueDepth == 0 &&
			avgGPUUtil < o.cfg.ScaleDownThreshold &&
			instanceCount > o.cfg.MinReplicas

		if hasDecision && time.Since(lastDecision) < o.cfg.ScaleUpDelay {
			continue
		}

		if shouldScaleUp && instanceCount < o.cfg.MaxReplicas {
			o.logger.Info("Scaling up",
				zap.String("model", mv.Name),
				zap.String("version", mv.Version),
				zap.Int("current", instanceCount),
				zap.Int64("queue", queueDepth),
				zap.Float64("gpu_util", avgGPUUtil))

			o.CreateInstance(ctx, mv.Name, mv.ID, mv.Version, mv.Namespace, mv.GPUMemoryMB)

			o.scalingMu.Lock()
			o.scalingDecisions[key] = time.Now()
			o.scalingMu.Unlock()
		} else if shouldScaleDown {
			if hasDecision && time.Since(lastDecision) < o.cfg.ScaleDownDelay {
				continue
			}

			o.logger.Info("Scaling down",
				zap.String("model", mv.Name),
				zap.String("version", mv.Version),
				zap.Int("current", instanceCount))

			if len(currentInstances) > 0 {
				o.TerminateInstance(ctx, currentInstances[len(currentInstances)-1].ID)

				o.scalingMu.Lock()
				o.scalingDecisions[key] = time.Now()
				o.scalingMu.Unlock()
			}
		}
	}
}

func (o *Orchestrator) healthCheckLoop(ctx context.Context) {
	defer o.wg.Done()

	interval := o.cfg.HealthCheckInterval
	if interval <= 0 {
		interval = 3 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-o.stopCh:
			return
		case <-ticker.C:
			o.checkHealth(ctx)
		}
	}
}

func (o *Orchestrator) checkHealth(ctx context.Context) {
	o.instancesMu.RLock()
	instances := make([]*InferenceInstance, 0, len(o.instances))
	for _, inst := range o.instances {
		instances = append(instances, inst)
	}
	o.instancesMu.RUnlock()

	for _, inst := range instances {
		if inst.Status != InstanceReady {
			continue
		}

		healthy := o.performHealthCheck(ctx, inst)
		if !healthy {
			o.logger.Warn("Instance health check failed",
				zap.String("instance", inst.ID),
				zap.String("model", inst.ModelName))
			o.UpdateInstanceStatus(ctx, inst.ID, InstanceStopped)
			o.removeInstanceFromRedis(ctx, inst)
		} else {
			o.instancesMu.Lock()
			if i, ok := o.instances[inst.ID]; ok {
				i.LastHeartbeat = time.Now()
			}
			o.instancesMu.Unlock()
			o.syncInstanceToRedis(ctx, inst)
		}
	}
}

func (o *Orchestrator) performHealthCheck(ctx context.Context, inst *InferenceInstance) bool {
	if o.cfg.HealthCheckInference {
		_, err := inst.tritonClient.Infer(ctx, inst.ModelName, inst.Version,
			[]*triton.InferenceTensor{
				{
					Name:  "input",
					Shape: []int64{1, 1},
					DType: "FP32",
					Data:  make([]float32, 1),
				},
			}, []string{"output"})
		if err != nil {
			o.logger.Debug("Trivial inference health check failed",
				zap.String("instance", inst.ID),
				zap.Error(err))
			return false
		}
		return true
	}

	ready, err := inst.tritonClient.IsModelReady(ctx, inst.ModelName, inst.Version)
	if err != nil || !ready {
		return false
	}
	return true
}

func (o *Orchestrator) queueDepthMonitor(ctx context.Context) {
	defer o.wg.Done()

	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-o.stopCh:
			return
		case <-ticker.C:
			o.updateQueueDepths(ctx)
		}
	}
}

func (o *Orchestrator) updateQueueDepths(ctx context.Context) {
	keys, err := o.redisClient.SMembers(ctx, "active_models")
	if err != nil {
		return
	}

	for _, key := range keys {
		depth, err := o.redisClient.LLen(ctx, fmt.Sprintf("queue:%s", key))
		if err != nil {
			continue
		}

		o.queuesMu.Lock()
		o.modelQueues[key] = depth
		o.queuesMu.Unlock()
	}
}

func (o *Orchestrator) getQueueDepth(modelName, version string) int64 {
	key := fmt.Sprintf("%s:%s", modelName, version)
	o.queuesMu.RLock()
	defer o.queuesMu.RUnlock()
	return o.modelQueues[key]
}

func (o *Orchestrator) getAverageGPUUtil(modelName, version string) float64 {
	instances := o.GetReadyInstances(modelName, version)
	if len(instances) == 0 {
		return 0
	}

	total := 0.0
	for _, inst := range instances {
		stats, err := inst.tritonClient.GetModelStats(context.Background(), modelName, version)
		if err == nil && stats.ComputeDuration > 0 {
			total += 0.7
		} else {
			total += float64(inst.CurrentLoad) / 10.0
		}
	}
	return total / float64(len(instances))
}

type modelVersionInfo struct {
	Name      string
	ID        string
	Version   string
	Namespace string
	GPUMemoryMB int64
}

func (o *Orchestrator) getActiveModelVersions() []*modelVersionInfo {
	query := `
		SELECT DISTINCT m.name, m.id, mv.version, m.namespace, mv.gpu_memory_mb
		FROM models m
		JOIN model_versions mv ON m.id = mv.model_id
		WHERE mv.status = 'ready'
	`

	rows, err := o.db.Query(context.Background(), query)
	if err != nil {
		return nil
	}
	defer rows.Close()

	var result []*modelVersionInfo
	for rows.Next() {
		info := &modelVersionInfo{}
		rows.Scan(&info.Name, &info.ID, &info.Version, &info.Namespace, &info.GPUMemoryMB)
		result = append(result, info)
	}
	return result
}

func (o *Orchestrator) allocateGPUDevice() int {
	return 0
}

func (o *Orchestrator) persistInstance(ctx context.Context, instance *InferenceInstance) error {
	query := `
		INSERT INTO inference_instances (id, model_version_id, model_name, version, namespace,
			instance_address, gpu_device_id, status, gpu_memory_used_mb, started_at, last_heartbeat)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
	`
	_, err := o.db.Exec(ctx, query, instance.ID, instance.ModelID, instance.ModelName,
		instance.Version, instance.Namespace, instance.Address, instance.GPUDeviceID,
		string(instance.Status), instance.GPUMemoryMB, instance.StartedAt, instance.LastHeartbeat)
	return err
}

func (o *Orchestrator) UpdateInstanceLoad(instanceID string, delta int64) {
	o.instancesMu.Lock()
	defer o.instancesMu.Unlock()

	if inst, ok := o.instances[instanceID]; ok {
		inst.ActiveRequests += delta
		inst.CurrentLoad = int(inst.ActiveRequests)
	}
}

func (o *Orchestrator) MarshalInstances() ([]byte, error) {
	o.instancesMu.RLock()
	defer o.instancesMu.RUnlock()
	return json.Marshal(o.instances)
}

func (o *Orchestrator) handleContainerExit(event *container.ContainerEvent) {
	o.logger.Warn("Container exited unexpectedly",
		zap.String("container_id", event.ContainerID),
		zap.String("container_name", event.ContainerName),
		zap.Int("exit_code", event.ExitCode),
		zap.String("event_type", event.EventType))

	o.instancesMu.Lock()
	var instance *InferenceInstance
	for _, inst := range o.instances {
		if inst.ContainerID == event.ContainerID {
			instance = inst
			break
		}
	}
	o.instancesMu.Unlock()

	if instance != nil {
		if event.EventType == "restart" {
			o.logger.Info("Container restarted, waiting for readiness",
				zap.String("instance", instance.ID))
			go o.waitForInstanceReady(context.Background(), instance)
		} else {
			o.logger.Error("Container died, marking instance as stopped",
				zap.String("instance", instance.ID))
			o.UpdateInstanceStatus(context.Background(), instance.ID, InstanceStopped)
			o.removeInstanceFromRedis(context.Background(), instance)
		}
	}
}

func (o *Orchestrator) waitForInstanceReady(ctx context.Context, instance *InferenceInstance) {
	timeout := time.After(60 * time.Second)
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-timeout:
			o.logger.Error("Instance failed to become ready after restart",
				zap.String("instance", instance.ID))
			o.UpdateInstanceStatus(ctx, instance.ID, InstanceStopped)
			o.removeInstanceFromRedis(ctx, instance)
			return
		case <-ticker.C:
			ready, err := instance.tritonClient.IsModelReady(ctx, instance.ModelName, instance.Version)
			if err == nil && ready {
				o.logger.Info("Instance recovered after restart",
					zap.String("instance", instance.ID))
				o.UpdateInstanceStatus(ctx, instance.ID, InstanceReady)
				o.syncInstanceToRedis(ctx, instance)
				return
			}
		case <-ctx.Done():
			return
		}
	}
}

func (o *Orchestrator) syncInstanceToRedis(ctx context.Context, instance *InferenceInstance) {
	if instance.Status != InstanceReady {
		return
	}

	key := fmt.Sprintf("instances:%s:%s", instance.ModelName, instance.Version)
	instanceData := map[string]interface{}{
		"id":              instance.ID,
		"address":         instance.Address,
		"grpc_port":       instance.GRPCPort,
		"http_port":       instance.HTTPPort,
		"gpu_device_id":   instance.GPUDeviceID,
		"current_load":    instance.CurrentLoad,
		"active_requests": instance.ActiveRequests,
		"last_heartbeat":  instance.LastHeartbeat.Unix(),
	}

	data, err := json.Marshal(instanceData)
	if err != nil {
		o.logger.Warn("Failed to marshal instance data for Redis", zap.Error(err))
		return
	}

	if err := o.redisClient.HSet(ctx, key, instance.ID, string(data)); err != nil {
		o.logger.Warn("Failed to sync instance to Redis", zap.Error(err))
	}

	if err := o.redisClient.Expire(ctx, key, 30*time.Second); err != nil {
		o.logger.Warn("Failed to set expiration on instance key", zap.Error(err))
	}
}

func (o *Orchestrator) removeInstanceFromRedis(ctx context.Context, instance *InferenceInstance) {
	key := fmt.Sprintf("instances:%s:%s", instance.ModelName, instance.Version)
	if err := o.redisClient.HDel(ctx, key, instance.ID); err != nil {
		o.logger.Warn("Failed to remove instance from Redis", zap.Error(err))
	}
}

func (o *Orchestrator) GetInstanceTritonClient(instanceID string) triton.TritonClient {
	o.instancesMu.RLock()
	defer o.instancesMu.RUnlock()
	if inst, ok := o.instances[instanceID]; ok {
		return inst.tritonClient
	}
	return nil
}
