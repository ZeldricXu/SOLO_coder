package dailynote

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type DailyNoteManager struct {
	cfg             *config.Config
	database        *db.Database
	templateManager *TemplateManager
	todoExtractor   *TodoExtractor
	dateFormat      string
	notePath        string
}

type DailyNoteInfo struct {
	Date     time.Time `json:"date"`
	Path     string    `json:"path"`
	Title    string    `json:"title"`
	Exists   bool      `json:"exists"`
	Template string    `json:"template,omitempty"`
}

func NewDailyNoteManager(cfg *config.Config, database *db.Database) *DailyNoteManager {
	dnm := &DailyNoteManager{
		cfg:             cfg,
		database:        database,
		templateManager: NewTemplateManager(cfg),
		todoExtractor:   NewTodoExtractor(database, cfg.VaultPath),
		dateFormat:      "2006-01-02",
		notePath:        cfg.DailyNotePath,
	}
	return dnm
}

func (dnm *DailyNoteManager) SetDateFormat(format string) {
	dnm.dateFormat = format
}

func (dnm *DailyNoteManager) SetNotePath(path string) {
	dnm.notePath = path
}

func (dnm *DailyNoteManager) GetNotePath(date time.Time) string {
	filename := date.Format(dnm.dateFormat) + ".md"
	return filepath.Join(dnm.notePath, filename)
}

func (dnm *DailyNoteManager) Today() (*DailyNoteInfo, error) {
	return dnm.GetNote(time.Now())
}

func (dnm *DailyNoteManager) Yesterday() (*DailyNoteInfo, error) {
	return dnm.GetNote(time.Now().AddDate(0, 0, -1))
}

func (dnm *DailyNoteManager) Tomorrow() (*DailyNoteInfo, error) {
	return dnm.GetNote(time.Now().AddDate(0, 0, 1))
}

func (dnm *DailyNoteManager) GetNote(date time.Time) (*DailyNoteInfo, error) {
	path := dnm.GetNotePath(date)
	title := date.Format(dnm.dateFormat)
	_, err := os.Stat(path)
	exists := err == nil

	return &DailyNoteInfo{
		Date:   date,
		Path:   path,
		Title:  title,
		Exists: exists,
	}, nil
}

func (dnm *DailyNoteManager) CreateToday() (*DailyNoteInfo, string, error) {
	return dnm.CreateNote(time.Now(), "")
}

func (dnm *DailyNoteManager) CreateNote(date time.Time, templateID string) (*DailyNoteInfo, string, error) {
	path := dnm.GetNotePath(date)

	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return nil, "", err
	}

	if _, err := os.Stat(path); err == nil {
		info, _ := dnm.GetNote(date)
		content, _ := os.ReadFile(path)
		return info, string(content), nil
	}

	var content string
	var err error

	if templateID == "" {
		templateID = "builtin-daily-note"
	}

	todos, todosErr := dnm.todoExtractor.ExtractAll(true)
	todosStr := ""
	if todosErr == nil {
		todosStr = dnm.todoExtractor.FormatTodos(todos)
	}

	variables := map[string]string{
		"title":   date.Format(dnm.dateFormat),
		"date":    date.Format(dnm.dateFormat),
		"todos":   todosStr,
		"yesterday": date.AddDate(0, 0, -1).Format(dnm.dateFormat),
		"tomorrow":  date.AddDate(0, 0, 1).Format(dnm.dateFormat),
	}

	content, err = dnm.templateManager.RenderTemplate(templateID, variables)
	if err != nil {
		content = fmt.Sprintf("# %s\n\n", date.Format(dnm.dateFormat))
	}

	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		return nil, "", err
	}

	dnm.saveToDatabase(path, date.Format(dnm.dateFormat), content)

	info, _ := dnm.GetNote(date)
	info.Template = templateID

	return info, content, nil
}

func (dnm *DailyNoteManager) saveToDatabase(path, title, content string) error {
	hash := utils.Hash(content)
	wordCount := utils.CountWords(content)

	note := &models.Note{
		Path:      path,
		Title:     title,
		Hash:      hash,
		WordCount: wordCount,
	}

	existing, err := dnm.database.GetNoteByPath(path)
	if err == nil && existing != nil {
		note.ID = existing.ID
	}

	return dnm.database.SaveNote(note)
}

func (dnm *DailyNoteManager) OpenToday() (string, error) {
	info, content, err := dnm.CreateToday()
	if err != nil {
		return "", err
	}

	dnm.markOpened(info.Path)

	return content, nil
}

func (dnm *DailyNoteManager) OpenDate(date time.Time) (string, error) {
	path := dnm.GetNotePath(date)

	if _, err := os.Stat(path); os.IsNotExist(err) {
		_, content, err := dnm.CreateNote(date, "")
		if err != nil {
			return "", err
		}
		dnm.markOpened(path)
		return content, nil
	}

	content, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}

	dnm.markOpened(path)

	return string(content), nil
}

func (dnm *DailyNoteManager) markOpened(path string) {
	note, err := dnm.database.GetNoteByPath(path)
	if err == nil && note != nil {
		dnm.database.UpdateNoteOpened(note.ID)
	}
}

func (dnm *DailyNoteManager) GetDateRange(start, end time.Time) ([]*DailyNoteInfo, error) {
	var notes []*DailyNoteInfo

	for d := start; !d.After(end); d = d.AddDate(0, 0, 1) {
		info, err := dnm.GetNote(d)
		if err != nil {
			continue
		}
		notes = append(notes, info)
	}

	return notes, nil
}

func (dnm *DailyNoteManager) GetRecentNotes(count int) ([]*DailyNoteInfo, error) {
	var notes []*DailyNoteInfo

	today := time.Now()
	for i := 0; i < count; i++ {
		date := today.AddDate(0, 0, -i)
		info, err := dnm.GetNote(date)
		if err != nil {
			continue
		}
		notes = append(notes, info)
	}

	return notes, nil
}

func (dnm *DailyNoteManager) UpdateTodosInNote(date time.Time) error {
	path := dnm.GetNotePath(date)

	content, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	todos, err := dnm.todoExtractor.ExtractAll(true)
	if err != nil {
		return err
	}

	todosStr := dnm.todoExtractor.FormatTodos(todos)

	newContent := replaceTodosSection(string(content), todosStr)

	if err := os.WriteFile(path, []byte(newContent), 0644); err != nil {
		return err
	}

	hash := utils.Hash(newContent)
	wordCount := utils.CountWords(newContent)

	note, err := dnm.database.GetNoteByPath(path)
	if err == nil && note != nil {
		note.Hash = hash
		note.WordCount = wordCount
		dnm.database.SaveNote(note)
	}

	return nil
}

func replaceTodosSection(content, todosStr string) string {
	if todosStr == "" {
		todosStr = "## 待办事项汇总\n\n暂无待办事项\n"
	}

	startMarker := "## 待办事项汇总"
	endMarker := "## "

	startIdx := indexOf(content, startMarker)
	if startIdx == -1 {
		return content
	}

	afterStart := content[startIdx+len(startMarker):]
	endIdx := indexOfNth(afterStart, endMarker, 1)

	if endIdx == -1 {
		return content[:startIdx] + todosStr
	}

	endAbsIdx := startIdx + len(startMarker) + endIdx

	return content[:startIdx] + todosStr + content[endAbsIdx:]
}

func indexOf(s, substr string) int {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return i
		}
	}
	return -1
}

func indexOfNth(s, substr string, n int) int {
	count := 0
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			count++
			if count == n {
				return i
			}
		}
	}
	return -1
}

func (dnm *DailyNoteManager) GetTemplateManager() *TemplateManager {
	return dnm.templateManager
}

func (dnm *DailyNoteManager) GetTodoExtractor() *TodoExtractor {
	return dnm.todoExtractor
}

func (dnm *DailyNoteManager) RefreshTodos() (int, error) {
	todos, err := dnm.todoExtractor.ExtractAll(true)
	if err != nil {
		return 0, err
	}

	today := time.Now()
	dnm.UpdateTodosInNote(today)

	return len(todos), nil
}

func (dnm *DailyNoteManager) NoteExists(date time.Time) bool {
	path := dnm.GetNotePath(date)
	_, err := os.Stat(path)
	return err == nil
}

func (dnm *DailyNoteManager) GetStats() (map[string]interface{}, error) {
	stats := make(map[string]interface{})

	pendingTodos, err := dnm.todoExtractor.ExtractAll(true)
	if err != nil {
		return nil, err
	}
	stats["pending_todos"] = len(pendingTodos)

	totalNotes := 0
	monthCount := 0
	now := time.Now()
	thisMonth := now.Format("2006-01")

	for i := 0; i < 365; i++ {
		date := now.AddDate(0, 0, -i)
		if dnm.NoteExists(date) {
			totalNotes++
			if date.Format("2006-01") == thisMonth {
				monthCount++
			}
		}
	}

	stats["total_notes"] = totalNotes
	stats["month_notes"] = monthCount
	stats["streak"] = dnm.calculateStreak()

	return stats, nil
}

func (dnm *DailyNoteManager) calculateStreak() int {
	streak := 0
	today := time.Now()

	for i := 0; i < 365; i++ {
		date := today.AddDate(0, 0, -i)
		if dnm.NoteExists(date) {
			streak++
		} else {
			if i == 0 {
				continue
			}
			break
		}
	}

	return streak
}
