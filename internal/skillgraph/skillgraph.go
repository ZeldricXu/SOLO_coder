package skillgraph

import (
	"sort"
	"time"

	"gorm.io/gorm"
	"session187/internal/common"
	"session187/pkg/errors"
)

type SkillNode struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID    string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Name        string                 `json:"name" gorm:"type:varchar(128)"`
	Description string                 `json:"description" gorm:"type:text"`
	Category    string                 `json:"category" gorm:"type:varchar(64);index"`
	Level       int                    `json:"level" gorm:"default:1"`
	ParentID    string                 `json:"parent_id" gorm:"type:varchar(64)"`
	Prerequisites []string             `json:"prerequisites" gorm:"type:jsonb;serializer:json"`
	Weight      float64                `json:"weight" gorm:"default:1.0"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type EmployeeSkill struct {
	ID             string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID       string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	EmployeeID     string                 `json:"employee_id" gorm:"type:varchar(64);index"`
	EmployeeName   string                 `json:"employee_name" gorm:"type:varchar(128)"`
	SkillID        string                 `json:"skill_id" gorm:"type:varchar(64);index"`
	SkillName      string                 `json:"skill_name"`
	Proficiency    int                    `json:"proficiency" gorm:"index"`
	Certified      bool                   `json:"certified"`
	CertifiedDate  *time.Time             `json:"certified_date"`
	ExperienceYears float64              `json:"experience_years"`
	LastUsed       *time.Time             `json:"last_used"`
	AssessmentDate *time.Time             `json:"assessment_date"`
	Metadata       map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

type LearningPath struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID    string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Name        string                 `json:"name" gorm:"type:varchar(128)"`
	Description string                 `json:"description" gorm:"type:text"`
	TargetRole  string                 `json:"target_role" gorm:"type:varchar(64);index"`
	SkillNodes  []string               `json:"skill_nodes" gorm:"type:jsonb;serializer:json"`
	EstimatedHours int                 `json:"estimated_hours"`
	Difficulty  string                 `json:"difficulty" gorm:"type:varchar(32)"`
	Status      string                 `json:"status" gorm:"type:varchar(32);index"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type SkillRecommendation struct {
	SkillID       string  `json:"skill_id"`
	SkillName     string  `json:"skill_name"`
	Reason        string  `json:"reason"`
	Priority      int     `json:"priority"`
	EstimatedDays int     `json:"estimated_days"`
	MatchScore    float64 `json:"match_score"`
}

type Manager struct {
	db *gorm.DB
}

func NewManager(db *gorm.DB) *Manager {
	return &Manager{db: db}
}

func (m *Manager) CreateSkill(tenantID, name, description, category string, level int, parentID string, prerequisites []string, weight float64) (*SkillNode, error) {
	skill := &SkillNode{
		ID:            common.GenerateID("skl"),
		TenantID:      tenantID,
		Name:          name,
		Description:   description,
		Category:      category,
		Level:         level,
		ParentID:      parentID,
		Prerequisites: prerequisites,
		Weight:        weight,
		CreatedAt:     common.TimeNowUTC(),
		UpdatedAt:     common.TimeNowUTC(),
	}
	if err := m.db.Create(skill).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建技能节点失败", err.Error())
	}
	return skill, nil
}

func (m *Manager) GetSkill(tenantID, skillID string) (*SkillNode, error) {
	var skill SkillNode
	err := m.db.Where("id = ? AND tenant_id = ?", skillID, tenantID).First(&skill).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询技能节点失败", err.Error())
	}
	return &skill, nil
}

func (m *Manager) ListSkills(tenantID, category string) ([]SkillNode, error) {
	var skills []SkillNode
	query := m.db.Where("tenant_id = ?", tenantID)
	if category != "" {
		query = query.Where("category = ?", category)
	}
	err := query.Order("category, level").Find(&skills).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询技能列表失败", err.Error())
	}
	return skills, nil
}

func (m *Manager) GetSkillTree(tenantID string) ([]SkillNode, error) {
	return m.ListSkills(tenantID, "")
}

func (m *Manager) AddEmployeeSkill(tenantID, employeeID, employeeName, skillID, skillName string, proficiency int, certified bool, experienceYears float64) (*EmployeeSkill, error) {
	now := common.TimeNowUTC()
	empSkill := &EmployeeSkill{
		ID:              common.GenerateID("esk"),
		TenantID:        tenantID,
		EmployeeID:      employeeID,
		EmployeeName:    employeeName,
		SkillID:         skillID,
		SkillName:       skillName,
		Proficiency:     proficiency,
		Certified:       certified,
		ExperienceYears: experienceYears,
		AssessmentDate:  &now,
		CreatedAt:       common.TimeNowUTC(),
		UpdatedAt:       common.TimeNowUTC(),
	}
	if err := m.db.Create(empSkill).Error; err != nil {
		return nil, errors.NewWithDetail(500, "添加员工技能失败", err.Error())
	}
	return empSkill, nil
}

func (m *Manager) GetEmployeeSkills(tenantID, employeeID string) ([]EmployeeSkill, error) {
	var skills []EmployeeSkill
	err := m.db.Where("tenant_id = ? AND employee_id = ?", tenantID, employeeID).
		Order("proficiency desc").Find(&skills).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询员工技能失败", err.Error())
	}
	return skills, nil
}

func (m *Manager) UpdateEmployeeSkill(tenantID, empSkillID string, proficiency int, certified bool) (*EmployeeSkill, error) {
	var empSkill EmployeeSkill
	err := m.db.Where("id = ? AND tenant_id = ?", empSkillID, tenantID).First(&empSkill).Error
	if err != nil {
		return nil, errors.ErrNotFound
	}
	now := common.TimeNowUTC()
	empSkill.Proficiency = proficiency
	empSkill.Certified = certified
	empSkill.AssessmentDate = &now
	empSkill.UpdatedAt = now
	if err := m.db.Save(&empSkill).Error; err != nil {
		return nil, errors.NewWithDetail(500, "更新员工技能失败", err.Error())
	}
	return &empSkill, nil
}

func (m *Manager) GetSkillGapAnalysis(tenantID, employeeID string) (map[string]interface{}, error) {
	empSkills, err := m.GetEmployeeSkills(tenantID, employeeID)
	if err != nil {
		return nil, err
	}
	allSkills, err := m.ListSkills(tenantID, "")
	if err != nil {
		return nil, err
	}
	empSkillMap := make(map[string]int)
	for _, s := range empSkills {
		empSkillMap[s.SkillID] = s.Proficiency
	}
	var gaps []map[string]interface{}
	for _, skill := range allSkills {
		prof, ok := empSkillMap[skill.ID]
		if !ok {
			gaps = append(gaps, map[string]interface{}{
				"skill_id":   skill.ID,
				"skill_name": skill.Name,
				"current":    0,
				"target":     skill.Level,
				"gap":        skill.Level,
			})
		} else if prof < skill.Level {
			gaps = append(gaps, map[string]interface{}{
				"skill_id":   skill.ID,
				"skill_name": skill.Name,
				"current":    prof,
				"target":     skill.Level,
				"gap":        skill.Level - prof,
			})
		}
	}
	return map[string]interface{}{
		"employee_id": employeeID,
		"total_skills": len(allSkills),
		"mastered_skills": len(empSkills),
		"gaps": gaps,
	}, nil
}

func (m *Manager) RecommendLearningPath(tenantID, employeeID, targetRole string) ([]SkillRecommendation, error) {
	empSkills, err := m.GetEmployeeSkills(tenantID, employeeID)
	if err != nil {
		return nil, err
	}
	empSkillMap := make(map[string]int)
	for _, s := range empSkills {
		empSkillMap[s.SkillID] = s.Proficiency
	}
	allSkills, err := m.ListSkills(tenantID, "")
	if err != nil {
		return nil, err
	}
	var recommendations []SkillRecommendation
	for _, skill := range allSkills {
		prof := empSkillMap[skill.ID]
		if prof < skill.Level {
			gap := skill.Level - prof
			matchScore := float64(gap) * skill.Weight
			recommendations = append(recommendations, SkillRecommendation{
				SkillID:       skill.ID,
				SkillName:     skill.Name,
				Reason:        "技能提升需求",
				Priority:      gap,
				EstimatedDays: gap * 7,
				MatchScore:    matchScore,
			})
		}
	}
	sort.Slice(recommendations, func(i, j int) bool {
		return recommendations[i].MatchScore > recommendations[j].MatchScore
	})
	return recommendations, nil
}

func (m *Manager) CreateLearningPath(tenantID, name, description, targetRole string, skillNodes []string, estimatedHours int, difficulty string) (*LearningPath, error) {
	path := &LearningPath{
		ID:             common.GenerateID("lpt"),
		TenantID:       tenantID,
		Name:           name,
		Description:    description,
		TargetRole:     targetRole,
		SkillNodes:     skillNodes,
		EstimatedHours: estimatedHours,
		Difficulty:     difficulty,
		Status:         "active",
		CreatedAt:      common.TimeNowUTC(),
		UpdatedAt:      common.TimeNowUTC(),
	}
	if err := m.db.Create(path).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建学习路径失败", err.Error())
	}
	return path, nil
}

func (m *Manager) GetLearningPaths(tenantID, targetRole string) ([]LearningPath, error) {
	var paths []LearningPath
	query := m.db.Where("tenant_id = ? AND status = ?", tenantID, "active")
	if targetRole != "" {
		query = query.Where("target_role = ?", targetRole)
	}
	err := query.Find(&paths).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询学习路径失败", err.Error())
	}
	return paths, nil
}

func (m *Manager) FindEmployeesBySkill(tenantID, skillID string, minProficiency int) ([]EmployeeSkill, error) {
	var employees []EmployeeSkill
	err := m.db.Where("tenant_id = ? AND skill_id = ? AND proficiency >= ?",
		tenantID, skillID, minProficiency).Order("proficiency desc").Find(&employees).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "按技能查找员工失败", err.Error())
	}
	return employees, nil
}

func (s *SkillNode) TableName() string {
	return "skill_nodes"
}

func (e *EmployeeSkill) TableName() string {
	return "employee_skills"
}

func (l *LearningPath) TableName() string {
	return "learning_paths"
}
