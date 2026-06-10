package dailynote

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type TodoItem struct {
	ID        uint   `json:"id"`
	Text      string `json:"text"`
	Done      bool   `json:"done"`
	NotePath  string `json:"note_path"`
	NoteTitle string `json:"note_title"`
	LineNum   int    `json:"line_num"`
	Priority  string `json:"priority"`
}

type TodoExtractor struct {
	database *db.Database
	vaultPath string
}

func NewTodoExtractor(database *db.Database, vaultPath string) *TodoExtractor {
	return &TodoExtractor{
		database:  database,
		vaultPath: vaultPath,
	}
}

func (te *TodoExtractor) ExtractFromContent(content string) []TodoItem {
	var todos []TodoItem

	re := regexp.MustCompile(`^\s*[-*+]\s+\[([ xX])\]\s+(.*)$`)
	priorityRe := regexp.MustCompile(`\[#([A-C])\]`)

	lines := strings.Split(content, "\n")
	for i, line := range lines {
		matches := re.FindStringSubmatch(line)
		if len(matches) < 3 {
			continue
		}

		status := matches[1]
		text := strings.TrimSpace(matches[2])

		done := status == "x" || status == "X"

		priority := ""
		if priorityMatches := priorityRe.FindStringSubmatch(text); len(priorityMatches) > 1 {
			priority = priorityMatches[1]
			text = priorityRe.ReplaceAllString(text, "")
			text = strings.TrimSpace(text)
		}

		todos = append(todos, TodoItem{
			Text:     text,
			Done:     done,
			LineNum:  i + 1,
			Priority: priority,
		})
	}

	return todos
}

func (te *TodoExtractor) ExtractFromNote(notePath string) ([]TodoItem, error) {
	absPath := utils.NormalizePath(notePath, te.vaultPath)

	content, err := os.ReadFile(absPath)
	if err != nil {
		return nil, err
	}

	todos := te.ExtractFromContent(string(content))
	title := utils.ExtractTitle(string(content))

	for i := range todos {
		todos[i].NotePath = notePath
		todos[i].NoteTitle = title
	}

	return todos, nil
}

func (te *TodoExtractor) ExtractAll(onlyPending bool) ([]TodoItem, error) {
	allNotes, err := te.database.GetAllNotes()
	if err != nil {
		return nil, err
	}

	var allTodos []TodoItem

	for _, note := range allNotes {
		notePath := note.Path

		absPath := utils.NormalizePath(notePath, te.vaultPath)
		if !utils.IsMarkdownFile(absPath) {
			continue
		}

		todos, err := te.ExtractFromNote(notePath)
		if err != nil {
			continue
		}

		for _, todo := range todos {
			if onlyPending && todo.Done {
				continue
			}
			allTodos = append(allTodos, todo)
		}
	}

	return allTodos, nil
}

func (te *TodoExtractor) FormatTodos(todos []TodoItem) string {
	if len(todos) == 0 {
		return ""
	}

	var builder strings.Builder

	builder.WriteString("## 待办事项汇总\n\n")

	pendingTodos := []TodoItem{}
	doneTodos := []TodoItem{}

	for _, todo := range todos {
		if todo.Done {
			doneTodos = append(doneTodos, todo)
		} else {
			pendingTodos = append(pendingTodos, todo)
		}
	}

	if len(pendingTodos) > 0 {
		builder.WriteString("### 未完成\n\n")
		for _, todo := range pendingTodos {
			prefix := "- [ ] "
			if todo.Priority != "" {
				prefix = "- [ ] [#" + todo.Priority + "] "
			}
			builder.WriteString(prefix + todo.Text)
			if todo.NoteTitle != "" {
				builder.WriteString(" (来自: " + todo.NoteTitle + ")")
			}
			builder.WriteString("\n")
		}
		builder.WriteString("\n")
	}

	if len(doneTodos) > 0 {
		builder.WriteString("### 已完成\n\n")
		for _, todo := range doneTodos {
			builder.WriteString("- [x] " + todo.Text)
			if todo.NoteTitle != "" {
				builder.WriteString(" (来自: " + todo.NoteTitle + ")")
			}
			builder.WriteString("\n")
		}
	}

	return builder.String()
}

func (te *TodoExtractor) GetPendingCount() (int, error) {
	todos, err := te.ExtractAll(true)
	if err != nil {
		return 0, err
	}
	return len(todos), nil
}

func (te *TodoExtractor) GetByNotePath(notePath string) ([]TodoItem, error) {
	return te.ExtractFromNote(notePath)
}

func (te *TodoExtractor) ScanDirectory(dirPath string) ([]TodoItem, error) {
	var allTodos []TodoItem

	err := filepath.Walk(dirPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		if info.IsDir() {
			return nil
		}

		if !utils.IsMarkdownFile(path) {
			return nil
		}

		todos, err := te.ExtractFromNote(path)
		if err != nil {
			return nil
		}

		allTodos = append(allTodos, todos...)

		return nil
	})

	if err != nil {
		return nil, err
	}

	return allTodos, nil
}

func (te *TodoExtractor) GroupByPriority(todos []TodoItem) map[string][]TodoItem {
	groups := make(map[string][]TodoItem)

	for _, todo := range todos {
		priority := todo.Priority
		if priority == "" {
			priority = "normal"
		}
		groups[priority] = append(groups[priority], todo)
	}

	return groups
}

func (te *TodoExtractor) GroupByNote(todos []TodoItem) map[string][]TodoItem {
	groups := make(map[string][]TodoItem)

	for _, todo := range todos {
		key := todo.NotePath
		groups[key] = append(groups[key], todo)
	}

	return groups
}

var _ = models.Note{}
