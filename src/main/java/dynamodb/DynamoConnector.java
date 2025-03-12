package dynamodb;

import java.io.Serializable;
import java.util.*;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import java.net.URI;
import org.apache.spark.sql.sources.Filter;

public abstract class DynamoConnector implements Connector, Serializable {

    protected final Map<String, String> properties;

    // Constructor to initialize properties map
    protected DynamoConnector(Map<String, String> parameters) {
        this.properties = parameters;
    }

    protected DynamoDbClient getDynamoDB(String region, String roleArn, String providerClassName) {
        String chosenRegion = (region != null && !region.isEmpty()) ? region : properties.getOrDefault("region", "us-east-1");
        return getDynamoDBClient(chosenRegion, roleArn, providerClassName);
    }

    private DynamoDbClient getDynamoDBClient(String region, String roleArn, String providerClassName) {
        String chosenRegion = (region != null && !region.isEmpty()) ? region : properties.getOrDefault("region", "us-east-1");

        AwsCredentialsProvider credentialsProvider;

        if (properties.getOrDefault("endpoint", "").contains("localhost")) {
            credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        } else {
            credentialsProvider = getCredentials(chosenRegion, roleArn, providerClassName);
        }

        DynamoDbClientBuilder clientBuilder = DynamoDbClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(chosenRegion));

        String endpoint = properties.get("endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
        }

        return clientBuilder.build();
    }

    private DynamoDbAsyncClient getDynamoDBAsyncClient(String region, String roleArn, String providerClassName) {
        String chosenRegion = (region != null && !region.isEmpty()) ? region : properties.getOrDefault("aws.dynamodb.region", "us-east-1");
        AwsCredentialsProvider credentialsProvider = getCredentials(chosenRegion, roleArn, providerClassName);

        DynamoDbAsyncClientBuilder clientBuilder = DynamoDbAsyncClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(chosenRegion));

        String endpoint = properties.get("aws.dynamodb.endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
        }

        return clientBuilder.build();
    }

    /**
     * Get credentials from an instantiated object of the class name given,
     * a passed-in role ARN, a profile, or return the default credential provider.
     */
    private AwsCredentialsProvider getCredentials(String region, String roleArn, String providerClassName) {
        if (providerClassName != null && !providerClassName.isEmpty()) {
            try {
                return (AwsCredentialsProvider) Class.forName(providerClassName).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to instantiate credentials provider: " + providerClassName, e);
            }
        } else if (roleArn != null && !roleArn.isEmpty()) {
            StsClient stsClient = StsClient.builder()
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .region(Region.of(region))
                    .build();

            AssumeRoleRequest assumeRoleRequest = AssumeRoleRequest.builder()
                    .roleSessionName("DynamoDBAssumed")
                    .roleArn(roleArn)
                    .build();

            AssumeRoleResponse assumeRoleResult = stsClient.assumeRole(assumeRoleRequest);

            AwsSessionCredentials assumeCreds = AwsSessionCredentials.create(
                    assumeRoleResult.credentials().accessKeyId(),
                    assumeRoleResult.credentials().secretAccessKey(),
                    assumeRoleResult.credentials().sessionToken()
            );

            return StaticCredentialsProvider.create(assumeCreds);
        } else {
            AwsCredentialsProvider provider = DefaultCredentialsProvider.create();
            if (provider.resolveCredentials() == null) {
                throw new RuntimeException("No AWS credentials found! Check your ~/.aws/credentials file or environment variables.");
            }
            return provider;
        }
    }

    // Abstract methods to be implemented by subclasses
    public abstract ScanResponse scan(int segmentNum, List<String> columns, Filter[] filters);

    public abstract KeySchema getKeySchema();

    public abstract double getReadLimit();

    public abstract int getItemLimit();

    public abstract int getTotalSegments();

    public abstract boolean isFilterPushdownEnabled();

    public boolean isEmpty() {
        return getItemLimit() == 0;
    }

    public boolean nonEmpty() {
        return !isEmpty();
    }
}
