package models

import (
	"time"
)

type Note struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	Path        string    `gorm:"uniqueIndex" json:"path"`
	Title       string    `json:"title"`
	Content     string    `json:"-" gorm:"-"`
	Hash        string    `json:"hash"`
	WordCount   int       `json:"word_count"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
	LastOpenedAt time.Time `json:"last_opened_at"`

	Tags      []Tag      `gorm:"many2many:note_tags;" json:"tags"`
	OutLinks  []Link     `gorm:"foreignKey:SourceID" json:"out_links"`
	InLinks   []Link     `gorm:"foreignKey:TargetID" json:"in_links"`
	Backlinks []*Note    `gorm:"-" json:"backlinks"`
}

type Tag struct {
	ID       uint   `gorm:"primaryKey" json:"id"`
	Name     string `gorm:"uniqueIndex" json:"name"`
	ParentID *uint  `json:"parent_id"`
	Parent   *Tag   `gorm:"foreignKey:ParentID" json:"parent"`
	Children []Tag  `gorm:"foreignKey:ParentID" json:"children"`
	Notes    []Note `gorm:"many2many:note_tags;" json:"notes"`
	Color    string `json:"color"`
}

type Link struct {
	ID         uint   `gorm:"primaryKey" json:"id"`
	SourceID   uint   `gorm:"index" json:"source_id"`
	TargetID   uint   `gorm:"index" json:"target_id"`
	SourcePath string `json:"source_path"`
	TargetPath string `json:"target_path"`
	AnchorText string `json:"anchor_text"`
	LineNum    int    `json:"line_num"`

	Source *Note `gorm:"foreignKey:SourceID" json:"source"`
	Target *Note `gorm:"foreignKey:TargetID" json:"target"`
}

type SearchIndex struct {
	ID        uint   `gorm:"primaryKey"`
	NoteID    uint   `gorm:"index"`
	Term      string `gorm:"index:idx_term"`
	Frequency int
	Positions []byte `gorm:"type:blob"`
}

type SearchResult struct {
	NoteID    uint     `json:"note_id"`
	Path      string   `json:"path"`
	Title     string   `json:"title"`
	Score     float64  `json:"score"`
	Excerpt   string   `json:"excerpt"`
	Highlights []string `json:"highlights"`
}

type FileEvent struct {
	Path      string    `json:"path"`
	Op        FileOp    `json:"op"`
	Timestamp time.Time `json:"timestamp"`
	Hash      string    `json:"hash"`
	Conflict  bool      `json:"conflict"`
	OurHash   string    `json:"our_hash"`
	TheirHash string    `json:"their_hash"`
}

type FileOp string

const (
	FileOpCreate FileOp = "create"
	FileOpModify FileOp = "modify"
	FileOpDelete FileOp = "delete"
	FileOpRename FileOp = "rename"
)

type Plugin struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Version     string    `json:"version"`
	Description string    `json:"description"`
	Author      string    `json:"author"`
	Enabled     bool      `json:"enabled"`
	Path        string    `json:"path"`
	Entry       string    `json:"entry"`
	InstalledAt time.Time `json:"installed_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type GraphNode struct {
	ID        uint    `json:"id"`
	Path      string  `json:"path"`
	Title     string  `json:"title"`
	X         float64 `json:"x"`
	Y         float64 `json:"y"`
	Vx        float64 `json:"-"`
	Vy        float64 `json:"-"`
	Size      int     `json:"size"`
	InDegree  int     `json:"in_degree"`
	OutDegree int     `json:"out_degree"`
	IsOrphan  bool    `json:"is_orphan"`
	Tags      []string `json:"tags"`
}

type GraphEdge struct {
	Source uint `json:"source"`
	Target uint `json:"target"`
}

type GraphData struct {
	Nodes []GraphNode `json:"nodes"`
	Edges []GraphEdge `json:"edges"`
}

type Template struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Content  string `json:"content"`
	Path     string `json:"path"`
	IsBuiltin bool   `json:"is_builtin"`
}

type ExportOptions struct {
	Format       string            `json:"format"`
	OutputPath   string            `json:"output_path"`
	CSSPath      string            `json:"css_path"`
	IncludeTOC   bool              `json:"include_toc"`
	Variables    map[string]string `json:"variables"`
	PDFPageSize  string            `json:"pdf_page_size"`
	PDFMargin    string            `json:"pdf_margin"`
}

type Folder struct {
	ID       uint     `gorm:"primaryKey" json:"id"`
	Name     string   `json:"name"`
	ParentID *uint    `json:"parent_id"`
	Parent   *Folder  `gorm:"foreignKey:ParentID" json:"parent"`
	Children []Folder `gorm:"foreignKey:ParentID" json:"children"`
	SortOrder int     `json:"sort_order"`
	Notes    []Note   `gorm:"many2many:note_folders;" json:"notes"`
	IsVirtual bool    `json:"is_virtual"`
	FilterConfig *FilterConfig `gorm:"-" json:"filter_config,omitempty"`
}

type FilterConfig struct {
	TagIDs      []uint `json:"tag_ids"`
	TagLogic    string `json:"tag_logic"`
	FolderIDs   []uint `json:"folder_ids"`
	FolderLogic string `json:"folder_logic"`
	CombinedLogic string `json:"combined_logic"`
}

type VirtualFolder struct {
	ID           string       `json:"id"`
	Name         string       `json:"name"`
	FilterConfig FilterConfig `json:"filter_config"`
	Icon         string       `json:"icon"`
}

type TagStats struct {
	TagID      uint   `json:"tag_id"`
	TagName    string `json:"tag_name"`
	NoteCount  int    `json:"note_count"`
	Color      string `json:"color"`
	IsUnused   bool   `json:"is_unused"`
}

type ConflictResolution string

const (
	ConflictKeepOurs   ConflictResolution = "keep_ours"
	ConflictKeepTheirs ConflictResolution = "keep_theirs"
	ConflictMerge      ConflictResolution = "merge"
)

type NodePreview struct {
	NoteID    uint     `json:"note_id"`
	Title     string   `json:"title"`
	Content   string   `json:"content"`
	Tags      []string `json:"tags"`
	WordCount int      `json:"word_count"`
	LinkCount int      `json:"link_count"`
	Path      string   `json:"path"`
}

type LayoutType string

const (
	LayoutForce        LayoutType = "force"
	LayoutCircular     LayoutType = "circular"
	LayoutHierarchical LayoutType = "hierarchical"
)

type GraphAction struct {
	Type   string                 `json:"type"`
	NodeID uint                   `json:"node_id"`
	Data   map[string]interface{} `json:"data"`
}
