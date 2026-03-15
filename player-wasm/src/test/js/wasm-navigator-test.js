#!/usr/bin/env node
'use strict';

/**
 * Headless Chromium test: SSO login -> Hotel Navigator appearance & ink colours.
 *
 * Loads the Habbo DCR with sw6 param (use.sso.ticket=1;sso.ticket=123) to
 * auto-login via SSO, waits for the Hotel Navigator window to appear, and
 * verifies the rendered output matches the reference screenshot colour profile
 * (teal headers, orange "Open" buttons, correct ink colours).
 *
 * Usage (via Gradle):
 *   ./gradlew :player-wasm:runWasmNavigatorTest
 *   ./gradlew :player-wasm:runWasmNavigatorTest -PoutputDir=C:/tmp/nav-test
 *
 * Args: distPath dcrFile castDir [outputDir]
 */

const puppeteer = require('puppeteer');
const http = require('http');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Args
// ---------------------------------------------------------------------------
const distPath  = process.argv[2] || path.resolve(__dirname, '../../../build/dist');
const dcrFile   = process.argv[3] || 'C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr';
const castDir   = process.argv[4] || 'C:/xampp/htdocs/dcr/14.1_b8';
const outputDir = process.argv[5] || path.resolve(process.cwd(), 'frames_navigator');

// Timeouts
const LOGIN_TIMEOUT_POLLS = 600;    // 60 seconds (SSO login may take longer)
const NAVIGATOR_TIMEOUT_MS = 30000; // 30 seconds for navigator to appear after login

// Navigator region: right side of stage where the navigator window appears
// Reference shows it at roughly x=350..720, y=60..500
const NAV_REGION_X = 350;
const NAV_REGION_Y = 60;
const NAV_REGION_W = 370;
const NAV_REGION_H = 440;

// Pixel-change threshold
const CHANGE_THRESHOLD = 0.05;  // 5% of sampled area must differ

// Navigator colour expectations from reference:
// - Teal/dark header bar (approx RGB 0,102,102 to 51,153,153)
// - Orange "Open" buttons (approx RGB 204,102,0 to 255,153,51)
// - White/light gray list background
const CONTENT_VARIETY_THRESHOLD = 20; // need rich colour diversity for navigator UI

// ---------------------------------------------------------------------------
// HTTP server
// ---------------------------------------------------------------------------
const MIME = {
    '.html': 'text/html',
    '.js':   'application/javascript',
    '.wasm': 'application/wasm',
    '.css':  'text/css',
    '.png':  'image/png',
    '.dcr':  'application/x-director',
    '.cct':  'application/x-director',
    '.cst':  'application/x-director',
    '.txt':  'text/plain',
};

function createServer() {
    return new Promise((resolve) => {
        const server = http.createServer((req, res) => {
            const url = decodeURIComponent(req.url.split('?')[0]);

            let filePath = path.join(distPath, url);
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            filePath = path.join(castDir, url.replace(/^.*\//, ''));
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            filePath = path.join(castDir, path.basename(url));
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            let ancestor = castDir;
            for (let i = 0; i < 3; i++) {
                ancestor = path.dirname(ancestor);
                const gamedataPath = path.join(ancestor, url);
                if (fs.existsSync(gamedataPath) && fs.statSync(gamedataPath).isFile()) {
                    return serveFile(res, gamedataPath);
                }
            }

            console.log('  [404] ' + url);
            res.writeHead(404);
            res.end('Not found: ' + url);
        });

        server.listen(0, '127.0.0.1', () => {
            resolve({ server, port: server.address().port });
        });
    });
}

function serveFile(res, filePath) {
    const ext = path.extname(filePath).toLowerCase();
    const mime = MIME[ext] || 'application/octet-stream';
    const data = fs.readFileSync(filePath);
    res.writeHead(200, {
        'Content-Type': mime,
        'Content-Length': data.length,
        'Access-Control-Allow-Origin': '*',
    });
    res.end(data);
}

// ---------------------------------------------------------------------------
// Canvas helpers
// ---------------------------------------------------------------------------
async function captureCanvas(page, filePath) {
    const dataUrl = await page.evaluate(() => {
        const canvas = document.getElementById('stage');
        return canvas ? canvas.toDataURL('image/png') : null;
    });
    if (!dataUrl) return;
    const base64 = dataUrl.replace(/^data:image\/png;base64,/, '');
    fs.writeFileSync(filePath, Buffer.from(base64, 'base64'));
}

async function sampleCanvasRegion(page, x, y, w, h) {
    return page.evaluate((rx, ry, rw, rh) => {
        const canvas = document.getElementById('stage');
        if (!canvas) return null;
        const ctx = canvas.getContext('2d');
        const data = ctx.getImageData(rx, ry, rw, rh).data;
        const samples = [];
        for (let i = 0; i < data.length; i += 16) {
            samples.push(data[i], data[i+1], data[i+2]);
        }
        return samples;
    }, x, y, w, h);
}

async function measureColorVariety(page, x, y, w, h) {
    return page.evaluate((rx, ry, rw, rh) => {
        const canvas = document.getElementById('stage');
        if (!canvas) return 0;
        const ctx = canvas.getContext('2d');
        const data = ctx.getImageData(rx, ry, rw, rh).data;
        const buckets = new Set();
        for (let i = 0; i < data.length; i += 16) {
            const r = data[i] >> 5;
            const g = data[i+1] >> 5;
            const b = data[i+2] >> 5;
            buckets.add((r << 6) | (g << 3) | b);
        }
        return buckets.size;
    }, x, y, w, h);
}

/**
 * Analyse colour distribution in the navigator region.
 * Returns counts of pixels in key colour ranges (teal, orange, white/gray, black).
 */
async function analyseNavigatorColours(page, x, y, w, h) {
    return page.evaluate((rx, ry, rw, rh) => {
        const canvas = document.getElementById('stage');
        if (!canvas) return null;
        const ctx = canvas.getContext('2d');
        const data = ctx.getImageData(rx, ry, rw, rh).data;
        let teal = 0, orange = 0, white = 0, gray = 0, black = 0, other = 0;
        let total = 0;
        for (let i = 0; i < data.length; i += 4) {
            const r = data[i], g = data[i+1], b = data[i+2];
            total++;
            // Teal/dark cyan: low R, mid-high G, mid-high B (header bars)
            if (r < 80 && g > 60 && g < 180 && b > 60 && b < 180 && Math.abs(g - b) < 40) {
                teal++;
            }
            // Orange: high R, mid G, low B (Open buttons)
            else if (r > 150 && g > 60 && g < 180 && b < 80) {
                orange++;
            }
            // White/near-white
            else if (r > 220 && g > 220 && b > 220) {
                white++;
            }
            // Gray
            else if (Math.abs(r - g) < 20 && Math.abs(g - b) < 20 && r > 80 && r < 220) {
                gray++;
            }
            // Black/near-black
            else if (r < 40 && g < 40 && b < 40) {
                black++;
            }
            else {
                other++;
            }
        }
        return { teal, orange, white, gray, black, other, total };
    }, x, y, w, h);
}

function computeChangeFraction(before, after) {
    if (!before || !after || before.length !== after.length) return 1.0;
    let changed = 0;
    const pixelCount = before.length / 3;
    for (let i = 0; i < before.length; i += 3) {
        const dr = Math.abs(before[i] - after[i]);
        const dg = Math.abs(before[i+1] - after[i+1]);
        const db = Math.abs(before[i+2] - after[i+2]);
        if (dr + dg + db > 30) changed++;
    }
    return changed / pixelCount;
}

// ---------------------------------------------------------------------------
// Main test
// ---------------------------------------------------------------------------
async function main() {
    console.log('=== WASM Habbo Navigator Test ===');
    console.log('Dist:    ', distPath);
    console.log('DCR:     ', dcrFile);
    console.log('Casts:   ', castDir);
    console.log('Output:  ', outputDir);

    if (!fs.existsSync(path.join(distPath, 'libreshockwave.js'))) {
        console.error('FAIL: libreshockwave.js not found in dist. Run assembleWasm first.');
        process.exit(1);
    }
    if (!fs.existsSync(dcrFile)) {
        console.error('FAIL: DCR file not found: ' + dcrFile);
        process.exit(1);
    }

    fs.mkdirSync(outputDir, { recursive: true });

    const { server, port } = await createServer();
    const baseUrl = `http://127.0.0.1:${port}`;
    console.log('Server:  ', baseUrl);

    const dcrFileName = path.basename(dcrFile);

    let browser;
    try {
        browser = await puppeteer.launch({
            headless: true,
            args: ['--no-sandbox', '--disable-setuid-sandbox'],
        });

        const page = await browser.newPage();

        const logs = [];
        page.on('console', msg => {
            const text = msg.text();
            logs.push(text);
            if (text.includes('[TEST]') || text.includes('[LS]') || text.includes('[W]')
                || text.includes('Error') || text.includes('SSO') || text.includes('sso')
                || text.includes('navigator') || text.includes('Navigator')
                || text.includes('[DEBUG')) {
                console.log('  [page] ' + text);
            }
        });

        // Build test HTML — includes sw6 for SSO auto-login
        const html = `<!DOCTYPE html>
<html><body>
<canvas id="stage" width="720" height="540"></canvas>
<script src="${baseUrl}/libreshockwave.js"><\/script>
<script>
    var _testState = { tick: 0, frame: 0, loaded: false, error: null, movieWidth: 0, movieHeight: 0 };

    var player = LibreShockwave.create('stage', {
        basePath: '${baseUrl}/',
        params: {
            sw1: 'site.url=http://127.0.0.1:${port};url.prefix=http://127.0.0.1:${port}',
            sw2: 'connection.info.host=127.0.0.1;connection.info.port=30001',
            sw3: 'client.reload.url=http://127.0.0.1:${port}/',
            sw4: 'connection.mus.host=127.0.0.1;connection.mus.port=38101',
            sw5: 'external.variables.txt=http://127.0.0.1:${port}/gamedata/external_variables.txt;external.texts.txt=http://127.0.0.1:${port}/gamedata/external_texts.txt',
            sw6: 'use.sso.ticket=1;sso.ticket=123'
        },
        autoplay: true,
        onLoad: function(info) {
            _testState.loaded = true;
            _testState.movieWidth = info.width;
            _testState.movieHeight = info.height;
            console.log('[TEST] Movie loaded: ' + info.width + 'x' + info.height + ', ' + info.frameCount + ' frames');
        },
        onFrame: function(frame, total) {
            _testState.tick++;
            _testState.frame = frame;
        },
        onError: function(msg) {
            _testState.error = msg;
            console.error('[TEST] Error: ' + msg);
        },
        onDebugLog: function(log) {
            // Debug log tracing disabled
        }
    });

    player.load('${baseUrl}/${dcrFileName}');
<\/script>
</body></html>`;

        const htmlPath = path.join(distPath, '_test_nav.html');
        fs.writeFileSync(htmlPath, html);

        await page.goto(`${baseUrl}/_test_nav.html`, { waitUntil: 'domcontentloaded' });
        console.log('Page loaded, waiting for SSO login + navigator...');

        const startTime = Date.now();

        // ----- Step 1: Wait for login / hotel view -----
        let hotelReady = false;
        for (let i = 0; i < LOGIN_TIMEOUT_POLLS; i++) {
            await new Promise(r => setTimeout(r, 100));

            const state = await page.evaluate(() => window._testState);
            if (!state) continue;
            if (state.error) {
                console.error('  Player error: ' + state.error);
            }

            if (state.loaded && state.tick >= 30) {
                const hasContent = await page.evaluate(() => {
                    const canvas = document.getElementById('stage');
                    if (!canvas) return false;
                    const ctx = canvas.getContext('2d');
                    const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
                    let nonBlack = 0;
                    for (let i = 0; i < data.length; i += 40) {
                        if (data[i] > 10 || data[i+1] > 10 || data[i+2] > 10) nonBlack++;
                    }
                    return nonBlack > 100;
                });

                if (hasContent) {
                    hotelReady = true;
                    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
                    console.log(`  Hotel view ready (frame=${state.frame}, tick=${state.tick}, ${elapsed}s)`);
                    break;
                }
            }

            if (i % 100 === 0 && i > 0) {
                console.log(`  Waiting... tick=${state.tick} frame=${state.frame} loaded=${state.loaded}`);
            }
        }

        if (!hotelReady) {
            console.error('FAIL: Hotel view did not appear within timeout');
            await captureCanvas(page, path.join(outputDir, 'timeout_hotel.png'));
            process.exitCode = 1;
            try { fs.unlinkSync(htmlPath); } catch(e) {}
            return;
        }

        // Let more frames render for navigator to appear after SSO login
        await new Promise(r => setTimeout(r, 3000));
        await captureCanvas(page, path.join(outputDir, '01_hotel_view.png'));
        console.log('  Saved hotel view screenshot');

        // ----- Step 2: Wait for navigator window to appear -----
        const beforeNav = await sampleCanvasRegion(page, NAV_REGION_X, NAV_REGION_Y, NAV_REGION_W, NAV_REGION_H);

        let navigatorAppeared = false;
        let colorVariety = 0;
        const navStart = Date.now();
        let captureIdx = 0;

        while (Date.now() - navStart < NAVIGATOR_TIMEOUT_MS) {
            await new Promise(r => setTimeout(r, 2000));

            colorVariety = await measureColorVariety(page, NAV_REGION_X, NAV_REGION_Y, NAV_REGION_W, NAV_REGION_H);
            const afterNav = await sampleCanvasRegion(page, NAV_REGION_X, NAV_REGION_Y, NAV_REGION_W, NAV_REGION_H);
            const change = computeChangeFraction(beforeNav, afterNav);
            const sec = ((Date.now() - navStart) / 1000).toFixed(0);
            console.log(`  Navigator poll +${sec}s: variety=${colorVariety} change=${(change * 100).toFixed(1)}%`);

            if (captureIdx < 8) {
                await captureCanvas(page, path.join(outputDir, `02_nav_poll_${String(captureIdx).padStart(2,'0')}.png`));
                captureIdx++;
            }

            if (colorVariety >= CONTENT_VARIETY_THRESHOLD) {
                navigatorAppeared = true;
                break;
            }
        }

        // Final captures
        await captureCanvas(page, path.join(outputDir, '03_navigator_final.png'));

        // ----- Step 3: Analyse navigator colours -----
        const colours = await analyseNavigatorColours(page, NAV_REGION_X, NAV_REGION_Y, NAV_REGION_W, NAV_REGION_H);
        if (colours) {
            const pct = (n) => (n / colours.total * 100).toFixed(1);
            console.log('\n--- Navigator Colour Analysis ---');
            console.log(`  Teal (headers):    ${colours.teal} (${pct(colours.teal)}%)`);
            console.log(`  Orange (buttons):  ${colours.orange} (${pct(colours.orange)}%)`);
            console.log(`  White:             ${colours.white} (${pct(colours.white)}%)`);
            console.log(`  Gray:              ${colours.gray} (${pct(colours.gray)}%)`);
            console.log(`  Black:             ${colours.black} (${pct(colours.black)}%)`);
            console.log(`  Other:             ${colours.other} (${pct(colours.other)}%)`);
            console.log(`  Total pixels:      ${colours.total}`);

            // Expected: reference has significant teal (header bars) and orange (Open buttons)
            const hasTeal = colours.teal > colours.total * 0.01;    // >1% teal
            const hasOrange = colours.orange > colours.total * 0.005; // >0.5% orange
            const hasWhite = colours.white > colours.total * 0.05;   // >5% white (list bg)

            if (hasTeal && hasOrange && hasWhite) {
                console.log('\n  Colour profile: MATCHES reference (teal headers + orange buttons + white lists)');
            } else {
                console.log('\n  Colour profile: DOES NOT MATCH reference');
                if (!hasTeal) console.log('    MISSING: Teal header bars (expected >1%)');
                if (!hasOrange) console.log('    MISSING: Orange "Open" buttons (expected >0.5%)');
                if (!hasWhite) console.log('    MISSING: White list background (expected >5%)');
            }
        }

        // Debug: read bitmap debug counters from WASM
        const bmpCounters = await page.evaluate(() => {
            if (window.player && window.player._wasmExports && window.player._wasmExports.getBmpDebugCounters) {
                return window.player._wasmExports.getBmpDebugCounters();
            }
            return -1;
        });
        if (bmpCounters >= 0) {
            const c1x1 = (bmpCounters >> 20) & 0x3FF;
            const cSet = (bmpCounters >> 10) & 0x3FF;
            const cFail = bmpCounters & 0x3FF;
            console.log('\n--- Bitmap Debug Counters ---');
            console.log('  1x1 defaults created: ' + c1x1);
            console.log('  setBitmapProp(image) success: ' + cSet);
            console.log('  setBitmapProp(image) fail (non-ImageRef): ' + cFail);
        }

        // Sample grey bar area: should be at approximately x=360-690, y=155-175 (first category row)
        const greyBarSample = await page.evaluate(() => {
            const canvas = document.getElementById('stage');
            if (!canvas) return null;
            const ctx = canvas.getContext('2d');
            // Sample a horizontal strip where the first grey bar should be
            const data = ctx.getImageData(365, 160, 300, 12).data;
            const colorCounts = {};
            for (let i = 0; i < data.length; i += 4) {
                const r = data[i], g = data[i+1], b = data[i+2];
                const key = r + ',' + g + ',' + b;
                colorCounts[key] = (colorCounts[key] || 0) + 1;
            }
            // Sort by frequency and return top 5
            const sorted = Object.entries(colorCounts).sort((a,b) => b[1] - a[1]);
            return sorted.slice(0, 8).map(e => '  rgb(' + e[0] + ') x' + e[1]);
        });
        if (greyBarSample) {
            console.log('\n--- Grey Bar Area Pixel Analysis (x=365-665, y=160-172) ---');
            greyBarSample.forEach(s => console.log(s));
        }

        // Dump sprite state for debugging ink issues
        const spriteDump = await page.evaluate(async () => {
            if (window.player && window.player.debugHitTest) {
                return await player.debugHitTest(-1, 0);
            }
            return { info: 'debugHitTest not available' };
        });
        console.log(`\n  [sprite dump] ${spriteDump.info}`);

        // ----- Results -----
        const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        console.log('\n--- Results ---');
        console.log('Navigator appeared: ', navigatorAppeared ? 'YES' : 'NO');
        console.log('Color variety:      ', colorVariety);
        console.log('Output dir:         ', outputDir);
        console.log('Elapsed:            ', totalElapsed + 's');

        if (navigatorAppeared) {
            const colourOk = colours && colours.teal > colours.total * 0.01
                          && colours.orange > colours.total * 0.005;
            if (colourOk) {
                console.log('\nPASS: Navigator appeared with correct colour profile');
            } else {
                console.log('\nFAIL: Navigator appeared but ink colours are wrong');
                console.log('  Compare screenshots in ' + outputDir + ' with docs/habbo-reference.png');
                process.exitCode = 1;
            }
        } else {
            console.log('\nFAIL: Navigator did not appear (SSO login may have failed)');
            console.log('  Check screenshots in ' + outputDir);
            process.exitCode = 1;
        }

        try { fs.unlinkSync(htmlPath); } catch(e) {}

    } finally {
        if (browser) await browser.close();
        server.close();
    }
}

main().catch(err => {
    console.error('FATAL:', err);
    process.exit(1);
});
