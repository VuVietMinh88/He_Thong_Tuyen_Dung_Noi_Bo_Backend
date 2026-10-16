package vn.ttcs.recruitment.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// A loopback-only SMTP fixture verifies actual MIME delivery without an external mailbox.
final class LocalSmtpServer implements AutoCloseable {

    private final ServerSocket server;
    private final ExecutorService listener = Executors.newSingleThreadExecutor();
    private final List<MimeMessage> messages = new CopyOnWriteArrayList<>();
    private volatile boolean reject;
    private volatile int acceptLimit = Integer.MAX_VALUE;
    private final AtomicInteger deliveryAttempts = new AtomicInteger();
    private volatile String rejectedRecipient;
    private volatile CountDownLatch receiptGate = new CountDownLatch(0);
    private volatile CountDownLatch dataReceived = new CountDownLatch(1);

    LocalSmtpServer() throws IOException {
        server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        listener.submit(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (Exception exception) {
                    if (!server.isClosed()) {
                        throw new IllegalStateException("Test SMTP server failed", exception);
                    }
                }
            }
        });
    }

    int port() { return server.getLocalPort(); }
    List<MimeMessage> messages() { return List.copyOf(messages); }
    void rejectDelivery(boolean value) { reject = value; }
    // Accepts this many messages, then answers every later one with a temporary failure, as a mail server that
    // stops working in the middle of a run.
    void rejectDeliveryAfter(int acceptedMessages) { acceptLimit = acceptedMessages; }
    // How many messages were handed over (DATA commands), accepted or not.
    int deliveryAttempts() { return deliveryAttempts.get(); }
    // Refuses this one address at RCPT TO, as a mail server does for a mailbox it does not accept; null accepts all.
    void rejectRecipient(String address) { rejectedRecipient = address; }
    void pauseReceipt() { receiptGate = new CountDownLatch(1); }
    void releaseReceipt() { receiptGate.countDown(); }
    boolean awaitData() throws InterruptedException { return dataReceived.await(5, TimeUnit.SECONDS); }

    void reset() {
        messages.clear();
        reject = false;
        acceptLimit = Integer.MAX_VALUE;
        deliveryAttempts.set(0);
        rejectedRecipient = null;
        receiptGate = new CountDownLatch(0);
        dataReceived = new CountDownLatch(1);
    }

    private void handle(Socket socket) throws Exception {
        socket.setSoTimeout(10000);
        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        reply(output, "220 localhost test SMTP");
        String command;
        while ((command = input.readLine()) != null) {
            if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                reply(output, "250 localhost");
            } else if (command.startsWith("RCPT TO:") && rejectedRecipient != null
                    && command.contains("<" + rejectedRecipient + ">")) {
                reply(output, "550 Mailbox unavailable");
            } else if (command.equals("DATA")) {
                deliveryAttempts.incrementAndGet();
                reply(output, "354 End with a dot");
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = input.readLine()) != null && !line.equals(".")) {
                    data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                }
                dataReceived.countDown();
                if (!receiptGate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release SMTP receipt");
                }
                if (reject || messages.size() >= acceptLimit) {
                    reply(output, "451 Temporary test failure");
                } else {
                    messages.add(new MimeMessage(Session.getInstance(new Properties()),
                            new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8))));
                    reply(output, "250 Accepted");
                }
            } else if (command.equals("QUIT")) {
                reply(output, "221 Bye");
                return;
            } else {
                reply(output, "250 OK");
            }
        }
    }

    private void reply(PrintWriter output, String response) {
        output.print(response + "\r\n");
        output.flush();
    }

    @Override
    public void close() throws IOException {
        releaseReceipt();
        server.close();
        listener.shutdownNow();
    }
}
