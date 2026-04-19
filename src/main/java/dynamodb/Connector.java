package dynamodb;

import org.apache.spark.sql.sources.Filter;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Abstraction over a DynamoDB data source. Implementations decide whether
 * a given set of pushed-down Spark filters can be served with a {@code Query}
 * (efficient, bounded by a hash key) or has to fall back to a parallel
 * {@code Scan}.
 */
public interface Connector {

    /**
     * Read a single partition's worth of items. The implementation transparently
     * chooses {@code Query} when the filters match the key schema (of the table
     * or the configured index), otherwise a parallel {@code Scan} segment.
     *
     * @param segmentNum      scan segment index; ignored when the connector
     *                        decides to issue a Query.
     * @param columns         columns to project; empty means all.
     * @param filters         pushed-down Spark filters.
     * @return an iterator of DynamoDB items that transparently paginates.
     */
    Iterator<Map<String, AttributeValue>> readItems(int segmentNum, List<String> columns, Filter[] filters);

    /**
     * Returns {@code true} when the given filters are sufficient to issue a
     * DynamoDB {@code Query} instead of a {@code Scan} (i.e. they contain an
     * equality on the hash key of the table or configured index).
     */
    boolean canQueryWithFilters(Filter[] filters);

    boolean isFilterPushdownEnabled();

    boolean nonEmpty();

    boolean isEmpty();

    /**
     * @return true when this connector is bound to a secondary index.
     */
    boolean isIndex();

    KeySchema getKeySchema();

    int getTotalSegments();

    double getReadLimit();
}
