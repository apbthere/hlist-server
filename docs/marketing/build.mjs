// Builds the LinkedIn visuals in this folder from a real HList build: a throwaway server with demo data, the mobile
// client in WebKit at iPhone/iPad size, and a real Claude Code session. See README.md for what it makes.
//
//   node docs/marketing/build.mjs
//
// Needs: the server jar (./mvnw -q -DskipTests package), the client next to this repository with npm ci and
// npm run build done (its e2e harness and Playwright are used), Claude Code signed in (CLAUDE_BIN to point at it),
// Swift (Xcode command line tools) for the MP4, and the internet (product photos, store icons, costco.com).
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const clientDir = resolve(process.env.HLIST_CLIENT_DIR ?? join(here, "../../../hlist-client"));
const { apiFetch, baseUrl, signUp, startServer } = await import(pathToFileURL(join(clientDir, "e2e/harness.mjs")));
const { webkit, chromium } = await import(pathToFileURL(join(clientDir, "node_modules/playwright-core/index.mjs")));
const claudeBin = process.env.CLAUDE_BIN ?? "claude";
const prompt = process.env.DEMO_PROMPT ?? "Add Bounty paper towels and the usual oat milk to my Costco list";
const pastedLink = "https://www.costco.com/p/-/bounty-advanced-paper-towels-2-ply-103-sheets-12-count/4000346087";
const work = mkdtempSync(join(tmpdir(), "hlist-marketing-"));

const green = "#248a3d";
const iPhone = { viewport: { width: 393, height: 852 }, deviceScaleFactor: 3, hasTouch: true, colorScheme: "light" };
const iPad = { viewport: { width: 820, height: 1180 }, deviceScaleFactor: 2, hasTouch: true, colorScheme: "light" };
const user = { username: "taylor", password: "demo-password" };

// ---------------------------------------------------------------------------------------------------- demo data

const departments = ["Produce", "Dairy & Eggs", "Bakery", "Meat & Seafood", "Pantry", "Household", "Frozen"];
// [list, completed, [name, brand, department, quantity, comment, checked off]]
const lists = [
  ["Costco, Oct 4", true, [
    ["Oat Milk", "Oatly", "Dairy & Eggs", 2, "Full fat"],
    ["Salmon Fillets", "Kirkland Signature", "Meat & Seafood", 1],
    ["Blueberries", null, "Produce", 1],
  ]],
  ["Target", false, [
    ["Laundry Detergent", "Tide", "Household", 1],
    ["Sparkling Water", "LaCroix", "Pantry", 2, "Lime"],
  ]],
  ["Publix Weekly", false, [
    ["Bananas", null, "Produce", 6],
    ["Sourdough Bread", null, "Bakery", 1],
    ["Orange Juice", "Tropicana", "Dairy & Eggs", 1, "No pulp"],
    ["Ground Coffee", "Starbucks", "Pantry", 1],
  ]],
  ["Costco Run", false, [
    ["Strawberries", null, "Produce", 1],
    ["Avocados", null, "Produce", 1, "Bag of 5"],
    ["Greek Yogurt", "Chobani", "Dairy & Eggs", 2, "Plain, 32 oz"],
    ["Cheddar Cheese", "Tillamook", "Dairy & Eggs", 1],
    ["Peanut Butter", "Jif", "Pantry", 1, "Creamy"],
    ["Eggs", null, "Dairy & Eggs", 2, "", true],
  ]],
];

async function seed(page) {
  const call = async (path, method, body, expected) => {
    const response = await apiFetch(page, path, { method, body });
    if (expected && response.status !== expected) throw new Error(`${method} ${path}: ${response.status} ${JSON.stringify(response.body)}`);
    return response.body;
  };
  const departmentIds = {};
  for (const name of departments) departmentIds[name] = (await call("/api/departments", "POST", { name }, 201)).departmentId;
  const brandIds = {};
  const ids = {};
  for (const [listName, completed, items] of lists) {
    const list = await call("/api/shopping-lists", "POST", { name: listName }, 201);
    ids[listName] = list.shoppingListId;
    for (const [name, brand, department, quantity, comment, checked] of items) {
      if (brand && !brandIds[brand]) brandIds[brand] = (await call("/api/brands", "POST", { name: brand }, 201)).brandId;
      const item = await call(`/api/shopping-lists/${list.shoppingListId}/items`, "POST", {
        name, quantity, departmentId: departmentIds[department], brandId: brandIds[brand] ?? null, comment,
      }, 201);
      ids[name] = item.itemId;
      await attachPhoto(page, call, item.itemId, name, brand);
      if (checked) await call(`/api/shopping-lists/${list.shoppingListId}/items/${item.itemId}/completion`, "PATCH", { completed: true });
    }
    if (completed) await call(`/api/shopping-lists/${list.shoppingListId}/completion`, "PATCH", { completed: true });
  }
  return ids;
}

/** The first Open Food Facts photo for the item; Open Food Facts is sometimes busy, so it tries a few times. */
async function attachPhoto(page, call, itemId, name, brand) {
  const query = new URLSearchParams({ name, ...(brand ? { brand } : {}) });
  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt) await page.waitForTimeout(3000);
    const suggestions = await call(`/api/photos/suggestions?${query}`, "GET");
    for (const suggestion of Array.isArray(suggestions) ? suggestions.slice(0, 3) : []) {
      const photo = await apiFetch(page, "/api/photos/import", { method: "POST", body: { url: suggestion.imageUrl } });
      if (photo.status !== 201) continue;
      await call(`/api/items/${itemId}/photo`, "PUT", { photoId: photo.body.photoId }, 204);
      return;
    }
  }
  if (name === "Oat Milk") throw new Error("No photo for Oat Milk (Open Food Facts busy?); try again later.");
  console.warn(`no photo for ${name}`);
}

async function settled(page) {
  await page.waitForLoadState("networkidle");
  await page.waitForFunction(() => [...document.images].every((image) => image.complete));
  await page.waitForTimeout(700); // sheet and list animations
}

// ---------------------------------------------------------------------------------------------------- app captures

/** Screens of the real app, saved in the work folder; returns their paths. */
async function captureScreens(browser, storageState, ids) {
  const shots = {};
  const shoot = async (page, name) => { shots[name] = join(work, `${name}.png`); await page.screenshot({ path: shots[name] }); };
  const phone = await (await browser.newContext({ ...iPhone, storageState })).newPage();

  await phone.goto(`${baseUrl}/app/lists`);
  await phone.getByText("Costco Run").waitFor();
  await settled(phone);
  await shoot(phone, "lists");

  await phone.getByText("Costco Run").click();
  await phone.getByText("Greek Yogurt").waitFor();
  await settled(phone);
  await shoot(phone, "list-items");

  // Pasting a costco.com link into a new item's name fills in its name, brand and photo. Not saved, so the
  // assistant demo later adds paper towels as a new product.
  await phone.getByRole("button", { name: "New Item" }).click();
  const name = phone.getByLabel("Item name").first();
  await name.waitFor();
  await name.evaluate((input, link) => {
    input.focus();
    const data = new DataTransfer();
    data.setData("text/plain", link);
    input.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
  }, pastedLink);
  await phone.getByRole("button", { name: "Show photo full screen" }).waitFor({ timeout: 60_000 });
  await phone.waitForFunction(() => /towel/i.test(document.querySelector("input[aria-label='Item name']")?.value ?? ""));
  await name.evaluate((input) => input.blur());
  await settled(phone);
  await shoot(phone, "item-editor");
  await phone.getByRole("button", { name: "Cancel" }).first().click();
  await phone.waitForTimeout(800);

  // Live updates: check an item off on the iPhone; the iPad, on the same list, updates by itself.
  const tablet = await (await browser.newContext({ ...iPad, storageState })).newPage();
  await tablet.goto(`${baseUrl}/app/lists/${ids["Costco Run"]}`);
  await tablet.getByText("Strawberries").waitFor();
  await settled(tablet);
  await phone.getByRole("button", { name: "Mark Strawberries as bought", exact: true }).click();
  await tablet.getByRole("button", { name: "Mark Strawberries as not bought", exact: true }).waitFor({ timeout: 10_000 });
  await settled(phone);
  await settled(tablet);
  await shoot(phone, "live-phone");
  await shoot(tablet, "live-tablet");
  await apiFetch(phone, `/api/shopping-lists/${ids["Costco Run"]}/items/${ids["Strawberries"]}/completion`,
    { method: "PATCH", body: { completed: false } });
  await phone.context().close();
  await tablet.context().close();
  return shots;
}

async function captureAccount(browser, storageState, shots) {
  const phone = await (await browser.newContext({ ...iPhone, storageState })).newPage();
  await phone.goto(`${baseUrl}/app/lists`);
  await phone.getByRole("button", { name: "Account" }).click();
  await phone.getByText("Connect an Assistant").waitFor();
  await phone.getByText("AI Assistants").evaluate((header) => header.scrollIntoView({ block: "start" }));
  await settled(phone);
  shots.account = join(work, "account.png");
  await phone.screenshot({ path: shots.account });
  await phone.context().close();
}

// ---------------------------------------------------------------------------------------------------- shared look

const css = `
  * { box-sizing: border-box; }
  body { margin: 0; font-family: -apple-system, "SF Pro Display", system-ui, sans-serif; color: #1c1c1e;
    -webkit-font-smoothing: antialiased; }
  .canvas { position: relative; overflow: hidden; background: linear-gradient(160deg, #f6faf7 0%, #e3f1e7 100%); }
  .headline { font-weight: 800; letter-spacing: -0.02em; line-height: 1.08; }
  .headline em { font-style: normal; color: ${green}; }
  .brand { position: absolute; display: flex; align-items: center; gap: 12px; font-weight: 800; letter-spacing: 0.04em; }
  .brand i { display: inline-grid; place-items: center; border-radius: 50%; background: ${green}; color: #fff;
    font-style: normal; font-weight: 800; }
  .phone { position: relative; background: #1c1c1e; border-radius: 56px; padding: 13px;
    box-shadow: 0 30px 60px rgba(16, 60, 30, .22), 0 6px 14px rgba(0,0,0,.12); }
  .phone img, .phone iframe { display: block; width: 100%; border-radius: 44px; border: 0; background: #f2f2f7; }
  .tablet { background: #1c1c1e; border-radius: 34px; padding: 16px;
    box-shadow: 0 30px 60px rgba(16, 60, 30, .22), 0 6px 14px rgba(0,0,0,.12); }
  .tablet img { display: block; width: 100%; border-radius: 20px; }
  .terminal { background: #1e1f24; color: #e6e6e6; border-radius: 16px; overflow: hidden;
    box-shadow: 0 30px 60px rgba(16, 60, 30, .22), 0 6px 14px rgba(0,0,0,.12); display: flex; flex-direction: column; }
  .terminal .bar { flex: none; height: 36px; background: #2c2d33; display: flex; align-items: center; gap: 8px; padding: 0 14px; }
  .terminal .bar i { width: 13px; height: 13px; border-radius: 50%; background: #ff5f57; }
  .terminal .bar i:nth-child(2) { background: #febc2e; } .terminal .bar i:nth-child(3) { background: #28c840; }
  .terminal .bar span { margin: 0 auto; color: #9a9ba3; font-size: 14px; }
  .terminal .body { flex: 1; overflow: hidden; display: flex; flex-direction: column; justify-content: flex-end;
    padding: 16px 20px; font-family: ui-monospace, Menlo, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
  .terminal .body div { margin-bottom: 8px; }
  .terminal .you { color: #fff; font-weight: 600; } .terminal .tool { color: #7cc7ff; }
  .terminal .dim { color: #9a9ba3; margin-top: -6px; } .terminal .ok { color: #8ee28e; margin-top: -6px; }
  .terminal .text { color: #f2f2f2; } .terminal .status { color: #d9a35b; }
`;

const dataUri = (path) => `data:image/png;base64,${readFileSync(path).toString("base64")}`;
const brand = (size, style) => `<div class="brand" style="font-size:${size}px;${style}"><i style="width:${size * 1.6}px;height:${size * 1.6}px">H</i>HList</div>`;
const page = (width, height, body) => `<!doctype html><meta charset="utf-8"><style>${css}</style>
  <div class="canvas" style="width:${width}px;height:${height}px">${body}</div>`;

async function render(browser, html, width, height, out, { scale = 1, type = "png" } = {}) {
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: scale, colorScheme: "light" });
  const tab = await context.newPage();
  await tab.setContent(html, { waitUntil: "load" });
  await tab.evaluate(() => document.fonts.ready);
  await tab.screenshot({ path: out, type, ...(type === "jpeg" ? { quality: 88 } : {}) });
  await context.close();
  return out;
}

// ---------------------------------------------------------------------------------------------------- architecture

function architectureSvg() {
  const box = (x, y, w, h, title, sub, { fill = "#ffffff", stroke = "#d6dbd8", color = "#1c1c1e" } = {}) => `
    <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="18" fill="${fill}" stroke="${stroke}" stroke-width="2"/>
    <text x="${x + w / 2}" y="${y + h / 2 - 6}" text-anchor="middle" font-size="30" font-weight="700" fill="${color}">${title}</text>
    <text x="${x + w / 2}" y="${y + h / 2 + 30}" text-anchor="middle" font-size="22" fill="#6e6e73">${sub}</text>`;
  const arrow = (x1, y1, x2, y2, { color = "#8e8e93", dashed = false } = {}) =>
    `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${color}" stroke-width="3" ${dashed ? 'stroke-dasharray="10 8"' : ""}
      marker-end="url(#${color === green ? "head-green" : "head"})"/>`;
  const label = (x, y, text, { anchor = "start", color = "#3a3a3c", weight = 600 } = {}) =>
    `<text x="${x}" y="${y}" text-anchor="${anchor}" font-size="22" font-weight="${weight}" fill="${color}">${text}</text>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1000" viewBox="0 0 1600 1000"
    font-family="-apple-system, 'SF Pro Display', 'Helvetica Neue', Arial, sans-serif">
  <defs>
    <marker id="head" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 10 5 0 10z" fill="#8e8e93"/></marker>
    <marker id="head-green" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 10 5 0 10z" fill="${green}"/></marker>
  </defs>
  <rect width="1600" height="1000" fill="#ffffff"/>
  ${box(60, 40, 440, 130, "iPhone &amp; iPad app", "Vue 3 + Ionic, installable PWA")}
  ${box(580, 40, 440, 130, "Desktop web", "HTML + JavaScript")}
  ${box(1100, 40, 440, 130, "AI assistants", "Claude · ChatGPT · Grok", { fill: "#eef7f0", stroke: green })}
  ${arrow(250, 172, 250, 326)}
  ${arrow(310, 328, 310, 174, { color: green, dashed: true })}
  ${label(325, 262, "Live updates (SSE)", { color: green })}
  ${arrow(800, 172, 800, 326)}
  ${label(815, 262, "Cookie + CSRF")}
  ${arrow(1320, 172, 1320, 326, { color: green })}
  ${label(1305, 240, "MCP over HTTPS", { anchor: "end", color: green })}
  ${label(1305, 272, "OAuth 2.1 + PKCE", { anchor: "end", color: green })}
  <rect x="60" y="330" width="1480" height="420" rx="24" fill="#f6faf7" stroke="${green}" stroke-width="3"/>
  <path d="M60 354a24 24 0 0 1 24-24h1432a24 24 0 0 1 24 24v36H60z" fill="${green}"/>
  <text x="800" y="371" text-anchor="middle" font-size="26" font-weight="700" fill="#ffffff">Spring Boot 4 · Java 25 · one origin</text>
  ${box(100, 420, 320, 120, "Spring Security 7", "Passwords · passkeys")}
  ${box(460, 420, 320, 120, "REST API", "/api · JSON")}
  ${box(820, 420, 320, 120, "MCP server", "8 tools · /mcp")}
  ${box(1180, 420, 320, 120, "OAuth 2.1 server", "Sign-in · consent")}
  ${box(100, 580, 440, 130, "Services", "Lists · items · photos · stores")}
  ${box(580, 580, 440, 130, "Live updates", "Events after commit → SSE")}
  ${box(1060, 580, 440, 130, "SafeDownloader", "HTTPS · public IPs · limits")}
  ${arrow(542, 645, 576, 645)}
  ${arrow(320, 712, 320, 816)}
  ${arrow(1280, 712, 1280, 816)}
  ${box(100, 820, 440, 120, "H2 database", "Spring Data JDBC")}
  ${box(1060, 820, 440, 120, "The internet", "Store sites · Open Food Facts")}
  ${label(1540, 985, "github.com/apbthere/hlist-server", { anchor: "end", color: "#8e8e93", weight: 400 })}
</svg>`;
}

// ---------------------------------------------------------------------------------------------------- assistant demo

/** Short, readable versions of what the tools returned, for the terminal. */
function summarize(tool, input, result) {
  try {
    const data = JSON.parse(result);
    if (tool === "list_shopping_lists") return { text: data.map((list) => `${list.name} · ${list.itemsLeft} to buy`).join(", ") || "No lists" };
    if (tool === "search_item_history") return { text: data.length ? `${data[0].name} · ${data[0].brand ?? "no brand"} · bought before` : "Nothing bought before" };
    if (tool === "get_list_items") return { text: `${data.length} items, no paper towels or oat milk` };
    if (tool === "add_item_to_list") {
      return { text: `${data.message.replace(/\.$/, "")}: ${data.item.name}${data.item.brand ? ` (${data.item.brand})` : ""}${data.item.hasPhoto ? " · with photo" : ""}`, cls: "ok" };
    }
    if (tool === "list_departments") return { text: `${data.length} departments` };
  } catch { /* not JSON */ }
  if (tool === "WebSearch") {
    const link = /https:\/\/www\.costco\.com\/[^\s"')\]]+/.exec(result)?.[0];
    return { text: link ? `costco.com/…${link.slice(-36)}` : "Done" };
  }
  return { text: result.split("\n")[0].slice(0, 70) };
}

function captionFor(tool) {
  return {
    list_shopping_lists: "Claude finds your Costco list",
    search_item_history: "…remembers what you usually buy",
    WebSearch: "…looks up Bounty on costco.com",
    add_item_to_list: "Added live, with product photos",
  }[tool];
}

function stageHtml(ids) {
  return `<!doctype html><meta charset="utf-8"><title>demo</title><style>${css}
  .caption { position: absolute; left: 0; right: 0; top: 0; height: 160px; display: grid; place-items: center;
    font-size: 52px; font-weight: 800; letter-spacing: -0.02em; text-align: center; padding: 0 40px; }
  .note { position: absolute; left: 40px; bottom: 22px; font-size: 18px; color: #6e6e73; }
  .terminal { position: absolute; left: 40px; top: 170px; width: 580px; height: 840px; }
  .terminal .body { font-size: 20px; line-height: 1.45; }
  .phone { position: absolute; left: 650px; top: 170px; width: 390px; height: 840px; }
  .screen { width: 364px; height: 814px; overflow: hidden; border-radius: 44px; }
  .screen iframe { width: 393px; height: 879px; transform: scale(0.926); transform-origin: 0 0; border-radius: 0; }
  </style>
  <div class="canvas" style="width:1080px;height:1080px">
    <div class="caption" id="caption">Just ask Claude.</div>
    <div class="terminal"><div class="bar"><i></i><i></i><i></i><span>Claude Code</span></div><div class="body" id="out"></div></div>
    <div class="phone"><div class="screen"><iframe src="/app/lists/${ids["Costco Run"]}"></iframe></div></div>
    <div class="note">Real session · HList demo data · pauses shortened</div>
  </div>
  <script>
    const out = document.getElementById("out");
    let status = null;
    window.line = (text, cls) => {
      const div = document.createElement("div"); div.className = cls; div.textContent = text;
      if (status) out.insertBefore(div, status); else out.append(div);
    };
    window.working = (on) => {
      if (on && !status) { status = document.createElement("div"); status.className = "status"; status.textContent = "✻ Working…"; out.append(status); }
      if (!on && status) { status.remove(); status = null; }
    };
    window.caption = (text) => { document.getElementById("caption").textContent = text; };
    window.type = async (text) => {
      const div = document.createElement("div"); div.className = "you"; div.textContent = "> "; out.append(div);
      for (const char of text) { div.textContent += char; await new Promise((done) => setTimeout(done, 45)); }
    };
  </script>`;
}

/**
 * Runs Claude Code for real against the demo server while recording the stage (terminal + live app) frame by
 * frame. Returns the frames and the terminal transcript.
 */
async function recordDemo(browser, storageState, ids, token) {
  const context = await browser.newContext({ viewport: { width: 1080, height: 1080 }, colorScheme: "light", storageState });
  const stage = await context.newPage();
  await stage.route(`${baseUrl}/marketing-stage`, (route) => route.fulfill({ contentType: "text/html", body: stageHtml(ids) }));
  await stage.goto(`${baseUrl}/marketing-stage`);
  await stage.frameLocator("iframe").getByText("Greek Yogurt").waitFor();
  await settled(stage);

  const frames = []; // { path, at (ms), hold }
  let recording = true;
  let previous = null;
  const started = Date.now();
  const recorder = (async () => {
    while (recording) {
      const png = await stage.screenshot().catch(() => null);
      if (!png) break;
      const hash = createHash("sha1").update(png).digest("hex");
      if (hash !== previous) {
        const path = join(work, `frame-${String(frames.length).padStart(5, "0")}.png`);
        writeFileSync(path, png);
        frames.push({ path, at: Date.now() - started });
        previous = hash;
      }
    }
  })();

  const transcript = [];
  const show = async (text, cls) => { transcript.push({ text, cls }); await stage.evaluate(([t, c]) => window.line(t, c), [text, cls]); };
  const config = { mcpServers: { hlist: { type: "http", url: `${baseUrl}/mcp`, headers: { Authorization: `Bearer ${token}` } } } };
  const tools = ["list_shopping_lists", "get_list_items", "search_item_history", "add_item_to_list", "list_departments"]
    .map((tool) => `mcp__hlist__${tool}`).concat("WebSearch");
  const events = [];
  const toolNames = {};
  let added = 0;

  /** One turn of the conversation: types the request, runs Claude Code (resuming the session) and shows its work. */
  async function turn(text, sessionId) {
    transcript.push({ text: `> ${text}`, cls: "you" });
    await stage.evaluate((typed) => window.type(typed), text);
    await stage.waitForTimeout(600);
    await stage.evaluate(() => window.working(true));
    const claude = spawn(claudeBin, ["-p", text, ...(sessionId ? ["--resume", sessionId] : []),
      "--mcp-config", JSON.stringify(config), "--strict-mcp-config", "--allowedTools", tools.join(","),
      "--output-format", "stream-json", "--verbose"], { cwd: work });
    let stderr = "";
    claude.stderr.on("data", (chunk) => { stderr += chunk; });
    let session = sessionId;
    let lastText = "";
    for await (const raw of createInterface({ input: claude.stdout })) {
      if (!raw.trim()) continue;
      const event = JSON.parse(raw);
      events.push(event);
      session ??= event.session_id;
      if (event.type !== "assistant" && event.type !== "user") continue;
      for (const part of Array.isArray(event.message.content) ? event.message.content : []) {
        if (part.type === "tool_use") {
          const tool = part.name.replace(/^mcp__hlist__/, "");
          if (tool === "ToolSearch") continue;
          toolNames[part.id] = { tool, input: part.input };
          const caption = captionFor(tool);
          if (caption) await stage.evaluate((words) => window.caption(words), caption);
          const args = tool === "WebSearch" ? JSON.stringify(part.input.query)
            : Object.entries(part.input).map(([key, value]) => `${key}: ${JSON.stringify(value)}`).join(", ");
          await show(tool === "WebSearch" ? `● Web Search(${args})` : `● hlist - ${tool}(${args})`, "tool");
        } else if (part.type === "tool_result" && toolNames[part.tool_use_id]) {
          const { tool, input } = toolNames[part.tool_use_id];
          const body = typeof part.content === "string" ? part.content : part.content.map((item) => item.text ?? "").join("");
          if (tool === "add_item_to_list" && /"outcome":"added"/.test(body)) added++;
          const { text: summary, cls = "dim" } = summarize(tool, input, body);
          await show(`  ⎿  ${summary}`, cls);
        } else if (part.type === "text" && part.text.trim()) {
          lastText = part.text.replace(/\*\*/g, "").replace(/\[([^\]]+)\]\([^)]+\)/g, "$1").trim();
          await show(`● ${lastText}`, "text");
        }
      }
    }
    const exitCode = await new Promise((done) => (claude.exitCode !== null ? done(claude.exitCode) : claude.once("exit", done)));
    writeFileSync(join(work, "claude-code.jsonl"), events.map((event) => JSON.stringify(event)).join("\n"));
    if (exitCode !== 0) throw new Error(`Claude Code exited ${exitCode}: ${stderr}`);
    await stage.evaluate(() => window.working(false));
    return { session, lastText };
  }

  try {
    const first = await turn(prompt);
    // HList tells assistants to ask when several products fit (Costco sells more than one Bounty pack), so the
    // demo answers the way a person would.
    if (added < 2 && first.lastText.includes("?")) {
      await stage.waitForTimeout(1500);
      await stage.evaluate((words) => window.caption(words), "It asks instead of guessing");
      await stage.waitForTimeout(1500);
      await turn(process.env.DEMO_ANSWER ?? "The first one, please.", first.session);
    }
    if (added < 2) throw new Error(`Claude added ${added} items, not 2; see ${work}/claude-code.jsonl`);
  } catch (error) {
    recording = false;
    await recorder.catch(() => {});
    throw error;
  }

  await stage.evaluate(() => window.working(false));
  await stage.waitForTimeout(500);
  await settled(stage); // the new items' photos
  await stage.frameLocator("iframe").getByText("Paper Towels").waitFor();
  await stage.waitForTimeout(2500);
  recording = false;
  await recorder;
  await context.close();
  // The last frame: terminal and app with the new items.
  return { frames, transcript, lastAppFrame: frames.at(-1).path };
}

/**
 * Turns the recorded frames into the video's timeline. Long pauses (the model thinking) are cut to 1.2 s; the
 * finished list holds for 4.5 s, then the end card for 4.5 s.
 */
function timeline(frames, endCard) {
  const plan = [];
  let at = 0;
  for (const [index, frame] of frames.entries()) {
    plan.push({ path: frame.path, seconds: at });
    const next = frames[index + 1];
    at += next ? Math.min((next.at - frame.at) / 1000, 1.2) : 4.5;
  }
  plan.push({ path: endCard, seconds: at });
  return { frames: plan, end: at + 4.5 };
}

function endCardHtml() {
  return page(1080, 1080, `<div style="position:absolute;inset:0;display:grid;place-items:center;text-align:center"><div>
    ${brand(40, "position:static;justify-content:center;margin-bottom:34px")}
    <div class="headline" style="font-size:76px">A shopping list<br>your AI can use</div>
    <div style="margin-top:28px;font-size:30px;color:${green};font-weight:700">github.com/apbthere/hlist-server</div></div></div>`);
}

// ---------------------------------------------------------------------------------------------------- layouts

function heroHtml(transcript, finalFrame) {
  const lines = transcript.map(({ text, cls }) => `<div class="${cls}">${text.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c])}</div>`).join("");
  return page(1200, 627, `
    ${brand(22, "left:48px;top:44px")}
    <div class="headline" style="position:absolute;left:48px;top:150px;width:400px;font-size:43px">
      A self-hosted shopping list <em>your AI assistant can use</em></div>
    <div class="terminal" style="position:absolute;left:470px;top:60px;width:420px;height:507px">
      <div class="bar"><i></i><i></i><i></i><span>Claude Code</span></div>
      <div class="body" style="font-size:13.5px;line-height:1.4;-webkit-mask-image:linear-gradient(to bottom,transparent 0,#000 48px)">${lines}</div></div>
    <div class="phone" style="position:absolute;left:910px;top:28px;width:262px;padding:9px;border-radius:40px">
      <div style="height:552px;overflow:hidden;border-radius:32px">
        <img src="${dataUri(finalFrame.phone)}" style="width:100%;border-radius:0"></div></div>`);
}

/** A feature still: headline over one or two device screenshots, sized to fit. */
function stillHtml(width, height, headline, devices) {
  const tall = height > width;
  const top = tall ? 290 : 200; // below a two-line headline
  const bottom = tall ? 120 : 100;
  const gap = 36;
  // Frame widths (padding included) for a given screen height.
  const widthFor = (kind, h) => (kind === "tablet" ? h / 1.439 + 32 : h / 2.168 + 26);
  let room = height - top - bottom - 26;
  const total = (h) => devices.reduce((sum, { kind }) => sum + widthFor(kind, h), 0) + gap * (devices.length - 1);
  while (total(room) > width - 120) room -= 10;
  const deviceHtml = devices.map(({ kind, path }) => (kind === "tablet"
    ? `<div class="tablet" style="width:${Math.round(widthFor(kind, room))}px"><img src="${dataUri(path)}"></div>`
    : `<div class="phone" style="width:${Math.round(widthFor(kind, room))}px"><img src="${dataUri(path)}"></div>`)).join("");
  return page(width, height, `
    <div class="headline" style="position:absolute;left:70px;right:70px;top:${tall ? 80 : 60}px;font-size:${tall ? 68 : 60}px;text-align:center">${headline}</div>
    <div style="position:absolute;left:0;right:0;top:${top}px;bottom:${bottom}px;display:flex;justify-content:center;align-items:center;gap:${gap}px">${deviceHtml}</div>
    ${brand(26, `right:44px;bottom:36px`)}`);
}

function slideHtml(number, total, headline, body) {
  return page(1080, 1350, `
    <div class="headline" style="position:absolute;left:80px;right:80px;top:100px;font-size:76px">${headline}</div>
    ${body}
    ${brand(28, "left:80px;bottom:60px")}
    <div style="position:absolute;right:80px;bottom:66px;font-size:26px;color:#8e8e93;font-weight:600">${number} / ${total}</div>`);
}

// ---------------------------------------------------------------------------------------------------- main

const outputs = [];
const output = (name) => { const path = join(here, name); outputs.push(path); return path; };

// LAYOUT_ONLY=<work folder from an earlier run> redoes the layouts and the video from that run's captures and
// recording, without a server or another Claude Code session.
const reuse = process.env.LAYOUT_ONLY;
const server = reuse ? null : await startServer();
let browser;
try {
  browser = await webkit.launch();
  let shots, demo;
  if (reuse) {
    ({ shots, demo } = JSON.parse(readFileSync(join(reuse, "state.json"), "utf8")));
  } else {
  const context = await browser.newContext(iPhone);
  const setup = await context.newPage();
  await signUp(setup, user.username, user.password);
  console.log("seeding demo data…");
  const ids = await seed(setup);
  const created = await apiFetch(setup, "/api/users/me/access-tokens", { method: "POST", body: { label: "Claude" } });
  const token = created.body.token;
  const storageState = await context.storageState();

  console.log("capturing screens…");
  shots = await captureScreens(browser, storageState, ids);

  console.log("running Claude Code and recording…");
  demo = await recordDemo(browser, storageState, ids, token);
  await captureAccount(browser, storageState, shots);
  writeFileSync(join(work, "state.json"), JSON.stringify({ shots, demo }));
  }

  // The phone part of the final stage frame, for the hero.
  const phoneFinal = join(work, "phone-final.png");
  const crop = await (await browser.newContext({ viewport: { width: 1080, height: 1080 } })).newPage();
  await crop.setContent(`<img src="${dataUri(demo.lastAppFrame)}">`);
  await crop.screenshot({ path: phoneFinal, clip: { x: 663, y: 183, width: 364, height: 814 } });
  await crop.context().close();

  console.log("encoding the video…");
  const endCard = await render(browser, endCardHtml(), 1080, 1080, join(work, "end-card.png"));
  const plan = timeline(demo.frames, endCard);
  writeFileSync(join(work, "frames.json"), JSON.stringify({ width: 1080, height: 1080, bitrate: 1_600_000, ...plan }));
  const encoded = spawnSync("swift", [join(here, "encode-mp4.swift"), join(work, "frames.json"), output("demo-1080x1080.mp4")], { encoding: "utf8" });
  if (encoded.status !== 0) throw new Error(`encoding failed: ${encoded.stderr}`);

  console.log("laying out images…");
  await render(browser, heroHtml(demo.transcript, { phone: phoneFinal }), 1200, 627, output("hero-1200x627.png"));
  writeFileSync(output("architecture.svg"), architectureSvg());
  await render(browser, `<!doctype html><style>body{margin:0}</style>${architectureSvg()}`, 1600, 1000, output("architecture.png"));

  const features = [
    ["list-items", "Grouped by aisle, <em>with product photos</em>", [{ kind: "phone", path: shots["list-items"] }]],
    ["item-editor", "Paste a store link. <em>Done.</em>", [{ kind: "phone", path: shots["item-editor"] }]],
    ["live-updates", "Check it off. <em>Every device updates.</em>",
      [{ kind: "phone", path: shots["live-phone"] }, { kind: "tablet", path: shots["live-tablet"] }]],
    ["connect-assistant", "Connect <em>Claude, ChatGPT or Grok</em>", [{ kind: "phone", path: shots.account }]],
  ];
  for (const [name, headline, devices] of features) {
    await render(browser, stillHtml(1080, 1080, headline, devices), 1080, 1080, output(`feature-${name}-1080x1080.png`));
    await render(browser, stillHtml(1080, 1350, headline, devices), 1080, 1350, output(`feature-${name}-1080x1350.png`));
  }

  // Carousel: 7 slides at 1080×1350, as JPEGs inside one PDF (LinkedIn document post).
  const phoneTag = (path, width) => `<div class="phone" style="width:${width}px"><img src="${dataUri(path)}"></div>`;
  const center = (html, top) => `<div style="position:absolute;left:0;right:0;top:${top}px;display:flex;justify-content:center;align-items:flex-end;gap:32px">${html}</div>`;
  const stack = [["Java 25", "Spring Boot 4"], ["Spring Security 7", "Passkeys"], ["MCP server", "OAuth 2.1 + PKCE"],
    ["Server-sent events", "Live updates"], ["Vue 3 + Ionic", "Installable PWA"], ["H2 + Spring Data JDBC", "One file"],
    ["Docker + Caddy", "HTTPS on a home NAS"], ["100+ tests", "JUnit, Vitest, Playwright"]];
  const slides = [
    ["Your AI can't see your <em>shopping list.</em>", center(phoneTag(shots.lists, 380), 370)],
    ["Now it can. <em>Just ask.</em>", `<div style="position:absolute;left:50px;top:340px;width:980px;height:800px;overflow:hidden;border-radius:28px;box-shadow:0 20px 40px rgba(16,60,30,.12)">
      <img src="${dataUri(demo.lastAppFrame)}" style="width:980px;margin-top:-140px"></div>`],
    ["Built like an <em>iOS app</em>", center([shots.lists, shots["list-items"], shots["item-editor"]].map((path) => phoneTag(path, 290)).join(""), 420)],
    ["Every device <em>updates instantly</em>", center(phoneTag(shots["live-phone"], 330) + `<div class="tablet" style="width:500px"><img src="${dataUri(shots["live-tablet"])}"></div>`, 380)],
    ["MCP, OAuth 2.1 and <em>live updates</em>", `<img src="${dataUri(join(here, "architecture.png"))}" style="position:absolute;left:60px;top:420px;width:960px;border-radius:20px;box-shadow:0 20px 40px rgba(16,60,30,.12)">`],
    ["The <em>stack</em>", `<div style="position:absolute;left:80px;right:80px;top:330px;display:grid;grid-template-columns:1fr 1fr;gap:24px">${stack.map(([title, sub]) =>
      `<div style="background:#fff;border-radius:22px;padding:30px 30px 26px;box-shadow:0 8px 20px rgba(16,60,30,.08)"><div style="font-size:34px;font-weight:800">${title}</div><div style="font-size:24px;color:#6e6e73;margin-top:6px">${sub}</div></div>`).join("")}</div>`],
    ["Self-hosted and <em>open source</em>", `<div style="position:absolute;left:80px;right:80px;top:400px;font-size:44px;font-weight:700;color:${green}">github.com/apbthere/hlist-server</div>
      <div style="position:absolute;left:80px;top:480px;font-size:30px;color:#6e6e73">MIT license · runs on a home server or NAS</div>
      <div class="terminal" style="position:absolute;left:80px;right:80px;top:600px;height:330px">
        <div class="bar"><i></i><i></i><i></i><span>Terminal</span></div>
        <div class="body" style="font-size:26px;line-height:1.6;justify-content:flex-start"><div class="dim" style="margin:0"># server and app, side by side</div><div>git clone …/hlist-server</div><div>git clone …/hlist-client</div><div>cd hlist-server</div><div class="ok" style="margin:0">docker compose up --build</div></div></div>`],
  ];
  const slidePaths = [];
  for (const [index, [headline, body]] of slides.entries()) {
    slidePaths.push(await render(browser, slideHtml(index + 1, slides.length, headline, body), 1080, 1350,
      join(work, `slide-${index + 1}.jpg`), { type: "jpeg" }));
  }
  const pdfBrowser = await chromium.launch();
  const pdfPage = await pdfBrowser.newPage();
  await pdfPage.setContent(`<!doctype html><style>@page{size:1080px 1350px;margin:0}body{margin:0}img{display:block;width:1080px;height:1350px;page-break-after:always}</style>
    ${slidePaths.map((path) => `<img src="data:image/jpeg;base64,${readFileSync(path).toString("base64")}">`).join("")}`);
  await pdfPage.pdf({ path: output("carousel-1080x1350.pdf"), width: "1080px", height: "1350px", printBackground: true });
  await pdfBrowser.close();
} finally {
  await browser?.close();
  await server?.stop();
}

console.log("\nFile | Size");
for (const path of outputs.filter(existsSync)) console.log(`${path.slice(here.length + 1)} | ${(statSync(path).size / 1024 / 1024).toFixed(2)} MB`);
console.log(`\nWork files (frames, raw captures, Claude Code transcript), safe to delete: ${work}`);
