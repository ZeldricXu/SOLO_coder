import { Command } from '@oclif/core';
export default class ValidateCommand extends Command {
    static description: string;
    static aliases: string[];
    static args: {
        environment: import("@oclif/core/lib/interfaces").Arg<string | undefined, Record<string, unknown>>;
    };
    static flags: {
        config: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        schema: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        json: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        noHistory: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        strict: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
    };
    run(): Promise<void>;
}
