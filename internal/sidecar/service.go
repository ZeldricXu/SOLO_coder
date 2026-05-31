package sidecar

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/abstraction"
	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type SidecarLifecycleService struct {
	instances map[string]*common.SidecarInstance
	policies  map[string]*common.InjectionPolicy
	mu        sync.RWMutex
}

func NewSidecarLifecycleService() abstraction.SidecarLifecycleManager {
	return &SidecarLifecycleService{
		instances: make(map[string]*common.SidecarInstance),
		policies:  make(map[string]*common.InjectionPolicy),
	}
}

func (s *SidecarLifecycleService) InjectSidecar(ctx context.Context, target *common.InjectionTarget, cfg *common.SidecarConfig) (*common.SidecarInstance, error) {
	if target == nil {
		return nil, common.NewBadRequestError("injection target cannot be nil")
	}
	if target.Name == "" || target.Namespace == "" {
		return nil, common.NewValidationError("target name and namespace are required", "target")
	}
	if cfg == nil {
		return nil, common.NewBadRequestError("sidecar config cannot be nil")
	}
	if cfg.Image == "" {
		return nil, common.NewValidationError("sidecar image is required", "image")
	}

	instanceID := fmt.Sprintf("sc_%d", time.Now().UnixNano())

	limits := &common.ResourceLimits{
		CPURequest:    "100m",
		CPULimit:      "500m",
		MemoryRequest: "128Mi",
		MemoryLimit:   "512Mi",
	}

	policy := s.getPolicyForTarget(target)
	if policy != nil && policy.DefaultLimits != nil {
		limits = policy.DefaultLimits
	}

	instance := &common.SidecarInstance{
		InstanceID:  instanceID,
		Target:      target.Name,
		Namespace:   target.Namespace,
		Config:      cfg,
		Limits:      limits,
		Status:      "injecting",
		PodName:     target.Name,
		ContainerID: fmt.Sprintf("container-%s", instanceID),
		InjectedAt:  time.Now(),
		UpdatedAt:   time.Now(),
	}

	go s.simulateInjection(instance)

	s.mu.Lock()
	s.instances[instanceID] = instance
	s.mu.Unlock()

	common.Info("sidecar injection started",
		zap.String("instance_id", instanceID),
		zap.String("target", target.Name),
		zap.String("namespace", target.Namespace),
		zap.String("image", cfg.Image),
	)

	return instance, nil
}

func (s *SidecarLifecycleService) simulateInjection(instance *common.SidecarInstance) {
	time.Sleep(2 * time.Second)

	s.mu.Lock()
	instance.Status = "running"
	instance.UpdatedAt = time.Now()
	s.mu.Unlock()

	common.Info("sidecar injection completed",
		zap.String("instance_id", instance.InstanceID),
		zap.String("target", instance.Target),
	)
}

func (s *SidecarLifecycleService) EjectSidecar(ctx context.Context, instanceID string) error {
	if instanceID == "" {
		return common.NewValidationError("instance_id is required", "instance_id")
	}

	s.mu.Lock()
	instance, exists := s.instances[instanceID]
	if !exists {
		s.mu.Unlock()
		return common.NewNotFoundError(fmt.Sprintf("sidecar instance %s not found", instanceID))
	}

	instance.Status = "ejecting"
	instance.UpdatedAt = time.Now()
	s.mu.Unlock()

	go func() {
		time.Sleep(1 * time.Second)
		s.mu.Lock()
		delete(s.instances, instanceID)
		s.mu.Unlock()
		common.Info("sidecar ejected",
			zap.String("instance_id", instanceID),
			zap.String("target", instance.Target),
		)
	}()

	return nil
}

func (s *SidecarLifecycleService) HotUpdateConfig(ctx context.Context, instanceID string, newConfig *common.SidecarConfig) error {
	if instanceID == "" {
		return common.NewValidationError("instance_id is required", "instance_id")
	}
	if newConfig == nil {
		return common.NewBadRequestError("new config cannot be nil")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	instance, exists := s.instances[instanceID]
	if !exists {
		return common.NewNotFoundError(fmt.Sprintf("sidecar instance %s not found", instanceID))
	}

	oldConfigJSON, _ := json.Marshal(instance.Config)
	newConfigJSON, _ := json.Marshal(newConfig)

	instance.Config = newConfig
	instance.UpdatedAt = time.Now()

	common.Info("sidecar config hot updated",
		zap.String("instance_id", instanceID),
		zap.String("old_config", string(oldConfigJSON)),
		zap.String("new_config", string(newConfigJSON)),
	)

	return nil
}

func (s *SidecarLifecycleService) SetResourceLimits(ctx context.Context, instanceID string, limits *common.ResourceLimits) error {
	if instanceID == "" {
		return common.NewValidationError("instance_id is required", "instance_id")
	}
	if limits == nil {
		return common.NewBadRequestError("limits cannot be nil")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	instance, exists := s.instances[instanceID]
	if !exists {
		return common.NewNotFoundError(fmt.Sprintf("sidecar instance %s not found", instanceID))
	}

	instance.Limits = limits
	instance.UpdatedAt = time.Now()

	common.Info("sidecar resource limits updated",
		zap.String("instance_id", instanceID),
		zap.String("cpu_limit", limits.CPULimit),
		zap.String("memory_limit", limits.MemoryLimit),
	)

	return nil
}

func (s *SidecarLifecycleService) GetSidecarStatus(ctx context.Context, instanceID string) (*common.SidecarInstance, error) {
	if instanceID == "" {
		return nil, common.NewValidationError("instance_id is required", "instance_id")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	instance, exists := s.instances[instanceID]
	if !exists {
		return nil, common.NewNotFoundError(fmt.Sprintf("sidecar instance %s not found", instanceID))
	}
	return instance, nil
}

func (s *SidecarLifecycleService) SetInjectionPolicy(ctx context.Context, policy *common.InjectionPolicy) error {
	if policy == nil {
		return common.NewBadRequestError("policy cannot be nil")
	}
	if policy.Namespace == "" {
		return common.NewValidationError("namespace is required", "namespace")
	}

	s.mu.Lock()
	s.policies[policy.Namespace] = policy
	s.mu.Unlock()

	common.Info("sidecar injection policy set",
		zap.String("namespace", policy.Namespace),
		zap.Bool("auto_inject", policy.AutoInject),
	)

	return nil
}

func (s *SidecarLifecycleService) ListSidecars(ctx context.Context, namespace string) ([]*common.SidecarInstance, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	list := make([]*common.SidecarInstance, 0)
	for _, inst := range s.instances {
		if namespace == "" || inst.Namespace == namespace {
			list = append(list, inst)
		}
	}
	return list, nil
}

func (s *SidecarLifecycleService) getPolicyForTarget(target *common.InjectionTarget) *common.InjectionPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()

	policy, exists := s.policies[target.Namespace]
	if !exists {
		return nil
	}

	if len(policy.Selector) == 0 {
		return policy
	}

	for k, v := range policy.Selector {
		if target.Labels[k] != v {
			return nil
		}
	}

	return policy
}

func (s *SidecarLifecycleService) GetInjectionPolicy(ctx context.Context, namespace string) (*common.InjectionPolicy, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	policy, exists := s.policies[namespace]
	if !exists {
		return nil, common.NewNotFoundError(fmt.Sprintf("no policy for namespace %s", namespace))
	}
	return policy, nil
}
