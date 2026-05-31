package skillgraph

import (
	"fmt"
	"math"
	"sort"
)

type SkillLevel int

const (
	LevelNone SkillLevel = iota
	LevelBeginner
	LevelIntermediate
	LevelAdvanced
	LevelExpert
)

func (l SkillLevel) String() string {
	switch l {
	case LevelNone:
		return "none"
	case LevelBeginner:
		return "beginner"
	case LevelIntermediate:
		return "intermediate"
	case LevelAdvanced:
		return "advanced"
	case LevelExpert:
		return "expert"
	default:
		return "unknown"
	}
}

type Skill struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Category    string   `json:"category"`
	Description string   `json:"description"`
	Prerequisites []string `json:"prerequisites"`
	Weight      float64  `json:"weight"`
}

type SkillAssessment struct {
	SkillID    string     `json:"skill_id"`
	Level      SkillLevel `json:"level"`
	Score      float64    `json:"score"`
	EvaluatedAt string    `json:"evaluated_at"`
}

type Employee struct {
	ID          string            `json:"id"`
	Name        string            `json:"name"`
	Department  string            `json:"department"`
	Skills      map[string]SkillAssessment `json:"skills"`
}

type LearningPath struct {
	EmployeeID string  `json:"employee_id"`
	TargetSkillID string `json:"target_skill_id"`
	Steps      []LearningStep `json:"steps"`
	EstimatedHours float64 `json:"estimated_hours"`
	Priority   float64 `json:"priority"`
}

type LearningStep struct {
	SkillID    string `json:"skill_id"`
	SkillName  string `json:"skill_name"`
	Action     string `json:"action"`
	Duration   float64 `json:"duration_hours"`
	Order      int    `json:"order"`
}

type SkillGraph struct {
	skills    map[string]*Skill
	children  map[string][]string
	employees map[string]*Employee
}

func NewSkillGraph() *SkillGraph {
	return &SkillGraph{
		skills:    make(map[string]*Skill),
		children:  make(map[string][]string),
		employees: make(map[string]*Employee),
	}
}

func (sg *SkillGraph) AddSkill(skill Skill) error {
	if _, exists := sg.skills[skill.ID]; exists {
		return fmt.Errorf("skill %s already exists", skill.ID)
	}
	sg.skills[skill.ID] = &skill
	for _, pre := range skill.Prerequisites {
		sg.children[pre] = append(sg.children[pre], skill.ID)
	}
	return nil
}

func (sg *SkillGraph) GetSkill(id string) (*Skill, bool) {
	s, ok := sg.skills[id]
	return s, ok
}

func (sg *SkillGraph) RemoveSkill(id string) error {
	if _, ok := sg.skills[id]; !ok {
		return fmt.Errorf("skill %s not found", id)
	}
	delete(sg.skills, id)
	delete(sg.children, id)
	for parent, kids := range sg.children {
		filtered := make([]string, 0, len(kids))
		for _, k := range kids {
			if k != id {
				filtered = append(filtered, k)
			}
		}
		sg.children[parent] = filtered
	}
	for _, s := range sg.skills {
		filtered := make([]string, 0, len(s.Prerequisites))
		for _, p := range s.Prerequisites {
			if p != id {
				filtered = append(filtered, p)
			}
		}
		s.Prerequisites = filtered
	}
	return nil
}

func (sg *SkillGraph) AddEmployee(emp Employee) {
	sg.employees[emp.ID] = &emp
}

func (sg *SkillGraph) GetEmployee(id string) (*Employee, bool) {
	e, ok := sg.employees[id]
	return e, ok
}

func (sg *SkillGraph) UpdateSkillLevel(employeeID, skillID string, level SkillLevel, score float64) error {
	emp, ok := sg.employees[employeeID]
	if !ok {
		return fmt.Errorf("employee %s not found", employeeID)
	}
	if _, ok := sg.skills[skillID]; !ok {
		return fmt.Errorf("skill %s not found", skillID)
	}
	if emp.Skills == nil {
		emp.Skills = make(map[string]SkillAssessment)
	}
	emp.Skills[skillID] = SkillAssessment{
		SkillID:     skillID,
		Level:       level,
		Score:       score,
		EvaluatedAt: fmt.Sprintf("%d", getCurrentTime()),
	}
	return nil
}

func (sg *SkillGraph) GetSkillMatchScore(employeeID string, requiredSkills map[string]SkillLevel) float64 {
	emp, ok := sg.employees[employeeID]
	if !ok {
		return 0
	}
	var totalWeight float64
	var totalScore float64
	for skillID, requiredLevel := range requiredSkills {
		skill, exists := sg.skills[skillID]
		if !exists {
			continue
		}
		weight := skill.Weight
		if weight == 0 {
			weight = 1.0
		}
		totalWeight += weight
		assessment, hasSkill := emp.Skills[skillID]
		if !hasSkill {
			totalScore += 0
			continue
		}
		levelDiff := float64(assessment.Level) - float64(requiredLevel)
		if levelDiff >= 0 {
			totalScore += weight
		} else {
			penalty := math.Abs(levelDiff) / float64(LevelExpert)
			totalScore += weight * (1 - penalty)
		}
	}
	if totalWeight == 0 {
		return 0
	}
	return totalScore / totalWeight
}

func (sg *SkillGraph) FindQualifiedEmployees(requiredSkills map[string]SkillLevel, minScore float64) []EmployeeMatch {
	var matches []EmployeeMatch
	for _, emp := range sg.employees {
		score := sg.GetSkillMatchScore(emp.ID, requiredSkills)
		if score >= minScore {
			matches = append(matches, EmployeeMatch{
				EmployeeID:   emp.ID,
				EmployeeName: emp.Name,
				MatchScore:   score,
			})
		}
	}
	sort.Slice(matches, func(i, j int) bool {
		return matches[i].MatchScore > matches[j].MatchScore
	})
	return matches
}

type EmployeeMatch struct {
	EmployeeID   string  `json:"employee_id"`
	EmployeeName string  `json:"employee_name"`
	MatchScore   float64 `json:"match_score"`
}

func (sg *SkillGraph) GenerateLearningPath(employeeID, targetSkillID string) (*LearningPath, error) {
	emp, ok := sg.employees[employeeID]
	if !ok {
		return nil, fmt.Errorf("employee %s not found", employeeID)
	}
	if _, ok := sg.skills[targetSkillID]; !ok {
		return nil, fmt.Errorf("skill %s not found", targetSkillID)
	}
	var path []string
	visited := make(map[string]bool)
	sg.findPrerequisitePath(targetSkillID, emp, visited, &path)
	seen := make(map[string]bool)
	uniquePath := make([]string, 0, len(path))
	for _, sid := range path {
		if !seen[sid] {
			seen[sid] = true
			uniquePath = append(uniquePath, sid)
		}
	}
	steps := make([]LearningStep, 0, len(uniquePath))
	totalHours := 0.0
	for i, sid := range uniquePath {
		skill := sg.skills[sid]
		assessment, has := emp.Skills[sid]
		action := "learn"
		if has && assessment.Level >= LevelAdvanced {
			action = "maintain"
		} else if has && assessment.Level >= LevelIntermediate {
			action = "advance"
		}
		duration := 10.0 * (float64(LevelExpert) - float64(func() SkillLevel {
			if has {
				return assessment.Level
			}
			return LevelNone
		}()))
		if duration <= 0 {
			duration = 2.0
		}
		steps = append(steps, LearningStep{
			SkillID:   sid,
			SkillName: skill.Name,
			Action:    action,
			Duration:  duration,
			Order:     i + 1,
		})
		totalHours += duration
	}
	priority := sg.GetSkillMatchScore(employeeID, map[string]SkillLevel{targetSkillID: LevelExpert})
	return &LearningPath{
		EmployeeID:     employeeID,
		TargetSkillID:  targetSkillID,
		Steps:          steps,
		EstimatedHours: totalHours,
		Priority:       priority,
	}, nil
}

func (sg *SkillGraph) findPrerequisitePath(skillID string, emp *Employee, visited map[string]bool, path *[]string) {
	if visited[skillID] {
		return
	}
	visited[skillID] = true
	skill, ok := sg.skills[skillID]
	if !ok {
		return
	}
	if assessment, has := emp.Skills[skillID]; has && assessment.Level >= LevelAdvanced {
		return
	}
	for _, pre := range skill.Prerequisites {
		sg.findPrerequisitePath(pre, emp, visited, path)
	}
	*path = append(*path, skillID)
}

func (sg *SkillGraph) GetSkillTree(rootID string, maxDepth int) *SkillTreeNode {
	skill, ok := sg.skills[rootID]
	if !ok {
		return nil
	}
	node := &SkillTreeNode{
		SkillID:   skill.ID,
		SkillName: skill.Name,
		Category:  skill.Category,
		Weight:    skill.Weight,
	}
	if maxDepth <= 0 {
		return node
	}
	for _, childID := range sg.children[rootID] {
		child := sg.GetSkillTree(childID, maxDepth-1)
		if child != nil {
			node.Children = append(node.Children, child)
		}
	}
	return node
}

type SkillTreeNode struct {
	SkillID   string           `json:"skill_id"`
	SkillName string           `json:"skill_name"`
	Category  string           `json:"category"`
	Weight    float64          `json:"weight"`
	Children  []*SkillTreeNode `json:"children"`
}

func (lp *LearningPath) Format() string {
	result := fmt.Sprintf("Learning Path for %s -> %s (Est. %.1f hours, Priority: %.2f)\n",
		lp.EmployeeID, lp.TargetSkillID, lp.EstimatedHours, lp.Priority)
	for _, step := range lp.Steps {
		result += fmt.Sprintf("  %d. %s (%s): %s - %.1f hours\n",
			step.Order, step.SkillName, step.SkillID, step.Action, step.Duration)
	}
	return result
}

func getCurrentTime() int64 {
	return 0
}
