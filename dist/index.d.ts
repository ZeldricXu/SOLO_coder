#!/usr/bin/env node
export interface CreateOptions {
    quiet?: boolean;
    framework?: string;
    packageManager?: string;
    docker?: boolean;
    ci?: string;
    deploy?: string;
    template?: string;
    templateVersion?: string;
    author?: string;
    description?: string;
    gitRemote?: string;
    preCommit?: boolean;
    force?: boolean;
}
export interface ValidationError {
    message: string;
    code: string;
}
export declare function validateCreateOptions(options: CreateOptions): ValidationError[];
//# sourceMappingURL=index.d.ts.map