package dev.mx3.nomessages.core.protocol

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import dev.mx3.nomessages.core.crypto.NativeCrypto
import dev.mx3.nomessages.core.groups.CliquePolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey

class ProtocolTest {
    private val crypto by lazy { NativeCrypto() }
    private val onionA = "a".repeat(56) + ".onion"
    private val onionB = "b".repeat(56) + ".onion"
    // Doorbell onion identity keys (reserved for T4.17). Any non-zero 32 bytes are accepted by the
    // engine: it signs and publishes the key, it does not use it to derive anything.
    private val doorbellA = ByteArray(32) { (it + 1).toByte() }
    private val doorbellB = ByteArray(32) { (it + 65).toByte() }
    private val doorbellC = ByteArray(32) { (it + 129).toByte() }
    @Test fun responseKeepsOriginalDeadlineAndDoesNotRedisplayTheConsumedOffer() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val first = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val second = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val offer = first.createOffer()
                time += 45
                val response = second.respond(offer)
                val accepted = first.processResponse(requireNotNull(response.responseQr))
                // Counted from STAGING (t = 1045), not from either offer's creation: acquisition and
                // confirmation are budgeted separately since T4.16 run6. Both sides staged at the
                // same instant here, so both deadlines coincide.
                assertEquals(1045L + PairingEngine.CONFIRMATION_TTL_SECONDS, response.expiresAt)
                assertEquals(response.expiresAt, accepted.expiresAt)
                assertNull(accepted.responseQr)
                time = accepted.expiresAt
                assertThrows(Exception::class.java) { first.confirm(accepted.handle, true) }
                assertThrows(Exception::class.java) { second.confirm(response.handle, true) }
            }
        }
    }
    @Test fun bilateralPairingAndSignalRestoreRoundTrip() {
        val a = PairingIdentity.create(crypto)
        val b = PairingIdentity.create(crypto)
        val ea = PairingEngine(crypto, a, onionA, doorbellA)
        val eb = PairingEngine(crypto, b, onionB, doorbellB)
        val br = eb.respond(ea.createOffer())
        val response = requireNotNull(br.responseQr)
        val ar = ea.processResponse(response)
        assertEquals(ar.sas, br.sas)
        // QR format 2 carries only the bundle hash, so each side has to be handed the peer's real
        // bundle before it may finish. This stands in for the Tor fetch MessagingEngine performs.
        exchangeBundles(ea, ar, eb, br)
        val ac = ea.confirm(ar.handle, true)
        val bc = eb.confirm(br.handle, true)
        val ca = ea.finish(ar.handle, bc)
        val cb = eb.finish(br.handle, ac)
        assertArrayEquals(ca.evidence, cb.evidence)
        val encrypted = a.sessions.encrypt(b.id, "hello secret".toByteArray())
        assertArrayEquals("hello secret".toByteArray(), b.sessions.decrypt(a.id, encrypted))
        val restored = PairingIdentity.restore(b.export())
        val reply = restored.sessions.encrypt(a.id, "reply".toByteArray())
        assertArrayEquals("reply".toByteArray(), a.sessions.decrypt(b.id, reply))
        assertThrows(Exception::class.java) { b.sessions.decrypt(a.id, encrypted) }
        val third = PairingIdentity.create(crypto)
        assertEquals(2, CliquePolicy(crypto).missingPairs(listOf(a.id,b.id,third.id), listOf(ca.evidence)).size)
    }
    @Test fun frameBucketsAndBoundsAreExact() {
        val sizes=listOf(1,240,241,1008,1009,4080,4081,16368,16369)
        val expected=listOf(256,256,1024,1024,4096,4096,16384,16384,16384)
        sizes.zip(expected).forEach { (size,bucket) ->
            val encoded=FrameCodec.encode(ByteArray(size))
            assertEquals(bucket+4,encoded.first().size)
        }
        assertNull(FrameCodec.read(ByteArrayInputStream(byteArrayOf())))
        assertThrows(Exception::class.java) { FrameCodec.encode(byteArrayOf()) }
        assertThrows(Exception::class.java) { FrameCodec.read(ByteArrayInputStream(byteArrayOf(0,0,0,0))) }
        assertThrows(Exception::class.java) { FrameCodec.read(ByteArrayInputStream(byteArrayOf(0))) }
        val broken=FrameCodec.encode(byteArrayOf(1))[0]
        java.nio.ByteBuffer.wrap(broken).putInt(4,Int.MAX_VALUE)
        assertThrows(Exception::class.java) { FrameAssembler().accept(broken) }
    }
    @Test fun framingSurvivesShortReadsAndRejectsTruncation() {
        val original = ByteArray(70_000) { it.toByte() }
        val frames = FrameCodec.encode(original)
        val assembler = FrameAssembler()
        var decoded: ByteArray? = null
        frames.forEach { frame ->
            val stream = object: ByteArrayInputStream(frame) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 3))
            }
            decoded = assembler.accept(FrameCodec.read(stream)!!)
        }
        assertArrayEquals(original, decoded)
        assertThrows(Exception::class.java) { FrameCodec.read(ByteArrayInputStream(frames[0].dropLast(1).toByteArray())) }
        assertThrows(Exception::class.java) { FrameAssembler().accept(frames[1]) }
    }
    @Test fun rejectsReplayExpiredTamperedAndUnconfirmedOffers() {
        var time = 1000L
        val a = PairingIdentity.create(crypto)
        val b = PairingIdentity.create(crypto)
        val ea = PairingEngine(crypto,a,onionA,doorbellA,{time})
        val eb = PairingEngine(crypto,b,onionB,doorbellB,{time})
        val offer = ea.createOffer()
        val br = eb.respond(offer)
        assertThrows(Exception::class.java) { eb.respond(offer) }
        val response = requireNotNull(br.responseQr)
        val ar = ea.processResponse(response)
        val bc = eb.confirm(br.handle,true)
        assertThrows(Exception::class.java) { ea.finish(ar.handle,bc) }
        assertThrows(Exception::class.java) { ea.confirm(ar.handle,false) }
        assertThrows(Exception::class.java) { ea.confirm(ar.handle,true) }
        val fresh = ea.createOffer()
        time += 120
        assertThrows(Exception::class.java) { eb.respond(fresh) }
        val tampered = fresh.dropLast(4)+"AAAA"
        assertThrows(Exception::class.java) { eb.respond(tampered) }
    }

    /**
     * The headline measurement of T4.16.
     *
     * The real symbol version is taken from ZXing's own encoder rather than compared against a
     * byte-capacity table, because ZXing's segment-mode optimiser may split a payload across byte,
     * alphanumeric and numeric segments - so a capacity table only bounds the version from above,
     * while `Encoder.encode(...).version` is the version the app will actually render. The same
     * error-correction level L that `PairingScreen.makeQr` uses is passed in.
     *
     * Measured on 2026-09-17, with the two doorbell fields included: offer 405 bytes / version 13,
     * response 448 bytes / version 14, confirmation 317 bytes / version 11. Version 14 is 73 modules
     * per side. For reference, format 1 produced ~2950 bytes, i.e. version 40 (177 modules per
     * side), which the user's Galaxy Note10+ camera could not read even filling a whole monitor.
     *
     * The response sits **at** the budget, not under it: version 14 holds 458 bytes at level L, so
     * there are ten bytes of slack. That is deliberate and is the reason the doorbell identity is
     * carried as a 32-byte key rather than as its 62-character address - see `Pairing.kt`. Anything
     * added to the offer from here needs the budget re-derived, not just this assertion relaxed.
     */
    @Test fun formatTwoQrPayloadsStayWithinTheVersionFourteenBudgetAtLevelL() {
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA)
                val eb = PairingEngine(crypto, b, onionB, doorbellB)
                val offer = ea.createOffer()
                val br = eb.respond(offer)
                val response = requireNotNull(br.responseQr)
                val ar = ea.processResponse(response)
                exchangeBundles(ea, ar, eb, br)
                val confirmation = ea.confirm(ar.handle, true)
                mapOf("offer" to offer, "response" to response, "confirmation" to confirmation).forEach { (name, payload) ->
                    val bytes = payload.toByteArray(Charsets.US_ASCII).size
                    assertTrue(bytes <= 500, "$name payload is $bytes bytes, over the 500-byte budget")
                    val version = Encoder.encode(payload, ErrorCorrectionLevel.L).version.versionNumber
                    assertTrue(version <= 14, "$name renders as QR version $version at level L, over the version-14 budget")
                }
            }
        }
    }

    /** The SAS is over the transcript of both offers, and each offer commits to its bundle hash. */
    @Test fun sasChangesWhenEitherSideUsesADifferentBundle() {
        val seen = mutableSetOf<String>()
        repeat(3) {
            PairingIdentity.create(crypto).use { a ->
                PairingIdentity.create(crypto).use { b ->
                    val ea = PairingEngine(crypto, a, onionA, doorbellA)
                    val eb = PairingEngine(crypto, b, onionB, doorbellB)
                    val br = eb.respond(ea.createOffer())
                    val ar = ea.processResponse(requireNotNull(br.responseQr))
                    assertEquals(ar.sas, br.sas)
                    seen += ar.sas
                }
            }
        }
        // Fresh identities and fresh one-time bundles each round: identical SAS values would mean
        // the transcript no longer depends on the per-offer material at all.
        assertEquals(3, seen.size)
    }

    /** A bundle that does not hash to what the peer signed is refused and burns the exchange. */
    @Test fun tamperedOrSubstitutedBundleIsRejectedAndCancelsThePairing() {
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                PairingIdentity.create(crypto).use { stranger ->
                    val ea = PairingEngine(crypto, a, onionA, doorbellA)
                    val eb = PairingEngine(crypto, b, onionB, doorbellB)
                    val es = PairingEngine(crypto, stranger, onionB, doorbellC)
                    val br = eb.respond(ea.createOffer())
                    val ar = ea.processResponse(requireNotNull(br.responseQr))
                    val real = requireNotNull(eb.bundleFor(ar.peerBundleNonce))
                    val flipped = real.copyOf()
                    flipped[flipped.lastIndex] = (flipped.last().toInt() xor 1).toByte()
                    assertThrows(Exception::class.java) { ea.acceptPeerBundle(ar.handle, flipped) }
                    // Burned: the handle is gone, so a later correct bundle cannot revive it.
                    assertThrows(Exception::class.java) { ea.acceptPeerBundle(ar.handle, real) }

                    // And a well-formed bundle belonging to somebody else is refused for the same
                    // reason - the hash comparison, not the bundle's own internal validity.
                    val eb2 = PairingEngine(crypto, b, onionB, doorbellB)
                    val ea2 = PairingEngine(crypto, a, onionA, doorbellA)
                    val br2 = eb2.respond(ea2.createOffer())
                    val ar2 = ea2.processResponse(requireNotNull(br2.responseQr))
                    // The stranger's own offer nonce, read out of its QR exactly as a scanner would.
                    val strangerQr = es.createOffer()
                    val strangerNonce = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, strangerQr), PairingEngine.OFFER_FIELD_KINDS)[4] as ByteArray
                    val foreign = requireNotNull(es.bundleFor(strangerNonce))
                    assertThrows(Exception::class.java) { ea2.acceptPeerBundle(ar2.handle, foreign) }
                }
            }
        }
    }

    /** A nonce is answered once and only for an offer this device itself minted. */
    @Test fun bundleForAnswersOnceAndOnlyForItsOwnLiveOffers() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val eb = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val offer = ea.createOffer()
                val nonce = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, offer), PairingEngine.OFFER_FIELD_KINDS)[4] as ByteArray

                assertNull(eb.bundleFor(nonce), "a device must never answer for somebody else's nonce")
                assertNull(ea.bundleFor(ByteArray(16)), "an unknown nonce must not be answered")
                assertNull(ea.bundleFor(ByteArray(8)), "a wrong-length nonce must not be answered")
                assertNotNull(ea.bundleFor(nonce))
                assertNull(ea.bundleFor(nonce), "a nonce must be answered exactly once")

                // A superseded offer stays answerable for the rest of its own window, so a scan in
                // the last seconds before the screen's automatic refresh can still complete.
                val refreshedFirst = ea.createOffer()
                val firstNonce = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, refreshedFirst), PairingEngine.OFFER_FIELD_KINDS)[4] as ByteArray
                ea.createOffer()
                assertNotNull(ea.bundleFor(firstNonce), "the offer just replaced must still be answerable")

                // Past its own window it is gone, even though it was never answered.
                val stale = ea.createOffer()
                val staleNonce = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, stale), PairingEngine.OFFER_FIELD_KINDS)[4] as ByteArray
                time += PairingEngine.PENDING_TTL_SECONDS
                assertNull(ea.bundleFor(staleNonce))
            }
        }
    }

    /** Format 1 QR codes are refused, at the prefix and at the version field. */
    @Test fun formatOneQrCodesAreRejected() {
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA)
                val eb = PairingEngine(crypto, b, onionB, doorbellB)
                val valid = ea.createOffer()
                val body = Proto.fromQr(PairingEngine.OFFER_PREFIX, valid)
                assertThrows(Exception::class.java) { eb.receive("nomessages:1:" + valid.substring(PairingEngine.OFFER_PREFIX.length)) }
                assertThrows(Exception::class.java) { eb.receive("nomessages-confirm:1:" + valid.substring(PairingEngine.OFFER_PREFIX.length)) }
                // Same body, version field forced back to 1: caught by Offer.decode, not the prefix.
                val fields = Proto.decode(body, PairingEngine.OFFER_FIELD_KINDS).toMutableList()
                fields[0] = 1L
                assertThrows(Exception::class.java) { eb.receive(Proto.qr(PairingEngine.OFFER_PREFIX, Proto.encode(fields))) }
            }
        }
    }

    /**
     * The two doorbell fields are inside the signature and inside the SAS transcript.
     *
     * Reserved for T4.17 - nothing rings anything yet - but they are wire fields today, so they get
     * the same treatment as every other signed field: editing one invalidates the offer, and the
     * value that reaches `PairedContact` is the one the peer signed.
     *
     * Since T4.17 phase 2 the exchange also has to hand back the token this device *minted*
     * (`PairedContact.doorbellTokenIssued`), which is not a wire field of the incoming offer but of
     * the outgoing one. It used to be discarded with the offer, leaving the vault able to ring a
     * peer's doorbell and unable to verify a ring at its own, so the mirror-image assertions below
     * are the point of the addition, not decoration.
     */
    @Test fun doorbellFieldsAreSignedCarriedIntoTheContactAndFreshPerOffer() {
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA)
                val eb = PairingEngine(crypto, b, onionB, doorbellB)
                val offer = ea.createOffer()
                val fields = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, offer), PairingEngine.OFFER_FIELD_KINDS)
                assertArrayEquals(doorbellA, fields[6] as ByteArray)
                val token = fields[7] as ByteArray
                assertEquals(32, token.size)

                // Editing either field breaks the Ed25519 signature over the canonical encoding.
                listOf(6, 7).forEach { index ->
                    val tampered = fields.toMutableList()
                    tampered[index] = ByteArray(32) { 9 }
                    assertThrows(Exception::class.java, {
                        PairingEngine(crypto, b, onionB, doorbellB).respond(Proto.qr(PairingEngine.OFFER_PREFIX, Proto.encode(tampered)))
                    }, "field $index must be covered by the signature")
                }
                // An all-zero doorbell key is not a usable onion identity and is refused outright.
                val zeroed = fields.toMutableList().also { it[6] = ByteArray(32) }
                assertThrows(Exception::class.java) {
                    PairingEngine(crypto, b, onionB, doorbellB).respond(Proto.qr(PairingEngine.OFFER_PREFIX, Proto.encode(zeroed)))
                }

                val br = eb.respond(offer)
                val ar = ea.processResponse(requireNotNull(br.responseQr))
                exchangeBundles(ea, ar, eb, br)
                val ac = ea.confirm(ar.handle, true)
                val bc = eb.confirm(br.handle, true)
                val contactForA = ea.finish(ar.handle, bc)
                val contactForB = eb.finish(br.handle, ac)
                // Each side ends up holding the OTHER side's key and token.
                assertArrayEquals(doorbellB, contactForA.doorbellKey)
                assertArrayEquals(doorbellA, contactForB.doorbellKey)
                assertArrayEquals(token, contactForB.doorbellToken)
                assertEquals(32, contactForA.doorbellToken.size)
                assertFalse(contactForA.doorbellToken.contentEquals(contactForB.doorbellToken))

                // ...and, since T4.17, also its OWN token: the one it minted in its own offer, which
                // is what it will have to recognise when this peer rings its doorbell. `token` was
                // read out of A's offer QR before any of this ran, so this pins the exact value
                // rather than merely "some 32 bytes".
                assertArrayEquals(token, contactForA.doorbellTokenIssued)
                // The two sides are mirror images: what A issued is what B must present, and the
                // other way round. Nothing here may be confused with the peer's half.
                assertArrayEquals(contactForA.doorbellTokenIssued, contactForB.doorbellToken)
                assertArrayEquals(contactForB.doorbellTokenIssued, contactForA.doorbellToken)
                assertFalse(contactForA.doorbellTokenIssued.contentEquals(contactForA.doorbellToken))
                assertEquals(32, contactForB.doorbellTokenIssued.size)

                // Fresh per offer: the key is stable, the token is not.
                val second = PairingEngine(crypto, a, onionA, doorbellA)
                val secondFields = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, second.createOffer()), PairingEngine.OFFER_FIELD_KINDS)
                assertArrayEquals(doorbellA, secondFields[6] as ByteArray)
                assertFalse(token.contentEquals(secondFields[7] as ByteArray))
            }
        }
    }

    /**
     * Regression for the T4.16 run5 defect (c), which failed a real two-emulator pairing.
     *
     * Two independent causes, both exercised here in one run because that is how they occurred:
     *
     * 1. A's offer regenerated (the 120 s automatic refresh) **after** B had already scanned it and
     *    computed a response against it. `processResponse` looked only at the current offer, so the
     *    reply digest matched nothing and pairing died with a generic "could not complete".
     * 2. B's response reached A more than 120 s after it was minted. `readOffer` applied the
     *    QR-reading TTL to it, even though B's screen keeps a response up - and displays its
     *    countdown - for the whole 300 s exchange window.
     *
     * Fixing only one leaves the other, so a fix has to survive both at once.
     */
    @Test fun responseIsAcceptedAfterTheOfferRegeneratedAndPastTheQrReadingTtl() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val eb = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val answered = ea.createOffer()

                time += 110
                val br = eb.respond(answered)
                val response = requireNotNull(br.responseQr)

                // Cause 1: A's screen refreshes. The answered offer becomes `superseded` and a
                // brand-new one goes on screen, so the "current" offer is no longer the one B
                // replied to.
                time += 15
                ea.createOffer()

                // Cause 2: A scans the response 145 s after B minted it - past the 120 s
                // QR-reading TTL, comfortably inside the 300 s exchange window.
                time += 130
                val ar = ea.processResponse(response)

                assertEquals(br.sas, ar.sas)
                // Each side's confirmation budget runs from ITS OWN staging, so a slow relay makes
                // the two deadlines differ by exactly that delay - B staged at 1110, A at 1255.
                assertEquals(1255L + PairingEngine.CONFIRMATION_TTL_SECONDS, ar.expiresAt)
                assertEquals(1110L + PairingEngine.CONFIRMATION_TTL_SECONDS, br.expiresAt)

                // And the pairing genuinely completes: the bundle fetch resolves against the
                // superseded offer that is now the staged exchange's own local offer.
                exchangeBundles(ea, ar, eb, br)
                val ac = ea.confirm(ar.handle, true)
                val bc = eb.confirm(br.handle, true)
                assertEquals(b.id, ea.finish(ar.handle, bc).peerId)
                assertEquals(a.id, eb.finish(br.handle, ac).peerId)
            }
        }
    }

    /**
     * The widened response window is a different deadline, not the absence of one. A standing
     * invitation keeps the 120 s reading TTL (covered by
     * `rejectsReplayExpiredTamperedAndUnconfirmedOffers`); a response gets the 300 s exchange
     * window, and past it is still refused.
     */
    @Test fun aResponseIsStillRefusedOnceTheExchangeWindowItselfHasClosed() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val eb = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val response = requireNotNull(eb.respond(ea.createOffer()).responseQr)

                time += PairingEngine.PENDING_TTL_SECONDS
                assertThrows(Exception::class.java) { ea.processResponse(response) }

                // And an offer that aged out of its own window is no longer a candidate at all,
                // which is the same rule `bundleFor` applies - one notion of "still live".
                val ec = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val stale = ec.createOffer()
                val nonce = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, stale), PairingEngine.OFFER_FIELD_KINDS)[4] as ByteArray
                time += PairingEngine.PENDING_TTL_SECONDS
                assertNull(ec.bundleFor(nonce))
                val late = PairingEngine(crypto, b, onionB, doorbellB, { time - PairingEngine.PENDING_TTL_SECONDS })
                val lateResponse = requireNotNull(late.respond(stale).responseQr)
                assertThrows(Exception::class.java) { ec.processResponse(lateResponse) }
            }
        }
    }

    /**
     * Display and validity are separate: three offers are alive at once, and all three answer.
     *
     * The peak of the steady state, derived rather than picked: an offer lives 300 s and the screen
     * rotates every 120 s, so `ceil(300 / 120) = 3` overlap. Offers minted at t = 0, 120 and 240 are
     * all inside their own window at t = 240, even though only the newest is on screen.
     *
     * The first fix for run5's defect (c) kept the current offer plus **one** predecessor, which is
     * exactly one short of this - so the oldest of three was burned 60 s before its own window
     * closed, and a peer holding it failed for the same reason as the original defect.
     */
    @Test fun everyOfferInsideItsOwnWindowStaysAnswerableNotJustTheCurrentAndOnePredecessor() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
            val first = nonceOf(ea.createOffer())
            time += PairingEngine.OFFER_TTL_SECONDS
            val second = nonceOf(ea.createOffer())
            time += PairingEngine.OFFER_TTL_SECONDS
            val third = nonceOf(ea.createOffer())

            // t = 1240: the first is 240 s old, the second 120 s, the third brand new. All three are
            // inside their own 300 s window, so all three must still hand out their bundle - and each
            // exactly once, which is what proves they are three distinct live offers and not one
            // answered three times.
            listOf(first, second, third).forEachIndexed { index, nonce ->
                assertNotNull(ea.bundleFor(nonce), "offer $index must still be answerable")
                assertNull(ea.bundleFor(nonce), "offer $index must be answered exactly once")
            }
            // Asserted after the behaviour, not before it, so that shrinking the cap makes this test
            // fail on what a peer would actually observe rather than on the constant itself.
            assertEquals(3, PairingEngine.MAX_LIVE_OFFERS, "ceil(PENDING_TTL / OFFER_TTL) = 3")

            // t = 1300: the first has aged out on its own schedule; the other two have not.
            time += PairingEngine.PENDING_TTL_SECONDS - 2 * PairingEngine.OFFER_TTL_SECONDS
            val fourth = nonceOf(ea.createOffer())
            assertNull(ea.bundleFor(first), "the first offer is past its own window")
            assertNotNull(ea.bundleFor(fourth))
        }
    }

    /** A response to the OLDEST of three concurrently valid offers still completes a pairing. */
    @Test fun aResponseToTheOldestOfThreeLiveOffersStillPairs() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val eb = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val answered = ea.createOffer()

                // B scans the first offer, then A's screen rotates twice before B's reply arrives.
                time += 110
                val br = eb.respond(answered)
                time += 10
                ea.createOffer()
                time += PairingEngine.OFFER_TTL_SECONDS
                ea.createOffer()

                // t = 1240: the answered offer is the OLDEST of three live ones - the entry the
                // current-plus-one-predecessor version had already burned.
                val ar = ea.processResponse(requireNotNull(br.responseQr))
                assertEquals(br.sas, ar.sas)
                assertEquals(1240L + PairingEngine.CONFIRMATION_TTL_SECONDS, ar.expiresAt)
                exchangeBundles(ea, ar, eb, br)
                val ac = ea.confirm(ar.handle, true)
                val bc = eb.confirm(br.handle, true)
                assertEquals(b.id, ea.finish(ar.handle, bc).peerId)
                assertEquals(a.id, eb.finish(br.handle, ac).peerId)
            }
        }
    }

    /** Staging an exchange discards the key material of every offer that was not answered. */
    @Test fun stagingAnExchangeBurnsTheOtherLiveOffers() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val eb = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val answered = ea.createOffer()
                time += 110
                val br = eb.respond(answered)
                time += 10
                val abandoned = nonceOf(ea.createOffer())

                val ar = ea.processResponse(requireNotNull(br.responseQr))

                // The offer nobody answered is gone: this device holds one exchange, so it can never
                // lead anywhere, and answering for it is what the rate-limit rule forbids.
                assertNull(ea.bundleFor(abandoned))
                // The answered one is still answerable - the peer may still be fetching its bundle.
                assertNotNull(ea.bundleFor(br.peerBundleNonce))
                assertEquals(6, ar.sas.length)
            }
        }
    }

    /**
     * Regression for T4.16 run6a/6c: a slow acquisition must not eat the human phase.
     *
     * Both live two-rotation attempts (250 s and 253 s between relays) were **accepted** by the
     * protocol and then ran out of time before the two people could finish. Under the old rule the
     * exchange expired [PairingEngine.PENDING_TTL_SECONDS] after the oldest offer, so ~240 s spent
     * acquiring left 4-35 s for the Tor bundle fetch, the spoken SAS, the alias and two confirmation
     * QRs. The mechanics were sound - run6b, with one rotation, completed with 165 s to spare - the
     * budget arithmetic was not.
     */
    @Test fun aSlowAcquisitionNoLongerEatsTheConfirmationBudget() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val eb = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val answered = ea.createOffer()
                time += 110
                val br = eb.respond(answered)
                time += 10
                ea.createOffer()
                time += PairingEngine.OFFER_TTL_SECONDS
                ea.createOffer()
                time += 5
                val ar = ea.processResponse(requireNotNull(br.responseQr))

                // 245 s of the acquisition budget is gone by the time the SAS exists. The old
                // offer-anchored deadline sat at 1300 - 55 s after staging - so a human phase of
                // 80 s was refused. The behaviour is asserted FIRST, so shrinking this budget fails
                // on a pairing that does not complete rather than on an arithmetic mismatch.
                val stagedAt = 1245L
                val oldDeadline = 1000L + PairingEngine.PENDING_TTL_SECONDS
                exchangeBundles(ea, ar, eb, br)
                time += 80
                assertTrue(time > oldDeadline, "this is the window the old rule refused")
                val ac = ea.confirm(ar.handle, true)
                val bc = eb.confirm(br.handle, true)
                assertEquals(b.id, ea.finish(ar.handle, bc).peerId)
                assertEquals(a.id, eb.finish(br.handle, ac).peerId)
                assertEquals(stagedAt + PairingEngine.CONFIRMATION_TTL_SECONDS, ar.expiresAt)
            }
        }
    }

    /**
     * The confirmation phase has a new deadline, not no deadline - and it still fails with the
     * specific "Pairing expired" the UI maps to `pairing_timed_out`, never a generic error.
     */
    @Test fun confirmationPastItsOwnBudgetStillFailsWithTheSpecificExpiryError() {
        var time = 1000L
        PairingIdentity.create(crypto).use { a ->
            PairingIdentity.create(crypto).use { b ->
                val ea = PairingEngine(crypto, a, onionA, doorbellA, { time })
                val eb = PairingEngine(crypto, b, onionB, doorbellB, { time })
                val br = eb.respond(ea.createOffer())
                val ar = ea.processResponse(requireNotNull(br.responseQr))
                exchangeBundles(ea, ar, eb, br)

                // One second inside the budget the SAS is still confirmable.
                time = 1000L + PairingEngine.CONFIRMATION_TTL_SECONDS - 1
                ea.confirm(ar.handle, true)

                // One second past it, the exchange is gone - with the specific message.
                time = 1000L + PairingEngine.CONFIRMATION_TTL_SECONDS
                val failure = assertThrows(IllegalStateException::class.java) { eb.confirm(br.handle, true) }
                assertEquals("Pairing expired", failure.message)
            }
        }
    }

    /** The nonce of an offer QR, read out exactly as a scanner would. */
    private fun nonceOf(qr: String): ByteArray =
        Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, qr), PairingEngine.OFFER_FIELD_KINDS)[4] as ByteArray

    /** Relays each side's real key bundle, standing in for `MessagingEngine`'s Tor fetch. */
    private fun exchangeBundles(ea: PairingEngine, ar: PairingProgress, eb: PairingEngine, br: PairingProgress) {
        ea.acceptPeerBundle(ar.handle, requireNotNull(eb.bundleFor(ar.peerBundleNonce)))
        eb.acceptPeerBundle(br.handle, requireNotNull(ea.bundleFor(br.peerBundleNonce)))
    }
}

class ProtocolAdversarialTest {
    /** See [ProtocolTest]; the same reserved-for-T4.17 doorbell key fixtures. */
    private fun doorbellKey(seed:Int)=ByteArray(32) { ((it + seed) or 1).toByte() }
    @Test fun officialLibsignalX25519MatchesRfc7748Section61() {
        // RFC 7748 §6.1: https://www.rfc-editor.org/rfc/rfc7748.html#section-6.1
        // Official 0.102.2 ECPrivateKey.calculateAgreement directly invokes Signal native X25519.
        val aliceSecret = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val bobSecret = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".unhex()
        val alicePublic = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".unhex()
        val bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".unhex()
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".unhex()
        try {
            val alice = ECPrivateKey(aliceSecret)
            val bob = ECPrivateKey(bobSecret)
            assertArrayEquals(alicePublic, alice.getPublicKey().publicKeyBytes)
            assertArrayEquals(bobPublic, bob.getPublicKey().publicKeyBytes)
            assertArrayEquals(expected, alice.calculateAgreement(ECPublicKey.fromPublicKeyBytes(bobPublic)))
            assertArrayEquals(expected, bob.calculateAgreement(ECPublicKey.fromPublicKeyBytes(alicePublic)))
        } finally { aliceSecret.fill(0); bobSecret.fill(0) }
    }
    @Test fun boundedMalformedQrCorpusRejectsWithoutChangingReceiverState() {
        PairingIdentity.create(crypto).use { sender ->
            PairingIdentity.create(crypto).use { receiver ->
                val source = PairingEngine(crypto, sender, "a".repeat(56)+".onion", doorbellKey(1), { 1000L })
                val consumed = mutableSetOf<String>()
                val target = PairingEngine(crypto, receiver, "b".repeat(56)+".onion", doorbellKey(2), { 1000L }, consumed)
                val valid = source.createOffer()
                val raw = Proto.fromQr(PairingEngine.OFFER_PREFIX, valid)
                val random = java.util.Random(0x57465152L)
                val corpus = mutableListOf<String>()
                repeat(128) {
                    val changed = raw.copyOf()
                    val index = random.nextInt(changed.size)
                    changed[index] = (changed[index].toInt() xor (1 shl random.nextInt(8))).toByte()
                    corpus += Proto.qr(PairingEngine.OFFER_PREFIX, changed)
                }
                repeat(32) { corpus += Proto.qr(PairingEngine.OFFER_PREFIX, raw.copyOf(random.nextInt(raw.size))) }
                for (bytes in listOf(byteArrayOf(), byteArrayOf(8), ByteArray(10) { 0x80.toByte() },
                    byteArrayOf(8, 1, 18, 0xff.toByte(), 0xff.toByte(), 0x7f), raw+byteArrayOf(8,1))) {
                    corpus += Proto.qr(PairingEngine.OFFER_PREFIX, bytes)
                }
                // Wrong version prefixes: "nomessages:3:" does not exist yet and "nomessages:1:" is
                // the format T4.16 replaced. Both must be refused before any parsing happens.
                // `substring(OFFER_PREFIX.length)` instead of a hard-coded 13 so a future rename
                // cannot silently turn one of these cases into the valid payload - which is exactly
                // what happened to the literal "nomessages:2:" that used to sit here.
                val body = valid.substring(PairingEngine.OFFER_PREFIX.length)
                corpus += listOf(valid+"=", valid+"\u0000", "nomessages:3:"+body, "nomessages:1:"+body,
                    PairingEngine.OFFER_PREFIX+"A".repeat(8193), PairingEngine.OFFER_PREFIX+"/", "")
                val before = receiver.export()
                try {
                    corpus.forEachIndexed { index, malformed ->
                        assertThrows(Exception::class.java, { target.receive(malformed) }, "Corpus case $index must reject")
                    }
                    assertTrue(consumed.isEmpty())
                    assertArrayEquals(before, receiver.export())
                    // A final valid input proves parser failures did not poison the engine.
                    assertEquals(sender.id, target.receive(valid).peerId)
                } finally { before.fill(0); source.cancel(); target.cancel() }
            }
        }
    }
    @Test fun canonicalCborUsesShortestUnsignedLengthsAndDomainArray() {
        assertArrayEquals(byteArrayOf(0x83.toByte(),0x61,0x78,0x01,0x41,0x00),cbor("x",1,byteArrayOf(0)))
        assertEquals("8417181818ff190100",cbor(23,24,255,256).hex())
    }
    private val crypto by lazy { NativeCrypto() }
    private fun engine(id:PairingIdentity)=PairingEngine(crypto,id,"a".repeat(56)+".onion",doorbellKey(id.id.hashCode()))
    private fun pair(a:PairingIdentity,b:PairingIdentity):ByteArray {
        val ea=engine(a); val eb=engine(b)
        val br=eb.receive(ea.createOffer()); val ar=ea.receive(br.responseQr!!)
        assertEquals(ar.sas,br.sas)
        // Stands in for MessagingEngine's Tor fetch of the peer's key bundle (QR format 2, T4.16).
        ea.acceptPeerBundle(ar.handle,requireNotNull(eb.bundleFor(ar.peerBundleNonce)))
        eb.acceptPeerBundle(br.handle,requireNotNull(ea.bundleFor(br.peerBundleNonce)))
        val ac=ea.confirm(ar.handle,true); val bc=eb.confirm(br.handle,true)
        val evidence=ea.finish(ar.handle,bc).evidence
        eb.finish(br.handle,ac)
        return evidence
    }
    @Test fun rejectsTamperedSignatureAndUnknownDuplicateOrOversizedProtobuf() {
        val a=PairingIdentity.create(crypto); val b=PairingIdentity.create(crypto)
        val offer=engine(a).createOffer()
        val raw=Proto.fromQr(PairingEngine.OFFER_PREFIX,offer)
        raw[raw.lastIndex]=(raw.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { engine(b).respond(Proto.qr(PairingEngine.OFFER_PREFIX,raw)) }
        val fresh=Proto.fromQr(PairingEngine.OFFER_PREFIX,offer)
        assertThrows(Exception::class.java) { engine(b).respond(Proto.qr(PairingEngine.OFFER_PREFIX,fresh+byteArrayOf(8,1))) }
        assertThrows(Exception::class.java) { engine(b).respond(PairingEngine.OFFER_PREFIX+"A".repeat(8193)) }
    }
    /**
     * Format 2 keeps the bundle out of the QR, so the substitution this used to test - swapping the
     * duplicated `signal` field and re-signing - no longer has a field to attack. The equivalent
     * attack is swapping the *hash*: the attacker re-signs an offer that commits to a bundle they
     * control. It fails for a different and stronger reason - the signature is over their own key,
     * so the offer is simply a different identity's offer, and the SAS the two humans compare is
     * taken over that transcript.
     *
     * What is asserted here is the part a machine can check: an offer whose `bundleHash` field was
     * edited is rejected outright (signature), and one that was edited AND re-signed with the
     * original key is impossible without that key.
     */
    @Test fun rejectsBundleHashSubstitutionInASignedOffer() {
        val a=PairingIdentity.create(crypto); val b=PairingIdentity.create(crypto)
        val offer=engine(a).createOffer()
        val values=Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX,offer),PairingEngine.OFFER_FIELD_KINDS).toMutableList()
        values[5]=ByteArray(32) // a hash of the attacker's own choosing
        assertThrows(Exception::class.java) { engine(b).respond(Proto.qr(PairingEngine.OFFER_PREFIX,Proto.encode(values))) }
        // Re-signing with a DIFFERENT key does not help either: the offer then belongs to that key,
        // and `ed` no longer matches the identity the victim believes they scanned.
        val impostor=PairingIdentity.create(crypto)
        // "nomessages-offer-v2": matches Offer.canonical(), bumped from v1 on 2026-09-17 (T4.16).
        val signed=cbor("nomessages-offer-v2",*values.take(7).toTypedArray())
        values[7]=crypto.sign(impostor.signing.secretKey,signed)
        assertThrows(Exception::class.java) { engine(b).respond(Proto.qr(PairingEngine.OFFER_PREFIX,Proto.encode(values))) }
    }
    @Test fun completeCliqueRequiresBothSignaturesAndListsMissingPairsDeterministically() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto);val c=PairingIdentity.create(crypto)
        val ab=pair(a,b);val ac=pair(a,c);val bc=pair(b,c)
        val members=listOf(c.id,a.id,b.id)
        val policy=CliquePolicy(crypto)
        assertEquals(emptyList<Any>(),policy.missingPairs(members,listOf(ab,ac,bc)))
        assertEquals(policy.missingPairs(members,listOf(ab)),policy.missingPairs(members.reversed(),listOf(ab)))
        val forged=ab.copyOf();forged[forged.lastIndex]=(forged.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { policy.missingPairs(members,listOf(forged)) }
        assertThrows(Exception::class.java) { policy.missingPairs(listOf(a.id,a.id,b.id),emptyList()) }
        assertThrows(Exception::class.java) { policy.missingPairs(listOf(a.id,b.id),emptyList()) }
        assertThrows(Exception::class.java) { policy.missingPairs(List(101) {a.id},emptyList()) }
        val fakeMembers=(1..100).map { it.toString(16).padStart(64,'0') }
        assertEquals(4950,policy.missingPairs(fakeMembers,emptyList()).size)
    }
    @Test fun existingSignalIdentityCannotBeSubstitutedEvenWithSameEd25519Key() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto)
        pair(a,b)
        val forgedIdentity=pack {
            writeInt(1);blob(a.signing.publicKey);blob(a.signing.secretKey);blob(SignalSessions.create(a.id).export())
        }
        val changed=PairingIdentity.restore(forgedIdentity)
        val ea=engine(changed);val eb=engine(b)
        val br=eb.respond(ea.createOffer());val ar=ea.processResponse(br.responseQr!!)
        ea.acceptPeerBundle(ar.handle,requireNotNull(eb.bundleFor(ar.peerBundleNonce)))
        eb.acceptPeerBundle(br.handle,requireNotNull(ea.bundleFor(br.peerBundleNonce)))
        val ac=ea.confirm(ar.handle,true);eb.confirm(br.handle,true)
        val pinned=b.sessions.peerIdentity(a.id)
        assertThrows(Exception::class.java) { eb.finish(br.handle,ac) }
        assertArrayEquals(pinned,b.sessions.peerIdentity(a.id))
    }
    @Test fun consumedOffersRemainRejectedAfterEngineRestore() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto)
        val consumed=mutableSetOf<String>()
        val qr=engine(a).createOffer()
        PairingEngine(crypto,b,"a".repeat(56)+".onion",doorbellKey(7),consumed=consumed).respond(qr)
        assertThrows(Exception::class.java) { PairingEngine(crypto,b,"a".repeat(56)+".onion",doorbellKey(7),consumed=consumed.toMutableSet()).respond(qr) }
    }
    @Test fun confirmationCannotBeReplayedIntoOtherExchange() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto)
        val ea=engine(a);val eb=engine(b)
        val br=eb.respond(ea.createOffer());val ar=ea.processResponse(br.responseQr!!)
        ea.acceptPeerBundle(ar.handle,requireNotNull(eb.bundleFor(ar.peerBundleNonce)))
        eb.acceptPeerBundle(br.handle,requireNotNull(ea.bundleFor(br.peerBundleNonce)))
        val bc=eb.confirm(br.handle,true)
        ea.confirm(ar.handle,true)
        val wrong=Proto.decode(Proto.fromQr(PairingEngine.CONFIRM_PREFIX,bc),"bbb").toMutableList()
        wrong[1]=a.publicKey
        assertThrows(Exception::class.java) { ea.finish(ar.handle,Proto.qr(PairingEngine.CONFIRM_PREFIX,Proto.encode(wrong))) }
        assertThrows(Exception::class.java) { ea.finish(ar.handle,bc) }
    }
    @Test fun multiplePrekeyMessagesBeforeAcknowledgementDecryptInOrder() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto)
        pair(a,b)
        val first=a.sessions.encrypt(b.id,"one".toByteArray())
        val second=a.sessions.encrypt(b.id,"two".toByteArray())
        assertArrayEquals("one".toByteArray(),b.sessions.decrypt(a.id,first))
        assertArrayEquals("two".toByteArray(),b.sessions.decrypt(a.id,second))
        val restored=PairingIdentity.restore(b.export())
        assertArrayEquals("ack".toByteArray(),a.sessions.decrypt(b.id,restored.sessions.encrypt(a.id,"ack".toByteArray())))
    }
    @Test fun simultaneousSignalInitiationResolvesBothDirections() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto)
        pair(a,b)
        val am=a.sessions.encrypt(b.id,"from A".toByteArray())
        val bm=b.sessions.encrypt(a.id,"from B".toByteArray())
        assertArrayEquals("from A".toByteArray(),b.sessions.decrypt(a.id,am))
        assertArrayEquals("from B".toByteArray(),a.sessions.decrypt(b.id,bm))
        val next=a.sessions.encrypt(b.id,"next".toByteArray())
        assertArrayEquals("next".toByteArray(),b.sessions.decrypt(a.id,next))
    }
    @Test fun candidateDecryptFindsAuthenticatedPeerAndRollsBackAllFailedTrials() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto);val c=PairingIdentity.create(crypto)
        pair(a,b);pair(b,c)
        val first=a.sessions.encrypt(b.id,"first".toByteArray())
        val incoming=b.sessions.decryptCandidates(listOf(c.id,a.id),first,maxRegularTrials=1)!!
        assertEquals(a.id,incoming.peerId)
        assertArrayEquals("first".toByteArray(),incoming.plaintext)
        a.sessions.decrypt(b.id,b.sessions.encrypt(a.id,"ack".toByteArray()))
        val regular=a.sessions.encrypt(b.id,"regular".toByteArray())
        val broken=regular.copyOf();broken[broken.lastIndex]=(broken.last().toInt() xor 64).toByte()
        val before=b.export()
        assertNull(b.sessions.decryptCandidates(listOf(c.id,a.id),broken))
        assertArrayEquals(before,b.export())
        assertNull(b.sessions.decryptCandidates(listOf(c.id,a.id),regular,maxRegularTrials=1))
        assertArrayEquals(before,b.export())
        assertEquals(a.id,b.sessions.decryptCandidates(listOf(a.id,c.id),regular,maxRegularTrials=1)!!.peerId)
        assertThrows(Exception::class.java) { b.sessions.validateCiphertext(byteArrayOf(2,0)) }
    }
    @Test fun ciphertextTamperRollsBackAndUnknownPeerCannotBecomeTrusted() {
        val a=PairingIdentity.create(crypto);val b=PairingIdentity.create(crypto);val stranger=PairingIdentity.create(crypto)
        pair(a,b)
        val encrypted=a.sessions.encrypt(b.id,"message".toByteArray())
        val tampered=encrypted.copyOf();tampered[tampered.lastIndex]=(tampered.last().toInt() xor 128).toByte()
        val before=b.export()
        assertThrows(Exception::class.java) { b.sessions.decrypt(a.id,tampered) }
        assertArrayEquals(before,b.export())
        assertThrows(Exception::class.java) { b.sessions.decrypt(stranger.id,encrypted) }
        assertArrayEquals("message".toByteArray(),b.sessions.decrypt(a.id,encrypted))
        val snapshot=a.export()
        a.sessions.encrypt(b.id,"will roll back".toByteArray())
        a.restoreInPlace(snapshot)
        assertArrayEquals(snapshot,a.export())
    }
}
