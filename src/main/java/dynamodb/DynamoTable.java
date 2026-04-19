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

import java.math.BigDecimal;
import java.util.*;

public class DynamoTable implements Table, SupportsRead {

    private static final Logger logger = LoggerFactory.getLogger(DynamoTable.class);

    private final StructType userSchema;
    private final Connector dynamoConnector;
    private final String tableName;

    public DynamoTable(Map<String, String> options, StructType userSchema) {
        this.userSchema = userSchema;

        Map<String, String> normalized = normalizeKeys(options);

        this.tableName = normalized.getOrDefault("tablename", "Unknown");
        String indexName = normalized.get("indexname");

        int defaultParallelism = normalized.containsKey("defaultparallelism")
                ? Integer.parseInt(normalized.get("defaultparallelism"))
                : getDefaultParallelism();

        if (indexName != null && !indexName.isEmpty()) {
            this.dynamoConnector = new DynamoQueryIndexConnector(this.tableName, indexName, defaultParallelism, normalized);
        } else {
            this.dynamoConnector = new DynamoScanConnector(this.tableName, defaultParallelism, normalized);
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

        if (dynamoConnector.isIndex()) {
            throw new IllegalArgumentException(
                    "Schema inference is not supported for secondary index reads. "
                            + "Please supply an explicit schema via `.schema(userSchema)`.");
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
        }
        logger.warn("Unable to read defaultParallelism from SparkSession; defaulting to 1. "
                + "Override with option `defaultParallelism` if needed.");
        return 1;
    }

    private StructType inferSchema() {
        List<Map<String, AttributeValue>> inferenceItems = new ArrayList<>();

        if (dynamoConnector.nonEmpty()) {
            Iterator<Map<String, AttributeValue>> items = dynamoConnector.readItems(
                    0, Collections.emptyList(), new Filter[0]);
            int limit = 500;
            while (items.hasNext() && limit-- > 0) {
                inferenceItems.add(items.next());
            }
        }

        Map<String, DataType> typeMapping = new LinkedHashMap<>();

        for (Map<String, AttributeValue> item : inferenceItems) {
            for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
                typeMapping.putIfAbsent(entry.getKey(), inferType(entry.getValue()));
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

    private DataType inferType(AttributeValue value) {
        if (value == null) {
            return DataTypes.StringType;
        }
        if (value.bool() != null) {
            return DataTypes.BooleanType;
        }
        if (value.n() != null) {
            try {
                BigDecimal number = new BigDecimal(value.n());
                if (number.scale() == 0) {
                    if (number.precision() < 10) return DataTypes.IntegerType;
                    else if (number.precision() < 19) return DataTypes.LongType;
                    else return DataTypes.createDecimalType(number.precision(), number.scale());
                }
                return DataTypes.DoubleType;
            } catch (NumberFormatException ignored) {
                return DataTypes.StringType;
            }
        }
        if (value.s() != null) {
            return DataTypes.StringType;
        }
        if (value.b() != null) {
            return DataTypes.BinaryType;
        }
        if (value.hasL()) {
            List<AttributeValue> list = value.l();
            if (list.isEmpty()) return DataTypes.createArrayType(DataTypes.StringType);
            return DataTypes.createArrayType(inferType(list.get(0)));
        }
        if (value.hasSs()) {
            return DataTypes.createArrayType(DataTypes.StringType);
        }
        if (value.hasNs()) {
            return DataTypes.createArrayType(DataTypes.DoubleType);
        }
        if (value.hasM()) {
            Map<String, AttributeValue> map = value.m();
            List<StructField> mapFields = new ArrayList<>();
            for (Map.Entry<String, AttributeValue> entry : map.entrySet()) {
                mapFields.add(new StructField(entry.getKey(), inferType(entry.getValue()), true, Metadata.empty()));
            }
            return new StructType(mapFields.toArray(new StructField[0]));
        }
        return DataTypes.StringType;
    }

    private static Map<String, String> normalizeKeys(Map<String, String> options) {
        if (options == null) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getKey() == null) continue;
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }
}
