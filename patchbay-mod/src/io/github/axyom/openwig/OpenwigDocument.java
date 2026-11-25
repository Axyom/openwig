package io.github.axyom.openwig;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.api.project.Factory;
import com.bitwig.extension.api.project.ProjectMetadata;
import com.bitwig.extension.api.project.Transport;
import com.bitwig.extension.api.project.timeline.TimeSignature;
import com.bitwig.dawproject.DawProject;
import com.bitwig.dawproject.MetaData;
import com.bitwig.extension.api.project.parameter.BooleanParameter;
import com.bitwig.extension.api.project.parameter.DoubleParameter;
import com.bitwig.extension.api.project.parameter.EnumParameter;
import com.bitwig.extension.api.project.parameter.Unit;
import com.bitwig.extension.api.project.parameter.AutomationEvents;
import com.bitwig.extension.api.project.timeline.AudioClip;
import com.bitwig.extension.api.project.timeline.ClipTimeline;
import com.bitwig.extension.api.project.timeline.Clip;
import com.bitwig.extension.api.project.timeline.CueMarker;
import com.bitwig.extension.api.project.timeline.EventTimeline;
import com.bitwig.extension.api.project.timeline.InstrumentNote;
import com.bitwig.extension.api.project.timeline.NoteClip;
import com.bitwig.extension.api.project.track.Track;
import com.bitwig.extension.api.project.track.TrackGroup;
import com.bitwig.extension.api.project.track.TrackOrTrackGroup;
import com.bitwig.extension.api.project.track.TrackType;
import com.bitwig.extension.api.project.mixer.Mixer;
import com.bitwig.extension.api.project.mixer.DeviceChain;
import com.bitwig.extension.api.project.mixer.NativeDevice;
import com.bitwig.extension.api.project.mixer.PluginDevice;
import com.bitwig.extension.api.project.mixer.Send;
import com.bitwig.extension.api.project.mixer.Vst2Device;
import com.bitwig.extension.api.project.mixer.Vst3Device;
import com.bitwig.extension.api.project.mixer.Device;
import com.bitwig.extension.api.project.track.MonitorMode;
import com.bitwig.flt.document.api.document.ApiProject;
import com.bitwig.flt.document.api.document.ApiObject;
import com.bitwig.flt.document.core.master.GJy; // the project's effect track group
import com.bitwig.flt.document.core.master.sJU; // a track item of a track group
import com.bitwig.flt.document.core.master.CmH; // a project document
import bitwig.obf.aCG; // an open project tab
import com.bitwig.flt.app.gEH; // the application
import com.bitwig.flt.app.lfZ; // project open flags
import com.bitwig.base.async.Pv; // a completion callback
import com.bitwig.flt.document.core.master.TzU; // a document with a file
import bitwig.obf.Ae2; // a parameter model that can be automated
import bitwig.obf.BrN; // an automation lane's list of points
import bitwig.obf.QQZ; // an automation lane
import bitwig.obf.aG; // a parameter's value range
import bitwig.obf.eIn; // a parameter's automation
import bitwig.obf.g13; // an automation point
import bitwig.obf.r1; // an automation interpolation mode
import bitwig.obf.xdh; // a parameter model with a value range
import com.bitwig.flt.document.core.master.device.Dr; // a plugin-state save callback
import com.bitwig.flt.document.core.master.device.Mv; // a plugin device
import com.bitwig.flt.document.core.master.device.R1c; // a plugin instance holder
import com.bitwig.flt.document.core.master.device.ogX; // a plugin parameter
import com.bitwig.flt.document.core.master.device.sgH; // a device
import com.bitwig.flt.document.core.master.device.utt; // a native device
import com.bitwig.flt.document.core.master.device.Mmq; // a modulator's contents
import com.bitwig.flt.document.core.master.device.QFr; // a modulator
import com.bitwig.flt.document.core.master.device.dr2; // a modulation binding
import com.bitwig.flt.document.core.master.device.itZ; // a device's modulator bank
import com.bitwig.flt.document.core.master.SZB; // a stored parameter reference
import com.bitwig.ramona.core.IqI; // a model object with an owner
import bitwig.obf.AZT; // a parameter that knows its device
import bitwig.obf.CHK; // a boolean parameter atom
import bitwig.obf.OMh; // a parameter atom with a label
import bitwig.obf.QCX; // a named model object
import bitwig.obf.dQh; // a Macro knob
import bitwig.obf.kh; // a decimal parameter atom
import bitwig.obf.xxu; // an enum parameter atom
import com.bitwig.flt.document.core.master.device.SRR; // a nested device chain
import bitwig.obf.WGX; // a drum pad
import com.bitwig.flt.document.core.master.device.YVC; // a multi-chain container (layers, bands, outputs)
import com.bitwig.flt.document.core.master.device.beU; // a chain slot
import com.bitwig.flt.document.core.master.device.dZe; // a component container
import com.bitwig.flt.document.core.master.device.k3K; // a device chain
import bitwig.obf.jes; // a routing source with a label
import bitwig.obf.nnO; // a sidechain component
import com.bitwig.flt.document.core.master.device.KFW; // a parallel layer bank
import com.bitwig.flt.document.core.master.device.LpH; // a Grid module definition
import com.bitwig.flt.document.core.master.device.ZFS; // a Grid module
import com.bitwig.flt.document.core.master.device.aII; // a Grid input port
import com.bitwig.flt.document.core.master.device.nWI; // a Grid
import bitwig.obf.LRP; // a model object that resolves component paths
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact-build song operations, called only from Bitwig's application event queue. */
public final class OpenwigDocument {
    private static final java.util.Map<String, JSONObject> pluginStateJobs = new java.util.LinkedHashMap<>();

    /** Bitwig's plugin-state save callback: a success records the state file, a message fails the job. */
    private static final class PluginStateCallback implements Dr {
        private final JSONObject status;
        PluginStateCallback(JSONObject status) { this.status = status; }
        private void complete(R1c holder) {
            java.io.File file = holder.fpX(); // the saved state file
            status.put("state", "complete").put("path", file == null ? JSONObject.NULL : file.getAbsolutePath())
                    .put("dirty", holder.wYd()); // state changed since the last save
        }
        @Override public void beI(R1c holder) { complete(holder); }
        @Override public void Gef(R1c holder) { complete(holder); }
        @Override public void beI(R1c holder, String error) { status.put("state", "failed").put("error", String.valueOf(error)); }
        @Override public String toString() { return "OpenwigPluginStateCapture"; }
    }

    /** @param holder the plugin device's plugin instance holder */
    private static JSONObject capturePluginState(R1c holder) {
        String loadState = ((Enum<?>) (Object) holder.LYs()).name(); // the plugin's load state
        if (loadState.equals("LOADING") || loadState.equals("QUEUED_FOR_LOADING"))
            return new JSONObject().put("state", "busy").put("load_state", loadState);
        if (!loadState.equals("LOADED")) throw new IllegalArgumentException("plugin state capture requires a loaded plugin: " + loadState);
        if (holder.iNE()) // a state save is already running
            return new JSONObject().put("state", "busy");
        String job = java.util.UUID.randomUUID().toString();
        JSONObject status = new JSONObject().put("job", job).put("state", "running");
        if (pluginStateJobs.size() >= 64) {
            String completed = null;
            for (String key : pluginStateJobs.keySet()) if (!pluginStateJobs.get(key).getString("state").equals("running")) { completed = key; break; }
            if (completed == null) throw new IllegalStateException("too many active plugin state jobs");
            pluginStateJobs.remove(completed);
        }
        pluginStateJobs.put(job, status);
        holder.beI(new PluginStateCallback(status)); // save the plugin state
        return new JSONObject(status.toString());
    }
    private OpenwigDocument() {}

    public static Object currentDocument() {
        gEH app = gEH.xw3();
        aCG selected = app == null ? null : app.cCV().Hqm(); // the project tab selection, its selected tab
        if (selected == null) throw new IllegalStateException("no selected Bitwig project");
        return selected.Anf(); // the tab's document
    }

    private static JSONObject documentInfo() {
        TzU raw = (TzU) currentDocument();
        return new JSONObject().put("path", raw.iEE())               // project file path
                .put("directory", raw.ckt()).put("name", raw.odI()); // its folder, its name
    }

    private static JSONObject listDocuments() {
        JSONArray result = new JSONArray();
        for (Object tab : (java.util.List<?>) gEH.xw3().VNw().getValue()) { // the open project tabs
            Object raw = ((aCG) tab).Anf();
            if (!(raw instanceof CmH document)) continue;
            ApiProject api = new ApiProject(document);
            try {
                result.put(new JSONObject().put("path", document.iEE())
                        .put("name", document.odI())
                        .put("main_tracks", api.getTrackGroup().mainTracks().size()));
            } finally { api.dispose(); }
        }
        return new JSONObject().put("documents", result);
    }

    private static JSONObject selectDocument(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("open project path is required");
        gEH app = gEH.xw3();
        aCG tab = app.ymo(new java.io.File(path).getAbsoluteFile()); // the open tab of that project file
        if (tab == null) throw new IllegalArgumentException("project is not open: " + path);
        app.cCV().Gef(tab); // select it
        return documentInfo();
    }

    // Native open/close run asynchronously; callers poll document.job_status.
    private static final java.util.Map<String, JSONObject> documentJobs = new java.util.LinkedHashMap<>();

    private static java.io.File projectFile(JSONObject params) {
        String path = params.optString("path", "");
        if (path.isBlank()) throw new IllegalArgumentException("project path is required");
        java.io.File file = new java.io.File(path).getAbsoluteFile();
        if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".bwproject"))
            throw new IllegalArgumentException("a .bwproject file is required");
        return file;
    }

    private static JSONObject jobSnapshot(JSONObject status) {
        synchronized (status) { return new JSONObject(status.toString()); }
    }

    private static void startDocumentJob(JSONObject status) {
        while (documentJobs.size() >= 32) {
            String finished = null;
            for (String key : documentJobs.keySet())
                if (!jobSnapshot(documentJobs.get(key)).getString("state").equals("running")) { finished = key; break; }
            if (finished == null) throw new IllegalStateException("too many active document jobs");
            documentJobs.remove(finished);
        }
        String job = java.util.UUID.randomUUID().toString();
        status.put("job", job).put("state", "running");
        documentJobs.put(job, status);
    }

    /** Bitwig's completion callback for an open or close: completion succeeds the job, an exception or message fails it. */
    private static final class DocumentJobCallback implements Pv {
        private final JSONObject status;
        DocumentJobCallback(JSONObject status) { this.status = status; }
        private void finish(String error) {
            synchronized (status) {
                if (error == null) status.put("state", "complete");
                else status.put("state", "failed").put("error", error);
            }
        }
        @Override public void taskCompleted() { finish(null); }
        @Override public void beI(Object result) { finish(null); }           // completed with a result
        @Override public void beI(String message) { finish(message); }       // failed with a message
        @Override public void beI(Exception failure) {                       // failed with an exception
            finish(String.valueOf(failure.getMessage() == null ? failure : failure.getMessage()));
        }
        @Override public String toString() { return "OpenwigDocumentJob"; }
    }

    private static gEH application() {
        gEH app = gEH.xw3();
        if (app == null) throw new IllegalStateException("Bitwig application is not ready");
        return app;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject openDocument(JSONObject params) {
        java.io.File file = projectFile(params);
        if (!file.isFile()) throw new IllegalArgumentException("project file not found: " + file);
        // Without the folder marker Bitwig never finishes opening the file (verified live).
        if (!new java.io.File(file.getParentFile(), ".bitwig-project").isFile())
            throw new IllegalArgumentException("not a Bitwig project folder (no .bitwig-project next to the file): " + file);
        gEH app = application();
        aCG tab = app.ymo(file); // the tab already showing this file, if any
        if (tab != null) {
            app.cCV().Gef(tab); // select it
            return new JSONObject().put("operation", "open").put("state", "complete")
                    .put("path", file.getPath()).put("already_open", true);
        }
        java.util.List<?> tabs = (java.util.List<?>) app.VNw().getValue();
        // Editions without multiple projects would prompt to close the current one.
        if (!app.IDq().dvk().getBoolValue() && !tabs.isEmpty()) // the edition's "multiple projects" feature
            throw new IllegalStateException("this Bitwig edition opens one project at a time; close the open project first");
        // The compile jar drops the enum's generic signature, so the set stays raw.
        @SuppressWarnings({"unchecked", "rawtypes"}) java.util.EnumSet flags = java.util.EnumSet.of((Enum) lfZ.valueOf("SELECT")); // open flags
        if (params.optBoolean("activate_engine", false)) flags.add(lfZ.valueOf("ACTIVATE_ENGINE"));
        JSONObject status = new JSONObject().put("operation", "open").put("path", file.getPath());
        startDocumentJob(status);
        app.beI(file.getPath(), tabs.size(), flags, new DocumentJobCallback(status)); // open the file in a new tab at this index
        return jobSnapshot(status);
    }

    /** The one never-saved tab with this name (e.g. "New 1" from the startup template). */
    private static aCG unsavedTab(gEH app, String name) {
        aCG found = null;
        for (Object item : (java.util.List<?>) app.VNw().getValue()) {
            aCG tab = (aCG) item;
            TzU raw = (TzU) tab.Anf();
            Object path = raw.iEE();
            if ((path == null || String.valueOf(path).isEmpty()) && name.equals(String.valueOf(raw.odI()))) {
                if (found != null) throw new IllegalArgumentException("more than one unsaved project is named " + name);
                found = tab;
            }
        }
        if (found == null) throw new IllegalArgumentException("no unsaved project named " + name);
        return found;
    }

    private static JSONObject closeDocument(JSONObject params) {
        if (!params.optBoolean("discard_changes", false))
            throw new IllegalArgumentException("document.close discards unsaved changes; pass discard_changes=true");
        String unsaved = params.optString("unsaved", "");
        java.io.File file = unsaved.isBlank() ? projectFile(params) : null;
        gEH app = application();
        aCG tab = file == null ? unsavedTab(app, unsaved) : app.ymo(file);
        if (tab == null) throw new IllegalArgumentException("project is not open: " + file);
        JSONObject status = new JSONObject().put("operation", "close");
        if (file == null) status.put("unsaved", unsaved); else status.put("path", file.getPath());
        startDocumentJob(status);
        // false skips the interactive save branch; the native path still stops the engine first.
        app.beI(tab, false, (bitwig.obf.dwF) null, (Pv) new DocumentJobCallback(status)); // close the tab
        return jobSnapshot(status);
    }

    private static ApiProject project() {
        return new ApiProject((CmH) DirectAccess.activeDocumentObject());
    }

    public static JSONObject audioStatus() {
        ApiProject project = project();
        try {
            CmH document = (CmH) DirectAccess.activeDocumentObject();
            return new JSONObject().put("connected", document.unz())
                    .put("ready", document.baq()).put("active", document.LYs())
                    .put("can_activate", document.zzi()).put("realtime", document.sUk())
                    .put("master", trackSnapshot(project.getTrackGroup().masterTrack(), -1));
        } finally {
            project.dispose();
        }
    }

    public static JSONObject activateAudio() {
        ((CmH) DirectAccess.activeDocumentObject()).sCA((com.bitwig.base.async.Pv) null); // activate the audio engine (no completion callback)
        return audioStatus();
    }

    /** Titles are literal path segments; duplicate siblings resolve to the first. */
    private static TrackOrTrackGroup resolve(ApiProject project, String path) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("track path is required");
        if (path.equals("master")) return project.getTrackGroup().masterTrack();
        boolean effect = path.startsWith("fx/");
        return resolveIn(effect ? project.getTrackGroup().effectTracks()
                : project.getTrackGroup().mainTracks(), effect ? path.substring(3) : path, path);
    }

    private static TrackOrTrackGroup resolveIn(java.util.List<?> siblings, String remaining, String fullPath) {
        TrackOrTrackGroup found = null;
        int length = -1;
        // Titles themselves can contain '/'. Prefer the longest matching title,
        // preserving first-match behavior for duplicate sibling titles.
        for (Object candidate : siblings) {
            TrackOrTrackGroup item = (TrackOrTrackGroup) candidate;
            String title = item.getTitle();
            if (!title.isEmpty() && title.length() > length &&
                    (remaining.equals(title) || remaining.startsWith(title + "/"))) {
                found = item;
                length = title.length();
            }
        }
        if (found != null) {
            if (length == remaining.length()) return found;
            if (found instanceof TrackGroup group)
                return resolveIn(group.mainTracks(), remaining.substring(length + 1), fullPath);
        }
        throw new IllegalArgumentException("track path not found: " + fullPath);
    }

    private static Integer indexIn(java.util.List<?> siblings, TrackOrTrackGroup wanted) {
        Object target = ((ApiObject) wanted).getTarget();
        for (int i = 0; i < siblings.size(); i++) {
            TrackOrTrackGroup item = (TrackOrTrackGroup) siblings.get(i);
            if (((ApiObject) item).getTarget() == target) return i;
            if (item instanceof TrackGroup group) {
                Integer child = indexIn(group.mainTracks(), wanted);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static Track view(TrackOrTrackGroup item) {
        return item instanceof Track direct ? direct : item.asTrack();
    }

    private static int sceneCount() {
        // ApiProject.getScene and ApiTrack.clipSlot CREATE objects out of range.
        // Count first using the exact-build model's read-only getter.
        return ((CmH) DirectAccess.activeDocumentObject()).svk().ce_();
    }

    public static JSONObject readObject(String method, String path, int index) {
        ApiProject project = project();
        try {
            TrackOrTrackGroup item = resolve(project, path);
            Track track = view(item);
            if (method.equals("clip.read")) {
                java.util.List<?> clips = track.clipTimeline().getEvents();
                if (index < 0 || index >= clips.size()) throw new IllegalArgumentException("clip index out of range: " + index);
                return readClip((Clip) clips.get(index));
            }
            java.util.IdentityHashMap<Object, JSONObject> owners = deviceOwners(project);
            java.util.IdentityHashMap<Object, JSONObject> atomOwners = atomOwnerIndex(project);
            if (method.equals("track.read")) {
                int trackIndex;
                if (path.equals("master")) trackIndex = -1;
                else if (path.startsWith("fx/"))
                    trackIndex = -indexIn(project.getTrackGroup().effectTracks(), item) - 2;
                else trackIndex = indexIn(project.getTrackGroup().mainTracks(), item);
                return readTrackOrGroup(item, trackIndex, path, sceneCount(), routingContext(project), owners, atomOwners);
            }
            java.util.List<?> devices = track.deviceChain().getDevices();
            if (index < 0 || index >= devices.size()) throw new IllegalArgumentException("device index out of range: " + index);
            Device device = (Device) devices.get(index);
            JSONObject result = readDevice(device, index, path, routingContext(project), owners, atomOwners);
            return result;
        } finally { project.dispose(); }
    }

    public static TrackOrTrackGroup trackOrGroup(ApiProject project, int index) {
        if (index == -1) {
            return project.getTrackGroup().masterTrack();
        }
        if (index < -1) {
            int effectIndex = -index - 2;
            if (effectIndex < 0 || effectIndex >= project.getTrackGroup().effectTracks().size()) {
                throw new IllegalArgumentException("effect track index out of range: " + effectIndex);
            }
            return (TrackOrTrackGroup) project.getTrackGroup().effectTracks().get(effectIndex);
        }
        if (index >= project.getTrackGroup().mainTracks().size()) {
            throw new IllegalArgumentException("main track index out of range: " + index);
        }
        return (TrackOrTrackGroup) project.getTrackGroup().mainTracks().get(index);
    }

    private static Track track(ApiProject project, int index) {
        if (index == -1) {
            return project.getTrackGroup().masterTrack();
        }
        TrackOrTrackGroup item = trackOrGroup(project, index);
        if (item instanceof Track tr) {
            return tr;
        }
        return item.asTrack();
    }

    private static Track effectTrack(ApiProject project, int index) {
        if (index < 0 || index >= project.getTrackGroup().effectTracks().size()) {
            throw new IllegalArgumentException("effect track index out of range: " + index);
        }
        return (Track) project.getTrackGroup().effectTracks().get(index);
    }

    public static int effectTrackCount() {
        ApiProject project = project();
        try {
            return project.getTrackGroup().effectTracks().size();
        } finally {
            project.dispose();
        }
    }

    public static int createEffectTrack(String name) {
        ApiProject project = project();
        try {
            Track effect = project.getFactory().createAudioTrack();
            effect.setTitle(name);
            project.getTrackGroup().addEffectTrack(effect);
            return project.getTrackGroup().effectTracks().size();
        } finally {
            project.dispose();
        }
    }

    public static int deleteEffectTrack(int effectIndex) {
        ApiProject project = project();
        try {
            Track effect = effectTrack(project, effectIndex);
            GJy group = (GJy) ((ApiObject) project.getTrackGroup()).getTarget();
            Object target = ((ApiObject) effect).getTarget();
            int before = project.getTrackGroup().effectTracks().size();
            DirectAccess.runInUndo(project,
                    () -> modifyModel(project, () -> DirectAccess.removeEffectTrack(group, target)));
            int after = project.getTrackGroup().effectTracks().size();
            if (after != before - 1 || modelOwner(target) != null) {
                throw new IllegalStateException("effect track was not removed from both native banks");
            }
            return after;
        } finally {
            project.dispose();
        }
    }

    public static int clearEffectTracks() {
        int before = effectTrackCount();
        for (int index = before - 1; index >= 0; index--) {
            deleteEffectTrack(index);
        }
        if (effectTrackCount() != 0) {
            throw new IllegalStateException("effect tracks remain after clear");
        }
        return before;
    }

    public static int clipCount(int trackIndex) {
        ApiProject project = project();
        try {
            return track(project, trackIndex).clipTimeline().getEvents().size();
        } finally {
            project.dispose();
        }
    }

    public static int deviceCount(int trackIndex) {
        ApiProject project = project();
        try {
            return track(project, trackIndex).deviceChain().getDevices().size();
        } finally {
            project.dispose();
        }
    }

    public static int masterDeviceCount() {
        ApiProject project = project();
        try {
            return project.getTrackGroup().masterTrack().deviceChain().getDevices().size();
        } finally {
            project.dispose();
        }
    }

    public static int insertMasterFactoryDevice(String name) {
        ApiProject project = project();
        try {
            java.util.UUID id = DirectAccess.factoryDeviceId(name + ".bwdevice");
            if (id == null) {
                throw new IllegalArgumentException("unknown Bitwig factory device: " + name);
            }
            Track master = project.getTrackGroup().masterTrack();
            DeviceChain chain = master.deviceChain();
            NativeDevice device = project.getFactory().createNativeDevice(id);
            DirectAccess.runInUndo(master.clipTimeline(), () -> chain.addDevice(device));
            return chain.getDevices().size();
        } finally {
            project.dispose();
        }
    }

    public static int clearMasterDevices() {
        int before = masterDeviceCount();
        for (int index = before - 1; index >= 0; index--) {
            ApiProject project = project();
            try {
                Track master = project.getTrackGroup().masterTrack();
                DeviceChain chain = master.deviceChain();
                Object device = chain.getDevices().get(index);
                DirectAccess.runInUndo(project,
                        () -> modifyModel(project, () -> DirectAccess.removeDevice(chain, device)));
                if (chain.getDevices().size() != index) {
                    throw new IllegalStateException("master device count did not decrease");
                }
            } finally {
                project.dispose();
            }
        }
        if (masterDeviceCount() != 0) {
            throw new IllegalStateException("master devices remain after clear");
        }
        return before;
    }

    public static JSONObject clearProject() {
        int masterDevices = clearMasterDevices();
        int effectTracks = clearEffectTracks();
        int mainTracks = clearMainTracks();
        return new JSONObject().put("main_tracks_deleted", mainTracks)
                .put("effect_tracks_deleted", effectTracks)
                .put("master_devices_deleted", masterDevices);
    }

    public static int createMainTrack(String kind, String name) {
        ApiProject project = project();
        try {
            Factory factory = project.getFactory();
            Track created;
            switch (kind) {
                case "instrument" -> created = factory.createInstrumentTrack();
                case "audio" -> created = factory.createAudioTrack();
                case "hybrid" -> created = factory.createHybridTrack();
                default -> throw new IllegalArgumentException(
                        "track kind must be instrument, audio, or hybrid: " + kind);
            }
            created.setTitle(name);
            project.getTrackGroup().addMainTrack(created);
            return project.getTrackGroup().mainTracks().size();
        } finally {
            project.dispose();
        }
    }

    public static int deleteTrack(int trackIndex) {
        ApiProject project = project();
        try {
            TrackOrTrackGroup targetItem = trackOrGroup(project, trackIndex);
            Object groupApi = project.getTrackGroup();
            GJy group = (GJy) ((ApiObject) groupApi).getTarget();
            sJU target = (sJU) ((ApiObject) targetItem).getTarget();
            int before = project.getTrackGroup().mainTracks().size();
            DirectAccess.runInUndo(project,
                    () -> modifyModel(project, () -> {
                        // The visible bank is a reference list; the owning bank
                        // keeps the track (and its engine playback) alive.
                        // Match native Delete Tracks: remove both memberships.
                        group.Gef(target);
                        group.Gef(target, true);
                    }));
            int after = project.getTrackGroup().mainTracks().size();
            if (after != before - 1 || modelOwner(target) != null) {
                throw new IllegalStateException("track was not removed from both native banks");
            }
            return after;
        } finally {
            project.dispose();
        }
    }

    public static int clearMainTracks() {
        int before = DirectAccess.mainTrackCount();
        for (int index = before - 1; index >= 0; index--) {
            deleteTrack(index);
        }
        if (DirectAccess.mainTrackCount() != 0) {
            throw new IllegalStateException("main tracks remain after clear");
        }
        return before;
    }

    public static JSONArray deviceSnapshot(int trackIndex) {
        ApiProject project = project();
        try {
            return deviceSnapshotList(track(project, trackIndex), null, routingContext(project),
                    deviceOwners(project), atomOwnerIndex(project));
        } finally {
            project.dispose();
        }
    }

    // Top-level device targets -> {"path", "device"} so modulator mappings can
    // resolve target ownership without any graph walk. Nested chain devices
    // are indexed by readNestedChain as they are read.
    private static java.util.IdentityHashMap<Object, JSONObject> deviceOwners(ApiProject project) {
        java.util.IdentityHashMap<Object, JSONObject> owners = new java.util.IdentityHashMap<>();
        for (Object item : project.getTrackGroup().mainTracks())
            indexDevices((TrackOrTrackGroup) item, ((TrackOrTrackGroup) item).getTitle(), owners);
        for (Object item : project.getTrackGroup().effectTracks())
            indexDevices((TrackOrTrackGroup) item, "fx/" + ((TrackOrTrackGroup) item).getTitle(), owners);
        indexDevices(project.getTrackGroup().masterTrack(), "master", owners);
        return owners;
    }

    private static void indexDevices(TrackOrTrackGroup item, String path,
                                     java.util.IdentityHashMap<Object, JSONObject> owners) {
        if (item == null || owners == null) return;
        if (item instanceof TrackGroup group) {
            for (Object member : group.mainTracks())
                indexDevices((TrackOrTrackGroup) member,
                        path + "/" + ((TrackOrTrackGroup) member).getTitle(), owners);
        }
        try {
            java.util.List<?> members = view(item).deviceChain().getDevices();
            for (int i = 0; i < members.size(); i++) {
                Object target = ((ApiObject) members.get(i)).getTarget();
                if (target != null && !owners.containsKey(target))
                    owners.put(target, new JSONObject().put("path", path).put("device", i));
            }
        } catch (RuntimeException unreadable) {
            // Channel kinds without a readable device chain stay unindexed.
        }
    }

    // Pre-index top-level native device parameter atoms -> {"path", "device", "parameter"}
    // so cross-device modulator bindings resolve by m1C identity.
    private static java.util.IdentityHashMap<Object, JSONObject> atomOwnerIndex(ApiProject project) {
        java.util.IdentityHashMap<Object, JSONObject> atomOwners = new java.util.IdentityHashMap<>();
        Class<?> decimal = bitwig.obf.xdh.class;
        Class<?> bool = bitwig.obf.cbF.class;
        Class<?> indexed = bitwig.obf.lpr.class;
        for (Object item : project.getTrackGroup().mainTracks())
            indexAtomOwners((TrackOrTrackGroup) item, ((TrackOrTrackGroup) item).getTitle(),
                    decimal, bool, indexed, atomOwners);
        for (Object item : project.getTrackGroup().effectTracks())
            indexAtomOwners((TrackOrTrackGroup) item, "fx/" + ((TrackOrTrackGroup) item).getTitle(),
                    decimal, bool, indexed, atomOwners);
        indexAtomOwners(project.getTrackGroup().masterTrack(), "master",
                decimal, bool, indexed, atomOwners);
        return atomOwners;
    }

    private static void indexAtomOwners(TrackOrTrackGroup item, String path,
                                        Class<?> decimal, Class<?> bool, Class<?> indexed,
                                        java.util.IdentityHashMap<Object, JSONObject> atomOwners) {
        if (item == null || atomOwners == null) return;
        if (item instanceof TrackGroup group) {
            for (Object member : group.mainTracks())
                indexAtomOwners((TrackOrTrackGroup) member,
                        path + "/" + ((TrackOrTrackGroup) member).getTitle(),
                        decimal, bool, indexed, atomOwners);
        }
        try {
            java.util.List<?> members = view(item).deviceChain().getDevices();
            for (int i = 0; i < members.size(); i++) {
                if (!(members.get(i) instanceof NativeDevice)) continue;
                Object target = ((ApiObject) members.get(i)).getTarget();
                if (target == null) continue;
                for (java.util.Map.Entry<Object, String> entry :
                        deviceAtomPaths(target, decimal, bool, indexed).entrySet()) {
                    if (!atomOwners.containsKey(entry.getKey()))
                        atomOwners.put(entry.getKey(), new JSONObject()
                                .put("path", path).put("device", i).put("parameter", entry.getValue()));
                }
            }
        } catch (RuntimeException unreadable) {
            // Channel kinds without a readable device chain stay unindexed.
        }
    }

    private static JSONArray deviceSnapshotList(Track view, String path,
                                                java.util.IdentityHashMap<Object, JSONObject> context,
                                                java.util.IdentityHashMap<Object, JSONObject> owners,
                                                java.util.IdentityHashMap<Object, JSONObject> atomOwners) {
        JSONArray devices = new JSONArray();
        java.util.List<?> members = view.deviceChain().getDevices();
        for (int i = 0; i < members.size(); i++) {
            Device device = (Device) members.get(i);
            devices.put(readDevice(device, i, path, context, owners, atomOwners));
        }
        return devices;
    }

    private static JSONObject readDevice(Device device, int i, String path,
                                         java.util.IdentityHashMap<Object, JSONObject> context,
                                         java.util.IdentityHashMap<Object, JSONObject> owners,
                                         java.util.IdentityHashMap<Object, JSONObject> atomOwners) {
        String kind = "unknown";
        if (device instanceof NativeDevice) {
            kind = "native";
        } else if (device instanceof Vst3Device) {
            kind = "vst3";
        } else if (device instanceof Vst2Device) {
            kind = "vst2";
        } else if (device instanceof PluginDevice) {
            kind = "plugin";
        }
        Object target = ((ApiObject) device).getTarget();
        // Per-call identity cache: do not rediscover parameter atoms for a
        // device reached twice through nested/shared component references.
        JSONObject cached = context.get(target);
        if (cached != null) {
            if (cached.optBoolean("reading", false))
                return new JSONObject().put("index", i).put("reference", true)
                        .put("device_name", device.getDisplayName());
            return new JSONObject(cached.toString()).put("index", i);
        }
        JSONObject result = new JSONObject().put("index", i)
                .put("display_name", device.getDisplayName())
                .put("kind", kind)
                .put("enabled", device.getEnabled().getValue())
                .put("active", device.getActive());
        if (path != null) result.put("path", path);
        context.put(target, result);
        result.put("reading", true);
        // Pure stored-model getters, cross-checked against ApiDawProjectExporter.
        sgH model = (sgH) target; // the device model
        result.put("device_name", model.af())
                .put("preset", model.VmZ())
                .put("displayed_preset", model.xpL());
        String role = ((Enum<?>) (Object) model.sM3()).name(); // the device's role (audio/note in and out)
        result.put("role", switch (role) {
            case "AUDIO_TO_AUDIO" -> "audioFX";
            case "NOTE_TO_NOTE" -> "noteFX";
            case "NOTE_TO_AUDIO" -> "instrument";
            case "AUDIO_TO_NOTE" -> "analyzer";
            default -> role;
        });
        if (device instanceof NativeDevice nativeDevice) {
            result.put("device_id", ((utt) target).GGt().sCA().toString()); // the definition's UUID
            JSONArray available = listDeviceParameters(device);
            JSONObject values = new JSONObject();
            for (int n = 0; n < available.length(); n++) {
                JSONObject parameter = available.getJSONObject(n);
                String name = parameter.getString("path");
                switch (parameter.getString("kind")) {
                    case "double" -> values.put(name, nativeDevice.getDoubleParameter(name).getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0);
                    case "boolean" -> values.put(name, nativeDevice.getBooleanParameter(name).getValue());
                    case "enum" -> values.put(name, nativeDevice.getEnumParameter(name).getValue());
                }
            }
            result.put("parameters", available).put("values", values);
            JSONArray sampleContent = readSampleContent(target);
            if (!sampleContent.isEmpty()) result.put("sample_content", sampleContent);
            JSONArray impulses = readImpulses(target);
            if (!impulses.isEmpty()) result.put("impulses", impulses);
            JSONArray modelFields = readModelFields(target);
            if (!modelFields.isEmpty()) result.put("model_fields", modelFields);
            Object contents = ((utt) target).edx(); // the device's contents
            Object modules = componentChildren(contents);
            if (modules instanceof java.util.List<?> children)
                for (Object child : children)
                    if ((child instanceof com.bitwig.flt.document.core.master.device.nWI))
                        result.put("grid", readGrid(nativeDevice));
        } else if (device instanceof PluginDevice) {
            if (target instanceof com.bitwig.flt.document.core.master.device.jyE) result.put("kind", "clap");
            Mv pluginDevice = (Mv) target;
            result.put("device_id", pluginDevice.egT().sCA()); // the plugin's ID
            R1c plugin = pluginDevice.dcC(); // the plugin instance holder
            JSONArray parameters = new JSONArray();
            if (plugin != null) {
                result.put("vendor", plugin.Bqr());
                for (Object item : plugin.af()) { // the plugin's cached parameters
                    ogX parameter = (ogX) item;
                    parameters.put(new JSONObject()
                            .put("id", parameter.unz())
                            .put("name", parameter.Bqd())
                            .put("value", parameter.FOm())
                            .put("gui_value", parameter.Anf())
                            .put("display", parameter.sSO())
                            .put("unit", parameter.zdi()));
                }
            }
            java.io.File stateFile = plugin == null ? null : plugin.fpX(); // the saved state file
            boolean hasState = stateFile != null && stateFile.isFile();
            result.put("state", hasState ? stateFile.getAbsolutePath() : JSONObject.NULL)
                    .put("state_file", hasState ? stateFile.getAbsolutePath() : JSONObject.NULL)
                    .put("state_path", hasState ? "plugins/" + stateFile.getName() : JSONObject.NULL)
                    .put("state_bytes", hasState ? stateFile.length() : 0);
            result.put("parameters", parameters).put("parameter_unit", "normalized")
                    .put("parameters_available", parameters.length() > 0)
                    .put("parameters_status", parameters.length() == 0 ? "unavailable_model_cache" : "resident_model_cache");
            Object sidechain = plugin == null ? null : plugin.pDG().DIi().BZt(); // sidechain routing, its source
            result.put("sidechain", sourceInfo(sidechain, context));
            JSONArray rawPluginChains = new JSONArray();
            JSONArray sidechains = new JSONArray();
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            boolean complete = true;
            if (plugin != null) {
                complete &= readNestedComponents(target, plugin.RiZ(), rawPluginChains, sidechains, seen, // the plugin's note/audio chains
                        context, path, owners, atomOwners, 0);
                complete &= readNestedComponents(target, plugin.QfO(), rawPluginChains, sidechains, seen,
                        context, path, owners, atomOwners, 0);
            }
            JSONArray chains = new JSONArray();
            for (int c = 0; c < rawPluginChains.length(); c++) {
                JSONObject chainItem = rawPluginChains.getJSONObject(c);
                if (chainItem.optJSONArray("devices") != null && chainItem.getJSONArray("devices").length() > 0) {
                    chains.put(chainItem);
                }
            }
            result.put("chains", chains).put("chains_complete", complete).put("sidechains", sidechains);
        }
        if (device instanceof NativeDevice) {
            JSONArray chains = new JSONArray();
            JSONArray sidechains = new JSONArray();
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            boolean complete = readNestedComponents(target, ((utt) target).edx(), chains, sidechains, seen,
                    context, path, owners, atomOwners, 0);
            result.put("chains", chains).put("chains_complete", complete).put("sidechains", sidechains)
                    .put("sidechain", sidechains.length() == 0 ? JSONObject.NULL :
                            sidechains.getJSONObject(0).get("source"));
        }
        // Stored model getters only (exact 6.1.5 signatures); no engine
        // interface, graph query, grid creation, or mapping callback.
        result.put("modulators", readModulators(target, path, i, owners, context, atomOwners))
                .put("modulators_status", "native");
        result.remove("reading");
        return result;
    }

    // Modulator reads live entirely on stored model state: gDz entries from the
    // device's YGC bank, the nJS definition via dsw(), value atoms through the
    // same walk as native device parameters, and modulation bindings as Hx3
    // component lists of AvA entries (target QFL, amount CzJ.Qcu).
    private static JSONArray readModulators(Object target, String path, int deviceIndex,
                                            java.util.IdentityHashMap<Object, JSONObject> owners,
                                            java.util.IdentityHashMap<Object, JSONObject> context,
                                            java.util.IdentityHashMap<Object, JSONObject> atomOwners) {
        JSONArray modulators = new JSONArray();
        Object bankList = ((sgH) target).LdK().dvk(); // the modulator bank's entries
        if (!(bankList instanceof java.util.List<?> entries)) return modulators;
        Class<?> decimal = bitwig.obf.xdh.class;
        Class<?> bool = bitwig.obf.cbF.class;
        Class<?> indexed = bitwig.obf.lpr.class;
        // Self-device atom index: m1C identity -> CONTENTS-relative parameter
        // path, so bindings inside the owning device resolve exactly.
        java.util.IdentityHashMap<Object, String> atomPaths = deviceAtomPaths(target, decimal, bool, indexed);
        for (Object entry : entries) {
            QFr modulator = (QFr) entry; // a modulator
            JSONObject item = new JSONObject()
                    .put("index", modulators.length())
                    .put("id", jsonModelValue(modulator.dNa()))
                    .put("device_name", modulator.af())
                    .put("display_name", modulator.pUD())
                    // The user device name is the name the file keeps; device_name
                    // is recomputed from it (or numbered from the definition) on load.
                    .put("user_name", modulator.jao())
                    .put("preset", modulator.VmZ())
                    .put("x", modulator.MgQ()).put("y", modulator.unz()); // panel position
            try {
                Mmq contents = modulator.MvA(); // the modulator's contents
                // The definition's factory identity (a UUID).
                Object identity = contents == null || contents.BCC() == null ? null : contents.BCC().sCA();
                item.put("modulator_id", identity == null ? JSONObject.NULL : identity.toString());
                item.put("parameters", readModulatorParameters(modulator, contents, decimal, bool, indexed));
                item.put("bindings", readModulatorBindings(contents, target, path, deviceIndex,
                        owners, context, atomPaths, atomOwners));
            } catch (RuntimeException unreadable) {
                item.put("read_error", unreadable.getClass().getSimpleName());
            }
            modulators.put(item);
        }
        return modulators;
    }

    private static java.util.IdentityHashMap<Object, String> deviceAtomPaths(Object target,
            Class<?> decimal, Class<?> bool, Class<?> indexed) {
        java.util.IdentityHashMap<Object, String> atomPaths = new java.util.IdentityHashMap<>();
        Object contents = target instanceof utt nativeDevice ? nativeDevice.edx() : null; // a native device's contents
        if (contents == null) return atomPaths;
        java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.List<Object> found = new java.util.ArrayList<>();
        walkAtoms(contents, 0, seen, found, decimal, bool, indexed);
        for (Object atom : found) {
            String fullPath = automationIdentifier(target, atom);
            if (fullPath != null && fullPath.startsWith("CONTENTS/"))
                atomPaths.put(atom, fullPath.substring("CONTENTS/".length()));
        }
        // Modulators can target other modulators of the same device (a Math
        // driving a Macro): keep the full MODULATORS/<i>/CONTENTS/<param> path.
        itZ bank = target instanceof sgH device ? device.LdK() : null; // the modulator bank
        Object modulatorList = bank == null ? null : bank.dvk();
        if (modulatorList instanceof java.util.List<?> modulators)
            for (Object modulator : modulators) {
                Object modulatorContents = modulator instanceof QFr m ? m.MvA() : null;
                if (modulatorContents == null) continue;
                java.util.List<Object> modulatorAtoms = new java.util.ArrayList<>();
                walkAtoms(modulatorContents, 0, new java.util.IdentityHashMap<>(), modulatorAtoms, decimal, bool, indexed);
                for (Object atom : modulatorAtoms) {
                    String fullPath = automationIdentifier(target, atom);
                    if (fullPath != null && fullPath.startsWith("MODULATORS/")) atomPaths.put(atom, fullPath);
                }
            }
        return atomPaths;
    }

    private static JSONArray readModulatorParameters(Object modulator, Object contents,
                                                     Class<?> decimal, Class<?> bool, Class<?> indexed) {
        JSONArray parameters = new JSONArray();
        if (contents == null) return parameters;
        java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.List<Object> found = new java.util.ArrayList<>();
        walkAtoms(contents, 0, seen, found, decimal, bool, indexed);
        for (Object atom : found) {
            String fullPath = automationIdentifier(modulator, atom);
            if (fullPath == null) fullPath = automationIdentifier(contents, atom);
            if (fullPath == null || !fullPath.startsWith("CONTENTS/")) continue;
            JSONObject parameter = new JSONObject()
                    .put("path", fullPath.substring("CONTENTS/".length()))
                    .put("kind", atomKind(atom, decimal, bool, indexed));
            // The stored current value; an enum atom's item, else its index.
            // Values are model-native, not normalized.
            parameter.put("value", jsonModelValue(atomValue(atom)));
            String unit = atom instanceof kh decimalAtom ? automationUnit(decimalAtom.XdI()) : null; // its value range
            if (unit != null) parameter.put("unit", unit);
            // A Macro knob can be bipolar, which widens its range to -1..1.
            if (atom instanceof dQh macro) parameter.put("bipolar", macro.af());
            Object label = atom instanceof OMh labelled ? labelled.Bqd() : null;
            if (label instanceof String text && !text.isEmpty()) parameter.put("label", text);
            // The writer takes normalized numbers and enum indexes; native
            // values above do not map linearly (an LFO RATE of 0 reads 1 normalized).
            try {
                Object wrapper = parameterWrapper(atom);
                if (wrapper instanceof DoubleParameter p) parameter.put("normalized", p.getValue(Unit.PERCENTAGE_OF_RANGE) / 100);
                else if (wrapper instanceof EnumParameter p) parameter.put("index", p.getValue());
            } catch (RuntimeException unavailable) {
                parameter.put("normalized_error", unavailable.getClass().getSimpleName());
            }
            parameters.put(parameter);
        }
        return parameters;
    }

    // Hx3 components hold the stored modulation bindings of a modulator: each
    // AvA entry references a parameter (QFL -> m1C) with CzJ.Qcu() as the
    // model-native amount. Targets resolve by atom identity inside the owning
    // device or global top-level index when possible, otherwise via QFL's
    // stored 6.1.5 owner/path fields (dY3.hMI/TEI, Ovr.U3o) and track (QFL.xTe).
    private static JSONArray readModulatorBindings(Object contents, Object self, String path, int deviceIndex,
                                                   java.util.IdentityHashMap<Object, JSONObject> owners,
                                                   java.util.IdentityHashMap<Object, JSONObject> context,
                                                   java.util.IdentityHashMap<Object, String> atomPaths,
                                                   java.util.IdentityHashMap<Object, JSONObject> atomOwners) {
        JSONArray bindings = new JSONArray();
        Object children = contents instanceof Mmq modulatorContents ? modulatorContents.ogS() : null; // its components
        if (!(children instanceof java.util.List<?> components)) return bindings;
        for (Object item : components) {
            // Macro sources use a subclass of the modulation-source component; an exact class test skipped every Macro mapping.
            if (!(item instanceof com.bitwig.flt.document.core.master.device.aoy component)) continue;
            Object entries = component.jTT(); // its modulation bindings
            if (!(entries instanceof java.util.List<?> list)) continue;
            String key = component.az_(); // the component's key
            for (Object listed : list) {
                if (!(listed instanceof dr2 entry)) continue; // a modulation binding
                if (bindings.length() >= 512) {
                    bindings.put(new JSONObject().put("truncated", true));
                    return bindings;
                }
                SZB reference = entry.cCV(); // the bound parameter's reference
                Object parameter = reference == null ? null : reference.neH(); // the parameter
                Object amount = entry.byf(); // the model-native amount
                JSONObject binding = new JSONObject().put("component", key == null ? "" : key);
                String parameterPath = parameter == null ? null : atomPaths.get(parameter);
                if (parameterPath != null) {
                    binding.put("target_parameter", parameterPath);
                    binding.put("target_owner", selfOwner(self, path, deviceIndex, owners));
                } else if (parameter != null && atomOwners != null && atomOwners.containsKey(parameter)) {
                    JSONObject entryOwner = atomOwners.get(parameter);
                    binding.put("target_parameter", entryOwner.optString("parameter"));
                    binding.put("target_owner", new JSONObject()
                            .put("path", entryOwner.optString("path"))
                            .put("device", entryOwner.getInt("device")));
                } else {
                    Object storedPath = reference == null ? null : reference.zzi(); // the stored parameter path
                    // Walk up from the target atom to its device: a modulator parameter
                    // (a Math input) then keeps its MODULATORS/<i>/CONTENTS/ path.
                    String held = null;
                    Object heldOwner = null;
                    Object cursor = parameter;
                    for (int depth = 0; cursor != null && depth < 24; depth++, cursor = modelOwner(cursor)) {
                        if (cursor != self && (owners == null || !owners.containsKey(cursor))) continue;
                        held = automationIdentifier(cursor, parameter);
                        heldOwner = cursor;
                        break;
                    }
                    if (held != null && held.startsWith("MODULATORS/")) {
                        // Another device's modulator parameter (a Math input): keep the modulator index.
                        binding.put("target_parameter", held);
                    } else if (storedPath instanceof String text && !text.isEmpty()) {
                        binding.put("target_parameter",
                                text.startsWith("CONTENTS/") ? text.substring("CONTENTS/".length()) : text);
                    } else {
                        Object name = reference == null ? null : reference.rw3(); // the stored parameter name
                        if (name == null && parameter instanceof QCX named) name = named.CnT();
                        if (name == null && parameter instanceof Ae2 automatable) name = automatable.bMd();
                        if (name == null && parameter instanceof Ae2 automatable) name = automatable.aH_();
                        if (name != null && !name.toString().isEmpty()) binding.put("target", name.toString());
                    }
                    if (parameter != null && !binding.has("target_parameter"))
                        binding.put("target_model", parameter.getClass().getSimpleName());
                    JSONObject located = null;
                    if (reference != null) {
                        Object ownerDevice = reference.ogS(); // the stored owner
                        if (ownerDevice == null) ownerDevice = reference.VNw(); // the stored device
                        located = locateBindingOwner(ownerDevice, self, path, deviceIndex, owners);
                        if (located == null && parameter != null) {
                            located = locateBindingOwner(parameter, self, path, deviceIndex, owners);
                            if (located == null) {
                                located = locateBindingOwner(parameter instanceof AZT owned ? owned.m2g() : null, // the parameter's device
                                        self, path, deviceIndex, owners);
                            }
                        }
                        if (located == null) {
                            // The stored track of the reference.
                            JSONObject track = context == null ? null
                                    : context.get(reference.qJX());
                            if (track != null) located = new JSONObject().put("path", track.optString("path"));
                        }
                    }
                    if (located != null) binding.put("target_owner", located);
                    else if (parameter != null)
                        binding.put("target_owner", new JSONObject().put("resolved", false));
                    // A parameter of a device's sub-module (a Polymer MODULES/<i>/CONTENTS/...) is stored
                    // relative to its module, which is ambiguous on the device; use the path from the
                    // owning device (its parameter-list path) when that device is the located owner.
                    String stored = binding.optString("target_parameter", "");
                    boolean sameOwner = located != null && heldOwner != null && (heldOwner == self
                            ? located.similar(selfOwner(self, path, deviceIndex, owners))
                            : owners.get(heldOwner) == located);
                    if (sameOwner && held != null && held.startsWith("CONTENTS/") && !stored.isEmpty()) {
                        String full = held.substring("CONTENTS/".length());
                        if (full.endsWith("/" + stored)) binding.put("target_parameter", full);
                    }
                }
                if (amount instanceof Number number) binding.put("amount", number.doubleValue());
                // The amount over the target's modulation span, clamped to -1..1.
                binding.put("amount_normalized", entry.BZt());
                binding.put("enabled", entry.tWW());
                bindings.put(binding);
            }
        }
        return bindings;
    }

    // The reading device as a binding owner, marked self: a device inside a chain needs its chain
    // and route from the track (indexed in owners); its own index alone points at a top-level device.
    private static JSONObject selfOwner(Object self, String path, int deviceIndex,
                                        java.util.IdentityHashMap<Object, JSONObject> owners) {
        JSONObject known = owners == null ? null : owners.get(self);
        return (known != null ? new JSONObject(known.toString())
                : new JSONObject().put("path", path).put("device", deviceIndex)).put("self", true);
    }

    private static JSONObject locateBindingOwner(Object start, Object self, String path, int deviceIndex,
                                                 java.util.IdentityHashMap<Object, JSONObject> owners) {
        Object cursor = start;
        for (int depth = 0; cursor != null && depth < 24; depth++) {
            if (cursor == self) return selfOwner(self, path, deviceIndex, owners);
            if (owners != null) {
                JSONObject matched = owners.get(cursor);
                if (matched != null) return matched;
            }
            cursor = modelOwner(cursor);
        }
        return null;
    }

    private static boolean readNestedComponents(Object owner, Object node, JSONArray out, JSONArray sidechains,
            java.util.IdentityHashMap<Object, Boolean> seen,
            java.util.IdentityHashMap<Object, JSONObject> context, String path,
            java.util.IdentityHashMap<Object, JSONObject> owners,
            java.util.IdentityHashMap<Object, JSONObject> atomOwners, int depth) {
        if (node == null || seen.containsKey(node)) return true;
        if (depth > 32 || seen.size() >= 20000) return false;
        seen.put(node, true);
        if ((node instanceof bitwig.obf.nnO)) {
            Object source = ((nnO) node).DIi().BZt(); // the sidechain's routing, its source
            sidechains.put(new JSONObject().put("component_path", jsonModelValue(automationIdentifier(owner, node)))
                    .put("source", sourceInfo(source, context)));
            return true;
        }
        if ((node instanceof com.bitwig.flt.document.core.master.device.SRR)) {
            JSONObject item = readNestedChain(owner, node, "chain", out.length(), path, context, owners, atomOwners, out.length());
            Object chainName = ((SRR) node).az_(); // the chain's name
            if (chainName instanceof String text && !text.isEmpty()) item.put("name", text);
            out.put(item);
            return true;
        }
        if ((node instanceof com.bitwig.flt.document.core.master.device.beU)) {
            Object chain = ((beU) node).VNw(); // the chain slot's chain
            if (chain != null) {
                JSONObject item = readNestedChain(owner, chain, "chain", out.length(), path, context, owners, atomOwners, out.length());
                Object slotName = ((beU) node).beI(); // the slot's name
                if (slotName instanceof String text && !text.isEmpty()) item.put("name", text);
                out.put(item);
            }
            return true;
        }
        if ((node instanceof com.bitwig.flt.document.core.master.device.YVC)) {
            String chainKind = (node instanceof com.bitwig.flt.document.core.master.device.KFW)
                    ? "layer" : (node instanceof com.bitwig.flt.document.core.master.device.b3K)
                    ? "multi_out" : "band";
            int index = 0;
            for (Object chain : ((YVC) node).LYs()) { // the container's chains
                JSONObject item = readNestedChain(owner, chain, chainKind, index, path, context, owners, atomOwners, out.length());
                Object chainName = chain instanceof SRR named ? named.az_() : null;
                if (chainName instanceof String text && !text.isEmpty()) item.put("name", text);
                out.put(item);
                index++;
            }
            return true;
        }
        if ((node instanceof bitwig.obf.aIt)) {
            int index = 0;
            for (Object item : ((bitwig.obf.aIt) node).beI()) { // the drum machine's pads
                WGX pad = (WGX) item;
                Object chain = pad.JTf(); // the pad's chain
                JSONObject entry = chain == null ? new JSONObject().put("kind", "drum_pad")
                        .put("index", index).put("has_chain", false).put("devices", new JSONArray())
                        : readNestedChain(owner, chain, "drum_pad", index, path, context, owners, atomOwners, out.length());
                entry.put("key", pad.unz()); // the pad's key
                out.put(entry);
                index++;
            }
            return true;
        }
        // These model classes are leaves for nesting discovery.
        if ((node instanceof bitwig.obf.xdh) || (node instanceof bitwig.obf.cbF) ||
                (node instanceof bitwig.obf.lpr)) return true;
        boolean complete = true;
        if (componentChildren(node) instanceof java.util.List<?> members) // no children: a leaf
            for (Object child : members)
                complete &= readNestedComponents(owner, child, out, sidechains, seen, context, path, owners, atomOwners, depth + 1);
        return complete;
    }

    private static JSONObject readNestedChain(Object owner, Object chain, String kind, int index,
            String path, java.util.IdentityHashMap<Object, JSONObject> context,
            java.util.IdentityHashMap<Object, JSONObject> owners,
            java.util.IdentityHashMap<Object, JSONObject> atomOwners, int chainPosition) {
        k3K stored = ((SRR) chain).YQe(); // the nested chain's device chain
        JSONArray devices = new JSONArray();
        if (stored != null) {
            {
                DeviceChain api = new com.bitwig.flt.document.api.document.ApiDeviceChain(stored);
                java.util.List<?> members = api.getDevices();
                for (int i = 0; i < members.size(); i++) {
                    Device member = (Device) members.get(i);
                    Object memberTarget = ((ApiObject) member).getTarget();
                    if (owners != null && memberTarget != null) {
                        // The full route from the track's chain: each step is a container
                        // device and the component of the chain inside it.
                        JSONObject parent = owners.get(owner);
                        JSONArray route = parent == null ? null : new JSONArray(parent.optJSONArray("chain_path") == null
                                ? "[]" : parent.getJSONArray("chain_path").toString());
                        if (route != null && parent.has("device"))
                            route.put(new JSONObject().put("device", parent.getInt("device"))
                                    .put("component", jsonModelValue(automationIdentifier(owner, chain))));
                        JSONObject entry = new JSONObject().put("path", path).put("chain", chainPosition).put("device", i);
                        if (route != null) entry.put("chain_path", route);
                        owners.put(memberTarget, entry);
                    }
                    devices.put(readDevice(member, i, path, context, owners, atomOwners));
                }
            }
        }
        return new JSONObject().put("kind", kind).put("index", index)
                .put("component_path", jsonModelValue(automationIdentifier(owner, chain)))
                .put("has_chain", stored != null).put("devices", devices)
                .put("layer", layerState(owner, chain));
    }

    private static java.util.IdentityHashMap<Object, JSONObject> routingContext(ApiProject project) {
        java.util.IdentityHashMap<Object, JSONObject> context = new java.util.IdentityHashMap<>();
        for (Object item : project.getTrackGroup().mainTracks()) {
            TrackOrTrackGroup track = (TrackOrTrackGroup) item;
            indexRouting(track, track.getTitle(), context);
        }
        for (Object item : project.getTrackGroup().effectTracks()) {
            TrackOrTrackGroup track = (TrackOrTrackGroup) item;
            indexRouting(track, "fx/" + track.getTitle(), context);
        }
        indexRouting(project.getTrackGroup().masterTrack(), "master", context);
        return context;
    }

    private static void indexRouting(TrackOrTrackGroup item, String path,
                                    java.util.IdentityHashMap<Object, JSONObject> context) {
        Track track = view(item);
        context.put(((ApiObject) track).getTarget(), new JSONObject().put("path", path));
        Object[] sources = {track.preFader(), track.postFader(), track.postFxNotes(), track.deviceChain().preFx()};
        String[] taps = {"pre_fader", "post_fader", "post_fx_notes", "pre_fx"};
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] != null) context.put(((ApiObject) sources[i]).getTarget(),
                    new JSONObject().put("path", path).put("tap", taps[i]));
        }
        if (item instanceof TrackGroup group)
            for (Object child : group.mainTracks()) {
                TrackOrTrackGroup member = (TrackOrTrackGroup) child;
                indexRouting(member, path + "/" + member.getTitle(), context);
            }
    }

    private static Object sourceInfo(Object source, java.util.IdentityHashMap<Object, JSONObject> context) {
        if (source == null) return JSONObject.NULL;
        JSONObject known = context.get(source);
        return known != null ? known : new JSONObject().put("model_class", source.getClass().getName())
                .put("label", source instanceof jes labelled ? labelled.iqe() : JSONObject.NULL).put("resolved", false);
    }

    // Optional-getter variant: absent methods read as null instead of failing
    // the surrounding read (used where the exact build may legitimately lack
    // a getter on a subclass).
    /** A native device's stored contents (its component tree), or null for other devices. */
    private static Object deviceContents(Object owner) {
        return owner instanceof utt nativeDevice ? nativeDevice.edx() : null;
    }

    /** A stored component's child components, or null for a leaf. */
    private static java.util.List<?> componentChildren(Object node) {
        return node instanceof dZe container ? container.ogS() : null;
    }

    /** A model object's owner (parent) in Bitwig's document tree, or null. */
    private static Object modelOwner(Object node) {
        return node instanceof IqI child ? child.L_() : null;
    }

    /** A parameter atom's stored current value: a number, a boolean, or an enum atom's item (else its index). */
    private static Object atomValue(Object atom) {
        if (atom instanceof kh decimal) return decimal.Anf();
        if (atom instanceof CHK bool) return bool.Anf();
        if (atom instanceof xxu indexed) { Object item = indexed.Anf(); return item != null ? item : indexed.byf(); }
        return null;
    }

    private static Object modelGetOrNull(Object target, String method) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method getter = target.getClass().getMethod(method);
            getter.setAccessible(true);
            return getter.invoke(target);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    private static Object modelGet(Object target, String method) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method getter = target.getClass().getMethod(method);
            getter.setAccessible(true);
            return getter.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("model getter " + target.getClass().getName() + "." + method, failure);
        }
    }

    private static Track resolveTrack(ApiProject project, JSONObject params) {
        if (params != null && !params.optString("path", "").isBlank()) {
            return view(resolve(project, params.getString("path")));
        }
        return track(project, params.getInt("track"));
    }

    private static NativeDevice nativeDeviceOnTrack(Track targetTrack, int deviceIndex) {
        java.util.List<?> members = targetTrack.deviceChain().getDevices();
        if (deviceIndex < 0 || deviceIndex >= members.size()) {
            throw new IllegalArgumentException("device index out of range: " + deviceIndex);
        }
        Device device = (Device) members.get(deviceIndex);
        if (!(device instanceof NativeDevice target)) {
            throw new IllegalArgumentException("device has no named parameters");
        }
        return target;
    }

    private static DeviceChain wrapDeviceChain(Object stored) {
        return new com.bitwig.flt.document.api.document.ApiDeviceChain((k3K) stored);
    }

    private static Object deviceComponent(Object owner, String path, Class<?> type) {
        if (path == null || path.isBlank() || path.contains("..") || path.length() > 2048)
            throw new IllegalArgumentException("native component path required");
        Object value = ((LRP) owner).beI(path, type); // the component at this path
        if (value == null || !type.isInstance(value)) throw new IllegalArgumentException("component has the wrong kind: " + path);
        return value;
    }

    /** Bitwig's completion callback for a native edit: success runs the check, a message or exception fails the job. */
    private static final class NativeCallback implements Pv {
        private final JSONObject status;
        private final Runnable success;
        private final String label;
        NativeCallback(JSONObject status, Runnable success, String label) { this.status = status; this.success = success; this.label = label; }
        @Override public void taskCompleted() { success.run(); }
        @Override public void beI(Object result) { success.run(); }                                          // completed
        @Override public void beI(String message) { status.put("state", "failed").put("error", message); }   // failed with a message
        @Override public void beI(Exception failure) { status.put("state", "failed").put("error", String.valueOf(failure)); }
        @Override public String toString() { return label; }
    }

    private static Track deviceScope(Track track, JSONObject params) {
        if (!params.has("chain")) return track;
        JSONArray steps = params.getJSONArray("chain");
        if (steps.isEmpty()) return track;
        if (steps.length() > 32) throw new IllegalArgumentException("nested chain depth exceeds 32");
        DeviceChain chain = track.deviceChain();
        for (Object value : steps) {
            JSONObject step = (JSONObject) value;
            if (!step.keySet().equals(java.util.Set.of("device", "component"))) throw new IllegalArgumentException("chain steps need device/component");
            Object rawIndex = step.get("device");
            if (!(rawIndex instanceof Integer)) throw new IllegalArgumentException("nested device index must be an integer");
            int index = (Integer) rawIndex;
            if (index < 0 || index >= chain.getDevices().size()) throw new IllegalArgumentException("nested owner device index out of range");
            Object owner = ((ApiObject) chain.getDevices().get(index)).getTarget();
            Object layer = deviceComponent(owner, step.getString("component"), com.bitwig.flt.document.core.master.device.SRR.class);
            Object stored = ((SRR) layer).YQe(); // the nested chain's device chain
            if (stored == null) throw new IllegalArgumentException("nested component has no device chain");
            chain = wrapDeviceChain(stored);
        }
        DeviceChain selected = chain;
        return (Track) java.lang.reflect.Proxy.newProxyInstance(Track.class.getClassLoader(), new Class<?>[]{Track.class}, (proxy, method, args) -> {
            if (method.getName().equals("deviceChain") && method.getParameterCount() == 0) return selected;
            try { return method.invoke(track, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    private static JSONObject readGrid(NativeDevice device) {
        Object owner = ((ApiObject) device).getTarget();
        nWI grid = (nWI) deviceComponent(owner, "CONTENTS/MODULES", nWI.class);
        JSONArray modules = new JSONArray();
        for (Object item : grid.dvk()) { // the Grid's modules
            ZFS module = (ZFS) item;
            LpH definition = module.MvA(); // the module's definition
            JSONArray inputs = new JSONArray(), outputs = new JSONArray();
            try (java.util.stream.Stream<?> atoms = module.vUU()) { // the module's atoms
                for (Object atom : atoms.toList()) {
                    boolean input = (atom instanceof com.bitwig.flt.document.core.master.device.aII);
                    boolean output = (atom instanceof com.bitwig.flt.document.core.master.device.GoC);
                    if (!input && !output) continue;
                    JSONObject port = new JSONObject().put("component", automationIdentifier(owner, atom));
                    if (input) {
                        Object source = ((aII) atom).GKF(); // the connected output
                        port.put("source", source == null ? JSONObject.NULL : automationIdentifier(owner, source));
                    }
                    (input ? inputs : outputs).put(port);
                }
            }
            modules.put(new JSONObject().put("component", automationIdentifier(owner, module))
                    .put("module_id", definition.unz().toString())
                    .put("name", module.af())
                    .put("x", module.MgQ()).put("y", module.unz())
                    .put("width", module.IvP()).put("height", module.CtA())
                    .put("inputs", inputs).put("outputs", outputs));
        }
        return new JSONObject().put("modules", modules);
    }

    private static int gridCoordinate(JSONObject params, String key) {
        Object value = params.get(key);
        if (!(value instanceof Integer n) || Math.abs((long) n) > 1000000)
            throw new IllegalArgumentException(key + " must be an integer within +/-1000000");
        return (Integer) value;
    }

    private static void gridPosition(nWI grid, ZFS module, int x, int y) {
        int width = module.IvP(), height = module.CtA(); // the module's size
        for (Object item : grid.dvk()) {
            if (item == module) continue;
            ZFS other = (ZFS) item;
            int ox = other.MgQ(), oy = other.unz(); // its position
            int ow = other.IvP(), oh = other.CtA();
            if (x < ox + ow && x + width > ox && y < oy + oh && y + height > oy)
                throw new IllegalArgumentException("module position overlaps another module");
        }
    }

    private static JSONObject executeGrid(String method, Track track, JSONObject params) {
        NativeDevice device = nativeDeviceOnTrack(track, params.getInt("device"));
        Object owner = ((ApiObject) device).getTarget();
        nWI grid = (nWI) deviceComponent(owner, "CONTENTS/MODULES", nWI.class);
        {
            if (method.equals("device.grid_add")) {
                if (grid.dvk().size() >= 4096) throw new IllegalArgumentException("Grid module limit exceeded");
                java.util.UUID id = java.util.UUID.fromString(params.getString("module_id"));
                int x = gridCoordinate(params, "x"), y = gridCoordinate(params, "y");
                ZFS module = ZFS.Bqk(id); // a new module from its definition ID
                gridPosition(grid, module, x, y);
                java.util.List<?> before = new java.util.ArrayList<>(grid.dvk());
                // Match the GUI's cloned-content insertion and its own undo operation.
                grid.Gef(java.util.List.of(module), x, y, true, (com.bitwig.base.async.PPR) null); // insert modules at a position
                Object inserted = ((java.util.List<?>) grid.dvk()).stream().filter(candidate -> !before.contains(candidate)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Grid module insertion did not add a module"));
                return new JSONObject().put("component", automationIdentifier(owner, inserted)).put("grid", readGrid(device));
            }
            if (method.equals("device.grid_connect")) {
                if (!params.has("output")) throw new IllegalArgumentException("output required (null disconnects)");
                String inputPath = params.getString("input");
                if (!inputPath.startsWith("CONTENTS/MODULES/")) throw new IllegalArgumentException("Grid input required");
                Object input = deviceComponent(owner, inputPath, com.bitwig.flt.document.core.master.device.aII.class);
                Object output = null;
                if (!params.isNull("output")) {
                    String outputPath = params.getString("output");
                    if (!outputPath.startsWith("CONTENTS/MODULES/")) throw new IllegalArgumentException("Grid output required");
                    output = deviceComponent(owner, outputPath, com.bitwig.flt.document.core.master.device.GoC.class);
                }
                // The input's public edit entry handles cable graph notifications and undo.
                ((aII) input).beI((bitwig.obf.tRH) output); // connect (null disconnects)
                return readGrid(device);
            }
            String component = params.getString("component");
            ZFS module = (ZFS) deviceComponent(owner, component, ZFS.class);
            if (!grid.dvk().contains(module)) throw new IllegalArgumentException("module outside selected grid");
            if (method.equals("device.grid_move")) {
                int x = gridCoordinate(params, "x"), y = gridCoordinate(params, "y");
                gridPosition(grid, module, x, y);
                DirectAccess.runInUndo(track.clipTimeline(), () -> {
                    module.sCA(x);  // set x
                    module.Heb(y);  // set y
                });
            } else if (method.equals("device.grid_delete")) {
                // Native deletion cleans up attached cables and owns its undo transaction.
                grid.beI((java.util.List<?>) java.util.List.of(module)); // delete modules
            } else throw new IllegalArgumentException("unsupported Grid operation");
            return readGrid(device);
        }
    }

    private static JSONObject newDeviceJob() {
        if (pluginStateJobs.size() >= 64) {
            String completed = null;
            for (String key : pluginStateJobs.keySet()) if (!pluginStateJobs.get(key).getString("state").equals("running")) { completed = key; break; }
            if (completed == null) throw new IllegalStateException("too many active device operations");
            pluginStateJobs.remove(completed);
        }
        String job = java.util.UUID.randomUUID().toString();
        JSONObject status = new JSONObject().put("job", job).put("state", "running");
        pluginStateJobs.put(job, status);
        return status;
    }

    private static Mixer layerMixer(Object layer) {
        return new com.bitwig.flt.document.api.document.ApiNestedDeviceChain((SRR) layer).getMixer();
    }

    private static JSONObject layerState(Object owner, Object layer) {
        Mixer mixer = layerMixer(layer);
        JSONObject result = new JSONObject().put("component", automationIdentifier(owner, layer))
                .put("name", ((SRR) layer).eXf()).put("title", ((SRR) layer).cc_()); // stored name, shown title
        if (mixer == null) return result.put("mixer", JSONObject.NULL);
        double db = mixer.getVolume().getValue(Unit.DECIBEL);
        return result.put("mixer", new JSONObject().put("volume", mixer.getVolume().getValue(Unit.RATIO))
                .put("volume_db", Double.isFinite(db) ? db : JSONObject.NULL)
                .put("pan", mixer.getPan().getValue(Unit.PERCENTAGE_OF_RANGE) / 100)
                .put("muted", mixer.getMute().getValue()));
    }

    private static JSONObject editLayer(String method, Track track, JSONObject params) {
        Object owner = ((ApiObject) nativeDeviceOnTrack(track, params.getInt("device"))).getTarget();
        SRR layer = (SRR) deviceComponent(owner, params.getString("component"), SRR.class);
        if (method.equals("device.layer_read")) return layerState(owner, layer);
        if (method.equals("device.layer_move")) {
            Object owning = layer.Fhl(); // the bank that owns the layer
            if (!(owning instanceof KFW bank)) throw new IllegalArgumentException("reordering requires a parallel layer");
            java.util.List<?> members = bank.LYs(); // its layers
            int source = members.indexOf(layer);
            Object value = params.get("index");
            if (!(value instanceof Integer index) || index < 0 || index >= members.size()) throw new IllegalArgumentException("layer destination index out of range");
            int destination = (Integer) value;
            if (source == destination) return new JSONObject().put("state", "complete").put("data", layerState(owner, layer));
            JSONObject status = newDeviceJob();
            Pv callback = new NativeCallback(status, () -> {
                try {
                    if (layer.Fhl() != bank || bank.LYs().indexOf(layer) != destination)
                        throw new IllegalStateException("layer did not reach its requested index");
                    status.put("data", layerState(owner, layer)).put("state", "complete");
                } catch (RuntimeException failure) { status.put("state", "failed").put("error", failure.toString()); }
            }, "OpenwigLayerMove");
            // Move the layers to an index, without copying, then report.
            bank.Gef(java.util.List.of(layer), destination > source ? destination + 1 : destination, false, callback);
            return new JSONObject(status.toString());
        }
        if (method.equals("device.layer_delete")) {
            Object owning = layer.Fhl(); // the bank that owns the layer
            if (!(owning instanceof KFW bank))
                throw new IllegalArgumentException("deletion requires a parallel layer");
            JSONObject status = newDeviceJob();
            Runnable remove = () -> {
                try {
                    if (layer.Fhl() != bank) throw new IllegalArgumentException("layer moved during state preparation");
                    int before = bank.LYs().size();
                    DirectAccess.runInUndo(track.clipTimeline(), () -> {
                        bank.yy(); // begin a bank edit
                        try {
                            com.bitwig.flt.document.core.master.device.ypF.beI(layer); // detach the layer's references
                            bank.beI(layer, true); // delete it from the bank
                        } finally { bank.waK(); } // end the bank edit
                    });
                    int after = bank.LYs().size();
                    if (after != before - 1 || layer.L_() != null) throw new IllegalStateException("layer ownership was not removed");
                    status.put("data", new JSONObject().put("count", after)).put("state", "complete");
                } catch (RuntimeException failure) { status.put("state", "failed").put("error", failure.toString()); }
            };
            // Prepare the layers' plugin states, then remove; errors fail the job.
            com.bitwig.flt.document.core.master.oIf.beI(java.util.List.of(layer), remove,
                    (com.bitwig.base.async.JDx) new NativeCallback(status, () -> { }, "OpenwigLayerDeleteError"));
            return new JSONObject(status.toString());
        }
        JSONObject changes = params.getJSONObject("changes");
        if (changes.isEmpty()) throw new IllegalArgumentException("layer changes required");
        if (changes.has("volume") && changes.has("volume_db")) throw new IllegalArgumentException("choose volume or volume_db");
        Mixer mixer = layerMixer(layer);
        for (String key : changes.keySet()) {
            Object value = changes.get(key);
            switch (key) {
                case "name" -> { if (!(value instanceof String name) || name.length() > 256) throw new IllegalArgumentException("layer name string required (maximum 256)"); }
                case "volume", "volume_db", "pan" -> {
                    if (mixer == null || !(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("finite mixer value required");
                    double n = ((Number) value).doubleValue();
                    double low = key.equals("volume") ? 0 : key.equals("pan") ? -1 : -120;
                    double high = key.equals("volume") ? 1.9952623149688795 : key.equals("pan") ? 1 : 6;
                    if (n < low || n > high) throw new IllegalArgumentException("layer mixer value out of range: " + key);
                }
                case "muted" -> { if (mixer == null || !(value instanceof Boolean)) throw new IllegalArgumentException("mixer muted boolean required"); }
                default -> throw new IllegalArgumentException("unknown layer field: " + key);
            }
        }
        DirectAccess.runInUndo(track.clipTimeline(), () -> {
            if (changes.has("name")) layer.byf(changes.getString("name")); // rename
            if (changes.has("volume")) mixer.getVolume().setValue(changes.getDouble("volume"), Unit.RATIO);
            if (changes.has("volume_db")) mixer.getVolume().setValue(changes.getDouble("volume_db"), Unit.DECIBEL);
            if (changes.has("pan")) mixer.getPan().setValue(changes.getDouble("pan") * 100, Unit.PERCENTAGE_OF_RANGE);
            if (changes.has("muted")) mixer.getMute().setValue(changes.getBoolean("muted"));
        });
        return layerState(owner, layer);
    }

    // Factory modulators registered in the exact build's packaging table (MD/LXO).
    private static JSONArray modulatorCatalog() {
        JSONArray result = new JSONArray();
        for (Object item : com.bitwig.flt.packaging.core.SJF.beI()) { // the factory device table
            com.bitwig.flt.packaging.core.odK entry = (com.bitwig.flt.packaging.core.odK) item;
            if (!((Enum<?>) (Object) entry.sCA()).name().equals("MODULATOR")) continue; // its kind
            result.put(new JSONObject().put("name", entry.Gef())
                    .put("modulator_id", entry.beI().toString()));
        }
        return result;
    }

    private static int integerField(JSONObject params, String key, int low, int high) {
        Object value = params.opt(key);
        if (!(value instanceof Integer number) || number < low || number > high)
            throw new IllegalArgumentException(key + " must be an integer within " + low + ".." + high);
        return number;
    }

    private static Object modulatorOwner(Track track, JSONObject params, String key) {
        java.util.List<?> members = track.deviceChain().getDevices();
        int index = integerField(params, key, 0, Math.max(0, members.size() - 1));
        if (index >= members.size()) throw new IllegalArgumentException(key + " index out of range");
        return ((ApiObject) members.get(index)).getTarget();
    }

    private static itZ modulatorBank(Object owner) {
        itZ bank = owner instanceof sgH device ? device.LdK() : null;
        if (bank == null) throw new IllegalArgumentException("device has no modulator bank");
        return bank;
    }

    private static java.util.List<?> modulatorList(Object owner) {
        return modulatorBank(owner).dvk(); // the bank's modulators
    }

    // Hx3 source components of one modulator, keyed by their stored component key.
    private static java.util.Map<String, Object> modulationSources(Object modulator) {
        java.util.Map<String, Object> sources = new java.util.LinkedHashMap<>();
        Object children = componentChildren(((QFr) modulator).MvA()); // the contents' components
        if (children instanceof java.util.List<?> list)
            for (Object child : list)
                if (child instanceof com.bitwig.flt.document.core.master.device.aoy source)
                    sources.put(String.valueOf(source.az_()), child);
        return sources;
    }

    private static java.util.Map<String, Object> modulatorAtoms(Object modulator) {
        Class<?> decimal = bitwig.obf.xdh.class, bool = bitwig.obf.cbF.class, indexed = bitwig.obf.lpr.class;
        Object contents = ((QFr) modulator).MvA(); // the modulator's contents
        java.util.Map<String, Object> atoms = new java.util.LinkedHashMap<>();
        if (contents == null) return atoms;
        java.util.List<Object> found = new java.util.ArrayList<>();
        walkAtoms(contents, 0, new java.util.IdentityHashMap<>(), found, decimal, bool, indexed);
        for (Object atom : found) {
            String fullPath = automationIdentifier(modulator, atom);
            if (fullPath == null) fullPath = automationIdentifier(contents, atom);
            if (fullPath != null && fullPath.startsWith("CONTENTS/")) atoms.put(fullPath.substring("CONTENTS/".length()), atom);
        }
        return atoms;
    }

    private static Object parameterWrapper(Object atom) {
        if (atom instanceof bitwig.obf.xdh decimal) return new com.bitwig.flt.document.api.document.ApiDecimalValueAtom(decimal);
        if (atom instanceof bitwig.obf.cbF bool) return new com.bitwig.flt.document.api.document.ApiBooleanValueAtom(bool);
        if (atom instanceof bitwig.obf.lpr indexed) return new com.bitwig.flt.document.api.document.ApiIndexedValueAtom(indexed);
        throw new IllegalArgumentException("unsupported modulator parameter kind");
    }

    private static JSONObject modulatorValues(java.util.Map<String, Object> atoms, Unit unit, String unitName) {
        JSONObject values = new JSONObject();
        for (java.util.Map.Entry<String, Object> entry : atoms.entrySet()) {
            Object wrapper = parameterWrapper(entry.getValue());
            if (wrapper instanceof DoubleParameter p) values.put(entry.getKey(), toClientValue(p.getValue(unit), unitName));
            else if (wrapper instanceof BooleanParameter p) values.put(entry.getKey(), p.getValue());
            else if (wrapper instanceof EnumParameter p) values.put(entry.getKey(), p.getValue());
        }
        return values;
    }

    // Validate a whole {path: value} batch before any write: normalized 0..1
    // numbers (or explicit-unit numbers), booleans, and in-range enum indexes.
    private static java.util.Map<String, Object> stageModulatorValues(java.util.Map<String, Object> atoms,
                                                                    JSONObject params, String unitName) {
        JSONObject changes = params.optJSONObject("values");
        if (changes == null || changes.isEmpty()) throw new IllegalArgumentException("nonempty values object required");
        java.util.Map<String, Object> wrappers = new java.util.LinkedHashMap<>();
        for (String name : changes.keySet()) {
            Object atom = atoms.get(name), value = changes.get(name);
            if (atom == null) throw new IllegalArgumentException("unknown modulator parameter: " + name);
            Object wrapper = parameterWrapper(atom);
            boolean valid = wrapper instanceof BooleanParameter ? value instanceof Boolean
                    : wrapper instanceof EnumParameter ? value instanceof Integer
                    : value instanceof Number number && !(value instanceof Boolean) && Double.isFinite(number.doubleValue())
                            && (unitName != null && !unitName.isBlank() || (number.doubleValue() >= 0 && number.doubleValue() <= 1));
            if (wrapper instanceof EnumParameter && valid) valid = (Integer) value >= 0;
            if (!valid) throw new IllegalArgumentException("invalid value for modulator parameter: " + name);
            wrappers.put(name, wrapper);
        }
        return wrappers;
    }

    private static void applyModulatorPolarity(Object modulator, JSONObject params) {
        JSONObject polarity = params.optJSONObject("bipolar");
        if (polarity == null) return;
        java.util.Map<String, Object> atoms = modulatorAtoms(modulator);
        for (String name : polarity.keySet()) {
            Object atom = atoms.get(name);
            if (!(atom instanceof dQh macro))
                throw new IllegalArgumentException("not a bipolar-capable parameter: " + name);
            boolean bipolar = polarity.getBoolean(name);
            // A Macro knob copies its flag from its bipolar panel value (the Macro's
            // BIPOLAR atom) when it attaches or loads, so the panel value is what
            // the project file keeps; the knob follows it through its listener.
            bitwig.obf.jVV panel = macro.nhI(); // the bipolar panel value
            if (panel != null) panel.sCA(bipolar);
            macro.MgQ(bipolar); // the knob's own flag
        }
    }

    private static void applyModulatorValues(java.util.Map<String, Object> wrappers, JSONObject changes, String unitName) {
        Unit unit = unitByName(unitName);
        // Switches and modes first: a bipolar flag changes the range a VALUE may take.
        for (java.util.Map.Entry<String, Object> entry : wrappers.entrySet()) {
            Object value = changes.get(entry.getKey());
            if (entry.getValue() instanceof BooleanParameter p) p.setValue((Boolean) value);
            else if (entry.getValue() instanceof EnumParameter p) p.setValue((Integer) value);
        }
        for (java.util.Map.Entry<String, Object> entry : wrappers.entrySet()) {
            Object value = changes.get(entry.getKey());
            if (entry.getValue() instanceof DoubleParameter p) p.setValue(fromClientValue(((Number) value).doubleValue(), unitName), unit);
        }
    }

    private static Object bindingFor(Object source, Object atom) {
        for (Object item : ((com.bitwig.flt.document.core.master.device.aoy) source).jTT()) { // its bindings
            SZB reference = item instanceof dr2 entry ? entry.cCV() : null;
            if (reference != null && reference.neH() == atom) return item;
        }
        return null;
    }

    // Modulator editing on the device's JDY bank. Insertion, deletion, moves and
    // modulation mappings call Bitwig's own entries, each owning its undo action
    // ("Add modulator", delete modulators, "Move Modulators", the merged mapping
    // amount transaction, "Clear modulation"); parameter writes share one undo.
    private static JSONObject editModulator(String method, Track track, JSONObject params) {
        Object owner = modulatorOwner(track, params, "device");
        java.util.List<?> modulators = modulatorList(owner);
        itZ bank = modulatorBank(owner);
        if (method.equals("device.modulator_add")) {
            if (modulators.size() >= 256) throw new IllegalArgumentException("modulator limit exceeded");
            java.util.UUID id;
            try { id = java.util.UUID.fromString(params.getString("modulator_id")); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("modulator UUID required"); }
            boolean known = false;
            for (Object item : modulatorCatalog()) known |= ((JSONObject) item).getString("modulator_id").equals(id.toString());
            if (!known) throw new IllegalArgumentException("not a factory modulator: " + id);
            int x = params.has("x") ? integerField(params, "x", 0, 4095) : 0;
            int y = params.has("y") ? integerField(params, "y", 0, 2) : 0;
            if (params.has("x") && bank.beI(x, y) != null) // the modulator at this cell
                throw new IllegalArgumentException("modulator cell is occupied");
            QFr created = QFr.Bqk(id); // a new modulator from its definition ID
            if (created == null || created.MvA() == null) throw new IllegalStateException("modulator definition did not load");
            // Initial values are written to the staged modulator, so the engine
            // instantiates it with them. A write to a just-inserted modulator in
            // a separate, immediately following event was live-verified to be
            // dropped by the engine (document and audio then disagree).
            String stagedUnit = params.optString("unit", null);
            applyModulatorPolarity(created, params);
            if (params.has("values")) applyModulatorValues(stageModulatorValues(modulatorAtoms(created), params, stagedUnit),
                    params.getJSONObject("values"), stagedUnit);
            java.util.List<?> before = new java.util.ArrayList<>(modulators);
            // Match the GUI insertion point: cloned content, native "Add modulator".
            bank.beI(java.util.List.of(created), x, y, true, (com.bitwig.base.async.PPR) null);
            java.util.List<?> after = modulatorList(owner);
            Object inserted = ((java.util.List<?>) after).stream().filter(candidate -> !before.contains(candidate)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("modulator insertion did not add a modulator"));
            if (after.size() != before.size() + 1) throw new IllegalStateException("modulator count mismatch after insertion");
            JSONObject result = new JSONObject().put("modulator", after.indexOf(inserted)).put("count", after.size());
            if (params.has("values")) result.put("values", modulatorValues(modulatorAtoms(inserted), unitByName(null), null));
            return result;
        }
        int index = integerField(params, "modulator", 0, Math.max(0, modulators.size() - 1));
        if (index >= modulators.size()) throw new IllegalArgumentException("modulator index out of range");
        QFr modulator = (QFr) modulators.get(index);
        switch (method) {
            case "device.modulator_delete" -> {
                bank.beI((java.util.List<?>) java.util.List.of(modulator)); // delete modulators
                if (modulatorList(owner).contains(modulator) || modulator.L_() != null)
                    throw new IllegalStateException("modulator was not removed");
                return new JSONObject().put("count", modulatorList(owner).size());
            }
            case "device.modulator_move" -> {
                int x = integerField(params, "x", 0, 4095), y = integerField(params, "y", 0, 2);
                int dx = x - modulator.MgQ(), dy = y - modulator.unz(); // from its panel position
                if (dx == 0 && dy == 0) return new JSONObject().put("modulator", index).put("x", x).put("y", y);
                if (bank.beI(x, y) != null) // the modulator at this cell
                    throw new IllegalArgumentException("modulator cell is occupied");
                bank.beI(modulator, java.util.List.of(modulator), dx, dy, (com.bitwig.base.async.PPR) null); // move by an offset
                if (modulator.MgQ() != x || modulator.unz() != y)
                    throw new IllegalStateException("modulator did not reach its requested cell");
                return new JSONObject().put("modulator", modulatorList(owner).indexOf(modulator)).put("x", x).put("y", y);
            }
            case "device.modulator_parameters" -> {
                java.util.Map<String, Object> atoms = modulatorAtoms(modulator);
                String unitName = params.optString("unit", null);
                if (params.has("bipolar"))
                    DirectAccess.runInUndo(track.clipTimeline(), () -> applyModulatorPolarity(modulator, params));
                if (params.has("values")) {
                    java.util.Map<String, Object> wrappers = stageModulatorValues(atoms, params, unitName);
                    DirectAccess.runInUndo(track.clipTimeline(), () -> applyModulatorValues(wrappers, params.getJSONObject("values"), unitName));
                }
                return new JSONObject().put("modulator", index).put("values", modulatorValues(atoms, unitByName(unitName), unitName));
            }
            case "device.modulator_rename" -> {
                String name = params.getString("name");
                if (name.length() > 256) throw new IllegalArgumentException("modulator name too long");
                if (params.has("parameter")) {
                    Object atom = modulatorAtoms(modulator).get(params.getString("parameter"));
                    if (atom == null) throw new IllegalArgumentException("unknown modulator parameter: " + params.getString("parameter"));
                    // A Macro knob relabels itself from its name panel value when it
                    // attaches or loads, so the panel value is what the project file keeps.
                    bitwig.obf.eqS panel = atom instanceof dQh macro ? macro.XUO() : null; // the name panel value
                    DirectAccess.runInUndo(track.clipTimeline(), () -> {
                        if (panel != null) panel.byf(name);
                        ((OMh) atom).Bqk(name); // the atom's label
                    });
                    return new JSONObject().put("modulator", index).put("parameter", params.getString("parameter")).put("label", ((OMh) atom).Bqd());
                }
                // This writes user_device_name, which the file keeps; the displayed
                // device_name is recomputed from it. An empty name restores the
                // definition's numbered title.
                DirectAccess.runInUndo(track.clipTimeline(), () -> modulator.PJ3(name));
                return new JSONObject().put("modulator", index).put("name", modulator.af())
                        .put("user_name", modulator.jao());
            }
            case "device.modulator_bind", "device.modulator_unbind" -> {
                java.util.Map<String, Object> sources = modulationSources(modulator);
                String key;
                if (params.has("source")) {
                    key = params.getString("source");
                    if (!sources.containsKey(key)) throw new IllegalArgumentException("unknown modulation source " + key + "; available " + sources.keySet());
                } else if (sources.size() == 1) key = sources.keySet().iterator().next();
                else throw new IllegalArgumentException("modulation source required; available " + sources.keySet());
                com.bitwig.flt.document.core.master.device.aoy source = (com.bitwig.flt.document.core.master.device.aoy) sources.get(key);
                Object targetOwner;
                if (params.has("target_component")) {
                    // A device inside one of the modulator owner's nested chains.
                    SRR layer = (SRR) deviceComponent(owner, params.getString("target_component"), SRR.class);
                    Object stored = layer.YQe(); // its device chain
                    if (stored == null) throw new IllegalArgumentException("target chain has no devices");
                    java.util.List<?> nested = wrapDeviceChain(stored).getDevices();
                    int at = integerField(params, "target_device", 0, Math.max(0, nested.size() - 1));
                    if (at >= nested.size()) throw new IllegalArgumentException("target_device index out of range");
                    targetOwner = ((ApiObject) nested.get(at)).getTarget();
                } else if (params.has("target_chain")) {
                    // A device anywhere in the track: chain steps from the track's own chain.
                    ApiProject scope = project();
                    try {
                        Track root = deviceScope(resolveTrack(scope, new JSONObject().put("path", params.getString("path"))),
                                new JSONObject().put("chain", params.getJSONArray("target_chain")));
                        targetOwner = modulatorOwner(root, params, "target_device");
                    } finally { scope.dispose(); }
                } else targetOwner = params.has("target_device") ? modulatorOwner(track, params, "target_device") : owner;
                String parameter = params.optString("target_parameter", "");
                if (parameter.isBlank() || parameter.startsWith("/") || parameter.contains("..") || parameter.length() > 1024)
                    throw new IllegalArgumentException("target_parameter path required");
                Object atom;
                if (parameter.startsWith("MODULATORS/")) atom = findTargetAtom(targetOwner, parameter);
                else {
                    try { atom = deviceComponent(targetOwner, "CONTENTS/" + parameter, bitwig.obf.Ae2.class); }
                    catch (IllegalArgumentException notAParameter) {
                        // A device's On switch and other reader targets outside CONTENTS.
                        atom = findTargetAtom(targetOwner, parameter);
                    }
                }
                if (atom == null) throw new IllegalArgumentException("modulation target not found: " + parameter);
                if (method.equals("device.modulator_unbind")) {
                    if (bindingFor(source, atom) == null) throw new IllegalArgumentException("no modulation mapping to " + parameter);
                    source.sCA((Ae2) atom); // clear the mapping
                    if (bindingFor(source, atom) != null) throw new IllegalStateException("modulation mapping was not removed");
                    return new JSONObject().put("modulator", index).put("source", key).put("removed", true);
                }
                Object amountValue = params.opt("amount");
                boolean nativeAmount = params.has("native_amount");
                if (nativeAmount) amountValue = params.opt("native_amount");
                if (!(amountValue instanceof Number amount) || amountValue instanceof Boolean || !Double.isFinite(amount.doubleValue())
                        || (!nativeAmount && Math.abs(amount.doubleValue()) > 1))
                    throw new IllegalArgumentException("amount must be a finite number within -1..1");
                if (!source.beI((bitwig.obf.Wrh) atom)) // can this source modulate the target
                    throw new IllegalArgumentException("Bitwig does not allow this modulation target: " + parameter);
                bitwig.obf.bU modulation = atom instanceof QCX modulated ? modulated.yy() : null; // the target's modulation state
                double span = modulation == null ? Double.NaN : modulation.y3f(); // its modulation range
                if (!Double.isFinite(span) || span <= 0) throw new IllegalArgumentException("target has no modulation range");
                // Creates or updates the mapping in Bitwig's own (mergeable) mapping
                // transaction; amount is native, clamped to +/-span.
                source.Gef((Ae2) atom, nativeAmount ? amount.doubleValue() : amount.doubleValue() * span);
                Object binding = bindingFor(source, atom);
                if (binding == null) throw new IllegalStateException("modulation mapping was not created");
                return new JSONObject().put("modulator", index).put("source", key)
                        .put("amount", ((dr2) binding).byf() / span)
                        .put("native_amount", ((dr2) binding).byf());
            }
            default -> throw new IllegalArgumentException("unsupported modulator operation");
        }
    }

    private static JSONArray chainBanks(Object owner) {
        JSONArray banks = new JSONArray();
        collectChainBanks(owner, owner instanceof utt nativeDevice ? nativeDevice.edx() : null, banks, new java.util.IdentityHashMap<>(), 0);
        if (owner instanceof Mv pluginDevice) {
            R1c plugin = pluginDevice.dcC(); // the plugin instance holder
            collectChainBanks(owner, plugin == null ? null : plugin.QfO(), banks, new java.util.IdentityHashMap<>(), 0);
        }
        return banks;
    }

    private static void collectChainBanks(Object owner, Object node, JSONArray banks,
            java.util.IdentityHashMap<Object, Boolean> seen, int depth) {
        if (node == null || depth > 32 || seen.size() > 10000 || seen.put(node, true) != null) return;
        if ((node instanceof com.bitwig.flt.document.core.master.device.YVC)) {
            banks.put(new JSONObject().put("component", jsonModelValue(automationIdentifier(owner, node)))
                    .put("count", ((YVC) node).LYs().size())
                    .put("can_create_layer", (node instanceof com.bitwig.flt.document.core.master.device.KFW)));
            return;
        }
        if ((node instanceof com.bitwig.flt.document.core.master.device.SRR)) return;
        Object children = componentChildren(node);
        if (children instanceof java.util.List<?> list)
            for (Object child : list) collectChainBanks(owner, child, banks, seen, depth + 1);
    }

    private static NativeDevice nativeDevice(ApiProject project, int trackIndex, int deviceIndex) {
        return nativeDeviceOnTrack(track(project, trackIndex), deviceIndex);
    }

    private static DoubleParameter automationParameter(ApiProject project, JSONObject params) {
        String name = params.getString("param");
        if (name.equals("tempo")) return project.getTransport().getTempo();
        Track target = resolveTrack(project, params);
        if (name.equals("volume")) return target.mixer().getVolume();
        if (name.equals("pan")) return target.mixer().getPan();
        DoubleParameter parameter = nativeDeviceOnTrack(target,
                params.optInt("device", 0)).getDoubleParameter(name);
        if (parameter == null) throw new IllegalArgumentException("no numeric parameter: " + name);
        return parameter;
    }

    private static BrN automationTimeline(DoubleParameter parameter) {
        eIn lane = ((Ae2) ((ApiObject) parameter).getTarget()).Or();
        return lane == null ? null : lane.tWW().Fhl();
    }

    private static JSONArray automationPoints(DoubleParameter parameter) {
        JSONArray result = new JSONArray();
        BrN timeline = automationTimeline(parameter);
        if (timeline == null) return result;
        aG range = ((xdh) ((ApiObject) parameter).getTarget()).XdI();
        for (Object item : timeline.beI()) {
            g13 point = (g13) item;
            result.put(new JSONObject().put("beat", point.ie())
                    .put("normalized", range.beI(point.beI()))
                    .put("curvature", point.dT_())
                    .put("interpolation", point.MgQ() == r1.beI ? "hold" : "linear"));
        }
        return result;
    }

    /** Replace one arranger automation lane after validating the complete request. */
    public static JSONObject automation(JSONObject params, boolean write) {
        ApiProject project = project();
        try {
            DoubleParameter parameter = automationParameter(project, params);
            if (write && params.optBoolean("shape", false)) {
                JSONArray points = params.getJSONArray("points");
                BrN timeline = automationTimeline(parameter);
                if (timeline == null) throw new IllegalStateException("automation lane missing after document commit");
                // Bitwig adds a beat-zero anchor when the requested lane starts
                // later. Preserve it, and shape only the points the caller sent.
                int offset = timeline.beI().size() == points.length() + 1
                        && points.getJSONArray(0).getDouble(0) > 0
                        && ((g13) timeline.beI().get(0)).ie() == 0 ? 1 : 0;
                if (timeline.beI().size() != points.length() + offset)
                    throw new IllegalStateException("automation point count mismatch after document commit");
                for (int i = 0; i < points.length(); i++) {
                    if (Math.abs(((g13) timeline.beI().get(i + offset)).ie()
                            - points.getJSONArray(i).getDouble(0)) > 1e-6)
                        throw new IllegalStateException("automation point time mismatch after document commit");
                }
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    aG range = ((xdh) ((ApiObject) parameter).getTarget()).XdI();
                    for (int i = 0; i < points.length(); i++) {
                        JSONArray input = points.getJSONArray(i);
                        g13 point = (g13) timeline.beI().get(i + offset);
                        String unit = params.optString("unit", "");
                        if (!params.getString("param").equals("tempo") && unit.isBlank()) {
                            point.beI(range.Gef(input.getDouble(1)));
                        }
                        boolean hold = input.optString(3, "linear").equals("hold");
                        point.beI(hold ? r1.beI : r1.Gef);
                        // Native serialization discards curvature on hold segments.
                        point.Gef(hold ? 0 : input.optDouble(2, 0));
                    }
                });
            } else if (write && params.optBoolean("clear", false)) {
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    BrN existing = automationTimeline(parameter);
                    if (existing != null) existing.qVz();
                });
            } else if (write) {
                JSONArray points = params.getJSONArray("points");
                if (points.length() < 2 || points.length() > 4096) {
                    throw new IllegalArgumentException("automation needs 2..4096 points");
                }
                String name = params.getString("param");
                String unitName = params.optString("unit", null);
                Unit unit = name.equals("tempo") ? Unit.BPM : unitByName(unitName);
                AutomationEvents events = project.getFactory().createDoubleAutomationEvents(unit);
                double previous = -1;
                for (int i = 0; i < points.length(); i++) {
                    JSONArray point = points.getJSONArray(i);
                    if (point.length() < 2 || point.length() > 4) {
                        throw new IllegalArgumentException("point must be [beat,value,curvature?,interpolation?]");
                    }
                    double beat = point.getDouble(0);
                    double value = point.getDouble(1);
                    double curvature = point.optDouble(2, 0);
                    String mode = point.optString(3, "linear");
                    if (!Double.isFinite(beat) || beat < 0 || beat <= previous
                            || !Double.isFinite(value) || !Double.isFinite(curvature)
                            || curvature < -1 || curvature > 1
                            || (!mode.equals("linear") && !mode.equals("hold"))) {
                        throw new IllegalArgumentException("invalid automation point at " + i);
                    }
                    if (name.equals("tempo")) {
                        if (value < 20 || value > 666) throw new IllegalArgumentException("tempo must be 20..666 BPM");
                    } else {
                        value = fromClientValue(value, unitName);
                        if (name.equals("pan") && (unitName == null || unitName.isBlank())) {
                            value = value * 2 - 100;
                        }
                    }
                    events.addPoint(beat, value);
                    previous = beat;
                }
                // The public API creates the lane; model caches update after this
                // document event. Shape its points in a subsequent committed event.
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    BrN existing = automationTimeline(parameter);
                    if (existing != null) existing.qVz();
                    parameter.setAutomation(events);
                });
            }
            JSONArray result = automationPoints(parameter);
            return new JSONObject().put("count", result.length()).put("points", result);
        } finally {
            project.dispose();
        }
    }

    private static Unit unitByName(String name) {
        if (name == null || name.isBlank()) return Unit.PERCENTAGE_OF_RANGE;
        return Unit.valueOf(name);
    }

    private static double fromClientValue(double value, String unitName) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("parameter value must be finite");
        if (unitName == null || unitName.isBlank()) {
            if (value < 0 || value > 1) {
                throw new IllegalArgumentException("normalized parameter value must be between 0 and 1");
            }
            return value * 100;
        }
        return value;
    }

    private static double toClientValue(double value, String unitName) {
        return unitName == null || unitName.isBlank() ? value / 100 : value;
    }

    private static JSONObject setDeviceParametersOnTrack(Track targetTrack, int deviceIndex,
                                                         JSONObject params, String unitName) {
        NativeDevice device = nativeDeviceOnTrack(targetTrack, deviceIndex);
        Unit unit = unitByName(unitName);
        java.util.Map<String, String> kinds = parameterKinds(device);
        for (String name : params.keySet()) {
            Object value = params.get(name);
            String kind = kinds.get(name);
            if ("boolean".equals(kind) && value instanceof Boolean) continue;
            if ("double".equals(kind) && value instanceof Number number) {
                fromClientValue(number.doubleValue(), unitName);
                continue;
            }
            if ("enum".equals(kind) && value instanceof Number number
                    && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue())
                    && number.doubleValue() >= Integer.MIN_VALUE
                    && number.doubleValue() <= Integer.MAX_VALUE) continue;
            throw new IllegalArgumentException("invalid value or unknown parameter: " + name);
        }
        JSONObject out = new JSONObject();
        for (String name : params.keySet()) {
            Object value = params.get(name);
            String kind = kinds.get(name);
            if ("boolean".equals(kind)) {
                boolean flag = (Boolean) value;
                BooleanParameter p = device.getBooleanParameter(name);
                if (p == null) throw new IllegalArgumentException("no boolean parameter: " + name);
                p.setValue(flag);
                out.put(name, p.getValue());
            } else if ("double".equals(kind)) {
                Number number = (Number) value;
                DoubleParameter p = device.getDoubleParameter(name);
                if (p == null) throw new IllegalArgumentException("no double parameter: " + name);
                p.setValue(fromClientValue(number.doubleValue(), unitName), unit);
                out.put(name, toClientValue(p.getValue(unit), unitName));
            } else if ("enum".equals(kind)) {
                int index = ((Number) value).intValue();
                EnumParameter p = device.getEnumParameter(name);
                if (p == null) throw new IllegalArgumentException("no enum parameter: " + name);
                p.setValue(index);
                out.put(name, p.getValue());
            }
        }
        return out;
    }

    public static JSONObject setDeviceParameters(int trackIndex, int deviceIndex,
                                                 JSONObject params, String unitName) {
        ApiProject project = project();
        try {
            return setDeviceParametersOnTrack(track(project, trackIndex), deviceIndex, params, unitName);
        } finally {
            project.dispose();
        }
    }

    private static JSONObject getDeviceParametersOnTrack(Track targetTrack, int deviceIndex,
                                                         JSONArray names, String unitName) {
        NativeDevice device = nativeDeviceOnTrack(targetTrack, deviceIndex);
        Unit unit = unitByName(unitName);
        java.util.Map<String, String> kinds = parameterKinds(device);
        JSONObject out = new JSONObject();
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i);
            String kind = kinds.get(name);
            if ("double".equals(kind)) {
                DoubleParameter p = device.getDoubleParameter(name);
                if (p == null) throw new IllegalArgumentException("no double parameter: " + name);
                out.put(name, toClientValue(p.getValue(unit), unitName));
            } else if ("boolean".equals(kind)) {
                BooleanParameter p = device.getBooleanParameter(name);
                if (p == null) throw new IllegalArgumentException("no boolean parameter: " + name);
                out.put(name, p.getValue());
            } else if ("enum".equals(kind)) {
                EnumParameter p = device.getEnumParameter(name);
                if (p == null) throw new IllegalArgumentException("no enum parameter: " + name);
                out.put(name, p.getValue());
            } else {
                throw new IllegalArgumentException("unknown device parameter: " + name);
            }
        }
        return out;
    }

    public static JSONObject getDeviceParameters(int trackIndex, int deviceIndex,
                                                 JSONArray names, String unitName) {
        ApiProject project = project();
        try {
            return getDeviceParametersOnTrack(track(project, trackIndex), deviceIndex, names, unitName);
        } finally {
            project.dispose();
        }
    }

    private static JSONObject renameDeviceOnTrack(Track targetTrack, int deviceIndex, String name) {
        java.util.List<?> members = targetTrack.deviceChain().getDevices();
        if (deviceIndex < 0 || deviceIndex >= members.size()) {
            throw new IllegalArgumentException("device index out of range: " + deviceIndex);
        }
        Device device = (Device) members.get(deviceIndex);
        device.setDisplayName(name);
        return new JSONObject().put("index", deviceIndex)
                .put("display_name", device.getDisplayName());
    }

    public static JSONObject renameDevice(int trackIndex, int deviceIndex, String name) {
        ApiProject project = project();
        try {
            return renameDeviceOnTrack(track(project, trackIndex), deviceIndex, name);
        } finally {
            project.dispose();
        }
    }

    private static JSONObject setDeviceExpandedOnTrack(Track targetTrack, int deviceIndex, boolean expanded) {
        java.util.List<?> members = targetTrack.deviceChain().getDevices();
        if (deviceIndex < 0 || deviceIndex >= members.size()) {
            throw new IllegalArgumentException("device index out of range: " + deviceIndex);
        }
        Device device = (Device) members.get(deviceIndex);
        device.setIsExpanded(expanded);
        return new JSONObject().put("index", deviceIndex).put("expanded", expanded);
    }

    public static JSONObject setDeviceExpanded(int trackIndex, int deviceIndex, boolean expanded) {
        ApiProject project = project();
        try {
            return setDeviceExpandedOnTrack(track(project, trackIndex), deviceIndex, expanded);
        } finally {
            project.dispose();
        }
    }

    private static int deleteTrackDeviceOnTrack(Track targetTrack, int deviceIndex) {
        DeviceChain chain = targetTrack.deviceChain();
        java.util.List<?> members = chain.getDevices();
        if (deviceIndex < 0 || deviceIndex >= members.size()) {
            throw new IllegalArgumentException("device index out of range: " + deviceIndex);
        }
        Object device = members.get(deviceIndex);
        DirectAccess.runInUndo(targetTrack.clipTimeline(),
                () -> DirectAccess.removeDevice(chain, device));
        return chain.getDevices().size();
    }

    public static int deleteTrackDevice(int trackIndex, int deviceIndex) {
        ApiProject project = project();
        try {
            return deleteTrackDeviceOnTrack(track(project, trackIndex), deviceIndex);
        } finally {
            project.dispose();
        }
    }

    /** Walk the device's CONTENTS children using bFO.hMI(), a read-only model getter. */
    private static JSONArray listDeviceParametersOnTrack(Track targetTrack, int deviceIndex) {
        java.util.List<?> members = targetTrack.deviceChain().getDevices();
        if (deviceIndex < 0 || deviceIndex >= members.size()) {
            throw new IllegalArgumentException("device index out of range: " + deviceIndex);
        }
        return listDeviceParameters((Device) members.get(deviceIndex));
    }

    public static JSONArray listDeviceParameters(int trackIndex, int deviceIndex) {
        ApiProject project = project();
        try {
            return listDeviceParametersOnTrack(track(project, trackIndex), deviceIndex);
        } finally { project.dispose(); }
    }

    private static JSONArray listDeviceParameters(Device device) {
        Object target = ((ApiObject) device).getTarget();
        // A pure getter for the native device's CONTENTS root.
        if (!(target instanceof utt)) throw new IllegalStateException("native device contents unavailable");
        Object contents = deviceContents(target);
        Class<?> decimal = bitwig.obf.xdh.class;
        Class<?> bool = bitwig.obf.cbF.class;
        Class<?> indexed = bitwig.obf.lpr.class;
        java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.List<Object> found = new java.util.ArrayList<>();
        walkAtoms(contents, 0, seen, found, decimal, bool, indexed);
        JSONArray out = new JSONArray();
        if (!hasIdentifierPaths(target)) return out;
        for (Object atom : found) {
            JSONObject item = new JSONObject().put("kind", atomKind(atom, decimal, bool, indexed));
            String path = automationIdentifier(target, atom);
            if (path == null || !path.startsWith("CONTENTS/")) continue;
            item.put("path", path.substring("CONTENTS/".length()));
            out.put(item);
        }
        return out;
    }

    private static java.util.Map<String, String> parameterKinds(Device device) {
        JSONArray available = listDeviceParameters(device);
        java.util.Map<String, String> kinds = new java.util.HashMap<>();
        for (int i = 0; i < available.length(); i++) {
            JSONObject item = available.getJSONObject(i);
            kinds.put(item.getString("path"), item.getString("kind"));
        }
        return kinds;
    }

    private static String atomKind(Object atom, Class<?> decimal, Class<?> bool, Class<?> indexed) {
        if (decimal != null && decimal.isInstance(atom)) return "double";
        if (bool != null && bool.isInstance(atom)) return "boolean";
        if (indexed != null && indexed.isInstance(atom)) return "enum";
        return "unknown";
    }

    /** Whether this model object renders component identifier paths (as preset files store them). */
    private static boolean hasIdentifierPaths(Object owner) {
        return owner instanceof LRP || owner instanceof com.bitwig.flt.document.core.master.device.QkE;
    }

    private static void walkAtoms(Object node, int depth,
                                  java.util.Map<Object, Boolean> seen,
                                  java.util.List<Object> found,
                                  Class<?> decimal, Class<?> bool, Class<?> indexed) {
        if (node == null || depth > 20 || seen.size() >= 5000 || seen.containsKey(node)) {
            return;
        }
        seen.put(node, Boolean.TRUE);
        if ((decimal != null && decimal.isInstance(node))
                || (bool != null && bool.isInstance(node))
                || (indexed != null && indexed.isInstance(node))) {
            found.add(node);
            return;
        }
        if (node instanceof nWI grid) {
            for (Object module : grid.dvk()) // a Grid's modules, each with its own contents
                walkAtoms(((ZFS) module).MvA(), depth + 1, seen, found, decimal, bool, indexed);
            return;
        }
        if (componentChildren(node) instanceof java.util.List<?> children) // no children: a leaf
            for (Object child : children)
                walkAtoms(child, depth + 1, seen, found, decimal, bool, indexed);
    }

    private static JSONObject setDeviceEnabledOnTrack(Track targetTrack, int deviceIndex, boolean enabled) {
        java.util.List<?> members = targetTrack.deviceChain().getDevices();
        if (deviceIndex < 0 || deviceIndex >= members.size()) {
            throw new IllegalArgumentException("device index out of range: " + deviceIndex);
        }
        Device device = (Device) members.get(deviceIndex);
        device.getEnabled().setValue(enabled);
        return new JSONObject().put("index", deviceIndex)
                .put("display_name", device.getDisplayName())
                .put("enabled", device.getEnabled().getValue());
    }

    public static JSONObject setDeviceEnabled(int trackIndex, int deviceIndex, boolean enabled) {
        ApiProject project = project();
        try {
            return setDeviceEnabledOnTrack(track(project, trackIndex), deviceIndex, enabled);
        } finally {
            project.dispose();
        }
    }

    private static int insertFactoryDeviceOnTrack(ApiProject project, Track targetTrack, String name) {
        java.util.UUID id = DirectAccess.factoryDeviceId(name + ".bwdevice");
        if (id == null) {
            throw new IllegalArgumentException("unknown Bitwig factory device: " + name);
        }
        return insertNativeDeviceOnTrack(project, targetTrack, id);
    }

    // Native device definitions are identified by the UUID the reader reports
    // as device_id; renamed or preset devices keep their definition's UUID.
    private static int insertNativeDeviceOnTrack(ApiProject project, Track targetTrack, java.util.UUID id) {
        String stage = "create device";
        try {
            NativeDevice device = project.getFactory().createNativeDevice(id);
            stage = "insert device";
            DeviceChain chain = targetTrack.deviceChain();
            DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> chain.addDevice(device));
            stage = "count devices";
            return chain.getDevices().size();
        } catch (Throwable failure) {
            throw new IllegalStateException("device stage " + stage, failure);
        }
    }

    public static int insertFactoryDevice(int trackIndex, String name) {
        ApiProject project = project();
        try {
            return insertFactoryDeviceOnTrack(project, track(project, trackIndex), name);
        } finally {
            project.dispose();
        }
    }

    private static JSONObject trackSnapshot(Track track, int index) {
        Mixer mixer = track.mixer();
        JSONArray sends = new JSONArray();
        for (int i = 0; i < mixer.getSendCount(); i++) {
            sends.put(mixer.getSend(i).getAmount()
                    .getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0);
        }
        Color color = track.getColor();
        Object rawTrack = ((ApiObject) track).getTarget();
        bitwig.obf.PiT model = rawTrack instanceof bitwig.obf.PiT t ? t : null;
        Object comment = model == null ? null : model.wze(); // the track comment
        bitwig.obf.nCQ channel = model == null ? null : model.pEy(); // the track's channel
        Object solo = channel == null ? null : channel.tWW(); // soloed
        return new JSONObject()
                .put("index", index)
                .put("name", track.getTitle())
                .put("comment", comment == null ? "" : comment)
                .put("volume", mixer.getVolume().getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0)
                .put("pan", (mixer.getPan().getValue(Unit.PERCENTAGE_OF_RANGE) + 100.0) / 200.0)
                .put("mute", mixer.getMute().getValue())
                .put("solo", solo instanceof Boolean flag && flag)
                .put("sends", sends)
                .put("color", color == null ? JSONObject.NULL : color.toHex());
    }

    public static JSONObject trackSnapshot(int trackIndex) {
        ApiProject project = project();
        try {
            return trackSnapshot(track(project, trackIndex), trackIndex);
        } finally {
            project.dispose();
        }
    }

    /** Read the whole open project: metadata, cue markers, all tracks, devices, clips, notes. */
    public static JSONObject dumpProject() {
        ApiProject project = project();
        try {
            JSONObject out = new JSONObject();
            Transport transport = project.getTransport();
            JSONObject transportInfo = new JSONObject()
                    .put("tempo", transport.getTempo().getValue(Unit.BPM))
                    .put("groove_enabled", transport.getEnableGroove().getValue())
                    .put("shuffle", transport.getShuffleAmount()
                            .getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0);
            TimeSignature signature = transport.getTimeSignature();
            if (signature != null) {
                transportInfo.put("time_signature", new JSONObject()
                        .put("numerator", signature.getNumerator())
                        .put("denominator", signature.getDenominator()));
            }
            out.put("transport", transportInfo);
            JSONObject metadata = new JSONObject();
            for (ProjectMetadata key : ProjectMetadata.values()) {
                String value = project.getMetadataText(key);
                if (value != null && !value.isBlank()) {
                    metadata.put(key.name().toLowerCase(java.util.Locale.ROOT), value);
                }
            }
            out.put("metadata", metadata);
            JSONArray markers = new JSONArray();
            for (Object item : project.getCueMarkers().getEvents()) {
                CueMarker marker = (CueMarker) item;
                Color color = marker.getColor();
                markers.put(new JSONObject().put("time", marker.getTime())
                        .put("name", marker.getTitle())
                        .put("color", color == null ? JSONObject.NULL : color.toHex()));
            }
            out.put("cue_markers", markers);
            java.util.IdentityHashMap<Object, JSONObject> context = routingContext(project);
            java.util.IdentityHashMap<Object, JSONObject> owners = deviceOwners(project);
            java.util.IdentityHashMap<Object, JSONObject> atomOwners = atomOwnerIndex(project);
            int scenesCount = sceneCount();
            JSONArray scenes = new JSONArray();
            for (int i = 0; i < scenesCount; i++) {
                com.bitwig.extension.api.project.Scene scene = project.getScene(i);
                Color color = scene.getColor();
                Object rawScene = ((ApiObject) scene).getTarget();
                Object comment = rawScene instanceof bitwig.obf.dYM stored ? stored.iUU() : null; // the scene comment
                scenes.put(new JSONObject().put("index", i).put("name", scene.getTitle())
                        .put("comment", comment == null ? "" : comment)
                        .put("color", color == null ? JSONObject.NULL : color.toHex()));
            }
            out.put("scenes", scenes).put("scene_count", scenesCount);
            JSONArray main = new JSONArray();
            java.util.List<?> tops = project.getTrackGroup().mainTracks();
            for (int i = 0; i < tops.size(); i++) {
                main.put(readTrackOrGroup((TrackOrTrackGroup) tops.get(i), i, ((TrackOrTrackGroup) tops.get(i)).getTitle(), scenesCount, context, owners, atomOwners));
            }
            out.put("main_tracks", main);
            JSONArray effects = new JSONArray();
            java.util.List<?> fx = project.getTrackGroup().effectTracks();
            for (int i = 0; i < fx.size(); i++) {
                effects.put(readTrackOrGroup((TrackOrTrackGroup) fx.get(i), -i - 2, "fx/" + ((TrackOrTrackGroup) fx.get(i)).getTitle(), scenesCount, context, owners, atomOwners));
            }
            out.put("effect_tracks", effects);
            out.put("master", readTrackOrGroup(project.getTrackGroup().masterTrack(), -1, "master", scenesCount, context, owners, atomOwners));
            return out;
        } finally {
            project.dispose();
        }
    }

    private static JSONObject readTrackOrGroup(TrackOrTrackGroup item, int index, String path, int scenesCount,
                                               java.util.IdentityHashMap<Object, JSONObject> context,
                                               java.util.IdentityHashMap<Object, JSONObject> owners,
                                               java.util.IdentityHashMap<Object, JSONObject> atomOwners) {
        Color color = item.getColor();
        JSONObject out = new JSONObject()
                .put("index", index)
                .put("name", item.getTitle())
                .put("path", path)
                .put("is_group", item instanceof TrackGroup)
                .put("color", color == null ? JSONObject.NULL : color.toHex());
        if (item instanceof TrackGroup group) {
            JSONArray children = new JSONArray();
            java.util.List<?> members = group.mainTracks();
            for (int i = 0; i < members.size(); i++) {
                children.put(readTrackOrGroup((TrackOrTrackGroup) members.get(i), i, path + "/" + ((TrackOrTrackGroup) members.get(i)).getTitle(), scenesCount, context, owners, atomOwners));
            }
            out.put("children", children);
        }
        Track view = item instanceof Track direct ? direct : item.asTrack();
        Object rawTrack = ((ApiObject) view).getTarget();
        bitwig.obf.PiT model = rawTrack instanceof bitwig.obf.PiT t ? t : null;
        Object comment = model == null ? null : model.wze(); // the track comment
        bitwig.obf.nCQ channel = model == null ? null : model.pEy(); // the track's channel
        Object solo = channel == null ? null : channel.tWW(); // soloed
        out.put("track_type", trackTypeName(view));
        out.put("is_active", view.isActive());
        out.put("comment", comment == null ? "" : comment);
        Mixer mixer = view.mixer();
        out.put("volume", mixer.getVolume().getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0)
                .put("pan", (mixer.getPan().getValue(Unit.PERCENTAGE_OF_RANGE) + 100.0) / 200.0)
                .put("mute", mixer.getMute().getValue())
                .put("solo", solo instanceof Boolean flag && flag);
        try {
            out.put("crossfade", mixer.getCrossfadeMode().getValue());
        } catch (RuntimeException unreadable) {
            // Crossfade mode is not readable on every channel kind.
        }
        JSONArray sends = new JSONArray();
        for (int i = 0; i < mixer.getSendCount(); i++) {
            Send send = mixer.getSend(i);
            JSONObject entry = new JSONObject()
                    .put("index", i)
                    .put("mode", send.getSendMode().name())
                    .put("effective_mode", send.getEffectiveSendMode().name())
                    .put("amount", send.getAmount()
                            .getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0);
            try {
                Track target = send.getTargetTrack();
                entry.put("target", target == null ? JSONObject.NULL : target.getTitle());
            } catch (RuntimeException unreadable) {
                entry.put("target", JSONObject.NULL);
            }
            sends.put(entry);
        }
        out.put("sends", sends);
        out.put("devices", deviceSnapshotList(view, path, context, owners, atomOwners));
        com.bitwig.extension.api.project.routing.Destination destination = view.destinationSelector().getDestination();
        Track destinationTrack = destination == null ? null : destination.getTrack();
        out.put("destination", destination == null ? JSONObject.NULL : destinationTrack == null ?
                sourceInfo(((ApiObject) destination).getTarget(), context) :
                context.getOrDefault(((ApiObject) destinationTrack).getTarget(),
                        new JSONObject().put("name", destinationTrack.getTitle())));
        com.bitwig.extension.api.project.routing.Source source = view.sourceSelector().getSource();
        out.put("source", sourceInfo(source == null ? null : ((ApiObject) source).getTarget(), context));
        out.put("automation", readTrackAutomation(view, path));
        JSONArray clips = new JSONArray();
        ClipTimeline timeline = view.clipTimeline();
        if (timeline != null) {
            for (Object clip : timeline.getEvents()) clips.put(readClip((Clip) clip));
        }
        out.put("clips", clips);
        JSONArray slots = new JSONArray();
        // Track slot banks can be shorter than the project scene bank.
        // Never ask clipSlot for a missing index: it silently appends a slot.
        int slotCount = ((bitwig.obf.PiT) ((ApiObject) view).getTarget()).ce_(); // the track's launcher slot count
        for (int i = 0; i < Math.min(slotCount, scenesCount); i++) {
            com.bitwig.extension.api.project.timeline.ClipSlot slot = view.clipSlot(i);
            Clip clip = slot.getClip();
            slots.put(new JSONObject().put("index", i).put("has_stop", slot.hasStopButton())
                    .put("clip", clip == null ? JSONObject.NULL : readClip(clip)));
        }
        out.put("clip_slots", slots);
        return out;
    }

    private static String trackTypeName(Track view) {
        try {
            TrackType type = view.getTrackType();
            return type == null ? null : type.name();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    // Exact-build native automation reader. Called only on document event queue.
    // All HMG lanes are returned; exporter omits targets absent from its parameter
    // identity map, so callers must distinguish raw inventory from export count.
    private static JSONArray readTrackAutomation(Track view, String path) {
        Object trackTarget = ((ApiObject) view).getTarget();
        JSONArray out = new JSONArray();
        Object pan = ((ApiObject) view.mixer().getPan()).getTarget();
        java.util.List<?> devices = view.deviceChain().getDevices();
        for (Object entry : ((bitwig.obf.PiT) trackTarget).Lna().beI()) { // the track's automation lanes
            bitwig.obf.oci lane = (bitwig.obf.oci) entry;
            Object target = lane.UnB(); // the automated parameter
            JSONObject item = new JSONObject().put("track_path", path)
                    .put("target_class", target == null ? JSONObject.NULL : target.getClass().getName())
                    .put("target_name", target == null ? JSONObject.NULL : jsonModelValue(((OMh) target).Bqd()))
                    .put("target_path", JSONObject.NULL).put("target_status", target == null ? "null" : "model");
            String targetPath = automationIdentifier(trackTarget, target);
            if (targetPath != null) item.put("target_path", targetPath);
            for (int i = 0; target != null && i < devices.size(); i++) {
                Object device = ((ApiObject) devices.get(i)).getTarget();
                String devicePath = automationIdentifier(device, target);
                if (devicePath != null && !devicePath.startsWith("PROJECT/")) {
                    item.put("device", i).put("target_path", devicePath)
                            .put("target_scope", devicePath.contains("DEVICE_CHAIN/") ? "nested_device" :
                                    devicePath.startsWith("MODULATORS/") ? "modulator" : "device");
                    break;
                }
            }
            QQZ timeline = lane.tWW(); // the lane's timeline
            if (timeline == null) {
                out.put(item.put("status", "null_timeline").put("unit", JSONObject.NULL)
                        .put("points", new JSONArray()).put("point_count", 0));
                continue;
            }
            Object type = timeline.Xit().LYs(); // the lane's value type
            boolean midi = (target instanceof bitwig.obf.E3p);
            boolean plugin = (target instanceof com.bitwig.flt.document.core.master.device.ogX);
            String unit = target == pan || midi || plugin ? "normalized" : automationUnit(type);
            if (midi) {
                bitwig.obf.ViP expression = (bitwig.obf.ViP) target; // a MIDI expression parameter
                String kind = ((Enum<?>) (Object) expression.XdI()).name();
                item.put("expression", kind.equals("CHANNEL_CONTROLLER") ? "channel_controller" :
                        kind.equals("CHANNEL_PRESSURE") ? "channel_pressure" : "pitch_bend")
                        .put("channel", expression.Anf());
                if (kind.equals("CHANNEL_CONTROLLER")) item.put("controller", expression.Q1());
            }
            if (plugin) item.put("parameter_id", jsonModelValue(((com.bitwig.flt.document.core.master.device.ogX) target).unz()));
            JSONArray points = readModelPoints(timeline, type, unit);
            out.put(item.put("unit", jsonModelValue(unit)).put("time_unit", "beats")
                    .put("points", points).put("point_count", points.length()).put("status", "read"));
            java.util.List<?> clips = timeline.beI(); // the lane's automation clips
            if (clips != null && !clips.isEmpty()) {
                // The composite walk interleaves clip-relative points with the lane's own.
                JSONArray read = new JSONArray();
                for (Object clip : clips) read.put(readAutomationClip(clip, type, unit));
                item.put("base_points", readModelPoints(((QQZ) timeline).Fhl(), type, unit)).put("clips", read);
            }
        }
        return out;
    }

    private static Object jsonModelValue(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static String automationIdentifier(Object owner, Object target) {
        if (!(target instanceof com.bitwig.flt.document.core.iface.YxJ component)) return null;
        try {
            if (owner instanceof LRP paths) return paths.beI(component); // the component's identifier path
            if (owner instanceof com.bitwig.flt.document.core.master.device.QkE paths) return paths.beI(component);
        } catch (IllegalArgumentException absent) { return null; }
        return null;
    }

    private static String automationUnit(Object type) {
        if (!(type instanceof aG range)) return null;
        String domain = range.BCC() == null ? "" : ((Enum<?>) (Object) range.BCC()).name(); // the value mapping
        if (domain.equals("DECIBEL")) return "decibel";
        if (domain.equals("PITCH") || domain.equals("PITCH_RELATIVE")) return "semitones";
        String unit = range.kwH() == null ? "" : ((Enum<?>) (Object) range.kwH()).name(); // the display unit
        if (unit.equals("BEAT_TIME")) return "beats";
        if (unit.equals("BPM")) return "bpm";
        if (unit.equals("SECONDS")) return "seconds";
        if (unit.equals("HERTZ")) return "hertz";
        return "linear";
    }

    // For raw note-expression or clip-automation timelines (lGQ subclasses).
    // Expression-specific unit overrides can use readModelPoints directly.
    private static JSONArray readExpressionPoints(Object timeline) {
        if (timeline == null) return new JSONArray();
        Object type = timelineType(timeline); // the expression's value type
        return readModelPoints(timeline, type, automationUnit(type));
    }

    private static JSONArray readModelPoints(Object timeline, Object type, String unit) {
        JSONArray points = new JSONArray();
        if (timeline == null) return points;
        if (!(timeline instanceof QQZ lane)) {
            for (Object point : timelinePoints(timeline)) // the timeline's stored points
                points.put(readModelPoint(point, type, unit));
            return points;
        }
        // Walk the lane with its clips the way playback does, collecting each point played.
        lane.beI(lane.Xit(), 0.0, bitwig.obf.er.Gef, bitwig.obf.tB3.beI(), true, true, new AutomationCollector(points, type, unit));
        return points;
    }

    // Point timelines come in two unrelated hierarchies (lanes and clip content; note
    // expressions), and each note expression kind declares its own type and kind getters.

    /** A point timeline's stored points. */
    private static java.util.List<?> timelinePoints(Object timeline) {
        if (timeline instanceof bitwig.obf.uJi lane) return lane.beI();
        if (timeline instanceof bitwig.obf.JrA expression) return expression.beI();
        throw new IllegalStateException("not a point timeline: " + timeline.getClass().getName());
    }

    /** A point timeline's value type (the parameter range its points are stored in). */
    private static Object timelineType(Object timeline) {
        if (timeline instanceof bitwig.obf.tvm t) return t.scr();
        if (timeline instanceof bitwig.obf.sQF t) return t.scr();
        if (timeline instanceof bitwig.obf.JiN t) return t.scr();
        if (timeline instanceof bitwig.obf.brA t) return t.scr();
        if (timeline instanceof bitwig.obf.PsI t) return t.scr();
        if (timeline instanceof bitwig.obf.CgT t) return t.scr();
        if (timeline instanceof bitwig.obf.yFk t) return t.scr();
        throw new IllegalStateException("no value type on " + timeline.getClass().getName());
    }

    /** A note expression timeline's kind (gain, pan, transpose, ...). */
    private static Enum<?> expressionKind(Object timeline) {
        Object kind = null;
        if (timeline instanceof bitwig.obf.PsI t) kind = t.xDy();
        else if (timeline instanceof bitwig.obf.uX1 t) kind = t.xDy();
        else if (timeline instanceof bitwig.obf.sQF t) kind = t.xDy();
        else if (timeline instanceof bitwig.obf.CgT t) kind = t.xDy();
        else if (timeline instanceof bitwig.obf.yFk t) kind = t.xDy();
        else if (timeline instanceof bitwig.obf.brA t) kind = t.xDy();
        else if (timeline instanceof bitwig.obf.JiN t) kind = t.xDy();
        if (kind == null) throw new IllegalStateException("no expression kind on " + timeline.getClass().getName());
        return (Enum<?>) kind;
    }

    /** Collects the points a composite automation walk plays back; every other callback is a no-op. */
    private static final class AutomationCollector implements bitwig.obf.nyK {
        private final JSONArray points;
        private final Object type;
        private final String unit;
        AutomationCollector(JSONArray points, Object type, String unit) { this.points = points; this.type = type; this.unit = unit; }
        @Override public void automationWillBePlayedBack(bitwig.obf.LCN point, bitwig.obf.LCN next, double time) { points.put(readModelPoint(point, type, unit)); }
        @Override public void automationAfterEndPointWillBePlayedBack(bitwig.obf.LCN point, double a, double b, double c) { points.put(readModelPoint(point, type, unit)); }
        @Override public void automationBeforeStartPointWillBePlayedBack(bitwig.obf.LCN point, double a, double b, double c) { }
        @Override public void automationSegmentWillBePlayedBack(bitwig.obf.LCN from, bitwig.obf.LCN to, double a, double b, double c) { }
        @Override public boolean nd_() { return true; }   // include points (the walk asks before each)
        @Override public boolean hK_() { return false; }  // do not stop early
        @Override public void beI(double a, double b, double c, double d, bitwig.obf.eB e) { }
        @Override public void beI(bitwig.obf.eB e, double a, double b, double c, int i) { }
        @Override public void Gef(bitwig.obf.eB e, double a, double b, double c, int i) { }
        @Override public void beI(bitwig.obf.de d, int i, double a, double b, double c, boolean f) { }
        @Override public void beI(bitwig.obf.de d, double a, double b) { }
        @Override public void beI(bitwig.obf.eA e, double a, double b, double c, boolean f) { }
        @Override public void beI(bitwig.obf.eA e, double a, double b) { }
        @Override public String toString() { return "Openwig automation reader"; }
    }

    // ---- Automation clips (UFD, an xh3 clip on a jmN lane): their own point
    // lane (MnS) in clip content time, loop region and play start like note clips.

    private static JSONObject readAutomationClip(Object item, Object type, String unit) {
        bitwig.obf.eOj clip = (bitwig.obf.eOj) item; // an automation clip
        bitwig.obf.iC2 region = clip.vce(); // its loop region
        return new JSONObject().put("start", clip.ie()).put("end", clip.oiY())
                .put("loop", clip.Xit())
                .put("loop_start", region.ie()).put("loop_duration", region.ckt())
                .put("play_start_offset", clip.LYs().np2().ie()) // play start marker
                .put("points", readModelPoints(clip.mEL(), type, unit));
    }

    private static bitwig.obf.gZu automationPoint(String kind, JSONArray point) {
        double time = point.getDouble(0);
        return switch (kind) {
            case "real" -> {
                g13 made = g13.beI(time, point.getDouble(1)); // a real point
                boolean hold = point.optString(3, "linear").equals("hold");
                made.beI(hold ? r1.beI : r1.Gef);
                made.Gef(hold ? 0 : point.optDouble(2, 0));
                yield made;
            }
            case "boolean" -> bitwig.obf.AvX.beI(time, point.getBoolean(1));
            case "enum" -> bitwig.obf.yKY.beI(time, point.getInt(1));
            case "integer" -> bitwig.obf.Bzm.beI(time, point.getInt(1));
            default -> throw new IllegalArgumentException("unknown automation kind: " + kind);
        };
    }

    // Bitwig's "Insert Automation Clip" (kXT): jmN.IYM(start, length) makes the
    // clip, which is then inserted into the lane. The lane's own points come
    // first (automation.write_target); they hold the value around the clips.
    private static JSONObject writeAutomationClips(ApiProject project, JSONObject params) {
        Track track = resolveTrack(project, params);
        Object owner = params.has("device") ? deviceTargetAt(track, params.getInt("device")) : ((ApiObject) track).getTarget();
        String target = params.getString("target");
        Object atom = findTargetAtom(owner, target);
        if (atom == null) throw new IllegalArgumentException("automation target not found: " + target);
        eIn laneOwner = ((Ae2) atom).Or();
        if (laneOwner == null || laneOwner.tWW() == null) throw new IllegalArgumentException("write the lane's points before its clips");
        QQZ lane = laneOwner.tWW();
        String kind = params.getString("kind");
        JSONArray clips = params.getJSONArray("clips");
        double previousEnd = -1;
        for (int i = 0; i < clips.length(); i++) {
            JSONObject clip = clips.getJSONObject(i);
            double start = clip.getDouble("start"), end = clip.getDouble("end");
            if (!(start >= 0) || !(end > start) || start < previousEnd - 1e-9)
                throw new IllegalArgumentException("automation clips must be sorted, positive and not overlap");
            previousEnd = end;
            double last = -Double.MAX_VALUE;
            for (Object item : clip.getJSONArray("points")) {
                double time = ((JSONArray) item).getDouble(0);
                if (!Double.isFinite(time) || time < last) throw new IllegalArgumentException("clip points must be sorted");
                last = time;
            }
        }
        DirectAccess.runInUndo(track.clipTimeline(), () -> {
            for (int i = 0; i < clips.length(); i++) {
                JSONObject spec = clips.getJSONObject(i);
                double start = spec.getDouble("start");
                bitwig.obf.eOj clip = lane.beI(start, spec.getDouble("end") - start); // make a clip on the lane
                BrN content = clip.mEL(); // its point list
                for (Object old : new java.util.ArrayList<>(content.beI())) content.Gef((bitwig.obf.gZu) old); // remove
                JSONArray points = spec.getJSONArray("points");
                for (int j = 0; j < points.length(); j++) content.beI(automationPoint(kind, points.getJSONArray(j)), j); // insert at
                clip.unz(spec.optBoolean("loop", false)); // looped
                bitwig.obf.iC2 region = clip.vce(); // its loop region
                // ApiClip rounds loop times through these helpers (the 6.1.5 bridge named a wrong class and skipped them).
                if (spec.has("loop_start")) region.scr(bitwig.obf.aoP.BBg(spec.getDouble("loop_start")));
                if (spec.has("loop_duration")) region.Bqk(bitwig.obf.aoP.unz(spec.getDouble("loop_duration")));
                if (spec.has("play_start_offset")) {
                    double offset = spec.getDouble("play_start_offset"), length = spec.getDouble("end") - start;
                    // A play start landing exactly on the end marker is clamped a tick early (the
                    // made clip can carry another clip's markers): then approach it from beyond.
                    clip.LRq(offset); // set the play start
                    double stored = clip.LYs().np2().ie();
                    if (Math.abs(stored - offset) > 1e-9) {
                        clip.LRq(offset + length);
                        clip.LRq(offset);
                    }
                }
                lane.beI((bitwig.obf.BVS) clip); // insert the clip into the lane
            }
        });
        JSONArray read = new JSONArray();
        Object type = lane.Xit().LYs(); // the lane's value type
        for (Object clip : lane.beI()) read.put(readAutomationClip(clip, type, automationUnit(type)));
        return new JSONObject().put("target", target).put("clips", read);
    }

    private static JSONObject readModelPoint(Object point, Object type, String unit) {
        if (point == null) return new JSONObject().put("status", "null_point")
                .put("time", JSONObject.NULL).put("value", JSONObject.NULL);
        JSONObject out = new JSONObject().put("time", ((bitwig.obf.gZu) point).ie()) // the point's time
                .put("point_class", point.getClass().getName());
        if (point instanceof g13 real) {
            double raw = real.beI();
            return out.put("kind", "real").put("raw_value", raw)
                    .put("value", convertAutomationReal(raw, type, unit))
                    .put("interpolation", "linear") // This is the exporter's interpolation.
                    // The 6.1.5 bridge compared the mode's name to an obfuscated field name, so it always said linear.
                    .put("model_interpolation", real.MgQ() == r1.beI ? "hold" : "linear")
                    .put("curvature", real.dT_());
        }
        if (point instanceof bitwig.obf.AvX bool)
            return out.put("kind", "boolean").put("value", bool.scr());
        if (point instanceof bitwig.obf.yKY indexed) {
            int raw = indexed.XnJ();
            Object value = ((bitwig.obf.aS) type).Gef(raw); // the enum type's value for this index
            return out.put("kind", "enum").put("raw_value", raw).put("value", value);
        }
        if (point instanceof bitwig.obf.Bzm integer)
            return out.put("kind", "integer").put("value", integer.XnJ());
        if (point instanceof bitwig.obf.nEr signature)
            return out.put("kind", "time_signature").put("value", JSONObject.NULL)
                    .put("numerator", signature.dU_()).put("denominator", signature.byf());
        return out.put("status", "unsupported_point_type").put("value", JSONObject.NULL);
    }

    private static double convertAutomationReal(double raw, Object type, String unit) {
        if (!(type instanceof bitwig.obf.aG))
            throw new IllegalStateException("real automation requires aB value type");
        aG range = (aG) type;
        if ("normalized".equals(unit)) return range.beI(raw); // normalize
        String destination = switch (unit == null ? "linear" : unit) {
            case "percent" -> "PERCENT";
            case "decibel" -> "DECIBEL";
            case "hertz" -> "HERTZ";
            case "semitones" -> "SEMITONES";
            case "seconds" -> "SECONDS";
            case "beats" -> "BEAT_TIME";
            case "bpm" -> "BPM";
            default -> "NONE";
        };
        // Convert from the stored domain to a display unit, as the DAWproject exporter does.
        return bitwig.obf.gf.beI(raw, range.BCC(), (bitwig.obf.ge) namedNativeEnum(bitwig.obf.ge.class, destination));
    }



    // Read-only PNT/Tc getters from exact original 6.1.5. Both vuj MIDI notes
    // and KoO audio events inherit these fields. No expression creation calls.
    private static JSONObject readNoteExtras(Object model) {
        if (model == null) return new JSONObject().put("status", "null_note_model");
        bitwig.obf.D note = (bitwig.obf.D) model; // a note or audio event
        int recurrence = note.fmw();
        int period = 1 + (recurrence & 7);
        JSONArray iterations = new JSONArray();
        for (int i = 0; i < period; i++) iterations.put((recurrence & (1 << (3 + i))) != 0);
        int repeat = note.neH();
        JSONObject out = new JSONObject()
                .put("chance_enabled", note.af())
                .put("chance", note.kwH())
                .put("occurrence_enabled", note.Hqm())
                .put("occurrence", ((Enum<?>) (Object) note.nhI()).name())
                .put("recurrence_enabled", note.XUO())
                .put("recurrence", recurrence)
                .put("recurrence_period", period)
                .put("recurrence_iterations", iterations)
                .put("repeat_enabled", note.IvP())
                .put("repeat_count", repeat)
                .put("repeat_subdivisions", Math.abs(repeat) + 1)
                .put("repeat_curve", note.iFu())
                .put("content_start", note.SQL());
        JSONArray expressions = new JSONArray();
        bitwig.obf.Fu3 content = note.EVg(); // the note's expression content
        if (content != null) {
            for (Object timeline : content.QDQ()) { // its expression timelines
                String name = expressionKind(timeline).name();
                String unit = name.equals("TRANSPOSE") || name.equals("FORMANT") ? "semitones" : "linear";
                Object type = timelineType(timeline);
                // This uses the same fK.IYM(raw, fG source, fH destination)
                // conversion as ApiDawProjectExporter. In particular, gain is
                // converted out of its CUBE_ROOT storage domain to linear.
                JSONArray points = readModelPoints(timeline, type, unit);
                java.util.List<?> stored = timelinePoints(timeline);
                // Exporter flattens interpolation to linear. Retain the actual
                // model interpolation/curve in JSON for native read fidelity.
                for (int i = 0; i < stored.size(); i++) {
                    Object point = stored.get(i);
                    if (point instanceof g13 real) {
                        points.getJSONObject(i)
                                .put("interpolation", ((Enum<?>) (Object) real.MgQ()).name().toLowerCase(java.util.Locale.ROOT))
                                .put("curvature", real.dT_());
                    }
                }
                expressions.put(new JSONObject().put("expression", name.toLowerCase(java.util.Locale.ROOT))
                        .put("unit", unit).put("time_unit", "beats")
                        .put("points", points).put("point_count", points.length()));
            }
        }
        return out.put("expressions", expressions);
    }

    private static JSONArray storedWarpMarkers(Object model) {
        JSONArray markers = new JSONArray();
        // The API returns a sanitized copy and can drop stored markers. Read
        // the original GLs timeline, exactly as the DAWproject exporter does.
        bitwig.obf.MZX storedWarp = ((bitwig.obf.poA) model).Ml2(); // the stored warp timeline
        int count = storedWarp.Hqm(); // its marker count
        for (int i = 0; i < count; i++) {
            bitwig.obf.oCa marker = storedWarp.sCA(i); // the marker at an index
            markers.put(new JSONObject().put("time", marker.ie())
                    .put("content_time", marker.beI()));
        }
        return markers;
    }

    private static JSONObject readAudioEvent(com.bitwig.extension.api.project.timeline.AudioNote event) {
        bitwig.obf.poA model = (bitwig.obf.poA) ((ApiObject) event).getTarget(); // an audio event
        Object sample = model.GKF(); // its sample
        java.io.File file = sample == null ? null : event.getAudioFile();
        JSONObject out = new JSONObject().put("start", event.getTime())
                .put("duration", event.getDuration()).put("muted", event.isMuted())
                .put("file", file == null ? JSONObject.NULL : file.getAbsolutePath())
                .put("sample_file_status", file == null ? "unresolved" : "resolved")
                .put("sample_rate", sample == null ? JSONObject.NULL : event.getSampleRate()).put("channels", sample == null ? JSONObject.NULL : event.getChannelCount())
                .put("sample_duration_seconds", sample == null ? JSONObject.NULL : event.getAudioFileDurationInSeconds())
                .put("algorithm", model.jTs().Gef()) // the stretch algorithm's name
                .put("fade_in", model.dM_())
                .put("fade_out", model.unz())
                .put("fade_time_unit", "beats")
                .put("play_start_offset", model.qJX())
                .put("warp_mode", event.getWarpMode().name())
                .put("preserve_transients_mode", event.getPreserveTransientsMode().name())
                .put("transient_rate", event.getTransientRate().name())
                .put("warp_grain_size", event.getWarpGrainSize().name())
                .put("warp_tail_mode", event.getWarpTailMode().name())
                .put("preview_onsets", event.getPreviewOnsets())
                .put("preserve_formants", event.getPreserveFormants())
                .put("formant_envelope_resolution", event.getFormantEnvelopeResolution())
                .put("reverse", model.p1J().Anf()) // the reverse setting
                .put("sample_play_start_seconds", model.odI())
                .put("sample_play_stop_seconds", model.dvk());
        com.bitwig.extension.api.project.timeline.WarpMarkers warp = event.getWarpEvents();
        JSONArray markers = storedWarpMarkers(model);
        if (!event.getWarpMode().name().equals("RAW")) {
            double mappedStop = model.Ml2().beI(model.SQL() + event.getDuration()); // map beats to source seconds
            out.put("sample_play_stop_seconds", Math.max(0, Math.min(event.getAudioFileDurationInSeconds(), mappedStop)));
        }
        out.put("warp_markers", event.getWarpMode().name().equals("RAW") ? new JSONArray() : markers)
                .put("api_warp_markers_valid", warp.areWarpMarkersValid());
        if (event.getWarpMode().name().equals("RAW")) out.put("inactive_warp_markers", markers);
        JSONObject extras = readNoteExtras(model);
        String contentTimeUnit = event.getWarpMode().name().equals("RAW") ? "seconds" : "beats";
        // Expression timelines use beats even when RAW source offsets use seconds.
        out.put("content_time_unit", contentTimeUnit);
        for (String key : extras.keySet()) out.put(key, extras.get(key));
        return out;
    }

    private static JSONObject readInstrumentNote(InstrumentNote note) {
        Object raw = ((ApiObject) note).getTarget();
        return readNoteExtras(raw).put("channel", note.getChannel()).put("key", note.getKey())
                .put("start", note.getTime()).put("duration", note.getDuration())
                .put("on_velocity", note.getOnVelocity()).put("off_velocity", note.getOffVelocity())
                .put("velocity_spread", ((bitwig.obf.c1N) raw).byf()).put("muted", note.isMuted());
    }

    private static JSONObject readClip(Clip clip) {
        Color color = clip.getColor();
        JSONObject out = new JSONObject().put("launch_settings", launchSettings(clip))
                .put("name", clip.getTitle())
                .put("start", clip.getTime())
                .put("duration", clip.getDuration())
                .put("muted", clip.isMuted())
                .put("loop", clip.isLoopEnabled())
                .put("loop_start", clip.getLoopStart())
                .put("loop_duration", clip.getLoopDuration())
                .put("play_start_offset", clip.getPlayStartOffset())
                .put("color", color == null ? JSONObject.NULL : color.toHex());
        if (clip instanceof NoteClip noteClip) {
            out.put("content", "note");
            JSONArray notes = new JSONArray();
            for (Object item : noteClip.getContent().getEventTimeline().getEvents()) {
                InstrumentNote note = (InstrumentNote) item;
                notes.put(readInstrumentNote(note));
            }
            out.put("notes", notes);
        } else if (clip instanceof AudioClip audioClip) {
            out.put("content", "audio")
                    .put("fade_in", audioClip.getFadeInTime())
                    .put("fade_out", audioClip.getFadeOutTime()).put("fade_time_unit", "beats");
            JSONArray events = new JSONArray();
            for (Object event : audioClip.getContent().getEventTimeline().getEvents())
                events.put(readAudioEvent((com.bitwig.extension.api.project.timeline.AudioNote) event));
            out.put("audio_events", events);
        } else {
            out.put("content", "other");
        }
        return out;
    }

    /** Export the open project as a .dawproject archive at the given path. */
    public static JSONObject exportDawProject(String path, boolean embedMedia) {
        ApiProject project = project();
        try {
            java.util.Map<java.io.File, String> media = new java.util.HashMap<>();
            com.bitwig.dawproject.Project exported = project.exportDawProject(media);
            MetaData metaData = new MetaData();
            metaData.title = project.getMetadataText(ProjectMetadata.TITLE);
            metaData.artist = project.getMetadataText(ProjectMetadata.ARTIST);
            metaData.album = project.getMetadataText(ProjectMetadata.ALBUM);
            metaData.originalArtist = project.getMetadataText(ProjectMetadata.ORIGINAL_ARTIST);
            metaData.songwriter = project.getMetadataText(ProjectMetadata.WRITER);
            metaData.producer = project.getMetadataText(ProjectMetadata.PRODUCER);
            metaData.year = project.getMetadataText(ProjectMetadata.YEAR);
            metaData.genre = project.getMetadataText(ProjectMetadata.GENRE);
            metaData.copyright = project.getMetadataText(ProjectMetadata.COPYRIGHT);
            metaData.comment = project.getMetadataText(ProjectMetadata.COMMENT);
            java.io.File target = new java.io.File(path);
            try {
                DawProject.save(exported, metaData,
                        embedMedia ? media : new java.util.HashMap<>(), target);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("dawproject export failed", failure);
            }
            return new JSONObject().put("path", target.getAbsolutePath())
                    .put("bytes", target.length())
                    .put("embedded_files", embedMedia ? media.size() : 0);
        } finally {
            project.dispose();
        }
    }

    /** List the arranger automation lanes that exist on one track. */
    public static JSONArray automationEnumerate(int trackIndex) {
        ApiProject project = project();
        try {
            return automationEnumerate(track(project, trackIndex));
        } finally { project.dispose(); }
    }

    public static JSONArray automationEnumerate(String path) {
        ApiProject project = project();
        try {
            JSONArray lanes = readTrackAutomation(view(resolve(project, path)), path);
            for (int i = 0; i < lanes.length(); i++) {
                JSONObject lane = lanes.getJSONObject(i);
                String name = lane.optString("target_path", lane.optString("target_name", ""));
                if (name.startsWith("CONTENTS/")) name = name.substring("CONTENTS/".length());
                lane.put("param", name).put("points", lane.getJSONArray("points").length());
            }
            return lanes;
        }
        finally { project.dispose(); }
    }

    private static JSONArray automationEnumerate(Track view) {
        JSONArray lanes = new JSONArray();
        addLane(lanes, "volume", view.mixer().getVolume(), -1);
        addLane(lanes, "pan", view.mixer().getPan(), -1);
        for (int i = 0; i < view.mixer().getSendCount(); i++) {
            addLane(lanes, "send" + i, view.mixer().getSend(i).getAmount(), -1);
        }
        java.util.List<?> devices = view.deviceChain().getDevices();
        for (int d = 0; d < devices.size(); d++) {
            if (!(devices.get(d) instanceof NativeDevice nativeDevice)) {
                continue;
            }
            Object target = ((ApiObject) devices.get(d)).getTarget();
            Class<?> decimal = bitwig.obf.xdh.class;
            if (decimal == null || target == null) {
                continue;
            }
            java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            java.util.List<Object> found = new java.util.ArrayList<>();
            if (!(target instanceof utt)) continue;
            Object contents = deviceContents(target);
            walkAtoms(contents, 0, seen, found, decimal, null, null);
            if (!hasIdentifierPaths(target)) {
                continue;
            }
            for (Object atom : found) {
                String name = automationIdentifier(target, atom);
                if (name == null || !name.startsWith("CONTENTS/")) {
                    continue;
                }
                DoubleParameter parameter = nativeDevice.getDoubleParameter(
                        name.substring("CONTENTS/".length()));
                if (parameter != null) {
                    addLane(lanes, name.substring("CONTENTS/".length()), parameter, d);
                }
            }
        }
        return lanes;
    }

    private static void addLane(JSONArray lanes, String name, DoubleParameter parameter,
                                int deviceIndex) {
        try {
            BrN timeline = automationTimeline(parameter);
            if (timeline == null) {
                return;
            }
            int points = timeline.beI().size();
            if (points <= 0) {
                return;
            }
            JSONObject lane = new JSONObject().put("param", name).put("points", points);
            if (deviceIndex >= 0) {
                lane.put("device", deviceIndex);
            }
            lanes.put(lane);
        } catch (RuntimeException unreadable) {
            // This parameter exposes no readable lane on the exact build.
        }
    }

    private static MonitorMode monitorMode(String name) {
        switch (name == null ? "" : name) {
            case "OFF": return MonitorMode.OFF;
            case "ON": return MonitorMode.ON;
            case "AUTO": return MonitorMode.AUTO;
            default: throw new IllegalArgumentException("monitor mode must be OFF, ON, or AUTO");
        }
    }

    private static void modifyModel(ApiObject owner, Runnable action) {
        try {
            java.lang.reflect.Method modify = ApiObject.class.getDeclaredMethod("modify", Runnable.class);
            modify.setAccessible(true);
            modify.invoke(owner, action);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("model modification wrapper failed", failure);
        }
    }

    private static void invokeModelSetter(Object target, String method, Class<?> paramType, Object arg) {
        try {
            java.lang.reflect.Method setter = target.getClass().getMethod(method, paramType);
            setter.setAccessible(true);
            setter.invoke(target, arg);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("model setter " + target.getClass().getName() + "." + method, failure);
        }
    }

    private static Color parseColor(JSONObject params) {
        if (params == null) return null;
        if (params.has("r") && params.has("g") && params.has("b")) {
            double r = params.getDouble("r");
            double g = params.getDouble("g");
            double b = params.getDouble("b");
            double a = params.optDouble("a", 1.0);
            if (r < 0 || r > 1 || g < 0 || g > 1 || b < 0 || b > 1 || a < 0 || a > 1) {
                throw new IllegalArgumentException("color components must be within 0..1");
            }
            return a >= 1.0 ? Color.fromRGB(r, g, b) : Color.fromRGBA(r, g, b, a);
        }
        String hex = params.optString("color", "");
        if (hex != null && !hex.isBlank()) {
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            if (clean.length() != 6) {
                throw new IllegalArgumentException("hex color must be #RRGGBB");
            }
            int rgb = Integer.parseInt(clean, 16);
            return Color.fromRGB(((rgb >> 16) & 0xff) / 255.0,
                    ((rgb >> 8) & 0xff) / 255.0, (rgb & 0xff) / 255.0);
        }
        return null;
    }

    private static void applyTrackField(ApiProject project, TrackOrTrackGroup item,
                                        String field, JSONObject params) {
        Track track = view(item);
        Object rawTrack = ((ApiObject) track).getTarget();
        switch (field) {
            case "name" -> {
                String name = params == null ? "" : params.optString("name", "");
                if (name.isBlank()) throw new IllegalArgumentException("track name is required");
                item.setTitle(name);
            }
            case "volume" -> {
                double value = params == null ? Double.NaN : params.optDouble("value", Double.NaN);
                // 1 is 0 dB; a fader at its top reads 2 (a project stores 1.999999999999999).
                if (!Double.isFinite(value) || value < 0 || value > 2) {
                    throw new IllegalArgumentException("value must be between 0 and 2 (the fader's top, about +6 dB)");
                }
                track.mixer().getVolume().setValue(value * 100.0, Unit.PERCENTAGE_OF_RANGE);
            }
            case "pan" -> {
                double value = params == null ? Double.NaN : params.optDouble("value", Double.NaN);
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("value must be between 0 and 1");
                }
                track.mixer().getPan()
                        .setValue((value * 2.0 - 1.0) * 100.0, Unit.PERCENTAGE_OF_RANGE);
            }
            case "mute" -> {
                if (params == null || !params.has("on")) {
                    throw new IllegalArgumentException("mute state is required");
                }
                track.mixer().getMute().setValue(params.getBoolean("on"));
            }
            case "solo" -> {
                if (params == null || !params.has("on")) {
                    throw new IllegalArgumentException("solo state is required");
                }
                boolean on = params.getBoolean("on");
                bitwig.obf.nCQ channel = ((bitwig.obf.PiT) rawTrack).pEy(); // the track's channel
                modifyModel((ApiObject) track, () -> channel.Gef(on)); // solo
            }
            case "comment" -> {
                String comment = params == null ? "" : params.optString("comment", "");
                modifyModel((ApiObject) track, () -> ((bitwig.obf.PiT) rawTrack).Heb(comment)); // the track comment
            }
            case "active" -> {
                if (params == null || !params.has("on")) {
                    throw new IllegalArgumentException("active state is required");
                }
                item.setIsActive(params.getBoolean("on"));
            }
            case "destination" -> {
                String destPath = params == null ? "" : params.optString("destination", "");
                if (destPath.isBlank()) {
                    throw new IllegalArgumentException("destination track path is required");
                }
                Track destTrack = view(resolve(project, destPath));
                track.destinationSelector().setDestination(destTrack.audioDestination());
            }
            case "monitor" -> {
                String mode = params == null ? "" : params.optString("mode", "");
                track.setMonitorMode(monitorMode(mode));
            }
            case "color" -> {
                Color color = parseColor(params);
                if (color == null) throw new IllegalArgumentException("r, g, and b are required");
                item.setColor(color);
            }
            case "send" -> {
                if (params == null || !params.has("send") || !params.has("value")) {
                    throw new IllegalArgumentException("send index and value are required");
                }
                int sendIndex = params.getInt("send");
                double value = params.getDouble("value");
                Mixer mixer = track.mixer();
                if (sendIndex < 0 || sendIndex >= mixer.getSendCount()) {
                    throw new IllegalArgumentException("send index out of range: " + sendIndex);
                }
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("send value must be within 0..1");
                }
                mixer.getSend(sendIndex).getAmount()
                        .setValue(value * 100.0, Unit.PERCENTAGE_OF_RANGE);
            }
            default -> throw new IllegalArgumentException("unknown track field: " + field);
        }
    }

    public static JSONObject updateTrack(int trackIndex, String field, JSONObject params) {
        ApiProject project = project();
        try {
            TrackOrTrackGroup item = trackOrGroup(project, trackIndex);
            applyTrackField(project, item, field, params);
            return trackSnapshot(view(item), trackIndex);
        } finally {
            project.dispose();
        }
    }

    public static JSONObject execute(String method, JSONObject params) {
        JSONObject p = params == null ? new JSONObject() : params;
        return switch (method) {
            case "document.info" -> documentInfo();
            case "document.list" -> listDocuments();
            case "document.select" -> selectDocument(p.optString("path", ""));
            case "document.open" -> openDocument(p);
            case "document.close" -> closeDocument(p);
            case "document.job_status" -> {
                JSONObject status = documentJobs.get(p.optString("job", ""));
                if (status == null) throw new IllegalArgumentException("unknown document job");
                yield jobSnapshot(status);
            }
            case "project.save_copy" -> saveNativeProjectCopy(p.optString("path", ""));
            case "project.duplicate_range" -> editRange(p, "duplicate");
            case "launcher.settings", "launcher.launch", "launcher.stop", "launcher.return_to_arranger", "launcher.put_notes", "launcher.import_audio", "launcher.copy_arranger", "launcher.clear", "launcher.read" -> editLauncher(method, p);
            case "audio_event.import", "audio_event.update", "audio_event.delete", "note.import", "note.update", "note.delete", "clip.replace_notes", "clip.copy", "clip.split", "clip.update", "clip.delete", "clip.import_audio" -> editClip(method, p);
            case "project.insert_time" -> editRange(p, "insert");
            case "project.delete_range" -> editRange(p, "delete");
            case "project.import_dawproject" -> importDawProject(p.optString("path", ""));
            case "project.set_metadata" -> setProjectMetadata(p);
            case "cue_marker.create" -> createCueMarker(p);
            case "scene.snapshot", "scene.insert", "scene.delete", "scene.move" -> editSceneBank(method, p);
            case "scene.update" -> updateScene(p);
            case "modulator.catalog" -> new JSONObject().put("modulators", modulatorCatalog());
            case "track.create_group" -> createTrackOrGroup(new JSONObject(p.toString()).put("type", "group"));
            case "track.create" -> createTrackOrGroup(p);
            case "track.rename" -> updateTrackTarget("name", p);
            case "track.set_volume" -> updateTrackTarget("volume", p);
            case "track.set_pan" -> updateTrackTarget("pan", p);
            case "track.set_mute" -> updateTrackTarget("mute", p);
            case "track.set_solo" -> updateTrackTarget("solo", p);
            case "track.set_comment" -> updateTrackTarget("comment", p);
            case "track.set_active" -> updateTrackTarget("active", p);
            case "track.set_destination" -> updateTrackTarget("destination", p);
            case "track.set_monitor" -> updateTrackTarget("monitor", p);
            case "track.set_color" -> updateTrackTarget("color", p);
            case "track.set_send" -> updateTrackTarget("send", p);
            case "track.set_source", "device.set_sidechain", "device.create_band", "device.drum_pad",
                 "device.set_active", "device.components", "transport.set_groove",
                 "transport.set_time_signature", "automation.write_target", "device.load_sample", "device.structure",
                 "project.render", "project.render_status", "debug.describe_modulator", "debug.atom_methods", "debug.atom_invoke",
                 "device.load_impulse", "device.component_rename", "automation.write_clips",
                 "device.model_fields", "device.set_model_fields" -> executeRebuild(method, p);
            case "device.modulator_add", "device.modulator_delete", "device.modulator_move", "device.modulator_parameters", "device.modulator_bind", "device.modulator_unbind", "device.modulator_rename", "device.layer_read", "device.layer_update", "device.layer_delete", "device.layer_move", "device.grid_add", "device.grid_move", "device.grid_delete", "device.grid_connect", "device.grid_read", "device.read", "device.chain_banks", "device.create_layer", "device.count", "device.snapshot", "device.set_enabled", "device.insert_factory", "device.insert_plugin", "device.plugin_parameters", "device.export_plugin_state", "device.plugin_state_status", "device.operation_status", "device.capture_plugin_state",
                 "device.set_parameters", "device.get_parameters", "device.rename",
                 "device.set_expanded", "device.delete", "device.list_parameters" ->
                    executeDevice(method, p);
            case "clip.count", "clip.note_count", "clip.create_arranger_with_notes" ->
                    executeClip(method, p);
            default -> throw new IllegalArgumentException("unsupported document method: " + method);
        };
    }

    // ---- Reconstruction writes: routing, bands, pads, activation, groove and
    // target-addressed automation. All run on the document event queue.

    private static Object deviceTargetAt(Track track, int index) {
        java.util.List<?> devices = track.deviceChain().getDevices();
        if (index < 0 || index >= devices.size()) throw new IllegalArgumentException("device index out of range: " + index);
        return ((ApiObject) devices.get(index)).getTarget();
    }

    // Taps match the reader's routing index; no tap means the track itself.
    private static com.bitwig.extension.api.project.routing.Source trackSource(ApiProject project, JSONObject spec) {
        Track track = view(resolve(project, spec.getString("path")));
        String tap = spec.isNull("tap") ? "" : spec.optString("tap", "");
        Object source = switch (tap) {
            case "pre_fader" -> track.preFader();
            case "post_fader" -> track.postFader();
            case "post_fx_notes" -> track.postFxNotes();
            case "pre_fx" -> track.deviceChain().preFx();
            case "" -> {
                try {
                    java.lang.reflect.Constructor<?> wrapper = com.bitwig.flt.document.api.document.ApiSource.class.getConstructors()[0];
                    yield wrapper.newInstance(((ApiObject) track).getTarget());
                } catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("track is not a source", failure); }
            }
            default -> throw new IllegalArgumentException("unknown source tap: " + tap);
        };
        if (!(source instanceof com.bitwig.extension.api.project.routing.Source result))
            throw new IllegalArgumentException("track has no source for tap: " + tap);
        return result;
    }

    private static BrN automationTimelineOf(Object atom) {
        eIn lane = ((Ae2) atom).Or();
        return lane == null ? null : lane.tWW().Fhl();
    }

    // Inverts the reader's target paths: walk the owner's stored components,
    // device enable atoms, modulators and nested chains until an identifier
    // matches. Getters are only called on the classes that define them
    // (obfuscated names collide across classes).
    private static Object findTargetAtom(Object owner, String target) {
        // The owner's own preset-identifier resolver handles most paths directly.
        try {
            Object direct = owner instanceof LRP paths ? paths.beI(target, Ae2.class) : null; // resolve the path
            if (direct != null && target.equals(automationIdentifier(owner, direct))) return direct;
        } catch (RuntimeException unresolved) {
            // Fall back to walking the stored model.
        }
        // A device's optional fields can be null (a plugin without its engine), and ArrayDeque refuses nulls.
        java.util.LinkedList<Object> queue = new java.util.LinkedList<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        queue.add(owner);
        while (!queue.isEmpty() && seen.size() < 300000) {
            Object node = queue.poll();
            if (node == null || seen.put(node, true) != null) continue;
            if (node != owner && target.equals(automationIdentifier(owner, node))) return node;
            if (node instanceof sgH device) {
                queue.add(deviceContents(node)); // a native device's contents
                queue.add(device.GAx());          // the device's own atoms (On switch and others)
                itZ bank = device.LdK();          // its modulators
                if (bank != null && bank.dvk() instanceof java.util.List<?> list) for (Object item : list) queue.add(item);
                continue;
            }
            if (node instanceof QFr modulator) {
                queue.add(modulator.MvA()); // the modulator's contents
                continue;
            }
            if (node instanceof bitwig.obf.PiT track) {
                Object stored = track.YQe(); // the track's device chain
                if (stored != null) for (Object device : wrapDeviceChain(stored).getDevices()) queue.add(((ApiObject) device).getTarget());
            }
            if (node instanceof SRR chain) {
                Object stored = chain.YQe(); // the nested chain's device chain
                if (stored != null) for (Object device : wrapDeviceChain(stored).getDevices()) queue.add(((ApiObject) device).getTarget());
            }
            if (node instanceof YVC bank) for (Object item : bank.LYs()) queue.add(item); // its chains
            if (node instanceof com.bitwig.flt.document.core.master.device.Hvv selector) queue.add(selector.rcd()); // the selected chain holder
            if (node instanceof beU slot) queue.add(slot.VNw()); // the slot's chain
            if (node instanceof bitwig.obf.aIt pads) for (Object pad : pads.beI()) queue.add(((WGX) pad).JTf()); // each pad's chain
            Object children = componentChildren(node);
            if (children instanceof java.util.List<?> list) for (Object item : list) if (item != null) queue.add(item);
        }
        return null;
    }

    private static JSONObject writeTargetAutomation(ApiProject project, JSONObject params) {
        Track track = resolveTrack(project, params);
        Object owner = params.has("device") ? deviceTargetAt(track, params.getInt("device")) : ((ApiObject) track).getTarget();
        String target = params.getString("target");
        Object atom = findTargetAtom(owner, target);
        if (atom == null) throw new IllegalArgumentException("automation target not found: " + target);
        Object wrapper = parameterWrapper(atom);
        String kind = params.getString("kind");
        JSONArray points = params.getJSONArray("points");
        if (points.length() < 2 || points.length() > 8192) throw new IllegalArgumentException("automation needs 2..8192 points");
        com.bitwig.extension.api.project.parameter.AutomationEvents events = switch (kind) {
            case "real" -> project.getFactory().createDoubleAutomationEvents(Unit.PERCENTAGE_OF_RANGE);
            case "boolean" -> project.getFactory().createBooleanAutomationEvents();
            case "enum", "integer" -> project.getFactory().createIntegerAutomationEvents();
            default -> throw new IllegalArgumentException("unknown automation kind: " + kind);
        };
        double previous = -1;
        for (int i = 0; i < points.length(); i++) {
            JSONArray point = points.getJSONArray(i);
            double time = point.getDouble(0);
            if (!Double.isFinite(time) || time < 0 || time < previous) throw new IllegalArgumentException("automation times must be sorted and nonnegative");
            // Real values are rewritten natively below; the API pass only lays out the points.
            events.addPoint(time, switch (kind) {
                case "real" -> (Object) 0.0;
                case "boolean" -> (Object) point.getBoolean(1);
                default -> (Object) point.getInt(1);
            });
            previous = time;
        }
        DirectAccess.runInUndo(track.clipTimeline(), () -> {
            BrN existing = automationTimelineOf(atom);
            if (existing != null) existing.qVz();
            ((com.bitwig.extension.api.project.parameter.Parameter) wrapper).setAutomation(events);
        });
        BrN created = automationTimelineOf(atom);
        if (created != null && created.beI().size() == points.length() + 1) {
            DirectAccess.runInUndo(track.clipTimeline(), () -> {
                created.qVz();
                ((com.bitwig.extension.api.project.parameter.Parameter) wrapper).setAutomation(events);
            });
        }
        if (kind.equals("real")) {
            DirectAccess.runInUndo(track.clipTimeline(), () -> {
                BrN timeline = automationTimelineOf(atom);
                if (timeline == null) throw new IllegalStateException("automation lane missing after write");
                java.util.List<?> stored = timeline.beI();
                int offset = stored.size() == points.length() + 1 && points.getJSONArray(0).getDouble(0) > 0
                        && ((g13) stored.get(0)).ie() == 0 ? 1 : 0;
                if (stored.size() != points.length() + offset)
                    throw new IllegalStateException("automation point count mismatch: " + stored.size() + " for " + points.length());
                for (int i = 0; i < points.length(); i++) {
                    JSONArray input = points.getJSONArray(i);
                    g13 point = (g13) stored.get(i + offset);
                    point.beI(input.getDouble(1));
                    boolean hold = input.optString(3, "linear").equals("hold");
                    point.beI(hold ? r1.beI : r1.Gef);
                    point.Gef(hold ? 0 : input.optDouble(2, 0));
                }
            });
        }
        BrN timeline = automationTimelineOf(atom);
        return new JSONObject().put("count", timeline == null ? 0 : timeline.beI().size()).put("target", target);
    }

    // ---- Sampler content: the lDv resource component holds a single sample
    // (Fyn), a multisample (da with TTF zones), a sliced sample (iyv) or nothing.

    // Values of public no-argument getters returning simple types (debugging aid).
    private static JSONObject describeGetters(Object target) {
        JSONObject out = new JSONObject();
        if (target == null) return out.put("null", true);
        out.put("class", target.getClass().getName());
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
            Class<?> type = m.getReturnType();
            if (!(type.isPrimitive() || type == String.class || type.isEnum() || Number.class.isAssignableFrom(type) || type == Boolean.class)) continue;
            if (type == void.class) continue;
            String name = m.getName();
            if (name.equals("hashCode") || name.equals("toString") || name.startsWith("set")) continue;
            try { Object value = m.invoke(target); out.put(name, value == null ? JSONObject.NULL : value instanceof Enum<?> e ? e.name() : value); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    /** The document API wrapper of a raw audio or note clip event. */
    private static Clip clipEventWrapper(boolean audio, Object raw) {
        return audio ? new com.bitwig.flt.document.api.document.ApiAudioNoteClipEvent((bitwig.obf.DyF) raw)
                : new com.bitwig.flt.document.api.document.ApiInstrumentNoteClipEvent((bitwig.obf.DrA) raw);
    }

    private static Object callApi(Object target, String method, Object... args) {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            boolean fits = true;
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < args.length && fits; i++) {
                Class<?> type = types[i].isPrimitive() ? java.lang.invoke.MethodType.methodType(types[i]).wrap().returnType() : types[i];
                fits = args[i] == null ? !types[i].isPrimitive() : type.isInstance(args[i]);
            }
            if (!fits) continue;
            try { m.setAccessible(true); return m.invoke(target, args); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(method + " failed", failure); }
        }
        throw new IllegalArgumentException("no method " + method + "/" + args.length + " on " + target.getClass().getName());
    }

    private static java.util.List<Object> samplerResources(Object owner) {
        java.util.List<Object> found = new java.util.ArrayList<>();
        java.util.LinkedList<Object> queue = new java.util.LinkedList<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        queue.add(deviceContents(owner));
        while (!queue.isEmpty() && seen.size() < 20000) {
            Object node = queue.poll();
            if (node == null || seen.put(node, true) != null) continue;
            if (node instanceof bitwig.obf.DKS) { found.add(node); continue; } // a sampler resource component
            // Nested chains hold their own devices, read separately.
            if ((node instanceof com.bitwig.flt.document.core.master.device.SRR)
                    || (node instanceof com.bitwig.flt.document.core.master.device.beU)
                    || (node instanceof com.bitwig.flt.document.core.master.device.YVC)) continue;
            Object children = componentChildren(node);
            if (children instanceof java.util.List<?> list) for (Object child : list) queue.add(child);
        }
        return found;
    }

    // The 6.1.5 bridge looked for the loop mode as a public enum getter whose type name
    // started with "by"; Bitwig exposes none (its loop mode getter is not public), so
    // loop_mode was never read or written. The port keeps that behaviour.
    private static JSONObject singleSampleState(Object api, Object model) {
        com.bitwig.extension.api.project.component.SingleSampleResource sample =
                (com.bitwig.extension.api.project.component.SingleSampleResource) api;
        JSONObject out = new JSONObject();
        com.bitwig.extension.api.project.file.SampleReference reference = sample.getSamplerReference();
        if (reference != null) {
            java.io.File file = reference.getLocalFile();
            out.put("file", file == null ? JSONObject.NULL : file.getPath())
                    .put("duration", reference.getDurationInSeconds())
                    .put("sample_rate", reference.getSampleRate())
                    .put("channels", reference.getChannelCount());
        }
        out.put("root_key", sample.getRootKey()).put("key_track", sample.getKeyTrack()).put("gain", sample.getGain())
                .put("detune", sample.getDetune()).put("sample_start", sample.getSampleStart()).put("sample_end", sample.getSampleEnd())
                .put("loop_start", sample.getLoopStart()).put("loop_end", sample.getLoopEnd()).put("loop_crossfade", sample.getLoopCrossfade());
        return out;
    }

    private static JSONArray readSampleContent(Object owner) {
        JSONArray out = new JSONArray();
        for (Object resource : samplerResources(owner)) {
            JSONObject item = new JSONObject().put("component", jsonModelValue(automationIdentifier(owner, resource)));
            Object content = ((bitwig.obf.DKS) resource).ccv(); // the loaded content
            try {
                if (content == null) item.put("kind", "empty");
                else if ((content instanceof bitwig.obf.trg)) {
                    JSONArray zones = new JSONArray();
                    for (Object zone : ((bitwig.obf.trg) content).unz()) { // the multisample's zones
                        com.bitwig.flt.document.api.document.ApiMultisampleZone api =
                                new com.bitwig.flt.document.api.document.ApiMultisampleZone((bitwig.obf.pte) zone);
                        JSONObject z = new JSONObject()
                                .put("low_key", api.getLowKey()).put("high_key", api.getHighKey())
                                .put("low_key_cf", api.getLowKeyCf()).put("high_key_cf", api.getHighKeyCf())
                                .put("low_velocity", api.getLowVelocity()).put("high_velocity", api.getHighVelocity())
                                .put("low_velocity_cf", api.getLowVelocityCf()).put("high_velocity_cf", api.getHighVelocityCf())
                                .put("low_select", api.getLowSelect()).put("high_select", api.getHighSelect())
                                .put("low_select_cf", api.getLowSelectCf()).put("high_select_cf", api.getHighSelectCf());
                        Object sampleApi = api.getResource();
                        z.put("sample", singleSampleState(sampleApi, ((ApiObject) sampleApi).getTarget()));
                        zones.put(z);
                    }
                    item.put("kind", "multisample").put("zones", zones);
                } else if (content instanceof bitwig.obf.ZFM) { // sliced sample content
                    item.put("kind", "sliced");
                } else if ((content instanceof bitwig.obf.KUZ)) {
                    item.put("kind", "single").put("sample", singleSampleState(
                            new com.bitwig.flt.document.api.document.ApiSingleSampleResource((bitwig.obf.KUZ) content), content));
                } else item.put("kind", "unknown").put("model_class", content.getClass().getName());
            } catch (RuntimeException unreadable) {
                item.put("read_error", unreadable.toString());
            }
            out.put(item);
        }
        return out;
    }

    // ---- Plain model fields. Some device settings are stored fields of an atom, not
    // automatable parameters: a Sampler's playback, repitch, cycles, spectral and grain
    // modes live on its sample player atom. An atom's descriptor (kX1.nj_(), a def)
    // lists its value fields (def.lKq(), deb, each owned by the descriptor that
    // declares it, deC.a1y()); a field reads and writes through Bitwig's own accessors
    // (deb.jPB(atom), deb.jPB(atom, value)), as the project serializer does.

    private static final java.util.Set<String> MODEL_FIELD_ATOMS = java.util.Set.of("sample_player_atom");
    // Runtime identity and editor state, never project content to copy.
    private static final java.util.Set<String> MODEL_FIELD_SKIP = java.util.Set.of(
            "managed_object_ID", "preset_identifier", "is_grid_module", "zone_to_observe", "module_analysis_output");

    private static String descriptorName(Object atom) {
        bitwig.obf.dge descriptor = atom instanceof com.bitwig.ramona.core.ytz node ? node.ni_() : null; // the atom's descriptor
        return descriptor == null ? null : descriptor.PfH(); // its name
    }

    private static java.util.List<Object> atomsWithDescriptor(Object owner, String descriptor) {
        java.util.List<Object> found = new java.util.ArrayList<>();
        java.util.LinkedList<Object> queue = new java.util.LinkedList<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        queue.add(deviceContents(owner));
        while (!queue.isEmpty() && seen.size() < 20000) {
            Object node = queue.poll();
            if (node == null || seen.put(node, true) != null) continue;
            if (descriptor.equals(descriptorName(node))) { found.add(node); continue; }
            // Nested chains hold their own devices, read separately.
            if ((node instanceof com.bitwig.flt.document.core.master.device.SRR)
                    || (node instanceof com.bitwig.flt.document.core.master.device.beU)
                    || (node instanceof com.bitwig.flt.document.core.master.device.YVC)) continue;
            Object children = componentChildren(node);
            if (children instanceof java.util.List<?> list) for (Object child : list) queue.add(child);
        }
        return found;
    }

    /** The atom's value fields that hold project content (runtime identity and editor state left out). */
    private static java.util.Map<String, Object> ownValueFields(Object atom) {
        bitwig.obf.dge descriptor = ((com.bitwig.ramona.core.ytz) atom).ni_(); // the atom's descriptor
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (Object field : descriptor.oCC()) { // its value fields
            String name = ((bitwig.obf.dfz) field).PfH();
            if (!MODEL_FIELD_SKIP.contains(name)) fields.put(name, field);
        }
        return fields;
    }

    private static Object readField(Object field, Object atom) {
        Object value = ((bitwig.obf.dfz) field).Gef((com.bitwig.ramona.core.ytz) atom); // the field's value on this atom
        if (value == null) return JSONObject.NULL;
        if (value instanceof Boolean || value instanceof Number || value instanceof String) return value;
        Object name = value instanceof com.bitwig.ramona.core.WG member ? member.Gef() : null; // an enum member's stored name
        return name != null ? name : String.valueOf(value);   // an enum member by its stored name
    }

    private static JSONArray readModelFields(Object owner) {
        JSONArray out = new JSONArray();
        for (String descriptor : MODEL_FIELD_ATOMS) {
            for (Object atom : atomsWithDescriptor(owner, descriptor)) {
                JSONObject fields = new JSONObject();
                try {
                    for (java.util.Map.Entry<String, Object> entry : ownValueFields(atom).entrySet())
                        fields.put(entry.getKey(), readField(entry.getValue(), atom));
                } catch (RuntimeException unreadable) {
                    fields.put("read_error", unreadable.toString());
                }
                out.put(new JSONObject().put("descriptor", descriptor)
                        .put("component", jsonModelValue(automationIdentifier(owner, atom))).put("fields", fields));
            }
        }
        return out;
    }

    private static JSONObject setModelFields(Track track, Object owner, JSONObject params) {
        String descriptor = params.getString("descriptor");
        if (!MODEL_FIELD_ATOMS.contains(descriptor)) throw new IllegalArgumentException("unsupported model atom: " + descriptor);
        java.util.List<Object> atoms = atomsWithDescriptor(owner, descriptor);
        int index = params.optInt("index", 0);
        if (index < 0 || index >= atoms.size()) throw new IllegalArgumentException("no " + descriptor + " at index " + index);
        Object atom = atoms.get(index);
        java.util.Map<String, Object> fields = ownValueFields(atom);
        JSONObject changes = params.getJSONObject("fields");
        java.util.Map<Object, Object> staged = new java.util.LinkedHashMap<>();
        JSONArray skipped = new JSONArray();
        for (String name : changes.keySet()) {
            Object field = fields.get(name);
            if (field == null) throw new IllegalArgumentException("unknown field: " + name);
            // Only settable fields have a public setter; the others are
            // derived (a Sampler's max_zones_playing_in_parallel follows its zones).
            if (!((bitwig.obf.dfz) field).ZRB()) { skipped.put(name); continue; }
            Object current = readField(field, atom);
            Object wanted = changes.get(name);
            Object value;
            if (field instanceof bitwig.obf.dfL enumField) {
                Object item = enumField.Bqk().Gef(String.valueOf(wanted)); // the enum type's member by name
                if (item == null) throw new IllegalArgumentException("unknown member " + wanted + " for " + name);
                value = item;
            } else if (current instanceof Boolean) value = changes.getBoolean(name);
            else if (current instanceof Integer) value = changes.getInt(name);
            else if (current instanceof Long) value = changes.getLong(name);
            else if (current instanceof Double || current instanceof Float) value = changes.getDouble(name);
            else if (current instanceof String) value = changes.getString(name);
            else throw new IllegalArgumentException("unsupported field type: " + name);
            staged.put(field, value);
        }
        DirectAccess.runInUndo(track.clipTimeline(), () -> {
            for (java.util.Map.Entry<Object, Object> entry : staged.entrySet())
                ((bitwig.obf.dfz) entry.getKey()).Gef((com.bitwig.ramona.core.ytz) atom, entry.getValue()); // write the field
        });
        JSONObject after = new JSONObject();
        for (java.util.Map.Entry<String, Object> entry : fields.entrySet()) after.put(entry.getKey(), readField(entry.getValue(), atom));
        return new JSONObject().put("descriptor", descriptor).put("index", index).put("fields", after).put("skipped", skipped);
    }

    // ---- Convolution impulses: an MM component holds gnS impulse resources that
    // point at a package file (AnI vendor/package + hQU type/path) or a local file.

    private static java.util.List<Object> componentsOfType(Object owner, Class<?> type) {
        java.util.List<Object> found = new java.util.ArrayList<>();
        java.util.LinkedList<Object> queue = new java.util.LinkedList<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        queue.add(deviceContents(owner));
        while (!queue.isEmpty() && seen.size() < 20000) {
            Object node = queue.poll();
            if (node == null || seen.put(node, true) != null) continue;
            if (type.isInstance(node)) { found.add(node); continue; }
            if ((node instanceof com.bitwig.flt.document.core.master.device.SRR)
                    || (node instanceof com.bitwig.flt.document.core.master.device.beU)
                    || (node instanceof com.bitwig.flt.document.core.master.device.YVC)) continue;
            Object children = componentChildren(node);
            if (children instanceof java.util.List<?> list) for (Object child : list) queue.add(child);
        }
        return found;
    }

    private static JSONObject impulseReference(Object resource) {
        if (resource == null) return null;
        JSONObject out = new JSONObject();
        bitwig.obf.RZF holder = ((bitwig.obf.VGK) resource).BBg(); // the impulse resource's file holder
        com.bitwig.flt.document.core.master.GDs reference = holder == null ? null : holder.sCA(); // its file reference
        // The reference's location: the 6.1.5 bridge took the first non-null getter returning one.
        com.bitwig.flt.document.core.master.Qgw location = reference == null ? null : reference.ZRB();
        if (location == null && reference instanceof com.bitwig.flt.document.core.master.oeQ other) location = other.y3f();
        if (location == null) return out.put("unresolved", true);
        com.bitwig.flt.packaging.core.Hvv packaged = location.MgQ(); // a package resource, if any
        if (packaged != null) {
            com.bitwig.flt.packaging.core.vQq identity = packaged.beI(); // vendor/package/version
            com.bitwig.flt.packaging.core.Tlo path = packaged.Gef();     // resource type and path
            out.put("vendor", identity.beI()).put("package", identity.Gef())
                    .put("version", identity.sCA())
                    .put("type", String.valueOf(path.beI().beI())).put("path", path.Gef());
        }
        Object file = location.BBg(); // a local file, if any
        if (file instanceof java.io.File f) out.put("file", f.getPath());
        return out;
    }

    private static JSONArray readImpulses(Object owner) {
        JSONArray out = new JSONArray();
        for (Object component : componentsOfType(owner, bitwig.obf.Bsy.class) /* Convolution impulse components */) {
            JSONObject item = new JSONObject().put("component", jsonModelValue(automationIdentifier(owner, component)));
            try {
                JSONObject current = impulseReference(((bitwig.obf.Bsy) component).cCV());   // the loaded impulse
                JSONObject alternate = impulseReference(((bitwig.obf.Bsy) component).VNw()); // the alternate impulse
                if (current != null) item.put("impulse", current);
                if (alternate != null) item.put("alternate", alternate);
            } catch (RuntimeException unreadable) {
                item.put("read_error", unreadable.toString());
            }
            out.put(item);
        }
        return out;
    }

    private static bitwig.obf.VGK packagedImpulse(JSONObject spec) {
        com.bitwig.flt.packaging.core.vQq identity = new com.bitwig.flt.packaging.core.vQq(
                spec.getString("vendor"), spec.getString("package"), spec.optInt("version", 1));
        com.bitwig.flt.packaging.core.rPO kind = null; // the package resource type
        for (Object candidate : com.bitwig.flt.packaging.core.rPO.values())
            if (spec.optString("type", "impulses").equals(String.valueOf(((com.bitwig.flt.packaging.core.rPO) candidate).beI())))
                kind = (com.bitwig.flt.packaging.core.rPO) candidate;
        if (kind == null) throw new IllegalArgumentException("unknown package resource type " + spec.optString("type"));
        com.bitwig.flt.packaging.core.Hvv resource = new com.bitwig.flt.packaging.core.Hvv(identity, kind, spec.getString("path"));
        com.bitwig.flt.document.core.master.fOU reference = com.bitwig.flt.document.core.master.fOU.beI(resource); // a file reference to it
        return bitwig.obf.VGK.beI(reference); // an impulse resource for it
    }

    private static JSONObject loadImpulse(Track track, JSONObject params) {
        Object owner = deviceTargetAt(track, params.getInt("device"));
        Object component = deviceComponent(owner, params.getString("component"), bitwig.obf.Bsy.class /* the Convolution impulse component */);
        JSONObject spec = params.getJSONObject("impulse");
        if (!spec.has("vendor")) throw new IllegalArgumentException("only package impulses are supported");
        bitwig.obf.VGK impulse = packagedImpulse(spec);
        DirectAccess.runInUndo(track.clipTimeline(), () -> ((bitwig.obf.Bsy) component).MgQ(impulse)); // load it
        return new JSONObject().put("content", readImpulses(owner));
    }

    private static void applySampleSettings(Object api, Object model, JSONObject sample) {
        com.bitwig.extension.api.project.component.SingleSampleResource target =
                (com.bitwig.extension.api.project.component.SingleSampleResource) api;
        if (sample.has("sample_start") && sample.has("sample_end")) {
            // Widen first so start/end never cross while being moved.
            target.setSampleEnd(sample.getDouble("sample_end"));
            target.setSampleStart(sample.getDouble("sample_start"));
            target.setSampleEnd(sample.getDouble("sample_end"));
        }
        if (sample.has("root_key")) target.setRootKey(sample.getInt("root_key"));
        if (sample.has("key_track")) target.setKeyTrack(sample.getDouble("key_track"));
        if (sample.has("gain")) target.setGain(sample.getDouble("gain"));
        if (sample.has("detune")) target.setDetune(sample.getDouble("detune"));
        if (sample.has("loop_start")) target.setLoopStart(sample.getDouble("loop_start"));
        if (sample.has("loop_end")) target.setLoopEnd(sample.getDouble("loop_end"));
        if (sample.has("loop_crossfade")) target.setLoopCrossfade(sample.getDouble("loop_crossfade"));
        // loop_mode: never read on 6.1.5 (see singleSampleState), so never written either.
    }

    // Same depth-first order as readNestedComponents, without reading devices.
    private static void chainOutline(Object owner, Object node, JSONArray out,
                                     java.util.IdentityHashMap<Object, Boolean> seen, int depth) {
        if (node == null || depth > 32 || seen.put(node, true) != null) return;
        if ((node instanceof bitwig.obf.nnO)) return;
        if ((node instanceof com.bitwig.flt.document.core.master.device.SRR)) {
            out.put(new JSONObject().put("kind", "chain").put("component_path", jsonModelValue(automationIdentifier(owner, node))));
            return;
        }
        if ((node instanceof com.bitwig.flt.document.core.master.device.beU)) {
            Object chain = ((beU) node).VNw(); // the slot's chain
            if (chain != null) out.put(new JSONObject().put("kind", "chain")
                    .put("component_path", jsonModelValue(automationIdentifier(owner, chain))));
            return;
        }
        if ((node instanceof com.bitwig.flt.document.core.master.device.YVC)) {
            String kind = (node instanceof com.bitwig.flt.document.core.master.device.KFW) ? "layer"
                    : (node instanceof com.bitwig.flt.document.core.master.device.b3K) ? "multi_out" : "band";
            for (Object chain : ((YVC) node).LYs())
                out.put(new JSONObject().put("kind", kind).put("component_path", jsonModelValue(automationIdentifier(owner, chain))));
            return;
        }
        if ((node instanceof bitwig.obf.xdh) || (node instanceof bitwig.obf.cbF) || (node instanceof bitwig.obf.lpr)) return;
        Object children = componentChildren(node);
        if (children instanceof java.util.List<?> list) for (Object child : list) chainOutline(owner, child, out, seen, depth + 1);
    }

    private static JSONObject loadSampleContent(ApiProject project, Track track, JSONObject params) {
        Object owner = deviceTargetAt(track, params.getInt("device"));
        Object resource = deviceComponent(owner, params.getString("component"), bitwig.obf.DKS.class);
        com.bitwig.flt.document.api.document.ApiSamplerResourceComponent component =
                new com.bitwig.flt.document.api.document.ApiSamplerResourceComponent((bitwig.obf.DKS) resource);
        String kind = params.getString("kind");
        DirectAccess.runInUndo(track.clipTimeline(), () -> {
            if (kind.equals("single")) {
                JSONObject sample = params.getJSONObject("sample");
                java.io.File file = new java.io.File(sample.getString("file"));
                if (!file.isFile()) throw new IllegalArgumentException("sample file not found: " + file);
                com.bitwig.extension.api.project.file.SampleReference reference = project.getFactory().createSampleReferenceForLocalFile(file);
                Object api = component.loadSample(reference, false);
                applySampleSettings(api, ((ApiObject) api).getTarget(), sample);
            } else if (kind.equals("multisample")) {
                com.bitwig.extension.api.project.component.Multisample multisample = component.createMultisample();
                for (Object value : params.getJSONArray("zones")) {
                    JSONObject zone = (JSONObject) value;
                    JSONObject sample = zone.getJSONObject("sample");
                    java.io.File file = new java.io.File(sample.getString("file"));
                    if (!file.isFile()) throw new IllegalArgumentException("sample file not found: " + file);
                    com.bitwig.extension.api.project.component.MultisampleZone api =
                            multisample.addZone(project.getFactory().createSampleReferenceForLocalFile(file));
                    for (String key : zone.keySet()) {
                        if (key.equals("sample")) continue;
                        int bound = zone.getInt(key);
                        switch (key) {
                            case "low_key" -> api.setLowKey(bound);
                            case "high_key" -> api.setHighKey(bound);
                            case "low_key_cf" -> api.setLowKeyCf(bound);
                            case "high_key_cf" -> api.setHighKeyCf(bound);
                            case "low_velocity" -> api.setLowVelocity(bound);
                            case "high_velocity" -> api.setHighVelocity(bound);
                            case "low_velocity_cf" -> api.setLowVelocityCf(bound);
                            case "high_velocity_cf" -> api.setHighVelocityCf(bound);
                            case "low_select" -> api.setLowSelect(bound);
                            case "high_select" -> api.setHighSelect(bound);
                            case "low_select_cf" -> api.setLowSelectCf(bound);
                            case "high_select_cf" -> api.setHighSelectCf(bound);
                            default -> throw new IllegalArgumentException("no method set" + key + "/1 on multisample zone");
                        }
                    }
                    Object sampleApi = api.getResource();
                    applySampleSettings(sampleApi, ((ApiObject) sampleApi).getTarget(), sample);
                }
            } else throw new IllegalArgumentException("unsupported sampler content kind: " + kind);
        });
        return new JSONObject().put("content", readSampleContent(owner));
    }

    // CLAP has no document API preset; build the device like Bitwig's DAWproject
    // importer (descriptor "clap:<id>", state file in the plugin-states folder).
    private static JSONObject insertClapPlugin(ApiProject project, Track targetTrack, String name, String id,
                                               JSONObject params, String path) {
        if (!params.has("state")) throw new IllegalArgumentException("CLAP insertion requires a .clap-preset state");
        java.io.File source = new java.io.File(params.getString("state"));
        if (!source.isFile() || source.length() == 0) throw new IllegalArgumentException("plugin state file required");
        try {
            java.io.File directory = bitwig.obf.XAe.cCV(); // the plugin-states folder
            java.io.File copy = new java.io.File(directory, java.util.UUID.randomUUID() + ".clap-preset");
            java.nio.file.Files.createDirectories(directory.toPath());
            java.nio.file.Files.copy(source.toPath(), copy.toPath());
            // A plugin descriptor; for a "clap:" ID it is a CLAP descriptor, as Bitwig's DAWproject importer casts it.
            com.bitwig.flt.device.YjF descriptor = (com.bitwig.flt.device.YjF) (Object) com.bitwig.flt.device.YEs.Gef("clap:" + id);
            // A CLAP device model: name, descriptor, version, state file.
            sgH device = com.bitwig.flt.document.core.master.device.jyE.beI(name, descriptor, params.optString("version", ""), copy);
            k3K chainModel = (k3K) ((ApiObject) targetTrack.deviceChain()).getTarget();
            DeviceChain chain = targetTrack.deviceChain();
            int before = chain.getDevices().size();
            DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> chainModel.Gef(device)); // append the device
            if (chain.getDevices().size() != before + 1) throw new IllegalStateException("plugin insertion count mismatch");
            return new JSONObject().put("device", before).put("count", before + 1)
                    .put("data", readDevice((Device) chain.getDevices().get(before), before, path, routingContext(project), deviceOwners(project), atomOwnerIndex(project)));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("CLAP insertion failed: " + failure, failure);
        }
    }

    private static final java.util.Map<String, JSONObject> renderJobs = new java.util.concurrent.ConcurrentHashMap<>();

    // Offline export through Bitwig's own "Exporting audio" job (Ovo), as its
    // project render tests do: post-fader, one track (master by default), beats.
    private static JSONObject startRender(ApiProject project, JSONObject params) {
        CmH document = (CmH) project.getTarget();
        String base = params.getString("path");
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) base = base.substring(0, base.length() - 4);
        double start = params.getDouble("start"), length = params.getDouble("length");
        if (!Double.isFinite(start) || start < 0 || !Double.isFinite(length) || length <= 0)
            throw new IllegalArgumentException("render range needs start >= 0 and length > 0 (beats)");
        java.io.File output = new java.io.File(base + ".wav");
        if (output.exists()) throw new IllegalArgumentException("render destination exists: " + output);
        Object track = params.has("track") ? ((ApiObject) view(resolve(project, params.getString("track")))).getTarget()
                : document.svk().smz(); // the effect track group's master track
        {
            // The job takes the range as start and end beats.
            bitwig.obf.lKW job = new bitwig.obf.lKW(document, java.util.List.of(track), new String[]{base}, true, start, start + length);
            // The job opens the output folder in Explorer when it succeeds; keep the desktop quiet.
            job.beI = null;
            bitwig.obf.rll settings = bitwig.obf.rll.Bqk(); // default export settings
            settings.Gef((bitwig.obf.vT) namedNativeEnum(bitwig.obf.vT.class, params.optString("tap", "POST_FADER"))); // the tap
            com.bitwig.flt.document.core.master.Gcs format = settings.kwH(); // the file format
            format.beI((bitwig.obf.yvd) namedNativeEnum(bitwig.obf.yvd.class, params.optString("bit_depth", "INT24"))); // bit depth
            format.beI(false); // a format flag, off as the 6.1.5 bridge set it
            String id = java.util.UUID.randomUUID().toString();
            JSONObject status = new JSONObject().put("job", id).put("state", "running").put("path", output.getPath());
            renderJobs.put(id, status);
            java.util.concurrent.CountDownLatch latch = job.tWW(); // released when the job ends
            job.Gef(settings); // start the job
            Thread waiter = new Thread(() -> {
                try {
                    boolean done = latch.await(params.optLong("timeout", 3600), java.util.concurrent.TimeUnit.SECONDS);
                    status.put("state", !done ? "timeout" : output.isFile() && output.length() > 44 ? "complete" : "failed")
                            .put("bytes", output.length());
                } catch (InterruptedException interrupted) { status.put("state", "interrupted"); }
            }, "openwig-render-wait");
            waiter.setDaemon(true);
            waiter.start();
            return new JSONObject(status.toString());
        }
    }

    private static JSONObject executeRebuild(String method, JSONObject params) {
        ApiProject project = project();
        try {
            return switch (method) {
                case "track.set_source" -> {
                    Track track = resolveTrack(project, params);
                    com.bitwig.extension.api.project.routing.Source source = params.isNull("source") ? null
                            : trackSource(project, params.getJSONObject("source"));
                    DirectAccess.runInUndo(track.clipTimeline(), () -> {
                        if (source != null) { track.sourceSelector().setSource(source); return; }
                        bitwig.obf.jN selector = (bitwig.obf.jN) ((ApiObject) track.sourceSelector()).getTarget();
                        selector.beI((bitwig.obf.wtA) null); // clear the source
                    });
                    com.bitwig.extension.api.project.routing.Source now = track.sourceSelector().getSource();
                    yield new JSONObject().put("source", sourceInfo(now == null ? null : ((ApiObject) now).getTarget(), routingContext(project)));
                }
                case "device.set_sidechain" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    Object component;
                    if (params.optString("component", "").isEmpty()) {
                        // A plugin's own sidechain input, as the reader reports it.
                        R1c plugin = owner instanceof Mv pluginDevice ? pluginDevice.dcC() : null;
                        component = plugin == null ? null : plugin.pDG();
                        if (!(component instanceof nnO))
                            throw new IllegalArgumentException("device has no plugin sidechain input");
                    } else component = deviceComponent(owner, params.getString("component"), nnO.class);
                    com.bitwig.extension.api.project.routing.Source source = params.isNull("source") ? null
                            : trackSource(project, params.getJSONObject("source"));
                    com.bitwig.flt.document.api.document.ApiSidechainRoutingComponent wrapper =
                            new com.bitwig.flt.document.api.document.ApiSidechainRoutingComponent((nnO) component);
                    DirectAccess.runInUndo(track.clipTimeline(), () -> wrapper.setSource(source));
                    yield new JSONObject().put("source", sourceInfo(((nnO) component).DIi().BZt(), routingContext(project)));
                }
                case "device.create_band" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    YVC bank = (YVC) deviceComponent(owner, params.getString("component"), YVC.class);
                    Object[] created = new Object[1];
                    DirectAccess.runInUndo(track.clipTimeline(), () -> {
                        // Same construction as the native "add layer" container, on any chain bank.
                        SRR chain = SRR.rcd(); // a new nested chain
                        chain.beI(com.bitwig.flt.document.core.master.device.aCR.qJX()); // with an empty device chain
                        bank.Bqk(chain); // add it to the bank
                        created[0] = chain;
                    });
                    yield new JSONObject().put("component", jsonModelValue(automationIdentifier(owner, created[0])))
                            .put("count", bank.LYs().size());
                }
                case "device.drum_pad" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    Object pads = deviceComponent(owner, params.optString("component", "CONTENTS/DRUM_PADS"), bitwig.obf.aIt.class);
                    int key = params.getInt("key");
                    if (key < 0 || key > 127) throw new IllegalArgumentException("drum pad key must be 0..127");
                    Object[] chain = new Object[1];
                    DirectAccess.runInUndo(track.clipTimeline(), () -> {
                        com.bitwig.extension.api.project.component.DrumPad pad =
                                new com.bitwig.flt.document.api.document.ApiDrumPads((bitwig.obf.aIt) pads).getDrumPad(key);
                        pad.getDeviceChain(); // creates the pad's chain
                        if (params.has("name")) pad.setName(params.getString("name"));
                        chain[0] = ((WGX) ((ApiObject) pad).getTarget()).JTf(); // the pad's chain
                    });
                    yield new JSONObject().put("component", jsonModelValue(automationIdentifier(owner, chain[0]))).put("key", key);
                }
                case "device.set_active" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    int index = params.getInt("device");
                    java.util.List<?> devices = track.deviceChain().getDevices();
                    if (index < 0 || index >= devices.size()) throw new IllegalArgumentException("device index out of range");
                    Device device = (Device) devices.get(index);
                    boolean on = params.getBoolean("on");
                    DirectAccess.runInUndo(track.clipTimeline(), () -> device.setActive(on));
                    yield new JSONObject().put("active", device.getActive());
                }
                case "device.components" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    JSONArray out = new JSONArray();
                    java.util.LinkedList<Object> queue = new java.util.LinkedList<>();
                    java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
                    queue.add(deviceContents(owner));
                    while (!queue.isEmpty() && out.length() < 4000) {
                        Object node = queue.poll();
                        if (node == null || seen.put(node, true) != null) continue;
                        out.put(new JSONObject().put("path", jsonModelValue(automationIdentifier(owner, node)))
                                .put("class", node.getClass().getName())
                                .put("super", node.getClass().getSuperclass() == null ? JSONObject.NULL : node.getClass().getSuperclass().getName()));
                        Object children = componentChildren(node);
                        if (children instanceof java.util.List<?> list) for (Object child : list) queue.add(child);
                    }
                    yield new JSONObject().put("components", out);
                }
                case "transport.set_groove" -> {
                    com.bitwig.extension.api.project.Transport transport = project.getTransport();
                    DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                        if (params.has("enabled")) transport.getEnableGroove().setValue(params.getBoolean("enabled"));
                        if (params.has("shuffle")) transport.getShuffleAmount().setValue(params.getDouble("shuffle") * 100, Unit.PERCENTAGE_OF_RANGE);
                        if (params.has("accent_amount")) transport.getAccentAmount().setValue(params.getDouble("accent_amount") * 100, Unit.PERCENTAGE_OF_RANGE);
                        if (params.has("accent_phase")) transport.getAccentPhase().setValue(params.getDouble("accent_phase") * 100, Unit.PERCENTAGE_OF_RANGE);
                    });
                    yield new JSONObject().put("enabled", transport.getEnableGroove().getValue())
                            .put("shuffle", transport.getShuffleAmount().getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0);
                }
                case "transport.set_time_signature" -> {
                    com.bitwig.extension.api.project.Transport transport = project.getTransport();
                    com.bitwig.extension.api.project.timeline.TimeSignature signature = project.getFactory().createTimeSignature(
                            params.getInt("numerator"), params.getInt("denominator"), params.optInt("ticks", 16));
                    DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> transport.setTimeSignature(signature));
                    yield new JSONObject().put("numerator", params.getInt("numerator")).put("denominator", params.getInt("denominator"));
                }
                case "automation.write_target" -> writeTargetAutomation(project, params);
                case "automation.write_clips" -> writeAutomationClips(project, params);
                case "device.load_sample" -> loadSampleContent(project, deviceScope(resolveTrack(project, params), params), params);
                case "debug.atom_methods", "debug.atom_invoke" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    Object atom = modulatorAtoms(modulatorList(owner).get(params.getInt("modulator"))).get(params.getString("parameter"));
                    if (atom == null) throw new IllegalArgumentException("unknown modulator parameter");
                    if (method.equals("debug.atom_methods")) {
                        JSONArray methods = new JSONArray();
                        for (Class<?> c = atom.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                            for (java.lang.reflect.Method m : c.getDeclaredMethods())
                                if (m.getParameterCount() == 1 && (m.getParameterTypes()[0] == boolean.class || m.getParameterTypes()[0] == double.class))
                                    methods.put(c.getName() + "." + m.getName() + "(" + m.getParameterTypes()[0].getName() + ")->" + m.getReturnType().getSimpleName()
                                            + (java.lang.reflect.Modifier.isPublic(m.getModifiers()) ? " public" : ""));
                        yield new JSONObject().put("methods", methods);
                    }
                    String name = params.getString("method");
                    boolean flag = params.getBoolean("value");
                    // Reflection kept: a debugging aid that invokes a method the caller names.
                    DirectAccess.runInUndo(track.clipTimeline(), () -> {
                        for (Class<?> c = atom.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                            try {
                                java.lang.reflect.Method m = c.getDeclaredMethod(name, boolean.class);
                                m.setAccessible(true);
                                m.invoke(atom, flag);
                                return;
                            } catch (NoSuchMethodException next) { /* keep looking */ }
                            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                        }
                        throw new IllegalArgumentException("no method " + name + "(boolean)");
                    });
                    JSONObject after = describeGetters(atom);
                    Object type = atom instanceof kh decimal ? decimal.XdI() : null; // its value range
                    if (type != null) after.put("type", describeGetters(type));
                    yield after;
                }
                case "debug.describe_modulator" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    java.util.List<?> modulators = modulatorList(owner);
                    Object modulator = modulators.get(params.getInt("modulator"));
                    JSONObject out = new JSONObject().put("modulator", describeGetters(modulator));
                    Object contents = ((QFr) modulator).MvA();
                    out.put("contents", describeGetters(contents));
                    JSONObject atoms = new JSONObject();
                    for (java.util.Map.Entry<String, Object> entry : modulatorAtoms(modulator).entrySet()) {
                        JSONObject atom = describeGetters(entry.getValue());
                        aG type = entry.getValue() instanceof kh decimal ? decimal.XdI() : null; // its value range
                        if (type != null) {
                            atom.put("type_class", type.getClass().getName()).put("type", describeGetters(type));
                            JSONArray probes = new JSONArray();
                            for (double v : new double[]{-1, -0.5, 0, 0.5, 1}) probes.put(type.beI(v)); // normalize
                            atom.put("normalize(-1,-.5,0,.5,1)", probes);
                        }
                        bitwig.obf.bU modulation = entry.getValue() instanceof QCX modulated ? modulated.yy() : null;
                        if (modulation != null) atom.put("modulation_span", modulation.y3f());
                        atoms.put(entry.getKey(), atom);
                    }
                    yield out.put("atoms", atoms);
                }
                case "project.render" -> startRender(project, params);
                case "device.model_fields" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    yield new JSONObject().put("model_fields", readModelFields(deviceTargetAt(track, params.getInt("device"))));
                }
                case "device.set_model_fields" -> {
                    Track track = deviceScope(resolveTrack(project, params), params);
                    yield setModelFields(track, deviceTargetAt(track, params.getInt("device")), params);
                }
                case "device.load_impulse" -> loadImpulse(deviceScope(resolveTrack(project, params), params), params);
                case "device.component_rename" -> {
                    // Named stored components (Grid modules) share urr's undoable name setter.
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    com.bitwig.flt.document.core.master.device.JDx component = (com.bitwig.flt.document.core.master.device.JDx)
                            deviceComponent(owner, params.getString("component"), com.bitwig.flt.document.core.master.device.JDx.class);
                    String name = params.getString("name");
                    DirectAccess.runInUndo(track.clipTimeline(), () -> component.scr(name)); // the undoable rename
                    yield new JSONObject().put("component", params.getString("component")).put("name", component.af());
                }
                case "project.render_status" -> {
                    JSONObject status = renderJobs.get(params.getString("job"));
                    if (status == null) throw new IllegalArgumentException("unknown render job");
                    yield new JSONObject(status.toString());
                }
                case "device.structure" -> {
                    // Cheap identity read for rebuild bookkeeping (no project-wide indexes).
                    Track track = deviceScope(resolveTrack(project, params), params);
                    Object owner = deviceTargetAt(track, params.getInt("device"));
                    JSONArray modulators = new JSONArray();
                    itZ bank = owner instanceof sgH device ? device.LdK() : null;
                    Object list = bank == null ? null : bank.dvk();
                    if (list instanceof java.util.List<?> entries) for (Object entry : entries) {
                        QFr modulator = (QFr) entry;
                        Mmq contents = modulator.MvA();
                        Object identity = contents == null || contents.BCC() == null ? null : contents.BCC().sCA();
                        modulators.put(new JSONObject().put("index", modulators.length())
                                .put("modulator_id", identity == null ? JSONObject.NULL : identity.toString())
                                .put("device_name", modulator.af())
                                .put("x", modulator.MgQ()).put("y", modulator.unz()));
                    }
                    JSONArray light = new JSONArray();
                    chainOutline(owner, deviceContents(owner), light, new java.util.IdentityHashMap<>(), 0);
                    yield new JSONObject().put("modulators", modulators).put("chains", light);
                }
                default -> throw new IllegalArgumentException("unsupported rebuild method: " + method);
            };
        } finally {
            project.dispose();
        }
    }

    private static JSONObject executeDevice(String method, JSONObject params) {
        ApiProject project = project();
        try {
            Track targetTrack = deviceScope(resolveTrack(project, params), params);
            String path = params.optString("path", null);
            return switch (method) {
                case "device.layer_read", "device.layer_update", "device.layer_delete", "device.layer_move" -> editLayer(method, targetTrack, params);
                case "device.modulator_add", "device.modulator_delete", "device.modulator_move", "device.modulator_parameters", "device.modulator_bind", "device.modulator_unbind", "device.modulator_rename" -> editModulator(method, targetTrack, params);
                case "device.grid_add", "device.grid_move", "device.grid_delete", "device.grid_connect" -> executeGrid(method, targetTrack, params);
                case "device.grid_read", "device.read", "device.chain_banks", "device.create_layer" -> {
                    int index = params.getInt("device");
                    java.util.List<?> members = targetTrack.deviceChain().getDevices();
                    if (index < 0 || index >= members.size()) throw new IllegalArgumentException("device index out of range");
                    Device device = (Device) members.get(index);
                    Object owner = ((ApiObject) device).getTarget();
                    if (method.equals("device.grid_read")) yield readGrid((NativeDevice) device);
                    if (method.equals("device.chain_banks")) yield new JSONObject().put("banks", chainBanks(owner));
                    if (method.equals("device.create_layer")) {
                        KFW bank = (KFW) deviceComponent(owner, params.getString("component"), KFW.class);
                        Object[] created = new Object[1];
                        DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> created[0] = ((ApiObject)
                                new com.bitwig.flt.document.api.document.ApiParallelNestedDeviceChainContainer(bank).createChain()).getTarget());
                        yield new JSONObject().put("component", automationIdentifier(owner, created[0]))
                                .put("count", bank.LYs().size());
                    }
                    yield readDevice(device, index, path, routingContext(project), deviceOwners(project), atomOwnerIndex(project));
                }
                case "device.capture_plugin_state" -> {
                    int index = params.getInt("device");
                    java.util.List<?> devices = targetTrack.deviceChain().getDevices();
                    if (index < 0 || index >= devices.size() || !(devices.get(index) instanceof PluginDevice))
                        throw new IllegalArgumentException("plugin device index required");
                    yield capturePluginState(((Mv) ((ApiObject) devices.get(index)).getTarget()).dcC()); // the plugin instance holder
                }
                case "device.plugin_state_status", "device.operation_status" -> {
                    JSONObject status = pluginStateJobs.get(params.getString("job"));
                    if (status == null) throw new IllegalArgumentException("unknown plugin state job");
                    yield new JSONObject(status.toString());
                }
                case "device.export_plugin_state" -> {
                    int index = params.getInt("device");
                    java.util.List<?> devices = targetTrack.deviceChain().getDevices();
                    if (index < 0 || index >= devices.size() || !(devices.get(index) instanceof Vst3Device))
                        throw new IllegalArgumentException("state export currently requires a VST3 device");
                    java.io.File file = new java.io.File(params.getString("file")).getAbsoluteFile();
                    if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".vstpreset") || file.exists())
                        throw new IllegalArgumentException("fresh .vstpreset destination required");
                    String job = java.util.UUID.randomUUID().toString();
                    JSONObject status = new JSONObject().put("job", job).put("state", "running").put("path", file.getPath());
                    while (pluginStateJobs.size() >= 64) {
                        String completed = null;
                        for (String key : pluginStateJobs.keySet()) if (!pluginStateJobs.get(key).getString("state").equals("running")) { completed = key; break; }
                        if (completed == null) throw new IllegalStateException("too many active plugin state exports");
                        pluginStateJobs.remove(completed);
                    }
                    try {
                        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
                        java.nio.file.Files.createFile(file.toPath());
                        Pv callback = new NativeCallback(status,
                                () -> status.put("state", "complete").put("bytes", file.length()), "OpenwigPluginStateExport");
                        pluginStateJobs.put(job, status);
                        R1c holder = ((Mv) ((ApiObject) devices.get(index)).getTarget()).dcC(); // the plugin instance holder
                        holder.beI(file, callback); // export the plugin state to a file
                    } catch (java.io.IOException failure) {
                        status.put("state", "failed").put("error", failure.toString());
                        throw new IllegalStateException("plugin state export failed", failure);
                    }
                    yield new JSONObject(status.toString());
                }
                case "device.plugin_parameters" -> {
                    int index = params.getInt("device");
                    java.util.List<?> devices = targetTrack.deviceChain().getDevices();
                    if (index < 0 || index >= devices.size() || !(devices.get(index) instanceof PluginDevice))
                        throw new IllegalArgumentException("plugin device index required");
                    R1c holder = ((Mv) ((ApiObject) devices.get(index)).getTarget()).dcC(); // the plugin instance holder
                    java.util.Map<String, DoubleParameter> available = new java.util.HashMap<>();
                    for (Object item : holder.af()) { // the plugin's cached parameters
                        ogX atom = (ogX) item;
                        available.put(String.valueOf(atom.unz()),
                                (DoubleParameter) new com.bitwig.flt.document.api.document.ApiPluginDeviceParameterAtom(atom));
                    }
                    JSONObject changes = params.optJSONObject("values");
                    if (changes != null) {
                        if (!((CmH) project.getTarget()).unz() || !((CmH) project.getTarget()).baq())
                            throw new IllegalArgumentException("plugin edits require connected, ready audio");
                        for (String id : changes.keySet()) {
                            double value = finiteField(changes, id, -1);
                            if (!available.containsKey(id) || value < 0 || value > 1)
                                throw new IllegalArgumentException("unknown resident parameter or value outside 0..1: " + id);
                        }
                        DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> {
                            for (String id : changes.keySet()) {
                                available.get(id).setValue(changes.getDouble(id) * 100, Unit.PERCENTAGE_OF_RANGE);
                            }
                        });
                    }
                    JSONObject values = new JSONObject();
                    for (String id : available.keySet()) {
                        values.put(id, available.get(id).getValue(Unit.PERCENTAGE_OF_RANGE) / 100);
                    }
                    yield new JSONObject().put("values", values).put("state_dirty", holder.wYd())
                            .put("load_state", ((Enum<?>) (Object) holder.LYs()).name())
                            .put("available", !available.isEmpty());
                }
                case "device.insert_plugin" -> {
                    String name = params.getString("name");
                    String format = params.getString("format");
                    String id = params.getString("id");
                    if (name.isBlank()) throw new IllegalArgumentException("plugin name required");
                    if (format.equals("clap")) {
                        yield insertClapPlugin(project, targetTrack, name, id, params, path);
                    }
                    if (format.equals("vst2")) {
                        int vst2Id;
                        try { vst2Id = Integer.parseInt(id); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("VST2 ID must be an integer"); }
                        com.bitwig.extension.api.project.mixer.PluginPreset vst2 = project.getFactory().createVst2DevicePreset(name, vst2Id, params.optInt("version", 0));
                        if (params.has("role")) {
                            int role = switch (params.getString("role")) {
                                case "audioFX" -> 0; case "noteFX" -> 1; case "instrument" -> 2; case "analyzer" -> 3;
                                default -> throw new IllegalArgumentException("unknown device role " + params.getString("role")); };
                            bitwig.obf.ztI descriptor = (bitwig.obf.ztI) ((ApiObject) vst2).getTarget(); // the preset's plugin descriptor
                            descriptor.beI(bitwig.obf.PYf.beI(role)); // its device role
                        }
                        if (params.has("state")) {
                            java.io.File source = new java.io.File(params.getString("state"));
                            String lower = source.getName().toLowerCase(java.util.Locale.ROOT);
                            if (!source.isFile() || source.length() == 0 || !(lower.endsWith(".fxb") || lower.endsWith(".fxp")))
                                throw new IllegalArgumentException("VST2 state requires an .fxb or .fxp file");
                            try {
                                com.bitwig.extension.api.project.mixer.PluginStateID stateId = project.getFactory().createPluginStateID(lower.substring(lower.length() - 3));
                                try (java.io.OutputStream stream = project.getFactory().createPluginStateOutput(stateId)) {
                                    java.nio.file.Files.copy(source.toPath(), stream);
                                }
                                vst2.setPluginStateID(stateId);
                            } catch (java.io.IOException failure) { throw new IllegalStateException("plugin state import failed", failure); }
                        }
                        Device staged = vst2.createDeviceInstance();
                        DeviceChain chain = targetTrack.deviceChain();
                        int before = chain.getDevices().size();
                        DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> chain.addDevice(staged));
                        if (chain.getDevices().size() != before + 1) throw new IllegalStateException("plugin insertion count mismatch");
                        yield new JSONObject().put("device", before).put("count", before + 1)
                                .put("data", readDevice((Device) chain.getDevices().get(before), before, path, routingContext(project), deviceOwners(project), atomOwnerIndex(project)));
                    }
                    com.bitwig.extension.api.project.mixer.PluginPreset preset;
                    if (format.equals("vst3")) {
                        if (!id.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("VST3 ID needs 32 hex digits");
                        preset = params.has("role")
                                ? project.getFactory().createVst3DevicePreset(name, java.util.HexFormat.of().parseHex(id), switch (params.getString("role")) {
                                    case "audioFX" -> 0; case "noteFX" -> 1; case "instrument" -> 2; case "analyzer" -> 3;
                                    default -> throw new IllegalArgumentException("unknown device role " + params.getString("role")); })
                                : project.getFactory().createVst3DevicePreset(name, java.util.HexFormat.of().parseHex(id));
                    } else throw new IllegalArgumentException("unsupported plugin format");
                    if (params.has("state")) {
                        java.io.File source = new java.io.File(params.getString("state"));
                        if (!source.isFile() || source.length() == 0) throw new IllegalArgumentException("plugin state file required");
                        if (!format.equals("vst3")) throw new IllegalArgumentException("state import currently requires VST3");
                        try {
                            byte[] header;
                            try (java.io.InputStream stream = new java.io.FileInputStream(source)) { header = stream.readNBytes(48); }
                            if (header.length < 48 || !new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("VST3")
                                    || !new String(header, 8, 32, java.nio.charset.StandardCharsets.US_ASCII).equalsIgnoreCase(id))
                                throw new IllegalArgumentException("state is not a VST3 preset for the requested class ID");
                            com.bitwig.extension.api.project.mixer.PluginStateID stateId = project.getFactory().createPluginStateID("vstpreset");
                            try (java.io.OutputStream stream = project.getFactory().createPluginStateOutput(stateId)) {
                                java.nio.file.Files.copy(source.toPath(), stream);
                            }
                            preset.setPluginStateID(stateId);
                        } catch (java.io.IOException failure) { throw new IllegalStateException("plugin state import failed", failure); }
                    }
                    Device staged = preset.createDeviceInstance();
                    DeviceChain chain = targetTrack.deviceChain();
                    int before = chain.getDevices().size();
                    DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> chain.addDevice(staged));
                    if (chain.getDevices().size() != before + 1) throw new IllegalStateException("plugin insertion count mismatch");
                    yield new JSONObject().put("device", before).put("count", before + 1)
                            .put("data", readDevice(staged, before, path, routingContext(project), deviceOwners(project), atomOwnerIndex(project)));
                }
                case "device.count" -> new JSONObject().put("count",
                        targetTrack.deviceChain().getDevices().size());
                case "device.snapshot" -> new JSONObject().put("devices",
                        deviceSnapshotList(targetTrack, path, routingContext(project),
                                deviceOwners(project), atomOwnerIndex(project)));
                case "device.set_enabled" -> {
                    int index = params.optInt("index", -1);
                    if (index < 0 || !params.has("on")) {
                        throw new IllegalArgumentException("device index and enabled state are required");
                    }
                    yield setDeviceEnabledOnTrack(targetTrack, index, params.getBoolean("on"));
                }
                case "device.insert_factory" -> {
                    String name = params.optString("name", "");
                    if (params.has("id")) {
                        java.util.UUID id;
                        try { id = java.util.UUID.fromString(params.getString("id")); }
                        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("native device UUID required"); }
                        yield new JSONObject().put("count", insertNativeDeviceOnTrack(project, targetTrack, id));
                    }
                    if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains(".")) {
                        throw new IllegalArgumentException("factory device name is required");
                    }
                    yield new JSONObject().put("count",
                            insertFactoryDeviceOnTrack(project, targetTrack, name));
                }
                case "device.set_parameters" -> {
                    int device = params.optInt("device", -1);
                    JSONObject values = params.optJSONObject("params");
                    String unit = params.optString("unit", null);
                    if (device < 0 || values == null || values.isEmpty()) {
                        throw new IllegalArgumentException(
                                "device index and a non-empty params object are required");
                    }
                    yield setDeviceParametersOnTrack(targetTrack, device, values, unit);
                }
                case "device.get_parameters" -> {
                    int device = params.optInt("device", -1);
                    JSONArray names = params.optJSONArray("names");
                    String unit = params.optString("unit", null);
                    if (device < 0 || names == null || names.length() == 0) {
                        throw new IllegalArgumentException(
                                "device index and a non-empty names array are required");
                    }
                    yield getDeviceParametersOnTrack(targetTrack, device, names, unit);
                }
                case "device.rename" -> {
                    int device = params.optInt("device", -1);
                    String name = params.optString("name", "");
                    if (device < 0 || name.isBlank()) {
                        throw new IllegalArgumentException("device index and name are required");
                    }
                    yield renameDeviceOnTrack(targetTrack, device, name);
                }
                case "device.set_expanded" -> {
                    int device = params.optInt("device", -1);
                    if (device < 0 || !params.has("on")) {
                        throw new IllegalArgumentException("device index and expanded state are required");
                    }
                    yield setDeviceExpandedOnTrack(targetTrack, device, params.getBoolean("on"));
                }
                case "device.delete" -> {
                    int device = params.optInt("device", -1);
                    if (device < 0) {
                        throw new IllegalArgumentException("device index is required");
                    }
                    yield new JSONObject().put("count", deleteTrackDeviceOnTrack(targetTrack, device));
                }
                case "device.list_parameters" -> {
                    int device = params.optInt("device", -1);
                    if (device < 0) {
                        throw new IllegalArgumentException("device index is required");
                    }
                    yield new JSONObject().put("parameters",
                            listDeviceParametersOnTrack(targetTrack, device));
                }
                default -> throw new IllegalArgumentException("unsupported device method: " + method);
            };
        } finally {
            project.dispose();
        }
    }

    private static JSONObject updateTrackTarget(String field, JSONObject params) {
        ApiProject project = project();
        try {
            String path = params.optString("path", "");
            int index = params.optInt("index", Integer.MIN_VALUE);
            TrackOrTrackGroup item;
            int resolvedIndex;
            if (!path.isBlank()) {
                item = resolve(project, path);
                if (path.equals("master")) resolvedIndex = -1;
                else if (path.startsWith("fx/")) {
                    Integer fxIdx = indexIn(project.getTrackGroup().effectTracks(), item);
                    resolvedIndex = fxIdx == null ? -2 : -fxIdx - 2;
                } else {
                    Integer mainIdx = indexIn(project.getTrackGroup().mainTracks(), item);
                    resolvedIndex = mainIdx == null ? -1 : mainIdx;
                }
            } else if (index != Integer.MIN_VALUE) {
                item = trackOrGroup(project, index);
                resolvedIndex = index;
            } else {
                throw new IllegalArgumentException("track index or path is required");
            }
            applyTrackField(project, item, field, params);
            JSONObject snap = trackSnapshot(view(item), resolvedIndex);
            snap.put("is_active", view(item).isActive());
            com.bitwig.extension.api.project.routing.Destination destination =
                    view(item).destinationSelector().getDestination();
            Track destTrack = destination == null ? null : destination.getTrack();
            snap.put("destination", destTrack == null ? JSONObject.NULL : destTrack.getTitle());
            if (!path.isBlank()) {
                // Titles may themselves contain '/', so derive the new path
                // from the model tree rather than splitting the old title.
                JSONObject location = field.equals("name")
                        ? routingContext(project).get(((ApiObject) view(item)).getTarget()) : null;
                snap.put("path", location == null ? path : location.getString("path"));
            }
            return snap;
        } finally {
            project.dispose();
        }
    }

    private static JSONObject createTrackOrGroup(JSONObject params) {
        ApiProject project = project();
        try {
            String kind = params.optString("type", "instrument");
            String name = params.optString("name", "");
            String parentPath = params.optString("parent", "");
            if (name.isBlank()) {
                throw new IllegalArgumentException("track name is required");
            }
            Factory factory = project.getFactory();
            TrackOrTrackGroup created = switch (kind) {
                case "instrument" -> factory.createInstrumentTrack();
                case "audio" -> factory.createAudioTrack();
                case "hybrid" -> factory.createHybridTrack();
                case "group" -> factory.createTrackGroup();
                default -> throw new IllegalArgumentException(
                        "track type must be instrument, audio, hybrid, or group: " + kind);
            };
            created.setTitle(name);
            TrackGroup parentGroup;
            String fullPath;
            if (parentPath == null || parentPath.isBlank()) {
                parentGroup = project.getTrackGroup();
                fullPath = name;
            } else {
                TrackOrTrackGroup resolvedParent = resolve(project, parentPath);
                if (!(resolvedParent instanceof TrackGroup grp)) {
                    throw new IllegalArgumentException("parent track is not a group: " + parentPath);
                }
                parentGroup = grp;
                fullPath = parentPath + "/" + name;
            }
            parentGroup.addMainTrack(created);
            int siblingsCount = parentGroup.mainTracks().size();
            return new JSONObject()
                    .put("count", project.getTrackGroup().mainTracks().size())
                    .put("index", siblingsCount - 1)
                    .put("name", created.getTitle())
                    .put("path", fullPath)
                    .put("is_group", created instanceof TrackGroup);
        } finally {
            project.dispose();
        }
    }

    private static JSONObject setProjectMetadata(JSONObject params) {
        ApiProject project = project();
        try {
            JSONObject values = params.has("metadata") && params.get("metadata") instanceof JSONObject nested
                    ? nested : params;
            for (String key : values.keySet()) {
                String normalized = switch (key.toUpperCase(java.util.Locale.ROOT)) {
                    case "SONGWRITER" -> "WRITER";
                    default -> key.toUpperCase(java.util.Locale.ROOT);
                };
                ProjectMetadata metaKey;
                try {
                    metaKey = ProjectMetadata.valueOf(normalized);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException("unknown metadata key: " + key);
                }
                Object val = values.get(key);
                project.setMetadataText(metaKey, val == JSONObject.NULL || val == null ? "" : val.toString());
            }
            JSONObject out = new JSONObject();
            for (ProjectMetadata key : ProjectMetadata.values()) {
                String value = project.getMetadataText(key);
                if (value != null && !value.isBlank()) {
                    out.put(key.name().toLowerCase(java.util.Locale.ROOT), value);
                }
            }
            return out;
        } finally {
            project.dispose();
        }
    }

    private static JSONObject createCueMarker(JSONObject params) {
        ApiProject project = project();
        try {
            double beat = params.optDouble("beat", params.optDouble("time", Double.NaN));
            String name = params.optString("name", "");
            if (!Double.isFinite(beat) || beat < 0) {
                throw new IllegalArgumentException("cue marker beat must be nonnegative");
            }
            if (name.isBlank()) {
                throw new IllegalArgumentException("cue marker name is required");
            }
            CueMarker marker = project.getFactory().createCueMarker(beat);
            marker.setTitle(name);
            Color color = parseColor(params);
            if (color != null) {
                marker.setColor(color);
            }
            DirectAccess.runInUndo(project.getCueMarkers(),
                    () -> project.getCueMarkers().addEvent(marker));
            Color actualColor = marker.getColor();
            return new JSONObject()
                    .put("count", project.getCueMarkers().getEvents().size())
                    .put("time", marker.getTime())
                    .put("name", marker.getTitle())
                    .put("color", actualColor == null ? JSONObject.NULL : actualColor.toHex());
        } finally {
            project.dispose();
        }
    }

    private static int sceneIndex(JSONObject params, String key, int limit, boolean append) {
        Object value = params.get(key);
        if (!(value instanceof Integer n) || n < 0 || n >= limit + (append ? 1 : 0))
            throw new IllegalArgumentException("scene " + key + " out of range");
        return (Integer) value;
    }

    private static JSONObject sceneSnapshot(ApiProject project, int index) {
        com.bitwig.extension.api.project.Scene scene = project.getScene(index);
        Color color = scene.getColor();
        Object raw = ((ApiObject) scene).getTarget();
        Object comment = raw instanceof bitwig.obf.dYM stored ? stored.iUU() : null; // the scene comment
        return new JSONObject().put("index", index).put("name", scene.getTitle())
                .put("comment", comment == null ? "" : comment)
                .put("color", color == null ? JSONObject.NULL : color.toHex());
    }

    private static JSONObject editSceneBank(String method, JSONObject params) {
        ApiProject project = project();
        try {
            int before = sceneCount();
            GJy root = ((CmH) project.getTarget()).svk(); // the effect track group, owner of the scene bank
            if (method.equals("scene.snapshot")) {
                JSONArray scenes = new JSONArray();
                for (int i = 0; i < before; i++) scenes.put(sceneSnapshot(project, i));
                return new JSONObject().put("count", before).put("scenes", scenes);
            }
            int index = params.has("index") ? sceneIndex(params, "index", before, method.equals("scene.insert")) : before;
            if (method.equals("scene.insert")) {
                if (before >= 4096) throw new IllegalArgumentException("scene bank limit exceeded");
                root.sCA(index, (bitwig.obf.Eoh) null); // insert an empty scene
                if (sceneCount() != before + 1) throw new IllegalStateException("scene insertion count mismatch");
            } else if (method.equals("scene.delete")) {
                index = sceneIndex(params, "index", before, false);
                root.beI(new int[]{index}); // delete scenes
                if (sceneCount() != before - 1) throw new IllegalStateException("scene deletion count mismatch");
                return new JSONObject().put("count", sceneCount());
            } else if (method.equals("scene.move")) {
                index = sceneIndex(params, "index", before, false);
                int destination = sceneIndex(params, "destination", before, false);
                if (index != destination)
                    root.beI(new int[]{index}, destination > index ? destination + 1 : destination); // move scenes
                if (sceneCount() != before) throw new IllegalStateException("scene reorder changed bank size");
                index = destination;
            } else throw new IllegalArgumentException("unsupported scene operation");
            return new JSONObject().put("count", sceneCount()).put("index", index).put("data", sceneSnapshot(project, index));
        } finally { project.dispose(); }
    }

    private static JSONObject updateScene(JSONObject params) {
        ApiProject project = project();
        try {
            int index = sceneIndex(params, "index", sceneCount(), false);
            for (String key : params.keySet()) {
                if (!java.util.Set.of("index", "name", "comment", "color", "r", "g", "b", "a").contains(key))
                    throw new IllegalArgumentException("unknown scene field: " + key);
                if ((key.equals("name") || key.equals("comment") || key.equals("color")) && !(params.get(key) instanceof String))
                    throw new IllegalArgumentException(key + " must be a string");
            }
            boolean rgb = params.has("r") || params.has("g") || params.has("b") || params.has("a");
            if (rgb && (params.has("color") || !params.has("r") || !params.has("g") || !params.has("b")))
                throw new IllegalArgumentException("choose hex color or complete RGB components");
            for (String key : java.util.List.of("r", "g", "b", "a"))
                if (params.has(key) && (!(params.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 1))
                    throw new IllegalArgumentException("scene color components must be finite numbers within 0..1");
            Color color = parseColor(params);
            com.bitwig.extension.api.project.Scene scene = project.getScene(index);
            DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                if (params.has("name")) scene.setTitle(params.getString("name"));
                if (params.has("comment")) {
                    Object rawScene = ((ApiObject) scene).getTarget();
                    modifyModel((ApiObject) scene, () -> ((bitwig.obf.dYM) rawScene).wGt(params.getString("comment"))); // the scene comment
                }
                if (color != null) scene.setColor(color);
            });
            return sceneSnapshot(project, index);
        } finally { project.dispose(); }
    }

    /** The open project's current selection context, which native edit entries take. */
    private static Object selectionContext(ApiProject project) {
        return ((CmH) project.getTarget()).PqZ().DYQ().nhI().gn_();
    }

    /** Bitwig's make-unique operation on a clip or event whose content a clone may share. */
    private static void makeUnique(Object clip) {
        ((bitwig.obf.ogn) clip).el();
    }

    private static double finiteField(JSONObject value, String key, double fallback) {
        double number = value.has(key) ? value.getDouble(key) : fallback;
        if (!Double.isFinite(number)) throw new IllegalArgumentException(key + " must be finite");
        return number;
    }

    private static void noClipOverlap(ClipTimeline timeline, Clip ignored, double start, double duration) {
        if (start < 0 || duration <= 0 || !Double.isFinite(start + duration))
            throw new IllegalArgumentException("clip start must be nonnegative and duration positive");
        for (Object member : timeline.getEvents()) {
            Clip other = (Clip) member;
            if (ignored != null && ((ApiObject) other).getTarget() == ((ApiObject) ignored).getTarget()) continue;
            if (start < other.getTime() + other.getDuration() - 1e-6
                    && start + duration > other.getTime() + 1e-6)
                throw new IllegalArgumentException("clip edit overlaps existing material");
        }
    }

    private static JSONObject editClip(String method, JSONObject params) {
        ApiProject project = project();
        try {
            Track track = resolveTrack(project, params);
            ClipTimeline timeline = track.clipTimeline();
            if (method.equals("clip.import_audio")) {
                double start = finiteField(params, "start", 0);
                com.bitwig.extension.api.project.timeline.AudioNote event = stageAudioEvent(project, params, start);
                double duration = event.getDuration();
                noClipOverlap(timeline, null, start, duration);
                AudioClip clip = project.getFactory().createAudioClip(false);
                clip.setTime(start);
                clip.setDuration(duration);
                clip.setTitle(params.optString("name", new java.io.File(params.getString("file")).getName()));
                clip.getContent().getEventTimeline().addEvent(event);
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> timeline.addEvent(clip));
                int index = -1;
                java.util.List<?> members = timeline.getEvents();
                for (int j = 0; j < members.size(); j++)
                    if (((ApiObject) members.get(j)).getTarget() == ((ApiObject) clip).getTarget()) index = j;
                return new JSONObject().put("clip", index).put("data", readClip(clip));
            }
            boolean launcher = params.has("slot");
            int index = params.getInt(launcher ? "slot" : "clip");
            java.util.List<?> members = timeline.getEvents();
            Clip clip;
            if (launcher) {
                com.bitwig.extension.api.project.timeline.ClipSlot slot = launcherSlot(project, track, index, false);
                clip = slot == null ? null : slot.getClip();
                if (clip == null) throw new IllegalArgumentException("launcher slot is empty");
            } else {
                if (index < 0 || index >= members.size()) throw new IllegalArgumentException("clip index out of range");
                clip = (Clip) members.get(index);
            }
            if (method.startsWith("audio_event.")) return editAudioEvent(project, clip, params, method);
            if (method.startsWith("note.")) return editNoteEvent(project, clip, params, method);
            if (method.equals("clip.replace_notes")) {
                if (!(clip instanceof NoteClip)) throw new IllegalArgumentException("MIDI replacement requires a note clip");
                JSONArray notes = params.getJSONArray("notes");
                java.util.List<InstrumentNote> staged = new java.util.ArrayList<>();
                for (int i = 0; i < notes.length(); i++) staged.add(stageInstrumentNote(project.getFactory(), notes.getJSONObject(i)));
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    // A split or native copy may still reference shared content.
                    makeUnique(((ApiObject) clip).getTarget());
                    EventTimeline content = ((NoteClip) clip).getContent().getEventTimeline();
                    double low = 0, high = 1;
                    for (Object member : content.getEvents()) {
                        InstrumentNote note = (InstrumentNote) member;
                        low = Math.min(low, note.getTime());
                        high = Math.max(high, note.getTime() + note.getDuration() + 1);
                    }
                    content.clearTime(low, high);
                    for (InstrumentNote note : staged) content.addEvent(note);
                });
                return readClip(clip);
            }
            if (method.equals("clip.copy")) {
                double start = finiteField(params, "start", clip.getTime() + clip.getDuration());
                Track destination = params.has("destination") ? resolveTrack(project, params.getJSONObject("destination")) : track;
                ClipTimeline output = destination.clipTimeline();
                noClipOverlap(output, null, start, clip.getDuration());
                {
                    bitwig.obf.TKq raw = ((bitwig.obf.TKq) ((ApiObject) clip).getTarget()).waK(); // a clone of the clip
                    com.bitwig.ramona.core.m1k.beI(raw); // prepare the clone for insertion
                    Clip cloned = clipEventWrapper(clip instanceof AudioClip, raw);
                    cloned.setTime(start);
                    Object context = selectionContext(project);
                    Object originalRaw = ((ApiObject) clip).getTarget();
                    double fadeTime = clip instanceof AudioClip ? ((AudioClip) clip).getFadeInTime() : 0;
                    double fadeGain = clip instanceof AudioClip ? ((bitwig.obf.oFQ) originalRaw).Bqk() : 1; // fade gain
                    bitwig.obf.fta fadeCurve = clip instanceof AudioClip ? ((bitwig.obf.oFQ) originalRaw).Anf() : null; // fade curve
                    DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                        output.addEvent(cloned);
                        // Native clone references can share content. Unlink using
                        // Bitwig's own make-unique operation after attaching it.
                        makeUnique(raw);
                        ((bitwig.obf.VfZ) raw).beI((bitwig.obf.snd) ((ApiObject) output).getTarget(), (bitwig.obf.ee) context); // finish the insertion
                        if (cloned instanceof AudioClip) {
                            // Insertion initializes automatic fade-in defaults;
                            // retain the source's explicit time, gain and curve.
                            ((AudioClip) cloned).setFadeInTime(fadeTime);
                            ((bitwig.obf.oFQ) raw).af(fadeGain);
                            ((bitwig.obf.oFQ) raw).unz(fadeCurve);
                        }
                    });
                    return clipLocation(output, raw);
                }
            }
            if (method.equals("clip.split")) {
                if (launcher) throw new IllegalArgumentException("split requires an arranger clip");
                double beat = finiteField(params, "beat", Double.NaN);
                if (beat <= clip.getTime() + 1e-6 || beat >= clip.getTime() + clip.getDuration() - 1e-6)
                    throw new IllegalArgumentException("split must be strictly inside the clip");
                {
                    Object context = selectionContext(project);
                    bitwig.obf.snd rawTimeline = (bitwig.obf.snd) ((ApiObject) timeline).getTarget();
                    // The native Split Event entry point owns its transaction.
                    rawTimeline.beI((bitwig.obf.UdX) ((ApiObject) clip).getTarget(), beat, (bitwig.obf.rBG) context, (bitwig.obf.Bpj) null);
                    JSONArray pieces = new JSONArray();
                    for (Object member : timeline.getEvents()) {
                        Clip piece = (Clip) member;
                        if (((ApiObject) piece).getTarget() == ((ApiObject) clip).getTarget()
                                || Math.abs(piece.getTime() - beat) < 1e-6)
                            pieces.put(clipLocation(timeline, ((ApiObject) piece).getTarget()));
                    }
                    if (pieces.length() != 2) throw new IllegalStateException("split did not produce two clips");
                    return new JSONObject().put("clips", pieces);
                }
            }
            if (method.equals("clip.delete")) {
                if (launcher) {
                    DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(),
                            () -> ((bitwig.obf.vMh) ((ApiObject) launcherSlot(project, track, index, false)).getTarget()).Gef((bitwig.obf.BVS) null)); // empty the slot
                    return launcherSnapshot(launcherSlot(project, track, index, false), index);
                }
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(),
                        () -> timeline.clearTime(clip.getTime(), clip.getTime() + clip.getDuration()));
                return new JSONObject().put("deleted", index).put("count", timeline.getEvents().size());
            }
            JSONObject changes = params.getJSONObject("changes");
            java.util.Set<String> allowed = java.util.Set.of("start", "duration", "name", "muted", "loop",
                    "loop_start", "loop_duration", "play_start_offset", "color", "fade_in", "fade_out");
            for (String key : changes.keySet())
                if (!allowed.contains(key) && !LAUNCH_SETTINGS.contains(key)) throw new IllegalArgumentException("unknown clip field: " + key);
            // Arranger clips keep launch settings too (they apply once the clip is in a launcher slot).
            Runnable settings = launchSettingsEdit(clip, changes);
            for (String key : new String[]{"muted", "loop"})
                if (changes.has(key) && !(changes.get(key) instanceof Boolean))
                    throw new IllegalArgumentException(key + " must be a boolean");
            if (changes.has("name") && !(changes.get("name") instanceof String))
                throw new IllegalArgumentException("clip name must be a string");
            double start = finiteField(changes, "start", clip.getTime());
            double duration = finiteField(changes, "duration", clip.getDuration());
            double loopStart = finiteField(changes, "loop_start", clip.getLoopStart());
            double loopDuration = finiteField(changes, "loop_duration", clip.getLoopDuration());
            double offset = finiteField(changes, "play_start_offset", clip.getPlayStartOffset());
            if (loopDuration <= 0) throw new IllegalArgumentException("loop duration must be positive");
            boolean audio = clip instanceof AudioClip;
            if (!audio && (changes.has("fade_in") || changes.has("fade_out")))
                throw new IllegalArgumentException("fades require an audio clip");
            double fadeIn = finiteField(changes, "fade_in", audio ? ((AudioClip) clip).getFadeInTime() : 0);
            double fadeOut = finiteField(changes, "fade_out", audio ? ((AudioClip) clip).getFadeOutTime() : 0);
            // Bitwig reports some stored clip fades as negative times; accept what it reports.
            if (Math.abs(fadeIn) > duration || Math.abs(fadeOut) > duration)
                throw new IllegalArgumentException("fades must fit within the clip duration");
            Color color = changes.has("color") ? parseColor(changes) : null;
            if (launcher && changes.has("start")) throw new IllegalArgumentException("launcher clips have no arranger start");
            if (!launcher) noClipOverlap(timeline, clip, start, duration);
            boolean reposition = !launcher && (start != clip.getTime() || duration != clip.getDuration());
            DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                if (reposition) {
                    // Detach the clip from the arranger timeline (without deleting it).
                    ((bitwig.obf.tRn) ((ApiObject) timeline).getTarget()).beI((bitwig.obf.TKq) ((ApiObject) clip).getTarget(), false);
                    clip.setTime(start);
                    clip.setDuration(duration);
                }
                if (changes.has("name")) clip.setTitle(changes.getString("name"));
                if (changes.has("color")) clip.setColor(color);
                if (changes.has("muted")) clip.setIsMuted(changes.getBoolean("muted"));
                if (changes.has("loop")) clip.setIsLoopEnabled(changes.getBoolean("loop"));
                if (changes.has("loop_start")) clip.setLoopStart(loopStart);
                if (changes.has("loop_duration")) clip.setLoopDuration(loopDuration);
                if (changes.has("play_start_offset")) clip.setPlayStartOffset(offset);
                if (launcher && changes.has("duration")) clip.setDuration(duration);
                if (changes.has("fade_in")) ((AudioClip) clip).setFadeInTime(fadeIn);
                if (changes.has("fade_out")) ((AudioClip) clip).setFadeOutTime(fadeOut);
                settings.run();
                if (reposition) timeline.addEvent(clip);
            });
            return readClip(clip);
        } finally { project.dispose(); }
    }

    private static JSONObject clipLocation(ClipTimeline timeline, Object raw) {
        java.util.List<?> clips = timeline.getEvents();
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = (Clip) clips.get(i);
            if (((ApiObject) clip).getTarget() == raw)
                return new JSONObject().put("clip", i).put("data", readClip(clip));
        }
        throw new IllegalStateException("clip is not attached to requested timeline");
    }

    private static JSONObject editableNoteData(InstrumentNote note) {
        JSONObject read = readInstrumentNote(note), data = new JSONObject();
        for (String name : NOTE_CONDITIONS) data.put(name, read.get(name));
        for (String name : new String[]{"key", "channel", "start", "duration", "on_velocity", "off_velocity", "velocity_spread", "content_start", "muted"})
            data.put(name, read.get(name));
        JSONObject curves = new JSONObject();
        for (Object item : read.getJSONArray("expressions")) {
            JSONObject expression = (JSONObject) item;
            JSONArray points = new JSONArray();
            for (Object value : expression.getJSONArray("points")) {
                JSONObject point = (JSONObject) value;
                points.put(new JSONArray().put(point.getDouble("time")).put(point.getDouble("value"))
                        .put(point.optDouble("curvature", 0)).put(point.optString("interpolation", "linear")));
            }
            curves.put(expression.getString("expression"), new JSONObject()
                    .put("unit", expression.getString("unit").equals("semitones") ? "SEMITONES" : "RATIO").put("points", points));
        }
        return data.put("expressions", curves);
    }

    private static JSONObject noteLocation(EventTimeline timeline, Object raw) {
        java.util.List<?> notes = timeline.getEvents();
        for (int i = 0; i < notes.size(); i++) if (((ApiObject) notes.get(i)).getTarget() == raw)
            return new JSONObject().put("note", i).put("data", readInstrumentNote((InstrumentNote) notes.get(i)));
        throw new IllegalStateException("note is not attached to requested content");
    }

    private static JSONObject editNoteEvent(ApiProject project, Clip clip, JSONObject params, String method) {
        if (!(clip instanceof NoteClip)) throw new IllegalArgumentException("note edit requires a MIDI clip");
        EventTimeline current = ((NoteClip) clip).getContent().getEventTimeline();
        int index = method.equals("note.import") ? -1 : params.getInt("note");
        if (!method.equals("note.import") && (index < 0 || index >= current.getEvents().size()))
            throw new IllegalArgumentException("note index out of range");
        InstrumentNote staged = null;
        if (!method.equals("note.delete")) {
            JSONObject data = method.equals("note.import") ? params.getJSONObject("data")
                    : editableNoteData((InstrumentNote) current.getEvents().get(index));
            if (method.equals("note.update")) {
                JSONObject changes = params.getJSONObject("changes");
                for (String name : changes.keySet()) {
                    if (name.equals("expressions")) {
                        JSONObject curves = data.getJSONObject("expressions");
                        JSONObject updates = changes.getJSONObject(name);
                        for (String curve : updates.keySet()) curves.put(curve, updates.get(curve));
                    } else data.put(name, changes.get(name));
                }
            }
            staged = stageInstrumentNote(project.getFactory(), data);
        }
        final InstrumentNote replacement = staged;
        final JSONObject[] result = new JSONObject[1];
        DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
            makeUnique(((ApiObject) clip).getTarget());
            EventTimeline timeline = ((NoteClip) clip).getContent().getEventTimeline();
            if (!method.equals("note.import")) {
                Object raw = ((ApiObject) timeline.getEvents().get(index)).getTarget();
                Object lane = modelOwner(raw); // the note's key lane
                ((bitwig.obf.UGI) lane).beI((bitwig.obf.gZu) raw, false); // remove the note
            }
            if (replacement != null) {
                timeline.addEvent(replacement);
                result[0] = noteLocation(timeline, ((ApiObject) replacement).getTarget());
            } else result[0] = new JSONObject().put("deleted", index).put("count", timeline.getEvents().size());
        });
        return result[0];
    }

    private static JSONObject editAudioEvent(ApiProject project, Clip clip, JSONObject params, String method) {
        if (!(clip instanceof AudioClip)) throw new IllegalArgumentException("audio event requires an audio clip");
        if (method.equals("audio_event.import")) {
            double start = finiteField(params, "start", 0);
            double globalStart = clip.getTime() + start - clip.getPlayStartOffset();
            if (start < 0 || globalStart < 0) throw new IllegalArgumentException("invalid audio event position");
            com.bitwig.extension.api.project.timeline.AudioNote staged = stageAudioEvent(project, params, globalStart);
            staged.setTime(start);
            EventTimeline current = ((AudioClip) clip).getContent().getEventTimeline();
            for (Object member : current.getEvents()) {
                com.bitwig.extension.api.project.timeline.AudioNote other = (com.bitwig.extension.api.project.timeline.AudioNote) member;
                if (start < other.getTime() + other.getDuration() - 1e-6 && start + staged.getDuration() > other.getTime() + 1e-6)
                    throw new IllegalArgumentException("audio event import overlaps existing material");
            }
            final JSONObject[] result = new JSONObject[1];
            DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                makeUnique(((ApiObject) clip).getTarget());
                EventTimeline timeline = ((AudioClip) clip).getContent().getEventTimeline();
                timeline.addEvent(staged);
                result[0] = audioEventLocation(timeline, ((ApiObject) staged).getTarget());
            });
            return result[0];
        }
        int index = params.getInt("event");
        EventTimeline content = ((AudioClip) clip).getContent().getEventTimeline();
        java.util.List<?> events = content.getEvents();
        if (index < 0 || index >= events.size()) throw new IllegalArgumentException("audio event index out of range");
        com.bitwig.extension.api.project.timeline.AudioNote existing = (com.bitwig.extension.api.project.timeline.AudioNote) events.get(index);
        JSONObject changes = method.equals("audio_event.delete") ? new JSONObject() : params.getJSONObject("changes");
        java.util.Set<String> allowed = java.util.Set.of("start", "duration", "source_offset", "gain_db", "muted", "reverse", "fade_in", "fade_out",
                "warp_mode", "warp_markers", "content_offset", "transpose", "formant", "pan",
                "preserve_formants", "preview_onsets", "preserve_transients_mode", "transient_rate", "warp_grain_size", "warp_tail_mode", "formant_envelope_resolution", "expressions",
                "chance", "chance_enabled", "occurrence", "occurrence_enabled", "recurrence", "recurrence_enabled", "repeat_count", "repeat_curve", "repeat_enabled");
        for (String key : changes.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("unknown audio event field: " + key);
        for (String key : new String[]{"muted", "reverse", "preserve_formants", "preview_onsets"})
            if (changes.has(key) && !(changes.get(key) instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean");
        double start = finiteField(changes, "start", existing.getTime());
        double duration = finiteField(changes, "duration", existing.getDuration());
        double fadeIn = finiteField(changes, "fade_in", ((bitwig.obf.poA) ((ApiObject) existing).getTarget()).dM_());
        double fadeOut = finiteField(changes, "fade_out", ((bitwig.obf.poA) ((ApiObject) existing).getTarget()).unz());
        double offset = finiteField(changes, "source_offset", 0), gain = finiteField(changes, "gain_db", 0);
        com.bitwig.extension.api.project.timeline.WarpMode mode = changes.has("warp_mode")
                ? com.bitwig.extension.api.project.timeline.WarpMode.valueOf(changes.getString("warp_mode")) : existing.getWarpMode();
        double contentOffset = finiteField(changes, "content_offset", 0);
        double resolution = finiteField(changes, "formant_envelope_resolution", existing.getFormantEnvelopeResolution());
        if (resolution != Math.rint(resolution) || resolution < 8 || resolution > 512)
            throw new IllegalArgumentException("formant envelope resolution must be an integer in 8..512");
        if (changes.has("content_offset") && changes.has("source_offset"))
            throw new IllegalArgumentException("address the source by either beats or seconds");
        double transpose = finiteField(changes, "transpose", 0), formant = finiteField(changes, "formant", 0), pan = finiteField(changes, "pan", 0);
        if (contentOffset < 0 || pan < -1 || pan > 1 || transpose < -96 || transpose > 96 || formant < -96 || formant > 96)
            throw new IllegalArgumentException("invalid audio processing value");
        if (changes.has("transpose") && (mode.name().equals("RAW") || mode.name().equals("REPITCH")))
            throw new IllegalArgumentException("transpose requires a pitch-processing warp mode");
        if (changes.has("content_offset") && mode.name().equals("RAW")) throw new IllegalArgumentException("RAW uses source_offset seconds");
        if (changes.has("warp_mode") && !mode.name().equals("RAW") && existing.getWarpMode().name().equals("RAW") && !changes.has("warp_markers"))
            throw new IllegalArgumentException("switching from RAW requires explicit warp markers");
        com.bitwig.extension.api.project.timeline.WarpMarkers warps = null;
        if (changes.has("warp_markers")) {
            JSONArray points = changes.getJSONArray("warp_markers");
            if (points.length() < 2) throw new IllegalArgumentException("at least two warp markers required");
            warps = project.getFactory().createWarpEvents();
            double lastBeat = -1, lastSecond = -1;
            for (int j = 0; j < points.length(); j++) {
                JSONArray point = points.getJSONArray(j);
                if (point.length() != 2) throw new IllegalArgumentException("warp markers need beat and source seconds");
                double beat = point.getDouble(0), second = point.getDouble(1);
                if (!Double.isFinite(beat) || !Double.isFinite(second) || beat < 0 || second < 0 || beat <= lastBeat || second <= lastSecond
                        || second > existing.getAudioFileDurationInSeconds()) throw new IllegalArgumentException("warp markers must increase within the source file");
                warps.addWarpEvent(beat, second); lastBeat = beat; lastSecond = second;
            }
            if (!warps.areWarpMarkersValid()) throw new IllegalArgumentException("invalid native warp mapping");
        }
        com.bitwig.extension.api.project.timeline.WarpMarkers stagedWarps = warps;
        JSONArray mapping = storedWarpMarkers(((ApiObject) existing).getTarget());
        if (warps != null) {
            mapping = new JSONArray();
            for (int j = 0; j < warps.getWarpMarkerCount(); j++) {
                com.bitwig.extension.api.project.timeline.WarpMarker marker = warps.getWarpMarkerAt(j);
                mapping.put(new JSONObject().put("time", marker.getBeatTime()).put("content_time", marker.getSeconds()));
            }
        }
        if (changes.has("content_offset")) {
            int count = mapping.length();
            if (count < 2 || contentOffset < mapping.getJSONObject(0).getDouble("time")
                    || contentOffset >= mapping.getJSONObject(count - 1).getDouble("time"))
                throw new IllegalArgumentException("content offset outside warp mapping");
        }
        com.bitwig.extension.api.project.timeline.PreserveTransientsMode transients = changes.has("preserve_transients_mode")
                ? com.bitwig.extension.api.project.timeline.PreserveTransientsMode.valueOf(changes.getString("preserve_transients_mode")) : null;
        com.bitwig.extension.api.project.timeline.TransientRate rate = changes.has("transient_rate")
                ? com.bitwig.extension.api.project.timeline.TransientRate.valueOf(changes.getString("transient_rate")) : null;
        com.bitwig.extension.api.project.timeline.WarpGrainSize grain = changes.has("warp_grain_size")
                ? com.bitwig.extension.api.project.timeline.WarpGrainSize.valueOf(changes.getString("warp_grain_size")) : null;
        com.bitwig.extension.api.project.timeline.WarpTailMode tail = changes.has("warp_tail_mode")
                ? com.bitwig.extension.api.project.timeline.WarpTailMode.valueOf(changes.getString("warp_tail_mode")) : null;
        // Events may start before their clip (content slid left); Bitwig stores negative starts.
        if (duration <= 0 || !Double.isFinite(start + duration) || fadeIn < 0 || fadeOut < 0 || fadeIn > duration || fadeOut > duration)
            throw new IllegalArgumentException("invalid audio event range/fades");
        boolean reversed = ((bitwig.obf.poA) ((ApiObject) existing).getTarget()).p1J().Anf(); // the reverse setting
        if (changes.has("source_offset") && (offset < 0 || offset > existing.getAudioFileDurationInSeconds()
                || (offset == existing.getAudioFileDurationInSeconds() && (!reversed || mode.name().equals("RAW")))))
            throw new IllegalArgumentException("source offset outside audio file");
        if (reversed && !mode.name().equals("RAW") && (changes.has("warp_mode") || changes.has("warp_markers")))
            throw new IllegalArgumentException("set reversed audio forward before replacing its warp mapping");
        double resolvedOffset = offset;
        if (changes.has("source_offset") && !mode.name().equals("RAW")) {
            boolean found = false;
            for (int j = 1; j < mapping.length(); j++) {
                JSONObject left = mapping.getJSONObject(j - 1), right = mapping.getJSONObject(j);
                double from = left.getDouble("content_time"), to = right.getDouble("content_time");
                if (from != to && offset >= Math.min(from, to) && offset <= Math.max(from, to)) {
                    resolvedOffset = left.getDouble("time") + (offset - from)
                            * (right.getDouble("time") - left.getDouble("time")) / (to - from);
                    if (resolvedOffset >= mapping.getJSONObject(mapping.length() - 1).getDouble("time")) break;
                    found = true; break;
                }
            }
            if (!found) throw new IllegalArgumentException("source offset outside warp mapping");
        }
        final double sourceContentOffset = resolvedOffset;
        if (changes.has("source_offset") && (changes.has("reverse") || (mode.name().equals("RAW") && reversed)))
            throw new IllegalArgumentException("change source offset and reverse separately; RAW offsets require forward audio");
        if (changes.has("start") || changes.has("duration")) for (Object member : events) {
            com.bitwig.extension.api.project.timeline.AudioNote other = (com.bitwig.extension.api.project.timeline.AudioNote) member;
            if (((ApiObject) other).getTarget() != ((ApiObject) existing).getTarget()
                    && start < other.getTime() + other.getDuration() - 1e-6 && start + duration > other.getTime() + 1e-6)
                throw new IllegalArgumentException("audio event edit overlaps existing material");
        }
        com.bitwig.extension.api.project.timeline.AudioNote stagedConditions = project.getFactory().createAudioNote(
                0, 1, project.getFactory().createSampleReferenceForLocalFile(existing.getAudioFile()));
        applyNoteConditions(stagedConditions, changes);
        if (changes.has("expressions")) {
            JSONObject curves = changes.getJSONObject("expressions");
            for (String field : new String[]{"gain", "pan", "transpose", "formant"}) {
                if (curves.has(field) && changes.has(field.equals("gain") ? "gain_db" : field))
                    throw new IllegalArgumentException("expression and constant both supplied for " + field);
            }
            // A transpose curve stays stored (inactive) on RAW/REPITCH events, as Bitwig keeps it.
            writeExpressions(project.getFactory(), stagedConditions, curves, true);
        }
        final JSONObject[] result = new JSONObject[1];
        DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
            makeUnique(((ApiObject) clip).getTarget());
            EventTimeline timeline = ((AudioClip) clip).getContent().getEventTimeline();
            com.bitwig.extension.api.project.timeline.AudioNote event = (com.bitwig.extension.api.project.timeline.AudioNote) timeline.getEvents().get(index);
            bitwig.obf.poA raw = (bitwig.obf.poA) ((ApiObject) event).getTarget();
            boolean reposition = changes.has("start") || changes.has("duration");
            {
                if (method.equals("audio_event.delete") || reposition) {
                    // API audio content wraps the polyphonic bank; its events
                    // actually belong to the (channel=0,key=0) native lane.
                    bitwig.obf.lgA bank = (bitwig.obf.lgA) ((ApiObject) timeline).getTarget();
                    Object lane = bank.byf(0, 0, false); // the lane for a channel and key, not created
                    ((bitwig.obf.UGI) lane).beI((bitwig.obf.gZu) raw, false); // remove the event
                }
                if (method.equals("audio_event.delete")) {
                    result[0] = new JSONObject().put("deleted", index).put("count", timeline.getEvents().size());
                    return;
                }
                if (reposition) { event.setTime(start); event.setDuration(duration); timeline.addEvent(event); }
                if (stagedWarps != null) event.setWarpEvents(stagedWarps);
                if (changes.has("warp_mode")) event.setWarpMode(mode);
                if (changes.has("content_offset")) raw.CKj(contentOffset); // the content offset
                if (changes.has("source_offset")) raw.CKj(sourceContentOffset);
                if (changes.has("gain_db")) event.getGain().setValue(gain, Unit.DECIBEL);
                if (changes.has("transpose")) event.getTranspose().setValue(transpose, Unit.SEMITONES);
                if (changes.has("formant")) event.getFormant().setValue(formant, Unit.SEMITONES);
                if (changes.has("pan")) event.getPan().setValue(pan, Unit.RATIO);
                if (changes.has("preserve_formants")) event.setPreserveFormants(changes.getBoolean("preserve_formants"));
                if (changes.has("preview_onsets")) event.setPreviewOnsets(changes.getBoolean("preview_onsets"));
                if (changes.has("formant_envelope_resolution")) event.setFormantEnvelopeResolution((int) resolution);
                if (transients != null) event.setPreserveTransientsMode(transients);
                if (rate != null) event.setTransientRate(rate);
                if (grain != null) event.setWarpGrainSize(grain);
                if (tail != null) event.setWarpTailMode(tail);
                if (changes.has("muted")) event.setIsMuted(changes.getBoolean("muted"));
                if (changes.has("fade_in")) event.setFadeInTime(fadeIn);
                if (changes.has("fade_out")) event.setFadeOutTime(fadeOut);
                if (changes.has("reverse") && changes.getBoolean("reverse") != reversed) {
                    raw.beI((bitwig.obf.Pws) selectionContext(project)); // reverse the event
                }
                applyNoteConditions(event, changes);
                if (changes.has("expressions")) writeExpressions(project.getFactory(), event, changes.getJSONObject("expressions"), true);
                result[0] = audioEventLocation(timeline, raw);
            }
        });
        return result[0];
    }

    private static com.bitwig.extension.api.project.timeline.ClipSlot launcherSlot(ApiProject project, Track track, int index, boolean create) {
        int scenes = ((CmH) project.getTarget()).svk().ce_(); // the project's scene count
        if (index < 0 || index >= scenes) throw new IllegalArgumentException("slot index outside project scene bank");
        int slots = ((bitwig.obf.PiT) ((ApiObject) track).getTarget()).ce_(); // the track's slot count
        if (!create && index >= slots) return null;
        return track.clipSlot(index);
    }

    private static JSONObject launcherSnapshot(com.bitwig.extension.api.project.timeline.ClipSlot slot, int index) {
        Clip clip = slot == null ? null : slot.getClip();
        return new JSONObject().put("slot", index).put("has_stop", slot != null && slot.hasStopButton())
                .put("clip", clip == null ? JSONObject.NULL : readClip(clip))
                .put("playing", clip != null && ((bitwig.obf.ogn) ((ApiObject) clip).getTarget()).zzi())
                .put("queued", clip != null && ((bitwig.obf.ogn) ((ApiObject) clip).getTarget()).UIe());
    }

    /** A Bitwig enum constant by its stored name (enum names are readable and stable across builds). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object namedNativeEnum(Class<?> type, String name) {
        try { return Enum.valueOf((Class) type, name); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("unsupported " + type.getSimpleName() + " value: " + name, failure); }
    }

    private static JSONObject launchSettings(Clip clip) {
        bitwig.obf.ogn raw = (bitwig.obf.ogn) ((ApiObject) clip).getTarget();
        return new JSONObject().put("launch_quantization", ((Enum<?>) (Object) raw.sLx()).name())
                .put("launch_mode", ((Enum<?>) (Object) raw.jKl()).name())
                .put("next_action", ((Enum<?>) (Object) raw.EzB()).name())
                .put("next_action_time", raw.xw3())
                .put("use_loop_start_as_quantization_reference", clip.useLoopStartAsQuantizationReference());
    }

    private static final java.util.Set<String> LAUNCH_SETTINGS = java.util.Set.of("launch_quantization", "launch_mode",
            "next_action", "next_action_time", "use_loop_start_as_quantization_reference");

    /** Validates the launch settings in changes; the returned edit runs inside the caller's undo transaction. */
    private static Runnable launchSettingsEdit(Clip clip, JSONObject changes) {
        String reference = "use_loop_start_as_quantization_reference";
        if (changes.has(reference) && !(changes.get(reference) instanceof Boolean)) throw new IllegalArgumentException(reference + " must be boolean");
        Object quantization = changes.has("launch_quantization") ? namedNativeEnum(bitwig.obf.lhA.class, changes.getString("launch_quantization")) : null;
        Object mode = changes.has("launch_mode") ? namedNativeEnum(bitwig.obf.MpD.class, changes.getString("launch_mode")) : null;
        Object action = changes.has("next_action") ? namedNativeEnum(bitwig.obf.bX.class, changes.getString("next_action")) : null;
        double time = finiteField(changes, "next_action_time", 0);
        if (time < 0) throw new IllegalArgumentException("next action time must be nonnegative");
        bitwig.obf.ogn raw = (bitwig.obf.ogn) ((ApiObject) clip).getTarget();
        return () -> {
            if (quantization != null) raw.beI((bitwig.obf.lhA) quantization);
            if (mode != null) raw.Gef((bitwig.obf.MpD) mode);
            if (action != null) raw.beI((bitwig.obf.bX) action);
            if (changes.has("next_action_time")) raw.Ml2(time);
            if (changes.has(reference)) clip.setUseLoopStartAsQuantizationReference(changes.getBoolean(reference));
        };
    }

    private static JSONObject editLauncher(String method, JSONObject params) {
        ApiProject project = project();
        try {
            Track track = resolveTrack(project, params);
            if (method.equals("launcher.stop") || method.equals("launcher.return_to_arranger")) {
                bitwig.obf.PiT raw = (bitwig.obf.PiT) ((ApiObject) track).getTarget();
                if (method.equals("launcher.stop")) {
                    if (params.has("immediate") && !(params.get("immediate") instanceof Boolean)) throw new IllegalArgumentException("immediate must be boolean");
                    Object quantization = namedNativeEnum(bitwig.obf.lhA.class, params.optBoolean("immediate", false) ? "OFF" : "DEFAULT");
                    raw.beI((bitwig.obf.lhA) quantization, false, -1); // stop the track's launcher clips
                } else raw.xpL(); // return to the arrangement
                return new JSONObject().put("requested", method);
            }
            int index = params.getInt("slot");
            com.bitwig.extension.api.project.timeline.ClipSlot existing = launcherSlot(project, track, index, false);
            if (method.equals("launcher.read")) return launcherSnapshot(existing, index);
            if (method.equals("launcher.launch")) {
                if (existing == null) throw new IllegalArgumentException("launcher slot does not exist");
                ((bitwig.obf.RK3) ((ApiObject) existing).getTarget()).Gef(true, false); // launch the slot
                return launcherSnapshot(existing, index);
            }
            if (method.equals("launcher.settings")) {
                if (existing == null || existing.getClip() == null) throw new IllegalArgumentException("launcher slot is empty");
                JSONObject changes = params.getJSONObject("changes");
                for (String key : changes.keySet())
                    if (!LAUNCH_SETTINGS.contains(key) && !key.equals("has_stop")) throw new IllegalArgumentException("unknown launch setting: " + key);
                if (changes.has("has_stop") && !(changes.get("has_stop") instanceof Boolean)) throw new IllegalArgumentException("has_stop must be boolean");
                Runnable settings = launchSettingsEdit(existing.getClip(), changes);
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    settings.run();
                    if (changes.has("has_stop")) existing.setHasStopButton(changes.getBoolean("has_stop"));
                });
                return launcherSnapshot(existing, index);
            }
            if (params.has("has_stop") && !(params.get("has_stop") instanceof Boolean)) throw new IllegalArgumentException("has_stop must be boolean");
            if (method.equals("launcher.clear")) {
                if (existing != null || params.has("has_stop")) DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    com.bitwig.extension.api.project.timeline.ClipSlot slot = existing == null ? launcherSlot(project, track, index, true) : existing;
                    ((bitwig.obf.vMh) ((ApiObject) slot).getTarget()).Gef((bitwig.obf.BVS) null); // empty the slot
                    if (params.has("has_stop")) slot.setHasStopButton(params.getBoolean("has_stop"));
                });
                return launcherSnapshot(launcherSlot(project, track, index, false), index);
            }
            if (params.has("replace") && !(params.get("replace") instanceof Boolean)) throw new IllegalArgumentException("replace must be boolean");
            if (existing != null && existing.getClip() != null && !params.optBoolean("replace", false))
                throw new IllegalArgumentException("launcher slot is occupied; explicit replace required");
            Clip staged;
            if (method.equals("launcher.copy_arranger")) {
                int clipIndex = params.getInt("clip");
                java.util.List<?> clips = track.clipTimeline().getEvents();
                if (clipIndex < 0 || clipIndex >= clips.size()) throw new IllegalArgumentException("clip index out of range");
                Clip source = (Clip) clips.get(clipIndex);
                bitwig.obf.TKq raw = ((bitwig.obf.TKq) ((ApiObject) source).getTarget()).waK(); // a clone of the clip
                com.bitwig.ramona.core.m1k.beI(raw); // prepare the clone for insertion
                staged = clipEventWrapper(source instanceof AudioClip, raw);
                staged.setTime(0);
            } else if (method.equals("launcher.put_notes")) {
                double duration = finiteField(params, "duration", -1);
                if (duration <= 0) throw new IllegalArgumentException("positive clip duration required");
                if (params.has("loop") && !(params.get("loop") instanceof Boolean)) throw new IllegalArgumentException("loop must be boolean");
                NoteClip clip = project.getFactory().createNoteClip(true);
                clip.setTime(0); clip.setDuration(duration); clip.setLoopDuration(duration);
                clip.setIsLoopEnabled(params.optBoolean("loop", true));
                clip.setTitle(params.optString("name", ""));
                JSONArray notes = params.getJSONArray("notes");
                for (int i = 0; i < notes.length(); i++) clip.getContent().getEventTimeline().addEvent(stageInstrumentNote(project.getFactory(), notes.getJSONObject(i)));
                staged = clip;
            } else if (method.equals("launcher.import_audio")) {
                com.bitwig.extension.api.project.timeline.AudioNote event = stageAudioEvent(project, params, 0);
                if (params.has("loop") && !(params.get("loop") instanceof Boolean)) throw new IllegalArgumentException("loop must be boolean");
                AudioClip clip = project.getFactory().createAudioClip(true);
                clip.setTime(0); clip.setDuration(event.getDuration()); clip.setLoopDuration(event.getDuration());
                clip.setIsLoopEnabled(params.optBoolean("loop", true));
                clip.setTitle(params.optString("name", new java.io.File(params.getString("file")).getName()));
                clip.getContent().getEventTimeline().addEvent(event);
                staged = clip;
            } else throw new IllegalArgumentException("unsupported launcher operation");
            DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                com.bitwig.extension.api.project.timeline.ClipSlot slot = launcherSlot(project, track, index, true);
                slot.setClip(staged);
                makeUnique(((ApiObject) staged).getTarget());
                if (params.has("has_stop")) slot.setHasStopButton(params.getBoolean("has_stop"));
            });
            return launcherSnapshot(launcherSlot(project, track, index, false), index);
        } finally { project.dispose(); }
    }

    private static JSONObject audioEventLocation(EventTimeline timeline, Object raw) {
        java.util.List<?> events = timeline.getEvents();
        for (int i = 0; i < events.size(); i++) if (((ApiObject) events.get(i)).getTarget() == raw)
            return new JSONObject().put("event", i).put("data", readAudioEvent(
                    (com.bitwig.extension.api.project.timeline.AudioNote) events.get(i)));
        throw new IllegalStateException("audio event is not attached to requested timeline");
    }

    private static com.bitwig.extension.api.project.timeline.AudioNote stageAudioEvent(ApiProject project, JSONObject params, double globalStart) {
        java.io.File file = new java.io.File(params.getString("file")).getAbsoluteFile();
        if (!file.isFile()) throw new IllegalArgumentException("audio file does not exist: " + file);
        double offset = finiteField(params, "source_offset", 0), gain = finiteField(params, "gain_db", 0);
        if (params.has("transpose")) throw new IllegalArgumentException("audio transpose is not yet playback-verified");
        if (!Double.isFinite(globalStart) || globalStart < 0 || offset < 0) throw new IllegalArgumentException("negative audio position");
        Factory factory = project.getFactory();
        com.bitwig.extension.api.project.file.SampleReference sample = factory.createSampleReferenceForLocalFile(file);
        double seconds = sample.getDurationInSeconds() - offset;
        if (sample.getSampleRate() <= 0 || sample.getChannelCount() <= 0 || seconds <= 0)
            throw new IllegalArgumentException("audio source has no usable frames at this offset");
        bitwig.obf.yrG transport = ((CmH) project.getTarget()).jPe(); // the document's transport model
        // Beats to seconds, add the source length, back to beats: the event's natural length.
        double natural = transport.MgQ(transport.BBg(globalStart) + seconds) - globalStart;
        double duration = finiteField(params, "duration", natural);
        if (duration <= 0 || !Double.isFinite(globalStart + duration)) throw new IllegalArgumentException("invalid audio duration");
        com.bitwig.extension.api.project.timeline.AudioNote event = factory.createAudioNote(0, duration, sample);
        event.setWarpMode(com.bitwig.extension.api.project.timeline.WarpMode.RAW);
        ((bitwig.obf.D) ((ApiObject) event).getTarget()).CKj(offset); // the source offset
        event.getGain().setValue(gain, Unit.DECIBEL);
        return event;
    }

    private static final java.util.Set<String> NOTE_CONDITIONS = java.util.Set.of("chance", "chance_enabled",
            "occurrence", "occurrence_enabled", "recurrence", "recurrence_enabled", "repeat_count", "repeat_curve", "repeat_enabled");

    private static void applyNoteConditions(com.bitwig.extension.api.project.timeline.Note note, JSONObject data) {
        bitwig.obf.D raw = (bitwig.obf.D) ((ApiObject) note).getTarget(); // a note or audio event
        for (String name : new String[]{"chance_enabled", "occurrence_enabled", "recurrence_enabled", "repeat_enabled"}) {
            if (!data.has(name)) continue;
            if (!(data.get(name) instanceof Boolean)) throw new IllegalArgumentException(name + " must be boolean");
            boolean on = data.getBoolean(name);
            switch (name) {
                case "chance_enabled" -> raw.BBg(on);
                case "occurrence_enabled" -> raw.unz(on);
                case "recurrence_enabled" -> raw.Anf(on);
                default -> raw.byf(on); // repeat_enabled
            }
        }
        if (data.has("chance")) {
            double value = finiteField(data, "chance", 1);
            if (value < 0 || value > 1) throw new IllegalArgumentException("chance must be in 0..1");
            note.setChance(value);
        }
        if (data.has("occurrence")) raw.beI((bitwig.obf.UQU) namedNativeEnum(bitwig.obf.UQU.class, data.getString("occurrence")));
        for (String name : new String[]{"recurrence", "repeat_count"}) {
            if (!data.has(name)) continue;
            double value = finiteField(data, name, 0);
            if (value != Math.rint(value) || (name.equals("recurrence") ? value < 0 || value > 2047 : value < -127 || value > 127))
                throw new IllegalArgumentException("invalid " + name);
            if (name.equals("recurrence")) raw.PJ3((int) value); else raw.gNZ((int) value); // recurrence mask, repeat count
        }
        if (data.has("repeat_curve")) {
            double value = finiteField(data, "repeat_curve", 0);
            if (value < -1 || value > 1) throw new IllegalArgumentException("repeat curve must be in -1..1");
            raw.ymo(value); // repeat curve
        }
    }

    private static void writeExpressions(Factory factory, com.bitwig.extension.api.project.timeline.Note note, JSONObject expressions, boolean audio) {
        if (expressions == null) throw new IllegalArgumentException("expressions must be an object");
        for (String name : expressions.keySet()) {
            com.bitwig.extension.api.project.timeline.NoteExpression expression = switch (name) {
                case "gain" -> note.getGain(); case "pan" -> note.getPan();
                case "transpose" -> note.getTranspose();
                case "formant" -> { if (!audio) throw new IllegalArgumentException("MIDI has no formant expression"); yield note.getFormant(); }
                case "timbre" -> { if (audio) throw new IllegalArgumentException("audio has no timbre expression"); yield ((InstrumentNote) note).getTimbre(); }
                case "pressure" -> { if (audio) throw new IllegalArgumentException("audio has no pressure expression"); yield ((InstrumentNote) note).getPressure(); }
                default -> throw new IllegalArgumentException("unknown expression: " + name);
            };
            JSONObject curve = expressions.getJSONObject(name);
            for (String field : curve.keySet()) if (!java.util.Set.of("unit", "points").contains(field))
                throw new IllegalArgumentException("unknown expression field: " + field);
            Unit unit = Unit.valueOf(curve.getString("unit"));
            JSONArray points = curve.getJSONArray("points");
            if (points.length() > 4096) throw new IllegalArgumentException("too many expression points");
            AutomationEvents events = factory.createDoubleAutomationEvents(unit);
            double previous = -1;
            for (int j = 0; j < points.length(); j++) {
                JSONArray point = points.getJSONArray(j);
                if (point.length() < 2 || point.length() > 4) throw new IllegalArgumentException("expression point needs time,value,curvature?,interpolation?");
                double time = point.getDouble(0), value = point.getDouble(1), curvature = point.optDouble(2, 0);
                String mode = point.optString(3, "linear");
                // Equal times are steps, as Bitwig stores them.
                if (!Double.isFinite(time) || !Double.isFinite(value) || !Double.isFinite(curvature) || time < 0 || time < previous
                        || curvature < -1 || curvature > 1 || (!mode.equals("linear") && !mode.equals("hold")))
                    throw new IllegalArgumentException("invalid expression point");
                events.addPoint(time, value); previous = time;
            }
            expression.setEvents(events, unit);
            bitwig.obf.Fu3 content = ((bitwig.obf.D) ((ApiObject) note).getTarget()).EVg(); // the note's expression content
            Object selected = null;
            for (Object timeline : content.QDQ())
                if (expressionKind(timeline).name().equalsIgnoreCase(name)) selected = timeline;
            if (selected == null) throw new IllegalStateException("expression timeline missing");
            java.util.List<?> stored = timelinePoints(selected);
            if (stored.size() != points.length()) throw new IllegalStateException("expression point count mismatch");
            for (int j = 0; j < points.length(); j++) {
                JSONArray input = points.getJSONArray(j);
                g13 point = (g13) stored.get(j);
                boolean hold = input.optString(3, "linear").equals("hold");
                point.beI(hold ? r1.beI : r1.Gef);
                point.Gef(hold ? 0 : input.optDouble(2, 0));
            }
        }
    }

    private static InstrumentNote stageInstrumentNote(Factory factory, JSONObject data) {
        java.util.Set<String> fields = new java.util.HashSet<>(NOTE_CONDITIONS);
        fields.addAll(java.util.Set.of("key", "channel", "start", "duration", "on_velocity", "off_velocity", "velocity_spread", "content_start", "muted", "expressions"));
        for (String key : data.keySet()) if (!fields.contains(key)) throw new IllegalArgumentException("unknown note field: " + key);
        double key = finiteField(data, "key", -1), channel = finiteField(data, "channel", 0);
        double start = finiteField(data, "start", 0), duration = finiteField(data, "duration", -1);
        double on = finiteField(data, "on_velocity", .8), off = finiteField(data, "off_velocity", .5);
        double spread = finiteField(data, "velocity_spread", 0), offset = finiteField(data, "content_start", 0);
        // Bitwig stores notes that start before the clip content and negative content starts.
        if (key != Math.rint(key) || key < 0 || key > 127 || channel != Math.rint(channel) || channel < 0 || channel > 15
                || duration <= 0 || !Double.isFinite(start + duration)
                || on < 0 || on > 1 || off < 0 || off > 1 || spread < 0 || spread > 1)
            throw new IllegalArgumentException("invalid note key/channel/time/velocity/spread/offset");
        if (data.has("muted") && !(data.get("muted") instanceof Boolean)) throw new IllegalArgumentException("note muted must be boolean");
        InstrumentNote note = factory.createInstrumentNote((int) channel, (int) key, start, duration, on, off);
        note.setIsMuted(data.optBoolean("muted", false));
        note.setVelocitySpread(spread);
        ((bitwig.obf.D) ((ApiObject) note).getTarget()).CKj(offset); // the content start
        applyNoteConditions(note, data);
        if (data.has("expressions")) writeExpressions(factory, note, data.getJSONObject("expressions"), false);
        return note;
    }

    private static JSONObject editRange(JSONObject params, String operation) {
        double start = params.getDouble("start");
        double end = params.getDouble("end");
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start)
            throw new IllegalArgumentException("a finite positive arranger range is required");
        ApiProject project = project();
        try {
            CmH raw = (CmH) project.getTarget();
            GJy root = raw.svk(); // the effect track group, root of the arrangement
            bitwig.obf.hmr selection = raw.PqZ().DYQ().nhI(); // the current selection
            bitwig.obf.vtX editor = bitwig.obf.vtX.Gak(); // the arranger time-range editor
            // These native commands own their undo transactions and handle
            // crossing clips, automation and cue markers across the root tree.
            java.util.List<?> roots = java.util.List.of(root.bV_()); // the arrangement timeline
            switch (operation) {
                case "duplicate" -> editor.Anf(roots, start, end, selection);
                case "insert" -> editor.sCA(roots, start, end, selection);
                case "delete" -> editor.MgQ(roots, start, end, selection);
                default -> throw new IllegalArgumentException("unknown range operation");
            }
            return new JSONObject().put("start", start).put("end", end)
                    .put(operation.equals("delete") ? "deleted_beats" : "inserted_beats", end - start);
        } finally { project.dispose(); }
    }

    /** A project media file entry's identity: its stored names, size and location. */
    private static java.util.List<Object> mediaIdentity(Object entry) {
        com.bitwig.flt.document.core.master.Qgw file = (com.bitwig.flt.document.core.master.Qgw) entry;
        com.bitwig.flt.document.core.master.Pip location = file.fmw();
        return java.util.Arrays.asList(file.sCA(), file.Bqk(), file.XUO(), file.qJX(),
                location == null ? "" : location.MgQ());
    }

    private static JSONObject saveNativeProjectCopy(String path) {
        if (path == null || path.isBlank() || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".bwproject"))
            throw new IllegalArgumentException("a .bwproject destination is required");
        java.io.File target = new java.io.File(path).getAbsoluteFile();
        if (target.exists()) throw new IllegalArgumentException("destination already exists: " + target);
        ApiProject project = project();
        CmH copy = null;
        java.nio.file.Path assets = null;
        boolean reserved = false;
        boolean markerCreated = false;
        boolean complete = false;
        java.nio.file.Path marker = target.getParentFile().toPath().resolve(".bitwig-project");
        try {
            java.nio.file.Files.createDirectories(target.getParentFile().toPath());
            java.nio.file.Files.createFile(target.toPath());
            reserved = true;
            if (!java.nio.file.Files.exists(marker)) {
                java.nio.file.Files.createFile(marker);
                markerCreated = true;
            }
            CmH source = (CmH) project.getTarget();
            com.bitwig.flt.document.file.Jpp serializer = com.bitwig.flt.document.file.JSA.sCA(); // the project serializer
            byte[] serialized = serializer.Gef(source); // serialize the project
            copy = (CmH) serializer.beI(serialized);   // and read it back as a detached copy
            // Resolve the clone against the source directory first. Native
            // cloning drops unused media entries, so source/clone list indexes
            // cannot be paired after edits such as deleting an audio clip.
            copy.byf(source.ckt()); // the project directory
            copy.unz(source.iEE()); // the project file path
            copy.ZRB(); // resolve its media
            java.util.List<?> copyFiles = copy.SKH().beI(); // its media file entries
            java.util.Map<java.util.List<Object>, Object> originalByIdentity = new java.util.HashMap<>();
            for (Object original : source.SKH().beI()) {
                Object previous = originalByIdentity.put(mediaIdentity(original), original);
                if (previous != null && !java.util.Objects.equals(((com.bitwig.flt.document.core.master.Qgw) previous).BBg(),
                        ((com.bitwig.flt.document.core.master.Qgw) original).BBg()))
                    throw new IllegalStateException("ambiguous native media identity");
            }
            java.util.List<java.io.File> resolvedFiles = new java.util.ArrayList<>();
            for (Object cloned : copyFiles) {
                Object original = originalByIdentity.get(mediaIdentity(cloned));
                if (original == null) throw new IllegalStateException("unmatched cloned media identity");
                resolvedFiles.add(((com.bitwig.flt.document.core.master.Qgw) original).BBg()); // the resolved file
            }
            copy.byf(target.getParent());
            copy.unz(target.getPath());
            int mediaCount = 0;
            java.util.Map<java.nio.file.Path, String> collected = new java.util.HashMap<>();
            assets = java.nio.file.Files.createTempDirectory(target.getParentFile().toPath(), "openwig-media-");
            for (int i = 0; i < copyFiles.size(); i++) {
                com.bitwig.flt.document.core.master.Qgw cloned = (com.bitwig.flt.document.core.master.Qgw) copyFiles.get(i);
                java.io.File sourceFile = resolvedFiles.get(i);
                if (sourceFile == null || !sourceFile.isFile()) {
                    if (!String.valueOf(cloned.XUO()).isEmpty()) continue;
                    throw new IllegalStateException("source media unavailable at native file index " + i);
                }
                java.nio.file.Path canonical = sourceFile.toPath().toRealPath();
                String relative = collected.get(canonical);
                if (relative == null) {
                    java.nio.file.Path destination = assets.resolve(mediaCount + "-" + sourceFile.getName());
                    java.nio.file.Files.copy(canonical, destination);
                    relative = target.getParentFile().toPath().relativize(destination).toString().replace('\\', '/');
                    collected.put(canonical, relative);
                    mediaCount++;
                }
                cloned.Gef(relative); // its stored relative path
                cloned.Gef((com.bitwig.flt.packaging.core.Hvv) null); // no package resource
                cloned.Gef(new java.io.File(target.getParentFile(), relative)); // its file
            }
            serializer.Gef(copy, target); // write the copy
            java.util.Set<String> states = new java.util.HashSet<>();
            source.beI(states); // collect the plugin state file names
            bitwig.obf.CZ3 embedded = new bitwig.obf.CZ3(); // files to embed in the project file
            for (String state : states) {
                java.io.File stateFile = bitwig.obf.seb.sCA(state); // a plugin state file by name
                if (!stateFile.isFile()) throw new IllegalStateException("plugin state unavailable: " + state);
                embedded.beI("plugin-states/" + state, stateFile);
            }
            if (!states.isEmpty()) com.bitwig.base.serial.file.Pv.beI(target, (bitwig.obf.bl3) embedded); // embed them
            complete = true;
            return new JSONObject().put("path", target.getPath()).put("bytes", target.length())
                    .put("media_files", mediaCount).put("plugin_states", states.size());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("native project copy failed", failure);
        } finally {
            try {
                if (copy != null) copy.iz_(); // dispose the detached copy
            } finally {
                project.dispose();
                if (!complete) {
                    try {
                        if (reserved) java.nio.file.Files.deleteIfExists(target.toPath());
                        if (markerCreated) java.nio.file.Files.deleteIfExists(marker);
                        if (assets != null) {
                            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(assets)) {
                                for (java.nio.file.Path item : files.sorted(java.util.Comparator.reverseOrder()).toList())
                                    java.nio.file.Files.deleteIfExists(item);
                            }
                        }
                    } catch (java.io.IOException cleanupFailure) {
                        System.err.println("openwig: native copy cleanup failed: " + cleanupFailure);
                    }
                }
            }
        }
    }

    private static JSONObject importDawProject(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        java.io.File file = new java.io.File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException("dawproject file not found: " + path);
        }
        ApiProject project = project();
        try {
            com.bitwig.dawproject.Project daw;
            MetaData meta = null;
            try {
                java.lang.reflect.Method loadProject = DawProject.class.getMethod("loadProject", java.io.File.class);
                daw = (com.bitwig.dawproject.Project) loadProject.invoke(null, file);
                try {
                    java.lang.reflect.Method loadMeta = DawProject.class.getMethod("loadMetadata", java.io.File.class);
                    meta = (MetaData) loadMeta.invoke(null, file);
                } catch (Throwable ignored) {
                    // Optional metadata.xml inside archive.
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("dawproject load failed", failure);
            }
            java.lang.reflect.Method loadEmbedded;
            try {
                loadEmbedded = DawProject.class.getMethod("loadEmbedded", java.io.File.class, String.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("dawproject loadEmbedded lookup failed", failure);
            }
            java.util.function.Function<Object, Object> streamProvider = entryPath -> {
                if (entryPath == null) return null;
                String rel = entryPath.toString();
                if (rel.startsWith("/")) rel = rel.substring(1);
                try {
                    byte[] bytes = (byte[]) loadEmbedded.invoke(null, file, rel);
                    return bytes == null ? null : new java.io.ByteArrayInputStream(bytes);
                } catch (Throwable absent) {
                    java.io.File external = new java.io.File(file.getParentFile(), rel);
                    if (external.isFile()) {
                        try {
                            return new java.io.FileInputStream(external);
                        } catch (java.io.IOException ignored) {
                            return null;
                        }
                    }
                    return null;
                }
            };
            try {
                Class<?> importerClass = com.bitwig.flt.document.api.document.ApiDawProjectImporter.class;
                java.lang.reflect.Constructor<?> ctor = importerClass.getConstructor(
                        com.bitwig.flt.document.core.master.CmH.class,
                        com.bitwig.dawproject.Project.class,
                        java.util.function.Function.class,
                        java.io.File.class);
                Object importer = ctor.newInstance(project.getTarget(), daw, streamProvider, file.getParentFile());
                java.lang.reflect.Method phase1 = importerClass.getMethod("convertPhase1");
                java.lang.reflect.Method phase2 = importerClass.getMethod("convertPhase2");
                DirectAccess.runInUndo(project, () -> {
                    modifyModel(project, () -> {
                        try {
                            phase1.invoke(importer);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("dawproject convertPhase1 failed", e);
                        }
                    });
                    modifyModel(project, () -> {
                        try {
                            phase2.invoke(importer);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("dawproject convertPhase2 failed", e);
                        }
                    });
                });
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("dawproject importer invocation failed", failure);
            }
            if (meta != null) {
                if (meta.title != null) project.setMetadataText(ProjectMetadata.TITLE, meta.title);
                if (meta.artist != null) project.setMetadataText(ProjectMetadata.ARTIST, meta.artist);
                if (meta.album != null) project.setMetadataText(ProjectMetadata.ALBUM, meta.album);
                if (meta.originalArtist != null) project.setMetadataText(ProjectMetadata.ORIGINAL_ARTIST, meta.originalArtist);
                if (meta.songwriter != null) project.setMetadataText(ProjectMetadata.WRITER, meta.songwriter);
                if (meta.producer != null) project.setMetadataText(ProjectMetadata.PRODUCER, meta.producer);
                if (meta.year != null) project.setMetadataText(ProjectMetadata.YEAR, meta.year);
                if (meta.genre != null) project.setMetadataText(ProjectMetadata.GENRE, meta.genre);
                if (meta.copyright != null) project.setMetadataText(ProjectMetadata.COPYRIGHT, meta.copyright);
                if (meta.comment != null) project.setMetadataText(ProjectMetadata.COMMENT, meta.comment);
            }
            return new JSONObject()
                    .put("path", file.getAbsolutePath())
                    .put("main_tracks", project.getTrackGroup().mainTracks().size())
                    .put("effect_tracks", project.getTrackGroup().effectTracks().size())
                    .put("scenes", sceneCount());
        } finally {
            project.dispose();
        }
    }

    private static JSONObject executeClip(String method, JSONObject params) {
        ApiProject project = project();
        try {
            Track targetTrack = resolveTrack(project, params);
            return switch (method) {
                case "clip.count" -> new JSONObject().put("count",
                        targetTrack.clipTimeline().getEvents().size());
                case "clip.note_count" -> {
                    int clipIndex = params.optInt("clip", -1);
                    java.util.List<?> clips = targetTrack.clipTimeline().getEvents();
                    if (clipIndex < 0 || clipIndex >= clips.size()) {
                        throw new IllegalArgumentException("clip index out of range: " + clipIndex);
                    }
                    Clip clip = (Clip) clips.get(clipIndex);
                    if (!(clip instanceof NoteClip noteClip)) {
                        throw new IllegalArgumentException("clip is not a note clip");
                    }
                    yield new JSONObject().put("count",
                            noteClip.getContent().getEventTimeline().getEvents().size());
                }
                case "clip.create_arranger_with_notes" -> {
                    double start = params.optDouble("start", Double.NaN);
                    double duration = params.optDouble("duration", Double.NaN);
                    JSONArray notes = params.optJSONArray("notes");
                    if (!Double.isFinite(start) || start < 0 ||
                            !Double.isFinite(duration) || duration <= 0 ||
                            notes == null || notes.length() > 4096) {
                        throw new IllegalArgumentException("invalid arranger clip request");
                    }
                    yield new JSONObject().put("count",
                            addNoteClipOnTrack(project, targetTrack, start, duration, notes));
                }
                default -> throw new IllegalArgumentException("unsupported clip method: " + method);
            };
        } finally {
            project.dispose();
        }
    }

    public static int noteCount(int trackIndex, int clipIndex) {
        ApiProject project = project();
        try {
            java.util.List<?> clips = track(project, trackIndex).clipTimeline().getEvents();
            if (clipIndex < 0 || clipIndex >= clips.size()) {
                throw new IllegalArgumentException("clip index out of range: " + clipIndex);
            }
            Clip clip = (Clip) clips.get(clipIndex);
            if (!(clip instanceof NoteClip noteClip)) {
                throw new IllegalArgumentException("clip is not a note clip");
            }
            return noteClip.getContent().getEventTimeline().getEvents().size();
        } finally {
            project.dispose();
        }
    }

    private static int addNoteClipOnTrack(ApiProject project, Track track,
                                          double start, double duration, JSONArray notes) {
        String stage = "factory";
        try {
            Factory factory = project.getFactory();
            stage = "new clip";
            NoteClip clip = factory.createNoteClip(false);
            stage = "set start";
            clip.setTime(start);
            stage = "set duration";
            clip.setDuration(duration);
            stage = "clip content";
            EventTimeline noteTimeline = clip.getContent().getEventTimeline();
            for (int i = 0; i < notes.length(); i++) {
                JSONArray item = notes.getJSONArray(i);
                if (item.length() != 5) {
                    throw new IllegalArgumentException("note must be [channel,key,start,duration,velocity]");
                }
                int channel = item.getInt(0);
                int key = item.getInt(1);
                double noteStart = item.getDouble(2);
                double noteDuration = item.getDouble(3);
                double velocity = item.getDouble(4);
                if (channel < 0 || channel > 15 || key < 0 || key > 127 ||
                        !Double.isFinite(noteStart) || noteStart < 0 ||
                        !Double.isFinite(noteDuration) || noteDuration <= 0 ||
                        !Double.isFinite(velocity) || velocity < 0 || velocity > 1) {
                    throw new IllegalArgumentException("invalid note at index " + i);
                }
                InstrumentNote note = factory.createInstrumentNote(channel, key,
                        noteStart, noteDuration, velocity, 0.5);
                stage = "add note " + i;
                noteTimeline.addEvent(note);
            }
            stage = "attach clip";
            ClipTimeline timeline = track.clipTimeline();
            DirectAccess.runInUndo(timeline, () -> timeline.addEvent(clip));
            stage = "count clips";
            return timeline.getEvents().size();
        } catch (Throwable failure) {
            throw new IllegalStateException("clip stage " + stage, failure);
        }
    }

    public static int addNoteClip(int trackIndex, double start, double duration, JSONArray notes) {
        ApiProject project = project();
        try {
            return addNoteClipOnTrack(project, track(project, trackIndex), start, duration, notes);
        } finally {
            project.dispose();
        }
    }
}
