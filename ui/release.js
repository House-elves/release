// Releases' tab on Elf Desk: the repos you can release with their versions, patch, minor and major buttons, and each
// release in progress from its PR to Maven Central. It runs in the desk's sandboxed frame: everything goes through
// `elf`, and only what elf.yaml declares. Releasing needs the GitHub elf on the desk.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, count, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

/** The steps a release goes through, as the tab shows them; the elf's stage names, in order. */
const STEPS = [['ci', 'PR and CI'], ['prepare', 'Tagged'], ['release', 'Released'], ['central', 'On Maven Central']];
const KINDS = ['patch', 'minor', 'major'];

async function board() {
    try {
        return await elf.readJson('board', 'board.json');
    } catch {
        return null;
    }
}

/** The releases in progress and the ones that finished lately: {repo: release}. */
async function releases() {
    const out = {};
    let files = [];
    try {
        files = await elf.list('releases');
    } catch {
        return out;
    }
    for (const f of files.filter(f => f.name.endsWith('.json') && !f.name.startsWith('.'))) {
        try {
            const r = await elf.readJson('releases', f.name);
            if (r?.repo) out[r.repo] = r;
        } catch {
            // being written: the next change reads it
        }
    }
    return out;
}

const active = r => r && (r.state === 'running' || r.state === 'waiting');

/** A link only to GitHub or Maven Central: what the elf saved came from them. */
function safe(url) {
    try {
        const u = new URL(url);
        return u.protocol === 'https:' && ['github.com', 'repo1.maven.org'].includes(u.hostname) ? url : null;
    } catch {
        return null;
    }
}

class ReleaseTab extends LitElement {

    static properties = {
        _board: { state: true },
        _releases: { state: true },
        _state: { state: true },
        _github: { state: true },
        _loading: { state: true },
        _filter: { state: true },
        _doing: { state: true },
        _error: { state: true },
        _rowError: { state: true },
        _starting: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
        .bar input { max-width: 18rem; }
        .status { display: inline-flex; align-items: center; gap: 8px; font-size: 14px; color: var(--accent); }
        section { display: grid; gap: 12px; }
        .sec-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
        .sec-head h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .sec-head .n { color: var(--muted); font-size: 15px; }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 4px 14px; align-items: center; padding: 12px 18px; }
        li + li { border-top: 1px solid var(--line); }
        li:hover { background: color-mix(in srgb, var(--surface-2) 55%, transparent); }
        .main { min-width: 0; display: grid; gap: 4px; }
        .line1 { display: flex; align-items: baseline; gap: 10px; min-width: 0; flex-wrap: wrap; }
        .title { color: var(--ink); font-weight: 500; text-decoration: none; overflow-wrap: anywhere; }
        .title:hover { text-decoration: underline; }
        .version { font-family: var(--font-code); font-size: 13.5px; color: var(--ink); }
        .meta { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 13px; color: var(--muted); }
        .meta .new { color: var(--ink); }
        .acts { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; align-items: center; }
        .acts button { font-variant-numeric: tabular-nums; }
        .acts button .k { color: var(--muted); font-weight: normal; margin-right: 4px; }
        .acts button.primary .k { color: inherit; opacity: 0.75; }
        li.quiet { opacity: 0.7; }
        .flight { display: grid; gap: 8px; padding: 14px 18px; }
        .flight + .flight { border-top: 1px solid var(--line); }
        .flight .say { color: var(--ink); }
        .flight.stopped { box-shadow: inset 3px 0 0 var(--bad); }
        .flight.done { box-shadow: inset 3px 0 0 var(--good, #2f855a); }
        .flight.waiting { box-shadow: inset 3px 0 0 var(--warn); }
        .steps { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 13px; color: var(--muted); }
        .steps span { display: inline-flex; align-items: center; gap: 6px; }
        .steps i { width: 18px; height: 4px; border-radius: 2px; background: var(--line); }
        .steps .done i { background: color-mix(in srgb, var(--accent) 45%, var(--line)); }
        .steps .now { color: var(--ink); font-weight: 500; }
        .steps .now i { background: var(--accent); }
        .steps .bad i { background: var(--bad); }
        .row-error { grid-column: 1 / -1; margin: 4px 0 0; font-size: 13.5px; }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; font-size: 14px; }
        .notice { padding: 18px 20px; display: grid; gap: 8px; max-width: 46rem; }
        .notice p { margin: 0; }
        .note { color: var(--muted); font-size: 13px; margin: 0; }
        pre.log { margin: 6px 0 0; padding: 12px 14px; border-radius: var(--r-control); overflow-x: auto;
            background: var(--log-bg); color: var(--log-ink); font: 12.5px/1.5 var(--font-code); white-space: pre-wrap; }
        @media (max-width: 640px) {
            h1 { font-size: 26px; }
            li { grid-template-columns: minmax(0, 1fr); }
            .acts { justify-content: flex-start; }
        }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._loading = true;
        this._filter = '';
        this._doing = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._github ??= await elf.hasElf('github').catch(() => false);
            this._state = await elf.state();
            this._board = await board();
            this._releases = await releases();
            this._error = null;
            // A release in progress with nothing following it (the desk restarted, say): follow it.
            if (Object.values(this._releases).some(active) && !this._state?.running?.follow && !this._kicked) {
                this._kicked = true;
                elf.run('follow').catch(() => {}).finally(() => { setTimeout(() => { this._kicked = false; }, 60_000); });
            }
        } catch (e) {
            this._error = `Couldn't load: ${e.message}`;
        } finally {
            this._loading = false;
        }
    }

    get _refreshing() {
        return this._starting === 'board' || !!this._state?.running?.board;
    }

    async _refresh() {
        this._starting = 'board';
        this._error = null;
        try {
            this._state = await elf.run('board');
        } catch (e) {
            this._error = e.message;
        } finally {
            this._starting = null;
        }
    }

    /** patch, minor or major: the desk's bar asks, then the PR goes up and following it starts. */
    async _release(row, kind) {
        const version = row.next[kind];
        this._doing = `${row.repo} release`;
        this._rowError = null;
        try {
            await elf.act('release', { repo: row.repo, kind, version, as: this._board.as });
            await this._load();
            elf.run('follow').catch(() => {});
        } catch (e) {
            if (!e.cancelled) this._rowError = { repo: row.repo, text: e.message };
        } finally {
            this._doing = null;
        }
    }

    async _do(action, r) {
        this._doing = `${r.repo} ${action}`;
        this._rowError = null;
        try {
            await elf.act(action, { repo: r.repo });
            await this._load();
            if (action === 'retry') elf.run('follow').catch(() => {});
        } catch (e) {
            if (!e.cancelled) this._rowError = { repo: r.repo, text: e.message };
        } finally {
            this._doing = null;
        }
    }

    render() {
        const b = this._board;
        const flights = Object.values(this._releases ?? {}).sort((a, x) => (x.startedAt ?? '').localeCompare(a.startedAt ?? ''));
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Releases</h1>
                    <p class="sub">${b ? html`${count(b.repos.length, 'repo')} you can release, in ${b.accounts.join(', ')}. Updated ${ago(b.at)}.`
                        : this._loading ? '' : 'Not read from GitHub yet.'}</p>
                </div>${settingsButton(null, 'Releases settings')}</div>
                <div class="bar">
                    <button class="primary" ?disabled=${this._refreshing} @click=${() => this._refresh()}>
                        ${this._refreshing ? spinner : ''}${this._refreshing ? 'Refreshing' : 'Refresh'}</button>
                    ${b && b.repos.length > 8 ? html`<input type="search" placeholder="Find a repo" aria-label="Find a repo"
                        .value=${this._filter} @input=${e => { this._filter = e.target.value; }}>` : ''}
                    ${this._state?.running?.follow ? html`<span class="status" role="status">${spinner}Following the releases in progress</span>` : ''}
                </div>
                ${this._state?.failed?.board && !this._state?.running?.board ? html`<div role="alert"><p class="error">The last refresh failed. The end of its log:</p>
                    <pre class="log">${this._state.failed.board}</pre></div>` : ''}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._github === false ? html`<div class="panel notice"><p><b>Add the GitHub elf to release from here.</b></p>
                    <p class="note">Releases lists your repos either way; the patch, minor and major buttons show once the GitHub elf is on your desk.</p></div>` : ''}
                ${flights.length ? this._flights(flights) : ''}
                ${this._loading && !b ? html`<div class="panel"><div class="skeleton" style="height:240px"></div></div>`
                    : b ? this._repos(b) : html`<div class="panel notice"><p>Set the accounts and organizations to look in (Settings), then Refresh.</p></div>`}
            </div>`;
    }

    _flights(flights) {
        return html`<section>
            <div class="sec-head"><h2>Releases</h2><span class="n">${flights.filter(active).length} in progress</span></div>
            <div class="panel">${flights.map(r => this._flight(r))}</div>
        </section>`;
    }

    _flight(r) {
        const at = STEPS.findIndex(([s]) => s === r.stage);
        const done = r.state === 'done';
        const runs = r.runs ?? {};
        const busy = this._doing?.startsWith(`${r.repo} `);
        return html`<div class="flight ${r.state}">
            <div class="line1">
                <a class="title" href=${safe(r.url) ?? '#'} target="_blank" rel="noopener">${r.repo}</a>
                <span class="version">${r.from} → <b>${r.version}</b></span>
                <span class="note">${r.kind}, started ${ago(r.startedAt)}${done && r.doneAt ? `, done ${ago(r.doneAt)}` : ''}</span>
            </div>
            <div class="steps" aria-label="How far it got">${STEPS.map(([s, label], i) => {
                const cls = done || i < at ? 'done' : i === at ? (r.state === 'stopped' ? 'bad' : 'now') : '';
                const link = s === 'ci' ? safe(r.pr?.url) : s === 'central' ? safe(r.centralUrl) : safe(runs[s]?.url);
                if (s === 'prepare' && !r.prepareWorkflow) return '';
                if (s === 'central' && !r.groupId) return '';
                return html`<span class=${cls}><i></i>${link ? html`<a href=${link} target="_blank" rel="noopener">${label}</a>` : label}</span>`;
            })}</div>
            <div class="say">${r.state === 'running' ? html`${spinner} ` : ''}${r.say}</div>
            ${r.problem ? html`<p class="note">${r.problem}</p>` : ''}
            <div class="acts" style="justify-content:flex-start">
                ${r.pr?.url ? html`<a class="btn sm quiet" href=${safe(r.pr.url)} target="_blank" rel="noopener">PR #${r.pr.number}</a>` : ''}
                ${r.state === 'stopped' && r.doneAt == null ? html`<button class="sm primary" ?disabled=${busy} @click=${() => this._do('retry', r)}>Try again</button>` : ''}
                ${active(r) && r.stage === 'ci' ? html`<button class="sm quiet" ?disabled=${busy} @click=${() => this._do('stop', r)}>Stop</button>` : ''}
                ${!active(r) ? html`<button class="sm quiet" ?disabled=${busy} @click=${() => this._do('dismiss', r)}>Dismiss</button>` : ''}
            </div>
            ${this._rowError?.repo === r.repo ? html`<p class="error row-error" role="alert">${this._rowError.text}</p>` : ''}
        </div>`;
    }

    _repos(b) {
        const f = this._filter.trim().toLowerCase();
        // What has changed most since its last release first; nothing new (just the version bump) last.
        const rows = b.repos.filter(r => !f || r.repo.toLowerCase().includes(f))
            .sort((a, x) => (x.since ?? 0) - (a.since ?? 0) || a.repo.localeCompare(x.repo));
        return html`<section>
            <div class="sec-head"><h2>Your repos</h2><span class="n">${rows.length}</span></div>
            <div class="panel">${rows.length ? html`<ul>${rows.map(r => this._row(r))}</ul>`
                : html`<p class="empty">${f ? 'No repo matches.' : 'No repo here has a .github/project.yml you can release. Add accounts or organizations in Settings.'}</p>`}</div>
        </section>`;
    }

    _row(r) {
        const flight = this._releases?.[r.repo];
        const busy = this._doing === `${r.repo} release`;
        const quiet = (r.since ?? 0) <= 1;
        return html`<li class=${quiet ? 'quiet' : ''}>
            <div class="main">
                <div class="line1"><a class="title" href=${safe(r.url) ?? '#'} target="_blank" rel="noopener">${r.repo}</a>
                    <span class="version">${r.version}</span></div>
                <div class="meta">
                    ${r.releasedAt ? html`<span>released ${ago(r.releasedAt)}</span>` : html`<span>no tag ${r.version} found</span>`}
                    ${typeof r.since === 'number' ? html`<span class=${quiet ? '' : 'new'}
                        title="Commits on ${r.branch} since the tag (the release's own version bump is one)">${count(r.since, 'commit')} since</span>` : ''}
                </div>
            </div>
            <div class="acts">${active(flight) ? html`<span class="note">${spinner} releasing ${flight.version}</span>`
                : !r.next ? html`<span class="note" title="Its version has a qualifier: set the next one in .github/project.yml by hand">release by hand</span>`
                : !this._github ? '' : KINDS.map(k => html`<button class=${k === 'patch' && !quiet ? 'sm primary' : 'sm'} ?disabled=${!!this._doing}
                    title="Release ${r.next[k]}" @click=${() => this._release(r, k)}>${busy ? '' : html`<span class="k">${k}</span>`}${r.next[k]}</button>`)}</div>
            ${this._rowError?.repo === r.repo && !flight ? html`<p class="error row-error" role="alert">${this._rowError.text}</p>` : ''}
        </li>`;
    }
}

/** The dashboard card: releases in progress, or the last one done, and attention when one stopped. */
class ReleaseCard extends LitElement {

    static properties = { _releases: { state: true }, _board: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        this._releases = Object.values(await releases());
        this._board = await board();
        this._loaded = true;
        elf.card({ attention: this._releases.some(r => r.state === 'stopped' || r.state === 'waiting') });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const all = this._releases;
        const going = all.filter(active), stopped = all.filter(r => r.state === 'stopped' && r.doneAt == null);
        const last = all.filter(r => r.state === 'done').sort((a, x) => (x.doneAt ?? '').localeCompare(a.doneAt ?? ''))[0];
        let say;
        if (stopped.length) say = html`<a href="#">${stopped[0].repo} ${stopped[0].version}</a> stopped: ${stopped[0].say}`;
        else if (going.length) say = html`<a href="#">${count(going.length, 'release')}</a> in progress: ${going[0].say}`;
        else if (last) say = html`Released <a href="#">${last.repo} ${last.version}</a> ${ago(last.doneAt)}.`;
        else say = this._board ? `${count(this._board.repos.length, 'repo')} you can release.` : 'Not read from GitHub yet. Refresh it from its tab.';
        return html`<p class="say">${say}</p>`;
    }
}

customElements.define('release-tab', ReleaseTab);
customElements.define('release-card', ReleaseCard);
document.body.append(document.createElement(elf.view === 'card' ? 'release-card' : 'release-tab'));
