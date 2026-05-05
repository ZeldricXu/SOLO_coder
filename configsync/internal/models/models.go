package models

import (
	"encoding/json"
	"time"
)

type VersionSnapshotMeta struct {
	ConfigFile   string    `json:"config_file"`
	TargetGroup  string    `json:"target_group"`
	Operator     string    `json:"operator"`
	ExecutedAt   time.Time `json:"executed_at"`
	ChangeType   string    `json:"change_type"`
	VersionTag   string    `json:"version_tag"`
	CommitHash   string    `json:"commit_hash,omitempty"`
	ServerCount  int       `json:"server_count,omitempty"`
}

func (m *VersionSnapshotMeta) ToJSON() (string, error) {
	data, err := json.Marshal(m)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (m *VersionSnapshotMeta) FromJSON(data string) error {
	return json.Unmarshal([]byte(data), m)
}

type Server struct {
	ServerID   string `json:"server_id"`
	Host       string `json:"host"`
	Port       int    `json:"port"`
	User       string `json:"user"`
	KeyFile    string `json:"key_file"`
	ConfigPath string `json:"config_path"`
}

type ServerGroup struct {
	GroupName     string   `json:"group_name"`
	Servers       []Server `json:"servers"`
	ReloadCommand string   `json:"reload_command"`
}

type PushResult struct {
	Success int `json:"success"`
	Failed  int `json:"failed"`
}

type ChangeRecord struct {
	ChangeID     string     `json:"change_id"`
	ConfigFile   string     `json:"config_file"`
	TargetGroup  string     `json:"target_group"`
	VersionTag   string     `json:"version_tag"`
	ChangeType   string     `json:"change_type"`
	DiffSummary   string     `json:"diff_summary"`
	ExecutedAt   time.Time  `json:"executed_at"`
	Operator     string     `json:"operator"`
	Result       PushResult `json:"result"`
}

type ServerPushStatus struct {
	ServerID string `json:"server_id"`
	Host     string `json:"host"`
	Status   string `json:"status"`
	Error    string `json:"error,omitempty"`
}

type PushSummary struct {
	Total     int
	Success   int
	Failed    int
	ServerStatuses []ServerPushStatus
}
