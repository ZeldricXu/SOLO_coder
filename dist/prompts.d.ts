import type { ProjectConfig, PackageManagerType } from './types.js';
export declare function runInteractiveWizard(defaults: Partial<ProjectConfig>, availablePMs: PackageManagerType[]): Promise<Partial<ProjectConfig>>;
export declare function confirmOverwrite(dir: string): Promise<boolean>;
//# sourceMappingURL=prompts.d.ts.map