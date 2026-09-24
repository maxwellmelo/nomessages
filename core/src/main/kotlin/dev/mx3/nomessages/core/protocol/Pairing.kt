package dev.mx3.nomessages.core.protocol

import dev.mx3.nomessages.core.crypto.Crypto
import dev.mx3.nomessages.core.crypto.SigningKeys
import java.time.Instant

class PairingIdentity private constructor(internal var signing:SigningKeys,sessions:SignalSessions):AutoCloseable {
    var sessions:SignalSessions=sessions
        private set
    private var closed=false
    val publicKey:ByteArray get()=signing.publicKey.copyOf()
    /** libsodium Ed25519 sk64 layout is seed32 || public32. Caller wipes this copy. */
    fun signingSeed():ByteArray { check(!closed); return signing.secretKey.copyOfRange(0,32) }
    fun restoreInPlace(blob:ByteArray) {
        check(!closed)
        val replacement=restore(blob)
        if(replacement.id!=id) { replacement.close(); error("Cannot replace vault identity") }
        signing.close(); sessions.close()
        signing=replacement.signing; sessions=replacement.sessions
    }
    val id:String get()=signing.publicKey.hex()
    fun export()=pack { writeInt(1); blob(signing.publicKey); blob(signing.secretKey); blob(sessions.export()) }
    override fun close() { signing.close(); sessions.close(); closed=true }
    companion object {
        fun create(crypto:Crypto):PairingIdentity { val keys=crypto.signingKeyPair(); return PairingIdentity(keys,SignalSessions.create(keys.publicKey.hex())) }
        fun restore(bytes:ByteArray)=unpack(bytes) {
            require(readInt()==1); val public=blob(32); val secret=blob(64); require(public.size==32 && secret.size==64)
            require(secret.copyOfRange(32,64).contentEquals(public)) { "Invalid Ed25519 identity export" }
            val sessions=SignalSessions.restore(blob(64*1024*1024))
            require(sessions.localId==public.hex()) { "Signal identity belongs to another vault identity" }
            PairingIdentity(SigningKeys(public,secret),sessions)
        }
    }
}

/**
 * A staged, not yet trusted, pairing exchange.
 *
 * [peerBundleNonce] is the nonce of the *peer's* offer. It is what a `BundleRequest` sent to
 * [peerOnion] must carry, because the QR no longer transports the 1.8 KB PQXDH bundle - only its
 * SHA-256 hash. [peerBundleReady] is false until [PairingEngine.acceptPeerBundle] has accepted a
 * bundle whose hash matches the one the peer signed; [PairingEngine.finish] refuses to complete
 * while it is false.
 *
 * [expiresAt] is the *confirmation* deadline (epoch seconds): [PairingEngine.CONFIRMATION_TTL_SECONDS]
 * from the moment this exchange was staged, **not** from either offer's creation. Acquiring an offer
 * and staging against it is budgeted separately - see [PairingEngine.PENDING_TTL_SECONDS] - because
 * anchoring both phases to offer creation meant a slow acquisition silently ate the human phase.
 */
data class PairingProgress(
    val handle:String,
    val responseQr:String?,
    val sas:String,
    val peerId:String,
    val peerOnion:String,
    val expiresAt:Long,
    val peerBundleNonce:ByteArray,
    val peerBundleReady:Boolean,
)
/**
 * The three doorbell fields **drive the doorbell feature (T4.17)**: they are what lets a device whose
 * messaging service is stopped still be woken by a peer whose delivery attempt failed. Two of them
 * describe the *peer* and one describes *this device*; they are deliberately not interchangeable, and
 * the exchange is the only moment either side can be learned.
 *
 * - [doorbellKey] - the peer's 32-byte Ed25519 onion-service identity key for a second, dedicated
 *   onion. Where to knock.
 * - [doorbellToken] - the 32-byte secret the peer minted for this exchange and expects to be
 *   presented when *its* doorbell is rung. What to say when knocking.
 * - [doorbellTokenIssued] - the 32-byte secret **this** device minted for this exchange, inside its
 *   own signed offer (see [PairingEngine.newOffer]). It is what the peer will present when it rings
 *   *this* device's doorbell, so it is the value a local listener has to recognise.
 *
 * [doorbellKey] and [doorbellToken] were inside the peer's signed offer, so the SAS the two humans
 * compared covers them. [doorbellTokenIssued] was inside this device's own signed offer, so it is
 * covered by the same SAS from the other direction - and it never leaves this device except inside
 * that one offer, delivered to that one peer.
 */
data class PairedContact(
    val peerId:String,
    val publicKey:ByteArray,
    val onion:String,
    val pairedAt:Long,
    val evidence:ByteArray,
    val doorbellKey:ByteArray,
    val doorbellToken:ByteArray,
    val doorbellTokenIssued:ByteArray,
)

/**
 * No peer is trusted before explicit local SAS approval and the peer's signed approval.
 *
 * ## QR format 2 (2026-09-17, T4.16)
 *
 * The offer QR carries `SHA-256(bundle.encode())` instead of the bundle itself. Mandatory
 * Kyber-1024 made the version 1 payload ~2950 bytes - a version-40-L QR with 177 modules per side,
 * which a real phone camera could not read even filling a whole monitor (measured on a Galaxy
 * Note10+; see `docs/development/protocol-report.md` item 2). The version 2 payload is 294 bytes
 * (offer) / 326 bytes (response), which renders as QR version 13 / 14 at level L - 73 modules per
 * side at worst, against 177.
 *
 * The bundle itself is fetched over Tor from the onion the QR already names, and is accepted only
 * when it hashes to the value the peer signed. The transport moves bytes; it grants no trust. See
 * `docs/security-model.md`.
 */
class PairingEngine(
    private val crypto:Crypto,
    private val identity:PairingIdentity,
    private val onion:String,
    /**
     * This device's doorbell onion-service identity key (32 bytes), derived from its own dedicated
     * vault seed - deliberately a different seed from the messaging onion's, so the two addresses
     * are unlinkable. Reserved for T4.17; nothing listens on it yet, but it is published and signed
     * now so the QR format does not have to change a second time.
     *
     * **Copied, not retained by reference.** It is read later, inside [createOffer], so holding the
     * caller's array would be an unwritten "do not wipe this" contract on a public constructor.
     * `DecoyFactory` violated exactly that and broke vault creation app-wide (T4.16 run5, defect a).
     * Thirty-two bytes buy the whole class of use-after-wipe bugs away, and a public key is not
     * secret material whose lifetime anyone needs to shorten.
     */
    doorbellKey:ByteArray,
    private val now:()->Long={Instant.now().epochSecond},
    private val consumed:MutableSet<String> = mutableSetOf(),
) {
    private val doorbell:ByteArray = doorbellKey.copyOf()
    /** An offer this device produced, together with the private key material behind its hash. */
    private class LocalOffer(val offer:Offer,val bundle:SignalBundle) { var answered=false }
    /**
     * Offers this device has emitted and not yet burned, newest first. The first entry is the one on
     * screen; the rest are still-valid predecessors.
     *
     * **Display and validity are separate.** The QR on screen rotates every [OFFER_TTL_SECONDS], but
     * every offer stays able to receive a response and answer a `BundleRequest` for its own full
     * [PENDING_TTL_SECONDS], counted from its own `created` and not from whether it is still
     * displayed. A peer who scanned an offer seconds before the rotation is still mid-handshake; the
     * rotation is a display decision and must not invalidate the thing they are holding.
     *
     * An entry therefore ages out on its own schedule, never by being pushed out by a newer one -
     * except at the [MAX_LIVE_OFFERS] cap, which is a guard against a pathological clock rather than
     * a working limit.
     */
    private val emitted=mutableListOf<LocalOffer>()
    private var pending:Pending?=null
    init {
        require(onion.matches(Regex("[a-z2-7]{56}\\.onion")))
        require(doorbell.size==32 && doorbell.any { it.toInt()!=0 }) { "Invalid doorbell identity key" }
    }
    /**
     * Produces a fresh offer QR and puts it on screen, keeping every predecessor that is still
     * inside its own [PENDING_TTL_SECONDS] window answerable - see [emitted].
     *
     * A pending exchange, in contrast, is burned: asking for a new QR in the middle of one is an
     * explicit restart, not a rotation.
     */
    fun createOffer():String {
        pending?.let { staged ->
            staged.localSignature?.fill(0)
            if(emitted.none { it===staged.local }) burn(staged.local)
        }
        pending=null
        prune()
        val local=newOffer(ByteArray(0))
        emitted.add(0,local)
        prune()
        return local.offer.qr()
    }

    /**
     * Drops emitted offers that are past their own window, then enforces [MAX_LIVE_OFFERS], burning
     * the key material of everything it removes. Expiry comes first so the cap only ever bites in
     * the pathological case; in steady state it never truncates anything.
     */
    private fun prune() {
        val expired=emitted.filterNot(::live)
        emitted.removeAll { !live(it) }
        while(emitted.size>MAX_LIVE_OFFERS) emitted.removeAt(emitted.lastIndex).let(::burn)
        expired.forEach(::burn)
    }
    fun receive(qr:String):PairingProgress {
        val decoded=Offer.decode(Proto.fromQr(OFFER_PREFIX,qr))
        return if(decoded.reply.isEmpty()) respond(qr) else processResponse(qr)
    }
    /**
     * Answers a standing invitation QR. That QR is still bounded by [OFFER_TTL_SECONDS]: it is
     * regenerated every 120 s, so a scan older than that is reading something the other screen has
     * already replaced. A *response* is not regenerated and is bounded differently - see
     * [processResponse].
     */
    fun respond(offerQr:String):PairingProgress {
        val remote=readOffer(offerQr,OFFER_TTL_SECONDS)
        require(remote.reply.isEmpty()) { "Expected pairing offer" }
        cancel()
        val local=newOffer(crypto.hash(remote.encode()))
        return stage(remote,local.offer,local,local.offer.qr())
    }
    /**
     * Accepts the QR that replies to one of this device's own offers.
     *
     * Two things here are deliberately **not** the 120 s QR-reading rule, and both were the T4.16
     * run5 defect:
     *
     * 1. The reply is matched against every offer in [standingOffers], not only the current one.
     *    The 120 s rotation can flip which offer is on screen between the moment the peer scans it
     *    and the moment its reply comes back, more than once: at a 120 s cadence inside a 300 s
     *    window, up to [MAX_LIVE_OFFERS] offers are simultaneously valid.
     * 2. The incoming response is bounded by [PENDING_TTL_SECONDS], not [OFFER_TTL_SECONDS]. A
     *    response is not a standing invitation: it belongs to an exchange the peer has already
     *    staged, its screen keeps it up for the whole 300 s window and shows that clock. Enforcing
     *    120 s here made the engine contradict the countdown the user was looking at.
     *
     * Widening this window loosens nothing. A response is consumable only by the device that minted
     * the offer it names (`reply` is that offer's digest), only once ([consumed]), and the exchange
     * it stages is itself bounded - by [CONFIRMATION_TTL_SECONDS] from the moment of staging.
     */
    fun processResponse(responseQr:String):PairingProgress {
        try {
            val candidates=standingOffers()
            require(candidates.isNotEmpty()) { "No active offer" }
            val remote=readOffer(responseQr,PENDING_TTL_SECONDS)
            val local=candidates.firstOrNull { remote.reply.contentEquals(crypto.hash(it.offer.encode())) }
                ?: error("Response does not match offer")
            val progress=stage(local.offer,remote,local,null)
            // Every other emitted offer is burned here rather than left answerable. This device can
            // hold only one exchange, so those offers can no longer lead anywhere - and answering a
            // BundleRequest for an offer that can never complete is exactly what the rate-limit rule
            // forbids. Burning at staging rather than at finish() also wipes their key material
            // sooner, and loses nothing: a cancelled exchange does not revive them, it yields a
            // fresh QR.
            emitted.filter { it!==local }.forEach(::burn)
            emitted.clear()
            return progress
        } catch(e:Exception) { cancel(); throw e }
    }
    private fun stage(first:Offer,second:Offer,local:LocalOffer,responseQr:String?):PairingProgress {
        val peer=if(local.offer===first) second else first
        // "nomessages-transcript-v2": CBOR domain separator. v1 -> v2 on 2026-09-17 together with the
        // QR format: the encoded offers fed in here changed shape, and reusing the v1 separator across
        // two incompatible encodings is exactly the ambiguity a domain separator exists to prevent.
        // The transcript still covers both complete offers, and each offer now commits to its own
        // bundle hash - so the SAS transitively authenticates both PQXDH bundles without ever having
        // seen them. Pre-release, no migration.
        val transcript=crypto.hash(cbor("nomessages-transcript-v2",first.encode(),second.encode()))
        val keys=listOf(first.ed.hex(),second.ed.hex()).sorted()
        val statement=PairStatement(keys[0],keys[1],transcript,maxOf(first.created,second.created))
        val handle=transcript.hex()
        // Anchored on NOW, the instant of staging, not on either offer's `created`.
        //
        // T4.16 run6 measured why: with two QR rotations spent acquiring an offer, ~240 s of the old
        // offer-anchored 300 s was already gone by the time the SAS existed, leaving 4-35 s for the
        // entire human phase - reading six digits aloud, confirming on both screens, and exchanging
        // two confirmation QRs. Both two-rotation attempts were accepted by the protocol and then ran
        // out of time. Acquisition and confirmation are different problems on different clocks, and
        // budgeting them from the same origin made one starve the other.
        //
        // Each device counts from its own staging, so the two deadlines differ by the relay delay
        // between them. That is correct rather than merely tolerable: the budget bounds how long
        // *this* device's pending key material may sit, and this device's user could act from the
        // moment its SAS appeared. In person the two stagings are seconds apart, so the shared window
        // is essentially the whole budget.
        val expiresAt=now()+CONFIRMATION_TTL_SECONDS
        val staged=Pending(handle,local,peer,statement,expiresAt)
        pending=staged
        return progress(staged,responseQr,sas(staged))
    }
    private fun progress(p:Pending,responseQr:String?,sas:String)=
        PairingProgress(p.handle,responseQr,sas,p.peer.ed.hex(),p.peer.onion,p.expires,p.peer.nonce.copyOf(),p.peerBundle!=null)
    /**
     * Accepts the peer's PQXDH bundle, fetched over Tor, for the pending exchange [handle].
     *
     * The only thing that makes it trustworthy is the hash comparison below: `bundleHash` is inside
     * the Ed25519-signed part of the offer this device already verified, so a transport that swaps
     * the bytes produces a mismatch here. Any failure burns the exchange rather than leaving a
     * half-verified one behind.
     */
    fun acceptPeerBundle(handle:String,encoded:ByteArray):PairingProgress {
        val p=get(handle)
        try {
            require(p.peerBundle==null) { "Key bundle already received for this pairing" }
            require(java.security.MessageDigest.isEqual(crypto.hash(encoded),p.peer.bundleHash)) {
                "Key bundle does not match the hash signed in the QR code"
            }
            val bundle=SignalBundle.decode(encoded)
            require(bundle.identity.size==33 && bundle.pre.size==33) { "Invalid key bundle" }
            p.peerBundle=bundle
            return progress(p,null,sas(p))
        } catch(e:Exception) { cancel(); throw e }
    }
    /**
     * The encoded bundle behind one of **this device's own** offers, or null.
     *
     * The rate limit is structural rather than numeric: an answer requires a nonce that this device
     * itself minted and published in a QR, inside that offer's own [PENDING_TTL_SECONDS] window, and
     * each nonce is answered exactly once. An unknown, stale, or already-consumed nonce yields null,
     * so nothing is revealed about offers that are not live.
     */
    fun bundleFor(nonce:ByteArray):ByteArray? {
        if(nonce.size!=16) return null
        // The same set [processResponse] accepts - up to MAX_LIVE_OFFERS of them - plus the offer
        // already inside a staged exchange: the peer may still be fetching its bundle after the SAS
        // has appeared on both screens.
        val match=(standingOffers()+listOfNotNull(pending?.local).filter(::live)).distinct()
            .firstOrNull { java.security.MessageDigest.isEqual(it.offer.nonce,nonce) } ?: return null
        if(match.answered) return null
        match.answered=true
        return match.bundle.encode()
    }
    fun confirm(handle:String,matches:Boolean):String {
        val p=get(handle)
        if(!matches) { cancel(); error("SAS mismatch; pairing cancelled") }
        val signature=crypto.sign(identity.signing.secretKey,p.statement.canonical())
        p.localSignature=signature
        return Proto.qr(CONFIRM_PREFIX,Proto.encode(listOf(p.statement.encode(),identity.publicKey,signature)))
    }
    fun finish(handle:String,peerConfirmationQr:String):PairedContact {
        val p=get(handle)
        val own=p.localSignature ?: error("Confirm the SAS locally first")
        try {
            val bundle=p.peerBundle ?: error("The other device's key bundle has not arrived yet")
            val fields=Proto.decode(Proto.fromQr(CONFIRM_PREFIX,peerConfirmationQr),"bbb")
            val statement=fields[0] as ByteArray; val signer=fields[1] as ByteArray; val sig=fields[2] as ByteArray
            require(statement.contentEquals(p.statement.encode()) && signer.contentEquals(p.peer.ed)) { "Confirmation transcript or signer mismatch" }
            require(sig.size==64 && crypto.verify(signer,p.statement.canonical(),sig)) { "Invalid confirmation signature" }
            identity.sessions.establish(p.peer.ed.hex(),bundle)
            val signatures=if(identity.id==p.statement.first) listOf(own,sig) else listOf(sig,own)
            val evidence=PairEvidence(p.statement,signatures[0],signatures[1]).encode()
            // The token THIS device minted in its own offer for THIS exchange - see [newOffer]. It
            // is handed out here because this is the last instant it exists anywhere: the exchange
            // ends, `pending` is dropped, and the offer that carried it is unreachable afterwards.
            // Before T4.17 it was simply lost, which left the vault able to say "what to present at
            // the peer's doorbell" but not "what to accept at mine" - an asymmetry with no way back,
            // since there is no second authenticated channel on which to agree a token later.
            val issued=p.local.offer.doorbellToken.copyOf()
            // Zeroed only after the copy above. Safe here and nowhere earlier: an offer can age out
            // of the standing set while an exchange staged against it is still live, so `burn` -
            // which runs on exactly that path - deliberately does NOT touch this array.
            p.local.offer.doorbellToken.fill(0)
            pending=null
            return PairedContact(p.peer.ed.hex(),p.peer.ed.copyOf(),p.peer.onion,p.statement.pairedAt,evidence,
                p.peer.doorbellKey.copyOf(),p.peer.doorbellToken.copyOf(),issued)
        } catch(e:Exception) { cancel(); throw e }
    }
    fun cancel() {
        val p=pending
        pending=null
        p?.localSignature?.fill(0)
        val all=(listOfNotNull(p?.local)+emitted).distinct()
        emitted.clear()
        all.forEach(::burn)
        // The doorbell tokens these offers minted die with them, exactly like `localSignature` above.
        // Zeroing is safe *here* and not inside `burn` because cancel drops every reference the
        // engine holds - the pending exchange and the whole emitted list - so no surviving offer can
        // still need its token, whereas `burn` also runs on offers that merely aged out of the
        // standing set while an exchange staged against one of them is still heading for `finish`.
        all.forEach { it.offer.doorbellToken.fill(0) }
    }
    private fun burn(target:LocalOffer) { identity.sessions.discardBundle(target.bundle.keyId) }
    private fun sas(p:Pending):String {
        val value=((p.statement.transcript[0].toInt() and 255) shl 16) or
            ((p.statement.transcript[1].toInt() and 255) shl 8) or (p.statement.transcript[2].toInt() and 255)
        return (value%1_000_000).toString().padStart(6,'0')
    }
    /**
     * The staged exchange, or a failure. This is the only gate on [confirm], [acceptPeerBundle] and
     * [finish], and it has exactly one deadline - [CONFIRMATION_TTL_SECONDS] from staging, via
     * [Pending.expires] - with no QR-reading TTL anywhere in it. Those three paths were therefore
     * never exposed to the which-offer-is-current inconsistency [processResponse] had: an offer can
     * be rotated away, an *exchange* cannot. There is at most one [pending], and the confirmation QR
     * carries the full [PairStatement], which binds it to that exchange and no other.
     */
    private fun get(handle:String):Pending {
        val p=pending ?: error("No pending pairing")
        require(handle==p.handle) { "Unknown pairing handle" }
        if(now()>=p.expires || now()<p.statement.pairedAt) { cancel(); error("Pairing expired") }
        return p
    }
    private fun newOffer(reply:ByteArray):LocalOffer {
        val bundle=identity.sessions.newBundle()
        // A fresh doorbell token per offer, exactly like the nonce: the peer of THIS exchange is the
        // only party that ever learns it, so two contacts can never present each other's token.
        // Whichever offer ends up completing an exchange hands this value back out of [finish] as
        // `PairedContact.doorbellTokenIssued`, so the caller can persist it against that one
        // contact; every other offer's token is zeroed unused by [cancel].
        val unsigned=Offer(identity.publicKey,onion,now(),crypto.random(16),crypto.hash(bundle.encode()),
            doorbell.copyOf(),crypto.random(32),reply,ByteArray(0))
        return LocalOffer(unsigned.copy(signature=crypto.sign(identity.signing.secretKey,unsigned.canonical())),bundle)
    }
    private fun within(value:Offer,seconds:Long):Boolean { val age=now()-value.created; return age>=0 && age<seconds }
    private fun live(candidate:LocalOffer)=within(candidate.offer,PENDING_TTL_SECONDS)
    /**
     * Every offer of this device's that a peer may still act on, newest first - the whole of
     * [emitted], each entry inside its own [PENDING_TTL_SECONDS] window.
     *
     * This is the **one** definition of "an offer of mine that is still live", and both paths that
     * have to recognise an offer after the fact use it: [bundleFor], which answers a peer's
     * key-bundle request, and [processResponse], which accepts the QR replying to it. It prunes as
     * it reads, so expired key material is burned when anybody asks rather than lingering until the
     * next [createOffer].
     *
     * The two paths used to disagree, and that was a real defect (T4.16 run5, defect c):
     * `bundleFor` tolerated a rotated-away offer while `processResponse` looked only at the current
     * one and re-applied the 120 s QR-reading TTL, so an ordinary-speed pairing whose offer rotated
     * between the peer's scan and the peer's reply failed with a generic "could not complete" -
     * while the screen was showing the 300 s clock. Two notions of "still valid" in one flow is the
     * bug, and the first fix for it still kept only one predecessor, which is one short of the
     * [MAX_LIVE_OFFERS] that actually overlap.
     */
    private fun standingOffers():List<LocalOffer> { prune(); return emitted.toList() }
    private fun readOffer(qr:String,maxAgeSeconds:Long):Offer {
        val bytes=Proto.fromQr(OFFER_PREFIX,qr)
        val result=Offer.decode(bytes)
        require(within(result,maxAgeSeconds)) { "Pairing expired or in future" }
        require(!result.ed.contentEquals(identity.publicKey)) { "Cannot pair with self" }
        require(crypto.verify(result.ed,result.canonical(),result.signature)) { "Invalid offer signature" }
        val hash=crypto.hash(bytes).hex()
        require(consumed.add(hash)) { "Pairing offer replay" }
        return result
    }
    private class Pending(val handle:String,val local:LocalOffer,val peer:Offer,val statement:PairStatement,val expires:Long) {
        var peerBundle:SignalBundle?=null
        var localSignature:ByteArray?=null
    }
    companion object {
        // OFFER_PREFIX/CONFIRM_PREFIX: QR wire-format protocol identifiers. Bumped 1 -> 2 on
        // 2026-09-17 (T4.16) because the offer payload changed shape; a version 1 QR is rejected at
        // the prefix, and a version 1 body re-labelled as 2 is rejected by Offer.decode.
        const val OFFER_PREFIX="nomessages:2:"
        /**
         * Field kinds of the ten wire fields of a format-2 offer, in order:
         * version, ed, onion, created, nonce, bundleHash, doorbellKey, doorbellToken, reply,
         * signature. Exposed so tests dissect the payload with the same string the decoder uses,
         * instead of a copy that can drift out of step with it.
         */
        internal const val OFFER_FIELD_KINDS="ibbibbbbbb"
        const val CONFIRM_PREFIX="nomessages-confirm:2:"
        /** How long a QR may still be scanned. Unchanged from format 1. */
        const val OFFER_TTL_SECONDS=120L
        /**
         * The **acquisition** budget: how long an emitted offer stays answerable, and therefore how
         * long matching a response and answering a `BundleRequest` against it may take. Counted from
         * each offer's own `created`. Longer than [OFFER_TTL_SECONDS] on purpose - building a circuit
         * to a fresh onion service takes 5-40 s, which the format-1 flow never needed.
         *
         * It deliberately no longer covers the confirmation phase; see [CONFIRMATION_TTL_SECONDS].
         */
        const val PENDING_TTL_SECONDS=300L

        /**
         * The **confirmation** budget: how long a staged exchange has, counted from staging.
         *
         * Sized from what actually happens after [stage] runs, which is more than the name "human
         * phase" suggests - the Tor bundle fetch is started by the runtime *after* staging, so it is
         * inside this budget, not the acquisition one:
         *
         * - key-bundle fetch over Tor, allowing one failed attempt: 60 s cap + 3 s backoff + ~40 s
         * - two people reading six digits aloud and comparing them: ~30 s
         * - typing a local alias: ~20 s
         * - showing and scanning two confirmation QRs: ~60 s
         *
         * That is ~215 s, so 240 s carries roughly ten percent headroom. T4.16 run6b independently
         * completed the whole post-staging flow with 165 s to spare, but through a scripted relay
         * with a first-attempt fetch - which removes precisely the two slowest steps above, so it is
         * a floor for this number rather than a target.
         *
         * Being generous here is the cheap direction. Too short costs a failed in-person ceremony -
         * the exact availability failure this whole task exists to remove - while too long costs one
         * extra one-time prekey bundle sitting in the store for a few more minutes, on a device the
         * user is holding, burned by [cancel] on lock, on leaving the screen, or at this deadline.
         */
        const val CONFIRMATION_TTL_SECONDS=240L
        /**
         * How many of this device's own offers may be simultaneously valid.
         *
         * Not an arbitrary bound: an offer lives [PENDING_TTL_SECONDS] and the screen rotates every
         * [OFFER_TTL_SECONDS], so `ceil(300 / 120) = 3` of them overlap at the peak of the steady
         * state. The cap is therefore sized to never truncate normal operation - it exists to keep
         * the set bounded if the clock or the caller behaves pathologically.
         */
        const val MAX_LIVE_OFFERS=3
    }
}

/**
 * The signed pairing offer, QR wire format 2.
 *
 * Signed field layout (protobuf-subset field order; 294 bytes encoded for an offer, 326 for a
 * response that also carries `reply`):
 *
 * | #  | field         | bytes | note                                              |
 * |----|---------------|-------|---------------------------------------------------|
 * | 1  | version       | 1     | always 2                                          |
 * | 2  | ed            | 32    | Ed25519 identity public key                       |
 * | 3  | onion         | 62    | messaging onion, `<56 chars>.onion`, US-ASCII     |
 * | 4  | created       | 5     | epoch seconds, varint                             |
 * | 5  | nonce         | 16    | also the `BundleRequest` selector                 |
 * | 6  | bundleHash    | 32    | `SHA-256(SignalBundle.encode())`                  |
 * | 7  | doorbellKey   | 32    | doorbell onion identity key - where to knock      |
 * | 8  | doorbellToken | 32    | minted fresh per offer - what to say when knocking |
 * | 9  | reply         | 0/32  | digest of the offer being answered                |
 * | 10 | signature     | 64    | Ed25519 over `canonical()` of fields 1-9          |
 *
 * `signal`, `ephemeral` and the 1832-byte `bundle` blob of format 1 are gone. They are not merely
 * moved out of the signature's reach: `bundleHash` covers the whole bundle, identity and one-time
 * prekey included, so the same substitution that format 1 blocked by repeating them as signed outer
 * fields is blocked here by a single hash comparison in [PairingEngine.acceptPeerBundle].
 *
 * ## Why [doorbellKey] is a key and not an address
 *
 * A v3 onion address is `base32(pubkey32 || checksum2 || version1) + ".onion"`: 62 US-ASCII
 * characters that encode 35 bytes, of which only the 32-byte key is not derivable. Carrying the key
 * costs 32 bytes instead of 62 and loses nothing - `OnionAddress.address()` reconstructs the exact
 * string. Those 30 bytes are what keeps the response QR inside the version-14 budget at EC level L;
 * with the textual address it measured version 15. See `docs/changes/Pairing.kt.md`.
 */
private data class Offer(val ed:ByteArray,val onion:String,val created:Long,val nonce:ByteArray,val bundleHash:ByteArray,val doorbellKey:ByteArray,val doorbellToken:ByteArray,val reply:ByteArray,val signature:ByteArray) {
    private fun fields()=listOf(2L,ed,onion.toByteArray(Charsets.US_ASCII),created,nonce,bundleHash,doorbellKey,doorbellToken,reply)
    // "nomessages-offer-v2": CBOR domain separator, bumped from "nomessages-offer-v1" on 2026-09-17
    // because the signed field list itself changed.
    fun canonical()=cbor("nomessages-offer-v2",*fields().toTypedArray())
    fun encode()=Proto.encode(fields()+listOf(signature))
    fun qr()=Proto.qr(PairingEngine.OFFER_PREFIX,encode())
    companion object {
        fun decode(bytes:ByteArray):Offer {
            val f=Proto.decode(bytes,PairingEngine.OFFER_FIELD_KINDS)
            require(f[0]==2L) { "Unsupported pairing QR version" }
            val ed=f[1] as ByteArray; val onionBytes=f[2] as ByteArray; val created=f[3] as Long
            val nonce=f[4] as ByteArray; val bundleHash=f[5] as ByteArray
            val doorbellKey=f[6] as ByteArray; val doorbellToken=f[7] as ByteArray
            val reply=f[8] as ByteArray; val sig=f[9] as ByteArray
            val onion=onionBytes.toString(Charsets.US_ASCII)
            require(ed.size==32 && nonce.size==16 && bundleHash.size==32 && sig.size==64)
            // Reserved for T4.17, but validated now: an all-zero key is not a valid Ed25519 onion
            // identity, and accepting one would put an unusable address into a signed contact record.
            require(doorbellKey.size==32 && doorbellKey.any { it.toInt()!=0 }) { "Invalid doorbell identity key" }
            require(doorbellToken.size==32) { "Invalid doorbell token" }
            require(onion.matches(Regex("[a-z2-7]{56}\\.onion")) && onion.toByteArray(Charsets.US_ASCII).contentEquals(onionBytes))
            require(created>0 && (reply.isEmpty() || reply.size==32))
            return Offer(ed,onion,created,nonce,bundleHash,doorbellKey,doorbellToken,reply,sig)
        }
    }
}

data class PairStatement(val first:String,val second:String,val transcript:ByteArray,val pairedAt:Long) {
    // "nomessages-pair-evidence-v1": CBOR domain separator for pairing evidence.
    fun canonical()=cbor("nomessages-pair-evidence-v1",first.unhex(),second.unhex(),transcript,pairedAt)
    fun encode()=pack { writeInt(1); blob(first.unhex());blob(second.unhex());blob(transcript);writeLong(pairedAt) }
    companion object {
        fun decode(bytes:ByteArray)=unpack(bytes,256) {
            require(readInt()==1); val a=blob(32); val b=blob(32); val hash=blob(32); val time=readLong()
            require(a.size==32 && b.size==32 && hash.size==32 && time>0 && a.hex()<b.hex())
            PairStatement(a.hex(),b.hex(),hash,time)
        }
    }
}
data class PairEvidence(val statement:PairStatement,val firstSignature:ByteArray,val secondSignature:ByteArray) {
    fun encode()=pack { writeInt(1); blob(statement.encode());blob(firstSignature);blob(secondSignature) }
    fun valid(crypto:Crypto)=crypto.verify(statement.first.unhex(),statement.canonical(),firstSignature) && crypto.verify(statement.second.unhex(),statement.canonical(),secondSignature)
    companion object {
        fun decode(bytes:ByteArray)=unpack(bytes,512) {
            require(readInt()==1); val statement=PairStatement.decode(blob(256)); val a=blob(64);val b=blob(64);require(a.size==64 && b.size==64)
            PairEvidence(statement,a,b)
        }
    }
}
