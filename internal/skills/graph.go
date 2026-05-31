package skills

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type GraphService struct {
	db *gorm.DB
}

func NewGraphService(db *gorm.DB) *GraphService {
	return &GraphService{db: db}
}

func (s *GraphService) CreateSkill(ctx context.Context, name, description, category string, parentID *string, level int, metadata map[string]interface{}) (*models.Skill, error) {
	metaBytes, _ := json.Marshal(metadata)

	skill := &models.Skill{
		ID:          fmt.Sprintf("skl_%s", uuid.New().String()[:8]),
		Name:        name,
		Description: description,
		Category:    category,
		ParentID:    parentID,
		Level:       level,
		Metadata:    metaBytes,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(skill).Error; err != nil {
		logger.Error("failed to create skill", zap.Error(err))
		return nil, err
	}

	return skill, nil
}

func (s *GraphService) GetSkillTree(ctx context.Context, category string) ([]*models.Skill, error) {
	var skills []*models.Skill
	query := s.db.WithContext(ctx).Model(&models.Skill{})
	if category != "" {
		query = query.Where("category = ?", category)
	}
	if err := query.Order("level, parent_id").Find(&skills).Error; err != nil {
		return nil, err
	}
	return skills, nil
}

func (s *GraphService) GetSkillChildren(ctx context.Context, parentID string) ([]*models.Skill, error) {
	var children []*models.Skill
	if err := s.db.WithContext(ctx).Where("parent_id = ?", parentID).Find(&children).Error; err != nil {
		return nil, err
	}
	return children, nil
}

func (s *GraphService) UpdateSkill(ctx context.Context, skillID string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return s.db.WithContext(ctx).Model(&models.Skill{}).Where("id = ?", skillID).Updates(updates).Error
}

func (s *GraphService) DeleteSkill(ctx context.Context, skillID string) error {
	children, err := s.GetSkillChildren(ctx, skillID)
	if err != nil {
		return err
	}
	if len(children) > 0 {
		return errors.New("cannot delete skill with children")
	}
	return s.db.WithContext(ctx).Where("id = ?", skillID).Delete(&models.Skill{}).Error
}

func (s *GraphService) AssessEmployeeSkill(ctx context.Context, employeeID, skillID string, proficiency int) (*models.EmployeeSkill, error) {
	if proficiency < 1 || proficiency > 5 {
		return nil, errors.New("proficiency must be between 1 and 5")
	}

	var existing models.EmployeeSkill
	err := s.db.WithContext(ctx).Where("employee_id = ? AND skill_id = ?", employeeID, skillID).First(&existing).Error
	if err == nil {
		now := time.Now()
		existing.Proficiency = proficiency
		existing.AssessmentAt = now
		existing.UpdatedAt = now
		if err := s.db.WithContext(ctx).Save(&existing).Error; err != nil {
			return nil, err
		}
		return &existing, nil
	}

	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, err
	}

	record := &models.EmployeeSkill{
		ID:           fmt.Sprintf("esk_%s", uuid.New().String()[:8]),
		EmployeeID:   employeeID,
		SkillID:      skillID,
		Proficiency:  proficiency,
		AssessmentAt: time.Now(),
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(record).Error; err != nil {
		logger.Error("failed to assess employee skill", zap.Error(err))
		return nil, err
	}

	return record, nil
}

func (s *GraphService) GetEmployeeSkills(ctx context.Context, employeeID string) ([]*models.EmployeeSkill, error) {
	var skills []*models.EmployeeSkill
	if err := s.db.WithContext(ctx).Where("employee_id = ?", employeeID).Find(&skills).Error; err != nil {
		return nil, err
	}
	return skills, nil
}

func (s *GraphService) GetEmployeeSkillGap(ctx context.Context, employeeID string, targetSkills []string) (map[string]int, error) {
	currentMap := make(map[string]int)
	skills, err := s.GetEmployeeSkills(ctx, employeeID)
	if err != nil {
		return nil, err
	}
	for _, es := range skills {
		currentMap[es.SkillID] = es.Proficiency
	}

	gap := make(map[string]int)
	for _, skillID := range targetSkills {
		current := currentMap[skillID]
		required := 4
		if current < required {
			gap[skillID] = required - current
		}
	}
	return gap, nil
}

type LearningStep struct {
	SkillID     string `json:"skill_id"`
	SkillName   string `json:"skill_name"`
	TargetLevel int    `json:"target_level"`
	Order       int    `json:"order"`
	Resources   []string `json:"resources,omitempty"`
}

func (s *GraphService) RecommendLearningPath(ctx context.Context, employeeID string, targetRole string) (*models.LearningPath, error) {
	var targetSkills []string
	switch targetRole {
	case "senior_engineer":
		targetSkills = []string{"golang", "system_design", "database", "distributed_systems"}
	case "tech_lead":
		targetSkills = []string{"golang", "system_design", "team_management", "architecture"}
	case "architect":
		targetSkills = []string{"architecture", "distributed_systems", "cloud", "security"}
	default:
		return nil, errors.New("unknown target role")
	}

	gap, err := s.GetEmployeeSkillGap(ctx, employeeID, targetSkills)
	if err != nil {
		return nil, err
	}

	var steps []LearningStep
	order := 1
	for skillID, delta := range gap {
		if delta > 0 {
			var skill models.Skill
			if err := s.db.WithContext(ctx).Where("id = ?", skillID).First(&skill).Error; err != nil {
				continue
			}
			steps = append(steps, LearningStep{
				SkillID:     skillID,
				SkillName:   skill.Name,
				TargetLevel: 4,
				Order:       order,
				Resources:   []string{"course_" + skillID, "book_" + skillID},
			})
			order++
		}
	}

	sort.Slice(steps, func(i, j int) bool {
		return steps[i].Order < steps[j].Order
	})

	stepsBytes, _ := json.Marshal(steps)

	path := &models.LearningPath{
		ID:          fmt.Sprintf("lp_%s", uuid.New().String()[:8]),
		EmployeeID:  employeeID,
		Name:        fmt.Sprintf("Path to %s", targetRole),
		Description: fmt.Sprintf("Recommended learning path for transitioning to %s role", targetRole),
		Steps:       stepsBytes,
		Status:      "recommended",
		Progress:    0,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(path).Error; err != nil {
		logger.Error("failed to create learning path", zap.Error(err))
		return nil, err
	}

	return path, nil
}

func (s *GraphService) UpdateLearningPathProgress(ctx context.Context, pathID string, progress float64) error {
	if progress < 0 || progress > 1 {
		return errors.New("progress must be between 0 and 1")
	}

	status := "in_progress"
	if progress >= 1 {
		status = "completed"
	}

	return s.db.WithContext(ctx).Model(&models.LearningPath{}).Where("id = ?", pathID).Updates(map[string]interface{}{
		"progress":   progress,
		"status":     status,
		"updated_at": time.Now(),
	}).Error
}

func (s *GraphService) GetLearningPaths(ctx context.Context, employeeID string) ([]*models.LearningPath, error) {
	var paths []*models.LearningPath
	if err := s.db.WithContext(ctx).Where("employee_id = ?", employeeID).Order("created_at DESC").Find(&paths).Error; err != nil {
		return nil, err
	}
	return paths, nil
}
