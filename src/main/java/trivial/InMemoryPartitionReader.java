package trivial;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.unsafe.types.UTF8String;

public class InMemoryPartitionReader implements PartitionReader<InternalRow> {

    private final String[] data = {"Alice", "Bob", "Charlie"};
    private int index = 0;

    @Override
    public boolean next() {
        return index < data.length;
    }

    @Override
    public InternalRow get() {
        String value = data[index++];
        // Convert the Java string to Spark's internal UTF8String and create a single‑column row.
        Object[] values = new Object[] { UTF8String.fromString(value) };
        return new GenericInternalRow(values);
    }

    @Override
    public void close() {
        // Nothing to close for an in-memory source.
    }

}
