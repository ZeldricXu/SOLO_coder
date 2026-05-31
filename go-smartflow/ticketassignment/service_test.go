package ticketassignment

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

type MockEmployeeRepo struct {
	mock.Mock
}

func (m *MockEmployeeRepo) FindAvailable() ([]Employee, error) {
	args := m.Called()
	return args.Get(0).([]Employee), args.Error(1)
}

func (m *MockEmployeeRepo) FindByID(id string) (*Employee, error) {
	args := m.Called(id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*Employee), args.Error(1)
}

func (m *MockEmployeeRepo) UpdateLoad(employeeID string, delta int) error {
	args := m.Called(employeeID, delta)
	return args.Error(0)
}

type MockTicketRepo struct {
	mock.Mock
}

func (m *MockTicketRepo) FindByID(id string) (*Ticket, error) {
	args := m.Called(id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*Ticket), args.Error(1)
}

func (m *MockTicketRepo) UpdateAssignee(ticketID, employeeID string) error {
	args := m.Called(ticketID, employeeID)
	return args.Error(0)
}

func (m *MockTicketRepo) Save(ticket *Ticket) error {
	args := m.Called(ticket)
	return args.Error(0)
}

type MockCache struct {
	mock.Mock
}

func (m *MockCache) Get(key string) (interface{}, bool) {
	args := m.Called(key)
	return args.Get(0), args.Bool(1)
}

func (m *MockCache) Set(key string, value interface{}, ttl time.Duration) {
	m.Called(key, value, ttl)
}

func (m *MockCache) Delete(key string) {
	m.Called(key)
}

type SlowEmployeeRepo struct {
	delay time.Duration
}

func (s *SlowEmployeeRepo) FindAvailable() ([]Employee, error) {
	time.Sleep(s.delay)
	return []Employee{}, nil
}

func (s *SlowEmployeeRepo) FindByID(id string) (*Employee, error) {
	time.Sleep(s.delay)
	return nil, nil
}

func (s *SlowEmployeeRepo) UpdateLoad(employeeID string, delta int) error {
	time.Sleep(s.delay)
	return nil
}

type ConcurrentEmployeeRepo struct {
	mu        sync.RWMutex
	employees map[string]*Employee
}

func NewConcurrentEmployeeRepo(employees []Employee) *ConcurrentEmployeeRepo {
	repo := &ConcurrentEmployeeRepo{
		employees: make(map[string]*Employee),
	}
	for i := range employees {
		repo.employees[employees[i].ID] = &employees[i]
	}
	return repo
}

func (c *ConcurrentEmployeeRepo) FindAvailable() ([]Employee, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make([]Employee, 0, len(c.employees))
	for _, emp := range c.employees {
		if emp.IsAvailable {
			result = append(result, *emp)
		}
	}
	return result, nil
}

func (c *ConcurrentEmployeeRepo) FindByID(id string) (*Employee, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	emp, exists := c.employees[id]
	if !exists {
		return nil, nil
	}
	return emp, nil
}

func (c *ConcurrentEmployeeRepo) UpdateLoad(employeeID string, delta int) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	emp, exists := c.employees[employeeID]
	if !exists {
		return errors.New("employee not found")
	}
	emp.CurrentLoad += delta
	return nil
}

type ConcurrentTicketRepo struct {
	mu      sync.RWMutex
	tickets map[string]*Ticket
}

func NewConcurrentTicketRepo() *ConcurrentTicketRepo {
	return &ConcurrentTicketRepo{
		tickets: make(map[string]*Ticket),
	}
}

func (c *ConcurrentTicketRepo) FindByID(id string) (*Ticket, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	t, exists := c.tickets[id]
	if !exists {
		return nil, nil
	}
	return t, nil
}

func (c *ConcurrentTicketRepo) UpdateAssignee(ticketID, employeeID string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	t, exists := c.tickets[ticketID]
	if !exists {
		return errors.New("ticket not found")
	}
	t.AssignedTo = employeeID
	t.Status = "ASSIGNED"
	return nil
}

func (c *ConcurrentTicketRepo) Save(ticket *Ticket) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.tickets[ticket.ID] = ticket
	return nil
}

func TestSkillMatcher_Match(t *testing.T) {
	matcher := NewSkillMatcher()

	tests := []struct {
		name           string
		employeeSkills []Skill
		requiredSkills []string
		expectedRatio  float64
		expectedCount  int
	}{
		{
			name: "完全匹配",
			employeeSkills: []Skill{
				{Name: "Java", Proficiency: 5},
				{Name: "Go", Proficiency: 4},
			},
			requiredSkills: []string{"Java", "Go"},
			expectedRatio:  1.0,
			expectedCount:  2,
		},
		{
			name: "部分匹配",
			employeeSkills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			requiredSkills: []string{"Java", "Go"},
			expectedRatio:  0.5,
			expectedCount:  1,
		},
		{
			name:           "无匹配",
			employeeSkills: []Skill{},
			requiredSkills: []string{"Python"},
			expectedRatio:  0,
			expectedCount:  0,
		},
		{
			name: "空需求",
			employeeSkills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			requiredSkills: []string{},
			expectedRatio:  1.0,
			expectedCount:  0,
		},
		{
			name: "大小写不敏感匹配",
			employeeSkills: []Skill{
				{Name: "java", Proficiency: 5},
			},
			requiredSkills: []string{"JAVA"},
			expectedRatio:  1.0,
			expectedCount:  1,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ratio, matched, avgProf := matcher.Match(tt.employeeSkills, tt.requiredSkills)
			assert.InDelta(t, tt.expectedRatio, ratio, 0.01)
			assert.Len(t, matched, tt.expectedCount)
			if tt.expectedCount > 0 {
				assert.Greater(t, avgProf, 0.0)
			}
		})
	}
}

func TestLoadBalancer_CalculateLoadScore(t *testing.T) {
	lb := NewLoadBalancer()
	config := AssignmentConfig{
		LoadBalanceWeight: 0.3,
	}

	tests := []struct {
		name          string
		employee      Employee
		expectedScore float64
	}{
		{
			name: "空闲员工",
			employee: Employee{
				CurrentLoad: 0,
				MaxLoad:     10,
				IsAvailable: true,
			},
			expectedScore: 0.3,
		},
		{
			name: "半负载员工",
			employee: Employee{
				CurrentLoad: 5,
				MaxLoad:     10,
				IsAvailable: true,
			},
			expectedScore: 0.15,
		},
		{
			name: "满负载员工",
			employee: Employee{
				CurrentLoad: 10,
				MaxLoad:     10,
				IsAvailable: true,
			},
			expectedScore: 0,
		},
		{
			name: "无效MaxLoad",
			employee: Employee{
				CurrentLoad: 0,
				MaxLoad:     0,
				IsAvailable: true,
			},
			expectedScore: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			score := lb.CalculateLoadScore(tt.employee, config)
			assert.InDelta(t, tt.expectedScore, score, 0.01)
		})
	}
}

func TestLoadBalancer_CanAcceptWork(t *testing.T) {
	lb := NewLoadBalancer()

	tests := []struct {
		name     string
		employee Employee
		expected bool
	}{
		{
			name: "可接受工作",
			employee: Employee{
				CurrentLoad: 5,
				MaxLoad:     10,
				IsAvailable: true,
			},
			expected: true,
		},
		{
			name: "不可用员工",
			employee: Employee{
				CurrentLoad: 0,
				MaxLoad:     10,
				IsAvailable: false,
			},
			expected: false,
		},
		{
			name: "满负载",
			employee: Employee{
				CurrentLoad: 10,
				MaxLoad:     10,
				IsAvailable: true,
			},
			expected: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := lb.CanAcceptWork(tt.employee)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestTicketAssignmentService_AssignTicket_NormalFlow(t *testing.T) {
	employees := []Employee{
		{
			ID:   "emp1",
			Name: "张三",
			Skills: []Skill{
				{Name: "Java", Proficiency: 5},
				{Name: "Spring", Proficiency: 4},
			},
			CurrentLoad: 2,
			MaxLoad:     10,
			IsAvailable: true,
		},
		{
			ID:   "emp2",
			Name: "李四",
			Skills: []Skill{
				{Name: "Java", Proficiency: 3},
				{Name: "Go", Proficiency: 5},
			},
			CurrentLoad: 1,
			MaxLoad:     10,
			IsAvailable: true,
		},
	}

	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return(employees, nil)
	mockEmpRepo.On("UpdateLoad", "emp1", 1).Return(nil)

	mockTicketRepo := &MockTicketRepo{}
	mockTicketRepo.On("UpdateAssignee", "ticket1", "emp1").Return(nil)

	mockCache := &MockCache{}
	mockCache.On("Delete", "employee:emp1").Return()
	mockCache.On("Delete", "ticket:ticket1").Return()

	config := AssignmentConfig{
		SkillMatchWeight:  0.5,
		ProficiencyWeight: 0.2,
		LoadBalanceWeight: 0.3,
		MinMatchThreshold: 0.5,
	}

	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		Title:          "Java开发任务",
		RequiredSkills: []string{"Java", "Spring"},
		Priority:       1,
		Status:         "NEW",
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, "ticket1", result.TicketID)
	assert.Equal(t, "emp1", result.EmployeeID)
	assert.Equal(t, "张三", result.EmployeeName)
	assert.Greater(t, result.Score, 0.0)
	assert.Len(t, result.MatchedSkills, 2)
	assert.Equal(t, "emp1", ticket.AssignedTo)
	assert.Equal(t, "ASSIGNED", ticket.Status)

	mockEmpRepo.AssertExpectations(t)
	mockTicketRepo.AssertExpectations(t)
	mockCache.AssertExpectations(t)
}

func TestTicketAssignmentService_AssignTicket_NoAvailableEmployees(t *testing.T) {
	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return([]Employee{}, nil)

	mockTicketRepo := &MockTicketRepo{}
	mockCache := &MockCache{}

	config := AssignmentConfig{}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		RequiredSkills: []string{"Java"},
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrNoAvailableEmployees)
}

func TestTicketAssignmentService_AssignTicket_NoMatchingEmployee(t *testing.T) {
	employees := []Employee{
		{
			ID:   "emp1",
			Name: "张三",
			Skills: []Skill{
				{Name: "Python", Proficiency: 5},
			},
			CurrentLoad: 0,
			MaxLoad:     10,
			IsAvailable: true,
		},
	}

	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return(employees, nil)

	mockTicketRepo := &MockTicketRepo{}
	mockCache := &MockCache{}

	config := AssignmentConfig{
		MinMatchThreshold: 0.5,
	}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		RequiredSkills: []string{"Java"},
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrNoMatchingEmployee)
}

func TestTicketAssignmentService_AssignTicket_AlreadyAssigned(t *testing.T) {
	mockEmpRepo := &MockEmployeeRepo{}
	mockTicketRepo := &MockTicketRepo{}
	mockCache := &MockCache{}

	config := AssignmentConfig{}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:         "ticket1",
		AssignedTo: "emp1",
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrTicketAlreadyAssigned)
}

func TestTicketAssignmentService_AssignTicket_NilTicket(t *testing.T) {
	mockEmpRepo := &MockEmployeeRepo{}
	mockTicketRepo := &MockTicketRepo{}
	mockCache := &MockCache{}

	config := AssignmentConfig{}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	result, err := service.AssignTicket(context.Background(), nil)

	assert.Nil(t, result)
	assert.Error(t, err)
}

func TestTicketAssignmentService_AssignTicket_AllEmployeesFullLoad(t *testing.T) {
	employees := []Employee{
		{
			ID:   "emp1",
			Name: "张三",
			Skills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			CurrentLoad: 10,
			MaxLoad:     10,
			IsAvailable: true,
		},
		{
			ID:   "emp2",
			Name: "李四",
			Skills: []Skill{
				{Name: "Java", Proficiency: 4},
			},
			CurrentLoad: 10,
			MaxLoad:     10,
			IsAvailable: true,
		},
	}

	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return(employees, nil)

	mockTicketRepo := &MockTicketRepo{}
	mockCache := &MockCache{}

	config := AssignmentConfig{}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		RequiredSkills: []string{"Java"},
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrNoMatchingEmployee)
}

func TestTicketAssignmentService_AssignTicket_LoadBalancing(t *testing.T) {
	employees := []Employee{
		{
			ID:   "emp1",
			Name: "张三",
			Skills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			CurrentLoad: 8,
			MaxLoad:     10,
			IsAvailable: true,
		},
		{
			ID:   "emp2",
			Name: "李四",
			Skills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			CurrentLoad: 2,
			MaxLoad:     10,
			IsAvailable: true,
		},
	}

	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return(employees, nil)
	mockEmpRepo.On("UpdateLoad", "emp2", 1).Return(nil)

	mockTicketRepo := &MockTicketRepo{}
	mockTicketRepo.On("UpdateAssignee", "ticket1", "emp2").Return(nil)

	mockCache := &MockCache{}
	mockCache.On("Delete", mock.Anything, mock.Anything).Return()

	config := AssignmentConfig{
		SkillMatchWeight:  0.5,
		ProficiencyWeight: 0.2,
		LoadBalanceWeight: 0.3,
	}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		RequiredSkills: []string{"Java"},
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	require.NoError(t, err)
	assert.Equal(t, "emp2", result.EmployeeID)
}

func TestTicketAssignmentService_AssignTicket_Timeout(t *testing.T) {
	slowRepo := &SlowEmployeeRepo{delay: 200 * time.Millisecond}
	mockTicketRepo := &MockTicketRepo{}
	mockCache := &MockCache{}

	config := AssignmentConfig{
		TimeoutMs: 100,
	}
	service := NewTicketAssignmentService(slowRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		RequiredSkills: []string{"Java"},
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrTimeout)
}

func TestTicketAssignmentService_CircuitBreaker(t *testing.T) {
	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return([]Employee{}, errors.New("db error"))

	mockTicketRepo := &MockTicketRepo{}
	mockCache := &MockCache{}

	config := AssignmentConfig{
		TimeoutMs:            100,
		CircuitBreakerThreshold: 3,
	}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		RequiredSkills: []string{"Java"},
	}

	for i := 0; i < 3; i++ {
		_, _ = service.AssignTicket(context.Background(), ticket)
	}

	assert.True(t, service.IsCircuitBreakerOpen())

	result, err := service.AssignTicket(context.Background(), ticket)
	assert.Nil(t, result)
	assert.ErrorIs(t, err, ErrCircuitBreakerOpen)

	service.ResetCircuitBreaker()
	assert.False(t, service.IsCircuitBreakerOpen())
}

func TestTicketAssignmentService_AssignBatch(t *testing.T) {
	employees := []Employee{
		{
			ID:   "emp1",
			Name: "张三",
			Skills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			CurrentLoad: 0,
			MaxLoad:     10,
			IsAvailable: true,
		},
		{
			ID:   "emp2",
			Name: "李四",
			Skills: []Skill{
				{Name: "Java", Proficiency: 4},
			},
			CurrentLoad: 0,
			MaxLoad:     10,
			IsAvailable: true,
		},
	}

	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return(employees, nil)
	mockEmpRepo.On("UpdateLoad", mock.Anything, 1).Return(nil)

	mockTicketRepo := &MockTicketRepo{}
	mockTicketRepo.On("UpdateAssignee", mock.Anything, mock.Anything).Return(nil)

	mockCache := &MockCache{}
	mockCache.On("Delete", mock.Anything).Return()

	config := AssignmentConfig{}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	tickets := make([]*Ticket, 5)
	for i := 0; i < 5; i++ {
		tickets[i] = &Ticket{
			ID:             fmt.Sprintf("ticket%d", i),
			RequiredSkills: []string{"Java"},
		}
	}

	results, errs := service.AssignBatch(context.Background(), tickets)

	assert.Len(t, results, 5)
	assert.Len(t, errs, 0)
}

func TestTicketAssignmentService_ConcurrentAssignment(t *testing.T) {
	employees := []Employee{
		{
			ID:   "emp1",
			Name: "张三",
			Skills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			CurrentLoad: 0,
			MaxLoad:     100,
			IsAvailable: true,
		},
		{
			ID:   "emp2",
			Name: "李四",
			Skills: []Skill{
				{Name: "Java", Proficiency: 4},
			},
			CurrentLoad: 0,
			MaxLoad:     100,
			IsAvailable: true,
		},
	}

	concurrentEmpRepo := NewConcurrentEmployeeRepo(employees)
	concurrentTicketRepo := NewConcurrentTicketRepo()
	mockCache := &MockCache{}
	mockCache.On("Delete", mock.Anything).Return()

	config := AssignmentConfig{}
	service := NewTicketAssignmentService(concurrentEmpRepo, concurrentTicketRepo, mockCache, config)

	numTickets := 50
	tickets := make([]*Ticket, numTickets)
	for i := 0; i < numTickets; i++ {
		tickets[i] = &Ticket{
			ID:             fmt.Sprintf("ticket%d", i),
			RequiredSkills: []string{"Java"},
			Status:         "NEW",
		}
		_ = concurrentTicketRepo.Save(tickets[i])
	}

	var wg sync.WaitGroup
	var successCount int32
	var errorCount int32

	for i := 0; i < numTickets; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, err := service.AssignTicket(context.Background(), tickets[idx])
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}(i)
	}

	wg.Wait()

	assert.Equal(t, int32(numTickets), successCount+errorCount)

	emp1, _ := concurrentEmpRepo.employees["emp1"]
	emp2, _ := concurrentEmpRepo.employees["emp2"]
	totalLoad := emp1.CurrentLoad + emp2.CurrentLoad
	assert.Equal(t, int(successCount), totalLoad)
}

func TestTicketAssignmentService_UpdateAssigneeFailureRollback(t *testing.T) {
	employees := []Employee{
		{
			ID:   "emp1",
			Name: "张三",
			Skills: []Skill{
				{Name: "Java", Proficiency: 5},
			},
			CurrentLoad: 0,
			MaxLoad:     10,
			IsAvailable: true,
		},
	}

	mockEmpRepo := &MockEmployeeRepo{}
	mockEmpRepo.On("FindAvailable").Return(employees, nil)
	mockEmpRepo.On("UpdateLoad", "emp1", 1).Return(nil)
	mockEmpRepo.On("UpdateLoad", "emp1", -1).Return(nil)

	mockTicketRepo := &MockTicketRepo{}
	mockTicketRepo.On("UpdateAssignee", "ticket1", "emp1").Return(errors.New("db error"))

	mockCache := &MockCache{}

	config := AssignmentConfig{}
	service := NewTicketAssignmentService(mockEmpRepo, mockTicketRepo, mockCache, config)

	ticket := &Ticket{
		ID:             "ticket1",
		RequiredSkills: []string{"Java"},
	}

	result, err := service.AssignTicket(context.Background(), ticket)

	assert.Nil(t, result)
	assert.Error(t, err)
	mockEmpRepo.AssertCalled(t, "UpdateLoad", "emp1", -1)
}
