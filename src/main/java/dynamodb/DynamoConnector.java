package dynamodb;

import java.io.Serializable;
import java.util.Map;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.net.URI;

/**
 * Base class for DynamoDB connectors. Holds shared logic to build an
 * authenticated {@link DynamoDbClient} from the user supplied options.
 */
public abstract class DynamoConnector implements Connector, Serializable {

    private static final long serialVersionUID = 1L;

    protected final Map<String, String> properties;

    protected DynamoConnector(Map<String, String> parameters) {
        this.properties = parameters;
    }

    /**
     * Build a {@link DynamoDbClient}. The returned client is NOT cached so
     * callers are free to use it from any thread/executor.
     */
    protected DynamoDbClient getDynamoDB(String region, String roleArn, String providerClassName) {
        String chosenRegion = (region != null && !region.isEmpty())
                ? region
                : properties.getOrDefault("region", "us-east-1");

        AwsCredentialsProvider credentialsProvider;
        String endpoint = properties.getOrDefault("endpoint", "");
        if (endpoint.contains("localhost") || endpoint.contains("127.0.0.1") || endpoint.contains("dynamodb:8000")) {
            // Local DynamoDB does not validate signatures — use placeholder credentials.
            credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        } else {
            credentialsProvider = getCredentials(chosenRegion, roleArn, providerClassName);
        }

        DynamoDbClientBuilder clientBuilder = DynamoDbClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(chosenRegion));

        if (endpoint != null && !endpoint.isEmpty()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
        }

        return clientBuilder.build();
    }

    /**
     * Resolve credentials, honouring (in order): an explicit provider class,
     * an assumed role ARN, or the default credentials provider chain.
     */
    private AwsCredentialsProvider getCredentials(String region, String roleArn, String providerClassName) {
        if (providerClassName != null && !providerClassName.isEmpty()) {
            try {
                return (AwsCredentialsProvider) Class.forName(providerClassName)
                        .getDeclaredConstructor().newInstance();
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
            return DefaultCredentialsProvider.create();
        }
    }

    public abstract int getItemLimit();

    @Override
    public boolean isEmpty() {
        return getItemLimit() == 0;
    }

    @Override
    public boolean nonEmpty() {
        return !isEmpty();
    }
}
