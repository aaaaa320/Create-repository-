/**
 * Android başlatıcı simgelerini üretir (harici bağımlılık yok).
 *
 * Uzantı simgeleriyle aynı tasarım kullanılır: yeşil yuvarlatılmış kare içinde
 * beyaz "T". Çıktılar `android/app/src/main/res/mipmap-*` klasörlerine yazılır;
 * API 26+ cihazlar bunun yerine `mipmap-anydpi-v26` içindeki uyarlanabilir
 * (adaptive) vektör simgeleri kullanır, bu PNG'ler API 24-25 yedeğidir.
 *
 * Kullanım: node scripts/generate-android-icons.mjs
 */

import { mkdir, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import path from "node:path";

import { render, toPng, ROOT } from "./generate-icons.mjs";

const RES_DIR = path.join(ROOT, "android", "app", "src", "main", "res");

/** Android yoğunluk kovaları ve başlatıcı simgesi boyutları (px). */
export const DENSITIES = [
  { bucket: "mipmap-mdpi", size: 48 },
  { bucket: "mipmap-hdpi", size: 72 },
  { bucket: "mipmap-xhdpi", size: 96 },
  { bucket: "mipmap-xxhdpi", size: 144 },
  { bucket: "mipmap-xxxhdpi", size: 192 }
];

/**
 * Kare simgenin piksellerini dairesel maskeyle kırpar ("round" başlatıcı simgesi).
 * Kenar yumuşatma için kare üretimdeki gibi 2x2 süper örnekleme kullanılır.
 */
export function renderCircle(size) {
  const pixels = render(size);
  const radius = size / 2;
  const samples = 2;
  const step = 1 / samples;

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      let coverage = 0;
      for (let sy = 0; sy < samples; sy += 1) {
        for (let sx = 0; sx < samples; sx += 1) {
          const px = x + sx * step + step / 2 - radius;
          const py = y + sy * step + step / 2 - radius;
          if (px * px + py * py <= radius * radius) coverage += 1;
        }
      }
      coverage /= samples * samples;

      const offset = (y * size + x) * 4 + 3;
      pixels[offset] = Math.round(pixels[offset] * coverage);
    }
  }

  return pixels;
}

/** Tüm yoğunluklar için ic_launcher.png ve ic_launcher_round.png dosyalarını yazar. */
export async function generateAndroidIcons() {
  for (const { bucket, size } of DENSITIES) {
    const directory = path.join(RES_DIR, bucket);
    await mkdir(directory, { recursive: true });

    const targets = [
      ["ic_launcher.png", render(size)],
      ["ic_launcher_round.png", renderCircle(size)]
    ];

    for (const [name, pixels] of targets) {
      const png = toPng(size, pixels);
      await writeFile(path.join(directory, name), png);
      console.log(`android/app/src/main/res/${bucket}/${name} yazıldı (${png.length} bayt)`);
    }
  }
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (invokedDirectly) {
  await generateAndroidIcons();
}
