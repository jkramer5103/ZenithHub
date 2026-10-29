/**
 * Settings search: every word of the query has to match a word somewhere in a setting
 * (its label, help, section, group, options or config path), in any order.
 * Words match from their start ("vis" finds "visual"), tolerate small typos, and also
 * match common synonyms, so "ping" finds mentions and "notification" finds alerts.
 */

const SYNONYMS: string[][] = [
    ["notification", "notify", "alert", "warn", "report"],
    ["ping", "mention"],
    ["food", "eat", "hunger"],
    ["health", "hp", "heal"],
    ["reconnect", "reconnecting"],
    ["disconnect", "logout", "leave", "leaves", "left", "kick"],
    ["connect", "join", "login"],
    ["player", "someone"],
    ["spectate", "spectator"],
    ["visual", "render", "nearby"],
    ["coords", "coordinate", "coordinates", "position", "location"],
    ["queue", "queueing", "prio", "priority"],
    ["chat", "whisper", "whispers"],
    ["proxy", "socks"],
    ["afk", "idle"],
    ["whitelist", "access"],
    ["blacklist", "block", "blocked", "ban"],
];

export interface Query {
    words: { word: string; synonyms: string[] }[];
}

interface Token {
    text: string;
    start: number;
}

/** lowercase words with their position in the original text; camelCase is split ("visualRange" → visual, range) */
function tokenize(text: string): Token[] {
    const tokens: Token[] = [];
    for (const m of text.matchAll(/[A-Za-z0-9]+/g)) {
        const raw = m[0];
        let from = 0;
        for (let i = 1; i <= raw.length; i++) {
            if (i === raw.length || (/[a-z0-9]/.test(raw[i - 1]) && /[A-Z]/.test(raw[i]))) {
                tokens.push({ text: raw.slice(from, i).toLowerCase(), start: m.index + from });
                from = i;
            }
        }
    }
    return tokens;
}

export function parseQuery(raw: string): Query {
    const words = tokenize(raw.replace(/[’']/g, "")).map(({ text: word }) => {
        const synonyms = new Set<string>();
        for (const group of SYNONYMS) {
            // "notif" counts as "notification", "pings" as "ping"
            if (group.some((s) => s.startsWith(word) || (word.length > 3 && word.startsWith(s))))
                group.forEach((s) => synonyms.add(s));
        }
        synonyms.delete(word);
        return { word, synonyms: [...synonyms] };
    });
    return { words };
}

/** edit distance, giving up once it exceeds max */
function distance(a: string, b: string, max: number) {
    if (Math.abs(a.length - b.length) > max) return max + 1;
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
        const cur = [i];
        let best = i;
        for (let j = 1; j <= b.length; j++) {
            cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
            best = Math.min(best, cur[j]);
        }
        if (best > max) return max + 1;
        prev = cur;
    }
    return prev[b.length];
}

/** how well one query word matches one token (0 = not at all), and how many of its characters to highlight */
function matchToken(word: { word: string; synonyms: string[] }, token: string): { quality: number; length: number } {
    const { word: w } = word;
    if (token.startsWith(w)) return { quality: token === w ? 1.3 : 1, length: w.length };
    for (const s of word.synonyms) {
        if (token.startsWith(s)) return { quality: 0.4, length: s.length };
    }
    if (w.length >= 5 && token.includes(w)) return { quality: 0.5, length: 0 };
    if (w.length >= 4) {
        const max = w.length >= 8 ? 2 : 1;
        // a typo in the word, or in the part of a longer word typed so far
        if (distance(w, token, max) <= max) return { quality: 0.45, length: token.length };
        if (token.length > w.length && distance(w, token.slice(0, w.length), max) <= max)
            return { quality: 0.4, length: w.length };
    }
    return { quality: 0, length: 0 };
}

export interface Haystack {
    text: string;
    weight: number;
}

/** 0 when some word matches nothing; otherwise higher for better matches */
export function score(query: Query, haystack: Haystack[]) {
    if (!query.words.length) return 1;
    const parts = haystack.map((h) => ({ tokens: tokenize(h.text).map((t) => t.text), weight: h.weight }));
    let total = 0;
    for (const word of query.words) {
        let best = 0;
        for (const part of parts) {
            for (const token of part.tokens) best = Math.max(best, part.weight * matchToken(word, token).quality);
        }
        if (!best) return 0;
        total += best;
    }
    return total;
}

/** how many of the query's words match somewhere in the haystack */
export function matchedWords(query: Query, haystack: Haystack[]) {
    const tokens = haystack.flatMap((h) => tokenize(h.text).map((t) => t.text));
    return query.words.filter((word) => tokens.some((token) => matchToken(word, token).quality > 0)).length;
}

/** Splits text into plain and matching pieces, for highlighting the query words. */
export function highlight(text: string, query: Query | null): { text: string; hit: boolean }[] {
    if (!query || !query.words.length || !text) return [{ text, hit: false }];
    const hits = new Array<boolean>(text.length).fill(false);
    for (const token of tokenize(text)) {
        for (const word of query.words) {
            const { quality, length } = matchToken(word, token.text);
            if (quality && length) hits.fill(true, token.start, token.start + length);
        }
    }
    const pieces: { text: string; hit: boolean }[] = [];
    for (let i = 0; i < text.length; i++) {
        const last = pieces.at(-1);
        if (last && last.hit === hits[i]) last.text += text[i];
        else pieces.push({ text: text[i], hit: hits[i] });
    }
    return pieces;
}
