package dynamodb;

import dynamodb.readers.DynamoReaderFactory;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.spark.sql.connector.expressions.Expression;
import org.apache.spark.sql.connector.expressions.Expressions;
import org.apache.spark.sql.connector.read.*;
import org.apache.spark.sql.connector.read.partitioning.KeyGroupedPartitioning;
import org.apache.spark.sql.connector.read.partitioning.Partitioning;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class DynamoBatchReader implements Scan, Batch, SupportsReportPartitioning {

    private final Connector connector;
    private final Filter[] filters;
    private final StructType schema;

    public DynamoBatchReader(Connector connector, Filter[] filters, StructType schema) {
        this.connector = connector;
        this.filters = filters;
        this.schema = schema;
    }

    @Override
    public StructType readSchema() {
        return schema;
    }

    @Override
    public Batch toBatch() {
        return this;
    }

    @Override
    public InputPartition[] planInputPartitions() {
        List<String> requiredColumns = new ArrayList<>();
        for (StructField field : schema.fields()) {
            requiredColumns.add(field.name());
        }

        return IntStream.range(0, connector.getTotalSegments())
                .mapToObj(i -> new ScanPartition(i, requiredColumns, filters))
                .toArray(InputPartition[]::new);
    }

    @Override
    public PartitionReaderFactory createReaderFactory() {
        return new DynamoReaderFactory(connector, schema);
    }

    @Override
    public Partitioning outputPartitioning() {
        return new KeyGroupedPartitioning(
                new Expression[] { Expressions.identity(connector.getKeySchema().getHashKeyName()) },
                connector.getTotalSegments()
        );
    }
}

