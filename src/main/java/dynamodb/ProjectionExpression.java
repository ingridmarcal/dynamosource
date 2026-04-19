package dynamodb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a DynamoDB {@code ProjectionExpression} together with a matching
 * {@code ExpressionAttributeNames} map so that reserved words and attributes
 * containing special characters are safe to reference by placeholders
 * (e.g. {@code "#p0, #p1"}).
 */
final class ProjectionExpression {

    final String expression;
    final Map<String, String> names;

    private ProjectionExpression(String expression, Map<String, String> names) {
        this.expression = expression;
        this.names = names;
    }

    static ProjectionExpression of(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return new ProjectionExpression(null, Collections.emptyMap());
        }
        Map<String, String> names = new LinkedHashMap<>();
        StringBuilder expr = new StringBuilder();
        int idx = 0;
        for (String column : columns) {
            if (column == null || column.isEmpty()) continue;
            String placeholder = "#p" + idx++;
            names.put(placeholder, column);
            if (expr.length() > 0) expr.append(", ");
            expr.append(placeholder);
        }
        if (expr.length() == 0) {
            return new ProjectionExpression(null, Collections.emptyMap());
        }
        return new ProjectionExpression(expr.toString(), names);
    }
}
