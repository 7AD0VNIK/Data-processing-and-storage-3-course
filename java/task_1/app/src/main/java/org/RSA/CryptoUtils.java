package org.RSA;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

public class CryptoUtils {
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Result(
            byte[] privateKey,
            byte[] publicKey,
            byte[] certificate
    ) {}

    public static Result generate(String name, X500Name issuer, PrivateKey issuerKey) throws Exception {

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(8192, RANDOM);
        KeyPair pair = generator.generateKeyPair();

        Instant now = Instant.now();

        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, name).build();
        BigInteger serial = new BigInteger(128, RANDOM).add(BigInteger.ONE);

        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        issuer,
                        serial,
                        Date.from(now),
                        Date.from(now.plus(365, ChronoUnit.DAYS)),
                        subject,
                        pair.getPublic()
                );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
        X509CertificateHolder certificate = builder.build(signer);

        return new Result(
                pair.getPrivate().getEncoded(),
                pair.getPublic().getEncoded(),
                certificate.getEncoded()
        );
    }

    public static PrivateKey loadPrivateKey(Path path) throws Exception {

        String pem = Files.readString(path, StandardCharsets.US_ASCII);

        String base64 = pem
                .replace(
                        "-----BEGIN PRIVATE KEY-----",
                        ""
                )
                .replace(
                        "-----END PRIVATE KEY-----",
                        ""
                )
                .replaceAll("\\s", "");

        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);

        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}