// Reading and writing zip files, for EPUB books and for library files. A file is read a piece
// at a time straight from disk, so a library of several gigabytes never has to fit in memory.

const utf8 = new TextDecoder();

async function bytes(blob, start, end) {
  return new DataView(await blob.slice(start, end).arrayBuffer());
}

/**
 * Reads a zip file's directory. Returns a Map of path -> {size, read(), blob(type)}: read gives
 * the file's bytes, blob gives it as a Blob without holding it in memory when it is stored
 * uncompressed.
 */
export async function readZip(file) {
  const size = file.size;
  const tailStart = Math.max(0, size - 66000);
  const tail = await bytes(file, tailStart, size);
  let end = -1;
  for (let i = tail.byteLength - 22; i >= 0; i--) {
    if (tail.getUint32(i, true) === 0x06054b50) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("That file isn't a readable zip");
  let count = tail.getUint16(end + 10, true);
  let dirSize = tail.getUint32(end + 12, true);
  let dirAt = tail.getUint32(end + 16, true);
  // Files over 4 GB, or with very many entries, keep the real figures in a second record.
  if (end >= 20 && tail.getUint32(end - 20, true) === 0x07064b50) {
    const at64 = Number(tail.getBigUint64(end - 20 + 8, true));
    const rec = await bytes(file, at64, at64 + 56);
    if (rec.getUint32(0, true) === 0x06064b50) {
      count = Number(rec.getBigUint64(32, true));
      dirSize = Number(rec.getBigUint64(40, true));
      dirAt = Number(rec.getBigUint64(48, true));
    }
  }
  const dir = await bytes(file, dirAt, dirAt + dirSize);
  const u8 = new Uint8Array(dir.buffer);
  const files = new Map();
  let at = 0;
  for (let n = 0; n < count && at + 46 <= dir.byteLength; n++) {
    if (dir.getUint32(at, true) !== 0x02014b50) break;
    const method = dir.getUint16(at + 10, true);
    let packed = dir.getUint32(at + 20, true), full = dir.getUint32(at + 24, true);
    const nameLen = dir.getUint16(at + 28, true), extraLen = dir.getUint16(at + 30, true), commentLen = dir.getUint16(at + 32, true);
    let local = dir.getUint32(at + 42, true);
    const name = utf8.decode(u8.subarray(at + 46, at + 46 + nameLen));
    // The 64-bit sizes, when a 32-bit field above is filled with ones.
    let x = at + 46 + nameLen;
    const extraEnd = x + extraLen;
    while (x + 4 <= extraEnd) {
      const id = dir.getUint16(x, true), len = dir.getUint16(x + 2, true);
      if (id === 1) {
        let p = x + 4;
        if (full === 0xffffffff) { full = Number(dir.getBigUint64(p, true)); p += 8; }
        if (packed === 0xffffffff) { packed = Number(dir.getBigUint64(p, true)); p += 8; }
        if (local === 0xffffffff) { local = Number(dir.getBigUint64(p, true)); }
      }
      x += 4 + len;
    }
    const where = async () => {
      const head = await bytes(file, local, local + 30);
      if (head.getUint32(0, true) !== 0x04034b50) throw new Error("That zip file is damaged");
      return local + 30 + head.getUint16(26, true) + head.getUint16(28, true);
    };
    const entry = {
      size: full,
      async blob(type = "application/octet-stream") {
        const start = await where();
        const raw = file.slice(start, start + packed);
        if (method === 0) return raw.slice(0, raw.size, type);
        if (method !== 8) throw new Error("That zip file uses a kind of compression this app can't read");
        const stream = raw.stream().pipeThrough(new DecompressionStream("deflate-raw"));
        return new Blob([await new Response(stream).arrayBuffer()], { type });
      },
      async read() {
        return new Uint8Array(await (await entry.blob()).arrayBuffer());
      },
    };
    if (!name.endsWith("/")) files.set(name, entry);
    at += 46 + nameLen + extraLen + commentLen;
  }
  return files;
}

let crcTable = null;

function crc32(data, crc = 0) {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c;
    }
  }
  let c = ~crc;
  for (let i = 0; i < data.length; i++) c = crcTable[(c ^ data[i]) & 255] ^ (c >>> 8);
  return ~c >>> 0;
}

/** The checksum of a Blob, read 8 MB at a time. */
async function blobCrc(blob) {
  let crc = 0;
  for (let at = 0; at < blob.size; at += 1 << 23) {
    crc = crc32(new Uint8Array(await blob.slice(at, at + (1 << 23)).arrayBuffer()), crc);
  }
  return crc;
}

/**
 * Builds a zip from [{name, blob}], stored without compression (books and pictures are already
 * compressed). Sizes and checksums go in front of each file, which is what lets the Android
 * version read the result as a stream. `progress(done, total)` is called after each file.
 */
export async function makeZip(entries, progress) {
  const enc = new TextEncoder();
  const parts = [], dir = [];
  let offset = 0;
  let done = 0;
  for (const { name, blob } of entries) {
    if (blob.size >= 0xffffffff) throw new Error("One book is larger than 4 GB, which a library file can't hold");
    const nameBytes = enc.encode(name);
    const crc = await blobCrc(blob);
    const head = new DataView(new ArrayBuffer(30));
    head.setUint32(0, 0x04034b50, true);
    head.setUint16(4, 20, true);
    head.setUint16(6, 0x0800, true);
    head.setUint16(8, 0, true);
    head.setUint16(12, 33, true);
    head.setUint32(14, crc, true);
    head.setUint32(18, blob.size, true);
    head.setUint32(22, blob.size, true);
    head.setUint16(26, nameBytes.length, true);
    parts.push(head.buffer, nameBytes, blob);
    const rec = new DataView(new ArrayBuffer(46));
    rec.setUint32(0, 0x02014b50, true);
    rec.setUint16(4, 20, true);
    rec.setUint16(6, 20, true);
    rec.setUint16(8, 0x0800, true);
    rec.setUint16(14, 33, true);
    rec.setUint32(16, crc, true);
    rec.setUint32(20, blob.size, true);
    rec.setUint32(24, blob.size, true);
    rec.setUint16(28, nameBytes.length, true);
    rec.setUint32(42, offset, true);
    dir.push(rec.buffer, nameBytes);
    offset += 30 + nameBytes.length + blob.size;
    if (offset >= 0xffffffff) throw new Error("These books come to more than 4 GB. Export fewer shelves at a time.");
    if (progress) progress(++done, entries.length);
  }
  const dirSize = dir.reduce((n, p) => n + p.byteLength, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, dirSize, true);
  end.setUint32(16, offset, true);
  return new Blob([...parts, ...dir, end.buffer], { type: "application/octet-stream" });
}
