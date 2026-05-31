"""
去中心化存储适配模块测试
====================
重点测试：
1. 资源释放完整性 - 验证HTTP客户端、连接池等资源正确关闭
2. 存储操作正确性 - 验证IPFS/Arweave的增删改查操作
3. 异常场景处理 - 验证网络错误、配置缺失等场景
"""

import asyncio
import json
import gc
import pytest
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch, call
import httpx

from wallethub.core import StorageNetwork, StorageError
from wallethub.modules.storage import IPFSClient, ArweaveClient, StorageManager, StoredContent
from tests.test_factories import StorageFactory, TestDataGenerator


class TestIPFSClient:
    """IPFS客户端测试"""

    @pytest.fixture
    def ipfs_client(self, mock_settings):
        """创建IPFS客户端实例"""
        return IPFSClient(
            api_url="http://localhost:5001",
            gateway_url="https://ipfs.io/ipfs/"
        )

    @pytest.mark.asyncio
    async def test_add_bytes_content(self, ipfs_client):
        """测试添加字节内容"""
        test_content = StorageFactory.create_test_content(size_kb=1)
        mock_response = {"Hash": "QmTestCID123", "Size": "1024", "Name": "content"}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.add(test_content, pin=True)

            assert result == mock_response
            mock_client.post.assert_called_once()
            args, kwargs = mock_client.post.call_args
            assert args[0] == "http://localhost:5001/api/v0/add"
            assert kwargs["params"] == {"pin": "true"}

    @pytest.mark.asyncio
    async def test_add_json_content(self, ipfs_client):
        """测试添加JSON内容"""
        test_json = StorageFactory.create_test_json()
        mock_response = {"Hash": "QmTestCID456", "Size": "256", "Name": "content"}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.add(test_json)

            assert result == mock_response
            mock_client.post.assert_called_once()

    @pytest.mark.asyncio
    async def test_add_string_content(self, ipfs_client):
        """测试添加字符串内容"""
        test_string = "Hello, IPFS!"
        mock_response = {"Hash": "QmTestCID789", "Size": "12", "Name": "content"}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.add(test_string)

            assert result == mock_response

    @pytest.mark.asyncio
    async def test_add_without_pin(self, ipfs_client):
        """测试不Pin添加内容"""
        test_content = StorageFactory.create_test_content()
        mock_response = {"Hash": "QmTestCIDNoPin", "Size": "1024", "Name": "content"}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.add(test_content, pin=False)

            assert result == mock_response
            args, kwargs = mock_client.post.call_args
            assert kwargs["params"] == {"pin": "false"}

    @pytest.mark.asyncio
    async def test_add_network_error(self, ipfs_client):
        """测试添加内容时网络错误"""
        test_content = StorageFactory.create_test_content()

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.side_effect = httpx.ConnectError("Connection refused")
            mock_client_class.return_value.__aenter__.return_value = mock_client

            with pytest.raises(StorageError, match="Failed to add to IPFS"):
                await ipfs_client.add(test_content)

    @pytest.mark.asyncio
    async def test_get_content(self, ipfs_client):
        """测试获取内容"""
        test_cid = "QmTestCID123"
        test_content = StorageFactory.create_test_content()

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                content=test_content,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.get(test_cid)

            assert result == test_content
            mock_client.post.assert_called_once()
            args, kwargs = mock_client.post.call_args
            assert args[0] == "http://localhost:5001/api/v0/cat"
            assert kwargs["params"] == {"arg": test_cid}

    @pytest.mark.asyncio
    async def test_get_json_content(self, ipfs_client):
        """测试获取JSON内容"""
        test_cid = "QmTestJSON123"
        test_json = StorageFactory.create_test_json()

        with patch.object(ipfs_client, 'get', return_value=json.dumps(test_json).encode()):
            result = await ipfs_client.get_json(test_cid)
            assert result == test_json

    @pytest.mark.asyncio
    async def test_get_invalid_json(self, ipfs_client):
        """测试获取无效JSON内容"""
        test_cid = "QmInvalidJSON"

        with patch.object(ipfs_client, 'get', return_value=b"not valid json"):
            with pytest.raises(StorageError, match="not valid JSON"):
                await ipfs_client.get_json(test_cid)

    @pytest.mark.asyncio
    async def test_pin_content(self, ipfs_client):
        """测试Pin内容"""
        test_cid = "QmTestToPin"
        mock_response = {"Pins": [test_cid]}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.pin(test_cid)

            assert result == mock_response
            args, kwargs = mock_client.post.call_args
            assert args[0] == "http://localhost:5001/api/v0/pin/add"
            assert kwargs["params"] == {"arg": test_cid}

    @pytest.mark.asyncio
    async def test_unpin_content(self, ipfs_client):
        """测试Unpin内容"""
        test_cid = "QmTestToUnpin"
        mock_response = {"Pins": [test_cid]}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.unpin(test_cid)

            assert result == mock_response
            args, kwargs = mock_client.post.call_args
            assert args[0] == "http://localhost:5001/api/v0/pin/rm"
            assert kwargs["params"] == {"arg": test_cid}

    @pytest.mark.asyncio
    async def test_list_pins(self, ipfs_client):
        """测试列出所有Pin"""
        mock_response = {"Keys": {"QmPin1": {"Type": "recursive"}, "QmPin2": {"Type": "recursive"}}}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.list_pins()

            assert result == mock_response

    @pytest.mark.asyncio
    async def test_pin_to_pinata(self, ipfs_client):
        """测试Pin到Pinata"""
        test_cid = "QmTestPinata"
        mock_response = {"status": "success", "pinata_cid": test_cid}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await ipfs_client.pin_to_pinata(test_cid, name="test_file")

            assert result == mock_response
            mock_client.post.assert_called_once()
            args, kwargs = mock_client.post.call_args
            assert args[0] == "https://api.pinata.cloud/pinning/pinByHash"
            assert kwargs["json"]["hashToPin"] == test_cid
            assert kwargs["json"]["pinataMetadata"]["name"] == "test_file"

    def test_get_gateway_url(self, ipfs_client):
        """测试获取网关URL"""
        test_cid = "QmTestURL123"
        url = ipfs_client.get_gateway_url(test_cid)
        assert url == "https://ipfs.io/ipfs/QmTestURL123"

    @pytest.mark.asyncio
    async def test_dag_operations(self, ipfs_client):
        """测试DAG操作"""
        test_data = StorageFactory.create_test_json()
        mock_put_response = {"Cid": {"/": "bafyTestDAG123"}}
        mock_get_response = test_data

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.side_effect = [
                AsyncMock(json=lambda: mock_put_response, raise_for_status=lambda: None),
                AsyncMock(json=lambda: mock_get_response, raise_for_status=lambda: None),
            ]
            mock_client_class.return_value.__aenter__.return_value = mock_client

            put_result = await ipfs_client.dag_put(test_data)
            assert put_result == mock_put_response

            get_result = await ipfs_client.dag_get("bafyTestDAG123")
            assert get_result == mock_get_response


class TestIPFSClientResourceManagement:
    """IPFS客户端资源管理测试 - 重点验证资源释放完整性"""

    @pytest.fixture
    def ipfs_client(self):
        """创建IPFS客户端实例"""
        return IPFSClient(
            api_url="http://localhost:5001",
            gateway_url="https://ipfs.io/ipfs/"
        )

    @pytest.mark.asyncio
    async def test_http_client_session_closed_after_add(self, ipfs_client):
        """测试添加内容后HTTP会话正确关闭"""
        test_content = StorageFactory.create_test_content()
        mock_response = {"Hash": "QmTest123", "Size": "1024"}

        mock_session = AsyncMock()
        mock_session.post.return_value = AsyncMock(
            json=lambda: mock_response,
            raise_for_status=lambda: None
        )

        with patch('httpx.AsyncClient', return_value=mock_session) as mock_client:
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            await ipfs_client.add(test_content)

            mock_session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_http_client_session_closed_after_get(self, ipfs_client):
        """测试获取内容后HTTP会话正确关闭"""
        test_cid = "QmTest123"
        test_content = StorageFactory.create_test_content()

        mock_session = AsyncMock()
        mock_session.post.return_value = AsyncMock(
            content=test_content,
            raise_for_status=lambda: None
        )

        with patch('httpx.AsyncClient', return_value=mock_session) as mock_client:
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            await ipfs_client.get(test_cid)

            mock_session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_http_client_session_closed_after_pin(self, ipfs_client):
        """测试Pin操作后HTTP会话正确关闭"""
        test_cid = "QmTest123"
        mock_response = {"Pins": [test_cid]}

        mock_session = AsyncMock()
        mock_session.post.return_value = AsyncMock(
            json=lambda: mock_response,
            raise_for_status=lambda: None
        )

        with patch('httpx.AsyncClient', return_value=mock_session) as mock_client:
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            await ipfs_client.pin(test_cid)

            mock_session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_http_client_session_closed_after_unpin(self, ipfs_client):
        """测试Unpin操作后HTTP会话正确关闭"""
        test_cid = "QmTest123"
        mock_response = {"Pins": [test_cid]}

        mock_session = AsyncMock()
        mock_session.post.return_value = AsyncMock(
            json=lambda: mock_response,
            raise_for_status=lambda: None
        )

        with patch('httpx.AsyncClient', return_value=mock_session) as mock_client:
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            await ipfs_client.unpin(test_cid)

            mock_session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_multiple_operations_independent_sessions(self, ipfs_client):
        """测试多个操作使用独立会话并都正确关闭"""
        test_content = StorageFactory.create_test_content()
        mock_response = {"Hash": "QmTest123", "Size": "1024"}

        sessions = []

        original_client = httpx.AsyncClient

        def create_session(*args, **kwargs):
            session = AsyncMock()
            session.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            session.__aenter__.return_value = session
            session.__aexit__.return_value = AsyncMock()
            sessions.append(session)
            return session

        with patch('httpx.AsyncClient', side_effect=create_session):
            for i in range(5):
                await ipfs_client.add(test_content)

            assert len(sessions) == 5
            for session in sessions:
                session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_session_closed_on_error(self, ipfs_client):
        """测试错误发生时会话仍然正确关闭"""
        test_content = StorageFactory.create_test_content()

        mock_session = AsyncMock()
        mock_session.post.side_effect = httpx.ConnectError("Connection refused")

        with patch('httpx.AsyncClient', return_value=mock_session) as mock_client:
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            with pytest.raises(StorageError):
                await ipfs_client.add(test_content)

            mock_session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_no_connection_leaks(self, ipfs_client):
        """测试没有连接泄漏 - 并发操作后所有连接都关闭"""
        test_content = StorageFactory.create_test_content()
        mock_response = {"Hash": "QmTest123", "Size": "1024"}

        active_sessions = []

        async def track_session():
            session = AsyncMock()
            session.post.return_value = AsyncMock(
                json=lambda: mock_response,
                raise_for_status=lambda: None
            )
            session.__aenter__.return_value = session
            session.__aexit__.side_effect = lambda *args: active_sessions.remove(session)
            active_sessions.append(session)
            return session

        with patch('httpx.AsyncClient', side_effect=lambda *args, **kwargs: track_session()):
            tasks = [ipfs_client.add(test_content) for _ in range(10)]
            await asyncio.gather(*tasks)

            assert len(active_sessions) == 0


class TestArweaveClient:
    """Arweave客户端测试"""

    @pytest.fixture
    def arweave_client(self, mock_settings):
        """创建Arweave客户端实例"""
        return ArweaveClient(
            gateway_url="https://arweave.net/",
            wallet_path=None
        )

    @pytest.mark.asyncio
    async def test_upload_bytes_data(self, arweave_client):
        """测试上传字节数据"""
        test_content = StorageFactory.create_test_content()
        mock_tx_id = "TestArweaveTxId123456789"

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                status_code=200,
                json=lambda: {"id": mock_tx_id},
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.upload_data(test_content)

            assert result == mock_tx_id
            mock_client.post.assert_called_once()

    @pytest.mark.asyncio
    async def test_upload_json_data(self, arweave_client):
        """测试上传JSON数据"""
        test_json = StorageFactory.create_test_json()
        mock_tx_id = "TestArweaveTxJson123"

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                status_code=200,
                json=lambda: {"id": mock_tx_id},
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.upload_data(test_json)

            assert result == mock_tx_id

    @pytest.mark.asyncio
    async def test_upload_with_tags(self, arweave_client):
        """测试带标签上传"""
        test_content = StorageFactory.create_test_content()
        tags = {"AppName": "WalletHub", "Version": "1.0"}
        mock_tx_id = "TestArweaveTxWithTags"

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                status_code=200,
                json=lambda: {"id": mock_tx_id},
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.upload_data(test_content, tags=tags)

            assert result == mock_tx_id

    @pytest.mark.asyncio
    async def test_upload_failure(self, arweave_client):
        """测试上传失败"""
        test_content = StorageFactory.create_test_content()

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                status_code=500,
                text="Internal Server Error"
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            with pytest.raises(StorageError, match="Arweave upload failed"):
                await arweave_client.upload_data(test_content)

    @pytest.mark.asyncio
    async def test_get_data(self, arweave_client):
        """测试获取数据"""
        test_tx_id = "TestArweaveTx123"
        test_content = StorageFactory.create_test_content()

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.get.return_value = AsyncMock(
                content=test_content,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.get_data(test_tx_id)

            assert result == test_content
            mock_client.get.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_json(self, arweave_client):
        """测试获取JSON数据"""
        test_tx_id = "TestArweaveTxJson"
        test_json = StorageFactory.create_test_json()

        with patch.object(arweave_client, 'get_data', return_value=json.dumps(test_json).encode()):
            result = await arweave_client.get_json(test_tx_id)
            assert result == test_json

    @pytest.mark.asyncio
    async def test_get_transaction(self, arweave_client):
        """测试获取交易信息"""
        test_tx_id = "TestArweaveTx123"
        mock_tx_info = {"id": test_tx_id, "owner": "owner_address", "tags": []}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.get.return_value = AsyncMock(
                json=lambda: mock_tx_info,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.get_transaction(test_tx_id)

            assert result == mock_tx_info

    @pytest.mark.asyncio
    async def test_get_status(self, arweave_client):
        """测试获取交易状态"""
        test_tx_id = "TestArweaveTx123"
        mock_status = {"status": "confirmed", "confirmed": True, "block_height": 1234567}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.get.return_value = AsyncMock(
                status_code=200,
                json=lambda: mock_status
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.get_status(test_tx_id)

            assert result == mock_status

    @pytest.mark.asyncio
    async def test_get_price(self, arweave_client):
        """测试获取存储价格"""
        data_size = 1024 * 1024
        mock_price = 1000000000000

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.get.return_value = AsyncMock(
                text=str(mock_price),
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.get_price(data_size)

            assert result == mock_price

    def test_get_gateway_url(self, arweave_client):
        """测试获取网关URL"""
        test_tx_id = "TestArweaveTx123"
        url = arweave_client.get_gateway_url(test_tx_id)
        assert url == "https://arweave.net/TestArweaveTx123"

    @pytest.mark.asyncio
    async def test_graphql_query(self, arweave_client):
        """测试GraphQL查询"""
        test_query = """
        query {
            transactions(first: 10) {
                edges { node { id } }
            }
        }
        """
        mock_result = {"data": {"transactions": {"edges": [{"node": {"id": "tx1"}}]}}}

        with patch('httpx.AsyncClient') as mock_client_class:
            mock_client = AsyncMock()
            mock_client.post.return_value = AsyncMock(
                json=lambda: mock_result,
                raise_for_status=lambda: None
            )
            mock_client_class.return_value.__aenter__.return_value = mock_client

            result = await arweave_client.graphql_query(test_query)

            assert result == mock_result

    def test_load_wallet_not_configured(self, arweave_client):
        """测试钱包未配置时加载钱包"""
        with pytest.raises(StorageError, match="wallet path not configured"):
            arweave_client.load_wallet()


class TestArweaveClientResourceManagement:
    """Arweave客户端资源管理测试"""

    @pytest.fixture
    def arweave_client(self):
        """创建Arweave客户端实例"""
        return ArweaveClient(
            gateway_url="https://arweave.net/",
            wallet_path=None
        )

    @pytest.mark.asyncio
    async def test_http_client_session_closed_after_upload(self, arweave_client):
        """测试上传后HTTP会话正确关闭"""
        test_content = StorageFactory.create_test_content()
        mock_tx_id = "TestTx123"

        mock_session = AsyncMock()
        mock_session.post.return_value = AsyncMock(
            status_code=200,
            json=lambda: {"id": mock_tx_id},
            raise_for_status=lambda: None
        )

        with patch('httpx.AsyncClient', return_value=mock_session):
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            await arweave_client.upload_data(test_content)

            mock_session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_http_client_session_closed_after_get(self, arweave_client):
        """测试获取数据后HTTP会话正确关闭"""
        test_tx_id = "TestTx123"

        mock_session = AsyncMock()
        mock_session.get.return_value = AsyncMock(
            content=b"test data",
            raise_for_status=lambda: None
        )

        with patch('httpx.AsyncClient', return_value=mock_session):
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            await arweave_client.get_data(test_tx_id)

            mock_session.__aexit__.assert_called_once()

    @pytest.mark.asyncio
    async def test_session_closed_on_upload_error(self, arweave_client):
        """测试上传错误时会话仍然正确关闭"""
        test_content = StorageFactory.create_test_content()

        mock_session = AsyncMock()
        mock_session.post.side_effect = httpx.ConnectError("Connection refused")

        with patch('httpx.AsyncClient', return_value=mock_session):
            mock_session.__aenter__.return_value = mock_session
            mock_session.__aexit__.return_value = AsyncMock()

            with pytest.raises(StorageError):
                await arweave_client.upload_data(test_content)

            mock_session.__aexit__.assert_called_once()


class TestStorageManager:
    """存储管理器测试"""

    @pytest.fixture
    def storage_manager(self):
        """创建存储管理器实例"""
        return StorageManager()

    @pytest.mark.asyncio
    async def test_store_to_ipfs(self, storage_manager):
        """测试存储到IPFS"""
        test_data = StorageFactory.create_test_json()
        mock_ipfs_result = {"Hash": "QmStoredCID123", "Size": "256"}

        with patch.object(storage_manager.ipfs, 'add', return_value=mock_ipfs_result):
            result = await storage_manager.store(
                test_data,
                network=StorageNetwork.IPFS,
                pin=True,
                metadata={"source": "test"}
            )

            assert isinstance(result, StoredContent)
            assert result.network == StorageNetwork.IPFS
            assert result.cid == "QmStoredCID123"
            assert result.pinned is True
            assert result.metadata["source"] == "test"
            assert result.content_id is not None

    @pytest.mark.asyncio
    async def test_store_to_arweave(self, storage_manager):
        """测试存储到Arweave"""
        test_data = StorageFactory.create_test_content()
        mock_tx_id = "ArweaveTxId123"

        with patch.object(storage_manager.arweave, 'upload_data', return_value=mock_tx_id):
            result = await storage_manager.store(
                test_data,
                network=StorageNetwork.ARWEAVE,
                pin=False
            )

            assert isinstance(result, StoredContent)
            assert result.network == StorageNetwork.ARWEAVE
            assert result.cid == mock_tx_id
            assert result.pinned is False

    @pytest.mark.asyncio
    async def test_store_unsupported_network(self, storage_manager):
        """测试存储到不支持的网络"""
        test_data = StorageFactory.create_test_content()

        with pytest.raises(StorageError, match="Unsupported storage network"):
            await storage_manager.store(test_data, network="unsupported_network")

    @pytest.mark.asyncio
    async def test_retrieve_from_ipfs(self, storage_manager):
        """测试从IPFS检索"""
        test_cid = "QmRetrieve123"
        test_content = StorageFactory.create_test_content()

        with patch.object(storage_manager.ipfs, 'get', return_value=test_content):
            result = await storage_manager.retrieve(test_cid, StorageNetwork.IPFS)
            assert result == test_content

    @pytest.mark.asyncio
    async def test_retrieve_from_arweave(self, storage_manager):
        """测试从Arweave检索"""
        test_tx_id = "ArweaveRetrieve123"
        test_content = StorageFactory.create_test_content()

        with patch.object(storage_manager.arweave, 'get_data', return_value=test_content):
            result = await storage_manager.retrieve(test_tx_id, StorageNetwork.ARWEAVE)
            assert result == test_content

    @pytest.mark.asyncio
    async def test_retrieve_json(self, storage_manager):
        """测试检索JSON内容"""
        test_cid = "QmJson123"
        test_json = StorageFactory.create_test_json()

        with patch.object(storage_manager.ipfs, 'get_json', return_value=test_json):
            result = await storage_manager.retrieve_json(test_cid)
            assert result == test_json

    @pytest.mark.asyncio
    async def test_pin_content(self, storage_manager):
        """测试Pin内容"""
        test_cid = "QmToPin123"

        with patch.object(storage_manager.ipfs, 'pin', return_value={"Pins": [test_cid]}) as mock_pin:
            await storage_manager.pin(test_cid)
            mock_pin.assert_called_once_with(test_cid)

    @pytest.mark.asyncio
    async def test_unpin_content(self, storage_manager):
        """测试Unpin内容"""
        test_cid = "QmToUnpin123"

        with patch.object(storage_manager.ipfs, 'unpin', return_value={"Pins": [test_cid]}) as mock_unpin:
            await storage_manager.unpin(test_cid)
            mock_unpin.assert_called_once_with(test_cid)

    def test_get_content_from_cache(self, storage_manager):
        """测试从缓存获取内容"""
        test_content = StoredContent(
            content_id="test_content_123",
            network=StorageNetwork.IPFS,
            cid="QmCached123",
            content_hash="0xabc123",
            content_type="application/json",
            size=1024,
            pinned=True,
            url="https://ipfs.io/ipfs/QmCached123"
        )
        storage_manager._content_cache[test_content.content_id] = test_content

        result = storage_manager.get_content(test_content.content_id)
        assert result == test_content

    def test_list_content_filtered(self, storage_manager):
        """测试过滤列出内容"""
        for i in range(6):
            network = StorageNetwork.IPFS if i % 2 == 0 else StorageNetwork.ARWEAVE
            content = StoredContent(
                content_id=f"content_{i}",
                network=network,
                cid=f"CID_{i}",
                content_hash=f"0x{i}",
                content_type="text/plain",
                size=100,
                pinned=False,
                url=f"https://example.com/{i}"
            )
            storage_manager._content_cache[content.content_id] = content

        ipfs_content = storage_manager.list_content(network=StorageNetwork.IPFS)
        arweave_content = storage_manager.list_content(network=StorageNetwork.ARWEAVE)
        all_content = storage_manager.list_content()

        assert len(ipfs_content) == 3
        assert len(arweave_content) == 3
        assert len(all_content) == 6

    def test_get_url(self, storage_manager):
        """测试获取访问URL"""
        ipfs_cid = "QmURL123"
        arweave_tx = "ArweaveURL123"

        ipfs_url = storage_manager.get_url(ipfs_cid, StorageNetwork.IPFS)
        arweave_url = storage_manager.get_url(arweave_tx, StorageNetwork.ARWEAVE)

        assert "ipfs" in ipfs_url.lower()
        assert ipfs_cid in ipfs_url
        assert arweave_tx in arweave_url


class TestStorageManagerResourceManagement:
    """存储管理器资源管理测试"""

    @pytest.fixture
    def storage_manager(self):
        """创建存储管理器实例"""
        return StorageManager()

    @pytest.mark.asyncio
    async def test_lazy_client_initialization(self, storage_manager):
        """测试客户端懒加载初始化"""
        assert storage_manager._ipfs_client is None
        assert storage_manager._arweave_client is None

        ipfs_client = storage_manager.ipfs
        assert storage_manager._ipfs_client is not None
        assert ipfs_client is storage_manager._ipfs_client

        arweave_client = storage_manager.arweave
        assert storage_manager._arweave_client is not None
        assert arweave_client is storage_manager._arweave_client

    @pytest.mark.asyncio
    async def test_client_reused_for_multiple_operations(self, storage_manager):
        """测试多次操作复用同一客户端实例"""
        mock_ipfs_result = {"Hash": "QmTest123", "Size": "1024"}

        with patch.object(storage_manager.ipfs, 'add', return_value=mock_ipfs_result):
            for _ in range(5):
                await storage_manager.store(StorageFactory.create_test_content())

            client1 = storage_manager.ipfs
            client2 = storage_manager.ipfs
            assert client1 is client2

    @pytest.mark.asyncio
    async def test_concurrent_storage_operations(self, storage_manager):
        """测试并发存储操作的资源管理"""
        mock_ipfs_result = {"Hash": "QmConcurrent123", "Size": "1024"}

        with patch.object(storage_manager.ipfs, 'add', return_value=mock_ipfs_result):
            tasks = [
                storage_manager.store(StorageFactory.create_test_content())
                for _ in range(10)
            ]
            results = await asyncio.gather(*tasks)

            assert len(results) == 10
            for result in results:
                assert isinstance(result, StoredContent)
                assert result.cid == "QmConcurrent123"

    @pytest.mark.asyncio
    async def test_mixed_network_operations(self, storage_manager):
        """测试混合网络操作的资源管理"""
        mock_ipfs_result = {"Hash": "QmMixed123", "Size": "1024"}
        mock_arweave_result = "ArweaveMixed123"

        with patch.object(storage_manager.ipfs, 'add', return_value=mock_ipfs_result), \
             patch.object(storage_manager.arweave, 'upload_data', return_value=mock_arweave_result):

            tasks = []
            for i in range(6):
                network = StorageNetwork.IPFS if i % 2 == 0 else StorageNetwork.ARWEAVE
                tasks.append(storage_manager.store(
                    StorageFactory.create_test_content(),
                    network=network
                ))

            results = await asyncio.gather(*tasks)
            assert len(results) == 6

            ipfs_results = [r for r in results if r.network == StorageNetwork.IPFS]
            arweave_results = [r for r in results if r.network == StorageNetwork.ARWEAVE]
            assert len(ipfs_results) == 3
            assert len(arweave_results) == 3
