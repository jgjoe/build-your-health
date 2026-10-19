package io.github.jgjoe.byh.perf;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the three order queries of {@code OrderMapper.xml} against every index scenario and records
 * client-side timings, V$SQL deltas and DBMS_XPLAN plans under {@code options.outDir()}.
 *
 * <p>One case is one (scenario, query, tier) combination. Every case executes its own tagged cursor
 * (the tag names run id, scenario, query and tier inside a SQL comment), so bind peeking of one
 * tier is never reused by another. The SQL text and the binds come from {@link MapperSql}, so the
 * measured statement is exactly what the application sends.</p>
 */
public final class PerfMeasure {

    /** Tables whose secondary indexes the scenarios control. */
    private static final String TABLES = "('MEMBER', 'PRODUCT', 'ORDERS', 'ORDER_ITEMS')";

    private static final String NON_PK_INDEXES =
            "SELECT i.INDEX_NAME FROM USER_INDEXES i"
                    + " WHERE i.TABLE_NAME IN " + TABLES
                    + " AND i.INDEX_TYPE <> 'LOB'"
                    + " AND NOT EXISTS (SELECT 1 FROM USER_CONSTRAINTS c"
                    + " WHERE c.INDEX_NAME = i.INDEX_NAME AND c.CONSTRAINT_TYPE IN ('P', 'U'))"
                    + " ORDER BY i.INDEX_NAME";

    private static final String CHILD_CURSORS =
            "SELECT CHILD_NUMBER, EXECUTIONS, PLAN_HASH_VALUE FROM V$SQL WHERE SQL_ID = ?"
                    + " ORDER BY CHILD_NUMBER";

    private static final String TYPICAL_FORMAT = "TYPICAL +PEEKED_BINDS";
    private static final String ALLSTATS_FORMAT = "ALLSTATS LAST +PEEKED_BINDS";

    private static final int Q1_OFFSET = 0;
    private static final int Q1_SIZE = 10;

    /** Q3 measures the mid tier's representative member only. */
    private static final String Q3_TIER = "mid";

    private PerfMeasure() {
    }

    /** Runs every case of every scenario, writes the output files, and leaves only PK indexes. */
    public static List<CaseSummary> run(Connection connection, MeasureOptions options) throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        Path outDir = options.outDir();
        Path plansDir = outDir.resolve("plans");
        Files.createDirectories(plansDir);

        MapperSql mapper = MapperSql.load();
        String q3User = options.config().representative(Q3_TIER);
        long q3OrderId = newestOrderId(connection, q3User);
        writeEnvironment(connection, options, startedAt, q3User, q3OrderId, outDir.resolve("environment.txt"));

        List<CaseSummary> summaries = new ArrayList<>();
        try (BufferedWriter raw = Files.newBufferedWriter(outDir.resolve("raw.csv"), StandardCharsets.UTF_8);
                BufferedWriter summary = Files.newBufferedWriter(outDir.resolve("summary.csv"),
                        StandardCharsets.UTF_8)) {
            raw.write("run_id,scenario,query,tier,run,elapsed_ms\n");
            summary.write("run_id,scenario,query,tier,sql_id,plan_hash_value,allstats_plan_hash_value,"
                    + "child_count,warmup,runs,median_ms,p95_ms,min_ms,max_ms,buffer_gets_per_exec,"
                    + "db_elapsed_ms_per_exec,rows_per_exec,indexes\n");
            try {
                for (Scenario scenario : options.scenarios()) {
                    dropNonPkIndexes(connection);
                    for (String create : scenario.createStatements()) {
                        execute(connection, create);
                    }
                    List<String> indexes = nonPkIndexNames(connection);

                    for (Tier tier : options.config().tiers()) {
                        String user = options.config().representative(tier.name());
                        CaseSummary q1 = measureCase(connection, mapper, options, scenario, MeasuredQuery.Q1,
                                tier.name(), Map.of("userId", user, "offset", Q1_OFFSET, "size", Q1_SIZE),
                                indexes, plansDir, raw);
                        summaries.add(q1);
                        writeSummary(summary, q1);

                        CaseSummary q2 = measureCase(connection, mapper, options, scenario, MeasuredQuery.Q2,
                                tier.name(), Map.of("userId", user), indexes, plansDir, raw);
                        summaries.add(q2);
                        writeSummary(summary, q2);
                    }

                    CaseSummary q3 = measureCase(connection, mapper, options, scenario, MeasuredQuery.Q3,
                            Q3_TIER, Map.of("orderId", q3OrderId, "userId", q3User), indexes, plansDir, raw);
                    summaries.add(q3);
                    writeSummary(summary, q3);
                }
            } finally {
                dropNonPkIndexes(connection);
            }
        }
        return summaries;
    }

    static CaseSummary measureCase(Connection connection, MapperSql mapper, MeasureOptions options,
            Scenario scenario, MeasuredQuery query, String tier, Map<String, Object> params,
            List<String> indexes, Path plansDir, BufferedWriter raw) throws SQLException, IOException {
        String tag = "R2 " + options.runId() + " " + scenario + " " + query + " " + tier;

        try (PreparedStatement statement = mapper.prepare(connection, query, params, tag)) {
            statement.setFetchSize(options.fetchSize());
            for (int execution = 0; execution < options.warmup(); execution++) {
                executeAndDrain(statement);
            }

            String sqlId = findSqlId(connection, tag);
            Snapshot before = snapshot(connection, sqlId);

            double[] elapsedMs = new double[options.runs()];
            for (int run = 0; run < options.runs(); run++) {
                long startedAt = System.nanoTime();
                executeAndDrain(statement);
                elapsedMs[run] = (System.nanoTime() - startedAt) / 1_000_000.0;
            }
            Snapshot after = snapshot(connection, sqlId);

            long executions = after.executions() - before.executions();
            if (executions <= 0) {
                throw new SQLException("V$SQL recorded no execution of sql_id " + sqlId + " (tag '" + tag + "')");
            }

            List<ChildCursor> children = childCursors(connection, sqlId);
            if (children.isEmpty()) {
                throw new SQLException("V$SQL has no child cursors for sql_id " + sqlId);
            }
            List<ChildCursor> byExecutions = new ArrayList<>(children);
            byExecutions.sort(Comparator.comparingLong(ChildCursor::executions).reversed()
                    .thenComparingInt(ChildCursor::childNumber));
            ChildCursor planChild = byExecutions.get(0);

            StringBuilder plans = new StringBuilder();
            for (ChildCursor child : children) {
                plans.append("--- CHILD ").append(child.childNumber()).append(' ').append(TYPICAL_FORMAT)
                        .append(" ---\n");
                plans.append(displayCursor(connection, sqlId, child.childNumber(), TYPICAL_FORMAT));
            }

            Map<Integer, Long> executionsBefore = new LinkedHashMap<>();
            for (ChildCursor child : children) {
                executionsBefore.put(child.childNumber(), child.executions());
            }

            // A cursor parsed under STATISTICS_LEVEL=TYPICAL collects no row source statistics, so
            // the ALLSTATS execution is prepared again from the identical SQL text (same tag, same
            // sql_id): Oracle then hard parses the instrumented child cursor and 'ALLSTATS LAST'
            // reports actual rows.
            int allstatsChild;
            alterStatisticsLevel(connection, "ALL");
            try (PreparedStatement allstatsStatement = mapper.prepare(connection, query, params, tag)) {
                allstatsStatement.setFetchSize(options.fetchSize());
                executeAndDrain(allstatsStatement);
                allstatsChild = childUsedByLastExecution(connection, sqlId, executionsBefore);
            } finally {
                alterStatisticsLevel(connection, "TYPICAL");
            }
            plans.append("--- ALLSTATS LAST ---\n");
            plans.append(displayCursor(connection, sqlId, allstatsChild, ALLSTATS_FORMAT));
            long allstatsPlanHash = planHashValue(connection, sqlId, allstatsChild);

            Files.writeString(plansDir.resolve(scenario + "_" + query + "_" + tier + ".txt"), plans.toString(),
                    StandardCharsets.UTF_8);

            for (int run = 0; run < elapsedMs.length; run++) {
                raw.write(options.runId() + "," + scenario + "," + query + "," + tier + "," + (run + 1) + ","
                        + decimal(elapsedMs[run]) + "\n");
            }
            raw.flush();

            CaseSummary caseSummary = new CaseSummary(options.runId(), scenario, query, tier, sqlId,
                    planChild.planHashValue(), allstatsPlanHash, children.size(), options.warmup(), options.runs(),
                    Stats.median(elapsedMs), Stats.p95(elapsedMs),
                    Arrays.stream(elapsedMs).min().orElseThrow(), Arrays.stream(elapsedMs).max().orElseThrow(),
                    (after.bufferGets() - before.bufferGets()) / (double) executions,
                    (after.elapsedMicros() - before.elapsedMicros()) / 1_000.0 / executions,
                    (after.rowsProcessed() - before.rowsProcessed()) / (double) executions,
                    indexes);
            System.out.println(scenario + " " + query + " " + tier + " medianMs="
                    + decimal(caseSummary.medianMs()) + " bufferGetsPerExec="
                    + decimal(caseSummary.bufferGetsPerExec()));
            return caseSummary;
        }
    }

    static void writeSummary(BufferedWriter summary, CaseSummary caseSummary) throws IOException {
        summary.write(caseSummary.runId() + "," + caseSummary.scenario() + "," + caseSummary.query() + ","
                + caseSummary.tier() + "," + caseSummary.sqlId() + "," + caseSummary.planHashValue() + ","
                + caseSummary.allstatsPlanHashValue() + "," + caseSummary.childCount() + ","
                + caseSummary.warmup() + "," + caseSummary.runs() + "," + decimal(caseSummary.medianMs()) + ","
                + decimal(caseSummary.p95Ms()) + "," + decimal(caseSummary.minMs()) + ","
                + decimal(caseSummary.maxMs()) + "," + decimal(caseSummary.bufferGetsPerExec()) + ","
                + decimal(caseSummary.dbElapsedMsPerExec()) + "," + decimal(caseSummary.rowsPerExec()) + ","
                + String.join(";", caseSummary.indexes()) + "\n");
        summary.flush();
    }

    static void writeEnvironment(Connection connection, MeasureOptions options, OffsetDateTime startedAt,
            String q3User, long q3OrderId, Path file) throws SQLException, IOException {
        StringBuilder environment = new StringBuilder();
        append(environment, "run_id", options.runId());
        append(environment, "started_at", startedAt.toString());
        append(environment, "java.version", System.getProperty("java.version"));
        append(environment, "os.name", System.getProperty("os.name"));
        append(environment, "os.arch", System.getProperty("os.arch"));
        append(environment, "available_processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        append(environment, "jdbc_driver_version", connection.getMetaData().getDriverName() + " "
                + connection.getMetaData().getDriverVersion());
        append(environment, "oracle_version", oracleVersion(connection));
        append(environment, "optimizer_features_enable", parameter(connection, "optimizer_features_enable"));
        append(environment, "statistics_level", parameter(connection, "statistics_level"));
        append(environment, "sga_target", parameter(connection, "sga_target"));
        append(environment, "pga_aggregate_target", parameter(connection, "pga_aggregate_target"));
        append(environment, "db_cache_size", parameter(connection, "db_cache_size"));
        append(environment, "buffer_cache_size", sgaInfo(connection));
        append(environment, "rows.member", Long.toString(rowCount(connection, "MEMBER")));
        append(environment, "rows.product", Long.toString(rowCount(connection, "PRODUCT")));
        append(environment, "rows.orders", Long.toString(rowCount(connection, "ORDERS")));
        append(environment, "rows.order_items", Long.toString(rowCount(connection, "ORDER_ITEMS")));
        append(environment, "seed", Long.toString(options.config().seed()));
        append(environment, "warmup", Integer.toString(options.warmup()));
        append(environment, "runs", Integer.toString(options.runs()));
        append(environment, "fetch_size", Integer.toString(options.fetchSize()));
        List<String> scenarioNames = new ArrayList<>();
        for (Scenario scenario : options.scenarios()) {
            scenarioNames.add(scenario.name());
        }
        append(environment, "scenarios", String.join(";", scenarioNames));
        for (Tier tier : options.config().tiers()) {
            String representative = options.config().representative(tier.name());
            append(environment, "tier." + tier.name() + ".representative", representative);
            append(environment, "tier." + tier.name() + ".orders",
                    Long.toString(orderCount(connection, representative)));
        }
        append(environment, "q3.user", q3User);
        append(environment, "q3.order_id", Long.toString(q3OrderId));
        Files.writeString(file, environment.toString(), StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder environment, String key, String value) {
        environment.append(key).append('=').append(value).append('\n');
    }

    /** Drops every non-PK, non-LOB index of the four measured tables. */
    private static void dropNonPkIndexes(Connection connection) throws SQLException {
        for (String index : nonPkIndexNames(connection)) {
            execute(connection, "DROP INDEX \"" + index + "\"");
        }
    }

    private static List<String> nonPkIndexNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(NON_PK_INDEXES)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** The mid representative's newest order, resolved once for every scenario. */
    static long newestOrderId(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ORDER_ID FROM ORDERS WHERE USER_ID = ?"
                        + " ORDER BY ORDER_DATE DESC, ORDER_ID DESC FETCH FIRST 1 ROWS ONLY")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("No orders for user " + userId);
                }
                return rows.getLong(1);
            }
        }
    }

    private static String findSqlId(Connection connection, String tag) throws SQLException {
        List<String> sqlIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT SQL_ID FROM V$SQL WHERE SQL_TEXT LIKE ?")) {
            statement.setString(1, "/* " + tag + " */%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    sqlIds.add(rows.getString(1));
                }
            }
        }
        if (sqlIds.size() != 1) {
            throw new SQLException("Expected exactly one sql_id for tag '" + tag + "' but found " + sqlIds);
        }
        return sqlIds.get(0);
    }

    private static Snapshot snapshot(Connection connection, String sqlId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SUM(EXECUTIONS), SUM(BUFFER_GETS), SUM(ELAPSED_TIME), SUM(ROWS_PROCESSED)"
                        + " FROM V$SQL WHERE SQL_ID = ?")) {
            statement.setString(1, sqlId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("V$SQL returned no row for sql_id " + sqlId);
                }
                return new Snapshot(required(rows, 1, sqlId, "EXECUTIONS"),
                        required(rows, 2, sqlId, "BUFFER_GETS"),
                        required(rows, 3, sqlId, "ELAPSED_TIME"),
                        required(rows, 4, sqlId, "ROWS_PROCESSED"));
            }
        }
    }

    private static List<ChildCursor> childCursors(Connection connection, String sqlId) throws SQLException {
        List<ChildCursor> children = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CHILD_CURSORS)) {
            statement.setString(1, sqlId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    children.add(new ChildCursor(rows.getInt(1), required(rows, 2, sqlId, "EXECUTIONS"),
                            required(rows, 3, sqlId, "PLAN_HASH_VALUE")));
                }
            }
        }
        return children;
    }

    /** The child whose EXECUTIONS grew by the ALLSTATS execution. */
    private static int childUsedByLastExecution(Connection connection, String sqlId,
            Map<Integer, Long> executionsBefore) throws SQLException {
        int used = -1;
        long most = 0;
        for (ChildCursor child : childCursors(connection, sqlId)) {
            long delta = child.executions() - executionsBefore.getOrDefault(child.childNumber(), 0L);
            if (delta > most) {
                most = delta;
                used = child.childNumber();
            }
        }
        if (used < 0) {
            throw new SQLException("No child cursor of sql_id " + sqlId + " recorded the ALLSTATS execution");
        }
        return used;
    }

    private static long planHashValue(Connection connection, String sqlId, int child) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT PLAN_HASH_VALUE FROM V$SQL WHERE SQL_ID = ? AND CHILD_NUMBER = ?")) {
            statement.setString(1, sqlId);
            statement.setInt(2, child);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("V$SQL has no child " + child + " for sql_id " + sqlId);
                }
                return required(rows, 1, sqlId, "PLAN_HASH_VALUE");
            }
        }
    }

    private static String displayCursor(Connection connection, String sqlId, int child, String format)
            throws SQLException {
        StringBuilder text = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, ?))")) {
            statement.setString(1, sqlId);
            statement.setInt(2, child);
            statement.setString(3, format);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    text.append(rows.getString(1)).append('\n');
                }
            }
        }
        if (text.length() == 0) {
            throw new SQLException("DBMS_XPLAN.DISPLAY_CURSOR(" + sqlId + ", " + child + ", '" + format
                    + "') returned no rows");
        }
        return text.toString();
    }

    private static void executeAndDrain(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                for (int column = 1; column <= columns; column++) {
                    rows.getObject(column);
                }
            }
        }
    }

    private static void alterStatisticsLevel(Connection connection, String level) throws SQLException {
        execute(connection, "ALTER SESSION SET STATISTICS_LEVEL = " + level);
    }

    private static String oracleVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT PRODUCT || ' ' || VERSION_FULL FROM PRODUCT_COMPONENT_VERSION"
                        + " WHERE PRODUCT LIKE 'Oracle%'")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getString(1);
                }
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT BANNER FROM V$VERSION WHERE ROWNUM = 1")) {
            if (rows.next()) {
                return rows.getString(1);
            }
        }
        throw new SQLException("Cannot read the Oracle version from PRODUCT_COMPONENT_VERSION or V$VERSION");
    }

    private static String parameter(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT VALUE FROM V$PARAMETER WHERE NAME = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("V$PARAMETER has no parameter " + name);
                }
                return rows.getString(1);
            }
        }
    }

    private static String sgaInfo(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT BYTES FROM V$SGAINFO WHERE NAME = 'Buffer Cache Size'")) {
            if (!rows.next()) {
                throw new SQLException("V$SGAINFO has no 'Buffer Cache Size' row");
            }
            return Long.toString(rows.getLong(1));
        }
    }

    private static long rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long orderCount(Connection connection, String userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ORDERS WHERE USER_ID = ?")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long required(ResultSet rows, int column, String sqlId, String name) throws SQLException {
        long value = rows.getLong(column);
        if (rows.wasNull()) {
            throw new SQLException("V$SQL " + name + " is NULL for sql_id " + sqlId);
        }
        return value;
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Sums of one sql_id over all of its child cursors, as read from V$SQL. */
    private record Snapshot(long executions, long bufferGets, long elapsedMicros, long rowsProcessed) {
    }

    /** One child cursor of a measured sql_id. */
    private record ChildCursor(int childNumber, long executions, long planHashValue) {
    }
}
