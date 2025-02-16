package trivial;

import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableProvider;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Map;

public class DefaultSource implements TableProvider {
    @Override
    public StructType inferSchema(CaseInsensitiveStringMap options) {
        // Delegate to getTable - here we ignore a user-supplied schema
        return getTable(null, new Transform[0], options.asCaseSensitiveMap()).schema();
    }

    @Override
    public Table getTable(StructType schema, Transform[] transforms, Map<String, String> properties) {
        return new InMemoryTable();
    }
}
