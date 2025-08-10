package dynamodb;

import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;
import org.apache.spark.sql.sources.Filter;

import java.util.List;

public interface Connector {


    ScanResponse scan(int segmentNum, List<String> columns, Filter[] filters);
    QueryIterable query(int segmentNum, List<String> columns, Filter[] filters);
    boolean isFilterPushdownEnabled();
    boolean nonEmpty();
    boolean isEmpty();
    boolean isQuery();
    boolean isScan();
    KeySchema getKeySchema();
    int getTotalSegments();
    double getReadLimit();

}
