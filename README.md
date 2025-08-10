# DynamoSource

This project provides a Spark DataSource for Amazon DynamoDB.

## Index queries and schema inference

When querying DynamoDB tables by secondary index the connector does not attempt to infer the schema.
Users must provide the desired schema via the `userSchema` option to avoid runtime errors.

```scala
spark.read
  .format("dynamodb")
  .option("tablename", "MyTable")
  .option("indexname", "MyIndex")
  .schema(userSchema)
  .load()
```

Without a user supplied schema, reading from a secondary index will fail during initialization.
