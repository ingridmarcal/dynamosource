package dynamodb;

import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableProvider;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.sources.DataSourceRegister;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Map;

public class DefaultSource implements TableProvider, DataSourceRegister {

    @Override
    public StructType inferSchema(CaseInsensitiveStringMap options) {
        // Pass null for userSchema when it's not provided
        return new DynamoTable(options, null).schema();
    }

    @Override
    public Table getTable(StructType schema, Transform[] partitioning, Map<String, String> properties) {
        // Directly pass the schema, can be null
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
