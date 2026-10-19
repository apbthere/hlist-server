// Chooses between the mobile app (/app/) and the classic desktop pages.
// Phones and tablets get the mobile app; "?ui=mobile" or "?ui=desktop" overrides the guess and is
// remembered in this browser (the mobile app's "Use Desktop Version" link sets it too).
(() => {
  const storageKey = "hlist-ui";
  const valid = (value) => (value === "mobile" || value === "desktop" ? value : null);

  function remembered() {
    try {
      return valid(localStorage.getItem(storageKey));
    } catch {
      return null;
    }
  }

  function remember(choice) {
    try {
      localStorage.setItem(storageKey, choice);
    } catch {
      // Storage unavailable (e.g. private browsing): the choice applies to this page only.
    }
  }

  function detected() {
    const userAgent = navigator.userAgent;
    // iPadOS Safari (and iPhone with "Request Desktop Website") reports a Mac user agent;
    // unlike a Mac it has a touch screen.
    const macWithTouch = /Macintosh/.test(userAgent) && navigator.maxTouchPoints > 1;
    return /iPhone|iPad|iPod|Android|Mobi/.test(userAgent) || macWithTouch ? "mobile" : "desktop";
  }

  function choose() {
    const fromUrl = valid(new URLSearchParams(window.location.search).get("ui"));
    if (fromUrl) remember(fromUrl);
    return fromUrl ?? remembered() ?? detected();
  }

  window.hlistUi = { choose };
})();
