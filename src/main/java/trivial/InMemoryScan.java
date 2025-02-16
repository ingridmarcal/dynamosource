package trivial;

import org.apache.spark.sql.connector.read.Batch;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

public class InMemoryScan implements Scan, Batch {
    @Override
    public InputPartition[] planInputPartitions() {
        // In this simple example, we use a single partition.
        return new InputPartition[]{ new InMemoryInputPartition() };
    }

    @Override
    public PartitionReaderFactory createReaderFactory() {
        return new InMemoryPartitionReaderFactory();
    }

    @Override
    public StructType readSchema() {
        return new StructType(new StructField[]{
                new StructField("value", DataTypes.StringType, false, Metadata.empty())
        });
    }

    @Override
    public Batch toBatch() {
        return this;
    }
}
