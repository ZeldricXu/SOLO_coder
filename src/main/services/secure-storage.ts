import { safeStorage, app } from 'electron';
import Database from 'better-sqlite3';
import { DatabaseService } from './database';

export interface StoredCredential {
  id: string;
  service: string;
  account: string;
  encryptedData: Buffer;
  createdAt: string;
  updatedAt: string;
}

export class SecureStorageService {
  private static instance: SecureStorageService;
  private dbService: DatabaseService | null = null;
  private encryptionAvailable: boolean = false;
  private isReady: boolean = false;

  private constructor() {}

  public static getInstance(): SecureStorageService {
    if (!SecureStorageService.instance) {
      SecureStorageService.instance = new SecureStorageService();
    }
    return SecureStorageService.instance;
  }

  public initialize(dbService: DatabaseService): void {
    this.dbService = dbService;
    
    try {
      this.encryptionAvailable = safeStorage.isEncryptionAvailable();
      
      if (!this.encryptionAvailable) {
        console.warn('SafeStorage encryption is not available. Credentials will be stored in plain text.');
      }
      
      this.ensureTable();
      this.isReady = true;
      
      console.log(`SecureStorage initialized. Encryption: ${this.encryptionAvailable ? 'Available' : 'Not Available'}`);
    } catch (error) {
      console.error('Failed to initialize SecureStorage:', error);
      this.encryptionAvailable = false;
    }
  }

  private ensureTable(): void {
    if (!this.dbService) return;

    const db = this.dbService.getDatabase();
    db.exec(`
      CREATE TABLE IF NOT EXISTS secure_credentials (
        id TEXT PRIMARY KEY,
        service TEXT NOT NULL,
        account TEXT NOT NULL DEFAULT 'default',
        encrypted_data BLOB NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE(service, account)
      );

      CREATE INDEX IF NOT EXISTS idx_secure_credentials_service 
        ON secure_credentials(service);
    `);
  }

  private getDb(): Database.Database {
    if (!this.dbService) {
      throw new Error('SecureStorageService not initialized');
    }
    return this.dbService.getDatabase();
  }

  public async storeCredential(
    service: string,
    account: string,
    data: Record<string, unknown>
  ): Promise<boolean> {
    if (!this.isReady) {
      throw new Error('SecureStorageService not ready');
    }

    const db = this.getDb();
    const jsonData = JSON.stringify(data);
    
    let encryptedBuffer: Buffer;
    
    if (this.encryptionAvailable) {
      try {
        encryptedBuffer = safeStorage.encryptString(jsonData);
      } catch (error) {
        console.error('Failed to encrypt credential:', error);
        encryptedBuffer = Buffer.from(jsonData, 'utf-8');
      }
    } else {
      encryptedBuffer = Buffer.from(jsonData, 'utf-8');
    }

    const now = new Date().toISOString();
    
    const stmt = db.prepare(`
      INSERT INTO secure_credentials (id, service, account, encrypted_data, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(service, account) DO UPDATE SET
        encrypted_data = excluded.encrypted_data,
        updated_at = excluded.updated_at
    `);

    const id = `${service}_${account}`;
    stmt.run(id, service, account, encryptedBuffer, now, now);

    return true;
  }

  public async getCredential(
    service: string,
    account: string = 'default'
  ): Promise<Record<string, unknown> | null> {
    if (!this.isReady) {
      throw new Error('SecureStorageService not ready');
    }

    const db = this.getDb();

    const row = db.prepare(`
      SELECT encrypted_data FROM secure_credentials
      WHERE service = ? AND account = ?
    `).get(service, account) as { encrypted_data: Buffer } | undefined;

    if (!row) {
      return null;
    }

    try {
      let jsonString: string;
      
      if (this.encryptionAvailable) {
        try {
          jsonString = safeStorage.decryptString(row.encrypted_data);
        } catch (error) {
          console.error('Failed to decrypt credential, trying plain text:', error);
          jsonString = row.encrypted_data.toString('utf-8');
        }
      } else {
        jsonString = row.encrypted_data.toString('utf-8');
      }

      return JSON.parse(jsonString);
    } catch (error) {
      console.error('Failed to parse credential:', error);
      return null;
    }
  }

  public async deleteCredential(
    service: string,
    account: string = 'default'
  ): Promise<boolean> {
    if (!this.isReady) {
      throw new Error('SecureStorageService not ready');
    }

    const db = this.getDb();

    const result = db.prepare(`
      DELETE FROM secure_credentials
      WHERE service = ? AND account = ?
    `).run(service, account);

    return result.changes > 0;
  }

  public async hasCredential(
    service: string,
    account: string = 'default'
  ): Promise<boolean> {
    if (!this.isReady) {
      return false;
    }

    const db = this.getDb();

    const row = db.prepare(`
      SELECT 1 FROM secure_credentials
      WHERE service = ? AND account = ?
    `).get(service, account);

    return !!row;
  }

  public isEncryptionAvailable(): boolean {
    return this.encryptionAvailable;
  }

  public async storeAIConfig(config: {
    api_url: string;
    api_key: string;
    model?: string;
    max_tokens?: number;
  }): Promise<boolean> {
    return this.storeCredential('ai-service', 'default', config);
  }

  public async getAIConfig(): Promise<{
    api_url: string;
    api_key: string;
    model?: string;
    max_tokens?: number;
  } | null> {
    const credential = await this.getCredential('ai-service', 'default');
    if (!credential) return null;
    
    return {
      api_url: credential.api_url as string,
      api_key: credential.api_key as string,
      model: credential.model as string | undefined,
      max_tokens: credential.max_tokens as number | undefined,
    };
  }

  public async deleteAIConfig(): Promise<boolean> {
    return this.deleteCredential('ai-service', 'default');
  }

  public async storeSyncConfig(config: {
    api_url: string;
    api_key: string;
    auto_sync?: boolean;
    sync_interval?: number;
  }): Promise<boolean> {
    return this.storeCredential('sync-service', 'default', config);
  }

  public async getSyncConfig(): Promise<{
    api_url: string;
    api_key: string;
    auto_sync?: boolean;
    sync_interval?: number;
  } | null> {
    const credential = await this.getCredential('sync-service', 'default');
    if (!credential) return null;
    
    return {
      api_url: credential.api_url as string,
      api_key: credential.api_key as string,
      auto_sync: credential.auto_sync as boolean | undefined,
      sync_interval: credential.sync_interval as number | undefined,
    };
  }

  public async deleteSyncConfig(): Promise<boolean> {
    return this.deleteCredential('sync-service', 'default');
  }
}
