export declare function createTempDir(prefix?: string): string;
export declare function removeTempDir(dir: string): void;
export declare function writeEnvFile(dir: string, filename: string, content: string): string;
export declare function writeTemplate(dir: string, filename: string, content: string): string;
export declare function readOutputFile(filePath: string): string;
export declare function createGitRepo(dir: string): void;
