package profiling

import (
	"bytes"
	"context"
	"runtime"
	"runtime/pprof"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
	apperrors "session189/pkg/errors"
)

type Profiler struct {
	mu            sync.Mutex
	activeProfile *activeProfile
	stopCh        chan struct{}
}

type activeProfile struct {
	buffer  *bytes.Buffer
	profileType domain.ProfileType
	duration time.Duration
}

func NewProfiler() *Profiler {
	return &Profiler{
		stopCh: make(chan struct{}),
	}
}

func (p *Profiler) StartCPUProfile(duration time.Duration) (*domain.ProfileSample, error) {
	p.mu.Lock()
	if p.activeProfile != nil {
		p.mu.Unlock()
		return nil, apperrors.Conflict("profiler is already running")
	}

	buf := &bytes.Buffer{}
	p.activeProfile = &activeProfile{
		buffer:      buf,
		profileType: domain.ProfileTypeCPU,
		duration:    duration,
	}
	p.mu.Unlock()

	if err := pprof.StartCPUProfile(buf); err != nil {
		p.mu.Lock()
		p.activeProfile = nil
		p.mu.Unlock()
		return nil, apperrors.Internal("start cpu profile failed", err)
	}

	go func() {
		timer := time.NewTimer(duration)
		defer timer.Stop()
		select {
		case <-timer.C:
		case <-p.stopCh:
		}
		pprof.StopCPUProfile()
	}()

	return p.waitAndSave(domain.ProfileTypeCPU, duration)
}

func (p *Profiler) StartMemoryProfile(duration time.Duration, rate int) (*domain.ProfileSample, error) {
	p.mu.Lock()
	if p.activeProfile != nil {
		p.mu.Unlock()
		return nil, apperrors.Conflict("profiler is already running")
	}

	if rate <= 0 {
		rate = 4096
	}

	oldRate := runtime.MemProfileRate
	runtime.MemProfileRate = rate
	defer func() { runtime.MemProfileRate = oldRate }()

	buf := &bytes.Buffer{}
	p.activeProfile = &activeProfile{
		buffer:      buf,
		profileType: domain.ProfileTypeMemory,
		duration:    duration,
	}
	p.mu.Unlock()

	time.Sleep(duration)
	runtime.GC()

	if err := pprof.WriteHeapProfile(buf); err != nil {
		p.mu.Lock()
		p.activeProfile = nil
		p.mu.Unlock()
		return nil, apperrors.Internal("write heap profile failed", err)
	}

	return p.waitAndSave(domain.ProfileTypeMemory, duration)
}

func (p *Profiler) StartGoroutineProfile() (*domain.ProfileSample, error) {
	buf := &bytes.Buffer{}
	if err := pprof.Lookup("goroutine").WriteTo(buf, 2); err != nil {
		return nil, apperrors.Internal("write goroutine profile failed", err)
	}

	sample := p.createSample(domain.ProfileTypeGoroutine, buf.Bytes(), 0, map[string]interface{}{
		"goroutine_count": runtime.NumGoroutine(),
	})

	if err := database.DB.Create(sample).Error; err != nil {
		return nil, apperrors.Internal("save profile sample failed", err)
	}

	logger.Info("Goroutine profile saved", zap.String("sample_id", sample.SampleID))
	return sample, nil
}

func (p *Profiler) Stop() {
	p.mu.Lock()
	if p.activeProfile != nil {
		close(p.stopCh)
		p.stopCh = make(chan struct{})
	}
	p.mu.Unlock()
}

func (p *Profiler) waitAndSave(profileType domain.ProfileType, duration time.Duration) (*domain.ProfileSample, error) {
	for {
		p.mu.Lock()
		active := p.activeProfile
		p.mu.Unlock()
		if active == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	p.mu.Lock()
	data := p.activeProfile.buffer.Bytes()
	p.activeProfile = nil
	p.mu.Unlock()

	sample := p.createSample(profileType, data, duration, p.defaultMetadata())

	if err := database.DB.Create(sample).Error; err != nil {
		return nil, apperrors.Internal("save profile sample failed", err)
	}

	logger.Info("Profile sample saved",
		zap.String("sample_id", sample.SampleID),
		zap.String("type", string(profileType)))

	return sample, nil
}

func (p *Profiler) createSample(profileType domain.ProfileType, data []byte, duration time.Duration, metadata map[string]interface{}) *domain.ProfileSample {
	if metadata == nil {
		metadata = make(map[string]interface{})
	}
	for k, v := range p.defaultMetadata() {
		metadata[k] = v
	}

	return &domain.ProfileSample{
		SampleID:    uuid.New().String(),
		ProfileType: profileType,
		Data:        data,
		Metadata:    metadata,
		Duration:    duration.Nanoseconds(),
		Timestamp:   time.Now(),
		CreatedAt:   time.Now(),
	}
}

func (p *Profiler) defaultMetadata() map[string]interface{} {
	return map[string]interface{}{
		"go_version": runtime.Version(),
		"os":         runtime.GOOS,
		"arch":       runtime.GOARCH,
		"cpus":       runtime.NumCPU(),
	}
}

func GetProfileSample(ctx context.Context, sampleID string) (*domain.ProfileSample, error) {
	var sample domain.ProfileSample
	if err := database.DB.WithContext(ctx).Where("sample_id = ?", sampleID).First(&sample).Error; err != nil {
		return nil, apperrors.Internal("get profile sample failed", err)
	}
	return &sample, nil
}

func ListProfileSamples(ctx context.Context, profileType domain.ProfileType, offset, limit int) ([]domain.ProfileSample, int64, error) {
	var samples []domain.ProfileSample
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.ProfileSample{})
	if profileType != "" {
		query = query.Where("profile_type = ?", profileType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, apperrors.Internal("count profile samples failed", err)
	}

	if err := query.Order("timestamp DESC").Offset(offset).Limit(limit).Find(&samples).Error; err != nil {
		return nil, 0, apperrors.Internal("list profile samples failed", err)
	}

	return samples, total, nil
}

func DeleteProfileSample(ctx context.Context, sampleID string) error {
	if err := database.DB.WithContext(ctx).Where("sample_id = ?", sampleID).Delete(&domain.ProfileSample{}).Error; err != nil {
		return apperrors.Internal("delete profile sample failed", err)
	}
	return nil
}
