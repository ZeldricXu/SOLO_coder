package annotation

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"pointcloud-platform/internal/database"
	"pointcloud-platform/pkg/math3d"
	"strings"
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
	Tags         map[string]string      `json:"tags,omitempty"`
	LabelGroups  []*LabelGroup          `json:"label_groups,omitempty"`
	CreatorID    string                 `json:"creator_id"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type LabelGroup struct {
	GroupName string                 `json:"group_name"`
	GroupType string                 `json:"group_type,omitempty"`
	Labels    map[string]string      `json:"labels"`
	Children  []*LabelGroup          `json:"children,omitempty"`
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

	var tagsJSON []byte
	if ann.Tags != nil {
		tagsJSON, err = json.Marshal(ann.Tags)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal tags: %w", err)
		}
	}

	var labelGroupsJSON []byte
	if ann.LabelGroups != nil {
		labelGroupsJSON, err = json.Marshal(ann.LabelGroups)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal label groups: %w", err)
		}
	}

	query := `
		INSERT INTO annotations (id, dataset_id, version_id, type, label, geometry, properties, tags, label_groups, creator_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
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

	var tagsParam interface{}
	if tagsJSON != nil {
		tagsParam = tagsJSON
	} else {
		tagsParam = nil
	}

	var labelGroupsParam interface{}
	if labelGroupsJSON != nil {
		labelGroupsParam = labelGroupsJSON
	} else {
		labelGroupsParam = nil
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
		tagsParam,
		labelGroupsParam,
		ann.CreatorID,
	).Scan(&ann.CreatedAt, &ann.UpdatedAt)

	if err != nil {
		return nil, fmt.Errorf("failed to create annotation: %w", err)
	}

	return ann, nil
}

func (s *AnnotationService) GetAnnotation(id string) (*Annotation, error) {
	query := `
		SELECT id, dataset_id, version_id, type, label, geometry, properties, tags, label_groups, creator_id, created_at, updated_at
		FROM annotations WHERE id = $1
	`

	var ann Annotation
	var versionID sql.NullString
	var geometryJSON, propertiesJSON, tagsJSON, labelGroupsJSON []byte

	err := database.DB.QueryRow(query, id).Scan(
		&ann.ID,
		&ann.DatasetID,
		&versionID,
		&ann.Type,
		&ann.Label,
		&geometryJSON,
		&propertiesJSON,
		&tagsJSON,
		&labelGroupsJSON,
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

	if tagsJSON != nil {
		if err := json.Unmarshal(tagsJSON, &ann.Tags); err != nil {
			return nil, fmt.Errorf("failed to unmarshal tags: %w", err)
		}
	}

	if labelGroupsJSON != nil {
		if err := json.Unmarshal(labelGroupsJSON, &ann.LabelGroups); err != nil {
			return nil, fmt.Errorf("failed to unmarshal label groups: %w", err)
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

	var tagsJSON []byte
	if ann.Tags != nil {
		tagsJSON, err = json.Marshal(ann.Tags)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal tags: %w", err)
		}
	}

	var labelGroupsJSON []byte
	if ann.LabelGroups != nil {
		labelGroupsJSON, err = json.Marshal(ann.LabelGroups)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal label groups: %w", err)
		}
	}

	query := `
		UPDATE annotations
		SET type = $1, label = $2, geometry = $3, properties = $4, tags = $5, label_groups = $6, updated_at = $7
		WHERE id = $8
		RETURNING dataset_id, version_id, creator_id, created_at, updated_at
	`

	var versionID sql.NullString
	var propsParam interface{}
	if propertiesJSON != nil {
		propsParam = propertiesJSON
	} else {
		propsParam = nil
	}

	var tagsParam interface{}
	if tagsJSON != nil {
		tagsParam = tagsJSON
	} else {
		tagsParam = nil
	}

	var labelGroupsParam interface{}
	if labelGroupsJSON != nil {
		labelGroupsParam = labelGroupsJSON
	} else {
		labelGroupsParam = nil
	}

	err = database.DB.QueryRow(
		query,
		string(ann.Type),
		ann.Label,
		geometryJSON,
		propsParam,
		tagsParam,
		labelGroupsParam,
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
		SELECT id, dataset_id, version_id, type, label, geometry, properties, tags, label_groups, creator_id, created_at, updated_at
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
		var geometryJSON, propertiesJSON, tagsJSON, labelGroupsJSON []byte

		err := rows.Scan(
			&ann.ID,
			&ann.DatasetID,
			&versionID,
			&ann.Type,
			&ann.Label,
			&geometryJSON,
			&propertiesJSON,
			&tagsJSON,
			&labelGroupsJSON,
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

		if tagsJSON != nil {
			if err := json.Unmarshal(tagsJSON, &ann.Tags); err != nil {
				return nil, 0, fmt.Errorf("failed to unmarshal tags: %w", err)
			}
		}

		if labelGroupsJSON != nil {
			if err := json.Unmarshal(labelGroupsJSON, &ann.LabelGroups); err != nil {
				return nil, 0, fmt.Errorf("failed to unmarshal label groups: %w", err)
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

type TagQueryCondition struct {
	Key      string      `json:"key"`
	Value    interface{} `json:"value,omitempty"`
	Operator string      `json:"operator"`
}

type LabelGroupQuery struct {
	GroupName string              `json:"group_name"`
	Labels    []TagQueryCondition `json:"labels"`
}

func (s *AnnotationService) ListAnnotationsByTag(datasetID string, tagKey string, tagValue string, limit, offset int) ([]*Annotation, int64, error) {
	baseQuery := `FROM annotations WHERE dataset_id = $1 AND tags @> jsonb_build_object($2, $3)`
	args := []interface{}{datasetID, tagKey, tagValue}

	countQuery := `SELECT COUNT(*) ` + baseQuery
	var total int64
	if err := database.DB.QueryRow(countQuery, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count annotations by tag: %w", err)
	}

	dataQuery := `
		SELECT id, dataset_id, version_id, type, label, geometry, properties, tags, label_groups, creator_id, created_at, updated_at
	` + baseQuery + ` ORDER BY created_at DESC `

	argIdx := 4
	if limit > 0 {
		dataQuery += fmt.Sprintf(" LIMIT $%d", argIdx)
		args = append(args, limit)
		argIdx++
	}
	if offset > 0 {
		dataQuery += fmt.Sprintf(" OFFSET $%d", argIdx)
		args = append(args, offset)
	}

	return s.queryAnnotations(dataQuery, args...)
}

func (s *AnnotationService) QueryAnnotationsByTags(datasetID string, conditions []TagQueryCondition, operator string, limit, offset int) ([]*Annotation, int64, error) {
	if operator == "" {
		operator = "AND"
	}

	baseQuery := `FROM annotations WHERE dataset_id = $1`
	args := []interface{}{datasetID}
	argIdx := 2

	var whereClauses []string
	for _, cond := range conditions {
		switch cond.Operator {
		case "exists":
			whereClauses = append(whereClauses, fmt.Sprintf("tags ? $%d", argIdx))
			args = append(args, cond.Key)
		case "not_exists":
			whereClauses = append(whereClauses, fmt.Sprintf("NOT (tags ? $%d)", argIdx))
			args = append(args, cond.Key)
		case "equals":
			whereClauses = append(whereClauses, fmt.Sprintf("tags @> jsonb_build_object($%d, $%d)", argIdx, argIdx+1))
			args = append(args, cond.Key, cond.Value)
		case "not_equals":
			whereClauses = append(whereClauses, fmt.Sprintf("NOT (tags @> jsonb_build_object($%d, $%d))", argIdx, argIdx+1))
			args = append(args, cond.Key, cond.Value)
			argIdx++
		case "contains":
			whereClauses = append(whereClauses, fmt.Sprintf("tags ->> $%d LIKE $%d", argIdx, argIdx+1))
			args = append(args, cond.Key, "%"+fmt.Sprintf("%v", cond.Value)+"%")
			argIdx++
		}
		argIdx++
	}

	if len(whereClauses) > 0 {
		joiner := " " + operator + " "
		baseQuery += " AND (" + strings.Join(whereClauses, joiner) + ")"
	}

	countQuery := `SELECT COUNT(*) ` + baseQuery
	var total int64
	if err := database.DB.QueryRow(countQuery, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count annotations by tags: %w", err)
	}

	dataQuery := `
		SELECT id, dataset_id, version_id, type, label, geometry, properties, tags, label_groups, creator_id, created_at, updated_at
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

	return s.queryAnnotations(dataQuery, args...)
}

func (s *AnnotationService) ListAnnotationsByLabelGroup(datasetID string, groupName string, labels map[string]string, limit, offset int) ([]*Annotation, int64, error) {
	baseQuery := `FROM annotations WHERE dataset_id = $1 AND EXISTS (
		SELECT 1 FROM jsonb_array_elements(label_groups) AS lg
		WHERE lg->>'group_name' = $2`
	args := []interface{}{datasetID, groupName}
	argIdx := 3

	for k, v := range labels {
		baseQuery += fmt.Sprintf(" AND lg->'labels' @> jsonb_build_object($%d, $%d)", argIdx, argIdx+1)
		args = append(args, k, v)
		argIdx += 2
	}
	baseQuery += ")"

	countQuery := `SELECT COUNT(*) ` + baseQuery
	var total int64
	if err := database.DB.QueryRow(countQuery, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count annotations by label group: %w", err)
	}

	dataQuery := `
		SELECT id, dataset_id, version_id, type, label, geometry, properties, tags, label_groups, creator_id, created_at, updated_at
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

	return s.queryAnnotations(dataQuery, args...)
}

func (s *AnnotationService) GetAnnotationTagKeys(datasetID string) ([]string, error) {
	query := `
		SELECT DISTINCT jsonb_object_keys(tags) as tag_key
		FROM annotations
		WHERE dataset_id = $1 AND tags IS NOT NULL
		ORDER BY tag_key
	`

	rows, err := database.DB.Query(query, datasetID)
	if err != nil {
		return nil, fmt.Errorf("failed to get tag keys: %w", err)
	}
	defer rows.Close()

	var keys []string
	for rows.Next() {
		var key string
		if err := rows.Scan(&key); err != nil {
			return nil, err
		}
		keys = append(keys, key)
	}

	return keys, nil
}

func (s *AnnotationService) UpdateAnnotationTags(annotationID string, tags map[string]string) (*Annotation, error) {
	tagsJSON, err := json.Marshal(tags)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal tags: %w", err)
	}

	query := `
		UPDATE annotations
		SET tags = COALESCE(tags, '{}'::jsonb) || $1, updated_at = $2
		WHERE id = $3
		RETURNING id, dataset_id, version_id, type, label, geometry, properties, tags, label_groups, creator_id, created_at, updated_at
	`

	ann := &Annotation{}
	var versionID sql.NullString
	var geometryJSON, propertiesJSON, tagsJSONResult, labelGroupsJSON []byte

	now := time.Now()
	err = database.DB.QueryRow(query, tagsJSON, now, annotationID).Scan(
		&ann.ID,
		&ann.DatasetID,
		&versionID,
		&ann.Type,
		&ann.Label,
		&geometryJSON,
		&propertiesJSON,
		&tagsJSONResult,
		&labelGroupsJSON,
		&ann.CreatorID,
		&ann.CreatedAt,
		&ann.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to update annotation tags: %w", err)
	}

	if versionID.Valid {
		ann.VersionID = versionID.String
	}

	json.Unmarshal(geometryJSON, &ann.Geometry)
	if propertiesJSON != nil {
		json.Unmarshal(propertiesJSON, &ann.Properties)
	}
	if tagsJSONResult != nil {
		json.Unmarshal(tagsJSONResult, &ann.Tags)
	}
	if labelGroupsJSON != nil {
		json.Unmarshal(labelGroupsJSON, &ann.LabelGroups)
	}

	return ann, nil
}

func (s *AnnotationService) queryAnnotations(query string, args ...interface{}) ([]*Annotation, int64, error) {
	rows, err := database.DB.Query(query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to query annotations: %w", err)
	}
	defer rows.Close()

	var annotations []*Annotation
	for rows.Next() {
		var ann Annotation
		var versionID sql.NullString
		var geometryJSON, propertiesJSON, tagsJSON, labelGroupsJSON []byte

		err := rows.Scan(
			&ann.ID,
			&ann.DatasetID,
			&versionID,
			&ann.Type,
			&ann.Label,
			&geometryJSON,
			&propertiesJSON,
			&tagsJSON,
			&labelGroupsJSON,
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
			json.Unmarshal(propertiesJSON, &ann.Properties)
		}
		if tagsJSON != nil {
			json.Unmarshal(tagsJSON, &ann.Tags)
		}
		if labelGroupsJSON != nil {
			json.Unmarshal(labelGroupsJSON, &ann.LabelGroups)
		}

		annotations = append(annotations, &ann)
	}

	var total int64
	if len(annotations) > 0 {
		total = int64(len(annotations))
	}

	return annotations, total, nil
}

func (s *AnnotationService) AddLabelGroup(annotationID string, group *LabelGroup) (*Annotation, error) {
	ann, err := s.GetAnnotation(annotationID)
	if err != nil {
		return nil, err
	}

	ann.LabelGroups = append(ann.LabelGroups, group)
	return s.UpdateAnnotation(annotationID, ann)
}

func (s *AnnotationService) GetAllLabelGroupNames(datasetID string) ([]string, error) {
	query := `
		SELECT DISTINCT lg->>'group_name' as group_name
		FROM annotations, jsonb_array_elements(label_groups) AS lg
		WHERE dataset_id = $1 AND label_groups IS NOT NULL
		ORDER BY group_name
	`

	rows, err := database.DB.Query(query, datasetID)
	if err != nil {
		return nil, fmt.Errorf("failed to get label group names: %w", err)
	}
	defer rows.Close()

	var names []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		names = append(names, name)
	}

	return names, nil
}
