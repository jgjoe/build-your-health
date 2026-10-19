package io.github.jgjoe.byh.perf;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Read-only SQL experiments: reuse R2 cursor measurement, without its index-dropping scenario runner. */
final class V3OrderProbe {
    private V3OrderProbe() {
    }

    static void run(Connection connection, String runId) throws Exception {
        if (!runId.matches("v3-orders-[a-z0-9-]+")) {
            throw new IllegalArgumentException("Use a fresh v3-orders-* run id");
        }
        List<String> indexes = Scenario.S2B.indexNames();
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME IN (?, ?)")) {
            statement.setString(1, indexes.get(0));
            statement.setString(2, indexes.get(1));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getInt(1) != 2) {
                    throw new IllegalStateException("Prepare both S2b indexes in the measurement schema first");
                }
            }
        }
        Path out = Path.of("perf", "results", runId);
        Files.createDirectory(out);
        GeneratorConfig config = GeneratorConfig.full(20261002L);
        MapperSql baseline = MapperSql.load();
        String mid = config.representative("mid");
        long detailId = PerfMeasure.newestOrderId(connection, mid);
        MeasureOptions environment = new MeasureOptions(config, 5, 30, 100, List.of(Scenario.S2B), out, runId);
        PerfMeasure.writeEnvironment(connection, environment, OffsetDateTime.now(), mid, detailId,
                out.resolve("environment.txt"));
        try (BufferedWriter raw = Files.newBufferedWriter(out.resolve("raw.csv"), StandardCharsets.UTF_8);
                BufferedWriter summary = Files.newBufferedWriter(out.resolve("summary.csv"), StandardCharsets.UTF_8);
                BufferedWriter equivalence = Files.newBufferedWriter(out.resolve("equivalence.txt"), StandardCharsets.UTF_8)) {
            raw.write("run_id,scenario,query,tier,run,elapsed_ms\n");
            summary.write("run_id,scenario,query,tier,sql_id,plan_hash_value,allstats_plan_hash_value,"
                    + "child_count,warmup,runs,median_ms,p95_ms,min_ms,max_ms,buffer_gets_per_exec,"
                    + "db_elapsed_ms_per_exec,rows_per_exec,indexes\n");
            for (V3OrderVariant variant : V3OrderVariant.values()) {
                MapperSql mapper = baseline.withVariant(variant);
                Path plans = Files.createDirectory(out.resolve(variant.name()));
                MeasureOptions options = new MeasureOptions(config, 5, 30, 100, List.of(Scenario.S2B),
                        out, runId + "-" + variant);
                for (Tier tier : config.tiers()) {
                    Map<String, Object> params = Map.of("userId", config.representative(tier.name()),
                            "offset", 0L, "size", 10);
                    Page expected = readPage(connection, baseline, params);
                    Page actual = readPage(connection, mapper, params);
                    if (!actual.equals(expected)) {
                        throw new IllegalStateException(variant + "/" + tier.name() + ": different complete result");
                    }
                    equivalence.write(variant + "/" + tier.name() + " equal; orders=" + actual.orders()
                            + "; joined_rows=" + actual.rows().size() + "\n");
                    Files.writeString(plans.resolve(tier.name() + ".sql"), mapper.sql(MeasuredQuery.Q1, params)
                            + "\n-- userId=" + params.get("userId") + ", offset=0, size=10\n", StandardCharsets.UTF_8);
                    CaseSummary result = PerfMeasure.measureCase(connection, mapper, options, Scenario.S2B,
                            MeasuredQuery.Q1, tier.name(), params, indexes, plans, raw);
                    PerfMeasure.writeSummary(summary, result);
                }
            }
            Path controls = Files.createDirectory(out.resolve("CONTROLS"));
            for (Tier tier : config.tiers()) {
                PerfMeasure.writeSummary(summary, PerfMeasure.measureCase(connection, baseline, environment,
                        Scenario.S2B, MeasuredQuery.Q2, tier.name(),
                        Map.of("userId", config.representative(tier.name())), indexes, controls, raw));
            }
            PerfMeasure.writeSummary(summary, PerfMeasure.measureCase(connection, baseline, environment,
                    Scenario.S2B, MeasuredQuery.Q3, "mid", Map.of("userId", mid, "orderId", detailId),
                    indexes, controls, raw));
        }
        System.out.println("output_dir=" + out.toAbsolutePath());
    }

    private static Page readPage(Connection connection, MapperSql mapper, Map<String, Object> params) throws Exception {
        List<String> values = new ArrayList<>();
        LinkedHashSet<Long> orders = new LinkedHashSet<>();
        try (var statement = mapper.prepare(connection, MeasuredQuery.Q1, params, "V3 result equivalence")) {
            statement.setFetchSize(100);
            try (ResultSet rows = statement.executeQuery()) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    orders.add(rows.getLong("ORDER_ID"));
                    StringBuilder value = new StringBuilder();
                    for (int i = 1; i <= columns; i++) {
                        String field = rows.getString(i);
                        value.append(field == null ? -1 : field.length()).append(':').append(field).append('|');
                    }
                    values.add(value.toString());
                }
            }
        }
        values.sort(String::compareTo); // line order within one order is not an API contract
        return new Page(List.copyOf(orders), List.copyOf(values));
    }

    private record Page(List<Long> orders, List<String> rows) {
    }
}
