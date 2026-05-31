package imagedistribution

import (
	"time"

	"github.com/imagecdn/imagecdn/internal/db"
	"gorm.io/gorm"
)

type Repository interface {
	CreateManifest(manifest *ImageManifest) error
	GetManifest(digest string) (*ImageManifest, error)
	GetManifestByID(id string) (*ImageManifest, error)
	UpdateManifest(manifest *ImageManifest) error
	ListManifests(registry, repository string, page, pageSize int) ([]ImageManifest, int64, error)

	CreateLayer(layer *ImageLayer) error
	GetLayer(digest string) (*ImageLayer, error)
	UpdateLayer(layer *ImageLayer) error
	ListLayers(manifestID string) ([]ImageLayer, error)
	GetDownloadedLayers() ([]ImageLayer, error)

	RegisterPeer(peer *P2PPeer) error
	UpdatePeerHeartbeat(nodeID string) error
	GetAvailablePeers(region string, limit int) ([]P2PPeer, error)
	UpdatePeerStatus(nodeID string, available bool) error

	CreateSession(session *P2PSession) error
	UpdateSessionProgress(id string, downloaded int64, speed float64) error
	CompleteSession(id string) error
	ListActiveSessions(layerDigest string) ([]P2PSession, error)

	CreateSync(sync *RegistrySync) error
	GetSync(id string) (*RegistrySync, error)
	UpdateSync(sync *RegistrySync) error
	ListSyncs(status string, page, pageSize int) ([]RegistrySync, int64, error)
	GetPendingSyncs() ([]RegistrySync, error)
}

type repository struct {
	db *gorm.DB
}

func NewRepository() Repository {
	return &repository{db: db.DB}
}

func (r *repository) CreateManifest(manifest *ImageManifest) error {
	return r.db.Create(manifest).Error
}

func (r *repository) GetManifest(digest string) (*ImageManifest, error) {
	var manifest ImageManifest
	err := r.db.Preload("Layers").Where("digest = ?", digest).First(&manifest).Error
	if err != nil {
		return nil, err
	}
	return &manifest, nil
}

func (r *repository) GetManifestByID(id string) (*ImageManifest, error) {
	var manifest ImageManifest
	err := r.db.Preload("Layers").Where("id = ?", id).First(&manifest).Error
	if err != nil {
		return nil, err
	}
	return &manifest, nil
}

func (r *repository) UpdateManifest(manifest *ImageManifest) error {
	return r.db.Save(manifest).Error
}

func (r *repository) ListManifests(registry, repository string, page, pageSize int) ([]ImageManifest, int64, error) {
	var manifests []ImageManifest
	var total int64

	query := r.db.Model(&ImageManifest{})
	if registry != "" {
		query = query.Where("registry = ?", registry)
	}
	if repository != "" {
		query = query.Where("repository LIKE ?", "%"+repository+"%")
	}

	query.Count(&total)
	err := query.Preload("Layers").Offset((page - 1) * pageSize).Limit(pageSize).Find(&manifests).Error
	return manifests, total, err
}

func (r *repository) CreateLayer(layer *ImageLayer) error {
	return r.db.Create(layer).Error
}

func (r *repository) GetLayer(digest string) (*ImageLayer, error) {
	var layer ImageLayer
	err := r.db.Where("digest = ?", digest).First(&layer).Error
	if err != nil {
		return nil, err
	}
	return &layer, nil
}

func (r *repository) UpdateLayer(layer *ImageLayer) error {
	return r.db.Save(layer).Error
}

func (r *repository) ListLayers(manifestID string) ([]ImageLayer, error) {
	var layers []ImageLayer
	err := r.db.Where("manifest_id = ?", manifestID).Find(&layers).Error
	return layers, err
}

func (r *repository) GetDownloadedLayers() ([]ImageLayer, error) {
	var layers []ImageLayer
	err := r.db.Where("downloaded = ?", true).Find(&layers).Error
	return layers, err
}

func (r *repository) RegisterPeer(peer *P2PPeer) error {
	return r.db.Create(peer).Error
}

func (r *repository) UpdatePeerHeartbeat(nodeID string) error {
	return r.db.Model(&P2PPeer{}).Where("node_id = ?", nodeID).Update("last_heartbeat", time.Now()).Error
}

func (r *repository) GetAvailablePeers(region string, limit int) ([]P2PPeer, error) {
	var peers []P2PPeer
	query := r.db.Where("available = ?", true)
	if region != "" {
		query = query.Where("region = ?", region)
	}
	err := query.Order("latency ASC").Limit(limit).Find(&peers).Error
	return peers, err
}

func (r *repository) UpdatePeerStatus(nodeID string, available bool) error {
	return r.db.Model(&P2PPeer{}).Where("node_id = ?", nodeID).Update("available", available).Error
}

func (r *repository) CreateSession(session *P2PSession) error {
	return r.db.Create(session).Error
}

func (r *repository) UpdateSessionProgress(id string, downloaded int64, speed float64) error {
	return r.db.Model(&P2PSession{}).Where("id = ?", id).Updates(map[string]interface{}{
		"downloaded_bytes": downloaded,
		"speed_mbps":       speed,
		"status":           "downloading",
	}).Error
}

func (r *repository) CompleteSession(id string) error {
	now := time.Now()
	return r.db.Model(&P2PSession{}).Where("id = ?", id).Updates(map[string]interface{}{
		"status":       "completed",
		"completed_at": &now,
	}).Error
}

func (r *repository) ListActiveSessions(layerDigest string) ([]P2PSession, error) {
	var sessions []P2PSession
	err := r.db.Where("layer_digest = ? AND status IN ?", layerDigest, []string{"pending", "downloading"}).Find(&sessions).Error
	return sessions, err
}

func (r *repository) CreateSync(sync *RegistrySync) error {
	return r.db.Create(sync).Error
}

func (r *repository) GetSync(id string) (*RegistrySync, error) {
	var sync RegistrySync
	err := r.db.Where("id = ?", id).First(&sync).Error
	if err != nil {
		return nil, err
	}
	return &sync, nil
}

func (r *repository) UpdateSync(sync *RegistrySync) error {
	return r.db.Save(sync).Error
}

func (r *repository) ListSyncs(status string, page, pageSize int) ([]RegistrySync, int64, error) {
	var syncs []RegistrySync
	var total int64

	query := r.db.Model(&RegistrySync{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	query.Count(&total)
	err := query.Order("started_at DESC").Offset((page - 1) * pageSize).Limit(pageSize).Find(&syncs).Error
	return syncs, total, err
}

func (r *repository) GetPendingSyncs() ([]RegistrySync, error) {
	var syncs []RegistrySync
	err := r.db.Where("status IN ?", []string{"pending", "retrying"}).Order("started_at ASC").Find(&syncs).Error
	return syncs, err
}
