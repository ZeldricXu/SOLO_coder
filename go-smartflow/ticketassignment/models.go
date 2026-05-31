package ticketassignment

import (
	"time"
)

type Skill struct {
	ID         string
	Name       string
	Proficiency int
}

type Employee struct {
	ID           string
	Name         string
	Skills       []Skill
	CurrentLoad  int
	MaxLoad      int
	IsAvailable  bool
	LastAssigned time.Time
}

type Ticket struct {
	ID             string
	Title          string
	Description    string
	RequiredSkills []string
	Priority       int
	CreatedAt      time.Time
	Status         string
	AssignedTo     string
}

type AssignmentResult struct {
	TicketID    string
	EmployeeID  string
	EmployeeName string
	Score       float64
	MatchedSkills []string
	LoadRatio   float64
}

type AssignmentConfig struct {
	SkillMatchWeight     float64
	ProficiencyWeight    float64
	LoadBalanceWeight    float64
	MinMatchThreshold    float64
	TimeoutMs            int
	CircuitBreakerThreshold int
}

type EmployeeRepository interface {
	FindAvailable() ([]Employee, error)
	FindByID(id string) (*Employee, error)
	UpdateLoad(employeeID string, delta int) error
}

type TicketRepository interface {
	FindByID(id string) (*Ticket, error)
	UpdateAssignee(ticketID, employeeID string) error
	Save(ticket *Ticket) error
}

type Cache interface {
	Get(key string) (interface{}, bool)
	Set(key string, value interface{}, ttl time.Duration)
	Delete(key string)
}
