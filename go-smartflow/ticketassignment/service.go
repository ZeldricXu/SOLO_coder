package ticketassignment

import (
	"context"
	"errors"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

var (
	ErrNoAvailableEmployees  = errors.New("no available employees")
	ErrNoMatchingEmployee    = errors.New("no employee matches required skills")
	ErrTicketAlreadyAssigned = errors.New("ticket already assigned")
	ErrCircuitBreakerOpen    = errors.New("circuit breaker is open")
	ErrTimeout               = errors.New("operation timeout")
)

type TicketAssignmentService struct {
	employeeRepo EmployeeRepository
	ticketRepo   TicketRepository
	matcher      *SkillMatcher
	loadBalancer *LoadBalancer
	config       AssignmentConfig
	cache        Cache

	circuitBreakerCount int32
	circuitBreakerOpen  int32
	circuitBreakerMutex sync.RWMutex
	lastFailureTime     time.Time
}

func NewTicketAssignmentService(
	employeeRepo EmployeeRepository,
	ticketRepo TicketRepository,
	cache Cache,
	config AssignmentConfig,
) *TicketAssignmentService {
	if config.SkillMatchWeight == 0 {
		config.SkillMatchWeight = 0.5
	}
	if config.ProficiencyWeight == 0 {
		config.ProficiencyWeight = 0.2
	}
	if config.LoadBalanceWeight == 0 {
		config.LoadBalanceWeight = 0.3
	}
	if config.MinMatchThreshold == 0 {
		config.MinMatchThreshold = 0.5
	}
	if config.TimeoutMs == 0 {
		config.TimeoutMs = 3000
	}
	if config.CircuitBreakerThreshold == 0 {
		config.CircuitBreakerThreshold = 5
	}

	return &TicketAssignmentService{
		employeeRepo: employeeRepo,
		ticketRepo:   ticketRepo,
		matcher:      NewSkillMatcher(),
		loadBalancer: NewLoadBalancer(),
		config:       config,
		cache:        cache,
	}
}

func (s *TicketAssignmentService) AssignTicket(ctx context.Context, ticket *Ticket) (*AssignmentResult, error) {
	if ticket == nil {
		return nil, errors.New("ticket cannot be nil")
	}
	if ticket.AssignedTo != "" {
		return nil, ErrTicketAlreadyAssigned
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

	resultChan := make(chan *AssignmentResult, 1)
	errChan := make(chan error, 1)

	go func() {
		result, err := s.doAssign(ctx, ticket)
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

func (s *TicketAssignmentService) doAssign(ctx context.Context, ticket *Ticket) (*AssignmentResult, error) {
	employees, err := s.employeeRepo.FindAvailable()
	if err != nil {
		return nil, err
	}
	if len(employees) == 0 {
		return nil, ErrNoAvailableEmployees
	}

	type scoredEmployee struct {
		employee      Employee
		score         float64
		matchedSkills []string
		loadRatio     float64
	}

	scored := make([]scoredEmployee, 0, len(employees))

	for _, emp := range employees {
		if !s.loadBalancer.CanAcceptWork(emp) {
			continue
		}

		matchRatio, matchedSkills, avgProficiency := s.matcher.Match(emp.Skills, ticket.RequiredSkills)

		if matchRatio < s.config.MinMatchThreshold {
			continue
		}

		skillScore := s.matcher.CalculateSkillScore(matchRatio, avgProficiency, s.config)
		loadScore := s.loadBalancer.CalculateLoadScore(emp, s.config)
		idleBonus := s.loadBalancer.CalculateIdleBonus(emp, 5*time.Minute)

		totalScore := skillScore + loadScore + idleBonus

		scored = append(scored, scoredEmployee{
			employee:      emp,
			score:         totalScore,
			matchedSkills: matchedSkills,
			loadRatio:     s.loadBalancer.GetLoadRatio(emp),
		})
	}

	if len(scored) == 0 {
		return nil, ErrNoMatchingEmployee
	}

	sort.Slice(scored, func(i, j int) bool {
		return scored[i].score > scored[j].score
	})

	best := scored[0]

	err = s.employeeRepo.UpdateLoad(best.employee.ID, 1)
	if err != nil {
		return nil, err
	}

	ticket.AssignedTo = best.employee.ID
	ticket.Status = "ASSIGNED"
	err = s.ticketRepo.UpdateAssignee(ticket.ID, best.employee.ID)
	if err != nil {
		_ = s.employeeRepo.UpdateLoad(best.employee.ID, -1)
		return nil, err
	}

	if s.cache != nil {
		s.cache.Delete("employee:" + best.employee.ID)
		s.cache.Delete("ticket:" + ticket.ID)
	}

	return &AssignmentResult{
		TicketID:      ticket.ID,
		EmployeeID:    best.employee.ID,
		EmployeeName:  best.employee.Name,
		Score:         best.score,
		MatchedSkills: best.matchedSkills,
		LoadRatio:     best.loadRatio,
	}, nil
}

func (s *TicketAssignmentService) AssignBatch(ctx context.Context, tickets []*Ticket) ([]*AssignmentResult, []error) {
	results := make([]*AssignmentResult, 0, len(tickets))
	errs := make([]error, 0, len(tickets))

	var wg sync.WaitGroup
	var mu sync.Mutex

	sem := make(chan struct{}, 10)

	for i := range tickets {
		wg.Add(1)
		sem <- struct{}{}

		go func(t *Ticket) {
			defer wg.Done()
			defer func() { <-sem }()

			result, err := s.AssignTicket(ctx, t)
			mu.Lock()
			defer mu.Unlock()
			if err != nil {
				errs = append(errs, err)
			} else {
				results = append(results, result)
			}
		}(tickets[i])
	}

	wg.Wait()
	return results, errs
}

func (s *TicketAssignmentService) recordFailure() {
	count := atomic.AddInt32(&s.circuitBreakerCount, 1)
	if count >= int32(s.config.CircuitBreakerThreshold) {
		atomic.StoreInt32(&s.circuitBreakerOpen, 1)
		s.circuitBreakerMutex.Lock()
		s.lastFailureTime = time.Now()
		s.circuitBreakerMutex.Unlock()
	}
}

func (s *TicketAssignmentService) ResetCircuitBreaker() {
	atomic.StoreInt32(&s.circuitBreakerOpen, 0)
	atomic.StoreInt32(&s.circuitBreakerCount, 0)
}

func (s *TicketAssignmentService) IsCircuitBreakerOpen() bool {
	return atomic.LoadInt32(&s.circuitBreakerOpen) == 1
}
