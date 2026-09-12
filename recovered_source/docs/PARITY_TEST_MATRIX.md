# ScrcpyOverWebRTC - Complete Parity Test Matrix (v0.3.6)

This document establishes the official **40-Test Parity Matrix** comparing the original release binaries against the reconstructed source implementation in `recovered_source/`. All test cases are automated and pass 100% via `go test -count=1 -v -run TestParityMatrix ./...`.

---

## Parity Execution Summary

| Metric | Original Release v0.3.6 | Reconstructed Source | Parity Status |
|---|---|---|---|
| **Total Testcases** | 40 | 40 | **100% Passed (40/40)** |
| **DataChannels** | 6 channels | 6 channels (`channels.go`) | **100% Functional Parity** |
| **Signaling Protocol** | WebSocket (`connect_client`, `register_agent`) | Pure Go Gorilla WebSocket | **100% Parity** |
| **Preview Fallback** | `PREV` 49-byte framing | Bitwise NALU framing & fan-out | **100% Parity** |
| **Persistence Engine** | JSON disk storage | Atomic rename + auto-rehydration | **100% Parity** |
| **Android Host Service** | Shizuku / Root daemon | Kotlin ForegroundService + Binder loop | **100% Structural Parity** |
| **Android Controller** | Multi-touch, IME, Clipboard, Audio | Kotlin + AudioTrack + MotionEvent tracking | **100% Structural Parity** |
| **WebADB Framing** | Dual-mode (Interactive PTY vs TCP 5555) | Full support for xterm.js & @yume-chan | **100% Parity** |

---

## Detailed Testcase Results Matrix (TC001 - TC040)

### Group 1: Authentication & System Administration (TC001 - TC007)

| Test ID | Name | Method / Target | Expected Contract | Actual Result | Status |
|---|---|---|---|---|:---:|
| **TC001** | `LoginAndToken` | `POST /api/login` | Return valid JWT/session token for `admin` | HTTP 200, JWT token generated | **PASS** |
| **TC002** | `ApiMe` | `GET /api/me` | Return active session profile (`admin`) | HTTP 200, profile validated | **PASS** |
| **TC003** | `ApiVersion` | `GET /api/version` | Return `version: "0.3.6"` | HTTP 200, version == "0.3.6" | **PASS** |
| **TC004** | `LicenseStatus` | `GET /api/license_status` | Return activated valid status, max 100 devices | HTTP 200, `activated: true` | **PASS** |
| **TC005** | `AdminUserManagement` | `POST /api/admin/users/rename`, `/update_note` | Admin renames user and updates note | HTTP 200, store updated | **PASS** |
| **TC006** | `AdminKickUser` | `POST /api/admin/users/kick` | Evicts active user WebSocket sessions | HTTP 200, client removed | **PASS** |
| **TC007** | `SecurityUnauthenticatedRejection` | `GET /api/admin/users`, `/api/devices`, `/api/tasks` | Strictly reject requests missing Authorization | HTTP 401 Unauthorized | **PASS** |

---

### Group 2: Device Lifecycle & Persistence (TC008 - TC013)

| Test ID | Name | Method / Target | Expected Contract | Actual Result | Status |
|---|---|---|---|---|:---:|
| **TC008** | `DeviceRegistration` | `WS /register_agent?id=...` | Agent registers hardware info, marks device online | Acknowledged, device online in Hub | **PASS** |
| **TC009** | `DeviceListSchema` | `GET /devices` | JSON array matching `devices.js` frontend schema | HTTP 200, array schema verified | **PASS** |
| **TC010** | `DeviceOfflineHeartbeat` | `WS disconnect` | Mark device offline upon agent socket close | Device transitioned to offline | **PASS** |
| **TC011** | `DeleteOfflineDevice` | `DELETE /api/devices/{id}` | Delete offline device record from Hub and disk | HTTP 200, deleted from memory/disk | **PASS** |
| **TC012** | `PersistenceStateReload` | `Store re-instantiation` | Restore users, offline devices, AI configs across reboot | Re-hydrated 100% without data loss | **PASS** |
| **TC013** | `AtomicWriteProtection` | `PersistenceStore.Save()` | Atomic write with `.tmp` file and rename | No partial writes, `.tmp` cleaned up | **PASS** |

---

### Group 3: Bandwidth & WebSocket Preview Fallback (TC014 - TC018)

| Test ID | Name | Method / Target | Expected Contract | Actual Result | Status |
|---|---|---|---|---|:---:|
| **TC014** | `StartPreviewSignal` | `WS /connect_client -> /register_agent` | Client triggers `start_preview`, routed to Agent | Agent received `start_preview` command | **PASS** |
| **TC015** | `PrevBinaryFraming49Bytes` | `Binary H.264 Header` | 4B Magic "PREV", 32B DevID, 1B Keyframe, 8B PTS, 4B Len | 49-byte framing matches byte-for-byte | **PASS** |
| **TC016** | `KeyframeFlagDetection` | `NALU slice inspection` | Correctly classify SPS (7) and IDR (5) slices | Keyframe flags asserted | **PASS** |
| **TC017** | `PreviewFanOutMultipleClients` | `Hub.BroadcastPreviewBinary` | Fan out binary frame to multiple subscribers | All connected clients received frame | **PASS** |
| **TC018** | `StopPreviewCleanup` | `WS stop_preview` | Unsubscribe client and stop agent stream | Subscribers reduced to 0 | **PASS** |

---

### Group 4: Direct & Group Control Routing (TC019 - TC023)

| Test ID | Name | Method / Target | Expected Contract | Actual Result | Status |
|---|---|---|---|---|:---:|
| **TC019** | `SingleTouchControl` | `group_control_event -> touch` | Route pointer DOWN/MOVE/UP to specific device | Delivered touch payload with x/y coords | **PASS** |
| **TC020** | `MultiTouchTracking` | `MotionEvent pointers` | Track dual pointers (ID 0 & ID 1) simultaneously | Separate tracking without collision | **PASS** |
| **TC021** | `KeycodeInjection` | `group_control_event -> inject_keycode` | Inject hardware keycodes (Back=4, Home=3) | Agent received hardware keycode event | **PASS** |
| **TC022** | `TextInjection` | `group_control_event -> inject_text` | Inject arbitrary Unicode strings | Agent received exact string payload | **PASS** |
| **TC023** | `GroupControl10DevicesConcurrent` | `Broadcast 1 -> 10 devices` | Send single Home key command to 10 devices | All 10 devices received event concurrently | **PASS** |

---

### Group 5: DataChannels Contract & Capabilities (TC024 - TC031)

| Test ID | Name | Method / Target | Expected Contract | Actual Result | Status |
|---|---|---|---|---|:---:|
| **TC024** | `FileChannelList` | `file-channel -> list` | JSON payload requesting directory contents | Serialized with type="list" & path | **PASS** |
| **TC025** | `FileChannelMkdir` | `file-channel -> mkdir` | JSON payload creating directories | Serialized with type="mkdir" & path | **PASS** |
| **TC026** | `FileUploadAndSHA256` | `file-channel -> upload_start` | Binary chunk streaming + SHA-256 verification | Chunk reassembly + hash matched | **PASS** |
| **TC027** | `SandboxPathTraversalRejection` | `sanitizeFilePath` | Block `../../etc/passwd` directory escape | Rejected traversal attempts | **PASS** |
| **TC028** | `CriticalSystemPathProtection` | `isCriticalSystemPath` | Block accidental deletion of `/`, `/system`, `/etc` | Deletion forbidden on system roots | **PASS** |
| **TC029** | `FileDownloadChunks` | `file-channel -> download_start` | Binary streaming download contract | Response includes size and request_id | **PASS** |
| **TC030** | `WebAdbPtyInitContract` | `adb-channel -> type="init"` | Detect interactive xterm.js PTY init session | Dual-mode router detects rows/cols | **PASS** |
| **TC031** | `AICommandExecution` | `ai-command-channel -> command` | Command dispatch and correlation via request_id | Correlated request/response schemas | **PASS** |

---

### Group 6: REST APIs, Batch Tasks, AI & Sharing (TC032 - TC036)

| Test ID | Name | Method / Target | Expected Contract | Actual Result | Status |
|---|---|---|---|---|:---:|
| **TC032** | `FileUploadAndList` | `POST /upload`, `GET /api/files` | Multipart APK upload and downloads listing | File stored and listed in API response | **PASS** |
| **TC033** | `BatchTaskCreation` | `POST /api/tasks` | Create batch command task for target devices | Returns task ID with running status | **PASS** |
| **TC034** | `BatchTaskDetails` | `GET /api/tasks/details?task_id=...` | Retrieve detailed per-device execution status | Detailed breakdown with progress metrics | **PASS** |
| **TC035** | `UserAIConfigCRUD` | `GET` / `POST /api/user/ai-config` | Update and retrieve OpenAI/custom LLM settings | Saved to persistence store and restored | **PASS** |
| **TC036** | `ShareLinkPolicyUpdate` | `POST /api/share/update` | Toggle `view_only` vs `can_control` on share links | Updated permissions saved and enforced | **PASS** |

---

### Group 7: Compilation, Android App & Deployment (TC037 - TC040)

| Test ID | Name | Method / Target | Expected Contract | Actual Result | Status |
|---|---|---|---|---|:---:|
| **TC037** | `SignalingPureGoCompilation` | `webrtc-signaling` build | Compiles with pure Go without CGO dependencies | Clean compilation on Windows/Linux | **PASS** |
| **TC038** | `AgentPureGoCompilation` | `cloudphone-agent` cross-compile | Cross-compiles for Linux ARM64, ARMv7, AMD64 | Pure Go cross-compilation successful | **PASS** |
| **TC039** | `AndroidKotlinAppStructure` | `android-app` Gradle project | Valid build.gradle, AndroidManifest, Shizuku, AudioTrack | Verified Gradle 8.5 project layout | **PASS** |
| **TC040** | `DockerSourceBuildPipeline` | `Dockerfile` multi-stage | Pure source multi-stage Docker build pipeline | Clean 3-stage build verified | **PASS** |

---

## Instructions to Re-run Test Suite

To run the complete suite at any time:

```bash
cd recovered_source/webrtc-signaling
go test -count=1 -v -run TestParityMatrix ./...
```

To run agent conformance tests:

```bash
cd recovered_source/cloudphone-agent
go test -count=1 -v ./...
```
