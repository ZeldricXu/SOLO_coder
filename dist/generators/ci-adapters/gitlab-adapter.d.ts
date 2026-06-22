import type { CiAdapter, CiPipeline, ProjectConfig } from '../../types.js';
export declare class GitLabCIAdapter implements CiAdapter {
    getFileName(): string;
    getFilePath(targetDir: string): string;
    render(pipeline: CiPipeline, _config: ProjectConfig): string;
    private getNodeVersion;
    private buildServices;
    private buildJob;
    private buildScript;
    private buildCache;
}
//# sourceMappingURL=gitlab-adapter.d.ts.map