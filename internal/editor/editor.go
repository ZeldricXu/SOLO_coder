package editor

import (
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type EditorMode string

const (
	ModeWYSIWYG EditorMode = "wysiwyg"
	ModeSource  EditorMode = "source"
)

type CursorPosition struct {
	Line   int
	Column int
	Offset int
}

type Selection struct {
	Start CursorPosition
	End   CursorPosition
	Text  string
}

type SelectionFormat struct {
	Bold      bool
	Italic    bool
	Underline bool
	Strike    bool
	Code      bool
	Heading   int
	ListType  string
	Quote     bool
	Link      bool
	HasLink   bool
	LinkURL   string
}

type EditorState struct {
	Content      string
	RenderedHTML string
	Cursor       CursorPosition
	ScrollTop    int
	IsDirty      bool
	Mode         EditorMode
	NotePath     string
	WordCount    int
	LastSavedAt  time.Time
}

type Editor struct {
	mu           sync.RWMutex
	state        *EditorState
	undoStack    *UndoStack
	autocomplete *Autocomplete
	database     *db.Database
	parser       markdown.Parser
	cfg          *config.Config
	autoSaveTimer *time.Timer
	changeCount  int
}

func New(database *db.Database, parser markdown.Parser, cfg *config.Config) *Editor {
	defaultMode := EditorMode(cfg.Editor.DefaultMode)
	if defaultMode == "" {
		defaultMode = ModeWYSIWYG
	}

	return &Editor{
		state: &EditorState{
			Mode:    defaultMode,
			IsDirty: false,
		},
		undoStack:    NewUndoStack(100),
		autocomplete: NewAutocomplete(database),
		database:     database,
		parser:       parser,
		cfg:          cfg,
	}
}

func (e *Editor) LoadNote(notePath string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	_, err := e.database.GetNoteByPath(notePath)
	if err != nil {
		return fmt.Errorf("failed to load note: %w", err)
	}

	content, err := e.loadNoteContent(notePath)
	if err != nil {
		return fmt.Errorf("failed to load note content: %w", err)
	}

	e.state.NotePath = notePath
	e.state.Content = content
	e.state.IsDirty = false
	e.state.WordCount = utils.CountWords(content)
	e.state.LastSavedAt = time.Now()
	e.state.Cursor = CursorPosition{Line: 0, Column: 0, Offset: 0}
	e.state.ScrollTop = 0

	e.renderContent()
	e.undoStack.Clear()
	e.pushUndoState()

	return nil
}

func (e *Editor) loadNoteContent(notePath string) (string, error) {
	fullPath := notePath
	if e.cfg.VaultPath != "" && !utils.IsMarkdownFile(notePath) {
		fullPath = fmt.Sprintf("%s/%s.md", e.cfg.VaultPath, notePath)
	}

	data, err := os.ReadFile(fullPath)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (e *Editor) GetState() *EditorState {
	e.mu.RLock()
	defer e.mu.RUnlock()

	state := *e.state
	return &state
}

func (e *Editor) SetMode(mode EditorMode) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.state.Mode == mode {
		return
	}

	e.state.Mode = mode
	e.renderContent()
}

func (e *Editor) ToggleMode() EditorMode {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.state.Mode == ModeWYSIWYG {
		e.state.Mode = ModeSource
	} else {
		e.state.Mode = ModeWYSIWYG
	}
	e.renderContent()
	return e.state.Mode
}

func (e *Editor) SetContent(content string) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.state.Content == content {
		return
	}

	e.pushUndoState()
	e.state.Content = content
	e.state.IsDirty = true
	e.state.WordCount = utils.CountWords(content)
	e.renderContent()
	e.scheduleAutoSave()
	e.changeCount++
}

func (e *Editor) InsertText(text string, position CursorPosition) CursorPosition {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.pushUndoState()

	content := []rune(e.state.Content)
	offset := position.Offset
	if offset > len(content) {
		offset = len(content)
	}

	newContent := make([]rune, 0, len(content)+len([]rune(text)))
	newContent = append(newContent, content[:offset]...)
	newContent = append(newContent, []rune(text)...)
	newContent = append(newContent, content[offset:]...)

	e.state.Content = string(newContent)
	e.state.IsDirty = true
	e.state.WordCount = utils.CountWords(e.state.Content)

	newOffset := offset + len([]rune(text))
	e.state.Cursor = e.offsetToPosition(newOffset)

	e.renderContent()
	e.scheduleAutoSave()
	e.changeCount++

	return e.state.Cursor
}

func (e *Editor) DeleteText(start, end CursorPosition) CursorPosition {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.pushUndoState()

	content := []rune(e.state.Content)
	startOffset := start.Offset
	endOffset := end.Offset

	if startOffset > endOffset {
		startOffset, endOffset = endOffset, startOffset
	}

	if startOffset < 0 {
		startOffset = 0
	}
	if endOffset > len(content) {
		endOffset = len(content)
	}

	newContent := make([]rune, 0, len(content)-(endOffset-startOffset))
	newContent = append(newContent, content[:startOffset]...)
	newContent = append(newContent, content[endOffset:]...)

	e.state.Content = string(newContent)
	e.state.IsDirty = true
	e.state.WordCount = utils.CountWords(e.state.Content)
	e.state.Cursor = e.offsetToPosition(startOffset)

	e.renderContent()
	e.scheduleAutoSave()
	e.changeCount++

	return e.state.Cursor
}

func (e *Editor) SetCursor(position CursorPosition) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.state.Cursor = position
}

func (e *Editor) SetSelection(selection Selection) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.state.Cursor = selection.End
}

func (e *Editor) GetSelectionFormat(selection Selection) *SelectionFormat {
	e.mu.RLock()
	defer e.mu.RUnlock()

	format := &SelectionFormat{}
	if selection.Text == "" {
		return format
	}

	text := selection.Text
	lines := splitLines(text)

	if len(lines) > 0 {
		firstLine := lines[0]
		if len(firstLine) >= 1 && firstLine[0] == '#' {
			level := 0
			for level < len(firstLine) && level < 6 && firstLine[level] == '#' {
				level++
			}
			if level < len(firstLine) && firstLine[level] == ' ' {
				format.Heading = level
			}
		}
	}

	isBold := true
	isItalic := true
	isCode := true
	isStrike := true
	isQuote := true
	isUnorderedList := true
	isOrderedList := true

	for _, line := range lines {
		trimmed := line
		if stringsHasPrefix(trimmed, "> ") {
			trimmed = trimmed[2:]
		}
		if stringsHasPrefix(trimmed, "- ") || stringsHasPrefix(trimmed, "* ") || stringsHasPrefix(trimmed, "+ ") {
			trimmed = trimmed[2:]
		}
		if matchOrderedList(trimmed) {
			trimmed = skipOrderedList(trimmed)
		}

		if !stringsHasPrefix(line, "> ") {
			isQuote = false
		}
		if !stringsHasPrefix(line, "- ") && !stringsHasPrefix(line, "* ") && !stringsHasPrefix(line, "+ ") {
			isUnorderedList = false
		}
		if !matchOrderedList(line) {
			isOrderedList = false
		}

		if !(stringsHasPrefix(trimmed, "**") && stringsHasSuffix(trimmed, "**") && len(trimmed) > 4) {
			isBold = false
		}
		if !(stringsHasPrefix(trimmed, "*") && stringsHasSuffix(trimmed, "*") && len(trimmed) > 2 && !(stringsHasPrefix(trimmed, "**"))) {
			isItalic = false
		}
		if !(stringsHasPrefix(trimmed, "`") && stringsHasSuffix(trimmed, "`") && len(trimmed) > 2) {
			isCode = false
		}
		if !(stringsHasPrefix(trimmed, "~~") && stringsHasSuffix(trimmed, "~~") && len(trimmed) > 4) {
			isStrike = false
		}
	}

	format.Bold = isBold
	format.Italic = isItalic
	format.Code = isCode
	format.Strike = isStrike
	format.Quote = isQuote

	if isUnorderedList {
		format.ListType = "unordered"
	} else if isOrderedList {
		format.ListType = "ordered"
	}

	if containsLink(text) {
		format.HasLink = true
		format.Link = true
		format.LinkURL = extractLinkURL(text)
	}

	return format
}

func (e *Editor) SetScrollTop(scrollTop int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.state.ScrollTop = scrollTop
}

func (e *Editor) Save() error {
	e.mu.Lock()
	defer e.mu.Unlock()

	return e.save()
}

func (e *Editor) save() error {
	if e.state.NotePath == "" {
		return fmt.Errorf("no note loaded")
	}

	if !e.state.IsDirty {
		return nil
	}

	notePath := e.state.NotePath
	if e.cfg.VaultPath != "" && !stringsHasSuffix(notePath, ".md") {
		notePath = fmt.Sprintf("%s/%s.md", e.cfg.VaultPath, notePath)
	}

	err := os.WriteFile(notePath, []byte(e.state.Content), 0644)
	if err != nil {
		return fmt.Errorf("failed to save note: %w", err)
	}

	hash := utils.Hash(e.state.Content)
	wordCount := utils.CountWords(e.state.Content)
	title := utils.ExtractTitle(e.state.Content)

	note, err := e.database.GetNoteByPath(e.state.NotePath)
	if err != nil {
		note = &models.Note{
			Path:      e.state.NotePath,
			Title:     title,
			Hash:      hash,
			WordCount: wordCount,
		}
	} else {
		note.Title = title
		note.Hash = hash
		note.WordCount = wordCount
	}

	result, err := e.parser.Parse(e.state.Content, e.state.NotePath)
	if err == nil {
		tags := make([]models.Tag, 0, len(result.Tags))
		for _, tagName := range result.Tags {
			tags = append(tags, models.Tag{Name: tagName})
		}
		note.Tags = tags
	}

	err = e.database.SaveNote(note)
	if err != nil {
		return fmt.Errorf("failed to save note to database: %w", err)
	}

	e.state.IsDirty = false
	e.state.LastSavedAt = time.Now()
	e.changeCount = 0

	return nil
}

func (e *Editor) scheduleAutoSave() {
	if !e.cfg.Editor.AutoSave {
		return
	}

	if e.autoSaveTimer != nil {
		e.autoSaveTimer.Stop()
	}

	interval := time.Duration(e.cfg.Editor.AutoSaveInterval) * time.Second
	e.autoSaveTimer = time.AfterFunc(interval, func() {
		e.mu.Lock()
		defer e.mu.Unlock()
		e.save()
	})
}

func (e *Editor) Undo() bool {
	e.mu.Lock()
	defer e.mu.Unlock()

	state := e.undoStack.Undo()
	if state == nil {
		return false
	}

	e.state.Content = state.Content
	e.state.Cursor = state.Cursor
	e.state.IsDirty = true
	e.state.WordCount = utils.CountWords(e.state.Content)
	e.renderContent()

	return true
}

func (e *Editor) Redo() bool {
	e.mu.Lock()
	defer e.mu.Unlock()

	state := e.undoStack.Redo()
	if state == nil {
		return false
	}

	e.state.Content = state.Content
	e.state.Cursor = state.Cursor
	e.state.IsDirty = true
	e.state.WordCount = utils.CountWords(e.state.Content)
	e.renderContent()

	return true
}

func (e *Editor) CanUndo() bool {
	return e.undoStack.CanUndo()
}

func (e *Editor) CanRedo() bool {
	return e.undoStack.CanRedo()
}

func (e *Editor) pushUndoState() {
	e.undoStack.Push(&UndoState{
		Content: e.state.Content,
		Cursor:  e.state.Cursor,
	})
}

func (e *Editor) renderContent() {
	if e.state.Mode == ModeWYSIWYG {
		result, err := e.parser.Parse(e.state.Content, e.state.NotePath)
		if err == nil {
			e.state.RenderedHTML = result.HTML
		}
	} else {
		e.state.RenderedHTML = ""
	}
}

func (e *Editor) offsetToPosition(offset int) CursorPosition {
	content := []rune(e.state.Content)
	if offset > len(content) {
		offset = len(content)
	}
	if offset < 0 {
		offset = 0
	}

	line := 0
	column := 0

	for i := 0; i < offset; i++ {
		if content[i] == '\n' {
			line++
			column = 0
		} else {
			column++
		}
	}

	return CursorPosition{
		Line:   line,
		Column: column,
		Offset: offset,
	}
}

func (e *Editor) PositionToCursor(line, column int) CursorPosition {
	e.mu.RLock()
	defer e.mu.RUnlock()

	content := []rune(e.state.Content)
	offset := 0
	currentLine := 0
	currentColumn := 0

	for i := 0; i < len(content); i++ {
		if currentLine == line && currentColumn == column {
			offset = i
			break
		}
		if content[i] == '\n' {
			currentLine++
			currentColumn = 0
		} else {
			currentColumn++
		}
		offset = i + 1
	}

	return CursorPosition{
		Line:   line,
		Column: column,
		Offset: offset,
	}
}

func (e *Editor) GetAutocomplete() *Autocomplete {
	return e.autocomplete
}

func (e *Editor) GetWordCount() int {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.state.WordCount
}

func (e *Editor) IsDirty() bool {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.state.IsDirty
}

func (e *Editor) GetRenderedHTML() string {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.state.RenderedHTML
}

func splitLines(s string) []string {
	var lines []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '\n' {
			lines = append(lines, s[start:i])
			start = i + 1
		}
	}
	if start <= len(s) {
		lines = append(lines, s[start:])
	}
	return lines
}

func stringsHasPrefix(s, prefix string) bool {
	return len(s) >= len(prefix) && s[:len(prefix)] == prefix
}

func stringsHasSuffix(s, suffix string) bool {
	return len(s) >= len(suffix) && s[len(s)-len(suffix):] == suffix
}

func matchOrderedList(s string) bool {
	if len(s) < 3 {
		return false
	}
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	if i == 0 || i >= len(s) {
		return false
	}
	return s[i] == '.' && i+1 < len(s) && s[i+1] == ' '
}

func skipOrderedList(s string) string {
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	if i+2 <= len(s) {
		return s[i+2:]
	}
	return s
}

func containsLink(s string) bool {
	depth := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '[' {
			depth++
		} else if s[i] == ']' {
			depth--
			if depth == 0 && i+1 < len(s) && s[i+1] == '(' {
				return true
			}
		}
	}
	return false
}

func extractLinkURL(s string) string {
	depth := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '[' {
			depth++
		} else if s[i] == ']' {
			depth--
			if depth == 0 && i+1 < len(s) && s[i+1] == '(' {
				start := i + 2
				end := start
				for end < len(s) && s[end] != ')' {
					end++
				}
				return s[start:end]
			}
		}
	}
	return ""
}
