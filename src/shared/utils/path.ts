import * as path from 'path';
import os from 'os';

export function normalizePath(p: string): string {
  return path.normalize(p).replace(/\\/g, '/');
}

export function joinPaths(...paths: string[]): string {
  return normalizePath(path.join(...paths));
}

export function getBasename(p: string, ext?: string): string {
  return path.basename(p, ext);
}

export function getDirname(p: string): string {
  return normalizePath(path.dirname(p));
}

export function getExtname(p: string): string {
  return path.extname(p);
}

export function isAbsolute(p: string): boolean {
  return path.isAbsolute(p);
}

export function resolvePath(...paths: string[]): string {
  return normalizePath(path.resolve(...paths));
}

export function relativePath(from: string, to: string): string {
  return normalizePath(path.relative(from, to));
}

export function getHomeDir(): string {
  return normalizePath(os.homedir());
}

export function getDocumentsDir(): string {
  const home = getHomeDir();
  const platform = process.platform;
  
  if (platform === 'darwin') {
    return joinPaths(home, 'Documents');
  } else if (platform === 'win32') {
    return joinPaths(home, 'Documents');
  } else {
    return joinPaths(home, 'Documents');
  }
}

export function getDefaultRepoPath(): string {
  return joinPaths(getDocumentsDir(), 'KnowledgeForge');
}

export function isMarkdownFile(p: string): boolean {
  const ext = getExtname(p).toLowerCase();
  return ['.md', '.markdown', '.mdx'].includes(ext);
}

export function ensureMarkdownExt(p: string): string {
  if (isMarkdownFile(p)) return p;
  return p + '.md';
}

export function isChildPath(parent: string, child: string): boolean {
  const parentNorm = normalizePath(parent).replace(/\/$/, '');
  const childNorm = normalizePath(child).replace(/\/$/, '');
  return childNorm.startsWith(parentNorm + '/');
}

export function isPathSafe(basePath: string, targetPath: string): boolean {
  const resolvedBase = resolvePath(basePath);
  const resolvedTarget = resolvePath(targetPath);
  return isChildPath(resolvedBase, resolvedTarget) || resolvedBase === resolvedTarget;
}

export function parseFileUrl(url: string): string {
  if (url.startsWith('file://')) {
    return decodeURIComponent(url.slice(7));
  }
  return url;
}

export function toFileUrl(p: string): string {
  return 'file://' + encodeURIComponent(p).replace(/%2F/g, '/');
}

export function getUniqueFileName(dir: string, name: string): string {
  const base = getBasename(name);
  const ext = getExtname(name);
  let counter = 1;
  let result = ensureMarkdownExt(name);
  
  while (true) {
    const fullPath = joinPaths(dir, result);
    try {
      require('fs').accessSync(fullPath);
      result = `${base}-${counter}${ext || '.md'}`;
      counter++;
    } catch {
      return result;
    }
  }
}
