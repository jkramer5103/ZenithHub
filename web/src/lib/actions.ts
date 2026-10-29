import { api } from './api';
import { toasts } from './toast.svelte';
import type { CommandResult } from './types';

/** A structured action for the manager's /action endpoint. It builds the Zenith command and checks permissions. */
export type Action = { action: string } & Record<string, unknown>;

function plain(markdown: string): string {
	return markdown
		.replace(/\*\*|__|~~|`/g, '')
		.replace(/\|\|/g, '')
		.trim();
}

/** What a command said, as one line for a toast. */
export function summary(result: CommandResult | { message: string }): { ok: boolean; text: string } {
	if ('message' in result) return { ok: true, text: result.message };
	const embed = result.embed;
	if (embed) {
		const text = [embed.title, embed.description].filter(Boolean).map((t) => plain(t!)).join(': ');
		return { ok: !embed.error, text: text || 'Done' };
	}
	return { ok: true, text: result.lines.length ? plain(result.lines[result.lines.length - 1]) : 'Done' };
}

/** Runs an action and toasts the outcome. Returns whether it worked. */
export async function runAction(instanceId: string, action: Action, quiet = false): Promise<boolean> {
	try {
		const result = await api.post<CommandResult | { message: string }>(`/instances/${instanceId}/action`, action);
		const { ok, text } = summary(result);
		if (!ok) toasts.show('error', text);
		else if (!quiet) toasts.success(text);
		return ok;
	} catch (e) {
		toasts.error(e);
		return false;
	}
}
