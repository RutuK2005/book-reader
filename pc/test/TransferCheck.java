package app.folio.reader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Checks how the Android app finds the start of a library file. Compile it together with
 * src/app/folio/reader/TransferFile.java and run app.folio.reader.TransferCheck.
 */
public class TransferCheck {
    private static byte[] zip(Object... namesAndBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.setLevel(Deflater.NO_COMPRESSION);
            for (int i = 0; i < namesAndBytes.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) namesAndBytes[i]));
                z.write((byte[]) namesAndBytes[i + 1]);
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** What the file turned out to be: the names of what follows the manifest, or the reason it was refused. */
    private static String open(byte[] file) throws Exception {
        try (ZipInputStream z = TransferFile.open(new ByteArrayInputStream(file))) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = z.read(buf)) > 0) text.write(buf, 0, n);
            StringBuilder s = new StringBuilder(new String(text.toByteArray(), "UTF-8"));
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                int size = 0;
                while ((n = z.read(buf)) > 0) size += n;
                s.append(" ").append(e.getName()).append(":").append(size);
            }
            return s.toString();
        } catch (TransferFile.Empty e) {
            return "EMPTY";
        } catch (TransferFile.NotLibrary e) {
            return "NOT";
        } catch (java.io.EOFException | java.util.zip.ZipException e) {
            return "CUT";
        }
    }

    private static int bad;

    private static void expect(String what, String got, String want) {
        boolean ok = got.equals(want);
        if (!ok) bad++;
        System.out.println((ok ? "ok    " : "WRONG ") + what + (ok ? "" : "  got " + got + ", wanted " + want));
    }

    public static void main(String[] a) throws Exception {
        byte[] manifest = "{\"format\":\"mybookspace-library\"}".getBytes("UTF-8");
        byte[] book = new byte[300000];
        new java.util.Random(7).nextBytes(book);
        byte[] lib = zip("manifest.json", manifest, "books/a.pdf", book, "covers/a.jpg", new byte[5000]);
        String whole = "{\"format\":\"mybookspace-library\"} books/a.pdf:300000 covers/a.jpg:5000";

        expect("a library file", open(lib), whole);
        expect("an empty file", open(new byte[0]), "EMPTY");
        expect("a picture", open(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3}), "NOT");
        expect("two bytes", open(new byte[]{'P', 'K'}), "NOT");
        expect("another zip", open(zip("notes.txt", new byte[10])), "NOT");
        expect("an epub", open(zip("mimetype", "application/epub+zip".getBytes(), "manifest.json", manifest)), "NOT");
        expect("wrapped in a zip", open(zip("MyBookSpace-library-2026-11-26.mbs", lib)), whole);
        expect("wrapped, in a folder, after other files",
                open(zip("readme.txt", new byte[3], "Shared/", new byte[0], "Shared/Library.MBS", lib)), whole);
        expect("a zip holding a false .mbs", open(zip("x.mbs", new byte[]{1, 2, 3, 4, 5})), "NOT");
        expect("cut short", open(java.util.Arrays.copyOf(lib, lib.length / 2)), "CUT");
        expect("wrapped and cut short", open(java.util.Arrays.copyOf(zip("x.mbs", lib), lib.length / 2)), "CUT");

        System.out.println(bad == 0 ? "ALL OK" : bad + " WRONG");
        if (bad != 0) System.exit(1);
    }
}
