package dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;
import software.amazon.awssdk.services.dynamodb.paginators.ScanIterable;
import org.apache.spark.sql.sources.Filter;

import java.io.Serializable;
import java.util.*;

/**
 * Connects to the main DynamoDB table. Automatically chooses between a
 * parallel {@code Scan} and a single {@code Query} based on whether the
 * pushed-down filters contain an equality on the table's hash key.
 */
public class DynamoScanConnector extends DynamoConnector implements Serializable {

    private static final long serialVersionUID = 1L;

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
        this.providerClassName = parameters.getOrDefault("providerclassname", "");

        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        DescribeTableRequest request = DescribeTableRequest.builder()
                .tableName(tableName)
                .build();

        DescribeTableResponse tableDescription = dynamoDbClient.describeTable(request);

        this.keySchema = KeySchema.fromDescription(tableDescription.table().keySchema());

        int bytesPerRCU = Integer.parseInt(parameters.getOrDefault("bytesperrcu", "4000"));
        int maxPartitionBytes = Integer.parseInt(parameters.getOrDefault("maxpartitionbytes", "128000000"));
        double targetCapacity = Double.parseDouble(parameters.getOrDefault("targetcapacity", "1"));
        int readFactor = consistentRead ? 1 : 2;

        long tableSize = Math.max(0L, tableDescription.table().tableSizeBytes());
        long itemCount = Math.max(1L, tableDescription.table().itemCount());

        int numPartitions = parameters.containsKey("readpartitions")
                ? Math.max(1, Integer.parseInt(parameters.get("readpartitions")))
                : Math.max(1, (int) (tableSize / (long) maxPartitionBytes));

        ProvisionedThroughputDescription provisionedThroughput = tableDescription.table().provisionedThroughput();
        long readThroughput = parameters.containsKey("throughput")
                ? Long.parseLong(parameters.get("throughput"))
                : (provisionedThroughput != null && provisionedThroughput.readCapacityUnits() != null
                        && provisionedThroughput.readCapacityUnits() > 0
                        ? provisionedThroughput.readCapacityUnits()
                        : 100L);

        double avgItemSize = tableSize > 0 ? ((double) tableSize) / itemCount : 1.0d;
        int safeParallelism = Math.max(1, parallelism);
        this.readLimit = Math.max(1.0d, (double) readThroughput * targetCapacity / safeParallelism);
        this.itemLimit = Math.max(1, (int) Math.min(
                Integer.MAX_VALUE,
                Math.floor((bytesPerRCU / Math.max(1.0d, avgItemSize)) * readLimit * readFactor)));
        this.totalSegments = numPartitions;
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
    public boolean isIndex() {
        return false;
    }

    @Override
    public boolean canQueryWithFilters(Filter[] filters) {
        if (!filterPushdown || filters == null || filters.length == 0) {
            return false;
        }
        FilterPushdown.KeyConditionResult result = FilterPushdown.extractKeyCondition(filters, keySchema);
        return result.hasKeyCondition();
    }

    @Override
    public Iterator<Map<String, AttributeValue>> readItems(int segmentNum, List<String> columns, Filter[] filters) {
        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        if (canQueryWithFilters(filters)) {
            return runQuery(dynamoDbClient, columns, filters);
        }
        return runScan(dynamoDbClient, segmentNum, columns, filters);
    }

    private Iterator<Map<String, AttributeValue>> runScan(DynamoDbClient client,
                                                          int segmentNum,
                                                          List<String> columns,
                                                          Filter[] filters) {
        ScanRequest.Builder builder = ScanRequest.builder()
                .tableName(tableName)
                .segment(segmentNum)
                .totalSegments(totalSegments)
                .limit(itemLimit)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();

        ProjectionExpression projection = ProjectionExpression.of(columns);
        if (projection.expression != null) {
            builder.projectionExpression(projection.expression);
            names.putAll(projection.names);
        }

        if (filters != null && filterPushdown && filters.length > 0) {
            FilterPushdown.Result filterResult = FilterPushdown.apply(filters);
            if (filterResult.getExpression() != null && !filterResult.getExpression().isEmpty()) {
                builder.filterExpression(filterResult.getExpression());
                names.putAll(filterResult.getExpressionAttributeNames());
                values.putAll(filterResult.getExpressionAttributeValues());
            }
        }

        if (!names.isEmpty()) builder.expressionAttributeNames(names);
        if (!values.isEmpty()) builder.expressionAttributeValues(values);

        ScanIterable paginator = client.scanPaginator(builder.build());
        return Iterators.flatten(paginator.iterator(), ScanResponse::items);
    }

    private Iterator<Map<String, AttributeValue>> runQuery(DynamoDbClient client,
                                                           List<String> columns,
                                                           Filter[] filters) {
        FilterPushdown.KeyConditionResult pushed = FilterPushdown.extractKeyCondition(filters, keySchema);

        Map<String, String> names = new HashMap<>(pushed.getExpressionAttributeNames());
        Map<String, AttributeValue> values = new HashMap<>(pushed.getExpressionAttributeValues());

        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression(pushed.getKeyConditionExpression())
                .limit(itemLimit)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        if (pushed.getFilterExpression() != null) {
            builder.filterExpression(pushed.getFilterExpression());
        }

        ProjectionExpression projection = ProjectionExpression.of(columns);
        if (projection.expression != null) {
            builder.projectionExpression(projection.expression);
            names.putAll(projection.names);
        }

        if (!names.isEmpty()) builder.expressionAttributeNames(names);
        if (!values.isEmpty()) builder.expressionAttributeValues(values);

        QueryIterable paginator = client.queryPaginator(builder.build());
        return Iterators.flatten(paginator.iterator(), QueryResponse::items);
    }

    /** Backwards-compatible entry point for callers that want the raw first page. */
    public ScanResponse scanFirstPage(int segmentNum, List<String> columns, Filter[] filters) {
        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        ScanRequest.Builder builder = ScanRequest.builder()
                .tableName(tableName)
                .segment(segmentNum)
                .totalSegments(totalSegments)
                .limit(itemLimit)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();

        ProjectionExpression projection = ProjectionExpression.of(columns);
        if (projection.expression != null) {
            builder.projectionExpression(projection.expression);
            names.putAll(projection.names);
        }

        if (filters != null && filterPushdown && filters.length > 0) {
            FilterPushdown.Result filterResult = FilterPushdown.apply(filters);
            if (filterResult.getExpression() != null && !filterResult.getExpression().isEmpty()) {
                builder.filterExpression(filterResult.getExpression());
                names.putAll(filterResult.getExpressionAttributeNames());
                values.putAll(filterResult.getExpressionAttributeValues());
            }
        }

        if (!names.isEmpty()) builder.expressionAttributeNames(names);
        if (!values.isEmpty()) builder.expressionAttributeValues(values);

        return dynamoDbClient.scan(builder.build());
    }
}
