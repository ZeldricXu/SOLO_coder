import fs from 'fs';
import path from 'path';

export function ensureDirectory(dirPath: string): void {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

export function writeJsonFile<T>(filePath: string, data: T, prettyPrint: boolean = false): void {
  ensureDirectory(path.dirname(filePath));
  const content = prettyPrint ? JSON.stringify(data, null, 2) : JSON.stringify(data);
  fs.writeFileSync(filePath, content, 'utf8');
}

export function readJsonFile<T>(filePath: string): T | null {
  if (!fs.existsSync(filePath)) {
    return null;
  }
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    return JSON.parse(content) as T;
  } catch {
    return null;
  }
}

export function appendToFile(filePath: string, content: string): void {
  ensureDirectory(path.dirname(filePath));
  fs.appendFileSync(filePath, content, 'utf8');
}

export function deleteFile(filePath: string): boolean {
  if (!fs.existsSync(filePath)) return false;
  try {
    fs.unlinkSync(filePath);
    return true;
  } catch {
    return false;
  }
}

export function getFileSize(filePath: string): number | null {
  if (!fs.existsSync(filePath)) return null;
  try {
    return fs.statSync(filePath).size;
  } catch {
    return null;
  }
}

export function getFileModifiedTime(filePath: string): number | null {
  if (!fs.existsSync(filePath)) return null;
  try {
    return fs.statSync(filePath).mtimeMs;
  } catch {
    return null;
  }
}

export function watchFile(
  filePath: string,
  callback: (event: 'change' | 'rename', filePath: string) => void
): () => void {
  const watcher = fs.watch(filePath, (event, filename) => {
    callback(event, filename || filePath);
  });
  return () => watcher.close();
}

export function listFiles(dirPath: string, pattern?: RegExp): string[] {
  if (!fs.existsSync(dirPath)) return [];
  try {
    const files = fs.readdirSync(dirPath, { withFileTypes: true });
    return files
      .filter(f => f.isFile())
      .filter(f => !pattern || pattern.test(f.name))
      .map(f => f.name);
  } catch {
    return [];
  }
}
