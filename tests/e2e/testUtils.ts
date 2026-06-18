import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

export class TestVault {
  private dir: string;

  constructor() {
    this.dir = fs.mkdtempSync(path.join(os.tmpdir(), 'test-vault-'));
  }

  getPath(): string {
    return this.dir;
  }

  createFile(relativePath: string, content: string): string {
    const fullPath = path.join(this.dir, relativePath);
    fs.mkdirSync(path.dirname(fullPath), { recursive: true });
    fs.writeFileSync(fullPath, content, 'utf-8');
    return fullPath;
  }

  readFile(relativePath: string): string {
    const fullPath = path.join(this.dir, relativePath);
    return fs.readFileSync(fullPath, 'utf-8');
  }

  exists(relativePath: string): boolean {
    const fullPath = path.join(this.dir, relativePath);
    return fs.existsSync(fullPath);
  }

  deleteFile(relativePath: string): void {
    const fullPath = path.join(this.dir, relativePath);
    if (fs.existsSync(fullPath)) {
      fs.unlinkSync(fullPath);
    }
  }

  renameFile(oldPath: string, newPath: string): void {
    const oldFullPath = path.join(this.dir, oldPath);
    const newFullPath = path.join(this.dir, newPath);
    fs.mkdirSync(path.dirname(newFullPath), { recursive: true });
    fs.renameSync(oldFullPath, newFullPath);
  }

  modifyFile(relativePath: string, content: string): void {
    const fullPath = path.join(this.dir, relativePath);
    fs.writeFileSync(fullPath, content, 'utf-8');
  }

  listAllFiles(): string[] {
    const files: string[] = [];
    
    function walk(dir: string, baseDir: string) {
      const entries = fs.readdirSync(dir, { withFileTypes: true });
      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(fullPath, baseDir);
        } else {
          files.push(path.relative(baseDir, fullPath));
        }
      }
    }
    
    walk(this.dir, this.dir);
    return files;
  }

  listMdFiles(): string[] {
    return this.listAllFiles().filter(f => f.endsWith('.md'));
  }

  cleanup(): void {
    if (fs.existsSync(this.dir)) {
      fs.rmSync(this.dir, { recursive: true, force: true });
    }
  }
}

export function createSampleNote(
  title: string,
  options: {
    tags?: string[];
    links?: string[];
    content?: string;
  } = {}
): string {
  const { tags = [], links = [], content = '' } = options;
  
  const frontmatter = tags.length > 0 
    ? `---\ntags: [${tags.join(', ')}]\n---\n\n` 
    : '';
  
  const linksSection = links.length > 0 
    ? '\n\n' + links.map(link => `[[${link}]]`).join('、') + '\n'
    : '';
  
  return `${frontmatter}# ${title}\n\n${content || '这是一篇测试笔记。'}${linksSection}`;
}

export function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export async function waitForCondition(
  condition: () => boolean | Promise<boolean>,
  timeout: number = 5000,
  interval: number = 100
): Promise<void> {
  const startTime = Date.now();
  
  while (Date.now() - startTime < timeout) {
    const result = await condition();
    if (result) return;
    await delay(interval);
  }
  
  throw new Error(`Timeout after ${timeout}ms waiting for condition`);
}

export function measureTime<T>(fn: () => T | Promise<T>): { result: T; duration: number } {
  const start = performance.now();
  const result = fn();
  
  if (result instanceof Promise) {
    throw new Error('measureTime does not support async functions, use measureTimeAsync instead');
  }
  
  return {
    result: result as T,
    duration: performance.now() - start,
  };
}

export async function measureTimeAsync<T>(
  fn: () => Promise<T>
): Promise<{ result: T; duration: number }> {
  const start = performance.now();
  const result = await fn();
  return {
    result,
    duration: performance.now() - start,
  };
}
