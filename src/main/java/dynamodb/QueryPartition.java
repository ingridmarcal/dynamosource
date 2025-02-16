package dynamodb;

import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.sources.Filter;

import java.io.Serializable;
import java.util.List;

public class QueryPartition implements InputPartition, Serializable {
    private final int partitionIndex;
    private final List<String> requiredColumns;
    private final List<Filter> filters;

    public QueryPartition(int partitionIndex, List<String> requiredColumns, List<Filter> filters) {
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

    public List<Filter> getFilters() {
        return filters;
    }
}

