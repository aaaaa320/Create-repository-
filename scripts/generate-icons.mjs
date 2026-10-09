/**
 * Uzantı simgelerini üretir (harici bağımlılık yok).
 *
 * Tasarım: yeşil renkte yuvarlatılmış kare içinde beyaz "T" işareti.
 * Kenar yumuşatma için 2x2 süper örnekleme kullanılır.
 *
 * Kullanım: node scripts/generate-icons.mjs
 */

import { deflateSync } from "node:zlib";
import { writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SIZES = [16, 32, 48, 128];

const GRADIENT_TOP = [22, 168, 117];
const GRADIENT_BOTTOM = [8, 123, 92];

function mix(top, bottom, t) {
  return top.map((value, index) => Math.round(value + (bottom[index] - value) * t));
}

function insideRoundedRect(x, y, size, radius) {
  if (x < 0 || y < 0 || x >= size || y >= size) return false;
  const limit = size - radius;
  const dx = x < radius ? radius - x : x >= limit ? x - limit + 1 : 0;
  const dy = y < radius ? radius - y : y >= limit ? y - limit + 1 : 0;
  if (dx === 0 || dy === 0) return true;
  return dx * dx + dy * dy <= radius * radius;
}

/** "T" harfinin maskesi: üstte yatay çubuk, altta dik çubuk. */
function insideGlyph(x, y, size) {
  const unit = size / 16;
  const barTop = 4 * unit;
  const barHeight = 2.6 * unit;
  const barLeft = 3.4 * unit;
  const barRight = size - 3.4 * unit;
  const stemWidth = 2.6 * unit;
  const stemLeft = (size - stemWidth) / 2;
  const stemRight = stemLeft + stemWidth;
  const stemBottom = size - 3.4 * unit;

  const inBar = y >= barTop && y <= barTop + barHeight && x >= barLeft && x <= barRight;
  const inStem = y >= barTop && y <= stemBottom && x >= stemLeft && x <= stemRight;
  return inBar || inStem;
}

function render(size) {
  const pixels = Buffer.alloc(size * size * 4);
  const radius = size * 0.24;
  const samples = 2;
  const step = 1 / samples;

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      let coverage = 0;
      let glyph = 0;

      for (let sy = 0; sy < samples; sy += 1) {
        for (let sx = 0; sx < samples; sx += 1) {
          const px = x + sx * step;
          const py = y + sy * step;
          if (insideRoundedRect(px, py, size, radius)) coverage += 1;
          if (insideGlyph(px, py, size)) glyph += 1;
        }
      }

      coverage /= samples * samples;
      glyph /= samples * samples;
      const t = (y + 0.5) / size;
      const base = mix(GRADIENT_TOP, GRADIENT_BOTTOM, t);
      const color = mix(base, [255, 255, 255], glyph);

      const offset = (y * size + x) * 4;
      pixels[offset] = color[0];
      pixels[offset + 1] = color[1];
      pixels[offset + 2] = color[2];
      // Beyaz alanların köşelerde saydamlaşmaması için kapsamayı alfaya uygula.
      pixels[offset + 3] = Math.round(255 * coverage);
    }
  }

  return pixels;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body) >>> 0, 0);
  return Buffer.concat([length, body, crc]);
}

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let crc = -1;
  for (const byte of buffer) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ byte) & 0xff];
  }
  return crc ^ -1;
}

function toPng(size, pixels) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit derinliği
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  // Her satırın başına filtre baytı (0 = None).
  const stride = size * 4;
  const raw = Buffer.alloc((stride + 1) * size);
  for (let y = 0; y < size; y += 1) {
    raw[y * (stride + 1)] = 0;
    pixels.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0))
  ]);
}

for (const size of SIZES) {
  const png = toPng(size, render(size));
  const file = path.join(ROOT, "icons", `icon${size}.png`);
  await writeFile(file, png);
  console.log(`icons/icon${size}.png yazıldı (${png.length} bayt)`);
}
