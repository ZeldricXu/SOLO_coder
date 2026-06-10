"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("./env/list");
const SchemaValidator_1 = require("../schemas/SchemaValidator");
const formatters_1 = require("../utils/formatters");
const HistoryStorage_1 = require("../storage/HistoryStorage");
const chalk_1 = __importDefault(require("chalk"));
const fs = __importStar(require("fs"));
class ValidateCommand extends core_1.Command {
    static description = 'Validate environment configuration against schema';
    static aliases = ['validate:check', 'check'];
    static args = {
        environment: core_1.Args.string({ description: 'Environment to validate (all if omitted)' }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        schema: core_1.Flags.string({ char: 's', description: 'Path to schema JSON file' }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
        noHistory: core_1.Flags.boolean({ description: 'Do not record validation history' }),
        strict: core_1.Flags.boolean({ description: 'Exit with error code on validation failure' }),
    };
    async run() {
        const { args, flags } = await this.parse(ValidateCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const schemaPath = flags.schema || ctx.config.schemaPath;
        if (!fs.existsSync(schemaPath)) {
            this.error(`Schema file not found: ${schemaPath}. Run 'config-flow init' to generate a sample.`);
        }
        const rawSchema = JSON.parse(fs.readFileSync(schemaPath, 'utf-8'));
        const validator = new SchemaValidator_1.SchemaValidator(rawSchema);
        const environments = args.environment
            ? [args.environment]
            : ctx.configManager.listEnvironments();
        const allReports = [];
        let anyInvalid = false;
        const storage = flags.noHistory ? null : new HistoryStorage_1.HistoryStorage(ctx.config.storagePath);
        for (const envName of environments) {
            const env = ctx.configManager.getEnvironment(envName);
            if (!env) {
                this.warn(`Environment not found: ${envName}, skipping`);
                continue;
            }
            try {
                const data = await env.loadAll();
                const report = validator.validate(data, envName);
                if (storage) {
                    await storage.recordValidation(report);
                }
                allReports.push(report);
                if (!flags.json) {
                    this.log((0, formatters_1.formatValidationReport)(report));
                    this.log('');
                }
                if (!report.valid)
                    anyInvalid = true;
            }
            catch (error) {
                this.error(`Failed to validate ${envName}: ${error.message}`);
            }
        }
        if (storage)
            storage.close();
        if (flags.json) {
            this.log(JSON.stringify(allReports, null, 2));
        }
        const validCount = allReports.filter((r) => r.valid).length;
        const totalCount = allReports.length;
        if (!flags.json) {
            this.log(`Validation Summary: ${chalk_1.default.green(validCount)} valid / ${totalCount} total`);
        }
        if (flags.strict && anyInvalid) {
            this.exit(1);
        }
    }
}
exports.default = ValidateCommand;
//# sourceMappingURL=validate.js.map