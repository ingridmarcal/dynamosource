package dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;
import software.amazon.awssdk.services.dynamodb.paginators.ScanIterable;

import java.io.Serializable;
import java.util.*;
import org.apache.spark.sql.sources.Filter;

/**
 * Connects to a DynamoDB global secondary index.
 *
 * <p>Whenever the pushed-down filters contain an equality on the index hash
 * key the connector issues a single {@code Query} (optionally narrowed by a
 * range-key predicate and a {@code FilterExpression}). Otherwise it falls
 * back to a parallel {@code Scan} over the index.
 *
 * <p>The legacy {@code keyconditionexpression} /
 * {@code expressionAttributeValues} options are still honoured for
 * backwards-compatibility; they win over anything derived from Spark filters.
 */
public class DynamoQueryIndexConnector extends DynamoConnector implements Serializable {

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
    private final String indexName;

    private final String userKeyConditionExpression;

    public DynamoQueryIndexConnector(String tableName,
                                     String indexName,
                                     int parallelism,
                                     Map<String, String> parameters) {
        super(parameters);
        this.tableName = tableName;
        this.indexName = indexName;
        this.consistentRead = Boolean.parseBoolean(parameters.getOrDefault("stronglyconsistentreads", "false"));
        this.filterPushdown = Boolean.parseBoolean(parameters.getOrDefault("filterpushdown", "true"));
        this.region = parameters.getOrDefault("region", "us-east-1");
        this.roleArn = parameters.getOrDefault("rolearn", "");
        this.providerClassName = parameters.getOrDefault("providerclassname", "");
        this.userKeyConditionExpression = parameters.getOrDefault("keyconditionexpression", "");

        DynamoDbClient dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);

        DescribeTableResponse tableDesc = dynamoDbClient.describeTable(
                DescribeTableRequest.builder().tableName(tableName).build()
        );

        KeySchema derived = null;
        long indexSize = 0L;
        long indexItemCount = 0L;
        ProvisionedThroughputDescription indexThroughput = null;

        List<GlobalSecondaryIndexDescription> gsis = tableDesc.table().globalSecondaryIndexes();
        if (gsis != null) {
            for (GlobalSecondaryIndexDescription idx : gsis) {
                if (idx.indexName().equals(indexName)) {
                    derived = KeySchema.fromDescription(idx.keySchema());
                    indexSize = idx.indexSizeBytes() == null ? 0L : idx.indexSizeBytes();
                    indexItemCount = idx.itemCount() == null ? 0L : idx.itemCount();
                    indexThroughput = idx.provisionedThroughput();
                    break;
                }
            }
        }
        if (derived == null) {
            List<LocalSecondaryIndexDescription> lsis = tableDesc.table().localSecondaryIndexes();
            if (lsis != null) {
                for (LocalSecondaryIndexDescription idx : lsis) {
                    if (idx.indexName().equals(indexName)) {
                        derived = KeySchema.fromDescription(idx.keySchema());
                        indexSize = idx.indexSizeBytes() == null ? 0L : idx.indexSizeBytes();
                        indexItemCount = idx.itemCount() == null ? 0L : idx.itemCount();
                        break;
                    }
                }
            }
        }
        if (derived == null) {
            throw new IllegalArgumentException("Index not found on table " + tableName + ": " + indexName);
        }
        this.keySchema = derived;

        int bytesPerRCU = Integer.parseInt(parameters.getOrDefault("bytesperrcu", "4000"));
        int maxPartitionBytes = Integer.parseInt(parameters.getOrDefault("maxpartitionbytes", "128000000"));
        double targetCapacity = Double.parseDouble(parameters.getOrDefault("targetcapacity", "1"));
        int readFactor = consistentRead ? 1 : 2;

        indexSize = Math.max(0L, indexSize);
        long safeItemCount = Math.max(1L, indexItemCount);

        int numPartitions = parameters.containsKey("readpartitions")
                ? Math.max(1, Integer.parseInt(parameters.get("readpartitions")))
                : Math.max(1, (int) (indexSize / (long) maxPartitionBytes));

        long readThroughput = parameters.containsKey("throughput")
                ? Long.parseLong(parameters.get("throughput"))
                : (indexThroughput != null && indexThroughput.readCapacityUnits() != null
                        && indexThroughput.readCapacityUnits() > 0
                        ? indexThroughput.readCapacityUnits()
                        : 100L);

        double avgItemSize = indexSize > 0 ? ((double) indexSize) / safeItemCount : 1.0d;
        int safeParallelism = Math.max(1, parallelism);
        double rateLimit = Math.max(1.0d, (double) readThroughput * targetCapacity / safeParallelism);
        this.readLimit = rateLimit;
        this.itemLimit = Math.max(1, (int) Math.min(
                Integer.MAX_VALUE,
                Math.floor((bytesPerRCU / Math.max(1.0d, avgItemSize)) * rateLimit * readFactor)));
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
        return true;
    }

    @Override
    public boolean canQueryWithFilters(Filter[] filters) {
        if (userKeyConditionExpression != null && !userKeyConditionExpression.isEmpty()) {
            return true;
        }
        if (!filterPushdown || filters == null || filters.length == 0) {
            return false;
        }
        return FilterPushdown.extractKeyCondition(filters, keySchema).hasKeyCondition();
    }

    @Override
    public Iterator<Map<String, AttributeValue>> readItems(int segmentNum, List<String> columns, Filter[] filters) {
        DynamoDbClient client = getDynamoDB(region, roleArn, providerClassName);

        if (canQueryWithFilters(filters)) {
            return runQuery(client, columns, filters);
        }
        return runScan(client, segmentNum, columns, filters);
    }

    private Iterator<Map<String, AttributeValue>> runScan(DynamoDbClient client,
                                                          int segmentNum,
                                                          List<String> columns,
                                                          Filter[] filters) {
        ScanRequest.Builder builder = ScanRequest.builder()
                .tableName(tableName)
                .indexName(indexName)
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
            FilterPushdown.Result result = FilterPushdown.apply(filters);
            if (result.getExpression() != null && !result.getExpression().isEmpty()) {
                builder.filterExpression(result.getExpression());
                names.putAll(result.getExpressionAttributeNames());
                values.putAll(result.getExpressionAttributeValues());
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
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();

        String keyConditionExpression;
        String filterExpression = null;

        if (userKeyConditionExpression != null && !userKeyConditionExpression.isEmpty()) {
            keyConditionExpression = userKeyConditionExpression;
            values.putAll(getUserExpressionAttributeValues());
            // When the user provides a raw expression, still try to push non-key filters
            // through a FilterExpression using the automatic translation.
            if (filters != null && filterPushdown && filters.length > 0) {
                FilterPushdown.Result result = FilterPushdown.apply(filters);
                if (result.getExpression() != null && !result.getExpression().isEmpty()) {
                    filterExpression = result.getExpression();
                    names.putAll(result.getExpressionAttributeNames());
                    values.putAll(result.getExpressionAttributeValues());
                }
            }
        } else {
            FilterPushdown.KeyConditionResult pushed = FilterPushdown.extractKeyCondition(filters, keySchema);
            keyConditionExpression = pushed.getKeyConditionExpression();
            filterExpression = pushed.getFilterExpression();
            names.putAll(pushed.getExpressionAttributeNames());
            values.putAll(pushed.getExpressionAttributeValues());
        }

        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .indexName(indexName)
                .keyConditionExpression(keyConditionExpression)
                .limit(itemLimit)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        if (filterExpression != null) {
            builder.filterExpression(filterExpression);
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

    // --- User-supplied expressionAttributeValues parsing kept for backward compat ---

    private Map<String, AttributeValue> getUserExpressionAttributeValues() {
        String jsonString = properties.get("expressionAttributeValues");
        if (jsonString == null) {
            jsonString = properties.get("expressionattributevalues");
        }
        if (jsonString == null || jsonString.isEmpty()) {
            return Collections.emptyMap();
        }
        return parseExpressionAttributeValues(jsonString);
    }

    private Map<String, AttributeValue> parseExpressionAttributeValues(String jsonString) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(jsonString);

            Map<String, AttributeValue> parsedValues = new HashMap<>();

            for (Iterator<Map.Entry<String, JsonNode>> it = rootNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                parsedValues.put(entry.getKey(), parseAttributeValue(entry.getValue()));
            }

            return parsedValues;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse expressionAttributeValues JSON", e);
        }
    }

    private AttributeValue parseAttributeValue(JsonNode node) {
        if (node.has("S")) {
            return AttributeValue.builder().s(node.get("S").asText()).build();
        } else if (node.has("N")) {
            return AttributeValue.builder().n(node.get("N").asText()).build();
        } else if (node.has("BOOL")) {
            return AttributeValue.builder().bool(node.get("BOOL").asBoolean()).build();
        } else if (node.has("L")) {
            List<AttributeValue> listValues = new ArrayList<>();
            for (JsonNode listNode : node.get("L")) {
                listValues.add(parseAttributeValue(listNode));
            }
            return AttributeValue.builder().l(listValues).build();
        } else if (node.has("M")) {
            Map<String, AttributeValue> mapValues = new HashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.get("M").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                mapValues.put(entry.getKey(), parseAttributeValue(entry.getValue()));
            }
            return AttributeValue.builder().m(mapValues).build();
        } else {
            throw new IllegalArgumentException("Unsupported AttributeValue type: " + node.toString());
        }
    }
}
