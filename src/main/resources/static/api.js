// Shared API access for HList pages. Authentication uses the server's session and remember-me cookies;
// no credentials are kept in browser storage.
(() => {
  function readCookie(name) {
    const entry = document.cookie.split("; ").find((part) => part.startsWith(`${name}=`));
    return entry ? decodeURIComponent(entry.slice(name.length + 1)) : null;
  }

  async function csrfToken() {
    if (!readCookie("XSRF-TOKEN")) await fetch("/api/auth/csrf", { credentials: "same-origin" });
    return readCookie("XSRF-TOKEN") || "";
  }

  // Identifies this page load; sent with every request so live-update events about this page's own changes
  // come back tagged with it and can be skipped (see live()).
  const clientId = window.crypto?.randomUUID?.() ?? `client-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;

  async function request(path, options = {}) {
    const method = options.method || "GET";
    const headers = { Accept: "application/json", "X-HList-Client": clientId };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = await csrfToken();
    let body;
    if (options.form !== undefined) {
      body = new URLSearchParams(options.form);
    } else if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(options.body);
    }
    const response = await fetch(path, { method, headers, body, credentials: "same-origin" });
    const text = response.status === 204 ? "" : await response.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch { data = { message: text }; } }
    return { response, data };
  }

  function errorMessage(response, data) {
    return data?.message || `${response.status} ${response.statusText}`;
  }

  async function api(path, options = {}) {
    const { response, data } = await request(path, options);
    if (response.status === 401) {
      window.location.replace("login.html");
      throw new Error("Your session has expired. Please sign in again.");
    }
    if (!response.ok) throw new Error(errorMessage(response, data));
    return data;
  }

  async function login(username, password, rememberMe) {
    const { response, data } = await request("/api/auth/login", {
      method: "POST",
      form: { username, password, "remember-me": rememberMe ? "true" : "false" }
    });
    if (!response.ok) throw new Error(errorMessage(response, data));
  }

  async function register(username, password, inviteCode) {
    const body = inviteCode ? { username, password, inviteCode } : { username, password };
    const { response, data } = await request("/api/users", { method: "POST", body });
    if (!response.ok) throw new Error(errorMessage(response, data));
  }

  // Whether creating an account needs an invite code (set on the server with HLIST_INVITE_CODE).
  async function inviteRequired() {
    const { response, data } = await request("/api/auth/registration");
    return response.ok && data?.inviteRequired === true;
  }

  // Resolves to the signed-in user, or null when there is no valid session or remember-me cookie.
  async function currentUser() {
    const { response, data } = await request("/api/users/me");
    return response.ok ? data : null;
  }

  async function logout() {
    try {
      await request("/api/auth/logout", { method: "POST" });
    } finally {
      sessionStorage.removeItem("hlist-session");
      window.location.replace("login.html");
    }
  }

  // Passkeys (Face ID / Touch ID instead of a password), the same as the mobile app's lib/passkeys.ts.
  const base64url = {
    encode(buffer) {
      let binary = "";
      for (const byte of new Uint8Array(buffer)) binary += String.fromCharCode(byte);
      return btoa(binary).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
    },
    decode(text) {
      const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
      return Uint8Array.from(binary, (char) => char.charCodeAt(0)).buffer;
    }
  };
  const descriptors = (list) => (list || []).map((descriptor) => ({ ...descriptor, id: base64url.decode(descriptor.id) }));
  let passkeySupport;
  let ceremony;

  // Whether this page can use passkeys: browser support, and the server accepts them from this address.
  function passkeysAvailable() {
    passkeySupport ??= (async () => {
      if (!window.isSecureContext || !window.PublicKeyCredential) return false;
      const { response, data } = await request("/api/auth/passkeys");
      return response.ok && data.origins.includes(window.location.origin);
    })().catch(() => false);
    return passkeySupport;
  }

  async function passkeyAutofillAvailable() {
    return await passkeysAvailable() && await PublicKeyCredential.isConditionalMediationAvailable?.().catch(() => false) === true;
  }

  // Dismissing the Face ID sheet, or another request replacing this one.
  function passkeyCancelled(error) {
    return error instanceof DOMException && (error.name === "NotAllowedError" || error.name === "AbortError");
  }

  function newCeremony() {
    ceremony?.abort();
    ceremony = new AbortController();
    return ceremony.signal;
  }

  async function passkeyOptions(path) {
    const { response, data } = await request(path, { method: "POST" });
    if (!response.ok || !data) throw new Error(data?.message || "Can't use passkeys right now. Try again later.");
    return data;
  }

  // With autofill, waits until the passkey is picked in the username field's AutoFill
  // (autocomplete="username webauthn"). rememberMe is a function, read once a passkey is chosen.
  async function signInWithPasskey(rememberMe, autofill = false) {
    const options = await passkeyOptions("/webauthn/authenticate/options");
    const credential = await navigator.credentials.get({
      publicKey: { ...options, challenge: base64url.decode(options.challenge), allowCredentials: descriptors(options.allowCredentials) },
      mediation: autofill ? "conditional" : undefined,
      signal: newCeremony()
    });
    const { response: assertion } = credential;
    const { response, data } = await request(`/login/webauthn?remember-me=${rememberMe()}`, {
      method: "POST",
      body: {
        id: credential.id,
        rawId: base64url.encode(credential.rawId),
        type: credential.type,
        response: {
          authenticatorData: base64url.encode(assertion.authenticatorData),
          clientDataJSON: base64url.encode(assertion.clientDataJSON),
          signature: base64url.encode(assertion.signature),
          userHandle: assertion.userHandle ? base64url.encode(assertion.userHandle) : undefined
        },
        clientExtensionResults: credential.getClientExtensionResults(),
        authenticatorAttachment: credential.authenticatorAttachment
      }
    });
    if (!response.ok) throw new Error(errorMessage(response, data));
  }

  // A name for the passkey in the mobile Account sheet; iPadOS Safari says it's a Mac, but has a touch screen.
  function deviceLabel() {
    const agent = navigator.userAgent;
    if (/iPhone/.test(agent)) return "iPhone";
    if (/iPad/.test(agent) || (/Macintosh/.test(agent) && navigator.maxTouchPoints > 1)) return "iPad";
    if (/Macintosh/.test(agent)) return "Mac";
    if (/Windows/.test(agent)) return "Windows";
    return "Passkey";
  }

  async function addPasskey() {
    const options = await passkeyOptions("/webauthn/register/options");
    let credential;
    try {
      credential = await navigator.credentials.create({
        publicKey: {
          ...options,
          user: { ...options.user, id: base64url.decode(options.user.id) },
          challenge: base64url.decode(options.challenge),
          excludeCredentials: descriptors(options.excludeCredentials)
        },
        signal: newCeremony()
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === "InvalidStateError") {
        throw new Error("This device already has a passkey for your account.");
      }
      throw error;
    }
    const { response: attestation } = credential;
    await api("/webauthn/register", {
      method: "POST",
      body: {
        publicKey: {
          label: deviceLabel(),
          credential: {
            id: credential.id,
            rawId: base64url.encode(credential.rawId),
            type: credential.type,
            response: {
              attestationObject: base64url.encode(attestation.attestationObject),
              clientDataJSON: base64url.encode(attestation.clientDataJSON),
              transports: attestation.getTransports?.() ?? []
            },
            clientExtensionResults: credential.getClientExtensionResults(),
            authenticatorAttachment: credential.authenticatorAttachment
          }
        }
      }
    });
  }

  const passkeys = {
    available: passkeysAvailable,
    autofillAvailable: passkeyAutofillAvailable,
    cancelled: passkeyCancelled,
    cancel: () => ceremony?.abort(),
    signIn: signInWithPasskey,
    add: addPasskey
  };

  // Formats an ISO timestamp as "Sep 29 2026" in the viewer's time zone; "" when missing or invalid.
  function formatDate(value) {
    const date = value ? new Date(value) : null;
    if (!date || Number.isNaN(date.getTime())) return "";
    const parts = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric" }).formatToParts(date);
    const part = (type) => parts.find((entry) => entry.type === type)?.value ?? "";
    return `${part("month")} ${part("day")} ${part("year")}`;
  }

  // Formats an ISO timestamp as "Sep 29 2026 6:24 PM" in the viewer's time zone; "" when missing or invalid.
  function formatDateTime(value) {
    const day = formatDate(value);
    if (!day) return "";
    const time = new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit" }).format(new Date(value));
    return `${day} ${time}`;
  }

  // Live updates (GET /api/events): calls handler({ type, listId }) for changes made on other devices, and
  // handler({ type: "resume" }) after a dropped connection comes back, since events may have been missed.
  // Returns a function that stops listening.
  function live(handler) {
    let source = null;
    let retry;
    let connectedBefore = false;
    let stopped = false;
    function connect() {
      if (stopped) return;
      source = new EventSource("/api/events", { withCredentials: true });
      source.addEventListener("ready", () => {
        if (connectedBefore) handler({ type: "resume" });
        connectedBefore = true;
      });
      source.addEventListener("change", (message) => {
        let change;
        try { change = JSON.parse(message.data); } catch { return; }
        if (change.origin !== clientId) handler(change);
      });
      source.onerror = () => {
        // EventSource retries interrupted connections itself; once it gives up (e.g. the session expired),
        // start a new one, which the remember-me cookie signs back in.
        if (source.readyState === EventSource.CLOSED) {
          clearTimeout(retry);
          retry = setTimeout(connect, 5000);
        }
      };
    }
    connect();
    return () => { stopped = true; clearTimeout(retry); source?.close(); };
  }

  // Runs fn once after a burst of calls settles (one change can send several events); errors are ignored,
  // since background refreshes shouldn't interrupt the user.
  function debounced(fn, delayMs = 150) {
    let timer;
    return () => { clearTimeout(timer); timer = setTimeout(() => { Promise.resolve().then(fn).catch(() => {}); }, delayMs); };
  }

  // An <img> for a list's store icon ("store": {"key","name"} from the lists API); "" when the list names no store.
  // The icon is removed again if the server has none (404). With keepSpace (rows that should line up), lists
  // without an icon get an empty placeholder of the same size instead, and a failed icon leaves its space.
  function storeIcon(store, { className = "store-icon", keepSpace = false } = {}) {
    if (!store?.key || !/^[a-z0-9-]+$/.test(store.key)) {
      return keepSpace ? `<span class="${className} store-icon-empty" aria-hidden="true"></span>` : "";
    }
    const label = String(store.name ?? store.key).replace(/[&<>"']/g, (character) => `&#${character.charCodeAt(0)};`);
    return `<img class="${className}" src="/api/store-icons/${store.key}" alt="${label}" title="${label}" width="20" height="20" `
      + `loading="lazy" decoding="async" data-keep-space="${keepSpace}" onload="this.classList.add('loaded')" onerror="hlist.storeIconFailed(this)">`;
  }

  // A store icon that couldn't load: an empty placeholder keeps rows aligned, otherwise it just goes away.
  function storeIconFailed(img) {
    if (img.dataset.keepSpace === "true") {
      const placeholder = document.createElement("span");
      placeholder.className = `${img.className} store-icon-empty`;
      placeholder.setAttribute("aria-hidden", "true");
      img.replaceWith(placeholder);
    } else {
      img.remove();
    }
  }

  window.hlist = { api, login, register, passkeys, inviteRequired, currentUser, logout, formatDate, formatDateTime, live, debounced, storeIcon, storeIconFailed };

  // Build watermark in the bottom-left corner of every desktop page: which server/client build is running.
  async function showBuildWatermark() {
    try {
      const response = await fetch("/api/version", { credentials: "same-origin" });
      if (!response.ok) return;
      const build = await response.json();
      const built = formatDateTime(build.builtAt);
      const mark = document.createElement("div");
      mark.id = "build-watermark";
      mark.textContent = `server ${build.serverCommit} · client ${build.clientCommit}${built ? ` · built ${built}` : ""}`;
      mark.title = "The build running on this server";
      mark.style.cssText = "position:fixed;left:10px;bottom:6px;z-index:5;color:rgba(32,42,36,.45);"
        + "font:11px ui-monospace,SFMono-Regular,Menlo,monospace;pointer-events:none;user-select:text";
      document.body.append(mark);
    } catch {
      // Watermark is informational only.
    }
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", showBuildWatermark);
  else showBuildWatermark();
})();
