package dynamodb;

import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.read.SupportsPushDownFilters;
import org.apache.spark.sql.connector.read.SupportsPushDownRequiredColumns;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.util.*;
import java.util.stream.Collectors;

public class DynamoScanBuilder implements ScanBuilder, SupportsPushDownRequiredColumns, SupportsPushDownFilters {

    private final Connector connector;
    private StructType currentSchema;
    private Filter[] acceptedFilters = new Filter[0];

    public DynamoScanBuilder(Connector connector, StructType schema) {
        this.connector = connector;
        this.currentSchema = schema;
    }

    @Override
    public Scan build() {
        return new DynamoBatchReader(connector, pushedFilters(), currentSchema);
    }

    @Override
    public void pruneColumns(StructType requiredSchema) {
        // Always keep hash/range key columns so we can still produce the
        // KeyGroupedPartitioning report and optionally pushdown key filters.
        Set<String> requiredFieldNames = new LinkedHashSet<>();
        requiredFieldNames.add(connector.getKeySchema().getHashKeyName());
        Optional.ofNullable(connector.getKeySchema().getRangeKeyName())
                .ifPresent(requiredFieldNames::add);

        for (StructField field : requiredSchema.fields()) {
            requiredFieldNames.add(field.name());
        }

        List<StructField> newFields = Arrays.stream(currentSchema.fields())
                .filter(field -> requiredFieldNames.contains(field.name()))
                .collect(Collectors.toList());

        if (!newFields.isEmpty()) {
            currentSchema = new StructType(newFields.toArray(new StructField[0]));
        }
    }

    @Override
    public Filter[] pushFilters(Filter[] filters) {
        if (filters == null) {
            acceptedFilters = new Filter[0];
            return new Filter[0];
        }
        if (!connector.isFilterPushdownEnabled()) {
            acceptedFilters = new Filter[0];
            return filters;
        }

        FilterPushdown.Tuple<List<Filter>, List<Filter>> partition = FilterPushdown.acceptFilters(filters);
        List<Filter> pushed = partition.getFirst();
        List<Filter> postScan = new ArrayList<>(partition.getSecond());

        // When the connector is about to run a Query, the hash-key equality and
        // an optional range-key predicate will be evaluated server-side via the
        // KeyConditionExpression — everything else via FilterExpression. Spark
        // still has to be told which filters it can skip post-scan, which is
        // exactly `pushed`.
        acceptedFilters = pushed.toArray(new Filter[0]);
        return postScan.toArray(new Filter[0]);
    }

    @Override
    public Filter[] pushedFilters() {
        return acceptedFilters == null ? new Filter[0] : acceptedFilters;
    }
}
