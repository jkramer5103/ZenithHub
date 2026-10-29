// Minimal ANSI SGR → HTML for console lines. Zenith renders Minecraft chat colors as 24-bit codes.

const BASIC = [
	'#3f3f46', '#f87171', '#4ade80', '#facc15', '#60a5fa', '#c084fc', '#22d3ee', '#e4e4e7',
	'#71717a', '#fca5a5', '#86efac', '#fde047', '#93c5fd', '#d8b4fe', '#67e8f9', '#ffffff'
];

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export function escapeHtml(s: string): string {
	return s.replace(/[&<>"']/g, (c) => ESCAPES[c]);
}

function color256(n: number): string {
	if (n < 16) return BASIC[n];
	if (n >= 232) {
		const v = 8 + (n - 232) * 10;
		return `rgb(${v},${v},${v})`;
	}
	n -= 16;
	const level = (x: number) => (x === 0 ? 0 : 55 + x * 40);
	return `rgb(${level(Math.floor(n / 36))},${level(Math.floor(n / 6) % 6)},${level(n % 6)})`;
}

interface Style {
	fg?: string;
	bg?: string;
	bold?: boolean;
	italic?: boolean;
	underline?: boolean;
	strike?: boolean;
	dim?: boolean;
}

function css(style: Style): string {
	const parts: string[] = [];
	if (style.fg) parts.push(`color:${style.fg}`);
	if (style.bg) parts.push(`background:${style.bg}`);
	if (style.bold) parts.push('font-weight:600');
	if (style.italic) parts.push('font-style:italic');
	if (style.dim) parts.push('opacity:.7');
	const deco = [style.underline && 'underline', style.strike && 'line-through'].filter(Boolean);
	if (deco.length) parts.push(`text-decoration:${deco.join(' ')}`);
	return parts.join(';');
}

function apply(style: Style, codes: number[]): Style {
	const s = { ...style };
	for (let i = 0; i < codes.length; i++) {
		const c = codes[i];
		if (c === 0) Object.keys(s).forEach((k) => delete s[k as keyof Style]);
		else if (c === 1) s.bold = true;
		else if (c === 2) s.dim = true;
		else if (c === 3) s.italic = true;
		else if (c === 4) s.underline = true;
		else if (c === 9) s.strike = true;
		else if (c === 22) s.bold = s.dim = false;
		else if (c === 23) s.italic = false;
		else if (c === 24) s.underline = false;
		else if (c === 29) s.strike = false;
		else if (c >= 30 && c <= 37) s.fg = BASIC[c - 30];
		else if (c >= 90 && c <= 97) s.fg = BASIC[c - 90 + 8];
		else if (c >= 40 && c <= 47) s.bg = BASIC[c - 40];
		else if (c >= 100 && c <= 107) s.bg = BASIC[c - 100 + 8];
		else if (c === 39) delete s.fg;
		else if (c === 49) delete s.bg;
		else if (c === 38 || c === 48) {
			const key = c === 38 ? 'fg' : 'bg';
			if (codes[i + 1] === 5) {
				s[key] = color256(codes[i + 2] ?? 0);
				i += 2;
			} else if (codes[i + 1] === 2) {
				s[key] = `rgb(${codes[i + 2] ?? 0},${codes[i + 3] ?? 0},${codes[i + 4] ?? 0})`;
				i += 4;
			}
		}
	}
	return s;
}

/** Converts ANSI-colored text to HTML. All text is escaped. */
export function ansiToHtml(input: string): string {
	let out = '';
	let style: Style = {};
	let last = 0;
	const re = /\x1b\[([\d;]*)([A-Za-z])/g;
	let m: RegExpExecArray | null;
	const flush = (text: string) => {
		if (!text) return;
		const c = css(style);
		out += c ? `<span style="${c}">${escapeHtml(text)}</span>` : escapeHtml(text);
	};
	while ((m = re.exec(input))) {
		flush(input.slice(last, m.index));
		last = re.lastIndex;
		if (m[2] === 'm') style = apply(style, m[1] === '' ? [0] : m[1].split(';').map((n) => parseInt(n, 10) || 0));
	}
	flush(input.slice(last));
	return out;
}
