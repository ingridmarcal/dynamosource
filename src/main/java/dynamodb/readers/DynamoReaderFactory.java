package dynamodb.readers;

import com.google.common.util.concurrent.RateLimiter;
import dynamodb.Connector;
import dynamodb.ScanPartition;
import dynamodb.TypeConversion;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import scala.collection.JavaConverters;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

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
            return new QueryPartitionReader((ScanPartition) partition);
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
        private final Filter[] filters;

        private final Iterator<Map<String, AttributeValue>> pageIterator;
        private final RateLimiter rateLimiter;
        private Iterator<InternalRow> innerIterator = Collections.emptyIterator();

        private InternalRow currentRow;
        private boolean proceed = false;

        private final Map<String, Function<Map<String, AttributeValue>, Object>> typeConversions;


        public ScanPartitionReader(ScanPartition scanPartition) {
            this.partitionIndex = scanPartition.getPartitionIndex();
            this.requiredColumns = scanPartition.getRequiredColumns();
            this.filters = scanPartition.getFilters();


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
                        ).toList()
                );
            } else {
                return InternalRow.fromSeq(
                        JavaConverters.asScalaBuffer(
                                item.values().stream()
                                        .map(value -> (Object) value.toString())
                                        .collect(Collectors.toList())
                        ).toList()
                );
            }
        }
    }

    private class QueryPartitionReader implements PartitionReader<InternalRow> {
        private final Iterator<QueryResponse> pageIterator;
        private Iterator<Map<String, AttributeValue>> itemIterator = Collections.emptyIterator();
        private final List<String> requiredColumns;
        private final RateLimiter rateLimiter;
        private final Map<String, Function<Map<String, AttributeValue>, Object>> typeConversions;

        public QueryPartitionReader(ScanPartition partition) {
            this.requiredColumns = partition.getRequiredColumns();

            this.rateLimiter = RateLimiter.create(connector.getReadLimit());
            QueryIterable queryIterable = connector.query(
                    partition.getPartitionIndex(),
                    requiredColumns,
                    partition.getFilters()
            );
            this.pageIterator = queryIterable.iterator();

            this.typeConversions = Arrays.stream(schema.fields())
                    .collect(Collectors.toMap(StructField::name,
                            field -> TypeConversion.apply(field.name(), field.dataType())));
        }

        @Override
        public boolean next() {
            if (itemIterator.hasNext()) {
                return true;
            }
            while (!itemIterator.hasNext() && pageIterator.hasNext()) {
                rateLimiter.acquire(connector.getItemLimit());
                QueryResponse page = pageIterator.next();
                itemIterator = page.items().iterator();
            }
            return itemIterator.hasNext();
        }

        @Override
        public InternalRow get() {
            Map<String, AttributeValue> item = itemIterator.next();
            return itemToRow(item);
        }

        @Override
        public void close() throws IOException {
        }

        private InternalRow itemToRow(Map<String, AttributeValue> item) {
            if (!requiredColumns.isEmpty()) {
                List<Object> values = requiredColumns.stream()
                        .map(columnName -> typeConversions.get(columnName).apply(item))
                        .collect(Collectors.toList());
                return InternalRow.fromSeq(JavaConverters.asScalaBuffer(values).toList());
            } else {
                List<Object> values = item.values().stream()
                        .map(value -> (Object) value.toString())
                        .collect(Collectors.toList());
                return InternalRow.fromSeq(JavaConverters.asScalaBuffer(values).toList());
            }
        }
    }
}

