import os
import sys

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS_DIR = os.path.dirname(os.path.abspath(__file__))

sys.path.insert(0, PROJECT_ROOT)
sys.path.insert(0, TESTS_DIR)

API_BASE_URL = os.getenv('API_BASE_URL', 'http://localhost:3000/api/v1')

CHAIN_IDS = {
    'ETHEREUM': 1,
    'BSC': 56,
    'POLYGON': 137,
    'ARBITRUM': 42161,
    'OPTIMISM': 10,
}

TRANSFER_STATUSES = [
    'PENDING',
    'LOCKED',
    'VALIDATED',
    'MINTED',
    'CONFIRMED',
    'FAILED',
    'REJECTED',
]

PROPOSAL_STATUSES = [
    'PENDING',
    'APPROVED',
    'EXECUTED',
    'REJECTED',
    'EXPIRED',
]

PROPOSAL_TYPES = [
    'TRANSFER',
    'APPROVE',
    'EXECUTE',
    'UPDATE_OWNERS',
    'CHANGE_THRESHOLD',
    'CUSTOM',
]

DEFAULT_MULTISIG_OWNERS = [
    '0x742d35Cc6634C0532925a3b844Bc9e8588c10516',
    '0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199',
    '0x1aE0EA34a72D944a8C7603FfB3eC30a6669E454C',
]

DEFAULT_THRESHOLD = 2
