package dev.tommy.foldshell;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Stable ADB identity, encrypted with a non-exportable Android Keystore AES key. */
public final class LocalAdb extends AbsAdbConnectionManager {
    private final PrivateKey key;
    private final Certificate certificate;
    public LocalAdb(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT); setTimeout(8, TimeUnit.SECONDS); setThrowOnUnauthorised(true);
        setHostAddress("127.0.0.1");
        String alias = "fold-adb-identity-v1";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        Key wrapping = store.getKey(alias, null);
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "adb-identity.bin"));
        if (file.getBaseFile().exists()) {
            try (DataInputStream input = new DataInputStream(file.openRead())) {
                if (input.readInt() != 1) throw new IOException("Unsupported identity format");
                byte[] iv = new byte[12]; input.readFully(iv);
                ByteArrayOutputStream encryptedBytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (encryptedBytes.size() + count > 16384) throw new IOException("Identity is too large");
                    encryptedBytes.write(buffer, 0, count);
                }
                byte[] encrypted = encryptedBytes.toByteArray();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, wrapping, new GCMParameterSpec(128, iv));
                byte[] plain = cipher.doFinal(encrypted);
                try (DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(plain))) {
                    int length = decoded.readInt();
                    if (length < 1 || length > 8192) throw new IOException("Invalid identity");
                    byte[] privateBytes = new byte[length]; decoded.readFully(privateBytes);
                    try { key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateBytes)); }
                    finally { Arrays.fill(privateBytes, (byte) 0); }
                    certificate = CertificateFactory.getInstance("X.509").generateCertificate(decoded);
                } finally { Arrays.fill(plain, (byte) 0); }
            }
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            java.security.KeyPair pair = generator.generateKeyPair(); key = pair.getPrivate();
            X500Name name = new X500Name("CN=Fold Transition");
            long now = System.currentTimeMillis();
            certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                    name, new BigInteger(128, new SecureRandom()), new Date(now - 86400000L),
                    new Date(now + 10L * 365 * 86400000), name, pair.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(key)));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                byte[] encoded = key.getEncoded();
                data.writeInt(encoded.length); data.write(encoded); data.write(certificate.getEncoded());
                Arrays.fill(encoded, (byte) 0);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, wrapping);
            byte[] plain = bytes.toByteArray();
            FileOutputStream output = file.startWrite();
            try {
                DataOutputStream data = new DataOutputStream(output);
                data.writeInt(1); data.write(cipher.getIV()); data.write(cipher.doFinal(plain)); data.flush();
                file.finishWrite(output);
            } catch (Exception error) { file.failWrite(output); throw error; }
            finally { Arrays.fill(plain, (byte) 0); }
        }
    }
    @Override protected PrivateKey getPrivateKey() { return key; }
    @Override protected Certificate getCertificate() { return certificate; }
    @Override protected String getDeviceName() { return "FoldTransition"; }
}
