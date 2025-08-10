package dynamodb;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.connector.catalog.*;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.math.BigDecimal;
import java.util.*;

public class DynamoTable implements Table, SupportsRead {

    private static final Logger logger = LoggerFactory.getLogger(DynamoTable.class);

    private final StructType userSchema;
    private final Connector dynamoConnector;
    private final String tableName;

    public DynamoTable(Map<String, String> options, StructType userSchema) {
        this.userSchema = userSchema;

        this.tableName = options.getOrDefault("tablename", "Unknown");
        String indexName = options.get("indexname");

        int defaultParallelism = options.containsKey("defaultparallelism")
                ? Integer.parseInt(options.get("defaultparallelism"))
                : getDefaultParallelism();

        if (indexName != null) {
            this.dynamoConnector = new DynamoQueryIndexConnector(this.tableName, indexName, defaultParallelism, options);
        } else {
            this.dynamoConnector = new DynamoScanConnector(this.tableName, defaultParallelism, options);
        }
    }

    @Override
    public ScanBuilder newScanBuilder(CaseInsensitiveStringMap caseInsensitiveStringMap) {
        return new DynamoScanBuilder(dynamoConnector, schema());
    }

    @Override
    public String name() {
        return this.tableName;
    }

    @Override
    public StructType schema() {
        if (userSchema != null) {
            return userSchema;
        }

        if (dynamoConnector.isQuery()) {
            throw new IllegalArgumentException(
                    "Schema inference is not supported for index queries. " +
                    "Please supply `userSchema` when reading via a secondary index.");
        }

        return inferSchema();
    }

    @Override
    public Set<TableCapability> capabilities() {
        return new HashSet<>(Arrays.asList(
                TableCapability.BATCH_READ,
                TableCapability.ACCEPT_ANY_SCHEMA
        ));
    }

    private int getDefaultParallelism() {
        Option<SparkSession> sparkSession = SparkSession.getActiveSession();

        if (sparkSession.isDefined()) {
            return sparkSession.get().sparkContext().defaultParallelism();
        } else {
            logger.warn("Unable to read defaultParallelism from SparkSession. Parallelism will be 1 unless overridden with option `defaultParallelism`");
            return 1;
        }
    }

    private StructType inferSchema() {
        List<Map<String, AttributeValue>> inferenceItems = new ArrayList<>();

        if (dynamoConnector.nonEmpty()) {
            ScanResponse scanResponse = dynamoConnector.scan(0, Collections.emptyList(), new Filter[0]);
            inferenceItems = scanResponse.items();
        }

        Map<String, DataType> typeMapping = new HashMap<>();

        for (Map<String, AttributeValue> item : inferenceItems) {
            for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
                typeMapping.put(entry.getKey(), inferType(entry.getValue()));
            }
        }

        List<StructField> typeSeq = new ArrayList<>();
        for (Map.Entry<String, DataType> entry : typeMapping.entrySet()) {
            typeSeq.add(new StructField(entry.getKey(), entry.getValue(), true, Metadata.empty()));
        }

        if (typeSeq.size() > 150) {
            throw new RuntimeException("Schema inference not possible, too many attributes in table.");
        }

        return new StructType(typeSeq.toArray(new StructField[0]));
    }

    private DataType inferType(Object value) {
        if (value instanceof BigDecimal) {
            BigDecimal number = (BigDecimal) value;
            if (number.scale() == 0) {
                if (number.precision() < 10) return DataTypes.IntegerType;
                else if (number.precision() < 19) return DataTypes.LongType;
                else return DataTypes.createDecimalType(number.precision(), number.scale());
            } else {
                return DataTypes.DoubleType;
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return DataTypes.createArrayType(DataTypes.StringType);
            return DataTypes.createArrayType(inferType(list.get(0)));
        } else if (value instanceof Set) {
            Set<?> set = (Set<?>) value;
            if (set.isEmpty()) return DataTypes.createArrayType(DataTypes.StringType);
            return DataTypes.createArrayType(inferType(set.iterator().next()));
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<StructField> mapFields = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String) {
                    mapFields.add(new StructField((String) entry.getKey(), inferType(entry.getValue()), true, Metadata.empty()));
                }
            }
            return new StructType(mapFields.toArray(new StructField[0]));
        } else if (value instanceof Boolean) {
            return DataTypes.BooleanType;
        } else if (value instanceof byte[]) {
            return DataTypes.BinaryType;
        } else {
            return DataTypes.StringType;
        }
    }
}
