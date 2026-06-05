package annotation

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"pointcloud-platform/internal/database"
	"pointcloud-platform/pkg/math3d"
	"time"

	"github.com/google/uuid"
)

type AnnotationType string
type MeasurementType string

const (
	AnnotationBBox3D  AnnotationType = "bbox3d"
	AnnotationPolygon AnnotationType = "polygon"
	AnnotationLine    AnnotationType = "line"
	AnnotationPoint   AnnotationType = "point"
	AnnotationSphere  AnnotationType = "sphere"
	AnnotationCylinder AnnotationType = "cylinder"

	MeasurementDistance MeasurementType = "distance"
	MeasurementAngle    MeasurementType = "angle"
	MeasurementArea     MeasurementType = "area"
	MeasurementVolume   MeasurementType = "volume"
)

type BBox3D struct {
	Center   math3d.Vec3 `json:"center"`
	Size     math3d.Vec3 `json:"size"`
	Rotation math3d.Vec3 `json:"rotation"`
}

type Polygon struct {
	Points []math3d.Vec3 `json:"points"`
	Closed bool          `json:"closed"`
}

type Line struct {
	Start math3d.Vec3 `json:"start"`
	End   math3d.Vec3 `json:"end"`
}

type Sphere struct {
	Center math3d.Vec3 `json:"center"`
	Radius float64     `json:"radius"`
}

type Cylinder struct {
	Center math3d.Vec3 `json:"center"`
	Radius float64     `json:"radius"`
	Height float64     `json:"height"`
	Axis   math3d.Vec3 `json:"axis"`
}

type Annotation struct {
	ID           string                 `json:"id"`
	DatasetID    string                 `json:"dataset_id"`
	VersionID    string                 `json:"version_id,omitempty"`
	Type         AnnotationType         `json:"type"`
	Label        string                 `json:"label"`
	Geometry     map[string]interface{} `json:"geometry"`
	Properties   map[string]interface{} `json:"properties,omitempty"`
	CreatorID    string                 `json:"creator_id"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type Measurement struct {
	ID         string          `json:"id"`
	DatasetID  string          `json:"dataset_id"`
	Type       MeasurementType `json:"type"`
	Points     []math3d.Vec3   `json:"points"`
	Value      float64         `json:"value"`
	Unit       string          `json:"unit"`
	Label      string          `json:"label,omitempty"`
	CreatorID  string          `json:"creator_id"`
	CreatedAt  time.Time       `json:"created_at"`
}

type AnnotationService struct{}

func NewAnnotationService() *AnnotationService {
	return &AnnotationService{}
}

func (s *AnnotationService) CreateAnnotation(ann *Annotation) (*Annotation, error) {
	if ann.ID == "" {
		ann.ID = uuid.New().String()
	}

	ann.CreatedAt = time.Now()
	ann.UpdatedAt = time.Now()

	geometryJSON, err := json.Marshal(ann.Geometry)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal geometry: %w", err)
	}

	var propertiesJSON []byte
	if ann.Properties != nil {
		propertiesJSON, err = json.Marshal(ann.Properties)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal properties: %w", err)
		}
	}

	query := `
		INSERT INTO annotations (id, dataset_id, version_id, type, label, geometry, properties, creator_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		RETURNING created_at, updated_at
	`

	var versionID sql.NullString
	if ann.VersionID != "" {
		versionID = sql.NullString{String: ann.VersionID, Valid: true}
	}

	var propsParam interface{}
	if propertiesJSON != nil {
		propsParam = propertiesJSON
	} else {
		propsParam = nil
	}

	err = database.DB.QueryRow(
		query,
		ann.ID,
		ann.DatasetID,
		versionID,
		string(ann.Type),
		ann.Label,
		geometryJSON,
		propsParam,
		ann.CreatorID,
	).Scan(&ann.CreatedAt, &ann.UpdatedAt)

	if err != nil {
		return nil, fmt.Errorf("failed to create annotation: %w", err)
	}

	return ann, nil
}

func (s *AnnotationService) GetAnnotation(id string) (*Annotation, error) {
	query := `
		SELECT id, dataset_id, version_id, type, label, geometry, properties, creator_id, created_at, updated_at
		FROM annotations WHERE id = $1
	`

	var ann Annotation
	var versionID sql.NullString
	var geometryJSON, propertiesJSON []byte

	err := database.DB.QueryRow(query, id).Scan(
		&ann.ID,
		&ann.DatasetID,
		&versionID,
		&ann.Type,
		&ann.Label,
		&geometryJSON,
		&propertiesJSON,
		&ann.CreatorID,
		&ann.CreatedAt,
		&ann.UpdatedAt,
	)

	if err != nil {
		return nil, fmt.Errorf("failed to get annotation: %w", err)
	}

	if versionID.Valid {
		ann.VersionID = versionID.String
	}

	if err := json.Unmarshal(geometryJSON, &ann.Geometry); err != nil {
		return nil, fmt.Errorf("failed to unmarshal geometry: %w", err)
	}

	if propertiesJSON != nil {
		if err := json.Unmarshal(propertiesJSON, &ann.Properties); err != nil {
			return nil, fmt.Errorf("failed to unmarshal properties: %w", err)
		}
	}

	return &ann, nil
}

func (s *AnnotationService) UpdateAnnotation(id string, ann *Annotation) (*Annotation, error) {
	ann.UpdatedAt = time.Now()

	geometryJSON, err := json.Marshal(ann.Geometry)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal geometry: %w", err)
	}

	var propertiesJSON []byte
	if ann.Properties != nil {
		propertiesJSON, err = json.Marshal(ann.Properties)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal properties: %w", err)
		}
	}

	query := `
		UPDATE annotations
		SET type = $1, label = $2, geometry = $3, properties = $4, updated_at = $5
		WHERE id = $6
		RETURNING dataset_id, version_id, creator_id, created_at, updated_at
	`

	var versionID sql.NullString
	var propsParam interface{}
	if propertiesJSON != nil {
		propsParam = propertiesJSON
	} else {
		propsParam = nil
	}

	err = database.DB.QueryRow(
		query,
		string(ann.Type),
		ann.Label,
		geometryJSON,
		propsParam,
		ann.UpdatedAt,
		id,
	).Scan(&ann.DatasetID, &versionID, &ann.CreatorID, &ann.CreatedAt, &ann.UpdatedAt)

	if err != nil {
		return nil, fmt.Errorf("failed to update annotation: %w", err)
	}

	if versionID.Valid {
		ann.VersionID = versionID.String
	}
	ann.ID = id

	return ann, nil
}

func (s *AnnotationService) DeleteAnnotation(id string) error {
	query := `DELETE FROM annotations WHERE id = $1`
	result, err := database.DB.Exec(query, id)
	if err != nil {
		return fmt.Errorf("failed to delete annotation: %w", err)
	}

	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("annotation not found")
	}

	return nil
}

func (s *AnnotationService) ListAnnotations(datasetID string, annotationType AnnotationType, limit, offset int) ([]*Annotation, int64, error) {
	baseQuery := `FROM annotations WHERE dataset_id = $1`
	args := []interface{}{datasetID}
	argIdx := 2

	if annotationType != "" {
		baseQuery += fmt.Sprintf(" AND type = $%d", argIdx)
		args = append(args, string(annotationType))
		argIdx++
	}

	countQuery := `SELECT COUNT(*) ` + baseQuery
	var total int64
	if err := database.DB.QueryRow(countQuery, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count annotations: %w", err)
	}

	dataQuery := `
		SELECT id, dataset_id, version_id, type, label, geometry, properties, creator_id, created_at, updated_at
	` + baseQuery + ` ORDER BY created_at DESC `

	if limit > 0 {
		dataQuery += fmt.Sprintf(" LIMIT $%d", argIdx)
		args = append(args, limit)
		argIdx++
	}
	if offset > 0 {
		dataQuery += fmt.Sprintf(" OFFSET $%d", argIdx)
		args = append(args, offset)
	}

	rows, err := database.DB.Query(dataQuery, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to list annotations: %w", err)
	}
	defer rows.Close()

	var annotations []*Annotation
	for rows.Next() {
		var ann Annotation
		var versionID sql.NullString
		var geometryJSON, propertiesJSON []byte

		err := rows.Scan(
			&ann.ID,
			&ann.DatasetID,
			&versionID,
			&ann.Type,
			&ann.Label,
			&geometryJSON,
			&propertiesJSON,
			&ann.CreatorID,
			&ann.CreatedAt,
			&ann.UpdatedAt,
		)
		if err != nil {
			return nil, 0, fmt.Errorf("failed to scan annotation: %w", err)
		}

		if versionID.Valid {
			ann.VersionID = versionID.String
		}

		if err := json.Unmarshal(geometryJSON, &ann.Geometry); err != nil {
			return nil, 0, fmt.Errorf("failed to unmarshal geometry: %w", err)
		}

		if propertiesJSON != nil {
			if err := json.Unmarshal(propertiesJSON, &ann.Properties); err != nil {
				return nil, 0, fmt.Errorf("failed to unmarshal properties: %w", err)
			}
		}

		annotations = append(annotations, &ann)
	}

	return annotations, total, nil
}

func (s *AnnotationService) CreateMeasurement(m *Measurement) (*Measurement, error) {
	if m.ID == "" {
		m.ID = uuid.New().String()
	}

	m.CreatedAt = time.Now()
	s.ComputeMeasurementValue(m)

	pointsJSON, err := json.Marshal(m.Points)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal points: %w", err)
	}

	query := `
		INSERT INTO measurements (id, dataset_id, type, points, value, unit, label, creator_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		RETURNING created_at
	`

	err = database.DB.QueryRow(
		query,
		m.ID,
		m.DatasetID,
		string(m.Type),
		pointsJSON,
		m.Value,
		m.Unit,
		m.Label,
		m.CreatorID,
	).Scan(&m.CreatedAt)

	if err != nil {
		return nil, fmt.Errorf("failed to create measurement: %w", err)
	}

	return m, nil
}

func (s *AnnotationService) GetMeasurement(id string) (*Measurement, error) {
	query := `
		SELECT id, dataset_id, type, points, value, unit, label, creator_id, created_at
		FROM measurements WHERE id = $1
	`

	var m Measurement
	var pointsJSON []byte

	err := database.DB.QueryRow(query, id).Scan(
		&m.ID,
		&m.DatasetID,
		&m.Type,
		&pointsJSON,
		&m.Value,
		&m.Unit,
		&m.Label,
		&m.CreatorID,
		&m.CreatedAt,
	)

	if err != nil {
		return nil, fmt.Errorf("failed to get measurement: %w", err)
	}

	if err := json.Unmarshal(pointsJSON, &m.Points); err != nil {
		return nil, fmt.Errorf("failed to unmarshal points: %w", err)
	}

	return &m, nil
}

func (s *AnnotationService) DeleteMeasurement(id string) error {
	query := `DELETE FROM measurements WHERE id = $1`
	result, err := database.DB.Exec(query, id)
	if err != nil {
		return fmt.Errorf("failed to delete measurement: %w", err)
	}

	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("measurement not found")
	}

	return nil
}

func (s *AnnotationService) ListMeasurements(datasetID string, measurementType MeasurementType, limit, offset int) ([]*Measurement, int64, error) {
	baseQuery := `FROM measurements WHERE dataset_id = $1`
	args := []interface{}{datasetID}
	argIdx := 2

	if measurementType != "" {
		baseQuery += fmt.Sprintf(" AND type = $%d", argIdx)
		args = append(args, string(measurementType))
		argIdx++
	}

	countQuery := `SELECT COUNT(*) ` + baseQuery
	var total int64
	if err := database.DB.QueryRow(countQuery, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count measurements: %w", err)
	}

	dataQuery := `
		SELECT id, dataset_id, type, points, value, unit, label, creator_id, created_at
	` + baseQuery + ` ORDER BY created_at DESC `

	if limit > 0 {
		dataQuery += fmt.Sprintf(" LIMIT $%d", argIdx)
		args = append(args, limit)
		argIdx++
	}
	if offset > 0 {
		dataQuery += fmt.Sprintf(" OFFSET $%d", argIdx)
		args = append(args, offset)
	}

	rows, err := database.DB.Query(dataQuery, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to list measurements: %w", err)
	}
	defer rows.Close()

	var measurements []*Measurement
	for rows.Next() {
		var m Measurement
		var pointsJSON []byte

		err := rows.Scan(
			&m.ID,
			&m.DatasetID,
			&m.Type,
			&pointsJSON,
			&m.Value,
			&m.Unit,
			&m.Label,
			&m.CreatorID,
			&m.CreatedAt,
		)
		if err != nil {
			return nil, 0, fmt.Errorf("failed to scan measurement: %w", err)
		}

		if err := json.Unmarshal(pointsJSON, &m.Points); err != nil {
			return nil, 0, fmt.Errorf("failed to unmarshal points: %w", err)
		}

		measurements = append(measurements, &m)
	}

	return measurements, total, nil
}

func (s *AnnotationService) ComputeMeasurementValue(m *Measurement) {
	switch m.Type {
	case MeasurementDistance:
		if len(m.Points) >= 2 {
			m.Value = m.Points[0].Distance(m.Points[1])
			m.Unit = "m"
		}
	case MeasurementAngle:
		if len(m.Points) >= 3 {
			v1 := m.Points[0].Sub(m.Points[1])
			v2 := m.Points[2].Sub(m.Points[1])
			angle := v1.Angle(v2)
			m.Value = angle * 180.0 / math.Pi
			m.Unit = "°"
		}
	case MeasurementArea:
		if len(m.Points) >= 3 {
			v1 := m.Points[1].Sub(m.Points[0])
			v2 := m.Points[2].Sub(m.Points[0])
			normal := v1.Cross(v2)
			m.Value = normal.Length() / 2.0
			m.Unit = "m²"
		}
	case MeasurementVolume:
		if len(m.Points) >= 4 {
			v1 := m.Points[1].Sub(m.Points[0])
			v2 := m.Points[2].Sub(m.Points[0])
			v3 := m.Points[3].Sub(m.Points[0])
			scalar := v1.Dot(v2.Cross(v3))
			m.Value = math.Abs(scalar) / 6.0
			m.Unit = "m³"
		}
	}
}

func (s *AnnotationService) GetAnnotationsInBounds(datasetID string, bounds math3d.AABB) ([]*Annotation, error) {
	annotations, _, err := s.ListAnnotations(datasetID, "", 0, 0)
	if err != nil {
		return nil, err
	}

	var filtered []*Annotation
	for _, ann := range annotations {
		if s.AnnotationIntersectsBounds(ann, bounds) {
			filtered = append(filtered, ann)
		}
	}

	return filtered, nil
}

func (s *AnnotationService) AnnotationIntersectsBounds(ann *Annotation, bounds math3d.AABB) bool {
	switch ann.Type {
	case AnnotationBBox3D:
		var bbox BBox3D
		geoJSON, _ := json.Marshal(ann.Geometry)
		json.Unmarshal(geoJSON, &bbox)
		bboxAABB := math3d.NewAABB(
			bbox.Center.Sub(bbox.Size.Mul(0.5)),
			bbox.Center.Add(bbox.Size.Mul(0.5)),
		)
		return bounds.Intersects(bboxAABB)
	case AnnotationPoint:
		var pt math3d.Vec3
		geoJSON, _ := json.Marshal(ann.Geometry)
		json.Unmarshal(geoJSON, &pt)
		return bounds.Contains(pt)
	case AnnotationLine:
		var line Line
		geoJSON, _ := json.Marshal(ann.Geometry)
		json.Unmarshal(geoJSON, &line)
		return bounds.Contains(line.Start) || bounds.Contains(line.End)
	case AnnotationPolygon:
		var poly Polygon
		geoJSON, _ := json.Marshal(ann.Geometry)
		json.Unmarshal(geoJSON, &poly)
		for _, p := range poly.Points {
			if bounds.Contains(p) {
				return true
			}
		}
	}
	return false
}
