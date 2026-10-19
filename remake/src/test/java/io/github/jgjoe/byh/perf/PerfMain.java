package io.github.jgjoe.byh.perf;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;

/**
 * Command-line entry point for the perf measurement database (RQ-F-011).
 *
 * <p>Connection details come only from the environment ({@code PERF_DB_URL},
 * {@code PERF_DB_USERNAME}, {@code PERF_DB_PASSWORD}) - nothing is hardcoded.</p>
 *
 * <pre>
 *   load [--seed N]   migrate the target schema, refuse a non-empty ORDERS, load full(seed)
 *   checksum          print row counts and content hashes as key=value lines
 *   measure [--run-id ID] [--warmup N] [--runs N] [--out DIR]
 *                     measure every index scenario and write raw/summary/plans/environment
 * </pre>
 */
public final class PerfMain {

    private static final String URL_ENV = "PERF_DB_URL";
    private static final String USERNAME_ENV = "PERF_DB_USERNAME";
    private static final String PASSWORD_ENV = "PERF_DB_PASSWORD";

    private static final long DEFAULT_SEED = 20261002L;

    private PerfMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
            return;
        }
        switch (args[0]) {
            case "load" -> load(args);
            case "checksum" -> checksum();
            case "measure" -> measure(args);
            case "v3-orders" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("v3-orders requires a fresh run id");
                }
                try (Connection connection = DriverManager.getConnection(requiredEnvironment(URL_ENV),
                        requiredEnvironment(USERNAME_ENV), requiredEnvironment(PASSWORD_ENV))) {
                    V3OrderProbe.run(connection, args[1]);
                }
            }
            default -> {
                System.err.println("Unknown command: " + args[0]);
                usage();
                System.exit(2);
            }
        }
    }

    /** Runs Flyway on the target schema, refuses a non-empty ORDERS, loads GeneratorConfig.full(seed). */
    private static void load(String[] args) throws SQLException {
        long seed = DEFAULT_SEED;
        for (int i = 1; i < args.length; i++) {
            if ("--seed".equals(args[i]) && i + 1 < args.length) {
                seed = Long.parseLong(args[++i]);
            } else {
                System.err.println("Usage: load [--seed N]");
                System.exit(2);
            }
        }
        String url = requiredEnvironment(URL_ENV);
        String username = requiredEnvironment(USERNAME_ENV);
        String password = requiredEnvironment(PASSWORD_ENV);

        System.out.println("Migrating " + username + " with classpath:db/migration up to V"
                + PerfDataLoader.BASELINE_SCHEMA_VERSION + " (R1 schema) ...");
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .target(PerfDataLoader.BASELINE_SCHEMA_VERSION)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            long existingOrders = countOrders(connection);
            if (existingOrders > 0) {
                System.err.println("Refusing to load: ORDERS already contains " + existingOrders
                        + " rows. Reset the measurement DB first:"
                        + " docker compose -f perf/compose.yaml down -v");
                System.exit(1);
                return;
            }
            GeneratorConfig config = GeneratorConfig.full(seed);
            System.out.println("Generating and loading " + config.memberCount() + " members, "
                    + config.productCount() + " products, " + config.orderCount()
                    + " orders (seed " + seed + ") ...");
            LoadResult result = PerfDataLoader.load(connection, config);
            System.out.println("Loaded members=" + result.members()
                    + " products=" + result.products()
                    + " orders=" + result.orders()
                    + " orderItems=" + result.orderItems());
            System.out.println("elapsed=" + result.elapsed());
        }
    }

    private static void checksum() throws SQLException {
        String url = requiredEnvironment(URL_ENV);
        String username = requiredEnvironment(USERNAME_ENV);
        String password = requiredEnvironment(PASSWORD_ENV);
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            PerfChecksum checksum = PerfChecksum.compute(connection);
            System.out.println("members=" + checksum.members());
            System.out.println("products=" + checksum.products());
            System.out.println("orders=" + checksum.orders());
            System.out.println("orderItems=" + checksum.orderItems());
            System.out.println("memberHash=" + checksum.memberHash());
            System.out.println("productHash=" + checksum.productHash());
            System.out.println("orderHash=" + checksum.orderHash());
            System.out.println("itemHash=" + checksum.itemHash());
        }
    }

    /**
     * Measures every index scenario against the loaded data and writes the result files. Defaults:
     * run id = local time {@code yyyyMMdd-HHmmss}, warmup 5, runs 30, out {@code perf/results/<run id>},
     * config {@code GeneratorConfig.full(20261002)}. Leaves only PK indexes behind.
     */
    private static void measure(String[] args) throws Exception {
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        int warmup = 5;
        int runs = 30;
        Path outDir = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--run-id" -> runId = optionValue(args, ++i, "--run-id");
                case "--warmup" -> warmup = Integer.parseInt(optionValue(args, ++i, "--warmup"));
                case "--runs" -> runs = Integer.parseInt(optionValue(args, ++i, "--runs"));
                case "--out" -> outDir = Path.of(optionValue(args, ++i, "--out"));
                default -> {
                    System.err.println("Unknown measure option: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        if (outDir == null) {
            outDir = Path.of("perf", "results", runId);
        }
        String url = requiredEnvironment(URL_ENV);
        String username = requiredEnvironment(USERNAME_ENV);
        String password = requiredEnvironment(PASSWORD_ENV);
        MeasureOptions options = new MeasureOptions(GeneratorConfig.full(DEFAULT_SEED), warmup, runs, 100,
                List.of(Scenario.values()), outDir, runId);
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            PerfMeasure.run(connection, options);
        }
        System.out.println("output_dir=" + outDir.toAbsolutePath());
    }

    private static String optionValue(String[] args, int index, String option) {
        if (index >= args.length) {
            System.err.println(option + " needs a value");
            usage();
            System.exit(2);
        }
        return args[index];
    }

    private static long countOrders(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT COUNT(*) FROM ORDERS")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            List<String> missing = new ArrayList<>();
            for (String candidate : List.of(URL_ENV, USERNAME_ENV, PASSWORD_ENV)) {
                String candidateValue = System.getenv(candidate);
                if (candidateValue == null || candidateValue.isBlank()) {
                    missing.add(candidate);
                }
            }
            System.err.println("Missing environment variable(s): " + String.join(", ", missing)
                    + ". Set PERF_DB_URL, PERF_DB_USERNAME and PERF_DB_PASSWORD.");
            System.exit(2);
        }
        return value;
    }

    private static void usage() {
        System.err.println("Usage: PerfMain <load [--seed N] | checksum"
                + " | measure [--run-id ID] [--warmup N] [--runs N] [--out DIR]"
                + " | v3-orders RUN_ID>");
    }
}
