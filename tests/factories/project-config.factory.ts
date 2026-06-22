import type {
  ProjectConfig,
  FrameworkType,
  PackageManagerType,
  CiProviderType,
  DeployTargetType,
  UserPreferences,
  CiConfig,
  DockerConfig,
} from '../src/types.js';
import { DEFAULT_CI_CONFIG, DEFAULT_DOCKER_CONFIG } from '../src/types.js';

export interface ProjectConfigOverrides {
  projectName?: string;
  description?: string;
  author?: string;
  framework?: FrameworkType;
  packageManager?: PackageManagerType;
  useDocker?: boolean;
  useCI?: boolean;
  ciProvider?: CiProviderType;
  deployTarget?: DeployTargetType;
  usePreCommitHook?: boolean;
  template?: string | null;
  quiet?: boolean;
  gitRemoteUrl?: string | undefined;
  targetDir?: string;
  projectVersion?: string;
}

export function createProjectConfig(
  overrides: ProjectConfigOverrides = {}
): ProjectConfig {
  const defaults: ProjectConfig = {
    projectName: 'test-project',
    description: 'A test project',
    author: 'Test Author',
    framework: 'node-backend',
    packageManager: 'npm',
    useDocker: true,
    useCI: true,
    ciProvider: 'github',
    deployTarget: 'docker',
    usePreCommitHook: true,
    template: null,
    quiet: false,
    gitRemoteUrl: undefined,
    targetDir: '/tmp/test-project',
    projectVersion: '0.1.0',
  };

  return { ...defaults, ...overrides };
}

export function createUserPreferences(
  overrides: Partial<UserPreferences> = {}
): UserPreferences {
  const defaults: UserPreferences = {
    lastFramework: 'node-backend',
    lastPackageManager: 'npm',
    lastCiProvider: 'github',
    lastAuthor: 'Test Author',
    lastUseDocker: true,
    lastUsePreCommitHook: true,
    templateVersion: '1.0.0',
    lastUpdateCheck: Date.now(),
  };

  return { ...defaults, ...overrides };
}

export function createCiConfig(
  overrides: Partial<CiConfig> = {}
): CiConfig {
  return { ...DEFAULT_CI_CONFIG, ...overrides };
}

export function createDockerConfig(
  overrides: Partial<DockerConfig> = {}
): DockerConfig {
  return { ...DEFAULT_DOCKER_CONFIG, ...overrides };
}

export const allFrameworks: FrameworkType[] = [
  'node-backend',
  'react-frontend',
  'vue-frontend',
  'cli-tool',
];

export const allPackageManagers: PackageManagerType[] = ['npm', 'yarn', 'pnpm'];

export const allCiProviders: CiProviderType[] = ['github', 'gitlab', 'none'];

export const allDeployTargets: DeployTargetType[] = ['docker', 'k8s', 'none'];
