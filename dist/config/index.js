"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ARWEAVE_CONFIG = exports.IPFS_CONFIG = exports.REDIS_CONFIG = exports.GAS_PRICE_PERCENTILES = exports.GAS_HISTORY_WINDOW = exports.DEFAULT_TIMEOUT = exports.DEFAULT_RETRY_DELAY = exports.DEFAULT_RETRY_ATTEMPTS = exports.DEFAULT_HD_PATH = exports.CHAIN_CONFIGS = void 0;
exports.CHAIN_CONFIGS = {
    1: {
        chainId: 1,
        name: 'Ethereum Mainnet',
        rpcUrl: process.env.ETH_MAINNET_RPC || 'https://eth.llamarpc.com',
        wsUrl: process.env.ETH_MAINNET_WS,
        blockExplorerUrl: 'https://etherscan.io',
        nativeCurrency: {
            name: 'Ether',
            symbol: 'ETH',
            decimals: 18,
        },
    },
    5: {
        chainId: 5,
        name: 'Goerli Testnet',
        rpcUrl: process.env.GOERLI_RPC || 'https://eth-goerli.public.blastapi.io',
        wsUrl: process.env.GOERLI_WS,
        blockExplorerUrl: 'https://goerli.etherscan.io',
        nativeCurrency: {
            name: 'Goerli Ether',
            symbol: 'ETH',
            decimals: 18,
        },
    },
    137: {
        chainId: 137,
        name: 'Polygon Mainnet',
        rpcUrl: process.env.POLYGON_RPC || 'https://polygon.llamarpc.com',
        wsUrl: process.env.POLYGON_WS,
        blockExplorerUrl: 'https://polygonscan.com',
        nativeCurrency: {
            name: 'MATIC',
            symbol: 'MATIC',
            decimals: 18,
        },
    },
    80001: {
        chainId: 80001,
        name: 'Mumbai Testnet',
        rpcUrl: process.env.MUMBAI_RPC || 'https://rpc-mumbai.maticvigil.com',
        wsUrl: process.env.MUMBAI_WS,
        blockExplorerUrl: 'https://mumbai.polygonscan.com',
        nativeCurrency: {
            name: 'MATIC',
            symbol: 'MATIC',
            decimals: 18,
        },
    },
    42161: {
        chainId: 42161,
        name: 'Arbitrum One',
        rpcUrl: process.env.ARBITRUM_RPC || 'https://arbitrum.llamarpc.com',
        wsUrl: process.env.ARBITRUM_WS,
        blockExplorerUrl: 'https://arbiscan.io',
        nativeCurrency: {
            name: 'Ether',
            symbol: 'ETH',
            decimals: 18,
        },
    },
    10: {
        chainId: 10,
        name: 'Optimism',
        rpcUrl: process.env.OPTIMISM_RPC || 'https://optimism.llamarpc.com',
        wsUrl: process.env.OPTIMISM_WS,
        blockExplorerUrl: 'https://optimistic.etherscan.io',
        nativeCurrency: {
            name: 'Ether',
            symbol: 'ETH',
            decimals: 18,
        },
    },
};
exports.DEFAULT_HD_PATH = "m/44'/60'/0'/0";
exports.DEFAULT_RETRY_ATTEMPTS = 3;
exports.DEFAULT_RETRY_DELAY = 1000;
exports.DEFAULT_TIMEOUT = 30000;
exports.GAS_HISTORY_WINDOW = 100;
exports.GAS_PRICE_PERCENTILES = { slow: 10, standard: 50, fast: 90 };
exports.REDIS_CONFIG = {
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT || '6379'),
    password: process.env.REDIS_PASSWORD,
    db: parseInt(process.env.REDIS_DB || '0'),
};
exports.IPFS_CONFIG = {
    url: process.env.IPFS_URL || 'http://localhost:5001',
    gateway: process.env.IPFS_GATEWAY || 'https://ipfs.io/ipfs/',
};
exports.ARWEAVE_CONFIG = {
    host: process.env.ARWEAVE_HOST || 'arweave.net',
    port: parseInt(process.env.ARWEAVE_PORT || '443'),
    protocol: process.env.ARWEAVE_PROTOCOL || 'https',
};
//# sourceMappingURL=index.js.map