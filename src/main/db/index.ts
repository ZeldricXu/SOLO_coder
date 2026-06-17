import Database from 'better-sqlite3';
import path from 'path';
import fs from 'fs';
import { app } from 'electron';

let db: Database.Database | null = null;

export function getDatabase(): Database.Database {
  if (!db) {
    const userDataPath = app.getPath('userData');
    const dbPath = path.join(userDataPath, 'knowledge-notes.db');
    
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    
    db = new Database(dbPath);
    db.pragma('journal_mode = WAL');
    db.pragma('foreign_keys = ON');
    
    initSchema(db);
  }
  return db;
}

function initSchema(db: Database.Database) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS notes (
      id TEXT PRIMARY KEY,
      title TEXT NOT NULL,
      path TEXT UNIQUE NOT NULL,
      content TEXT,
      tags TEXT DEFAULT '[]',
      frontmatter TEXT DEFAULT '{}',
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );
    
    CREATE INDEX IF NOT EXISTS idx_notes_title ON notes(title);
    CREATE INDEX IF NOT EXISTS idx_notes_path ON notes(path);
    
    CREATE TABLE IF NOT EXISTS links (
      id TEXT PRIMARY KEY,
      source_id TEXT NOT NULL,
      target_id TEXT,
      source_path TEXT NOT NULL,
      target_path TEXT NOT NULL,
      link_text TEXT NOT NULL,
      context TEXT,
      created_at INTEGER NOT NULL,
      FOREIGN KEY (source_id) REFERENCES notes(id) ON DELETE CASCADE,
      FOREIGN KEY (target_id) REFERENCES notes(id) ON DELETE SET NULL
    );
    
    CREATE INDEX IF NOT EXISTS idx_links_source ON links(source_id);
    CREATE INDEX IF NOT EXISTS idx_links_target ON links(target_id);
    CREATE INDEX IF NOT EXISTS idx_links_source_path ON links(source_path);
    CREATE INDEX IF NOT EXISTS idx_links_target_path ON links(target_path);
    
    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT
    );
    
    CREATE TABLE IF NOT EXISTS tags (
      id TEXT PRIMARY KEY,
      name TEXT UNIQUE NOT NULL,
      color TEXT,
      created_at INTEGER NOT NULL
    );
    
    CREATE TABLE IF NOT EXISTS note_tags (
      note_id TEXT NOT NULL,
      tag_id TEXT NOT NULL,
      PRIMARY KEY (note_id, tag_id),
      FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
      FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
    );
  `);
}

export function closeDatabase() {
  if (db) {
    db.close();
    db = null;
  }
}
