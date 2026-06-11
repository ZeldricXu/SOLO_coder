import { Command } from '@oclif/core';
export default class RenderCommand extends Command {
    static description: string;
    static aliases: string[];
    static args: {
        template: import("@oclif/core/lib/interfaces").Arg<string | undefined, Record<string, unknown>>;
        environment: import("@oclif/core/lib/interfaces").Arg<string | undefined, Record<string, unknown>>;
    };
    static flags: {
        config: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        template: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        output: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        templatesDir: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        data: import("@oclif/core/lib/interfaces").OptionFlag<string[] | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
        stdin: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        listHelpers: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        listPartials: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        dryRun: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        json: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        verbose: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        templateEngine: import("@oclif/core/lib/interfaces").OptionFlag<string, import("@oclif/core/lib/interfaces").CustomOptions>;
    };
    run(): Promise<void>;
    private readStdin;
    private applyDataOverrides;
    private setByPath;
    private parseValue;
}
