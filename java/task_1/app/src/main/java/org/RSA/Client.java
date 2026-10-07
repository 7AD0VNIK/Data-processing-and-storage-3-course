package org.RSA;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class Client {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: Client <name> <host> <port> " + "[--delay seconds] [--abort]");
            return;
        }

        String name = args[0];
        String host = args[1];
        int port = Integer.parseInt(args[2]);

        validateName(name);

        int delay = 0;
        boolean abort = false;

        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--delay")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "--delay requires number of seconds"
                    );
                }

                delay = Integer.parseInt(args[++i]);

            } else if (args[i].equals("--abort")) {
                abort = true;
            }
        }

        try (Socket socket = new Socket(host, port)) {
            OutputStream out = socket.getOutputStream();

            /*
             * ASCII name + нулевой байт.
             */
            out.write(name.getBytes(StandardCharsets.US_ASCII));

            out.write(0);
            out.flush();

            if (abort) {
                System.out.println("Request sent, connection closed.");
                return;
            }

            if (delay > 0) {
                Thread.sleep(delay * 1000L);
            }

            DataInputStream in = new DataInputStream(socket.getInputStream());

            byte[] privateKey = readBlock(in);
            byte[] publicKey = readBlock(in);
            byte[] certificate = readBlock(in);

            writePem(Path.of(name + ".key"), "PRIVATE KEY", privateKey);

            /*
             * Сертификат уже содержит public key.
             */
            writePem(Path.of(name + ".crt"), "CERTIFICATE", certificate);

            System.out.println("Saved: " + name + ".key, " + name + ".crt");
            System.out.println("Public key received: " + publicKey.length + " bytes");
        }
    }

    private static void validateName(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name cannot be empty");
        }

        for (char c : name.toCharArray()) {
            if (c > 0x7F || c == 0) {
                throw new IllegalArgumentException("Name must contain ASCII characters only");
            }
        }
    }

    private static byte[] readBlock(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1_000_000) {
            throw new IOException("Invalid block length: " + length);
        }

        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static void writePem(Path path, String type, byte[] data) throws IOException {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(data);
        String pem =
                "-----BEGIN " + type + "-----\n"
                        + body
                        + "\n-----END "
                        + type
                        + "-----\n";

        Files.writeString(path, pem, StandardCharsets.US_ASCII);
    }
}