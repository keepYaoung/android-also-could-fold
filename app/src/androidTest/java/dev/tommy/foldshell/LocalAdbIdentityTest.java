package dev.tommy.foldshell;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Does not pair, access the network, or change the user's saved ADB identity. */
@RunWith(AndroidJUnit4.class)
public final class LocalAdbIdentityTest {
    @Test public void identitySurvivesReloadAndRejectsTampering() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = Files.createTempDirectory(target.getCacheDir().toPath(), "identity-test").toFile();
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        File stored = new File(directory, "adb-identity.bin");
        try {
            LocalAdb first = new LocalAdb(isolated);
            LocalAdb reloaded = new LocalAdb(isolated);
            assertArrayEquals(first.getCertificate().getEncoded(), reloaded.getCertificate().getEncoded());
            assertArrayEquals(first.getPrivateKey().getEncoded(), reloaded.getPrivateKey().getEncoded());
            byte[] disk = Files.readAllBytes(stored.toPath());
            byte[] secret = first.getPrivateKey().getEncoded();
            boolean plaintext = false;
            for (int i = 0; i <= disk.length - secret.length; i++) {
                boolean match = true;
                for (int j = 0; j < secret.length; j++) if (disk[i + j] != secret[j]) { match = false; break; }
                if (match) { plaintext = true; break; }
            }
            assertFalse("Private key must not be stored as plaintext", plaintext);
            try (RandomAccessFile file = new RandomAccessFile(stored, "rw")) {
                file.seek(file.length() - 1); int last = file.read();
                file.seek(file.length() - 1); file.write(last ^ 1);
            }
            try { new LocalAdb(isolated); fail("Tampered identity must fail, not silently rotate"); }
            catch (javax.crypto.AEADBadTagException expected) { }
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }
}
