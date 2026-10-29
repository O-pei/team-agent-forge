package com.agentforge.datasource.security;

import com.agentforge.common.exception.BizException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL 安全校验器（发布门槛）：仅允许单条简单 SELECT。
 *
 * <p>防护点：</p>
 * <ol>
 *   <li>拒绝空 SQL / 超长 SQL（&gt;10000 字符）；</li>
 *   <li>拒绝注释（{@code --}、{@code /*}、{@code *}{@code /}、{@code #}），防注释注入；</li>
 *   <li>JSqlParser 解析后必须恰好 1 条语句，且为 {@link Select} 的 PlainSelect（拒绝 UNION 等集合运算）；</li>
 *   <li>拒绝 DDL/DML/过程调用/文件写出等危险关键字与 {@code SELECT ... INTO}；</li>
 *   <li>拒绝 {@code FOR UPDATE} / {@code LOCK IN SHARE MODE} 加锁查询；</li>
 *   <li>自动强制 LIMIT：无 LIMIT 时追加，有 LIMIT 且超过上限时收紧到 maxRows（≤500）。</li>
 * </ol>
 */
public final class SqlGuard {

    /** 单次查询最大返回行数上限。 */
    public static final int MAX_ROWS = 500;
    /** 未指定 maxRows 时的默认值。 */
    public static final int DEFAULT_MAX_ROWS = 100;
    /** JDBC 查询超时（秒）。 */
    public static final int QUERY_TIMEOUT_SECONDS = 10;
    /** SQL 最大长度。 */
    public static final int MAX_SQL_LENGTH = 10_000;

    private static final Pattern COMMENT = Pattern.compile("(--|/\\*|\\*/|#)");
    private static final Pattern LOCKING = Pattern.compile(
            "(?i)\\bfor\\s+update\\b|\\block\\s+in\\s+share\\s+mode\\b|\\bnowait\\b");
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|merge|call|execute|exec"
                    + "|rename|shutdown|handler|prepare|deallocate|load_file|outfile|dumpfile)\\b");

    private SqlGuard() {
    }

    /**
     * 校验并改写 SQL。
     *
     * @param sql             用户 SQL
     * @param requestedMaxRows 期望最大行数（≤0 用默认值，大于 500 按 500）
     * @return 加好 LIMIT 的安全 SQL 与最终行数上限
     */
    public static GuardedSql validate(String sql, int requestedMaxRows) {
        if (sql == null || sql.isBlank()) {
            throw BizException.badRequest("SQL 不能为空");
        }
        String trimmed = sql.strip();
        if (trimmed.length() > MAX_SQL_LENGTH) {
            throw BizException.badRequest("SQL 过长，最大 " + MAX_SQL_LENGTH + " 字符");
        }
        if (COMMENT.matcher(trimmed).find()) {
            throw BizException.badRequest("SQL 不允许包含注释");
        }
        if (LOCKING.matcher(trimmed).find()) {
            throw BizException.badRequest("禁止执行加锁查询（FOR UPDATE / LOCK IN SHARE MODE）");
        }
        Matcher dangerous = DANGEROUS.matcher(trimmed);
        if (dangerous.find()) {
            throw BizException.badRequest("只允许只读 SELECT 查询，检测到不允许的关键字: "
                    + dangerous.group(1).toUpperCase(Locale.ROOT));
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed);
        } catch (JSQLParserException e) {
            throw BizException.badRequest("SQL 解析失败: " + firstLine(e.getMessage()));
        }
        if (statements.size() != 1) {
            throw BizException.badRequest("只允许单条 SQL 语句（检测到 " + statements.size() + " 条）");
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof Select)) {
            throw BizException.badRequest("只允许 SELECT 查询");
        }
        // SetOperationList（UNION 等）也是 Select 子类，直接 instanceof PlainSelect 判定，避免 getPlainSelect() 强转异常
        if (!(statement instanceof PlainSelect plainSelect)) {
            throw BizException.badRequest("只允许单条简单 SELECT（不支持 UNION / 集合运算）");
        }
        if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
            throw BizException.badRequest("禁止 SELECT ... INTO 写出数据");
        }
        if (plainSelect.getIntoTempTable() != null) {
            throw BizException.badRequest("禁止 SELECT ... INTO 临时表");
        }

        int maxRows = normalizeMaxRows(requestedMaxRows);
        Limit limit = plainSelect.getLimit();
        if (limit == null) {
            Limit created = new Limit();
            created.setRowCount(new LongValue(maxRows));
            plainSelect.setLimit(created);
        } else if (limit.getRowCount() instanceof LongValue rowCount && rowCount.getValue() > maxRows) {
            limit.setRowCount(new LongValue(maxRows));
        }
        String guardedSql = statement.toString().strip();
        if (guardedSql.endsWith(";")) {
            guardedSql = guardedSql.substring(0, guardedSql.length() - 1);
        }
        return new GuardedSql(guardedSql, maxRows);
    }

    /** 行数上限归一化：≤0 → 默认 100，>500 → 500。 */
    public static int normalizeMaxRows(int requestedMaxRows) {
        if (requestedMaxRows <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(requestedMaxRows, MAX_ROWS);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "语法错误";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /**
     * 校验结果。
     *
     * @param sql     已追加/收紧 LIMIT 的安全 SQL
     * @param maxRows 最终行数上限
     */
    public record GuardedSql(String sql, int maxRows) {
    }
}
