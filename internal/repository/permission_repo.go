package repository

import (
	"context"
	"errors"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type PermissionRepo struct {
	*BaseRepo
}

func NewPermissionRepo(db *gorm.DB) *PermissionRepo {
	return &PermissionRepo{BaseRepo: NewBaseRepo(db)}
}

func (r *PermissionRepo) Grant(ctx context.Context, perm *model.Permission) error {
	return r.WithTenant(ctx).Create(perm).Error
}

func (r *PermissionRepo) Revoke(ctx context.Context, permID uuid.UUID) error {
	return r.WithTenant(ctx).Where("id = ?", permID.String()).Delete(&model.Permission{}).Error
}

func (r *PermissionRepo) CheckPermission(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType, action model.PermissionAction) (bool, error) {
	requiredRole := model.ActionToRequiredRole(action)
	requiredWeight := model.RoleWeight(requiredRole)

	var perm model.Permission
	err := r.WithTenant(ctx).
		Where("subject_type = ? AND subject_id = ? AND resource_type = ? AND resource_id = ?",
			model.SubjectTypeUser, userID.String(), resourceType, resourceID.String()).
		First(&perm).Error

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return false, nil
		}
		return false, err
	}

	return model.RoleWeight(perm.Role) >= requiredWeight, nil
}

func (r *PermissionRepo) GetUserRole(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType) (model.Role, error) {
	var perm model.Permission
	err := r.WithTenant(ctx).
		Where("subject_type = ? AND subject_id = ? AND resource_type = ? AND resource_id = ?",
			model.SubjectTypeUser, userID.String(), resourceType, resourceID.String()).
		First(&perm).Error

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return "", nil
		}
		return "", err
	}

	return perm.Role, nil
}

func (r *PermissionRepo) CheckByGroups(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType, action model.PermissionAction) (bool, error) {
	requiredRole := model.ActionToRequiredRole(action)
	requiredWeight := model.RoleWeight(requiredRole)

	var groupIDs []string
	err := r.WithTenant(ctx).Model(&model.UserGroupMember{}).
		Where("user_id = ?", userID.String()).
		Pluck("group_id", &groupIDs).Error
	if err != nil {
		return false, err
	}

	if len(groupIDs) == 0 {
		return false, nil
	}

	var perms []model.Permission
	err = r.WithTenant(ctx).
		Where("subject_type = ? AND subject_id IN ? AND resource_type = ? AND resource_id = ?",
			model.SubjectTypeGroup, groupIDs, resourceType, resourceID.String()).
		Find(&perms).Error
	if err != nil {
		return false, err
	}

	for _, perm := range perms {
		if model.RoleWeight(perm.Role) >= requiredWeight {
			return true, nil
		}
	}

	return false, nil
}
