package dynamodb;

import org.apache.spark.sql.sources.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    @Test
    public void testExtractKeyConditionHashKeyOnly() {
        KeySchema schema = KeySchema.fromDescription(Arrays.asList(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()
        ));
        Filter[] filters = new Filter[]{
                new EqualTo("pk", "abc"),
                new GreaterThan("amount", 10)
        };
        FilterPushdown.KeyConditionResult result =
                FilterPushdown.extractKeyCondition(filters, schema);

        assertTrue(result.hasKeyCondition());
        assertEquals("#n0 = :v0", result.getKeyConditionExpression());
        assertEquals("(#n1 > :v1)", result.getFilterExpression());
        assertEquals("pk", result.getExpressionAttributeNames().get("#n0"));
        assertEquals("amount", result.getExpressionAttributeNames().get("#n1"));

        Map<String, AttributeValue> vals = result.getExpressionAttributeValues();
        assertEquals("abc", vals.get(":v0").s());
        assertEquals("10", vals.get(":v1").n());
    }

    @Test
    public void testExtractKeyConditionHashAndRange() {
        KeySchema schema = KeySchema.fromDescription(Arrays.asList(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()
        ));
        Filter[] filters = new Filter[]{
                new EqualTo("pk", "abc"),
                new StringStartsWith("sk", "2024-"),
                new GreaterThan("amount", 10)
        };
        FilterPushdown.KeyConditionResult result =
                FilterPushdown.extractKeyCondition(filters, schema);

        assertTrue(result.hasKeyCondition());
        assertEquals("#n0 = :v0 AND begins_with(#n1, :v1)", result.getKeyConditionExpression());
        assertEquals("(#n2 > :v2)", result.getFilterExpression());
        assertEquals("pk", result.getExpressionAttributeNames().get("#n0"));
        assertEquals("sk", result.getExpressionAttributeNames().get("#n1"));
        assertEquals("amount", result.getExpressionAttributeNames().get("#n2"));
    }

    @Test
    public void testExtractKeyConditionWithoutHashKeyEquality() {
        KeySchema schema = KeySchema.fromDescription(Arrays.asList(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()
        ));
        Filter[] filters = new Filter[]{
                new GreaterThan("amount", 10)
        };
        FilterPushdown.KeyConditionResult result =
                FilterPushdown.extractKeyCondition(filters, schema);

        assertFalse(result.hasKeyCondition());
        assertNull(result.getKeyConditionExpression());
        assertEquals("(#n0 > :v0)", result.getFilterExpression());
    }

    @Test
    public void testExtractKeyConditionUnsupportedPostFilter() {
        KeySchema schema = KeySchema.fromDescription(Arrays.asList(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()
        ));
        Filter[] filters = new Filter[]{
                new EqualTo("pk", "abc"),
                new StringEndsWith("name", "x") // unsupported in DynamoDB
        };
        FilterPushdown.KeyConditionResult result =
                FilterPushdown.extractKeyCondition(filters, schema);

        assertTrue(result.hasKeyCondition());
        assertNull(result.getFilterExpression());
        List<Filter> post = result.getPostFilters();
        assertEquals(1, post.size());
        assertTrue(post.get(0) instanceof StringEndsWith);
    }

    @Test
    public void testAcceptFiltersRejectsStringEndsWith() {
        Filter[] filters = new Filter[]{
                new EqualTo("a", "b"),
                new StringEndsWith("c", "d")
        };
        FilterPushdown.Tuple<List<Filter>, List<Filter>> split = FilterPushdown.acceptFilters(filters);
        assertEquals(1, split.getFirst().size());
        assertEquals(1, split.getSecond().size());
        assertTrue(split.getSecond().get(0) instanceof StringEndsWith);
    }

    @Test
    public void testReusesAttributeNamePlaceholder() {
        Filter[] filters = new Filter[]{
                new EqualTo("size", "L"),
                new GreaterThan("size", "S")
        };
        FilterPushdown.Result result = FilterPushdown.apply(filters);
        // The same attribute name should re-use a single placeholder.
        assertEquals("(#n0 = :v0) AND (#n0 > :v1)", result.getExpression());
        assertEquals(1, result.getExpressionAttributeNames().size());
        assertEquals("size", result.getExpressionAttributeNames().get("#n0"));
    }
}
