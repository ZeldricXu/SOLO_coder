package dailynote

import (
	"strings"
	"testing"
)

func TestScriptTemplate_SimpleExpression(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	result, err := engine.Render("<%= 1 + 2 %>", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	expected := "3"
	if result != expected {
		t.Errorf("expected %q, got %q", expected, result)
	}
}

func TestScriptTemplate_ContextVariable(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	ctx := &TemplateContext{
		Notes: NotesContext{
			Total:  42,
			Recent: []RecentNote{},
			ByTag:  map[string]int{},
		},
		Tags: TagsContext{
			All:   []TagInfo{},
			Cloud: []TagCloudItem{},
		},
		RecentFiles: []RecentNote{},
		Date: DateContext{
			Now:      "2026-06-10",
			Weekday:  "星期二",
		},
		Custom:  map[string]string{},
		Weather: "晴",
	}
	engine.InjectContext(ctx)

	result, err := engine.Render("<%= context.notes.total %>", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if result != "42" {
		t.Errorf("expected %q, got %q", "42", result)
	}
}

func TestScriptTemplate_Loop(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	template := "<% for(var i=0; i<3; i++) { %>item<%= i %>\n<% } %>"
	result, err := engine.Render(template, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !strings.Contains(result, "item0") {
		t.Errorf("expected result to contain 'item0', got %q", result)
	}
	if !strings.Contains(result, "item1") {
		t.Errorf("expected result to contain 'item1', got %q", result)
	}
	if !strings.Contains(result, "item2") {
		t.Errorf("expected result to contain 'item2', got %q", result)
	}
}

func TestScriptTemplate_LegacyVariables(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	customVars := map[string]string{
		"date": "2026-06-10",
	}

	result, err := engine.Render("Today is {{date}}", customVars)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	expected := "Today is 2026-06-10"
	if result != expected {
		t.Errorf("expected %q, got %q", expected, result)
	}
}

func TestScriptTemplate_ErrorHandling(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	result, err := engine.Render("<%= undefinedFunc() %>", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !strings.Contains(result, "script error") {
		t.Errorf("expected result to contain 'script error', got %q", result)
	}
}

func TestScriptTemplate_MixedLegacyAndScript(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	customVars := map[string]string{
		"name": "test",
	}

	result, err := engine.Render("Hello {{name}}, 1+1=<%= 1 + 1 %>", customVars)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !strings.Contains(result, "Hello test") {
		t.Errorf("expected result to contain 'Hello test', got %q", result)
	}
	if !strings.Contains(result, "1+1=2") {
		t.Errorf("expected result to contain '1+1=2', got %q", result)
	}
}

func TestScriptTemplate_NoScriptTags(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	customVars := map[string]string{
		"title": "MyNote",
	}

	result, err := engine.Render("# {{title}}\n\nHello world", customVars)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !strings.Contains(result, "# MyNote") {
		t.Errorf("expected result to contain '# MyNote', got %q", result)
	}
}

func TestScriptTemplate_DateContext(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	ctx := &TemplateContext{
		Notes: NotesContext{
			Total:  0,
			Recent: []RecentNote{},
			ByTag:  map[string]int{},
		},
		Tags: TagsContext{
			All:   []TagInfo{},
			Cloud: []TagCloudItem{},
		},
		RecentFiles: []RecentNote{},
		Date: DateContext{
			Now:      "2026-06-10",
			Yesterday: "2026-06-09",
			Tomorrow:  "2026-06-11",
			Weekday:   "星期二",
			Year:      "2026",
			Month:     "06",
			Day:       "10",
		},
		Custom:  map[string]string{},
		Weather: "晴",
	}
	engine.InjectContext(ctx)

	result, err := engine.Render("<%= context.date.weekday %>", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if result != "星期二" {
		t.Errorf("expected %q, got %q", "星期二", result)
	}
}

func TestScriptTemplate_WeatherContext(t *testing.T) {
	engine := NewScriptTemplateEngine(nil, nil)

	ctx := &TemplateContext{
		Notes: NotesContext{
			Total:  0,
			Recent: []RecentNote{},
			ByTag:  map[string]int{},
		},
		Tags: TagsContext{
			All:   []TagInfo{},
			Cloud: []TagCloudItem{},
		},
		RecentFiles: []RecentNote{},
		Date: DateContext{
			Now: "2026-06-10",
		},
		Custom:  map[string]string{},
		Weather: "多云",
	}
	engine.InjectContext(ctx)

	result, err := engine.Render("<%= context.weather %>", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if result != "多云" {
		t.Errorf("expected %q, got %q", "多云", result)
	}
}
