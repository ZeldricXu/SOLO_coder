package entity

import (
	"time"
)

type AdversarialPrompt struct {
	ID              string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelID         string                 `gorm:"type:varchar(64);not null;index" json:"model_id"`
	Strategy        string                 `gorm:"type:varchar(128);not null" json:"strategy"`
	OriginalPrompt  string                 `gorm:"type:text;not null" json:"original_prompt"`
	AdversarialText string                 `gorm:"type:text;not null" json:"adversarial_text"`
	AttackType      string                 `gorm:"type:varchar(64);not null" json:"attack_type"`
	SuccessRate     float64                `json:"success_rate"`
	Metadata        map[string]interface{} `gorm:"type:jsonb" json:"metadata,omitempty"`
	GeneratedBy     string                 `gorm:"type:varchar(128)" json:"generated_by"`
	CreatedAt       time.Time              `gorm:"not null" json:"created_at"`
}

type AttackStrategy struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name        string                 `gorm:"type:varchar(256);not null;uniqueIndex" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Type        string                 `gorm:"type:varchar(64);not null" json:"type"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	Enabled     bool                   `gorm:"default:true" json:"enabled"`
	Severity    string                 `gorm:"type:varchar(64)" json:"severity"`
	CreatedAt   time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"not null" json:"updated_at"`
}

type SecurityAssessment struct {
	ID                string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelID           string                 `gorm:"type:varchar(64);not null;index" json:"model_id"`
	Name              string                 `gorm:"type:varchar(256);not null" json:"name"`
	Status            string                 `gorm:"type:varchar(64);not null;index" json:"status"`
	Strategies        []string               `gorm:"type:jsonb" json:"strategies"`
	TotalTests        int                    `json:"total_tests"`
	SuccessfulAttacks int                    `json:"successful_attacks"`
	FailedAttacks     int                    `json:"failed_attacks"`
	OverallScore      float64                `json:"overall_score"`
	RiskLevel         string                 `gorm:"type:varchar(64)" json:"risk_level"`
	Details           map[string]interface{} `gorm:"type:jsonb" json:"details,omitempty"`
	StartTime         time.Time              `json:"start_time"`
	EndTime           *time.Time             `json:"end_time,omitempty"`
	CreatedBy         string                 `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt         time.Time              `gorm:"not null" json:"created_at"`
}

type Vulnerability struct {
	ID              string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	AssessmentID    string                 `gorm:"type:varchar(64);not null;index" json:"assessment_id"`
	Type            string                 `gorm:"type:varchar(128);not null" json:"type"`
	Severity        string                 `gorm:"type:varchar(64);not null" json:"severity"`
	Description     string                 `gorm:"type:text" json:"description"`
	ExamplePrompt   string                 `gorm:"type:text" json:"example_prompt"`
	ExampleResponse string                 `gorm:"type:text" json:"example_response"`
	Status          string                 `gorm:"type:varchar(64);not null" json:"status"`
	Metadata        map[string]interface{} `gorm:"type:jsonb" json:"metadata,omitempty"`
	DiscoveredAt    time.Time              `gorm:"not null" json:"discovered_at"`
}

type AttackType string

const (
	AttackTypeJailbreak       AttackType = "jailbreak"
	AttackTypePromptInjection AttackType = "prompt_injection"
	AttackTypeDataLeakage     AttackType = "data_leakage"
	AttackTypeAdversarialSuf  AttackType = "adversarial_suffix"
	AttackTypeRolePlay        AttackType = "role_play"
	AttackTypeObfuscation     AttackType = "obfuscation"
)

type RiskLevel string

const (
	RiskLevelCritical RiskLevel = "critical"
	RiskLevelHigh     RiskLevel = "high"
	RiskLevelMedium   RiskLevel = "medium"
	RiskLevelLow      RiskLevel = "low"
)
