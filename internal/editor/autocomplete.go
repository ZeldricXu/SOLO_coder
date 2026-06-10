package editor

import (
	"strings"

	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
)

type AutocompleteType string

const (
	AutocompleteLink AutocompleteType = "link"
	AutocompleteTag  AutocompleteType = "tag"
)

type AutocompleteItem struct {
	Type  AutocompleteType
	Value string
	Label string
	Icon  string
}

type AutocompleteResult struct {
	Type      AutocompleteType
	Query     string
	Items     []AutocompleteItem
	StartPos  int
	EndPos    int
}

type Autocomplete struct {
	database *db.Database
}

func NewAutocomplete(database *db.Database) *Autocomplete {
	return &Autocomplete{
		database: database,
	}
}

func (a *Autocomplete) GetCompletions(content string, cursorOffset int) (*AutocompleteResult, error) {
	result := &AutocompleteResult{}

	contentRunes := []rune(content)
	if cursorOffset > len(contentRunes) {
		cursorOffset = len(contentRunes)
	}

	linkStart := a.findWikiLinkStart(contentRunes, cursorOffset)
	if linkStart >= 0 {
		result.Type = AutocompleteLink
		result.StartPos = linkStart
		result.EndPos = cursorOffset
		result.Query = string(contentRunes[linkStart+2 : cursorOffset])
		items, err := a.getLinkCompletions(result.Query)
		if err != nil {
			return nil, err
		}
		result.Items = items
		return result, nil
	}

	tagStart := a.findTagStart(contentRunes, cursorOffset)
	if tagStart >= 0 {
		result.Type = AutocompleteTag
		result.StartPos = tagStart
		result.EndPos = cursorOffset
		result.Query = string(contentRunes[tagStart+1 : cursorOffset])
		items, err := a.getTagCompletions(result.Query)
		if err != nil {
			return nil, err
		}
		result.Items = items
		return result, nil
	}

	return nil, nil
}

func (a *Autocomplete) findWikiLinkStart(content []rune, cursorOffset int) int {
	for i := cursorOffset - 1; i >= 1; i-- {
		if content[i] == '[' && content[i-1] == '[' {
			for j := i + 1; j < cursorOffset; j++ {
				if content[j] == ']' || content[j] == '\n' || content[j] == '|' {
					return -1
				}
			}
			return i - 1
		}
		if content[i] == '\n' || content[i] == ' ' || content[i] == '\t' {
			break
		}
	}
	return -1
}

func (a *Autocomplete) findTagStart(content []rune, cursorOffset int) int {
	for i := cursorOffset - 1; i >= 0; i-- {
		if content[i] == '#' {
			if i > 0 && (content[i-1] == ' ' || content[i-1] == '\n' || content[i-1] == '\t') {
			} else if i > 0 {
				if (content[i-1] >= 'a' && content[i-1] <= 'z') ||
					(content[i-1] >= 'A' && content[i-1] <= 'Z') ||
					(content[i-1] >= '0' && content[i-1] <= '9') ||
					content[i-1] == '_' {
					continue
				}
			}

			for j := i + 1; j < cursorOffset; j++ {
				ch := content[j]
				if !(ch >= 'a' && ch <= 'z') &&
					!(ch >= 'A' && ch <= 'Z') &&
					!(ch >= '0' && ch <= '9') &&
					ch != '_' && ch != '/' && ch != '-' {
					return -1
				}
			}

			if i+1 < cursorOffset {
				return i
			}
		}
		if content[i] == '\n' || content[i] == ' ' || content[i] == '\t' {
			break
		}
	}
	return -1
}

func (a *Autocomplete) getLinkCompletions(query string) ([]AutocompleteItem, error) {
	notes, err := a.database.GetAllNotes()
	if err != nil {
		return nil, err
	}

	items := make([]AutocompleteItem, 0, 10)
	queryLower := strings.ToLower(query)

	for _, note := range notes {
		if len(items) >= 10 {
			break
		}

		titleLower := strings.ToLower(note.Title)
		pathLower := strings.ToLower(note.Path)

		if query == "" ||
			strings.Contains(titleLower, queryLower) ||
			strings.Contains(pathLower, queryLower) {
			items = append(items, AutocompleteItem{
				Type:  AutocompleteLink,
				Value: note.Path,
				Label: note.Title,
				Icon:  "📄",
			})
		}
	}

	return items, nil
}

func (a *Autocomplete) getTagCompletions(query string) ([]AutocompleteItem, error) {
	tags, err := a.database.SearchTags(query, 10)
	if err != nil {
		return nil, err
	}

	items := make([]AutocompleteItem, 0, len(tags))
	for _, tag := range tags {
		items = append(items, AutocompleteItem{
			Type:  AutocompleteTag,
			Value: tag.Name,
			Label: tag.Name,
			Icon:  "🏷️",
		})
	}

	return items, nil
}

func (a *Autocomplete) ApplyCompletion(content string, cursorOffset int, item AutocompleteItem) (string, CursorPosition) {
	contentRunes := []rune(content)
	if cursorOffset > len(contentRunes) {
		cursorOffset = len(contentRunes)
	}

	var startPos int
	var replacement string

	switch item.Type {
	case AutocompleteLink:
		startPos = a.findWikiLinkStart(contentRunes, cursorOffset)
		if startPos < 0 {
			return content, CursorPosition{Offset: cursorOffset}
		}
		replacement = "[[" + item.Value + "]]"
	case AutocompleteTag:
		startPos = a.findTagStart(contentRunes, cursorOffset)
		if startPos < 0 {
			return content, CursorPosition{Offset: cursorOffset}
		}
		replacement = "#" + item.Value
	default:
		return content, CursorPosition{Offset: cursorOffset}
	}

	newContentRunes := make([]rune, 0, len(contentRunes)-cursorOffset+startPos+len([]rune(replacement)))
	newContentRunes = append(newContentRunes, contentRunes[:startPos]...)
	newContentRunes = append(newContentRunes, []rune(replacement)...)
	newContentRunes = append(newContentRunes, contentRunes[cursorOffset:]...)

	newOffset := startPos + len([]rune(replacement))

	return string(newContentRunes), CursorPosition{
		Offset: newOffset,
		Line:   0,
		Column: 0,
	}
}

func (a *Autocomplete) RefreshNotes() error {
	return nil
}

func (a *Autocomplete) RefreshTags() error {
	return nil
}

func (a *Autocomplete) SearchNotes(query string, limit int) ([]models.Note, error) {
	notes, err := a.database.GetAllNotes()
	if err != nil {
		return nil, err
	}

	result := make([]models.Note, 0, limit)
	queryLower := strings.ToLower(query)

	for _, note := range notes {
		if len(result) >= limit {
			break
		}

		titleLower := strings.ToLower(note.Title)
		pathLower := strings.ToLower(note.Path)

		if strings.Contains(titleLower, queryLower) || strings.Contains(pathLower, queryLower) {
			result = append(result, *note)
		}
	}

	return result, nil
}

func (a *Autocomplete) SearchTags(query string, limit int) ([]models.Tag, error) {
	return a.database.SearchTags(query, limit)
}
