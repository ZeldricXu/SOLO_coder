import { Route } from './types';
import { generateId, logger } from '../utils/common';

export class Router {
  private routes: Map<string, Route> = new Map();
  private pathMatchers: Array<{ route: Route; regex: RegExp; paramNames: string[] }> = [];

  addRoute(route: Omit<Route, 'id'>): Route {
    const id = generateId('route_');
    const fullRoute: Route = { ...route, id } as Route;

    this.routes.set(id, fullRoute);
    this.buildPathMatcher(fullRoute);

    logger.info(`Route added`, { id, path: fullRoute.path, method: fullRoute.method });
    return fullRoute;
  }

  private buildPathMatcher(route: Route): void {
    const paramNames: string[] = [];
    const pattern = route.path.replace(/:(\w+)/g, (_, name) => {
      paramNames.push(name);
      return '([^/]+)';
    });
    const regex = new RegExp(`^${pattern}$`);

    this.pathMatchers.push({ route, regex, paramNames });
  }

  matchRoute(method: string, path: string): { route: Route; params: Record<string, string> } | null {
    for (const matcher of this.pathMatchers) {
      if (matcher.route.method !== method) continue;

      const match = path.match(matcher.regex);
      if (match) {
        const params: Record<string, string> = {};
        matcher.paramNames.forEach((name, index) => {
          params[name] = match[index + 1];
        });
        return { route: matcher.route, params };
      }
    }
    return null;
  }

  getRoute(id: string): Route | undefined {
    return this.routes.get(id);
  }

  updateRoute(id: string, updates: Partial<Route>): Route | undefined {
    const route = this.routes.get(id);
    if (!route) return undefined;

    const updated: Route = { ...route, ...updates };
    this.routes.set(id, updated);

    this.rebuildPathMatchers();
    logger.info(`Route updated`, { id });
    return updated;
  }

  removeRoute(id: string): boolean {
    const deleted = this.routes.delete(id);
    if (deleted) {
      this.rebuildPathMatchers();
      logger.info(`Route removed`, { id });
    }
    return deleted;
  }

  private rebuildPathMatchers(): void {
    this.pathMatchers = [];
    for (const route of this.routes.values()) {
      this.buildPathMatcher(route);
    }
  }

  listRoutes(): Route[] {
    return Array.from(this.routes.values());
  }
}

export const router = new Router();
