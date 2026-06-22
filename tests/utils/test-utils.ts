import { Volume } from 'memfs';
import { fs as memfsFs } from 'memfs';
import path from 'path';

export function createMockFs(initialFiles: Record<string, string> = {}) {
  const vol = new Volume();
  vol.fromJSON(initialFiles, '/');

  const mockFs = {
    ...memfsFs,
    volume: vol,
    pathExists: async (p: string) => {
      try {
        await memfsFs.promises.access(p);
        return true;
      } catch {
        return false;
      }
    },
    ensureDir: async (p: string) => {
      await memfsFs.promises.mkdir(p, { recursive: true });
    },
    readJson: async (p: string) => {
      const content = await memfsFs.promises.readFile(p, 'utf-8');
      return JSON.parse(content as string);
    },
    writeJson: async (p: string, data: unknown, options?: { spaces?: number }) => {
      const content = JSON.stringify(data, null, options?.spaces ?? 2);
      await memfsFs.promises.writeFile(p, content, 'utf-8');
    },
    copy: async (src: string, dest: string) => {
      const stat = await memfsFs.promises.stat(src);
      if (stat.isDirectory()) {
        await memfsFs.promises.mkdir(dest, { recursive: true });
        const entries = await memfsFs.promises.readdir(src);
        for (const entry of entries) {
          const srcPath = path.join(src, entry as string);
          const destPath = path.join(dest, entry as string);
          await mockFs.copy(srcPath, destPath);
        }
      } else {
        const dir = path.dirname(dest);
        await memfsFs.promises.mkdir(dir, { recursive: true });
        const content = await memfsFs.promises.readFile(src);
        await memfsFs.promises.writeFile(dest, content);
      }
    },
    remove: async (p: string) => {
      try {
        const stat = await memfsFs.promises.stat(p);
        if (stat.isDirectory()) {
          const entries = await memfsFs.promises.readdir(p);
          for (const entry of entries) {
            await mockFs.remove(path.join(p, entry as string));
          }
          await memfsFs.promises.rmdir(p);
        } else {
          await memfsFs.promises.unlink(p);
        }
      } catch {
        // 文件不存在则忽略
      }
    },
    outputFile: async (p: string, data: string | Buffer) => {
      const dir = path.dirname(p);
      await memfsFs.promises.mkdir(dir, { recursive: true });
      await memfsFs.promises.writeFile(p, data);
    },
    emptyDir: async (p: string) => {
      try {
        const entries = await memfsFs.promises.readdir(p);
        for (const entry of entries) {
          await mockFs.remove(path.join(p, entry as string));
        }
      } catch {
        await memfsFs.promises.mkdir(p, { recursive: true });
      }
    },
  };

  return mockFs;
}

export type MockFs = ReturnType<typeof createMockFs>;

export async function createTempRealDir(): Promise<string> {
  const os = await import('os');
  const fs = await import('fs/promises');
  const tmpDir = path.join(os.tmpdir(), `csp-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  await fs.mkdir(tmpDir, { recursive: true });
  return tmpDir;
}

export async function removeDir(dir: string): Promise<void> {
  const fs = await import('fs/promises');
  try {
    await fs.rm(dir, { recursive: true, force: true });
  } catch {
    // 忽略错误
  }
}

export function normalizePath(p: string): string {
  return p.split(path.sep).join('/');
}

export async function dirExists(dir: string): Promise<boolean> {
  const fs = await import('fs/promises');
  try {
    const stat = await fs.stat(dir);
    return stat.isDirectory();
  } catch {
    return false;
  }
}

export async function fileExists(file: string): Promise<boolean> {
  const fs = await import('fs/promises');
  try {
    const stat = await fs.stat(file);
    return stat.isFile();
  } catch {
    return false;
  }
}
