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

type VersionRepository struct {
	db *gorm.DB
}

func NewVersionRepository(db *gorm.DB) *VersionRepository {
	return &VersionRepository{db: db}
}

func (r *VersionRepository) CreateSnapshotLabel(ctx context.Context, tenantID uuid.UUID, docIDs []uuid.UUID, label string, creatorID uuid.UUID) (uuid.UUID, error) {
	snapshotID := uuid.New()
	now := time.Now().UTC()

	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		for _, docID := range docIDs {
			latestVer, err := r.getLatestVerForDoc(ctx, tx, docID, tenantID)
			if err != nil {
				return err
			}
			if latestVer == 0 {
				continue
			}
			if err := tx.Model(&model.DocumentVersion{}).
				Where("document_id = ? AND version = ? AND tenant_id = ?", docID, latestVer, tenantID).
				UpdateColumns(map[string]interface{}{
					"snapshot_label": label,
					"updated_at":     now,
				}).Error; err != nil {
				return err
			}
		}
		return nil
	})

	return snapshotID, err
}

func (r *VersionRepository) getLatestVerForDoc(ctx context.Context, tx *gorm.DB, docID uuid.UUID, tenantID uuid.UUID) (int, error) {
	var maxVersion int
	err := tx.Model(&model.DocumentVersion{}).
		Where("document_id = ? AND tenant_id = ?", docID, tenantID).
		Select("COALESCE(MAX(version), 0)").
		Scan(&maxVersion).Error
	return maxVersion, err
}

func (r *VersionRepository) ListBySnapshotLabel(ctx context.Context, tenantID uuid.UUID, label string) ([]model.DocumentVersion, error) {
	var versions []model.DocumentVersion
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND snapshot_label = ?", tenantID, label).
		Preload("Document").
		Preload("Editor").
		Order("created_at DESC").
		Find(&versions).Error
	return versions, err
}

func (r *VersionRepository) ListSnapshots(ctx context.Context, tenantID uuid.UUID, page, pageSize int) (*database.PaginatedResult, error) {
	type SnapshotInfo struct {
		Label       string    `json:"label"`
		DocCount    int64     `json:"doc_count"`
		CreatedAt   time.Time `json:"created_at"`
		LatestEditor string   `json:"latest_editor"`
	}

	var results []SnapshotInfo
	var total int64

	labelQuery := r.db.WithContext(ctx).Model(&model.DocumentVersion{}).
		Where("tenant_id = ? AND snapshot_label IS NOT NULL AND snapshot_label != ''", tenantID).
		Select("snapshot_label as label, COUNT(*) as doc_count, MAX(created_at) as created_at").
		Group("snapshot_label").
		Order("created_at DESC")

	err := labelQuery.Count(&total).Error
	if err != nil {
		return nil, err
	}

	offset := (page - 1) * pageSize
	err = labelQuery.Offset(offset).Limit(pageSize).Scan(&results).Error
	if err != nil {
		return nil, err
	}

	totalPages := int(total) / pageSize
	if int(total)%pageSize > 0 {
		totalPages++
	}

	return &database.PaginatedResult{
		Total:      total,
		Page:       page,
		PageSize:   pageSize,
		TotalPages: totalPages,
		Data:       results,
	}, nil
}

func (r *VersionRepository) RestoreVersion(ctx context.Context, docID uuid.UUID, version int, restorerID uuid.UUID) (*model.Document, *model.DocumentVersion, error) {
	var restoredDoc *model.Document
	var newVersion *model.DocumentVersion

	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var srcVersion model.DocumentVersion
		err := tx.Scopes(database.TenantScope(ctx)).
			Where("document_id = ? AND version = ?", docID, version).
			First(&srcVersion).Error
		if err != nil {
			return err
		}

		var doc model.Document
		err = tx.Scopes(database.TenantScope(ctx)).First(&doc, docID).Error
		if err != nil {
			return err
		}

		nextVerNum, err := r.getLatestVerForDoc(ctx, tx, docID, doc.TenantID)
		if err != nil {
			return err
		}
		nextVerNum++

		doc.Title = srcVersion.Title
		doc.Content = srcVersion.Content
		doc.Summary = srcVersion.Summary
		doc.Tags = srcVersion.Tags
		doc.WordCount = srcVersion.WordCount
		doc.LastEditorID = restorerID
		doc.CurrentVersion = nextVerNum
		doc.UpdatedAt = time.Now().UTC()
		if err := tx.Save(&doc).Error; err != nil {
			return err
		}

		nv := model.DocumentVersion{
			BaseModel: model.BaseModel{
				ID:        uuid.New(),
				CreatedAt: time.Now().UTC(),
				UpdatedAt: time.Now().UTC(),
			},
			TenantScoped: model.TenantScoped{TenantID: doc.TenantID},
			DocumentID:   docID,
			Version:      nextVerNum,
			Title:        srcVersion.Title,
			Content:      srcVersion.Content,
			Summary:      srcVersion.Summary,
			Tags:         srcVersion.Tags,
			EditorID:     restorerID,
			ChangeLog:    "Restored from version " + string(rune(version+'0')),
			WordCount:    srcVersion.WordCount,
			SizeBytes:    srcVersion.SizeBytes,
		}
		if err := tx.Create(&nv).Error; err != nil {
			return err
		}

		restoredDoc = &doc
		newVersion = &nv
		return nil
	})

	return restoredDoc, newVersion, err
}

func (r *VersionRepository) PurgeOldVersions(ctx context.Context, docID uuid.UUID, keepVersions int) (int64, error) {
	if keepVersions <= 0 {
		keepVersions = 10
	}

	subQuery := r.db.WithContext(ctx).Model(&model.DocumentVersion{}).
		Where("document_id = ?", docID).
		Order("version DESC").
		Limit(keepVersions).
		Select("version")

	result := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Where("document_id = ? AND version NOT IN (?)", docID, subQuery).
		Delete(&model.DocumentVersion{})

	return result.RowsAffected, result.Error
}

func (r *VersionRepository) GetVersionByID(ctx context.Context, id uuid.UUID) (*model.DocumentVersion, error) {
	var v model.DocumentVersion
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).
		Preload("Document").
		Preload("Editor").
		First(&v, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &v, nil
}
