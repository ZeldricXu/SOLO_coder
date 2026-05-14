package models

import "time"

type BackupVersion struct {
	VersionID    string    `json:"version_id"`
	SourcePath   string    `json:"source_path"`
	BackupPath   string    `json:"backup_path"`
	CreatedAt    time.Time `json:"created_at"`
	FileCount    int       `json:"file_count"`
	ChangedCount int       `json:"changed_count"`
	BackupSize   int64     `json:"backup_size"`
	Checksum     string    `json:"checksum"`
	Type         string    `json:"type"`
}

type FileChangeRecord struct {
	ChangeID   string `json:"change_id"`
	VersionID  string `json:"version_id"`
	FilePath   string `json:"file_path"`
	ChangeType string `json:"change_type"`
	OldHash    string `json:"old_hash"`
	NewHash    string `json:"new_hash"`
	FileSize   int64  `json:"file_size"`
}

type FileInfo struct {
	RelativePath string    `json:"relative_path"`
	FullPath     string    `json:"full_path"`
	Size         int64     `json:"size"`
	ModTime      time.Time `json:"mod_time"`
	Hash         string    `json:"hash"`
}

type BackupLog struct {
	LogID     string    `json:"log_id"`
	Operation string    `json:"operation"`
	VersionID string    `json:"version_id"`
	Status    string    `json:"status"`
	Duration  int64     `json:"duration"`
	Errors    []string  `json:"errors"`
	LoggedAt  time.Time `json:"logged_at"`
}

type ScheduledTask struct {
	TaskID        string    `json:"task_id"`
	SourcePath    string    `json:"source_path"`
	BackupPath    string    `json:"backup_path"`
	Schedule      string    `json:"schedule"`
	Enabled       bool      `json:"enabled"`
	CreatedAt     time.Time `json:"created_at"`
	LastRunAt     time.Time `json:"last_run_at"`
	LastRunStatus string    `json:"last_run_status"`
}

type BackupResult struct {
	VersionID     string
	Success       bool
	FileCount     int
	ChangedCount  int
	AddedCount    int
	ModifiedCount int
	DeletedCount  int
	BackupSize    int64
	Errors        []string
	Duration      time.Duration
}

type RestoreResult struct {
	VersionID     string
	Success       bool
	RestoredCount int
	FailedCount   int
	TotalSize     int64
	Errors        []string
	Duration      time.Duration
}

type AppConfig struct {
	VersionRetention int      `json:"version_retention"`
	HashWorkers      int      `json:"hash_workers"`
	ScheduleTasks    []ScheduleTaskConfig `json:"scheduled_tasks"`
}

type ScheduleTaskConfig struct {
	ID       string `json:"id"`
	Source   string `json:"source"`
	Schedule string `json:"schedule"`
	Enabled  bool   `json:"enabled"`
}

type VersionRetentionPolicy struct {
	MaxVersions int `json:"max_versions"`
}
