package trivial;

import org.apache.spark.sql.connector.catalog.SupportsRead;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableCapability;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Collections;
import java.util.Set;

public class InMemoryTable implements Table, SupportsRead {
    @Override
    public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) {
        return new InMemoryScanBuilder();
    }

    @Override
    public String name() {
        return "InMemoryTable";
    }

    @Override
    public StructType schema() {
        // Define a simple schema: a single non-nullable string column "value"
        return new StructType(new StructField[]{
                new StructField("value", DataTypes.StringType, false, Metadata.empty())
        });
    }

    @Override
    public Set<TableCapability> capabilities() {
        return Collections.singleton(TableCapability.BATCH_READ);
    }


}
