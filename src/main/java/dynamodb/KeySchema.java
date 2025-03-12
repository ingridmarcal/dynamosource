package dynamodb;

import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

import java.io.Serializable;
import java.util.List;

public class KeySchema implements Serializable {
    private final String hashKeyName;
    private final String rangeKeyName;  // Removed Optional

    public KeySchema(String hashKeyName, String rangeKeyName) {
        this.hashKeyName = hashKeyName;
        this.rangeKeyName = rangeKeyName;  // Can be null if not provided
    }

    public String getHashKeyName() {
        return hashKeyName;
    }

    public String getRangeKeyName() {
        return rangeKeyName;  // Caller should check for null if needed
    }

    public boolean hasRangeKey() {
        return rangeKeyName != null && !rangeKeyName.isEmpty();
    }

    public static KeySchema fromDescription(List<KeySchemaElement> keySchemaElements) {
        String hashKeyName = keySchemaElements.stream()
                .filter(k -> k.keyType() == KeyType.HASH)
                .map(KeySchemaElement::attributeName)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No HASH key found in key schema"));

        String rangeKeyName = keySchemaElements.stream()
                .filter(k -> k.keyType() == KeyType.RANGE)
                .map(KeySchemaElement::attributeName)
                .findFirst()
                .orElse(null);  // If no RANGE key, return null

        return new KeySchema(hashKeyName, rangeKeyName);
    }
}
