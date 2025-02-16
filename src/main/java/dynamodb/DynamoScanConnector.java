package dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;


import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.spark.sql.sources.Filter;


public class DynamoScanConnector extends DynamoConnector implements Serializable {

    private final boolean consistentRead;
    private final boolean filterPushdown;
    private final Optional<String> region;
    private final Optional<String> roleArn;
    private final Optional<String> providerClassName;

    private final KeySchema keySchema;
    private final double readLimit;
    private final int itemLimit;
    private final int totalSegments;
    private final String tableName;


    public DynamoScanConnector(String tableName, int parallelism, Map<String, String> parameters){
        this.tableName = tableName;
        this.consistentRead = Boolean.parseBoolean(parameters.getOrDefault("stronglyconsistentreads", "false"));
        this.filterPushdown = Boolean.parseBoolean(parameters.getOrDefault("filterpushdown", "true"));
        this.region = Optional.ofNullable(parameters.get("region"));
        this.roleArn = Optional.ofNullable(parameters.get("rolearn"));
        this.providerClassName = Optional.ofNullable(parameters.get("providerClassName"));

        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        // Use DescribeTableRequest instead of direct describeTable() call
        DescribeTableRequest request = DescribeTableRequest.builder()
                .tableName(tableName)
                .build();

        DescribeTableResponse tableDescription = dynamoDbClient.describeTable(request);

        // Extract key schema
        this.keySchema = KeySchema.fromDescription(tableDescription.table().keySchema());

        int bytesPerRCU = Integer.parseInt(parameters.getOrDefault("bytesperrcu", "4000"));
        int maxPartitionBytes = Integer.parseInt(parameters.getOrDefault("maxpartitionbytes", "128000000"));
        double targetCapacity = Double.parseDouble(parameters.getOrDefault("targetcapacity", "1"));
        int readFactor = consistentRead ? 1 : 2;

        long tableSize = tableDescription.table().tableSizeBytes();
        long itemCount = tableDescription.table().itemCount();

        int numPartitions = parameters.containsKey("readpartitions")
                ? Integer.parseInt(parameters.get("readpartitions"))
                : Math.max(1, (int) (tableSize / maxPartitionBytes));

        long readThroughput = Long.parseLong(parameters.getOrDefault("throughput",
                Optional.ofNullable(tableDescription.table().provisionedThroughput().readCapacityUnits())
                        .filter(cap -> cap > 0)  // Explicit unboxing
                        .map(String::valueOf)
                        .orElse("100")));


        double avgItemSize = (double) tableSize / itemCount;
        this.readLimit = readThroughput * targetCapacity / parallelism;
        this.itemLimit = Math.max(1, (int) ((bytesPerRCU / avgItemSize) * readLimit * readFactor));
        this.totalSegments = numPartitions;
    }

    @Override
    public ScanResponse scan(int segmentNum, List<String> columns, List<Filter> filters) {
        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        // Build ScanRequest
        ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                .tableName(tableName)
                .segment(segmentNum)
                .totalSegments(totalSegments)
                .limit(itemLimit)  // Equivalent to withMaxPageSize()
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        // Handle column projection (ProjectionExpression)
        if (!columns.isEmpty()) {
            scanRequestBuilder.projectionExpression(String.join(", ", columns));
        }

        // Handle filter conditions (FilterExpression)
        if (!filters.isEmpty() && filterPushdown) {
            String filterExpression = FilterPushdown.apply(filters); // Corrected usage
            scanRequestBuilder.filterExpression(filterExpression);
        }

        // Execute the scan
        return dynamoDbClient.scan(scanRequestBuilder.build());
    }

    @Override
    public List<Map<String, AttributeValue>> query(int segmentNum, List<String> columns, List<Filter> filters) {
        return List.of();
    }


    @Override
    public KeySchema getKeySchema() {
        return null;
    }

    @Override
    public double getReadLimit() {
        return 0;
    }

    @Override
    public int getItemLimit() {
        return 0;
    }

    @Override
    public int getTotalSegments() {
        return 0;
    }

    @Override
    public boolean isFilterPushdownEnabled() {
        return false;
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public boolean isScan() {
        return true;
    }
}

