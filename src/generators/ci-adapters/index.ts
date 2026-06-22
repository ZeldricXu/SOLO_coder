import type { CiAdapter, CiProviderType } from '../../types.js';
import { GitHubActionsAdapter } from './github-adapter.js';
import { GitLabCIAdapter } from './gitlab-adapter.js';
import { BitbucketPipelinesAdapter } from './bitbucket-adapter.js';
import { CircleCIAdapter } from './circleci-adapter.js';

const adapterMap: Record<Exclude<CiProviderType, 'none'>, new () => CiAdapter> = {
  github: GitHubActionsAdapter,
  gitlab: GitLabCIAdapter,
  bitbucket: BitbucketPipelinesAdapter,
  circleci: CircleCIAdapter,
};

export function getCiAdapter(provider: CiProviderType): CiAdapter | null {
  if (provider === 'none') return null;
  const AdapterClass = adapterMap[provider];
  if (!AdapterClass) return null;
  return new AdapterClass();
}

export { GitHubActionsAdapter, GitLabCIAdapter, BitbucketPipelinesAdapter, CircleCIAdapter };
export type { CiAdapter };
