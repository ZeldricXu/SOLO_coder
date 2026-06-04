export type Role = 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER';

export type SpacePermission = 'view' | 'edit' | 'manage' | 'share' | 'delete';

export type DocumentPermission = 'view' | 'create' | 'edit' | 'delete' | 'review';

export type CommentPermission = 'view' | 'create' | 'resolve' | 'delete';

export type PermissionType = SpacePermission | DocumentPermission | CommentPermission;

export interface PermissionCheckOptions {
  requireAll?: boolean;
}

export interface RoleHierarchy {
  [key: string]: number;
}

export interface RolePermissions {
  space: SpacePermission[];
  document: DocumentPermission[];
  comment: CommentPermission[];
}

export interface PermissionContext {
  userId: string;
  spaceId: string;
  userRole?: Role;
}
