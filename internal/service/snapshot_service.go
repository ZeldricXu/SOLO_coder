package service

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"github.com/minio/minio-go/v7"
	"gorm.io/gorm"
)

type SnapshotManifest struct {
	Version        string                 `json:"version"`
	SnapshotID     string                 `json:"snapshot_id"`
	SpaceID        string                 `json:"space_id"`
	TenantID       string                 `json:"tenant_id"`
	CreatedAt      time.Time              `json:"created_at"`
	SnapshotType   string                 `json:"snapshot_type"`
	IncludeAttachments bool               `json:"include_attachments"`
	IncludeDeleted bool                   `json:"include_deleted"`
	Directories    []*model.Directory     `json:"directories"`
	Documents      []*model.Document      `json:"documents"`
	Versions       []*model.DocumentVersion `json:"versions"`
	Attachments    []*model.Attachment    `json:"attachments"`
	Stats          SnapshotStats          `json:"stats"`
}

type SnapshotStats struct {
	DirCount        int `json:"dir_count"`
	DocCount        int `json:"doc_count"`
	VersionCount    int `json:"version_count"`
	AttachmentCount int `json:"attachment_count"`
}

type CreateSnapshotPolicyRequest struct {
	Name               string `json:"name"`
	Frequency          string `json:"frequency"`
	CronExpr           string `json:"cron_expr"`
	Hour               int    `json:"hour"`
	DayOfWeek          int    `json:"day_of_week"`
	DayOfMonth         int    `json:"day_of_month"`
	RetentionDays      int    `json:"retention_days"`
	IncludeAttachments bool   `json:"include_attachments"`
	IncludeDeleted     bool   `json:"include_deleted"`
	IsEnabled          bool   `json:"is_enabled"`
}

type UpdateSnapshotPolicyRequest struct {
	Name               string `json:"name"`
	Frequency          string `json:"frequency"`
	CronExpr           string `json:"cron_expr"`
	Hour               int    `json:"hour"`
	DayOfWeek          int    `json:"day_of_week"`
	DayOfMonth         int    `json:"day_of_month"`
	RetentionDays      int    `json:"retention_days"`
	IncludeAttachments *bool  `json:"include_attachments"`
	IncludeDeleted     *bool  `json:"include_deleted"`
	IsEnabled          *bool  `json:"is_enabled"`
}

type SnapshotService struct {
	db          *gorm.DB
	minioClient *minio.Client
	config      *config.Config
}

func NewSnapshotService(db *gorm.DB, minioClient *minio.Client, cfg *config.Config) *SnapshotService {
	return &SnapshotService{
		db:          db,
		minioClient: minioClient,
		config:      cfg,
	}
}

func (s *SnapshotService) CreateSnapshotPolicy(ctx context.Context, userID uuid.UUID, spaceID uuid.UUID, req CreateSnapshotPolicyRequest) (*model.SnapshotPolicy, error) {
	if req.Name == "" {
		return nil, errors.New("policy name is required")
	}
	if req.Frequency == "" {
		req.Frequency = "daily"
	}
	validFreqs := map[string]bool{"daily": true, "weekly": true, "monthly": true, "custom": true}
	if !validFreqs[req.Frequency] {
		return nil, errors.New("invalid frequency, must be daily/weekly/monthly/custom")
	}
	if req.Frequency == "custom" && req.CronExpr == "" {
		return nil, errors.New("cron_expr is required for custom frequency")
	}

	tenantIDStr, ok := database.GetTenantID(ctx)
	if !ok || tenantIDStr == "" {
		return nil, errors.New("tenant context missing")
	}

	if req.RetentionDays <= 0 {
		req.RetentionDays = s.config.Snapshot.DefaultRetentionDays
	}

	policy := &model.SnapshotPolicy{
		TenantScoped:       model.TenantScoped{TenantID: tenantIDStr},
		SpaceID:            spaceID.String(),
		Name:               req.Name,
		Frequency:          req.Frequency,
		CronExpr:           req.CronExpr,
		Hour:               req.Hour,
		DayOfWeek:          req.DayOfWeek,
		DayOfMonth:         req.DayOfMonth,
		RetentionDays:      req.RetentionDays,
		IncludeAttachments: req.IncludeAttachments,
		IncludeDeleted:     req.IncludeDeleted,
		IsEnabled:          req.IsEnabled,
		CreatedBy:          userID.String(),
	}

	policy.NextRunAt = s.CalculateNextRun(policy)

	if err := s.db.WithContext(ctx).Create(policy).Error; err != nil {
		return nil, fmt.Errorf("create snapshot policy: %w", err)
	}

	return policy, nil
}

func (s *SnapshotService) UpdateSnapshotPolicy(ctx context.Context, policyID uuid.UUID, req UpdateSnapshotPolicyRequest) (*model.SnapshotPolicy, error) {
	var policy model.SnapshotPolicy
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", policyID.String()).
		First(&policy).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("snapshot policy not found")
		}
		return nil, err
	}

	updates := map[string]interface{}{}
	if req.Name != "" {
		updates["name"] = req.Name
	}
	if req.Frequency != "" {
		validFreqs := map[string]bool{"daily": true, "weekly": true, "monthly": true, "custom": true}
		if !validFreqs[req.Frequency] {
			return nil, errors.New("invalid frequency")
		}
		updates["frequency"] = req.Frequency
	}
	if req.CronExpr != "" {
		updates["cron_expr"] = req.CronExpr
	}
	if req.Hour != 0 {
		updates["hour"] = req.Hour
	}
	if req.DayOfWeek != 0 || req.Frequency == "weekly" {
		updates["day_of_week"] = req.DayOfWeek
	}
	if req.DayOfMonth != 0 || req.Frequency == "monthly" {
		updates["day_of_month"] = req.DayOfMonth
	}
	if req.RetentionDays != 0 {
		updates["retention_days"] = req.RetentionDays
	}
	if req.IncludeAttachments != nil {
		updates["include_attachments"] = *req.IncludeAttachments
	}
	if req.IncludeDeleted != nil {
		updates["include_deleted"] = *req.IncludeDeleted
	}
	if req.IsEnabled != nil {
		updates["is_enabled"] = *req.IsEnabled
	}

	needRecalcNextRun := req.Frequency != "" || req.Hour != 0 || req.DayOfWeek != 0 || req.DayOfMonth != 0 || req.CronExpr != ""

	if len(updates) > 0 {
		if err := s.db.WithContext(ctx).Model(&policy).Updates(updates).Error; err != nil {
			return nil, fmt.Errorf("update snapshot policy: %w", err)
		}
	}

	if needRecalcNextRun {
		if err := s.db.WithContext(ctx).First(&policy, policyID.String()).Error; err != nil {
			return nil, err
		}
		nextRun := s.CalculateNextRun(&policy)
		if err := s.db.WithContext(ctx).Model(&policy).Update("next_run_at", nextRun).Error; err != nil {
			return nil, err
		}
		policy.NextRunAt = nextRun
	}

	return &policy, nil
}

func (s *SnapshotService) ListSnapshotPolicies(ctx context.Context, spaceID uuid.UUID) ([]*model.SnapshotPolicy, error) {
	var policies []*model.SnapshotPolicy
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("space_id = ?", spaceID.String()).
		Order("created_at DESC").
		Find(&policies).Error
	if err != nil {
		return nil, fmt.Errorf("list snapshot policies: %w", err)
	}
	return policies, nil
}

func (s *SnapshotService) DeleteSnapshotPolicy(ctx context.Context, policyID uuid.UUID) error {
	result := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", policyID.String()).
		Delete(&model.SnapshotPolicy{})
	if result.Error != nil {
		return fmt.Errorf("delete snapshot policy: %w", result.Error)
	}
	if result.RowsAffected == 0 {
		return errors.New("snapshot policy not found")
	}
	return nil
}

func (s *SnapshotService) CreateManualSnapshot(ctx context.Context, userID uuid.UUID, spaceID uuid.UUID, name, description string, includeAttachments bool) (*model.SpaceSnapshot, error) {
	if name == "" {
		name = fmt.Sprintf("manual-snapshot-%s", time.Now().Format("20060102-150405"))
	}

	tenantIDStr, ok := database.GetTenantID(ctx)
	if !ok || tenantIDStr == "" {
		return nil, errors.New("tenant context missing")
	}

	snapshot := &model.SpaceSnapshot{
		TenantScoped:       model.TenantScoped{TenantID: tenantIDStr},
		SpaceID:            spaceID.String(),
		Name:               name,
		Description:        description,
		SnapshotType:       "manual",
		Status:             "pending",
		StorageType:        "minio",
		CreatedBy:          userID.String(),
	}

	if err := s.db.WithContext(ctx).Create(snapshot).Error; err != nil {
		return nil, fmt.Errorf("create manual snapshot: %w", err)
	}

	policy := &model.SnapshotPolicy{
		BaseModel:          model.BaseModel{ID: ""},
		TenantScoped:       model.TenantScoped{TenantID: tenantIDStr},
		SpaceID:            spaceID.String(),
		IncludeAttachments: includeAttachments,
		IncludeDeleted:     false,
		RetentionDays:      s.config.Snapshot.DefaultRetentionDays,
	}

	return s.ExecuteSnapshotWithPolicy(ctx, snapshot, policy)
}

func (s *SnapshotService) ExecuteSnapshot(ctx context.Context, policy *model.SnapshotPolicy) (*model.SpaceSnapshot, error) {
	snapshot := &model.SpaceSnapshot{
		TenantScoped:       model.TenantScoped{TenantID: policy.TenantID},
		SpaceID:            policy.SpaceID,
		PolicyID:           policy.ID,
		Name:               fmt.Sprintf("%s-%s", policy.Name, time.Now().Format("20060102-150405")),
		SnapshotType:       "automatic",
		Status:             "pending",
		StorageType:        "minio",
		CreatedBy:          policy.CreatedBy,
	}

	if err := s.db.WithContext(ctx).Create(snapshot).Error; err != nil {
		return nil, fmt.Errorf("create snapshot record: %w", err)
	}

	return s.ExecuteSnapshotWithPolicy(ctx, snapshot, policy)
}

func (s *SnapshotService) ExecuteSnapshotWithPolicy(ctx context.Context, snapshot *model.SpaceSnapshot, policy *model.SnapshotPolicy) (*model.SpaceSnapshot, error) {
	now := time.Now()
	if err := s.db.WithContext(ctx).Model(snapshot).Update("status", "running").Error; err != nil {
		return nil, fmt.Errorf("update snapshot status to running: %w", err)
	}

	tempDir := filepath.Join(s.config.Snapshot.TempDir, snapshot.ID)
	if err := os.MkdirAll(tempDir, 0755); err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("create temp dir: %v", err))
		return nil, fmt.Errorf("create temp dir: %w", err)
	}
	defer os.RemoveAll(tempDir)

	var dirs []*model.Directory
	var docs []*model.Document
	var versions []*model.DocumentVersion
	var attachments []*model.Attachment

	query := s.db.WithContext(ctx).Where("space_id = ? AND tenant_id = ?", snapshot.SpaceID, snapshot.TenantID)
	if !policy.IncludeDeleted {
		query = query.Where("deleted_at IS NULL")
	}

	if err := query.Find(&dirs).Error; err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("query directories: %v", err))
		return nil, fmt.Errorf("query directories: %w", err)
	}

	query = s.db.WithContext(ctx).Where("space_id = ? AND tenant_id = ?", snapshot.SpaceID, snapshot.TenantID)
	if !policy.IncludeDeleted {
		query = query.Where("deleted_at IS NULL")
	}
	if err := query.Find(&docs).Error; err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("query documents: %v", err))
		return nil, fmt.Errorf("query documents: %w", err)
	}

	docIDs := make([]string, 0, len(docs))
	for _, d := range docs {
		docIDs = append(docIDs, d.ID)
	}

	if len(docIDs) > 0 {
		query = s.db.WithContext(ctx).Where("space_id = ? AND tenant_id = ? AND doc_id IN ?", snapshot.SpaceID, snapshot.TenantID, docIDs)
		if !policy.IncludeDeleted {
			query = query.Where("deleted_at IS NULL")
		}
		if err := query.Find(&versions).Error; err != nil {
			s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("query versions: %v", err))
			return nil, fmt.Errorf("query versions: %w", err)
		}

		query = s.db.WithContext(ctx).Where("space_id = ? AND tenant_id = ? AND doc_id IN ?", snapshot.SpaceID, snapshot.TenantID, docIDs)
		if !policy.IncludeDeleted {
			query = query.Where("deleted_at IS NULL")
		}
		if err := query.Find(&attachments).Error; err != nil {
			s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("query attachments: %v", err))
			return nil, fmt.Errorf("query attachments: %w", err)
		}
	}

	manifest := SnapshotManifest{
		Version:            "1.0",
		SnapshotID:         snapshot.ID,
		SpaceID:            snapshot.SpaceID,
		TenantID:           snapshot.TenantID,
		CreatedAt:          now,
		SnapshotType:       snapshot.SnapshotType,
		IncludeAttachments: policy.IncludeAttachments,
		IncludeDeleted:     policy.IncludeDeleted,
		Directories:        dirs,
		Documents:          docs,
		Versions:           versions,
		Attachments:        attachments,
		Stats: SnapshotStats{
			DirCount:        len(dirs),
			DocCount:        len(docs),
			VersionCount:    len(versions),
			AttachmentCount: len(attachments),
		},
	}

	manifestPath := filepath.Join(tempDir, "manifest.json")
	manifestData, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("marshal manifest: %v", err))
		return nil, fmt.Errorf("marshal manifest: %w", err)
	}
	if err := os.WriteFile(manifestPath, manifestData, 0644); err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("write manifest: %v", err))
		return nil, fmt.Errorf("write manifest: %w", err)
	}

	zipPath := filepath.Join(tempDir, snapshot.ID+".zip")
	if err := s.createZipFile(ctx, zipPath, tempDir, manifestPath, attachments, policy.IncludeAttachments); err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("create zip: %v", err))
		return nil, fmt.Errorf("create zip: %w", err)
	}

	zipInfo, err := os.Stat(zipPath)
	if err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("stat zip: %v", err))
		return nil, fmt.Errorf("stat zip: %w", err)
	}

	checksum, err := s.calculateSHA256(zipPath)
	if err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("calculate checksum: %v", err))
		return nil, fmt.Errorf("calculate checksum: %w", err)
	}

	objectKey := fmt.Sprintf("snapshots/%s/%s/%s.zip", snapshot.TenantID, snapshot.SpaceID, snapshot.ID)
	zipFile, err := os.Open(zipPath)
	if err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("open zip: %v", err))
		return nil, fmt.Errorf("open zip: %w", err)
	}
	defer zipFile.Close()

	_, err = s.minioClient.PutObject(ctx, s.config.Snapshot.ColdBucket, objectKey, zipFile, zipInfo.Size(), minio.PutObjectOptions{
		ContentType: "application/zip",
	})
	if err != nil {
		s.markSnapshotFailed(ctx, snapshot, fmt.Sprintf("upload to minio: %v", err))
		return nil, fmt.Errorf("upload to minio: %w", err)
	}

	var expireAt *time.Time
	if policy.RetentionDays > 0 {
		t := now.AddDate(0, 0, policy.RetentionDays)
		expireAt = &t
	}

	completedAt := time.Now()
	updates := map[string]interface{}{
		"status":           "completed",
		"storage_path":     objectKey,
		"archive_size":     zipInfo.Size(),
		"doc_count":        len(docs),
		"version_count":    len(versions),
		"attachment_count": len(attachments),
		"dir_count":        len(dirs),
		"checksum_sha256":  checksum,
		"completed_at":     completedAt,
		"error_msg":        "",
	}
	if expireAt != nil {
		updates["expire_at"] = expireAt
	}

	if err := s.db.WithContext(ctx).Model(snapshot).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("update snapshot completed: %w", err)
	}

	snapshot.Status = "completed"
	snapshot.StoragePath = objectKey
	snapshot.ArchiveSize = zipInfo.Size()
	snapshot.DocCount = len(docs)
	snapshot.VersionCount = len(versions)
	snapshot.AttachmentCount = len(attachments)
	snapshot.DirCount = len(dirs)
	snapshot.ChecksumSHA256 = checksum
	snapshot.CompletedAt = &completedAt
	snapshot.ExpireAt = expireAt

	return snapshot, nil
}

func (s *SnapshotService) createZipFile(ctx context.Context, zipPath, tempDir, manifestPath string, attachments []*model.Attachment, includeAttachments bool) error {
	zipFile, err := os.Create(zipPath)
	if err != nil {
		return fmt.Errorf("create zip file: %w", err)
	}
	defer zipFile.Close()

	zw := zip.NewWriter(zipFile)
	defer zw.Close()

	if err := s.addFileToZip(zw, manifestPath, "manifest.json"); err != nil {
		return fmt.Errorf("add manifest to zip: %w", err)
	}

	if includeAttachments {
		attachmentsDir := filepath.Join(tempDir, "attachments")
		if err := os.MkdirAll(attachmentsDir, 0755); err != nil {
			return fmt.Errorf("create attachments dir: %w", err)
		}

		for _, att := range attachments {
			if att.StoragePath == "" {
				continue
			}
			obj, err := s.minioClient.GetObject(ctx, s.config.MinIO.Bucket, att.StoragePath, minio.GetObjectOptions{})
			if err != nil {
				continue
			}

			attFileName := fmt.Sprintf("%s_%s", att.ID, att.FileName)
			attLocalPath := filepath.Join(attachmentsDir, attFileName)
			attFile, err := os.Create(attLocalPath)
			if err != nil {
				obj.Close()
				continue
			}
			_, err = io.Copy(attFile, obj)
			obj.Close()
			attFile.Close()
			if err != nil {
				continue
			}

			zipEntryName := fmt.Sprintf("attachments/%s", attFileName)
			if err := s.addFileToZip(zw, attLocalPath, zipEntryName); err != nil {
				continue
			}
		}
	}

	return nil
}

func (s *SnapshotService) addFileToZip(zw *zip.Writer, filePath, entryName string) error {
	file, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		return err
	}

	header, err := zip.FileInfoHeader(info)
	if err != nil {
		return err
	}
	header.Name = entryName
	header.Method = zip.Deflate

	writer, err := zw.CreateHeader(header)
	if err != nil {
		return err
	}

	_, err = io.Copy(writer, file)
	return err
}

func (s *SnapshotService) calculateSHA256(filePath string) (string, error) {
	f, err := os.Open(filePath)
	if err != nil {
		return "", err
	}
	defer f.Close()

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}

	return hex.EncodeToString(h.Sum(nil)), nil
}

func (s *SnapshotService) markSnapshotFailed(ctx context.Context, snapshot *model.SpaceSnapshot, errMsg string) {
	_ = s.db.WithContext(ctx).Model(snapshot).Updates(map[string]interface{}{
		"status":    "failed",
		"error_msg": errMsg,
	})
}

func (s *SnapshotService) ListSnapshots(ctx context.Context, spaceID uuid.UUID, page, pageSize int) ([]*model.SpaceSnapshot, int64, error) {
	var snapshots []*model.SpaceSnapshot
	var total int64

	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}

	db := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Model(&model.SpaceSnapshot{}).
		Where("space_id = ?", spaceID.String())

	if err := db.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count snapshots: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := db.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&snapshots).Error; err != nil {
		return nil, 0, fmt.Errorf("list snapshots: %w", err)
	}

	return snapshots, total, nil
}

func (s *SnapshotService) GetSnapshot(ctx context.Context, snapshotID uuid.UUID) (*model.SpaceSnapshot, error) {
	var snapshot model.SpaceSnapshot
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", snapshotID.String()).
		First(&snapshot).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("get snapshot: %w", err)
	}
	return &snapshot, nil
}

func (s *SnapshotService) GetSnapshotDownloadURL(ctx context.Context, snapshotID uuid.UUID, expireMinutes int) (string, error) {
	snapshot, err := s.GetSnapshot(ctx, snapshotID)
	if err != nil {
		return "", err
	}
	if snapshot == nil {
		return "", errors.New("snapshot not found")
	}
	if snapshot.Status != "completed" {
		return "", errors.New("snapshot is not completed")
	}
	if snapshot.StoragePath == "" {
		return "", errors.New("snapshot has no storage path")
	}

	if expireMinutes <= 0 {
		expireMinutes = 60
	}
	expiry := time.Duration(expireMinutes) * time.Minute

	reqParams := make(url.Values)
	presignedURL, err := s.minioClient.PresignedGetObject(ctx, s.config.Snapshot.ColdBucket, snapshot.StoragePath, expiry, reqParams)
	if err != nil {
		return "", fmt.Errorf("generate presigned url: %w", err)
	}

	return presignedURL.String(), nil
}

func (s *SnapshotService) DeleteSnapshot(ctx context.Context, snapshotID uuid.UUID) error {
	var snapshot model.SpaceSnapshot
	err := s.db.Scopes(database.TenantScope(ctx)).WithContext(ctx).
		Where("id = ?", snapshotID.String()).
		First(&snapshot).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return errors.New("snapshot not found")
		}
		return fmt.Errorf("get snapshot: %w", err)
	}

	return s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if snapshot.StoragePath != "" {
			if err := s.minioClient.RemoveObject(ctx, s.config.Snapshot.ColdBucket, snapshot.StoragePath, minio.RemoveObjectOptions{}); err != nil {
				return fmt.Errorf("remove minio object: %w", err)
			}
		}

		if err := tx.Scopes(database.TenantScope(ctx)).Delete(&snapshot).Error; err != nil {
			return fmt.Errorf("delete snapshot db: %w", err)
		}

		return nil
	})
}

func (s *SnapshotService) DownloadSnapshotZIP(ctx context.Context, snapshotID uuid.UUID, w io.Writer) error {
	snapshot, err := s.GetSnapshot(ctx, snapshotID)
	if err != nil {
		return err
	}
	if snapshot == nil {
		return errors.New("snapshot not found")
	}
	if snapshot.Status != "completed" {
		return errors.New("snapshot is not completed")
	}
	if snapshot.StoragePath == "" {
		return errors.New("snapshot has no storage path")
	}

	obj, err := s.minioClient.GetObject(ctx, s.config.Snapshot.ColdBucket, snapshot.StoragePath, minio.GetObjectOptions{})
	if err != nil {
		return fmt.Errorf("get object from minio: %w", err)
	}
	defer obj.Close()

	_, err = io.Copy(w, obj)
	if err != nil {
		return fmt.Errorf("stream object: %w", err)
	}

	return nil
}

func (s *SnapshotService) CalculateNextRun(policy *model.SnapshotPolicy) *time.Time {
	now := time.Now()
	var next time.Time

	switch policy.Frequency {
	case "daily":
		next = time.Date(now.Year(), now.Month(), now.Day(), policy.Hour, 0, 0, 0, now.Location())
		if !next.After(now) {
			next = next.AddDate(0, 0, 1)
		}
	case "weekly":
		daysUntil := (policy.DayOfWeek - int(now.Weekday()) + 7) % 7
		next = time.Date(now.Year(), now.Month(), now.Day()+daysUntil, policy.Hour, 0, 0, 0, now.Location())
		if daysUntil == 0 && !next.After(now) {
			next = next.AddDate(0, 0, 7)
		}
	case "monthly":
		day := policy.DayOfMonth
		if day < 1 {
			day = 1
		}
		year, month, _ := now.Date()
		next = time.Date(year, month, day, policy.Hour, 0, 0, 0, now.Location())
		lastDay := time.Date(year, month+1, 0, 0, 0, 0, 0, now.Location()).Day()
		if day > lastDay {
			day = lastDay
			next = time.Date(year, month, day, policy.Hour, 0, 0, 0, now.Location())
		}
		if !next.After(now) {
			year, month, _ = now.AddDate(0, 1, 0).Date()
			day = policy.DayOfMonth
			lastDay = time.Date(year, month+1, 0, 0, 0, 0, 0, now.Location()).Day()
			if day > lastDay {
				day = lastDay
			}
			next = time.Date(year, month, day, policy.Hour, 0, 0, 0, now.Location())
		}
	default:
		next = now.Add(24 * time.Hour)
	}

	return &next
}

func (s *SnapshotService) CleanupExpiredSnapshots(ctx context.Context) (int, error) {
	var expired []*model.SpaceSnapshot
	now := time.Now()

	err := s.db.WithContext(ctx).
		Where("expire_at IS NOT NULL AND expire_at <= ? AND status != 'deleted'", now).
		Find(&expired).Error
	if err != nil {
		return 0, fmt.Errorf("find expired snapshots: %w", err)
	}

	count := 0
	for _, snap := range expired {
		if err := s.DeleteSnapshot(ctx, uuid.MustParse(snap.ID)); err != nil {
			continue
		}
		count++
	}

	return count, nil
}
