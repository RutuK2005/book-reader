import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Checks library files against the zip classes the Android app uses.
 *   java ZipCheck.java read  file.mbs                  reads it as a stream, the way Android imports
 *   java ZipCheck.java write out.mbs manifest.json book.pdf name-in-zip   writes it the way Android exports
 */
public class ZipCheck {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("read")) {
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(a[1]), 1 << 16))) {
                ZipEntry e;
                int n = 0;
                long total = 0;
                while ((e = zip.getNextEntry()) != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = zip.read(buf)) > 0) out.write(buf, 0, r);
                    if (n == 0 && !e.getName().equals("manifest.json")) throw new Exception("manifest is not first: " + e.getName());
                    if (n == 0 && !new String(out.toByteArray(), StandardCharsets.UTF_8).contains("\"mybookspace-library\"")) throw new Exception("bad manifest");
                    System.out.println(e.getName() + "  " + out.size() + " bytes");
                    total += out.size();
                    n++;
                }
                System.out.println("OK: " + n + " entries, " + total + " bytes, read as a stream");
            }
        } else {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(a[1]), 1 << 16))) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(Files.readAllBytes(Paths.get(a[2])));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(a[4]));
                try (InputStream in = new FileInputStream(a[3])) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
            System.out.println("written " + a[1]);
        }
    }
}
