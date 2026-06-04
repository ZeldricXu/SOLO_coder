import type { Role, SpacePermission, DocumentPermission, CommentPermission, RoleHierarchy, RolePermissions } from '../types/permission';

export const ROLE_HIERARCHY: RoleHierarchy = {
  OWNER: 4,
  ADMIN: 3,
  EDITOR: 2,
  VIEWER: 1,
};

export const ROLES: Role[] = ['OWNER', 'ADMIN', 'EDITOR', 'VIEWER'];

export const SPACE_PERMISSIONS: SpacePermission[] = ['view', 'edit', 'manage', 'share', 'delete'];

export const DOCUMENT_PERMISSIONS: DocumentPermission[] = ['view', 'create', 'edit', 'delete', 'review'];

export const COMMENT_PERMISSIONS: CommentPermission[] = ['view', 'create', 'resolve', 'delete'];

export const ROLE_PERMISSIONS: Record<Role, RolePermissions> = {
  OWNER: {
    space: ['view', 'edit', 'manage', 'share', 'delete'],
    document: ['view', 'create', 'edit', 'delete', 'review'],
    comment: ['view', 'create', 'resolve', 'delete'],
  },
  ADMIN: {
    space: ['view', 'edit', 'manage', 'share'],
    document: ['view', 'create', 'edit', 'delete', 'review'],
    comment: ['view', 'create', 'resolve', 'delete'],
  },
  EDITOR: {
    space: ['view', 'edit'],
    document: ['view', 'create', 'edit'],
    comment: ['view', 'create', 'resolve'],
  },
  VIEWER: {
    space: ['view'],
    document: ['view'],
    comment: ['view', 'create'],
  },
};

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: '所有者',
  ADMIN: '管理员',
  EDITOR: '编辑者',
  VIEWER: '阅读者',
};

export const canEditRoles: Role[] = ['OWNER', 'ADMIN', 'EDITOR'];

export const canManageRoles: Role[] = ['OWNER', 'ADMIN'];

export const canShareRoles: Role[] = ['OWNER', 'ADMIN'];

export const canDeleteRoles: Role[] = ['OWNER'];

export function getPermissionsForRole(role: Role): RolePermissions {
  return ROLE_PERMISSIONS[role];
}

export function hasRole(requiredRole: Role, userRole: Role): boolean {
  return ROLE_HIERARCHY[userRole] >= ROLE_HIERARCHY[requiredRole];
}

export function hasSpacePermission(permission: SpacePermission, role: Role): boolean {
  return ROLE_PERMISSIONS[role].space.includes(permission);
}

export function hasDocumentPermission(permission: DocumentPermission, role: Role): boolean {
  return ROLE_PERMISSIONS[role].document.includes(permission);
}

export function hasCommentPermission(permission: CommentPermission, role: Role): boolean {
  return ROLE_PERMISSIONS[role].comment.includes(permission);
}
