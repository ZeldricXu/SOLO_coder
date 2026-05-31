package ticket

import (
	"sort"
	"time"

	"gorm.io/gorm"
	"session187/internal/common"
	"session187/pkg/errors"
)

type TicketStatus string

const (
	StatusOpen     TicketStatus = "open"
	StatusAssigned TicketStatus = "assigned"
	StatusInProgress TicketStatus = "in_progress"
	StatusResolved TicketStatus = "resolved"
	StatusClosed   TicketStatus = "closed"
)

type TicketPriority string

const (
	PriorityLow    TicketPriority = "low"
	PriorityMedium TicketPriority = "medium"
	PriorityHigh  TicketPriority = "high"
	PriorityUrgent TicketPriority = "urgent"
)

type Ticket struct {
	ID             string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID         string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Title             string                 `json:"title" gorm:"type:varchar(256)"`
	Description       string                 `json:"description" gorm:"type:text"`
	Status            TicketStatus           `json:"status" gorm:"type:varchar(32);index"`
	Priority          TicketPriority         `json:"priority" gorm:"type:varchar(32);index"`
	Category          string                 `json:"category" gorm:"type:varchar(64);index"`
	Tags              []string               `json:"tags" gorm:"type:jsonb;serializer:json"`
	RequiredSkills    []string               `json:"required_skills" gorm:"type:jsonb;serializer:json"`
	ReporterID        string                 `json:"reporter_id" gorm:"type:varchar(64)"`
	ReporterName      string                 `json:"reporter_name"`
	AssigneeID        string                 `json:"assignee_id" gorm:"type:varchar(64);index"`
	AssigneeName      string                 `json:"assignee_name"`
	ExpectedResolution *time.Time             `json:"expected_resolution"`
	Resolution        string                 `json:"resolution" gorm:"type:text"`
	Metadata          map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt         time.Time              `json:"created_at"`
	UpdatedAt         time.Time              `json:"updated_at"`
	ResolvedAt        *time.Time             `json:"resolved_at"`
	ClosedAt          *time.Time             `json:"closed_at"`
}

type Agent struct {
	ID            string                 `json:"id" gorm:"type:varchar(64)"`
	TenantID        string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Name            string                 `json:"name" gorm:"type:varchar(128)"`
	Email           string                 `json:"email" gorm:"type:varchar(128)"`
	Skills          []string               `json:"skills" gorm:"type:jsonb;serializer:json"`
	SkillLevels     map[string]int         `json:"skill_levels" gorm:"type:jsonb"`
	CurrentLoad     int                    `json:"current_load" gorm:"default:0"`
	MaxLoad         int                    `json:"max_load" gorm:"default:10"`
	Status          string                 `json:"status" gorm:"type:varchar(32);index"`
	AvailableHours    []string               `json:"available_hours" gorm:"type:jsonb;serializer:json"`
	PerformanceScore float64              `json:"performance_score" gorm:"default:0"`
	Metadata        map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
}

type AssignmentResult struct {
	AgentID    string  `json:"agent_id"`
	AgentName  string  `json:"agent_name"`
	MatchScore float64 `json:"match_score"`
	SkillMatch float64 `json:"skill_match"`
	LoadFactor float64 `json:"load_factor"`
	Reason    string  `json:"reason"`
}

type Allocator struct {
	db *gorm.DB
}

func NewAllocator(db *gorm.DB) *Allocator {
	return &Allocator{db: db}
}

func (a *Allocator) CreateTicket(tenantID, title, description string, priority TicketPriority, category string, tags, requiredSkills []string, reporterID, reporterName string) (*Ticket, error) {
	ticket := &Ticket{
		ID:          common.GenerateID("tkt"),
		TenantID:      tenantID,
		Title:         title,
		Description:   description,
		Status:        StatusOpen,
		Priority:      priority,
		Category:      category,
		Tags:          tags,
		RequiredSkills: requiredSkills,
		ReporterID:    reporterID,
		ReporterName:  reporterName,
		CreatedAt:     common.TimeNowUTC(),
		UpdatedAt:     common.TimeNowUTC(),
	}
	if err := a.db.Create(ticket).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建工单失败", err.Error())
	}
	return ticket, nil
}

func (a *Allocator) GetTicket(tenantID, ticketID string) (*Ticket, error) {
	var ticket Ticket
	err := a.db.Where("id = ? AND tenant_id = ?", ticketID, tenantID).First(&ticket).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询工单失败", err.Error())
	}
	return &ticket, nil
}

func (a *Allocator) ListTickets(tenantID string, status string, assigneeID string, page, pageSize int) ([]Ticket, int64, error) {
	var tickets []Ticket
	var total int64
	query := a.db.Model(&Ticket{}).Where("tenant_id = ?", tenantID)
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if assigneeID != "" {
		query = query.Where("assignee_id = ?", assigneeID)
	}
	query.Count(&total)
	offset := (page - 1) * pageSize
	err := query.Order("created_at desc").Offset(offset).Limit(pageSize).Find(&tickets).Error
	if err != nil {
		return nil, 0, errors.NewWithDetail(500, "查询工单列表失败", err.Error())
	}
	return tickets, total, nil
}

func (a *Allocator) CreateAgent(tenantID, name, email string, skills []string, skillLevels map[string]int, maxLoad int) (*Agent, error) {
	agent := &Agent{
		ID:          common.GenerateID("agt"),
		TenantID:    tenantID,
		Name:          name,
		Email:         email,
		Skills:        skills,
		SkillLevels:   skillLevels,
		MaxLoad:       maxLoad,
		Status:        "available",
		CurrentLoad:   0,
		PerformanceScore: 0.85,
		CreatedAt:     common.TimeNowUTC(),
		UpdatedAt:     common.TimeNowUTC(),
	}
	if err := a.db.Create(agent).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建客服失败", err.Error())
	}
	return agent, nil
}

func (a *Allocator) GetAgent(tenantID, agentID string) (*Agent, error) {
	var agent Agent
	err := a.db.Where("id = ? AND tenant_id = ?", agentID, tenantID).First(&agent).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询客服失败", err.Error())
	}
	return &agent, nil
}

func (a *Allocator) ListAgents(tenantID string) ([]Agent, error) {
	var agents []Agent
	err := a.db.Where("tenant_id = ?", tenantID).Find(&agents).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询客服列表失败", err.Error())
	}
	return agents, nil
}

func (a *Allocator) AssignTicket(tenantID, ticketID string) (*AssignmentResult, error) {
	ticket, err := a.GetTicket(tenantID, ticketID)
	if err != nil {
		return nil, err
	}
	agents, err := a.ListAgents(tenantID)
	if err != nil {
		return nil, err
	}
	candidates := a.findCandidates(ticket, agents)
	if len(candidates) == 0 {
		return nil, errors.New(404, "没有找到合适的客服")
	}
	best := candidates[0]
	ticket.AssigneeID = best.AgentID
	ticket.AssigneeName = best.AgentName
	ticket.Status = StatusAssigned
	ticket.UpdatedAt = common.TimeNowUTC()
	a.db.Save(ticket)
	for _, agent := range agents {
		if agent.ID == best.AgentID {
			agent.CurrentLoad++
			agent.UpdatedAt = common.TimeNowUTC()
			a.db.Save(&agent)
			break
		}
	}
	return &best, nil
}

func (a *Allocator) findCandidates(ticket *Ticket, agents []Agent) []AssignmentResult {
	var results []AssignmentResult
	for _, agent := range agents {
		if agent.Status != "available" {
			continue
		}
		skillMatch := a.calculateSkillMatch(ticket.RequiredSkills, agent.Skills, agent.SkillLevels)
		loadFactor := a.calculateLoadFactor(agent.CurrentLoad, agent.MaxLoad)
		matchScore := skillMatch*0.6 + loadFactor*0.3 + agent.PerformanceScore*0.1
		results = append(results, AssignmentResult{
			AgentID:    agent.ID,
			AgentName:  agent.Name,
			MatchScore: matchScore,
			SkillMatch: skillMatch,
			LoadFactor: loadFactor,
			Reason:    a.generateReason(skillMatch, loadFactor, agent),
		})
	}
	sort.Slice(results, func(i, j int) bool {
		return results[i].MatchScore > results[j].MatchScore
	})
	return results
}

func (a *Allocator) calculateSkillMatch(required []string, agentSkills []string, skillLevels map[string]int) float64 {
	if len(required) == 0 {
		return 1.0
	}
	agentSkillMap := make(map[string]int)
	for _, s := range agentSkills {
		agentSkillMap[s] = skillLevels[s]
	}
	totalScore := 0
	maxScore := len(required) * 5
	for _, req := range required {
		if level, ok := agentSkillMap[req]; ok {
			totalScore += level
		}
	}
	return float64(totalScore) / float64(maxScore)
}

func (a *Allocator) calculateLoadFactor(currentLoad, maxLoad int) float64 {
	if maxLoad == 0 {
		return 0
	}
	return 1.0 - float64(currentLoad)/float64(maxLoad)
}

func (a *Allocator) generateReason(skillMatch, loadFactor float64, agent Agent) string {
	reasons := []string{}
	if skillMatch >= 0.8 {
		reasons = append(reasons, "技能高度匹配")
	} else if skillMatch >= 0.5 {
		reasons = append(reasons, "技能部分匹配")
	}
	if loadFactor >= 0.7 {
		reasons = append(reasons, "工作负载较低")
	}
	if len := len(reasons)
	if len == 0 {
		return "默认分配"
	}
	result := ""
	for i, r := range reasons {
		if i > 0 {
			result += "，"
		}
		result += r
	}
	return result
}

func (a *Allocator) UpdateTicketStatus(tenantID, ticketID string, status TicketStatus, resolution string) (*Ticket, error) {
	ticket, err := a.GetTicket(tenantID, ticketID)
	if err != nil {
		return nil, err
	}
	now := common.TimeNowUTC()
	ticket.Status = status
	ticket.UpdatedAt = now
	if status == StatusResolved {
		ticket.ResolvedAt = &now
		ticket.Resolution = resolution
	}
	if status == StatusClosed {
		ticket.ClosedAt = &now
	}
	if status == StatusClosed || status == StatusResolved {
		a.db.Model(&Agent{}).Where("id = ?", ticket.AssigneeID).UpdateColumn("current_load", gorm.Expr("current_load - 1"))
	}
	if err := a.db.Save(ticket).Error; err != nil {
		return nil, errors.NewWithDetail(500, "更新工单状态失败", err.Error())
	}
	return ticket, nil
}

func (a *Allocator) GetAgentLoad(tenantID, agentID string) (map[string]interface{}, error) {
	agent, err := a.GetAgent(tenantID, agentID)
	if err != nil {
		return nil, err
	}
	var openTickets int64
	a.db.Model(&Ticket{}).Where("assignee_id = ? AND status IN ?", agentID, []string{string(StatusAssigned), string(StatusInProgress)}).Count(&openTickets)
	return map[string]interface{}{
		"agent_id":      agentID,
		"current_load":  agent.CurrentLoad,
		"max_load":    agent.MaxLoad,
		"open_tickets": openTickets,
		"utilization": float64(agent.CurrentLoad) / float64(agent.MaxLoad),
	}, nil
}

func (t *Ticket) TableName() string {
	return "tickets"
}

func (a *Agent) TableName() string {
	return "ticket_agents"
}
