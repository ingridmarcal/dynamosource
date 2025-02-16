package dynamodb;

import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

import java.util.List;
import java.util.Optional;

public class KeySchema {
    private final String hashKeyName;
    private final Optional<String> rangeKeyName;

    public KeySchema(String hashKeyName, Optional<String> rangeKeyName) {
        this.hashKeyName = hashKeyName;
        this.rangeKeyName = rangeKeyName;
    }

    public String getHashKeyName() {
        return hashKeyName;
    }

    public Optional<String> getRangeKeyName() {
        return rangeKeyName;
    }

    public static KeySchema fromDescription(List<KeySchemaElement> keySchemaElements) {
        String hashKeyName = keySchemaElements.stream()
                .filter(k -> k.keyType() == KeyType.HASH)
                .map(KeySchemaElement::attributeName)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No HASH key found in key schema"));

        Optional<String> rangeKeyName = keySchemaElements.stream()
                .filter(k -> k.keyType() == KeyType.RANGE)
                .map(KeySchemaElement::attributeName)
                .findFirst();

        return new KeySchema(hashKeyName, rangeKeyName);
    }
}
