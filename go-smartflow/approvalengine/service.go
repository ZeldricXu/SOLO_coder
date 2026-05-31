package approvalengine

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
)

var (
	ErrProcessNotFound      = errors.New("process not found")
	ErrInstanceNotFound     = errors.New("approval instance not found")
	ErrInvalidApprover      = errors.New("invalid approver")
	ErrAlreadyProcessed     = errors.New("node already processed")
	ErrCircuitBreakerOpen   = errors.New("circuit breaker is open")
	ErrTimeout              = errors.New("operation timeout")
	ErrNoApprovers          = errors.New("no approvers configured")
)

type ApprovalEngineService struct {
	processRepo    ProcessRepository
	instanceRepo   InstanceRepository
	notification   NotificationService
	evaluator      *ConditionEvaluator
	config         EngineConfig

	circuitBreakerCount int32
	circuitBreakerOpen  int32
	lastFailureTime     time.Time
	mu                  sync.RWMutex
}

func NewApprovalEngineService(
	processRepo ProcessRepository,
	instanceRepo InstanceRepository,
	notification NotificationService,
	config EngineConfig,
) *ApprovalEngineService {
	if config.TimeoutMs == 0 {
		config.TimeoutMs = 3000
	}
	if config.CircuitBreakerThreshold == 0 {
		config.CircuitBreakerThreshold = 5
	}

	return &ApprovalEngineService{
		processRepo:  processRepo,
		instanceRepo: instanceRepo,
		notification: notification,
		evaluator:    NewConditionEvaluator(),
		config:       config,
	}
}

func (s *ApprovalEngineService) StartProcess(ctx context.Context, request *ApprovalRequest) (*ApprovalResult, error) {
	if request == nil {
		return nil, errors.New("request cannot be nil")
	}

	if atomic.LoadInt32(&s.circuitBreakerOpen) == 1 {
		if time.Since(s.lastFailureTime) > 30*time.Second {
			atomic.StoreInt32(&s.circuitBreakerOpen, 0)
			atomic.StoreInt32(&s.circuitBreakerCount, 0)
		} else {
			return nil, ErrCircuitBreakerOpen
		}
	}

	ctx, cancel := context.WithTimeout(ctx, time.Duration(s.config.TimeoutMs)*time.Millisecond)
	defer cancel()

	resultChan := make(chan *ApprovalResult, 1)
	errChan := make(chan error, 1)

	go func() {
		result, err := s.doStartProcess(ctx, request)
		if err != nil {
			s.recordFailure()
			errChan <- err
			return
		}
		atomic.StoreInt32(&s.circuitBreakerCount, 0)
		resultChan <- result
	}()

	select {
	case <-ctx.Done():
		s.recordFailure()
		return nil, ErrTimeout
	case err := <-errChan:
		return nil, err
	case result := <-resultChan:
		return result, nil
	}
}

func (s *ApprovalEngineService) doStartProcess(ctx context.Context, request *ApprovalRequest) (*ApprovalResult, error) {
	startNode, err := s.processRepo.GetStartNode(request.ProcessCode)
	if err != nil {
		return nil, err
	}
	if startNode == nil {
		return nil, ErrProcessNotFound
	}

	instance := &ApprovalInstance{
		ID:              uuid.New().String(),
		RequestID:       request.ID,
		CurrentNodeID:   startNode.ID,
		CurrentNodeCode: startNode.NodeCode,
		Status:          NodeStatusPending,
		ApprovedBy:      make([]string, 0),
		ApprovalHistory: make([]ApprovalRecord, 0),
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	err = s.instanceRepo.Save(instance)
	if err != nil {
		return nil, err
	}

	if startNode.IsEnd {
		now := time.Now()
		instance.CompletedAt = &now
		instance.Status = NodeStatusApproved
		_ = s.instanceRepo.Update(instance)

		if s.notification != nil {
			result := &ApprovalResult{
				InstanceID: instance.ID,
				Status:     instance.Status,
				Completed:  true,
			}
			_ = s.notification.SendResultNotification(request.Initiator, result)
		}

		return &ApprovalResult{
			InstanceID:  instance.ID,
			Status:      NodeStatusApproved,
			CurrentNode: startNode.NodeCode,
			Completed:   true,
		}, nil
	}

	if s.notification != nil {
		for _, approver := range startNode.Approvers {
			_ = s.notification.SendApprovalNotification(approver, instance, startNode)
		}
	}

	return &ApprovalResult{
		InstanceID:  instance.ID,
		Status:      NodeStatusPending,
		CurrentNode: startNode.NodeCode,
		Completed:   false,
	}, nil
}

func (s *ApprovalEngineService) Approve(ctx context.Context, instanceID, approver, reason string) (*ApprovalResult, error) {
	return s.processApproval(ctx, instanceID, approver, reason, NodeStatusApproved)
}

func (s *ApprovalEngineService) Reject(ctx context.Context, instanceID, approver, reason string) (*ApprovalResult, error) {
	return s.processApproval(ctx, instanceID, approver, reason, NodeStatusRejected)
}

func (s *ApprovalEngineService) processApproval(
	ctx context.Context,
	instanceID, approver, reason string,
	action NodeStatus,
) (*ApprovalResult, error) {
	if instanceID == "" || approver == "" {
		return nil, errors.New("instanceID and approver are required")
	}

	ctx, cancel := context.WithTimeout(ctx, time.Duration(s.config.TimeoutMs)*time.Millisecond)
	defer cancel()

	resultChan := make(chan *ApprovalResult, 1)
	errChan := make(chan error, 1)

	go func() {
		result, err := s.doProcessApproval(ctx, instanceID, approver, reason, action)
		if err != nil {
			errChan <- err
			return
		}
		resultChan <- result
	}()

	select {
	case <-ctx.Done():
		return nil, ErrTimeout
	case err := <-errChan:
		return nil, err
	case result := <-resultChan:
		return result, nil
	}
}

func (s *ApprovalEngineService) doProcessApproval(
	ctx context.Context,
	instanceID, approver, reason string,
	action NodeStatus,
) (*ApprovalResult, error) {
	instance, err := s.instanceRepo.FindByID(instanceID)
	if err != nil {
		return nil, err
	}
	if instance == nil {
		return nil, ErrInstanceNotFound
	}

	if instance.CompletedAt != nil {
		return nil, ErrAlreadyProcessed
	}

	currentNode, err := s.processRepo.GetNodeByCode("", instance.CurrentNodeCode)
	if err != nil {
		return nil, err
	}
	if currentNode == nil {
		return nil, errors.New("current node not found")
	}

	if !s.isValidApprover(approver, currentNode.Approvers) {
		return nil, ErrInvalidApprover
	}

	for _, approved := range instance.ApprovedBy {
		if approved == approver {
			return nil, ErrAlreadyProcessed
		}
	}

	record := ApprovalRecord{
		ID:         uuid.New().String(),
		InstanceID: instanceID,
		NodeID:     currentNode.ID,
		Approver:   approver,
		Action:     action,
		Reason:     reason,
		OperatedAt: time.Now(),
	}
	instance.ApprovalHistory = append(instance.ApprovalHistory, record)

	if action == NodeStatusRejected {
		instance.Status = NodeStatusRejected
		instance.RejectedBy = approver
		instance.RejectReason = reason
		now := time.Now()
		instance.CompletedAt = &now
		instance.UpdatedAt = time.Now()
		_ = s.instanceRepo.Update(instance)

		if s.notification != nil {
			_ = s.notification.SendResultNotification("", &ApprovalResult{
				InstanceID: instance.ID,
				Status:     instance.Status,
				Completed:  true,
			})
		}

		return &ApprovalResult{
			InstanceID:  instance.ID,
			Status:      NodeStatusRejected,
			CurrentNode: currentNode.NodeCode,
			Completed:   true,
		}, nil
	}

	instance.ApprovedBy = append(instance.ApprovedBy, approver)
	instance.UpdatedAt = time.Now()

	nodeCompleted := false
	switch currentNode.ApprovalType {
	case ApprovalTypeANY:
		nodeCompleted = len(instance.ApprovedBy) >= 1
	case ApprovalTypeALL:
		nodeCompleted = len(instance.ApprovedBy) >= len(currentNode.Approvers)
	default:
		nodeCompleted = len(instance.ApprovedBy) >= 1
	}

	if !nodeCompleted {
		_ = s.instanceRepo.Update(instance)
		return &ApprovalResult{
			InstanceID:  instance.ID,
			Status:      NodeStatusPending,
			CurrentNode: currentNode.NodeCode,
			ApprovedBy:  instance.ApprovedBy,
			Completed:   false,
		}, nil
	}

	return s.advanceToNextNode(ctx, instance, currentNode)
}

func (s *ApprovalEngineService) advanceToNextNode(
	ctx context.Context,
	instance *ApprovalInstance,
	currentNode *ApprovalNode,
) (*ApprovalResult, error) {
	nextNodeID, found := s.findNextNode(currentNode, instance)
	if !found || nextNodeID == "" {
		now := time.Now()
		instance.Status = NodeStatusApproved
		instance.CompletedAt = &now
		instance.UpdatedAt = time.Now()
		_ = s.instanceRepo.Update(instance)

		if s.notification != nil {
			_ = s.notification.SendResultNotification("", &ApprovalResult{
				InstanceID: instance.ID,
				Status:     instance.Status,
				Completed:  true,
			})
		}

		return &ApprovalResult{
			InstanceID: instance.ID,
			Status:     NodeStatusApproved,
			ApprovedBy: instance.ApprovedBy,
			Completed:  true,
		}, nil
	}

	nextNode, err := s.processRepo.GetNode("", nextNodeID)
	if err != nil {
		return nil, err
	}
	if nextNode == nil {
		now := time.Now()
		instance.Status = NodeStatusApproved
		instance.CompletedAt = &now
		_ = s.instanceRepo.Update(instance)
		return &ApprovalResult{
			InstanceID: instance.ID,
			Status:     NodeStatusApproved,
			Completed:  true,
		}, nil
	}

	instance.CurrentNodeID = nextNode.ID
	instance.CurrentNodeCode = nextNode.NodeCode
	instance.ApprovedBy = make([]string, 0)
	instance.UpdatedAt = time.Now()

	if nextNode.IsEnd {
		now := time.Now()
		instance.Status = NodeStatusApproved
		instance.CompletedAt = &now
		_ = s.instanceRepo.Update(instance)

		if s.notification != nil {
			_ = s.notification.SendResultNotification("", &ApprovalResult{
				InstanceID: instance.ID,
				Status:     instance.Status,
				Completed:  true,
			})
		}

		return &ApprovalResult{
			InstanceID:  instance.ID,
			Status:      NodeStatusApproved,
			CurrentNode: nextNode.NodeCode,
			Completed:   true,
		}, nil
	}

	_ = s.instanceRepo.Update(instance)

	if s.notification != nil {
		for _, approver := range nextNode.Approvers {
			_ = s.notification.SendApprovalNotification(approver, instance, nextNode)
		}
	}

	return &ApprovalResult{
		InstanceID:  instance.ID,
		Status:      NodeStatusPending,
		CurrentNode: nextNode.NodeCode,
		Completed:   false,
	}, nil
}

func (s *ApprovalEngineService) findNextNode(currentNode *ApprovalNode, instance *ApprovalInstance) (string, bool) {
	for _, nextNodeID := range currentNode.NextNodes {
		nextNode, err := s.processRepo.GetNode("", nextNodeID)
		if err != nil || nextNode == nil {
			continue
		}

		if len(nextNode.Conditions) == 0 {
			return nextNodeID, true
		}

		data := make(map[string]interface{})
		for _, record := range instance.ApprovalHistory {
			data["lastApprover"] = record.Approver
		}

		if s.evaluator.Evaluate(nextNode.Conditions, data) {
			return nextNodeID, true
		}
	}

	return "", false
}

func (s *ApprovalEngineService) isValidApprover(approver string, approvers []string) bool {
	for _, a := range approvers {
		if a == approver {
			return true
		}
	}
	return false
}

func (s *ApprovalEngineService) recordFailure() {
	count := atomic.AddInt32(&s.circuitBreakerCount, 1)
	if count >= int32(s.config.CircuitBreakerThreshold) {
		atomic.StoreInt32(&s.circuitBreakerOpen, 1)
		s.mu.Lock()
		s.lastFailureTime = time.Now()
		s.mu.Unlock()
	}
}

func (s *ApprovalEngineService) ResetCircuitBreaker() {
	atomic.StoreInt32(&s.circuitBreakerOpen, 0)
	atomic.StoreInt32(&s.circuitBreakerCount, 0)
}

func (s *ApprovalEngineService) IsCircuitBreakerOpen() bool {
	return atomic.LoadInt32(&s.circuitBreakerOpen) == 1
}
