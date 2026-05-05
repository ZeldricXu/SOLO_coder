const Minio = require('minio');
const config = require('../config/minio');
const fs = require('fs').promises;
const path = require('path');
const { v4: uuidv4 } = require('uuid');

class StorageService {
  constructor() {
    this.client = new Minio.Client({
      endPoint: config.endPoint,
      port: config.port,
      useSSL: config.useSSL,
      accessKey: config.accessKey,
      secretKey: config.secretKey
    });
    this.bucket = config.bucket;
  }

  async ensureBucketExists() {
    try {
      const exists = await this.client.bucketExists(this.bucket);
      if (!exists) {
        await this.client.makeBucket(this.bucket, 'us-east-1');
        console.log(`Bucket ${this.bucket} created successfully`);
      }
    } catch (error) {
      console.error('Error ensuring bucket exists:', error);
      throw error;
    }
  }

  generateStoragePath(fileType, filename) {
    const ext = path.extname(filename);
    const newFilename = `${uuidv4()}${ext}`;
    const datePrefix = new Date().toISOString().slice(0, 10).replace(/-/g, '/');
    
    return {
      path: `${fileType}/${datePrefix}/${newFilename}`,
      filename: newFilename
    };
  }

  async uploadFromPath(localPath, storagePath, contentType = 'application/octet-stream') {
    try {
      await this.client.fPutObject(this.bucket, storagePath, localPath, {
        'Content-Type': contentType
      });
      return {
        success: true,
        bucket: this.bucket,
        path: storagePath
      };
    } catch (error) {
      console.error('Error uploading file to storage:', error);
      throw error;
    }
  }

  async uploadFromBuffer(buffer, storagePath, contentType = 'application/octet-stream') {
    try {
      await this.client.putObject(this.bucket, storagePath, buffer, buffer.length, {
        'Content-Type': contentType
      });
      return {
        success: true,
        bucket: this.bucket,
        path: storagePath
      };
    } catch (error) {
      console.error('Error uploading buffer to storage:', error);
      throw error;
    }
  }

  async downloadToPath(storagePath, localPath) {
    try {
      await this.client.fGetObject(this.bucket, storagePath, localPath);
      return {
        success: true,
        localPath: localPath
      };
    } catch (error) {
      console.error('Error downloading file from storage:', error);
      throw error;
    }
  }

  async downloadToBuffer(storagePath) {
    try {
      const dataStream = await this.client.getObject(this.bucket, storagePath);
      const chunks = [];
      
      for await (const chunk of dataStream) {
        chunks.push(chunk);
      }
      
      return Buffer.concat(chunks);
    } catch (error) {
      console.error('Error downloading buffer from storage:', error);
      throw error;
    }
  }

  async getPresignedUrl(storagePath, expiresIn = 7 * 24 * 60 * 60) {
    try {
      const url = await this.client.presignedGetObject(
        this.bucket,
        storagePath,
        expiresIn
      );
      return url;
    } catch (error) {
      console.error('Error getting presigned URL:', error);
      throw error;
    }
  }

  async getPresignedUploadUrl(storagePath, expiresIn = 24 * 60 * 60) {
    try {
      const url = await this.client.presignedPutObject(
        this.bucket,
        storagePath,
        expiresIn
      );
      return url;
    } catch (error) {
      console.error('Error getting presigned upload URL:', error);
      throw error;
    }
  }

  async deleteObject(storagePath) {
    try {
      await this.client.removeObject(this.bucket, storagePath);
      return {
        success: true,
        deleted: storagePath
      };
    } catch (error) {
      console.error('Error deleting object from storage:', error);
      throw error;
    }
  }

  async deleteObjects(storagePaths) {
    try {
      const objectsList = storagePaths.map(path => ({ name: path }));
      await this.client.removeObjects(this.bucket, objectsList);
      return {
        success: true,
        deleted: storagePaths
      };
    } catch (error) {
      console.error('Error deleting multiple objects from storage:', error);
      throw error;
    }
  }

  async objectExists(storagePath) {
    try {
      await this.client.statObject(this.bucket, storagePath);
      return true;
    } catch (error) {
      if (error.code === 'NoSuchKey') {
        return false;
      }
      throw error;
    }
  }

  async getObjectInfo(storagePath) {
    try {
      const stat = await this.client.statObject(this.bucket, storagePath);
      return {
        size: stat.size,
        etag: stat.etag,
        lastModified: stat.lastModified,
        metaData: stat.metaData
      };
    } catch (error) {
      console.error('Error getting object info:', error);
      throw error;
    }
  }

  async listObjects(prefix = '', recursive = false) {
    try {
      const objects = [];
      const stream = this.client.listObjects(this.bucket, prefix, recursive);
      
      return new Promise((resolve, reject) => {
        stream.on('data', (obj) => objects.push(obj));
        stream.on('error', reject);
        stream.on('end', () => resolve(objects));
      });
    } catch (error) {
      console.error('Error listing objects:', error);
      throw error;
    }
  }

  async copyObject(sourcePath, destPath) {
    try {
      const conditions = new Minio.CopyConditions();
      await this.client.copyObject(
        this.bucket,
        destPath,
        `/${this.bucket}/${sourcePath}`,
        conditions
      );
      return {
        success: true,
        source: sourcePath,
        destination: destPath
      };
    } catch (error) {
      console.error('Error copying object:', error);
      throw error;
    }
  }
}

module.exports = new StorageService();
