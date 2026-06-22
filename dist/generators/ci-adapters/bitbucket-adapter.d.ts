import type { CiAdapter, CiPipeline, ProjectConfig } from '../../types.js';
export declare class BitbucketPipelinesAdapter implements CiAdapter {
    getFileName(): string;
    getFilePath(targetDir: string): string;
    render(pipeline: CiPipeline, _config: ProjectConfig): string;
    private getNodeVersion;
    private buildPipelineSteps;
    private buildStep;
    private buildScript;
    private buildServices;
}
//# sourceMappingURL=bitbucket-adapter.d.ts.map