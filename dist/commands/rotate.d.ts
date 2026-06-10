import { Command } from '@oclif/core';
export default class RotateCommand extends Command {
    static description: string;
    static args: {
        key: import("@oclif/core/lib/interfaces").Arg<string, Record<string, unknown>>;
        environment: import("@oclif/core/lib/interfaces").Arg<string, Record<string, unknown>>;
    };
    static flags: {
        config: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        batch: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        list: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        check: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        maxAge: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        schedule: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        operator: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        notify: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        verify: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        json: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        force: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
    };
    run(): Promise<void>;
    private listHistory;
    private parseDuration;
}
