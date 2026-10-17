package org.RSA;

import org.bouncycastle.asn1.x500.X500Name;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.Iterator;
import java.util.concurrent.*;

public class Server {
    private final Selector selector;
    private final ServerSocketChannel server;
    private final ExecutorService workers;

    private final PrivateKey issuerKey;
    private final X500Name issuerName;

    private final ConcurrentHashMap<String, CompletableFuture<CryptoUtils.Result>> cache = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<Ready> readyQueue = new ConcurrentLinkedQueue<>();

    private static class ClientState {
        final ByteBuffer input = ByteBuffer.allocate(1024);
        final ByteArrayOutputStream name = new ByteArrayOutputStream();
        ByteBuffer output;
    }

    private record Ready(SelectionKey key, CryptoUtils.Result result, Throwable error) {}

    public Server(int port, int workerCount, String issuer, Path issuerKeyFile) throws Exception {
        issuerKey = CryptoUtils.loadPrivateKey(issuerKeyFile);
        issuerName = new X500Name(issuer);
        workers = Executors.newFixedThreadPool(workerCount);
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress("0.0.0.0", port));
        server.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void run() throws IOException {
        System.out.println("Server: " + server.getLocalAddress());

        while (true) {
            selector.select();
            processReadyResults();
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();

            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (!key.isValid()) {
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        accept();
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                } catch (IOException e) {
                    close(key);
                }
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel client;

        while ((client = server.accept()) != null) {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ, new ClientState());
            System.out.println("Connected: " + client.getRemoteAddress());
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();

        int n = client.read(state.input);

        if (n == -1) {
            close(key);
            return;
        }

        if (n == 0) {
            return;
        }

        state.input.flip();

        while (state.input.hasRemaining()) {
            byte b = state.input.get();

            if (b == 0) {
                String name = state.name.toString(StandardCharsets.US_ASCII);

                if (name.isEmpty()) {
                    close(key);
                    return;
                }

                key.interestOps(0);

                request(name, key);

                state.input.clear();
                return;
            }

            if ((b & 0x80) != 0 || state.name.size() >= 1000) {
                close(key);
                return;
            }

            state.name.write(b);
        }

        state.input.clear();
    }

    private void request(String name, SelectionKey key) {
        CompletableFuture<CryptoUtils.Result> future = getOrStartGeneration(name);

        future.whenComplete((result, error) -> {
            readyQueue.add(new Ready(key, result, error));
            selector.wakeup();
        });
    }

    private CompletableFuture<CryptoUtils.Result> getOrStartGeneration(String name) {

        while (true) {
            CompletableFuture<CryptoUtils.Result> existing = cache.get(name);

            if (existing != null) {
                return existing;
            }

            CompletableFuture<CryptoUtils.Result> created = new CompletableFuture<>();
            if (cache.putIfAbsent(name, created) == null) {
                startGeneration(name, created);
                return created;
            }
        }
    }

    private void startGeneration(String name, CompletableFuture<CryptoUtils.Result> future) {
        workers.submit(() -> {
            try {
                System.out.println("Generating: " + name);
                CryptoUtils.Result result = CryptoUtils.generate(name, issuerName, issuerKey);
                future.complete(result);
                System.out.println("Ready: " + name);

            } catch (Throwable e) {
                cache.remove(name, future);
                future.completeExceptionally(e);
            }
        });
    }

    private void processReadyResults() {
        Ready ready;

        while ((ready = readyQueue.poll()) != null) {
            SelectionKey key = ready.key();

            if (!key.isValid()) {
                continue;
            }

            if (ready.error() != null) {
                close(key);
                continue;
            }

            ClientState state = (ClientState) key.attachment();
            state.output = makeResponse(ready.result());
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private static ByteBuffer makeResponse(CryptoUtils.Result result) {
        byte[] privateKey = result.privateKey();
        byte[] publicKey = result.publicKey();
        byte[] certificate = result.certificate();

        ByteBuffer out = ByteBuffer.allocate(
                12
                        + privateKey.length
                        + publicKey.length
                        + certificate.length
        );

        out.putInt(privateKey.length);
        out.put(privateKey);

        out.putInt(publicKey.length);
        out.put(publicKey);

        out.putInt(certificate.length);
        out.put(certificate);

        out.flip();

        return out;
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();
        client.write(state.output);

        if (!state.output.hasRemaining()) {
            close(key);
        }
    }

    private static void close(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {}

        key.cancel();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println(
                    "Usage: Server <port> <workers> " + "<issuer-dn> <issuer-key.pem>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        int workers = Integer.parseInt(args[1]);

        new Server(port, workers, args[2], Path.of(args[3])).run();
    }
}