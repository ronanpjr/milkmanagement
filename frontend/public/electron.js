const fs = require('fs');
const path = require('path');
const { app, BrowserWindow, ipcMain, nativeImage, dialog, session, shell } = require('electron');
const { autoUpdater } = require('electron-updater');

// Use built-in Electron property instead of electron-is-dev package
const isDev = !app.isPackaged;

// In dev (`npm run electron:dev`), this main process is spawned separately
// from the `craco start` dev server, so it never inherits the frontend
// `.env` file — only actual shell-exported env vars. `craco start`/CRA reads
// `.env` on its own, but this process doesn't, so without this it silently
// falls back to localhost regardless of what `.env` says. Load it explicitly
// here (dev only; a packaged build has no `.env` file to read and should
// rely on real OS env vars / --erp-api-base-url instead).
if (isDev) {
    require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
}

// No production URL/IP is hardcoded here. The backend base URL used by the
// renderer (src/services/apiService.js) is resolved, in order, from:
//   1. REACT_APP_API_BASE_URL baked in at `npm run build` time (see .env.example).
//   2. window.__ERP_API_BASE_URL__, injected below via the preload
//      script from the REACT_APP_API_BASE_URL (or ERP_API_BASE_URL) OS
//      environment variable present when this Electron process is launched
//      — this lets a packaged build point at a different backend without a
//      rebuild, e.g.:
//        REACT_APP_API_BASE_URL=https://api.example.com ./sm-frontend
//   3. A localhost fallback for local development.
const apiBaseUrlOverride = process.env.REACT_APP_API_BASE_URL || process.env.ERP_API_BASE_URL || null;

// Force locale to pt-BR
app.commandLine.appendSwitch('lang', 'pt-BR');

// Set the App User Model ID (helper for Windows/Linux icon grouping)
if (process.platform === 'linux') {
    app.setAppUserModelId('com.erp.ocr.desktop');
}

const DEV_SERVER_ORIGIN = 'http://localhost:3000';

// Only fallback origin allowed by src/services/apiService.js when neither the
// build-time nor the runtime variable is set (local development only).
const LOCAL_FALLBACK_ORIGIN = 'http://localhost:8080';

const FALLBACK_PDF_FILENAME = 'monthly-settlement.pdf';

// ---------------------------------------------------------------------------
// Origin / CSP helpers
// ---------------------------------------------------------------------------

/**
 * Normalizes a base URL into a bare origin usable in a CSP source expression.
 * Returns null for anything that isn't an absolute http(s) URL, so a garbage
 * or unset variable can never inject a bogus token into the policy.
 */
function toOrigin(value) {
    if (typeof value !== 'string' || value.trim() === '') {
        return null;
    }

    let parsed;
    try {
        parsed = new URL(value.trim());
    } catch (err) {
        return null;
    }

    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        return null;
    }

    return parsed.origin;
}

/**
 * The backend origin baked into the packaged index.html at `npm run build`
 * time, published there as a plain `<meta name="erp-api-base-url">` tag (not an
 * http-equiv, so it has no enforcing power) by CRA's InterpolateHtmlPlugin.
 *
 * It used to be read from a <meta http-equiv="Content-Security-Policy"> tag.
 * That was a dead end: a meta CSP and the header this process sends are both
 * policies for the same document, and the browser enforces the INTERSECTION.
 * The old meta's connect-src was frozen to the build-time URL, so the runtime
 * override — the whole reason apiBaseUrlOverride exists — was dropped from the
 * effective policy, and pointing a packaged build at another backend without
 * rebuilding was blocked. FT-1 re-introduces a meta CSP (that is the policy
 * which applies to the packaged `file://` app, where this process's header
 * never runs), but with a permissive connect-src, so it hardens the shipped
 * app without pinning the override. The build-time origin is still needed here
 * to build the http(s) header — a package launched with no env var must reach
 * the backend it was built against — and that is all this tag is used for.
 *
 * The main process has no access to the build-time environment of
 * `npm run build`, so reading the generated HTML is the only way to learn it.
 */
function readBuildTimeOrigins() {
    const indexHtml = path.join(__dirname, '..', 'build', 'index.html');
    let html;
    try {
        html = fs.readFileSync(indexHtml, 'utf8');
    } catch (err) {
        return [];
    }

    const meta = html.match(/<meta[^>]+name=["']erp-api-base-url["'][^>]*>/i);
    if (!meta) {
        return [];
    }

    // O valor pode ser uma URL com aspas dupla, aspas simples ou sem
    // nenhuma, entao tenta as tres antes de desistir.
    const content = meta[0].match(/content="([^"]*)"/i)
        || meta[0].match(/content='([^']*)'/i)
        || meta[0].match(/content=([^\s>]+)/i);
    if (!content) {
        return [];
    }

    const origem = toOrigin(content[1]);
    // toOrigin devolve null quando o valor nao e uma URL utilizavel — o que
    // acontece de proposito quando REACT_APP_API_BASE_URL nao foi definida no
    // build: o CRA deixa o placeholder literal e o dev cai no localhost, e o
    // mesmo vale para o CSP.
    return origem ? [origem] : [];
}

/**
 * The origins the renderer may actually call: the runtime override (if any),
 * whatever the build froze into the HTML, and the localhost development
 * fallback. Duplicates removed, order preserved.
 */
function effectiveApiOrigins() {
    const candidates = [toOrigin(apiBaseUrlOverride), ...readBuildTimeOrigins(), LOCAL_FALLBACK_ORIGIN];
    return [...new Set(candidates.filter(Boolean))];
}

// style-src / font-src open exactly the Google Fonts origins the app uses
// (src/index.css @imports Inter from fonts.googleapis.com, whose CSS then
// fetches the files from fonts.gstatic.com). Without them the import is
// blocked and the app silently falls back to sans-serif. Keeping these in the
// header as well as in the meta (public/index.html) means the intersection of
// the two policies does not block the app's own assets in the http(s) case.
function buildCspHeader() {
    const connectSrc = ["'self'", ...effectiveApiOrigins()].join(' ');
    return [
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "img-src 'self' data:",
        "font-src 'self' data: https://fonts.gstatic.com",
        `connect-src ${connectSrc}`,
        "object-src 'none'",
        "base-uri 'self'",
        "frame-ancestors 'none'",
    ].join('; ');
}

/**
 * B1 / FT-1: this header carries the policy for the http(s) case — the dev
 * server, or any deployment served over HTTP. It can afford to be *stricter*
 * than the meta CSP in public/index.html, because it can pin `connect-src` to
 * the origins this process actually knows about (runtime override + build-time
 * origin + localhost fallback), and a header can express `frame-ancestors` and
 * report violations, which a `<meta>` cannot.
 *
 * The packaged app, though, is served over `file://`, where webRequest /
 * onHeadersReceived never runs — so this header does not apply there. That
 * case is governed solely by the meta CSP in public/index.html: strict
 * `script-src` / `object-src` / `base-uri` / `frame-ancestors` with a
 * permissive `connect-src`, so the runtime origin override the preload injects
 * is never frozen out.
 *
 * Where both apply (an http(s) document that also carries the meta, i.e. the
 * dev server), the browser enforces the INTERSECTION: this header's stricter
 * `connect-src` wins, which is exactly the intent, and its `script-src`
 * matches the meta's, so nothing that used to load stops loading.
 */
function applyCspHeader() {
    session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
        if (!details.url.startsWith('http://') && !details.url.startsWith('https://')) {
            callback({});
            return;
        }

        const responseHeaders = { ...(details.responseHeaders || {}) };
        responseHeaders['Content-Security-Policy'] = [buildCspHeader()];
        callback({ responseHeaders });
    });
}

// ---------------------------------------------------------------------------
// IPC (registered once, at module load — see registerIpcHandlers below)
// ---------------------------------------------------------------------------

function senderWindow(event) {
    return BrowserWindow.fromWebContents(event.sender);
}

/**
 * The preload is only attached to windows we create, but a handler that acts
 * on the filesystem or the window itself should still refuse to serve a frame
 * that is not our own app.
 */
function isTrustedSender(event) {
    const url = (event.senderFrame && event.senderFrame.url) || event.sender.getURL();

    if (isDev) {
        return url.startsWith(DEV_SERVER_ORIGIN);
    }

    return url.startsWith('file://');
}

/**
 * A3: the suggested filename embeds `produtorNome`, which comes from the
 * server (and from OCR), so it is untrusted input. Keep only the basename,
 * drop path separators, reserved characters and control characters, and force
 * a .pdf extension — the user can still pick any directory in the save dialog,
 * but the value we hand to it can never escape a directory by itself.
 */
function sanitizePdfFilename(rawFilename) {
    const asText = typeof rawFilename === 'string' ? rawFilename : '';
    // Normalize Windows separators first: path.basename() only splits on the
    // platform separator, and this app also ships a Windows build.
    const baseName = path.basename(asText.replace(/\\/g, '/'));

    const cleaned = baseName
        .replace(/[\u0000-\u001f\u007f]/g, '')
        .replace(/[<>:"|?*]/g, '-')
        .replace(/-{2,}/g, '-')
        .replace(/^[.\s-]+/, '')
        .replace(/[.\s]+$/, '')
        .slice(0, 200)
        .trim();

    if (!cleaned) {
        return FALLBACK_PDF_FILENAME;
    }

    return cleaned.toLowerCase().endsWith('.pdf') ? cleaned : `${cleaned}.pdf`;
}

// A1: these used to live inside createWindow(), which runs again on every
// `activate`. ipcMain.handle throws "Attempted to register a second handler
// for 'save-to-pdf'" the second time, taking the whole main process down —
// so closing and reopening the app (or clicking the dock/taskbar icon with no
// window open) was a hard crash. Register once, and resolve the target window
// from the event's sender instead of closing over the last created `win`.
function registerIpcHandlers() {
    ipcMain.on('window-minimize', (event) => {
        const win = senderWindow(event);
        if (win && isTrustedSender(event)) {
            win.minimize();
        }
    });

    ipcMain.on('window-maximize', (event) => {
        const win = senderWindow(event);
        if (!win || !isTrustedSender(event)) {
            return;
        }

        if (win.isMaximized()) {
            win.unmaximize();
        } else {
            win.maximize();
        }
    });

    ipcMain.on('window-close', (event) => {
        const win = senderWindow(event);
        if (win && isTrustedSender(event)) {
            win.close();
        }
    });

    ipcMain.handle('save-to-pdf', async (event, filename) => {
        const win = senderWindow(event);
        if (!win || !isTrustedSender(event)) {
            throw new Error('Origem nao confiavel para save-to-pdf');
        }

        // Show Save Dialog
        const { filePath } = await dialog.showSaveDialog(win, {
            defaultPath: sanitizePdfFilename(filename),
            filters: [{ name: 'PDF Files', extensions: ['pdf'] }]
        });

        if (!filePath) {
            return;
        }

        try {
            const data = await win.webContents.printToPDF({});
            fs.writeFileSync(filePath, data);
        } catch (error) {
            console.error('Failed to save PDF:', error);
            throw error;
        }
    });
}

registerIpcHandlers();

// ---------------------------------------------------------------------------
// Navigation hardening (A2)
// ---------------------------------------------------------------------------

function isAppUrl(url) {
    return isDev ? url.startsWith(DEV_SERVER_ORIGIN) : url.startsWith('file://');
}

/**
 * Links to the outside world are handed to the OS browser — never opened
 * inside the app, where the preload (and therefore `saveToPdf`) would be
 * inherited by the foreign page.
 */
function openExternalSafely(url) {
    try {
        const parsed = new URL(url);
        if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
            shell.openExternal(url);
            return;
        }
    } catch (err) {
        // not a URL we can parse; fall through
    }

    console.warn('Blocked external navigation:', url);
}

function hardenNavigation(win) {
    // window.open() / target=_blank would create a new BrowserWindow that
    // inherits this window's webPreferences, preload included — the foreign
    // page would get window.electronAPI.saveToPdf (file write) for free.
    win.webContents.setWindowOpenHandler(({ url }) => {
        openExternalSafely(url);
        return { action: 'deny' };
    });

    win.webContents.on('will-navigate', (event, url) => {
        if (isAppUrl(url)) {
            return;
        }

        event.preventDefault();
        openExternalSafely(url);
    });

    // No <webview> anywhere in this app; refuse to attach one rather than
    // letting it come up with node integration.
    win.webContents.on('will-attach-webview', (event) => {
        event.preventDefault();
    });
}

function createWindow() {
    // Load icon using nativeImage for better compatibility
    const iconPath = path.join(__dirname, 'logo.svg');
    const appIcon = nativeImage.createFromPath(iconPath);

    const win = new BrowserWindow({
        width: 1200,
        height: 800,
        frame: false, // Disable native frame
        icon: appIcon, // Set system icon using nativeImage
        webPreferences: {
            contextIsolation: true,
            nodeIntegration: false,
            preload: path.join(__dirname, 'preload.js'),
            // Passed through to the preload script via process.argv so it can
            // expose window.__ERP_API_BASE_URL__ to the renderer.
            additionalArguments: apiBaseUrlOverride ? [`--erp-api-base-url=${apiBaseUrlOverride}`] : [],
        },
    });

    hardenNavigation(win);

    // Explicitly set icon for Linux (sometimes required in dev mode)
    if (process.platform === 'linux') {
        win.setIcon(appIcon);
    }

    win.loadURL(
        isDev
            ? DEV_SERVER_ORIGIN
            : `file://${path.join(__dirname, '../build/index.html')}`
    );

    // Open the DevTools.
    if (isDev) {
        win.webContents.openDevTools({ mode: 'detach' });
    }
}

// Auto-update via electron-updater against GitHub Releases on the public
// your-github-org/erp-ai-ocr-releases repo (see build.publish in package.json).
// Windows-only: Linux ships deb/rpm (no self-update path — see CLAUDE.md),
// and this app isn't distributed for macOS. The feed is public, so no token
// is embedded in the installer; electron-updater reads the latest release
// anonymously. Releases are cut by release-please (vX.Y.Z tag) and published
// by .github/workflows/release.yml.
function setupAutoUpdater() {
    if (!app.isPackaged || process.platform !== 'win32') {
        return;
    }

    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;

    autoUpdater.on('error', (err) => {
        console.error('Auto-update error:', err);
    });

    autoUpdater.on('update-downloaded', (info) => {
        dialog.showMessageBox({
            type: 'info',
            title: 'Atualização disponível',
            message: `Uma nova versão (${info.version}) foi baixada. Reiniciar agora para instalar?`,
            buttons: ['Reiniciar agora', 'Mais tarde'],
            defaultId: 0,
            cancelId: 1,
        }).then(({ response }) => {
            if (response === 0) {
                autoUpdater.quitAndInstall();
            }
        });
    });

    const checkForUpdates = () => {
        autoUpdater.checkForUpdates().catch((err) => console.error('Update check failed:', err));
    };

    checkForUpdates();
    // Long-running desktop app with no periodic restart, so keep rechecking.
    setInterval(checkForUpdates, 4 * 60 * 60 * 1000);
}

app.whenReady().then(() => {
    applyCspHeader();
    createWindow();
    setupAutoUpdater();
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
    }
});
