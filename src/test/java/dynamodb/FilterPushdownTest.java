import org.junit.jupiter.api.Test;
import org.apache.spark.sql.sources.EqualTo;
import org.apache.spark.sql.sources.Filter;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.junit.jupiter.api.Assertions.*;

public class FilterPushdownTest {

    @Test
    public void testReservedWordAttributeName() {
        Filter[] filters = new Filter[]{new EqualTo("size", "L")};
        FilterPushdown.Result result = FilterPushdown.apply(filters);
        assertEquals("(#n0 = :v0)", result.getExpression());
        assertEquals("size", result.getExpressionAttributeNames().get("#n0"));
        AttributeValue value = result.getExpressionAttributeValues().get(":v0");
        assertNotNull(value);
        assertEquals("L", value.s());
    }

    @Test
    public void testSpecialCharacterAttributeName() {
        Filter[] filters = new Filter[]{new EqualTo("user-name", "Bob")};
        FilterPushdown.Result result = FilterPushdown.apply(filters);
        assertEquals("(#n0 = :v0)", result.getExpression());
        assertEquals("user-name", result.getExpressionAttributeNames().get("#n0"));
        AttributeValue value = result.getExpressionAttributeValues().get(":v0");
        assertNotNull(value);
        assertEquals("Bob", value.s());
    }
}
