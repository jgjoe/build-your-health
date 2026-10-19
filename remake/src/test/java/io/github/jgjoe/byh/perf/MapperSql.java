package io.github.jgjoe.byh.perf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;

/**
 * Loads {@code mapper/OrderMapper.xml} through MyBatis once and produces exactly what the
 * application sends: the SQL text comes from {@link MappedStatement#getBoundSql(Object)} with the
 * same parameter values MyBatis uses, and the binds are set by MyBatis'
 * {@link DefaultParameterHandler}. The SQL is never copied.
 */
public final class MapperSql {

    private static final String MAPPER_RESOURCE = "mapper/OrderMapper.xml";

    private final Configuration configuration;
    private final V3OrderVariant variant;

    private MapperSql(Configuration configuration) {
        this(configuration, V3OrderVariant.CURRENT);
    }

    private MapperSql(Configuration configuration, V3OrderVariant variant) {
        this.configuration = configuration;
        this.variant = variant;
    }

    MapperSql withVariant(V3OrderVariant variant) {
        return new MapperSql(configuration, variant);
    }

    /** Parses the application's OrderMapper.xml into a standalone MyBatis configuration. */
    public static MapperSql load() {
        Configuration configuration = new Configuration();
        try (InputStream mapper = MapperSql.class.getClassLoader().getResourceAsStream(MAPPER_RESOURCE)) {
            if (mapper == null) {
                throw new IllegalStateException(MAPPER_RESOURCE + " is not on the classpath");
            }
            new XMLMapperBuilder(mapper, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + MAPPER_RESOURCE, e);
        }
        return new MapperSql(configuration);
    }

    /** The SQL MyBatis would send for this query and these parameter values. */
    public String sql(MeasuredQuery query, Map<String, Object> params) {
        return boundSql(query, params).getSql();
    }

    /** Prepares the SQL with the tag comment in front and binds params via DefaultParameterHandler. */
    public PreparedStatement prepare(Connection connection, MeasuredQuery query, Map<String, Object> params,
                                     String tag) throws SQLException {
        MappedStatement statement = statement(query);
        BoundSql boundSql = boundSql(query, params);
        PreparedStatement prepared = connection.prepareStatement("/* " + tag + " */" + boundSql.getSql());
        try {
            new DefaultParameterHandler(statement, params, boundSql).setParameters(prepared);
        } catch (RuntimeException e) {
            try {
                prepared.close();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return prepared;
    }

    private BoundSql boundSql(MeasuredQuery query, Map<String, Object> params) {
        if (query != MeasuredQuery.Q1 || variant == V3OrderVariant.CURRENT) {
            return statement(query).getBoundSql(params);
        }
        // Render the still-supported generic pagination branch, then bind the actual offset (0).
        // This preserves reproducible V2 comparisons after the app gains a first-page fast path.
        Map<String, Object> renderParams = new HashMap<>(params);
        renderParams.put("offset", 1L);
        BoundSql generic = statement(query).getBoundSql(renderParams);
        BoundSql transformed = variant.apply(configuration, generic);
        return new BoundSql(configuration, transformed.getSql(), transformed.getParameterMappings(), params);
    }

    private MappedStatement statement(MeasuredQuery query) {
        Objects.requireNonNull(query, "query");
        return configuration.getMappedStatement(query.statementId());
    }
}
