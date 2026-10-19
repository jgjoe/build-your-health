package io.github.jgjoe.byh.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;

/** First-page experiments only; never used by the application's mapper. */
enum V3OrderVariant {
    CURRENT, BOUND, LITERAL_OFFSET, LITERAL_SIZE, LITERAL_BOTH, FIRST_ROWS, ROWNUM;

    private static final Pattern LIMIT = Pattern.compile(
            "OFFSET\\s+(?:\\?|0)\\s+ROWS\\s+FETCH\\s+NEXT\\s+(?:\\?|10)\\s+ROWS\\s+ONLY",
            Pattern.CASE_INSENSITIVE);

    BoundSql apply(Configuration configuration, BoundSql original) {
        if (this == CURRENT) {
            return original;
        }
        String sql = original.getSql();
        if (!LIMIT.matcher(sql).find()) {
            throw new IllegalArgumentException("Expected the application's OFFSET/FETCH first-page query");
        }
        boolean offsetBind = this != LITERAL_OFFSET && this != LITERAL_BOTH && this != ROWNUM;
        boolean sizeBind = this != LITERAL_SIZE && this != LITERAL_BOTH;
        if (this == ROWNUM) {
            int begin = sql.indexOf("FROM (") + "FROM (".length();
            int end = sql.indexOf(") o", begin);
            if (begin < "FROM (".length() || end < begin) {
                throw new IllegalArgumentException("Expected parent-order inline view");
            }
            String ordered = LIMIT.matcher(sql.substring(begin, end)).replaceFirst("");
            sql = sql.substring(0, begin) + "SELECT * FROM (" + ordered
                    + ") WHERE ROWNUM <= ? " + sql.substring(end);
        } else {
            String limit = "OFFSET " + (offsetBind ? "?" : "0") + " ROWS FETCH NEXT "
                    + (sizeBind ? "?" : "10") + " ROWS ONLY";
            sql = LIMIT.matcher(sql).replaceFirst(java.util.regex.Matcher.quoteReplacement(limit));
            if (this == FIRST_ROWS) {
                sql = sql.replaceFirst("SELECT\\s+ORDER_ID,", "SELECT /*+ FIRST_ROWS(10) */ ORDER_ID,");
            }
        }
        List<ParameterMapping> bindings = new ArrayList<>();
        bindings.add(original.getParameterMappings().get(0)); // USER_ID, still bound in every variant
        if (offsetBind) {
            bindings.add(new ParameterMapping.Builder(configuration, "offset", Long.class).build());
        }
        if (sizeBind) {
            bindings.add(new ParameterMapping.Builder(configuration, "size", Integer.class).build());
        }
        return new BoundSql(configuration, sql, bindings, original.getParameterObject());
    }
}
