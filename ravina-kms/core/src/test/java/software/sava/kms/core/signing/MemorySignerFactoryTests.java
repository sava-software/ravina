package software.sava.kms.core.signing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class MemorySignerFactoryTests {

  private static String EXPECTED_PUB_KEY;
  private static String BASE58_PRIVATE_KEY;

  @BeforeAll
  static void setup() {
    final byte[] keyPair = Signer.generatePrivateKeyPairBytes();
    final byte[] privateKey = java.util.Arrays.copyOfRange(keyPair, 0, Signer.KEY_LENGTH);
    final var signer = Signer.createFromKeyPair(keyPair);
    EXPECTED_PUB_KEY = signer.publicKey().toBase58();
    BASE58_PRIVATE_KEY = Base58.encode(privateKey);
  }

  // --- MemorySignerFactory JSON tests ---

  @Test
  void testMemorySignerFromJson() {
    final var json = String.format("""
        {"encoding":"base58PrivateKey","secret":"%s"}""", BASE58_PRIVATE_KEY);
    final var factory = new MemorySignerFactory();
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    final var service = factory.createService(ji);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  @Test
  void testMemorySignerFromJsonWithExecutorAndBackoff() {
    final var json = String.format("""
        {"encoding":"base58PrivateKey","secret":"%s"}""", BASE58_PRIVATE_KEY);
    final var factory = new MemorySignerFactory();
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    final var service = factory.createService(null, null, ji);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
    assertNull(service.capacityMonitor());
  }

  // --- MemorySignerFactory Properties tests ---

  @Test
  void testMemorySignerFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base58PrivateKey");
    props.setProperty("secret", BASE58_PRIVATE_KEY);
    final var factory = new MemorySignerFactory();
    final var service = factory.createService(props);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  @Test
  void testMemorySignerFromPropertiesWithPrefix() {
    final var props = new Properties();
    props.setProperty("signer.encoding", "base58PrivateKey");
    props.setProperty("signer.secret", BASE58_PRIVATE_KEY);
    final var factory = new MemorySignerFactory();
    final var service = factory.createService(null, null, "signer", props);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  @Test
  void testMemorySignerFromPropertiesWithDottedPrefix() {
    final var props = new Properties();
    props.setProperty("my.signer.encoding", "base58PrivateKey");
    props.setProperty("my.signer.secret", BASE58_PRIVATE_KEY);
    final var factory = new MemorySignerFactory();
    final var service = factory.createService(null, null, "my.signer.", props);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  // --- MemorySignerFromFilePointerFactory JSON tests ---

  @Test
  void testFilePointerFromJsonWithJsonFile(@TempDir Path tempDir) throws IOException {
    final var json = String.format("""
        {"encoding":"base58PrivateKey","secret":"%s"}""", BASE58_PRIVATE_KEY);
    final var keyFile = tempDir.resolve("key.json");
    Files.writeString(keyFile, json);

    final var factoryJson = String.format("""
        {"filePath":"%s"}""", keyFile.toString().replace("\\", "\\\\"));
    final var factory = new MemorySignerFromFilePointerFactory();
    final var ji = JsonIterator.parse(factoryJson.getBytes(StandardCharsets.UTF_8));
    final var service = factory.createService(ji);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  @Test
  void testFilePointerFromJsonWithPropertiesFile(@TempDir Path tempDir) throws IOException {
    final var content = String.format("""
        encoding=base58PrivateKey
        secret=%s""", BASE58_PRIVATE_KEY);
    final var keyFile = tempDir.resolve("key.properties");
    Files.writeString(keyFile, content);

    final var factoryJson = String.format("""
        {"filePath":"%s"}""", keyFile.toString().replace("\\", "\\\\"));
    final var factory = new MemorySignerFromFilePointerFactory();
    final var ji = JsonIterator.parse(factoryJson.getBytes(StandardCharsets.UTF_8));
    final var service = factory.createService(ji);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  // --- MemorySignerFromFilePointerFactory Properties tests ---

  @Test
  void testFilePointerFromProperties(@TempDir Path tempDir) throws IOException {
    final var json = String.format("""
        {"encoding":"base58PrivateKey","secret":"%s"}""", BASE58_PRIVATE_KEY);
    final var keyFile = tempDir.resolve("key.json");
    Files.writeString(keyFile, json);

    final var props = new Properties();
    props.setProperty("filePath", keyFile.toString());
    final var factory = new MemorySignerFromFilePointerFactory();
    final var service = factory.createService(props);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  @Test
  void testFilePointerFromPropertiesWithPrefix(@TempDir Path tempDir) throws IOException {
    final var content = String.format("""
        encoding=base58PrivateKey
        secret=%s""", BASE58_PRIVATE_KEY);
    final var keyFile = tempDir.resolve("key.properties");
    Files.writeString(keyFile, content);

    final var props = new Properties();
    props.setProperty("signer.filePath", keyFile.toString());
    final var factory = new MemorySignerFromFilePointerFactory();
    final var service = factory.createService(null, null, "signer", props);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  @Test
  void testFilePointerFromPropertiesWithDottedPrefix(@TempDir Path tempDir) throws IOException {
    final var json = String.format("""
        {"encoding":"base58PrivateKey","secret":"%s"}""", BASE58_PRIVATE_KEY);
    final var keyFile = tempDir.resolve("key.json");
    Files.writeString(keyFile, json);

    final var props = new Properties();
    props.setProperty("my.signer.filePath", keyFile.toString());
    final var factory = new MemorySignerFromFilePointerFactory();
    final var service = factory.createService(null, null, "my.signer.", props);
    final var publicKey = service.publicKey().join();
    assertEquals(EXPECTED_PUB_KEY, publicKey.toBase58());
  }

  @Test
  void testFilePointerMissingFilePathFromProperties() {
    final var props = new Properties();
    final var factory = new MemorySignerFromFilePointerFactory();
    assertThrows(IllegalStateException.class, () -> factory.createService(props));
  }

  @Test
  void testFilePointerUnsupportedExtension(@TempDir Path tempDir) throws IOException {
    final var keyFile = tempDir.resolve("key.txt");
    Files.writeString(keyFile, "some content");

    final var props = new Properties();
    props.setProperty("filePath", keyFile.toString());
    final var factory = new MemorySignerFromFilePointerFactory();
    assertThrows(IllegalArgumentException.class, () -> factory.createService(props));
  }
}
