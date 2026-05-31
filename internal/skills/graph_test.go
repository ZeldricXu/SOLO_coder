package skills

import (
	"context"
	"encoding/json"
	"sync"
	"testing"

	"github.com/datamigration/platform/pkg/models"
	"github.com/datamigration/platform/pkg/testutil"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func setupTestDB(t *testing.T) (*gorm.DB, *GraphService) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(&models.Skill{}, &models.EmployeeSkill{}, &models.LearningPath{})
	require.NoError(t, err)

	service := NewGraphService(db)
	return db, service
}

func TestCreateSkill_Success(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	metadata := map[string]interface{}{
		"level": "expert",
		"duration_hours": 40,
	}

	skill, err := service.CreateSkill(ctx, "Go Programming", "Advanced Go development", "engineering", nil, 3, metadata)
	require.NoError(t, err)
	require.NotNil(t, skill)

	assert.NotEmpty(t, skill.ID)
	assert.Equal(t, "Go Programming", skill.Name)
	assert.Equal(t, "Advanced Go development", skill.Description)
	assert.Equal(t, "engineering", skill.Category)
	assert.Equal(t, 3, skill.Level)
	assert.Nil(t, skill.ParentID)
	assert.NotEmpty(t, skill.Metadata)
}

func TestCreateSkill_WithParent(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	parent, err := service.CreateSkill(ctx, "Programming", "Basic programming", "engineering", nil, 1, nil)
	require.NoError(t, err)

	child, err := service.CreateSkill(ctx, "Go", "Go language", "engineering", &parent.ID, 2, nil)
	require.NoError(t, err)

	assert.Equal(t, parent.ID, *child.ParentID)
}

func TestGetSkillTree_AllCategories(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	_, err := service.CreateSkill(ctx, "Skill 1", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "Skill 2", "", "management", nil, 1, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "Skill 3", "", "engineering", nil, 2, nil)
	require.NoError(t, err)

	_ = factory

	skills, err := service.GetSkillTree(ctx, "")
	require.NoError(t, err)
	assert.Len(t, skills, 3)
}

func TestGetSkillTree_ByCategory(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	_, err := service.CreateSkill(ctx, "Skill 1", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "Skill 2", "", "management", nil, 1, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "Skill 3", "", "engineering", nil, 2, nil)
	require.NoError(t, err)

	skills, err := service.GetSkillTree(ctx, "engineering")
	require.NoError(t, err)
	assert.Len(t, skills, 2)

	skills, err = service.GetSkillTree(ctx, "management")
	require.NoError(t, err)
	assert.Len(t, skills, 1)
}

func TestGetSkillChildren(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	parent, err := service.CreateSkill(ctx, "Parent", "Parent skill", "engineering", nil, 1, nil)
	require.NoError(t, err)

	child1, err := service.CreateSkill(ctx, "Child 1", "", "engineering", &parent.ID, 2, nil)
	require.NoError(t, err)
	child2, err := service.CreateSkill(ctx, "Child 2", "", "engineering", &parent.ID, 2, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "Orphan", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	children, err := service.GetSkillChildren(ctx, parent.ID)
	require.NoError(t, err)
	assert.Len(t, children, 2)

	childIDs := map[string]bool{}
	for _, c := range children {
		childIDs[c.ID] = true
	}
	assert.True(t, childIDs[child1.ID])
	assert.True(t, childIDs[child2.ID])
}

func TestUpdateSkill(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "Original", "Original desc", "engineering", nil, 1, nil)
	require.NoError(t, err)

	err = service.UpdateSkill(ctx, skill.ID, map[string]interface{}{
		"name":        "Updated",
		"description": "Updated desc",
		"level":       2,
	})
	require.NoError(t, err)

	var loaded models.Skill
	err = service.db.First(&loaded, "id = ?", skill.ID).Error
	require.NoError(t, err)
	assert.Equal(t, "Updated", loaded.Name)
	assert.Equal(t, "Updated desc", loaded.Description)
	assert.Equal(t, 2, loaded.Level)
}

func TestDeleteSkill_WithChildren(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	parent, err := service.CreateSkill(ctx, "Parent", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	_, err = service.CreateSkill(ctx, "Child", "", "engineering", &parent.ID, 2, nil)
	require.NoError(t, err)

	err = service.DeleteSkill(ctx, parent.ID)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "cannot delete skill with children")
}

func TestDeleteSkill_WithoutChildren(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "ToDelete", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	err = service.DeleteSkill(ctx, skill.ID)
	require.NoError(t, err)

	var count int64
	service.db.Model(&models.Skill{}).Count(&count)
	assert.Equal(t, int64(0), count)
}

func TestAssessEmployeeSkill_NewAssessment(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	record, err := service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 4)
	require.NoError(t, err)
	require.NotNil(t, record)

	assert.Equal(t, "emp_1", record.EmployeeID)
	assert.Equal(t, skill.ID, record.SkillID)
	assert.Equal(t, 4, record.Proficiency)
	assert.NotEmpty(t, record.AssessmentAt)
}

func TestAssessEmployeeSkill_UpdateExisting(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	original, err := service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 3)
	require.NoError(t, err)

	updated, err := service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 5)
	require.NoError(t, err)

	assert.Equal(t, original.ID, updated.ID)
	assert.Equal(t, 5, updated.Proficiency)
	assert.True(t, updated.AssessmentAt.After(original.AssessmentAt))
}

func TestAssessEmployeeSkill_Validation(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	record, err := service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 0)
	require.Error(t, err)
	assert.Nil(t, record)
	assert.Contains(t, err.Error(), "proficiency must be between 1 and 5")

	record, err = service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 6)
	require.Error(t, err)
	assert.Nil(t, record)

	record, err = service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 1)
	require.NoError(t, err)
	assert.Equal(t, 1, record.Proficiency)

	record, err = service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 5)
	require.NoError(t, err)
	assert.Equal(t, 5, record.Proficiency)
}

func TestGetEmployeeSkills(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill1, _ := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	skill2, _ := service.CreateSkill(ctx, "Python", "", "engineering", nil, 1, nil)
	skill3, _ := service.CreateSkill(ctx, "Java", "", "engineering", nil, 1, nil)

	_, err := service.AssessEmployeeSkill(ctx, "emp_1", skill1.ID, 4)
	require.NoError(t, err)
	_, err = service.AssessEmployeeSkill(ctx, "emp_1", skill2.ID, 3)
	require.NoError(t, err)
	_, err = service.AssessEmployeeSkill(ctx, "emp_2", skill3.ID, 5)
	require.NoError(t, err)

	skills, err := service.GetEmployeeSkills(ctx, "emp_1")
	require.NoError(t, err)
	assert.Len(t, skills, 2)

	skillIDs := map[string]bool{}
	for _, s := range skills {
		skillIDs[s.SkillID] = true
	}
	assert.True(t, skillIDs[skill1.ID])
	assert.True(t, skillIDs[skill2.ID])
}

func TestGetEmployeeSkillGap(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill1, _ := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	skill2, _ := service.CreateSkill(ctx, "System Design", "", "engineering", nil, 1, nil)
	skill3, _ := service.CreateSkill(ctx, "Database", "", "engineering", nil, 1, nil)

	_, err := service.AssessEmployeeSkill(ctx, "emp_1", skill1.ID, 5)
	require.NoError(t, err)
	_, err = service.AssessEmployeeSkill(ctx, "emp_1", skill2.ID, 3)
	require.NoError(t, err)

	gap, err := service.GetEmployeeSkillGap(ctx, "emp_1", []string{skill1.ID, skill2.ID, skill3.ID})
	require.NoError(t, err)

	assert.Len(t, gap, 2)
	assert.Equal(t, 1, gap[skill2.ID])
	assert.Equal(t, 4, gap[skill3.ID])
	assert.NotContains(t, gap, skill1.ID)
}

func TestRecommendLearningPath_SeniorEngineer(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	_, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	systemDesign, err := service.CreateSkill(ctx, "System Design", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	dbSkill, err := service.CreateSkill(ctx, "Database", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	distributed, err := service.CreateSkill(ctx, "Distributed Systems", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	err = service.db.Model(&models.Skill{}).Where("name = ?", "Go").Update("id", "golang").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", systemDesign.ID).Update("id", "system_design").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", dbSkill.ID).Update("id", "database").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", distributed.ID).Update("id", "distributed_systems").Error
	require.NoError(t, err)

	_, err = service.AssessEmployeeSkill(ctx, "emp_1", "golang", 5)
	require.NoError(t, err)

	path, err := service.RecommendLearningPath(ctx, "emp_1", "senior_engineer")
	require.NoError(t, err)
	require.NotNil(t, path)

	assert.Equal(t, "emp_1", path.EmployeeID)
	assert.Contains(t, path.Name, "senior_engineer")
	assert.NotEmpty(t, path.Steps)
	assert.Equal(t, 0.0, path.Progress)
	assert.Equal(t, "recommended", path.Status)

	var steps []LearningStep
	err = json.Unmarshal(path.Steps, &steps)
	require.NoError(t, err)
	assert.Len(t, steps, 3)
}

func TestRecommendLearningPath_TechLead(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	_, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	systemDesign, _ := service.CreateSkill(ctx, "System Design", "", "engineering", nil, 1, nil)
	teamMgmt, _ := service.CreateSkill(ctx, "Team Management", "", "management", nil, 1, nil)
	arch, _ := service.CreateSkill(ctx, "Architecture", "", "engineering", nil, 1, nil)

	err = service.db.Model(&models.Skill{}).Where("name = ?", "Go").Update("id", "golang").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", systemDesign.ID).Update("id", "system_design").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", teamMgmt.ID).Update("id", "team_management").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", arch.ID).Update("id", "architecture").Error
	require.NoError(t, err)

	_, err = service.AssessEmployeeSkill(ctx, "emp_1", "golang", 5)
	require.NoError(t, err)
	_, err = service.AssessEmployeeSkill(ctx, "emp_1", "system_design", 4)
	require.NoError(t, err)

	path, err := service.RecommendLearningPath(ctx, "emp_1", "tech_lead")
	require.NoError(t, err)
	require.NotNil(t, path)

	var steps []LearningStep
	err = json.Unmarshal(path.Steps, &steps)
	require.NoError(t, err)
	assert.Len(t, steps, 2)
}

func TestRecommendLearningPath_UnknownRole(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	path, err := service.RecommendLearningPath(ctx, "emp_1", "unknown_role")
	require.Error(t, err)
	assert.Nil(t, path)
	assert.Contains(t, err.Error(), "unknown target role")
}

func TestUpdateLearningPathProgress_Validation(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	path := &models.LearningPath{
		ID:         "lp_test",
		EmployeeID: "emp_1",
		Name:       "Test Path",
		Status:     "recommended",
		Progress:   0,
	}
	err := service.db.Create(path).Error
	require.NoError(t, err)

	err = service.UpdateLearningPathProgress(ctx, "lp_test", -0.1)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "progress must be between 0 and 1")

	err = service.UpdateLearningPathProgress(ctx, "lp_test", 1.1)
	require.Error(t, err)
}

func TestUpdateLearningPathProgress_StatusTransition(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	path := &models.LearningPath{
		ID:         "lp_test",
		EmployeeID: "emp_1",
		Name:       "Test Path",
		Status:     "recommended",
		Progress:   0,
	}
	err := service.db.Create(path).Error
	require.NoError(t, err)

	err = service.UpdateLearningPathProgress(ctx, "lp_test", 0.5)
	require.NoError(t, err)

	var loaded models.LearningPath
	service.db.First(&loaded, "id = ?", "lp_test")
	assert.Equal(t, 0.5, loaded.Progress)
	assert.Equal(t, "in_progress", loaded.Status)

	err = service.UpdateLearningPathProgress(ctx, "lp_test", 1.0)
	require.NoError(t, err)

	service.db.First(&loaded, "id = ?", "lp_test")
	assert.Equal(t, 1.0, loaded.Progress)
	assert.Equal(t, "completed", loaded.Status)
}

func TestGetLearningPaths(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	path1 := &models.LearningPath{
		ID:         "lp_1",
		EmployeeID: "emp_1",
		Name:       "Path 1",
		Status:     "completed",
		Progress:   1.0,
	}
	path2 := &models.LearningPath{
		ID:         "lp_2",
		EmployeeID: "emp_1",
		Name:       "Path 2",
		Status:     "in_progress",
		Progress:   0.5,
	}
	path3 := &models.LearningPath{
		ID:         "lp_3",
		EmployeeID: "emp_2",
		Name:       "Path 3",
		Status:     "recommended",
		Progress:   0,
	}

	err := service.db.Create(path1).Error
	require.NoError(t, err)
	err = service.db.Create(path2).Error
	require.NoError(t, err)
	err = service.db.Create(path3).Error
	require.NoError(t, err)

	paths, err := service.GetLearningPaths(ctx, "emp_1")
	require.NoError(t, err)
	assert.Len(t, paths, 2)
	assert.Equal(t, "lp_2", paths[0].ID)
	assert.Equal(t, "lp_1", paths[1].ID)
}

func TestConcurrentSkillAssessments(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	const numGoroutines = 100
	var wg sync.WaitGroup
	var mu sync.Mutex
	failures := 0

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(empID string) {
			defer wg.Done()
			_, err := service.AssessEmployeeSkill(ctx, empID, skill.ID, 3)
			if err != nil {
				mu.Lock()
				failures++
				mu.Unlock()
			}
		}(fmt.Sprintf("emp_%d", i))
	}
	wg.Wait()

	assert.Equal(t, 0, failures)

	var count int64
	service.db.Model(&models.EmployeeSkill{}).Count(&count)
	assert.Equal(t, int64(numGoroutines), count)
}

func TestConcurrentSameEmployeeAssessments(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	const numGoroutines = 50
	var wg sync.WaitGroup
	var mu sync.Mutex
	failures := 0

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(proficiency int) {
			defer wg.Done()
			_, err := service.AssessEmployeeSkill(ctx, "emp_same", skill.ID, proficiency)
			if err != nil {
				mu.Lock()
				failures++
				mu.Unlock()
			}
		}(i%5 + 1)
	}
	wg.Wait()

	assert.Equal(t, 0, failures)

	var records []models.EmployeeSkill
	service.db.Where("employee_id = ? AND skill_id = ?", "emp_same", skill.ID).Find(&records)
	assert.Len(t, records, 1)
}

func TestSkillTreeOrdering(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	_, err := service.CreateSkill(ctx, "L2_A", "", "engineering", nil, 2, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "L1_A", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "L2_B", "", "engineering", nil, 2, nil)
	require.NoError(t, err)
	_, err = service.CreateSkill(ctx, "L1_B", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	skills, err := service.GetSkillTree(ctx, "engineering")
	require.NoError(t, err)
	assert.Len(t, skills, 4)

	assert.Equal(t, 1, skills[0].Level)
	assert.Equal(t, 1, skills[1].Level)
	assert.Equal(t, 2, skills[2].Level)
	assert.Equal(t, 2, skills[3].Level)
}

func TestLearningPathStepsOrder(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	_, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)
	systemDesign, _ := service.CreateSkill(ctx, "System Design", "", "engineering", nil, 1, nil)
	dbSkill, _ := service.CreateSkill(ctx, "Database", "", "engineering", nil, 1, nil)
	distributed, _ := service.CreateSkill(ctx, "Distributed Systems", "", "engineering", nil, 1, nil)

	err = service.db.Model(&models.Skill{}).Where("name = ?", "Go").Update("id", "golang").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", systemDesign.ID).Update("id", "system_design").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", dbSkill.ID).Update("id", "database").Error
	require.NoError(t, err)
	err = service.db.Model(&models.Skill{}).Where("id = ?", distributed.ID).Update("id", "distributed_systems").Error
	require.NoError(t, err)

	path, err := service.RecommendLearningPath(ctx, "emp_new", "senior_engineer")
	require.NoError(t, err)

	var steps []LearningStep
	err = json.Unmarshal(path.Steps, &steps)
	require.NoError(t, err)

	for i, step := range steps {
		assert.Equal(t, i+1, step.Order)
		assert.Equal(t, 4, step.TargetLevel)
		assert.Len(t, step.Resources, 2)
	}
}

func TestAssessEmployeeSkill_DataConsistency(t *testing.T) {
	_, service := setupTestDB(t)
	ctx := context.Background()

	skill, err := service.CreateSkill(ctx, "Go", "", "engineering", nil, 1, nil)
	require.NoError(t, err)

	first, err := service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 3)
	require.NoError(t, err)
	firstID := first.ID
	firstCreated := first.CreatedAt

	second, err := service.AssessEmployeeSkill(ctx, "emp_1", skill.ID, 4)
	require.NoError(t, err)

	assert.Equal(t, firstID, second.ID)
	assert.Equal(t, firstCreated, second.CreatedAt)
	assert.True(t, second.UpdatedAt.After(first.UpdatedAt))
	assert.True(t, second.AssessmentAt.After(first.AssessmentAt))
}
