package container

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"sync"
	"time"

	"go.uber.org/zap"
)

type RuntimeMode string

const (
	RuntimeModeDocker  RuntimeMode = "docker"
	RuntimeModeProcess RuntimeMode = "process"
)

type ContainerStatus string

const (
	ContainerStatusCreated ContainerStatus = "created"
	ContainerStatusRunning ContainerStatus = "running"
	ContainerStatusStopped ContainerStatus = "stopped"
	ContainerStatusExited  ContainerStatus = "exited"
	ContainerStatusUnknown ContainerStatus = "unknown"
)

type ContainerInfo struct {
	ID          string
	Name        string
	Image       string
	Status      ContainerStatus
	Address     string
	GRPCPort    int
	HTTPPort    int
	GPUDeviceID int
	ExitCode    int
	StartedAt   time.Time
	Labels      map[string]string
}

type ContainerEvent struct {
	ContainerID   string
	ContainerName string
	EventType     string
	ExitCode      int
	Timestamp     time.Time
}

type ExitHandler func(event *ContainerEvent)

type ContainerManager interface {
	CreateContainer(ctx context.Context, modelName, version, namespace, instanceID string, gpuDeviceID int, labels map[string]string) (*ContainerInfo, error)
	StartContainer(ctx context.Context, containerID string) error
	StopContainer(ctx context.Context, containerID string, timeout time.Duration) error
	RemoveContainer(ctx context.Context, containerID string) error
	GetContainerStatus(ctx context.Context, containerID string) (ContainerStatus, error)
	ListContainers(ctx context.Context, labels map[string]string) ([]*ContainerInfo, error)
	GetContainerLogs(ctx context.Context, containerID string, tail int) (string, error)
	SetExitHandler(handler ExitHandler)
	Close() error
}

type DockerContainerManager struct {
	cfg         DockerConfig
	logger      *zap.Logger
	containers  map[string]*dockerContainerState
	containersMu sync.RWMutex
	exitHandler ExitHandler
	eventCancel context.CancelFunc
	eventWg     sync.WaitGroup
	portCounter int
}

type dockerContainerState struct {
	ID          string
	Name        string
	Image       string
	Status      ContainerStatus
	GRPCPort    int
	HTTPPort    int
	GPUDeviceID int
	Labels      map[string]string
	StartedAt   time.Time
	ExitCode    int
}

type DockerConfig struct {
	Image                string
	ModelRepositoryPath  string
	Network              string
	GRPCPortStart        int
	HTTPPortStart        int
	AutoRemove           bool
	RestartPolicy        string
	ContainerNamePrefix  string
}

func NewDockerContainerManager(cfg DockerConfig, logger *zap.Logger) (*DockerContainerManager, error) {
	if cfg.GRPCPortStart == 0 {
		cfg.GRPCPortStart = 8001
	}
	if cfg.HTTPPortStart == 0 {
		cfg.HTTPPortStart = 8000
	}
	if cfg.ContainerNamePrefix == "" {
		cfg.ContainerNamePrefix = "triton"
	}

	mgr := &DockerContainerManager{
		cfg:         cfg,
		logger:      logger,
		containers:  make(map[string]*dockerContainerState),
		portCounter: 0,
	}

	go mgr.watchEvents()

	return mgr, nil
}

func (d *DockerContainerManager) CreateContainer(ctx context.Context, modelName, version, namespace, instanceID string, gpuDeviceID int, labels map[string]string) (*ContainerInfo, error) {
	containerName := fmt.Sprintf("%s-%s-%s", d.cfg.ContainerNamePrefix, modelName, instanceID[:8])
	if labels == nil {
		labels = make(map[string]string)
	}
	labels["model-inference-platform/instance-id"] = instanceID
	labels["model-inference-platform/model-name"] = modelName
	labels["model-inference-platform/version"] = version
	labels["model-inference-platform/namespace"] = namespace
	labels["model-inference-platform"] = "true"

	d.containersMu.Lock()
	grpcPort := d.cfg.GRPCPortStart + d.portCounter
	httpPort := d.cfg.HTTPPortStart + d.portCounter
	d.portCounter++
	d.containersMu.Unlock()

	containerID := fmt.Sprintf("docker-%s", instanceID)

	state := &dockerContainerState{
		ID:          containerID,
		Name:        containerName,
		Image:       d.cfg.Image,
		Status:      ContainerStatusCreated,
		GRPCPort:    grpcPort,
		HTTPPort:    httpPort,
		GPUDeviceID: gpuDeviceID,
		Labels:      labels,
		StartedAt:   time.Now(),
	}

	d.containersMu.Lock()
	d.containers[containerID] = state
	d.containersMu.Unlock()

	return &ContainerInfo{
		ID:          containerID,
		Name:        containerName,
		Image:       d.cfg.Image,
		Status:      ContainerStatusCreated,
		Address:     fmt.Sprintf("localhost:%d", grpcPort),
		GRPCPort:    grpcPort,
		HTTPPort:    httpPort,
		GPUDeviceID: gpuDeviceID,
		Labels:      labels,
		StartedAt:   time.Now(),
	}, nil
}

func (d *DockerContainerManager) StartContainer(ctx context.Context, containerID string) error {
	d.containersMu.Lock()
	defer d.containersMu.Unlock()

	if c, ok := d.containers[containerID]; ok {
		c.Status = ContainerStatusRunning
		return nil
	}
	return fmt.Errorf("container not found: %s", containerID)
}

func (d *DockerContainerManager) StopContainer(ctx context.Context, containerID string, timeout time.Duration) error {
	d.containersMu.Lock()
	defer d.containersMu.Unlock()

	if c, ok := d.containers[containerID]; ok {
		c.Status = ContainerStatusStopped
		return nil
	}
	return fmt.Errorf("container not found: %s", containerID)
}

func (d *DockerContainerManager) RemoveContainer(ctx context.Context, containerID string) error {
	d.containersMu.Lock()
	defer d.containersMu.Unlock()

	delete(d.containers, containerID)
	return nil
}

func (d *DockerContainerManager) GetContainerStatus(ctx context.Context, containerID string) (ContainerStatus, error) {
	d.containersMu.RLock()
	defer d.containersMu.RUnlock()

	if c, ok := d.containers[containerID]; ok {
		return c.Status, nil
	}
	return ContainerStatusUnknown, fmt.Errorf("container not found: %s", containerID)
}

func (d *DockerContainerManager) ListContainers(ctx context.Context, labelFilters map[string]string) ([]*ContainerInfo, error) {
	d.containersMu.RLock()
	defer d.containersMu.RUnlock()

	var result []*ContainerInfo
	for _, c := range d.containers {
		match := true
		for k, v := range labelFilters {
			if c.Labels[k] != v {
				match = false
				break
			}
		}
		if match {
			result = append(result, &ContainerInfo{
				ID:          c.ID,
				Name:        c.Name,
				Image:       c.Image,
				Status:      c.Status,
				Address:     fmt.Sprintf("localhost:%d", c.GRPCPort),
				GRPCPort:    c.GRPCPort,
				HTTPPort:    c.HTTPPort,
				GPUDeviceID: c.GPUDeviceID,
				Labels:      c.Labels,
				StartedAt:   c.StartedAt,
			})
		}
	}
	return result, nil
}

func (d *DockerContainerManager) GetContainerLogs(ctx context.Context, containerID string, tail int) (string, error) {
	return "", fmt.Errorf("logs not implemented for mock docker mode")
}

func (d *DockerContainerManager) SetExitHandler(handler ExitHandler) {
	d.exitHandler = handler
}

func (d *DockerContainerManager) watchEvents() {
	ctx, cancel := context.WithCancel(context.Background())
	d.eventCancel = cancel

	d.eventWg.Add(1)
	defer d.eventWg.Done()

	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (d *DockerContainerManager) Close() error {
	if d.eventCancel != nil {
		d.eventCancel()
	}
	d.eventWg.Wait()
	return nil
}

type ProcessContainerManager struct {
	cfg         ProcessConfig
	logger      *zap.Logger
	processes   map[string]*ProcessInfo
	processesMu sync.RWMutex
	exitHandler ExitHandler
}

type ProcessInfo struct {
	cmd          *exec.Cmd
	containerID  string
	modelName    string
	version      string
	namespace    string
	instanceID   string
	grpcPort     int
	httpPort     int
	gpuDeviceID  int
	exitCh       chan struct{}
	terminating  bool
}

type ProcessConfig struct {
	TritonExecutable   string
	ModelRepositoryPath string
	GRPCPortStart      int
	HTTPPortStart      int
	GPUDeviceID        int
}

func NewProcessContainerManager(cfg ProcessConfig, logger *zap.Logger) *ProcessContainerManager {
	return &ProcessContainerManager{
		cfg:       cfg,
		logger:    logger,
		processes: make(map[string]*ProcessInfo),
	}
}

func (p *ProcessContainerManager) CreateContainer(ctx context.Context, modelName, version, namespace, instanceID string, gpuDeviceID int, labels map[string]string) (*ContainerInfo, error) {
	grpcPort := p.allocatePort(p.cfg.GRPCPortStart)
	httpPort := p.allocatePort(p.cfg.HTTPPortStart)

	containerID := fmt.Sprintf("process-%s", instanceID)
	containerName := fmt.Sprintf("triton-%s-%s", modelName, instanceID[:8])

	cmd := exec.CommandContext(ctx, p.cfg.TritonExecutable,
		"--model-repository="+p.cfg.ModelRepositoryPath,
		"--grpc-port="+fmt.Sprintf("%d", grpcPort),
		"--http-port="+fmt.Sprintf("%d", httpPort),
		"--model-control-mode=explicit",
		"--strict-model-config=false",
	)

	env := os.Environ()
	env = append(env, fmt.Sprintf("CUDA_VISIBLE_DEVICES=%d", gpuDeviceID))
	cmd.Env = env

	info := &ProcessInfo{
		cmd:         cmd,
		containerID: containerID,
		modelName:   modelName,
		version:     version,
		namespace:   namespace,
		instanceID:  instanceID,
		grpcPort:    grpcPort,
		httpPort:    httpPort,
		gpuDeviceID: gpuDeviceID,
		exitCh:      make(chan struct{}),
	}

	p.processesMu.Lock()
	p.processes[containerID] = info
	p.processesMu.Unlock()

	return &ContainerInfo{
		ID:          containerID,
		Name:        containerName,
		Image:       "triton-process",
		Status:      ContainerStatusCreated,
		Address:     fmt.Sprintf("localhost:%d", grpcPort),
		GRPCPort:    grpcPort,
		HTTPPort:    httpPort,
		GPUDeviceID: gpuDeviceID,
		Labels:      labels,
		StartedAt:   time.Now(),
	}, nil
}

func (p *ProcessContainerManager) StartContainer(ctx context.Context, containerID string) error {
	p.processesMu.RLock()
	info, ok := p.processes[containerID]
	p.processesMu.RUnlock()

	if !ok {
		return fmt.Errorf("container not found: %s", containerID)
	}

	if err := info.cmd.Start(); err != nil {
		return fmt.Errorf("failed to start process: %w", err)
	}

	go p.monitorProcess(info)

	return nil
}

func (p *ProcessContainerManager) StopContainer(ctx context.Context, containerID string, timeout time.Duration) error {
	p.processesMu.RLock()
	info, ok := p.processes[containerID]
	p.processesMu.RUnlock()

	if !ok {
		return nil
	}

	info.terminating = true

	if info.cmd.Process != nil {
		if err := info.cmd.Process.Signal(os.Interrupt); err != nil {
			return err
		}

		select {
		case <-info.exitCh:
			return nil
		case <-time.After(timeout):
			return info.cmd.Process.Kill()
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return nil
}

func (p *ProcessContainerManager) RemoveContainer(ctx context.Context, containerID string) error {
	p.StopContainer(ctx, containerID, 10*time.Second)

	p.processesMu.Lock()
	delete(p.processes, containerID)
	p.processesMu.Unlock()

	return nil
}

func (p *ProcessContainerManager) GetContainerStatus(ctx context.Context, containerID string) (ContainerStatus, error) {
	p.processesMu.RLock()
	info, ok := p.processes[containerID]
	p.processesMu.RUnlock()

	if !ok {
		return ContainerStatusUnknown, fmt.Errorf("container not found: %s", containerID)
	}

	select {
	case <-info.exitCh:
		return ContainerStatusExited, nil
	default:
		if info.cmd.ProcessState != nil && info.cmd.ProcessState.Exited() {
			return ContainerStatusExited, nil
		}
		if info.cmd.Process != nil {
			return ContainerStatusRunning, nil
		}
		return ContainerStatusCreated, nil
	}
}

func (p *ProcessContainerManager) ListContainers(ctx context.Context, labels map[string]string) ([]*ContainerInfo, error) {
	p.processesMu.RLock()
	defer p.processesMu.RUnlock()

	var result []*ContainerInfo
	for _, info := range p.processes {
		status := ContainerStatusCreated
		select {
		case <-info.exitCh:
			status = ContainerStatusExited
		default:
			if info.cmd.Process != nil {
				status = ContainerStatusRunning
			}
		}
		result = append(result, &ContainerInfo{
			ID:          info.containerID,
			Name:        fmt.Sprintf("triton-%s-%s", info.modelName, info.instanceID[:8]),
			Image:       "triton-process",
			Status:      status,
			Address:     fmt.Sprintf("localhost:%d", info.grpcPort),
			GRPCPort:    info.grpcPort,
			HTTPPort:    info.httpPort,
			GPUDeviceID: info.gpuDeviceID,
			StartedAt:   time.Now(),
		})
	}
	return result, nil
}

func (p *ProcessContainerManager) GetContainerLogs(ctx context.Context, containerID string, tail int) (string, error) {
	return "", fmt.Errorf("logs not implemented for process mode")
}

func (p *ProcessContainerManager) SetExitHandler(handler ExitHandler) {
	p.exitHandler = handler
}

func (p *ProcessContainerManager) monitorProcess(info *ProcessInfo) {
	err := info.cmd.Wait()

	close(info.exitCh)

	if !info.terminating && p.exitHandler != nil {
		exitCode := -1
		if info.cmd.ProcessState != nil {
			exitCode = info.cmd.ProcessState.ExitCode()
		}
		event := &ContainerEvent{
			ContainerID:   info.containerID,
			ContainerName: fmt.Sprintf("triton-%s-%s", info.modelName, info.instanceID[:8]),
			EventType:     "die",
			ExitCode:      exitCode,
			Timestamp:     time.Now(),
		}
		go p.exitHandler(event)
	}

	if err != nil && !info.terminating {
		p.logger.Warn("Process exited unexpectedly",
			zap.String("instance", info.instanceID),
			zap.String("model", info.modelName),
			zap.Error(err))
	}
}

func (p *ProcessContainerManager) allocatePort(startPort int) int {
	p.processesMu.RLock()
	defer p.processesMu.RUnlock()

	usedPorts := make(map[int]bool)
	for _, info := range p.processes {
		usedPorts[info.grpcPort] = true
		usedPorts[info.httpPort] = true
	}

	port := startPort
	for usedPorts[port] {
		port++
	}
	return port
}

func (p *ProcessContainerManager) Close() error {
	p.processesMu.Lock()
	defer p.processesMu.Unlock()

	for _, info := range p.processes {
		if info.cmd.Process != nil {
			info.cmd.Process.Kill()
		}
	}
	return nil
}

func toPtr[T any](v T) *T {
	return &v
}
