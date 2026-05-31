const { v4: uuidv4 } = require('uuid');
const Chance = require('chance');

const chance = new Chance();

const PermissionLevel = {
  FULL_ACCESS: 'full_access',
  RESTRICTED: 'restricted',
  READ_ONLY: 'read_only',
  ADMIN: 'admin'
};

const SensitivityLevel = {
  PUBLIC: 'public',
  INTERNAL: 'internal',
  CONFIDENTIAL: 'confidential',
  SECRET: 'secret',
  TOP_SECRET: 'top_secret'
};

const generateAuthContext = (permissionLevel = 'restricted') => ({
  user_id: uuidv4(),
  username: chance.email(),
  permission_level: permissionLevel,
  roles: ['user'],
  expires_at: new Date(Date.now() + 3600000).toISOString()
});

const generateSignature = (data, timestamp) => {
  const crypto = require('crypto');
  const hmac = crypto.createHmac('sha256', 'test-secret-key');
  hmac.update(JSON.stringify(data) + timestamp);
  return hmac.digest('hex');
};

const generateSignedRequest = (payload, permissionLevel) => {
  const timestamp = Date.now();
  const signature = generateSignature(payload, timestamp);
  return {
    payload,
    signature,
    timestamp,
    nonce: uuidv4(),
    auth_context: generateAuthContext(permissionLevel)
  };
};

const generateBinaryResponse = (data) => {
  const buffer = Buffer.from(JSON.stringify(data));
  const length = buffer.length;
  const checksum = require('crypto').createHash('sha256').update(buffer).digest('hex');
  return {
    data: buffer.toString('base64'),
    length,
    checksum,
    timestamp: Date.now()
  };
};

module.exports = {
  PermissionLevel,
  SensitivityLevel,
  generateAuthContext,
  generateSignature,
  generateSignedRequest,
  generateBinaryResponse,
  generateRandomString: (length = 16) => chance.string({ length }),
  generateRandomNumber: (min = 0, max = 1000) => chance.integer({ min, max }),
  generateRandomEmail: () => chance.email(),
  generateRandomPhone: () => chance.phone(),
  generateRandomIdCard: () => chance.ssn(),
  generateRandomBankCard: () => chance.cc(),
  generateRandomName: () => chance.name(),
  generateRandomAddress: () => chance.address(),
  uuid: uuidv4
};
