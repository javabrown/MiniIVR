# MiniSipp 1.0 — standalone Java SIP-over-UDP test client

**Requirements:** JDK 8 or later. No Maven, Gradle, external JARs, or SIP libraries.

## Compile and run

```bash
javac MiniSipp.java
java MiniSipp --host 127.0.0.1 --port 5060 --from 1001 --to 5000 --duration-ms 3000
```

Send 10 sequential calls:

```bash
java MiniSipp --host 127.0.0.1 --count 10 --interval-ms 1000 --duration-ms 2000
```

Send a SIP OPTIONS probe:

```bash
java MiniSipp --host 127.0.0.1 --scenario options
```

Inject a test header in the initial INVITE or OPTIONS request:

```bash
java MiniSipp --host 127.0.0.1 --header 'X-Test-ID: Pindrop-001' --header 'X-Lab-Mode: on'
```

When behind NAT or on a host with multiple interfaces, you may need to provide a reachable IP:

```bash
java MiniSipp --host 192.168.0.109 --local-port 5070 --advertise-ip 192.168.0.50
```

## Command-line options

| Option | Default | Meaning |
| --- | --- | --- |
| `--host` | required | Target SIP server DNS name or IPv4 address |
| `--port` | 5060 | Target SIP UDP port |
| `--scenario` | call | `call` (INVITE/ACK/BYE) or `options` |
| `--from` | 1001 | Caller SIP username |
| `--to` | 1002 | Callee SIP username |
| `--local-port` | 0 | SIP UDP listening port; 0 = assigned by OS |
| `--rtp-port` | 40000 | Port *advertised* in SDP; no RTP is sent |
| `--count` | 1 | Number of sequential test attempts |
| `--interval-ms` | 1000 | Sleep between attempts |
| `--duration-ms` | 2000 | Time between established call and BYE |
| `--timeout-ms` | 10000 | Max response wait for each SIP transaction |
| `--advertise-ip` | auto | Local IPv4 address advertised in Via, Contact, SDP |
| `--header` | none | Extra header for INVITE and OPTIONS; repeatable |
| `--quiet` | off | Print one line per SIP message rather than full SIP wire text |

## Call scenario

```text
MiniSipp                         SIP target
   | -------- INVITE+SDP -------> |
   | <-------- 100 Trying ------- |
   | <-------- 180 Ringing ------ |
   | <-------- 200 OK ----------- |
   | -------- ACK --------------> |
   | ... hold duration ...        |
   | -------- BYE --------------> |
   | <-------- 200 OK ----------- |
```

A rejected INVITE gets an ACK on the same transaction branch; the test is marked failed. UDP requests are retried on a basic exponential schedule, except an INVITE is not retried after a provisional response.

## File structure (one file for convenience)

- `Config` parses CLI arguments.
- `UdpTransport` sends and receives UDP packets.
- `SipMessage` parses responses and headers.
- `SipCall` manages the call or OPTIONS scenario.
- `SdpFactory` builds the initial audio SDP offer.

## Boundaries of version 1

- SIP signaling only: **no RTP packets/audio playback**, even though the SDP advertises PCMU/PCMA on the chosen port. This version is not an end-to-end voice-quality or Pindrop media test.
- IPv4 and UDP only. No SIP TLS/TCP, SRTP, or NAT traversal.
- No 401/407 SIP digest challenge handling.
- No full SIP dialog routing (Contact, Record-Route, Route), early-media handling, forking, PRACK, CANCEL, re-INVITE, or inbound BYE requests. All requests go to the configured `--host:--port` as a single SIP hop.
- No concurrency or load-testing rate controller. `--count` runs calls sequentially.
- No full standards-compliant SIP transaction/dialog state machine.

**Next useful step for Pindrop:** parse answer SDP, add an RTP sender/receiver for PCMU WAV test audio, and then make the call flow scriptable. Use only with servers you administer or have explicit authorization to test.

Protocol references: RFC 3261 (SIP), RFC 8866 (SDP), RFC 3264 (offer/answer).
