#![cfg(feature = "mls")]
use nomessages::mls;

#[test]
fn three_members_persist_authenticate_remove_and_reject_replay() {
    let a = mls::key_package(&[11; 32]).unwrap();
    let b = mls::key_package(&[22; 32]).unwrap();
    let c = mls::key_package(&[33; 32]).unwrap();
    let ids = vec![
        a.members[0].identity.clone(),
        b.members[0].identity.clone(),
        c.members[0].identity.clone(),
    ];
    let coordinator = ids[0].clone();
    let a = mls::create(&a.state).unwrap();
    let a = mls::add(&a.state, &[b.message.clone(), c.message.clone()], &ids).unwrap();
    assert!(mls::join(&b.state, &a.welcome, &ids, &ids[1]).is_err());
    let b = mls::join(&b.state, &a.welcome, &ids, &coordinator).unwrap();
    let c = mls::join(&c.state, &a.welcome, &ids, &coordinator).unwrap();
    assert_eq!(a.group_id, b.group_id);
    let sent = mls::encrypt(&a.state, b"segredo do grupo").unwrap();
    let received = mls::process(&b.state, &sent.message, &ids, &coordinator).unwrap();
    assert_eq!(received.application, b"segredo do grupo");
    assert_eq!(received.sender_identity, coordinator);
    assert!(mls::process(&received.state, &sent.message, &ids, &coordinator).is_err());
    let retained = ids[..2].to_vec();
    let removed = mls::remove(&sent.state, &[ids[2].clone()], &retained).unwrap();
    assert!(mls::process(&received.state, &removed.message, &ids, &coordinator).is_err());
    assert!(mls::process(&received.state, &removed.message, &retained, &ids[1]).is_err());
    let b2 = mls::process(&received.state, &removed.message, &retained, &coordinator).unwrap();
    let after = mls::encrypt(&removed.state, b"apos remocao").unwrap();
    assert_eq!(
        mls::process(&b2.state, &after.message, &retained, &coordinator)
            .unwrap()
            .application,
        b"apos remocao"
    );
    assert!(mls::process(&c.state, &after.message, &ids, &coordinator).is_err());
}

#[test]
fn membership_and_snapshot_boundaries_fail_closed() {
    assert!(mls::key_package(&[0; 31]).is_err());
    assert!(mls::create(&[0; 4]).is_err());
    let a = mls::key_package(&[1; 32]).unwrap();
    let b = mls::key_package(&[2; 32]).unwrap();
    let a = mls::create(&a.state).unwrap();
    assert!(mls::add(&a.state, &[b.message.clone()], &[]).is_err());
    let mut altered = a.state.clone();
    altered.push(0);
    assert!(mls::encrypt(&altered, b"x").is_err());
    let identity = a.members[0].identity.clone();
    // A tiny private-message header must not allocate its claimed 1 GiB group ID.
    for malformed in [
        vec![],
        vec![0, 1, 0, 2, 0xbf, 0xff, 0xff, 0xff],
        vec![0, 1, 0, 2, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff],
    ] {
        assert!(mls::process(&a.state, &malformed, &[identity.clone()], &identity).is_err());
    }
}

#[test]
fn late_application_is_accepted_only_within_two_retained_epochs() {
    let a = mls::key_package(&[51; 32]).unwrap();
    let b = mls::key_package(&[52; 32]).unwrap();
    let c = mls::key_package(&[53; 32]).unwrap();
    let ids = vec![
        a.members[0].identity.clone(),
        b.members[0].identity.clone(),
        c.members[0].identity.clone(),
    ];
    let retained = ids[..2].to_vec();
    let coordinator = ids[0].clone();
    let a = mls::create(&a.state).unwrap();
    let a = mls::add(&a.state, &[b.message.clone(), c.message.clone()], &ids).unwrap();
    let b = mls::join(&b.state, &a.welcome, &ids, &coordinator).unwrap();
    let late = mls::encrypt(&a.state, b"delayed").unwrap();
    let expired = mls::encrypt(&late.state, b"too old").unwrap();
    let removed = mls::remove(&expired.state, &[ids[2].clone()], &retained).unwrap();
    let b = mls::process(&b.state, &removed.message, &retained, &coordinator).unwrap();
    let b = mls::process(&b.state, &late.message, &retained, &coordinator).unwrap();
    assert_eq!(b.application, b"delayed");
    let c_again = mls::key_package(&[53; 32]).unwrap();
    let added = mls::add(&removed.state, &[c_again.message.clone()], &ids).unwrap();
    let b = mls::process(&b.state, &added.message, &ids, &coordinator).unwrap();
    let removed_again = mls::remove(&added.state, &[ids[2].clone()], &retained).unwrap();
    let b = mls::process(&b.state, &removed_again.message, &retained, &coordinator).unwrap();
    assert!(mls::process(&b.state, &expired.message, &retained, &coordinator).is_err());
}
