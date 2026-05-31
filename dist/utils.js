"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.generateId = generateId;
exports.getCurrentTimestamp = getCurrentTimestamp;
exports.createSuccessResult = createSuccessResult;
exports.createErrorResult = createErrorResult;
exports.sha256 = sha256;
exports.hmacSha256 = hmacSha256;
exports.randomBytes = randomBytes;
exports.encrypt = encrypt;
exports.decrypt = decrypt;
exports.generateKeyPair = generateKeyPair;
exports.sign = sign;
exports.verify = verify;
exports.deepClone = deepClone;
exports.isObject = isObject;
exports.getNestedValue = getNestedValue;
exports.setNestedValue = setNestedValue;
exports.validateEmail = validateEmail;
exports.validatePhone = validatePhone;
exports.validateIdCard = validateIdCard;
exports.validateBankCard = validateBankCard;
exports.validateAddress = validateAddress;
const crypto = __importStar(require("crypto"));
function generateId(prefix = 'id') {
    const random = crypto.randomBytes(12).toString('hex');
    return `${prefix}_${random}`;
}
function getCurrentTimestamp() {
    return new Date().toISOString();
}
function createSuccessResult(data, code = 'OK', traceId) {
    return {
        success: true,
        data,
        code,
        timestamp: getCurrentTimestamp(),
        traceId,
    };
}
function createErrorResult(error, code = 'ERROR', traceId) {
    return {
        success: false,
        error,
        code,
        timestamp: getCurrentTimestamp(),
        traceId,
    };
}
function sha256(data) {
    return crypto.createHash('sha256').update(data).digest('hex');
}
function hmacSha256(data, key) {
    return crypto.createHmac('sha256', key).update(data).digest('hex');
}
function randomBytes(length) {
    return crypto.randomBytes(length);
}
function encrypt(data, key) {
    const iv = crypto.randomBytes(16);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const encrypted = Buffer.concat([cipher.update(data, 'utf8'), cipher.final()]);
    const authTag = cipher.getAuthTag();
    return {
        iv: iv.toString('hex'),
        encrypted: Buffer.concat([encrypted, authTag]).toString('hex'),
    };
}
function decrypt(encrypted, iv, key) {
    const ivBuffer = Buffer.from(iv, 'hex');
    const encryptedBuffer = Buffer.from(encrypted, 'hex');
    const authTag = encryptedBuffer.slice(-16);
    const data = encryptedBuffer.slice(0, -16);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, ivBuffer);
    decipher.setAuthTag(authTag);
    return Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8');
}
function generateKeyPair() {
    const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
        modulusLength: 2048,
        publicKeyEncoding: { type: 'spki', format: 'pem' },
        privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });
    return { publicKey, privateKey };
}
function sign(data, privateKey) {
    return crypto.createSign('sha256').update(data).sign(privateKey, 'hex');
}
function verify(data, signature, publicKey) {
    return crypto.createVerify('sha256').update(data).verify(publicKey, signature, 'hex');
}
function deepClone(obj) {
    return JSON.parse(JSON.stringify(obj));
}
function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function getNestedValue(obj, path) {
    return path.split('.').reduce((current, key) => {
        if (isObject(current)) {
            return current[key];
        }
        return undefined;
    }, obj);
}
function setNestedValue(obj, path, value) {
    const keys = path.split('.');
    const lastKey = keys.pop();
    const target = keys.reduce((current, key) => {
        if (!isObject(current[key])) {
            current[key] = {};
        }
        return current[key];
    }, obj);
    target[lastKey] = value;
}
function validateEmail(email) {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}
function validatePhone(phone) {
    return /^1[3-9]\d{9}$/.test(phone);
}
function validateIdCard(idCard) {
    return /^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/.test(idCard);
}
function validateBankCard(card) {
    return /^\d{16,19}$/.test(card);
}
function validateAddress(address) {
    return address.length > 5 && /[省市区镇村街道]/.test(address);
}
//# sourceMappingURL=utils.js.map