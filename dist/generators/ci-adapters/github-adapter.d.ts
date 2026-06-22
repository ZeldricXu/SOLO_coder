import type { CiAdapter, CiPipeline, ProjectConfig } from '../../types.js';
export declare class GitHubActionsAdapter implements CiAdapter {
    getFileName(): string;
    getFilePath(targetDir: string): string;
    render(pipeline: CiPipeline, _config: ProjectConfig): string;
    private buildTrigger;
    private buildJobs;
    private buildJob;
    private buildSteps;
    private buildServices;
}
//# sourceMappingURL=github-adapter.d.ts.map