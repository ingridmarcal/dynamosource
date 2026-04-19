# DynamoSource

Spark DataSource for Amazon DynamoDB.

## Prerequisites

- Java 8+
- Maven 3.x
- Docker and Docker Compose

## Build

```bash
mvn package
```

The packaged connector will be available at `target/spark-dynamodb-datasource-1.0-SNAPSHOT.jar`.

## Run locally with Docker Compose

The repository provides a `docker-compose.yml` that starts a local DynamoDB emulator and a Spark container.

1. **Start the environment**

   ```bash
   docker compose up -d
   ```

2. **Build the connector** (if not already built)

   ```bash
   mvn package
   ```

3. **Open a Spark shell** inside the running Spark container and include the connector JAR:

   ```bash
   docker compose exec spark spark-shell \
     --master local[*] \
     --jars /workspace/target/spark-dynamodb-datasource-1.0-SNAPSHOT.jar
   ```

4. **Read from DynamoDB Local**

   ```scala
   spark.read
     .format("dynamodb")
     .option("endpoint", "http://dynamodb:8000")
     .option("region", "us-east-1")
     .option("tablename", "MyTable")
     .load()
   ```

5. **Stop the services**

   ```bash
   docker compose down
   ```

## Filter pushdown and secondary indexes

The connector pushes Spark predicates down to DynamoDB:

- When the pushed-down filters contain an equality on the **hash key** of the
  table (or of the configured secondary index), the connector issues a
  DynamoDB `Query` instead of a full-table `Scan`. An optional predicate on
  the range key (`=`, `<`, `<=`, `>`, `>=`, `begins_with`) is merged into the
  `KeyConditionExpression`.
- Every other supported predicate becomes a `FilterExpression` so that
  unnecessary items never leave DynamoDB.
- Predicates that DynamoDB cannot evaluate (e.g. `StringEndsWith`) are kept
  by Spark as post-scan filters; no correctness is lost.

### Querying a Global Secondary Index with filters

When querying DynamoDB tables by secondary index the connector does not
attempt to infer the schema; users must supply an explicit `schema(...)`.

```scala
import org.apache.spark.sql.types._

val userSchema = StructType(Seq(
  StructField("pk", StringType),
  StructField("sk", StringType),
  StructField("attribute", StringType)
))

spark.read
  .format("dynamodb")
  .option("endpoint", "http://dynamodb:8000")
  .option("region", "us-east-1")
  .option("tablename", "MyTable")
  .option("indexname", "MyIndex")
  .schema(userSchema)
  .load()
  .where($"pk" === "user#42" && $"sk".startsWith("2024-"))
  .show()
```

In the example above the connector issues a single `Query` on `MyIndex`
pinned by the hash-key equality and narrowed by the `begins_with` condition
on the range key — no full scan is performed.

Without a user supplied schema, reading from a secondary index will fail
during initialization.

### Options

| Option                       | Default        | Description                                                                 |
|------------------------------|----------------|-----------------------------------------------------------------------------|
| `tablename`                  | _(required)_   | DynamoDB table name.                                                        |
| `indexname`                  | _(unset)_      | Secondary index name. When set, reads go through the index.                 |
| `region`                     | `us-east-1`    | AWS region.                                                                 |
| `endpoint`                   | _(unset)_      | Override endpoint (e.g. `http://dynamodb:8000` for DynamoDB Local).         |
| `filterpushdown`             | `true`         | Set to `false` to disable pushdown entirely.                                |
| `stronglyconsistentreads`    | `false`        | Use strongly consistent reads.                                              |
| `rolearn`                    | _(unset)_      | IAM role to assume via STS.                                                 |
| `providerclassname`          | _(unset)_      | Fully-qualified `AwsCredentialsProvider` implementation to instantiate.     |
| `defaultparallelism`         | Spark default  | Target parallelism for partition planning.                                  |
| `readpartitions`             | _(auto)_       | Override the number of scan segments.                                       |
| `throughput`                 | _(auto)_       | Override the capacity used for rate-limiting.                               |
| `bytesperrcu`                | `4000`         | Bytes served per RCU (4 KB for eventually consistent, 8 KB for transactional).|
| `maxpartitionbytes`          | `128000000`    | Target bytes per Spark partition when auto-deriving segments.               |
| `targetcapacity`             | `1`            | Fraction of provisioned throughput to consume (0.0–1.0).                    |
| `keyconditionexpression`     | _(unset)_      | Raw `KeyConditionExpression` — advanced / backwards-compat escape hatch.    |
| `expressionAttributeValues`  | _(unset)_      | JSON map of `:placeholder -> AttributeValue` for the raw expression above.  |

## Testing

Run unit tests with:

```bash
mvn test
```

