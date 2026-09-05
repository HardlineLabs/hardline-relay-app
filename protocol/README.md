# Relay status contract v1

This directory is the protocol contract, not duplicated business logic.
The Relay App repository owns changes; the ATAK plugin keeps an exact reviewed
snapshot. Compare both directories before integration. Version incompatible changes.

The TSV contains public, non-secret status vectors: version, mode, state, display.
A connected Android service is not proof of a connected radio. A connected radio is
not proof that another peer can receive traffic. Never derive mesh state from TAK
server state. "simulated" must be obvious in every display.

The initial apps bind independently to Meshtastic Android 2.7.13 IMeshService.
Relay owns radio configuration (future implementation). The ATAK adapter owns display.
No exported Hardline configuration service, shared key file, or unprotected
configuration broadcast is introduced.

A future CoT wire format needs a separate versioned contract and airtime budget.
No custom on-air protocol is committed by this scaffold.
