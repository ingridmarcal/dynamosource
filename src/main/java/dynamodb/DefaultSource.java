package dynamodb;

import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableProvider;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.sources.DataSourceRegister;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Map;

/**
 * Spark {@link TableProvider} registered under the short name {@code dynamodb}.
 * Resolved at runtime through
 * {@code META-INF/services/org.apache.spark.sql.sources.DataSourceRegister}.
 */
public class DefaultSource implements TableProvider, DataSourceRegister {

    @Override
    public StructType inferSchema(CaseInsensitiveStringMap options) {
        return new DynamoTable(options.asCaseSensitiveMap(), null).schema();
    }

    @Override
    public Table getTable(StructType schema, Transform[] partitioning, Map<String, String> properties) {
        return new DynamoTable(properties, schema);
    }

    @Override
    public boolean supportsExternalMetadata() {
        return true;
    }

    @Override
    public String shortName() {
        return "dynamodb";
    }
}
