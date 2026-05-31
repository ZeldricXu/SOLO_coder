package masking

import (
	"context"
	"strings"
	"sync"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
)

type RoleBasedMasker struct {
	sensitiveFields map[string][]domain.UserRole
	mu              sync.RWMutex
	logger          domain.Logger
}

type MaskingConfig struct {
	Logger domain.Logger
}

func NewRoleBasedMasker(cfg MaskingConfig) *RoleBasedMasker {
	masker := &RoleBasedMasker{
		sensitiveFields: make(map[string][]domain.UserRole),
		logger:          cfg.Logger,
	}

	masker.RegisterDefaultRules()
	return masker
}

func (m *RoleBasedMasker) RegisterDefaultRules() {
	m.RegisterSensitiveField("email", []domain.UserRole{domain.RoleAdmin})
	m.RegisterSensitiveField("phone", []domain.UserRole{domain.RoleAdmin, domain.RoleOperator})
	m.RegisterSensitiveField("id_card", []domain.UserRole{domain.RoleAdmin})
	m.RegisterSensitiveField("bank_account", []domain.UserRole{domain.RoleAdmin})
	m.RegisterSensitiveField("password", []domain.UserRole{})
	m.RegisterSensitiveField("address", []domain.UserRole{domain.RoleAdmin, domain.RoleOperator})
	m.RegisterSensitiveField("salary", []domain.UserRole{domain.RoleAdmin})
	m.RegisterSensitiveField("credit_card", []domain.UserRole{domain.RoleAdmin})
	m.RegisterSensitiveField("ssn", []domain.UserRole{domain.RoleAdmin})
	m.RegisterSensitiveField("medical_record", []domain.UserRole{domain.RoleAdmin, domain.RoleOperator})
}

func (m *RoleBasedMasker) Mask(ctx context.Context, data map[string]interface{}, user *domain.User) (map[string]interface{}, error) {
	if data == nil {
		return nil, apperr.NewValidationError("data is required", "input data cannot be nil")
	}
	if user == nil {
		return nil, apperr.NewValidationError("user is required", "user context cannot be nil")
	}

	result := m.deepCopy(data)
	m.maskMap(result, "", user)

	m.logger.Debug("data masked", "user_roles", user.Roles, "fields_processed", len(result))
	return result, nil
}

func (m *RoleBasedMasker) RegisterSensitiveField(fieldPath string, roles []domain.UserRole) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sensitiveFields[fieldPath] = roles
}

func (m *RoleBasedMasker) IsSensitive(fieldPath string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, exists := m.sensitiveFields[fieldPath]
	return exists
}

func (m *RoleBasedMasker) maskMap(data map[string]interface{}, prefix string, user *domain.User) {
	for k, v := range data {
		currentPath := k
		if prefix != "" {
			currentPath = prefix + "." + k
		}

		switch val := v.(type) {
		case map[string]interface{}:
			m.maskMap(val, currentPath, user)
		case []interface{}:
			for i, item := range val {
				if nested, ok := item.(map[string]interface{}); ok {
					m.maskMap(nested, currentPath+"["+string(rune(i))+"]", user)
				}
			}
		default:
			if m.needsMasking(currentPath, user) {
				data[k] = m.maskValue(k, v)
			}
		}
	}
}

func (m *RoleBasedMasker) needsMasking(fieldPath string, user *domain.User) bool {
	m.mu.RLock()
	roles, exists := m.sensitiveFields[fieldPath]
	m.mu.RUnlock()

	if !exists {
		parts := strings.Split(fieldPath, ".")
		for i := len(parts) - 1; i >= 0; i-- {
			parentPath := strings.Join(parts[:i+1], ".")
			m.mu.RLock()
			roles, exists = m.sensitiveFields[parentPath]
			m.mu.RUnlock()
			if exists {
				break
			}
		}
	}

	if !exists {
		return false
	}

	if len(roles) == 0 {
		return true
	}

	for _, userRole := range user.Roles {
		for _, allowedRole := range roles {
			if userRole == allowedRole {
				return false
			}
		}
	}

	return true
}

func (m *RoleBasedMasker) maskValue(fieldName string, value interface{}) interface{} {
	if value == nil {
		return nil
	}

	strVal, ok := value.(string)
	if !ok {
		strVal = "***"
	}

	switch {
	case strings.Contains(fieldName, "email"):
		return maskEmail(strVal)
	case strings.Contains(fieldName, "phone") || strings.Contains(fieldName, "mobile"):
		return maskPhone(strVal)
	case strings.Contains(fieldName, "id_card") || strings.Contains(fieldName, "idcard"):
		return maskIDCard(strVal)
	case strings.Contains(fieldName, "bank") || strings.Contains(fieldName, "account"):
		return maskBankAccount(strVal)
	case strings.Contains(fieldName, "credit") || strings.Contains(fieldName, "card"):
		return maskCreditCard(strVal)
	case strings.Contains(fieldName, "password") || strings.Contains(fieldName, "secret"):
		return "********"
	default:
		return maskGeneric(strVal)
	}
}

func maskEmail(email string) string {
	parts := strings.Split(email, "@")
	if len(parts) != 2 {
		return "***@***.***"
	}
	username := parts[0]
	domain := parts[1]

	if len(username) <= 2 {
		username = strings.Repeat("*", len(username))
	} else {
		username = string(username[0]) + strings.Repeat("*", len(username)-2) + string(username[len(username)-1])
	}

	return username + "@" + domain
}

func maskPhone(phone string) string {
	if len(phone) < 7 {
		return strings.Repeat("*", len(phone))
	}
	return phone[:3] + strings.Repeat("*", len(phone)-7) + phone[len(phone)-4:]
}

func maskIDCard(id string) string {
	if len(id) < 10 {
		return strings.Repeat("*", len(id))
	}
	return id[:6] + strings.Repeat("*", len(id)-10) + id[len(id)-4:]
}

func maskBankAccount(account string) string {
	if len(account) < 8 {
		return strings.Repeat("*", len(account))
	}
	return account[:4] + strings.Repeat("*", len(account)-8) + account[len(account)-4:]
}

func maskCreditCard(card string) string {
	card = strings.ReplaceAll(card, " ", "")
	card = strings.ReplaceAll(card, "-", "")
	if len(card) < 8 {
		return strings.Repeat("*", len(card))
	}
	return card[:4] + " **** **** " + card[len(card)-4:]
}

func maskGeneric(s string) string {
	if len(s) <= 4 {
		return strings.Repeat("*", len(s))
	}
	if len(s) <= 8 {
		return string(s[0]) + strings.Repeat("*", len(s)-2) + string(s[len(s)-1])
	}
	return string(s[:2]) + strings.Repeat("*", len(s)-4) + string(s[len(s)-2:])
}

func (m *RoleBasedMasker) deepCopy(src map[string]interface{}) map[string]interface{} {
	dst := make(map[string]interface{})
	for k, v := range src {
		dst[k] = copyValue(v)
	}
	return dst
}

func copyValue(v interface{}) interface{} {
	switch val := v.(type) {
	case map[string]interface{}:
		dst := make(map[string]interface{})
		for mk, mv := range val {
			dst[mk] = copyValue(mv)
		}
		return dst
	case []interface{}:
		dst := make([]interface{}, len(val))
		for i, item := range val {
			dst[i] = copyValue(item)
		}
		return dst
	default:
		return v
	}
}

type MaskingRule struct {
	FieldPath string
	Strategy  string
	Pattern   string
	Roles     []domain.UserRole
}

func (m *RoleBasedMasker) RegisterRule(rule MaskingRule) {
	m.RegisterSensitiveField(rule.FieldPath, rule.Roles)
}

func (m *RoleBasedMasker) GetSensitiveFields() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	fields := make([]string, 0, len(m.sensitiveFields))
	for f := range m.sensitiveFields {
		fields = append(fields, f)
	}
	return fields
}

func (m *RoleBasedMasker) RemoveRule(fieldPath string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.sensitiveFields, fieldPath)
}

type MaskingPolicy struct {
	DefaultMode   string
	Fields        []MaskingRule
	AllowOverride bool
}

func (m *RoleBasedMasker) ApplyPolicy(policy MaskingPolicy) {
	for _, rule := range policy.Fields {
		m.RegisterRule(rule)
	}
}

func (m *RoleBasedMasker) BatchMask(ctx context.Context, records []map[string]interface{}, user *domain.User) ([]map[string]interface{}, error) {
	result := make([]map[string]interface{}, len(records))
	for i, rec := range records {
		masked, err := m.Mask(ctx, rec, user)
		if err != nil {
			return nil, err
		}
		result[i] = masked
	}
	return result, nil
}
