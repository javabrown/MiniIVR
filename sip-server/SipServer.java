
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;

public class SipServer {

    private static final int PORT = 5060;
    private static final int BUFFER_SIZE = 65535;

    public static void main(String[] args) {

        System.out.println();
        System.out.println("==========================================");
        System.out.println("          SIMPLE JAVA SIP SERVER");
        System.out.println("==========================================");

        try (DatagramSocket socket = new DatagramSocket(PORT)) {

            System.out.println();
            System.out.println("       SIP SERVER STARTED");
            System.out.println("------------------------------------------");

            System.out.println("Server IP Addresses:");

            Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface networkInterface =
                    interfaces.nextElement();

                if (!networkInterface.isUp() ||
                    networkInterface.isLoopback()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                    networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {

                    InetAddress address = addresses.nextElement();

                    if (address instanceof Inet4Address) {
                        System.out.println(
                            "  " + address.getHostAddress()
                        );
                    }
                }
            }

            System.out.println("------------------------------------------");
            System.out.println("Listening IP   : " +
                socket.getLocalAddress().getHostAddress());
            System.out.println("Listening Port : " + PORT);
            System.out.println("Protocol       : UDP");
            System.out.println("Status         : RUNNING");
            System.out.println("------------------------------------------");

            System.out.println();
            System.out.println("Waiting for incoming SIP messages...");
            System.out.println("Press CTRL+C to stop the server.");
            System.out.println("==========================================");

            while (true) {

                byte[] buffer = new byte[BUFFER_SIZE];

                DatagramPacket packet =
                    new DatagramPacket(buffer, buffer.length);

                socket.receive(packet);

                String sipMessage = new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8
                );

                String senderIP =
                    packet.getAddress().getHostAddress();

                int senderPort = packet.getPort();

                String timestamp = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                );

                System.out.println();
                System.out.println("==========================================");
                System.out.println("          INCOMING SIP MESSAGE");
                System.out.println("==========================================");

                System.out.println("Time        : " + timestamp);
                System.out.println("Sender IP   : " + senderIP);
                System.out.println("Sender Port : " + senderPort);
                System.out.println("Size        : " +
                    packet.getLength() + " bytes");

                System.out.println("------------------------------------------");
                System.out.println(sipMessage);
                System.out.println("------------------------------------------");

                // Respond to SIP OPTIONS
                if (sipMessage.startsWith("OPTIONS ")) {

                    String response =
                        "SIP/2.0 200 OK\r\n" +
                        getHeader(sipMessage, "Via") + "\r\n" +
                        getHeader(sipMessage, "From") + "\r\n" +
                        getHeader(sipMessage, "To") + ";tag=java123\r\n" +
                        getHeader(sipMessage, "Call-ID") + "\r\n" +
                        getHeader(sipMessage, "CSeq") + "\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";

                    byte[] responseBytes =
                        response.getBytes(StandardCharsets.UTF_8);

                    DatagramPacket responsePacket =
                        new DatagramPacket(
                            responseBytes,
                            responseBytes.length,
                            packet.getAddress(),
                            packet.getPort()
                        );

                    socket.send(responsePacket);

                    System.out.println(">>> SIP 200 OK response sent");
                }

                System.out.println("==========================================");
            }

        } catch (Exception e) {

            System.err.println("SIP Server Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String getHeader(
        String message, String headerName) {

        String[] lines = message.split("\\r?\\n");

        for (String line : lines) {

            if (line.regionMatches(
                    true, 0, headerName + ":", 0,
                    headerName.length() + 1)) {

                return line;
            }
        }

        return "";
    }
}
