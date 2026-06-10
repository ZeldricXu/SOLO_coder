package tags

import (
	"database/sql"
	"errors"
	"fmt"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
)

type TagManager struct {
	db     *db.Database
	config *config.Config
}

func NewTagManager(database *db.Database, cfg *config.Config) *TagManager {
	return &TagManager{
		db:     database,
		config: cfg,
	}
}

func (tm *TagManager) CreateTag(name string, parentID *uint, color string) (*models.Tag, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("tag name cannot be empty")
	}

	if parentID != nil {
		_, err := tm.db.GetTagByID(*parentID)
		if err != nil {
			return nil, fmt.Errorf("parent tag not found: %w", err)
		}
	}

	if color == "" {
		color = "#6366f1"
	}

	existing, err := tm.db.GetTagByName(name)
	if err == nil && existing != nil {
		return nil, errors.New("tag already exists")
	}
	if err != nil && err != sql.ErrNoRows {
		return nil, err
	}

	tag := &models.Tag{
		Name:     name,
		ParentID: parentID,
		Color:    color,
	}

	if err := tm.db.SaveTag(tag); err != nil {
		return nil, err
	}

	return tag, nil
}

func (tm *TagManager) GetTag(id uint) (*models.Tag, error) {
	return tm.db.GetTagByID(id)
}

func (tm *TagManager) GetTagByName(name string) (*models.Tag, error) {
	return tm.db.GetTagByName(name)
}

func (tm *TagManager) UpdateTag(id uint, name string, parentID *uint, color string) (*models.Tag, error) {
	tag, err := tm.db.GetTagByID(id)
	if err != nil {
		return nil, err
	}

	if name != "" {
		name = strings.TrimSpace(name)
		if name == "" {
			return nil, errors.New("tag name cannot be empty")
		}
		tag.Name = name
	}

	if parentID != nil {
		if *parentID == id {
			return nil, errors.New("tag cannot be its own parent")
		}
		_, err := tm.db.GetTagByID(*parentID)
		if err != nil {
			return nil, fmt.Errorf("parent tag not found: %w", err)
		}
		if tm.isDescendant(id, *parentID) {
			return nil, errors.New("cannot set descendant as parent")
		}
		tag.ParentID = parentID
	}

	if color != "" {
		tag.Color = color
	}

	if err := tm.db.SaveTag(tag); err != nil {
		return nil, err
	}

	return tag, nil
}

func (tm *TagManager) DeleteTag(id uint) error {
	_, err := tm.db.GetTagByID(id)
	if err != nil {
		return err
	}
	return tm.db.DeleteTag(id)
}

func (tm *TagManager) RenameTag(id uint, newName string) (*models.Tag, error) {
	return tm.UpdateTag(id, newName, nil, "")
}

func (tm *TagManager) ListTags(parentID *uint) ([]models.Tag, error) {
	allTags, err := tm.db.GetAllTags()
	if err != nil {
		return nil, err
	}

	if parentID == nil {
		var rootTags []models.Tag
		for _, tag := range allTags {
			if tag.ParentID == nil {
				rootTags = append(rootTags, tag)
			}
		}
		return rootTags, nil
	}

	var childTags []models.Tag
	for _, tag := range allTags {
		if tag.ParentID != nil && *tag.ParentID == *parentID {
			childTags = append(childTags, tag)
		}
	}
	return childTags, nil
}

func (tm *TagManager) GetTagTree() ([]models.Tag, error) {
	allTags, err := tm.db.GetAllTags()
	if err != nil {
		return nil, err
	}

	tagMap := make(map[uint]*models.Tag)
	for i := range allTags {
		tagMap[allTags[i].ID] = &allTags[i]
	}

	var roots []models.Tag
	for i := range allTags {
		tag := &allTags[i]
		if tag.ParentID == nil {
			roots = append(roots, *tag)
		} else {
			parent := tagMap[*tag.ParentID]
			if parent != nil {
				parent.Children = append(parent.Children, *tag)
			}
		}
	}

	return roots, nil
}

func (tm *TagManager) GetTagPath(id uint) ([]models.Tag, error) {
	var path []models.Tag

	current, err := tm.db.GetTagByID(id)
	if err != nil {
		return nil, err
	}

	for current != nil {
		path = append([]models.Tag{*current}, path...)
		if current.ParentID == nil {
			break
		}
		current, err = tm.db.GetTagByID(*current.ParentID)
		if err != nil {
			return nil, err
		}
	}

	return path, nil
}

func (tm *TagManager) GetDescendantTags(id uint) ([]models.Tag, error) {
	var descendants []models.Tag

	children, err := tm.ListTags(&id)
	if err != nil {
		return nil, err
	}

	for _, child := range children {
		descendants = append(descendants, child)
		subDescendants, err := tm.GetDescendantTags(child.ID)
		if err != nil {
			return nil, err
		}
		descendants = append(descendants, subDescendants...)
	}

	return descendants, nil
}

func (tm *TagManager) isDescendant(ancestorID, descendantID uint) bool {
	descendants, err := tm.GetDescendantTags(ancestorID)
	if err != nil {
		return false
	}
	for _, d := range descendants {
		if d.ID == descendantID {
			return true
		}
	}
	return false
}

func (tm *TagManager) Autocomplete(prefix string, limit int) ([]models.Tag, error) {
	if limit <= 0 {
		limit = 10
	}
	return tm.db.SearchTags(prefix, limit)
}

func (tm *TagManager) GetAllTags() ([]models.Tag, error) {
	return tm.db.GetAllTags()
}

func (tm *TagManager) GetTagStats() ([]models.TagStats, error) {
	allTags, err := tm.db.GetAllTags()
	if err != nil {
		return nil, err
	}

	var stats []models.TagStats
	for _, tag := range allTags {
		count, err := tm.db.GetTagNoteCount(tag.ID)
		if err != nil {
			return nil, err
		}
		stats = append(stats, models.TagStats{
			TagID:     tag.ID,
			TagName:   tag.Name,
			NoteCount: count,
			Color:     tag.Color,
			IsUnused:  count == 0,
		})
	}

	return stats, nil
}

func (tm *TagManager) GetUnusedTags() ([]models.TagStats, error) {
	allStats, err := tm.GetTagStats()
	if err != nil {
		return nil, err
	}

	var unused []models.TagStats
	for _, stat := range allStats {
		if stat.IsUnused {
			unused = append(unused, stat)
		}
	}
	return unused, nil
}

func (tm *TagManager) AddTagToNote(noteID, tagID uint) error {
	_, err := tm.db.GetTagByID(tagID)
	if err != nil {
		return err
	}
	return tm.db.AddTagToNote(noteID, tagID)
}

func (tm *TagManager) RemoveTagFromNote(noteID, tagID uint) error {
	return tm.db.RemoveTagFromNote(noteID, tagID)
}

func (tm *TagManager) GetNoteTags(noteID uint) ([]models.Tag, error) {
	return tm.db.GetTagsByNote(noteID)
}

func (tm *TagManager) BatchAddTags(noteIDs []uint, tagIDs []uint) error {
	for _, noteID := range noteIDs {
		for _, tagID := range tagIDs {
			if err := tm.db.AddTagToNote(noteID, tagID); err != nil {
				return err
			}
		}
	}
	return nil
}

func (tm *TagManager) BatchRemoveTags(noteIDs []uint, tagIDs []uint) error {
	for _, noteID := range noteIDs {
		for _, tagID := range tagIDs {
			if err := tm.db.RemoveTagFromNote(noteID, tagID); err != nil {
				return err
			}
		}
	}
	return nil
}

func (tm *TagManager) BatchAddTagsByName(noteIDs []uint, tagNames []string, createIfNotExist bool) error {
	var tagIDs []uint

	for _, name := range tagNames {
		tag, err := tm.db.GetTagByName(name)
		if err != nil {
			if err == sql.ErrNoRows && createIfNotExist {
				newTag, err := tm.CreateTag(name, nil, "")
				if err != nil {
					return err
				}
				tagIDs = append(tagIDs, newTag.ID)
			} else {
				return err
			}
		} else {
			tagIDs = append(tagIDs, tag.ID)
		}
	}

	return tm.BatchAddTags(noteIDs, tagIDs)
}

func (tm *TagManager) SetNoteTags(noteID uint, tagNames []string) error {
	currentTags, err := tm.db.GetTagsByNote(noteID)
	if err != nil {
		return err
	}

	currentTagMap := make(map[string]uint)
	for _, tag := range currentTags {
		currentTagMap[tag.Name] = tag.ID
	}

	newTagSet := make(map[string]bool)
	for _, name := range tagNames {
		newTagSet[name] = true
	}

	for name, tagID := range currentTagMap {
		if !newTagSet[name] {
			if err := tm.db.RemoveTagFromNote(noteID, tagID); err != nil {
				return err
			}
		}
	}

	for name := range newTagSet {
		if _, exists := currentTagMap[name]; !exists {
			tag, err := tm.db.GetTagByName(name)
			if err != nil {
				if err == sql.ErrNoRows {
					newTag, err := tm.CreateTag(name, nil, "")
					if err != nil {
						return err
					}
					tag = newTag
				} else {
					return err
				}
			}
			if err := tm.db.AddTagToNote(noteID, tag.ID); err != nil {
				return err
			}
		}
	}

	return nil
}

func (tm *TagManager) GetTagNotes(tagID uint, includeChildren bool) ([]*models.Note, error) {
	allNotes, err := tm.db.GetAllNotes()
	if err != nil {
		return nil, err
	}

	var tagIDs []uint
	tagIDs = append(tagIDs, tagID)

	if includeChildren {
		children, err := tm.GetDescendantTags(tagID)
		if err != nil {
			return nil, err
		}
		for _, child := range children {
			tagIDs = append(tagIDs, child.ID)
		}
	}

	var result []*models.Note
	for _, note := range allNotes {
		tags, err := tm.db.GetTagsByNote(note.ID)
		if err != nil {
			return nil, err
		}

		noteTagIDs := make(map[uint]bool)
		for _, tag := range tags {
			noteTagIDs[tag.ID] = true
		}

		for _, tid := range tagIDs {
			if noteTagIDs[tid] {
				result = append(result, note)
				break
			}
		}
	}

	return result, nil
}

func (tm *TagManager) MergeTags(sourceID, targetID uint) error {
	if sourceID == targetID {
		return errors.New("cannot merge tag with itself")
	}

	_, err := tm.db.GetTagByID(sourceID)
	if err != nil {
		return err
	}
	_, err = tm.db.GetTagByID(targetID)
	if err != nil {
		return err
	}

	sourceNotes, err := tm.GetTagNotes(sourceID, false)
	if err != nil {
		return err
	}

	var noteIDs []uint
	for _, note := range sourceNotes {
		noteIDs = append(noteIDs, note.ID)
	}

	if err := tm.BatchAddTags(noteIDs, []uint{targetID}); err != nil {
		return err
	}

	children, err := tm.ListTags(&sourceID)
	if err != nil {
		return err
	}
	for _, child := range children {
		_, err := tm.UpdateTag(child.ID, "", &targetID, "")
		if err != nil {
			return err
		}
	}

	return tm.db.DeleteTag(sourceID)
}
