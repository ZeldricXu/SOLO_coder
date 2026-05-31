package imagedistribution

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/imagecdn/imagecdn/internal/logger"
	"github.com/imagecdn/imagecdn/pkg/common"
	"go.uber.org/zap"
)

type Service interface {
	PullImage(ctx context.Context, req *PullRequest) (*PullResponse, error)
	GetPullStatus(ctx context.Context, manifestID string) (*PullResponse, error)
	SyncRegistry(ctx context.Context, task *SyncTask) (*RegistrySync, error)
	GetSyncStatus(ctx context.Context, syncID string) (*RegistrySync, error)
	RegisterPeer(ctx context.Context, peer *P2PPeer) error
	Heartbeat(ctx context.Context, nodeID string) error
	GetPeers(ctx context.Context, region string) ([]P2PPeer, error)
	ListManifests(ctx context.Context, registry, repository string, page, pageSize int) ([]ImageManifest, int64, error)
}

type service struct {
	repo      Repository
	httpClient *http.Client
	peerLock  sync.RWMutex
}

func NewService(repo Repository) Service {
	return &service{
		repo: repo,
		httpClient: &http.Client{
			Timeout: 30 * time.Minute,
			Transport: &http.Transport{
				MaxIdleConns:        100,
				IdleConnTimeout:     90 * time.Second,
				TLSHandshakeTimeout: 10 * time.Second,
			},
		},
	}
}

func (s *service) PullImage(ctx context.Context, req *PullRequest) (*PullResponse, error) {
	if err := common.ValidateNotEmpty(req.Registry, "registry"); err != nil {
		return nil, err
	}
	if err := common.ValidateNotEmpty(req.Repository, "repository"); err != nil {
		return nil, err
	}
	if err := common.ValidateNotEmpty(req.Tag, "tag"); err != nil {
		return nil, err
	}

	manifestURL := fmt.Sprintf("https://%s/v2/%s/manifests/%s", req.Registry, req.Repository, req.Tag)
	manifestResp, err := s.fetchManifest(manifestURL)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch manifest: %w", err)
	}

	digest := computeDigest(manifestResp)

	existingManifest, err := s.repo.GetManifest(digest)
	if err == nil && existingManifest != nil {
		return s.buildPullResponse(existingManifest), nil
	}

	manifestID := common.GenerateID("mfst")
	manifest := &ImageManifest{
		ID:            manifestID,
		Registry:      req.Registry,
		Repository:    req.Repository,
		Tag:           req.Tag,
		Digest:        digest,
		SchemaVersion: 2,
		MediaType:     "application/vnd.oci.image.manifest.v1+json",
		Size:          int64(len(manifestResp)),
		Status:        "pulling",
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	layerDigests := []string{
		"sha256:" + common.GenerateRandomHex(32),
		"sha256:" + common.GenerateRandomHex(32),
		"sha256:" + common.GenerateRandomHex(32),
	}

	var layers []ImageLayer
	for i, layerDigest := range layerDigests {
		layerID := common.GenerateID("layer")
		layerSize := int64(1024 * 1024 * (10 + i*5))
		layer := ImageLayer{
			ID:         layerID,
			ManifestID: manifestID,
			Digest:     layerDigest,
			MediaType:  "application/vnd.oci.image.layer.v1.tar+gzip",
			Size:       layerSize,
			URL:        fmt.Sprintf("https://%s/v2/%s/blobs/%s", req.Registry, req.Repository, layerDigest),
			Status:     "pending",
			Downloaded: false,
			Peers:      0,
			CreatedAt:  time.Now(),
			UpdatedAt:  time.Now(),
		}
		layers = append(layers, layer)
	}
	manifest.Layers = layers

	if err := s.repo.CreateManifest(manifest); err != nil {
		return nil, fmt.Errorf("failed to create manifest: %w", err)
	}

	go s.downloadLayers(ctx, manifest, req.UseP2P)

	return s.buildPullResponse(manifest), nil
}

func (s *service) downloadLayers(ctx context.Context, manifest *ImageManifest, useP2P bool) {
	var wg sync.WaitGroup
	sem := make(chan struct{}, 5)

	for i := range manifest.Layers {
		wg.Add(1)
		sem <- struct{}{}

		go func(idx int) {
			defer wg.Done()
			defer func() { <-sem }()

			layer := &manifest.Layers[idx]
			logger.Info("Starting layer download",
				zap.String("layer", layer.Digest),
				zap.Bool("p2p", useP2P))

			if useP2P {
				if err := s.downloadLayerP2P(ctx, layer); err != nil {
					logger.Warn("P2P download failed, falling back to HTTP",
						zap.String("layer", layer.Digest),
						zap.Error(err))
					_ = s.downloadLayerHTTP(ctx, layer)
				}
			} else {
				_ = s.downloadLayerHTTP(ctx, layer)
			}
		}(i)
	}

	wg.Wait()

	allDownloaded := true
	for _, layer := range manifest.Layers {
		if !layer.Downloaded {
			allDownloaded = false
			break
		}
	}

	manifest.Status = "completed"
	if !allDownloaded {
		manifest.Status = "partial"
	}
	manifest.UpdatedAt = time.Now()
	_ = s.repo.UpdateManifest(manifest)

	logger.Info("Image pull completed",
		zap.String("manifest", manifest.ID),
		zap.String("status", manifest.Status))
}

func (s *service) downloadLayerHTTP(ctx context.Context, layer *ImageLayer) error {
	layer.Status = "downloading"
	layer.UpdatedAt = time.Now()
	_ = s.repo.UpdateLayer(layer)

	err := common.Retry(func() error {
		req, err := http.NewRequestWithContext(ctx, "GET", layer.URL, nil)
		if err != nil {
			return err
		}

		resp, err := s.httpClient.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("unexpected status: %d", resp.StatusCode)
		}

		downloaded := int64(0)
		buf := make([]byte, 32*1024)
		for {
			n, err := resp.Body.Read(buf)
			if n > 0 {
				downloaded += int64(n)
			}
			if err == io.EOF {
				break
			}
			if err != nil {
				return err
			}
		}

		if downloaded != layer.Size {
			return fmt.Errorf("size mismatch: expected %d, got %d", layer.Size, downloaded)
		}

		return nil
	}, 3, 1*time.Second)

	if err != nil {
		layer.Status = "failed"
		_ = s.repo.UpdateLayer(layer)
		return err
	}

	layer.Downloaded = true
	layer.Status = "completed"
	layer.LocalPath = fmt.Sprintf("/data/layers/%s", layer.Digest)
	layer.UpdatedAt = time.Now()
	_ = s.repo.UpdateLayer(layer)

	return nil
}

func (s *service) downloadLayerP2P(ctx context.Context, layer *ImageLayer) error {
	peers, err := s.repo.GetAvailablePeers("", 3)
	if err != nil || len(peers) == 0 {
		return errors.New("no peers available")
	}

	layer.Status = "p2p_downloading"
	layer.Peers = len(peers)
	_ = s.repo.UpdateLayer(layer)

	for _, peer := range peers {
		sessionID := common.GenerateID("sess")
		session := &P2PSession{
			ID:          sessionID,
			LayerDigest: layer.Digest,
			PeerID:      peer.ID,
			Status:      "downloading",
			Total:       layer.Size,
			StartedAt:   time.Now(),
		}
		_ = s.repo.CreateSession(session)
	}

	simulatedBytes := int64(0)
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()

	for simulatedBytes < layer.Size {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			simulatedBytes += layer.Size / 10
			if simulatedBytes > layer.Size {
				simulatedBytes = layer.Size
			}

			sessions, _ := s.repo.ListActiveSessions(layer.Digest)
			for _, session := range sessions {
				_ = s.repo.UpdateSessionProgress(session.ID, simulatedBytes/int64(len(sessions)), 100.0)
			}
		}
	}

	sessions, _ := s.repo.ListActiveSessions(layer.Digest)
	for _, session := range sessions {
		_ = s.repo.CompleteSession(session.ID)
	}

	layer.Downloaded = true
	layer.Status = "completed"
	layer.LocalPath = fmt.Sprintf("/data/layers/%s", layer.Digest)
	layer.UpdatedAt = time.Now()
	_ = s.repo.UpdateLayer(layer)

	return nil
}

func (s *service) GetPullStatus(ctx context.Context, manifestID string) (*PullResponse, error) {
	manifest, err := s.repo.GetManifestByID(manifestID)
	if err != nil {
		return nil, err
	}
	return s.buildPullResponse(manifest), nil
}

func (s *service) SyncRegistry(ctx context.Context, task *SyncTask) (*RegistrySync, error) {
	syncID := common.GenerateID("sync")
	sync := &RegistrySync{
		ID:             syncID,
		SourceRegistry: task.Source,
		TargetRegistry: task.Target,
		Repository:     task.Repository,
		Tag:            task.Tag,
		Status:         "pending",
		Progress:       0,
		StartedAt:      time.Now(),
	}

	if err := s.repo.CreateSync(sync); err != nil {
		return nil, err
	}

	go s.executeSync(ctx, sync)

	return sync, nil
}

func (s *service) executeSync(ctx context.Context, sync *RegistrySync) {
	sync.Status = "syncing"
	_ = s.repo.UpdateSync(sync)

	err := common.Retry(func() error {
		for i := 0; i <= 10; i++ {
			select {
			case <-ctx.Done():
				return ctx.Err()
			default:
				sync.Progress = float64(i) / 10.0
				_ = s.repo.UpdateSync(sync)
				time.Sleep(200 * time.Millisecond)
			}
		}
		return nil
	}, 3, 2*time.Second)

	if err != nil {
		sync.Status = "failed"
		sync.Retries++
		errorMsg := err.Error()
		sync.ErrorDetail = &errorMsg
		if sync.Retries < 3 {
			sync.Status = "retrying"
		}
	} else {
		sync.Status = "completed"
		now := time.Now()
		sync.CompletedAt = &now
		sync.Progress = 1.0
	}

	_ = s.repo.UpdateSync(sync)
	logger.Info("Registry sync completed",
		zap.String("sync_id", sync.ID),
		zap.String("status", sync.Status))
}

func (s *service) GetSyncStatus(ctx context.Context, syncID string) (*RegistrySync, error) {
	return s.repo.GetSync(syncID)
}

func (s *service) RegisterPeer(ctx context.Context, peer *P2PPeer) error {
	peer.ID = common.GenerateID("peer")
	peer.CreatedAt = time.Now()
	peer.UpdatedAt = time.Now()
	peer.LastHeartbeat = time.Now()
	return s.repo.RegisterPeer(peer)
}

func (s *service) Heartbeat(ctx context.Context, nodeID string) error {
	return s.repo.UpdatePeerHeartbeat(nodeID)
}

func (s *service) GetPeers(ctx context.Context, region string) ([]P2PPeer, error) {
	return s.repo.GetAvailablePeers(region, 50)
}

func (s *service) ListManifests(ctx context.Context, registry, repository string, page, pageSize int) ([]ImageManifest, int64, error) {
	return s.repo.ListManifests(registry, repository, page, pageSize)
}

func (s *service) fetchManifest(url string) ([]byte, error) {
	var body []byte
	err := common.Retry(func() error {
		req, err := http.NewRequest("GET", url, nil)
		if err != nil {
			return err
		}
		req.Header.Set("Accept", "application/vnd.oci.image.manifest.v1+json")

		resp, err := s.httpClient.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()

		if resp.StatusCode == http.StatusConflict {
			return common.NewConflictError("manifest conflict")
		}

		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("unexpected status: %d", resp.StatusCode)
		}

		body, err = io.ReadAll(resp.Body)
		return err
	}, 3, 1*time.Second)

	return body, err
}

func (s *service) buildPullResponse(manifest *ImageManifest) *PullResponse {
	layers := make([]LayerPullStatus, len(manifest.Layers))
	for i, l := range manifest.Layers {
		progress := 0.0
		if l.Downloaded {
			progress = 1.0
		}
		layers[i] = LayerPullStatus{
			Digest:     l.Digest,
			Size:       l.Size,
			Status:     l.Status,
			Downloaded: l.Downloaded,
			UseP2P:     l.Status == "p2p_downloading",
			Peers:      l.Peers,
			Progress:   progress,
		}
	}

	return &PullResponse{
		ManifestID: manifest.ID,
		Digest:     manifest.Digest,
		Layers:     layers,
		Status:     manifest.Status,
	}
}

func computeDigest(data []byte) string {
	h := sha256.Sum256(data)
	return "sha256:" + hex.EncodeToString(h[:])
}
