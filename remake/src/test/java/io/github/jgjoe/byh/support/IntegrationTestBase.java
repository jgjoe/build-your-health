package io.github.jgjoe.byh.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.oracle.OracleContainer;

/**
 * Black-box API test base: real HTTP through MockMvc, real Oracle through Testcontainers.
 * Every test starts from an empty database (only the migration-defined schema remains).
 */
@SpringBootTest(properties = "byh.login.cleanup-delay-ms=3600000")
@AutoConfigureMockMvc
@Import(StatementCountConfiguration.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        OracleContainer oracle = OracleTestDatabase.container();
        registry.add("spring.datasource.url", oracle::getJdbcUrl);
        registry.add("spring.datasource.username", oracle::getUsername);
        registry.add("spring.datasource.password", oracle::getPassword);
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DELETE FROM LOGIN_ATTEMPT");
        jdbc.execute("DELETE FROM ORDER_ITEMS");
        jdbc.execute("DELETE FROM ORDERS");
        jdbc.execute("DELETE FROM PRODUCT");
        jdbc.execute("DELETE FROM MEMBER");
        TestFixtures.dropFailTriggerIfExists(jdbc);
    }

    protected void insertMember(String id, String rawPassword, String name) {
        TestFixtures.insertMember(jdbc, id, rawPassword, name);
    }

    protected void insertProduct(String productId, String productName, int regularPrice, int discountPrice,
                                 String manufacturer) {
        TestFixtures.insertProduct(jdbc, productId, productName, regularPrice, discountPrice, manufacturer);
    }

    protected long insertOrder(String userId, String orderTimestamp, String deliveryDate, int totalPrice,
                               String recipientName) {
        return TestFixtures.insertOrder(jdbc, userId, orderTimestamp, deliveryDate, totalPrice, recipientName,
                "서울시 중구 세종대로 1", "04523");
    }

    protected long insertOrderItem(long orderId, String productId, int quantity, int price) {
        return TestFixtures.insertOrderItem(jdbc, orderId, productId, quantity, price);
    }

    protected int countRows(String table) {
        return TestFixtures.countRows(jdbc, table);
    }

    protected MockHttpSession login(String id, String password) throws Exception {
        return TestFixtures.login(mockMvc, id, password);
    }

    protected void createFailTrigger() {
        TestFixtures.createFailTrigger(jdbc);
    }
}
