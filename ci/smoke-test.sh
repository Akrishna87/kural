#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the APK, checks Discover against Apple's real
# directory, then follows a test show served from this machine (with chapters and a transcript),
# plays an episode, opens its chapters and transcript, changes speed, checks it keeps playing in
# the background, queues another episode and finds the show in the Library.
# Usage: smoke-test.sh <apk> <output dir>
set -euo pipefail

APK="$1"
OUT="$2"
PKG=io.github.akrishna87.podcasts
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

fail() {
  echo "SMOKE TEST FAILED: $*"
  adb exec-out screencap -p > "$OUT/failure.png" || true
  if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/failure.xml" > /dev/null 2>&1; then
    echo "On screen:"; grep -o '\(text\|content-desc\)="[^"]\+"' "$OUT/failure.xml" | head -80 || true
  fi
  adb logcat -d > "$OUT/logcat.txt" || true
  echo "Crashes and errors from the app:"
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 || true
  grep -E " E (AndroidRuntime|ExoPlayer|MediaSession)" "$OUT/logcat.txt" | tail -20 || true
  exit 1
}
dump() {
  local i
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1 || { sleep 2; continue; }
    # The emulator's own apps sometimes freeze while it warms up; wave the "isn't responding" popup away.
    grep -q "isn&apos;t responding\|isn't responding" "$OUT/$1.xml" || return 0
    echo "(dismissing a system 'isn't responding' popup)"
    python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait" > /dev/null 2>&1 && adb shell input tap $(python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait")
    sleep 3
  done
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
show() { echo "  on screen: $(tr '\n' ' ' < "$OUT/$1.xml" | grep -o '\(text\|content-desc\)="[^"]\+"' | sed 's/^[a-z-]*=//' | head -50 | tr '\n' ' ')"; }
tap() { # tap <dump name> <text> [first|last]
  local xy
  xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2" "${3:-first}") || fail "couldn't find '$2' on screen"
  adb shell input tap $xy
}
wait_for() { # wait_for <dump name> <grep -E pattern> <seconds> <what>
  local end=$((SECONDS + $3))
  while [ $SECONDS -lt $end ]; do
    dump "$1"
    grep -Eq "$2" "$OUT/$1.xml" && return 0
    sleep 3
  done
  fail "$4 (waited $3 s)"
}
session() { adb shell dumpsys media_session > "$OUT/session.txt"; }
playing() { session; grep -Eq "\{state=(PLAYING|3)" "$OUT/session.txt"; }
hide_keyboard() { if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi; }
scroll_down() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 400; sleep 1; }

# ----- A test show served from this machine (the emulator sees it as 10.0.2.2) -----
FEED_DIR="$(mktemp -d)"
# Episode one lasts 10 minutes so it's still playing (at 1.5x, from 1:30) when the lock-screen step checks it.
ffmpeg -loglevel error -f lavfi -i "sine=frequency=440:duration=600" -f lavfi -i "anoisesrc=d=600:a=0.02" \
  -filter_complex "[0][1]amix=inputs=2,volume=2" -ac 1 -ar 22050 -b:a 48k "$FEED_DIR/ep1.mp3"
ffmpeg -loglevel error -f lavfi -i "sine=frequency=660:duration=120" -ac 1 -ar 22050 -b:a 48k "$FEED_DIR/ep2.mp3"
cat > "$FEED_DIR/ep1.vtt" <<'VTT'
WEBVTT

00:00:00.000 --> 00:00:05.000
<v Host>Welcome to the Podwave test show.

00:00:05.000 --> 00:00:30.000
<v Guest>Thanks for having me on the transcript.
VTT
NOW_RFC=$(date -u -R)
YESTERDAY_RFC=$(date -u -R -d "yesterday")
cat > "$FEED_DIR/feed.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
     xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:psc="http://podlove.org/simple-chapters">
  <channel>
    <title>Podwave Test Show</title>
    <link>https://github.com/Akrishna87/Akrishna87</link>
    <description>A show made up for the automatic test.</description>
    <itunes:author>Test Host</itunes:author>
    <item>
      <title>Episode One With Chapters</title>
      <guid>ep-1</guid>
      <pubDate>$NOW_RFC</pubDate>
      <enclosure url="http://10.0.2.2:8000/ep1.mp3" length="3600000" type="audio/mpeg"/>
      <itunes:duration>10:00</itunes:duration>
      <description><![CDATA[<p>Topics:</p><ul><li>0:00 Hello there</li><li>1:30 The middle part</li></ul>]]></description>
      <psc:chapters version="1.2">
        <psc:chapter start="00:00:00" title="Hello there"/>
        <psc:chapter start="00:01:30" title="The middle part"/>
        <psc:chapter start="00:03:00" title="Goodbye section"/>
      </psc:chapters>
      <podcast:transcript url="http://10.0.2.2:8000/ep1.vtt" type="text/vtt"/>
      <podcast:person role="guest">Sam Guest</podcast:person>
    </item>
    <item>
      <title>Episode Two Plain</title>
      <guid>ep-2</guid>
      <pubDate>$YESTERDAY_RFC</pubDate>
      <enclosure url="http://10.0.2.2:8000/ep2.mp3" length="720000" type="audio/mpeg"/>
      <itunes:duration>2:00</itunes:duration>
      <description>The second one.</description>
    </item>
  </channel>
</rss>
XML
# A huge show (1,500 episodes, ~30 MB of show notes), like the long-running shows people follow.
python3 - "$FEED_DIR/big.xml" <<'PY'
import sys, email.utils
with open(sys.argv[1], "w") as f:
    f.write('<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Podwave Huge Show</title>')
    for n in range(1, 1501):
        date = email.utils.formatdate(1_500_000_000 + n * 86400, usegmt=True)
        notes = "Notes &amp; links for the episode. " * 600
        f.write(f'<item><title>Huge Episode {n}</title><guid>h-{n}</guid><pubDate>{date}</pubDate>'
                f'<enclosure url="http://10.0.2.2:8000/ep2.mp3" length="720000" type="audio/mpeg"/>'
                f'<content:encoded><![CDATA[<p>{notes}</p>]]></content:encoded></item>\n')
    f.write('</channel></rss>')
PY
echo "huge test feed: $(du -h "$FEED_DIR/big.xml" | cut -f1)"
(cd "$FEED_DIR" && python3 -m http.server 8000 > "$OUT/http.log" 2>&1) &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
sleep 1
curl -sf http://127.0.0.1:8000/feed.xml > /dev/null || fail "the test feed server didn't start"

adb wait-for-device
adb install -r "$APK"
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c

SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}

echo "--- Launching the app (opens on Home)"
adb shell am start -W -n "$PKG/.MainActivity"
wait_for home 'text="Welcome to Podwave"' 30 "Home doesn't show the welcome"
sleep 6
dump home
shot 1-home
grep -q 'text="Popular right now"' "$OUT/home.xml" && echo "PASS: Home suggests popular shows" || echo "WARNING: Home shows no suggestions yet"
echo "PASS: Home"

echo "--- Discover: Apple's top chart and search"
tap home "Discover" last
wait_for discover 'text="Top shows"' 20 "Discover doesn't show 'Top shows'"
grep -q 'content-desc="Search by voice"' "$OUT/discover.xml" || fail "Discover has no voice search button"
echo "PASS: voice search button"
sleep 8
dump discover
shot 2-discover
show discover
grep -q "Couldn't load\|No internet" "$OUT/discover.xml" && echo "WARNING: the top chart didn't load"
tap discover "Shows, episodes, people or topics"
sleep 1
adb shell input text "science%spodcast"
adb shell input keyevent KEYCODE_ENTER
sleep 3
hide_keyboard
wait_for search 'content-desc="Follow [^"]+"' 60 "searching the directory found no shows"
shot 3-search
echo "PASS: directory search finds shows"
tap search "Clear search"
sleep 1

echo "--- Adding the test show by its feed address"
dump discover2
for _ in 1 2 3; do grep -q 'text="Add by feed address"' "$OUT/discover2.xml" && break; scroll_down; dump discover2; done
tap discover2 "Add by feed address"
sleep 1
dump add
tap add "https://example.com/feed.xml"
adb shell input text "http://10.0.2.2:8000/feed.xml"
sleep 1
hide_keyboard
dump add2
tap add2 "Open"
wait_for show 'text="Podwave Test Show"' 40 "the test show's page didn't open"
wait_for show 'text="Episode One With Chapters"' 20 "the test show has no episodes"
shot 4-show
tap show "Follow"
wait_for following 'text="Following"' 20 "following the show didn't work"
echo "PASS: add by feed address and follow"

echo "--- Episode page and playing"
tap following "Episode One With Chapters"
wait_for episode 'text="Chapters"' 20 "the episode page has no chapters"
shot 5-episode
grep -q 'text="Sam Guest"' "$OUT/episode.xml" || echo "WARNING: the guest isn't shown"
tap episode "Play"
for _ in $(seq 1 20); do playing && break; sleep 2; done
playing || fail "the episode didn't start playing"
echo "PASS: the episode plays"
sleep 2
adb shell dumpsys media.audio_flinger > "$OUT/audioflinger.txt" 2>/dev/null || true
if grep -qi "loudness" "$OUT/audioflinger.txt"; then
  echo "PASS: the volume boost is attached ($(grep -i -m1 'loudness' "$OUT/audioflinger.txt" | tr -s ' ' | cut -c1-80))"
else
  echo "WARNING: no loudness enhancer found in the audio system"
fi

echo "--- Full player: chapters, transcript, speed"
sleep 2
dump playing
# The mini player shows the episode's title; tapping it opens the full player.
tap playing "Episode One With Chapters" last
wait_for player 'text="NOW PLAYING"' 15 "the full player didn't open"
shot 6-player
# The full player sits over the episode page, which is still in the dump, so tap the last match.
tap player "Chapters" last
wait_for chapters 'text="The middle part"' 10 "the player's chapters tab is empty"
tap chapters "The middle part" last
sleep 3
dump after-chapter
grep -q 'text="The middle part"' "$OUT/after-chapter.xml" || fail "jumping to a chapter didn't work"
shot 7-chapters
echo "PASS: chapters"
tap after-chapter "Transcript" last
wait_for transcript 'Thanks for having me' 20 "the transcript didn't load"
shot 8-transcript
echo "PASS: transcript"
tap transcript "Playback speed" last
sleep 1
dump speed
tap speed "1.5×"
sleep 1
dump speed2
tap speed2 "Done"
sleep 2
dump player-fast
grep -q 'text="1.5×"' "$OUT/player-fast.xml" || fail "the speed didn't change to 1.5×"
echo "PASS: playback speed"

echo "--- Player gestures: double-tap the artwork, swipe down to close, swipe the mini player up"
tap player-fast "Now playing" last # the tab (the header says NOW PLAYING too, and comes first)
wait_for gestures 'content-desc="Episode artwork"' 10 "the Now playing tab doesn't show the artwork"
read -r AX1 AY1 AX2 AY2 <<<"$(python3 "$HERE/find_text.py" "$OUT/gestures.xml" "Episode artwork" exact-bounds)"
AY=$(((AY1 + AY2) / 2))
# The position the app reports to Android; exact while paused.
pos() { session; awk -v p="package=$PKG" 'index($0, p) { f = 1 } f && match($0, /position=[0-9]+/) { print substr($0, RSTART + 9, RLENGTH - 9); exit }' "$OUT/session.txt"; }
double_tap() { # double_tap <x> <min change in ms, negative for back>: retries, since adb's two taps aren't always quick enough
  local gap before after
  for gap in 0.12 0.15 0.1 0.2 0.08 0.25; do
    before=$(pos)
    adb shell "input tap $1 $AY & sleep $gap; input tap $1 $AY"
    sleep 2
    after=$(pos)
    [ -n "$before" ] && [ -n "$after" ] || fail "couldn't read the playback position"
    echo "double tap at x=$1 (taps $gap s apart): $before -> $after ms"
    if [ "$2" -gt 0 ] && [ $((after - before)) -ge "$2" ]; then return 0; fi
    if [ "$2" -lt 0 ] && [ $((after - before)) -le "$2" ]; then return 0; fi
  done
  fail "double-tapping the artwork at x=$1 didn't move the position by $2 ms"
}
adb shell input keyevent KEYCODE_MEDIA_PAUSE # paused, so the reported position is exact
for _ in $(seq 1 5); do playing || break; sleep 1; done
playing && fail "the media pause key didn't pause"
double_tap $((AX1 + (AX2 - AX1) * 3 / 4)) 25000 # the right half: forward 30 s (the default)
double_tap $((AX1 + (AX2 - AX1) / 4)) -8000 # the left half: back 10 s (the default)
echo "PASS: double-tapping the artwork's sides skips forward and back"
# Back where it was (+30 s - 10 s), so the episode still has time left for the later steps.
dump gestures-paused
tap gestures-paused "Back 10 seconds" last
sleep 1
tap gestures-paused "Back 10 seconds" last
sleep 1
adb shell input keyevent KEYCODE_MEDIA_PLAY
for _ in $(seq 1 5); do playing && break; sleep 1; done
playing || fail "playback didn't resume after the double-tap test"
adb shell input swipe $((W / 2)) $((H * 25 / 100)) $((W / 2)) $((H * 85 / 100)) 300 # swipe down
closed=""
for _ in $(seq 1 5); do
  sleep 1
  dump after-swipe-down
  grep -q 'text="NOW PLAYING"' "$OUT/after-swipe-down.xml" || { closed=yes; break; }
done
[ -n "$closed" ] || fail "swiping down didn't close the full player"
shot 8a-swiped-down
echo "PASS: swiping the player down closes it"
# The mini player shows the episode's title (the episode page underneath does too, earlier in the dump).
read -r MX MY <<<"$(python3 "$HERE/find_text.py" "$OUT/after-swipe-down.xml" "Episode One With Chapters" last)"
adb shell input swipe $MX $MY $MX $((MY - H / 4)) 300 # swipe the mini player up
wait_for player-fast 'text="NOW PLAYING"' 10 "swiping the mini player up didn't open the full player"
echo "PASS: swiping the mini player up opens the full player"

echo "--- Equaliser (from the player's sound options)"
tap player-fast "Trim silence and volume boost" last
wait_for sound 'text="Equaliser"' 10 "the sound options have no Equaliser entry"
tap sound "Equaliser" last
wait_for eq 'content-desc="Equaliser on/off"' 10 "the equaliser screen didn't open"
shot 8a-equaliser
if grep -q "Hz" "$OUT/eq.xml"; then
  tap eq "Equaliser on/off"
  sleep 1
  tap eq "Equaliser bands" # the middle of the curve: sets a band
  sleep 1
  dump eq-on
  grep -q 'checked="true"' "$OUT/eq-on.xml" || fail "the equaliser didn't turn on"
  shot 8a-equaliser-on
  echo "PASS: the equaliser opens and can be adjusted"
elif grep -q "doesn" "$OUT/eq.xml"; then
  echo "PASS: the equaliser opens (this emulator has no equaliser effect, and the screen says so)"
else
  show eq
  fail "the equaliser screen shows neither bands nor an explanation"
fi
adb shell input keyevent KEYCODE_BACK # back to the player
wait_for player-fast 'text="NOW PLAYING"' 10 "Back from the equaliser didn't return to the player"
sleep 1
dump player-fast

echo "--- Clips: save one to the phone, share another"
tap player-fast "Clip this part"
wait_for clip 'text="Save to phone"' 10 "the clip editor didn't open"
shot 8b-clip
tap clip "Save to phone"
saved=""
for _ in $(seq 1 30); do
  saved=$(adb shell ls -l /sdcard/Music/Podwave/ 2>/dev/null | tr -d '\r' | awk '/\.m4a$/ {print $5; exit}' || true)
  [ -n "$saved" ] && break
  sleep 2
done
[ -n "$saved" ] || fail "the clip wasn't saved to Music/Podwave"
[ "$saved" -gt 10000 ] || fail "the saved clip is too small ($saved bytes)"
echo "PASS: a 30-second clip was saved to Music/Podwave ($saved bytes)"
sleep 2
dump player-clip
tap player-clip "Clip this part"
wait_for clip2 'text="Share clip"' 10 "the clip editor didn't open again"
tap clip2 "Share clip"
shared=""
for _ in $(seq 1 30); do
  adb shell dumpsys window | grep "mCurrentFocus" | grep -qi "chooser\|resolver" && { shared=yes; break; }
  sleep 2
done
[ -n "$shared" ] || fail "sharing the clip didn't open the share sheet"
shot 8c-share-clip
adb shell input keyevent KEYCODE_BACK
sleep 2
echo "PASS: sharing a clip opens the share sheet"

echo "--- Background playback"
adb shell input keyevent KEYCODE_HOME
sleep 8
playing || fail "playback stopped when the app went to the background"
adb shell cmd statusbar expand-notifications
sleep 2
shot 9-notification
dump notification
if grep -q 'content-desc="Forward 30 seconds"' "$OUT/notification.xml"; then
  echo "PASS: the media controls show skip buttons"
else
  echo "WARNING: the media controls don't show the skip buttons"
fi
adb shell cmd statusbar collapse
echo "PASS: keeps playing in the background"

echo "--- Up Next and Library"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 2
adb shell input keyevent KEYCODE_BACK # close the full player
sleep 1
dump back-home
tap back-home "Library" last
wait_for library 'text="Podwave Test Show"' 15 "the Library doesn't list the followed show"
shot 10-library
tap library "Podwave Test Show"
wait_for show-again 'text="Episode Two Plain"' 15 "the show page didn't open from the Library"
tap show-again "Add Episode Two Plain to Favourites"
wait_for starred-episode 'content-desc="Remove Episode Two Plain from Favourites"' 10 "adding the episode to Favourites didn't work"
tap starred-episode "More for Episode Two Plain"
sleep 1
dump menu
tap menu "Play last"
sleep 2
dump queued
tap queued "Up Next" last
wait_for upnext 'text="Episode Two Plain"' 10 "Up Next doesn't list the queued episode"
grep -q 'text="NOW PLAYING"' "$OUT/upnext.xml" || fail "Up Next doesn't show what's playing"
shot 11-up-next
echo "PASS: Up Next"

echo "--- Favourites"
tap upnext "Library" last
sleep 1
dump library-again
tap library-again "Favourites"
wait_for starred 'text="Episode Two Plain"' 10 "the favourite episode isn't under Library → Favourites"
shot 11b-starred
echo "PASS: favourites"

echo "--- Player on the lock screen"
# Paused, so the episode can't end and change while the test taps the heart.
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 1
adb shell locksettings set-pin 1111 > /dev/null
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell input keyevent KEYCODE_WAKEUP
sleep 3
dump lock
shot 11c-lock-screen
grep -q 'content-desc="Lock screen player"' "$OUT/lock.xml" || fail "waking the locked phone didn't show the player over the lock screen"
grep -q 'text="Library"' "$OUT/lock.xml" && fail "the library is reachable from the lock screen"
LOCK_EP=$(grep -o 'text="Episode [^"]*"' "$OUT/lock.xml" | head -1 | sed 's/^text="//; s/"$//')
[ -n "$LOCK_EP" ] || fail "the lock-screen player doesn't show the episode"
# The heart: add it to Favourites (or take it out, if it's already there) without unlocking.
if grep -q 'content-desc="Remove from Favourites"' "$OUT/lock.xml"; then BEFORE="Remove from Favourites"; AFTER="Add to Favourites"; else BEFORE="Add to Favourites"; AFTER="Remove from Favourites"; fi
tap lock "$BEFORE"
sleep 1
dump lock-loved
shot 11d-lock-screen-loved
grep -q "content-desc=\"$AFTER\"" "$OUT/lock-loved.xml" || fail "tapping the heart on the lock screen didn't change it"
grep -q 'content-desc="Lock screen player"' "$OUT/lock-loved.xml" || fail "tapping the heart left the lock-screen player"
tap lock-loved "Unlock to open Podwave"
sleep 2
adb shell input text 1111
adb shell input keyevent KEYCODE_ENTER
sleep 3
dump unlocked
grep -q 'text="Library"' "$OUT/unlocked.xml" || fail "unlocking didn't go back to the app"
# Library → Favourites is on screen: the episode is listed there now only if the heart added it.
if [ "$AFTER" = "Remove from Favourites" ]; then
  grep -q "content-desc=\"Remove $LOCK_EP from Favourites\"" "$OUT/unlocked.xml" || fail "the heart tapped on the lock screen wasn't saved to Favourites"
else
  grep -q "content-desc=\"Remove $LOCK_EP from Favourites\"" "$OUT/unlocked.xml" && fail "taking the heart off on the lock screen wasn't saved"
fi
adb shell locksettings clear --old 1111 > /dev/null
adb shell input keyevent KEYCODE_MEDIA_PLAY
echo "PASS: the player shows over the lock screen, and only the player; its heart adds to Favourites"

echo "--- Light theme (the player stays dark)"
tap unlocked "Settings"
wait_for theme-settings 'text="APPEARANCE"' 10 "Settings doesn't start with Appearance"
tap theme-settings "Light"
sleep 2
shot 11c-light-settings
B=$(adb exec-out screencap | python3 "$HERE/brightness.py")
echo "background brightness in the light theme: $B"
[ "$B" -ge 200 ] || fail "choosing Light didn't make the app light (brightness $B)"
dump light-settings
MINI_EP=$(grep -o 'text="Episode [^"]*"' "$OUT/light-settings.xml" | tail -1 | sed 's/^text="//; s/"$//')
[ -n "$MINI_EP" ] || fail "the mini player isn't shown in Settings"
tap light-settings "$MINI_EP" last # the mini player opens the full player
sleep 2
shot 11d-light-player
B=$(adb exec-out screencap | python3 "$HERE/brightness.py")
echo "full player brightness in the light theme: $B"
[ "$B" -le 140 ] || fail "the full player isn't dark in the light theme (brightness $B)"
adb shell input keyevent KEYCODE_BACK # close the full player
sleep 1
dump light-settings2
tap light-settings2 "Dark"
sleep 2
B=$(adb exec-out screencap | python3 "$HERE/brightness.py")
echo "background brightness back in the dark theme: $B"
[ "$B" -le 60 ] || fail "choosing Dark didn't make the app dark again (brightness $B)"
adb shell input keyevent KEYCODE_BACK # leave Settings
sleep 1
echo "PASS: light and dark themes"

echo "--- Following a huge show (1,500 episodes, ~30 MB feed)"
tap unlocked "Discover" last
sleep 2
dump discover3
for _ in 1 2 3 4; do grep -q 'text="Add by feed address"' "$OUT/discover3.xml" && break; scroll_down; dump discover3; done
tap discover3 "Add by feed address"
sleep 1
dump add3
tap add3 "https://example.com/feed.xml"
adb shell input text "http://10.0.2.2:8000/big.xml"
sleep 1
hide_keyboard
dump add4
tap add4 "Open"
wait_for huge 'text="Podwave Huge Show"' 90 "the huge show's page didn't open"
wait_for huge 'text="Huge Episode 1500"' 30 "the huge show's newest episode isn't listed"
tap huge "Follow"
wait_for huge-following 'text="Following"' 90 "following the huge show didn't work"
shot 12-huge-show
echo "PASS: huge feeds load and can be followed"

echo "--- Following a real long-running show from search"
adb shell input keyevent KEYCODE_BACK
sleep 2
dump discover4
tap discover4 "Shows, episodes, people or topics"
sleep 1
adb shell input text "how%sto%sbe%sawesome%sat%syour%sjob"
adb shell input keyevent KEYCODE_ENTER
sleep 3
hide_keyboard
wait_for awesome 'content-desc="Follow How to Be Awesome at Your Job"' 60 "search didn't find How to Be Awesome at Your Job"
tap awesome "Follow How to Be Awesome at Your Job"
end=$((SECONDS + 150))
while [ $SECONDS -lt $end ]; do
  dump awesome-following
  grep -q 'content-desc="Following How to Be Awesome at Your Job"' "$OUT/awesome-following.xml" && break
  sleep 5
done
shot 13-awesome
grep -q 'content-desc="Following How to Be Awesome at Your Job"' "$OUT/awesome-following.xml" \
  || fail "following How to Be Awesome at Your Job (a real 1,200-episode show) didn't work"
echo "PASS: followed How to Be Awesome at Your Job"

echo "--- Home with shows followed"
dump before-home
tap before-home "Home" last
wait_for home-full 'text="Your shows"' 15 "Home doesn't list the shows you follow"
grep -q 'text="Podwave Test Show"' "$OUT/home-full.xml" || fail "Home's 'Your shows' doesn't include the test show"
grep -q 'text="Favourites"' "$OUT/home-full.xml" || echo "WARNING: Home's Favourites row isn't on the first screen"
shot 14-home-following
# With an episode in progress, Continue listening comes first, so this may be further down.
cp "$OUT/home-full.xml" "$OUT/home-latest.xml"
for _ in 1 2 3 4; do grep -q 'text="Latest from your shows"' "$OUT/home-latest.xml" && break; scroll_down; dump home-latest; done
grep -q 'text="Latest from your shows"' "$OUT/home-latest.xml" || fail "Home doesn't show the latest episodes from your shows"
for _ in 1 2 3 4 5 6; do grep -q 'text="SUGGESTED FOR YOU"' "$OUT/home-full2.xml" 2>/dev/null && break; scroll_down; dump home-full2; done
shot 15-home-suggestions
if grep -q 'text="SUGGESTED FOR YOU"' "$OUT/home-full2.xml"; then
  echo "PASS: Home suggests shows ($(grep -o 'text="Top in [^"]*"' "$OUT/home-full2.xml" | head -3 | tr '\n' ' '))"
else
  echo "WARNING: Home shows no suggestions"
fi
echo "PASS: Home lists your shows and their latest episodes"

echo "--- Your recap (from the Library, then Home)"
# Episode one has been playing for minutes by now, so the recap has listening time and the show.
dump before-recap
tap before-recap "Library" last
wait_for recap-library 'content-desc="Your recap"' 15 "the Library has no recap button"
tap recap-library "Your recap"
wait_for recap 'text="of listening"' 15 "the recap didn't open"
sleep 6 # the play log is read again every few seconds
dump recap
shot 16-recap
grep -Eq 'text="([1-9][0-9]* h [0-9]+ min|[1-9][0-9]* min)"' "$OUT/recap.xml" || { show recap; fail "the recap shows no listening time this month"; }
grep -q 'text="Top shows"' "$OUT/recap.xml" || { show recap; fail "the recap has no top shows after listening"; }
grep -q 'text="Podwave Test Show"' "$OUT/recap.xml" || { show recap; fail "the test show isn't in the recap"; }
tap recap "All time"
sleep 2
dump recap-all
grep -q 'text="Podwave Test Show"' "$OUT/recap-all.xml" || fail "All time doesn't include this month's listening"
echo "PASS: the recap shows listening time and the test show"
adb shell input keyevent KEYCODE_BACK
sleep 1
dump recap-back
tap recap-back "Home" last
sleep 2
for _ in 1 2 3 4 5 6 7 8; do adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 150; done # back to the top
sleep 1
dump recap-home
# The whole card on screen: its "Your <month> so far" title and its "See your recap" line.
for _ in 1 2 3 4 5 6 7 8; do
  grep -q 'text="See your recap ›"' "$OUT/recap-home.xml" && grep -q "so far" "$OUT/recap-home.xml" && break
  if grep -q 'text="See your recap ›"' "$OUT/recap-home.xml"; then
    adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H / 2)) 400 # its title is just above the top: back up a little
    sleep 1
  else
    scroll_down
  fi
  dump recap-home
done
grep -q 'text="See your recap ›"' "$OUT/recap-home.xml" || fail "Home has no recap card after listening"
shot 17-home-recap
grep -q "so far" "$OUT/recap-home.xml" || fail "Home's recap card doesn't show this month"
echo "PASS: Home shows this month's recap"

echo "--- Home-screen widget"
adb shell dumpsys appwidget > "$OUT/appwidget.txt"
grep -q "podcasts.PlayerWidget" "$OUT/appwidget.txt" || fail "the home-screen widget isn't registered with the launcher"
echo "PASS: home-screen widget is available"

echo "--- Backup and restore: back up to Download/Podwave, wipe the app (like a new phone), restore"
BK=/sdcard/Download/Podwave
scroll_to() { # scroll_to <dump name> <text>: scroll down until <text> is on screen, above the mini player
  local i xy
  for i in $(seq 1 12); do
    dump "$1"
    if xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2" exact 2> /dev/null) && [ "${xy#* }" -lt $((H * 70 / 100)) ]; then return 0; fi
    scroll_down
  done
  fail "couldn't scroll to '$2'"
}
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 1
# Episode Two goes in Up Next (if it isn't already), so there's a known episode to come back.
dump bk-start
tap bk-start "Library" last
sleep 2
dump bk-library
tap bk-library "Shows" exact
wait_for bk-shows 'text="Podwave Test Show"' 15 "the Library doesn't list the followed show"
tap bk-shows "Podwave Test Show"
wait_for bk-show 'content-desc="More for Episode Two Plain"' 15 "the test show's page didn't open"
tap bk-show "More for Episode Two Plain"
sleep 1
dump bk-menu
if grep -q 'text="Remove from Up Next"' "$OUT/bk-menu.xml"; then adb shell input keyevent KEYCODE_BACK; else tap bk-menu "Play last"; fi
sleep 2
adb shell input keyevent KEYCODE_MEDIA_PAUSE
adb shell input keyevent KEYCODE_BACK # the show page -> Library
sleep 2
dump bk-library2
tap bk-library2 "Settings"
sleep 2
# Settings is long (Appearance comes first), so the section's heading may scroll past before its buttons are low enough to tap.
scroll_to bk-settings-head "BACKUP AND RESTORE"
scroll_to bk-settings "Back up now"
grep -q 'text="Automatic backup"' "$OUT/bk-settings.xml" || fail "Settings has no Automatic backup switch"
tap bk-settings "Back up now" exact
shot 16-backup
ok=""
for _ in $(seq 1 20); do
  sleep 3
  rm -rf "$OUT/backup"
  adb pull "$BK" "$OUT/backup" > /dev/null 2>&1 || continue
  if python3 - "$OUT/backup" > "$OUT/backup-check.txt" 2>&1 <<'PY'
import glob, json, os, sys
d = sys.argv[1]
b = json.load(open(os.path.join(d, "Podwave backup.json")))
assert b["app"] == "Podwave", b.get("app")
lib = b["library"]
shows = [p["podcast"]["title"] for p in lib["podcasts.json"] if p.get("subscribed")]
print("shows in the backup:", shows)
assert "Podwave Test Show" in shows
titles = {}
for name in b["episodes"]:
    for e in json.load(open(os.path.join(d, name))):
        titles[e["id"]] = e["title"]
queue = [titles.get(i, "?" + i) for i in lib["queue.json"]]
print("Up Next in the backup:", queue)
assert "Episode Two Plain" in queue
speed = b["settings"]["settings"]["speed"]
print("speed in the backup:", speed)
assert speed["t"] == "f" and abs(speed["v"] - 1.5) < 1e-6
assert "theme" in b["settings"] and "equaliser" in b["settings"]
assert not glob.glob(os.path.join(d, "**", "*.media"), recursive=True), "downloaded audio is in the backup"
PY
  then ok=yes; break; fi
done
cat "$OUT/backup-check.txt" || true
adb shell "ls -lR '$BK'" | head -20 || true
[ -n "$ok" ] || fail "Back up now didn't save the shows, Up Next and settings in Download/Podwave"
echo "PASS: Back up now saves your shows, Up Next and settings in Download/Podwave"

sleep 5 # let any backup still being written finish
adb shell pm clear "$PKG" # what uninstalling (or a new phone) does to the app's own data
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
wait_for wiped 'text="Welcome to Podwave"' 30 "the app didn't start afresh after clearing its data"
grep -q 'text="Podwave Test Show"' "$OUT/wiped.xml" && fail "the app's data wasn't cleared"
shot 17-wiped
tap wiped "Settings"
sleep 2
scroll_to bk-restore "Restore from backup"
tap bk-restore "Restore from backup" exact
sleep 2
dump restore-explain
grep -q "replaces" "$OUT/restore-explain.xml" || fail "restoring doesn't explain that it replaces what's there"
tap restore-explain "Choose folder"
sleep 4
dump restore-picker
shot 18-restore-picker
python3 "$HERE/find_text.py" "$OUT/restore-picker.xml" "Use this folder" > /dev/null 2>&1 || { tap restore-picker "Podwave"; sleep 2; dump restore-picker; }
tap restore-picker "Use this folder"
sleep 2
dump restore-allow
tap restore-allow "Allow"
wait_for restore-ask 'Restore this backup\?' 30 "picking the Podwave folder didn't offer to restore the backup"
shot 19-restore-ask
grep -q "shows you follow\|1 show you follow" "$OUT/restore-ask.xml" || fail "the restore dialog doesn't say how many shows the backup has"
grep -q "in Up Next" "$OUT/restore-ask.xml" || fail "the restore dialog doesn't mention Up Next"
tap restore-ask "Restore" exact
sleep 6
adb shell input keyevent KEYCODE_BACK # Settings -> Home
wait_for restored-home 'text="Podwave Test Show"' 30 "the followed show didn't come back after restoring"
shot 20-restored-home
tap restored-home "Up Next" last
wait_for restored-upnext 'text="Episode Two Plain"' 20 "Up Next didn't come back after restoring"
shot 21-restored-up-next
tap restored-upnext "Home" last
sleep 2
dump restored-home2
tap restored-home2 "Settings"
sleep 2
dump restored-settings
grep -q 'text="1.5×"' "$OUT/restored-settings.xml" || fail "the playback speed (1.5×) wasn't restored"
adb shell input keyevent KEYCODE_BACK
# Backups now carry on in the restored folder: no "(1)" copies next to the old files.
sleep 15
adb shell "ls -R '$BK'" > "$OUT/backup-files.txt"
cat "$OUT/backup-files.txt"
grep -q "(1)\|(2)" "$OUT/backup-files.txt" && fail "restoring left duplicate backup files"
echo "PASS: after wiping the app, shows, Up Next and settings come back from Download/Podwave"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
