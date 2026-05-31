import { ModuleResult } from './types';
export declare function generateId(prefix?: string): string;
export declare function getCurrentTimestamp(): string;
export declare function createSuccessResult<T>(data: T, code?: string, traceId?: string): ModuleResult<T>;
export declare function createErrorResult<T = unknown>(error: string, code?: string, traceId?: string): ModuleResult<T>;
export declare function sha256(data: string): string;
export declare function hmacSha256(data: string, key: string): string;
export declare function randomBytes(length: number): Buffer;
export declare function encrypt(data: string, key: Buffer): {
    iv: string;
    encrypted: string;
};
export declare function decrypt(encrypted: string, iv: string, key: Buffer): string;
export declare function generateKeyPair(): {
    publicKey: string;
    privateKey: string;
};
export declare function sign(data: string, privateKey: string): string;
export declare function verify(data: string, signature: string, publicKey: string): boolean;
export declare function deepClone<T>(obj: T): T;
export declare function isObject(value: unknown): value is Record<string, unknown>;
export declare function getNestedValue(obj: Record<string, unknown>, path: string): unknown;
export declare function setNestedValue(obj: Record<string, unknown>, path: string, value: unknown): void;
export declare function validateEmail(email: string): boolean;
export declare function validatePhone(phone: string): boolean;
export declare function validateIdCard(idCard: string): boolean;
export declare function validateBankCard(card: string): boolean;
export declare function validateAddress(address: string): boolean;
