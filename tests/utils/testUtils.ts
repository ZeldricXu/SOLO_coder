import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

export class TempVault {
  private dir: string;
  
  constructor() {
    this.dir = fs.mkdtempSync(path.join(os.tmpdir(), 'knowledge-notes-test-'));
  }
  
  getPath(): string {
    return this.dir;
  }
  
  createFile(relativePath: string, content: string): string {
    const fullPath = path.join(this.dir, relativePath);
    const dirPath = path.dirname(fullPath);
    
    if (!fs.existsSync(dirPath)) {
      fs.mkdirSync(dirPath, { recursive: true });
    }
    
    fs.writeFileSync(fullPath, content, 'utf-8');
    return fullPath;
  }
  
  readFile(relativePath: string): string {
    const fullPath = path.join(this.dir, relativePath);
    return fs.readFileSync(fullPath, 'utf-8');
  }
  
  deleteFile(relativePath: string): void {
    const fullPath = path.join(this.dir, relativePath);
    if (fs.existsSync(fullPath)) {
      fs.unlinkSync(fullPath);
    }
  }
  
  renameFile(oldPath: string, newPath: string): void {
    const oldFull = path.join(this.dir, oldPath);
    const newFull = path.join(this.dir, newPath);
    const newDir = path.dirname(newFull);
    
    if (!fs.existsSync(newDir)) {
      fs.mkdirSync(newDir, { recursive: true });
    }
    
    fs.renameSync(oldFull, newFull);
  }
  
  modifyFile(relativePath: string, content: string): void {
    const fullPath = path.join(this.dir, relativePath);
    fs.writeFileSync(fullPath, content, 'utf-8');
  }
  
  exists(relativePath: string): boolean {
    return fs.existsSync(path.join(this.dir, relativePath));
  }
  
  cleanup(): void {
    if (fs.existsSync(this.dir)) {
      fs.rmSync(this.dir, { recursive: true, force: true });
    }
  }
  
  createBatchFiles(count: number, prefix: string = 'note'): string[] {
    const paths: string[] = [];
    for (let i = 0; i < count; i++) {
      const content = `# ${prefix} ${i}\n\nContent of note ${i}.\n\n[[${prefix}-${Math.max(0, i - 1)}]]`;
      paths.push(this.createFile(`${prefix}-${i}.md`, content));
    }
    return paths;
  }
}

export function waitFor(condition: () => boolean | Promise<boolean>, timeout = 5000, interval = 50): Promise<void> {
  return new Promise((resolve, reject) => {
    const startTime = Date.now();
    
    const check = async () => {
      try {
        const result = await condition();
        if (result) {
          resolve();
          return;
        }
        
        if (Date.now() - startTime > timeout) {
          reject(new Error('Timeout waiting for condition'));
          return;
        }
        
        setTimeout(check, interval);
      } catch (err) {
        reject(err);
      }
    };
    
    check();
  });
}

export function measurePerformance<T>(fn: () => T | Promise<T>, iterations = 1): Promise<{ result: T; avgTime: number; totalTime: number }> {
  return new Promise(async (resolve) => {
    const start = process.hrtime.bigint();
    let result: T;
    
    for (let i = 0; i < iterations; i++) {
      result = await fn();
    }
    
    const end = process.hrtime.bigint();
    const totalTime = Number(end - start) / 1e6;
    const avgTime = totalTime / iterations;
    
    resolve({ result: result!, avgTime, totalTime });
  });
}

export function getCallCount(mockFn: jest.Mock): number {
  return mockFn.mock.calls.length;
}

export function getLastCallArgs<T = any>(mockFn: jest.Mock): T {
  return mockFn.mock.calls[mockFn.mock.calls.length - 1] as T;
}

export function getAllCallArgs<T = any>(mockFn: jest.Mock): T[] {
  return mockFn.mock.calls as T[];
}

export function createSpy(obj: any, method: string): jest.SpyInstance {
  return jest.spyOn(obj, method);
}

export async function flushPromises(): Promise<void> {
  await new Promise(resolve => setImmediate(resolve));
}

export function arrayEquals<T>(a: T[], b: T[]): boolean {
  if (a.length !== b.length) return false;
  return a.every((val, idx) => val === b[idx]);
}

export function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj));
}

export function generateId(prefix: string = 'id'): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}
