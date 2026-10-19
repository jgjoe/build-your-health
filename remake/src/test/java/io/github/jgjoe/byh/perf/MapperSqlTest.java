package io.github.jgjoe.byh.perf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MapperSql}: the SQL text and the binds the harness uses must be
 * exactly what the application sends through MyBatis.
 */
class MapperSqlTest {

    private static final long SEED = 20261002L;
    private static final GeneratorConfig CONFIG = GeneratorConfig.small(SEED);

    private static PerfTestSchema schema;
    private static MapperSql mapper;

    @BeforeAll
    static void createSchemaLoadSmallDataAndMapperSql() throws Exception {
        schema = PerfTestSchema.create();
        PerfDataLoader.load(schema.connection(), CONFIG);
        mapper = MapperSql.load();
    }

    @AfterAll
    static void closeSchema() throws Exception {
        if (schema != null) {
            schema.close();
        }
    }

    @Test
    void m1_boundSqlHasNoMyBatisPlaceholdersAndTheExpectedBindCount() {
        String q1 = mapper.sql(MeasuredQuery.Q1, Map.of("userId", "u000001", "offset", 0, "size", 10));
        assertThat(q1).doesNotContain("#{");
        assertThat(placeholders(q1)).isEqualTo(2);
        String nextPage = mapper.sql(MeasuredQuery.Q1, Map.of("userId", "u000001", "offset", 10, "size", 10));
        assertThat(nextPage).doesNotContain("#{");
        assertThat(placeholders(nextPage)).isEqualTo(3);

        String q2 = mapper.sql(MeasuredQuery.Q2, Map.of("userId", "u000001"));
        assertThat(q2).doesNotContain("#{");
        assertThat(placeholders(q2)).isEqualTo(1);

        String q3 = mapper.sql(MeasuredQuery.Q3, Map.of("orderId", 1L, "userId", "u000001"));
        assertThat(q3).doesNotContain("#{");
        assertThat(placeholders(q3)).isEqualTo(2);

        assertThat(MeasuredQuery.Q1.statementId())
                .isEqualTo("io.github.jgjoe.byh.order.OrderMapper.findOrdersByUserId");
        assertThat(MeasuredQuery.Q2.statementId())
                .isEqualTo("io.github.jgjoe.byh.order.OrderMapper.countOrdersByUserId");
        assertThat(MeasuredQuery.Q3.statementId())
                .isEqualTo("io.github.jgjoe.byh.order.OrderMapper.findOrderByIdAndUserId");
    }

    @Test
    void m2_countOrdersByUserIdMatchesAnIndependentCount() throws Exception {
        String userId = CONFIG.representative("heavy");
        long expected = countOrders(schema.connection(), userId);
        assertThat(expected).isPositive();

        try (PreparedStatement statement = mapper.prepare(schema.connection(), MeasuredQuery.Q2,
                Map.of("userId", userId), "R2 m2 Q2 heavy");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as("Q2 returns a row").isTrue();
            assertThat(rows.getLong(1)).as("count of %s", userId).isEqualTo(expected);
            assertThat(rows.next()).as("Q2 returns exactly one row").isFalse();
        }
    }

    @Test
    void m3_findOrdersByUserIdReturnsTheTenNewestOrdersInOrder() throws Exception {
        String userId = CONFIG.representative("mid");
        List<Long> expected = newestOrderIds(schema.connection(), userId);
        assertThat(expected).hasSize(10);

        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        try (PreparedStatement statement = mapper.prepare(schema.connection(), MeasuredQuery.Q1,
                Map.of("userId", userId, "offset", 0, "size", 10), "R2 m3 Q1 mid");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                distinct.add(rows.getLong("ORDER_ID"));
            }
        }
        assertThat(List.copyOf(distinct)).containsExactlyElementsOf(expected);
    }

    @Test
    void everyPaginationExperimentReturnsTheSameFirstPage() throws Exception {
        for (Tier tier : CONFIG.tiers()) {
            String userId = CONFIG.representative(tier.name());
            List<Long> expected = newestOrderIds(schema.connection(), userId);
            for (V3OrderVariant variant : V3OrderVariant.values()) {
                LinkedHashSet<Long> actual = new LinkedHashSet<>();
                try (PreparedStatement statement = mapper.withVariant(variant).prepare(schema.connection(),
                        MeasuredQuery.Q1, Map.of("userId", userId, "offset", 0L, "size", 10),
                        "V3 variants " + tier.name() + " " + variant);
                        ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        actual.add(rows.getLong("ORDER_ID"));
                    }
                }
                assertThat(List.copyOf(actual)).as("%s/%s", tier.name(), variant)
                        .containsExactlyElementsOf(expected);
            }
        }
    }

    @Test
    void m4_theStatementSentToOracleStartsWithTheTag() throws Exception {
        String tag = "R2 m4 Q2 mid";
        String prefix = "/* " + tag + " */";
        try (PreparedStatement statement = mapper.prepare(schema.connection(), MeasuredQuery.Q2,
                Map.of("userId", CONFIG.representative("mid")), tag);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                rows.getLong(1);
            }
        }

        List<String> texts = new ArrayList<>();
        try (PreparedStatement statement = schema.connection().prepareStatement(
                "SELECT SQL_TEXT FROM V$SQL WHERE SQL_TEXT LIKE ?")) {
            statement.setString(1, prefix + "%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    texts.add(rows.getString(1));
                }
            }
        }
        assertThat(texts).as("V$SQL texts starting with '%s'", prefix).isNotEmpty();
        assertThat(texts).allSatisfy(text -> assertThat(text).startsWith(prefix));
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(character -> character == '?').count();
    }

    private static long countOrders(Connection connection, String userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ORDERS WHERE USER_ID = ?")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /** ORDER_IDs of the member's ten newest orders, in that order, from an independent query. */
    private static List<Long> newestOrderIds(Connection connection, String userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ORDER_ID FROM ORDERS WHERE USER_ID = ?"
                        + " ORDER BY ORDER_DATE DESC, ORDER_ID DESC FETCH FIRST 10 ROWS ONLY")) {
            statement.setString(1, userId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
            return ids;
        }
    }
}
