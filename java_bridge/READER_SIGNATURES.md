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
- **Track/scene solo and comments:** `roo.lzE():String` reads the track comment,
  and `roo.fcV():nr` then `nr.oLP():boolean` reads the channel solo state (for
  `TrackGroup`, `view` wraps the group's master channel `roo`). `eNm.Vc():String`
  reads the scene comment.
- **Additional write signatures:** `ApiProject` extends `ApiObject` (the
  compile-time stub must preserve this). `ApiObject.modify(Runnable)` wraps
  raw-model edits. Track `roo.a1y(String)` sets comments, channel
  `nr.jPB(boolean)` sets solo, and scene `eNm.nsm(String)` sets comments.
  `Factory.createTrackGroup()` plus `TrackGroup.addMainTrack(...)` creates
  nested groups/tracks. `Track.audioDestination():AudioDestination` feeds
  `DestinationSelector.setDestination(Destination)`. Cue-marker creation uses
  `Factory.createCueMarker(double)` and timeline `addEvent` inside undo.
  The exact-build `ApiDawProjectImporter(oZU, Project, Function, File)` has
  `convertPhase1()` and `convertPhase2()`, matching `ApiProject.importDawProject`.
  The bridge invokes each phase inside the modification wrapper and a project
  undo transaction. See README for the limited synthetic live import gate.
- **Plugin parameters and state:** `vgq.Oof():brS`; inherited `og3.bla():String`
  is vendor, `brS.qP3():File` returns the saved `.vstpreset` state file on disk
  (`plugins/<name>.vstpreset` in `.dawproject`), and `HT3():List` holds resident
  `GAN` descriptors. `GAN.KF3():int` is the parameter ID, `rqQ():String` its
  name, `YiG():double` the normalized exporter value, `wtf():double` the GUI
  value, `SSK()/AAj():String` display and unit labels. Engine callbacks populate
  the list; an empty resident list does not prove a zero-parameter plugin. Do
  not call `GAN.tPX(double)`, which sends an engine formatting request.
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
  traversal. `device.YhC.Lg():YLB` exposes single-slot chains (`YhC.IYM():String`
  reads the slot name, e.g. `FX`, `CHAIN`); `device.EhV.NFl():List` exposes
  multi-chain containers (`device.AYz` layers, `device.MU2`/`device.YvS`
  multiband/splitter bands, and `device.eiA` multi-out chains, with
  `YLB.az_():String` reading the chain/band key). On plugins (`brS`), inherited
  `og3.Ce():Piy` exposes the plugin FX chain slot (`YhC`) and `og3.vCL():eiA`
  exposes multi-out chains (`EhV`). `Jnx.IYM():List` exposes pads,
  `ujg.n3R():YLB` an existing pad chain; `YLB.nY():Wju` exposes a stored device
  chain. The protected `ApiDeviceChain(Wju)` constructor only wraps that object.
  Do not call `ApiDrumPad.getDeviceChain/getMixer` on an empty pad: they create
  a chain. Discovery is bounded, reports truncation, and caches each device per
  call.
- **Automation:** `roo.HMG().IYM():List` exposes arranger lanes; each lane
  has `DvC():m1C` target and `oLP():jmN` composite timeline. The exporter uses
  `jmN.IYM(fui,double,double,TCM,boolean,boolean,htX)` with its stored context,
  `QsC.IYM()` filter, and `dZ.jPB` end position. The reader implements the same
  read callbacks and type conversions, retaining native targets beyond export
  coverage. Absolute `PROJECT/...` paths do not establish device ownership.
- **Modulators:** `Wcl.YGC():JDY`, then `JDY.Klm():List` exposes stored `gDz`
  entries. Names/presets use the inherited identity getters above; `UNI():UUID`
  is nullable. `EHY.dsw():nJS` returns the modulator `CONTENTS` component.
  Stored modulator parameters are `Ob`/`KOL`/`mqD` atoms walked through
  `hMI():List`, addressed by `fvT.IYM(deb)` (`CONTENTS/...`), with values read
  via `Rem.wtf():double`, `HDw.wtf():boolean`, `dZZ.Qcu():int`, and units from
  `m1C.ViH():fG`. Modulation bindings live on `Hx3` child components of `nJS`
  (`AwX.gTC():List` of `AvA`, component key `az_():String`); `CzJ.kxC():QFL`
  holds the target reference, `kX1.XYl():m1C` the target parameter, and
  `CzJ.Qcu():double` the model-native amount. Target paths resolve by `m1C`
  identity against owning/top-level device atom maps or migrated
  `dY3.TEI():String`; target owners resolve via `dY3.hMI():kX1`,
  `Ovr.U3o():Wcl`, and bounded `kX1.L_():kX1` parent traversal to the enclosing
  top-level device, with `QFL.xTe():roo` as track fallback (`dY3.Lg()` and
  `dY3.heQ()` are legacy serialization fields migrated to `hMI()`/`TEI()` at
  load time). Grid devices/modulators are read only as stored value atoms,
  never through engine or grid graph methods.

No observers, engine graph search, launcher writes, project saves, controller
changes, catalog capture, or recreation/import are part of these readers.


## Native copy writing and project selection (6.1.5)

- `eY1.kxC().WEC():aBx` selects the current project tab, whose `wtf():IPw`
  is the native document. `eY1.Trh(File):aBx` finds an already open file;
  selection uses `aCy.jPB(aBx)`. Document identity uses `BEq()` (filename),
  `UBi()` (directory), and `IT3()` (display name). `p3P()` is a UUID.
- `m1G.Gcr()` returns the native serializer. Inherited `jPB(Object):byte[]`,
  `IYM(byte[]):Object`, and `jPB(Object,File)` provide detached cloning and
  native output. Byte-array loads need explicit `Qcu(directory)`,
  `KF3(filename)`, then `SOE()` before the clone's media index is populated.
- `oZU.mfz().IYM():List<fe>` indexes media. `fe.xeZ():File` resolves files;
  `jPB(String)` sets project-relative locations, `jPB(MU2)` sets package
  identity (null removes it), and `jPB(File)` supplies the collected file.
  `oZU.iz_()` disposes the detached clone. `mfE.AOA(File)` creates the empty
  `.bitwig-project` marker used by the normal loader; without it, opening
  from an arbitrary directory can require a folder-conversion dialog.
- `oZU.IYM(Set<String>)` collects plugin state identifiers; `ri2.Gcr(String)`
  resolves their files. `new Y3m().IYM(String,File)` registers resources;
  `com.bitwig.base.serial.file.rdP.IYM(File,mgr)` appends them to the native
  file at `plugin-states/<identifier>`, matching normal Save As behavior.

These writer signatures are separate from the read-only reader contract above.


## Arranger Duplicate and Push (6.1.5)

`pZb.mjf().wtf(List<zJD>, double start, double end, Lsv)` runs the native
Duplicate-and-Push edit. `oZU.Qjx().bV_()` addresses the root arranger tree;
`oZU.qVp().TcG().tY1()` supplies its editor selection. The implementation
inserts empty time via `XCv.IYM(start, length, fKr, Set)` across all affected
clip/automation timelines, moves cue markers when the root is selected, and
copies the range with `HRW.IYM(..., false, true, fKr)`. The native command owns
its undo transaction; do not nest it in a second synthetic transaction.
Live probe: clips 0/8 -> 0/4/12, marker 8 -> 12, automation moved and copied.


Empty-time insertion uses `pZb.Gcr(List<zJD>, start, end, Lsv)`; delete-and-pull
uses `pZb.AOA(List<zJD>, start, end, Lsv)`. Both are native transaction-owning
commands using the same root/editor selections as Duplicate and Push. Live gate:
`tests/live_java_ranges.py` verified nested crossing clips, later clips,
effect/master automation and cue markers in both directions. Automation creation
through the public API prepends a `GUX` beat-zero anchor for a nonzero first point;
shape application must offset caller points past that anchor and validate times.

### Verified clip writes and local audio (6.1.5)

- `Clip` setters cover time/duration/title/mute/color/loop/play offset;
  `AudioClip.setFadeInTime/OutTime` use beats. Detach attached events with
  native timeline `IYM(No, boolean=false)` before changing positions; reattach
  via `ClipTimeline.addEvent` so sorting is updated. `clearTime(start,end)`
  deletes an isolated clip. Requests reject overlapping ranges before mutation.
- `Factory.createAudioClip(false)`, `createSampleReferenceForLocalFile(File)`,
  `createAudioNote(time,duration,SampleReference)` create RAW audio: event time
  and duration use beats; raw `KoO.UJq(double)` sets source offset in seconds.
  `AudioNote.getGain().setValue(value, Unit.DECIBEL)` stores gain correctly.
  Native transport `oZU.THS().xeZ(beats)` maps to seconds, `.AOA(seconds)`
  maps back to beats. These derive default duration at the insertion position.
- Media identity for detached native save uses `fe.Gcr()` name, `tDS()` relative
  location, `kAg()` package identity, `xTe()` length and `QmZ().AOA()` absolute
  source location (when present). Source/clone indexes are NOT stable after
  deleting media: unused entries disappear during serialization. Match identities
  and resolve source files from the live index; reject ambiguous matches.

### Native copy/split and MIDI expressions (6.1.5)

- Raw `xh3.VxG()` clones a clip, preserving content/settings. Initialize the
  cloned object's native identity with `nvG.IYM(kX1)`, wrap ME2/ukL via the public
  ApiInstrumentNoteClipEvent/ApiAudioNoteClipEvent constructors, attach it to the
  destination, then call `xh3.knw()` to make its referenced content unique.
  `knw()` invokes `IW()` / `xh3.IYM(List)`, cloning shared content references.
- `XCv.IYM(L3, double, ydz, wYJ)` is the transaction-owning Split Event entry;
  `iGQ.IYM(Bq2,double,fKr,wYJ)` supplies clip-specific offset/content behavior.
  Context is `oZU.qVp().TcG().tY1().gn_()` (fKr). Callback can be null. Reject
  boundary/outside splits before invoking. Never nest another undo transaction.
- MIDI replacement makes content unique, reads the resulting current content
  timeline, clears its full note range and attaches staged InstrumentNotes in
  one native undo transaction. `InstrumentNote.setIsMuted/setChance` and
  getGain/getPan/getTranspose/getTimbre/getPressure are available.
- `ApiNoteExpression.setEvents(AutomationEvents,Unit)` writes local beat/value
  curves; `Factory.createDoubleAutomationEvents(Unit)` plus `.addPoint` stages
  them. Readback verifies gain domain conversion, pressure/timbre percentages,
  semitone transpose, channel/velocities/chance, and persistence. This does not
  establish audible audio-clip transpose support.
