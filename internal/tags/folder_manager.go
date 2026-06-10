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

type FolderManager struct {
	db     *db.Database
	config *config.Config
}

func NewFolderManager(database *db.Database, cfg *config.Config) *FolderManager {
	return &FolderManager{
		db:     database,
		config: cfg,
	}
}

func (fm *FolderManager) CreateFolder(name string, parentID *uint) (*models.Folder, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("folder name cannot be empty")
	}

	if parentID != nil {
		_, err := fm.db.GetFolderByID(*parentID)
		if err != nil {
			return nil, fmt.Errorf("parent folder not found: %w", err)
		}
	}

	existing, err := fm.getFolderByName(name, parentID)
	if err == nil && existing != nil {
		return nil, errors.New("folder already exists in this parent")
	}
	if err != nil && err != sql.ErrNoRows {
		return nil, err
	}

	maxSort, err := fm.getMaxSortOrder(parentID)
	if err != nil {
		return nil, err
	}

	folder := &models.Folder{
		Name:      name,
		ParentID:  parentID,
		SortOrder: maxSort + 1,
		IsVirtual: false,
	}

	if err := fm.db.SaveFolder(folder); err != nil {
		return nil, err
	}

	return folder, nil
}

func (fm *FolderManager) GetFolder(id uint) (*models.Folder, error) {
	return fm.db.GetFolderByID(id)
}

func (fm *FolderManager) UpdateFolder(id uint, name string, parentID *uint) (*models.Folder, error) {
	folder, err := fm.db.GetFolderByID(id)
	if err != nil {
		return nil, err
	}

	if name != "" {
		name = strings.TrimSpace(name)
		if name == "" {
			return nil, errors.New("folder name cannot be empty")
		}
		folder.Name = name
	}

	if parentID != nil {
		if *parentID == id {
			return nil, errors.New("folder cannot be its own parent")
		}
		_, err := fm.db.GetFolderByID(*parentID)
		if err != nil {
			return nil, fmt.Errorf("parent folder not found: %w", err)
		}
		if fm.isDescendant(id, *parentID) {
			return nil, errors.New("cannot set descendant as parent")
		}
		folder.ParentID = parentID
	}

	if err := fm.db.SaveFolder(folder); err != nil {
		return nil, err
	}

	return folder, nil
}

func (fm *FolderManager) DeleteFolder(id uint) error {
	_, err := fm.db.GetFolderByID(id)
	if err != nil {
		return err
	}

	children, err := fm.db.GetFoldersByParent(&id)
	if err != nil {
		return err
	}
	for _, child := range children {
		if err := fm.DeleteFolder(child.ID); err != nil {
			return err
		}
	}

	return fm.db.DeleteFolder(id)
}

func (fm *FolderManager) RenameFolder(id uint, newName string) (*models.Folder, error) {
	return fm.UpdateFolder(id, newName, nil)
}

func (fm *FolderManager) MoveFolder(id, newParentID uint) (*models.Folder, error) {
	return fm.UpdateFolder(id, "", &newParentID)
}

func (fm *FolderManager) ListFolders(parentID *uint) ([]models.Folder, error) {
	return fm.db.GetFoldersByParent(parentID)
}

func (fm *FolderManager) GetFolderTree() ([]models.Folder, error) {
	allFolders, err := fm.db.GetAllFolders()
	if err != nil {
		return nil, err
	}

	folderMap := make(map[uint]*models.Folder)
	for i := range allFolders {
		folderMap[allFolders[i].ID] = &allFolders[i]
	}

	var roots []models.Folder
	for i := range allFolders {
		folder := &allFolders[i]
		if folder.ParentID == nil {
			roots = append(roots, *folder)
		} else {
			parent := folderMap[*folder.ParentID]
			if parent != nil {
				parent.Children = append(parent.Children, *folder)
			}
		}
	}

	return roots, nil
}

func (fm *FolderManager) GetFolderPath(id uint) ([]models.Folder, error) {
	var path []models.Folder

	current, err := fm.db.GetFolderByID(id)
	if err != nil {
		return nil, err
	}

	for current != nil {
		path = append([]models.Folder{*current}, path...)
		if current.ParentID == nil {
			break
		}
		current, err = fm.db.GetFolderByID(*current.ParentID)
		if err != nil {
			return nil, err
		}
	}

	return path, nil
}

func (fm *FolderManager) GetDescendantFolders(id uint) ([]models.Folder, error) {
	var descendants []models.Folder

	children, err := fm.db.GetFoldersByParent(&id)
	if err != nil {
		return nil, err
	}

	for _, child := range children {
		descendants = append(descendants, child)
		subDescendants, err := fm.GetDescendantFolders(child.ID)
		if err != nil {
			return nil, err
		}
		descendants = append(descendants, subDescendants...)
	}

	return descendants, nil
}

func (fm *FolderManager) isDescendant(ancestorID, descendantID uint) bool {
	descendants, err := fm.GetDescendantFolders(ancestorID)
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

func (fm *FolderManager) GetAllFolders() ([]models.Folder, error) {
	return fm.db.GetAllFolders()
}

func (fm *FolderManager) getFolderByName(name string, parentID *uint) (*models.Folder, error) {
	allFolders, err := fm.db.GetAllFolders()
	if err != nil {
		return nil, err
	}

	for _, folder := range allFolders {
		if folder.Name == name {
			if parentID == nil && folder.ParentID == nil {
				return &folder, nil
			}
			if parentID != nil && folder.ParentID != nil && *folder.ParentID == *parentID {
				return &folder, nil
			}
		}
	}

	return nil, sql.ErrNoRows
}

func (fm *FolderManager) getMaxSortOrder(parentID *uint) (int, error) {
	folders, err := fm.db.GetFoldersByParent(parentID)
	if err != nil {
		return 0, err
	}

	maxSort := 0
	for _, f := range folders {
		if f.SortOrder > maxSort {
			maxSort = f.SortOrder
		}
	}
	return maxSort, nil
}

func (fm *FolderManager) ReorderFolder(id uint, newPosition int) error {
	folder, err := fm.db.GetFolderByID(id)
	if err != nil {
		return err
	}

	siblings, err := fm.db.GetFoldersByParent(folder.ParentID)
	if err != nil {
		return err
	}

	if newPosition < 0 {
		newPosition = 0
	}
	if newPosition >= len(siblings) {
		newPosition = len(siblings) - 1
	}

	type folderSort struct {
		id        uint
		sortOrder int
	}

	var items []folderSort
	for _, s := range siblings {
		if s.ID != id {
			items = append(items, folderSort{id: s.ID, sortOrder: s.SortOrder})
		}
	}

	var reordered []folderSort
	reordered = append(reordered, items[:newPosition]...)
	reordered = append(reordered, folderSort{id: id, sortOrder: 0})
	reordered = append(reordered, items[newPosition:]...)

	for i, item := range reordered {
		_, err := fm.db.Exec(`
			UPDATE folders SET sort_order=? WHERE id=?
		`, i+1, item.id)
		if err != nil {
			return err
		}
	}

	return nil
}

func (fm *FolderManager) MoveNoteToFolder(noteID, folderID uint) error {
	_, err := fm.db.GetFolderByID(folderID)
	if err != nil {
		return err
	}
	return fm.db.SaveNoteFolder(noteID, folderID)
}

func (fm *FolderManager) RemoveNoteFromFolder(noteID, folderID uint) error {
	return fm.db.DeleteNoteFolder(noteID, folderID)
}

func (fm *FolderManager) GetNoteFolders(noteID uint) ([]models.Folder, error) {
	return fm.db.GetNoteFolders(noteID)
}

func (fm *FolderManager) GetFolderNotes(folderID uint, includeSubfolders bool) ([]*models.Note, error) {
	var folderIDs []uint
	folderIDs = append(folderIDs, folderID)

	if includeSubfolders {
		children, err := fm.GetDescendantFolders(folderID)
		if err != nil {
			return nil, err
		}
		for _, child := range children {
			folderIDs = append(folderIDs, child.ID)
		}
	}

	noteMap := make(map[uint]*models.Note)
	for _, fid := range folderIDs {
		notes, err := fm.db.GetFolderNotes(fid)
		if err != nil {
			return nil, err
		}
		for _, note := range notes {
			if _, exists := noteMap[note.ID]; !exists {
				noteMap[note.ID] = note
			}
		}
	}

	var result []*models.Note
	for _, note := range noteMap {
		result = append(result, note)
	}

	return result, nil
}

func (fm *FolderManager) BatchMoveNotesToFolder(noteIDs []uint, folderID uint) error {
	_, err := fm.db.GetFolderByID(folderID)
	if err != nil {
		return err
	}

	for _, noteID := range noteIDs {
		if err := fm.db.SaveNoteFolder(noteID, folderID); err != nil {
			return err
		}
	}
	return nil
}

func (fm *FolderManager) BatchRemoveNotesFromFolder(noteIDs []uint, folderID uint) error {
	for _, noteID := range noteIDs {
		if err := fm.db.DeleteNoteFolder(noteID, folderID); err != nil {
			return err
		}
	}
	return nil
}

func (fm *FolderManager) SetNoteFolders(noteID uint, folderIDs []uint) error {
	currentFolders, err := fm.db.GetNoteFolders(noteID)
	if err != nil {
		return err
	}

	currentFolderMap := make(map[uint]bool)
	for _, folder := range currentFolders {
		currentFolderMap[folder.ID] = true
	}

	newFolderSet := make(map[uint]bool)
	for _, id := range folderIDs {
		newFolderSet[id] = true
	}

	for folderID := range currentFolderMap {
		if !newFolderSet[folderID] {
			if err := fm.db.DeleteNoteFolder(noteID, folderID); err != nil {
				return err
			}
		}
	}

	for folderID := range newFolderSet {
		if !currentFolderMap[folderID] {
			if err := fm.db.SaveNoteFolder(noteID, folderID); err != nil {
				return err
			}
		}
	}

	return nil
}

func (fm *FolderManager) GetFolderNoteCount(folderID uint, includeSubfolders bool) (int, error) {
	notes, err := fm.GetFolderNotes(folderID, includeSubfolders)
	if err != nil {
		return 0, err
	}
	return len(notes), nil
}
