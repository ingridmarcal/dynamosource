package dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import org.apache.spark.sql.sources.Filter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Connector {


    ScanResponse scan(int segmentNum, List<String> columns, List<Filter> filters);
    List<Map<String, AttributeValue>> query(int segmentNum, List<String> columns, List<Filter> filters);
    boolean isFilterPushdownEnabled();
    boolean nonEmpty();
    boolean isEmpty();
    boolean isQuery();
    boolean isScan();
    KeySchema getKeySchema();
    int getTotalSegments();
    double getReadLimit();

}
