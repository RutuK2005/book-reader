package app.folio.reader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds the start of a library file. Kept free of Android classes so it can be checked on a PC
 * (see pc/test/TransferCheck.java).
 */
final class TransferFile {
    static final String MANIFEST = "manifest.json";

    /** The file has nothing in it: it was sent or picked before the export had finished. */
    static final class Empty extends IOException {
        Empty() {
            super("empty");
        }
    }

    /** The file is something else altogether. */
    static final class NotLibrary extends IOException {
        NotLibrary() {
            super("not a library");
        }
    }

    private TransferFile() {
    }

    /**
     * Returns the zip with its manifest as the entry about to be read. Some ways of sending a
     * file wrap it in a zip of their own; a library file found inside one is opened as well.
     */
    static ZipInputStream open(InputStream in) throws IOException {
        PushbackInputStream head = new PushbackInputStream(new BufferedInputStream(in, 1 << 16), 4);
        byte[] sig = new byte[4];
        int got = 0, n;
        while (got < 4 && (n = head.read(sig, got, 4 - got)) > 0) got += n;
        if (got == 0) throw new Empty();
        head.unread(sig, 0, got);
        if (got < 4 || sig[0] != 'P' || sig[1] != 'K' || sig[2] != 3 || sig[3] != 4) throw new NotLibrary();
        ZipInputStream zip = new ZipInputStream(head);
        ZipEntry e = zip.getNextEntry();
        if (e != null && MANIFEST.equals(e.getName())) return zip;
        for (; e != null; e = zip.getNextEntry()) {
            if (e.isDirectory() || !e.getName().toLowerCase(Locale.US).endsWith(".mbs")) continue;
            ZipInputStream inner = new ZipInputStream(zip);
            ZipEntry first = inner.getNextEntry();
            if (first != null && MANIFEST.equals(first.getName())) return inner;
            break;
        }
        throw new NotLibrary();
    }
}
