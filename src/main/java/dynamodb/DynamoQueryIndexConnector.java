package dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

import java.io.Serializable;
import java.util.*;
import org.apache.spark.sql.sources.Filter;

public class DynamoQueryIndexConnector extends DynamoConnector implements Serializable {

    private static final long serialVersionUID = 1L; // Serializable version

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

    private final String keyConditionExpression;

    // Mark as transient to prevent serialization
    private transient DynamoDbClient dynamoDbClient;

    // No-arg constructor for deserialization
    public DynamoQueryIndexConnector() {
        super(new HashMap<>());
        this.consistentRead = false;
        this.filterPushdown = false;
        this.region = "us-east-1";
        this.roleArn = "";
        this.providerClassName = "";
        this.keySchema = null;
        this.readLimit = 0;
        this.itemLimit = 0;
        this.totalSegments = 0;
        this.tableName = "";
        this.indexName = "";
        this.keyConditionExpression = "";
    }

    public DynamoQueryIndexConnector(String tableName, String indexName, int parallelism, Map<String, String> parameters) {
        super(parameters);
        this.tableName = tableName;
        this.indexName = indexName;
        this.consistentRead = Boolean.parseBoolean(parameters.getOrDefault("stronglyconsistentreads", "false"));
        this.filterPushdown = Boolean.parseBoolean(parameters.getOrDefault("filterpushdown", "true"));
        this.region = parameters.getOrDefault("region", "us-east-1");
        this.roleArn = parameters.getOrDefault("roleArn", "");
        this.providerClassName = parameters.getOrDefault("providerclassname", "");
        this.keyConditionExpression = parameters.getOrDefault("keyconditionexpression", "");

        // Initialize DynamoDB client lazily
        initDynamoDbClient();

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
        double targetCapacity = Double.parseDouble(parameters.getOrDefault("targetcapacity", "1"));
        int readFactor = consistentRead ? 1 : 2;

        // Index parameters
        long indexSize = indexDesc.indexSizeBytes();
        long itemCount = indexDesc.itemCount() > 0 ? indexDesc.itemCount() : 1; // Avoid division by zero

        // Provisioned or on-demand throughput
        long readThroughput = parameters.containsKey("throughput")
                ? Long.parseLong(parameters.get("throughput"))
                : (indexDesc.provisionedThroughput() != null && indexDesc.provisionedThroughput().readCapacityUnits() > 0
                ? indexDesc.provisionedThroughput().readCapacityUnits()
                : 100L);

        // Rate limit calculation
        double avgItemSize = (double) indexSize / itemCount;
        double rateLimit = readThroughput * targetCapacity / parallelism;
        this.itemLimit = Math.max(1, (int) ((bytesPerRCU / avgItemSize) * rateLimit * readFactor));
        this.readLimit = rateLimit;
        // Query operations cannot be reliably partitioned, so force a single segment
        this.totalSegments = 1;
    }

    // Lazy initialization for DynamoDbClient
    private void initDynamoDbClient() {
        if (this.dynamoDbClient == null) {
            this.dynamoDbClient = getDynamoDB(region, roleArn, providerClassName);
        }
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
    public ScanResponse scan(int segmentNum, List<String> columns, Filter[] filters) {
        initDynamoDbClient(); // Ensure client is initialized on executor

        ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                .tableName(tableName)
                .indexName(indexName)
                .segment(segmentNum)
                .totalSegments(totalSegments)
                .limit(itemLimit)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .consistentRead(consistentRead);

        if (!columns.isEmpty()) {
            scanRequestBuilder.projectionExpression(String.join(", ", columns));
        }

        if(filters != null) {
            if (filters.length > 0 && filterPushdown) {
                scanRequestBuilder.filterExpression(FilterPushdown.apply(filters));
            }
        }


        return dynamoDbClient.scan(scanRequestBuilder.build());
    }

    @Override
    public QueryIterable query(int segmentNum, List<String> columns, Filter[] filters) {
        initDynamoDbClient(); // Ensure client is initialized on executor

        if (segmentNum != 0) {
            throw new IllegalArgumentException("Query operations do not support parallel segments; segmentNum must be 0");
        }

        Map<String, AttributeValue> expressionValues = getExpressionAttributeValues();

        return dynamoDbClient.queryPaginator(queryRequest -> {
            queryRequest
                    .tableName(tableName)
                    .indexName(indexName)
                    .consistentRead(consistentRead)
                    .limit(itemLimit);

            if (keyConditionExpression != null && !keyConditionExpression.isEmpty()) {
                queryRequest.keyConditionExpression(keyConditionExpression);
            }

            if (filterPushdown && filters != null) {
                queryRequest.filterExpression(FilterPushdown.apply(filters));
            }

            if (!columns.isEmpty()) {
                // Create mappings for reserved keywords
                Map<String, String> expressionAttributeNames = new HashMap<>();
                List<String> projectionFields = new ArrayList<>();

                for (String column : columns) {
                    String placeholder = "#" + column;
                    expressionAttributeNames.put(placeholder, column);
                    projectionFields.add(placeholder);
                }

                // Set ProjectionExpression with placeholders
                queryRequest.projectionExpression(String.join(", ", projectionFields));

                queryRequest.expressionAttributeNames(expressionAttributeNames);

            }

            if (expressionValues != null && !expressionValues.isEmpty()) {
                queryRequest.expressionAttributeValues(expressionValues);
            }
        });
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

    // --- Private methods kept intact ---

    private Map<String, AttributeValue> getExpressionAttributeValues() {
        String jsonString = properties.get("expressionAttributeValues");
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
                String key = entry.getKey();
                JsonNode valueNode = entry.getValue();

                parsedValues.put(key, parseAttributeValue(valueNode));
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
