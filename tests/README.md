# DIDAuth Service Test Suite

This directory contains comprehensive automated tests for the DIDAuth decentralized identity verification service.

## Test Structure

```
tests/
├── conftest.py                 # Shared fixtures and configurations
├── pytest.ini                  # pytest configuration
├── requirements.txt            # Test dependencies
├── builders/
│   ├── __init__.py            # Builder module with all test data constructors
│   └── module_exports.py      # Module exports documentation
├── test_zkp_verification.py   # ZKP module tests (normal + exception flows)
├── test_hd_wallet.py          # HD Wallet tests (concurrent safety focus)
└── test_block_indexer.py      # Block Indexer tests (resource release focus)
```

## Test Focus Areas

### 1. Zero-Knowledge Proof Verification Module (`test_zkp_verification.py`)
**Focus**: Normal flow and exception flow verification

**Normal Flow Tests**:
- Valid proof verification
- Multiple public inputs handling
- Metrics recording
- Proof status retrieval
- Circuit diversity support
- Status lifecycle transitions
- Concurrent verification capacity

**Exception Flow Tests**:
- Empty proof data handling
- Short proof data rejection
- Malformed JSON proof handling
- Invalid proof failure
- Non-existent proof queries
- Database error handling
- Verification timeout handling
- Null/empty circuit ID validation

### 2. HD Wallet Address Derivation Module (`test_hd_wallet.py`)
**Focus**: Concurrent operation safety

**Normal Flow Tests**:
- Ethereum address derivation
- Bitcoin address derivation
- Custom labels and tags
- HD path sequence uniqueness
- Wallet detail retrieval
- Wallet listing with filters

**Concurrent Safety Tests**:
- 50 concurrent address derivations
- 30 concurrent address book additions
- Same-path concurrent derivation
- Mixed CRUD operations under concurrency
- ThreadPoolExecutor thread safety
- Memory consistency under stress

**Edge Case Tests**:
- Unknown chain type rejection
- Non-existent wallet queries
- Duplicate address book rejection
- Very large derivation index
- Multi-chain support validation

### 3. Block Data Indexer Module (`test_block_indexer.py`)
**Focus**: Resource release completeness

**Normal Flow Tests**:
- Block parsing and indexing
- Empty block handling
- Block retrieval by number
- Transaction retrieval by hash
- Latest blocks listing

**Resource Release Tests**:
- Large block memory release verification
- Concurrent indexing file descriptor leak detection
- Weak reference garbage collection
- Database connection release tracking
- Batch processing memory stability
- Duplicate block indexing resource leak prevention

**Data Integrity Tests**:
- Transaction data integrity verification
- Block/transaction count consistency

## Builder Pattern

All test data construction uses the Builder pattern through `tests.builders.BuilderFactory`:

```python
from tests.builders import BuilderFactory

# Build valid ZKP proof
proof = BuilderFactory.zkp_proof().with_valid_proof().build()

# Build HD wallet for Ethereum with index 5
wallet = BuilderFactory.hd_wallet().for_eth().with_index(5).build()

# Build block with 100 transactions
block = BuilderFactory.block_data().with_transaction_count(100).build()

# Bulk build with modifications
wallets = BuilderFactory.bulk_build(
    BuilderFactory.hd_wallet().for_eth(),
    count=10,
    modifier=lambda i, b: b.with_index(i).with_label(f"Wallet {i}")
)
```

## Running Tests

### Prerequisites

```bash
cd session179
pip install -r tests/requirements.txt
```

### Basic Usage

```bash
# Run all tests
python run_tests.py

# Run only unit tests
python run_tests.py unit

# Run only ZKP tests
python run_tests.py zkp

# Run only HD wallet tests
python run_tests.py hdwallet

# Run only block indexer tests
python run_tests.py indexer

# Run concurrent tests
python run_tests.py concurrent

# Run with verbose output
python run_tests.py -v

# Run with coverage report
python run_tests.py --coverage

# Run tests concurrently
python run_tests.py --concurrent
```

### Direct pytest Usage

```bash
# Run all tests
pytest tests/ -v

# Run specific test file
pytest tests/test_zkp_verification.py -v

# Run specific test class
pytest tests/test_zkp_verification.py::TestZkpNormalFlow -v

# Run specific test method
pytest tests/test_zkp_verification.py::TestZkpNormalFlow::test_verify_valid_proof_success -v

# Run with markers
pytest tests/ -m "zkp and normal" -v
pytest tests/ -m "concurrent" -v
pytest tests/ -m "resource" -v
```

### Test Markers

| Marker | Description |
|--------|-------------|
| `unit` | Unit tests |
| `integration` | Integration tests |
| `concurrent` | Concurrent operation tests |
| `zkp` | ZKP module tests |
| `hdwallet` | HD Wallet module tests |
| `indexer` | Block Indexer module tests |
| `normal` | Normal flow tests |
| `exception` | Exception flow tests |
| `resource` | Resource management tests |

## Coverage Target

- Minimum coverage: 70%
- Core modules (ZKP, HD Wallet, Block Indexer): >85%
- Critical paths: 100%

## Test Data Isolation

All tests use:
- Unique generated identifiers
- In-memory mocks for external dependencies
- Builder pattern for consistent data construction
- No shared state between tests
- Automatic cleanup via pytest fixtures

## Mock Strategy

External dependencies are mocked using `unittest.mock`:
- Database mappers (MyBatis-Plus)
- Meter registry (Micrometer)
- WebClient for HTTP requests
- ObjectMapper for JSON serialization
- All database operations

This ensures tests are fast, deterministic, and do not require external services.
