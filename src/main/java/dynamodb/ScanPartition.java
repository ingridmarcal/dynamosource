package dynamodb;

import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.sources.Filter;
import java.io.Serializable;
import java.util.List;

public class ScanPartition implements InputPartition, Serializable {

    private final int partitionIndex;
    private final List<String> requiredColumns;
    private final Filter[] filters;

    public ScanPartition(int partitionIndex, List<String> requiredColumns, Filter[] filters) {
        this.partitionIndex = partitionIndex;
        this.requiredColumns = requiredColumns;
        this.filters = filters;
    }

    public int getPartitionIndex() {
        return partitionIndex;
    }

    public List<String> getRequiredColumns() {
        return requiredColumns;
    }

    public Filter[] getFilters() {
        return filters;
    }
}

