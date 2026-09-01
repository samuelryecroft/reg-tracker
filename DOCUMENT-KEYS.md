# Report documents: storage, encryption and keys

How generated report `.docx` files are protected, and the operational steps that go with it.
Implements `DOCUMENT-ENCRYPTION-DESIGN.md` §4 (Option 1 + approach (a)) as workstream **WS-B** of
`DEPLOYMENT-PLAN.md`. Design rationale lives in that document; this one is what you need to run it.

## The short version

Every approved report is encrypted **inside the application** before its bytes reach any store.

1. A random AES-256-GCM **data key** is generated for that one file.
2. The document is encrypted under it.
3. The data key is **wrapped** by the owning organisation's **KEK**, which lives in Azure Key Vault
   and never leaves it.
4. The ciphertext goes to a private Blob container; the wrapped key, IV, key name and key version go
   with it as blob metadata.

Downloading reverses it. Nothing is ever stored or served in the clear.

Encrypting in the application rather than relying on Azure's service-side encryption is the whole
point: service-side encryption is transparent to anyone holding storage credentials, so it does not
address a leaked account key, a stolen backup, or prod blobs copied somewhere they should not be.

## What this does and does not protect against

Worth being precise, because overstating it in an assurance conversation would be worse than not
doing it at all.

| Threat | Covered? |
|---|---|
| Leaked storage key/SAS, misconfigured container, stolen backup | **Yes** - the attacker gets ciphertext; the keys are in a different trust boundary |
| Cloud-platform insider or legal compulsion at the platform | **Yes** - Azure never sees plaintext |
| Prod blobs copied to dev, an over-broad listing, an ops export | **Yes** - copies are inert without Key Vault access |
| A cross-organisation bug in the application (IDOR) | **Partly** - a second, independent gate; see "Cross-organisation isolation" below |
| A compromised application (RCE, stolen managed identity) | **No.** The app can unwrap any organisation's key by design. No application-layer scheme changes this |

So this is defence in depth **around** `OrganisationAccessService`, not a replacement for it.

## Cross-organisation isolation

Three independent things have to agree before a document decrypts:

1. The owning organisation is resolved from the report's own `InterviewRequest -> Home ->
   Organisation`, **separately from the access check that has already run**. A bug in the access
   check therefore cannot also pick the key.
2. The organisation id and the storage key are bound into the AES-GCM tag as additional
   authenticated data. Relabelling a blob as another organisation's, or copying its bytes over a
   different report's key, fails authentication.
3. `KeyProvider.unwrap` refuses a wrapped key whose name does not match the resolved organisation.

`EnvelopeEncryptionServiceTest` and `EncryptedReportStoreTest` cover all three.

## Configuration

| Property | Dev default | Deployment |
|---|---|---|
| `app.documents.storage` | `local` | `azure-blob` |
| `app.documents.local.directory` | `./generated-reports` | unused |
| `app.documents.keys` | `local` | `key-vault` |
| `app.documents.blob.endpoint` | - | account endpoint; managed identity authenticates |
| `app.documents.blob.container` | `report-documents` | same |
| `app.documents.key-vault.uri` | - | the UK South vault |
| `app.documents.key-vault.wrap-algorithm` | - | `RSA-OAEP-256` |
| `app.documents.key-vault.auto-create-keys` | `true` | **`false`** - see onboarding below |

There is no property that turns encryption off. The two backends choose *custody*, not *whether*.

`DocumentStorageConfig` **refuses to start** if a production environment (`app.env=prod`, or a
`prod` Spring profile) selects either local backend. That is deliberate: a misconfigured deployment
must fail loudly rather than run in a state that looks encrypted and is not.

### Running locally

The default needs nothing: ciphertext goes to `app.documents.local.directory`
(`./generated-reports`), and per-organisation KEKs are derived from
`app.documents.local-keys.master-secret`. Set `DOCUMENT_KEY_SECRET` if you want local documents to
stay readable across a machine rebuild.

To exercise the real Blob code path without an Azure subscription:

```bash
docker compose up -d azurite
./mvnw spring-boot:run -Dspring-boot.run.profiles=azurite
```

Key Vault has no emulator, so keys still come from the local provider under that profile.

## Why RSA keys, when the design said symmetric

`DOCUMENT-ENCRYPTION-DESIGN.md` §4 nominated a per-organisation **symmetric** KEK. That form is not
provisionable: standard and premium Key Vault support **RSA and EC keys only**, and symmetric
(`oct`) keys exist solely in Managed HSM, which was ruled out at roughly £2,000/month against a
~£30/month hosting bill. Confirmed with DevOps against the Azure platform, not assumed.

The realised form is therefore a per-organisation **RSA-2048** key wrapped with **RSA-OAEP-256** -
which is the mechanism §2(b) of the design document already spelled out, so this stays inside the
signed-off design space. It preserves the property that actually mattered: the private key never
leaves the vault and unwrapping is a server-side vault operation. A 32-byte data key fits
RSA-2048/OAEP-SHA256 comfortably, and at roughly two key operations per report the cost difference
is nil.

The envelope records the wrap algorithm per document, so this is a configuration detail rather than
a rewrite. If the deployment ever gains a Managed HSM, setting
`app.documents.key-vault.wrap-algorithm=A256KW` applies to documents written afterwards while older
ones keep unwrapping with whatever wrapped them.

## Onboarding a new organisation — required step

**The application does not create keys in production.** It runs as **Key Vault Crypto User**
(get/wrap/unwrap). Creating and deleting keys lives in **Crypto Officer**, and the two role sets are
disjoint - so lazy creation would mean the request-path identity held delete and purge rights over
the entire vault. Not an acceptable trade for this data.

So, whenever a **CARE_PROVIDER organisation is created in the application**:

```bash
az keyvault key create \
  --vault-name <vault> \
  --name org-<organisationId>-kek \
  --kty RSA --size 2048 \
  --ops wrapKey unwrapKey
```

The organisation id is the database row id, visible in the admin organisation screen. Do this
**before** that organisation's first report is approved.

If the key is missing, approval **fails closed** with a message naming the key. That is correct
behaviour, not a bug - the alternative is an approved report with no retrievable document.

## Rotating an organisation's KEK

Rotation is cheap because the files are never touched. Each stored envelope records the key
*version* it was wrapped with, and unwrapping uses that recorded version, so old documents keep
working the moment a new version exists.

```bash
az keyvault key rotate --vault-name <vault> --name org-<organisationId>-kek
```

That is the whole required procedure. New reports wrap under the new version immediately; existing
reports keep unwrapping under the old one.

Re-wrapping existing data keys is **optional catch-up**, only worth doing if you need the old key
version to become unused (for example after a suspected exposure). It reads each blob's metadata,
unwraps with the recorded version, wraps with the current one, and writes the metadata back - the
ciphertext itself is never re-encrypted. There is no automation for this yet; it is not needed for
routine rotation.

## Key loss

Accepted at design time (`DOCUMENT-ENCRYPTION-DESIGN.md` §4, decision 5): **a destroyed KEK makes
that organisation's stored reports permanently unreadable.** The mitigation is Key Vault
**soft-delete and purge protection, both ON**, with 90-day retention, provisioned in WS-D. Purge
protection is irreversible once enabled - that is intentional.

These are statutory records, so treat deleting a KEK as equivalent to destroying the reports.

## Operating the fail-closed behaviour

| What the user sees | What happened | What to do |
|---|---|---|
| **503**, "secure key service is temporarily unavailable" | Key Vault unreachable, RBAC denied, or the organisation's key does not exist | Check vault availability and the identity's Crypto User role assignment. If the organisation is new, its key was never created - see onboarding |
| **500**, "could not be verified and has not been released" | Authentication failed: altered or truncated bytes, a mismatched envelope, or a key from another organisation | **Investigate as a security event.** Check the `DOCUMENT_CRYPTO_FAILED` audit rows and Key Vault's own operation log, which the application cannot edit |
| **404** | No such document | The report was approved before WS-B, or the blob is gone |

Approval is transactional: if encryption or the write fails, the approval rolls back rather than
recording a report whose document does not exist.

## Audit

Every key operation raises an `audit_events` row: `DOCUMENT_KEY_WRAPPED`, `DOCUMENT_KEY_UNWRAPPED`,
`DOCUMENT_CRYPTO_FAILED`. Each records the actor, report, organisation, storage key and - for wraps -
the key name and version. Never the key material, and never document content.

Key Vault logs the same operations independently, somewhere the application has no ability to alter.
Reconciling the two is what makes the trail worth having.

## Migration

None needed. Production is greenfield and WS-B lands **before** first traffic, which is why the plan
sequences it there rather than retrofitting. Any `.docx` sitting on disk in a non-production
environment is disposable; a file with no envelope beside it is refused rather than served, so old
files cannot leak through.
