# Garmin Connect (fork feature)

This fork connects NOOP to Garmin Connect in two directions. Upstream NOOP has neither; see the fork
section of the [README](../README.md). Both are available on iOS, macOS and Android.

```
Garmin device (e.g. Edge 840) ──sync──▶ Garmin Connect ──auto──▶ Strava
                                           ▲        │
                 FIT upload (you, on the   │        │  Personal API (read-only, automatic)
                 Garmin Connect website)   │        ▼
                                          NOOP
```

There is no Strava integration in NOOP: Garmin Connect forwards activities to Strava once Strava is
connected in Garmin Connect (Settings → Connected Apps).

## Importing Garmin activities into NOOP

NOOP reads your activities with Garmin's official **Personal API** (launched October 2026). It needs
no server and no Garmin developer account.

1. In Garmin Connect, create a Personal API key:
   [connect.garmin.com/app/settings/personalApiToken](https://connect.garmin.com/app/settings/personalApiToken).
2. In NOOP, open **Data Sources → Garmin Connect**, paste the key and save it.
3. Tap **Sync now**. The first sync reads the last 30 days; later syncs continue from the newest
   activity seen. With **Import automatically** on, NOOP also syncs when the app comes to the
   foreground, at most once an hour.

Each activity is imported from its original FIT file: GPS route, per-second heart rate, altitude,
distance, calories, and Garmin's own Training Effect and load (shown in the workout note). Imported
workouts use the source `garmin-connect`. Their heart rate feeds the day's Effort the same way an
imported activity file does.

Removing the key stops future syncs; already imported workouts stay.

## Sending NOOP workouts to Garmin Connect

Garmin offers no upload API for individuals, so this step is manual but official:

1. Open a workout recorded in NOOP and choose **Send to Garmin Connect**. NOOP builds a FIT file with
   the strap's per-second heart rate, the GPS route (with real timestamps and altitude for workouts
   recorded from this version on) and the workout summary, and opens the share sheet.
2. Upload the file at Garmin Connect → **Import Data**
   ([connect.garmin.com/modern/import-data](https://connect.garmin.com/modern/import-data)).
3. If Strava is connected in Garmin Connect, Garmin forwards the workout to Strava.

**Device label.** Garmin Connect only credits badges and challenges for files from a Garmin device,
so the file names a device you pick under **Data Sources → Garmin Connect → Exported files identify
as**:

| Option | Effect (tested 2026-11-19) |
| --- | --- |
| Forerunner 945 (default) | Imported as an original device recording, with HR graph and badges, forwarded to Strava. No Training Effect. |
| Tacx Training App | Same, plus a "First Tacx Ride" badge. No Training Effect for an outdoor run either. |
| NOOP (no Garmin device) | Imported, but Garmin credits no badges. |

Garmin Connect does not calculate Training Effect or Training Load for uploaded files with either
label, so these runs do not yet count toward Garmin's Training Status. That is an open question in
this fork.

## Duplicates

- A workout NOOP sent to Garmin comes back through the Personal API. NOOP recognises it (it overlaps a
  workout recorded in NOOP) and does not import it a second time.
- The same Garmin ride arriving through Health Connect or Apple Health as well collapses into one
  workout in the lists; the Garmin import is kept because it carries the original file's data.
- A workout the strap detected during a Garmin ride is hidden behind the ride.

## Privacy

Network details are in [`PRIVACY_SECURITY.md` §1.1e](PRIVACY_SECURITY.md#11e-garmin-connect-import-fork-feature-off-until-a-key-is-saved).
In short: nothing talks to Garmin until you save a key, the key stays in the Keychain or Android's
encrypted storage, and the import only reads from Garmin. Sending to Garmin is a file you upload
yourself.

## Known limits

- Workouts recorded before this version have no per-point GPS timestamps, so their exported pace curve
  is evened out over the workout (distance and time are correct).
- On Android, the timed GPS track lives in a side file next to the database. It is not part of a
  `.noopbak` backup, and editing a workout's start time or sport detaches it; the export then falls
  back to the evened-out route.
- The integration has not yet been tested against the live Garmin API from the apps, only with the
  same requests from a script.
