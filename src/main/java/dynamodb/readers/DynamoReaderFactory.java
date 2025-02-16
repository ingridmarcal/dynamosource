package dynamodb.readers;

import com.google.common.util.concurrent.RateLimiter;
import dynamodb.Connector;
import dynamodb.QueryPartition;
import dynamodb.ScanPartition;
import dynamodb.TypeConversion;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import scala.collection.JavaConverters;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DynamoReaderFactory implements PartitionReaderFactory {

    private final Connector connector;
    private final StructType schema;

    public DynamoReaderFactory(Connector connector, StructType schema) {
        this.connector = connector;
        this.schema = schema;
    }

    @Override
    public PartitionReader<InternalRow> createReader(InputPartition partition) {
        if (connector.isEmpty()) {
            return new EmptyReader();
        }else if (connector.isQuery()){
            return new QueryPartitionReader((QueryPartition) partition);
        } else {
            return new ScanPartitionReader((ScanPartition) partition);
        }
    }

    /** Empty reader for handling empty results */
    private static class EmptyReader implements PartitionReader<InternalRow> {
        @Override
        public boolean next() {
            return false;
        }

        @Override
        public InternalRow get() {
            throw new IllegalStateException("Unable to call get() on empty iterator");
        }

        @Override
        public void close() {
        }
    }

    /** Reader for handling scan partitions */
    private class ScanPartitionReader implements PartitionReader<InternalRow> {

        private final int partitionIndex;
        private final List<String> requiredColumns;
        private final List<org.apache.spark.sql.sources.Filter> filters;

        private final Iterator<Map<String, AttributeValue>> pageIterator;
        private final RateLimiter rateLimiter;
        private Iterator<InternalRow> innerIterator = Collections.emptyIterator();

        private InternalRow currentRow;
        private boolean proceed = false;

        private final Map<String, Function<Map<String, AttributeValue>, Object>> typeConversions;


        public ScanPartitionReader(ScanPartition scanPartition) {
            this.partitionIndex = scanPartition.getPartitionIndex();
            this.requiredColumns = scanPartition.getRequiredColumns();
            this.filters = Arrays.asList(scanPartition.getFilters());


            ScanResponse scanResponse = connector.scan(partitionIndex, requiredColumns, filters);
            this.pageIterator = scanResponse.items().iterator();
            this.rateLimiter = RateLimiter.create(connector.getReadLimit());

            this.typeConversions = Arrays.stream(schema.fields())
                    .collect(Collectors.toMap(StructField::name, field -> TypeConversion.apply(field.name(), field.dataType())));

        }

        @Override
        public boolean next() {
            proceed = true;
            if (innerIterator.hasNext()) {
                return true;
            } else if (pageIterator.hasNext()) {
                nextPage();
                return next();
            }
            return false;
        }

        @Override
        public InternalRow get() {
            if (proceed) {
                currentRow = innerIterator.next();
                proceed = false;
            }
            return currentRow;
        }

        @Override
        public void close() {
        }

        /** Processes the next page of scan results */
        private void nextPage() {
            List<Map<String, AttributeValue>> page = new ArrayList<>();
            while (pageIterator.hasNext()) {
                page.add(pageIterator.next());
            }
            innerIterator = page.stream().map(this::itemToRow).iterator();
        }

        private InternalRow itemToRow(Map<String, AttributeValue> item) {
            if (!requiredColumns.isEmpty()) {
                return InternalRow.fromSeq(
                        JavaConverters.asScalaBuffer(
                                requiredColumns.stream()
                                        .map(columnName -> typeConversions.get(columnName).apply(item))
                                        .collect(Collectors.toList())
                        ).toSeq()
                );
            } else {
                return InternalRow.fromSeq(
                        JavaConverters.asScalaBuffer(
                                item.values().stream()
                                        .map(value -> (Object) value.toString()) // ✅ Explicitly cast to Object
                                        .collect(Collectors.toList())
                        ).toSeq()
                );
            }
        }
    }

    private class QueryPartitionReader implements PartitionReader<InternalRow> {
        private final Iterator<Map<String, AttributeValue>> itemsIterator;
        private final List<String> requiredColumns;
        private final List<org.apache.spark.sql.sources.Filter> filters;
        private final int partitionIndex;
        private final Map<String, Function<Map<String, AttributeValue>, Object>> typeConversions;


        public QueryPartitionReader(QueryPartition partition) {
            this.requiredColumns = partition.getRequiredColumns();
            this.filters = partition.getFilters();
            this.partitionIndex = partition.getPartitionIndex();


            List<Map<String, AttributeValue>> fullResult = connector.query(partitionIndex, requiredColumns, filters);
            this.itemsIterator = fullResult.iterator();

            this.typeConversions = Arrays.stream(schema.fields())
                    .collect(Collectors.toMap(StructField::name, field -> TypeConversion.apply(field.name(), field.dataType())));
        }

        @Override
        public boolean next() {
            return itemsIterator.hasNext();
        }

        @Override
        public InternalRow get() {
            Map<String, AttributeValue> item = itemsIterator.next();
            return itemToRow(item); // Convert DynamoDB item to Spark InternalRow
        }

        @Override
        public void close() throws IOException {

        }

        private InternalRow itemToRow(Map<String, AttributeValue> item) {
            if (!requiredColumns.isEmpty()) {
                return InternalRow.fromSeq(
                        JavaConverters.asScalaBuffer(
                                requiredColumns.stream()
                                        .map(columnName -> typeConversions.get(columnName).apply(item))
                                        .collect(Collectors.toList())
                        ).toSeq()
                );
            } else {
                return InternalRow.fromSeq(
                        JavaConverters.asScalaBuffer(
                                item.values().stream()
                                        .map(value -> (Object) value.toString()) // ✅ Explicitly cast to Object
                                        .collect(Collectors.toList())
                        ).toSeq()
                );
            }
        }
    }
}

