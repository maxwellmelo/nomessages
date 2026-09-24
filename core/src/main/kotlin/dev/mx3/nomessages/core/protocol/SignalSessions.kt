package dev.mx3.nomessages.core.protocol

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.*
import org.signal.libsignal.protocol.kem.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.message.*
import java.security.SecureRandom
import java.util.UUID

data class DecryptedSignal(val peerId:String,val plaintext:ByteArray)

/** Actual official libsignal; QR is the prekey distribution channel, with no account server. */
class SignalSessions private constructor(private val store: BlobSignalStore, internal val localId: String): AutoCloseable {
    companion object {
        internal fun create(localId: String) = SignalSessions(BlobSignalStore(IdentityKeyPair.generate().serialize(), SecureRandom().nextInt(16380)+1), localId)
        fun restore(blob: ByteArray): SignalSessions = unpack(blob) {
            require(readInt()==1); val id=readUTF(); id.unhex(); SignalSessions(BlobSignalStore.restore(this.blob()),id)
        }
    }
    private var closed=false
    fun export(): ByteArray { check(!closed); return pack { writeInt(1); writeUTF(localId); blob(store.export()) } }
    fun peerIdentity(peerId:String):ByteArray { check(!closed); return store.pins[peerId]?.copyOf() ?: error("Peer is not paired") }
    private fun address(id:String): SignalProtocolAddress { id.unhex(); return SignalProtocolAddress(id,1) }
    private fun cipher(peerId:String)=SessionCipher(store,address(localId),address(peerId))
    private fun <T> atomic(block:()->T):T {
        check(!closed)
        store.begin()
        try { val result=block(); store.commit(); return result }
        catch(e: Exception) { store.rollback(); throw e }
    }
    fun encrypt(peerId:String, plaintext:ByteArray):ByteArray = atomic {
        require(plaintext.size<=FrameCodec.MAX_MESSAGE-8192)
        val cipher=cipher(peerId).encrypt(plaintext)
        byteArrayOf(cipher.type.toByte())+cipher.serialize()
    }
    fun decrypt(peerId:String,ciphertext:ByteArray):ByteArray {
        check(!closed)
        val parsed=parse(ciphertext)
        return decryptParsed(peerId,parsed)
    }
    /** Parse only once when trial-authenticating a newly accepted stream. */
    fun decryptCandidates(peerIds:Collection<String>,ciphertext:ByteArray,maxRegularTrials:Int=1024):DecryptedSignal? {
        check(!closed)
        require(peerIds.size in 1..1024 && maxRegularTrials in 1..1024)
        val parsed=parse(ciphertext)
        val sender=parsed.preKey?.identityKey?.serialize()
        var regularTrials=0
        for(peerId in peerIds) {
            val pinned=store.pins[peerId] ?: continue
            if(sender!=null && !pinned.contentEquals(sender)) continue
            if(sender==null && regularTrials++>=maxRegularTrials) break
            try { return DecryptedSignal(peerId,decryptParsed(peerId,parsed)) }
            catch(_:Exception) { /* Each rejected candidate rolls back only touched entries. */ }
        }
        return null
    }
    fun validateCiphertext(ciphertext:ByteArray) { check(!closed); parse(ciphertext) }
    private data class Parsed(val preKey:PreKeySignalMessage?=null,val message:SignalMessage?=null)
    private fun parse(ciphertext:ByteArray):Parsed {
        require(ciphertext.size in 2..FrameCodec.MAX_MESSAGE)
        require(ciphertext[0].toInt() in listOf(CiphertextMessage.PREKEY_TYPE,CiphertextMessage.WHISPER_TYPE)) { "Unknown Signal ciphertext type" }
        val bytes=ciphertext.copyOfRange(1,ciphertext.size)
        return when(ciphertext[0].toInt()) {
            CiphertextMessage.PREKEY_TYPE -> Parsed(preKey=PreKeySignalMessage(bytes))
            CiphertextMessage.WHISPER_TYPE -> Parsed(message=SignalMessage(bytes))
            else -> throw IllegalArgumentException("Unknown Signal ciphertext type")
        }
    }
    private fun decryptParsed(peerId:String,parsed:Parsed):ByteArray = atomic {
        require(store.pins.containsKey(peerId)) { "Peer is not paired" }
        parsed.preKey?.let { cipher(peerId).decrypt(it) } ?: cipher(peerId).decrypt(parsed.message!!)
    }
    internal fun newBundle(): SignalBundle = atomic {
        val random=SecureRandom()
        var id:Int
        do { id=random.nextInt(Int.MAX_VALUE-1)+1 } while(store.pre.containsKey(id) || store.signed.containsKey(id) || store.kyber.containsKey(id))
        val identity=store.getIdentityKeyPair()
        val pre=ECKeyPair.generate(); val signed=ECKeyPair.generate(); val kem=KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signedSig=identity.privateKey.calculateSignature(signed.publicKey.serialize())
        val kemSig=identity.privateKey.calculateSignature(kem.publicKey.serialize())
        val timestamp=System.currentTimeMillis()
        store.storePreKey(id,PreKeyRecord(id,pre))
        store.storeSignedPreKey(id,SignedPreKeyRecord(id,timestamp,signed,signedSig))
        store.storeKyberPreKey(id,KyberPreKeyRecord(id,timestamp,kem,kemSig))
        SignalBundle(store.getLocalRegistrationId(),id,identity.publicKey.serialize(),pre.publicKey.serialize(),signed.publicKey.serialize(),signedSig,kem.publicKey.serialize(),kemSig)
    }
    internal fun discardBundle(id:Int) {
        check(!closed)
        store.removePreKey(id); store.removeSignedPreKey(id); store.discardKyber(id)
    }
    internal fun establish(peerId:String,bundle:SignalBundle) = atomic {
        val prior=store.pins[peerId]
        require(prior==null || prior.contentEquals(bundle.identity)) { "Signal identity substitution" }
        require(store.pins.none { (id,key) -> id!=peerId && key.contentEquals(bundle.identity) }) { "Signal identity already belongs to another contact" }
        store.pin(peerId,bundle.identity)
        SessionBuilder(store,address(peerId),address(localId)).process(bundle.native())
    }
    override fun close() { if(!closed) { store.close(); closed=true } }
}

/**
 * A complete PQXDH prekey bundle.
 *
 * Wire version 3 (2026-09-17, T4.16) is **self-contained**: it carries `identity` and `pre` itself
 * instead of borrowing them from the enclosing pairing offer. Version 2 could omit them because the
 * offer QR repeated both as signed outer fields; the QR now carries only `SHA-256(encode())`, so the
 * blob has to stand on its own. What binds it to the scanned identity is that hash, which is inside
 * the Ed25519-signed part of the offer - see `Pairing.kt` and `docs/security-model.md`.
 *
 * The encoded size is ~1832 bytes, dominated by the 1569-byte Kyber-1024 public key. That is exactly
 * why it no longer travels inside the QR.
 */
internal data class SignalBundle(val registration:Int,val keyId:Int,val identity:ByteArray,val pre:ByteArray,val signed:ByteArray,val signature:ByteArray,val kyber:ByteArray,val kyberSignature:ByteArray) {
    fun encode()=pack { writeInt(VERSION); writeInt(registration); writeInt(keyId); listOf(identity,pre,signed,signature,kyber,kyberSignature).forEach { blob(it) } }
    fun native()=PreKeyBundle(registration,1,keyId,ECPublicKey(pre),keyId,ECPublicKey(signed),signature,IdentityKey(identity),keyId,KEMPublicKey(kyber),kyberSignature)
    companion object {
        const val VERSION=3
        /** Upper bound for a transported bundle; the real encoding is ~1832 bytes. */
        const val MAX_ENCODED_BYTES=4096
        fun decode(bytes:ByteArray)=unpack(bytes,MAX_ENCODED_BYTES) {
            require(readInt()==VERSION) { "Unsupported Signal bundle version" }
            val registration=readInt(); val id=readInt()
            require(registration in 1..16380 && id>0)
            val values=(0..5).map { blob(2048) }
            require(values[0].size==33 && values[1].size==33 && values[2].size==33 && values[3].size==64 && values[4].size==1569 && values[5].size==64)
            val public=ECPublicKey(values[0])
            require(public.verifySignature(values[2],values[3]) && public.verifySignature(values[4],values[5])) { "Invalid signed Signal bundle" }
            SignalBundle(registration,id,values[0],values[1],values[2],values[3],values[4],values[5]).also { it.native() }
        }
    }
}

/** Serialized records returned as fresh native objects: mutations cannot escape store/rollback. */
private class BlobSignalStore(private val identityBytes:ByteArray,private val registration:Int):SignalProtocolStore,AutoCloseable {
    val pins=sortedMapOf<String,ByteArray>()
    val pre=sortedMapOf<Int,ByteArray>()
    val signed=sortedMapOf<Int,ByteArray>()
    val kyber=sortedMapOf<Int,ByteArray>()
    private val sessions=sortedMapOf<String,ByteArray>()
    private data class Change(val previous:ByteArray?,val restore:()->Unit)
    private var journal:LinkedHashMap<String,Change>?=null
    fun begin() { check(journal==null); journal=linkedMapOf() }
    fun commit() { val changes=journal ?: error("No store transaction"); journal=null; changes.values.forEach { it.previous?.fill(0) } }
    fun rollback() { val changes=journal ?: error("No store transaction"); journal=null; changes.values.toList().asReversed().forEach { it.restore() } }
    /** Retain only the first prior value of touched entries, never clone untouched peers. */
    private fun <K> replace(map:MutableMap<K,ByteArray>,namespace:String,key:K,value:ByteArray?) {
        val old=map[key]
        val changes=journal
        if(changes==null) old?.fill(0)
        else {
            val token="$namespace:$key"
            val saved=changes[token]
            if(saved==null) {
                changes[token]=Change(old) {
                    val current=map.remove(key)
                    if(current!==old) current?.fill(0)
                    if(old!=null) map[key]=old
                }
            } else if(old!==saved.previous) old?.fill(0)
        }
        if(value==null) map.remove(key) else map[key]=value
    }
    fun pin(peerId:String,identity:ByteArray) { replace(pins,"identity",peerId,identity.copyOf()) }
    fun discardKyber(id:Int) { replace(kyber,"kyber",id,null) }
    private fun key(address:SignalProtocolAddress):String { require(address.deviceId==1); return address.name }
    override fun getIdentityKeyPair()=IdentityKeyPair(identityBytes)
    override fun getLocalRegistrationId()=registration
    override fun getIdentity(address:SignalProtocolAddress)=pins[key(address)]?.let(::IdentityKey)
    override fun isTrustedIdentity(address:SignalProtocolAddress,identityKey:IdentityKey,direction:IdentityKeyStore.Direction)=pins[key(address)]?.contentEquals(identityKey.serialize())==true
    override fun saveIdentity(address:SignalProtocolAddress,identityKey:IdentityKey):IdentityKeyStore.IdentityChange {
        require(isTrustedIdentity(address,identityKey,IdentityKeyStore.Direction.SENDING)) { "Unpaired or changed identity" }
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }
    override fun loadPreKey(id:Int)=PreKeyRecord(pre[id] ?: throw InvalidKeyIdException("Unknown prekey"))
    override fun storePreKey(id:Int,record:PreKeyRecord) { replace(pre,"pre",id,record.serialize()) }
    override fun containsPreKey(id:Int)=pre.containsKey(id)
    override fun removePreKey(id:Int) { replace(pre,"pre",id,null) }
    override fun loadSignedPreKey(id:Int)=SignedPreKeyRecord(signed[id] ?: throw InvalidKeyIdException("Unknown signed prekey"))
    override fun loadSignedPreKeys()=signed.values.map(::SignedPreKeyRecord)
    override fun storeSignedPreKey(id:Int,record:SignedPreKeyRecord) { replace(signed,"signed",id,record.serialize()) }
    override fun containsSignedPreKey(id:Int)=signed.containsKey(id)
    override fun removeSignedPreKey(id:Int) { replace(signed,"signed",id,null) }
    override fun loadKyberPreKey(id:Int)=KyberPreKeyRecord(kyber[id] ?: throw InvalidKeyIdException("Unknown Kyber prekey"))
    override fun loadKyberPreKeys()=kyber.values.map(::KyberPreKeyRecord)
    override fun storeKyberPreKey(id:Int,record:KyberPreKeyRecord) { replace(kyber,"kyber",id,record.serialize()) }
    override fun containsKyberPreKey(id:Int)=kyber.containsKey(id)
    // Every QR uses a distinct one-time Kyber prekey. No last-resort key or reuse cache.
    override fun markKyberPreKeyUsed(id:Int,signedId:Int,baseKey:ECPublicKey) { require(kyber.containsKey(id)); discardKyber(id) }
    override fun loadSession(address:SignalProtocolAddress)=sessions[key(address)]?.let(::SessionRecord) ?: SessionRecord()
    override fun loadExistingSessions(addresses:List<SignalProtocolAddress>)=addresses.map { sessions[key(it)]?.let(::SessionRecord) ?: throw NoSessionException("No session") }
    override fun getSubDeviceSessions(name:String)=emptyList<Int>()
    override fun storeSession(address:SignalProtocolAddress,record:SessionRecord) { replace(sessions,"session",key(address),record.serialize()) }
    override fun containsSession(address:SignalProtocolAddress)=sessions.containsKey(key(address))
    override fun deleteSession(address:SignalProtocolAddress) { replace(sessions,"session",key(address),null) }
    override fun deleteAllSessions(name:String) { replace(sessions,"session",name,null) }
    override fun loadSenderKey(sender:SignalProtocolAddress,distributionId:UUID):SenderKeyRecord?=null
    override fun storeSenderKey(sender:SignalProtocolAddress,distributionId:UUID,record:SenderKeyRecord) { error("Groups require MLS") }
    fun export()=pack {
        writeInt(1); blob(identityBytes); writeInt(registration)
        for(map in listOf(pins,sessions)) { writeInt(map.size); map.forEach { (k,v)-> writeUTF(k); blob(v) } }
        for(map in listOf(pre,signed,kyber)) { writeInt(map.size); map.forEach { (k,v)-> writeInt(k); blob(v) } }
    }
    override fun close() { journal?.values?.forEach { it.previous?.fill(0) }; journal=null; identityBytes.fill(0); listOf(pins,sessions).forEach { map->map.values.forEach { it.fill(0) }; map.clear() }; listOf(pre,signed,kyber).forEach { map->map.values.forEach { it.fill(0) };map.clear() } }
    companion object {
        fun restore(bytes:ByteArray):BlobSignalStore=unpack(bytes) {
            require(readInt()==1); val identity=blob(256); IdentityKeyPair(identity)
            val registration=readInt(); require(registration in 1..16380)
            val store=BlobSignalStore(identity,registration)
            for(map in listOf(store.pins,store.sessions)) { val count=readInt(); require(count in 0..10000); repeat(count) { val k=readUTF(); k.unhex(); require(map.put(k,blob())==null) } }
            for(map in listOf(store.pre,store.signed,store.kyber)) { val count=readInt(); require(count in 0..10000); repeat(count) { val k=readInt(); require(k>0); require(map.put(k,blob())==null) } }
            store
        }
    }
}
