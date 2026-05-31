import { v4 as uuidv4 } from 'uuid';

export const generateId = (prefix: string): string => {
  return `${prefix}_${uuidv4().replace(/-/g, '').substring(0, 12)}`;
};

export const generateRunId = (): string => generateId('run');
export const generateResourceId = (): string => generateId('rsc');
export const generateConfigId = (): string => generateId('cfg');
export const generateSnapshotId = (): string => generateId('snap');
export const generateCommandId = (): string => generateId('cmd');
export const generateAuditLogId = (): string => generateId('audit');
export const generateScenarioId = (): string => generateId('scn');
export const generateInjectionId = (): string => generateId('inj');
export const generatePolicyId = (): string => generateId('pol');
export const generateUpstreamId = (): string => generateId('ups');
export const generateCertId = (): string => generateId('cert');
export const generateRevocationId = (): string => generateId('rev');
export const generateLayerId = (): string => generateId('layer');
export const generateSyncTaskId = (): string => generateId('sync');
export const generateTemplateId = (): string => generateId('tpl');
export const generateEventId = (): string => generateId('evt');
