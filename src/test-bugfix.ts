import {
  createDataMasking,
  createAuditLog,
  createFederatedLearning,
  createMPC,
  defaultMaskingConfigs,
  User,
  UserRole
} from './index';

function assert(condition: boolean, msg: string): void {
  if (!condition) throw new Error(`FAIL: ${msg}`);
  console.log(`  ✓ ${msg}`);
}

async function runBugfixTests() {
  console.log('='.repeat(60));
  console.log('线上缺陷修复验证');
  console.log('='.repeat(60));
  console.log();

  const staffRole: UserRole = {
    id: 'role-2',
    name: '普通员工',
    permissions: ['view:phone'],
    dataAccessLevel: 2
  };

  const staffUser: User = {
    id: 'user-2',
    username: 'staff',
    roles: [staffRole],
    department: '市场部'
  };

  console.log('缺陷1: 状态机卡中间态');
  console.log('-'.repeat(50));

  const fl = createFederatedLearning();
  const c1 = fl.registerClient({ name: 'A', endpoint: 'http://a', publicKey: 'key-a', datasets: ['d1'] });
  const c2 = fl.registerClient({ name: 'B', endpoint: 'http://b', publicKey: 'key-b', datasets: ['d2'] });
  const task = fl.createTrainingTask('test', 'CNN', { learningRate: 0.01 }, [c1.id, c2.id], 3, 5);
  fl.startTraining(task!.id);

  fl.submitGradient(task!.id, c1.id, [1, 2, 3, 4, 5], 100);
  fl.submitGradient(task!.id, c2.id, [1, 2, 3, 4, 5], 200);

  const taskAfterAgg = fl.getTask(task!.id);
  assert(
    taskAfterAgg!.status !== 'aggregating',
    'FL: 聚合完成后状态不是 aggregating（不会卡死）'
  );
  assert(
    taskAfterAgg!.status === 'training' || taskAfterAgg!.status === 'completed',
    `FL: 聚合后状态正确: ${taskAfterAgg!.status}`
  );

  const mpc = createMPC();
  const p1 = mpc.registerParticipant({ name: 'P1', publicKey: 'pk1', endpoint: 'http://p1' });
  const p2 = mpc.registerParticipant({ name: 'P2', publicKey: 'pk2', endpoint: 'http://p2' });
  const mpcTask = mpc.createTask('t', 'secret-sharing', [p1.id, p2.id], { v: 'number' }, { r: 'number' });
  mpc.submitEncryptedInput(mpcTask!.id, p1.id, { value: 10 });
  mpc.submitEncryptedInput(mpcTask!.id, p2.id, { value: 20 });
  const mpcResult = mpc.executeTask(mpcTask!.id);
  assert(mpcResult !== null, 'MPC: 执行成功不卡死');
  assert(mpc.getTask(mpcTask!.id)!.status === 'completed', 'MPC: 状态正确转到 completed');
  console.log();

  console.log('缺陷2: 数据脱敏请求不返回');
  console.log('-'.repeat(50));

  const masking = createDataMasking('test-key');
  masking.registerFieldConfigs(defaultMaskingConfigs);

  const emailResult = masking.maskData(
    { email: 'zhangsan@example.com' },
    staffUser
  );
  assert(
    !emailResult.email.includes('zhangsan@example'),
    'maskPartial visibleEnd=0: 不再泄漏完整 email'
  );
  assert(
    emailResult.email.startsWith('zh'),
    'maskPartial visibleEnd=0: 保留前2字符'
  );
  assert(
    emailResult.email === 'zh******************',
    `maskPartial visibleEnd=0: 正确脱敏结果 "${emailResult.email}"`
  );

  const decryptBadData = masking.decryptValue('invalid-data');
  assert(
    decryptBadData === '',
    'decryptValue 异常时返回空串而非抛出异常'
  );

  const decryptCorruptAuthTag = masking.decryptValue('aabbccdd:11223344:00000000000000000000000000000000');
  assert(
    decryptCorruptAuthTag === '',
    'decryptValue 认证失败时返回空串而非抛出异常'
  );

  const numericData = masking.maskData(
    { salary: 50000, name: '张三' },
    staffUser
  );
  assert(
    typeof numericData.salary === 'string' && numericData.salary !== '50000',
    `非字符串 number 值 (salary=50000) 现在也被脱敏: ${numericData.salary}`
  );

  const boolData = masking.maskData(
    { salary: true },
    staffUser
  );
  assert(
    typeof boolData.salary === 'string',
    'boolean 值也被转为字符串脱敏'
  );
  console.log();

  console.log('缺陷3: 审计日志内存稳步上升');
  console.log('-'.repeat(50));

  const auditSmall = createAuditLog({ maxEntries: 5 });
  for (let i = 0; i < 10; i++) {
    auditSmall.createLog({
      userId: `user-${i}`,
      action: 'TEST',
      resourceType: 'DATA',
      resourceId: `res-${i}`
    });
  }

  assert(
    auditSmall.getLogs().length <= 5,
    `日志轮转: 插入10条，当前活跃日志不超过5 (实际: ${auditSmall.getLogs().length})`
  );
  assert(
    auditSmall.getArchivedLogCount() > 0,
    `归档存在: ${auditSmall.getArchivedLogCount()} 个归档块`
  );
  assert(
    auditSmall.getTotalLogCount() === 10,
    `总日志数仍为10 (活跃+归档)`
  );

  const verification = auditSmall.verifyChain();
  assert(
    verification.isValid,
    '轮转后活跃链仍可验证通过'
  );

  const auditNoArchive = createAuditLog({ maxEntries: 3, archiveEnabled: false });
  for (let i = 0; i < 10; i++) {
    auditNoArchive.createLog({
      userId: `user-${i}`,
      action: 'TEST',
      resourceType: 'DATA',
      resourceId: `res-${i}`
    });
  }
  assert(
    auditNoArchive.getLogs().length <= 3,
    `禁用归档: 日志不超过maxEntries (实际: ${auditNoArchive.getLogs().length})`
  );
  assert(
    auditNoArchive.getArchivedLogCount() === 0,
    '禁用归档: 无归档数据'
  );

  const auditDefault = createAuditLog();
  assert(
    auditDefault.getMaxEntries() === 10000,
    `默认 maxEntries=10000`
  );

  auditSmall.setMaxEntries(2);
  assert(
    auditSmall.getLogs().length <= 2,
    `动态调整 maxEntries 后日志被修剪 (实际: ${auditSmall.getLogs().length})`
  );

  const cleared = auditSmall.clearArchivedLogs();
  assert(
    cleared > 0,
    `清理归档: 清理了 ${cleared} 个归档块`
  );
  console.log();

  console.log('='.repeat(60));
  console.log('线上缺陷修复验证全部通过! ✓');
  console.log('='.repeat(60));
}

runBugfixTests().catch(err => {
  console.error(err);
  process.exit(1);
});
