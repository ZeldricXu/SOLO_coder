import { Command } from '@oclif/core';
export default class GitDiffCommand extends Command {
    static description: string;
    static aliases: string[];
    static args: {
        commitA: import("@oclif/core/lib/interfaces").Arg<string | undefined, Record<string, unknown>>;
        commitB: import("@oclif/core/lib/interfaces").Arg<string | undefined, Record<string, unknown>>;
    };
    static flags: {
        config: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        environment: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        key: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        noColor: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        json: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
    };
    run(): Promise<void>;
}
