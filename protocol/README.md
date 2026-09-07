# Hardline Relay contracts

Relay App owns these contracts; the ATAK plugin keeps an exact reviewed snapshot.
Run tools/compare-contract.ps1 before integrating paired changes.

- [Offline channel profiles](channel-profile-v1.md): QR encryption, saved profiles,
  radio activation, progress and the read-only app/plugin metadata boundary.
- [PLI](pli-v1.md): compact positions, bounded receipts and honest freshness.
- [Points](point-v1.md): explicit ATAK contact sends and recipient receipts.
- [Chat](chat-v1.md): ATAK contact conversations over the private Relay channel.
- [Status vectors](status-v1.tsv): initial standalone harness fixtures.

Radio connectivity, observed peer communication and TAK server connectivity are
independent. Submission is not delivery. A receipt is application processing evidence
on the supported trusted-phone setup, not proof of human observation or identity.
The standalone harness remains explicitly labelled as a simulation.
