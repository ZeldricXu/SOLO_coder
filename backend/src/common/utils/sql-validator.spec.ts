import { SqlValidator } from './sql-validator';

describe('SqlValidator', () => {
  describe('危险语句拦截', () => {
    it('应该拦截 DROP TABLE', () => {
      const result = SqlValidator.validate('DROP TABLE users');
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/DROP\s+TABLE/i);
    });

    it('应该拦截小写的 drop table', () => {
      const result = SqlValidator.validate('drop table users');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 DROP TABLE IF EXISTS', () => {
      const result = SqlValidator.validate('DROP TABLE IF EXISTS orders');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 DELETE FROM', () => {
      const result = SqlValidator.validate('DELETE FROM orders WHERE 1=1');
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/DELETE\s+FROM/i);
    });

    it('应该拦截 delete from 小写', () => {
      const result = SqlValidator.validate('delete from users where id = 1');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 INSERT INTO', () => {
      const result = SqlValidator.validate("INSERT INTO users (id, name) VALUES (1, 'test')");
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/INSERT\s+INTO/i);
    });

    it('应该拦截 insert into 小写', () => {
      const result = SqlValidator.validate("insert into orders select * from other");
      expect(result.safe).toBe(false);
    });

    it('应该拦截 UPDATE SET', () => {
      const result = SqlValidator.validate("UPDATE users SET name = 'hacked'");
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/UPDATE/i);
    });

    it('应该拦截 update set 小写', () => {
      const result = SqlValidator.validate("update users set password = 'x' where id = 1");
      expect(result.safe).toBe(false);
    });

    it('应该拦截 ALTER TABLE', () => {
      const result = SqlValidator.validate('ALTER TABLE users ADD COLUMN password TEXT');
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/ALTER\s+TABLE/i);
    });

    it('应该拦截 alter table 小写', () => {
      const result = SqlValidator.validate('alter table orders drop column old_col');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 TRUNCATE TABLE', () => {
      const result = SqlValidator.validate('TRUNCATE TABLE orders');
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/TRUNCATE/i);
    });

    it('应该拦截 truncate 小写', () => {
      const result = SqlValidator.validate('truncate table users');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 CREATE TABLE', () => {
      const result = SqlValidator.validate('CREATE TABLE fake_table (id INT)');
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/CREATE/i);
    });

    it('应该拦截 CREATE INDEX', () => {
      const result = SqlValidator.validate('CREATE INDEX idx_user_name ON users(name)');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 CREATE VIEW', () => {
      const result = SqlValidator.validate('CREATE VIEW fake_view AS SELECT * FROM users');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 CREATE DATABASE', () => {
      const result = SqlValidator.validate('CREATE DATABASE hacked_db');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 CREATE SCHEMA', () => {
      const result = SqlValidator.validate('CREATE SCHEMA new_schema');
      expect(result.safe).toBe(false);
    });

    it('应该拦截 GRANT', () => {
      const result = SqlValidator.validate("GRANT ALL PRIVILEGES ON *.* TO 'hacker'@'%'");
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/GRANT/i);
    });

    it('应该拦截 REVOKE', () => {
      const result = SqlValidator.validate("REVOKE SELECT ON users FROM 'analyst'@'%'");
      expect(result.safe).toBe(false);
      expect(result.reason).toMatch(/REVOKE/i);
    });
  });

  describe('合法复杂查询放行', () => {
    it('应该放行简单 SELECT 查询', () => {
      const result = SqlValidator.validate('SELECT * FROM orders');
      expect(result.safe).toBe(true);
    });

    it('应该放行带 WHERE 的 SELECT', () => {
      const result = SqlValidator.validate("SELECT * FROM orders WHERE status = 'completed' AND amount > 100");
      expect(result.safe).toBe(true);
    });

    it('应该放行多层 CTE 嵌套 WITH 查询', () => {
      const sql = `
        WITH cte1 AS (
          SELECT user_id, COUNT(*) as order_count
          FROM orders
          WHERE created_at >= '2024-01-01'
          GROUP BY user_id
        ),
        cte2 AS (
          SELECT u.id, u.name, c.order_count
          FROM users u
          JOIN cte1 c ON u.id = c.user_id
          WHERE c.order_count > 5
        )
        SELECT * FROM cte2 ORDER BY order_count DESC
      `;
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放行窗口函数查询', () => {
      const sql = `
        SELECT
          id,
          category,
          amount,
          ROW_NUMBER() OVER (PARTITION BY category ORDER BY amount DESC) AS rn,
          SUM(amount) OVER (PARTITION BY category) AS category_total
        FROM orders
      `;
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放带子查询的 SELECT', () => {
      const sql = `
        SELECT * FROM (
          SELECT category, SUM(amount) AS total_amount
          FROM orders
          WHERE amount > 100
          GROUP BY category
        ) AS subq
        WHERE total_amount > 10000
        ORDER BY total_amount DESC
      `;
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放行复杂 JOIN 查询', () => {
      const sql = `
        SELECT o.id, o.amount, u.name, p.title, c.category_name
        FROM orders o
        JOIN users u ON o.user_id = u.id
        LEFT JOIN products p ON o.product_id = p.id
        LEFT JOIN categories c ON p.category_id = c.id
        WHERE o.status = 'completed'
        ORDER BY o.created_at DESC
      `;
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放行 UNION 查询', () => {
      const sql = `
        SELECT id, name, 'user' AS type FROM users
        UNION ALL
        SELECT id, name, 'admin' AS type FROM admins
        ORDER BY name
      `;
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放行带聚合函数的查询', () => {
      const sql = `
        SELECT
          DATE(created_at) AS date,
          COUNT(*) AS order_count,
          SUM(amount) AS total_amount,
          AVG(amount) AS avg_amount
        FROM orders
        WHERE created_at >= '2024-01-01'
        GROUP BY DATE(created_at)
        ORDER BY date
      `;
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放行行内注释的 SELECT（注释中含危险词不应误报）', () => {
      const sql = 'SELECT * FROM orders -- DROP TABLE should not be blocked';
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放行带 /* */ 块注释的 SELECT（注释中含危险词不应误报）', () => {
      const sql = 'SELECT /* drop table please ignore */ * FROM orders';
      const result = SqlValidator.validate(sql);
      expect(result.safe).toBe(true);
    });

    it('应该放行 SELECT INTO 变量（非 INSERT INTO 表）', () => {
      const result = SqlValidator.validate('SELECT COUNT(*) INTO @total FROM orders');
      expect(result.safe).toBe(true);
    });
  });

  describe('isSelectOnly', () => {
    it('应该识别 SELECT 开头的查询', () => {
      expect(SqlValidator.isSelectOnly('SELECT * FROM orders')).toBe(true);
    });

    it('应该识别 WITH 开头的 CTE 查询', () => {
      expect(SqlValidator.isSelectOnly('WITH cte AS (SELECT 1) SELECT * FROM cte')).toBe(true);
    });

    it('应该识别大写 SELECT', () => {
      expect(SqlValidator.isSelectOnly('SELECT 1')).toBe(true);
    });

    it('应该识别小写 select（不区分大小写）', () => {
      expect(SqlValidator.isSelectOnly('select 1')).toBe(true);
    });

    it('应该返回 false 对于非 SELECT/WITH 开头', () => {
      expect(SqlValidator.isSelectOnly('INSERT INTO table VALUES (1)')).toBe(false);
      expect(SqlValidator.isSelectOnly('UPDATE table SET col = 1')).toBe(false);
      expect(SqlValidator.isSelectOnly('DELETE FROM table')).toBe(false);
    });
  });

  describe('边界情况', () => {
    it('应该处理前后空白', () => {
      const result = SqlValidator.validate('   SELECT * FROM users   ');
      expect(result.safe).toBe(true);
    });

    it('空字符串应该安全', () => {
      const result = SqlValidator.validate('');
      expect(result.safe).toBe(true);
    });

    it('只有 SELECT 关键字的安全查询', () => {
      const result = SqlValidator.validate('SELECT 1');
      expect(result.safe).toBe(true);
    });

    it('混合大小写的危险语句也应该被拦截', () => {
      const result = SqlValidator.validate('DrOp TaBlE users');
      expect(result.safe).toBe(false);
    });

    it('子查询中不应误包含危险词（CREATE 作为列别名）', () => {
      const result = SqlValidator.validate("SELECT id, 'created' AS create_status FROM users");
      expect(result.safe).toBe(true);
    });
  });
});
