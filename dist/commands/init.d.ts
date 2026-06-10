import { Command } from '@oclif/core';
export default class InitCommand extends Command {
    static description: string;
    static examples: string[];
    static flags: {
        force: import("@oclif/core/lib/interfaces").BooleanFlag<boolean>;
        output: import("@oclif/core/lib/interfaces").OptionFlag<string | undefined, import("@oclif/core/lib/interfaces").CustomOptions>;
    };
    run(): Promise<void>;
}
