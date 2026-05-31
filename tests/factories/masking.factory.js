const { v4: uuidv4 } = require('uuid');
const Chance = require('chance');
const { 
  PermissionLevel, 
  generateRandomEmail, 
  generateRandomPhone, 
  generateRandomIdCard, 
  generateRandomBankCard,
  generateRandomName,
  generateRandomAddress
} = require('./common.factory');

const chance = new Chance();

const MaskingRuleType = {
  EMAIL: 'email',
  PHONE: 'phone',
  ID_CARD: 'id_card',
  BANK_CARD: 'bank_card',
  NAME: 'name',
  ADDRESS: 'address',
  GENERIC: 'generic'
};

const generateMaskingRule = (type = MaskingRuleType.EMAIL) => ({
  id: uuidv4(),
  name: `${type}_rule`,
  type,
  pattern: getPatternForType(type),
  replacement: getReplacementForType(type),
  required_permission: getRequiredPermissionForType(type),
  priority: chance.integer({ min: 1, max: 100 }),
  enabled: true,
  description: `${type}脱敏规则`
});

function getPatternForType(type) {
  const patterns = {
    [MaskingRuleType.EMAIL]: '[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}',
    [MaskingRuleType.PHONE]: '1[3-9]\\d{9}',
    [MaskingRuleType.ID_CARD]: '\\d{17}[\\dXx]',
    [MaskingRuleType.BANK_CARD]: '\\d{16,19}',
    [MaskingRuleType.NAME]: '[\\u4e00-\\u9fa5]{2,4}',
    [MaskingRuleType.ADDRESS]: '[\\u4e00-\\u9fa5]+[市县区街道乡镇村].*',
    [MaskingRuleType.GENERIC]: '.*'
  };
  return patterns[type] || patterns[MaskingRuleType.GENERIC];
}

function getReplacementForType(type) {
  const replacements = {
    [MaskingRuleType.EMAIL]: '***@***.com',
    [MaskingRuleType.PHONE]: '1**********',
    [MaskingRuleType.ID_CARD]: '******************',
    [MaskingRuleType.BANK_CARD]: '****************',
    [MaskingRuleType.NAME]: '***',
    [MaskingRuleType.ADDRESS]: '********',
    [MaskingRuleType.GENERIC]: '***'
  };
  return replacements[type] || replacements[MaskingRuleType.GENERIC];
}

function getRequiredPermissionForType(type) {
  const permissions = {
    [MaskingRuleType.EMAIL]: PermissionLevel.RESTRICTED,
    [MaskingRuleType.PHONE]: PermissionLevel.RESTRICTED,
    [MaskingRuleType.ID_CARD]: PermissionLevel.FULL_ACCESS,
    [MaskingRuleType.BANK_CARD]: PermissionLevel.FULL_ACCESS,
    [MaskingRuleType.NAME]: PermissionLevel.READ_ONLY,
    [MaskingRuleType.ADDRESS]: PermissionLevel.RESTRICTED,
    [MaskingRuleType.GENERIC]: PermissionLevel.READ_ONLY
  };
  return permissions[type] || permissions[MaskingRuleType.GENERIC];
}

const generateSensitiveDataRecord = (options = {}) => {
  const record = {
    id: uuidv4(),
    email: options.email || generateRandomEmail(),
    phone: options.phone || generateRandomPhone(),
    id_card: options.id_card || generateRandomIdCard(),
    bank_card: options.bank_card || generateRandomBankCard(),
    name: options.name || generateRandomName(),
    address: options.address || generateRandomAddress(),
    salary: options.salary || chance.integer({ min: 5000, max: 100000 }),
    created_at: new Date().toISOString()
  };
  
  if (options.extraFields) {
    Object.assign(record, options.extraFields);
  }
  
  return record;
};

const generateMaskingRequest = (options = {}) => ({
  data: options.data || generateSensitiveDataRecord(),
  fields: options.fields || ['email', 'phone', 'id_card', 'bank_card', 'name', 'address'],
  context: options.context || {
    source: 'api',
    request_id: uuidv4(),
    purpose: 'data-processing'
  }
});

const generateBatchMaskingRequest = (count = 10) => ({
  records: Array.from({ length: count }, () => generateSensitiveDataRecord()),
  fields: ['email', 'phone', 'id_card', 'bank_card', 'name', 'address'],
  context: {
    source: 'batch-job',
    purpose: 'data-export'
  }
});

const generateConcurrentTestScenarios = () => ([
  {
    name: '不同权限用户同时访问同一数据',
    concurrency: 10,
    users: [
      { permission: PermissionLevel.ADMIN, expectedMaskingLevel: 'none' },
      { permission: PermissionLevel.FULL_ACCESS, expectedMaskingLevel: 'partial' },
      { permission: PermissionLevel.RESTRICTED, expectedMaskingLevel: 'strict' },
      { permission: PermissionLevel.READ_ONLY, expectedMaskingLevel: 'full' }
    ],
    iterations: 100
  },
  {
    name: '同一用户并发访问不同数据',
    concurrency: 20,
    user: { permission: PermissionLevel.RESTRICTED },
    dataCount: 100,
    expectedIsolation: true
  },
  {
    name: '高并发下脱敏规则一致性',
    concurrency: 50,
    iterations: 200,
    expectedRuleConsistency: 1.0
  }
]);

const generateIsolationTestCases = () => {
  const testData = generateSensitiveDataRecord();
  return [
    {
      name: '管理员权限不脱敏',
      permission: PermissionLevel.ADMIN,
      data: testData,
      expectedMasked: false
    },
    {
      name: '完全访问权限部分脱敏',
      permission: PermissionLevel.FULL_ACCESS,
      data: testData,
      expectedPartialMasking: true,
      visibleFields: ['name', 'email']
    },
    {
      name: '受限权限严格脱敏',
      permission: PermissionLevel.RESTRICTED,
      data: testData,
      expectedStrictMasking: true,
      maskedFields: ['email', 'phone', 'id_card', 'bank_card']
    },
    {
      name: '只读权限完全脱敏',
      permission: PermissionLevel.READ_ONLY,
      data: testData,
      expectedFullMasking: true
    }
  ];
};

module.exports = {
  MaskingRuleType,
  generateMaskingRule,
  generateSensitiveDataRecord,
  generateMaskingRequest,
  generateBatchMaskingRequest,
  generateConcurrentTestScenarios,
  generateIsolationTestCases,
  getPatternForType,
  getReplacementForType,
  getRequiredPermissionForType
};
