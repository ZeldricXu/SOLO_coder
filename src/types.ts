export type FrameworkType = 'node-backend' | 'react-frontend' | 'vue-frontend' | 'cli-tool';

export type PackageManagerType = 'npm' | 'yarn' | 'pnpm';

export type CiProviderType = 'github' | 'gitlab' | 'none';

export type DeployTargetType = 'docker' | 'k8s' | 'none';

export interface ProjectConfig {
  projectName: string;
  description: string;
  author: string;
  framework: FrameworkType;
  packageManager: PackageManagerType;
  useDocker: boolean;
  useCI: boolean;
  ciProvider: CiProviderType;
  deployTarget: DeployTargetType;
  usePreCommitHook: boolean;
  template: string | null;
  quiet: boolean;
  gitRemoteUrl: string | undefined;
  targetDir: string;
  projectVersion: string;
}

export interface TemplateConfig {
  name: string;
  description: string;
  framework: FrameworkType;
  dependencies: Record<string, string>;
  devDependencies: Record<string, string>;
  scripts: Record<string, string>;
  files: TemplateFile[];
}

export interface TemplateFile {
  source: string;
  target: string;
  isTemplate: boolean;
  perm?: number;
}

export interface UserPreferences {
  lastFramework?: FrameworkType;
  lastPackageManager?: PackageManagerType;
  lastCiProvider?: CiProviderType;
  lastAuthor?: string;
  lastUseDocker?: boolean;
  lastUsePreCommitHook?: boolean;
  templateVersion?: string;
  lastUpdateCheck?: number;
}

export interface TemplateInfo {
  name: string;
  version: string;
  url: string;
  updatedAt: string;
}

export interface CiConfig {
  nodeVersion: string;
  usePostgres: boolean;
  useRedis: boolean;
  postgresVersion: string;
  redisVersion: string;
}

export interface DockerConfig {
  nodeVersion: string;
  exposePort: number;
  usePostgres: boolean;
  useRedis: boolean;
  postgresVersion: string;
  redisVersion: string;
}

export const DEFAULT_CI_CONFIG: CiConfig = {
  nodeVersion: '20',
  usePostgres: true,
  useRedis: true,
  postgresVersion: '15',
  redisVersion: '7',
};

export const DEFAULT_DOCKER_CONFIG: DockerConfig = {
  nodeVersion: '22',
  exposePort: 3000,
  usePostgres: true,
  useRedis: true,
  postgresVersion: '15',
  redisVersion: '7',
};

export const FRAMEWORK_NAMES: Record<FrameworkType, string> = {
  'node-backend': 'Node.js Backend',
  'react-frontend': 'React Frontend',
  'vue-frontend': 'Vue Frontend',
  'cli-tool': 'CLI Tool',
};

export const PACKAGE_MANAGER_NAMES: Record<PackageManagerType, string> = {
  npm: 'npm',
  yarn: 'Yarn',
  pnpm: 'pnpm',
};

export const CI_PROVIDER_NAMES: Record<CiProviderType, string> = {
  github: 'GitHub Actions',
  gitlab: 'GitLab CI',
  none: 'None',
};

export const DEPLOY_TARGET_NAMES: Record<DeployTargetType, string> = {
  docker: 'Docker / Docker Compose',
  k8s: 'Kubernetes',
  none: 'None',
};
