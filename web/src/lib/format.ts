import { escapeHtml } from './ansi';

export function duration(seconds: number | null | undefined): string {
	if (seconds == null || seconds < 0) return '-';
	const d = Math.floor(seconds / 86400);
	const h = Math.floor((seconds % 86400) / 3600);
	const m = Math.floor((seconds % 3600) / 60);
	const s = Math.floor(seconds % 60);
	if (d > 0) return `${d}d ${h}h`;
	if (h > 0) return `${h}h ${m}m`;
	if (m > 0) return `${m}m ${s}s`;
	return `${s}s`;
}

export function clock(ts: number): string {
	return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

export function relative(ts: number): string {
	return duration(Math.max(0, (Date.now() - ts) / 1000)) + ' ago';
}

/** Renders the Discord markdown subset Zenith uses in command output: **bold**, `code`, ||spoiler||, [links](url). */
export function discordMarkdown(text: string): string {
	return escapeHtml(text)
		.replace(/```(?:\w*\n)?([\s\S]*?)```/g, '<pre class="my-1 whitespace-pre-wrap rounded bg-zinc-950 p-2 text-xs">$1</pre>')
		.replace(/`([^`]+)`/g, '<code class="rounded bg-zinc-800 px-1 text-[0.85em]">$1</code>')
		.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
		.replace(/__([^_]+)__/g, '<u>$1</u>')
		.replace(/\|\|([^|]+)\|\|/g, '<span class="spoiler" data-spoiler>$1</span>')
		.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a class="text-emerald-400 underline" target="_blank" rel="noreferrer" href="$2">$1</a>')
		.replace(/\n/g, '<br>');
}
