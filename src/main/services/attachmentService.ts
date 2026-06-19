import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import type { AttachmentFile } from '../../shared/types';

const IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg', '.bmp'];
const PDF_EXTENSIONS = ['.pdf'];
const DOCUMENT_EXTENSIONS = ['.doc', '.docx', '.txt', '.md', '.rtf', '.odt'];

let vaultPath: string = '';

function getFileType(filePath: string): AttachmentFile['type'] {
  const ext = path.extname(filePath).toLowerCase();
  
  if (IMAGE_EXTENSIONS.includes(ext)) return 'image';
  if (PDF_EXTENSIONS.includes(ext)) return 'pdf';
  if (DOCUMENT_EXTENSIONS.includes(ext)) return 'document';
  return 'other';
}

function getMimeType(filePath: string): string {
  const ext = path.extname(filePath).toLowerCase();
  const mimeTypes: Record<string, string> = {
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.png': 'image/png',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.svg': 'image/svg+xml',
    '.bmp': 'image/bmp',
    '.pdf': 'application/pdf',
    '.doc': 'application/msword',
    '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    '.txt': 'text/plain',
    '.md': 'text/markdown',
    '.rtf': 'application/rtf',
    '.odt': 'application/vnd.oasis.opendocument.text',
  };
  return mimeTypes[ext] || 'application/octet-stream';
}

function generateId(): string {
  return 'att-' + crypto.randomBytes(8).toString('hex');
}

function getAssetsDir(): string {
  if (!vaultPath) {
    throw new Error('Vault path not set');
  }
  const assetsDir = path.join(vaultPath, 'assets');
  if (!fs.existsSync(assetsDir)) {
    fs.mkdirSync(assetsDir, { recursive: true });
  }
  return assetsDir;
}

function getAttachmentInfo(
  filePath: string,
  basePath: string
): AttachmentFile | null {
  try {
    const stats = fs.statSync(filePath);
    if (!stats.isFile()) return null;
    
    const relativePath = path.relative(basePath, filePath);
    const fileType = getFileType(filePath);
    
    return {
      id: generateId(),
      name: path.basename(filePath),
      path: filePath,
      relativePath,
      size: stats.size,
      type: fileType,
      mimeType: getMimeType(filePath),
      createdAt: stats.birthtimeMs,
      updatedAt: stats.mtimeMs,
    };
  } catch (err) {
    console.error('Error getting attachment info:', err);
    return null;
  }
}

function walkDirectory(dir: string, basePath: string, files: AttachmentFile[] = []): AttachmentFile[] {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  
  for (const entry of entries) {
    if (entry.name.startsWith('.')) continue;
    
    const fullPath = path.join(dir, entry.name);
    
    if (entry.isDirectory()) {
      if (entry.name === 'assets') {
        walkDirectory(fullPath, basePath, files);
      }
    } else {
      const info = getAttachmentInfo(fullPath, basePath);
      if (info && info.type !== 'other') {
        files.push(info);
      }
    }
  }
  
  return files;
}

export const AttachmentService = {
  init(vault: string) {
    vaultPath = vault;
    const assetsDir = path.join(vaultPath, 'assets');
    if (!fs.existsSync(assetsDir)) {
      fs.mkdirSync(assetsDir, { recursive: true });
    }
    return this;
  },

  getAssetsPath(): string {
    return getAssetsDir();
  },

  list(): AttachmentFile[] {
    const assetsDir = getAssetsDir();
    return walkDirectory(assetsDir, vaultPath);
  },

  async upload(filePath: string, targetDir?: string): Promise<AttachmentFile> {
    return new Promise((resolve, reject) => {
      try {
        if (!fs.existsSync(filePath)) {
          reject(new Error('Source file does not exist'));
          return;
        }
        
        const assetsDir = getAssetsDir();
        const destDir = targetDir 
          ? path.join(assetsDir, targetDir)
          : assetsDir;
        
        if (!fs.existsSync(destDir)) {
          fs.mkdirSync(destDir, { recursive: true });
        }
        
        const originalName = path.basename(filePath);
        let destPath = path.join(destDir, originalName);
        
        let counter = 1;
        while (fs.existsSync(destPath)) {
          const ext = path.extname(originalName);
          const nameWithoutExt = path.basename(originalName, ext);
          destPath = path.join(destDir, `${nameWithoutExt} (${counter})${ext}`);
          counter++;
        }
        
        fs.copyFileSync(filePath, destPath);
        
        const attachment = getAttachmentInfo(destPath, vaultPath);
        if (!attachment) {
          reject(new Error('Failed to get attachment info'));
          return;
        }
        
        resolve(attachment);
      } catch (err) {
        reject(err);
      }
    });
  },

  async uploadFromData(name: string, data: Buffer, targetDir?: string): Promise<{ attachment: AttachmentFile; relativePath: string }> {
    return new Promise((resolve, reject) => {
      try {
        const assetsDir = getAssetsDir();
        const destDir = targetDir 
          ? path.join(assetsDir, targetDir)
          : assetsDir;
        
        if (!fs.existsSync(destDir)) {
          fs.mkdirSync(destDir, { recursive: true });
        }
        
        let destPath = path.join(destDir, name);
        
        let counter = 1;
        while (fs.existsSync(destPath)) {
          const ext = path.extname(name);
          const nameWithoutExt = path.basename(name, ext);
          destPath = path.join(destDir, `${nameWithoutExt} (${counter})${ext}`);
          counter++;
        }
        
        fs.writeFileSync(destPath, data);
        
        const attachment = getAttachmentInfo(destPath, vaultPath);
        if (!attachment) {
          reject(new Error('Failed to get attachment info'));
          return;
        }
        
        const relativePath = path.relative(vaultPath, destPath);
        resolve({ attachment, relativePath });
      } catch (err) {
        reject(err);
      }
    });
  },

  delete(attachmentId: string): boolean {
    const attachments = this.list();
    const attachment = attachments.find(a => a.id === attachmentId);
    
    if (!attachment) return false;
    
    try {
      fs.unlinkSync(attachment.path);
      return true;
    } catch (err) {
      console.error('Error deleting attachment:', err);
      return false;
    }
  },

  rename(attachmentId: string, newName: string): AttachmentFile | null {
    const attachments = this.list();
    const attachment = attachments.find(a => a.id === attachmentId);
    
    if (!attachment) return null;
    
    try {
      const oldPath = attachment.path;
      const dir = path.dirname(oldPath);
      const newPath = path.join(dir, newName);
      
      if (fs.existsSync(newPath)) {
        return null;
      }
      
      fs.renameSync(oldPath, newPath);
      
      return getAttachmentInfo(newPath, vaultPath);
    } catch (err) {
      console.error('Error renaming attachment:', err);
      return null;
    }
  },

  getThumbnail(attachmentId: string): string | null {
    const attachments = this.list();
    const attachment = attachments.find(a => a.id === attachmentId);
    
    if (!attachment) return null;
    
    if (attachment.type === 'image') {
      try {
        const data = fs.readFileSync(attachment.path);
        const base64 = data.toString('base64');
        return `data:${attachment.mimeType};base64,${base64}`;
      } catch (err) {
        console.error('Error generating thumbnail:', err);
        return null;
      }
    }
    
    if (attachment.type === 'pdf') {
      return '📄';
    }
    
    if (attachment.type === 'document') {
      return '📝';
    }
    
    return '📎';
  },
};
