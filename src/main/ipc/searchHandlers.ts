import { ipcMain } from 'electron';
import { IpcChannelName } from '../../shared/ipc/channels';
import { SearchService } from '../services/searchService';

interface SearchHandlerDeps {
  searchService: typeof SearchService;
}

export function registerSearchHandlers(deps: SearchHandlerDeps): void {
  const { searchService } = deps;

  ipcMain.handle(IpcChannelName.SEARCH_QUERY, (_event, q: string, options?: any) => {
    return searchService.query(q, options);
  });
}
