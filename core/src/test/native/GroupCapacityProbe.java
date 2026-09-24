import dev.mx3.nomessages.core.messaging.Envelope;
import dev.mx3.nomessages.core.messaging.EnvelopeCodec;
import dev.mx3.nomessages.core.nativebridge.MlsKind;
import dev.mx3.nomessages.core.nativebridge.MlsNative;
import dev.mx3.nomessages.core.nativebridge.MlsResult;
import dev.mx3.nomessages.core.protocol.PairEvidence;
import dev.mx3.nomessages.core.protocol.PairStatement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Actual maximum-size MLS group and serialized invitation sizing; no network or user data. */
class GroupCapacityProbe {
    private static final List<MlsResult> owned = new ArrayList<>();

    private static MlsResult keep(MlsResult result) {
        owned.add(result);
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        byte[] seed = new byte[32];
        byte[] plaintext = "Maximum-size MLS group".getBytes(StandardCharsets.UTF_8);
        try {
            MlsNative mls = MlsNative.INSTANCE;
            List<MlsResult> packages = new ArrayList<>();
            List<byte[]> identities = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                seed[0] = (byte) i;
                seed[31] = 73;
                MlsResult key = keep(mls.keyPackage(seed));
                packages.add(key);
                identities.add(key.getMembers().get(0).getIdentity());
            }
            MlsResult created = keep(mls.create(packages.get(0).getState()));
            MlsResult added = keep(mls.add(created.getState(),
                packages.subList(1, 100).stream().map(MlsResult::getMessage).toList(), identities));
            require(added.getMembers().size() == 100, "Maximum-size membership mismatch");
            MlsResult joined = keep(mls.join(packages.get(99).getState(), added.getWelcome(), identities, identities.get(0)));
            MlsResult sent = keep(mls.encrypt(added.getState(), plaintext));
            MlsResult received = keep(mls.process(joined.getState(), sent.getMessage(), identities, identities.get(0)));
            require(received.getKind() == MlsKind.APPLICATION, "Application message rejected");
            require(Arrays.equals(plaintext, received.getApplication()), "Hundredth member cannot decrypt");

            List<String> members = identities.stream().map(HexFormat.of()::formatHex).sorted().toList();
            List<byte[]> proofs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                for (int j = i + 1; j < 100; j++) {
                    // Fixed-length synthetic signatures measure encoding only; this is not a clique-authentication test.
                    proofs.add(new PairEvidence(new PairStatement(members.get(i), members.get(j), new byte[32], 1),
                        new byte[64], new byte[64]).encode());
                }
            }
            Envelope.GroupInvite invite = new Envelope.GroupInvite(
                "00000000-0000-4000-8000-000000000001", 1,
                "00000000-0000-4000-8000-000000000002", "G".repeat(128),
                HexFormat.of().formatHex(identities.get(0)), members, proofs, added.getWelcome(),
                "00000000-0000-4000-8000-000000000003");
            byte[] encoded = EnvelopeCodec.INSTANCE.encode(invite);
            long proofBytes = proofs.stream().mapToLong(value -> value.length).sum();
            long minimumStorage = 99L * encoded.length + proofBytes + added.getState().length;
            System.out.printf("PASS: 100 real MLS members; member 100 joined and decrypted. Welcome=%d bytes; state=%d bytes.%n",
                added.getWelcome().length, added.getState().length);
            System.out.printf("Invitations: 4950 proofs; %d bytes each; 99 queued invites + graph + MLS state need at least %d bytes (%.2f MiB), before Signal/SQL overhead.%n",
                encoded.length, minimumStorage, minimumStorage / (1024.0 * 1024.0));
            Arrays.fill(encoded, (byte) 0);
        } finally {
            owned.forEach(MlsResult::wipe);
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
