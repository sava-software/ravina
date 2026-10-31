package software.sava.kms.google;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.kms.v1.LocationName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// The two runtime paths of the KMS client that run next to jars sava-build's `gcp-kms`
/// convention excludes (see [GoogleKMSClientFactory]), driven over the network: the TLS
/// handshake with the public endpoint (grpc-netty-shaded's ALPN path, where conscrypt is an
/// optional provider) and the auth library's token fetch over `NetHttpTransport` (where the
/// Apache transport used to sit). Both calls are expected to reach Google's servers and be
/// refused there: the first completes the TLS handshake and is refused as unauthenticated, the
/// second by the token endpoint's own answer. A `NoClassDefFoundError` means an exclusion took a
/// jar the path loads, and an offline failure (`UNAVAILABLE`, a connection error, a proxy's
/// refusal) fails the assertion rather than passing it.
///
/// Tagged `live`: `test`, `check`, CI and PIT never run this class (the tag is excluded from the
/// `test` task and the class from the `googleKms` suite's selection in the module's build script).
/// Run it on purpose with `./gradlew :ravina-kms-google:liveTest`.
@Tag("live")
final class KmsLivePathTests {

  @Test
  void unauthenticatedCallOverTlsIsRefusedByTheServer() throws Exception {
    final var settings = KeyManagementServiceSettings.newBuilder()
        .setCredentialsProvider(NoCredentialsProvider.create())
        .build();
    try (final var client = KeyManagementServiceClient.create(settings)) {
      client.listKeyRings(LocationName.of("sava-trial-project", "global")).iterateAll().iterator().hasNext();
      fail("the server accepted an unauthenticated call");
    } catch (final NoClassDefFoundError e) {
      throw e;
    } catch (final Exception e) {
      final var text = e.toString() + (e.getCause() == null ? "" : " / " + e.getCause());
      assertTrue(text.contains("UNAUTHENTICATED") || text.contains("PERMISSION_DENIED"),
          "expected the server's refusal, got: " + text);
    }
  }

  @Test
  void tokenFetchOverTheHttpTransportIsRefusedByTheTokenEndpoint() throws Exception {
    final var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    final PrivateKey privateKey = generator.generateKeyPair().getPrivate();
    final var credentials = ServiceAccountCredentials.newBuilder()
        .setClientEmail("trial@sava-trial-project.iam.gserviceaccount.com")
        .setClientId("trial")
        .setPrivateKey(privateKey)
        .setPrivateKeyId("trial-key")
        .setScopes(List.of("https://www.googleapis.com/auth/cloudkms"))
        .build();
    try {
      credentials.refreshAccessToken();
      fail("the token endpoint accepted a made-up key");
    } catch (final NoClassDefFoundError e) {
      throw e;
    } catch (final IOException e) {
      final var text = e.toString() + (e.getCause() == null ? "" : " / " + e.getCause());
      // Only the endpoint's JSON body says invalid_grant or invalid_client: a bare status code
      // could also be a proxy's refusal of the tunnel, which never reached Google.
      assertTrue(text.contains("invalid_grant") || text.contains("invalid_client"),
          "expected the token endpoint's refusal, got: " + text);
    }
  }
}
