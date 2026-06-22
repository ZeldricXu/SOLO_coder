import type { CiAdapter, CiProviderType } from '../../types.js';
import { GitHubActionsAdapter } from './github-adapter.js';
import { GitLabCIAdapter } from './gitlab-adapter.js';
import { BitbucketPipelinesAdapter } from './bitbucket-adapter.js';
import { CircleCIAdapter } from './circleci-adapter.js';
export declare function getCiAdapter(provider: CiProviderType): CiAdapter | null;
export { GitHubActionsAdapter, GitLabCIAdapter, BitbucketPipelinesAdapter, CircleCIAdapter };
export type { CiAdapter };
//# sourceMappingURL=index.d.ts.map