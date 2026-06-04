import type Database from 'better-sqlite3';

export abstract class BaseRepository {
  protected db: Database.Database;

  constructor(db: Database.Database) {
    this.db = db;
  }

  protected transaction<T>(fn: () => T): T {
    const tx = this.db.transaction(fn);
    return tx();
  }

  protected prepare(sql: string): Database.Statement {
    return this.db.prepare(sql);
  }

  protected exec(sql: string): void {
    this.db.exec(sql);
  }
}
