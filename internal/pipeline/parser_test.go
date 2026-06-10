package pipeline

import (
	"fmt"
	"os"
	"testing"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/tests/fixtures"
	"github.com/stretchr/testify/assert"
)

func TestParseYAML_Success(t *testing.T) {
	parser := NewParser()

	data, err := os.ReadFile("testdata/valid_pipeline.yaml")
	assert.NoError(t, err)

	result, err := parser.ParseYAML(data)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.NotNil(t, result.Definition)
	assert.Empty(t, result.Errors)

	assert.Equal(t, "test-pipeline-valid", result.Definition.Name)
	assert.Equal(t, "1.0.0", result.Definition.Version)

	assert.Len(t, result.Definition.Stages, 5)

	expectedStages := []struct {
		name      string
		stageType types.StageType
		dependsOn []string
	}{
		{"scan-code", types.StageTypeScan, nil},
		{"build-app", types.StageTypeBuild, []string{"scan-code"}},
		{"unit-test", types.StageTypeTest, []string{"scan-code"}},
		{"integration-test", types.StageTypeTest, []string{"unit-test", "build-app"}},
		{"deploy-staging", types.StageTypeDeploy, []string{"integration-test"}},
	}

	for i, expected := range expectedStages {
		assert.Equal(t, expected.name, result.Definition.Stages[i].Name)
		assert.Equal(t, expected.stageType, result.Definition.Stages[i].Type)
		assert.Equal(t, expected.dependsOn, result.Definition.Stages[i].DependsOn)
	}

	assert.Len(t, result.Definition.Triggers, 3)
}

func TestParseJSON_Success(t *testing.T) {
	parser := NewParser()

	data, err := os.ReadFile("testdata/valid_pipeline.json")
	assert.NoError(t, err)

	result, err := parser.ParseJSON(data)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.NotNil(t, result.Definition)
	assert.Empty(t, result.Errors)

	assert.Equal(t, "test-pipeline-json", result.Definition.Name)
	assert.Equal(t, "1.0.0", result.Definition.Version)
	assert.Len(t, result.Definition.Stages, 3)
	assert.Len(t, result.Definition.Triggers, 1)

	assert.Equal(t, "init", result.Definition.Stages[0].Name)
	assert.Equal(t, types.StageTypeBuild, result.Definition.Stages[0].Type)
	assert.Equal(t, "test", result.Definition.Stages[1].Name)
	assert.Equal(t, types.StageTypeTest, result.Definition.Stages[1].Type)
	assert.Equal(t, []string{"init"}, result.Definition.Stages[1].DependsOn)
	assert.Equal(t, "deploy", result.Definition.Stages[2].Name)
	assert.Equal(t, types.StageTypeDeploy, result.Definition.Stages[2].Type)
	assert.Equal(t, []string{"test"}, result.Definition.Stages[2].DependsOn)
}

func TestTopologicalSort(t *testing.T) {
	parser := NewParser()

	t.Run("linear pipeline", func(t *testing.T) {
		stages := fixtures.GenerateDAGStages(0)
		order, err := parser.TopologicalSort(stages)
		assert.NoError(t, err)
		assert.Len(t, order, 2)
		assert.Equal(t, "init", order[0])
		assert.Equal(t, "final", order[1])
	})

	t.Run("parallel stages pipeline", func(t *testing.T) {
		parallelWidth := 3
		stages := fixtures.GenerateDAGStages(parallelWidth)
		order, err := parser.TopologicalSort(stages)
		assert.NoError(t, err)
		assert.Len(t, order, parallelWidth+2)

		assert.Equal(t, "init", order[0])

		parallelSet := make(map[string]bool)
		for i := 0; i < parallelWidth; i++ {
			parallelSet[order[i+1]] = true
		}
		for i := 0; i < parallelWidth; i++ {
			assert.True(t, parallelSet[fmt.Sprintf("parallel-%d", i)])
		}

		assert.Equal(t, "final", order[parallelWidth+1])
	})

	t.Run("complex DAG", func(t *testing.T) {
		stages := []types.StageDefinition{
			{Name: "a", Type: types.StageTypeBuild, Commands: []string{"echo a"}},
			{Name: "b", Type: types.StageTypeBuild, DependsOn: []string{"a"}, Commands: []string{"echo b"}},
			{Name: "c", Type: types.StageTypeBuild, DependsOn: []string{"a"}, Commands: []string{"echo c"}},
			{Name: "d", Type: types.StageTypeBuild, DependsOn: []string{"b", "c"}, Commands: []string{"echo d"}},
			{Name: "e", Type: types.StageTypeBuild, DependsOn: []string{"d"}, Commands: []string{"echo e"}},
		}

		order, err := parser.TopologicalSort(stages)
		assert.NoError(t, err)
		assert.Len(t, order, 5)

		pos := make(map[string]int)
		for i, name := range order {
			pos[name] = i
		}

		assert.Less(t, pos["a"], pos["b"])
		assert.Less(t, pos["a"], pos["c"])
		assert.Less(t, pos["b"], pos["d"])
		assert.Less(t, pos["c"], pos["d"])
		assert.Less(t, pos["d"], pos["e"])
	})
}

func TestParseYAML_CyclicDependency(t *testing.T) {
	parser := NewParser()

	data, err := os.ReadFile("testdata/cyclic_pipeline.yaml")
	assert.NoError(t, err)

	result, err := parser.ParseYAML(data)
	assert.Error(t, err)
	assert.NotNil(t, result)
	assert.NotEmpty(t, result.Errors)

	foundCycle := false
	for _, e := range result.Errors {
		if e.Path == "stages" && (e.Message == "circular dependency detected: stage-a -> stage-b" || e.Message == "circular dependency detected: stage-b -> stage-a") {
			foundCycle = true
			break
		}
	}
	assert.True(t, foundCycle, "Expected circular dependency error")
}

func TestParseYAML_EmptyDefinition(t *testing.T) {
	parser := NewParser()

	t.Run("empty bytes", func(t *testing.T) {
		_, err := parser.ParseYAML([]byte{})
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "empty pipeline definition")
	})

	t.Run("whitespace only", func(t *testing.T) {
		_, err := parser.ParseYAML([]byte("   \n   \t  \n"))
		assert.Error(t, err)
	})

	t.Run("yaml document with empty content", func(t *testing.T) {
		_, err := parser.ParseYAML([]byte("name: \nstages: []\n"))
		assert.Error(t, err)
	})
}

func TestParseYAML_InvalidStageType(t *testing.T) {
	parser := NewParser()

	invalidYAML := `
name: test-pipeline
stages:
  - name: valid-stage
    type: build
    commands:
      - echo hello
  - name: invalid-stage
    type: invalid_type
    commands:
      - echo world
`

	result, err := parser.ParseYAML([]byte(invalidYAML))
	assert.Error(t, err)
	assert.NotNil(t, result)

	foundInvalidType := false
	for _, e := range result.Errors {
		if e.Path == "stages[1].type" && e.Message == "invalid stage type: invalid_type" {
			foundInvalidType = true
			break
		}
	}
	assert.True(t, foundInvalidType, "Expected invalid stage type error")
}

func TestParseYAML_DuplicateStageName(t *testing.T) {
	parser := NewParser()

	duplicateYAML := `
name: test-pipeline
stages:
  - name: build-stage
    type: build
    commands:
      - echo build
  - name: build-stage
    type: test
    commands:
      - echo test
`

	result, err := parser.ParseYAML([]byte(duplicateYAML))
	assert.Error(t, err)
	assert.NotNil(t, result)

	foundDuplicate := false
	for _, e := range result.Errors {
		if e.Path == "stages[1].name" && e.Message == "duplicate stage name: build-stage" {
			foundDuplicate = true
			break
		}
	}
	assert.True(t, foundDuplicate, "Expected duplicate stage name error")
}

func TestParseYAML_SelfDependency(t *testing.T) {
	parser := NewParser()

	selfDepYAML := `
name: test-pipeline
stages:
  - name: build
    type: build
    depends_on:
      - build
    commands:
      - echo build
`

	result, err := parser.ParseYAML([]byte(selfDepYAML))
	assert.Error(t, err)
	assert.NotNil(t, result)

	foundSelfDep := false
	for _, e := range result.Errors {
		if e.Path == "stages[0].depends_on[0]" && e.Message == "stage cannot depend on itself" {
			foundSelfDep = true
			break
		}
	}
	assert.True(t, foundSelfDep, "Expected self dependency error")
}

func TestParseYAML_UnknownDependency(t *testing.T) {
	parser := NewParser()

	unknownDepYAML := `
name: test-pipeline
stages:
  - name: build
    type: build
    depends_on:
      - non-existent-stage
    commands:
      - echo build
`

	result, err := parser.ParseYAML([]byte(unknownDepYAML))
	assert.Error(t, err)
	assert.NotNil(t, result)

	foundUnknownDep := false
	for _, e := range result.Errors {
		if e.Path == "stages[0].depends_on[0]" && e.Message == "dependency stage not found: non-existent-stage" {
			foundUnknownDep = true
			break
		}
	}
	assert.True(t, foundUnknownDep, "Expected unknown dependency error")
}

func TestToJSON_Roundtrip(t *testing.T) {
	parser := NewParser()

	yamlData, err := os.ReadFile("testdata/valid_pipeline.yaml")
	assert.NoError(t, err)

	parseResult, err := parser.ParseYAML(yamlData)
	assert.NoError(t, err)
	assert.NotNil(t, parseResult.Definition)

	jsonData, err := parser.ToJSON(parseResult.Definition)
	assert.NoError(t, err)
	assert.NotEmpty(t, jsonData)

	parsedFromJSON, err := parser.ParseJSON(jsonData)
	assert.NoError(t, err)
	assert.NotNil(t, parsedFromJSON.Definition)

	assert.Equal(t, parseResult.Definition.Name, parsedFromJSON.Definition.Name)
	assert.Equal(t, parseResult.Definition.Version, parsedFromJSON.Definition.Version)
	assert.Equal(t, parseResult.Definition.Description, parsedFromJSON.Definition.Description)
	assert.Len(t, parsedFromJSON.Definition.Stages, len(parseResult.Definition.Stages))

	for i := range parseResult.Definition.Stages {
		assert.Equal(t, parseResult.Definition.Stages[i].Name, parsedFromJSON.Definition.Stages[i].Name)
		assert.Equal(t, parseResult.Definition.Stages[i].Type, parsedFromJSON.Definition.Stages[i].Type)
		assert.Equal(t, parseResult.Definition.Stages[i].DependsOn, parsedFromJSON.Definition.Stages[i].DependsOn)
	}
}

func TestToYAML_Roundtrip(t *testing.T) {
	parser := NewParser()

	jsonData, err := os.ReadFile("testdata/valid_pipeline.json")
	assert.NoError(t, err)

	parseResult, err := parser.ParseJSON(jsonData)
	assert.NoError(t, err)
	assert.NotNil(t, parseResult.Definition)

	yamlData, err := parser.ToYAML(parseResult.Definition)
	assert.NoError(t, err)
	assert.NotEmpty(t, yamlData)

	parsedFromYAML, err := parser.ParseYAML(yamlData)
	assert.NoError(t, err)
	assert.NotNil(t, parsedFromYAML.Definition)

	assert.Equal(t, parseResult.Definition.Name, parsedFromYAML.Definition.Name)
	assert.Equal(t, parseResult.Definition.Version, parsedFromYAML.Definition.Version)
	assert.Equal(t, parseResult.Definition.Description, parsedFromYAML.Definition.Description)
	assert.Len(t, parsedFromYAML.Definition.Stages, len(parseResult.Definition.Stages))

	for i := range parseResult.Definition.Stages {
		assert.Equal(t, parseResult.Definition.Stages[i].Name, parsedFromYAML.Definition.Stages[i].Name)
		assert.Equal(t, parseResult.Definition.Stages[i].Type, parsedFromYAML.Definition.Stages[i].Type)
		assert.Equal(t, parseResult.Definition.Stages[i].DependsOn, parsedFromYAML.Definition.Stages[i].DependsOn)
	}
}
