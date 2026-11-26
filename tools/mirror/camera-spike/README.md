<!-- SPDX-License-Identifier: MIT -->
# Mirror camera spike (`dev.glassworks.camspike`)

A throwaway Android app that opens the Mirror's camera in each way the camera pane could, and logs what arrives:
frame counts and rates, and for every 30th frame the mean of Y, the share of zero pixels and the share of rows that
are all zero. It found the blank sessions in
[camera-stock.md](../../../frames/lululemon-mirror/research/camera-stock.md). It never writes outside its own
folder on the Mirror, and the camera is open for at most 20 s a run.

## Build and run

```sh
tools/mirror/camera-spike/build.sh                         # out/camspike.apk, no device, no network
export MIRROR_ADB="adb -s <serial>"
$MIRROR_ADB install -r -g tools/mirror/camera-spike/out/camspike.apk
tools/mirror/camera-spike/run.sh c1c --es test c1 --es disp st     # one run, results in out/c1c/
$MIRROR_ADB uninstall dev.glassworks.camspike               # when done
```

Close Glassworks' camera first (camera privacy on): two apps can't hold the camera, and connecting while another
app holds it crashed the Mirror's `mediaserver`.

## The runs

Each run is one fresh camera session. `run.sh <name>` passes `--es name <name>`; the rest are the extras below.

| Extra | Values | What it does |
|---|---|---|
| `test` | `c1`, `c1enc`, `c2`, `info` | Camera1 with a preview callback; Camera1 feeding the hardware H.264 encoder's buffers; Camera2 like the pane; the encoder's color formats and the camera's Camera1 parameters |
| `disp` | `st` (default), `gl`, `ir` | Camera1's preview target: a SurfaceTexture nobody reads, a GL consumer, an ImageReader |
| `set` | `key=value;…` | Camera1 parameters to set first, e.g. `picture-size=1920x1080` or `recording-hint=true` |
| `c2out` | `both` (default), `reader`, `enc` | Camera2's outputs: an ImageReader (YUV_420_888 1280x720) and the encoder's input surface, one of them, or both in one repeating request at 30 fps |
| `fps` | `lower,upper`, e.g. `15,15`, or `template` | Camera2's AE target fps range, instead of the highest-floor range that tops out at 30; `template` keeps the request template's own range (the log prints it either way) |
| `fmt` | `0x15` (default) or `0x27` | `c1enc`'s encoder input format: YUV420SemiPlanar or YUV420PackedSemiPlanar, the two whose layout the probe writes (semi-planar NV12). Any other format fails the run |

The commands behind the note's table, in the order they ran. The spike grew during the session (the
first runs came before the `disp` extra, with the SurfaceTexture target it now defaults to); with this source, these
commands give each run's settings:

```sh
run.sh info --es test info
run.sh c1 --es test c1
run.sh c1-hint --es test c1 --es set 'recording-hint=true'
run.sh c1-pic --es test c1 --es set 'picture-size=1920x1080'
run.sh c1-gl --es test c1 --es disp gl
run.sh c1-pic2 --es test c1 --es set 'picture-size=1920x1080' --es disp st
run.sh c1enc --es test c1enc --es set 'picture-size=1920x1080' --es disp st
run.sh c2 --es test c2
run.sh c2b --es test c2
run.sh c1b --es test c1 --es disp gl
run.sh c2r --es test c2 --es c2out reader
run.sh c1c --es test c1 --es disp st
run.sh c2e --es test c2 --es c2out enc
```

The fps comparison in the note's attempt 6 (`a` and `b` are two passes of the same ranges, each run a fresh
session) is `fps.sh`, which runs the Camera2 reader once per range with the spike force-stopped in between, and
reads how much of a saved frame is the shim's drawn green:

```sh
fps.sh a30:30,30 a15:15,15 a24:24,24 a730:7,30 b30:30,30 b15:15,15
fps.sh t1:template t2:template t3:template    # the request template's own range
```

`run.sh` gives each launch its own id and takes only the result that carries it. It stops with an error at once when
the spike ignores the launch (one without `--es test`, or one while a run is still going), and after 60 s when the
spike doesn't finish, so it never shows another run's log. A run name is one folder name.

Each writes `out/<name>/log.txt` and, for good sessions, a JPEG or PNG of a frame. Saved frames show the room:
don't commit or share them.
