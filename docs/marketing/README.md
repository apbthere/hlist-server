# LinkedIn visuals

Images, a carousel and a video for featuring HList on LinkedIn. Everything is captured from a real build:

- a throwaway server with demo data (user "taylor", lists for Costco, Publix and Target);
- the mobile app in WebKit at iPhone and iPad size;
- a real Claude Code session, recorded next to the live app.

Nothing shows personal lists, real accounts, tokens or the home server's address.

## Files

| File | Size | Use |
| --- | --- | --- |
| `hero-1200x627.png` | 0.5 MB | Link and post image; **Featured** on the profile. Claude Code adding items next to the app, with the tagline |
| `demo-1080x1080.mp4` | 1.0 MB | Post video, square, 31 s, captions burned in (LinkedIn autoplays muted). A real Claude Code session adds Bounty paper towels and the usual oat milk; the items appear in the app with photos |
| `carousel-1080x1350.pdf` | 1.1 MB | Document (carousel) post, 7 slides: problem → demo → screens → live updates → architecture → stack → repo |
| `feature-list-items-1080x1080.png` / `-1080x1350.png` | 0.8 / 0.9 MB | **Projects** media or post: a list grouped by department, with product photos |
| `feature-item-editor-1080x1080.png` / `-1080x1350.png` | 0.8 / 0.9 MB | Projects media or post: a pasted costco.com link filled in the name, brand and photo |
| `feature-live-updates-1080x1080.png` / `-1080x1350.png` | 0.5 / 0.7 MB | Projects media or post: an item checked off on the iPhone shows on the iPad |
| `feature-connect-assistant-1080x1080.png` / `-1080x1350.png` | 0.8 / 0.9 MB | Projects media or post: Account → AI Assistants with Claude connected |
| `architecture.png` | 0.2 MB | Architecture diagram (1600×1000), for posts and [docs/architecture.md](../architecture.md) |
| `architecture.svg` | 10 KB | The diagram's source, editable |

The square (1080×1080) and portrait (1080×1350) stills have the same content. Portrait takes more of the feed on a
phone; square suits the Projects section.

## Post caption

> I built HList, a self-hosted shopping list my AI assistant can use.
>
> Say "add Bounty paper towels and the usual oat milk to my Costco list" and Claude finds the list, checks what
> I've bought before, looks up the product on costco.com, and adds both, with photos. The items show up on my
> phone and iPad instantly.
>
> Under the hood:
> • An MCP server written from the spec, with OAuth 2.1 + PKCE, so assistants like Claude and Grok connect by just
>   signing in
> • Spring Boot 4 and Spring Security 7 with passkeys (Face ID)
> • Live updates over server-sent events, sent only after each change commits
> • Safe fetching of product pages (HTTPS only, public addresses only), so the server can't be turned against my
>   home network
> • A Vue 3 + Ionic app that installs on iPhone and iPad, running on my home NAS
>
> Open source (MIT): https://github.com/apbthere/hlist-server
>
> #AI #MCP #SpringBoot #Java #Vue #SelfHosted

## Projects description

> HList is a self-hosted shopping list with a built-in MCP server, so Claude, ChatGPT or Grok can read your lists
> and add items: "add the usual oat milk to my Costco list" finds the list, reuses past purchases and fetches
> product photos. Built with Java 25, Spring Boot 4 and Spring Security 7 (passkeys, OAuth 2.1), server-sent events
> for live updates, and a Vue 3 + Ionic app that installs on iPhone and iPad.

## Rebuilding

```sh
./mvnw -q -DskipTests package                       # the server jar
(cd ../hlist-client && npm ci && npm run build)     # the client, next to this repository
CLAUDE_BIN=<path to claude> node docs/marketing/build.mjs
```

It needs the internet (product photos, store icons, costco.com), Claude Code signed in, and Swift (Xcode's command
line tools) for the MP4. A run takes about 3 minutes, and its two Claude Code turns cost about $0.45.

- **The Claude session is real, so it varies.** HList tells assistants to ask when several products fit, and
  Costco sells more than one Bounty pack. When Claude asks, the script answers "The first one, please." in the same
  session (`DEMO_ANSWER` changes the answer, `DEMO_PROMPT` the request). The video says "It asks instead of
  guessing" there, and its footer says the pauses are shortened.
- **To change only layouts or captions**, rerun with the work folder the last run printed. This reuses its
  captures and recording, with no server or Claude session:
  `LAYOUT_ONLY=<work folder> node docs/marketing/build.mjs`.
- `encode-mp4.swift` turns the recorded frames into the MP4 with macOS's AVFoundation, so no ffmpeg is needed.
