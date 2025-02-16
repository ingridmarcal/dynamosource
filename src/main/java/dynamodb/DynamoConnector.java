package dynamodb;

import java.util.*;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import java.util.Optional;

import java.net.URI;
import java.util.Optional;
import org.apache.spark.sql.sources.Filter;


public abstract class DynamoConnector implements Connector{

    protected final Map<String, String> properties;

    // Constructor to initialize properties map
    protected DynamoConnector() {
        this.properties = new HashMap<>();
        System.getProperties().forEach((key, value) ->
                this.properties.put(String.valueOf(key), String.valueOf(value))
        );
    }

    protected DynamoDbClient getDynamoDB(Optional<String> region, Optional<String> roleArn, Optional<String> providerClassName) {
        String chosenRegion = region.orElseGet(() -> properties.getOrDefault("aws.dynamodb.region", "us-east-1"));

        return getDynamoDBClient(Optional.ofNullable(chosenRegion), roleArn, providerClassName);
    }

    private DynamoDbClient getDynamoDBClient(Optional<String> region, Optional<String> roleArn, Optional<String> providerClassName) {
        String chosenRegion = region.orElseGet(() -> properties.getOrDefault("aws.dynamodb.region", "us-east-1"));
        AwsCredentialsProvider credentialsProvider = getCredentials(chosenRegion, roleArn, providerClassName);

        DynamoDbClientBuilder clientBuilder = DynamoDbClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(chosenRegion));

        Optional.ofNullable(properties.get("endpoint"))
                .ifPresent(endpoint -> clientBuilder.endpointOverride(URI.create(endpoint)));

        return clientBuilder.build();
    }

    private DynamoDbAsyncClient getDynamoDBAsyncClient(Optional<String> region, Optional<String> roleArn, Optional<String> providerClassName) {
        String chosenRegion = region.orElseGet(() -> properties.getOrDefault("aws.dynamodb.region", "us-east-1"));
        AwsCredentialsProvider credentialsProvider = getCredentials(chosenRegion, roleArn, providerClassName);

        DynamoDbAsyncClientBuilder clientBuilder = DynamoDbAsyncClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(chosenRegion));

        Optional.ofNullable(properties.get("aws.dynamodb.endpoint"))
                .ifPresent(endpoint -> clientBuilder.endpointOverride(URI.create(endpoint)));

        return clientBuilder.build();
    }

    /**
     * Get credentials from an instantiated object of the class name given,
     * a passed-in role ARN, a profile, or return the default credential provider.
     */
    private AwsCredentialsProvider getCredentials(String chosenRegion, Optional<String> roleArn, Optional<String> providerClassName) {
        return providerClassName.map(providerClass -> {
            try {
                return (AwsCredentialsProvider) Class.forName(providerClass).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to instantiate credentials provider: " + providerClass, e);
            }
        }).orElseGet(() -> {
            if (roleArn.isPresent()) {
                StsClient stsClient = StsClient.builder()
                        .credentialsProvider(DefaultCredentialsProvider.create()) // ✅ IAM Role for Glue Job
                        .region(Region.of(chosenRegion))
                        .build();

                AssumeRoleRequest assumeRoleRequest = AssumeRoleRequest.builder()
                        .roleSessionName("DynamoDBAssumed")
                        .roleArn(roleArn.get())
                        .build();

                AssumeRoleResponse assumeRoleResult = stsClient.assumeRole(assumeRoleRequest);

                AwsSessionCredentials assumeCreds = AwsSessionCredentials.create(
                        assumeRoleResult.credentials().accessKeyId(),
                        assumeRoleResult.credentials().secretAccessKey(),
                        assumeRoleResult.credentials().sessionToken()
                );

                return StaticCredentialsProvider.create(assumeCreds);
            } else {
                // ✅ Support Glue Interactive Sessions & Local Development
                return ProfileCredentialsProvider.create();
            }
        });
    }



    public abstract ScanResponse scan(int segmentNum, List<String> columns, List<Filter> filters);

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
