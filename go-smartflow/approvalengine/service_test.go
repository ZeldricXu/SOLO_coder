package approvalengine

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

type MockProcessRepo struct {
	mock.Mock
}

func (m *MockProcessRepo) GetNodeByCode(processCode, nodeCode string) (*ApprovalNode, error) {
	args := m.Called(processCode, nodeCode)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*ApprovalNode), args.Error(1)
}

func (m *MockProcessRepo) GetStartNode(processCode string) (*ApprovalNode, error) {
	args := m.Called(processCode)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*ApprovalNode), args.Error(1)
}

func (m *MockProcessRepo) GetNode(processCode, nodeID string) (*ApprovalNode, error) {
	args := m.Called(processCode, nodeID)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*ApprovalNode), args.Error(1)
}

type MockInstanceRepo struct {
	mock.Mock
}

func (m *MockInstanceRepo) Save(instance *ApprovalInstance) error {
	args := m.Called(instance)
	return args.Error(0)
}

func (m *MockInstanceRepo) FindByID(id string) (*ApprovalInstance, error) {
	args := m.Called(id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*ApprovalInstance), args.Error(1)
}

func (m *MockInstanceRepo) Update(instance *ApprovalInstance) error {
	args := m.Called(instance)
	return args.Error(0)
}

type MockNotificationService struct {
	mock.Mock
}

func (m *MockNotificationService) SendApprovalNotification(approver string, instance *ApprovalInstance, node *ApprovalNode) error {
	args := m.Called(approver, instance, node)
	return args.Error(0)
}

func (m *MockNotificationService) SendResultNotification(initiator string, result *ApprovalResult) error {
	args := m.Called(initiator, result)
	return args.Error(0)
}

type SlowProcessRepo struct {
	delay time.Duration
}

func (s *SlowProcessRepo) GetNodeByCode(processCode, nodeCode string) (*ApprovalNode, error) {
	time.Sleep(s.delay)
	return nil, nil
}

func (s *SlowProcessRepo) GetStartNode(processCode string) (*ApprovalNode, error) {
	time.Sleep(s.delay)
	return nil, errors.New("timeout")
}

func (s *SlowProcessRepo) GetNode(processCode, nodeID string) (*ApprovalNode, error) {
	time.Sleep(s.delay)
	return nil, nil
}

type ConcurrentInstanceRepo struct {
	mu        sync.RWMutex
	instances map[string]*ApprovalInstance
}

func NewConcurrentInstanceRepo() *ConcurrentInstanceRepo {
	return &ConcurrentInstanceRepo{
		instances: make(map[string]*ApprovalInstance),
	}
}

func (c *ConcurrentInstanceRepo) Save(instance *ApprovalInstance) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.instances[instance.ID] = instance
	return nil
}

func (c *ConcurrentInstanceRepo) FindByID(id string) (*ApprovalInstance, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	inst, exists := c.instances[id]
	if !exists {
		return nil, nil
	}
	return inst, nil
}

func (c *ConcurrentInstanceRepo) Update(instance *ApprovalInstance) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.instances[instance.ID] = instance
	return nil
}

func TestConditionEvaluator_Evaluate(t *testing.T) {
	evaluator := NewConditionEvaluator()

	tests := []struct {
		name       string
		conditions []Condition
		data       map[string]interface{}
		expected   bool
	}{
		{
			name:       "空条件",
			conditions: []Condition{},
			data:       map[string]interface{}{},
			expected:   true,
		},
		{
			name: "等于条件-匹配",
			conditions: []Condition{
				{Field: "amount", Operator: "==", Value: 1000},
			},
			data:     map[string]interface{}{"amount": 1000},
			expected: true,
		},
		{
			name: "等于条件-不匹配",
			conditions: []Condition{
				{Field: "amount", Operator: "==", Value: 1000},
			},
			data:     map[string]interface{}{"amount": 2000},
			expected: false,
		},
		{
			name: "大于条件",
			conditions: []Condition{
				{Field: "amount", Operator: ">", Value: 1000},
			},
			data:     map[string]interface{}{"amount": 2000},
			expected: true,
		},
		{
			name: "小于等于条件",
			conditions: []Condition{
				{Field: "amount", Operator: "<=", Value: 5000},
			},
			data:     map[string]interface{}{"amount": 5000},
			expected: true,
		},
		{
			name: "不等于条件",
			conditions: []Condition{
				{Field: "status", Operator: "!=", Value: "rejected"},
			},
			data:     map[string]interface{}{"status": "pending"},
			expected: true,
		},
		{
			name: "包含条件",
			conditions: []Condition{
				{Field: "title", Operator: "contains", Value: "紧急"},
			},
			data:     map[string]interface{}{"title": "紧急采购申请"},
			expected: true,
		},
		{
			name: "IN条件-匹配",
			conditions: []Condition{
				{Field: "dept", Operator: "in", Value: []interface{}{"研发", "产品"}},
			},
			data:     map[string]interface{}{"dept": "研发"},
			expected: true,
		},
		{
			name: "IN条件-不匹配",
			conditions: []Condition{
				{Field: "dept", Operator: "in", Value: []interface{}{"研发", "产品"}},
			},
			data:     map[string]interface{}{"dept": "财务"},
			expected: false,
		},
		{
			name: "多条件AND",
			conditions: []Condition{
				{Field: "amount", Operator: ">", Value: 1000},
				{Field: "amount", Operator: "<", Value: 10000},
			},
			data:     map[string]interface{}{"amount": 5000},
			expected: true,
		},
		{
			name: "多条件AND-失败",
			conditions: []Condition{
				{Field: "amount", Operator: ">", Value: 1000},
				{Field: "amount", Operator: "<", Value: 5000},
			},
			data:     map[string]interface{}{"amount": 6000},
			expected: false,
		},
		{
			name: "字段不存在",
			conditions: []Condition{
				{Field: "not_exist", Operator: "==", Value: "test"},
			},
			data:     map[string]interface{}{},
			expected: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := evaluator.Evaluate(tt.conditions, tt.data)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestApprovalEngineService_StartProcess_NormalFlow(t *testing.T) {
	startNode := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"manager1", "manager2"},
		IsStart:      true,
		NextNodes:    []string{"node2"},
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetStartNode", "leave_approval").Return(startNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("Save", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendApprovalNotification", "manager1", mock.Anything, mock.Anything).Return(nil)
	mockNotification.On("SendApprovalNotification", "manager2", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	request := &ApprovalRequest{
		ID:          "req1",
		ProcessCode: "leave_approval",
		Title:       "请假申请",
		Initiator:   "employee1",
	}

	result, err := service.StartProcess(context.Background(), request)

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, NodeStatusPending, result.Status)
	assert.Equal(t, "MANAGER_APPROVAL", result.CurrentNode)
	assert.False(t, result.Completed)

	mockProcessRepo.AssertExpectations(t)
	mockInstanceRepo.AssertExpectations(t)
	mockNotification.AssertExpectations(t)
}

func TestApprovalEngineService_StartProcess_NilRequest(t *testing.T) {
	mockProcessRepo := &MockProcessRepo{}
	mockInstanceRepo := &MockInstanceRepo{}
	mockNotification := &MockNotificationService{}

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.StartProcess(context.Background(), nil)

	assert.Nil(t, result)
	assert.Error(t, err)
}

func TestApprovalEngineService_StartProcess_ProcessNotFound(t *testing.T) {
	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetStartNode", "invalid_process").Return(nil, errors.New("not found"))

	mockInstanceRepo := &MockInstanceRepo{}
	mockNotification := &MockNotificationService{}

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	request := &ApprovalRequest{
		ID:          "req1",
		ProcessCode: "invalid_process",
		Title:       "测试",
	}

	result, err := service.StartProcess(context.Background(), request)

	assert.Nil(t, result)
	assert.Error(t, err)
}

func TestApprovalEngineService_StartProcess_SingleNodeDirectApprove(t *testing.T) {
	startNode := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "END",
		NodeName:     "结束",
		ApprovalType: ApprovalTypeANY,
		IsStart:      true,
		IsEnd:        true,
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetStartNode", "auto_approve").Return(startNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("Save", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)
	mockInstanceRepo.On("Update", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendResultNotification", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	request := &ApprovalRequest{
		ID:          "req1",
		ProcessCode: "auto_approve",
		Title:       "自动审批",
		Initiator:   "employee1",
	}

	result, err := service.StartProcess(context.Background(), request)

	require.NoError(t, err)
	assert.Equal(t, NodeStatusApproved, result.Status)
	assert.True(t, result.Completed)
}

func TestApprovalEngineService_Approve_ANYType(t *testing.T) {
	startNode := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"manager1", "manager2"},
		NextNodes:    []string{"node_end"},
	}

	endNode := &ApprovalNode{
		ID:       "node_end",
		NodeCode: "END",
		NodeName: "结束",
		IsEnd:    true,
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "MANAGER_APPROVAL").Return(startNode, nil)
	mockProcessRepo.On("GetNode", "", "node_end").Return(endNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)
	mockInstanceRepo.On("Update", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendResultNotification", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "inst1", "manager1", "同意")

	require.NoError(t, err)
	assert.Equal(t, NodeStatusApproved, result.Status)
	assert.True(t, result.Completed)
	assert.Contains(t, result.ApprovedBy, "manager1")
}

func TestApprovalEngineService_Approve_ALLType(t *testing.T) {
	startNode := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeALL,
		Approvers:    []string{"manager1", "manager2"},
		NextNodes:    []string{"node_end"},
	}

	endNode := &ApprovalNode{
		ID:       "node_end",
		NodeCode: "END",
		NodeName: "结束",
		IsEnd:    true,
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "MANAGER_APPROVAL").Return(startNode, nil)
	mockProcessRepo.On("GetNode", "", "node_end").Return(endNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)
	mockInstanceRepo.On("Update", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendResultNotification", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "inst1", "manager1", "同意")
	require.NoError(t, err)
	assert.Equal(t, NodeStatusPending, result.Status)
	assert.False(t, result.Completed)
	assert.Len(t, result.ApprovedBy, 1)

	result2, err2 := service.Approve(context.Background(), "inst1", "manager2", "同意")
	require.NoError(t, err2)
	assert.Equal(t, NodeStatusApproved, result2.Status)
	assert.True(t, result2.Completed)
	assert.Len(t, result2.ApprovedBy, 2)
}

func TestApprovalEngineService_Reject(t *testing.T) {
	startNode := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"manager1", "manager2"},
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "MANAGER_APPROVAL").Return(startNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)
	mockInstanceRepo.On("Update", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendResultNotification", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Reject(context.Background(), "inst1", "manager1", "不符合规定")

	require.NoError(t, err)
	assert.Equal(t, NodeStatusRejected, result.Status)
	assert.True(t, result.Completed)
}

func TestApprovalEngineService_InvalidApprover(t *testing.T) {
	startNode := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"manager1", "manager2"},
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "MANAGER_APPROVAL").Return(startNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)

	mockNotification := &MockNotificationService{}

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "inst1", "unauthorized_person", "同意")

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrInvalidApprover)
}

func TestApprovalEngineService_DuplicateApproval(t *testing.T) {
	startNode := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeALL,
		Approvers:    []string{"manager1", "manager2"},
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{"manager1"},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "MANAGER_APPROVAL").Return(startNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)

	mockNotification := &MockNotificationService{}

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "inst1", "manager1", "再次同意")

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrAlreadyProcessed)
}

func TestApprovalEngineService_InstanceNotFound(t *testing.T) {
	mockProcessRepo := &MockProcessRepo{}
	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "invalid_inst").Return(nil, errors.New("not found"))

	mockNotification := &MockNotificationService{}

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "invalid_inst", "manager1", "同意")

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrInstanceNotFound)
}

func TestApprovalEngineService_AlreadyCompleted(t *testing.T) {
	now := time.Now()
	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusApproved,
		ApprovedBy:      []string{"manager1"},
		CompletedAt:     &now,
	}

	mockProcessRepo := &MockProcessRepo{}
	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)

	mockNotification := &MockNotificationService{}

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "inst1", "manager2", "同意")

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrAlreadyProcessed)
}

func TestApprovalEngineService_Timeout(t *testing.T) {
	slowRepo := &SlowProcessRepo{delay: 200 * time.Millisecond}
	mockInstanceRepo := &MockInstanceRepo{}
	mockNotification := &MockNotificationService{}

	config := EngineConfig{
		TimeoutMs: 100,
	}
	service := NewApprovalEngineService(slowRepo, mockInstanceRepo, mockNotification, config)

	request := &ApprovalRequest{
		ID:          "req1",
		ProcessCode: "leave_approval",
		Title:       "测试",
	}

	result, err := service.StartProcess(context.Background(), request)

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrTimeout)
}

func TestApprovalEngineService_CircuitBreaker(t *testing.T) {
	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetStartNode", "test_process").Return(nil, errors.New("db error"))

	mockInstanceRepo := &MockInstanceRepo{}
	mockNotification := &MockNotificationService{}

	config := EngineConfig{
		CircuitBreakerThreshold: 3,
	}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	request := &ApprovalRequest{
		ID:          "req1",
		ProcessCode: "test_process",
		Title:       "测试",
	}

	for i := 0; i < 3; i++ {
		_, _ = service.StartProcess(context.Background(), request)
	}

	assert.True(t, service.IsCircuitBreakerOpen())

	result, err := service.StartProcess(context.Background(), request)
	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrCircuitBreakerOpen)

	service.ResetCircuitBreaker()
	assert.False(t, service.IsCircuitBreakerOpen())
}

func TestApprovalEngineService_MultiNodeFlow(t *testing.T) {
	node1 := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"manager1"},
		IsStart:      true,
		NextNodes:    []string{"node2"},
	}

	node2 := &ApprovalNode{
		ID:           "node2",
		NodeCode:     "DIRECTOR_APPROVAL",
		NodeName:     "总监审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"director1"},
		NextNodes:    []string{"node_end"},
	}

	endNode := &ApprovalNode{
		ID:       "node_end",
		NodeCode: "END",
		NodeName: "结束",
		IsEnd:    true,
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "MANAGER_APPROVAL").Return(node1, nil)
	mockProcessRepo.On("GetNodeByCode", "", "DIRECTOR_APPROVAL").Return(node2, nil)
	mockProcessRepo.On("GetNode", "", "node2").Return(node2, nil)
	mockProcessRepo.On("GetNode", "", "node_end").Return(endNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)
	mockInstanceRepo.On("Update", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendApprovalNotification", "director1", mock.Anything, mock.Anything).Return(nil)
	mockNotification.On("SendResultNotification", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result1, err1 := service.Approve(context.Background(), "inst1", "manager1", "同意")
	require.NoError(t, err1)
	assert.Equal(t, NodeStatusPending, result1.Status)
	assert.Equal(t, "DIRECTOR_APPROVAL", result1.CurrentNode)
	assert.False(t, result1.Completed)

	result2, err2 := service.Approve(context.Background(), "inst1", "director1", "同意")
	require.NoError(t, err2)
	assert.Equal(t, NodeStatusApproved, result2.Status)
	assert.True(t, result2.Completed)
}

func TestApprovalEngineService_ConcurrentApproval(t *testing.T) {
	node := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "APPROVAL",
		NodeName:     "审批",
		ApprovalType: ApprovalTypeALL,
		Approvers:    []string{"approver1", "approver2", "approver3"},
		NextNodes:    []string{"node_end"},
	}

	endNode := &ApprovalNode{
		ID:       "node_end",
		NodeCode: "END",
		NodeName: "结束",
		IsEnd:    true,
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		RequestID:       "req1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	concurrentRepo := NewConcurrentInstanceRepo()
	_ = concurrentRepo.Save(instance)

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "APPROVAL").Return(node, nil)
	mockProcessRepo.On("GetNode", "", "node_end").Return(endNode, nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendResultNotification", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, concurrentRepo, mockNotification, config)

	approvers := []string{"approver1", "approver2", "approver3"}
	var wg sync.WaitGroup
	var successCount int32
	var errorCount int32

	for _, approver := range approvers {
		wg.Add(1)
		go func(ap string) {
			defer wg.Done()
			_, err := service.Approve(context.Background(), "inst1", ap, "同意")
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}(approver)
	}

	wg.Wait()

	finalInstance, _ := concurrentRepo.FindByID("inst1")
	if finalInstance != nil && finalInstance.CompletedAt != nil {
		assert.Len(t, finalInstance.ApprovedBy, 3)
	}
}

func TestApprovalEngineService_EmptyApprovers(t *testing.T) {
	node := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "APPROVAL",
		NodeName:     "审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{},
		NextNodes:    []string{"node_end"},
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		CurrentNodeCode: "APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "APPROVAL").Return(node, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)

	mockNotification := &MockNotificationService{}

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "inst1", "anyone", "同意")

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrInvalidApprover)
}

func TestApprovalEngineService_ConditionalRouting(t *testing.T) {
	node1 := &ApprovalNode{
		ID:           "node1",
		NodeCode:     "MANAGER_APPROVAL",
		NodeName:     "经理审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"manager1"},
		IsStart:      true,
		NextNodes:    []string{"node2", "node3"},
	}

	node2 := &ApprovalNode{
		ID:       "node2",
		NodeCode: "DIRECTOR_APPROVAL",
		NodeName: "总监审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:    []string{"director1"},
		Conditions: []Condition{
			{Field: "amount", Operator: ">", Value: 10000},
		},
		NextNodes: []string{"node_end"},
	}

	node3 := &ApprovalNode{
		ID:         "node3",
		NodeCode:   "FINANCE_APPROVAL",
		NodeName:   "财务审批",
		ApprovalType: ApprovalTypeANY,
		Approvers:  []string{"finance1"},
		NextNodes:  []string{"node_end"},
	}

	endNode := &ApprovalNode{
		ID:       "node_end",
		NodeCode: "END",
		NodeName: "结束",
		IsEnd:    true,
	}

	instance := &ApprovalInstance{
		ID:              "inst1",
		CurrentNodeID:   "node1",
		CurrentNodeCode: "MANAGER_APPROVAL",
		Status:          NodeStatusPending,
		ApprovedBy:      []string{},
		ApprovalHistory: []ApprovalRecord{},
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	mockProcessRepo := &MockProcessRepo{}
	mockProcessRepo.On("GetNodeByCode", "", "MANAGER_APPROVAL").Return(node1, nil)
	mockProcessRepo.On("GetNode", "", "node2").Return(node2, nil)
	mockProcessRepo.On("GetNode", "", "node3").Return(node3, nil)
	mockProcessRepo.On("GetNode", "", "node_end").Return(endNode, nil)

	mockInstanceRepo := &MockInstanceRepo{}
	mockInstanceRepo.On("FindByID", "inst1").Return(instance, nil)
	mockInstanceRepo.On("Update", mock.AnythingOfType("*approvalengine.ApprovalInstance")).Return(nil)

	mockNotification := &MockNotificationService{}
	mockNotification.On("SendApprovalNotification", "finance1", mock.Anything, mock.Anything).Return(nil)

	config := EngineConfig{}
	service := NewApprovalEngineService(mockProcessRepo, mockInstanceRepo, mockNotification, config)

	result, err := service.Approve(context.Background(), "inst1", "manager1", "同意")
	require.NoError(t, err)
	assert.Equal(t, "FINANCE_APPROVAL", result.CurrentNode)
}
