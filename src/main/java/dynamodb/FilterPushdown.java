package dynamodb;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import org.apache.spark.sql.sources.*;

import java.util.*;
import java.util.stream.Collectors;

public class FilterPushdown {

    public static Result apply(Filter[] filters) {
        PlaceholderContext context = new PlaceholderContext();
        String expression = Arrays.stream(filters)
                .map(f -> buildCondition(f, context))
                .map(FilterPushdown::parenthesize)
                .collect(Collectors.joining(" AND "));
        return new Result(expression, context.getExpressionAttributeNames(), context.getExpressionAttributeValues());
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

    private static boolean checkFilter(Filter filter) {
        if (filter instanceof StringEndsWith) return false;
        if (filter instanceof And) {
            return checkFilter(((And) filter).left()) && checkFilter(((And) filter).right());
        }
        if (filter instanceof Or) {
            return checkFilter(((Or) filter).left()) && checkFilter(((Or) filter).right());
        }
        if (filter instanceof Not) {
            return checkFilter(((Not) filter).child());
        }
        return true;
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
        if (value instanceof String) {
            return AttributeValue.builder().s((String) value).build();
        }
        if (value instanceof Number) {
            return AttributeValue.builder().n(value.toString()).build();
        }
        if (value instanceof Boolean) {
            return AttributeValue.builder().bool((Boolean) value).build();
        }
        throw new IllegalArgumentException("Unsupported value type for DynamoDB filter: " + value.getClass().getSimpleName());
    }

    private static String parenthesize(String condition) {
        return "(" + condition + ")";
    }

    private static class PlaceholderContext {
        private final Map<String, String> expressionAttributeNames = new LinkedHashMap<>();
        private final Map<String, AttributeValue> expressionAttributeValues = new LinkedHashMap<>();
        private int nameCounter = 0;
        private int valueCounter = 0;

        String addAttributeName(String actualName) {
            String placeholder = "#n" + nameCounter++;
            expressionAttributeNames.put(placeholder, actualName);
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
