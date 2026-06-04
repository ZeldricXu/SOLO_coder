import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand, HeadObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { nanoid } from 'nanoid';

export interface UploadOptions {
  contentType?: string;
  folder?: string;
  acl?: 'public-read' | 'private';
  metadata?: Record<string, string>;
}

export interface FileInfo {
  url: string;
  key: string;
  size: number;
  contentType: string;
  etag?: string;
}

class S3StorageService {
  private client: S3Client;
  private bucket: string;
  private publicUrl: string;

  constructor() {
    const endpoint = process.env.S3_ENDPOINT || 'https://s3.amazonaws.com';
    const region = process.env.S3_REGION || 'us-east-1';
    const accessKeyId = process.env.S3_ACCESS_KEY_ID;
    const secretAccessKey = process.env.S3_SECRET_ACCESS_KEY;

    if (!accessKeyId || !secretAccessKey) {
      throw new Error('S3 credentials not configured');
    }

    this.bucket = process.env.S3_BUCKET_NAME || 'knowledge-hub';
    this.publicUrl = process.env.S3_PUBLIC_URL || endpoint;

    this.client = new S3Client({
      endpoint,
      region,
      credentials: {
        accessKeyId,
        secretAccessKey,
      },
      forcePathStyle: true,
    });
  }

  private generateKey(filename: string, folder?: string): string {
    const ext = filename.split('.').pop()?.toLowerCase() || '';
    const id = nanoid(16);
    const timestamp = Date.now();
    const prefix = folder ? `${folder}/` : '';
    return `${prefix}${timestamp}-${id}.${ext}`;
  }

  async uploadFile(
    file: Buffer | Uint8Array | Blob | string,
    filename: string,
    options: UploadOptions = {}
  ): Promise<FileInfo> {
    const {
      contentType = 'application/octet-stream',
      folder = 'uploads',
      acl = 'private',
      metadata = {},
    } = options;

    const key = this.generateKey(filename, folder);

    const command = new PutObjectCommand({
      Bucket: this.bucket,
      Key: key,
      Body: file as any,
      ContentType: contentType,
      ACL: acl,
      Metadata: metadata,
    });

    await this.client.send(command);

    const headCommand = new HeadObjectCommand({
      Bucket: this.bucket,
      Key: key,
    });

    const headResult = await this.client.send(headCommand);

    return {
      url: `${this.publicUrl}/${this.bucket}/${key}`,
      key,
      size: headResult.ContentLength || 0,
      contentType,
      etag: headResult.ETag,
    };
  }

  async uploadFileFromPath(
    filePath: string,
    filename: string,
    options: UploadOptions = {}
  ): Promise<FileInfo> {
    const fs = await import('fs/promises');
    const buffer = await fs.readFile(filePath);
    return this.uploadFile(buffer, filename, options);
  }

  async getPresignedUrl(key: string, expiresIn: number = 3600): Promise<string> {
    const command = new GetObjectCommand({
      Bucket: this.bucket,
      Key: key,
    });

    return getSignedUrl(this.client, command, { expiresIn });
  }

  async getFile(key: string): Promise<{
    body?: ReadableStream<any>;
    contentType?: string;
    contentLength?: number;
  }> {
    const command = new GetObjectCommand({
      Bucket: this.bucket,
      Key: key,
    });

    const result = await this.client.send(command);
    return {
      body: result.Body as ReadableStream<any>,
      contentType: result.ContentType,
      contentLength: result.ContentLength,
    };
  }

  async deleteFile(key: string): Promise<void> {
    const command = new DeleteObjectCommand({
      Bucket: this.bucket,
      Key: key,
    });

    await this.client.send(command);
  }

  async fileExists(key: string): Promise<boolean> {
    try {
      const command = new HeadObjectCommand({
        Bucket: this.bucket,
        Key: key,
      });

      await this.client.send(command);
      return true;
    } catch (error: any) {
      if (error.name === 'NotFound') {
        return false;
      }
      throw error;
    }
  }

  async uploadImage(
    file: Buffer | Uint8Array | Blob,
    filename: string,
    options: Omit<UploadOptions, 'folder'> = {}
  ): Promise<FileInfo> {
    return this.uploadFile(file, filename, {
      ...options,
      folder: 'images',
      acl: 'public-read',
    });
  }

  async uploadDocument(
    file: Buffer | Uint8Array | Blob,
    filename: string,
    options: Omit<UploadOptions, 'folder'> = {}
  ): Promise<FileInfo> {
    return this.uploadFile(file, filename, {
      ...options,
      folder: 'documents',
      acl: 'private',
    });
  }

  async uploadAvatar(
    file: Buffer | Uint8Array | Blob,
    userId: string
  ): Promise<FileInfo> {
    return this.uploadFile(file, `${userId}.png`, {
      folder: 'avatars',
      acl: 'public-read',
      contentType: 'image/png',
    });
  }
}

export const s3Storage = new S3StorageService();

export async function getPresignedUploadUrl(
  filename: string,
  contentType: string,
  folder: string = 'uploads'
): Promise<{ url: string; key: string }> {
  const key = `${folder}/${Date.now()}-${nanoid(16)}-${filename}`;

  const command = new PutObjectCommand({
    Bucket: process.env.S3_BUCKET_NAME,
    Key: key,
    ContentType: contentType,
    ACL: 'private',
  });

  const url = await getSignedUrl(new S3Client({
    endpoint: process.env.S3_ENDPOINT,
    region: process.env.S3_REGION,
    credentials: {
      accessKeyId: process.env.S3_ACCESS_KEY_ID!,
      secretAccessKey: process.env.S3_SECRET_ACCESS_KEY!,
    },
    forcePathStyle: true,
  }), command, { expiresIn: 3600 });

  return { url, key };
}
