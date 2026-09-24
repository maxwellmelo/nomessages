package dev.mx3.nomessages.core.groups

import dev.mx3.nomessages.core.crypto.Crypto
import dev.mx3.nomessages.core.protocol.PairEvidence
import dev.mx3.nomessages.core.protocol.unhex

data class MissingPair(val first:String,val second:String)

/** Membership policy only; actual application group encryption belongs to OpenMLS. */
class CliquePolicy(private val crypto:Crypto) {
    fun missingPairs(members:Collection<String>,evidence:Collection<ByteArray>):List<MissingPair> {
        require(members.size in 3..100) { "Groups require 3 to 100 members" }
        require(members.toSet().size==members.size) { "Duplicate group member" }
        val sorted=members.sorted(); sorted.forEach { it.unhex() }
        require(evidence.size<=4950) { "Too much pairing evidence" }
        val edges=mutableSetOf<MissingPair>()
        for(bytes in evidence) {
            val e=PairEvidence.decode(bytes)
            require(e.valid(crypto)) { "Pairing evidence requires both signatures" }
            edges+=MissingPair(e.statement.first,e.statement.second)
        }
        return buildList { for(i in sorted.indices) for(j in i+1 until sorted.size) {
            val pair=MissingPair(sorted[i],sorted[j]); if(pair !in edges) add(pair)
        } }
    }
    fun requireClique(members:Collection<String>,evidence:Collection<ByteArray>) {
        val missing=missingPairs(members,evidence)
        require(missing.isEmpty()) { "Group is missing ${missing.size} pairing edges" }
    }
}
