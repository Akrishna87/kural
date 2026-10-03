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
ffmpeg -loglevel error -f lavfi -i "sine=frequency=440:duration=240" -f lavfi -i "anoisesrc=d=240:a=0.02" \
  -filter_complex "[0][1]amix=inputs=2,volume=2" -ac 1 -ar 22050 -b:a 48k "$FEED_DIR/ep1.mp3"
ffmpeg -loglevel error -f lavfi -i "sine=frequency=660:duration=120" -ac 1 -ar 22050 -b:a 48k "$FEED_DIR/ep2.mp3"
cat > "$FEED_DIR/ep1.vtt" <<'VTT'
WEBVTT

00:00:00.000 --> 00:00:05.000
<v Host>Welcome to the Kural test show.

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
    <title>Kural Test Show</title>
    <link>https://github.com/Akrishna87/Akrishna87</link>
    <description>A show made up for the automatic test.</description>
    <itunes:author>Test Host</itunes:author>
    <item>
      <title>Episode One With Chapters</title>
      <guid>ep-1</guid>
      <pubDate>$NOW_RFC</pubDate>
      <enclosure url="http://10.0.2.2:8000/ep1.mp3" length="1440000" type="audio/mpeg"/>
      <itunes:duration>4:00</itunes:duration>
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
    f.write('<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>Kural Huge Show</title>')
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
wait_for home 'text="Welcome to Kural"' 30 "Home doesn't show the welcome"
sleep 6
dump home
shot 1-home
grep -q 'text="Popular right now"' "$OUT/home.xml" && echo "PASS: Home suggests popular shows" || echo "WARNING: Home shows no suggestions yet"
echo "PASS: Home"

echo "--- Discover: Apple's top chart and search"
tap home "Discover" last
wait_for discover 'text="Top shows"' 20 "Discover doesn't show 'Top shows'"
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
wait_for show 'text="Kural Test Show"' 40 "the test show's page didn't open"
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
wait_for library 'text="Kural Test Show"' 15 "the Library doesn't list the followed show"
shot 10-library
tap library "Kural Test Show"
wait_for show-again 'text="Episode Two Plain"' 15 "the show page didn't open from the Library"
tap show-again "More for Episode Two Plain"
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

echo "--- Following a huge show (1,500 episodes, ~30 MB feed)"
tap upnext "Discover" last
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
wait_for huge 'text="Kural Huge Show"' 90 "the huge show's page didn't open"
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
grep -q 'text="Kural Test Show"' "$OUT/home-full.xml" || fail "Home's 'Your shows' doesn't include the test show"
grep -q 'text="Latest from your shows"' "$OUT/home-full.xml" || fail "Home doesn't show the latest episodes from your shows"
shot 14-home-following
for _ in 1 2 3 4 5 6; do grep -q 'text="SUGGESTED FOR YOU"' "$OUT/home-full2.xml" 2>/dev/null && break; scroll_down; dump home-full2; done
shot 15-home-suggestions
if grep -q 'text="SUGGESTED FOR YOU"' "$OUT/home-full2.xml"; then
  echo "PASS: Home suggests shows ($(grep -o 'text="Top in [^"]*"' "$OUT/home-full2.xml" | head -3 | tr '\n' ' '))"
else
  echo "WARNING: Home shows no suggestions"
fi
echo "PASS: Home lists your shows and their latest episodes"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
