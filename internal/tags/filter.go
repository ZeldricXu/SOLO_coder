package tags

import (
	"errors"
	"fmt"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
)

const (
	LogicAND = "AND"
	LogicOR  = "OR"
)

type FilterManager struct {
	db            *db.Database
	config        *config.Config
	tagManager    *TagManager
	folderManager *FolderManager
}

func NewFilterManager(database *db.Database, cfg *config.Config) *FilterManager {
	return &FilterManager{
		db:            database,
		config:        cfg,
		tagManager:    NewTagManager(database, cfg),
		folderManager: NewFolderManager(database, cfg),
	}
}

func (fm *FilterManager) TagManager() *TagManager {
	return fm.tagManager
}

func (fm *FilterManager) FolderManager() *FolderManager {
	return fm.folderManager
}

type FilterOptions struct {
	TagIDs        []uint
	TagLogic      string
	IncludeTagChildren bool
	FolderIDs     []uint
	FolderLogic   string
	IncludeSubfolders  bool
	CombinedLogic string
}

func (fm *FilterManager) FilterNotes(opts FilterOptions) ([]*models.Note, error) {
	allNotes, err := fm.db.GetAllNotes()
	if err != nil {
		return nil, err
	}

	noteTagMap, err := fm.buildNoteTagMap(allNotes)
	if err != nil {
		return nil, err
	}

	noteFolderMap, err := fm.buildNoteFolderMap(allNotes)
	if err != nil {
		return nil, err
	}

	var result []*models.Note
	for _, note := range allNotes {
		tagMatch := fm.matchTags(note.ID, noteTagMap, opts.TagIDs, opts.TagLogic, opts.IncludeTagChildren)
		folderMatch := fm.matchFolders(note.ID, noteFolderMap, opts.FolderIDs, opts.FolderLogic, opts.IncludeSubfolders)

		var matches bool
		switch opts.CombinedLogic {
		case LogicOR:
			matches = tagMatch || folderMatch
		default:
			if len(opts.TagIDs) > 0 && len(opts.FolderIDs) > 0 {
				matches = tagMatch && folderMatch
			} else if len(opts.TagIDs) > 0 {
				matches = tagMatch
			} else if len(opts.FolderIDs) > 0 {
				matches = folderMatch
			} else {
				matches = true
			}
		}

		if matches {
			result = append(result, note)
		}
	}

	return result, nil
}

func (fm *FilterManager) buildNoteTagMap(notes []*models.Note) (map[uint]map[uint]bool, error) {
	noteTagMap := make(map[uint]map[uint]bool)

	for _, note := range notes {
		tags, err := fm.db.GetTagsByNote(note.ID)
		if err != nil {
			return nil, err
		}
		tagSet := make(map[uint]bool)
		for _, tag := range tags {
			tagSet[tag.ID] = true
		}
		noteTagMap[note.ID] = tagSet
	}

	return noteTagMap, nil
}

func (fm *FilterManager) buildNoteFolderMap(notes []*models.Note) (map[uint]map[uint]bool, error) {
	noteFolderMap := make(map[uint]map[uint]bool)

	for _, note := range notes {
		folders, err := fm.db.GetNoteFolders(note.ID)
		if err != nil {
			return nil, err
		}
		folderSet := make(map[uint]bool)
		for _, folder := range folders {
			folderSet[folder.ID] = true
		}
		noteFolderMap[note.ID] = folderSet
	}

	return noteFolderMap, nil
}

func (fm *FilterManager) matchTags(noteID uint, noteTagMap map[uint]map[uint]bool, tagIDs []uint, logic string, includeChildren bool) bool {
	if len(tagIDs) == 0 {
		return true
	}

	noteTags := noteTagMap[noteID]
	if noteTags == nil {
		return false
	}

	allTagIDs := make(map[uint]bool)
	for _, tid := range tagIDs {
		allTagIDs[tid] = true
		if includeChildren {
			children, err := fm.tagManager.GetDescendantTags(tid)
			if err == nil {
				for _, child := range children {
					allTagIDs[child.ID] = true
				}
			}
		}
	}

	switch logic {
	case LogicOR:
		for tid := range allTagIDs {
			if noteTags[tid] {
				return true
			}
		}
		return false
	default:
		for tid := range allTagIDs {
			if !noteTags[tid] {
				return false
			}
		}
		return true
	}
}

func (fm *FilterManager) matchFolders(noteID uint, noteFolderMap map[uint]map[uint]bool, folderIDs []uint, logic string, includeSubfolders bool) bool {
	if len(folderIDs) == 0 {
		return true
	}

	noteFolders := noteFolderMap[noteID]
	if noteFolders == nil {
		return false
	}

	allFolderIDs := make(map[uint]bool)
	for _, fid := range folderIDs {
		allFolderIDs[fid] = true
		if includeSubfolders {
			children, err := fm.folderManager.GetDescendantFolders(fid)
			if err == nil {
				for _, child := range children {
					allFolderIDs[child.ID] = true
				}
			}
		}
	}

	switch logic {
	case LogicOR:
		for fid := range allFolderIDs {
			if noteFolders[fid] {
				return true
			}
		}
		return false
	default:
		for fid := range allFolderIDs {
			if !noteFolders[fid] {
				return false
			}
		}
		return true
	}
}

type VirtualFolderManager struct {
	db          *db.Database
	config      *config.Config
	virtualFolders map[string]*models.VirtualFolder
}

func NewVirtualFolderManager(database *db.Database, cfg *config.Config) *VirtualFolderManager {
	vfm := &VirtualFolderManager{
		db:             database,
		config:         cfg,
		virtualFolders: make(map[string]*models.VirtualFolder),
	}
	vfm.initBuiltin()
	return vfm
}

func (vfm *VirtualFolderManager) initBuiltin() {
	vfm.virtualFolders["all"] = &models.VirtualFolder{
		ID:   "all",
		Name: "全部笔记",
		Icon: "📚",
		FilterConfig: models.FilterConfig{
			TagIDs:      []uint{},
			TagLogic:    LogicOR,
			FolderIDs:   []uint{},
			FolderLogic: LogicOR,
			CombinedLogic: LogicAND,
		},
	}

	vfm.virtualFolders["untagged"] = &models.VirtualFolder{
		ID:   "untagged",
		Name: "未分类",
		Icon: "📄",
		FilterConfig: models.FilterConfig{
			TagIDs:      []uint{},
			TagLogic:    LogicAND,
			FolderIDs:   []uint{},
			FolderLogic: LogicAND,
			CombinedLogic: LogicAND,
		},
	}

	vfm.virtualFolders["recent"] = &models.VirtualFolder{
		ID:   "recent",
		Name: "最近打开",
		Icon: "⏰",
		FilterConfig: models.FilterConfig{
			TagIDs:      []uint{},
			TagLogic:    LogicOR,
			FolderIDs:   []uint{},
			FolderLogic: LogicOR,
			CombinedLogic: LogicAND,
		},
	}
}

func (vfm *VirtualFolderManager) CreateVirtualFolder(name string, config models.FilterConfig, icon string) (*models.VirtualFolder, error) {
	if name == "" {
		return nil, errors.New("virtual folder name cannot be empty")
	}

	id := generateVirtualFolderID(name)
	if _, exists := vfm.virtualFolders[id]; exists {
		return nil, fmt.Errorf("virtual folder '%s' already exists", name)
	}

	vf := &models.VirtualFolder{
		ID:           id,
		Name:         name,
		FilterConfig: config,
		Icon:         icon,
	}

	vfm.virtualFolders[id] = vf
	return vf, nil
}

func (vfm *VirtualFolderManager) GetVirtualFolder(id string) (*models.VirtualFolder, error) {
	vf, exists := vfm.virtualFolders[id]
	if !exists {
		return nil, fmt.Errorf("virtual folder '%s' not found", id)
	}
	return vf, nil
}

func (vfm *VirtualFolderManager) UpdateVirtualFolder(id string, name string, config *models.FilterConfig, icon string) (*models.VirtualFolder, error) {
	vf, exists := vfm.virtualFolders[id]
	if !exists {
		return nil, fmt.Errorf("virtual folder '%s' not found", id)
	}

	if name != "" {
		vf.Name = name
	}
	if config != nil {
		vf.FilterConfig = *config
	}
	if icon != "" {
		vf.Icon = icon
	}

	return vf, nil
}

func (vfm *VirtualFolderManager) DeleteVirtualFolder(id string) error {
	if _, exists := vfm.virtualFolders[id]; !exists {
		return fmt.Errorf("virtual folder '%s' not found", id)
	}
	delete(vfm.virtualFolders, id)
	return nil
}

func (vfm *VirtualFolderManager) ListVirtualFolders() []*models.VirtualFolder {
	var result []*models.VirtualFolder
	for _, vf := range vfm.virtualFolders {
		result = append(result, vf)
	}
	return result
}

func (vfm *VirtualFolderManager) GetVirtualFolderNotes(id string, filterMgr *FilterManager) ([]*models.Note, error) {
	vf, err := vfm.GetVirtualFolder(id)
	if err != nil {
		return nil, err
	}

	opts := FilterOptions{
		TagIDs:            vf.FilterConfig.TagIDs,
		TagLogic:          vf.FilterConfig.TagLogic,
		IncludeTagChildren: true,
		FolderIDs:         vf.FilterConfig.FolderIDs,
		FolderLogic:       vf.FilterConfig.FolderLogic,
		IncludeSubfolders:  true,
		CombinedLogic:     vf.FilterConfig.CombinedLogic,
	}

	notes, err := filterMgr.FilterNotes(opts)
	if err != nil {
		return nil, err
	}

	if id == "recent" {
		sortNotesByRecent(notes)
		if len(notes) > 50 {
			notes = notes[:50]
		}
	}

	return notes, nil
}

func generateVirtualFolderID(name string) string {
	return "vf_" + name
}

func sortNotesByRecent(notes []*models.Note) {
	for i := 1; i < len(notes); i++ {
		key := notes[i]
		j := i - 1
		for j >= 0 && notes[j].LastOpenedAt.Before(key.LastOpenedAt) {
			notes[j+1] = notes[j]
			j--
		}
		notes[j+1] = key
	}
}

type CombinedManager struct {
	TagManager     *TagManager
	FolderManager  *FolderManager
	FilterManager  *FilterManager
	VirtualFolderManager *VirtualFolderManager
}

func NewCombinedManager(database *db.Database, cfg *config.Config) *CombinedManager {
	tagMgr := NewTagManager(database, cfg)
	folderMgr := NewFolderManager(database, cfg)
	filterMgr := NewFilterManager(database, cfg)
	vfMgr := NewVirtualFolderManager(database, cfg)

	return &CombinedManager{
		TagManager:          tagMgr,
		FolderManager:       folderMgr,
		FilterManager:       filterMgr,
		VirtualFolderManager: vfMgr,
	}
}

func (cm *CombinedManager) GetNoteWithDetails(noteID uint) (*models.Note, error) {
	note, err := cm.FilterManager.db.GetNoteByID(noteID)
	if err != nil {
		return nil, err
	}

	tags, err := cm.TagManager.GetNoteTags(noteID)
	if err != nil {
		return nil, err
	}
	note.Tags = tags

	return note, nil
}

func (cm *CombinedManager) SearchNotes(query string, opts FilterOptions) ([]*models.Note, error) {
	filtered, err := cm.FilterManager.FilterNotes(opts)
	if err != nil {
		return nil, err
	}

	if query == "" {
		return filtered, nil
	}

	var result []*models.Note
	lowerQuery := strings.ToLower(query)
	for _, note := range filtered {
		if strings.Contains(strings.ToLower(note.Title), lowerQuery) ||
			strings.Contains(strings.ToLower(note.Path), lowerQuery) {
			result = append(result, note)
		}
	}

	return result, nil
}
