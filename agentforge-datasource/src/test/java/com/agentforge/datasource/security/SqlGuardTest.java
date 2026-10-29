package com.agentforge.datasource.security;

import com.agentforge.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恶意 SQL 表驱动校验（发布门槛）。
 */
class SqlGuardTest {

    static Stream<String> maliciousSql() {
        return Stream.of(
                "DELETE FROM af_user",
                "delete from af_user where id = 1",
                "UPDATE af_user SET password = 'x'",
                "DROP TABLE af_user",
                "TRUNCATE TABLE af_user",
                "INSERT INTO af_user(id) VALUES (1)",
                "ALTER TABLE af_user ADD COLUMN x INT",
                "CREATE TABLE hacker(id INT)",
                "GRANT ALL ON *.* TO 'x'@'%'",
                "REVOKE ALL ON *.* FROM 'x'@'%'",
                "CALL some_procedure()",
                "EXEC xp_cmdshell 'dir'",
                "SELECT 1; DELETE FROM af_user",
                "SELECT * FROM af_user; DROP TABLE af_user",
                "SELECT * FROM af_user -- comment injection",
                "SELECT 1 /* comment */",
                "SELECT * FROM af_user FOR UPDATE",
                "SELECT * FROM af_user LOCK IN SHARE MODE",
                "SELECT * INTO OUTFILE '/tmp/dump.txt' FROM af_user",
                "SELECT * FROM a UNION SELECT * FROM b",
                "SELECT * FROM af_user INTO OUTFILE '/tmp/x'",
                "WITH x AS (SELECT 1) DELETE FROM af_user",
                "",
                "   ");
    }

    @ParameterizedTest
    @MethodSource("maliciousSql")
    @DisplayName("恶意/非只读 SQL 必须被拒绝")
    void rejectsMaliciousSql(String sql) {
        assertThrows(BizException.class, () -> SqlGuard.validate(sql, 100), "应拒绝: " + sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "select * from af_user",
            "SELECT id, name FROM af_user WHERE id = 1",
            "SELECT COUNT(*) FROM af_user",
            "SELECT REPLACE(name, 'a', 'b') FROM af_user",
            "SELECT * FROM af_user WHERE create_time > '2020-01-01' AND deleted_flag = 0",
            "SELECT u.id, r.name FROM af_user u JOIN af_role r ON u.role_id = r.id"
    })
    @DisplayName("合法只读 SELECT 必须通过")
    void acceptsReadOnlySelect(String sql) {
        SqlGuard.GuardedSql guarded = SqlGuard.validate(sql, 100);
        assertTrue(guarded.sql().toLowerCase().contains("limit"), "应自动追加 LIMIT: " + guarded.sql());
        assertEquals(100, guarded.maxRows());
    }

    @Test
    @DisplayName("无 LIMIT 时自动追加 LIMIT maxRows")
    void appendsLimitWhenMissing() {
        SqlGuard.GuardedSql guarded = SqlGuard.validate("SELECT * FROM af_user", 50);
        assertEquals("SELECT * FROM af_user LIMIT 50", guarded.sql());
        assertEquals(50, guarded.maxRows());
    }

    @Test
    @DisplayName("已有过大 LIMIT 时收紧到上限")
    void shrinksTooLargeLimit() {
        SqlGuard.GuardedSql guarded = SqlGuard.validate("SELECT * FROM af_user LIMIT 999999", 100);
        assertEquals("SELECT * FROM af_user LIMIT 100", guarded.sql());
    }

    @Test
    @DisplayName("已符合上限的 LIMIT 保持不变")
    void keepsSmallLimit() {
        SqlGuard.GuardedSql guarded = SqlGuard.validate("SELECT * FROM af_user LIMIT 10", 100);
        assertEquals("SELECT * FROM af_user LIMIT 10", guarded.sql());
    }

    @Test
    @DisplayName("行数上限归一到 500")
    void maxRowsCapped() {
        assertEquals(500, SqlGuard.validate("SELECT 1", 1000).maxRows());
        assertEquals(100, SqlGuard.validate("SELECT 1", 0).maxRows());
        assertEquals(100, SqlGuard.validate("SELECT 1", -5).maxRows());
        assertEquals(100, SqlGuard.DEFAULT_MAX_ROWS);
        assertEquals(500, SqlGuard.MAX_ROWS);
        assertEquals(10, SqlGuard.QUERY_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("多个分号语句只允许一条")
    void rejectsMultipleStatements() {
        assertThrows(BizException.class, () -> SqlGuard.validate("SELECT 1; SELECT 2", 100));
    }

    @Test
    @DisplayName("超长 SQL 拒绝")
    void rejectsTooLongSql() {
        String huge = "SELECT '" + "a".repeat(SqlGuard.MAX_SQL_LENGTH) + "'";
        assertThrows(BizException.class, () -> SqlGuard.validate(huge, 100));
    }

    @Test
    @DisplayName("非法 SQL 语法给出 400 而非 500")
    void rejectsSyntaxError() {
        BizException exception = assertThrows(BizException.class,
                () -> SqlGuard.validate("SELECT FROM WHERE", 100));
        assertEquals(40000, exception.getCode());
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    @DisplayName("LIMIT 参数列表大小写不敏感但保留原文")
    void resultIsList() {
        List<String> one = List.of(SqlGuard.validate("select id from af_user", 5).sql());
        assertEquals(1, one.size());
        assertTrue(one.get(0).toLowerCase().contains("limit 5"));
    }
}
