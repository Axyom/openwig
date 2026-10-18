# Exact-build reader evidence

These read paths were checked with JDK 21 `javap -p -c` and CFR 0.152 against
the original Bitwig 6.1.5 JAR, SHA-256
`d56248d003101ead1381c8a4fd3e256d933773beccbeb3366c9abe744de41a49`.
Detailed decompilations and private-project artifacts remain in ignored scratch
storage. Live claims and remaining coverage are recorded in README and
`JAVA_BRIDGE_GAPS.md`; signatures alone do not establish live coverage.

- **Scene/slot bounds:** `oZU.Qjx():Yko` and inherited `fkP.ce_():int` return
  existing counts. `ApiProject.getScene(int)` creates missing scenes, and
  `ApiTrack.clipSlot(int)` appends missing slots. Never probe past the count.
- **Identity:** model `Wcl` inherits `HT3():String` (device name),
  `ruM():String` (rename), `SVb()/Sxv():String` (preset names), and
  `Jxd():eOc` (role). Native `tjt.odb():MD`, then `MD.Gcr():UUID` supplies
  identity. Plugin `vgq.urJ():LXO`, then `LXO.Gcr():String` supplies identity.
  `eOc` field identifiers differ from `Enum.name()`; names are
  `AUDIO_TO_AUDIO`, `NOTE_TO_NOTE`, `NOTE_TO_AUDIO`, `AUDIO_TO_NOTE`.
- **Plugin parameters:** `vgq.Oof():brS`; inherited `og3.bla():String` is
  vendor and `HT3():List` holds resident `GAN` descriptors. `GAN.KF3():int`
  is the parameter ID, `rqQ():String` its name, `YiG():double` the normalized
  exporter value, `wtf():double` the GUI value, `SSK()/AAj():String` display
  and unit labels. Engine callbacks populate the list; an empty resident list
  does not prove a zero-parameter plugin. Do not call `GAN.tPX(double)`, which
  sends an engine formatting request.
- **Routing:** API source/destination selectors wrap stored `qWE.LZe():iml`
  and `hGY.LZe():pys`. `Destination.getTrack()` uses `pys.Wqw():roo`.
  `q3G.IxG():String` reads a stored endpoint label. Match model identities from
  `preFader`, `postFader`, `postFxNotes`, and `DeviceChain.preFx` to track paths.
  Plugin sidechain uses `brS.Idv().APa().LZe()`; native `WYJ` inherits the same
  stored selector getter from `Fij`. Avoid `kdJ.Qcu()` and reactive `toString()`.
- **Notes:** `PNT` getters `HT3/Mqo` read chance enable/value;
  `WEC/tY1` occurrence enable/enum; `kAg/QmZ` recurrence enable/mask;
  `ap/XYl/QPT` repeat enable/signed count/curve. Recurrence period is
  `1 + (mask & 7)` and iteration bits start at bit 3. Repeat subdivisions are
  `abs(count) + 1`; the raw signed count is retained.
- **Expressions:** `PNT.smZ().CZR():List` visits existing `BbA` timelines.
  `sRk.bXk():ust` identifies the expression, `hRH.IYM():List` its points,
  `lGQ.eUA():bL` its value type. `GUX.tS/IYM/AOA/dT_` read beat/value/
  interpolation/curvature. `fK.IYM(double,fG,fH)` converts stored domains to
  exporter units; gain storage must not be mislabeled linear. No timeline
  creation getter is used. RAW audio expression time is seconds.
- **Audio:** `AudioClip.getContent().getEventTimeline().getEvents()` contains
  `AudioNote` sample events. Its API supplies file/rate/channels/duration and
  stretch settings. Model `KoO.jsk().wtf():boolean` reads reverse;
  `TdG().jPB():String` reads the export algorithm. Read original warp markers
  through `KoO.p3P():GLs`, `GLs.WEC():int`, `GLs.Gcr(int):LTX`, then
  `LTX.tS()/IYM():double`. The API's copied warp object drops some stored
  markers; its validity flag is labeled `api_warp_markers_valid` separately.
- **Nesting:** stored component children use the existing `hMI():List`
  traversal. `device.AYz.NFl():List` exposes layers;
  `Jnx.IYM():List` exposes pads, `ujg.n3R():YLB` an existing pad chain;
  `YLB.nY():Wju` exposes a stored device chain. The protected
  `ApiDeviceChain(Wju)` constructor only wraps that object. Do not call
  `ApiDrumPad.getDeviceChain/getMixer` on an empty pad: they create a chain.
  Discovery is bounded, reports truncation, and caches each device per call.
- **Automation:** `roo.HMG().IYM():List` exposes arranger lanes; each lane
  has `DvC():m1C` target and `oLP():jmN` composite timeline. The exporter uses
  `jmN.IYM(fui,double,double,TCM,boolean,boolean,htX)` with its stored context,
  `QsC.IYM()` filter, and `dZ.jPB` end position. The reader implements the same
  read callbacks and type conversions, retaining native targets beyond export
  coverage. Absolute `PROJECT/...` paths do not establish device ownership.
- **Modulator inventory:** `Wcl.YGC():JDY`, then `JDY.Klm():List` exposes
  stored `gDz` entries. Names/presets use the inherited identity getters above;
  `UNI():UUID` is nullable. The verified boundary is metadata inventory;
  mappings, parameters and Grid internals are not read through engine methods.

No observers, engine graph search, launcher writes, project saves, controller
changes, catalog capture, or recreation/import are part of these readers.
