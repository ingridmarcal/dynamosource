package dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.*;
import org.apache.spark.sql.sources.Filter;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

public class DynamoQueryIndexConnector extends DynamoConnector {

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
    private final String indexName;

    private final Optional<String> keyConditionExpression;

    public DynamoQueryIndexConnector(String tableName, String indexName, int parallelism, Map<String, String> parameters) {
        this.tableName = tableName;
        this.indexName = indexName;
        this.consistentRead = Boolean.parseBoolean(parameters.getOrDefault("stronglyconsistentreads", "false"));
        this.filterPushdown = Boolean.parseBoolean(parameters.getOrDefault("filterpushdown", "true"));
        this.region = Optional.ofNullable(parameters.get("region"));
        this.roleArn = Optional.ofNullable(parameters.get("roleArn"));
        this.providerClassName = Optional.ofNullable(parameters.get("providerclassname"));
        this.keyConditionExpression = Optional.ofNullable(parameters.get("keyconditionexpression"));

        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        // Describe the index
        DescribeTableResponse tableDesc = dynamoDbClient.describeTable(
                DescribeTableRequest.builder().tableName(tableName).build()
        );

        GlobalSecondaryIndexDescription indexDesc = tableDesc.table().globalSecondaryIndexes()
                .stream()
                .filter(idx -> idx.indexName().equals(indexName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Index not found: " + indexName));

        // Extract key schema
        this.keySchema = KeySchema.fromDescription(indexDesc.keySchema());

        // User parameters
        int bytesPerRCU = Integer.parseInt(parameters.getOrDefault("bytesperrcu", "4000"));
        int maxPartitionBytes = Integer.parseInt(parameters.getOrDefault("maxpartitionbytes", "128000000"));
        double targetCapacity = Double.parseDouble(parameters.getOrDefault("targetcapacity", "1"));
        int readFactor = consistentRead ? 1 : 2;

        // Index parameters
        long indexSize = indexDesc.indexSizeBytes();
        long itemCount = indexDesc.itemCount();

        // Partitioning calculation
        int numPartitions = parameters.containsKey("readpartitions")
                ? Integer.parseInt(parameters.get("readpartitions"))
                : Math.max(1, (int) (indexSize / maxPartitionBytes));

        // Provisioned or on-demand throughput
        long readThroughput = parameters.containsKey("throughput")
                ? Long.parseLong(parameters.get("throughput"))
                : Optional.ofNullable(indexDesc.provisionedThroughput())
                .map(ProvisionedThroughputDescription::readCapacityUnits)
                .filter(rcu -> rcu > 0)
                .orElse(100L);

        // Rate limit calculation
        double avgItemSize = (double) indexSize / itemCount;
        double rateLimit = readThroughput * targetCapacity / parallelism;
        this.itemLimit = Math.max(1, (int) ((bytesPerRCU / avgItemSize) * rateLimit * readFactor));
        this.readLimit = rateLimit;
        this.totalSegments = numPartitions;
    }

    @Override
    public boolean isFilterPushdownEnabled() {
        return filterPushdown;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public boolean isScan() {
        return false;
    }

    @Override
    public ScanResponse scan(int segmentNum, List<String> columns, List<Filter> filters) {
        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                .tableName(tableName)
                .indexName(indexName)
                .segment(segmentNum)
                .totalSegments(totalSegments)
                .limit(itemLimit)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        // Handle column projections
        if (!columns.isEmpty()) {
            scanRequestBuilder.projectionExpression(String.join(", ", columns));
        }

        // Handle filter expressions
        if (!filters.isEmpty() && filterPushdown) {
            scanRequestBuilder.filterExpression(FilterPushdown.apply(filters));
        }

        return dynamoDbClient.scan(scanRequestBuilder.build());
    }

    @Override
    public List<Map<String, AttributeValue>> query(int segmentNum, List<String> columns, List<Filter> filters) {
        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        // Collect all results across pages
        List<Map<String, AttributeValue>> fullResult = new ArrayList<>();

        // Use queryPaginator() for automatic pagination handling
        QueryIterable queryIterable = dynamoDbClient.queryPaginator(queryRequest -> {
            queryRequest
                    .tableName(tableName)
                    .indexName(indexName) // Query using the index
                    .consistentRead(consistentRead)
                    .limit(itemLimit);

            this.keyConditionExpression.ifPresent(queryRequest::keyConditionExpression);

            if (filterPushdown && !filters.isEmpty()) {
                queryRequest.filterExpression(FilterPushdown.apply(filters));
            }

            if (!columns.isEmpty()) {
                queryRequest.projectionExpression(String.join(", ", columns));
            }
        });

        // Iterate through pages & collect all items
        for (QueryResponse page : queryIterable) {
            fullResult.addAll(page.items());
        }

        return fullResult;  // Return all items as a single list
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
}

