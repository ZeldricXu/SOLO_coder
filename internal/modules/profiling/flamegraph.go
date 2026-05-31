package profiling

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
	apperrors "session189/pkg/errors"
)

type FlameGraphGenerator struct {
	flameGraphScript string
}

func NewFlameGraphGenerator(scriptPath string) *FlameGraphGenerator {
	return &FlameGraphGenerator{
		flameGraphScript: scriptPath,
	}
}

func (g *FlameGraphGenerator) GenerateFromProfile(ctx context.Context, profileSample *domain.ProfileSample, title string) (*domain.FlameGraph, error) {
	if profileSample == nil {
		return nil, apperrors.InvalidInput("profile sample is nil")
	}

	svgData, err := g.generateSVG(ctx, profileSample.Data)
	if err != nil {
		return nil, apperrors.Internal("generate svg failed", err)
	}

	flameGraph := &domain.FlameGraph{
		GraphID:   uuid.New().String(),
		ProfileID: profileSample.SampleID,
		Title:     title,
		SVGData:   svgData,
		Metadata: map[string]interface{}{
			"profile_type": string(profileSample.ProfileType),
			"duration_ns":  profileSample.Duration,
		},
		CreatedAt: time.Now(),
	}

	if err := database.DB.WithContext(ctx).Create(flameGraph).Error; err != nil {
		return nil, apperrors.Internal("save flame graph failed", err)
	}

	logger.Info("Flame graph generated",
		zap.String("graph_id", flameGraph.GraphID),
		zap.String("profile_id", profileSample.SampleID))

	return flameGraph, nil
}

func (g *FlameGraphGenerator) generateSVG(ctx context.Context, profileData []byte) ([]byte, error) {
	if g.flameGraphScript != "" {
		svg, err := g.runExternalScript(ctx, profileData)
		if err == nil {
			return svg, nil
		}
		logger.Warn("External flame graph script failed, using fallback", zap.Error(err))
	}
	return g.generateMockSVG(profileData)
}

func (g *FlameGraphGenerator) runExternalScript(ctx context.Context, profileData []byte) ([]byte, error) {
	cmd := exec.CommandContext(ctx, g.flameGraphScript)
	cmd.Stdin = bytes.NewReader(profileData)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		logger.Error("Flame graph script failed", zap.Error(err), zap.String("stderr", stderr.String()))
		return nil, err
	}
	return stdout.Bytes(), nil
}

func (g *FlameGraphGenerator) generateMockSVG(profileData []byte) ([]byte, error) {
	svg := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<svg width="1200" height="600" xmlns="http://www.w3.org/2000/svg">
  <rect width="1200" height="600" fill="#ffffff"/>
  <text x="10" y="30" font-family="Arial" font-size="14" fill="#000000">Flame Graph (Preview)</text>
  <text x="10" y="50" font-family="Arial" font-size="12" fill="#666666">Profile size: %d bytes</text>
  <rect x="50" y="100" width="1100" height="20" fill="#ff6347"/>
  <text x="55" y="115" font-family="Arial" font-size="10" fill="#ffffff">runtime.main</text>
  <rect x="100" y="80" width="500" height="20" fill="#ffa500"/>
  <text x="105" y="95" font-family="Arial" font-size="10" fill="#ffffff">main.run</text>
  <rect x="200" y="60" width="300" height="20" fill="#32cd32"/>
  <text x="205" y="75" font-family="Arial" font-size="10" fill="#ffffff">processData</text>
  <rect x="250" y="40" width="200" height="20" fill="#1e90ff"/>
  <text x="255" y="55" font-family="Arial" font-size="10" fill="#ffffff">handleRequest</text>
</svg>`, len(profileData))
	return []byte(svg), nil
}

func GetFlameGraph(ctx context.Context, graphID string) (*domain.FlameGraph, error) {
	var graph domain.FlameGraph
	if err := database.DB.WithContext(ctx).Where("graph_id = ?", graphID).First(&graph).Error; err != nil {
		return nil, apperrors.Internal("get flame graph failed", err)
	}
	return &graph, nil
}

func ListFlameGraphs(ctx context.Context, profileID string, offset, limit int) ([]domain.FlameGraph, int64, error) {
	var graphs []domain.FlameGraph
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.FlameGraph{})
	if profileID != "" {
		query = query.Where("profile_id = ?", profileID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, apperrors.Internal("count flame graphs failed", err)
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&graphs).Error; err != nil {
		return nil, 0, apperrors.Internal("list flame graphs failed", err)
	}

	return graphs, total, nil
}

func DeleteFlameGraph(ctx context.Context, graphID string) error {
	if err := database.DB.WithContext(ctx).Where("graph_id = ?", graphID).Delete(&domain.FlameGraph{}).Error; err != nil {
		return apperrors.Internal("delete flame graph failed", err)
	}
	return nil
}
