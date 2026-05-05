import Database from 'better-sqlite3';
import { v4 as uuidv4 } from 'uuid';
import { Note, Folder, Tag, AppSettings, SyncConfig, AIConfig } from '../../shared/types';
import { EventBus, eventBus, NoteEventData, IndexEventData } from '../../shared/utils/event-bus';

const SCHEMA_VERSION = 1;

export interface DatabaseServiceOptions {
  enableEventBus?: boolean;
  batchIndexUpdates?: boolean;
}

const DEFAULT_OPTIONS: DatabaseServiceOptions = {
  enableEventBus: true,
  batchIndexUpdates: true,
};

export class DatabaseService {
  private static instance: DatabaseService;
  private db: Database.Database | null = null;
  private dbPath: string;
  private options: DatabaseServiceOptions;
  private eventBus: EventBus;

  private constructor(dbPath: string, options?: DatabaseServiceOptions) {
    this.dbPath = dbPath;
    this.options = { ...DEFAULT_OPTIONS, ...options };
    this.eventBus = eventBus;
  }

  public static getInstance(dbPath?: string, options?: DatabaseServiceOptions): DatabaseService {
    if (!DatabaseService.instance) {
      if (!dbPath) {
        throw new Error('Database path is required for first initialization');
      }
      DatabaseService.instance = new DatabaseService(dbPath, options);
    }
    return DatabaseService.instance;
  }

  public initialize(): void {
    this.db = new Database(this.dbPath);
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.createTables();
    this.migrate();
    this.initializeSettings();
    this.setupEventListeners();
  }

  private setupEventListeners(): void {
    if (!this.options.enableEventBus) return;

    this.eventBus.on<IndexEventData>('index:update', (payload) => {
      const data = payload.data;
      if (Array.isArray(data.events)) {
        for (const event of data.events) {
          this.handleIndexUpdate(event.data as IndexEventData);
        }
      } else {
        this.handleIndexUpdate(data);
      }
    });
  }

  private handleIndexUpdate(data: IndexEventData): void {
    switch (data.operation) {
      case 'create':
      case 'update':
        this.updateFTSSearchIndex(data.note_id);
        break;
      case 'delete':
        this.removeFTSSearchIndex(data.note_id);
        break;
    }
  }

  private createTables(): void {
    if (!this.db) throw new Error('Database not initialized');

    this.db.exec(`
      CREATE TABLE IF NOT EXISTS schema_version (
        version INTEGER PRIMARY KEY,
        applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS notes (
        note_id TEXT PRIMARY KEY,
        title TEXT NOT NULL DEFAULT '',
        content TEXT NOT NULL DEFAULT '',
        content_type TEXT NOT NULL DEFAULT 'markdown',
        folder_id TEXT,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        word_count INTEGER NOT NULL DEFAULT 0,
        ai_summary TEXT,
        sync_status TEXT NOT NULL DEFAULT 'pending',
        version INTEGER NOT NULL DEFAULT 1,
        deleted_at TIMESTAMP,
        FOREIGN KEY (folder_id) REFERENCES folders (folder_id)
      );

      CREATE TABLE IF NOT EXISTS folders (
        folder_id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        parent_id TEXT,
        order_index INTEGER NOT NULL DEFAULT 0,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        deleted_at TIMESTAMP,
        FOREIGN KEY (parent_id) REFERENCES folders (folder_id)
      );

      CREATE TABLE IF NOT EXISTS tags (
        tag_id TEXT PRIMARY KEY,
        name TEXT NOT NULL UNIQUE,
        color TEXT NOT NULL DEFAULT '#3b82f6',
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        deleted_at TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS note_tags (
        note_id TEXT NOT NULL,
        tag_id TEXT NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (note_id, tag_id),
        FOREIGN KEY (note_id) REFERENCES notes (note_id) ON DELETE CASCADE,
        FOREIGN KEY (tag_id) REFERENCES tags (tag_id) ON DELETE CASCADE
      );

      CREATE TABLE IF NOT EXISTS settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      );

      CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
        title,
        content,
        tags,
        content='notes',
        content_rowid='rowid'
      );

      CREATE INDEX IF NOT EXISTS idx_notes_folder_id ON notes(folder_id) WHERE deleted_at IS NULL;
      CREATE INDEX IF NOT EXISTS idx_notes_updated_at ON notes(updated_at) WHERE deleted_at IS NULL;
      CREATE INDEX IF NOT EXISTS idx_notes_sync_status ON notes(sync_status) WHERE deleted_at IS NULL;
      CREATE INDEX IF NOT EXISTS idx_folders_parent_id ON folders(parent_id) WHERE deleted_at IS NULL;
      CREATE INDEX IF NOT EXISTS idx_tags_name ON tags(name) WHERE deleted_at IS NULL;
      CREATE INDEX IF NOT EXISTS idx_note_tags_note_id ON note_tags(note_id);
      CREATE INDEX IF NOT EXISTS idx_note_tags_tag_id ON note_tags(tag_id);
    `);
  }

  private migrate(): void {
    if (!this.db) throw new Error('Database not initialized');

    const row = this.db.prepare('SELECT version FROM schema_version ORDER BY version DESC LIMIT 1').get() as { version: number } | undefined;
    const currentVersion = row?.version || 0;

    if (currentVersion < SCHEMA_VERSION) {
      for (let v = currentVersion + 1; v <= SCHEMA_VERSION; v++) {
        this.runMigration(v);
      }
      this.db.prepare('INSERT OR REPLACE INTO schema_version (version) VALUES (?)').run(SCHEMA_VERSION);
    }
  }

  private runMigration(version: number): void {
    if (!this.db) throw new Error('Database not initialized');
    switch (version) {
      case 1:
        break;
      default:
        throw new Error(`Unknown migration version: ${version}`);
    }
  }

  private initializeSettings(): void {
    if (!this.db) throw new Error('Database not initialized');

    const defaultSettings: Record<string, string> = {
      theme: 'system',
      language: 'zh-CN',
      auto_save: 'true',
    };

    const insertStmt = this.db.prepare(
      'INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)'
    );

    for (const [key, value] of Object.entries(defaultSettings)) {
      insertStmt.run(key, value);
    }
  }

  public createNote(note: Omit<Note, 'note_id' | 'created_at' | 'updated_at' | 'version'>): Note {
    if (!this.db) throw new Error('Database not initialized');

    const noteId = uuidv4();
    const now = new Date().toISOString();
    const tags = note.tags || [];

    const insertStmt = this.db.prepare(`
      INSERT INTO notes (note_id, title, content, content_type, folder_id, created_at, updated_at, word_count, ai_summary, sync_status, version)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
    `);

    insertStmt.run(
      noteId,
      note.title,
      note.content,
      note.content_type,
      note.folder_id,
      now,
      now,
      note.word_count,
      note.ai_summary,
      'pending'
    );

    if (tags.length > 0) {
      const tagInsertStmt = this.db.prepare('INSERT OR IGNORE INTO tags (tag_id, name) VALUES (?, ?)');
      const linkStmt = this.db.prepare('INSERT OR IGNORE INTO note_tags (note_id, tag_id) VALUES (?, ?)');

      const transaction = this.db.transaction(() => {
        for (const tagName of tags) {
          const tagId = uuidv4();
          tagInsertStmt.run(tagId, tagName);
          linkStmt.run(noteId, tagId);
        }
      });
      transaction();
    }

    const createdNote = this.getNoteById(noteId)!;

    if (this.options.batchIndexUpdates && this.options.enableEventBus) {
      this.eventBus.emitBatched<IndexEventData>('index:update', {
        note_id: noteId,
        operation: 'create',
        fields: ['title', 'content', 'tags'],
      });
    } else {
      this.updateFTSSearchIndex(noteId);
    }

    if (this.options.enableEventBus) {
      this.eventBus.emit<NoteEventData>('note:created', {
        note_id: noteId,
        title: note.title,
        content: note.content,
        tags,
        folder_id: note.folder_id,
      }, 'database');
    }

    return createdNote;
  }

  public updateNote(noteId: string, updates: Partial<Omit<Note, 'note_id' | 'created_at' | 'version'>>): Note {
    if (!this.db) throw new Error('Database not initialized');

    const now = new Date().toISOString();
    const currentNote = this.getNoteById(noteId);

    if (!currentNote) {
      throw new Error(`Note not found: ${noteId}`);
    }

    const oldValues: NoteEventData['old_values'] = {};
    const updatedFields: string[] = [];

    if (updates.title !== undefined && updates.title !== currentNote.title) {
      oldValues.title = currentNote.title;
      updatedFields.push('title');
    }
    if (updates.content !== undefined && updates.content !== currentNote.content) {
      oldValues.content = currentNote.content;
      updatedFields.push('content');
    }
    if (updates.tags !== undefined && JSON.stringify(updates.tags) !== JSON.stringify(currentNote.tags)) {
      oldValues.tags = [...currentNote.tags];
      updatedFields.push('tags');
    }

    const updateFields: string[] = ['updated_at = ?', 'version = version + 1'];
    const updateValues: unknown[] = [now];

    if (updates.title !== undefined) {
      updateFields.push('title = ?');
      updateValues.push(updates.title);
    }
    if (updates.content !== undefined) {
      updateFields.push('content = ?');
      updateValues.push(updates.content);
    }
    if (updates.content_type !== undefined) {
      updateFields.push('content_type = ?');
      updateValues.push(updates.content_type);
    }
    if (updates.folder_id !== undefined) {
      updateFields.push('folder_id = ?');
      updateValues.push(updates.folder_id);
    }
    if (updates.word_count !== undefined) {
      updateFields.push('word_count = ?');
      updateValues.push(updates.word_count);
    }
    if (updates.ai_summary !== undefined) {
      updateFields.push('ai_summary = ?');
      updateValues.push(updates.ai_summary);
    }
    if (updates.sync_status !== undefined) {
      updateFields.push('sync_status = ?');
      updateValues.push(updates.sync_status);
    }

    updateValues.push(noteId);

    const query = `UPDATE notes SET ${updateFields.join(', ')} WHERE note_id = ?`;
    this.db.prepare(query).run(...updateValues);

    if (updates.tags !== undefined) {
      this.updateNoteTags(noteId, updates.tags);
    }

    const needsIndexUpdate = updatedFields.includes('title') || 
                             updatedFields.includes('content') || 
                             updatedFields.includes('tags');

    if (needsIndexUpdate) {
      if (this.options.batchIndexUpdates && this.options.enableEventBus) {
        this.eventBus.emitBatched<IndexEventData>('index:update', {
          note_id: noteId,
          operation: 'update',
          fields: updatedFields,
        });
      } else {
        this.updateFTSSearchIndex(noteId);
      }
    }

    const updatedNote = this.getNoteById(noteId)!;

    if (this.options.enableEventBus && updatedFields.length > 0) {
      this.eventBus.emit<NoteEventData>('note:updated', {
        note_id: noteId,
        title: updates.title,
        content: updates.content,
        tags: updates.tags,
        folder_id: updates.folder_id,
        old_values: oldValues,
      }, 'database');

      if (updatedFields.includes('content')) {
        this.eventBus.emit<NoteEventData>('note:content-changed', {
          note_id: noteId,
          content: updates.content,
          old_values: { content: oldValues.content },
        }, 'database');
      }

      if (updatedFields.includes('title')) {
        this.eventBus.emit<NoteEventData>('note:title-changed', {
          note_id: noteId,
          title: updates.title,
          old_values: { title: oldValues.title },
        }, 'database');
      }

      if (updatedFields.includes('tags')) {
        this.eventBus.emit<NoteEventData>('note:tags-changed', {
          note_id: noteId,
          tags: updates.tags,
          old_values: { tags: oldValues.tags },
        }, 'database');
      }
    }

    return updatedNote;
  }

  private updateNoteTags(noteId: string, newTags: string[]): void {
    if (!this.db) throw new Error('Database not initialized');

    const transaction = this.db.transaction(() => {
      this.db!.prepare('DELETE FROM note_tags WHERE note_id = ?').run(noteId);

      for (const tagName of newTags) {
        let tagRow = this.db!.prepare(
          'SELECT tag_id FROM tags WHERE name = ? AND deleted_at IS NULL'
        ).get(tagName) as { tag_id: string } | undefined;

        if (!tagRow) {
          const tagId = uuidv4();
          this.db!.prepare(
            'INSERT INTO tags (tag_id, name) VALUES (?, ?)'
          ).run(tagId, tagName);
          tagRow = { tag_id: tagId };
        }

        this.db!.prepare(
          'INSERT INTO note_tags (note_id, tag_id) VALUES (?, ?)'
        ).run(noteId, tagRow.tag_id);
      }
    });

    transaction();
  }

  public deleteNote(noteId: string): void {
    if (!this.db) throw new Error('Database not initialized');

    const now = new Date().toISOString();
    this.db.prepare('UPDATE notes SET deleted_at = ?, sync_status = ? WHERE note_id = ?')
      .run(now, 'pending', noteId);

    this.removeFTSSearchIndex(noteId);

    if (this.options.enableEventBus) {
      this.eventBus.emit<{ note_id: string }>('note:deleted', {
        note_id: noteId,
      }, 'database');

      if (this.options.batchIndexUpdates) {
        this.eventBus.emitBatched<IndexEventData>('index:update', {
          note_id: noteId,
          operation: 'delete',
        });
      }
    }
  }

  private removeFTSSearchIndex(noteId: string): void {
    if (!this.db) throw new Error('Database not initialized');

    this.db.prepare('DELETE FROM notes_fts WHERE rowid = (SELECT rowid FROM notes WHERE note_id = ?)')
      .run(noteId);
  }

  public getNoteById(noteId: string): Note | null {
    if (!this.db) throw new Error('Database not initialized');

    const row = this.db.prepare(`
      SELECT n.*, 
             (SELECT GROUP_CONCAT(t.name, ',') FROM note_tags nt JOIN tags t ON nt.tag_id = t.tag_id WHERE nt.note_id = n.note_id) as tags_string
      FROM notes n
      WHERE n.note_id = ? AND n.deleted_at IS NULL
    `).get(noteId) as (Record<string, unknown> & { tags_string?: string }) | undefined;

    if (!row) return null;

    return this.mapNoteRow(row);
  }

  public getNotes(folderId?: string, limit?: number, offset?: number): Note[] {
    if (!this.db) throw new Error('Database not initialized');

    let query = `
      SELECT n.*, 
             (SELECT GROUP_CONCAT(t.name, ',') FROM note_tags nt JOIN tags t ON nt.tag_id = t.tag_id WHERE nt.note_id = n.note_id) as tags_string
      FROM notes n
      WHERE n.deleted_at IS NULL
    `;
    const params: unknown[] = [];

    if (folderId) {
      query += ' AND n.folder_id = ?';
      params.push(folderId);
    } else {
      query += ' AND n.folder_id IS NULL';
    }

    query += ' ORDER BY n.updated_at DESC';

    if (limit) {
      query += ' LIMIT ?';
      params.push(limit);
      if (offset) {
        query += ' OFFSET ?';
        params.push(offset);
      }
    }

    const rows = this.db.prepare(query).all(...params) as Array<Record<string, unknown> & { tags_string?: string }>;

    return rows.map(row => this.mapNoteRow(row));
  }

  public getNotesCount(folderId?: string): number {
    if (!this.db) throw new Error('Database not initialized');

    let query = 'SELECT COUNT(*) as count FROM notes WHERE deleted_at IS NULL';
    const params: unknown[] = [];

    if (folderId) {
      query += ' AND folder_id = ?';
      params.push(folderId);
    } else {
      query += ' AND folder_id IS NULL';
    }

    const result = this.db.prepare(query).get(...params) as { count: number };
    return result.count;
  }

  public createFolder(folder: Omit<Folder, 'folder_id' | 'created_at' | 'updated_at'>): Folder {
    if (!this.db) throw new Error('Database not initialized');

    const folderId = uuidv4();
    const now = new Date().toISOString();

    this.db.prepare(`
      INSERT INTO folders (folder_id, name, parent_id, order_index, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(folderId, folder.name, folder.parent_id, folder.order_index, now, now);

    if (this.options.enableEventBus) {
      this.eventBus.emit('folder:created', {
        folder_id: folderId,
        name: folder.name,
        parent_id: folder.parent_id,
      }, 'database');
    }

    return this.getFolderById(folderId)!;
  }

  public updateFolder(folderId: string, updates: Partial<Omit<Folder, 'folder_id' | 'created_at'>>): Folder {
    if (!this.db) throw new Error('Database not initialized');

    const now = new Date().toISOString();
    const updateFields: string[] = ['updated_at = ?'];
    const updateValues: unknown[] = [now];

    if (updates.name !== undefined) {
      updateFields.push('name = ?');
      updateValues.push(updates.name);
    }
    if (updates.parent_id !== undefined) {
      updateFields.push('parent_id = ?');
      updateValues.push(updates.parent_id);
    }
    if (updates.order_index !== undefined) {
      updateFields.push('order_index = ?');
      updateValues.push(updates.order_index);
    }

    updateValues.push(folderId);

    const query = `UPDATE folders SET ${updateFields.join(', ')} WHERE folder_id = ?`;
    this.db.prepare(query).run(...updateValues);

    if (this.options.enableEventBus) {
      this.eventBus.emit('folder:updated', {
        folder_id: folderId,
        ...updates,
      }, 'database');
    }

    return this.getFolderById(folderId)!;
  }

  public deleteFolder(folderId: string): void {
    if (!this.db) throw new Error('Database not initialized');

    const now = new Date().toISOString();
    this.db.prepare('UPDATE folders SET deleted_at = ? WHERE folder_id = ?').run(now, folderId);
    this.db.prepare('UPDATE notes SET folder_id = NULL, sync_status = ? WHERE folder_id = ?').run('pending', folderId);

    if (this.options.enableEventBus) {
      this.eventBus.emit('folder:deleted', {
        folder_id: folderId,
      }, 'database');
    }
  }

  public getFolderById(folderId: string): Folder | null {
    if (!this.db) throw new Error('Database not initialized');

    const row = this.db.prepare(
      'SELECT * FROM folders WHERE folder_id = ? AND deleted_at IS NULL'
    ).get(folderId) as Record<string, unknown> | undefined;

    if (!row) return null;

    return this.mapFolderRow(row);
  }

  public getFolders(parentId?: string): Folder[] {
    if (!this.db) throw new Error('Database not initialized');

    let query = 'SELECT * FROM folders WHERE deleted_at IS NULL';
    const params: unknown[] = [];

    if (parentId) {
      query += ' AND parent_id = ?';
      params.push(parentId);
    } else {
      query += ' AND parent_id IS NULL';
    }

    query += ' ORDER BY order_index ASC';

    const rows = this.db.prepare(query).all(...params) as Array<Record<string, unknown>>;

    return rows.map(row => this.mapFolderRow(row));
  }

  public createTag(tag: Omit<Tag, 'tag_id' | 'created_at'>): Tag {
    if (!this.db) throw new Error('Database not initialized');

    const tagId = uuidv4();
    const now = new Date().toISOString();

    this.db.prepare(`
      INSERT INTO tags (tag_id, name, color, created_at)
      VALUES (?, ?, ?, ?)
    `).run(tagId, tag.name, tag.color, now);

    if (this.options.enableEventBus) {
      this.eventBus.emit('tag:created', {
        tag_id: tagId,
        name: tag.name,
        color: tag.color,
      }, 'database');
    }

    return this.getTagById(tagId)!;
  }

  public updateTag(tagId: string, updates: Partial<Omit<Tag, 'tag_id' | 'created_at'>>): Tag {
    if (!this.db) throw new Error('Database not initialized');

    if (updates.name !== undefined) {
      this.db.prepare('UPDATE tags SET name = ? WHERE tag_id = ?')
        .run(updates.name, tagId);
    }
    if (updates.color !== undefined) {
      this.db.prepare('UPDATE tags SET color = ? WHERE tag_id = ?')
        .run(updates.color, tagId);
    }

    if (this.options.enableEventBus) {
      this.eventBus.emit('tag:updated', {
        tag_id: tagId,
        ...updates,
      }, 'database');
    }

    return this.getTagById(tagId)!;
  }

  public deleteTag(tagId: string): void {
    if (!this.db) throw new Error('Database not initialized');

    const now = new Date().toISOString();
    this.db.prepare('UPDATE tags SET deleted_at = ? WHERE tag_id = ?').run(now, tagId);

    if (this.options.enableEventBus) {
      this.eventBus.emit('tag:deleted', {
        tag_id: tagId,
      }, 'database');
    }
  }

  public getTagById(tagId: string): Tag | null {
    if (!this.db) throw new Error('Database not initialized');

    const row = this.db.prepare(
      'SELECT * FROM tags WHERE tag_id = ? AND deleted_at IS NULL'
    ).get(tagId) as Record<string, unknown> | undefined;

    if (!row) return null;

    return {
      tag_id: row.tag_id as string,
      name: row.name as string,
      color: row.color as string,
      created_at: row.created_at as string,
    };
  }

  public getTags(): Tag[] {
    if (!this.db) throw new Error('Database not initialized');

    const rows = this.db.prepare(
      'SELECT * FROM tags WHERE deleted_at IS NULL ORDER BY name ASC'
    ).all() as Array<Record<string, unknown>>;

    return rows.map(row => ({
      tag_id: row.tag_id as string,
      name: row.name as string,
      color: row.color as string,
      created_at: row.created_at as string,
    }));
  }

  public updateFTSSearchIndex(noteId: string): void {
    if (!this.db) throw new Error('Database not initialized');

    const note = this.getNoteById(noteId);
    if (!note) return;

    const row = this.db.prepare('SELECT rowid FROM notes WHERE note_id = ?').get(noteId) as { rowid: number } | undefined;
    if (!row) return;

    const tagsString = note.tags.join(' ');

    this.db.prepare(`
      INSERT OR REPLACE INTO notes_fts (rowid, title, content, tags)
      VALUES (?, ?, ?, ?)
    `).run(row.rowid, note.title, note.content, tagsString);
  }

  public rebuildFTSSearchIndex(): void {
    if (!this.db) throw new Error('Database not initialized');

    const transaction = this.db.transaction(() => {
      this.db!.prepare('DELETE FROM notes_fts').run();

      const notes = this.db!.prepare(`
        SELECT n.rowid, n.title, n.content,
               (SELECT GROUP_CONCAT(t.name, ' ') FROM note_tags nt JOIN tags t ON nt.tag_id = t.tag_id WHERE nt.note_id = n.note_id) as tags_string
        FROM notes n
        WHERE n.deleted_at IS NULL
      `).all() as Array<{ rowid: number; title: string; content: string; tags_string?: string }>;

      const insertStmt = this.db!.prepare(`
        INSERT INTO notes_fts (rowid, title, content, tags)
        VALUES (?, ?, ?, ?)
      `);

      for (const note of notes) {
        insertStmt.run(note.rowid, note.title, note.content, note.tags_string || '');
      }
    });

    transaction();
  }

  public getSettings(): AppSettings {
    if (!this.db) throw new Error('Database not initialized');

    const rows = this.db.prepare('SELECT key, value FROM settings').all() as Array<{ key: string; value: string }>;
    const settingsMap: Record<string, string> = {};

    for (const row of rows) {
      settingsMap[row.key] = row.value;
    }

    let syncConfig: SyncConfig | null = null;
    if (settingsMap.sync_config) {
      try {
        syncConfig = JSON.parse(settingsMap.sync_config);
      } catch {
        syncConfig = null;
      }
    }

    let aiConfig: AIConfig | null = null;
    if (settingsMap.ai_config) {
      try {
        aiConfig = JSON.parse(settingsMap.ai_config);
      } catch {
        aiConfig = null;
      }
    }

    return {
      theme: (settingsMap.theme as 'light' | 'dark' | 'system') || 'system',
      language: settingsMap.language || 'zh-CN',
      auto_save: settingsMap.auto_save === 'true',
      sync_config: syncConfig,
      ai_config: aiConfig,
    };
  }

  public updateSettings(settings: Partial<AppSettings>): void {
    if (!this.db) throw new Error('Database not initialized');

    const transaction = this.db.transaction(() => {
      if (settings.theme !== undefined) {
        this.db!.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)')
          .run('theme', settings.theme);
      }
      if (settings.language !== undefined) {
        this.db!.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)')
          .run('language', settings.language);
      }
      if (settings.auto_save !== undefined) {
        this.db!.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)')
          .run('auto_save', settings.auto_save ? 'true' : 'false');
      }
      if (settings.sync_config !== undefined) {
        const value = settings.sync_config ? JSON.stringify(settings.sync_config) : '';
        this.db!.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)')
          .run('sync_config', value);
      }
      if (settings.ai_config !== undefined) {
        const value = settings.ai_config ? JSON.stringify(settings.ai_config) : '';
        this.db!.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)')
          .run('ai_config', value);
      }
    });

    transaction();
  }

  public getNotesForSync(): Note[] {
    if (!this.db) throw new Error('Database not initialized');

    const rows = this.db.prepare(`
      SELECT n.*, 
             (SELECT GROUP_CONCAT(t.name, ',') FROM note_tags nt JOIN tags t ON nt.tag_id = t.tag_id WHERE nt.note_id = n.note_id) as tags_string
      FROM notes n
      WHERE n.sync_status != 'synced'
    `).all() as Array<Record<string, unknown> & { tags_string?: string }>;

    return rows.map(row => this.mapNoteRow(row));
  }

  private mapNoteRow(row: Record<string, unknown> & { tags_string?: string }): Note {
    let tags: string[] = [];
    if (row.tags_string) {
      tags = row.tags_string.split(',').filter(t => t.trim());
    }

    return {
      note_id: row.note_id as string,
      title: row.title as string,
      content: row.content as string,
      content_type: row.content_type as 'markdown' | 'rich-text',
      tags,
      folder_id: row.folder_id as string | null,
      created_at: row.created_at as string,
      updated_at: row.updated_at as string,
      word_count: row.word_count as number,
      ai_summary: row.ai_summary as string | null,
      sync_status: row.sync_status as 'synced' | 'pending' | 'error',
      version: row.version as number,
    };
  }

  private mapFolderRow(row: Record<string, unknown>): Folder {
    return {
      folder_id: row.folder_id as string,
      name: row.name as string,
      parent_id: row.parent_id as string | null,
      order_index: row.order_index as number,
      created_at: row.created_at as string,
      updated_at: row.updated_at as string,
    };
  }

  public close(): void {
    if (this.db) {
      this.db.close();
      this.db = null;
    }
  }

  public getDatabase(): Database.Database {
    if (!this.db) {
      throw new Error('Database not initialized');
    }
    return this.db;
  }
}
