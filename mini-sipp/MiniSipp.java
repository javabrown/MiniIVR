import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MiniSipp 1.0: standalone Java 8+ SIP-over-UDP call generator.
 *
 * Normal flow: INVITE (SDP) -> 1xx -> 200 OK -> ACK -> BYE -> 200 OK.
 * Rejected flow: INVITE -> non-2xx final -> ACK (same transaction branch).
 *
 * Deliberate limitations: no RTP, SIP digest auth, TCP/TLS, CANCEL,
 * Route/Record-Route processing, forking, concurrency, or full transaction FSM.
 * Run only against SIP equipment you own or are authorized to test.
 */
public final class MiniSipp {
    private MiniSipp() { }

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            Config.usage();
            System.exit(2);
            return;
        }
        if (config == null) { // --help
            return;
        }

        InetAddress address = InetAddress.getByName(config.host);
        if (!(address.getHostAddress().contains(":"))) { // SDP/Via generator is IPv4-only
            InetSocketAddress server = new InetSocketAddress(address, config.port);
            int passed = 0;
            try (DatagramSocket socket = new DatagramSocket(config.localPort)) {
                // Choose the source IP used to reach the destination without sending a packet.
                String localIp;
                try (DatagramSocket probe = new DatagramSocket()) {
                    probe.connect(server);
                    localIp = probe.getLocalAddress().getHostAddress();
                }
                if (config.advertiseIp != null) {
                    localIp = config.advertiseIp;
                }
                UdpTransport transport = new UdpTransport(socket, server, config.verbose);
                System.out.printf("MiniSipp: UDP %s:%d -> %s:%d | calls=%d%n",
                        localIp, socket.getLocalPort(), config.host, config.port, config.count);
                for (int i = 1; i <= config.count; i++) {
                    System.out.printf("%n=== %s %d/%d ===%n", config.scenario, i, config.count);
                    try {
                        SipCall call = new SipCall(transport, config, localIp);
                        if ("options".equals(config.scenario)) {
                            call.runOptions();
                        } else {
                            call.run();
                        }
                        passed++;
                        System.out.println("RESULT: PASS");
                    } catch (Exception e) {
                        System.err.println("RESULT: FAIL - " + e.getMessage());
                    }
                    if (i < config.count) {
                        Thread.sleep(config.intervalMs);
                    }
                }
            }
            System.out.printf("%nSummary: %d/%d calls passed%n", passed, config.count);
            if (passed != config.count) {
                System.exit(1);
            }
        } else {
            throw new IllegalArgumentException("Version 1 supports IPv4 only");
        }
    }

    static final class Config {
        String host;
        String scenario = "call";
        final List<String> extraHeaders = new ArrayList<>();
        int port = 5060;
        String from = "1001";
        String to = "1002";
        int localPort = 0;
        int rtpPort = 40000;
        int count = 1;
        long intervalMs = 1000;
        long durationMs = 2000;
        long timeoutMs = 10000;
        String advertiseIp;
        boolean verbose = true;

        static Config parse(String[] args) {
            Config c = new Config();
            if (args.length == 0) {
                throw new IllegalArgumentException("Missing --host");
            }
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if ("--help".equals(key) || "-h".equals(key)) {
                    usage();
                    return null;
                }
                if ("--quiet".equals(key)) {
                    c.verbose = false;
                    continue;
                }
                if (!key.startsWith("--") || i + 1 == args.length) {
                    throw new IllegalArgumentException("Expected --key value: " + key);
                }
                String value = args[++i];
                switch (key) {
                    case "--host": c.host = value; break;
                    case "--scenario": c.scenario = value.toLowerCase(java.util.Locale.ROOT); break;
                    case "--header": c.extraHeaders.add(value); break;
                    case "--port": c.port = Integer.parseInt(value); break;
                    case "--from": c.from = value; break;
                    case "--to": c.to = value; break;
                    case "--local-port": c.localPort = Integer.parseInt(value); break;
                    case "--rtp-port": c.rtpPort = Integer.parseInt(value); break;
                    case "--count": c.count = Integer.parseInt(value); break;
                    case "--interval-ms": c.intervalMs = Long.parseLong(value); break;
                    case "--duration-ms": c.durationMs = Long.parseLong(value); break;
                    case "--timeout-ms": c.timeoutMs = Long.parseLong(value); break;
                    case "--advertise-ip": c.advertiseIp = value; break;
                    default: throw new IllegalArgumentException("Unknown option " + key);
                }
            }
            if (c.host == null || c.host.isEmpty()) throw new IllegalArgumentException("--host is required");
            if (!("call".equals(c.scenario) || "options".equals(c.scenario))) {
                throw new IllegalArgumentException("--scenario must be call or options");
            }
            for (String header : c.extraHeaders) {
                int colon = header.indexOf(':');
                if (colon < 1 || !header.substring(0, colon).matches("[A-Za-z0-9-]+") ||
                        header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0 ||
                        header.length() > 1000) {
                    throw new IllegalArgumentException("Invalid --header: " + header);
                }
            }
            if (c.port < 1 || c.port > 65535 || c.localPort < 0 || c.localPort > 65535 ||
                    c.rtpPort < 1 || c.rtpPort > 65535 || c.count < 1 || c.intervalMs < 0 ||
                    c.durationMs < 0 || c.timeoutMs < 1000) {
                throw new IllegalArgumentException("Invalid ports, counts, or timings");
            }
            if (!c.from.matches("[A-Za-z0-9_.+~-]+") || !c.to.matches("[A-Za-z0-9_.+~-]+")) {
                throw new IllegalArgumentException("--from/--to must be simple SIP user names");
            }
            if (c.advertiseIp != null && !c.advertiseIp.matches("[0-9.]+")) {
                throw new IllegalArgumentException("--advertise-ip must be an IPv4 address");
            }
            return c;
        }

        static void usage() {
            System.out.println("Usage: java MiniSipp --host 127.0.0.1 [options]");
            System.out.println("  --port 5060        --from 1001         --to 1002");
            System.out.println("  --scenario call|options   --header 'X-Test-ID: 123' (repeatable)");
            System.out.println("  --local-port 0     --rtp-port 40000    --advertise-ip 192.168.1.10");
            System.out.println("  --count 1          --interval-ms 1000  --duration-ms 2000");
            System.out.println("  --timeout-ms 10000 --quiet            --help");
            System.out.println("RTP port appears only in SDP; this version does not send RTP.");
        }
    }

    static final class UdpTransport {
        final DatagramSocket socket;
        final InetSocketAddress server;
        final boolean verbose;

        UdpTransport(DatagramSocket socket, InetSocketAddress server, boolean verbose) {
            this.socket = socket;
            this.server = server;
            this.verbose = verbose;
        }

        void send(String message) throws Exception {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(bytes, bytes.length, server));
            if (verbose) {
                System.out.println("\n>>> " + server + "\n" + message);
            } else {
                System.out.println(">>> " + message.substring(0, message.indexOf("\r\n")));
            }
        }

        SipMessage receive(long waitMs) throws Exception {
            byte[] bytes = new byte[65535];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            socket.setSoTimeout((int) Math.max(1, Math.min(waitMs, Integer.MAX_VALUE)));
            socket.receive(packet);
            String raw = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8);
            SipMessage message = new SipMessage(raw);
            if (verbose) {
                System.out.println("\n<<< " + packet.getSocketAddress() + "\n" + raw);
            } else {
                System.out.println("<<< " + message.firstLine);
            }
            return message;
        }
    }

    static final class SipMessage {
        final String firstLine;
        final Map<String, String> headers = new HashMap<>();

        SipMessage(String raw) {
            String headerBlock = raw.split("\r?\n\r?\n", 2)[0];
            String[] lines = headerBlock.split("\r?\n");
            firstLine = lines.length == 0 ? "" : lines[0];
            String lastKey = null;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                // Accept common RFC 3261 obsolete folded header continuations.
                if ((line.startsWith(" ") || line.startsWith("\t")) && lastKey != null) {
                    headers.put(lastKey, headers.get(lastKey) + " " + line.trim());
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    lastKey = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                    headers.put(lastKey, line.substring(colon + 1).trim());
                }
            }
        }

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        int responseCode() {
            if (!firstLine.startsWith("SIP/2.0 ")) return -1;
            String[] tokens = firstLine.split("\\s+", 3);
            try {
                return Integer.parseInt(tokens[1]);
            } catch (Exception e) {
                return -1;
            }
        }

        boolean matches(String callId, String method) {
            String cseq = header("CSeq");
            return callId.equals(header("Call-ID")) && cseq != null &&
                    cseq.toUpperCase(java.util.Locale.ROOT).matches("\\d+\\s+" + method + "\\s*");
        }
    }

    static final class SipCall {
        final UdpTransport udp;
        final Config config;
        final String localIp;
        final String callId;
        final String fromTag;
        final String uri;
        final String initialTo;
        final String fromHeader;
        final String contact;
        final String inviteBranch;
        final int localPort;

        SipCall(UdpTransport udp, Config config, String localIp) {
            this.udp = udp;
            this.config = config;
            this.localIp = localIp;
            this.localPort = udp.socket.getLocalPort();
            this.callId = UUID.randomUUID() + "@" + localIp;
            this.fromTag = token();
            this.uri = "sip:" + config.to + "@" + config.host + ":" + config.port;
            this.initialTo = "<sip:" + config.to + "@" + config.host + ">";
            this.fromHeader = "<sip:" + config.from + "@" + localIp + ">;tag=" + fromTag;
            this.contact = "<sip:" + config.from + "@" + localIp + ":" + localPort + ">";
            this.inviteBranch = branch();
        }

        void runOptions() throws Exception {
            String options = request("OPTIONS", 1, branch(), initialTo, "");
            SipMessage response = exchangeFinal(options, "OPTIONS", true);
            int status = response.responseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("OPTIONS failed: " + response.firstLine);
            }
            System.out.println("OPTIONS succeeded: " + response.firstLine);
        }

        void run() throws Exception {
            String offer = SdpFactory.offer(localIp, config.rtpPort);
            String invite = request("INVITE", 1, inviteBranch, initialTo, offer);
            SipMessage response = exchangeInvite(invite);
            int status = response.responseCode();
            String dialogTo = response.header("To");
            if (dialogTo == null) dialogTo = initialTo;

            if (status < 200 || status >= 300) {
                // A non-2xx ACK belongs to the original INVITE transaction.
                udp.send(request("ACK", 1, inviteBranch, dialogTo, ""));
                throw new IllegalStateException("INVITE rejected: " + response.firstLine);
            }
            if (!dialogTo.toLowerCase(java.util.Locale.ROOT).contains(";tag=")) {
                throw new IllegalStateException("2xx To header lacks dialog tag");
            }

            // A 2xx ACK is a new request/transaction and uses a new Via branch.
            final String ack = request("ACK", 1, branch(), dialogTo, "");
            udp.send(ack);
            System.out.println("Call established (200 OK + ACK). Holding " + config.durationMs + " ms.");

            // During the configured hold time, acknowledge duplicate INVITE 2xx responses.
            long stop = System.currentTimeMillis() + config.durationMs;
            while (System.currentTimeMillis() < stop) {
                long left = stop - System.currentTimeMillis();
                try {
                    SipMessage incoming = udp.receive(Math.min(left, 250));
                    if (incoming.matches(callId, "INVITE") && incoming.responseCode() >= 200 &&
                            incoming.responseCode() < 300) {
                        udp.send(ack);
                    }
                } catch (SocketTimeoutException ignored) {
                    // No incoming packets during call holding period is expected.
                }
            }

            String bye = request("BYE", 2, branch(), dialogTo, "");
            SipMessage byeResponse = exchangeFinal(bye, "BYE", true);
            int byeStatus = byeResponse.responseCode();
            if (byeStatus < 200 || byeStatus >= 300) {
                throw new IllegalStateException("BYE failed: " + byeResponse.firstLine);
            }
            System.out.println("Call ended (BYE + " + byeResponse.firstLine + ").");
        }

        SipMessage exchangeInvite(String invite) throws Exception {
            return exchange(invite, "INVITE", false);
        }

        SipMessage exchangeFinal(String request, String method, boolean retransmit) throws Exception {
            return exchange(request, method, retransmit);
        }

        SipMessage exchange(String request, String method, boolean forceRetransmit) throws Exception {
            long deadline = System.currentTimeMillis() + config.timeoutMs;
            long retryMs = 500;
            long nextRetry = System.currentTimeMillis() + retryMs;
            boolean sawProvisional = false;
            udp.send(request);
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                boolean mayRetry = forceRetransmit || !sawProvisional;
                long nextEvent = mayRetry ? Math.min(deadline, nextRetry) : deadline;
                long waitMs = Math.max(1, nextEvent - now);
                try {
                    SipMessage message = udp.receive(waitMs);
                    if (!message.matches(callId, method)) continue;
                    int code = message.responseCode();
                    if (code < 100) continue;
                    if (code < 200) {
                        sawProvisional = true;
                        continue;
                    }
                    return message;
                } catch (SocketTimeoutException ignored) {
                    // Retransmit on the next loop, as permitted by state.
                }
                if (mayRetry && System.currentTimeMillis() >= nextRetry) {
                    udp.send(request); // Same request, Call-ID, CSeq, Via branch
                    retryMs = Math.min(retryMs * 2, 4000);
                    nextRetry = System.currentTimeMillis() + retryMs;
                }
            }
            throw new SocketTimeoutException("Timed out waiting for " + method + " final response");
        }

        String request(String method, int cseq, String viaBranch, String toHeader, String body) {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(uri).append(" SIP/2.0\r\n");
            sb.append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(localPort)
                    .append(";branch=").append(viaBranch).append(";rport\r\n");
            sb.append("Max-Forwards: 70\r\n");
            sb.append("From: ").append(fromHeader).append("\r\n");
            sb.append("To: ").append(toHeader).append("\r\n");
            sb.append("Call-ID: ").append(callId).append("\r\n");
            sb.append("CSeq: ").append(cseq).append(' ').append(method).append("\r\n");
            sb.append("Contact: ").append(contact).append("\r\n");
            sb.append("User-Agent: MiniSipp/1.0\r\n");
            if ("INVITE".equals(method) || "OPTIONS".equals(method)) {
                for (String header : config.extraHeaders) {
                    sb.append(header).append("\r\n");
                }
            }
            if (!body.isEmpty()) sb.append("Content-Type: application/sdp\r\n");
            sb.append("Content-Length: ")
                    .append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n");
            sb.append(body);
            return sb.toString();
        }

        static String token() {
            return UUID.randomUUID().toString().replace("-", "");
        }

        static String branch() {
            return "z9hG4bK-" + token();
        }
    }

    static final class SdpFactory {
        static String offer(String ip, int rtpPort) {
            long id = System.currentTimeMillis();
            return "v=0\r\n" +
                    "o=minisipp " + id + " " + id + " IN IP4 " + ip + "\r\n" +
                    "s=MiniSipp test\r\n" +
                    "c=IN IP4 " + ip + "\r\n" +
                    "t=0 0\r\n" +
                    "m=audio " + rtpPort + " RTP/AVP 0 8\r\n" +
                    "a=rtpmap:0 PCMU/8000\r\n" +
                    "a=rtpmap:8 PCMA/8000\r\n" +
                    "a=sendrecv\r\n";
        }
    }
}
