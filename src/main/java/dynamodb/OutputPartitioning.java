package dynamodb;

import org.apache.spark.sql.connector.expressions.Expression;
import org.apache.spark.sql.connector.read.partitioning.KeyGroupedPartitioning;
import org.apache.spark.sql.connector.read.partitioning.Partitioning;

public class OutputPartitioning extends KeyGroupedPartitioning {

    public OutputPartitioning(Expression[]  partitionKeys, int numPartitions) {
        super(partitionKeys, numPartitions);
    }


}
