package dynamodb;

import org.apache.spark.sql.connector.read.partitioning.Partitioning;

public class OutputPartitioning implements Partitioning {

    private final int numPartitions;

    public OutputPartitioning(int numPartitions) {
        this.numPartitions = numPartitions;
    }

    @Override
    public int numPartitions() {
        return numPartitions;
    }

}
