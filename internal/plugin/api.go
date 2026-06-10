package plugin

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"github.com/dop251/goja"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/internal/search"
	"github.com/solocoder/knowledgebase/internal/tags"
)

type Command struct {
	ID       string
	Name     string
	PluginID string
	callback goja.Callable
	vm       *goja.Runtime
}

type View struct {
	ID       string
	PluginID string
	Title    string
	options  *goja.Object
	vm       *goja.Runtime
}

type Notification struct {
	ID       string
	PluginID string
	Title    string
	Message  string
}

type PluginAPI struct {
	db            *db.Database
	cfg           *config.Config
	search        *search.SearchEngine
	tagManager    *tags.TagManager
	commands      map[string]*Command
	views         map[string]*View
	notifications []*Notification
	settings      map[string]map[string]interface{}
	mu            sync.RWMutex
	notifCb       func(*Notification)
}

func NewPluginAPI(database *db.Database, cfg *config.Config, searchEngine *search.SearchEngine, tagMgr *tags.TagManager) *PluginAPI {
	return &PluginAPI{
		db:         database,
		cfg:        cfg,
		search:     searchEngine,
		tagManager: tagMgr,
		commands:   make(map[string]*Command),
		views:      make(map[string]*View),
		settings:   make(map[string]map[string]interface{}),
	}
}

func (api *PluginAPI) SetNotificationCallback(cb func(*Notification)) {
	api.notifCb = cb
}

func (api *PluginAPI) RegisterCommand(pluginID, name string, callback goja.Callable, vm *goja.Runtime) {
	api.mu.Lock()
	defer api.mu.Unlock()

	cmdID := fmt.Sprintf("%s:%s", pluginID, name)
	api.commands[cmdID] = &Command{
		ID:       cmdID,
		Name:     name,
		PluginID: pluginID,
		callback: callback,
		vm:       vm,
	}
}

func (api *PluginAPI) GetCommands() []*Command {
	api.mu.RLock()
	defer api.mu.RUnlock()

	cmds := make([]*Command, 0, len(api.commands))
	for _, cmd := range api.commands {
		cmds = append(cmds, cmd)
	}
	return cmds
}

func (api *PluginAPI) GetPluginCommands(pluginID string) []*Command {
	api.mu.RLock()
	defer api.mu.RUnlock()

	var cmds []*Command
	for _, cmd := range api.commands {
		if cmd.PluginID == pluginID {
			cmds = append(cmds, cmd)
		}
	}
	return cmds
}

func (api *PluginAPI) ExecuteCommand(cmdID string) error {
	api.mu.RLock()
	cmd, ok := api.commands[cmdID]
	api.mu.RUnlock()

	if !ok {
		return fmt.Errorf("command not found: %s", cmdID)
	}

	_, err := cmd.callback(goja.Undefined())
	return err
}

func (api *PluginAPI) RegisterView(pluginID, viewID string, options *goja.Object, vm *goja.Runtime) {
	api.mu.Lock()
	defer api.mu.Unlock()

	fullID := fmt.Sprintf("%s:%s", pluginID, viewID)
	title := ""
	if titleVal := options.Get("title"); titleVal != nil {
		title = titleVal.String()
	}

	api.views[fullID] = &View{
		ID:       fullID,
		PluginID: pluginID,
		Title:    title,
		options:  options,
		vm:       vm,
	}
}

func (api *PluginAPI) GetViews() []*View {
	api.mu.RLock()
	defer api.mu.RUnlock()

	views := make([]*View, 0, len(api.views))
	for _, v := range api.views {
		views = append(views, v)
	}
	return views
}

type NoteInfo struct {
	ID        uint   `json:"id"`
	Path      string `json:"path"`
	Title     string `json:"title"`
	Content   string `json:"content,omitempty"`
	WordCount int    `json:"word_count"`
}

func (api *PluginAPI) GetNote(path string) (*NoteInfo, error) {
	note, err := api.db.GetNoteByPath(path)
	if err != nil {
		return nil, err
	}

	content, err := api.loadNoteContent(note)
	if err != nil {
		content = ""
	}

	return &NoteInfo{
		ID:        note.ID,
		Path:      note.Path,
		Title:     note.Title,
		Content:   content,
		WordCount: note.WordCount,
	}, nil
}

func (api *PluginAPI) loadNoteContent(note *models.Note) (string, error) {
	vaultPath := api.cfg.VaultPath
	fullPath := filepath.Join(vaultPath, note.Path)

	if _, err := os.Stat(fullPath); os.IsNotExist(err) {
		return "", fmt.Errorf("note file not found: %s", note.Path)
	}

	data, err := os.ReadFile(fullPath)
	if err != nil {
		return "", err
	}

	return string(data), nil
}

func (api *PluginAPI) SearchNotes(query string) ([]*NoteInfo, error) {
	searchQuery := search.SearchQuery{
		Query:    query,
		Page:     0,
		PageSize: 100,
	}

	results, _, err := api.search.Search(searchQuery)
	if err != nil {
		return nil, err
	}

	notes := make([]*NoteInfo, 0, len(results))
	for _, r := range results {
		notes = append(notes, &NoteInfo{
			ID:    r.NoteID,
			Path:  r.Path,
			Title: r.Title,
		})
	}

	return notes, nil
}

type TagInfo struct {
	ID        uint   `json:"id"`
	Name      string `json:"name"`
	Color     string `json:"color"`
	NoteCount int    `json:"note_count"`
}

func (api *PluginAPI) GetTags() ([]*TagInfo, error) {
	tagStats, err := api.tagManager.GetTagStats()
	if err != nil {
		return nil, err
	}

	tags := make([]*TagInfo, 0, len(tagStats))
	for _, ts := range tagStats {
		tags = append(tags, &TagInfo{
			ID:        ts.TagID,
			Name:      ts.TagName,
			Color:     ts.Color,
			NoteCount: ts.NoteCount,
		})
	}

	return tags, nil
}

func (api *PluginAPI) SendNotification(pluginID, title, message string) {
	api.mu.Lock()
	defer api.mu.Unlock()

	notif := &Notification{
		ID:       fmt.Sprintf("%s-%d", pluginID, len(api.notifications)),
		PluginID: pluginID,
		Title:    title,
		Message:  message,
	}

	api.notifications = append(api.notifications, notif)

	if api.notifCb != nil {
		api.notifCb(notif)
	}
}

func (api *PluginAPI) GetNotifications() []*Notification {
	api.mu.RLock()
	defer api.mu.RUnlock()

	notifs := make([]*Notification, len(api.notifications))
	copy(notifs, api.notifications)
	return notifs
}

func (api *PluginAPI) GetSetting(pluginID, key string) (interface{}, error) {
	api.mu.RLock()
	defer api.mu.RUnlock()

	settings, ok := api.settings[pluginID]
	if !ok {
		if err := api.loadSettings(pluginID); err != nil {
			return nil, err
		}
		settings = api.settings[pluginID]
		if settings == nil {
			return nil, fmt.Errorf("setting not found: %s", key)
		}
	}

	val, ok := settings[key]
	if !ok {
		return nil, fmt.Errorf("setting not found: %s", key)
	}

	return val, nil
}

func (api *PluginAPI) SetSetting(pluginID, key string, value interface{}) error {
	api.mu.Lock()
	defer api.mu.Unlock()

	if _, ok := api.settings[pluginID]; !ok {
		api.settings[pluginID] = make(map[string]interface{})
	}

	api.settings[pluginID][key] = value

	return api.saveSettings(pluginID)
}

func (api *PluginAPI) loadSettings(pluginID string) error {
	settingsPath := api.getSettingsPath(pluginID)
	data, err := os.ReadFile(settingsPath)
	if err != nil {
		if os.IsNotExist(err) {
			api.settings[pluginID] = make(map[string]interface{})
			return nil
		}
		return err
	}

	var settings map[string]interface{}
	if err := json.Unmarshal(data, &settings); err != nil {
		return err
	}

	api.settings[pluginID] = settings
	return nil
}

func (api *PluginAPI) saveSettings(pluginID string) error {
	settingsPath := api.getSettingsPath(pluginID)
	settings := api.settings[pluginID]

	data, err := json.MarshalIndent(settings, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(settingsPath, data, 0644)
}

func (api *PluginAPI) getSettingsPath(pluginID string) string {
	settingsDir := filepath.Join(api.cfg.PluginPath, ".settings")
	os.MkdirAll(settingsDir, 0755)
	return filepath.Join(settingsDir, pluginID+".json")
}

func (api *PluginAPI) ClearPluginData(pluginID string) {
	api.mu.Lock()
	defer api.mu.Unlock()

	for cmdID := range api.commands {
		if len(cmdID) >= len(pluginID) && cmdID[:len(pluginID)] == pluginID {
			delete(api.commands, cmdID)
		}
	}

	for viewID := range api.views {
		if len(viewID) >= len(pluginID) && viewID[:len(pluginID)] == pluginID {
			delete(api.views, viewID)
		}
	}

	delete(api.settings, pluginID)
	settingsPath := api.getSettingsPath(pluginID)
	os.Remove(settingsPath)
}
