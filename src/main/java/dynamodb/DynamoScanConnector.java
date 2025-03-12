package dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.apache.spark.sql.sources.Filter;

public class DynamoScanConnector extends DynamoConnector implements Serializable {

    private final boolean consistentRead;
    private final boolean filterPushdown;
    private final String region;
    private final String roleArn;
    private final String providerClassName;

    private final KeySchema keySchema;
    private final double readLimit;
    private final int itemLimit;
    private final int totalSegments;
    private final String tableName;

    public DynamoScanConnector(String tableName, int parallelism, Map<String, String> parameters) {
        super(parameters);
        this.tableName = tableName;
        this.consistentRead = Boolean.parseBoolean(parameters.getOrDefault("stronglyconsistentreads", "false"));
        this.filterPushdown = Boolean.parseBoolean(parameters.getOrDefault("filterpushdown", "true"));
        this.region = parameters.getOrDefault("region", "us-east-1");
        this.roleArn = parameters.getOrDefault("rolearn", "");
        this.providerClassName = parameters.getOrDefault("providerClassName", "");

        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        // Use DescribeTableRequest
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

        ProvisionedThroughputDescription provisionedThroughput = tableDescription.table().provisionedThroughput();
        long readThroughput = provisionedThroughput != null && provisionedThroughput.readCapacityUnits() > 0
                ? provisionedThroughput.readCapacityUnits()
                : Long.parseLong(parameters.getOrDefault("throughput", "100"));

        double avgItemSize = (double) tableSize / itemCount;
        this.readLimit = readThroughput * targetCapacity / parallelism;
        this.itemLimit = Math.max(1, (int) ((bytesPerRCU / avgItemSize) * readLimit * readFactor));
        this.totalSegments = numPartitions;
    }

    @Override
    public ScanResponse scan(int segmentNum, List<String> columns, Filter[] filters) {
        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        // Build ScanRequest
        ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                .tableName(tableName)
                .segment(segmentNum)
                .totalSegments(totalSegments)
                .limit(itemLimit)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        // Handle column projection
        if (!columns.isEmpty()) {
            scanRequestBuilder.projectionExpression(String.join(", ", columns));
        }

        // Handle filter conditions
        if (filters != null && filterPushdown) {
            String filterExpression = FilterPushdown.apply(filters);
            scanRequestBuilder.filterExpression(filterExpression);
        }

        return dynamoDbClient.scan(scanRequestBuilder.build());
    }

    @Override
    public List<Map<String, AttributeValue>> query(int segmentNum, List<String> columns, Filter[] filters) {
        return List.of();
    }

    @Override
    public KeySchema getKeySchema() {
        return this.keySchema;
    }

    @Override
    public double getReadLimit() {
        return this.readLimit;
    }

    @Override
    public int getItemLimit() {
        return this.itemLimit;
    }

    @Override
    public int getTotalSegments() {
        return this.totalSegments;
    }

    @Override
    public boolean isFilterPushdownEnabled() {
        return this.filterPushdown;
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
