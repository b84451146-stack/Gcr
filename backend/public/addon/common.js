const params = new URLSearchParams(location.search);

// Must be called from a click (popup). Returns a Google OAuth access token.
function getToken(scope) {
  return new Promise((resolve, reject) => {
    if (!window.google || !google.accounts) return reject(new Error("Google sign-in script did not load"));
    const client = google.accounts.oauth2.initTokenClient({
      client_id: window.GCR_CLIENT_ID,
      scope,
      login_hint: params.get("login_hint") || undefined,
      callback: (r) => (r.error ? reject(new Error(r.error_description || r.error)) : resolve(r.access_token)),
      error_callback: (e) => reject(new Error(e.type || "Sign-in cancelled")),
    });
    client.requestAccessToken();
  });
}

async function api(path, token, options = {}) {
  const r = await fetch(path, {
    ...options,
    headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || "Request failed (" + r.status + ")");
  return data;
}

const mins = (s) => Math.round(s / 60) + " min";
const appsText = (byApp) =>
  Object.entries(byApp).sort((a, b) => b[1] - a[1]).map(([n, s]) => n + " " + mins(s)).join(", ") || "-";

function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text; // textContent: no HTML injection
  if (cls) e.className = cls;
  return e;
}
function setStatus(msg, isErr) {
  const s = document.getElementById("status");
  s.textContent = msg;
  s.className = isErr ? "err" : "muted";
}
