package db

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "github.com/mattn/go-sqlite3"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/models"
)

type Database struct {
	*sql.DB
	cfg *config.Config
}

func New(cfg *config.Config) (*Database, error) {
	if err := os.MkdirAll(filepath.Dir(cfg.DBPath), 0755); err != nil {
		return nil, err
	}

	dsn := fmt.Sprintf("file:%s?_fk=1&_journal=WAL&_sync=NORMAL", cfg.DBPath)
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, err
	}

	if _, err := db.Exec("PRAGMA foreign_keys = ON;"); err != nil {
		db.Close()
		return nil, err
	}

	database := &Database{db, cfg}
	if err := database.migrate(); err != nil {
		db.Close()
		return nil, err
	}

	return database, nil
}

func (db *Database) migrate() error {
	schemas := []string{
		`CREATE TABLE IF NOT EXISTS notes (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			path TEXT UNIQUE NOT NULL,
			title TEXT NOT NULL,
			hash TEXT NOT NULL,
			word_count INTEGER DEFAULT 0,
			created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
			updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
			last_opened_at DATETIME DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS tags (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			name TEXT UNIQUE NOT NULL,
			parent_id INTEGER,
			color TEXT DEFAULT '#6366f1',
			FOREIGN KEY (parent_id) REFERENCES tags(id) ON DELETE SET NULL
		)`,
		`CREATE TABLE IF NOT EXISTS note_tags (
			note_id INTEGER,
			tag_id INTEGER,
			PRIMARY KEY (note_id, tag_id),
			FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
			FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
		)`,
		`CREATE TABLE IF NOT EXISTS links (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			source_id INTEGER NOT NULL,
			target_id INTEGER,
			source_path TEXT NOT NULL,
			target_path TEXT NOT NULL,
			anchor_text TEXT,
			line_num INTEGER DEFAULT 0,
			FOREIGN KEY (source_id) REFERENCES notes(id) ON DELETE CASCADE,
			FOREIGN KEY (target_id) REFERENCES notes(id) ON DELETE SET NULL
		)`,
		`CREATE TABLE IF NOT EXISTS search_index (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			note_id INTEGER NOT NULL,
			term TEXT NOT NULL,
			frequency INTEGER DEFAULT 0,
			positions BLOB,
			FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_search_term ON search_index(term)`,
		`CREATE INDEX IF NOT EXISTS idx_search_note ON search_index(note_id)`,
		`CREATE INDEX IF NOT EXISTS idx_notes_path ON notes(path)`,
		`CREATE TABLE IF NOT EXISTS folders (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			name TEXT NOT NULL,
			parent_id INTEGER,
			sort_order INTEGER DEFAULT 0,
			is_virtual INTEGER DEFAULT 0,
			FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE SET NULL
		)`,
		`CREATE TABLE IF NOT EXISTS note_folders (
			note_id INTEGER,
			folder_id INTEGER,
			PRIMARY KEY (note_id, folder_id),
			FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
			FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_folders_parent ON folders(parent_id)`,
		`CREATE INDEX IF NOT EXISTS idx_folders_sort ON folders(sort_order)`,
		`CREATE INDEX IF NOT EXISTS idx_links_source ON links(source_id)`,
		`CREATE INDEX IF NOT EXISTS idx_links_target ON links(target_id)`,
	}

	for _, schema := range schemas {
		if _, err := db.Exec(schema); err != nil {
			return fmt.Errorf("migration failed: %w, sql: %s", err, schema)
		}
	}

	if err := db.RunMigrations(); err != nil {
		return fmt.Errorf("schema migration failed: %w", err)
	}

	return nil
}

func (db *Database) SaveNote(note *models.Note) error {
	now := time.Now()
	if note.ID == 0 {
		note.CreatedAt = now
		note.UpdatedAt = now
		note.LastOpenedAt = now
		res, err := db.Exec(`
			INSERT INTO notes (path, title, hash, word_count, created_at, updated_at, last_opened_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
		`, note.Path, note.Title, note.Hash, note.WordCount,
			note.CreatedAt, note.UpdatedAt, note.LastOpenedAt)
		if err != nil {
			return err
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		note.ID = uint(id)
	} else {
		note.UpdatedAt = now
		_, err := db.Exec(`
			UPDATE notes SET path=?, title=?, hash=?, word_count=?, updated_at=?
			WHERE id=?
		`, note.Path, note.Title, note.Hash, note.WordCount, note.UpdatedAt, note.ID)
		if err != nil {
			return err
		}
	}
	return db.saveNoteTags(note)
}

func (db *Database) saveNoteTags(note *models.Note) error {
	if _, err := db.Exec("DELETE FROM note_tags WHERE note_id=?", note.ID); err != nil {
		return err
	}

	for _, tag := range note.Tags {
		tagID, err := db.getOrCreateTag(tag.Name, tag.ParentID)
		if err != nil {
			return err
		}
		if _, err := db.Exec(`
			INSERT OR IGNORE INTO note_tags (note_id, tag_id) VALUES (?, ?)
		`, note.ID, tagID); err != nil {
			return err
		}
	}
	return nil
}

func (db *Database) getOrCreateTag(name string, parentID *uint) (uint, error) {
	var id uint
	err := db.QueryRow("SELECT id FROM tags WHERE name=?", name).Scan(&id)
	if err == sql.ErrNoRows {
		res, err := db.Exec(`
			INSERT INTO tags (name, parent_id) VALUES (?, ?)
		`, name, parentID)
		if err != nil {
			return 0, err
		}
		newID, err := res.LastInsertId()
		if err != nil {
			return 0, err
		}
		return uint(newID), nil
	}
	return id, err
}

func (db *Database) GetNoteByPath(path string) (*models.Note, error) {
	var note models.Note
	err := db.QueryRow(`
		SELECT id, path, title, hash, word_count, created_at, updated_at, last_opened_at
		FROM notes WHERE path=?
	`, path).Scan(&note.ID, &note.Path, &note.Title, &note.Hash,
		&note.WordCount, &note.CreatedAt, &note.UpdatedAt, &note.LastOpenedAt)
	if err != nil {
		return nil, err
	}

	if err := db.loadNoteTags(&note); err != nil {
		return nil, err
	}
	return &note, nil
}

func (db *Database) GetNoteByID(id uint) (*models.Note, error) {
	var note models.Note
	err := db.QueryRow(`
		SELECT id, path, title, hash, word_count, created_at, updated_at, last_opened_at
		FROM notes WHERE id=?
	`, id).Scan(&note.ID, &note.Path, &note.Title, &note.Hash,
		&note.WordCount, &note.CreatedAt, &note.UpdatedAt, &note.LastOpenedAt)
	if err != nil {
		return nil, err
	}
	if err := db.loadNoteTags(&note); err != nil {
		return nil, err
	}
	return &note, nil
}

func (db *Database) loadNoteTags(note *models.Note) error {
	rows, err := db.Query(`
		SELECT t.id, t.name, t.parent_id, t.color
		FROM tags t
		INNER JOIN note_tags nt ON t.id = nt.tag_id
		WHERE nt.note_id = ?
	`, note.ID)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		var tag models.Tag
		var parentID sql.NullInt64
		if err := rows.Scan(&tag.ID, &tag.Name, &parentID, &tag.Color); err != nil {
			return err
		}
		if parentID.Valid {
			pid := uint(parentID.Int64)
			tag.ParentID = &pid
		}
		note.Tags = append(note.Tags, tag)
	}
	return nil
}

func (db *Database) DeleteNote(path string) error {
	_, err := db.Exec("DELETE FROM notes WHERE path=?", path)
	return err
}

func (db *Database) UpdateNoteOpened(id uint) error {
	_, err := db.Exec("UPDATE notes SET last_opened_at=? WHERE id=?", time.Now(), id)
	return err
}

func (db *Database) SaveLinks(sourceID uint, links []models.Link) error {
	if _, err := db.Exec("DELETE FROM links WHERE source_id=?", sourceID); err != nil {
		return err
	}

	for _, link := range links {
		_, err := db.Exec(`
			INSERT INTO links (source_id, target_id, source_path, target_path, anchor_text, line_num)
			VALUES (?, ?, ?, ?, ?, ?)
		`, sourceID, link.TargetID, link.SourcePath, link.TargetPath,
			link.AnchorText, link.LineNum)
		if err != nil {
			return err
		}
	}
	return nil
}

func (db *Database) AddLink(link *models.Link) error {
	_, err := db.Exec(`
		INSERT INTO links (source_id, target_id, source_path, target_path, anchor_text, line_num)
		VALUES (?, ?, ?, ?, ?, ?)
	`, link.SourceID, link.TargetID, link.SourcePath, link.TargetPath,
		link.AnchorText, link.LineNum)
	return err
}

func (db *Database) DeleteLink(sourceID, targetID uint) error {
	_, err := db.Exec("DELETE FROM links WHERE source_id=? AND target_id=?", sourceID, targetID)
	return err
}

func (db *Database) DeleteNoteByID(id uint) error {
	_, err := db.Exec("DELETE FROM links WHERE source_id=? OR target_id=?", id, id)
	if err != nil {
		return err
	}
	_, err = db.Exec("DELETE FROM note_tags WHERE note_id=?", id)
	if err != nil {
		return err
	}
	_, err = db.Exec("DELETE FROM notes WHERE id=?", id)
	return err
}

func (db *Database) GetLinks() ([]models.Link, error) {
	rows, err := db.Query(`
		SELECT id, source_id, target_id, source_path, target_path, anchor_text, line_num
		FROM links
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var links []models.Link
	for rows.Next() {
		var link models.Link
		var targetID sql.NullInt64
		if err := rows.Scan(&link.ID, &link.SourceID, &targetID,
			&link.SourcePath, &link.TargetPath, &link.AnchorText, &link.LineNum); err != nil {
			return nil, err
		}
		if targetID.Valid {
			link.TargetID = uint(targetID.Int64)
		}
		links = append(links, link)
	}
	return links, nil
}

func (db *Database) GetBacklinks(noteID uint) ([]models.Link, error) {
	rows, err := db.Query(`
		SELECT l.id, l.source_id, l.target_id, l.source_path, l.target_path, l.anchor_text, l.line_num,
			   n.title as source_title
		FROM links l
		INNER JOIN notes n ON l.source_id = n.id
		WHERE l.target_id = ?
	`, noteID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var links []models.Link
	for rows.Next() {
		var link models.Link
		var targetID sql.NullInt64
		var sourceTitle string
		if err := rows.Scan(&link.ID, &link.SourceID, &targetID,
			&link.SourcePath, &link.TargetPath, &link.AnchorText, &link.LineNum, &sourceTitle); err != nil {
			return nil, err
		}
		if targetID.Valid {
			link.TargetID = uint(targetID.Int64)
		}
		link.Source = &models.Note{ID: link.SourceID, Path: link.SourcePath, Title: sourceTitle}
		links = append(links, link)
	}
	return links, nil
}

func (db *Database) SaveSearchIndex(noteID uint, term string, freq int, positions []int) error {
	posData, _ := json.Marshal(positions)
	_, err := db.Exec(`
		INSERT INTO search_index (note_id, term, frequency, positions)
		VALUES (?, ?, ?, ?)
	`, noteID, term, freq, posData)
	return err
}

func (db *Database) ClearSearchIndex(noteID uint) error {
	_, err := db.Exec("DELETE FROM search_index WHERE note_id=?", noteID)
	return err
}

func (db *Database) SearchByTerm(term string) ([]struct {
	NoteID    uint
	Frequency int
	Positions []int
}, error) {
	rows, err := db.Query(`
		SELECT note_id, frequency, positions
		FROM search_index WHERE term = ?
	`, term)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var results []struct {
		NoteID    uint
		Frequency int
		Positions []int
	}

	for rows.Next() {
		var r struct {
			NoteID    uint
			Frequency int
			Positions []byte
		}
		if err := rows.Scan(&r.NoteID, &r.Frequency, &r.Positions); err != nil {
			return nil, err
		}
		var positions []int
		json.Unmarshal(r.Positions, &positions)
		results = append(results, struct {
			NoteID    uint
			Frequency int
			Positions []int
		}{r.NoteID, r.Frequency, positions})
	}
	return results, nil
}

func (db *Database) GetAllNotes() ([]*models.Note, error) {
	rows, err := db.Query(`
		SELECT id, path, title, hash, word_count, created_at, updated_at, last_opened_at
		FROM notes ORDER BY updated_at DESC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var notes []*models.Note
	for rows.Next() {
		var note models.Note
		if err := rows.Scan(&note.ID, &note.Path, &note.Title, &note.Hash,
			&note.WordCount, &note.CreatedAt, &note.UpdatedAt, &note.LastOpenedAt); err != nil {
			return nil, err
		}
		notes = append(notes, &note)
	}
	return notes, nil
}

func (db *Database) GetAllTags() ([]models.Tag, error) {
	rows, err := db.Query(`
		SELECT id, name, parent_id, color FROM tags ORDER BY name
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var tags []models.Tag
	for rows.Next() {
		var tag models.Tag
		var parentID sql.NullInt64
		if err := rows.Scan(&tag.ID, &tag.Name, &parentID, &tag.Color); err != nil {
			return nil, err
		}
		if parentID.Valid {
			pid := uint(parentID.Int64)
			tag.ParentID = &pid
		}
		tags = append(tags, tag)
	}
	return tags, nil
}

func (db *Database) GetNotePaths() (map[string]uint, error) {
	rows, err := db.Query("SELECT id, path FROM notes")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	paths := make(map[string]uint)
	for rows.Next() {
		var id uint
		var path string
		if err := rows.Scan(&id, &path); err != nil {
			return nil, err
		}
		paths[path] = id
	}
	return paths, nil
}

func (db *Database) GetNoteHashes() (map[string]string, error) {
	rows, err := db.Query("SELECT path, hash FROM notes")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	hashes := make(map[string]string)
	for rows.Next() {
		var path, hash string
		if err := rows.Scan(&path, &hash); err != nil {
			return nil, err
		}
		hashes[path] = hash
	}
	return hashes, nil
}

func (db *Database) GetDocLengths() (map[uint]int, error) {
	rows, err := db.Query("SELECT id, word_count FROM notes")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	lengths := make(map[uint]int)
	for rows.Next() {
		var id uint
		var wc int
		if err := rows.Scan(&id, &wc); err != nil {
			return nil, err
		}
		lengths[id] = wc
	}
	return lengths, nil
}

func (db *Database) GetTotalDocCount() (int, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM notes").Scan(&count)
	return count, err
}

func (db *Database) SaveTag(tag *models.Tag) error {
	if tag.ID == 0 {
		res, err := db.Exec(`
			INSERT INTO tags (name, parent_id, color) VALUES (?, ?, ?)
		`, tag.Name, tag.ParentID, tag.Color)
		if err != nil {
			return err
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		tag.ID = uint(id)
	} else {
		_, err := db.Exec(`
			UPDATE tags SET name=?, parent_id=?, color=? WHERE id=?
		`, tag.Name, tag.ParentID, tag.Color, tag.ID)
		if err != nil {
			return err
		}
	}
	return nil
}

func (db *Database) DeleteTag(id uint) error {
	_, err := db.Exec("DELETE FROM tags WHERE id=?", id)
	return err
}

func (db *Database) SaveFolder(folder *models.Folder) error {
	if folder.ID == 0 {
		res, err := db.Exec(`
			INSERT INTO folders (name, parent_id, sort_order, is_virtual) VALUES (?, ?, ?, ?)
		`, folder.Name, folder.ParentID, folder.SortOrder, folder.IsVirtual)
		if err != nil {
			return err
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		folder.ID = uint(id)
	} else {
		_, err := db.Exec(`
			UPDATE folders SET name=?, parent_id=?, sort_order=?, is_virtual=? WHERE id=?
		`, folder.Name, folder.ParentID, folder.SortOrder, folder.IsVirtual, folder.ID)
		if err != nil {
			return err
		}
	}
	return nil
}

func (db *Database) DeleteFolder(id uint) error {
	_, err := db.Exec("DELETE FROM folders WHERE id=?", id)
	return err
}

func (db *Database) GetFolderByID(id uint) (*models.Folder, error) {
	var folder models.Folder
	var parentID sql.NullInt64
	var isVirtual int
	err := db.QueryRow(`
		SELECT id, name, parent_id, sort_order, is_virtual FROM folders WHERE id=?
	`, id).Scan(&folder.ID, &folder.Name, &parentID, &folder.SortOrder, &isVirtual)
	if err != nil {
		return nil, err
	}
	if parentID.Valid {
		pid := uint(parentID.Int64)
		folder.ParentID = &pid
	}
	folder.IsVirtual = isVirtual == 1
	return &folder, nil
}

func (db *Database) GetAllFolders() ([]models.Folder, error) {
	rows, err := db.Query(`
		SELECT id, name, parent_id, sort_order, is_virtual FROM folders ORDER BY sort_order, name
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var folders []models.Folder
	for rows.Next() {
		var folder models.Folder
		var parentID sql.NullInt64
		var isVirtual int
		if err := rows.Scan(&folder.ID, &folder.Name, &parentID, &folder.SortOrder, &isVirtual); err != nil {
			return nil, err
		}
		if parentID.Valid {
			pid := uint(parentID.Int64)
			folder.ParentID = &pid
		}
		folder.IsVirtual = isVirtual == 1
		folders = append(folders, folder)
	}
	return folders, nil
}

func (db *Database) GetFoldersByParent(parentID *uint) ([]models.Folder, error) {
	var rows *sql.Rows
	var err error
	if parentID == nil {
		rows, err = db.Query(`
			SELECT id, name, parent_id, sort_order, is_virtual FROM folders 
			WHERE parent_id IS NULL ORDER BY sort_order, name
		`)
	} else {
		rows, err = db.Query(`
			SELECT id, name, parent_id, sort_order, is_virtual FROM folders 
			WHERE parent_id=? ORDER BY sort_order, name
		`, *parentID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var folders []models.Folder
	for rows.Next() {
		var folder models.Folder
		var pID sql.NullInt64
		var isVirtual int
		if err := rows.Scan(&folder.ID, &folder.Name, &pID, &folder.SortOrder, &isVirtual); err != nil {
			return nil, err
		}
		if pID.Valid {
			pid := uint(pID.Int64)
			folder.ParentID = &pid
		}
		folder.IsVirtual = isVirtual == 1
		folders = append(folders, folder)
	}
	return folders, nil
}

func (db *Database) SaveNoteFolder(noteID, folderID uint) error {
	_, err := db.Exec(`
		INSERT OR IGNORE INTO note_folders (note_id, folder_id) VALUES (?, ?)
	`, noteID, folderID)
	return err
}

func (db *Database) DeleteNoteFolder(noteID, folderID uint) error {
	_, err := db.Exec(`
		DELETE FROM note_folders WHERE note_id=? AND folder_id=?
	`, noteID, folderID)
	return err
}

func (db *Database) DeleteNoteFoldersByNote(noteID uint) error {
	_, err := db.Exec("DELETE FROM note_folders WHERE note_id=?", noteID)
	return err
}

func (db *Database) GetNoteFolders(noteID uint) ([]models.Folder, error) {
	rows, err := db.Query(`
		SELECT f.id, f.name, f.parent_id, f.sort_order, f.is_virtual
		FROM folders f
		INNER JOIN note_folders nf ON f.id = nf.folder_id
		WHERE nf.note_id = ?
		ORDER BY f.name
	`, noteID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var folders []models.Folder
	for rows.Next() {
		var folder models.Folder
		var parentID sql.NullInt64
		var isVirtual int
		if err := rows.Scan(&folder.ID, &folder.Name, &parentID, &folder.SortOrder, &isVirtual); err != nil {
			return nil, err
		}
		if parentID.Valid {
			pid := uint(parentID.Int64)
			folder.ParentID = &pid
		}
		folder.IsVirtual = isVirtual == 1
		folders = append(folders, folder)
	}
	return folders, nil
}

func (db *Database) GetFolderNotes(folderID uint) ([]*models.Note, error) {
	rows, err := db.Query(`
		SELECT n.id, n.path, n.title, n.hash, n.word_count, n.created_at, n.updated_at, n.last_opened_at
		FROM notes n
		INNER JOIN note_folders nf ON n.id = nf.note_id
		WHERE nf.folder_id = ?
		ORDER BY n.updated_at DESC
	`, folderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var notes []*models.Note
	for rows.Next() {
		var note models.Note
		if err := rows.Scan(&note.ID, &note.Path, &note.Title, &note.Hash,
			&note.WordCount, &note.CreatedAt, &note.UpdatedAt, &note.LastOpenedAt); err != nil {
			return nil, err
		}
		notes = append(notes, &note)
	}
	return notes, nil
}

func (db *Database) GetTagByID(id uint) (*models.Tag, error) {
	var tag models.Tag
	var parentID sql.NullInt64
	err := db.QueryRow(`
		SELECT id, name, parent_id, color FROM tags WHERE id=?
	`, id).Scan(&tag.ID, &tag.Name, &parentID, &tag.Color)
	if err != nil {
		return nil, err
	}
	if parentID.Valid {
		pid := uint(parentID.Int64)
		tag.ParentID = &pid
	}
	return &tag, nil
}

func (db *Database) GetTagByName(name string) (*models.Tag, error) {
	var tag models.Tag
	var parentID sql.NullInt64
	err := db.QueryRow(`
		SELECT id, name, parent_id, color FROM tags WHERE name=?
	`, name).Scan(&tag.ID, &tag.Name, &parentID, &tag.Color)
	if err != nil {
		return nil, err
	}
	if parentID.Valid {
		pid := uint(parentID.Int64)
		tag.ParentID = &pid
	}
	return &tag, nil
}

func (db *Database) GetTagNoteCount(tagID uint) (int, error) {
	var count int
	err := db.QueryRow(`
		SELECT COUNT(*) FROM note_tags WHERE tag_id=?
	`, tagID).Scan(&count)
	return count, err
}

func (db *Database) GetTagsByNote(noteID uint) ([]models.Tag, error) {
	rows, err := db.Query(`
		SELECT t.id, t.name, t.parent_id, t.color
		FROM tags t
		INNER JOIN note_tags nt ON t.id = nt.tag_id
		WHERE nt.note_id = ?
		ORDER BY t.name
	`, noteID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var tags []models.Tag
	for rows.Next() {
		var tag models.Tag
		var parentID sql.NullInt64
		if err := rows.Scan(&tag.ID, &tag.Name, &parentID, &tag.Color); err != nil {
			return nil, err
		}
		if parentID.Valid {
			pid := uint(parentID.Int64)
			tag.ParentID = &pid
		}
		tags = append(tags, tag)
	}
	return tags, nil
}

func (db *Database) AddTagToNote(noteID, tagID uint) error {
	_, err := db.Exec(`
		INSERT OR IGNORE INTO note_tags (note_id, tag_id) VALUES (?, ?)
	`, noteID, tagID)
	return err
}

func (db *Database) RemoveTagFromNote(noteID, tagID uint) error {
	_, err := db.Exec(`
		DELETE FROM note_tags WHERE note_id=? AND tag_id=?
	`, noteID, tagID)
	return err
}

func (db *Database) SearchTags(prefix string, limit int) ([]models.Tag, error) {
	rows, err := db.Query(`
		SELECT id, name, parent_id, color FROM tags 
		WHERE name LIKE ? 
		ORDER BY name 
		LIMIT ?
	`, prefix+"%", limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var tags []models.Tag
	for rows.Next() {
		var tag models.Tag
		var parentID sql.NullInt64
		if err := rows.Scan(&tag.ID, &tag.Name, &parentID, &tag.Color); err != nil {
			return nil, err
		}
		if parentID.Valid {
			pid := uint(parentID.Int64)
			tag.ParentID = &pid
		}
		tags = append(tags, tag)
	}
	return tags, nil
}
