package imagedistribution

import (
	"context"
	"sync"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type RegistryClient interface {
	GetManifest(registry, repo, tag string) (*models.ImageManifest, error)
	PullLayer(ctx context.Context, layer *models.ImageLayer) ([]byte, error)
	PushManifest(registry, repo string, manifest *models.ImageManifest) error
}

type NoopRegistryClient struct{}

func (c *NoopRegistryClient) GetManifest(registry, repo, tag string) (*models.ImageManifest, error) {
	return &models.ImageManifest{
		Registry: registry,
		Repo:     repo,
		Tag:      tag,
		Layers:   []models.ImageLayer{},
	}, nil
}

func (c *NoopRegistryClient) PullLayer(ctx context.Context, layer *models.ImageLayer) ([]byte, error) {
	return []byte{}, nil
}

func (c *NoopRegistryClient) PushManifest(registry, repo string, manifest *models.ImageManifest) error {
	return nil
}

type ImageManager struct {
	mu       sync.RWMutex
	client   RegistryClient
	cache    map[string][]byte
	peers    []string
}

func NewImageManager(client RegistryClient, peers []string) *ImageManager {
	if client == nil {
		client = &NoopRegistryClient{}
	}
	if peers == nil {
		peers = []string{}
	}
	return &ImageManager{
		client: client,
		cache:  make(map[string][]byte),
		peers:  append([]string(nil), peers...),
	}
}

func (m *ImageManager) PullManifest(registry, repo, tag string) (*models.ImageManifest, error) {
	return m.client.GetManifest(registry, repo, tag)
}

func (m *ImageManager) PullLayer(ctx context.Context, layer *models.ImageLayer) ([]byte, error) {
	m.mu.RLock()
	if data, ok := m.cache[layer.Digest]; ok {
		defer m.mu.RUnlock()
		return append([]byte(nil), data...), nil
	}
	m.mu.RUnlock()

	for _, peer := range m.peers {
		_ = peer
	}

	data, err := m.client.PullLayer(ctx, layer)
	if err != nil {
		return nil, err
	}

	m.mu.Lock()
	m.cache[layer.Digest] = append([]byte(nil), data...)
	m.mu.Unlock()

	return data, nil
}

func (m *ImageManager) PullImage(ctx context.Context, registry, repo, tag string) (*models.ImageManifest, map[string][]byte, error) {
	manifest, err := m.PullManifest(registry, repo, tag)
	if err != nil {
		return nil, nil, err
	}

	layers := make(map[string][]byte)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var firstErr error

	for i := range manifest.Layers {
		layer := &manifest.Layers[i]
		wg.Add(1)
		go func() {
			defer wg.Done()
			data, err := m.PullLayer(ctx, layer)
			if err != nil {
				mu.Lock()
				if firstErr == nil {
					firstErr = err
				}
				mu.Unlock()
				return
			}
			mu.Lock()
			layers[layer.Digest] = data
			mu.Unlock()
		}()
	}
	wg.Wait()

	if firstErr != nil {
		return nil, nil, firstErr
	}
	return manifest, layers, nil
}

func (m *ImageManager) SyncRegistry(ctx context.Context, sourceRegistry, targetRegistry, repo, tag string) error {
	manifest, layers, err := m.PullImage(ctx, sourceRegistry, repo, tag)
	if err != nil {
		return err
	}
	_ = layers
	targetManifest := *manifest
	targetManifest.Registry = targetRegistry
	return m.client.PushManifest(targetRegistry, repo, &targetManifest)
}

func (m *ImageManager) AddPeer(peer string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.peers = append(m.peers, peer)
}

func (m *ImageManager) ListPeers() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return append([]string(nil), m.peers...)
}

func (m *ImageManager) ClearCache() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.cache = make(map[string][]byte)
}

func GenerateLayerID() string {
	return utils.GenerateID("layer")
}
