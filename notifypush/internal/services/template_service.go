package services

import (
	"errors"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"strings"
	"time"
)

type TemplateService struct {
	storage         *storage.MemoryStorage
	expressionEngine *ExpressionEngine
}

func NewTemplateService(storage *storage.MemoryStorage) *TemplateService {
	return &TemplateService{
		storage:          storage,
		expressionEngine: NewExpressionEngine(),
	}
}

func (s *TemplateService) CreateTemplate(req *models.TemplateCreateRequest) (*models.Template, error) {
	if req.TemplateID == "" {
		return nil, errors.New("template_id is required")
	}
	if req.TemplateName == "" {
		return nil, errors.New("template_name is required")
	}
	if req.TemplateContent == "" {
		return nil, errors.New("template_content is required")
	}

	existing, _ := s.storage.GetTemplate(req.TemplateID)
	if existing != nil {
		return nil, errors.New("template already exists")
	}

	template := &models.Template{
		TemplateID:      req.TemplateID,
		TemplateName:    req.TemplateName,
		TemplateType:    models.TemplateType(req.TemplateType),
		TemplateContent: req.TemplateContent,
		Subject:         req.Subject,
		Variables:       req.Variables,
		Status:          "active",
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	err := s.storage.SaveTemplate(template)
	if err != nil {
		return nil, err
	}

	return template, nil
}

func (s *TemplateService) GetTemplate(templateID string) (*models.Template, error) {
	template, err := s.storage.GetTemplate(templateID)
	if err != nil {
		return nil, err
	}
	if template == nil {
		return nil, errors.New("template not found")
	}
	return template, nil
}

func (s *TemplateService) ParseTemplate(templateID string, variables map[string]string) (string, string, error) {
	template, err := s.GetTemplate(templateID)
	if err != nil {
		return "", "", err
	}

	content := template.TemplateContent
	subject := template.Subject

	useExpressionEngine := strings.Contains(content, "{{") || strings.Contains(subject, "{{")
	useSimpleReplacement := strings.Contains(content, "{") && !strings.Contains(content, "{{")

	if useExpressionEngine {
		parsedContent, err := s.expressionEngine.Parse(content, variables)
		if err != nil {
			return "", "", err
		}
		parsedSubject, err := s.expressionEngine.Parse(subject, variables)
		if err != nil {
			return "", "", err
		}
		return parsedContent, parsedSubject, nil
	}

	if useSimpleReplacement {
		for _, varName := range template.Variables {
			placeholder := "{" + varName + "}"
			if strings.Contains(content, placeholder) {
				value, exists := variables[varName]
				if !exists {
					return "", "", errors.New("missing variable: " + varName)
				}
				content = strings.ReplaceAll(content, placeholder, value)
			}
		}
	}

	return content, subject, nil
}

func (s *TemplateService) ValidateTemplate(templateID string) (bool, error) {
	template, err := s.GetTemplate(templateID)
	if err != nil {
		return false, err
	}

	if template.Status != "active" {
		return false, errors.New("template is not active")
	}

	return true, nil
}
