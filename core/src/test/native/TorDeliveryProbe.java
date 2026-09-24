import dev.mx3.nomessages.core.nativebridge.TorFrame;
import dev.mx3.nomessages.core.nativebridge.TorNative;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;

/** Explicit live transport probe. Uses a fresh identity and sends only random bytes to itself. */
class TorDeliveryProbe {
    private static final long started = System.nanoTime();

    private static void log(String message) {
        System.out.printf("%7.1fs %s%n", (System.nanoTime() - started) / 1_000_000_000.0, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        require("1".equals(System.getenv("NOMESSAGES_LIVE_TOR")), "Set NOMESSAGES_LIVE_TOR=1 to allow live Tor traffic");
        byte[] seed = new byte[32];
        byte[] payload = new byte[16_388];
        byte[] reply = new byte[127];
        SecureRandom random = new SecureRandom();
        random.nextBytes(seed);
        random.nextBytes(payload);
        random.nextBytes(reply);
        Path root = Files.createTempDirectory("nomessages-tor-delivery-");
        TorNative tor = TorNative.INSTANCE;
        TorFrame incoming = null;
        TorFrame acknowledged = null;
        try {
            String expected = tor.address(seed);
            log("Starting fresh self-addressed onion service");
            String onion = tor.start(seed, root.resolve("state").toString(), root.resolve("cache").toString(), new String[0]);
            Arrays.fill(seed, (byte) 0);
            require(onion.equals(expected), "JNI onion identity mismatch");
            require(tor.status().equals("PUBLISHING"), "Launch must not claim readiness");
            log("Bootstrap complete; waiting for the onion descriptor to be published");
            // No send before the service reports itself reachable: a blind wait only measured how
            // patient the probe was, not whether the descriptor had actually been uploaded.
            long publishing = System.nanoTime();
            String reached = tor.awaitReady(TorNative.MAX_READY_TIMEOUT_MILLIS);
            require(reached.equals("READY"), "Onion descriptor was not published within 300s");
            require(tor.status().equals("READY"), "Readiness wait returned an inconsistent status");
            log(String.format("Descriptor published after %.1fs", (System.nanoTime() - publishing) / 1_000_000_000.0));
            long firstFrame = System.nanoTime();
            long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
            long connection;
            int attempts = 0;
            for (;;) {
                log("Connecting to own service, attempt " + ++attempts);
                try {
                    connection = tor.send(onion, payload);
                    break;
                } catch (IllegalStateException failure) {
                    log("Attempt did not connect (" + failure.getClass().getSimpleName() + ")");
                    if (System.nanoTime() >= deadline) throw failure;
                    Thread.sleep(3_000);
                }
            }
            log(String.format("Time to first frame after publication: %.1fs", (System.nanoTime() - firstFrame) / 1_000_000_000.0));
            log("Full-size frame sent; waiting for service receive");
            incoming = tor.poll(30_000);
            require(incoming != null && incoming.getConnectionId() != connection, "Missing service-side stream");
            require(Arrays.equals(payload, incoming.getPayload()), "Transport payload mismatch");
            tor.reply(incoming.getConnectionId(), reply);
            acknowledged = tor.poll(30_000);
            require(acknowledged != null && acknowledged.getConnectionId() == connection, "Missing same-stream reply");
            require(Arrays.equals(reply, acknowledged.getPayload()), "Transport reply mismatch");
            require(!Files.exists(root.resolve("state/keystore")), "Unexpected persistent onion keystore");
            log("Bidirectional transport verified through Kotlin/JNI");
        } finally {
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(reply, (byte) 0);
            if (incoming != null) Arrays.fill(incoming.getPayload(), (byte) 0);
            if (acknowledged != null) Arrays.fill(acknowledged.getPayload(), (byte) 0);
            tor.stop();
            require(tor.status().equals("STOPPED"), "Transport did not enter stopped state");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            log("Transport stopped; temporary state removed");
        }
        log("PASS live self-delivery and reply; device socket accounting and messaging remain separate gates");
    }
}
