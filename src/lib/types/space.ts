import type { Role } from './permission';
import type { User } from '@prisma/client';

export type SpaceVisibility = 'PRIVATE' | 'PUBLIC';

export interface SpaceMember {
  id: string;
  userId: string;
  spaceId: string;
  role: Role;
  joinedAt: Date;
  user: Pick<User, 'id' | 'name' | 'email' | 'avatar'>;
}

export interface SpaceBasic {
  id: string;
  name: string;
  description: string | null;
  icon: string | null;
  color: string | null;
  visibility: SpaceVisibility;
  createdById: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface SpaceWithCounts extends SpaceBasic {
  _count: {
    members: number;
    documents: number;
  };
}

export interface SpaceWithOwner extends SpaceWithCounts {
  createdBy: Pick<User, 'id' | 'name' | 'email' | 'avatar'>;
}

export interface SpaceWithMembers extends SpaceWithOwner {
  members: SpaceMember[];
}

export interface CreateSpaceInput {
  name: string;
  description?: string;
  icon?: string;
  color?: string;
  visibility?: SpaceVisibility;
  password?: string;
}

export interface UpdateSpaceInput {
  id: string;
  name?: string;
  description?: string;
  icon?: string;
  color?: string;
  visibility?: SpaceVisibility;
}

export interface AddMemberInput {
  spaceId: string;
  userId: string;
  role?: Role;
}

export interface UpdateMemberRoleInput {
  spaceId: string;
  userId: string;
  role: Role;
}

export interface RemoveMemberInput {
  spaceId: string;
  userId: string;
}

export interface SetSpacePasswordInput {
  spaceId: string;
  password: string;
}

export interface VerifySpacePasswordInput {
  spaceId: string;
  password: string;
}

export interface CreateShareLinkInput {
  spaceId: string;
  password?: string;
  expiresAt?: Date;
  role?: Role;
}

export interface SpaceShareLink {
  id: string;
  spaceId: string;
  token: string;
  role: Role;
  expiresAt: Date | null;
  createdAt: Date;
  createdById: string;
}

export interface ShareLinkWithSpace extends SpaceShareLink {
  space: SpaceBasic;
}

export interface ValidateShareLinkResult {
  valid: boolean;
  space?: SpaceBasic;
  document?: any;
  role?: Role;
  requiresPassword: boolean;
  expiresAt?: Date | null;
  error?: string;
  token?: string;
}

export interface PaginatedSpaces {
  items: SpaceWithOwner[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface SpaceListFilter {
  search?: string;
  role?: Role;
  page?: number;
  pageSize?: number;
}
