package approvalengine

import (
	"time"
)

type ApprovalType string

const (
	ApprovalTypeANY ApprovalType = "ANY"
	ApprovalTypeALL ApprovalType = "ALL"
)

type NodeStatus string

const (
	NodeStatusPending  NodeStatus = "PENDING"
	NodeStatusApproved NodeStatus = "APPROVED"
	NodeStatusRejected NodeStatus = "REJECTED"
)

type ApprovalRequest struct {
	ID              string
	ProcessCode     string
	BusinessKey     string
	Title           string
	Amount          float64
	Initiator       string
	InitiatorDept   string
	FormData        map[string]interface{}
	CreatedAt       time.Time
}

type ApprovalNode struct {
	ID            string
	NodeCode      string
	NodeName      string
	ApprovalType  ApprovalType
	Approvers     []string
	Conditions    []Condition
	NextNodes     []string
	IsStart       bool
	IsEnd         bool
	TimeoutSec    int
	EscalateTo    string
}

type Condition struct {
	Field    string
	Operator string
	Value    interface{}
}

type ApprovalInstance struct {
	ID              string
	RequestID       string
	CurrentNodeID   string
	CurrentNodeCode string
	Status          NodeStatus
	ApprovedBy      []string
	RejectedBy      string
	RejectReason    string
	ApprovalHistory []ApprovalRecord
	CreatedAt       time.Time
	UpdatedAt       time.Time
	CompletedAt     *time.Time
}

type ApprovalRecord struct {
	ID         string
	InstanceID string
	NodeID     string
	Approver   string
	Action     NodeStatus
	Reason     string
	OperatedAt time.Time
}

type ApprovalResult struct {
	InstanceID string
	Status     NodeStatus
	CurrentNode string
	ApprovedBy []string
	Completed  bool
}

type EngineConfig struct {
	TimeoutMs            int
	CircuitBreakerThreshold int
}

type ProcessRepository interface {
	GetNodeByCode(processCode, nodeCode string) (*ApprovalNode, error)
	GetStartNode(processCode string) (*ApprovalNode, error)
	GetNode(processCode, nodeID string) (*ApprovalNode, error)
}

type InstanceRepository interface {
	Save(instance *ApprovalInstance) error
	FindByID(id string) (*ApprovalInstance, error)
	Update(instance *ApprovalInstance) error
}

type NotificationService interface {
	SendApprovalNotification(approver string, instance *ApprovalInstance, node *ApprovalNode) error
	SendResultNotification(initiator string, result *ApprovalResult) error
}
