package registry

import (
	"context"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/abstraction"
	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type ImageDistributionService struct {
	images     map[string]*common.ImageInfo
	manifests  map[string]*common.ImageManifest
	cache      map[string]bool
	p2pNodes   map[string][]string
	mu         sync.RWMutex
}

func NewImageDistributionService() abstraction.ImageDistributionService {
	return &ImageDistributionService{
		images:    make(map[string]*common.ImageInfo),
		manifests: make(map[string]*common.ImageManifest),
		cache:     make(map[string]bool),
		p2pNodes:  make(map[string][]string),
	}
}

func (s *ImageDistributionService) PullImage(ctx context.Context, ref string, layers []string) (*common.ImagePullResult, error) {
	if ref == "" {
		return nil, common.NewValidationError("image reference is required", "ref")
	}

	registry, repository, tag, err := parseImageRef(ref)
	if err != nil {
		return nil, common.NewValidationError(err.Error(), "ref")
	}

	start := time.Now()

	manifest, err := s.GetImageManifest(ctx, ref)
	if err != nil {
		return nil, err
	}

	var totalSize, pulledSize, cachedSize int64
	pulledLayers := make([]string, 0)

	for _, layer := range manifest.Layers {
		if len(layers) > 0 && !contains(layers, layer.Digest) {
			continue
		}
		totalSize += layer.Size
		cacheKey := fmt.Sprintf("%s/%s@%s", registry, repository, layer.Digest)
		if s.isCached(cacheKey) {
			cachedSize += layer.Size
		} else {
			pulledSize += layer.Size
			s.markCached(cacheKey)
		}
		pulledLayers = append(pulledLayers, layer.Digest)
	}

	imageInfo := &common.ImageInfo{
		Ref:        ref,
		Registry:   registry,
		Repository: repository,
		Tag:        tag,
		Digest:     manifest.Digest,
		Size:       totalSize,
		PushedAt:   time.Now(),
	}

	s.mu.Lock()
	s.images[ref] = imageInfo
	s.mu.Unlock()

	result := &common.ImagePullResult{
		Ref:        ref,
		Layers:     pulledLayers,
		TotalSize:  totalSize,
		PulledSize: pulledSize,
		CachedSize: cachedSize,
		Duration:   time.Since(start),
		Success:    true,
	}

	common.Info("image pull completed",
		zap.String("ref", ref),
		zap.Int("layers", len(pulledLayers)),
		zap.Int64("total_size", totalSize),
		zap.Int64("pulled_size", pulledSize),
		zap.Int64("cached_size", cachedSize),
		zap.Duration("duration", result.Duration),
	)

	return result, nil
}

func (s *ImageDistributionService) SyncImage(ctx context.Context, sourceRef, targetRef string) (*common.ImageSyncResult, error) {
	if sourceRef == "" || targetRef == "" {
		return nil, common.NewValidationError("source and target references are required", "refs")
	}

	start := time.Now()

	sourceManifest, err := s.GetImageManifest(ctx, sourceRef)
	if err != nil {
		return nil, err
	}

	totalSize := int64(0)
	for _, layer := range sourceManifest.Layers {
		totalSize += layer.Size
	}

	sourceRegistry, sourceRepo, sourceTag, _ := parseImageRef(sourceRef)
	targetRegistry, targetRepo, targetTag, _ := parseImageRef(targetRef)

	targetInfo := &common.ImageInfo{
		Ref:        targetRef,
		Registry:   targetRegistry,
		Repository: targetRepo,
		Tag:        targetTag,
		Digest:     sourceManifest.Digest,
		Size:       totalSize,
		PushedAt:   time.Now(),
	}

	s.mu.Lock()
	s.images[targetRef] = targetInfo
	s.manifests[targetRef] = sourceManifest
	s.mu.Unlock()

	result := &common.ImageSyncResult{
		SourceRef:  sourceRef,
		TargetRef:  targetRef,
		TotalSize:  totalSize,
		SyncedSize: totalSize,
		Duration:   time.Since(start),
		Success:    true,
	}

	common.Info("image sync completed",
		zap.String("source", sourceRef),
		zap.String("target", targetRef),
		zap.Int64("size", totalSize),
		zap.String("source_registry", sourceRegistry),
		zap.String("target_registry", targetRegistry),
		zap.Duration("duration", result.Duration),
	)

	return result, nil
}

func (s *ImageDistributionService) EnableP2P(ctx context.Context, imageRef string, nodes []string) (*common.P2PStatus, error) {
	if imageRef == "" {
		return nil, common.NewValidationError("image reference is required", "ref")
	}
	if len(nodes) == 0 {
		return nil, common.NewValidationError("at least one node is required", "nodes")
	}

	s.mu.Lock()
	s.p2pNodes[imageRef] = nodes
	s.mu.Unlock()

	status := &common.P2PStatus{
		ImageRef:   imageRef,
		Nodes:      nodes,
		Seeders:    len(nodes),
		Leechers:   0,
		Enabled:    true,
		Throughput: int64(len(nodes)) * 1024 * 1024,
	}

	common.Info("P2P distribution enabled",
		zap.String("image_ref", imageRef),
		zap.Int("nodes", len(nodes)),
		zap.Int("seeders", status.Seeders),
	)

	return status, nil
}

func (s *ImageDistributionService) GetImageManifest(ctx context.Context, ref string) (*common.ImageManifest, error) {
	if ref == "" {
		return nil, common.NewValidationError("image reference is required", "ref")
	}

	s.mu.RLock()
	manifest, exists := s.manifests[ref]
	s.mu.RUnlock()

	if exists {
		return manifest, nil
	}

	_, repository, _, _ := parseImageRef(ref)

	layers := make([]*common.ImageLayer, 3)
	for i := 0; i < 3; i++ {
		layerSize := int64(10*1024*1024 + i*5*1024*1024)
		layers[i] = &common.ImageLayer{
			Digest:    fmt.Sprintf("sha256:layer%d_%x", i, time.Now().UnixNano()),
			Size:      layerSize,
			MediaType: "application/vnd.oci.image.layer.v1.tar+gzip",
			Cached:    false,
		}
	}

	manifest = &common.ImageManifest{
		Digest:    fmt.Sprintf("sha256:manifest_%x", time.Now().UnixNano()),
		SchemaV:   2,
		Layers:    layers,
		Config: &common.ImageConfig{
			Entrypoint: []string{"/app/start"},
			Cmd:        []string{},
			Env:        []string{"ENV=production"},
			Labels:     map[string]string{"app": repository},
			User:       "appuser",
			WorkingDir: "/app",
		},
		Labels:    map[string]string{"maintainer": "chaoslab"},
		CreatedAt: time.Now(),
	}

	s.mu.Lock()
	s.manifests[ref] = manifest
	s.mu.Unlock()

	common.Debug("image manifest generated",
		zap.String("ref", ref),
		zap.String("digest", manifest.Digest),
		zap.Int("layers", len(layers)),
	)

	return manifest, nil
}

func (s *ImageDistributionService) ListImages(ctx context.Context, registry string) ([]*common.ImageInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	list := make([]*common.ImageInfo, 0)
	for _, img := range s.images {
		if registry == "" || img.Registry == registry {
			list = append(list, img)
		}
	}
	return list, nil
}

func (s *ImageDistributionService) DeleteImage(ctx context.Context, ref string) error {
	if ref == "" {
		return common.NewValidationError("image reference is required", "ref")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	_, exists := s.images[ref]
	if !exists {
		return common.NewNotFoundError(fmt.Sprintf("image %s not found", ref))
	}

	delete(s.images, ref)
	delete(s.manifests, ref)
	delete(s.p2pNodes, ref)

	common.Info("image deleted",
		zap.String("ref", ref),
	)

	return nil
}

func (s *ImageDistributionService) isCached(key string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cache[key]
}

func (s *ImageDistributionService) markCached(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cache[key] = true
}

func parseImageRef(ref string) (registry, repository, tag string, err error) {
	u, err := url.Parse("https://" + ref)
	if err != nil {
		return "", "", "", fmt.Errorf("invalid image reference: %s", ref)
	}

	pathParts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(pathParts) == 0 {
		return "", "", "", fmt.Errorf("invalid image reference format")
	}

	registry = u.Host
	if registry == "" {
		registry = "docker.io"
	}

	tag = "latest"
	lastPart := pathParts[len(pathParts)-1]
	if strings.Contains(lastPart, ":") {
		tagParts := strings.SplitN(lastPart, ":", 2)
		pathParts[len(pathParts)-1] = tagParts[0]
		tag = tagParts[1]
	}

	repository = strings.Join(pathParts, "/")
	return
}

func contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}
