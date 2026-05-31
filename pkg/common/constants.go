package common

const (
	AppName = "TechPlatform"
	Version = "1.0.0"

	CacheKeyPrefix      = "techplatform:"
	CacheDefaultTTL      = 3600
	CacheShortTTL     = 300

	EnvProduction  = "production"
	EnvStaging   = "staging"
	EnvDevelopment = "development"

	DocSourceConfluence = "confluence"
	DocSourceGitlab   = "gitlab"
	DocSourceLocal    = "local"
	DocSourceNotion   = "notion"

	TaskStatusPending   = "pending"
	TaskStatusRunning = "running"
	TaskStatusSuccess = "success"
	TaskStatusFailed  = "failed"

	NotifyLevelInfo     = "info"
	NotifyLevelWarning  = "warning"
	NotifyLevelCritical = "critical"
	NotifyLevelError    = "error"

	EnvStatusCreating = "creating"
	EnvStatusRunning  = "running"
	EnvStatusStopped  = "stopped"
	EnvStatusFailed   = "failed"

	SeverityCritical = "CRITICAL"
	SeverityHigh     = "HIGH"
	SeverityMedium   = "MEDIUM"
	SeverityLow      = "LOW"
)
