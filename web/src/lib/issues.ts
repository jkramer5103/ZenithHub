import type { Account, Instance } from './types.js';

export interface Issue {
	level: 'error' | 'warn';
	title: string;
	detail?: string;
}

const DAY = 86_400_000;

/** What needs the user's attention on an instance, worst first. Being offline on purpose isn't a problem. */
export function instanceIssues(instance: Instance, account: Account | undefined, now = Date.now()): Issue[] {
	const issues: Issue[] = [];
	if (account?.authError) issues.push({ level: 'error', title: 'Sign-in failed', detail: account.authError });
	if (account?.tokenExpiresAt && account.tokenExpiresAt - now < DAY) {
		const expired = account.tokenExpiresAt <= now;
		issues.push({
			level: expired ? 'error' : 'warn',
			title: expired ? 'Login expired' : 'Login expires soon',
			detail: 'Sign in again under Settings → Accounts.'
		});
	}
	const runtime = instance.runtime;
	if (runtime.phase === 'BACKOFF') issues.push({ level: 'error', title: 'Crashed', detail: runtime.lastError ?? undefined });
	const status = runtime.phase === 'RUNNING' ? runtime.status : null;
	if (status && !status.connected && status.lastDisconnectReason && instance.desiredRunning) {
		issues.push({ level: 'warn', title: 'Disconnected', detail: status.lastDisconnectReason });
	}
	const afk = status?.online ? status.afkZone : undefined;
	if (afk?.state === 'FAILED') issues.push({ level: 'error', title: "Couldn't get into the AFK zone", detail: afk.failure });
	if (afk?.state === 'IN_ZONE' && afk.earning === false) {
		const label = afk.rewardLabel || 'rewards';
		const message = afk.serverMessageBlocks ? afk.serverMessage : undefined;
		issues.push({
			level: 'error',
			title: message && /verif|discord/i.test(message) ? `Not verified, earns no ${label}` : `Not earning ${label}`,
			detail: message ? `The server says: “${message}”` : `The counter hasn't gone up since it went in.`
		});
	}
	return issues;
}
