import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SipServer 2.0: a self-contained SIP/UDP sandbox (Java 8+, no dependencies).
 *
 * Signaling: OPTIONS, INVITE (100/180/200), ACK, BYE, error responses.
 * - RFC-style dialog identifiers and tagged replies.
 * - Idempotent final responses for duplicate UDP requests.
 * - Retransmits INVITE 200 OK until ACK (up to 32 seconds).
 * - One media socket per dialog, optional PCMU/PCMA RTP receive or echo.
 * - Small extension points: RequestRouter, MediaEngine, AudioProcessor.
 *
 * Not a production SIP server: no REGISTER/authentication, TCP/TLS, SIP proxy,
 * forking, re-INVITE, hold, NAT traversal, RTCP, SRTP or SIP-over-WebSocket.
 * Use only on an authorized test network. Avoid exposing UDP 5060 publicly.
 */
public final class SipServer {
    private SipServer() { }

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            Config.usage();
            return;
        }
        if (config == null) return;
        new Sandbox(config).run();
    }

    // ----- Configuration -----
    static final class Config {
        String bind = "0.0.0.0";
        int port = 5060;
        String advertiseIp;
        String mediaMode = "off";  // off | receive | echo
        int maxCalls = 100;
        boolean verbose;

        static Config parse(String[] args) throws Exception {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    usage();
                    return null;
                }
                if ("--verbose".equals(arg)) {
                    c.verbose = true;
                    continue;
                }
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                String value = args[++i];
                switch (arg) {
                    case "--bind": c.bind = value; break;
                    case "--port": c.port = Integer.parseInt(value); break;
                    case "--advertise-ip": c.advertiseIp = value; break;
                    case "--media": c.mediaMode = value.toLowerCase(Locale.ROOT); break;
                    case "--max-calls": c.maxCalls = Integer.parseInt(value); break;
                    default: throw new IllegalArgumentException("Unknown option " + arg);
                }
            }
            if (c.port <= 0 || c.port > 65535 || c.maxCalls <= 0 || c.maxCalls > 10000) {
                throw new IllegalArgumentException("Invalid --port or --max-calls");
            }
            if (!Arrays.asList("off", "receive", "echo").contains(c.mediaMode)) {
                throw new IllegalArgumentException("--media must be off, receive, or echo");
            }
            if (!(InetAddress.getByName(c.bind) instanceof Inet4Address)) {
                throw new IllegalArgumentException("--bind must resolve to IPv4");
            }
            if (c.advertiseIp != null && !(InetAddress.getByName(c.advertiseIp) instanceof Inet4Address)) {
                throw new IllegalArgumentException("--advertise-ip must resolve to IPv4");
            }
            return c;
        }

        static void usage() {
            System.out.println("Usage: java SipServer [options]");
            System.out.println("  --bind 0.0.0.0          IP to listen on for SIP and RTP");
            System.out.println("  --port 5060             UDP SIP port");
            System.out.println("  --advertise-ip IP       IP placed in Contact and SDP (for NAT/lab)");
            System.out.println("  --media off             SIP-only; answer SDP with audio port 0");
            System.out.println("  --media receive         Receive G.711 RTP and log statistics");
            System.out.println("  --media echo            Receive and echo G.711 RTP back to sender");
            System.out.println("  --max-calls 100         Simultaneous call limit");
            System.out.println("  --verbose               Print complete SIP requests/responses");
            System.out.println("  --help                  Show help");
        }
    }

    // ----- Networking and request dispatch -----
    static final class Sandbox {
        final Config config;
        final Map<String, CallSession> calls = new ConcurrentHashMap<>();
        final Map<String, CachedResponse> transactions = new ConcurrentHashMap<>();
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sip-timer");
            thread.setDaemon(true);
            return thread;
        });
        final RequestRouter router;
        final MediaEngine mediaEngine;
        DatagramSocket socket;

        Sandbox(Config config) {
            this.config = config;
            this.mediaEngine = "off".equals(config.mediaMode)
                    ? new DisabledMediaEngine()
                    : new G711RtpEngine(config, new LoggingAudioProcessor());
            this.router = new RequestRouter(this);
        }

        void run() throws Exception {
            InetAddress bindAddress = InetAddress.getByName(config.bind);
            try (DatagramSocket sipSocket = new DatagramSocket(config.port, bindAddress)) {
                socket = sipSocket;
                log("SIP sandbox listening on " + config.bind + ":" + config.port
                        + "/UDP | media=" + config.mediaMode + " | maxCalls=" + config.maxCalls);
                log("OPTIONS / INVITE / ACK / BYE supported. Ctrl+C to exit.");
                Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll));
                timer.scheduleAtFixedRate(this::cleanCache, 30, 30, TimeUnit.SECONDS);
                while (true) {
                    try {
                        byte[] data = new byte[65535];
                        DatagramPacket packet = new DatagramPacket(data, data.length);
                        socket.receive(packet);
                        SipRequest request;
                        try {
                            request = SipRequest.parse(packet);
                        } catch (IllegalArgumentException bad) {
                            log("WARN malformed request from " + packet.getSocketAddress() + ": " + bad.getMessage());
                            continue;  // Cannot form a valid response without mandatory headers.
                        }
                        log("<<< " + packet.getSocketAddress() + " " + request.startLine);
                        if (config.verbose) System.out.println(request.raw);

                        if (!"ACK".equals(request.method)) {
                            CachedResponse old = transactions.get(request.transactionKey());
                            if (old != null && old.expiresAt > System.currentTimeMillis()) {
                                sendBytes(old.bytes, packet.getAddress(), packet.getPort());
                                log("[retransmission] replayed final response");
                                continue;
                            }
                        }
                        try {
                            router.dispatch(request);
                        } catch (Exception ex) {
                            log("ERROR handling " + request.method + ": " + ex);
                            if (!"ACK".equals(request.method)) {
                                reply(request, 500, "Server Internal Error", null, null, false);
                            }
                        }
                    } catch (SocketException closed) {
                        if (sipSocket.isClosed()) break;
                        throw closed;
                    } catch (Exception ex) {
                        log("ERROR receiving packet: " + ex);
                    }
                }
            } finally {
                closeAll();
            }
        }

        void reply(SipRequest req, int code, String reason, String toTag,
                   String sdp, boolean inviteContact) throws IOException {
            String ip = advertisedIp(req.peerAddress, req.peerPort);
            byte[] bytes = SipResponse.build(req, code, reason, toTag, sdp,
                    inviteContact ? "<sip:sandbox@" + ip + ":" + config.port + ">" : null);
            sendBytes(bytes, req.peerAddress, req.peerPort);
            if (code >= 200) {
                transactions.put(req.transactionKey(), new CachedResponse(bytes));
            }
        }

        void sendBytes(byte[] bytes, InetAddress address, int port) throws IOException {
            DatagramSocket s = socket;
            if (s == null || s.isClosed()) return;
            s.send(new DatagramPacket(bytes, bytes.length, address, port));
            if (config.verbose) System.out.println("<<< SEND >>>\n" + new String(bytes, StandardCharsets.UTF_8));
            else log(">>> " + new String(bytes, 0, indexOfCrLf(bytes), StandardCharsets.US_ASCII));
        }

        String advertisedIp(InetAddress peer, int port) throws SocketException {
            if (config.advertiseIp != null) return config.advertiseIp;
            if (!"0.0.0.0".equals(config.bind)) return config.bind;
            try (DatagramSocket probe = new DatagramSocket()) {
                probe.connect(peer, port);
                return probe.getLocalAddress().getHostAddress();
            }
        }

        void schedule200Retry(CallSession session, long delayMs) {
            timer.schedule(() -> {
                if (session.state != State.WAIT_ACK || calls.get(session.key) != session) return;
                if (System.currentTimeMillis() - session.acceptedAt >= 32000) {
                    log("INVITE 200 OK not ACKed; expiring " + session.callId);
                    endCall(session);
                    return;
                }
                try {
                    sendBytes(session.inviteOk, session.peerAddress, session.peerPort);
                } catch (IOException ex) {
                    log("WARN 200 retry failed: " + ex.getMessage());
                }
                schedule200Retry(session, Math.min(delayMs * 2, 4000));
            }, delayMs, TimeUnit.MILLISECONDS);
        }

        void endCall(CallSession call) {
            call.state = State.TERMINATED;
            calls.remove(call.key, call);
            call.media.close();
            log("CALL END id=" + call.callId + " " + call.media.stats());
        }

        void cleanCache() {
            long now = System.currentTimeMillis();
            transactions.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        }

        void closeAll() {
            timer.shutdownNow();
            for (CallSession call : calls.values()) endCall(call);
            DatagramSocket s = socket;
            if (s != null && !s.isClosed()) s.close();
        }
    }

    // ----- SIP request logic; add new methods/scenarios here -----
    static final class RequestRouter {
        final Sandbox app;

        RequestRouter(Sandbox app) { this.app = app; }

        void dispatch(SipRequest request) throws Exception {
            switch (request.method) {
                case "OPTIONS":
                    app.reply(request, 200, "OK", randomTag(), null, false);
                    break;
                case "INVITE":
                    invite(request);
                    break;
                case "ACK":
                    ack(request);
                    break;
                case "BYE":
                    bye(request);
                    break;
                default:
                    app.reply(request, 501, "Not Implemented", null, null, false);
            }
        }

        void invite(SipRequest req) throws Exception {
            if (req.fromTag() == null) {
                app.reply(req, 400, "Missing From Tag", null, null, false);
                return;
            }
            if (req.toTag() != null) {
                app.reply(req, 501, "Re-INVITE Not Supported", req.toTag(), null, false);
                return;
            }
            String key = req.callKey();
            if (app.calls.containsKey(key)) {
                app.reply(req, 491, "Request Pending", app.calls.get(key).toTag, null, false);
                return;
            }
            if (app.calls.size() >= app.config.maxCalls) {
                app.reply(req, 503, "Service Unavailable", randomTag(), null, false);
                return;
            }
            app.reply(req, 100, "Trying", null, null, false);
            MediaSession media;
            try {
                media = app.mediaEngine.open(req);
            } catch (UnsupportedAudioException ex) {
                app.reply(req, 488, "Not Acceptable Here", randomTag(), null, false);
                return;
            }
            String tag = randomTag();
            CallSession call = new CallSession(req, tag, media);
            app.calls.put(key, call);
            try {
                String ip = app.advertisedIp(req.peerAddress, req.peerPort);
                app.reply(req, 180, "Ringing", tag, null, false);
                String answer = media.answerSdp(ip);
                byte[] ok = SipResponse.build(req, 200, "OK", tag, answer,
                        "<sip:sandbox@" + ip + ":" + app.config.port + ">");
                call.inviteOk = ok;
                call.acceptedAt = System.currentTimeMillis();
                call.state = State.WAIT_ACK;
                app.sendBytes(ok, req.peerAddress, req.peerPort);
                app.transactions.put(req.transactionKey(), new CachedResponse(ok));
                app.schedule200Retry(call, 500);
                log("CALL SETUP " + call.callId + " media=" + media.description());
            } catch (Exception ex) {
                app.endCall(call);
                throw ex;
            }
        }

        void ack(SipRequest req) {
            CallSession call = app.calls.get(req.callKey());
            if (call != null && call.toTag.equals(req.toTag()) && call.state == State.WAIT_ACK) {
                call.state = State.ESTABLISHED;
                log("CALL ESTABLISHED id=" + call.callId);
            } else {
                log("ACK ignored: no matching pending call");
            }
        }

        void bye(SipRequest req) throws IOException {
            CallSession call = app.calls.get(req.callKey());
            if (call == null || !call.toTag.equals(req.toTag())) {
                app.reply(req, 481, "Call/Transaction Does Not Exist", null, null, false);
                return;
            }
            app.reply(req, 200, "OK", call.toTag, null, false);
            app.endCall(call);
        }
    }

    enum State { WAIT_ACK, ESTABLISHED, TERMINATED }

    static final class CallSession {
        final String key;
        final String callId;
        final String toTag;
        final InetAddress peerAddress;
        final int peerPort;
        final MediaSession media;
        volatile State state = State.WAIT_ACK;
        volatile long acceptedAt;
        volatile byte[] inviteOk;

        CallSession(SipRequest req, String toTag, MediaSession media) {
            this.key = req.callKey();
            this.callId = req.header("Call-ID");
            this.toTag = toTag;
            this.peerAddress = req.peerAddress;
            this.peerPort = req.peerPort;
            this.media = media;
        }
    }

    static final class CachedResponse {
        final byte[] bytes;
        final long expiresAt = System.currentTimeMillis() + 60000;
        CachedResponse(byte[] bytes) { this.bytes = bytes; }
    }

    // ----- SIP text parser and response writer -----
    static final class SipRequest {
        final String raw;
        final String startLine;
        final String method;
        final Map<String, List<String>> headers;
        final String body;
        final InetAddress peerAddress;
        final int peerPort;
        final long cseqNumber;

        SipRequest(String raw, String startLine, String method, Map<String, List<String>> headers,
                   String body, InetAddress peerAddress, int peerPort, long cseqNumber) {
            this.raw = raw;
            this.startLine = startLine;
            this.method = method;
            this.headers = headers;
            this.body = body;
            this.peerAddress = peerAddress;
            this.peerPort = peerPort;
            this.cseqNumber = cseqNumber;
        }

        static SipRequest parse(DatagramPacket p) {
            String raw = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            int separatorLength = 4;
            if (split < 0) {
                split = raw.indexOf("\n\n");
                separatorLength = 2;
            }
            if (split < 0) throw new IllegalArgumentException("Missing header/body delimiter");
            String[] lines = raw.substring(0, split).split("\r?\n");
            if (lines.length == 0) throw new IllegalArgumentException("Empty SIP request");
            String[] first = lines[0].trim().split("\\s+");
            if (first.length != 3 || !first[0].matches("[A-Z]+") || !"SIP/2.0".equals(first[2])) {
                throw new IllegalArgumentException("Invalid SIP start-line: " + lines[0]);
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            String last = null;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if ((line.startsWith(" ") || line.startsWith("\t")) && last != null) {
                    List<String> values = headers.get(last);
                    int lastIndex = values.size() - 1;
                    values.set(lastIndex, values.get(lastIndex) + " " + line.trim());
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 1) throw new IllegalArgumentException("Invalid header: " + line);
                last = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                headers.computeIfAbsent(last, k -> new ArrayList<>()).add(line.substring(colon + 1).trim());
            }
            String[] mandatory = {"via", "from", "to", "call-id", "cseq"};
            for (String name : mandatory) {
                if (!headers.containsKey(name) || headers.get(name).get(0).isEmpty()) {
                    throw new IllegalArgumentException("Missing " + name);
                }
            }
            String cseq = headers.get("cseq").get(0);
            String[] cseqTokens = cseq.split("\\s+");
            long cseqNumber;
            try {
                if (cseqTokens.length != 2 || !cseqTokens[1].equalsIgnoreCase(first[0])) {
                    throw new NumberFormatException("CSeq method mismatch");
                }
                cseqNumber = Long.parseLong(cseqTokens[0]);
                if (cseqNumber < 0) throw new NumberFormatException("Negative CSeq");
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("Invalid CSeq " + cseq);
            }
            String body = raw.substring(split + separatorLength);
            SipRequest req = new SipRequest(raw, lines[0], first[0], headers, body,
                    p.getAddress(), p.getPort(), cseqNumber);
            if (req.header("Call-ID").length() > 256) throw new IllegalArgumentException("Call-ID too long");
            return req;
        }

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        List<String> allHeaders(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? new ArrayList<>() : values;
        }

        String fromTag() { return SipHeaders.param(header("From"), "tag"); }
        String toTag() { return SipHeaders.param(header("To"), "tag"); }

        String callKey() { return header("Call-ID") + "|" + fromTag(); }

        String transactionKey() {
            return callKey() + "|" + method + "|" + cseqNumber + "|"
                    + SipHeaders.param(header("Via"), "branch");
        }
    }

    static final class SipHeaders {
        private SipHeaders() { }

        static String param(String value, String name) {
            if (value == null) return null;
            // Simple semicolon parameter extraction (adequate for sandbox tags/branches).
            for (String token : value.split(";")) {
                int equals = token.indexOf('=');
                if (equals > 0 && token.substring(0, equals).trim().equalsIgnoreCase(name)) {
                    return token.substring(equals + 1).trim().replace("\"", "");
                }
            }
            return null;
        }
    }

    static final class SipResponse {
        private SipResponse() { }

        static byte[] build(SipRequest req, int code, String reason, String toTag,
                            String body, String contact) {
            StringBuilder out = new StringBuilder();
            out.append("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
            for (String via : req.allHeaders("Via")) {
                // Fill in empty rport (RFC 3581); replies are always sent to UDP source.
                String top = via.replaceAll("(?i);rport(?=;|$)", ";rport=" + req.peerPort);
                out.append("Via: ").append(top).append("\r\n");
            }
            out.append("From: ").append(req.header("From")).append("\r\n");
            String to = req.header("To");
            if (toTag != null && SipHeaders.param(to, "tag") == null) to += ";tag=" + toTag;
            out.append("To: ").append(to).append("\r\n");
            out.append("Call-ID: ").append(req.header("Call-ID")).append("\r\n");
            out.append("CSeq: ").append(req.header("CSeq")).append("\r\n");
            out.append("Server: JavaSipSandbox/2.0\r\n");
            if ("OPTIONS".equals(req.method)) out.append("Allow: INVITE, ACK, BYE, OPTIONS\r\n");
            if (contact != null) out.append("Contact: ").append(contact).append("\r\n");
            if (body != null) out.append("Content-Type: application/sdp\r\n");
            byte[] content = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            out.append("Content-Length: ").append(content.length).append("\r\n\r\n");
            return (out.toString() + (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8);
        }
    }

    // ----- Media: keep the signaling layer independent of RTP -----
    interface MediaEngine {
        MediaSession open(SipRequest invite) throws IOException, UnsupportedAudioException;
    }

    interface MediaSession extends AutoCloseable {
        String answerSdp(String localIp);
        String description();
        String stats();
        void close();
    }

    static final class UnsupportedAudioException extends Exception {
        UnsupportedAudioException(String message) { super(message); }
    }

    static final class DisabledMediaEngine implements MediaEngine {
        public MediaSession open(SipRequest req) { return new DisabledMediaSession(); }
    }

    static final class DisabledMediaSession implements MediaSession {
        public String answerSdp(String ip) {
            return Sdp.answer(ip, 0, 0, "inactive");
        }
        public String description() { return "disabled"; }
        public String stats() { return "audio=disabled"; }
        public void close() { }
    }

    static final class Sdp {
        private Sdp() { }

        static String answer(String ip, int port, int payloadType, String direction) {
            long id = System.currentTimeMillis();
            String codec = payloadType == 8 ? "PCMA" : "PCMU";
            return "v=0\r\n"
                    + "o=sandbox " + id + " " + id + " IN IP4 " + ip + "\r\n"
                    + "s=Java SIP Sandbox\r\n"
                    + "c=IN IP4 " + ip + "\r\n"
                    + "t=0 0\r\n"
                    + "m=audio " + port + " RTP/AVP " + payloadType + "\r\n"
                    + "a=rtpmap:" + payloadType + " " + codec + "/8000\r\n"
                    + "a=" + direction + "\r\n"
                    + "a=ptime:20\r\n";
        }

        static int offeredCodec(String body) throws UnsupportedAudioException {
            if (body == null) throw new UnsupportedAudioException("Missing SDP");
            for (String line : body.split("\\r?\\n")) {
                if (line.startsWith("m=audio ")) {
                    String[] parts = line.substring(2).trim().split("\\s+");
                    if (parts.length < 4 || !"RTP/AVP".equalsIgnoreCase(parts[2])) {
                        throw new UnsupportedAudioException("Unsupported audio profile");
                    }
                    int remotePort;
                    try {
                        remotePort = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ex) {
                        throw new UnsupportedAudioException("Invalid audio port");
                    }
                    if (remotePort <= 0 || remotePort > 65535) {
                        throw new UnsupportedAudioException("Audio stream disabled/invalid");
                    }
                    // G.711 PCMU (0) preferred, then G.711 PCMA (8).
                    for (int target : new int[]{0, 8}) {
                        for (int i = 3; i < parts.length; i++) {
                            if (Integer.toString(target).equals(parts[i])) return target;
                        }
                    }
                    throw new UnsupportedAudioException("No supported G.711 codec");
                }
            }
            throw new UnsupportedAudioException("No audio m= line");
        }
    }

    static final class G711RtpEngine implements MediaEngine {
        final Config config;
        final AudioProcessor audioProcessor;

        G711RtpEngine(Config config, AudioProcessor audioProcessor) {
            this.config = config;
            this.audioProcessor = audioProcessor;
        }

        public MediaSession open(SipRequest invite) throws IOException, UnsupportedAudioException {
            int pt = Sdp.offeredCodec(invite.body);
            DatagramSocket mediaSocket = new DatagramSocket(0, InetAddress.getByName(config.bind));
            RtpMediaSession session = new RtpMediaSession(mediaSocket, pt, invite.peerAddress,
                    "echo".equals(config.mediaMode), invite.header("Call-ID"), audioProcessor);
            session.start();
            return session;
        }
    }

    /** Replace this with recording, VAD, STT, Pindrop integrations, etc. */
    interface AudioProcessor {
        void onPcmSamples(String callId, short[] pcm16, int sampleRate);
    }

    static final class LoggingAudioProcessor implements AudioProcessor {
        public void onPcmSamples(String callId, short[] pcm16, int sampleRate) {
            // Intentional no-op: the RTP session tracks packet/sample counters.
            // Future: hand off these decoded PCM16 samples asynchronously.
        }
    }

    static final class RtpMediaSession implements MediaSession {
        final DatagramSocket socket;
        final int payloadType;
        final InetAddress allowedSender;
        final boolean echo;
        final String callId;
        final AudioProcessor processor;
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicLong packets = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong ignored = new AtomicLong();
        final int ssrc = UUID.randomUUID().hashCode();
        int echoSeq = (int) (System.nanoTime() & 0xffff);

        RtpMediaSession(DatagramSocket socket, int payloadType, InetAddress allowedSender,
                        boolean echo, String callId, AudioProcessor processor) {
            this.socket = socket;
            this.payloadType = payloadType;
            this.allowedSender = allowedSender;
            this.echo = echo;
            this.callId = callId;
            this.processor = processor;
        }

        void start() {
            Thread thread = new Thread(this::receiveLoop, "rtp-" + socket.getLocalPort());
            thread.setDaemon(true);
            thread.start();
        }

        void receiveLoop() {
            try {
                socket.setSoTimeout(1000);
                byte[] buf = new byte[2048];
                while (running.get()) {
                    DatagramPacket incoming = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(incoming);
                    } catch (SocketTimeoutException timeout) {
                        continue;
                    } catch (SocketException closed) {
                        if (!running.get()) break;
                        throw closed;
                    }
                    if (!incoming.getAddress().equals(allowedSender)) {
                        ignored.incrementAndGet();
                        continue; // Avoid reflecting traffic to unrelated third parties.
                    }
                    RtpPacket pkt = RtpPacket.parse(incoming.getData(), incoming.getLength());
                    if (pkt == null || pkt.payloadType != payloadType) {
                        ignored.incrementAndGet();
                        continue;
                    }
                    packets.incrementAndGet();
                    bytes.addAndGet(pkt.payloadLength);
                    short[] pcm = new short[pkt.payloadLength];
                    for (int i = 0; i < pcm.length; i++) {
                        int value = incoming.getData()[pkt.payloadOffset + i] & 0xff;
                        pcm[i] = payloadType == 0 ? G711.decodeMuLaw(value) : G711.decodeALaw(value);
                    }
                    processor.onPcmSamples(callId, pcm, 8000);
                    if (echo) {
                        byte[] reply = pkt.buildEcho(incoming.getData(), echoSeq++ & 0xffff, ssrc);
                        socket.send(new DatagramPacket(reply, reply.length,
                                incoming.getAddress(), incoming.getPort()));
                    }
                }
            } catch (Exception ex) {
                if (running.get()) log("WARN RTP session " + callId + " error: " + ex);
            }
        }

        public String answerSdp(String ip) {
            return Sdp.answer(ip, socket.getLocalPort(), payloadType, echo ? "sendrecv" : "recvonly");
        }
        public String description() {
            return "RTP/UDP:" + socket.getLocalPort() + " " + (payloadType == 0 ? "PCMU" : "PCMA")
                    + " " + (echo ? "echo" : "receive");
        }
        public String stats() {
            return "rtpPackets=" + packets.get() + " payloadBytes=" + bytes.get()
                    + " ignoredPackets=" + ignored.get();
        }
        public void close() {
            if (running.compareAndSet(true, false)) socket.close();
        }
    }

    static final class RtpPacket {
        final int payloadType;
        final int payloadOffset;
        final int payloadLength;
        final int timestamp;

        RtpPacket(int pt, int offset, int len, int timestamp) {
            this.payloadType = pt;
            this.payloadOffset = offset;
            this.payloadLength = len;
            this.timestamp = timestamp;
        }

        static RtpPacket parse(byte[] data, int length) {
            if (length < 12 || ((data[0] >>> 6) & 3) != 2) return null;
            int csrcCount = data[0] & 15;
            int offset = 12 + csrcCount * 4;
            if (length < offset) return null;
            if ((data[0] & 16) != 0) {
                if (length < offset + 4) return null;
                int words = ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
                offset += 4 + words * 4;
                if (offset > length) return null;
            }
            int padding = (data[0] & 32) == 0 ? 0 : (data[length - 1] & 255);
            if (padding > length - offset) return null;
            int ts = (data[4] & 255) << 24 | (data[5] & 255) << 16
                    | (data[6] & 255) << 8 | (data[7] & 255);
            return new RtpPacket(data[1] & 127, offset, length - offset - padding, ts);
        }

        byte[] buildEcho(byte[] received, int sequence, int ssrc) {
            byte[] out = new byte[12 + payloadLength];
            out[0] = (byte) 0x80;
            out[1] = (byte) payloadType;
            out[2] = (byte) (sequence >>> 8);
            out[3] = (byte) sequence;
            writeInt(out, 4, timestamp);
            writeInt(out, 8, ssrc);
            System.arraycopy(received, payloadOffset, out, 12, payloadLength);
            return out;
        }

        static void writeInt(byte[] b, int off, int value) {
            b[off] = (byte) (value >>> 24);
            b[off + 1] = (byte) (value >>> 16);
            b[off + 2] = (byte) (value >>> 8);
            b[off + 3] = (byte) value;
        }
    }

    /** G.711 to linear signed 16-bit PCM; hook for future audio processing. */
    static final class G711 {
        private G711() { }

        static short decodeMuLaw(int byteValue) {
            int u = (~byteValue) & 0xff;
            int sample = (((u & 0x0f) << 3) + 0x84) << ((u & 0x70) >>> 4);
            sample -= 0x84;
            return (short) ((u & 0x80) != 0 ? -sample : sample);
        }

        static short decodeALaw(int byteValue) {
            int a = byteValue ^ 0x55;
            int sample = (a & 0x0f) << 4;
            int segment = (a & 0x70) >>> 4;
            if (segment == 0) sample += 8;
            else if (segment == 1) sample += 0x108;
            else sample = (sample + 0x108) << (segment - 1);
            return (short) ((a & 0x80) != 0 ? sample : -sample);
        }
    }

    static int indexOfCrLf(byte[] data) {
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') return i;
        }
        return data.length;
    }

    static String randomTag() { return "sbx-" + UUID.randomUUID().toString().substring(0, 8); }

    static void log(String message) {
        System.out.println("[" + LocalTime.now().withNano(0) + "] " + message);
    }
}
