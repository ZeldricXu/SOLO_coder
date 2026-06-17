import { getDatabase } from './index';
import type { AppSettings, PanelLayout } from '../../shared/types';

const DEFAULT_SETTINGS: AppSettings = {
  vaultPath: '',
  theme: 'dark',
  layouts: [],
  activePlugins: ['backlinks', 'tags', 'command-palette'],
};

export const SettingsService = {
  get(): AppSettings {
    const db = getDatabase();
    const rows = db.prepare('SELECT key, value FROM settings').all() as { key: string; value: string }[];
    
    const settings = { ...DEFAULT_SETTINGS };
    
    for (const row of rows) {
      try {
        const parsed = JSON.parse(row.value);
        if (row.key in settings) {
          (settings as any)[row.key] = parsed;
        }
      } catch {
        if (row.key in settings && typeof (settings as any)[row.key] === 'string') {
          (settings as any)[row.key] = row.value;
        }
      }
    }
    
    return settings;
  },

  update(updates: Partial<AppSettings>): AppSettings {
    const db = getDatabase();
    const current = this.get();
    const merged = { ...current, ...updates };
    
    const insertStmt = db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)');
    
    for (const [key, value] of Object.entries(merged)) {
      insertStmt.run(key, JSON.stringify(value));
    }
    
    return merged;
  },

  getVaultPath(): string {
    return this.get().vaultPath;
  },

  setVaultPath(path: string): void {
    this.update({ vaultPath: path });
  },
};
