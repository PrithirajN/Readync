<p align="center">
  <img src="assets/banner.png" alt="Readync: sync your reading progress, highlights and ratings to Hardcover, Goodreads, StoryGraph, Fable, Pagebound and Seekquel" width="100%">
</p>

<p align="center">
  <a href="https://github.com/PrithirajN/Readync/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/PrithirajN/Readync?style=flat-square&color=4A7A62&label=latest"></a>
  <a href="https://github.com/PrithirajN/Readync/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/PrithirajN/Readync/total?style=flat-square&color=2F5444"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-4A7A62?style=flat-square&logo=android&logoColor=white">
  <img alt="Free, no ads" src="https://img.shields.io/badge/free-no%20ads-2F5444?style=flat-square">
  <a href="LICENSE"><img alt="MIT licence" src="https://img.shields.io/badge/licence-MIT-4A7A62?style=flat-square"></a>
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-2F5444?style=flat-square&logo=kotlin&logoColor=white">
</p>

<p align="center">
  <a href="https://github.com/PrithirajN/Readync/releases/latest"><b>Download the APK</b></a> ·
  <a href="#install">Install</a> ·
  <a href="#features">Features</a> ·
  <a href="#supported-services">Services</a> ·
  <a href="#faq">FAQ</a> ·
  <a href="#source-code">Source code</a>
</p>

<p align="center">
  <a href="https://buymeacoffee.com/prithi"><img alt="Buy me a coffee" src="https://img.shields.io/badge/Buy%20me%20a%20coffee-support%20Readync-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
  <a href="https://github.com/sponsors/PrithirajN"><img alt="Sponsor on GitHub" src="https://img.shields.io/badge/Sponsor-on%20GitHub-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
</p>

---

**Readync** is a free Android app that keeps your reading in step everywhere. It reads your progress
and highlights from your e-reader ([CrossPoint Sync](https://sync.crosspointreader.com) or any KOSync
server), from physical books and from web novels. Then it updates **Hardcover**, **Goodreads**,
**StoryGraph**, **Fable**, **Pagebound** and **Seekquel** for you, in the background.

You read, and your trackers keep up. You don't have to update page numbers by hand on six sites.

It's drawn like a small reading gadget: each book sits on a dark screen with a pixel percentage that rolls like an odometer, the buttons are keys that sink with a click you can feel, switches glow orange when on, and services each have their own status light.

## Features

### 📚 One sync, every tracker
- **Automatic progress** to Hardcover, Goodreads, StoryGraph, Fable, Pagebound and Seekquel, every 15 minutes.
- **Public or private**, chosen per tracker.
- **Books matched for you**, with covers and descriptions; pick with one tap when it's unclear.
- **Real reading dates** sent to the trackers that take them.
- **Never sent backwards**: trackers keep the furthest place you've reached. A big jump back (a reset device, the wrong copy opened) asks before it's used, and a finished book opened again asks "Reading it again?" once.
- **One post per passage**: the same highlight arriving from two readers goes to each tracker once, and an edited one replaces its old entry where the tracker allows.
- **The same book twice?** Two copies that look alike ask to be joined or kept apart; nothing is joined by itself.

### ✍️ Highlights, ratings and reviews
- **Highlights and notes** go to every tracker that takes them (some are opt-in).
- **Rate once, everywhere**: stars in tenths and a Markdown review, spoiler tags included.
- **Check before sending** (optional): mark each new highlight fine, a spoiler or private. No AI.
- **Export** to Readwise, Notion and Obsidian after each sync.
- **Snap a page** from a paper book or a screenshot and the highlighted passage is saved.

<details>
<summary>More on highlights</summary>

- Hardcover, StoryGraph and Seekquel get highlights by default; Goodreads, Pagebound and Fable when you turn them on.
- A spoiler goes behind Fable's, Pagebound's and Threads' spoiler tap, to private journals as it is, and to Goodreads and Micro.blog held back or with a warning, as you choose. Waiting highlights show on the Highlight of the day card and each book's Highlights tab.
- Readwise gets them for its daily review; Notion gets a page per book with its cover; Obsidian gets a Markdown note per book, keeping anything you write around it.
</details>

### 📖 Any way you read
- **E-readers**: CrossPoint Sync, or KOReader through a KOSync server (your own included: a certificate you made is trusted once it's installed on the phone).
- **KOReader highlights** from its own files on the same phone, or from HighlightSync / AnnotationSync on WebDAV.
- **Moon+ Reader** through its WebDAV sync.
- **Paper books**: photograph the page you're on, or scan the barcode to add one.
- **Web novels and fan fiction** (Royal Road, AO3, Scribble Hub, Wattpad…) with new-chapter alerts.
- **Reading apps and audiobooks**: Audiobookshelf, Jellyfin, BookFusion, BookOrbit, Readest, Readwise Reader, Spotify and Micro.blog.

<details>
<summary>More on sources</summary>

- KOReader highlights can be put on a CrossPoint device **on their exact words**, and CrossPoint highlights can go back to KOReader through HighlightSync. A book known only by KOReader's ID can be named from its highlights' own words.
- Web novels are linked from the link inside your EPUB (FanFicFare, WebToEpub) or found by title. The book page shows your chapter and how many are out past your copy.
- Audiobooks keep pace with the ebook **chapter by chapter**: the audiobook's chapters are matched to the book's by their titles (from the server's ebook or a copy kept on the phone; the audio is never downloaded), so intros and credits don't throw the place off. **Match chapters** sets them by hand, **Resume in Spotify** starts from your place, and **Not the right spot?** fixes the match. A service also linked on your crosspoint-sync server is never sent the same thing twice.
</details>

### 📚 Your collection
- **The books you own**, with format, price paid and where: scan the barcodes on your shelves, or mark any book. Owned books you haven't started wait off your trackers, with what they're worth.
- **Series gaps**: the books missing between the ones you own, and the next one to read, with price tracking in a tap.
- **Your shelves, drawn**: build your real bookcases (tall, cube grid, floating ledges, low), each spine as thick as its page count in its cover's colour, arranged by series, author, genre or colour. Tap a spine to pull it out.
- **A calendar** of reading days, starts and finishes, books added, releases and price drops, and **stamps** for milestones.
- **A journal page** for any book: cover taped on, your dates, stars, review and highlights, in handwriting, to share.

### 🎁 Made from your reading
- **Quote cards**: ten looks, from Book card (15 layouts) and Book photo to System window and Wordprint, coloured from the cover.
- **Moving quotes**: a highlight as a short video for Reels, Stories or Threads, made in a live studio.
- **Book recap**: a finished book in numbers, as a 15-second video or a carousel of one-stat pictures.
- **Wrapped**: your month or year in reading as story slides, down to your reader type.
- **Shelf poster**: a year's books as spines on a shelf or a wall of covers.
- **Post to Threads** in a tap, or let a book's new highlights go up after a sync (off until you turn it on).
- **Post to X**: the X app opens with the picture and words filled in.
- **Daily review**, a **reading timer** for paper books, and **Who's this?** for characters (on-device AI, no spoilers).

<details>
<summary>More on what you can make</summary>

- **Book recap** stats: days, pages, words, a bar per day, how steadily you read, pace, highlights and where they fall, against your usual and your year.
- **Wrapped** slides: pages and days, streaks, month by month, books finished, lengths, time to finish, ratings, genres and moods, new authors, how it compares with the stretch before, and everything on one card. Books from imported history count too.
- **Moving quotes**: typewriter, word by word, highlighter sweep, kinetic words or a slow fade, over the cover, its colour, night or paper; story, post or square.
- Quote cards with **Copies for quotes** (see Catalogs) show the real lines around your passage without photographing the page.
</details>

### ✨ And more
- **Catalogs**: the 50 newest books across all your OPDS servers, and every server searched at once, with download details and paste-a-list setup.
- **Books in a post**: paste or share a TikTok, Instagram, Threads or YouTube post (or screenshots or a screen recording of one, read on your phone frame by frame) and the books it names are found (caption, the words on every picture of a carousel, hashtags; comparisons like "X meets Y" left out), checked against Google Books (a book the caption and cover both name is kept even before Google lists it), then added to Want to Read or price-tracked. Read in the background with a notification when done, so you can share and go back to scrolling. Missed one? Tap it among the words read on the picture, or type its title.
- **Copies for quotes**: books you're reading kept privately inside the app, for the lines around your quotes.
- **Price tracker** for Amazon.in, Bookswagon and Atlantic Books, plus any other bookshop's page you give it (Flipkart, Crossword…), with your own target price.
- **SFF news** set like a magazine, with a Screen view for films and series, and a clean reader.
- **Library checks, a rating rubric, a top-books image and a reading-log export** on the Activity tab: books whose dates or page counts would throw your numbers off, scores on what matters to you beside your stars (with a gut-vs-rubric chart), your year's top 10 or 20 as one ranked image, and every book as an Excel or CSV file.
- **Import your history** from Goodreads, StoryGraph, Hardcover, Seekquel or KOReader.
- **Live Updates** (Android 16): a reading session's timer, a catalog download and a sync you started stay at the top of your notifications, with a chip by the clock.
- **Widgets**, a Quick Settings tile and app shortcuts.
- **Backups** locked with a password, or a data-only copy to move phones.
- Optional **on-device AI**. Nothing leaves your phone.

<details>
<summary>More on these</summary>

- **Catalogs** work with Calibre-Web, Calibre's server, Kavita, Komga, Stump, COPS and Project Gutenberg (OPDS 1 and 2). The same book on several servers shows once; sign-ins stay on the phone and go only to their own server.
- **Price tracker**: share a bookshop's page to Readync (or paste it, or import an Amazon wish list) and it's tracked in the background, with a notification saying the price now; you're told when a price is under yours, or 10% under the lowest seen. Checked twice a day.
- **SFF news**: For you, Latest, Screen, Genres and Saved; articles in Lora with a drop cap and an AI menu to sum up or check for spoilers.
- **Import** keeps what's already in Readync, sends nothing to your trackers, and brings day-by-day reading where the source has it.
</details>

## Install

1. Open the **[latest release](https://github.com/PrithirajN/Readync/releases/latest)** on your Android phone.
2. Download `Readync.apk` under **Assets**.
3. Open it. If Android asks, allow installs from your browser.
4. Sign in to CrossPoint Sync, then link your trackers under **Services**.

Readync needs Android 8.0 or newer.

> [!TIP]
> ☕ **Enjoying Readync?** It's free, with no ads and nothing locked, and it's made by one person.
> If it saves you time, [buy me a coffee](https://buymeacoffee.com/prithi) or [sponsor me on GitHub](https://github.com/sponsors/PrithirajN) to help keep it going.

## Updates

**Automatic:** install [Obtainium](https://github.com/ImranR98/Obtainium), then in Readync go to
**Services → App update → Set up in Obtainium**. Obtainium tells you when a new version is out and
installs it.

**By hand:** download the newest `Readync.apk` from [Releases](https://github.com/PrithirajN/Readync/releases)
and install it over your current copy.

Either way, you stay signed in to all your trackers.

## Supported services

### Trackers

| Tracker | Progress | Highlights | Rating & review | Reading dates |
| --- | :---: | :---: | :---: | :---: |
| Hardcover | ✅ | ✅ | ✅ | ✅ |
| Goodreads | ✅ | optional | ✅ | ✅ |
| StoryGraph | ✅ | ✅ (journal) | ✅ | ✅ |
| Fable | ✅ | optional | ✅ | ✅ |
| Pagebound | ✅ | optional | ✅ | ✅ |
| Seekquel | ✅ | ✅ | rating | source of dates |

### Where your reading comes from

| Source | Progress | Highlights | How it connects |
| --- | :---: | :---: | --- |
| CrossPoint Sync | ✅ | ✅ | Your CrossPoint Sync account |
| KOReader (KOSync) | ✅ | — | KOReader's own KOSync server or a self-hosted one |
| KOReader on the same phone | — | ✅ | Its own files, no plugin needed |
| KOReader elsewhere | — | ✅ | HighlightSync or AnnotationSync plugin on Koofr or any WebDAV |
| Moon+ Reader | ✅ | ✅ | Its WebDAV sync (a free Koofr account works), read only |
| Physical books | ✅ | ✅ | A photo of the page (page number and highlighted passage), barcode to add |
| Reading timer | ✅ | — | Pages and minutes from a timed session |

### Reading apps and servers

| Service | Your place | Highlights | Other |
| --- | :---: | :---: | --- |
| Audiobookshelf | both ways | — | Ebook and audiobook kept in line |
| Jellyfin | both ways | — | Ebook and audiobook kept in line |
| BookFusion | both ways | — | Opens at your exact place |
| BookOrbit | both ways | ✅ | Highlights at their exact spot |
| Readest | both ways | ✅ | Highlights at their exact spot |
| Readwise Reader | — | — | Finished books archived |
| Spotify audiobooks | read only | — | Listening ahead moves the book; Resume in Spotify |
| Micro.blog | ✅ | ✅ | Currently reading and Finished reading shelves |

### Highlight exports

| Service | What it gets |
| --- | --- |
| Readwise | Every highlight with your notes, page and date, for its daily review |
| Notion | A page per book: cover, highlights as quotes, notes in callouts |
| Obsidian | A Markdown note per book in a vault folder you pick |

### Web novels and fan fiction

| Site | Linked from your EPUB | New-chapter alerts |
| --- | :---: | --- |
| Royal Road | ✅ | every few hours |
| Scribble Hub | ✅ | every few hours |
| Archive of Our Own (AO3) | ✅ | daily |
| Wattpad | ✅ | daily |
| …and more | ✅ | daily |

### Sharing

| Service | How |
| --- | --- |
| Threads | Post in a tap, or let a book's new highlights go up after each sync (off until you turn it on) |
| X | Opens the X app with the picture or video and words filled in; you post it |
| Any app | Share or save the picture or video |

### Importing your history

| Source | What comes in |
| --- | --- |
| Goodreads export | Books, dates, ratings, reviews |
| StoryGraph export | Books, dates, ratings, reviews |
| Hardcover (export or direct) | Books, dates, ratings, reviews, quotes as highlights, progress updates by day |
| Seekquel (direct) | Books, dates, ratings, reviews |
| KOReader statistics | Pages and minutes for each day |

### Catalogs (OPDS)

| Works with | |
| --- | --- |
| Calibre-Web, Calibre's content server, COPS | OPDS 1 |
| Kavita, Komga, Stump | OPDS 1 and 2 |
| Project Gutenberg and other public catalogs | OPDS 1 and 2 |

All of them searched at once, with downloads into Downloads, and copies for quotes kept inside the app.

### Price tracking

| Store | Formats |
| --- | --- |
| Amazon.in | Print and Kindle edition prices |
| Bookswagon | Print |
| Atlantic Books | Print |
| Any other bookshop (Flipkart, Crossword, Sapna Online…) | The page you share, read from the price it gives search engines |

## FAQ

<details>
<summary><b>How do I sync my e-reader with Goodreads or StoryGraph?</b></summary>

Install Readync, sign in with your CrossPoint Sync account and link Goodreads or StoryGraph
under Services. Your progress then updates on its own as you read.
</details>

<details>
<summary><b>Is Readync free?</b></summary>

Yes. There are no ads, no subscription and nothing locked behind a paywall.
</details>

<details>
<summary><b>Where are my passwords kept?</b></summary>

On your phone. Readync talks to each tracker directly from your phone, and only keeps the session
token it needs.
</details>

<details>
<summary><b>Does it work with KOReader?</b></summary>

Yes. Add a KOSync server (KOReader's own, or a self-hosted one) under Services, alongside your CrossPoint Sync account.
</details>

<details>
<summary><b>Does it work with Moon+ Reader?</b></summary>

Yes. Turn on Moon+ Reader's sync to WebDAV (a free Koofr account works), then connect the same account in Readync under Services › Moon+ Reader. The app has a step-by-step guide. Readync only reads Moon+ Reader's sync files; it never changes anything there.
</details>

<details>
<summary><b>Can I track physical books?</b></summary>

Yes. Add the book (or scan its barcode), then snap a photo of the page you're on or type the page
number. The update goes to all your trackers.
</details>

## Source code

This repository has the app's code, so you can read it, learn from it and build your own copy. To be
upfront: **it isn't the complete code of the official app.**

**The official APK (in Releases) is built from a private repository**, from this same code plus parts
that aren't published here:

- **The Book photo studio**, which turns your highlights into photos of real-looking books: the light,
  the desks, the paper, the moods, drawing on the page, and making one from a photo of the page. In this
  code it's a stand-in that shows a plain card instead.

There are also connections that **aren't in the official APK or here**: Kindle, Libby and Audible.
They're only in the developer's own copy. Here they're empty stand-ins, so the code builds the same.

So if you build this repository yourself, you get a working Readync without the Book photo studio. If you
want the studio, use the official APK. It's free.

The stand-ins are in `app/src/stubs/`. When `app/src/private/` exists (only in the private repository),
the build uses the real parts instead.

### Build it yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```
./gradlew assembleLiteDebug
```

The APK is in `app/build/outputs/apk/lite/debug/`. It's signed with Android's debug key, so it won't
install over the official app (or the other way round): uninstall one before installing the other.

## A note on the trackers

Hardcover and Seekquel have official APIs. Goodreads, StoryGraph, Fable and Pagebound don't, so
Readync talks to them the way their own websites and apps do, using your sign-in, from your phone.
That can break when they change things, and it's up to you to use it within their terms. Readync
isn't affiliated with any of these services.

## Support

<a href="https://buymeacoffee.com/prithi"><img alt="Buy me a coffee" src="https://img.shields.io/badge/Buy%20me%20a%20coffee-%E2%98%95-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
<a href="https://github.com/sponsors/PrithirajN"><img alt="Sponsor on GitHub" src="https://img.shields.io/badge/Sponsor-on%20GitHub-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>

Readync is free, with no ads and nothing locked. If it's useful to you, you can
[buy me a coffee](https://buymeacoffee.com/prithi) ☕ or [sponsor me on GitHub](https://github.com/sponsors/PrithirajN) 💖. It doesn't unlock anything; it just helps keep the app going.

## Licence

The code here is under the [MIT licence](LICENSE). Fonts, icons and code by others keep their own
licences, listed in [`licenses/`](licenses/). Some of the request handling is based on
[ShelfSync](licenses/shelfsync-LICENSE.txt) (MIT).

---

<p align="center"><sub>Readync · Goodreads sync · StoryGraph sync · Hardcover sync · e-reader reading tracker for Android</sub></p>
