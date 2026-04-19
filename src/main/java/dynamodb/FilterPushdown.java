package dynamodb;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import org.apache.spark.sql.sources.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Translates Spark {@link Filter}s into DynamoDB expression strings.
 * <p>
 * Besides building a {@code FilterExpression}, this class can also extract a
 * {@code KeyConditionExpression} that matches the connector's {@link KeySchema}
 * so that the connector can use a {@code Query} instead of a {@code Scan}
 * whenever the filters pin the hash key (and optionally constrain the range
 * key).
 */
public class FilterPushdown {

    /** Builds a flat {@code FilterExpression} AND-ing all supplied filters. */
    public static Result apply(Filter[] filters) {
        PlaceholderContext context = new PlaceholderContext();
        return apply(filters, context);
    }

    private static Result apply(Filter[] filters, PlaceholderContext context) {
        String expression = Arrays.stream(filters)
                .map(f -> buildCondition(f, context))
                .map(FilterPushdown::parenthesize)
                .collect(Collectors.joining(" AND "));
        return new Result(
                expression,
                new LinkedHashMap<>(context.getExpressionAttributeNames()),
                new LinkedHashMap<>(context.getExpressionAttributeValues()));
    }

    /**
     * Extracts a {@code KeyConditionExpression} (when possible) and a
     * {@code FilterExpression} from the supplied Spark filters given the
     * {@link KeySchema} of the target table or index.
     *
     * <p>A key condition is produced only when the filters contain an
     * {@code EqualTo} on the hash key. The range key can optionally be
     * constrained by an {@code EqualTo}, an ordering comparison, or a
     * {@code StringStartsWith}. All other filters become part of the
     * {@code FilterExpression} or are returned as post-filters when they
     * cannot be expressed in DynamoDB.
     */
    public static KeyConditionResult extractKeyCondition(Filter[] filters, KeySchema keySchema) {
        PlaceholderContext context = new PlaceholderContext();

        List<Filter> keyConditionFilters = new ArrayList<>();
        List<Filter> remainingFilters = new ArrayList<>();
        List<Filter> postFilters = new ArrayList<>();

        // Find a single EqualTo on the hash key.
        Filter hashKeyEq = null;
        String hashKey = keySchema.getHashKeyName();
        String rangeKey = keySchema.getRangeKeyName();

        for (Filter f : filters) {
            if (hashKeyEq == null && isEqualToOnAttribute(f, hashKey)) {
                hashKeyEq = f;
            } else {
                remainingFilters.add(f);
            }
        }

        String keyConditionExpression = null;
        if (hashKeyEq != null) {
            keyConditionFilters.add(hashKeyEq);

            // Attempt at most one range-key predicate as part of the key condition.
            Filter rangeKeyCondition = null;
            if (rangeKey != null) {
                Iterator<Filter> it = remainingFilters.iterator();
                while (it.hasNext()) {
                    Filter f = it.next();
                    if (isRangeKeyEligible(f, rangeKey)) {
                        rangeKeyCondition = f;
                        it.remove();
                        break;
                    }
                }
            }

            List<String> conditionPieces = new ArrayList<>();
            conditionPieces.add(buildCondition(hashKeyEq, context));
            if (rangeKeyCondition != null) {
                conditionPieces.add(buildCondition(rangeKeyCondition, context));
                keyConditionFilters.add(rangeKeyCondition);
            }
            keyConditionExpression = String.join(" AND ", conditionPieces);
        }

        // Build a filter expression from remaining filters that are supported;
        // unsupported ones are handed back to Spark as post-filters.
        List<Filter> filterExprFilters = new ArrayList<>();
        for (Filter f : remainingFilters) {
            if (checkFilter(f)) {
                filterExprFilters.add(f);
            } else {
                postFilters.add(f);
            }
        }

        String filterExpression = null;
        if (!filterExprFilters.isEmpty()) {
            filterExpression = filterExprFilters.stream()
                    .map(f -> buildCondition(f, context))
                    .map(FilterPushdown::parenthesize)
                    .collect(Collectors.joining(" AND "));
        }

        return new KeyConditionResult(
                keyConditionExpression,
                filterExpression,
                new LinkedHashMap<>(context.getExpressionAttributeNames()),
                new LinkedHashMap<>(context.getExpressionAttributeValues()),
                keyConditionFilters,
                filterExprFilters,
                postFilters);
    }

    public static Tuple<List<Filter>, List<Filter>> acceptFilters(Filter[] filters) {
        List<Filter> validFilters = new ArrayList<>();
        List<Filter> invalidFilters = new ArrayList<>();

        for (Filter filter : filters) {
            if (checkFilter(filter)) {
                validFilters.add(filter);
            } else {
                invalidFilters.add(filter);
            }
        }
        return new Tuple<>(validFilters, invalidFilters);
    }

    static boolean checkFilter(Filter filter) {
        if (filter instanceof EqualTo
                || filter instanceof GreaterThan
                || filter instanceof GreaterThanOrEqual
                || filter instanceof LessThan
                || filter instanceof LessThanOrEqual
                || filter instanceof In
                || filter instanceof IsNull
                || filter instanceof IsNotNull
                || filter instanceof StringStartsWith
                || filter instanceof StringContains) {
            return true;
        }
        if (filter instanceof And) {
            return checkFilter(((And) filter).left()) && checkFilter(((And) filter).right());
        }
        if (filter instanceof Or) {
            return checkFilter(((Or) filter).left()) && checkFilter(((Or) filter).right());
        }
        if (filter instanceof Not) {
            return checkFilter(((Not) filter).child());
        }
        // Explicitly unsupported: StringEndsWith (DynamoDB has no suffix operator), EqualNullSafe, etc.
        return false;
    }

    private static boolean isEqualToOnAttribute(Filter filter, String attribute) {
        if (!(filter instanceof EqualTo)) return false;
        return ((EqualTo) filter).attribute().equals(attribute);
    }

    private static boolean isRangeKeyEligible(Filter filter, String rangeKey) {
        if (filter instanceof EqualTo) return ((EqualTo) filter).attribute().equals(rangeKey);
        if (filter instanceof GreaterThan) return ((GreaterThan) filter).attribute().equals(rangeKey);
        if (filter instanceof GreaterThanOrEqual) return ((GreaterThanOrEqual) filter).attribute().equals(rangeKey);
        if (filter instanceof LessThan) return ((LessThan) filter).attribute().equals(rangeKey);
        if (filter instanceof LessThanOrEqual) return ((LessThanOrEqual) filter).attribute().equals(rangeKey);
        if (filter instanceof StringStartsWith) return ((StringStartsWith) filter).attribute().equals(rangeKey);
        return false;
    }

    private static String buildCondition(Filter filter, PlaceholderContext context) {
        if (filter instanceof EqualTo) {
            String name = context.addAttributeName(((EqualTo) filter).attribute());
            String value = context.addAttributeValue(((EqualTo) filter).value());
            return String.format("%s = %s", name, value);
        } else if (filter instanceof GreaterThan) {
            String name = context.addAttributeName(((GreaterThan) filter).attribute());
            String value = context.addAttributeValue(((GreaterThan) filter).value());
            return String.format("%s > %s", name, value);
        } else if (filter instanceof GreaterThanOrEqual) {
            String name = context.addAttributeName(((GreaterThanOrEqual) filter).attribute());
            String value = context.addAttributeValue(((GreaterThanOrEqual) filter).value());
            return String.format("%s >= %s", name, value);
        } else if (filter instanceof LessThan) {
            String name = context.addAttributeName(((LessThan) filter).attribute());
            String value = context.addAttributeValue(((LessThan) filter).value());
            return String.format("%s < %s", name, value);
        } else if (filter instanceof LessThanOrEqual) {
            String name = context.addAttributeName(((LessThanOrEqual) filter).attribute());
            String value = context.addAttributeValue(((LessThanOrEqual) filter).value());
            return String.format("%s <= %s", name, value);
        } else if (filter instanceof In) {
            String name = context.addAttributeName(((In) filter).attribute());
            Object[] vals = ((In) filter).values();
            String placeholders = Arrays.stream(vals)
                    .map(context::addAttributeValue)
                    .collect(Collectors.joining(", "));
            return String.format("%s IN (%s)", name, placeholders);
        } else if (filter instanceof IsNull) {
            String name = context.addAttributeName(((IsNull) filter).attribute());
            return String.format("attribute_not_exists(%s)", name);
        } else if (filter instanceof IsNotNull) {
            String name = context.addAttributeName(((IsNotNull) filter).attribute());
            return String.format("attribute_exists(%s)", name);
        } else if (filter instanceof StringStartsWith) {
            String name = context.addAttributeName(((StringStartsWith) filter).attribute());
            String value = context.addAttributeValue(((StringStartsWith) filter).value());
            return String.format("begins_with(%s, %s)", name, value);
        } else if (filter instanceof StringContains) {
            String name = context.addAttributeName(((StringContains) filter).attribute());
            String value = context.addAttributeValue(((StringContains) filter).value());
            return String.format("contains(%s, %s)", name, value);
        } else if (filter instanceof And) {
            return String.format("(%s AND %s)",
                    buildCondition(((And) filter).left(), context),
                    buildCondition(((And) filter).right(), context));
        } else if (filter instanceof Or) {
            return String.format("(%s OR %s)",
                    buildCondition(((Or) filter).left(), context),
                    buildCondition(((Or) filter).right(), context));
        } else if (filter instanceof Not) {
            return String.format("NOT (%s)", buildCondition(((Not) filter).child(), context));
        }
        throw new UnsupportedOperationException("Unsupported filter type: " + filter.getClass().getSimpleName());
    }

    private static AttributeValue toAttributeValue(Object value) {
        if (value == null) {
            return AttributeValue.builder().nul(true).build();
        }
        if (value instanceof String) {
            return AttributeValue.builder().s((String) value).build();
        }
        if (value instanceof Number) {
            return AttributeValue.builder().n(value.toString()).build();
        }
        if (value instanceof Boolean) {
            return AttributeValue.builder().bool((Boolean) value).build();
        }
        if (value instanceof byte[]) {
            return AttributeValue.builder()
                    .b(software.amazon.awssdk.core.SdkBytes.fromByteArray((byte[]) value))
                    .build();
        }
        throw new IllegalArgumentException("Unsupported value type for DynamoDB filter: " + value.getClass().getSimpleName());
    }

    private static String parenthesize(String condition) {
        return "(" + condition + ")";
    }

    private static class PlaceholderContext {
        private final Map<String, String> expressionAttributeNames = new LinkedHashMap<>();
        private final Map<String, AttributeValue> expressionAttributeValues = new LinkedHashMap<>();
        private final Map<String, String> nameToPlaceholder = new HashMap<>();
        private int nameCounter = 0;
        private int valueCounter = 0;

        String addAttributeName(String actualName) {
            String existing = nameToPlaceholder.get(actualName);
            if (existing != null) {
                return existing;
            }
            String placeholder = "#n" + nameCounter++;
            expressionAttributeNames.put(placeholder, actualName);
            nameToPlaceholder.put(actualName, placeholder);
            return placeholder;
        }

        String addAttributeValue(Object value) {
            String placeholder = ":v" + valueCounter++;
            expressionAttributeValues.put(placeholder, toAttributeValue(value));
            return placeholder;
        }

        Map<String, String> getExpressionAttributeNames() {
            return expressionAttributeNames;
        }

        Map<String, AttributeValue> getExpressionAttributeValues() {
            return expressionAttributeValues;
        }
    }

    public static class Result {
        private final String expression;
        private final Map<String, String> expressionAttributeNames;
        private final Map<String, AttributeValue> expressionAttributeValues;

        public Result(String expression,
                      Map<String, String> expressionAttributeNames,
                      Map<String, AttributeValue> expressionAttributeValues) {
            this.expression = expression;
            this.expressionAttributeNames = expressionAttributeNames;
            this.expressionAttributeValues = expressionAttributeValues;
        }

        public String getExpression() {
            return expression;
        }

        public Map<String, String> getExpressionAttributeNames() {
            return expressionAttributeNames;
        }

        public Map<String, AttributeValue> getExpressionAttributeValues() {
            return expressionAttributeValues;
        }
    }

    public static class KeyConditionResult {
        private final String keyConditionExpression;
        private final String filterExpression;
        private final Map<String, String> expressionAttributeNames;
        private final Map<String, AttributeValue> expressionAttributeValues;
        private final List<Filter> keyConditionFilters;
        private final List<Filter> filterExpressionFilters;
        private final List<Filter> postFilters;

        public KeyConditionResult(String keyConditionExpression,
                                  String filterExpression,
                                  Map<String, String> expressionAttributeNames,
                                  Map<String, AttributeValue> expressionAttributeValues,
                                  List<Filter> keyConditionFilters,
                                  List<Filter> filterExpressionFilters,
                                  List<Filter> postFilters) {
            this.keyConditionExpression = keyConditionExpression;
            this.filterExpression = filterExpression;
            this.expressionAttributeNames = expressionAttributeNames;
            this.expressionAttributeValues = expressionAttributeValues;
            this.keyConditionFilters = keyConditionFilters;
            this.filterExpressionFilters = filterExpressionFilters;
            this.postFilters = postFilters;
        }

        /** May be null when no hash-key equality was supplied. */
        public String getKeyConditionExpression() {
            return keyConditionExpression;
        }

        /** May be null when every supported filter was consumed by the key condition. */
        public String getFilterExpression() {
            return filterExpression;
        }

        public Map<String, String> getExpressionAttributeNames() {
            return expressionAttributeNames;
        }

        public Map<String, AttributeValue> getExpressionAttributeValues() {
            return expressionAttributeValues;
        }

        public List<Filter> getKeyConditionFilters() {
            return keyConditionFilters;
        }

        public List<Filter> getFilterExpressionFilters() {
            return filterExpressionFilters;
        }

        public List<Filter> getPostFilters() {
            return postFilters;
        }

        public boolean hasKeyCondition() {
            return keyConditionExpression != null && !keyConditionExpression.isEmpty();
        }
    }

    public static class Tuple<A, B> {
        private final A first;
        private final B second;

        public Tuple(A first, B second) {
            this.first = first;
            this.second = second;
        }

        public A getFirst() {
            return first;
        }

        public B getSecond() {
            return second;
        }
    }
}
