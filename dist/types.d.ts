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
export declare const DEFAULT_CI_CONFIG: CiConfig;
export declare const DEFAULT_DOCKER_CONFIG: DockerConfig;
export declare const FRAMEWORK_NAMES: Record<FrameworkType, string>;
export declare const PACKAGE_MANAGER_NAMES: Record<PackageManagerType, string>;
export declare const CI_PROVIDER_NAMES: Record<CiProviderType, string>;
export declare const DEPLOY_TARGET_NAMES: Record<DeployTargetType, string>;
//# sourceMappingURL=types.d.ts.map