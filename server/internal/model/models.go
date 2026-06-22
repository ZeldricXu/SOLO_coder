package model

import (
	"database/sql/driver"
	"encoding/json"
	"time"
)

type JSON json.RawMessage

func (j *JSON) Scan(value interface{}) error {
	if value == nil {
		*j = nil
		return nil
	}
	bytes, ok := value.([]byte)
	if !ok {
		return nil
	}
	*j = append((*j)[0:0], bytes...)
	return nil
}

func (j JSON) Value() (driver.Value, error) {
	if len(j) == 0 {
		return nil, nil
	}
	return []byte(j), nil
}

func (j JSON) MarshalJSON() ([]byte, error) {
	if len(j) == 0 {
		return []byte("null"), nil
	}
	return []byte(j), nil
}

func (j *JSON) UnmarshalJSON(data []byte) error {
	*j = append((*j)[0:0], data...)
	return nil
}

const (
	TableUser               = "users"
	TableOnboardingTemplate = "onboarding_templates"
	TableTemplateTaskItem   = "template_task_items"
	TableEmployee           = "employees"
	TableOnboarding         = "onboardings"
	TableOnboardingTask     = "onboarding_tasks"
	TableDocument           = "documents"
	TableDocumentSignature  = "document_signatures"
	TableITAutomationJob    = "it_automation_jobs"
	TablePortalTimelineItem = "portal_timeline_items"
	TableNotification       = "notifications"
	TableSurveyResponse     = "survey_responses"
	TableSelfServiceRequest = "self_service_requests"
)

type User struct {
	ID         string    `json:"id"`
	Username   string    `json:"username"`
	Password   string    `json:"-"`
	Name       string    `json:"name"`
	Email      string    `json:"email"`
	Phone      string    `json:"phone,omitempty"`
	AvatarURL  string    `json:"avatar_url,omitempty"`
	Department string    `json:"department,omitempty"`
	Position   string    `json:"position,omitempty"`
	Role       string    `json:"role"`
	Status     string    `json:"status"`
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
}

func (User) TableName() string { return TableUser }

func (u *User) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &u.ID
	case "username":
		return &u.Username
	case "password":
		return &u.Password
	case "name":
		return &u.Name
	case "email":
		return &u.Email
	case "phone":
		return &u.Phone
	case "avatar_url":
		return &u.AvatarURL
	case "department":
		return &u.Department
	case "position":
		return &u.Position
	case "role":
		return &u.Role
	case "status":
		return &u.Status
	case "created_at":
		return &u.CreatedAt
	case "updated_at":
		return &u.UpdatedAt
	default:
		return nil
	}
}

type OnboardingTemplate struct {
	ID            string    `json:"id"`
	Name          string    `json:"name"`
	Description   string    `json:"description,omitempty"`
	PositionType  string    `json:"position_type"`
	IsDefault     bool      `json:"is_default"`
	CreatedBy     string    `json:"created_by,omitempty"`
	CreatedAt     time.Time `json:"created_at"`
	UpdatedAt     time.Time `json:"updated_at"`
}

func (OnboardingTemplate) TableName() string { return TableOnboardingTemplate }

func (t *OnboardingTemplate) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &t.ID
	case "name":
		return &t.Name
	case "description":
		return &t.Description
	case "position_type":
		return &t.PositionType
	case "is_default":
		return &t.IsDefault
	case "created_by":
		return &t.CreatedBy
	case "created_at":
		return &t.CreatedAt
	case "updated_at":
		return &t.UpdatedAt
	default:
		return nil
	}
}

type TemplateTaskItem struct {
	ID                 string    `json:"id"`
	TemplateID         string    `json:"template_id"`
	Title              string    `json:"title"`
	Description        string    `json:"description,omitempty"`
	Category           string    `json:"category"`
	AssigneeType       string    `json:"assignee_type"`
	AssigneeRole       string    `json:"assignee_role,omitempty"`
	SortOrder          int       `json:"sort_order"`
	DeadlineOffsetHours int      `json:"deadline_offset_hours"`
	IsRequired         bool      `json:"is_required"`
	CreatedAt          time.Time `json:"created_at"`
}

func (TemplateTaskItem) TableName() string { return TableTemplateTaskItem }

func (t *TemplateTaskItem) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &t.ID
	case "template_id":
		return &t.TemplateID
	case "title":
		return &t.Title
	case "description":
		return &t.Description
	case "category":
		return &t.Category
	case "assignee_type":
		return &t.AssigneeType
	case "assignee_role":
		return &t.AssigneeRole
	case "sort_order":
		return &t.SortOrder
	case "deadline_offset_hours":
		return &t.DeadlineOffsetHours
	case "is_required":
		return &t.IsRequired
	case "created_at":
		return &t.CreatedAt
	default:
		return nil
	}
}

type Employee struct {
	ID               string    `json:"id"`
	UserID           string    `json:"user_id,omitempty"`
	Name             string    `json:"name"`
	Email            string    `json:"email"`
	Phone            string    `json:"phone,omitempty"`
	Department       string    `json:"department,omitempty"`
	Position         string    `json:"position,omitempty"`
	PositionType     string    `json:"position_type,omitempty"`
	HireDate         time.Time `json:"hire_date"`
	Status           string    `json:"status"`
	EmergencyContact JSON      `json:"emergency_contact,omitempty"`
	BankInfo         JSON      `json:"bank_info,omitempty"`
	PersonalInfo     JSON      `json:"personal_info,omitempty"`
	CreatedAt        time.Time `json:"created_at"`
	UpdatedAt        time.Time `json:"updated_at"`
}

func (Employee) TableName() string { return TableEmployee }

func (e *Employee) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &e.ID
	case "user_id":
		return &e.UserID
	case "name":
		return &e.Name
	case "email":
		return &e.Email
	case "phone":
		return &e.Phone
	case "department":
		return &e.Department
	case "position":
		return &e.Position
	case "position_type":
		return &e.PositionType
	case "hire_date":
		return &e.HireDate
	case "status":
		return &e.Status
	case "emergency_contact":
		return &e.EmergencyContact
	case "bank_info":
		return &e.BankInfo
	case "personal_info":
		return &e.PersonalInfo
	case "created_at":
		return &e.CreatedAt
	case "updated_at":
		return &e.UpdatedAt
	default:
		return nil
	}
}

type Onboarding struct {
	ID               string     `json:"id"`
	EmployeeID       string     `json:"employee_id"`
	TemplateID       string     `json:"template_id"`
	HRID             string     `json:"hr_id"`
	MentorID         string     `json:"mentor_id,omitempty"`
	LeaderID         string     `json:"leader_id,omitempty"`
	Status           string     `json:"status"`
	StartDate        time.Time  `json:"start_date"`
	Progress         int        `json:"progress"`
	PortalToken      string     `json:"portal_token,omitempty"`
	PortalExpiresAt  *time.Time `json:"portal_expires_at,omitempty"`
	Notes            string     `json:"notes,omitempty"`
	CreatedAt        time.Time  `json:"created_at"`
	UpdatedAt        time.Time  `json:"updated_at"`
}

func (Onboarding) TableName() string { return TableOnboarding }

func (o *Onboarding) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &o.ID
	case "employee_id":
		return &o.EmployeeID
	case "template_id":
		return &o.TemplateID
	case "hr_id":
		return &o.HRID
	case "mentor_id":
		return &o.MentorID
	case "leader_id":
		return &o.LeaderID
	case "status":
		return &o.Status
	case "start_date":
		return &o.StartDate
	case "progress":
		return &o.Progress
	case "portal_token":
		return &o.PortalToken
	case "portal_expires_at":
		return &o.PortalExpiresAt
	case "notes":
		return &o.Notes
	case "created_at":
		return &o.CreatedAt
	case "updated_at":
		return &o.UpdatedAt
	default:
		return nil
	}
}

type OnboardingTask struct {
	ID              string     `json:"id"`
	OnboardingID    string     `json:"onboarding_id"`
	TemplateItemID  string     `json:"template_item_id,omitempty"`
	Title           string     `json:"title"`
	Description     string     `json:"description,omitempty"`
	Category        string     `json:"category"`
	AssigneeType    string     `json:"assignee_type"`
	AssigneeID      string     `json:"assignee_id,omitempty"`
	Status          string     `json:"status"`
	Priority        string     `json:"priority"`
	Deadline        time.Time  `json:"deadline"`
	CompletedAt     *time.Time `json:"completed_at,omitempty"`
	CompletedBy     string     `json:"completed_by,omitempty"`
	EscalationCount int        `json:"escalation_count"`
	SortOrder       int        `json:"sort_order"`
	Notes           string     `json:"notes,omitempty"`
	CreatedAt       time.Time  `json:"created_at"`
	UpdatedAt       time.Time  `json:"updated_at"`
}

func (OnboardingTask) TableName() string { return TableOnboardingTask }

func (t *OnboardingTask) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &t.ID
	case "onboarding_id":
		return &t.OnboardingID
	case "template_item_id":
		return &t.TemplateItemID
	case "title":
		return &t.Title
	case "description":
		return &t.Description
	case "category":
		return &t.Category
	case "assignee_type":
		return &t.AssigneeType
	case "assignee_id":
		return &t.AssigneeID
	case "status":
		return &t.Status
	case "priority":
		return &t.Priority
	case "deadline":
		return &t.Deadline
	case "completed_at":
		return &t.CompletedAt
	case "completed_by":
		return &t.CompletedBy
	case "escalation_count":
		return &t.EscalationCount
	case "sort_order":
		return &t.SortOrder
	case "notes":
		return &t.Notes
	case "created_at":
		return &t.CreatedAt
	case "updated_at":
		return &t.UpdatedAt
	default:
		return nil
	}
}

type Document struct {
	ID            string     `json:"id"`
	OnboardingID  string     `json:"onboarding_id,omitempty"`
	EmployeeID    string     `json:"employee_id"`
	Title         string     `json:"title"`
	DocType       string     `json:"doc_type"`
	Category      string     `json:"category"`
	FileURL       string     `json:"file_url,omitempty"`
	FileKey       string     `json:"file_key,omitempty"`
	Status        string     `json:"status"`
	EsignFlowID   string     `json:"esign_flow_id,omitempty"`
	EsignProvider string     `json:"esign_provider,omitempty"`
	EsignStatus   string     `json:"esign_status"`
	SignedAt      *time.Time `json:"signed_at,omitempty"`
	ContentHash   string     `json:"content_hash,omitempty"`
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
}

func (Document) TableName() string { return TableDocument }

func (d *Document) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &d.ID
	case "onboarding_id":
		return &d.OnboardingID
	case "employee_id":
		return &d.EmployeeID
	case "title":
		return &d.Title
	case "doc_type":
		return &d.DocType
	case "category":
		return &d.Category
	case "file_url":
		return &d.FileURL
	case "file_key":
		return &d.FileKey
	case "status":
		return &d.Status
	case "esign_flow_id":
		return &d.EsignFlowID
	case "esign_provider":
		return &d.EsignProvider
	case "esign_status":
		return &d.EsignStatus
	case "signed_at":
		return &d.SignedAt
	case "content_hash":
		return &d.ContentHash
	case "created_at":
		return &d.CreatedAt
	case "updated_at":
		return &d.UpdatedAt
	default:
		return nil
	}
}

type DocumentSignature struct {
	ID          string     `json:"id"`
	DocumentID  string     `json:"document_id"`
	SignerName  string     `json:"signer_name"`
	SignerEmail string     `json:"signer_email"`
	SignerType  string     `json:"signer_type"`
	SignerID    string     `json:"signer_id,omitempty"`
	Status      string     `json:"status"`
	SignedAt    *time.Time `json:"signed_at,omitempty"`
	SignURL     string     `json:"sign_url,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
}

func (DocumentSignature) TableName() string { return TableDocumentSignature }

func (d *DocumentSignature) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &d.ID
	case "document_id":
		return &d.DocumentID
	case "signer_name":
		return &d.SignerName
	case "signer_email":
		return &d.SignerEmail
	case "signer_type":
		return &d.SignerType
	case "signer_id":
		return &d.SignerID
	case "status":
		return &d.Status
	case "signed_at":
		return &d.SignedAt
	case "sign_url":
		return &d.SignURL
	case "created_at":
		return &d.CreatedAt
	default:
		return nil
	}
}

type ITAutomationJob struct {
	ID           string     `json:"id"`
	OnboardingID string     `json:"onboarding_id"`
	TaskID       string     `json:"task_id,omitempty"`
	JobType      string     `json:"job_type"`
	TargetSystem string     `json:"target_system"`
	Params       JSON       `json:"params"`
	Status       string     `json:"status"`
	Result       JSON       `json:"result,omitempty"`
	RetryCount   int        `json:"retry_count"`
	MaxRetries   int        `json:"max_retries"`
	ErrorMessage string     `json:"error_message,omitempty"`
	StartedAt    *time.Time `json:"started_at,omitempty"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	CreatedAt    time.Time  `json:"created_at"`
	UpdatedAt    time.Time  `json:"updated_at"`
}

func (ITAutomationJob) TableName() string { return TableITAutomationJob }

func (j *ITAutomationJob) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &j.ID
	case "onboarding_id":
		return &j.OnboardingID
	case "task_id":
		return &j.TaskID
	case "job_type":
		return &j.JobType
	case "target_system":
		return &j.TargetSystem
	case "params":
		return &j.Params
	case "status":
		return &j.Status
	case "result":
		return &j.Result
	case "retry_count":
		return &j.RetryCount
	case "max_retries":
		return &j.MaxRetries
	case "error_message":
		return &j.ErrorMessage
	case "started_at":
		return &j.StartedAt
	case "completed_at":
		return &j.CompletedAt
	case "created_at":
		return &j.CreatedAt
	case "updated_at":
		return &j.UpdatedAt
	default:
		return nil
	}
}

type PortalTimelineItem struct {
	ID           string    `json:"id"`
	OnboardingID string    `json:"onboarding_id"`
	Title        string    `json:"title"`
	Description  string    `json:"description,omitempty"`
	Phase        string    `json:"phase"`
	DayOffset    int       `json:"day_offset"`
	TimeSlot     string    `json:"time_slot,omitempty"`
	ItemType     string    `json:"item_type"`
	IsCompleted  bool      `json:"is_completed"`
	SortOrder    int       `json:"sort_order"`
	CreatedAt    time.Time `json:"created_at"`
}

func (PortalTimelineItem) TableName() string { return TablePortalTimelineItem }

func (p *PortalTimelineItem) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &p.ID
	case "onboarding_id":
		return &p.OnboardingID
	case "title":
		return &p.Title
	case "description":
		return &p.Description
	case "phase":
		return &p.Phase
	case "day_offset":
		return &p.DayOffset
	case "time_slot":
		return &p.TimeSlot
	case "item_type":
		return &p.ItemType
	case "is_completed":
		return &p.IsCompleted
	case "sort_order":
		return &p.SortOrder
	case "created_at":
		return &p.CreatedAt
	default:
		return nil
	}
}

type Notification struct {
	ID            string    `json:"id"`
	UserID        string    `json:"user_id"`
	Title         string    `json:"title"`
	Content       string    `json:"content,omitempty"`
	Type          string    `json:"type"`
	ReferenceType string    `json:"reference_type,omitempty"`
	ReferenceID   string    `json:"reference_id,omitempty"`
	IsRead        bool      `json:"is_read"`
	CreatedAt     time.Time `json:"created_at"`
}

func (Notification) TableName() string { return TableNotification }

func (n *Notification) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &n.ID
	case "user_id":
		return &n.UserID
	case "title":
		return &n.Title
	case "content":
		return &n.Content
	case "type":
		return &n.Type
	case "reference_type":
		return &n.ReferenceType
	case "reference_id":
		return &n.ReferenceID
	case "is_read":
		return &n.IsRead
	case "created_at":
		return &n.CreatedAt
	default:
		return nil
	}
}

type SurveyResponse struct {
	ID           string    `json:"id"`
	OnboardingID string    `json:"onboarding_id"`
	EmployeeID   string    `json:"employee_id"`
	OverallScore int       `json:"overall_score,omitempty"`
	Questions    JSON      `json:"questions"`
	Comments     string    `json:"comments,omitempty"`
	SubmittedAt  time.Time `json:"submitted_at"`
}

func (SurveyResponse) TableName() string { return TableSurveyResponse }

func (s *SurveyResponse) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &s.ID
	case "onboarding_id":
		return &s.OnboardingID
	case "employee_id":
		return &s.EmployeeID
	case "overall_score":
		return &s.OverallScore
	case "questions":
		return &s.Questions
	case "comments":
		return &s.Comments
	case "submitted_at":
		return &s.SubmittedAt
	default:
		return nil
	}
}

type SelfServiceRequest struct {
	ID          string     `json:"id"`
	EmployeeID  string     `json:"employee_id"`
	RequestType string     `json:"request_type"`
	Title       string     `json:"title"`
	Description string     `json:"description,omitempty"`
	Status      string     `json:"status"`
	Attachments JSON       `json:"attachments,omitempty"`
	ApprovedBy  string     `json:"approved_by,omitempty"`
	ApprovedAt  *time.Time `json:"approved_at,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
}

func (SelfServiceRequest) TableName() string { return TableSelfServiceRequest }

func (s *SelfServiceRequest) FieldPtr(name string) interface{} {
	switch name {
	case "id":
		return &s.ID
	case "employee_id":
		return &s.EmployeeID
	case "request_type":
		return &s.RequestType
	case "title":
		return &s.Title
	case "description":
		return &s.Description
	case "status":
		return &s.Status
	case "attachments":
		return &s.Attachments
	case "approved_by":
		return &s.ApprovedBy
	case "approved_at":
		return &s.ApprovedAt
	case "created_at":
		return &s.CreatedAt
	case "updated_at":
		return &s.UpdatedAt
	default:
		return nil
	}
}
