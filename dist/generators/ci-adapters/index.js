"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CircleCIAdapter = exports.BitbucketPipelinesAdapter = exports.GitLabCIAdapter = exports.GitHubActionsAdapter = void 0;
exports.getCiAdapter = getCiAdapter;
const github_adapter_js_1 = require("./github-adapter.js");
Object.defineProperty(exports, "GitHubActionsAdapter", { enumerable: true, get: function () { return github_adapter_js_1.GitHubActionsAdapter; } });
const gitlab_adapter_js_1 = require("./gitlab-adapter.js");
Object.defineProperty(exports, "GitLabCIAdapter", { enumerable: true, get: function () { return gitlab_adapter_js_1.GitLabCIAdapter; } });
const bitbucket_adapter_js_1 = require("./bitbucket-adapter.js");
Object.defineProperty(exports, "BitbucketPipelinesAdapter", { enumerable: true, get: function () { return bitbucket_adapter_js_1.BitbucketPipelinesAdapter; } });
const circleci_adapter_js_1 = require("./circleci-adapter.js");
Object.defineProperty(exports, "CircleCIAdapter", { enumerable: true, get: function () { return circleci_adapter_js_1.CircleCIAdapter; } });
const adapterMap = {
    github: github_adapter_js_1.GitHubActionsAdapter,
    gitlab: gitlab_adapter_js_1.GitLabCIAdapter,
    bitbucket: bitbucket_adapter_js_1.BitbucketPipelinesAdapter,
    circleci: circleci_adapter_js_1.CircleCIAdapter,
};
function getCiAdapter(provider) {
    if (provider === 'none')
        return null;
    const AdapterClass = adapterMap[provider];
    if (!AdapterClass)
        return null;
    return new AdapterClass();
}
//# sourceMappingURL=index.js.map