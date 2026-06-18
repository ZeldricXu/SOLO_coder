package repository

import (
	"context"
	"errors"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type UserRepository struct {
	db *gorm.DB
}

func NewUserRepository(db *gorm.DB) *UserRepository {
	return &UserRepository{db: db}
}

func (r *UserRepository) Create(ctx context.Context, user *model.User) error {
	user.CreatedAt = time.Now().UTC()
	user.UpdatedAt = time.Now().UTC()
	if user.ID == uuid.Nil {
		user.ID = uuid.New()
	}
	err := r.db.WithContext(ctx).Create(user).Error
	if err != nil {
		if IsUniqueViolation(err) {
			return ErrAlreadyExists
		}
		return err
	}
	return nil
}

func (r *UserRepository) GetByID(ctx context.Context, id uuid.UUID) (*model.User, error) {
	var user model.User
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Preload("Department").First(&user, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &user, nil
}

func (r *UserRepository) GetByUsername(ctx context.Context, tenantID uuid.UUID, username string) (*model.User, error) {
	var user model.User
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND username = ?", tenantID, username).
		First(&user).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &user, nil
}

func (r *UserRepository) GetByEmail(ctx context.Context, tenantID uuid.UUID, email string) (*model.User, error) {
	var user model.User
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND email = ?", tenantID, email).
		First(&user).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &user, nil
}

func (r *UserRepository) Update(ctx context.Context, user *model.User) error {
	user.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Save(user)
	if result.Error != nil {
		if IsUniqueViolation(result.Error) {
			return ErrAlreadyExists
		}
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) Delete(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.User{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) List(ctx context.Context, q *UserListQuery) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Model(&model.User{}).Scopes(database.TenantScope(ctx))
	if q != nil {
		if q.DepartmentID != uuid.Nil {
			query = query.Where("department_id = ?", q.DepartmentID)
		}
		if q.Status != "" {
			query = query.Where("status = ?", q.Status)
		}
		if q.Keyword != "" {
			query = query.Where("(username ILIKE ? OR email ILIKE ? OR full_name ILIKE ?)",
				"%"+q.Keyword+"%", "%"+q.Keyword+"%", "%"+q.Keyword+"%")
		}
		if q.IsSuperAdmin != nil {
			query = query.Where("is_super_admin = ?", *q.IsSuperAdmin)
		}
	}

	var users []model.User
	pr, err := database.Paginate(query.Preload("Department").Order("created_at DESC"),
		q.Page, q.PageSize, &users)
	if err != nil {
		return nil, err
	}
	pr.Data = users
	return pr, nil
}

type UserListQuery struct {
	TenantID      uuid.UUID
	DepartmentID  uuid.UUID
	Status        model.UserStatus
	Keyword       string
	Page          int
	PageSize      int
	IsSuperAdmin  *bool
}

func (r *UserRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status model.UserStatus) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.User{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"status":     status,
			"updated_at": time.Now().UTC(),
		})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) UpdateLastLogin(ctx context.Context, id uuid.UUID, ip string) error {
	now := time.Now().UTC()
	return r.db.WithContext(ctx).Model(&model.User{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"last_login_at": &now,
			"last_login_ip": ip,
			"updated_at":    now,
		}).Error
}

func (r *UserRepository) CreateGroup(ctx context.Context, group *model.UserGroup) error {
	group.CreatedAt = time.Now().UTC()
	group.UpdatedAt = time.Now().UTC()
	if group.ID == uuid.Nil {
		group.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(group).Error
}

func (r *UserRepository) GetGroup(ctx context.Context, id uuid.UUID) (*model.UserGroup, error) {
	var group model.UserGroup
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Preload("Users").First(&group, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &group, nil
}

func (r *UserRepository) ListGroups(ctx context.Context, tenantID uuid.UUID, keyword string, page, pageSize int) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Model(&model.UserGroup{}).Where("tenant_id = ?", tenantID)
	if keyword != "" {
		query = query.Where("name ILIKE ? OR code ILIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}

	var groups []model.UserGroup
	pr, err := database.Paginate(query.Order("created_at DESC"), page, pageSize, &groups)
	if err != nil {
		return nil, err
	}
	pr.Data = groups
	return pr, nil
}

func (r *UserRepository) UpdateGroup(ctx context.Context, group *model.UserGroup) error {
	group.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Save(group)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) DeleteGroup(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.UserGroup{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) AddUserToGroup(ctx context.Context, tenantID, groupID, userID, addedBy uuid.UUID) error {
	member := model.UserGroupMember{
		ID:       uuid.New(),
		GroupID:  groupID,
		UserID:   userID,
		TenantID: tenantID,
		JoinedAt: time.Now().UTC(),
		AddedBy:  addedBy,
	}
	err := r.db.WithContext(ctx).Create(&member).Error
	if err != nil {
		if IsUniqueViolation(err) {
			return ErrAlreadyExists
		}
		return err
	}
	return nil
}

func (r *UserRepository) RemoveUserFromGroup(ctx context.Context, groupID, userID uuid.UUID) error {
	result := r.db.WithContext(ctx).
		Where("group_id = ? AND user_id = ?", groupID, userID).
		Delete(&model.UserGroupMember{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) GetUserGroups(ctx context.Context, userID uuid.UUID) ([]model.UserGroup, error) {
	var groups []model.UserGroup
	err := r.db.WithContext(ctx).
		Raw(`SELECT ug.* FROM user_groups ug
			INNER JOIN user_group_members ugm ON ug.id = ugm.group_id
			WHERE ugm.user_id = ?`, userID).
		Scan(&groups).Error
	return groups, err
}

func (r *UserRepository) CreateDepartment(ctx context.Context, dept *model.Department) error {
	dept.CreatedAt = time.Now().UTC()
	dept.UpdatedAt = time.Now().UTC()
	if dept.ID == uuid.Nil {
		dept.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(dept).Error
}

func (r *UserRepository) GetDepartment(ctx context.Context, id uuid.UUID) (*model.Department, error) {
	var dept model.Department
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Preload("Children").First(&dept, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &dept, nil
}

func (r *UserRepository) ListDepartments(ctx context.Context, tenantID uuid.UUID) ([]model.Department, error) {
	var depts []model.Department
	err := r.db.WithContext(ctx).
		Where("tenant_id = ?", tenantID).
		Order("sort_order ASC, created_at ASC").
		Find(&depts).Error
	return depts, err
}

func (r *UserRepository) UpdateDepartment(ctx context.Context, dept *model.Department) error {
	dept.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Save(dept)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) DeleteDepartment(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.Department{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *UserRepository) CreateAPIToken(ctx context.Context, token *model.ApiToken) error {
	token.CreatedAt = time.Now().UTC()
	token.UpdatedAt = time.Now().UTC()
	if token.ID == uuid.Nil {
		token.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(token).Error
}

func (r *UserRepository) GetAPIToken(ctx context.Context, id uuid.UUID) (*model.ApiToken, error) {
	var token model.ApiToken
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&token, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &token, nil
}

func (r *UserRepository) GetAPITokenByHash(ctx context.Context, tokenHash string) (*model.ApiToken, error) {
	var token model.ApiToken
	err := r.db.WithContext(ctx).Where("token_hash = ?", tokenHash).First(&token).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &token, nil
}

func (r *UserRepository) ListAPITokens(ctx context.Context, userID uuid.UUID) ([]model.ApiToken, error) {
	var tokens []model.ApiToken
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("user_id = ?", userID).
		Order("created_at DESC").
		Find(&tokens).Error
	return tokens, err
}

func (r *UserRepository) UpdateAPITokenUsage(ctx context.Context, id uuid.UUID, ip string) error {
	now := time.Now().UTC()
	return r.db.WithContext(ctx).Model(&model.ApiToken{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"last_used_at": &now,
			"last_used_ip": ip,
			"use_count":    gorm.Expr("use_count + 1"),
		}).Error
}

func (r *UserRepository) RevokeAPIToken(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Model(&model.ApiToken{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"status":     model.ApiTokenStatusRevoked,
			"updated_at": time.Now().UTC(),
		})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}
