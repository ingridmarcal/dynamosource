package trivial;

import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.ScanBuilder;

public class InMemoryScanBuilder implements ScanBuilder {

    @Override
    public Scan build() {
        return new InMemoryScan();
    }
}
