import dev.mx3.nomessages.core.nativebridge.MlsKind;
import dev.mx3.nomessages.core.nativebridge.MlsNative;
import dev.mx3.nomessages.core.nativebridge.MlsResult;
import dev.mx3.nomessages.core.nativebridge.TorNative;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Standalone host integration probe: exercises Kotlin codecs and real JNI, without sockets. */
class NativeBridgeProbe {
    private static final List<MlsResult> owned = new ArrayList<>();

    private static MlsResult keep(MlsResult result) {
        owned.add(result);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        byte[] seed = new byte[32];
        byte[] plaintext = "Kotlin JNI boundary".getBytes(StandardCharsets.UTF_8);
        try {
            MlsNative mls = MlsNative.INSTANCE;
            MlsResult a = keep(mls.keyPackage(seed));
            Arrays.fill(seed, (byte) 1);
            MlsResult b = keep(mls.keyPackage(seed));
            Arrays.fill(seed, (byte) 2);
            MlsResult c = keep(mls.keyPackage(seed));
            byte[] alice = a.getMembers().get(0).getIdentity();
            List<byte[]> members = List.of(alice, b.getMembers().get(0).getIdentity(), c.getMembers().get(0).getIdentity());
            MlsResult group = keep(mls.create(a.getState()));
            MlsResult added = keep(mls.add(group.getState(), List.of(b.getMessage(), c.getMessage()), members));
            require(added.getKind() == MlsKind.COMMIT && added.getMembers().size() == 3, "Invalid decoded membership");
            MlsResult joined = keep(mls.join(b.getState(), added.getWelcome(), members, alice));
            MlsResult sent = keep(mls.encrypt(added.getState(), plaintext));
            MlsResult received = keep(mls.process(joined.getState(), sent.getMessage(), members, alice));
            require(received.getKind() == MlsKind.APPLICATION, "Invalid decoded message kind");
            require(Arrays.equals(plaintext, received.getApplication()), "JNI plaintext mismatch");
            require(Arrays.equals(alice, received.getSenderIdentity()), "JNI sender mismatch");
            require(Arrays.equals(added.getGroupId(), received.getGroupId()), "JNI group mismatch");
            try {
                mls.encrypt(new byte[] {1}, plaintext);
                throw new AssertionError("Invalid native state accepted");
            } catch (IllegalStateException expected) {
                require(!expected.getMessage().contains("Kotlin JNI boundary"), "Native error leaked input");
            }
            String onion = TorNative.INSTANCE.address(seed);
            require(onion.matches("[a-z2-7]{56}\\.onion"), "Invalid JNI onion result");
            require(TorNative.INSTANCE.status().equals("STOPPED"), "Offline probe started transport");
            System.out.println("Native JNI probe passed: Kotlin MLS codecs, group transaction, exception mapping, offline Tor identity.");
        } finally {
            owned.forEach(MlsResult::wipe);
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
