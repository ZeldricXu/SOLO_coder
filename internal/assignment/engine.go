package assignment

import (
	"fmt"
	"math"
	"sort"
	"sync"
	"time"
)

type WorkOrderPriority int

const (
	PriorityLow WorkOrderPriority = iota
	PriorityMedium
	PriorityHigh
	PriorityCritical
)

func (p WorkOrderPriority) String() string {
	switch p {
	case PriorityLow:
		return "low"
	case PriorityMedium:
		return "medium"
	case PriorityHigh:
		return "high"
	case PriorityCritical:
		return "critical"
	default:
		return "unknown"
	}
}

type WorkOrderStatus string

const (
	StatusPending   WorkOrderStatus = "pending"
	StatusAssigned  WorkOrderStatus = "assigned"
	StatusInProgress WorkOrderStatus = "in_progress"
	StatusCompleted WorkOrderStatus = "completed"
	StatusEscalated WorkOrderStatus = "escalated"
)

type WorkOrder struct {
	ID              string            `json:"id"`
	Title           string            `json:"title"`
	Description     string            `json:"description"`
	Priority        WorkOrderPriority `json:"priority"`
	Status          WorkOrderStatus   `json:"status"`
	RequiredSkills  map[string]float64 `json:"required_skills"`
	Category        string            `json:"category"`
	TenantID        string            `json:"tenant_id"`
	CreatedAt       time.Time         `json:"created_at"`
	AssignedTo      string            `json:"assigned_to,omitempty"`
	AssignedAt      *time.Time        `json:"assigned_at,omitempty"`
	SLATimeout      time.Duration     `json:"sla_timeout"`
	SkillWeight     float64           `json:"skill_weight"`
	LoadWeight      float64           `json:"load_weight"`
}

type Agent struct {
	ID         string             `json:"id"`
	Name       string             `json:"name"`
	Department string             `json:"department"`
	Skills     map[string]float64 `json:"skills"`
	MaxLoad    int                `json:"max_load"`
	ActiveOrders int              `json:"active_orders"`
	IsOnline   bool               `json:"is_online"`
	TenantID   string             `json:"tenant_id"`
}

type AssignmentResult struct {
	WorkOrderID  string  `json:"work_order_id"`
	AgentID      string  `json:"agent_id"`
	AgentName    string  `json:"agent_name"`
	SkillScore   float64 `json:"skill_score"`
	LoadScore    float64 `json:"load_score"`
	TotalScore   float64 `json:"total_score"`
	Reason       string  `json:"reason"`
}

type LoadBalancer struct {
	mu     sync.RWMutex
	agents map[string]*Agent
}

func NewLoadBalancer() *LoadBalancer {
	return &LoadBalancer{
		agents: make(map[string]*Agent),
	}
}

func (lb *LoadBalancer) RegisterAgent(agent Agent) {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	lb.agents[agent.ID] = &agent
}

func (lb *LoadBalancer) RemoveAgent(agentID string) {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	delete(lb.agents, agentID)
}

func (lb *LoadBalancer) SetAgentOnline(agentID string, online bool) error {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	agent, ok := lb.agents[agentID]
	if !ok {
		return fmt.Errorf("agent %s not found", agentID)
	}
	agent.IsOnline = online
	return nil
}

func (lb *LoadBalancer) IncrementLoad(agentID string) error {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	agent, ok := lb.agents[agentID]
	if !ok {
		return fmt.Errorf("agent %s not found", agentID)
	}
	if agent.ActiveOrders >= agent.MaxLoad {
		return fmt.Errorf("agent %s has reached max load", agentID)
	}
	agent.ActiveOrders++
	return nil
}

func (lb *LoadBalancer) DecrementLoad(agentID string) error {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	agent, ok := lb.agents[agentID]
	if !ok {
		return fmt.Errorf("agent %s not found", agentID)
	}
	if agent.ActiveOrders > 0 {
		agent.ActiveOrders--
	}
	return nil
}

func (lb *LoadBalancer) GetLoadScore(agentID string) float64 {
	lb.mu.RLock()
	defer lb.mu.RUnlock()
	agent, ok := lb.agents[agentID]
	if !ok {
		return 0
	}
	if agent.MaxLoad == 0 {
		return 0
	}
	loadRatio := float64(agent.ActiveOrders) / float64(agent.MaxLoad)
	return 1.0 - loadRatio
}

func (lb *LoadBalancer) GetAvailableAgents(tenantID string) []*Agent {
	lb.mu.RLock()
	defer lb.mu.RUnlock()
	var available []*Agent
	for _, agent := range lb.agents {
		if agent.IsOnline && agent.ActiveOrders < agent.MaxLoad {
			if tenantID == "" || agent.TenantID == tenantID {
				available = append(available, agent)
			}
		}
	}
	return available
}

func (lb *LoadBalancer) GetAgent(agentID string) (*Agent, bool) {
	lb.mu.RLock()
	defer lb.mu.RUnlock()
	a, ok := lb.agents[agentID]
	return a, ok
}

type SkillMatcher struct{}

func NewSkillMatcher() *SkillMatcher {
	return &SkillMatcher{}
}

func (sm *SkillMatcher) CalculateMatchScore(agentSkills map[string]float64, requiredSkills map[string]float64) float64 {
	if len(requiredSkills) == 0 {
		return 1.0
	}
	var totalWeight float64
	var totalScore float64
	for skillID, requiredLevel := range requiredSkills {
		totalWeight += requiredLevel
		agentLevel, has := agentSkills[skillID]
		if !has {
			totalScore += 0
			continue
		}
		if agentLevel >= requiredLevel {
			totalScore += requiredLevel
		} else {
			ratio := agentLevel / requiredLevel
			totalScore += requiredLevel * ratio
		}
	}
	if totalWeight == 0 {
		return 0
	}
	return totalScore / totalWeight
}

func (sm *SkillMatcher) FindSkillGaps(agentSkills map[string]float64, requiredSkills map[string]float64) map[string]float64 {
	gaps := make(map[string]float64)
	for skillID, requiredLevel := range requiredSkills {
		agentLevel, has := agentSkills[skillID]
		if !has {
			gaps[skillID] = requiredLevel
		} else if agentLevel < requiredLevel {
			gaps[skillID] = requiredLevel - agentLevel
		}
	}
	return gaps
}

type RoutingStrategy int

const (
	StrategySkillOnly RoutingStrategy = iota
	StrategyLoadOnly
	StrategyWeighted
	StrategyAdaptive
)

type AssignmentEngine struct {
	skillMatcher *SkillMatcher
	loadBalancer *LoadBalancer
	strategy     RoutingStrategy
	skillWeight  float64
	loadWeight   float64
	mu           sync.Mutex
	assignments  map[string]*AssignmentResult
}

func NewAssignmentEngine(strategy RoutingStrategy) *AssignmentEngine {
	engine := &AssignmentEngine{
		skillMatcher: NewSkillMatcher(),
		loadBalancer: NewLoadBalancer(),
		strategy:     strategy,
		skillWeight:  0.6,
		loadWeight:   0.4,
		assignments:  make(map[string]*AssignmentResult),
	}
	switch strategy {
	case StrategySkillOnly:
		engine.skillWeight = 1.0
		engine.loadWeight = 0.0
	case StrategyLoadOnly:
		engine.skillWeight = 0.0
		engine.loadWeight = 1.0
	case StrategyWeighted:
		engine.skillWeight = 0.6
		engine.loadWeight = 0.4
	case StrategyAdaptive:
		engine.skillWeight = 0.5
		engine.loadWeight = 0.5
	}
	return engine
}

func (ae *AssignmentEngine) GetLoadBalancer() *LoadBalancer {
	return ae.loadBalancer
}

func (ae *AssignmentEngine) SetWeights(skillWeight, loadWeight float64) {
	total := skillWeight + loadWeight
	if total > 0 {
		ae.skillWeight = skillWeight / total
		ae.loadWeight = loadWeight / total
	}
}

func (ae *AssignmentEngine) AssignWorkOrder(order *WorkOrder) (*AssignmentResult, error) {
	ae.mu.Lock()
	defer ae.mu.Unlock()

	if order.Status != StatusPending {
		return nil, fmt.Errorf("work order %s is not in pending state", order.ID)
	}

	availableAgents := ae.loadBalancer.GetAvailableAgents(order.TenantID)
	if len(availableAgents) == 0 {
		return nil, fmt.Errorf("no available agents for work order %s", order.ID)
	}

	skillWeight := ae.skillWeight
	loadWeight := ae.loadWeight

	if ae.strategy == StrategyAdaptive {
		skillWeight, loadWeight = ae.adaptiveWeights(availableAgents, order)
	}

	type candidateScore struct {
		agent      *Agent
		skillScore float64
		loadScore  float64
		totalScore float64
	}

	var candidates []candidateScore
	for _, agent := range availableAgents {
		skillScore := ae.skillMatcher.CalculateMatchScore(agent.Skills, order.RequiredSkills)
		loadScore := ae.loadBalancer.GetLoadScore(agent.ID)
		totalScore := skillWeight*skillScore + loadWeight*loadScore
		priorityBoost := float64(order.Priority) * 0.05
		totalScore += priorityBoost
		candidates = append(candidates, candidateScore{
			agent:      agent,
			skillScore: skillScore,
			loadScore:  loadScore,
			totalScore: totalScore,
		})
	}

	sort.Slice(candidates, func(i, j int) bool {
		return candidates[i].totalScore > candidates[j].totalScore
	})

	best := candidates[0]
	ae.loadBalancer.IncrementLoad(best.agent.ID)

	now := time.Now()
	order.Status = StatusAssigned
	order.AssignedTo = best.agent.ID
	order.AssignedAt = &now

	result := &AssignmentResult{
		WorkOrderID: order.ID,
		AgentID:     best.agent.ID,
		AgentName:   best.agent.Name,
		SkillScore:  best.skillScore,
		LoadScore:   best.loadScore,
		TotalScore:  best.totalScore,
		Reason:      ae.buildReason(best.skillScore, best.loadScore, best.totalScore, skillWeight, loadWeight),
	}
	ae.assignments[order.ID] = result
	return result, nil
}

func (ae *AssignmentEngine) adaptiveWeights(agents []*Agent, order *WorkOrder) (float64, float64) {
	var skillVariance float64
	var loadVariance float64
	var avgSkill float64
	var avgLoad float64
	for _, agent := range agents {
		avgSkill += ae.skillMatcher.CalculateMatchScore(agent.Skills, order.RequiredSkills)
		avgLoad += ae.loadBalancer.GetLoadScore(agent.ID)
	}
	n := float64(len(agents))
	if n > 0 {
		avgSkill /= n
		avgLoad /= n
	}
	for _, agent := range agents {
		s := ae.skillMatcher.CalculateMatchScore(agent.Skills, order.RequiredSkills)
		l := ae.loadBalancer.GetLoadScore(agent.ID)
		skillVariance += math.Pow(s-avgSkill, 2)
		loadVariance += math.Pow(l-avgLoad, 2)
	}
	if n > 1 {
		skillVariance /= n
		loadVariance /= n
	}
	totalVariance := skillVariance + loadVariance
	if totalVariance == 0 {
		return 0.5, 0.5
	}
	skillWeight := skillVariance / totalVariance
	loadWeight := loadVariance / totalVariance
	return skillWeight, loadWeight
}

func (ae *AssignmentEngine) buildReason(skillScore, loadScore, totalScore, skillWeight, loadWeight float64) string {
	return fmt.Sprintf("Assigned with total score %.3f (skill: %.3f*%.2f + load: %.3f*%.2f)",
		totalScore, skillScore, skillWeight, loadScore, loadWeight)
}

func (ae *AssignmentEngine) ReassignWorkOrder(order *WorkOrder, fromAgentID string) (*AssignmentResult, error) {
	ae.mu.Lock()
	ae.loadBalancer.DecrementLoad(fromAgentID)
	order.Status = StatusPending
	order.AssignedTo = ""
	order.AssignedAt = nil
	ae.mu.Unlock()

	result, err := ae.AssignWorkOrder(order)
	return result, err
}

func (ae *AssignmentEngine) CompleteWorkOrder(order *WorkOrder) error {
	ae.mu.Lock()
	defer ae.mu.Unlock()

	if order.AssignedTo != "" {
		ae.loadBalancer.DecrementLoad(order.AssignedTo)
	}
	order.Status = StatusCompleted
	return nil
}

func (ae *AssignmentEngine) GetAssignmentHistory(workOrderID string) (*AssignmentResult, bool) {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	r, ok := ae.assignments[workOrderID]
	return r, ok
}

func (ae *AssignmentEngine) BatchAssign(orders []*WorkOrder) ([]*AssignmentResult, []error) {
	var results []*AssignmentResult
	var errs []error
	sort.Slice(orders, func(i, j int) bool {
		return orders[i].Priority > orders[j].Priority
	})
	for _, order := range orders {
		result, err := ae.AssignWorkOrder(order)
		if err != nil {
			errs = append(errs, err)
		} else {
			results = append(results, result)
		}
	}
	return results, errs
}

func (ar *AssignmentResult) Format() string {
	return fmt.Sprintf("WorkOrder %s -> Agent %s (%s) | Score: %.3f (Skill: %.3f, Load: %.3f) | %s",
		ar.WorkOrderID, ar.AgentID, ar.AgentName, ar.TotalScore, ar.SkillScore, ar.LoadScore, ar.Reason)
}
