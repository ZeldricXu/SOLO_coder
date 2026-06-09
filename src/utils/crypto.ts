import { createHmac, createHash, randomBytes, timingSafeEqual } from 'crypto';

export function generateApiKey(): string {
  return `sk_${randomBytes(32).toString('hex')}`;
}

export function generateHmacSignature(
  payload: string,
  secret: string,
  algorithm: 'sha256' | 'sha512' = 'sha256'
): string {
  return createHmac(algorithm, secret)
    .update(payload)
    .digest('hex');
}

export function verifyHmacSignature(
  signature: string,
  payload: string,
  secret: string,
  algorithm: 'sha256' | 'sha512' = 'sha256'
): boolean {
  const expectedSignature = generateHmacSignature(payload, secret, algorithm);
  return timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expectedSignature)
  );
}

export function hashContent(content: string): string {
  return createHash('sha256').update(content).digest('hex');
}

export function generateWebhookSignature(
  timestamp: number,
  payload: string,
  secret: string
): string {
  const data = `${timestamp}.${payload}`;
  return generateHmacSignature(data, secret);
}

export function verifyWebhookSignature(
  signature: string,
  timestamp: number,
  payload: string,
  secret: string
): boolean {
  const expected = generateWebhookSignature(timestamp, payload, secret);
  return verifyHmacSignature(signature, `${timestamp}.${payload}`, secret);
}

export function generateApprovalSignature(
  userId: string,
  contentId: string,
  decision: string,
  timestamp: number,
  secret: string
): string {
  const data = `${userId}|${contentId}|${decision}|${timestamp}`;
  return generateHmacSignature(data, secret);
}

export function generateId(prefix = ''): string {
  const timestamp = Date.now().toString(36);
  const random = randomBytes(8).toString('hex');
  return prefix ? `${prefix}_${timestamp}${random}` : `${timestamp}${random}`;
}
