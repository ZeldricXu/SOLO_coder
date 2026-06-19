package search

import (
	"context"
	"strings"
	"sync"
)

type OCRService interface {
	Name() string
	Enabled() bool
	ExtractText(ctx context.Context, imageData []byte, fileName string) (string, error)
	SupportsFormat(fileName string) bool
}

type OCRServiceRegistry struct {
	mu       sync.RWMutex
	services map[string]OCRService
}

func NewOCRServiceRegistry() *OCRServiceRegistry {
	return &OCRServiceRegistry{
		services: make(map[string]OCRService),
	}
}

func (r *OCRServiceRegistry) Register(service OCRService) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.services[service.Name()] = service
}

func (r *OCRServiceRegistry) Get(name string) (OCRService, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	svc, ok := r.services[name]
	return svc, ok
}

func (r *OCRServiceRegistry) List() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	names := make([]string, 0, len(r.services))
	for name := range r.services {
		names = append(names, name)
	}
	return names
}

func (r *OCRServiceRegistry) ExtractText(ctx context.Context, imageData []byte, fileName string, preferredService string) (string, error) {
	if preferredService != "" {
		if svc, ok := r.Get(preferredService); ok && svc.Enabled() && svc.SupportsFormat(fileName) {
			return svc.ExtractText(ctx, imageData, fileName)
		}
	}

	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, svc := range r.services {
		if svc.Enabled() && svc.SupportsFormat(fileName) {
			return svc.ExtractText(ctx, imageData, fileName)
		}
	}

	return "", nil
}

type StubOCRService struct{}

func (s *StubOCRService) Name() string {
	return "stub"
}

func (s *StubOCRService) Enabled() bool {
	return true
}

func (s *StubOCRService) ExtractText(ctx context.Context, imageData []byte, fileName string) (string, error) {
	return "", nil
}

func (s *StubOCRService) SupportsFormat(fileName string) bool {
	return isImageFile(fileName)
}

type TesseractMock struct {
	EnabledFlag bool
}

func (t *TesseractMock) Name() string {
	return "tesseract"
}

func (t *TesseractMock) Enabled() bool {
	return t.EnabledFlag
}

func (t *TesseractMock) ExtractText(ctx context.Context, imageData []byte, fileName string) (string, error) {
	return "", nil
}

func (t *TesseractMock) SupportsFormat(fileName string) bool {
	return isImageFile(fileName)
}

type PaddleOCRMock struct {
	EnabledFlag bool
}

func (p *PaddleOCRMock) Name() string {
	return "paddleocr"
}

func (p *PaddleOCRMock) Enabled() bool {
	return p.EnabledFlag
}

func (p *PaddleOCRMock) ExtractText(ctx context.Context, imageData []byte, fileName string) (string, error) {
	return "", nil
}

func (p *PaddleOCRMock) SupportsFormat(fileName string) bool {
	return isImageFile(fileName)
}

func isImageFile(fileName string) bool {
	imageExts := map[string]struct{}{
		".png":  {},
		".jpg":  {},
		".jpeg": {},
		".gif":  {},
		".bmp":  {},
		".tiff": {},
		".webp": {},
	}

	lowerName := strings.ToLower(fileName)
	for ext := range imageExts {
		if strings.HasSuffix(lowerName, ext) {
			return true
		}
	}
	return false
}
