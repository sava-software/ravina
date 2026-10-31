package software.sava.kms.google;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// The KMS client's class path under sava-build's `gcp-kms` convention, which excludes the
/// DirectPath bundle, the in-process channel, conscrypt, the Apache HTTP transport and three
/// annotation jars from the client's dependencies (see [GoogleKMSClientFactory]). Creating a client links and verifies the
/// generated client, settings and stub classes and runs the ordinary, non-DirectPath branch of
/// gax's `InstantiatingGrpcChannelProvider.createSingleChannel`; the channel is lazy, so nothing
/// connects. A `NoClassDefFoundError` here means an exclusion took a jar the client loads.
final class KmsClientClassPathTests {

  @Test
  void clientCreatesWithoutTheExcludedJars() throws Exception {
    final var settings = KeyManagementServiceSettings.newBuilder()
        .setCredentialsProvider(NoCredentialsProvider.create())
        .setEndpoint("localhost:1")
        .build();
    try (final var client = KeyManagementServiceClient.create(settings)) {
      assertNotNull(client.getSettings());
    }
  }
}
