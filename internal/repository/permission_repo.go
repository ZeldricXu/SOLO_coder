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

type PermissionRepository struct {
	db *gorm.DB
}

func NewPermissionRepository(db *gorm.DB) *PermissionRepository {
	return &PermissionRepository{db: db}
}

func (r *PermissionRepository) Create(ctx context.Context, perm *model.Permission) error {
	perm.CreatedAt = time.Now().UTC()
	perm.UpdatedAt = time.Now().UTC()
	if perm.ID == uuid.Nil {
		perm.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(perm).Error
}

func (r *PermissionRepository) GetByID(ctx context.Context, id uuid.UUID) (*model.Permission, error) {
	var perm model.Permission
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&perm, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &perm, nil
}

func (r *PermissionRepository) Update(ctx context.Context, perm *model.Permission) error {
	perm.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Save(perm)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *PermissionRepository) Delete(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).Delete(&model.Permission{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *PermissionRepository) DeleteByResource(ctx context.Context, resourceType model.ResourceType, resourceID uuid.UUID) error {
	return r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("resource_type = ? AND resource_id = ?", resourceType, resourceID).
		Delete(&model.Permission{}).Error
}

func (r *PermissionRepository) ListByResource(ctx context.Context, resourceType model.ResourceType, resourceID uuid.UUID) ([]model.Permission, error) {
	var perms []model.Permission
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("resource_type = ? AND resource_id = ?", resourceType, resourceID).
		Order("created_at ASC").
		Find(&perms).Error
	return perms, err
}

func (r *PermissionRepository) ListBySubject(ctx context.Context, subjectType model.SubjectType, subjectID uuid.UUID) ([]model.Permission, error) {
	var perms []model.Permission
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("subject_type = ? AND subject_id = ?", subjectType, subjectID).
		Find(&perms).Error
	return perms, err
}

func (r *PermissionRepository) GetSubjectsResources(ctx context.Context, userID uuid.UUID, groupIDs []uuid.UUID, deptIDs []uuid.UUID) ([]model.Permission, error) {
	query := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx))

	orConditions := r.db.Where("subject_type = ? AND subject_id = ?", model.SubjectTypeUser, userID)

	if len(groupIDs) > 0 {
		orConditions = orConditions.Or("subject_type = ? AND subject_id IN ?", model.SubjectTypeGroup, groupIDs)
	}
	if len(deptIDs) > 0 {
		orConditions = orConditions.Or("subject_type = ? AND subject_id IN ?", model.SubjectTypeDepartment, deptIDs)
	}

	query = query.Where(orConditions)

	var perms []model.Permission
	err := query.Find(&perms).Error
	return perms, err
}

func (r *PermissionRepository) CheckPermission(ctx context.Context, userID uuid.UUID, groupIDs []uuid.UUID, deptIDs []uuid.UUID, resourceType model.ResourceType, resourceID uuid.UUID, action model.PermissionAction) (bool, error) {
	perms, err := r.GetSubjectsResources(ctx, userID, groupIDs, deptIDs)
	if err != nil {
		return false, err
	}

	maxRoleWeight := 0
	for _, perm := range perms {
		if perm.ResourceType != resourceType {
			continue
		}
		if perm.ResourceID != resourceID && perm.ResourceType != model.ResourceTypeTenant {
			continue
		}
		if perm.Effect != "allow" {
			continue
		}
		if perm.ExpiresAt != nil && time.Now().UTC().After(*perm.ExpiresAt) {
			continue
		}
		roleWeight := model.RoleWeight(perm.Role)
		if roleWeight > maxRoleWeight {
			maxRoleWeight = roleWeight
		}
	}

	requiredWeight := 0
	switch action {
	case model.ActionAdmin, model.ActionManage:
		requiredWeight = model.RoleWeight(model.RoleAdmin)
	case model.ActionCreate, model.ActionUpdate, model.ActionDelete:
		requiredWeight = model.RoleWeight(model.RoleEditor)
	case model.ActionReview:
		requiredWeight = model.RoleWeight(model.RoleReviewer)
	case model.ActionComment:
		requiredWeight = model.RoleWeight(model.RoleCommenter)
	case model.ActionRead, model.ActionExport:
		requiredWeight = model.RoleWeight(model.RoleViewer)
	default:
		requiredWeight = model.RoleWeight(model.RoleViewer)
	}

	return maxRoleWeight >= requiredWeight, nil
}

func (r *PermissionRepository) GetResourceRole(ctx context.Context, userID uuid.UUID, groupIDs []uuid.UUID, deptIDs []uuid.UUID, resourceType model.ResourceType, resourceID uuid.UUID) (model.Role, error) {
	perms, err := r.GetSubjectsResources(ctx, userID, groupIDs, deptIDs)
	if err != nil {
		return "", err
	}

	maxRoleWeight := 0
	maxRole := model.Role("")

	for _, perm := range perms {
		if perm.ResourceType == model.ResourceTypeTenant && perm.ResourceType != resourceType {
			roleWeight := model.RoleWeight(perm.Role)
			if roleWeight > maxRoleWeight {
				maxRoleWeight = roleWeight
				maxRole = perm.Role
			}
			continue
		}
		if perm.ResourceType != resourceType || perm.ResourceID != resourceID {
			continue
		}
		if perm.Effect != "allow" {
			continue
		}
		if perm.ExpiresAt != nil && time.Now().UTC().After(*perm.ExpiresAt) {
			continue
		}
		roleWeight := model.RoleWeight(perm.Role)
		if roleWeight > maxRoleWeight {
			maxRoleWeight = roleWeight
			maxRole = perm.Role
		}
	}

	return maxRole, nil
}

func (r *PermissionRepository) BatchCreate(ctx context.Context, perms []model.Permission) error {
	if len(perms) == 0 {
		return nil
	}
	now := time.Now().UTC()
	for i := range perms {
		if perms[i].ID == uuid.Nil {
			perms[i].ID = uuid.New()
		}
		perms[i].CreatedAt = now
		perms[i].UpdatedAt = now
	}
	return r.db.WithContext(ctx).Create(&perms).Error
}

func (r *PermissionRepository) GrantRole(ctx context.Context, resourceType model.ResourceType, resourceID uuid.UUID, subjectType model.SubjectType, subjectID uuid.UUID, role model.Role, grantedBy uuid.UUID) error {
	now := time.Now().UTC()
	tenantID, hasTenant := database.GetTenantID(ctx)
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var existing model.Permission
		query := tx.Where("resource_type = ? AND resource_id = ? AND subject_type = ? AND subject_id = ?",
			resourceType, resourceID, subjectType, subjectID)
		if hasTenant {
			query = query.Where("tenant_id = ?", tenantID)
		}
		err := query.First(&existing).Error

		if err == nil {
			existing.Role = role
			existing.UpdatedAt = now
			existing.GrantedBy = grantedBy
			return tx.Save(&existing).Error
		} else if !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}

		tenantID, _ := database.GetTenantID(ctx)
		perm := model.Permission{
			BaseModel: model.BaseModel{
				ID:        uuid.New(),
				CreatedAt: now,
				UpdatedAt: now,
			},
			TenantScoped: model.TenantScoped{TenantID: tenantID},
			ResourceType: resourceType,
			ResourceID:   resourceID,
			Role:         role,
			SubjectType:  subjectType,
			SubjectID:    subjectID,
			Actions:      model.StringArray{},
			Effect:       "allow",
			GrantedBy:    grantedBy,
		}
		return tx.Create(&perm).Error
	})
}

func (r *PermissionRepository) RevokeRole(ctx context.Context, resourceType model.ResourceType, resourceID uuid.UUID, subjectType model.SubjectType, subjectID uuid.UUID) error {
	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("resource_type = ? AND resource_id = ? AND subject_type = ? AND subject_id = ?",
			resourceType, resourceID, subjectType, subjectID).
		Delete(&model.Permission{})
	return result.Error
}

func (r *PermissionRepository) GetAccessibleResources(ctx context.Context, userID uuid.UUID, groupIDs []uuid.UUID, deptIDs []uuid.UUID, resourceType model.ResourceType, minRole model.Role) ([]uuid.UUID, error) {
	perms, err := r.GetSubjectsResources(ctx, userID, groupIDs, deptIDs)
	if err != nil {
		return nil, err
	}

	minWeight := model.RoleWeight(minRole)
	resourceIDs := make(map[uuid.UUID]struct{})

	for _, perm := range perms {
		if perm.ResourceType != resourceType && perm.ResourceType != model.ResourceTypeTenant {
			continue
		}
		if perm.Effect != "allow" {
			continue
		}
		if perm.ExpiresAt != nil && time.Now().UTC().After(*perm.ExpiresAt) {
			continue
		}
		if model.RoleWeight(perm.Role) >= minWeight {
			if perm.ResourceType == model.ResourceTypeTenant {
				return nil, nil
			}
			resourceIDs[perm.ResourceID] = struct{}{}
		}
	}

	ids := make([]uuid.UUID, 0, len(resourceIDs))
	for id := range resourceIDs {
		ids = append(ids, id)
	}
	return ids, nil
}
