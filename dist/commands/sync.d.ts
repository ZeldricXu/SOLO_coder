import { Command } from '@oclif/core';
export default class SyncCommand extends Command {
    static description: string;
    static aliases: string[];
    static args: {
        key: import("@oclif/core/lib/interfaces").Arg<string, Record<string, unknown>>;
        source: import("@oclif/core/lib/interfaces").Arg<string, Record<string, unknown>>;
        targets: import("@oclif/core/lib/interfaces").Arg<string, Record<string, unknown>>;
    };
    static flags: {
        config: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        dryRun: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        validate: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        verify: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        schema: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        gitCommit: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        notify: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        operator: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        json: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        force: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
    };
    run(): Promise<void>;
}
