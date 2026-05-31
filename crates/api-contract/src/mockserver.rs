use crate::models::{ApiContract, MockServerConfig};
use crate::schema::SchemaParser;
use anyhow::Result;
use rusqlite::{Connection, params};
use uuid::Uuid;
use std::sync::Mutex;

pub struct MockServerManager {
    conn: Mutex<Connection>,
}

impl MockServerManager {
    pub fn new(conn: Connection) -> Self {
        MockServerManager {
            conn: Mutex::new(conn),
        }
    }

    pub fn init_schema(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "CREATE TABLE IF NOT EXISTS mock_servers (
                server_id TEXT PRIMARY KEY,
                port INTEGER NOT NULL,
                contract_id TEXT NOT NULL,
                is_running BOOLEAN NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            )",
            [],
        )?;

        conn.execute(
            "CREATE TABLE IF NOT EXISTS mock_endpoints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                server_id TEXT NOT NULL,
                path TEXT NOT NULL,
                method TEXT NOT NULL,
                status_code INTEGER NOT NULL,
                FOREIGN KEY (server_id) REFERENCES mock_servers(server_id)
            )",
            [],
        )?;

        Ok(())
    }

    pub fn create_server(&self, contract: &ApiContract) -> Result<MockServerConfig> {
        let server_id = Uuid::new_v4();
        let port = 8000 + (server_id.as_u128() % 1000) as u16;
        let endpoints = SchemaParser::extract_endpoints(contract.schema_type, &contract.schema_content)?;

        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO mock_servers (server_id, port, contract_id, is_running, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                server_id.to_string(),
                port as i32,
                contract.id.to_string(),
                false,
                chrono::Utc::now().to_rfc3339()
            ],
        )?;

        for endpoint in &endpoints {
            conn.execute(
                "INSERT INTO mock_endpoints (server_id, path, method, status_code)
                 VALUES (?1, ?2, ?3, ?4)",
                params![
                    server_id.to_string(),
                    endpoint.path,
                    endpoint.method,
                    endpoint.status_code as i32
                ],
            )?;
        }

        Ok(MockServerConfig {
            server_id,
            port,
            endpoints,
            is_running: false,
        })
    }

    pub fn start_server(&self, server_id: Uuid) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE mock_servers SET is_running = 1 WHERE server_id = ?1",
            params![server_id.to_string()],
        )?;
        Ok(())
    }

    pub fn stop_server(&self, server_id: Uuid) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE mock_servers SET is_running = 0 WHERE server_id = ?1",
            params![server_id.to_string()],
        )?;
        Ok(())
    }

    pub fn list_servers(&self) -> Result<Vec<MockServerConfig>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT server_id, port, is_running FROM mock_servers"
        )?;

        let server_iter = stmt.query_map([], |row| {
            let server_id_str: String = row.get(0)?;
            let server_id = Uuid::parse_str(&server_id_str).unwrap_or(Uuid::nil());
            Ok(MockServerConfig {
                server_id,
                port: row.get::<_, i32>(1)? as u16,
                endpoints: Vec::new(),
                is_running: row.get(2)?,
            })
        })?;

        let mut servers = Vec::new();
        for server in server_iter {
            servers.push(server?);
        }

        Ok(servers)
    }
}
