package dynamodb;

import org.apache.spark.sql.connector.expressions.Expression;
import org.apache.spark.sql.connector.read.partitioning.KeyGroupedPartitioning;
import org.apache.spark.sql.connector.read.partitioning.Partitioning;

import java.io.Serializable;

public class OutputPartitioning implements Partitioning {
    private final KeyGroupedPartitioning delegate;

    public OutputPartitioning(Expression[] keys, int numPartitions) {
        this.delegate = new KeyGroupedPartitioning(keys, numPartitions);
    }

    @Override
    public int numPartitions() {
        return delegate.numPartitions();
    }

    public Expression[] keys() {
        return delegate.keys();
    }
}