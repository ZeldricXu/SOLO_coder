package ticketassignment

import "strings"

type SkillMatcher struct{}

func NewSkillMatcher() *SkillMatcher {
	return &SkillMatcher{}
}

func (m *SkillMatcher) Match(employeeSkills []Skill, requiredSkills []string) (float64, []string, float64) {
	if len(requiredSkills) == 0 {
		return 1.0, []string{}, 0
	}

	matchedSkills := make([]string, 0)
	totalProficiency := 0.0
	matchedCount := 0

	for _, required := range requiredSkills {
		for _, skill := range employeeSkills {
			if strings.EqualFold(skill.Name, required) {
				matchedSkills = append(matchedSkills, skill.Name)
				totalProficiency += float64(skill.Proficiency)
				matchedCount++
				break
			}
		}
	}

	matchRatio := float64(matchedCount) / float64(len(requiredSkills))
	avgProficiency := 0.0
	if matchedCount > 0 {
		avgProficiency = totalProficiency / float64(matchedCount)
	}

	return matchRatio, matchedSkills, avgProficiency
}

func (m *SkillMatcher) CalculateSkillScore(matchRatio, avgProficiency float64, config AssignmentConfig) float64 {
	normalizedProficiency := avgProficiency / 5.0
	return config.SkillMatchWeight*matchRatio + config.ProficiencyWeight*normalizedProficiency
}
