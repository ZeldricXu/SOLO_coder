package notification

import (
	"fmt"
	"sync"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type TemplateManager struct {
	templates map[string]*contracts.NotificationTemplate
	mu        sync.RWMutex
}

func NewTemplateManager() *TemplateManager {
	return &TemplateManager{
		templates: make(map[string]*contracts.NotificationTemplate),
	}
}

func (m *TemplateManager) Register(template *contracts.NotificationTemplate) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.templates[template.ID] = template
	return nil
}

func (m *TemplateManager) Get(templateID string) (*contracts.NotificationTemplate, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	tpl, exists := m.templates[templateID]
	if !exists {
		return nil, fmt.Errorf("template not found: %s", templateID)
	}
	return tpl, nil
}

func (m *TemplateManager) List() []*contracts.NotificationTemplate {
	m.mu.RLock()
	defer m.mu.RUnlock()

	templates := make([]*contracts.NotificationTemplate, 0, len(m.templates))
	for _, tpl := range m.templates {
		templates = append(templates, tpl)
	}
	return templates
}
