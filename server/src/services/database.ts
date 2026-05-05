import Database from 'better-sqlite3';
import { v4 as uuidv4 } from 'uuid';
import { join } from 'path';
import { existsSync, mkdirSync } from 'fs';
import { TranscribeRecord, Segment, DatabaseRecord } from '../types';

const DATA_DIR = join(process.cwd(), 'data');

if (!existsSync(DATA_DIR)) {
  mkdirSync(DATA_DIR, { recursive: true });
}

const dbPath = join(DATA_DIR, 'voicetrans.db');
const db = new Database(dbPath);

db.exec(`
  CREATE TABLE IF NOT EXISTS transcribe_records (
    transcribe_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    audio_language TEXT NOT NULL,
    target_language TEXT,
    segments TEXT NOT NULL,
    total_duration INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
  );

  CREATE INDEX IF NOT EXISTS idx_session_id ON transcribe_records(session_id);
  CREATE INDEX IF NOT EXISTS idx_created_at ON transcribe_records(created_at);
`);

export class DatabaseService {
  static createRecord(record: Omit<TranscribeRecord, 'transcribeId' | 'createdAt'>): TranscribeRecord {
    const transcribeId = uuidv4();
    const createdAt = new Date().toISOString();
    const segmentsJson = JSON.stringify(record.segments);

    const stmt = db.prepare(`
      INSERT INTO transcribe_records (
        transcribe_id, session_id, audio_language, target_language,
        segments, total_duration, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
    `);

    stmt.run(
      transcribeId,
      record.sessionId,
      record.audioLanguage,
      record.targetLanguage || null,
      segmentsJson,
      record.totalDuration,
      createdAt
    );

    return {
      transcribeId,
      ...record,
      createdAt,
    };
  }

  static updateRecord(transcribeId: string, updates: {
    segments?: Segment[];
    totalDuration?: number;
  }): void {
    const current = this.getRecord(transcribeId);
    if (!current) return;

    const segments = updates.segments || current.segments;
    const totalDuration = updates.totalDuration ?? current.totalDuration;
    const segmentsJson = JSON.stringify(segments);

    const stmt = db.prepare(`
      UPDATE transcribe_records
      SET segments = ?, total_duration = ?
      WHERE transcribe_id = ?
    `);

    stmt.run(segmentsJson, totalDuration, transcribeId);
  }

  static getRecord(transcribeId: string): TranscribeRecord | null {
    const stmt = db.prepare(`
      SELECT * FROM transcribe_records WHERE transcribe_id = ?
    `);

    const row = stmt.get(transcribeId) as DatabaseRecord | undefined;
    if (!row) return null;

    return this.mapRowToRecord(row);
  }

  static getRecordsBySession(sessionId: string): TranscribeRecord[] {
    const stmt = db.prepare(`
      SELECT * FROM transcribe_records 
      WHERE session_id = ? 
      ORDER BY created_at DESC
    `);

    const rows = stmt.all(sessionId) as DatabaseRecord[];
    return rows.map(row => this.mapRowToRecord(row));
  }

  static getHistory(
    options: {
      limit?: number;
      offset?: number;
      startTime?: string;
      endTime?: string;
    } = {}
  ): { records: TranscribeRecord[]; total: number } {
    const { limit = 20, offset = 0, startTime, endTime } = options;

    let whereClause = '1=1';
    const params: (string | number)[] = [];

    if (startTime) {
      whereClause += ' AND created_at >= ?';
      params.push(startTime);
    }
    if (endTime) {
      whereClause += ' AND created_at <= ?';
      params.push(endTime);
    }

    const countStmt = db.prepare(`
      SELECT COUNT(*) as count FROM transcribe_records WHERE ${whereClause}
    `);
    const countResult = countStmt.get(...params) as { count: number };
    const total = countResult.count;

    const selectStmt = db.prepare(`
      SELECT * FROM transcribe_records 
      WHERE ${whereClause}
      ORDER BY created_at DESC
      LIMIT ? OFFSET ?
    `);

    const rows = selectStmt.all(...params, limit, offset) as DatabaseRecord[];
    const records = rows.map(row => this.mapRowToRecord(row));

    return { records, total };
  }

  static deleteRecord(transcribeId: string): void {
    const stmt = db.prepare(`
      DELETE FROM transcribe_records WHERE transcribe_id = ?
    `);
    stmt.run(transcribeId);
  }

  private static mapRowToRecord(row: DatabaseRecord): TranscribeRecord {
    return {
      transcribeId: row.transcribe_id,
      sessionId: row.session_id,
      audioLanguage: row.audio_language,
      targetLanguage: row.target_language ?? undefined,
      segments: JSON.parse(row.segments) as Segment[],
      totalDuration: row.total_duration,
      createdAt: row.created_at,
    };
  }
}
