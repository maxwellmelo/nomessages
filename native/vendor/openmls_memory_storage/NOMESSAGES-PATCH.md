# NoMessages storage hardening

Based on the unmodified crates.io openmls_memory_storage 0.6.0 package, MIT licensed, upstream https://github.com/openmls/openmls/tree/main/memory_storage.

Local changes only: use zeroize for replaced/deleted map values, removed list entries, list serialization scratch buffers, incoming serialized values on error/unwind, and all stored values on Drop (including poisoned locks); avoid an unnecessary plaintext value clone on write; return a parsing error rather than unwrap in read_list. Public storage/provider APIs and MLS behavior are unchanged. The upstream filesystem `persistence` and `test-utils` features remain disabled in NoMessages production.

This reduces known native allocator retention. It is not a claim that Rust/JVM/OS memory can universally be erased or that exported backups preserve forward secrecy against rollback.
