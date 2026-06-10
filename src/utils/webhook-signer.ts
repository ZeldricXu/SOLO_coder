import { createHmac, createSign, createVerify, timingSafeEqual } from 'crypto';

export type SignAlgorithm = 'HMAC-SHA256' | 'RSA-SHA256';

export interface SignOptions {
  algorithm?: SignAlgorithm;
  includeTimestamp?: boolean;
  signatureHeader?: string;
  timestampHeader?: string;
  toleranceSeconds?: number;
}

export interface SignatureVerificationResult {
  valid: boolean;
  error?: string;
  timestamp?: number;
  algorithm?: SignAlgorithm;
}

export interface WebhookSigner {
  sign(payload: string, secret: string, timestamp?: number): string;
  verify(payload: string, signature: string, secret: string, timestamp?: number): SignatureVerificationResult;
  getAlgorithm(): SignAlgorithm;
}

class HmacWebhookSigner implements WebhookSigner {
  private readonly algorithm: SignAlgorithm = 'HMAC-SHA256';
  private readonly toleranceSeconds: number;

  constructor(toleranceSeconds = 300) {
    this.toleranceSeconds = toleranceSeconds;
  }

  sign(payload: string, secret: string, timestamp: number = Date.now()): string {
    const data = `${timestamp}.${payload}`;
    const signature = createHmac('sha256', secret)
      .update(data)
      .digest('hex');
    return `t=${timestamp},v1=${signature}`;
  }

  verify(
    payload: string,
    signatureHeader: string,
    secret: string,
    timestamp?: number
  ): SignatureVerificationResult {
    try {
      const parsed = this.parseSignature(signatureHeader);
      if (!parsed) {
        return { valid: false, error: 'Invalid signature format', algorithm: this.algorithm };
      }

      const signatureTimestamp = parsed.timestamp;
      const providedSignature = parsed.signature;

      if (timestamp === undefined) {
        const now = Math.floor(Date.now() / 1000);
        if (Math.abs(now - signatureTimestamp) > this.toleranceSeconds) {
          return {
            valid: false,
            error: 'Signature timestamp outside tolerance window',
            timestamp: signatureTimestamp,
            algorithm: this.algorithm,
          };
        }
      } else if (timestamp !== signatureTimestamp) {
        return {
          valid: false,
          error: 'Timestamp mismatch',
          timestamp: signatureTimestamp,
          algorithm: this.algorithm,
        };
      }

      const expectedData = `${signatureTimestamp}.${payload}`;
      const expectedSignature = createHmac('sha256', secret)
        .update(expectedData)
        .digest('hex');

      const isValid = timingSafeEqual(
        Buffer.from(providedSignature),
        Buffer.from(expectedSignature)
      );

      if (!isValid) {
        return {
          valid: false,
          error: 'Signature verification failed',
          timestamp: signatureTimestamp,
          algorithm: this.algorithm,
        };
      }

      return {
        valid: true,
        timestamp: signatureTimestamp,
        algorithm: this.algorithm,
      };
    } catch (error) {
      return {
        valid: false,
        error: (error as Error).message,
        algorithm: this.algorithm,
      };
    }
  }

  getAlgorithm(): SignAlgorithm {
    return this.algorithm;
  }

  private parseSignature(signatureHeader: string): { timestamp: number; signature: string } | null {
    const parts = signatureHeader.split(',');
    let timestamp: number | null = null;
    let signature: string | null = null;

    for (const part of parts) {
      const [key, value] = part.trim().split('=');
      if (key === 't') {
        timestamp = parseInt(value, 10);
      } else if (key === 'v1') {
        signature = value;
      }
    }

    if (!timestamp || !signature || isNaN(timestamp)) {
      return null;
    }

    return { timestamp, signature };
  }
}

class RsaWebhookSigner implements WebhookSigner {
  private readonly algorithm: SignAlgorithm = 'RSA-SHA256';
  private readonly toleranceSeconds: number;

  constructor(toleranceSeconds = 300) {
    this.toleranceSeconds = toleranceSeconds;
  }

  sign(payload: string, privateKey: string, timestamp: number = Date.now()): string {
    const data = `${timestamp}.${payload}`;
    const signature = createSign('RSA-SHA256')
      .update(data)
      .sign(privateKey, 'base64');
    return `t=${timestamp},v1=${signature}`;
  }

  verify(
    payload: string,
    signatureHeader: string,
    publicKey: string,
    timestamp?: number
  ): SignatureVerificationResult {
    try {
      const parsed = this.parseSignature(signatureHeader);
      if (!parsed) {
        return { valid: false, error: 'Invalid signature format', algorithm: this.algorithm };
      }

      const signatureTimestamp = parsed.timestamp;
      const providedSignature = parsed.signature;

      if (timestamp === undefined) {
        const now = Math.floor(Date.now() / 1000);
        if (Math.abs(now - signatureTimestamp) > this.toleranceSeconds) {
          return {
            valid: false,
            error: 'Signature timestamp outside tolerance window',
            timestamp: signatureTimestamp,
            algorithm: this.algorithm,
          };
        }
      } else if (timestamp !== signatureTimestamp) {
        return {
          valid: false,
          error: 'Timestamp mismatch',
          timestamp: signatureTimestamp,
          algorithm: this.algorithm,
        };
      }

      const expectedData = `${signatureTimestamp}.${payload}`;
      const isValid = createVerify('RSA-SHA256')
        .update(expectedData)
        .verify(publicKey, providedSignature, 'base64');

      if (!isValid) {
        return {
          valid: false,
          error: 'Signature verification failed',
          timestamp: signatureTimestamp,
          algorithm: this.algorithm,
        };
      }

      return {
        valid: true,
        timestamp: signatureTimestamp,
        algorithm: this.algorithm,
      };
    } catch (error) {
      return {
        valid: false,
        error: (error as Error).message,
        algorithm: this.algorithm,
      };
    }
  }

  getAlgorithm(): SignAlgorithm {
    return this.algorithm;
  }

  private parseSignature(signatureHeader: string): { timestamp: number; signature: string } | null {
    const parts = signatureHeader.split(',');
    let timestamp: number | null = null;
    let signature: string | null = null;

    for (const part of parts) {
      const [key, value] = part.trim().split('=');
      if (key === 't') {
        timestamp = parseInt(value, 10);
      } else if (key === 'v1') {
        signature = value;
      }
    }

    if (!timestamp || !signature || isNaN(timestamp)) {
      return null;
    }

    return { timestamp, signature };
  }
}

export function createWebhookSigner(
  algorithm: SignAlgorithm = 'HMAC-SHA256', toleranceSeconds?: number): WebhookSigner {
  switch (algorithm) {
    case 'HMAC-SHA256':
      return new HmacWebhookSigner(toleranceSeconds);
    case 'RSA-SHA256':
      return new RsaWebhookSigner(toleranceSeconds);
    default:
      throw new Error(`Unsupported algorithm: ${algorithm}`);
  }
}

export const defaultWebhookSigner = createWebhookSigner('HMAC-SHA256');

export function generateWebhookSignature(
  payload: string,
  secret: string,
  algorithm: SignAlgorithm = 'HMAC-SHA256',
  timestamp?: number
): string {
  const signer = createWebhookSigner(algorithm);
  return signer.sign(payload, secret, timestamp);
}

export function verifyWebhookSignature(
  payload: string,
  signature: string,
  secret: string,
  algorithm: SignAlgorithm = 'HMAC-SHA256',
  timestamp?: number
): SignatureVerificationResult {
  const signer = createWebhookSigner(algorithm);
  return signer.verify(payload, signature, secret, timestamp);
}
