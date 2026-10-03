# 🎙️ Kural podcasts for Android

Kural (குரல், "voice") is a free podcast player for Android. It has no account, ads or
tracking. It opens on **your** shows rather than on promotions, and it brings together the
features people like most in Pocket Casts, Overcast, Apple Podcasts, Castro and AntennaPod.

## Installing it

1. On your Android phone, open the
   [latest build](https://github.com/Akrishna87/Akrishna87/releases/tag/podcasts-latest)
   and tap **Kural.apk** to download it.
2. Open the downloaded file. Android will ask you to allow installing apps from your browser
   (or Files app). Allow it, then tap **Install**. Google Play Protect may warn that it doesn't
   recognise the app, because it isn't from the Play Store. Tap **More details → Install anyway**.
3. Open **Kural**. Find shows in **Discover**, or bring your shows from another app with
   **Import from another app (OPML)**.

To update, install a newer `Kural.apk` the same way. It installs over the old one and keeps
your shows, Up Next and your place in every episode.

## What listeners asked for, and where it is

The features come from what listeners ask for on the Pocket Casts and AntennaPod forums and on
Reddit, and from what users praise in the leading apps.

| People asked for | In Kural |
|---|---|
| "Take me straight to my subscriptions, not ads for other shows" | **Home** opens on Continue listening, Up Next, mentions you follow and **New episodes**. Discover stays in its own tab. |
| A queue you control (Pocket Casts' Up Next) | **Up Next**: play next / play last, drag ≡ to reorder, swipe to remove, total time left (adjusted for speed). Finished episodes leave by themselves. |
| Inbox triage (Castro) | **New episodes** on Home, each with *Play next*, *Play last* or *Dismiss*. Nothing auto-plays unless you ask. |
| Smart Speed and Voice Boost (Overcast), Trim Silence (Pocket Casts) | **Trim silence** and **Volume boost**, app-wide or per show. Speed goes from 0.5× to 3× in 0.05 steps. |
| Per-podcast speed and "skip first X seconds" | **Show settings**: custom speed and effects, **skip intro** and **skip outro**, oldest-first order, auto-download, auto-add to Up Next, notifications. |
| Chronological play for serials | **Oldest first** per show. Serial shows default to it, offer **Play episode 1**, and autoplay continues in order. |
| Chapters | Feed chapters (Podlove), Podcasting 2.0 JSON chapters, and **ID3 chapters inside the MP3**. Chapter list, next/previous chapter buttons and chapter artwork. |
| Transcripts (Apple Podcasts) | Podcasting 2.0 transcripts (JSON, WebVTT, SRT, HTML). A synced view highlights the line being spoken; tap a line to jump there. |
| "Find specific moments in episodes" | **Timestamps in show notes are tappable** and jump to that moment. Links in notes open too. |
| Bookmarks and notes (Pocket Casts forum) | **Bookmark** any moment with an optional note. Bookmarks are listed per episode and in Library, and you can share a moment. |
| "Alert me when my favourite guest appears on any show" | **Guests & topics**: follow a name or topic and Kural searches every podcast in Apple's directory for new episodes that mention it, with notifications. **Tap a guest** on an episode to find their other appearances. |
| Smart playlists ("auto-updating playlists by category") | **Filters**: pick shows and rules (unplayed, downloaded, in progress, starred, released in the last N days, under N minutes). |
| A smarter sleep timer | Minutes, **end of episode** or **end of chapter**. The sound fades out at the end. Press play within 5 minutes of it stopping and the same timer starts again. |
| Android Auto | Browse Up Next, New episodes, In progress, Downloads and Shows from the car, and ask for a show by voice. |
| Headphone buttons that make sense for talk | Next/previous on headphones and in the car **skip forward/back** (or change episode if you prefer). The notification and lock screen show skip-back/skip-forward buttons using your skip lengths. |
| Leaving an app without losing your shows | **OPML import and export**. Subscribe links (`itpc://`, `pcast://`, `feed://`, Apple Podcasts links) and "Share → Kural" also work. |
| Downloads that tidy up after themselves | Download over Wi-Fi only, automatic downloads for chosen shows, and **delete played episodes** (starred and bookmarked ones are kept). |
| Stats (Pocket Casts) | Time listened, **time saved** by speed, trimmed silences and skipped intros, episodes finished, and your most-listened shows. |

There are more touches throughout: artwork-tinted player and show pages, Podcasting 2.0
**funding** links ("Support the show"), hosts and guests on episode pages, search inside your
own shows, mark all played, and stars.

## How it's built

It uses Kotlin and Jetpack Compose, with Media3 (ExoPlayer) for audio, WorkManager for the
background refresh, Coil for artwork and AndroidX Palette for artwork colours.

- `feed/` reads RSS and Atom feeds with the iTunes and Podcasting 2.0 tags
  (`FeedParser.kt`), JSON chapters, transcripts, OPML and show notes (links and timestamps).
  `Directory.kt` talks to Apple's public podcast directory (search, top charts by country and
  category, episode search). It is the same directory AntennaPod uses, and it needs no key.
- `data/Library.kt` holds your shows, episodes, Up Next, bookmarks, filters, alerts and stats,
  saved as small JSON files on the phone.
- `PlaybackService.kt` owns the player and keeps it in step with Up Next. It applies each show's
  speed and effects, skips intros and outros, runs the sleep timer, reads ID3 chapters, keeps
  stats, and serves Android Auto (`CarLibrary.kt`).
- `Refresh.kt` checks feeds (politely, with ETag/Last-Modified), handles new episodes as each
  show's settings say, checks guest and topic alerts, and posts notifications.
- `ci/icon/gen.py` draws the launcher icon (a microphone sending out sound waves).
- `ci/smoke-test.sh` runs on an Android emulator for every build. It checks Apple's real top
  chart and search, then follows a test show served from the CI machine (with chapters, a
  transcript and a guest). It plays an episode, opens its chapters and transcript, changes
  speed, checks playback continues in the background, queues another episode and finds the show
  in the Library. Screenshots from that run are attached to each release.

The unit tests in `app/src/test` cover feed parsing (dates, durations, chapters, transcripts,
show notes, OPML), the directory's answers, and the library (Up Next, filters, saving, autoplay
order).

Builds are made by the [Kural APK workflow](../.github/workflows/podcasts-apk.yml) on every
push that touches `podcasts-android/`.
