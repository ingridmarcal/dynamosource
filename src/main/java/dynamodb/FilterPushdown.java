package dynamodb;

import software.amazon.awssdk.services.dynamodb.model.Condition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import org.apache.spark.sql.sources.*;
import java.util.*;
import java.util.stream.Collectors;

public class FilterPushdown {

    public static String apply(List<Filter> filters) {
        return filters.stream()
                .map(FilterPushdown::buildCondition)
                .map(FilterPushdown::parenthesize)
                .collect(Collectors.joining(" AND "));
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

    private static String buildCondition(Filter filter) {
        if (filter instanceof EqualTo) {
            return String.format("%s = %s", ((EqualTo) filter).attribute(), formatValue(((EqualTo) filter).value()));
        } else if (filter instanceof GreaterThan) {
            return String.format("%s > %s", ((GreaterThan) filter).attribute(), formatValue(((GreaterThan) filter).value()));
        } else if (filter instanceof GreaterThanOrEqual) {
            return String.format("%s >= %s", ((GreaterThanOrEqual) filter).attribute(), formatValue(((GreaterThanOrEqual) filter).value()));
        } else if (filter instanceof LessThan) {
            return String.format("%s < %s", ((LessThan) filter).attribute(), formatValue(((LessThan) filter).value()));
        } else if (filter instanceof LessThanOrEqual) {
            return String.format("%s <= %s", ((LessThanOrEqual) filter).attribute(), formatValue(((LessThanOrEqual) filter).value()));
        } else if (filter instanceof In) {
            List<Object> values = Arrays.asList(((In) filter).values()); // Convert to List
            String formattedValues = values.stream()
                    .map(FilterPushdown::formatValue)
                    .collect(Collectors.joining(", "));
            return String.format("%s IN (%s)", ((In) filter).attribute(), formattedValues);
        } else if (filter instanceof IsNull) {
            return String.format("attribute_not_exists(%s)", ((IsNull) filter).attribute());
        } else if (filter instanceof IsNotNull) {
            return String.format("attribute_exists(%s)", ((IsNotNull) filter).attribute());
        } else if (filter instanceof StringStartsWith) {
            return String.format("begins_with(%s, %s)", ((StringStartsWith) filter).attribute(), formatValue(((StringStartsWith) filter).value()));
        } else if (filter instanceof StringContains) {
            return String.format("contains(%s, %s)", ((StringContains) filter).attribute(), formatValue(((StringContains) filter).value()));
        } else if (filter instanceof And) {
            return String.format("(%s AND %s)", buildCondition(((And) filter).left()), buildCondition(((And) filter).right()));
        } else if (filter instanceof Or) {
            return String.format("(%s OR %s)", buildCondition(((Or) filter).left()), buildCondition(((Or) filter).right()));
        } else if (filter instanceof Not) {
            return String.format("NOT (%s)", buildCondition(((Not) filter).child()));
        }
        throw new UnsupportedOperationException("Unsupported filter type: " + filter.getClass().getSimpleName());
    }

    private static String formatValue(Object value) {
        if (value instanceof String) return String.format("'%s'", value);
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported value type for DynamoDB filter: " + value.getClass().getSimpleName());
    }

    private static String parenthesize(String condition) {
        return "(" + condition + ")";
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
