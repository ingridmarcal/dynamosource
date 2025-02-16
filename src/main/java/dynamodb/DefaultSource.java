package dynamodb;

import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableProvider;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.sources.DataSourceRegister;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Map;
import java.util.Optional;

public class DefaultSource implements TableProvider, DataSourceRegister {


    @Override
    public StructType inferSchema(CaseInsensitiveStringMap options) {
        return new DynamoTable(options, Optional.empty()).schema(); // ✅ Pass Optional.empty() as the second argument
    }


    @Override
    public Table getTable(StructType schema, Transform[] partitioning, Map<String, String> properties) {
        return new DynamoTable(new CaseInsensitiveStringMap(properties), Optional.ofNullable(schema)); // ✅ Use `new` keyword
    }

    @Override
    public boolean supportsExternalMetadata(){
        return true;
    }

    @Override
    public String shortName() {
        return "dynamodb";
    }
}
