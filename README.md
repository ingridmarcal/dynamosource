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

## Index queries and schema inference

When querying DynamoDB tables by secondary index the connector does not attempt to infer the schema. Users must provide the desired schema via the `userSchema` option to avoid runtime errors.

```scala
spark.read
  .format("dynamodb")
  .option("tablename", "MyTable")
  .option("indexname", "MyIndex")
  .schema(userSchema)
  .load()
```

### Example

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
  .show()
```

Without a user supplied schema, reading from a secondary index will fail during initialization.

## Testing

Run unit tests with:

```bash
mvn test
```

