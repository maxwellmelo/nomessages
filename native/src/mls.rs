//! Stateless MLS transactions. Snapshots are SECRET and belong only in SQLCipher.
use crate::wire::{MAX_STATE, Reader, put_bytes, put_u32};
use anyhow::{Result, anyhow, ensure};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider;
use std::collections::BTreeSet;
use tls_codec::{Deserialize as _, Serialize as _};
use zeroize::{Zeroize, Zeroizing};
pub const MAX_FRAME: usize = 12 * 1024 * 1024;

const SUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
const MAX_KEYS: usize = 32768;

#[derive(Clone)]
pub struct MlsMember {
    pub identity: Vec<u8>,
    pub signature_key: Vec<u8>,
    pub leaf_index: u32,
}
pub struct MlsResult {
    pub state: Vec<u8>,
    pub group_id: Vec<u8>,
    pub message: Vec<u8>,
    pub welcome: Vec<u8>,
    pub application: Vec<u8>,
    pub sender_identity: Vec<u8>,
    pub members: Vec<MlsMember>,
    pub kind: u32,
}
impl Drop for MlsResult {
    fn drop(&mut self) {
        self.state.zeroize();
        self.application.zeroize();
    }
}
impl MlsResult {
    pub(crate) fn encode(&self) -> Result<Vec<u8>> {
        let fields = [
            &self.state,
            &self.group_id,
            &self.message,
            &self.welcome,
            &self.application,
            &self.sender_identity,
        ];
        let mut encoded_len = 8usize;
        for bytes in fields {
            encoded_len = encoded_len
                .checked_add(4 + bytes.len())
                .ok_or_else(|| anyhow!("Result exceeds limit"))?;
        }
        for member in &self.members {
            encoded_len = encoded_len
                .checked_add(12 + member.identity.len() + member.signature_key.len())
                .ok_or_else(|| anyhow!("Result exceeds limit"))?;
        }
        ensure!(
            encoded_len <= MAX_STATE + 3 * MAX_FRAME,
            "Result exceeds limit"
        );
        // Allocate once before copying secrets; Vec growth would abandon unwiped buffers.
        let mut out = Zeroizing::new(Vec::with_capacity(encoded_len));
        put_u32(&mut out, self.kind as usize);
        for bytes in fields {
            put_bytes(&mut out, bytes);
        }
        put_u32(&mut out, self.members.len());
        for m in &self.members {
            put_bytes(&mut out, &m.identity);
            put_bytes(&mut out, &m.signature_key);
            put_u32(&mut out, m.leaf_index as usize);
        }
        ensure!(
            out.len() <= MAX_STATE + 3 * MAX_FRAME,
            "Result exceeds limit"
        );
        Ok(std::mem::take(&mut *out))
    }
}

struct Session {
    provider: OpenMlsRustCrypto,
    signer: SignatureKeyPair,
    identity: Vec<u8>,
    group_id: Vec<u8>,
}
impl Drop for Session {
    fn drop(&mut self) {
        if let Ok(mut values) = self.provider.storage().values.write() {
            for (mut key, mut value) in values.drain() {
                key.zeroize();
                value.zeroize();
            }
        }
    }
}
impl Session {
    fn load(bytes: &[u8]) -> Result<Self> {
        let mut r = Reader::new(bytes)?;
        ensure!(r.u32()? == 1, "Unsupported MLS snapshot");
        let identity = r.bytes(32)?.to_vec();
        ensure!(identity.len() == 32, "Invalid identity");
        let group_id = r.bytes(256)?.to_vec();
        let signer: SignatureKeyPair = serde_json::from_slice(r.bytes(4096)?)?;
        ensure!(
            signer.to_public_vec() == identity,
            "Identity binding failed"
        );
        let session = Self {
            provider: OpenMlsRustCrypto::default(),
            signer,
            identity,
            group_id,
        };
        let count = r.u32()? as usize;
        ensure!(count <= MAX_KEYS, "Too many storage entries");
        {
            let mut values = session
                .provider
                .storage()
                .values
                .write()
                .map_err(|_| anyhow!("Storage unavailable"))?;
            for _ in 0..count {
                let key = r.bytes(MAX_FRAME)?;
                let value = r.bytes(MAX_STATE)?;
                ensure!(!values.contains_key(key), "Duplicate storage entry");
                values.insert(key.to_vec(), value.to_vec());
            }
        }
        r.finish()?;
        Ok(session)
    }
    fn snapshot(&self) -> Result<Vec<u8>> {
        let mut signer = Zeroizing::new(Vec::with_capacity(4096));
        serde_json::to_writer(&mut *signer, &self.signer)?;
        ensure!(signer.len() <= 4096, "Signer exceeds limit");
        let values = self
            .provider
            .storage()
            .values
            .read()
            .map_err(|_| anyhow!("Storage unavailable"))?;
        ensure!(values.len() <= MAX_KEYS, "Too many storage entries");
        let mut encoded_len = 20 + self.identity.len() + self.group_id.len() + signer.len();
        for (key, value) in values.iter() {
            ensure!(key.len() <= MAX_FRAME, "State exceeds limit");
            encoded_len = encoded_len
                .checked_add(8 + key.len() + value.len())
                .ok_or_else(|| anyhow!("State exceeds limit"))?;
            ensure!(encoded_len <= MAX_STATE, "State exceeds limit");
        }
        ensure!(encoded_len <= MAX_STATE, "State exceeds limit");
        let mut out = Zeroizing::new(Vec::with_capacity(encoded_len));
        put_u32(&mut out, 1);
        put_bytes(&mut out, &self.identity);
        put_bytes(&mut out, &self.group_id);
        put_bytes(&mut out, &signer);
        put_u32(&mut out, values.len());
        let mut ordered: Vec<_> = values.iter().collect();
        ordered.sort_by(|a, b| a.0.cmp(b.0));
        for (key, value) in ordered {
            ensure!(
                key.len() <= MAX_FRAME && out.len() + key.len() + value.len() + 8 <= MAX_STATE,
                "State exceeds limit"
            );
            put_bytes(&mut out, key);
            put_bytes(&mut out, value);
        }
        Ok(std::mem::take(&mut *out))
    }
    fn group(&self) -> Result<MlsGroup> {
        ensure!(!self.group_id.is_empty(), "Group not created");
        MlsGroup::load(
            self.provider.storage(),
            &GroupId::from_slice(&self.group_id),
        )?
        .ok_or_else(|| anyhow!("Missing MLS state"))
    }
    fn result(
        &self,
        group: Option<&MlsGroup>,
        kind: u32,
        message: Vec<u8>,
        welcome: Vec<u8>,
        application: Vec<u8>,
        sender: Vec<u8>,
    ) -> Result<MlsResult> {
        let mut application = Zeroizing::new(application);
        let members = if let Some(g) = group {
            members(g.members())?
        } else {
            vec![MlsMember {
                identity: self.identity.clone(),
                signature_key: self.identity.clone(),
                leaf_index: 0,
            }]
        };
        Ok(MlsResult {
            state: self.snapshot()?,
            group_id: self.group_id.clone(),
            message,
            welcome,
            application: std::mem::take(&mut *application),
            sender_identity: sender,
            members,
            kind,
        })
    }
}

fn identity(credential: &Credential, signature: &[u8]) -> Result<Vec<u8>> {
    ensure!(
        credential.credential_type() == CredentialType::Basic,
        "Unsupported credential"
    );
    let id = credential.serialized_content();
    ensure!(id.len() == 32 && id == signature, "Unbound MLS identity");
    Ok(id.to_vec())
}
fn members(input: impl Iterator<Item = Member>) -> Result<Vec<MlsMember>> {
    let result: Vec<_> = input
        .map(|m| {
            Ok(MlsMember {
                identity: identity(&m.credential, &m.signature_key)?,
                signature_key: m.signature_key,
                leaf_index: m.index.u32(),
            })
        })
        .collect::<Result<_>>()?;
    ensure!(
        !result.is_empty() && result.len() <= 100,
        "Invalid group size"
    );
    let unique: BTreeSet<_> = result.iter().map(|m| &m.identity).collect();
    ensure!(unique.len() == result.len(), "Duplicate group identity");
    Ok(result)
}
fn expected(actual: &[Vec<u8>], approved: &[Vec<u8>]) -> Result<()> {
    ensure!(
        !approved.is_empty() && approved.len() <= 100 && approved.iter().all(|x| x.len() == 32),
        "Invalid approved membership"
    );
    let a: BTreeSet<_> = actual.iter().collect();
    let b: BTreeSet<_> = approved.iter().collect();
    ensure!(
        a.len() == actual.len() && b.len() == approved.len() && a == b,
        "Membership was not approved"
    );
    Ok(())
}
fn group_expected(g: &MlsGroup, approved: &[Vec<u8>]) -> Result<()> {
    expected(
        &members(g.members())?
            .into_iter()
            .map(|m| m.identity)
            .collect::<Vec<_>>(),
        approved,
    )
}
fn check_message(data: &[u8]) -> Result<()> {
    ensure!(
        !data.is_empty() && data.len() <= MAX_FRAME,
        "Invalid MLS message size"
    );
    Ok(())
}
fn message_in(bytes: &[u8]) -> Result<MlsMessageIn> {
    check_message(bytes)?;
    Ok(MlsMessageIn::tls_deserialize_exact(bytes)?)
}

pub fn key_package(seed: &[u8]) -> Result<MlsResult> {
    ensure!(seed.len() == 32, "Identity seed must be 32 bytes");
    let pk = crate::crypto::signing_public_key_from_seed(seed)
        .map_err(|_| anyhow!("Crypto unavailable"))?;
    ensure!(pk.len() == 32, "Invalid signing identity");
    let signer = SignatureKeyPair::from_raw(SignatureScheme::ED25519, seed.to_vec(), pk.clone());
    let provider = OpenMlsRustCrypto::default();
    signer.store(provider.storage())?;
    let credential = CredentialWithKey {
        credential: BasicCredential::new(pk.clone()).into(),
        signature_key: pk.clone().into(),
    };
    let kp = KeyPackage::builder().build(SUITE, &provider, &signer, credential)?;
    let session = Session {
        provider,
        signer,
        identity: pk,
        group_id: Vec::new(),
    };
    session.result(
        None,
        0,
        kp.key_package().tls_serialize_detached()?,
        Vec::new(),
        Vec::new(),
        Vec::new(),
    )
}
pub fn create(state: &[u8]) -> Result<MlsResult> {
    let mut s = Session::load(state)?;
    ensure!(s.group_id.is_empty(), "Group already exists");
    let credential = CredentialWithKey {
        credential: BasicCredential::new(s.identity.clone()).into(),
        signature_key: s.identity.clone().into(),
    };
    let group = MlsGroup::builder()
        .ciphersuite(SUITE)
        .set_past_epoch_deletion_policy(openmls::group::PastEpochDeletionPolicy::MaxEpochs(2))
        .sender_ratchet_configuration(SenderRatchetConfiguration::new(32, 1024))
        .use_ratchet_tree_extension(true)
        .build(&s.provider, &s.signer, credential)?;
    s.group_id = group.group_id().as_slice().to_vec();
    s.result(
        Some(&group),
        1,
        Vec::new(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
    )
}
pub fn add(state: &[u8], packages: &[Vec<u8>], approved: &[Vec<u8>]) -> Result<MlsResult> {
    ensure!(
        !packages.is_empty() && packages.len() < 100,
        "Invalid add count"
    );
    let s = Session::load(state)?;
    let mut group = s.group()?;
    let mut ids: Vec<_> = members(group.members())?
        .into_iter()
        .map(|m| m.identity)
        .collect();
    let mut validated = Vec::new();
    for bytes in packages {
        check_message(bytes)?;
        let kp = KeyPackageIn::tls_deserialize_exact(bytes)?
            .validate(s.provider.crypto(), ProtocolVersion::Mls10)?;
        ids.push(identity(
            kp.leaf_node().credential(),
            kp.leaf_node().signature_key().as_slice(),
        )?);
        validated.push(kp);
    }
    expected(&ids, approved)?;
    let (commit, welcome, _) = group.add_members(&s.provider, &s.signer, &validated)?;
    group.merge_pending_commit(&s.provider)?;
    let welcome: MlsMessageOut = welcome.into();
    s.result(
        Some(&group),
        2,
        commit.tls_serialize_detached()?,
        welcome.tls_serialize_detached()?,
        Vec::new(),
        s.identity.clone(),
    )
}
pub fn join(
    state: &[u8],
    welcome: &[u8],
    approved: &[Vec<u8>],
    coordinator: &[u8],
) -> Result<MlsResult> {
    let mut s = Session::load(state)?;
    ensure!(s.group_id.is_empty(), "Already joined");
    let welcome = match message_in(welcome)?.extract() {
        MlsMessageBodyIn::Welcome(w) => w,
        _ => return Err(anyhow!("Expected Welcome")),
    };
    let config = MlsGroupJoinConfig::builder()
        .set_past_epoch_deletion_policy(openmls::group::PastEpochDeletionPolicy::MaxEpochs(2))
        .sender_ratchet_configuration(SenderRatchetConfiguration::new(32, 1024))
        .use_ratchet_tree_extension(true)
        .build();
    let staged = StagedWelcome::new_from_welcome(&s.provider, &config, welcome, None)?;
    let sender = staged.welcome_sender()?;
    ensure!(
        identity(sender.credential(), sender.signature_key().as_slice())? == coordinator,
        "Welcome coordinator mismatch"
    );
    let staged_members = members(staged.members())?;
    expected(
        &staged_members
            .into_iter()
            .map(|m| m.identity)
            .collect::<Vec<_>>(),
        approved,
    )?;
    let group = staged.into_group(&s.provider)?;
    s.group_id = group.group_id().as_slice().to_vec();
    s.result(
        Some(&group),
        1,
        Vec::new(),
        Vec::new(),
        Vec::new(),
        coordinator.to_vec(),
    )
}
pub fn encrypt(state: &[u8], plaintext: &[u8]) -> Result<MlsResult> {
    ensure!(
        plaintext.len() <= MAX_FRAME - 65536,
        "Application message too large"
    );
    let s = Session::load(state)?;
    let mut group = s.group()?;
    let message = group
        .create_message(&s.provider, &s.signer, plaintext)?
        .tls_serialize_detached()?;
    s.result(
        Some(&group),
        3,
        message,
        Vec::new(),
        Vec::new(),
        s.identity.clone(),
    )
}
pub fn process(
    state: &[u8],
    bytes: &[u8],
    approved: &[Vec<u8>],
    coordinator: &[u8],
) -> Result<MlsResult> {
    let msg = message_in(bytes)?.try_into_protocol_message()?;
    let s = Session::load(state)?;
    let mut group = s.group()?;
    let processed = group.process_message(&s.provider, msg)?;
    let sender = processed.credential().serialized_content().to_vec();
    ensure!(
        processed.credential().credential_type() == CredentialType::Basic && sender.len() == 32,
        "Invalid sender credential"
    );
    let sender_member = members(group.members())?
        .into_iter()
        .any(|m| m.identity == sender);
    ensure!(sender_member, "Unknown MLS sender");
    match processed.into_content() {
        ProcessedMessageContent::ApplicationMessage(message) => {
            group_expected(&group, approved)?;
            s.result(
                Some(&group),
                3,
                Vec::new(),
                Vec::new(),
                message.into_bytes(),
                sender,
            )
        }
        ProcessedMessageContent::StagedCommitMessage(commit) => {
            ensure!(sender == coordinator, "Commit coordinator mismatch");
            // Inspect the cryptographically verified future tree BEFORE merging.
            let tree = commit
                .export_ratchet_tree(s.provider.crypto(), group.export_ratchet_tree())?
                .ok_or_else(|| anyhow!("Local member removed"))?;
            let ids: Vec<_> = tree
                .leaves()
                .map(|leaf| identity(leaf.credential(), leaf.signature_key().as_slice()))
                .collect::<Result<_>>()?;
            expected(&ids, approved)?;
            group.merge_staged_commit(&s.provider, *commit)?;
            s.result(Some(&group), 2, Vec::new(), Vec::new(), Vec::new(), sender)
        }
        _ => Err(anyhow!(
            "Standalone proposals and external joins are disabled"
        )),
    }
}
pub fn remove(state: &[u8], removed: &[Vec<u8>], approved: &[Vec<u8>]) -> Result<MlsResult> {
    ensure!(
        !removed.is_empty() && removed.len() < 100,
        "Invalid removal count"
    );
    let s = Session::load(state)?;
    let mut group = s.group()?;
    ensure!(
        !removed.contains(&s.identity),
        "Coordinator cannot remove itself"
    );
    let current = members(group.members())?;
    let indices: Vec<_> = current
        .iter()
        .filter(|m| removed.contains(&m.identity))
        .map(|m| LeafNodeIndex::new(m.leaf_index))
        .collect();
    ensure!(
        indices.len() == removed.len(),
        "Unknown or duplicate removal"
    );
    let ids: Vec<_> = current
        .into_iter()
        .filter(|m| !removed.contains(&m.identity))
        .map(|m| m.identity)
        .collect();
    expected(&ids, approved)?;
    let (commit, _, _) = group.remove_members(&s.provider, &s.signer, &indices)?;
    group.merge_pending_commit(&s.provider)?;
    s.result(
        Some(&group),
        2,
        commit.tls_serialize_detached()?,
        Vec::new(),
        Vec::new(),
        s.identity.clone(),
    )
}
