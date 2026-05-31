import { ChainConfig, ChainId } from '../types';
export declare const CHAIN_CONFIGS: Record<ChainId, ChainConfig>;
export declare const DEFAULT_HD_PATH = "m/44'/60'/0'/0";
export declare const DEFAULT_RETRY_ATTEMPTS = 3;
export declare const DEFAULT_RETRY_DELAY = 1000;
export declare const DEFAULT_TIMEOUT = 30000;
export declare const GAS_HISTORY_WINDOW = 100;
export declare const GAS_PRICE_PERCENTILES: {
    slow: number;
    standard: number;
    fast: number;
};
export declare const REDIS_CONFIG: {
    host: string;
    port: number;
    password: string | undefined;
    db: number;
};
export declare const IPFS_CONFIG: {
    url: string;
    gateway: string;
};
export declare const ARWEAVE_CONFIG: {
    host: string;
    port: number;
    protocol: string;
};
//# sourceMappingURL=index.d.ts.map