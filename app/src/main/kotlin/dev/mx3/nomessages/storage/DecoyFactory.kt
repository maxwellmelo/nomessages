package dev.mx3.nomessages.storage

import dev.mx3.nomessages.core.crypto.Crypto
import dev.mx3.nomessages.core.protocol.PairingEngine
import dev.mx3.nomessages.core.protocol.PairingIdentity

/** Builds cryptographically valid, permanently offline synthetic peers for the decoy fixture. */
internal class DecoyFactory(private val crypto: Crypto) {
    fun create(database: ChatDatabase, aliases: List<String>): List<ContactRecord> {
        require(aliases.size in 3..5)
        val localOnion = syntheticOnion()
        val onionSeed = crypto.random(32)
        return PairingIdentity.create(crypto).use { identity ->
            try {
                val contacts = aliases.map { alias -> pair(identity, localOnion, alias, database) }
                val exported = identity.export()
                try {
                    database.putMeta(StorageKeys.IDENTITY, exported)
                    database.putMeta(StorageKeys.ONION_SEED, onionSeed)
                } finally {
                    crypto.wipe(exported)
                }
                contacts
            } finally {
                crypto.wipe(onionSeed)
            }
        }
    }

    private fun pair(
        localIdentity: PairingIdentity,
        localOnion: String,
        alias: String,
        database: ChatDatabase,
    ): ContactRecord = PairingIdentity.create(crypto).use { syntheticPeer ->
        // Decoy doorbell identities are synthesised the same way the decoy onions are - a real
        // Ed25519 public key with the private half discarded - so a decoy contact's doorbell column
        // is indistinguishable from a real one's by inspection. Reserved for T4.17; nothing listens.
        // These two arrays are NOT wiped after the engines are built. `PairingEngine` keeps the
        // array it is handed by reference (`private val doorbellKey:ByteArray`) and only reads it
        // later, inside `createOffer()`. Zeroing it right after construction therefore zeroed the
        // key the offer was about to publish, and `Offer.decode` - which correctly refuses an
        // all-zero doorbell identity - threw `IllegalArgumentException: Invalid doorbell identity
        // key` from `respond()`. That aborted `seedDecoy()`, and with it `VaultManager.create()`:
        // no vault could be created at all on a device (15/19 instrumented cases failed on
        // emulator-5556, 2026-09-17). Wiping buys nothing here in any case: this is a *public*
        // Ed25519 key whose private half `syntheticOnionKey()` already discarded, and it is written
        // to the contact row verbatim a few lines below as `doorbellOnion`.
        val local = PairingEngine(crypto, localIdentity, localOnion, syntheticOnionKey())
        val remote = PairingEngine(crypto, syntheticPeer, syntheticOnion(), syntheticOnionKey())
        val remoteProgress = remote.respond(local.createOffer())
        val localProgress = local.processResponse(checkNotNull(remoteProgress.responseQr))
        check(localProgress.sas == remoteProgress.sas)
        // QR format 2 keeps the key bundle out of the QR, so each side is handed the peer's bundle
        // directly. On a real pairing this is a Tor round trip; here both engines are in-process,
        // and the decoy must still traverse exactly the same verified acceptance path so the
        // resulting session state is byte-shaped like a genuine one.
        local.acceptPeerBundle(localProgress.handle, checkNotNull(remote.bundleFor(localProgress.peerBundleNonce)))
        remote.acceptPeerBundle(remoteProgress.handle, checkNotNull(local.bundleFor(remoteProgress.peerBundleNonce)))
        val localConfirmation = local.confirm(localProgress.handle, true)
        val remoteConfirmation = remote.confirm(remoteProgress.handle, true)
        val contact = local.finish(localProgress.handle, remoteConfirmation)
        remote.finish(remoteProgress.handle, localConfirmation)

        val first = minOf(localIdentity.id, contact.peerId)
        val second = maxOf(localIdentity.id, contact.peerId)
        val pairedAtMillis = Math.multiplyExact(contact.pairedAt, 1000L)
        database.putPairEvidence(PairEvidence(first, second, contact.evidence, pairedAtMillis))
        ContactRecord(
            id = contact.peerId,
            alias = alias,
            onion = contact.onion,
            identityPublic = contact.publicKey,
            signalPeer = localIdentity.sessions.peerIdentity(contact.peerId),
            pairedAt = pairedAtMillis,
            displayOnly = false,
            // Populated, not left empty: an all-blank doorbell column on every decoy contact while
            // real contacts carry one would make the two distinguishable by inspection alone.
            doorbellOnion = OnionAddress.address(contact.doorbellKey),
            doorbellToken = contact.doorbellToken,
            // Schema v4 (T4.17). Nothing extra is synthesised here: the decoy pairing above is a
            // genuine two-engine exchange, so `local` really did mint a token for this synthetic
            // peer and `finish` hands it back exactly as it does on a real pairing. The column is
            // therefore filled by the same code path and with the same kind of value as in the real
            // vault - 32 bytes of `crypto.random`, statistically identical, unrelated to any other
            // contact's - which is the whole point: a column that were populated only in the real
            // vault, or only in the decoy, would be a tell on its own, and the parity has to hold
            // for the new column from the moment it exists rather than be retrofitted later.
            doorbellTokenIssued = contact.doorbellTokenIssued,
        ).also(database::putContact)
    }

    internal fun syntheticOnion(): String {
        val key = syntheticOnionKey()
        return try { OnionAddress.address(key) } finally { crypto.wipe(key) }
    }

    /** A fresh, genuine Ed25519 public key with its private half discarded. See [syntheticOnion]. */
    internal fun syntheticOnionKey(): ByteArray {
        // A real v3 onion address embeds a genuine Ed25519 public key: a point that a key
        // generator derived (via SHA-512 expansion and clamping of a secret seed), which is
        // always a valid Edwards-curve point in the prime-order subgroup. 32 raw random bytes are
        // NOT that: they decompress to a valid curve point only ~50% of the time and pass the
        // subgroup check even less often, so a decoy contact's onion would be detectable as
        // synthetic just by decoding it - no cryptanalysis required, only base32 + a curve check.
        // signingKeyPair() runs the same native Ed25519 keygen used for real identities elsewhere
        // in this class (see PairingIdentity.create above), so the embedded key is indistinguishable
        // from a real node's public key. The private half is immediately discarded.
        // The base32 + SHA3-256 checksum encoding moved to OnionAddress on 2026-09-17 (T4.16),
        // which needed the same conversion for the doorbell identity key. Android exposes no
        // SHA3-256 provider, so it still uses the bundled digest; the address is byte-identical to
        // what this function produced before.
        return crypto.signingKeyPair().use { it.publicKey }
    }
}
