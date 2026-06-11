import { Command } from '@oclif/core';
export default class DiffCommand extends Command {
    static description: string;
    static aliases: string[];
    static args: {
        envA: import("@oclif/core/lib/interfaces").Arg<string, Record<string, unknown>>;
        envB: import("@oclif/core/lib/interfaces").Arg<string | undefined, Record<string, unknown>>;
    };
    static flags: {
        config: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        type: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        key: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        ignore: import("@oclif/core/lib/interfaces").OptionFlag<string[] | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        json: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        noColor: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        noHistory: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        output: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        failOnDrift: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        cascade: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
    };
    run(): Promise<void>;
    private runCascade;
    private runStandard;
}
