import { IPermissionChecker } from './interfaces';
import { User, SensitiveFieldConfig } from '../core/types';

export class PermissionChecker implements IPermissionChecker {
  public hasPermission(user: User, config: SensitiveFieldConfig): boolean {
    if (!config.requiredPermission) {
      return false;
    }

    return user.roles.some(role => 
      role.permissions.includes(config.requiredPermission!)
    );
  }
}

export const createPermissionChecker = (): PermissionChecker => {
  return new PermissionChecker();
};
