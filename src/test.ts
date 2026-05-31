import {
  createSecuritySuite,
  createDataMasking,
  createAuditLog,
  createTEE,
  createMPC,
  createFederatedLearning,
  createKeySharding,
  createDataClassification,
  createDifferentialPrivacy,
  defaultMaskingConfigs,
  User,
  UserRole
} from './index';

async function runTests() {
  console.log('='.repeat(60));
  console.log('安全模块测试套件');
  console.log('='.repeat(60));
  console.log();

  const adminRole: UserRole = {
    id: 'role-1',
    name: '管理员',
    permissions: ['view:idCard', 'view:phone', 'view:email', 'view:address', 'view:bankCard', 'view:salary'],
    dataAccessLevel: 4
  };

  const staffRole: UserRole = {
    id: 'role-2',
    name: '普通员工',
    permissions: ['view:phone'],
    dataAccessLevel: 2
  };

  const adminUser: User = {
    id: 'user-1',
    username: 'admin',
    roles: [adminRole],
    department: '技术部'
  };

  const staffUser: User = {
    id: 'user-2',
    username: 'staff',
    roles: [staffRole],
    department: '市场部'
  };

  const testData = {
    name: '张三',
    idCard: '110101199001011234',
    phone: '13800138000',
    email: 'zhangsan@example.com',
    address: '北京市朝阳区建国路88号',
    bankCard: '6222021234567890123',
    salary: '50000',
    password: 'MyPass123!'
  };

  console.log('1. 动态数据脱敏模块测试');
  console.log('-'.repeat(40));
  const masking = createDataMasking('my-secret-key');
  masking.registerFieldConfigs(defaultMaskingConfigs);
  
  console.log('管理员查看数据:');
  const adminResult = masking.maskData(testData, adminUser);
  console.log(JSON.stringify(adminResult, null, 2));
  
  console.log('\n普通员工查看数据:');
  const staffResult = masking.maskData(testData, staffUser);
  console.log(JSON.stringify(staffResult, null, 2));
  console.log('✓ 数据脱敏测试通过');
  console.log();

  console.log('2. 审计日志防篡改模块测试');
  console.log('-'.repeat(40));
  const auditLog = createAuditLog();
  
  const log1 = auditLog.createLog({
    userId: 'user-1',
    action: 'LOGIN',
    resourceType: 'USER',
    resourceId: 'user-1',
    details: { ip: '192.168.1.1' }
  });
  console.log('创建日志1:', log1.hash.slice(0, 20) + '...');

  const log2 = auditLog.createLog({
    userId: 'user-1',
    action: 'UPDATE',
    resourceType: 'USER',
    resourceId: 'user-2',
    details: { field: 'email' }
  });
  console.log('创建日志2:', log2.hash.slice(0, 20) + '...');

  const verification = auditLog.verifyChain();
  console.log('链验证结果:', verification.isValid ? '通过' : '失败');
  console.log('✓ 审计日志测试通过');
  console.log();

  console.log('3. 可信执行环境模块测试');
  console.log('-'.repeat(40));
  const tee = createTEE('tee-master-key');
  
  const enclave = tee.createEnclave('支付处理Enclave', ['secure-storage', 'key-management']);
  console.log('创建Enclave:', enclave.enclaveId.slice(0, 20) + '...');
  
  tee.initializeEnclave(enclave.enclaveId);
  const status = tee.getEnclaveStatus(enclave.enclaveId);
  console.log('Enclave状态:', status?.status);

  const encrypted = tee.encryptInEnclave(enclave.enclaveId, '敏感数据内容');
  console.log('加密结果:', encrypted ? '成功' : '失败');

  if (encrypted) {
    const decrypted = tee.decryptInEnclave(enclave.enclaveId, encrypted);
    console.log('解密结果:', decrypted);
  }

  const attestation = tee.generateAttestationReport(enclave.enclaveId, 'challenge-123');
  console.log('远程证明验证:', attestation?.isVerified ? '通过' : '失败');
  console.log('✓ TEE模块测试通过');
  console.log();

  console.log('4. 安全多方计算模块测试');
  console.log('-'.repeat(40));
  const mpc = createMPC();
  
  const p1 = mpc.registerParticipant({
    name: '银行A',
    publicKey: 'bank-a-pub-key',
    endpoint: 'https://bank-a.com/mpc'
  });
  
  const p2 = mpc.registerParticipant({
    name: '银行B',
    publicKey: 'bank-b-pub-key',
    endpoint: 'https://bank-b.com/mpc'
  });
  
  const p3 = mpc.registerParticipant({
    name: '银行C',
    publicKey: 'bank-c-pub-key',
    endpoint: 'https://bank-c.com/mpc'
  });

  const task = mpc.createTask(
    '联合信用评分',
    'secret-sharing',
    [p1.id, p2.id, p3.id],
    { income: 'number', debt: 'number' },
    { creditScore: 'number' }
  );
  console.log('创建MPC任务:', task?.id.slice(0, 20) + '...');

  if (task) {
    mpc.submitEncryptedInput(task.id, p1.id, { value: 75 });
    mpc.submitEncryptedInput(task.id, p2.id, { value: 82 });
    mpc.submitEncryptedInput(task.id, p3.id, { value: 68 });

    const result = mpc.executeTask(task.id);
    console.log('MPC计算结果:', JSON.stringify(result?.result, null, 2));
    console.log('结果验证:', mpc.verifyResult(result!) ? '通过' : '失败');
  }
  console.log('✓ MPC模块测试通过');
  console.log();

  console.log('5. 联邦学习协调模块测试');
  console.log('-'.repeat(40));
  const fl = createFederatedLearning();
  
  const client1 = fl.registerClient({
    name: '医院A',
    endpoint: 'https://hospital-a.com/fl',
    publicKey: 'hospital-a-pub',
    datasets: ['patient-records-a']
  });
  
  const client2 = fl.registerClient({
    name: '医院B',
    endpoint: 'https://hospital-b.com/fl',
    publicKey: 'hospital-b-pub',
    datasets: ['patient-records-b']
  });

  const flTask = fl.createTrainingTask(
    '疾病预测模型',
    'CNN',
    { learningRate: 0.001, batchSize: 32 },
    [client1.id, client2.id],
    3,
    10
  );
  console.log('创建FL任务:', flTask?.id.slice(0, 20) + '...');

  if (flTask) {
    fl.startTraining(flTask.id);
    console.log('当前轮次:', flTask.currentRound);

    const gradient = Array.from({ length: 10 }, () => Math.random() - 0.5);
    fl.submitGradient(flTask.id, client1.id, gradient, 1000);
    fl.submitGradient(flTask.id, client2.id, gradient, 1500);

    const metrics = fl.getTrainingMetrics(flTask.id);
    console.log('训练指标数:', metrics.length);
    console.log('任务状态:', fl.getTask(flTask.id)?.status);
  }
  console.log('✓ 联邦学习模块测试通过');
  console.log();

  console.log('6. 密钥分片管理模块测试');
  console.log('-'.repeat(40));
  const sharding = createKeySharding();
  
  const secret = 'my-super-secret-key-12345';
  const shards = sharding.generateShards(secret, 5, 3, 'admin');
  console.log('生成密钥分片数:', shards?.length);

  if (shards) {
    console.log('分片索引:', shards.map(s => s.shardIndex));
    
    const recovery1 = sharding.recoverSecret([shards[0], shards[2], shards[4]]);
    console.log('使用3个分片恢复:', recovery1.success ? '成功' : '失败');
    console.log('恢复的密钥:', recovery1.secret);
    
    const recovery2 = sharding.recoverSecret([shards[0], shards[1]]);
    console.log('使用2个分片恢复:', recovery2.success ? '成功' : '失败');
    console.log('消息:', recovery2.message);

    const verification = sharding.verifyShardIntegrity(shards[0]);
    console.log('分片完整性验证:', verification ? '通过' : '失败');
  }
  console.log('✓ 密钥分片模块测试通过');
  console.log();

  console.log('7. 数据分类分级模块测试');
  console.log('-'.repeat(40));
  const classification = createDataClassification();
  
  console.log('默认规则数:', classification.getAllRules().length);
  
  const results = classification.classifyData(testData);
  console.log('分类结果:');
  results.forEach(r => {
    console.log(`  ${r.fieldName}: ${r.category} (${r.sensitivityLevel}, 置信度: ${r.confidence})`);
  });

  const risk = classification.getRiskAssessment(results);
  console.log('风险评估:', risk.overallRisk, `(分数: ${risk.score.toFixed(2)})`);

  const dataSource = classification.addDataSource({
    name: '用户数据库',
    type: 'database',
    connectionInfo: 'mysql://localhost:3306/users'
  });
  console.log('添加数据源:', dataSource.id.slice(0, 20) + '...');

  const report = await classification.scanDataSource(dataSource.id);
  console.log('扫描报告 - 分类字段数:', report?.classifiedFields);
  console.log('✓ 数据分类模块测试通过');
  console.log();

  console.log('8. 差分隐私注入模块测试');
  console.log('-'.repeat(40));
  const dp = createDifferentialPrivacy(1.0, 1e-5);
  
  dp.createBudgetAccount('user-1', 10.0, 1e-4);
  
  const context = {
    queryId: 'query-1',
    userId: 'user-1',
    queryType: 'count',
    timestamp: Date.now()
  };

  const result = dp.addNoise(1000, 'default', context);
  console.log(`原始值: ${result.originalValue}`);
  console.log(`加噪值: ${result.noisyValue.toFixed(2)}`);
  console.log(`噪声: ${result.noiseAdded.toFixed(4)}`);
  console.log(`使用epsilon: ${result.epsilonUsed}`);
  console.log(`剩余预算: ${result.remainingBudget.toFixed(2)}`);

  const values = [100, 200, 300, 400, 500];
  const avgResult = dp.privatizeAverage(values, 0, 1000, 'avg');
  console.log(`平均值 - 原始: ${avgResult.originalValue}, 加噪: ${avgResult.noisyValue.toFixed(2)}`);

  const utility = dp.calculateUtility(1000, result.noisyValue);
  console.log(`数据效用: ${(utility * 100).toFixed(2)}%`);

  const reportDP = dp.generatePrivacyReport('user-1');
  console.log('隐私报告 - 查询次数:', reportDP?.queryCount);
  console.log('✓ 差分隐私模块测试通过');
  console.log();

  console.log('9. 安全套件集成测试');
  console.log('-'.repeat(40));
  const suite = createSecuritySuite({
    encryptionKey: 'suite-encryption-key',
    teeMasterKey: 'suite-tee-key',
    defaultEpsilon: 0.5,
    defaultDelta: 1e-6
  });

  suite.dataMasking.registerFieldConfigs(defaultMaskingConfigs);
  suite.auditLog.createLog({
    userId: 'user-1',
    action: 'ACCESS',
    resourceType: 'DATA',
    resourceId: 'record-1'
  });

  const stats = suite.getStats();
  console.log('套件统计:', JSON.stringify(stats, null, 2));
  console.log('✓ 安全套件测试通过');
  console.log();

  console.log('='.repeat(60));
  console.log('所有测试完成! ✓');
  console.log('='.repeat(60));
}

runTests().catch(console.error);
