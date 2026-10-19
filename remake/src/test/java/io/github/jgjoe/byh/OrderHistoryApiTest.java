package io.github.jgjoe.byh;

import com.jayway.jsonpath.ReadContext;
import io.github.jgjoe.byh.support.IntegrationTestBase;
import io.github.jgjoe.byh.support.Responses;
import io.github.jgjoe.byh.support.StatementCounter;
import io.github.jgjoe.byh.support.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** TC-208 (order history, RQ-F-009). */
class OrderHistoryApiTest extends IntegrationTestBase {

    private static final int ORDERS_ALICE = 3;
    private static final int ORDERS_BOB = 10;

    @Test
    void tc208_orderHistoryNewestFirstWithLinesAndPaging() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        insertProduct("PA", "상품A", 2000, 1000, "제조사A");
        insertProduct("PB", "상품B", 3000, 2500, "제조사B");

        List<Long> expectedNewestFirst = new ArrayList<>();
        for (int day = 1; day <= 12; day++) {
            boolean twoLines = day % 2 == 0;
            long orderId = insertOrder("alice", String.format("2026-02-%02d 10:00:00", day), "2026-03-01",
                    twoLines ? 6000 : 1000, "받는사람" + day);
            insertOrderItem(orderId, "PA", 1, 1000);
            if (twoLines) {
                insertOrderItem(orderId, "PB", 2, 2500);
            }
            expectedNewestFirst.add(0, orderId);
        }

        MockHttpSession session = login("alice", "pw1234");

        MvcResult firstPage = mockMvc.perform(get("/api/orders")
                .param("page", "1").param("size", "10").session(session)).andReturn();
        assertThat(firstPage.getResponse().getStatus()).isEqualTo(200);
        ReadContext firstJson = Responses.json(firstPage);
        assertThat(Responses.longValue(firstJson, "$.total")).isEqualTo(12);
        assertThat(Responses.longValue(firstJson, "$.page")).isEqualTo(1);
        assertThat(Responses.longValue(firstJson, "$.size")).isEqualTo(10);

        List<Map<String, Object>> items = Responses.objects(firstJson, "$.items");
        assertThat(items).hasSize(10);
        assertThat(orderIds(items)).containsExactlyElementsOf(expectedNewestFirst.subList(0, 10));
        for (Map<String, Object> item : items) {
            assertOrderMatchesDatabase(item);
        }

        MvcResult secondPage = mockMvc.perform(get("/api/orders")
                .param("page", "2").param("size", "10").session(session)).andReturn();
        assertThat(secondPage.getResponse().getStatus()).isEqualTo(200);
        ReadContext secondJson = Responses.json(secondPage);
        assertThat(Responses.longValue(secondJson, "$.total")).isEqualTo(12);
        assertThat(orderIds(Responses.objects(secondJson, "$.items")))
                .containsExactlyElementsOf(expectedNewestFirst.subList(10, 12));
    }

    @Test
    void paginationKeepsTiedOrdersAndCompleteLinesAtEveryBoundary() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        insertMember("bob", "pw1234", "밥");
        insertMember("empty", "pw1234", "없음");
        insertProduct("PA", "상품A", 2000, 1000, "제조사A");
        insertProduct("PB", "상품B", 3000, 2500, "제조사B");
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = insertOrder("alice", "2026-02-01 10:00:00", "2026-03-01", 6000, "받는사람");
            insertOrderItem(id, "PA", 1, 1000);
            insertOrderItem(id, "PB", 2, 2500);
            expected.add(0, id);
        }
        long foreign = insertOrder("bob", "2026-02-02 10:00:00", "2026-03-01", 1000, "다른회원");
        insertOrderItem(foreign, "PA", 1, 1000);
        MockHttpSession session = login("alice", "pw1234");
        for (int size : new int[] {1, 2, 10, 11, 100}) {
            for (int page = 1; page <= (12 + size - 1) / size + 1; page++) {
                MvcResult result = mockMvc.perform(get("/api/orders").param("page", Integer.toString(page))
                        .param("size", Integer.toString(size)).session(session)).andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                ReadContext json = Responses.json(result);
                assertThat(Responses.longValue(json, "$.total")).isEqualTo(12);
                List<Map<String, Object>> items = Responses.objects(json, "$.items");
                int start = Math.min((page - 1) * size, 12);
                assertThat(orderIds(items)).containsExactlyElementsOf(expected.subList(start, Math.min(start + size, 12)));
                for (Map<String, Object> item : items) {
                    assertOrderMatchesDatabase(item);
                }
            }
        }
        MvcResult hugePage = mockMvc.perform(get("/api/orders").param("page", "2147483647")
                .param("size", "100").session(session)).andReturn();
        assertThat(hugePage.getResponse().getStatus()).isEqualTo(200);
        assertThat(Responses.objects(Responses.json(hugePage), "$.items")).isEmpty();
        assertThat(Responses.longValue(Responses.json(hugePage), "$.total")).isEqualTo(12);

        MvcResult empty = mockMvc.perform(get("/api/orders").session(login("empty", "pw1234"))).andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        assertThat(Responses.objects(Responses.json(empty), "$.items")).isEmpty();
        assertThat(Responses.longValue(Responses.json(empty), "$.total")).isZero();
    }

    @Test
    void paginationRejectsInvalidPageAndSize() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        MockHttpSession session = login("alice", "pw1234");
        for (String page : new String[] {"0", "-1", "abc"}) {
            MvcResult result = mockMvc.perform(get("/api/orders").param("page", page).session(session)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(Responses.code(result)).isEqualTo("INVALID_PARAMETER");
        }
        for (String size : new String[] {"0", "-1", "101", "abc"}) {
            MvcResult result = mockMvc.perform(get("/api/orders").param("size", size).session(session)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(Responses.code(result)).isEqualTo("INVALID_PARAMETER");
        }
    }

    @Test
    void tc208_orderHistoryQueryCountDoesNotGrowWithOrders() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        insertMember("bob", "pw1234", "밥");
        insertProduct("P1", "상품1", 2000, 1000, "제조사A");

        for (int i = 1; i <= ORDERS_ALICE + ORDERS_BOB; i++) {
            String userId = i <= ORDERS_ALICE ? "alice" : "bob";
            long orderId = insertOrder(userId, String.format("2026-04-%02d 10:00:00", i), "2026-04-20",
                    1000, "받는사람" + i);
            insertOrderItem(orderId, "P1", 1, 1000);
        }

        MockHttpSession aliceSession = login("alice", "pw1234");
        MockHttpSession bobSession = login("bob", "pw1234");

        // Warm-up: statement preparation and pool setup must not be attributed to the measurement.
        mockMvc.perform(get("/api/orders").param("page", "1").param("size", "10").session(aliceSession)).andReturn();

        StatementCounter.reset();
        MvcResult alicePage = mockMvc.perform(get("/api/orders")
                .param("page", "1").param("size", "10").session(aliceSession)).andReturn();
        int statementsForAlice = StatementCounter.executedStatements();
        assertThat(alicePage.getResponse().getStatus()).isEqualTo(200);
        assertThat(Responses.longValue(Responses.json(alicePage), "$.total")).isEqualTo(ORDERS_ALICE);

        StatementCounter.reset();
        MvcResult bobPage = mockMvc.perform(get("/api/orders")
                .param("page", "1").param("size", "10").session(bobSession)).andReturn();
        int statementsForBob = StatementCounter.executedStatements();
        assertThat(bobPage.getResponse().getStatus()).isEqualTo(200);
        assertThat(Responses.longValue(Responses.json(bobPage), "$.total")).isEqualTo(ORDERS_BOB);

        assertThat(statementsForAlice).as("statements executed for %d orders", ORDERS_ALICE).isPositive();
        assertThat(statementsForBob)
                .as("statements for %d orders (%d) must not grow past %d orders (%d)",
                        ORDERS_BOB, statementsForBob, ORDERS_ALICE, statementsForAlice)
                .isEqualTo(statementsForAlice);
    }

    private static List<Long> orderIds(List<Map<String, Object>> items) {
        return items.stream().map(item -> ((Number) item.get("orderId")).longValue()).toList();
    }

    private void assertOrderMatchesDatabase(Map<String, Object> item) {
        long orderId = ((Number) item.get("orderId")).longValue();
        TestFixtures.OrderRow stored = TestFixtures.order(jdbc, orderId);
        assertThat(((Number) item.get("totalPrice")).intValue())
                .as("totalPrice of order %d", orderId).isEqualTo(stored.totalPrice());
        assertThat(item.get("recipientName")).as("recipientName of order %d", orderId).isEqualTo(stored.recipientName());

        List<ApiLine> fromResponse = Responses.nestedObjects(item, "lines").stream()
                .map(line -> new ApiLine(Objects.toString(line.get("productId")), Objects.toString(line.get("productName")),
                        ((Number) line.get("quantity")).intValue(), ((Number) line.get("price")).intValue()))
                .toList();
        List<ApiLine> fromDatabase = jdbc.query("""
                        SELECT oi.PRODUCT_ID, p.PRODUCT_NAME, oi.QUANTITY, oi.PRICE
                        FROM ORDER_ITEMS oi JOIN PRODUCT p ON p.PRODUCT_ID = oi.PRODUCT_ID
                        WHERE oi.ORDER_ID = ? ORDER BY oi.ITEM_ID
                        """,
                (rs, rowNum) -> new ApiLine(rs.getString("PRODUCT_ID"), rs.getString("PRODUCT_NAME"),
                        rs.getInt("QUANTITY"), rs.getInt("PRICE")),
                orderId);

        assertThat(fromResponse).as("lines of order %d", orderId).containsExactlyInAnyOrderElementsOf(fromDatabase);
    }

    private record ApiLine(String productId, String productName, int quantity, int price) {
    }
}
