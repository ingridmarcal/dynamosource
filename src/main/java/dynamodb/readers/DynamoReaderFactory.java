package dynamodb.readers;

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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creates a single paginating {@link PartitionReader} for every planned
 * partition. The connector decides internally whether the underlying call is
 * a Query or a Scan.
 */
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
        }
        return new ItemPartitionReader((ScanPartition) partition);
    }

    /** Reader used when the connector is proven to be empty (avoids a round-trip). */
    private static final class EmptyReader implements PartitionReader<InternalRow> {
        @Override public boolean next() { return false; }
        @Override public InternalRow get() { throw new IllegalStateException("Empty reader"); }
        @Override public void close() { }
    }

    /** Paginating reader that consumes the connector's unified item iterator. */
    private final class ItemPartitionReader implements PartitionReader<InternalRow> {

        private final Iterator<Map<String, AttributeValue>> iterator;
        private final List<String> requiredColumns;
        private final Map<String, Function<Map<String, AttributeValue>, Object>> typeConversions;

        private Map<String, AttributeValue> current;

        ItemPartitionReader(ScanPartition partition) {
            this.requiredColumns = partition.getRequiredColumns() == null
                    ? Collections.emptyList()
                    : partition.getRequiredColumns();
            Filter[] filters = partition.getFilters();
            this.iterator = connector.readItems(partition.getPartitionIndex(), requiredColumns, filters);
            this.typeConversions = Arrays.stream(schema.fields())
                    .collect(Collectors.toMap(
                            StructField::name,
                            field -> TypeConversion.apply(field.name(), field.dataType())));
        }

        @Override
        public boolean next() {
            if (iterator.hasNext()) {
                current = iterator.next();
                return true;
            }
            return false;
        }

        @Override
        public InternalRow get() {
            return itemToRow(current);
        }

        @Override
        public void close() { }

        private InternalRow itemToRow(Map<String, AttributeValue> item) {
            List<Object> values;
            if (!requiredColumns.isEmpty()) {
                values = requiredColumns.stream()
                        .map(col -> typeConversions.getOrDefault(col, m -> null).apply(item))
                        .collect(Collectors.toList());
            } else {
                values = Arrays.stream(schema.fields())
                        .map(StructField::name)
                        .map(col -> typeConversions.getOrDefault(col, m -> null).apply(item))
                        .collect(Collectors.toList());
            }
            return InternalRow.fromSeq(JavaConverters.asScalaBuffer(values).toList());
        }
    }
}
