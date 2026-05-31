package imagedistribution

import (
	"time"

	"github.com/imagecdn/imagecdn/internal/models"
)

type ImageManifest struct {
	ID           string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Registry     string                 `gorm:"type:varchar(128);index" json:"registry"`
	Repository   string                 `gorm:"type:varchar(256);index" json:"repository"`
	Tag          string                 `gorm:"type:varchar(128);index" json:"tag"`
	Digest       string                 `gorm:"type:varchar(128);uniqueIndex" json:"digest"`
	SchemaVersion int                   `json:"schema_version"`
	MediaType    string                 `gorm:"type:varchar(128)" json:"media_type"`
	Size         int64                  `json:"size"`
	Layers       []ImageLayer           `gorm:"foreignKey:ManifestID" json:"layers"`
	Status       string                 `gorm:"type:varchar(32);index" json:"status"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type ImageLayer struct {
	ID         string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ManifestID string    `gorm:"type:varchar(64);index" json:"manifest_id"`
	Digest     string    `gorm:"type:varchar(128);index" json:"digest"`
	MediaType  string    `gorm:"type:varchar(128)" json:"media_type"`
	Size       int64     `json:"size"`
	URL        string    `gorm:"type:varchar(512)" json:"url"`
	Status     string    `gorm:"type:varchar(32);index" json:"status"`
	Downloaded bool      `gorm:"default:false;index" json:"downloaded"`
	LocalPath  string    `gorm:"type:varchar(512)" json:"local_path"`
	Peers      int       `gorm:"default:0" json:"peers"`
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
}

type P2PPeer struct {
	ID            string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	NodeID        string    `gorm:"type:varchar(64);uniqueIndex" json:"node_id"`
	Address       string    `gorm:"type:varchar(128)" json:"address"`
	Region        string    `gorm:"type:varchar(64);index" json:"region"`
	Available     bool      `gorm:"default:true;index" json:"available"`
	Bandwidth     int64     `json:"bandwidth"`
	LastHeartbeat time.Time `gorm:"index" json:"last_heartbeat"`
	Latency       int64     `json:"latency_ms"`
	CreatedAt     time.Time `json:"created_at"`
	UpdatedAt     time.Time `json:"updated_at"`
}

type P2PSession struct {
	ID           string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	LayerDigest  string    `gorm:"type:varchar(128);index" json:"layer_digest"`
	PeerID       string    `gorm:"type:varchar(64);index" json:"peer_id"`
	Status       string    `gorm:"type:varchar(32);index" json:"status"`
	Downloaded   int64     `json:"downloaded_bytes"`
	Total        int64     `json:"total_bytes"`
	Speed        float64   `json:"speed_mbps"`
	StartedAt    time.Time `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
}

type RegistrySync struct {
	ID              string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	SourceRegistry  string    `gorm:"type:varchar(128)" json:"source_registry"`
	TargetRegistry  string    `gorm:"type:varchar(128)" json:"target_registry"`
	Repository      string    `gorm:"type:varchar(256);index" json:"repository"`
	Tag             string    `gorm:"type:varchar(128)" json:"tag"`
	Status          string    `gorm:"type:varchar(32);index" json:"status"`
	Progress        float64   `gorm:"default:0" json:"progress"`
	Retries         int       `gorm:"default:0" json:"retries"`
	ErrorDetail     *string   `json:"error_detail"`
	StartedAt       time.Time `json:"started_at"`
	CompletedAt     *time.Time `json:"completed_at"`
}

type SyncTask struct {
	ID         string            `json:"id"`
	Source     string            `json:"source"`
	Target     string            `json:"target"`
	Repository string            `json:"repository"`
	Tag        string            `json:"tag"`
	Labels     map[string]string `json:"labels"`
}

type PullRequest struct {
	Registry   string            `json:"registry"`
	Repository string            `json:"repository"`
	Tag        string            `json:"tag"`
	UseP2P     bool              `json:"use_p2p"`
	Priority   int               `json:"priority"`
	Labels     map[string]string `json:"labels"`
}

type PullResponse struct {
	ManifestID string                 `json:"manifest_id"`
	Digest     string                 `json:"digest"`
	Layers     []LayerPullStatus      `json:"layers"`
	Status     string                 `json:"status"`
}

type LayerPullStatus struct {
	Digest     string  `json:"digest"`
	Size       int64   `json:"size"`
	Status     string  `json:"status"`
	Downloaded bool    `json:"downloaded"`
	UseP2P     bool    `json:"use_p2p"`
	Peers      int     `json:"peers"`
	Progress   float64 `json:"progress"`
}
