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
        List<String> keyFields = new ArrayList<>();
        keyFields.add(connector.getKeySchema().getHashKeyName());
        connector.getKeySchema().getRangeKeyName().ifPresent(keyFields::add);

        // Ensure primary key fields are always included
        Set<String> requiredFieldNames = new HashSet<>(keyFields);
        for (StructField field : requiredSchema.fields()) {
            requiredFieldNames.add(field.name());
        }

        // Filter schema based on required fields
        List<StructField> newFields = Arrays.stream(currentSchema.fields())
                .filter(field -> requiredFieldNames.contains(field.name()))
                .collect(Collectors.toList());

        currentSchema = new StructType(newFields.toArray(new StructField[0]));
    }

    @Override
    public Filter[] pushFilters(Filter[] filters) {
        if (connector.isFilterPushdownEnabled()) {
            FilterPushdown.Tuple<List<Filter>, List<Filter>> filterPartition = FilterPushdown.acceptFilters(filters);
            acceptedFilters = filterPartition.getFirst().toArray(new Filter[0]);
            return filterPartition.getSecond().toArray(new Filter[0]); // Return filters that must be evaluated after scanning.
        } else {
            return filters; // No pushdown support, return all filters for post-scan evaluation.
        }
    }

    @Override
    public Filter[] pushedFilters() {
        return acceptedFilters;
    }
}

