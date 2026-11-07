# Readync

Readync keeps your reading in step everywhere. It reads your progress and highlights from
[CrossPoint Sync](https://sync.crosspointreader.com) (or a self-hosted crosspoint-sync server), KOSync,
Mihon backups, physical books and web novels, and sends them to **Hardcover**, **Seekquel**,
**StoryGraph**, **Goodreads**, **Fable** and **Pagebound**. It also has an SFF news tab, reading stats
and widgets.

## Get the app

Download the latest APK from [Releases](../../releases), or add this repository to
[Obtainium](https://github.com/ImranR98/Obtainium) to get updates automatically.

## What's in this repository, and what isn't

This repository has the app's code so you can read it, learn from it and build your own copy. To be
upfront: **it isn't the complete code of the official app.**

**The official APK (in Releases) is built from a private repository**, from this same code plus parts
that aren't published here:

- **The Book photo studio**, which makes your highlights into photos of real-looking books: the light,
  the desks, the paper, the moods, drawing on the page, and making one from a photo of the page. In this
  code it's a stand-in that shows a plain card instead.

There are also connections that **aren't in the official APK or here**: Kindle, Libby and Audible.
They're only in the developer's own copy. Here they're empty stand-ins, so the code builds the same.

So if you build this repository yourself, you get a working Readync without the Book photo studio. If you
want the studio, use the official APK. It's free.

The stand-ins are in `app/src/stubs/`. When `app/src/private/` exists (only in the private repository),
the build uses the real parts instead.

## Build it yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```
./gradlew assembleLiteDebug
```

The APK is in `app/build/outputs/apk/lite/debug/`. It's signed with Android's debug key, so it installs
beside the official app, not over it, and the two can't update each other.

## Support

Readync is free, with no ads and nothing locked. If it's useful to you, you can
[buy me a coffee](https://buymeacoffee.com/prithi). It doesn't unlock anything, it just helps keep the app going.

## A note on the trackers

Hardcover and Seekquel have official APIs. Goodreads, StoryGraph, Fable and Pagebound don't, so Readync
talks to them the way their websites and apps do, using your own sign-in, from your phone. That can
break when they change things, and it's up to you to use it within their terms.

## Licence

The code here is under the [MIT licence](LICENSE). Fonts, icons and code by others keep their own
licences, listed in [`licenses/`](licenses/). Some of the request handling is based on
[ShelfSync](licenses/shelfsync-LICENSE.txt) (MIT).
