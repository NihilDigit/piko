#!/bin/bash
# Piko macOS updater, started detached by DesktopAppUpdater right before the app exits.
# Waits for the app to exit, replaces the whole .app bundle with the one inside the staged DMG,
# then opens the app again.
#
# The bundle is replaced as a unit, never file by file: the app is code-signed (ad hoc) and
# changing any file inside a sealed bundle breaks the seal. The new bundle is copied next to
# the old one first (same volume, so the swap is two renames), and the old one is kept until
# the new one is in place; a failure leaves the old bundle where it was.
#
# Arguments: <pids comma-separated> <bundle path> <dmg path> <checksums file> <log file>
set -u
pids="$1"; bundle="$2"; dmg="$3"; checksums="$4"; log="$5"
staging="$(dirname "$log")"

say() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$log"; }
fail() {
    say "update failed: $*"
    # Read by DesktopAppUpdater on the next launch, which then says the update did not finish.
    printf '%s\n' "$*" > "$staging/failed"
    relaunch
    exit 1
}
relaunch() {
    open "$bundle" >> "$log" 2>&1 && say "opened $bundle" || say "could not open $bundle"
}

say "update started: pids=$pids bundle=$bundle"
deadline=$((SECONDS + 120))
for pid in ${pids//,/ }; do
    while kill -0 "$pid" 2>/dev/null; do
        # Nothing touched yet. A still running app only gets activated by the open in fail().
        if (( SECONDS > deadline )); then fail 'the app did not exit within 120 s'; fi
        sleep 0.2
    done
done
say 'app exited'

# After the exit, not before: until then the app can still write to the staging dir.
# A missing or empty list fails too: a failed redirect would skip the loop and install unchecked.
[ -s "$checksums" ] || fail "checksum list missing or empty: $checksums"
verified=0
while read -r expected relative; do
    [ -z "$expected" ] && continue
    [ -f "$staging/$relative" ] || fail "staged file missing: $relative"
    actual="$(shasum -a 256 "$staging/$relative" | cut -d' ' -f1)" || fail "could not hash $relative"
    [ "$actual" = "$expected" ] || fail "staged file changed: $relative ($actual != $expected)"
    verified=$((verified + 1))
done < "$checksums"
[ "$verified" -gt 0 ] || fail 'checksum list has no entries'
say "verified $verified staged files"

mount="$(mktemp -d "$staging/mount.XXXXXX")"
hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$mount" "$dmg" >> "$log" 2>&1 || fail 'hdiutil attach failed'
source_app="$(find "$mount" -maxdepth 1 -name '*.app' -type d | head -n 1)"
# Names of this run only: a Piko.app.old the user keeps as a backup must never be touched.
# Only paths this script created are ever removed.
new="$bundle.piko-update-new.$$"
old="$bundle.piko-update-old.$$"
if [ -e "$new" ] || [ -e "$old" ]; then
    hdiutil detach "$mount" -force >> "$log" 2>&1
    fail "temporary path already exists: $new or $old"
fi
if [ -z "$source_app" ]; then
    hdiutil detach "$mount" -force >> "$log" 2>&1
    fail 'no .app in the DMG'
fi
ditto "$source_app" "$new" >> "$log" 2>&1
copied=$?
hdiutil detach "$mount" -force >> "$log" 2>&1
rmdir "$mount" 2>/dev/null
[ $copied -eq 0 ] || { rm -rf "$new"; fail 'copying the new bundle failed'; }
if ! codesign --verify --deep --strict "$new" >> "$log" 2>&1; then
    # Which resource broke the seal: without it a failure here says nothing about the cause.
    codesign --verify --deep --strict -vvvv "$new" >> "$log" 2>&1
    rm -rf "$new"
    fail 'the new bundle does not pass codesign --verify'
fi
# The DMG came from the app's own download, which is not quarantined, but a copy from a
# quarantined image inherits the flag and Gatekeeper would then refuse the unnotarized app.
xattr -dr com.apple.quarantine "$new" 2>/dev/null

mv "$bundle" "$old" || { rm -rf "$new"; fail 'moving the old bundle aside failed'; }
if ! mv "$new" "$bundle"; then
    mv "$old" "$bundle"
    fail 'moving the new bundle into place failed'
fi
rm -rf "$old"
say 'update applied'
# Only after success: a failed update is retried from the same download. The logs stay.
rm -f "$dmg" "$checksums"
relaunch
exit 0
