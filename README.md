# ProWLED

A mobile-friendly web UI for controlling [WLED](https://kno.wled.ge/) devices on your local network.

## New Features

- Sound-reactive mode (microphone)
- LED Mapper for custom LED layouts


## Usage

Built with [Capacitor](https://capacitorjs.com/) — runs as a native app on Android.

Add your device IP in the **Devices** tab and tap **Connect**. The device must be on the same local network.

## Sound Reactive Mode

Sound Reactive Mode relies on a native Capacitor plugin (`SoundReactive`) that runs inside the mobile app wrapper. The plugin captures audio on the device (microphone), processes it natively, and streams the resulting LED data **directly to the WLED device over UDP** — bypassing the HTTP JSON API entirely.

From the app's side, tapping **Start** calls `plugin.start()` with the WLED IP, LED count, chosen effect, color settings, sensitivity, and noise floor. The plugin handles the audio capture loop and UDP transmission independently. The app can call `plugin.setEffect()` mid-stream to update parameters without stopping.

Because the UDP stream is handled natively, **this feature does not work in a plain browser** — it requires the app to be running inside its Capacitor shell with the plugin present. If the plugin is absent, Start will show an error.

Effects available: GEQ Spectrum (16-band frequency visualizer), Ripple, Strobe, VU Meter, and Twinkle.

Note: System audio is not implemented as of now, its just a place holder.

---

## LED Maps

WLED's LED map feature remaps the physical order of LEDs to a virtual order. This is useful when strips double back or are arranged non-linearly — so effects render spatially correct rather than following the wire order.

In the **Ledmap** tab you draw lines on a canvas to represent strips or sections. Each line needs a **start LED index** and an **end LED index** (the physical indices as WLED numbers them). If end is lower than start, the segment is treated as reversed.

Once all lines have their ranges set, tapping **Save** builds a JSON object (`{"map": [...]}`) where each position in the array contains the virtual index that maps to that physical position. This is uploaded to the device at `/edit` as `ledmap.json`, then WLED reboots automatically to apply it.

Tapping **Reset** uploads `{"map": []}`, clearing the custom map and restoring default linear order, then reboots.

The progress bar shows how many LEDs your lines cover versus the device's total — a sanity check before uploading.

Layout lines are saved locally per device IP in `localStorage` so they persist between sessions.

---

## Notes

- Tested against WLED 0.14+. Some config features may not work on older firmware.
- Color wheel requests are throttled to ~20/sec to avoid overwhelming the device.
