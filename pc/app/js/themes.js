// Reading themes and home page colour schemes: the same values as the Android app.

export const THEME_NAMES = ["White", "Paper", "Sepia", "Green", "Gray", "Night"];
export const THEME_BG = [0xffffff, 0xfaf5e9, 0xf3e7cc, 0xcfe8cf, 0x2c2c2e, 0x000000];
export const THEME_FG = [0x000000, 0x1d1b18, 0x50392a, 0x1d2e1f, 0xd0d0d0, 0x9a9a9a];

export const isDark = (t) => t >= 4;

const warmG = (w) => 1 - 0.1 * w;
const warmB = (w) => 1 - 0.42 * w;

export function tint(color, warmth) {
  const r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
  return (r << 16) | (Math.round(g * warmG(warmth)) << 8) | Math.round(b * warmB(warmth));
}

/**
 * A page is drawn once as black on white; the theme is a per-channel linear map applied when it
 * is shown (white becomes the paper colour, black the ink colour), so changing theme never
 * redraws a page. Returns the values for an SVG feColorMatrix.
 */
export function matrix(theme, warmth) {
  const bg = THEME_BG[theme], fg = THEME_FG[theme];
  const row = (shift, w) => {
    const lo = (fg >> shift) & 255, hi = (bg >> shift) & 255;
    return [((hi - lo) / 255) * w, (lo / 255) * w];
  };
  const [rs, ro] = row(16, 1), [gs, go] = row(8, warmG(warmth)), [bs, bo] = row(0, warmB(warmth));
  return `${rs} 0 0 0 ${ro}  0 ${gs} 0 0 ${go}  0 0 ${bs} 0 ${bo}  0 0 0 1 0`;
}

/** Home page colour schemes: page, card, text, accent, and 1 if it is a dark scheme. */
export const SCHEME_NAMES = ["Match reading theme",
  "Linen", "Snow", "Sage", "Blush", "Sky", "Lavender", "Sand", "Mint", "Peach", "Rose gold", "Lemon", "Stone",
  "Coral", "Teal", "Lilac", "Clay",
  "Midnight", "Charcoal", "Forest", "Plum", "Espresso", "Ocean", "Black", "Wine", "Slate", "Ember", "Aurora", "Graphite"];
export const SCHEMES = [null,
  [0xefeae0, 0xfffdf8, 0x1d1b18, 0xb5651d, 0], [0xf2f4f7, 0xffffff, 0x15171a, 0x2f6fed, 0],
  [0xe4ece1, 0xf8fbf6, 0x1f2a20, 0x3f7d4e, 0], [0xf6e7e5, 0xfff8f6, 0x2b1d1c, 0xc0504d, 0],
  [0xe2edf6, 0xf7fbff, 0x16232e, 0x2b7bb9, 0], [0xebe6f5, 0xfaf8ff, 0x221d30, 0x6b4fb3, 0],
  [0xede0ca, 0xfbf4e6, 0x33271a, 0xa0621e, 0], [0xddf0ea, 0xf5fcfa, 0x12302a, 0x1e8c73, 0],
  [0xfae5d3, 0xfff7ef, 0x3a2416, 0xd9742b, 0], [0xf2e0dc, 0xfff6f3, 0x3b2422, 0xb76e79, 0],
  [0xf5efcb, 0xfffceb, 0x2e2a12, 0x9a7b00, 0], [0xe1e1de, 0xf6f6f4, 0x1e1e1c, 0x555555, 0],
  [0xfbe3dc, 0xfff6f3, 0x3a1d17, 0xe2573e, 0], [0xdceeef, 0xf4fbfb, 0x10292b, 0x0f7c86, 0],
  [0xf0e4f4, 0xfcf7fe, 0x2c1b33, 0xa153c2, 0], [0xebdcd2, 0xfaf2ec, 0x35221a, 0x9c4a2f, 0],
  [0x0f1420, 0x1a2233, 0xe6eaf2, 0x5b8def, 1], [0x17171a, 0x242428, 0xededed, 0xd9902f, 1],
  [0x0f1a14, 0x1a2a21, 0xe2efe6, 0x3fa36b, 1], [0x1a1220, 0x2a1d33, 0xefe6f5, 0xa868e8, 1],
  [0x1c1512, 0x2b211c, 0xf1e7df, 0xc97f45, 1], [0x0b1a22, 0x143040, 0xe0f0f6, 0x1e9fd6, 1],
  [0x000000, 0x161616, 0xe8e8e8, 0xd9902f, 1], [0x1f0f14, 0x331a22, 0xf5e4e8, 0xd4486a, 1],
  [0x1b2026, 0x282f38, 0xe5e9ee, 0x6e9ccb, 1], [0x1a1310, 0x2a1e18, 0xf6e9e0, 0xe8622c, 1],
  [0x0e1a1b, 0x16292b, 0xe0f2f1, 0x2bb8a8, 1], [0x202124, 0x2d2e32, 0xe8eaed, 0x9aa0a6, 1]];

export const DEFAULT_ACCENT = 0xb5651d;
export const MARK_COLORS = ["rgba(255,212,0,0.40)", "rgba(69,201,106,0.40)", "rgba(74,158,255,0.40)", "rgba(255,111,168,0.40)"];
