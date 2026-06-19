import type { ProjectConfig, DockerConfig } from '../types.js';
export declare class DockerGenerator {
    private config;
    private dockerConfig;
    private targetDir;
    constructor(config: ProjectConfig, dockerConfig?: Partial<DockerConfig>);
    generate(): Promise<void>;
    private generateDockerfile;
    private getDockerfileContent;
    private getBackendDockerfile;
    private getFrontendDockerfile;
    private getLockFile;
    private getPmInstallCommand;
    private getPmRunCommand;
    private getExposePort;
    private isFrontend;
    private generateDockerCompose;
    private getDockerComposeConfig;
    private getDependsOn;
    private generateNginxConfig;
    private generateDockerIgnore;
    private generateK8sManifests;
}
//# sourceMappingURL=docker-generator.d.ts.map