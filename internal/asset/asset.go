package asset

import (
	"database/sql"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/database"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/pkg/math3d"
	"time"

	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

type Project struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Description string    `json:"description,omitempty"`
	OwnerID     string    `json:"owner_id"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type Dataset struct {
	ID            string     `json:"id"`
	ProjectID     string     `json:"project_id"`
	Name          string     `json:"name"`
	Description   string     `json:"description,omitempty"`
	PointCount    int64      `json:"point_count"`
	BoundsMin     math3d.Vec3 `json:"bounds_min"`
	BoundsMax     math3d.Vec3 `json:"bounds_max"`
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
}

type DatasetVersion struct {
	ID            string      `json:"id"`
	DatasetID     string      `json:"dataset_id"`
	Version       int         `json:"version"`
	FilePath      string      `json:"file_path"`
	FileFormat    string      `json:"file_format"`
	FileSize      int64       `json:"file_size"`
	PointCount    int64       `json:"point_count"`
	ScaleFactor   math3d.Vec3 `json:"scale_factor"`
	Offset        math3d.Vec3 `json:"offset"`
	CoordSystem   string      `json:"coord_system"`
	CreatedAt     time.Time   `json:"created_at"`
}

type User struct {
	ID           string    `json:"id"`
	Username     string    `json:"username"`
	Email        string    `json:"email"`
	Role         string    `json:"role"`
	CreatedAt    time.Time `json:"created_at"`
}

type Permission struct {
	ID         string    `json:"id"`
	ProjectID  string    `json:"project_id"`
	UserID     string    `json:"user_id"`
	Permission string    `json:"permission"`
	CreatedAt  time.Time `json:"created_at"`
}

type AssetService struct {
	cfg        *config.StorageConfig
	parseSvc   *parser.ParseService
}

func NewAssetService(cfg *config.StorageConfig, parseSvc *parser.ParseService) *AssetService {
	return &AssetService{
		cfg:      cfg,
		parseSvc: parseSvc,
	}
}

func (s *AssetService) CreateProject(name, description, ownerID string) (*Project, error) {
	id := uuid.New().String()
	now := time.Now()

	query := `
		INSERT INTO projects (id, name, description, owner_id)
		VALUES ($1, $2, $3, $4)
		RETURNING created_at, updated_at
	`

	var project Project
	err := database.DB.QueryRow(query, id, name, description, ownerID).
		Scan(&project.CreatedAt, &project.UpdatedAt)
	if err != nil {
		return nil, fmt.Errorf("failed to create project: %w", err)
	}

	project.ID = id
	project.Name = name
	project.Description = description
	project.OwnerID = ownerID

	s.GrantPermission(project.ID, ownerID, "owner")

	return &project, nil
}

func (s *AssetService) GetProject(id string) (*Project, error) {
	query := `
		SELECT id, name, description, owner_id, created_at, updated_at
		FROM projects WHERE id = $1
	`

	var project Project
	err := database.DB.QueryRow(query, id).Scan(
		&project.ID,
		&project.Name,
		&project.Description,
		&project.OwnerID,
		&project.CreatedAt,
		&project.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to get project: %w", err)
	}

	return &project, nil
}

func (s *AssetService) ListProjects(userID string, limit, offset int) ([]*Project, int64, error) {
	countQuery := `
		SELECT COUNT(DISTINCT p.id) FROM projects p
		LEFT JOIN project_permissions pp ON p.id = pp.project_id
		WHERE p.owner_id = $1 OR pp.user_id = $1
	`
	var total int64
	if err := database.DB.QueryRow(countQuery, userID).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count projects: %w", err)
	}

	dataQuery := `
		SELECT DISTINCT p.id, p.name, p.description, p.owner_id, p.created_at, p.updated_at
		FROM projects p
		LEFT JOIN project_permissions pp ON p.id = pp.project_id
		WHERE p.owner_id = $1 OR pp.user_id = $1
		ORDER BY p.updated_at DESC
		LIMIT $2 OFFSET $3
	`

	rows, err := database.DB.Query(dataQuery, userID, limit, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to list projects: %w", err)
	}
	defer rows.Close()

	var projects []*Project
	for rows.Next() {
		var project Project
		err := rows.Scan(
			&project.ID,
			&project.Name,
			&project.Description,
			&project.OwnerID,
			&project.CreatedAt,
			&project.UpdatedAt,
		)
		if err != nil {
			return nil, 0, fmt.Errorf("failed to scan project: %w", err)
		}
		projects = append(projects, &project)
	}

	return projects, total, nil
}

func (s *AssetService) DeleteProject(id string) error {
	query := `DELETE FROM projects WHERE id = $1`
	result, err := database.DB.Exec(query, id)
	if err != nil {
		return fmt.Errorf("failed to delete project: %w", err)
	}

	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("project not found")
	}

	return nil
}

func (s *AssetService) CreateDataset(projectID, name, description string) (*Dataset, error) {
	id := uuid.New().String()
	now := time.Now()

	query := `
		INSERT INTO datasets (id, project_id, name, description)
		VALUES ($1, $2, $3, $4)
		RETURNING created_at, updated_at
	`

	var dataset Dataset
	err := database.DB.QueryRow(query, id, projectID, name, description).
		Scan(&dataset.CreatedAt, &dataset.UpdatedAt)
	if err != nil {
		return nil, fmt.Errorf("failed to create dataset: %w", err)
	}

	dataset.ID = id
	dataset.ProjectID = projectID
	dataset.Name = name
	dataset.Description = description

	return &dataset, nil
}

func (s *AssetService) GetDataset(id string) (*Dataset, error) {
	query := `
		SELECT id, project_id, name, description, point_count,
		       bounds_min_x, bounds_min_y, bounds_min_z,
		       bounds_max_x, bounds_max_y, bounds_max_z,
		       created_at, updated_at
		FROM datasets WHERE id = $1
	`

	var dataset Dataset
	var minX, minY, minZ, maxX, maxY, maxZ sql.NullFloat64

	err := database.DB.QueryRow(query, id).Scan(
		&dataset.ID,
		&dataset.ProjectID,
		&dataset.Name,
		&dataset.Description,
		&dataset.PointCount,
		&minX, &minY, &minZ,
		&maxX, &maxY, &maxZ,
		&dataset.CreatedAt,
		&dataset.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to get dataset: %w", err)
	}

	if minX.Valid {
		dataset.BoundsMin = math3d.Vec3{X: minX.Float64, Y: minY.Float64, Z: minZ.Float64}
		dataset.BoundsMax = math3d.Vec3{X: maxX.Float64, Y: maxY.Float64, Z: maxZ.Float64}
	}

	return &dataset, nil
}

func (s *AssetService) ListDatasets(projectID string, limit, offset int) ([]*Dataset, int64, error) {
	countQuery := `SELECT COUNT(*) FROM datasets WHERE project_id = $1`
	var total int64
	if err := database.DB.QueryRow(countQuery, projectID).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count datasets: %w", err)
	}

	dataQuery := `
		SELECT id, project_id, name, description, point_count,
		       bounds_min_x, bounds_min_y, bounds_min_z,
		       bounds_max_x, bounds_max_y, bounds_max_z,
		       created_at, updated_at
		FROM datasets WHERE project_id = $1
		ORDER BY updated_at DESC
		LIMIT $2 OFFSET $3
	`

	rows, err := database.DB.Query(dataQuery, projectID, limit, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to list datasets: %w", err)
	}
	defer rows.Close()

	var datasets []*Dataset
	for rows.Next() {
		var dataset Dataset
		var minX, minY, minZ, maxX, maxY, maxZ sql.NullFloat64

		err := rows.Scan(
			&dataset.ID,
			&dataset.ProjectID,
			&dataset.Name,
			&dataset.Description,
			&dataset.PointCount,
			&minX, &minY, &minZ,
			&maxX, &maxY, &maxZ,
			&dataset.CreatedAt,
			&dataset.UpdatedAt,
		)
		if err != nil {
			return nil, 0, fmt.Errorf("failed to scan dataset: %w", err)
		}

		if minX.Valid {
			dataset.BoundsMin = math3d.Vec3{X: minX.Float64, Y: minY.Float64, Z: minZ.Float64}
			dataset.BoundsMax = math3d.Vec3{X: maxX.Float64, Y: maxY.Float64, Z: maxZ.Float64}
		}

		datasets = append(datasets, &dataset)
	}

	return datasets, total, nil
}

func (s *AssetService) DeleteDataset(id string) error {
	query := `DELETE FROM datasets WHERE id = $1`
	result, err := database.DB.Exec(query, id)
	if err != nil {
		return fmt.Errorf("failed to delete dataset: %w", err)
	}

	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("dataset not found")
	}

	return nil
}

func (s *AssetService) CreateVersion(datasetID string, file io.Reader, filename string, fileSize int64) (*DatasetVersion, error) {
	format := parser.GetFormatFromFilename(filename)
	if format == "" {
		return nil, fmt.Errorf("unsupported file format: %s", filename)
	}

	ext := filepath.Ext(filename)
	newFilename := fmt.Sprintf("%s_%d%s", datasetID, time.Now().Unix(), ext)
	filePath := filepath.Join(s.cfg.UploadDir, newFilename)

	f, err := os.Create(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to create file: %w", err)
	}
	defer f.Close()

	if _, err := io.Copy(f, file); err != nil {
		return nil, fmt.Errorf("failed to save file: %w", err)
	}

	header, err := s.parseSvc.ParseFileHeader(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to parse file header: %w", err)
	}

	var version int
	err = database.DB.QueryRow(
		`SELECT COALESCE(MAX(version), 0) + 1 FROM dataset_versions WHERE dataset_id = $1`,
		datasetID,
	).Scan(&version)
	if err != nil {
		version = 1
	}

	id := uuid.New().String()
	now := time.Now()

	query := `
		INSERT INTO dataset_versions (
			id, dataset_id, version, file_path, file_format, file_size,
			point_count, scale_factor_x, scale_factor_y, scale_factor_z,
			offset_x, offset_y, offset_z, coord_system
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
		RETURNING created_at
	`

	var dv DatasetVersion
	err = database.DB.QueryRow(
		query,
		id, datasetID, version, filePath, format, fileSize,
		header.PointCount,
		header.ScaleFactor.X, header.ScaleFactor.Y, header.ScaleFactor.Z,
		header.Offset.X, header.Offset.Y, header.Offset.Z,
		header.CoordSystem,
	).Scan(&dv.CreatedAt)
	if err != nil {
		return nil, fmt.Errorf("failed to create version: %w", err)
	}

	dv.ID = id
	dv.DatasetID = datasetID
	dv.Version = version
	dv.FilePath = filePath
	dv.FileFormat = format
	dv.FileSize = fileSize
	dv.PointCount = int64(header.PointCount)
	dv.ScaleFactor = header.ScaleFactor
	dv.Offset = header.Offset
	dv.CoordSystem = header.CoordSystem

	_, err = database.DB.Exec(`
		UPDATE datasets SET
			point_count = $1,
			bounds_min_x = $2, bounds_min_y = $3, bounds_min_z = $4,
			bounds_max_x = $5, bounds_max_y = $6, bounds_max_z = $7,
			updated_at = $8
		WHERE id = $9
	`,
		dv.PointCount,
		header.MinBounds.X, header.MinBounds.Y, header.MinBounds.Z,
		header.MaxBounds.X, header.MaxBounds.Y, header.MaxBounds.Z,
		now, datasetID,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to update dataset: %w", err)
	}

	return &dv, nil
}

func (s *AssetService) GetVersions(datasetID string) ([]*DatasetVersion, error) {
	query := `
		SELECT id, dataset_id, version, file_path, file_format, file_size,
		       point_count, scale_factor_x, scale_factor_y, scale_factor_z,
		       offset_x, offset_y, offset_z, coord_system, created_at
		FROM dataset_versions WHERE dataset_id = $1
		ORDER BY version DESC
	`

	rows, err := database.DB.Query(query, datasetID)
	if err != nil {
		return nil, fmt.Errorf("failed to list versions: %w", err)
	}
	defer rows.Close()

	var versions []*DatasetVersion
	for rows.Next() {
		var dv DatasetVersion
		err := rows.Scan(
			&dv.ID,
			&dv.DatasetID,
			&dv.Version,
			&dv.FilePath,
			&dv.FileFormat,
			&dv.FileSize,
			&dv.PointCount,
			&dv.ScaleFactor.X, &dv.ScaleFactor.Y, &dv.ScaleFactor.Z,
			&dv.Offset.X, &dv.Offset.Y, &dv.Offset.Z,
			&dv.CoordSystem,
			&dv.CreatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan version: %w", err)
		}
		versions = append(versions, &dv)
	}

	return versions, nil
}

func (s *AssetService) GetVersion(id string) (*DatasetVersion, error) {
	query := `
		SELECT id, dataset_id, version, file_path, file_format, file_size,
		       point_count, scale_factor_x, scale_factor_y, scale_factor_z,
		       offset_x, offset_y, offset_z, coord_system, created_at
		FROM dataset_versions WHERE id = $1
	`

	var dv DatasetVersion
	err := database.DB.QueryRow(query, id).Scan(
		&dv.ID,
		&dv.DatasetID,
		&dv.Version,
		&dv.FilePath,
		&dv.FileFormat,
		&dv.FileSize,
		&dv.PointCount,
		&dv.ScaleFactor.X, &dv.ScaleFactor.Y, &dv.ScaleFactor.Z,
		&dv.Offset.X, &dv.Offset.Y, &dv.Offset.Z,
		&dv.CoordSystem,
		&dv.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to get version: %w", err)
	}

	return &dv, nil
}

func (s *AssetService) GrantPermission(projectID, userID, permission string) error {
	id := uuid.New().String()
	query := `
		INSERT INTO project_permissions (id, project_id, user_id, permission)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (project_id, user_id, permission) DO NOTHING
	`

	_, err := database.DB.Exec(query, id, projectID, userID, permission)
	if err != nil {
		return fmt.Errorf("failed to grant permission: %w", err)
	}
	return nil
}

func (s *AssetService) RevokePermission(projectID, userID, permission string) error {
	query := `
		DELETE FROM project_permissions
		WHERE project_id = $1 AND user_id = $2 AND permission = $3
	`
	result, err := database.DB.Exec(query, projectID, userID, permission)
	if err != nil {
		return fmt.Errorf("failed to revoke permission: %w", err)
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("permission not found")
	}
	return nil
}

func (s *AssetService) CheckPermission(projectID, userID, permission string) (bool, error) {
	var count int
	query := `
		SELECT COUNT(*) FROM project_permissions
		WHERE project_id = $1 AND user_id = $2 AND permission = $3
	`
	err := database.DB.QueryRow(query, projectID, userID, permission).Scan(&count)
	if err != nil {
		return false, err
	}

	if count == 0 {
		query = `SELECT COUNT(*) FROM projects WHERE id = $1 AND owner_id = $2`
		err = database.DB.QueryRow(query, projectID, userID).Scan(&count)
		if err != nil {
			return false, err
		}
	}

	return count > 0, nil
}

func (s *AssetService) CreateUser(username, email, password string) (*User, error) {
	id := uuid.New().String()
	now := time.Now()

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("failed to hash password: %w", err)
	}

	query := `
		INSERT INTO users (id, username, email, password_hash, role)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING created_at
	`

	var user User
	err = database.DB.QueryRow(query, id, username, email, string(hashedPassword), "user").
		Scan(&user.CreatedAt)
	if err != nil {
		return nil, fmt.Errorf("failed to create user: %w", err)
	}

	user.ID = id
	user.Username = username
	user.Email = email
	user.Role = "user"

	return &user, nil
}

func (s *AssetService) GetUser(id string) (*User, error) {
	query := `
		SELECT id, username, email, role, created_at
		FROM users WHERE id = $1
	`

	var user User
	err := database.DB.QueryRow(query, id).Scan(
		&user.ID,
		&user.Username,
		&user.Email,
		&user.Role,
		&user.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to get user: %w", err)
	}

	return &user, nil
}

func (s *AssetService) Authenticate(username, password string) (*User, error) {
	var user User
	var passwordHash string

	query := `
		SELECT id, username, email, role, password_hash, created_at
		FROM users WHERE username = $1 OR email = $1
	`

	err := database.DB.QueryRow(query, username).Scan(
		&user.ID,
		&user.Username,
		&user.Email,
		&user.Role,
		&passwordHash,
		&user.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("invalid credentials")
	}

	if err := bcrypt.CompareHashAndPassword([]byte(passwordHash), []byte(password)); err != nil {
		return nil, fmt.Errorf("invalid credentials")
	}

	return &user, nil
}

func (s *AssetService) GetDatasetStats(datasetID string) (map[string]interface{}, error) {
	dataset, err := s.GetDataset(datasetID)
	if err != nil {
		return nil, err
	}

	versions, err := s.GetVersions(datasetID)
	if err != nil {
		return nil, err
	}

	versionCount := len(versions)
	latestVersion := (*DatasetVersion)(nil)
	if versionCount > 0 {
		latestVersion = versions[0]
	}

	stats := map[string]interface{}{
		"id":              dataset.ID,
		"name":            dataset.Name,
		"point_count":     dataset.PointCount,
		"bounds_min":      dataset.BoundsMin,
		"bounds_max":      dataset.BoundsMax,
		"version_count":   versionCount,
		"latest_version":  latestVersion,
		"coord_system":    "",
		"file_size_total": int64(0),
	}

	if latestVersion != nil {
		stats["coord_system"] = latestVersion.CoordSystem
		for _, v := range versions {
			stats["file_size_total"] = stats["file_size_total"].(int64) + v.FileSize
		}
	}

	var annotationCount int
	database.DB.QueryRow(`SELECT COUNT(*) FROM annotations WHERE dataset_id = $1`, datasetID).Scan(&annotationCount)
	stats["annotation_count"] = annotationCount

	var measurementCount int
	database.DB.QueryRow(`SELECT COUNT(*) FROM measurements WHERE dataset_id = $1`, datasetID).Scan(&measurementCount)
	stats["measurement_count"] = measurementCount

	return stats, nil
}
