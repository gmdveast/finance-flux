# Finance Flux connector IPC v1

This contract lets an independently installed Android companion publish normalized transaction candidates to Finance Flux. The companion does not receive vault access, Syncthing access, desktop APIs, or Excel access. It must not expose source messages or unrelated personal data through IPC.

The canonical AIDL interface is `app/src/main/aidl/com/gmdveast/financeflux/ipc/IFinanceFluxConnector.aidl`. JSON payloads follow the schemas in `contracts/`.

## Android service

The companion declares one exported bound service with the action `com.gmdveast.financeflux.CONNECTOR_SERVICE`. It must not require a signature-level permission because each connector can have an independent signing key. Finance Flux discovers services with a narrow `<queries><intent>` declaration, then binds to the exact `ComponentName`; it does not use implicit binding.

Before connecting, Finance Flux shows the package name and SHA-256 signing-certificate fingerprint and requires the user to trust that exact pair. It checks the installed signer again before every sync. A package or signing-key change requires explicit trust again. The companion should independently verify the Finance Flux host package and signing certificate before serving data; the production Finance Flux certificate fingerprint will be published with a production build. Debug fingerprints are not production trust anchors.

## AIDL methods

| Method | Behavior |
| --- | --- |
| `getManifestJson()` | Returns one manifest object, at most 8 KiB. The manifest declares a stable `connectorId` and supported IPC versions. |
| `listCandidatesJson(profileId, limit)` | Returns a JSON array for that profile, at most 50 candidates and 64 KiB total. Each `candidateId` remains stable until acknowledged. |
| `acknowledgeCandidate(profileId, candidateId)` | Removes or marks only that exact candidate as delivered. Acknowledgement happens after Flux has published the schema 2 JSON file. |

All methods must be safe to retry. Candidate IDs are unique within one connector profile. A companion must retain a candidate if it has not received acknowledgement. Unknown or malformed candidates are not acknowledged, so the user can fix profile configuration or update the connector.

## Candidate normalization

A candidate contains a real transaction date, one supported operation type, a positive amount, and a three-letter currency. Transfers also carry a destination account ID and a positive destination amount. The destination amount is provided in the destination account's currency; Flux never converts it. Notes are optional and limited to 500 characters. Candidate payloads have no free-form category or exchange-rate field: the user selects category defaults in the profile, and Finance desktop owns CBR rate lookup.

Flux checks the profile account against the current desktop-generated `config/lists.json`. The source currency must match the selected account. For income and expenses, the profile's default category must still exist in the matching desktop reference list. If a reference list changed, Flux holds the candidate and asks the user to refresh the list or profile.

After validation, Flux creates schema 2 with `automation.connectorId` and `automation.profileId`. It writes `incoming/<event-id>.pending`, closes the document stream, and asks the selected SAF provider to rename it to `.json`. If rename is unsupported, Flux retains `.pending` and does not acknowledge the candidate. A Markdown card is created only after the JSON is published; card creation failure never removes the queued JSON.

## Profile binding

Profiles are stored locally and include the connector's package name, stable connector ID, a user-facing name, and an active ordinary account row ID from `config/lists.json`. A connector may have many profiles, including profiles for multiple bank accounts. Profiles can select default expense and income categories independently. Disabled profiles are not polled.

## Compatibility and limits

- IPC version and app SemVer are independent. A service must reject a version range that does not include v1.
- Response size limits are enforced before JSON parsing. Do not return the entire SMS inbox or an unbounded candidate history.
- Candidate IDs and idempotency mappings are retained locally; duplicate delivery must not create a second queue JSON.
- Finance Flux uses the exact Finance desktop schemas for event JSON, reference lists, receipts, and resets. The copied schemas are updated from the desktop repository with `scripts/sync-desktop-contracts.mjs`.
- Production automation remains blocked until the security milestone adds Android Keystore event signatures, desktop producer trust, key rotation/revocation, and replay/tamper tests.
