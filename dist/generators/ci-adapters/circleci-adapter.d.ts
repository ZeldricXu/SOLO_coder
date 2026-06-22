import type { CiAdapter, CiPipeline, ProjectConfig } from '../../types.js';
export declare class CircleCIAdapter implements CiAdapter {
    getFileName(): string;
    getFilePath(targetDir: string): string;
    render(pipeline: CiPipeline, _config: ProjectConfig): string;
    private buildDockerExecutor;
    private getNodeVersion;
    private buildJob;
    private buildWorkflowJobs;
}
//# sourceMappingURL=circleci-adapter.d.ts.map