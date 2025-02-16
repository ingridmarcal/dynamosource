package dynamodb;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.util.ArrayBasedMapData;
import org.apache.spark.sql.catalyst.util.GenericArrayData;
import org.apache.spark.sql.types.*;
import org.apache.spark.unsafe.types.UTF8String;
import scala.collection.JavaConverters;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TypeConversion {

    public static Function<Map<String, AttributeValue>, Object> apply(String attrName, DataType sparkType) {
        switch (sparkType.typeName()) {
            case "boolean":
                return nullableGet(item -> item.get(attrName).bool());
            case "string":
                return nullableGet(item -> UTF8String.fromString(item.get(attrName).s()));
            case "integer":
                return nullableGet(item -> Integer.parseInt(item.get(attrName).n()));
            case "long":
                return nullableGet(item -> Long.parseLong(item.get(attrName).n()));
            case "double":
                return nullableGet(item -> Double.parseDouble(item.get(attrName).n()));
            case "float":
                return nullableGet(item -> Float.parseFloat(item.get(attrName).n()));
            case "binary":
                return nullableGet(item -> item.get(attrName).b().asByteArray());
            case "decimal":
                return nullableGet(item -> new BigDecimal(item.get(attrName).n()));
            case "array":
                return nullableGet(item -> extractArray(convertValue(((ArrayType) sparkType).elementType()), item.get(attrName)));
            case "map":
                if (!((MapType) sparkType).keyType().sameType(DataTypes.StringType)) {
                    throw new IllegalArgumentException("Invalid Map key type. DynamoDB only supports String as Map key type.");
                }
                return nullableGet(item -> extractMap(convertValue(((MapType) sparkType).valueType()), item.get(attrName)));
            case "struct":
                StructType structType = (StructType) sparkType;
                Map<String, Function<AttributeValue, Object>> nestedConversions = Arrays.stream(structType.fields())
                        .collect(Collectors.toMap(StructField::name, field -> convertValue(field.dataType())));
                return nullableGet(item -> extractStruct(nestedConversions, item.get(attrName)));
            default:
                throw new IllegalArgumentException("Spark DataType '" + sparkType.typeName() + "' could not be mapped to a corresponding DynamoDB data type.");
        }
    }

    private static Function<AttributeValue, Object> convertValue(DataType sparkType) {
        switch (sparkType.typeName()) {
            case "integer":
                return attr -> attr.n() == null ? null : Integer.parseInt(attr.n());
            case "long":
                return attr -> attr.n() == null ? null : Long.parseLong(attr.n());
            case "double":
                return attr -> attr.n() == null ? null : Double.parseDouble(attr.n());
            case "float":
                return attr -> attr.n() == null ? null : Float.parseFloat(attr.n());
            case "decimal":
                return attr -> attr.n() == null ? null : new BigDecimal(attr.n());
            case "array":
                return attr -> extractArray(convertValue(((ArrayType) sparkType).elementType()), attr);
            case "map":
                if (!((MapType) sparkType).keyType().sameType(DataTypes.StringType)) {
                    throw new IllegalArgumentException("Invalid Map key type. DynamoDB only supports String as Map key type.");
                }
                return attr -> extractMap(convertValue(((MapType) sparkType).valueType()), attr);
            case "struct":
                StructType structType = (StructType) sparkType;
                Map<String, Function<AttributeValue, Object>> nestedConversions = Arrays.stream(structType.fields())
                        .collect(Collectors.toMap(StructField::name, field -> convertValue(field.dataType())));
                return attr -> extractStruct(nestedConversions, attr);
            case "boolean":
                return attr -> attr.bool();
            case "string":
                return attr -> UTF8String.fromString(attr.s());
            case "binary":
                return attr -> attr.b().asByteArray();
            default:
                throw new IllegalArgumentException("Spark DataType '" + sparkType.typeName() + "' could not be mapped to a corresponding DynamoDB data type.");
        }
    }

    private static Function<Map<String, AttributeValue>, Object> nullableGet(Function<Map<String, AttributeValue>, Object> getter) {
        return item -> {
            try {
                return getter.apply(item);
            } catch (Exception e) {
                return null;
            }
        };
    }

    private static Object extractArray(Function<AttributeValue, Object> converter, AttributeValue attr) {
        if (attr.l() != null) {
            return new GenericArrayData(attr.l().stream().map(converter).collect(Collectors.toList()));
        }
        if (attr.ss() != null) {
            return new GenericArrayData(attr.ss().stream().map(UTF8String::fromString).collect(Collectors.toList()));
        }
        return null;
    }


    private static Object extractMap(Function<AttributeValue, Object> converter, AttributeValue attr) {
        if (attr.m() != null) {
            Map<String, AttributeValue> map = attr.m();

            // ✅ Extract keys and convert to Spark UTF8String
            Object[] keyArray = map.keySet().stream()
                    .map(UTF8String::fromString)
                    .toArray();

            // ✅ Extract values using the converter function
            Object[] valueArray = map.values().stream()
                    .map(converter)
                    .toArray();

            // ✅ Create an `ArrayBasedMapData` using `ArrayData`
            return new ArrayBasedMapData(new GenericArrayData(keyArray), new GenericArrayData(valueArray));
        }
        return null;
    }

    /** Extracts the actual value from AttributeValue as a String */
    private static String extractStringValue(AttributeValue attr) {
        if (attr.s() != null) return attr.s();
        if (attr.n() != null) return attr.n();
        if (attr.bool() != null) return String.valueOf(attr.bool());
        if (attr.l() != null) return attr.l().toString();
        if (attr.m() != null) return attr.m().toString();
        return "";
    }





    private static Object extractStruct(Map<String, Function<AttributeValue, Object>> conversions, AttributeValue attr) {
        if (attr.m() != null) {
            Map<String, AttributeValue> map = attr.m();
            return InternalRow.fromSeq(
                    JavaConverters.asScalaBuffer(
                            conversions.entrySet().stream()
                                    .map(entry -> entry.getValue().apply(map.get(entry.getKey())))
                                    .collect(Collectors.toList())
                    ).toSeq() // ✅ Convert List<Object> to Seq<Object>
            );
        }
        return null;
    }
}

