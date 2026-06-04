package auth

import (
	"context"
	"fmt"
	"time"

	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/storage"
	"github.com/google/uuid"
)

type Role string

const (
	RoleAdmin  Role = "admin"
	RoleEditor Role = "editor"
	RoleViewer Role = "viewer"
)

type Permission string

const (
	PermTaskCreate   Permission = "task:create"
	PermTaskRead     Permission = "task:read"
	PermTaskUpdate   Permission = "task:update"
	PermTaskDelete   Permission = "task:delete"
	PermTaskExecute  Permission = "task:execute"
	PermExecutionRead Permission = "execution:read"
	PermWorkerManage Permission = "worker:manage"
	PermNamespaceManage Permission = "namespace:manage"
)

type AuthManager struct {
	db *storage.Database
}

type UserContext struct {
	UserID    string
	Namespace string
	Roles     []Role
}

type contextKey string

const userContextKey contextKey = "user"

func NewAuthManager(db *storage.Database) *AuthManager {
	return &AuthManager{db: db}
}

func ContextWithUser(ctx context.Context, user *UserContext) context.Context {
	return context.WithValue(ctx, userContextKey, user)
}

func UserFromContext(ctx context.Context) (*UserContext, bool) {
	user, ok := ctx.Value(userContextKey).(*UserContext)
	return user, ok
}

func (am *AuthManager) CreateNamespace(name, description, createdBy string) (*models.Namespace, error) {
	ns := &models.Namespace{
		ID:          uuid.New().String(),
		Name:        name,
		Description: description,
		CreatedBy:   createdBy,
		CreatedAt:   time.Now(),
	}

	query := `
		INSERT INTO namespaces (id, name, description, created_by, created_at)
		VALUES ($1, $2, $3, $4, $5)
	`

	_, err := am.db.Exec(query, ns.ID, ns.Name, ns.Description, ns.CreatedBy, ns.CreatedAt)
	if err != nil {
		return nil, fmt.Errorf("failed to create namespace: %w", err)
	}

	return ns, nil
}

func (am *AuthManager) GetNamespace(name string) (*models.Namespace, error) {
	var ns models.Namespace
	err := am.db.Get(&ns, "SELECT * FROM namespaces WHERE name = $1", name)
	if err != nil {
		return nil, fmt.Errorf("namespace not found: %w", err)
	}
	return &ns, nil
}

func (am *AuthManager) ListNamespaces() ([]models.Namespace, error) {
	var namespaces []models.Namespace
	err := am.db.Select(&namespaces, "SELECT * FROM namespaces ORDER BY name")
	if err != nil {
		return nil, err
	}
	return namespaces, nil
}

func (am *AuthManager) CheckPermission(user *UserContext, namespace string, perm Permission) bool {
	if user == nil {
		return false
	}

	if user.Namespace != "*" && user.Namespace != namespace {
		return false
	}

	for _, role := range user.Roles {
		if role == RoleAdmin {
			return true
		}
	}

	editorPerms := map[Permission]bool{
		PermTaskCreate:   true,
		PermTaskRead:     true,
		PermTaskUpdate:   true,
		PermTaskExecute:  true,
		PermExecutionRead: true,
	}

	viewerPerms := map[Permission]bool{
		PermTaskRead:     true,
		PermExecutionRead: true,
	}

	for _, role := range user.Roles {
		switch role {
		case RoleEditor:
			if editorPerms[perm] {
				return true
			}
		case RoleViewer:
			if viewerPerms[perm] {
				return true
			}
		}
	}

	return false
}

func (am *AuthManager) LogAudit(namespace, actor, action, resource, resourceID, ipAddress string, oldValue, newValue []byte) error {
	audit := &models.AuditLog{
		ID:         uuid.New().String(),
		Namespace:  namespace,
		Actor:      actor,
		Action:     action,
		Resource:   resource,
		ResourceID: resourceID,
		OldValue:   oldValue,
		NewValue:   newValue,
		IPAddress:  ipAddress,
		CreatedAt:  time.Now(),
	}

	query := `
		INSERT INTO audit_logs (id, namespace, actor, action, resource, resource_id, 
			old_value, new_value, ip_address, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
	`

	_, err := am.db.Exec(query, audit.ID, audit.Namespace, audit.Actor, audit.Action,
		audit.Resource, audit.ResourceID, audit.OldValue, audit.NewValue,
		audit.IPAddress, audit.CreatedAt)
	if err != nil {
		return fmt.Errorf("failed to log audit: %w", err)
	}

	return nil
}

func (am *AuthManager) QueryAuditLogs(namespace string, limit, offset int) ([]models.AuditLog, int, error) {
	var logs []models.AuditLog
	var total int

	countQuery := `SELECT COUNT(*) FROM audit_logs WHERE namespace = $1 OR $1 = ''`
	err := am.db.Get(&total, countQuery, namespace)
	if err != nil {
		return nil, 0, err
	}

	query := `
		SELECT * FROM audit_logs 
		WHERE namespace = $1 OR $1 = '' 
		ORDER BY created_at DESC 
		LIMIT $2 OFFSET $3
	`
	err = am.db.Select(&logs, query, namespace, limit, offset)
	if err != nil {
		return nil, 0, err
	}

	return logs, total, nil
}

func (am *AuthManager) GetAdminUser() *UserContext {
	return &UserContext{
		UserID:    "admin",
		Namespace: "*",
		Roles:     []Role{RoleAdmin},
	}
}
