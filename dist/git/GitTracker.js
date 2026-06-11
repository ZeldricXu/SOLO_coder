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
exports.GitTracker = void 0;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const dayjs_1 = __importDefault(require("dayjs"));
class GitTracker {
    repoPath;
    configDir;
    git = null;
    constructor(repoPath) {
        this.repoPath = path.resolve(repoPath);
        this.configDir = path.join(this.repoPath, 'configs');
    }
    async initGit() {
        if (this.git)
            return;
        const { default: simpleGit } = await Promise.resolve().then(() => __importStar(require('simple-git')));
        this.git = simpleGit(this.repoPath);
        if (!fs.existsSync(this.repoPath)) {
            fs.mkdirSync(this.repoPath, { recursive: true });
        }
        if (!fs.existsSync(this.configDir)) {
            fs.mkdirSync(this.configDir, { recursive: true });
        }
        const gitDir = path.join(this.repoPath, '.git');
        if (!fs.existsSync(gitDir)) {
            await this.git.init();
        }
    }
    async ensureInitialized(options = {}) {
        await this.initGit();
        const hasCommits = await this.hasCommits();
        if (!hasCommits) {
            const readme = '# ConfigFlow Configurations\n\nThis directory is managed by ConfigFlow CLI.\n';
            fs.writeFileSync(path.join(this.repoPath, 'README.md'), readme);
            await this.git.add('.');
            await this.git.commit('init: initial config repository', [], {
                '--author': this.formatAuthor(options),
            });
        }
    }
    async hasCommits() {
        try {
            const log = await this.git.log(['-1']);
            return !!log.latest;
        }
        catch {
            return false;
        }
    }
    formatAuthor(options) {
        const name = options.authorName || options.operator || process.env.USER || 'config-flow';
        const email = options.authorEmail || 'config-flow@local';
        return `${name} <${email}>`;
    }
    saveEnvironmentSnapshot(environment, data) {
        const filePath = path.join(this.configDir, `${environment}.json`);
        const content = JSON.stringify(data, null, 2) + '\n';
        fs.writeFileSync(filePath, content);
        return filePath;
    }
    saveAllSnapshots(snapshot) {
        const paths = [];
        for (const [env, data] of Object.entries(snapshot)) {
            paths.push(this.saveEnvironmentSnapshot(env, data));
        }
        return paths;
    }
    flattenData(obj, prefix = '') {
        const result = {};
        for (const [key, value] of Object.entries(obj)) {
            const fullKey = prefix ? `${prefix}.${key}` : key;
            if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
                Object.assign(result, this.flattenData(value, fullKey));
            }
            else {
                result[fullKey] = value;
            }
        }
        return result;
    }
    async loadEnvironmentSnapshot(environment, commitHash) {
        if (commitHash) {
            return this.loadSnapshotAtCommit(environment, commitHash);
        }
        const filePath = path.join(this.configDir, `${environment}.json`);
        if (!fs.existsSync(filePath))
            return null;
        try {
            const raw = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
            return this.flattenData(raw);
        }
        catch {
            return null;
        }
    }
    async loadSnapshotAtCommit(environment, commitHash) {
        await this.initGit();
        const relativePath = path.relative(this.repoPath, path.join(this.configDir, `${environment}.json`));
        try {
            const content = await this.git.show([`${commitHash}:${relativePath}`]);
            return JSON.parse(content);
        }
        catch {
            return null;
        }
    }
    async commitChanges(message, options = {}) {
        await this.initGit();
        await this.ensureInitialized(options);
        const status = await this.git.status();
        if (status.files.length === 0) {
            return null;
        }
        await this.git.add('.');
        const author = this.formatAuthor(options);
        const fullMessage = options.operator
            ? `[${options.operator}] ${message}`
            : message;
        const result = await this.git.commit(fullMessage, [], {
            '--author': author,
        });
        if (!result.commit)
            return null;
        return {
            hash: result.commit,
            author: author,
            timestamp: Date.now(),
            message: fullMessage,
            changes: status.files.map((f) => f.path),
        };
    }
    async log(options = {}) {
        await this.initGit();
        const logArgs = [];
        if (options.limit)
            logArgs.push(`-n${options.limit}`);
        if (options.since)
            logArgs.push(`--since=${new Date(options.since).toISOString()}`);
        if (options.until)
            logArgs.push(`--until=${new Date(options.until).toISOString()}`);
        const relConfigDir = path.relative(this.repoPath, this.configDir);
        const pathFilter = options.environment
            ? path.join(relConfigDir, `${options.environment}.json`)
            : relConfigDir;
        try {
            const log = await this.git.log([...logArgs, '--', pathFilter]);
            return log.all.map((entry) => ({
                hash: entry.hash,
                author: `${entry.author_name} <${entry.author_email}>`,
                timestamp: new Date(entry.date).getTime(),
                message: entry.message,
                changes: entry.diff?.files?.map((f) => f.file) || [],
            }));
        }
        catch {
            return [];
        }
    }
    async getKeyHistory(environment, key, limit = 20) {
        await this.initGit();
        const commits = await this.log({ environment, limit });
        const history = [];
        for (const commit of commits) {
            const snapshot = await this.loadSnapshotAtCommit(environment, commit.hash);
            if (!snapshot)
                continue;
            const value = this.getValueByPath(snapshot, key);
            if (value === undefined)
                continue;
            const last = history[history.length - 1];
            if (last && JSON.stringify(last.value) === JSON.stringify(value)) {
                continue;
            }
            history.push({
                commitHash: commit.hash,
                timestamp: commit.timestamp,
                author: commit.author,
                message: commit.message,
                value,
            });
        }
        return history.reverse();
    }
    async diffCommits(commitA, commitB, environment) {
        await this.initGit();
        const diffArgs = [`${commitA}..${commitB}`];
        const relConfigDir = path.relative(this.repoPath, this.configDir);
        if (environment) {
            diffArgs.push('--', path.join(relConfigDir, `${environment}.json`));
        }
        else {
            diffArgs.push('--', relConfigDir);
        }
        const diffOutput = await this.git.diff(diffArgs);
        const fileDiffs = [];
        const sections = diffOutput.split(/^diff --git /m).slice(1);
        for (const section of sections) {
            const lines = section.split('\n');
            const fileMatch = lines[0]?.match(/b\/(.+)$/);
            const file = fileMatch ? fileMatch[1] : 'unknown';
            fileDiffs.push({
                file,
                changes: section,
            });
        }
        return fileDiffs;
    }
    async getLastCommitHash(environment) {
        const commits = await this.log({ environment, limit: 1 });
        return commits[0]?.hash || null;
    }
    async formatCommitRecord(record) {
        const lines = [];
        lines.push(`commit ${record.hash}`);
        lines.push(`Author: ${record.author}`);
        lines.push(`Date:   ${(0, dayjs_1.default)(record.timestamp).format('YYYY-MM-DD HH:mm:ss ZZ')}`);
        lines.push('');
        lines.push(`    ${record.message}`);
        if (record.changes.length > 0) {
            lines.push('');
            lines.push('    Changes:');
            for (const c of record.changes) {
                lines.push(`      - ${c}`);
            }
        }
        return lines.join('\n');
    }
    async formatKeyHistory(history, key, environment) {
        const lines = [];
        lines.push(`History for key "${key}" in environment "${environment}"`);
        lines.push('='.repeat(80));
        if (history.length === 0) {
            lines.push('(no history found)');
            return lines.join('\n');
        }
        for (const entry of history) {
            lines.push('');
            lines.push(`commit ${entry.commitHash}`);
            lines.push(`Author: ${entry.author}`);
            lines.push(`Date:   ${(0, dayjs_1.default)(entry.timestamp).format('YYYY-MM-DD HH:mm:ss ZZ')}`);
            lines.push('');
            lines.push(`    ${entry.message}`);
            lines.push('');
            lines.push(`    Value: ${JSON.stringify(entry.value, null, 2).split('\n').join('\n    ')}`);
        }
        return lines.join('\n');
    }
    getValueByPath(data, pathStr) {
        const parts = pathStr.split('.');
        let current = data;
        for (const part of parts) {
            if (current === null || current === undefined)
                return undefined;
            if (typeof current === 'object' && !Array.isArray(current)) {
                current = current[part];
            }
            else {
                return undefined;
            }
        }
        return current;
    }
    getRepoPath() {
        return this.repoPath;
    }
    getConfigDir() {
        return this.configDir;
    }
}
exports.GitTracker = GitTracker;
//# sourceMappingURL=GitTracker.js.map