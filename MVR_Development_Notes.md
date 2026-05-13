# OZEDI Member Verification Request (MVR) — Development Notes

## Overview

Added an MVR Check button to the Employee Maintenance superannuation screen (`paem01` / `paem01s1c`) that submits a Member Verification Request to the OZEDI SuperStream API. The employee's super fund details are verified against the ATO SuperStream network.

---

## Files Changed

### New Programs
| File | Description |
|---|---|
| `pamvr01.cbl` | New program — MVR caller, modelled on PAST35 |
| `pamvr01.pl` | Procedure logic — reads env vars, builds JSON, calls script, reads response |
| `pamvr01.ws` | Working storage — credentials, JSON buffer, log fields |
| `pamvr01s0c.pl/.sd/.ws` | Empty screen stubs (same pattern as past35s0c) |

### New Scripts
| File | Description |
|---|---|
| `sendmvr.js` | Node.js — authenticates to OZEDI B2B, POSTs MVR JSON to SS2 endpoint |
| `send-mvr.sh` | Unix shell wrapper for sendmvr.js |
| `run-sendmvr.bat` | Windows batch wrapper for sendmvr.js |

### Modified Programs
| File | Changes |
|---|---|
| `paem01.cbl` | Added PAPSDAT copybooks (sl/fd/ws/ds/op/pl) |
| `paem01.pl` | Added `DO-MVR-CHECK`, `SET-MVR-ENV-VARS`, `CALL-PAMVR01` sections; MVR check inside ENTER-SUPER-DETAILS loop |
| `paem01s1c.sd` | Added MVR Check button (COL 170), shifted Exit to COL 260 |
| `paem01s1c.pl` | Added `STD-MVR-BUTTON` bypass to all after-procedures; `ENABLE-ALL-BUTTONS` conditionally enables MVR button |
| `paem01s1c.ws` | Added `STD-MVR-BUTTON` constant (value 500), `PAEM01S1C-MVR-BTN-ENABLED`, DOB/TFN/postcode/ABN work fields |

---

## How It Works

### Button Enable Logic
The MVR button on the S (Superannuation) screen is enabled on entry only when:
- `CPCOYCO-PY-STP-OZEDI-API-FLAG = "Y"` (company is configured for OZEDI)
- `PASTAFF-SUPER-CODE` is not spaces (employee has a super fund assigned)

### Data Flow
1. User clicks **MVR Check** on the S screen
2. `paem01.pl` — `DO-MVR-CHECK`:
   - Validates super code and member/TFN present
   - Reads PACODES for fund details (USI, ESA, ABN, fund name, APRA/SMSF type)
   - Opens PAPSDAT (using `PAPASS-YEAR-NO`) for employer name, ABN, contact details
   - Sets environment variables
   - Calls `PAMVR01`
3. `pamvr01.pl` — `SEND-MVR-DATA`:
   - Reads CPOZEDI for send directory
   - Accepts all env vars
   - Splits contact name into given/family
   - Builds compact JSON body using `WITH POINTER`
   - Writes JSON to `CPOZEDI-SEND-DIR\mvr_body.json`
   - Calls `run-sendmvr.bat` via `C$SYSTEM`
   - Scans send dir for `payload*.json` response
   - Extracts `messageUuid` from response
   - Displays result message

### APRA vs SMSF Receiver Logic
- Fund type **A** (APRA) → `uniqueSuperannuationIdentifier` populated, `targetElectronicServiceAddress` blank
- Fund type **S** (SMSF) → `targetElectronicServiceAddress` populated, USI omitted

### Date of Birth Conversion
`PASTAFF-DATE-OF-BIRTH-WS` (already DDMMYY display format) is redefines as DD/MM/YY and converted to ISO `YYYY-MM-DD` format for the JSON. Century: YY ≥ 50 → 19xx, YY < 50 → 20xx.

### TFN vs Member Number
If `PASTAFF-TAX-FILE-NO > 0`, TFN is used as `superannuationFundMember` and `taxFileNumberNotProvided = "N"`. Otherwise falls back to `PASTAFF-SUPER-MEMBER-NO` with flag `"Y"`.

---

## OZEDI API Configuration

### Endpoints
| Purpose | URL |
|---|---|
| Authentication | `https://api-ebusiness.ozedi.com.au/v2/authenticate` |
| MVR Upload | `https://api-superstream.ozedi.com.au/api/uploads/process/{account}/{client}/{ABN}?autoSendWhenReady=true` |

### URL Parameters
| Parameter | Value | Source |
|---|---|---|
| `account_number` | `121457442888897` | eBusiness account — hardcoded in `sendmvr.js` |
| `client_id` | `8981020306` | SS2 client ID — hardcoded in `sendmvr.js` |
| `ABN` | employer ABN | From PAPSDAT, passed via env var |

### Credentials (pamvr01.ws)
Currently using personal account `nigelwarne66@gmail.com`. **Should be replaced with a dedicated API service account** once OZEDI set one up for the SS2 service (same as `api@landmarksoftware.com.au` is used for STP).

### Response
Successful response contains `messageUuid` field (e.g. `84ac1102-...@OZEDI`). Displayed to user as:
- Message line 1: `MVR submitted. UUID:`
- Message line 2: full UUID

---

## Logging

`pamvr01` writes `mvr_debug.log` to `CPOZEDI-SEND-DIR` (same folder as STP files). Opens in EXTEND mode so repeated runs append. Logs:
- All env var parameters received
- First 190 chars of JSON body built
- Shell command executed
- First 190 chars of API response
- UUID on success, or error detail on failure

---

## Known Limitations / Future Work

1. **SS2 credentials** — `nigelwarne66@gmail.com` / `August2023!1abc` are personal credentials hardcoded in `pamvr01.ws`. Replace with a dedicated API service account.

2. **SS2 client ID hardcoded** — `8981020306` is hardcoded in `sendmvr.js`. Should be added as `CPCOYCO-PY-SS2-OZEDI-CLIENT-ID` to the company setup file, same pattern as `CPCOYCO-PY-STP-OZEDI-CLIENT-ID` for STP.

3. **Account numbers hardcoded** — `121457442888897` (account) in `sendmvr.js`. These are Landmark-level constants unlikely to change but should be documented.

4. **Fund activation** — OZEDI/SuperStream connection to each fund must be activated in the OZEDI portal before MVRs to that fund will succeed. A "fund not ready" response is expected until this is done.

5. **MVR result polling** — currently only submits the MVR and returns the UUID. OZEDI processes MVRs asynchronously; a future enhancement could poll `GET /uploads/status/{client_id}/{upload_uuid}` and display the verification result.

---

## Source Fields Reference

| MVR JSON Field | Landmark Source |
|---|---|
| `sender.abn` | `PAPSDAT-EMPLOYER-ABN` |
| `sender.organisationalName` | `PAPSDAT-EMPLOYER-NAME-1` |
| `sender.emailAddress` | `PAPSDAT-EMPLOYER-EMAIL` |
| `sender.phone` | `PAPSDAT-EMPLOYER-CONTACT-PHONE` |
| `sender.givenName/familyName` | Split from `PAPSDAT-EMPLOYER-CONTACT-NAME` |
| `receiver.uniqueSuperannuationIdentifier` | `PACODES-FUND-USI` (APRA) |
| `receiver.targetElectronicServiceAddress` | `PACODES-FUND-ESA` (SMSF) |
| `receiver.abn` | `PACODES-FUND-ABN` |
| `receiver.organisationalName` | `PACODES-FUND-NAME` |
| `employers[0].abn` | `PAPSDAT-EMPLOYER-ABN` |
| `members[0].name.givenName` | `PASTAFF-FIRST-NAME` |
| `members[0].name.otherGivenName` | `PASTAFF-SECOND-NAME` (if present) |
| `members[0].name.familyName` | `PASTAFF-SURNAME` |
| `members[0].birthDate` | `PASTAFF-DATE-OF-BIRTH-WS` → ISO YYYY-MM-DD |
| `members[0].sex` | `PASTAFF-SEX` |
| `members[0].superannuationFundMember` | `PASTAFF-TAX-FILE-NO` (preferred) or `PASTAFF-SUPER-MEMBER-NO` |
| `members[0].taxFileNumberNotProvided` | `"N"` if TFN used, `"Y"` if member no used |
| `members[0].address.addressLine1` | `PASTAFF-ADDR-1` |
| `members[0].address.suburb` | `PASTAFF-CITY` |
| `members[0].address.state` | `PASTAFF-STATE` |
| `members[0].address.postCode` | `PASTAFF-POSTCODE(1:4)` |
