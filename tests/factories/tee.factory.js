const { v4: uuidv4 } = require('uuid');
const Chance = require('chance');
const { generateSignedRequest, PermissionLevel } = require('./common.factory');

const chance = new Chance();

const EnclaveStatus = {
  PENDING: 'pending',
  INITIALIZING: 'initializing',
  RUNNING: 'running',
  STOPPED: 'stopped',
  TERMINATED: 'terminated',
  ERROR: 'error'
};

const TEEType = {
  SGX: 'sgx',
  SEV: 'sev',
  TDX: 'tdx',
  TRUSTZONE: 'trustzone'
};

const generateEnclaveConfig = (options = {}) => ({
  tee_type: options.tee_type || TEEType.SGX,
  memory_size: options.memory_size || 1024,
  cpu_cores: options.cpu_cores || 2,
  enclave_image: options.enclave_image || 'enclave:v1.0.0',
  secure_boot: options.secure_boot !== false,
  attestation_required: options.attestation_required !== false,
  network_policy: options.network_policy || 'isolated',
  metadata: options.metadata || {}
});

const generateCreateEnclaveRequest = (options = {}) => ({
  name: options.name || `enclave-${uuidv4().slice(0, 8)}`,
  description: options.description || '测试用TEE enclave',
  config: generateEnclaveConfig(options.config),
  labels: options.labels || {
    environment: 'test',
    workload: 'secure-computation'
  }
});

const generateAttestationRequest = (enclaveId, options = {}) => ({
  enclave_id: enclaveId,
  challenge: options.challenge || uuidv4(),
  nonce: options.nonce || uuidv4(),
  quote_type: options.quote_type || 'sgx_epid',
  include_cert_chain: options.include_cert_chain !== false
});

const generateSecureFunctionPayload = (operation, data) => ({
  operation,
  data,
  request_id: uuidv4(),
  timestamp: Date.now()
});

const generateEncryptedInput = (data) => {
  const key = Buffer.from('aes-256-gcm-test-key-1234567890');
  const iv = Buffer.from(crypto.randomBytes(12));
  const cipher = require('crypto').createCipheriv('aes-256-gcm', key, iv);
  let encrypted = cipher.update(JSON.stringify(data), 'utf8', 'hex');
  encrypted += cipher.final('hex');
  const authTag = cipher.getAuthTag().toString('hex');
  
  return {
    iv: iv.toString('hex'),
    ciphertext: encrypted,
    auth_tag: authTag,
    key_id: 'test-key-001'
  };
};

const generateTEETestCases = () => ({
  enclaveCreation: [
    {
      name: '标准SGX enclave创建',
      request: generateCreateEnclaveRequest(),
      expectedStatus: 201,
      expectedEnclaveStatus: EnclaveStatus.PENDING
    },
    {
      name: '高配置SEV enclave创建',
      request: generateCreateEnclaveRequest({
        config: {
          tee_type: TEEType.SEV,
          memory_size: 8192,
          cpu_cores: 8
        }
      }),
      expectedStatus: 201,
      expectedEnclaveStatus: EnclaveStatus.PENDING
    },
    {
      name: '最小配置enclave创建',
      request: generateCreateEnclaveRequest({
        config: {
          memory_size: 256,
          cpu_cores: 1,
          secure_boot: false
        }
      }),
      expectedStatus: 201,
      expectedEnclaveStatus: EnclaveStatus.PENDING
    }
  ],
  
  attestationScenarios: [
    {
      name: '标准远程证明流程',
      enclaveId: uuidv4(),
      request: generateAttestationRequest(uuidv4()),
      expectedStatus: 200
    },
    {
      name: '带证书链的证明请求',
      enclaveId: uuidv4(),
      request: generateAttestationRequest(uuidv4(), { include_cert_chain: true }),
      expectedStatus: 200
    }
  ],
  
  consistencyScenarios: [
    {
      name: '同一enclave多次查询数据一致性',
      enclaveId: null,
      iterations: 10,
      expectedConsistencyRate: 1.0
    },
    {
      name: '并发enclave创建状态一致性',
      concurrency: 5,
      expectedStatus: EnclaveStatus.PENDING
    },
    {
      name: 'enclave状态流转一致性',
      enclaveId: null,
      transitions: [
        EnclaveStatus.PENDING,
        EnclaveStatus.INITIALIZING,
        EnclaveStatus.RUNNING,
        EnclaveStatus.STOPPED,
        EnclaveStatus.TERMINATED
      ]
    }
  ]
});

module.exports = {
  EnclaveStatus,
  TEEType,
  generateEnclaveConfig,
  generateCreateEnclaveRequest,
  generateAttestationRequest,
  generateSecureFunctionPayload,
  generateEncryptedInput,
  generateTEETestCases,
  generateSignedTEERequest: (payload, permissionLevel) => 
    generateSignedRequest(payload, permissionLevel || PermissionLevel.ADMIN)
};
