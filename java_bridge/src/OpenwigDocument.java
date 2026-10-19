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
import com.bitwig.flt.document.core.master.Yko;
import com.bitwig.flt.document.core.master.Kkl;
import com.bitwig.flt.document.core.master.oZU;
import com.openwig.bridge.DirectAccess;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact-build song operations, called only from Bitwig's application event queue. */
public final class OpenwigDocument {
    private static final java.util.Map<String, JSONObject> pluginStateJobs = new java.util.LinkedHashMap<>();

    private static JSONObject capturePluginState(Object holder) {
        String loadState = ((Enum<?>) modelGet(holder, "NFl")).name();
        if (loadState.equals("LOADING") || loadState.equals("QUEUED_FOR_LOADING"))
            return new JSONObject().put("state", "busy").put("load_state", loadState);
        if (!loadState.equals("LOADED")) throw new IllegalArgumentException("plugin state capture requires a loaded plugin: " + loadState);
        if (Boolean.TRUE.equals(modelGet(holder, "iP2")))
            return new JSONObject().put("state", "busy");
        String job = java.util.UUID.randomUUID().toString();
        JSONObject status = new JSONObject().put("job", job).put("state", "running");
        try {
            Class<?> type = classForName("com.bitwig.flt.document.core.master.device.OsP");
            Object callback = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, call, args) -> {
                if (call.getName().equals("IYM") && args.length == 2) status.put("state", "failed").put("error", String.valueOf(args[1]));
                else if (call.getName().equals("IYM") || call.getName().equals("jPB")) {
                    Object file = modelGet(holder, "qP3");
                    status.put("state", "complete").put("path", file == null ? JSONObject.NULL : ((java.io.File) file).getAbsolutePath())
                            .put("dirty", modelGet(holder, "dJV"));
                }
                else if (call.getName().equals("hashCode")) return System.identityHashCode(proxy);
                else if (call.getName().equals("equals")) return proxy == args[0];
                else if (call.getName().equals("toString")) return "OpenwigPluginStateCapture";
                return null;
            });
            if (pluginStateJobs.size() >= 64) {
                String completed = null;
                for (String key : pluginStateJobs.keySet()) if (!pluginStateJobs.get(key).getString("state").equals("running")) { completed = key; break; }
                if (completed == null) throw new IllegalStateException("too many active plugin state jobs");
                pluginStateJobs.remove(completed);
            }
            pluginStateJobs.put(job, status);
            holder.getClass().getMethod("IYM", type).invoke(holder, callback);
            return new JSONObject(status.toString());
        } catch (ReflectiveOperationException failure) {
            status.put("state", "failed").put("error", failure.toString());
            throw new IllegalStateException("plugin state capture failed", failure);
        }
    }
    private OpenwigDocument() {}

    public static Object currentDocument() {
        try {
            Object app = classForName("com.bitwig.flt.app.eY1").getMethod("ncg").invoke(null);
            Object selected = app == null ? null : modelGet(modelGet(app, "kxC"), "WEC");
            if (selected == null) throw new IllegalStateException("no selected Bitwig project");
            return modelGet(selected, "wtf");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot resolve selected Bitwig project", failure);
        }
    }

    private static JSONObject documentInfo() {
        Object raw = currentDocument();
        return new JSONObject().put("path", modelGet(raw, "BEq"))
                .put("directory", modelGet(raw, "UBi")).put("name", modelGet(raw, "IT3"));
    }

    private static JSONObject listDocuments() {
        try {
            Object app = classForName("com.bitwig.flt.app.eY1").getMethod("ncg").invoke(null);
            JSONArray result = new JSONArray();
            for (Object tab : (java.util.List<?>) modelGet(modelGet(app, "Lg"), "getValue")) {
                Object raw = modelGet(tab, "wtf");
                if (!(raw instanceof oZU)) continue;
                ApiProject api = new ApiProject((oZU) raw);
                try {
                    result.put(new JSONObject().put("path", modelGet(raw, "BEq"))
                            .put("name", modelGet(raw, "IT3"))
                            .put("main_tracks", api.getTrackGroup().mainTracks().size()));
                } finally { api.dispose(); }
            }
            return new JSONObject().put("documents", result);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("cannot list projects", failure); }
    }

    private static JSONObject selectDocument(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("open project path is required");
        try {
            Object app = classForName("com.bitwig.flt.app.eY1").getMethod("ncg").invoke(null);
            Object tab = app.getClass().getMethod("Trh", java.io.File.class)
                    .invoke(app, new java.io.File(path).getAbsoluteFile());
            if (tab == null) throw new IllegalArgumentException("project is not open: " + path);
            Object selection = modelGet(app, "kxC");
            selection.getClass().getMethod("jPB", classForName("aBx")).invoke(selection, tab);
            return documentInfo();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("cannot select project", failure); }
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

    /** Native F3z/rdP callback: completion succeeds the job, an exception or message fails it. */
    private static Object documentCallback(String type, JSONObject status) {
        Class<?> callbackType = classForName(type);
        if (callbackType == null) throw new IllegalStateException("missing native callback type " + type);
        return java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, call, args) -> {
            Object value = args == null || args.length == 0 ? null : args[0];
            switch (call.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == value;
                case "toString": return "OpenwigDocumentJob";
                case "taskCompleted":
                    synchronized (status) { status.put("state", "complete"); }
                    return null;
                case "IYM":
                    synchronized (status) {
                        if (value instanceof Throwable failure)
                            status.put("state", "failed").put("error", String.valueOf(failure.getMessage() == null ? failure : failure.getMessage()));
                        else if (value instanceof String message) status.put("state", "failed").put("error", message);
                        else status.put("state", "complete");
                    }
                    return null;
                default: return null;
            }
        });
    }

    private static Object application() throws ReflectiveOperationException {
        Object app = classForName("com.bitwig.flt.app.eY1").getMethod("ncg").invoke(null);
        if (app == null) throw new IllegalStateException("Bitwig application is not ready");
        return app;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JSONObject openDocument(JSONObject params) {
        java.io.File file = projectFile(params);
        if (!file.isFile()) throw new IllegalArgumentException("project file not found: " + file);
        try {
            Object app = application();
            Object tab = app.getClass().getMethod("Trh", java.io.File.class).invoke(app, file);
            if (tab != null) {
                Object selection = modelGet(app, "kxC");
                selection.getClass().getMethod("jPB", classForName("aBx")).invoke(selection, tab);
                return new JSONObject().put("operation", "open").put("state", "complete")
                        .put("path", file.getPath()).put("already_open", true);
            }
            java.util.List<?> tabs = (java.util.List<?>) modelGet(modelGet(app, "Lg"), "getValue");
            // Editions without multiple projects would prompt to close the current one.
            if (!Boolean.TRUE.equals(modelGet(modelGet(modelGet(app, "H3n"), "Klm"), "getBoolValue")) && !tabs.isEmpty())
                throw new IllegalStateException("this Bitwig edition opens one project at a time; close the open project first");
            Class<Enum> flagType = (Class<Enum>) classForName("com.bitwig.flt.app.rLC");
            java.util.EnumSet flags = java.util.EnumSet.of(Enum.valueOf(flagType, "SELECT"));
            if (params.optBoolean("activate_engine", false)) flags.add(Enum.valueOf(flagType, "ACTIVATE_ENGINE"));
            JSONObject status = new JSONObject().put("operation", "open").put("path", file.getPath());
            startDocumentJob(status);
            Object callback = documentCallback("com.bitwig.base.async.F3z", status);
            app.getClass().getMethod("IYM", String.class, int.class, java.util.Set.class, classForName("com.bitwig.base.async.F3z"))
                    .invoke(app, file.getPath(), tabs.size(), flags, callback);
            return jobSnapshot(status);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot open project", failure);
        }
    }

    private static JSONObject closeDocument(JSONObject params) {
        if (!params.optBoolean("discard_changes", false))
            throw new IllegalArgumentException("document.close discards unsaved changes; pass discard_changes=true");
        java.io.File file = projectFile(params);
        try {
            Object app = application();
            Object tab = app.getClass().getMethod("Trh", java.io.File.class).invoke(app, file);
            if (tab == null) throw new IllegalArgumentException("project is not open: " + file);
            Class<?> tabType = classForName("aBx");
            Class<?> doneType = classForName("com.bitwig.base.async.rdP");
            java.lang.reflect.Method close = null;
            for (java.lang.reflect.Method candidate : app.getClass().getMethods()) {
                Class<?>[] types = candidate.getParameterTypes();
                if (candidate.getName().equals("IYM") && types.length == 4 && types[0] == tabType
                        && types[1] == boolean.class && types[3] == doneType) { close = candidate; break; }
            }
            if (close == null) throw new IllegalStateException("native close method not found");
            JSONObject status = new JSONObject().put("operation", "close").put("path", file.getPath());
            startDocumentJob(status);
            // false skips the interactive save branch; the native path still stops the engine first.
            close.invoke(app, tab, false, null, documentCallback("com.bitwig.base.async.rdP", status));
            return jobSnapshot(status);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot close project", failure);
        }
    }

    private static ApiProject project() {
        return new ApiProject((oZU) DirectAccess.activeDocumentObject());
    }

    public static JSONObject audioStatus() {
        ApiProject project = project();
        try {
            oZU document = (oZU) DirectAccess.activeDocumentObject();
            return new JSONObject().put("connected", document.KF3())
                    .put("ready", document.va2()).put("active", document.NFl())
                    .put("can_activate", document.TEI()).put("realtime", document.GZM())
                    .put("master", trackSnapshot(project.getTrackGroup().masterTrack(), -1));
        } finally {
            project.dispose();
        }
    }

    public static JSONObject activateAudio() {
        ((oZU) DirectAccess.activeDocumentObject()).Gcr(null);
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
        return ((oZU) DirectAccess.activeDocumentObject()).Qjx().ce_();
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
            Yko group = (Yko) ((ApiObject) project.getTrackGroup()).getTarget();
            Object target = ((ApiObject) effect).getTarget();
            int before = project.getTrackGroup().effectTracks().size();
            DirectAccess.runInUndo(project,
                    () -> modifyModel(project, () -> DirectAccess.removeEffectTrack(group, target)));
            int after = project.getTrackGroup().effectTracks().size();
            if (after != before - 1 || modelGet(target, "L_") != null) {
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
            Yko group = (Yko) ((ApiObject) groupApi).getTarget();
            Kkl target = (Kkl) ((ApiObject) targetItem).getTarget();
            int before = project.getTrackGroup().mainTracks().size();
            DirectAccess.runInUndo(project,
                    () -> modifyModel(project, () -> {
                        // The visible bank is a reference list; the owning bank
                        // keeps the track (and its engine playback) alive.
                        // Match native Delete Tracks: remove both memberships.
                        group.jPB(target);
                        group.jPB(target, true);
                    }));
            int after = project.getTrackGroup().mainTracks().size();
            if (after != before - 1 || modelGet(target, "L_") != null) {
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
        Class<?> decimal = classForName("Ob");
        Class<?> bool = classForName("KOL");
        Class<?> indexed = classForName("mqD");
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
        result.put("device_name", modelGet(target, "HT3"))
                .put("preset", modelGet(target, "SVb"))
                .put("displayed_preset", modelGet(target, "Sxv"));
        String role = ((Enum<?>) modelGet(target, "Jxd")).name();
        result.put("role", switch (role) {
            case "AUDIO_TO_AUDIO" -> "audioFX";
            case "NOTE_TO_NOTE" -> "noteFX";
            case "NOTE_TO_AUDIO" -> "instrument";
            case "AUDIO_TO_NOTE" -> "analyzer";
            default -> role;
        });
        if (device instanceof NativeDevice nativeDevice) {
            result.put("device_id", modelGet(modelGet(target, "odb"), "Gcr").toString());
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
            Object contents = modelGet(target, "bhG");
            Object modules = modelGetOrNull(contents, "hMI");
            if (modules instanceof java.util.List<?> children)
                for (Object child : children)
                    if (automationInstance("com.bitwig.flt.document.core.master.device.ruo", child))
                        result.put("grid", readGrid(nativeDevice));
        } else if (device instanceof PluginDevice) {
            Class<?> clap = classForName("com.bitwig.flt.document.core.master.device.cq");
            if (clap != null && clap.isInstance(target)) result.put("kind", "clap");
            result.put("device_id", modelGet(modelGet(target, "urJ"), "Gcr"));
            Object plugin = modelGet(target, "Oof");
            JSONArray parameters = new JSONArray();
            if (plugin != null) {
                result.put("vendor", modelGet(plugin, "bla"));
                for (Object parameter : (java.util.List<?>) modelGet(plugin, "HT3")) {
                    parameters.put(new JSONObject()
                            .put("id", modelGet(parameter, "KF3"))
                            .put("name", modelGet(parameter, "rqQ"))
                            .put("value", modelGet(parameter, "YiG"))
                            .put("gui_value", modelGet(parameter, "wtf"))
                            .put("display", modelGet(parameter, "SSK"))
                            .put("unit", modelGet(parameter, "AAj")));
                }
            }
            java.io.File stateFile = plugin == null ? null : (java.io.File) modelGetOrNull(plugin, "qP3");
            boolean hasState = stateFile != null && stateFile.isFile();
            result.put("state", hasState ? stateFile.getAbsolutePath() : JSONObject.NULL)
                    .put("state_file", hasState ? stateFile.getAbsolutePath() : JSONObject.NULL)
                    .put("state_path", hasState ? "plugins/" + stateFile.getName() : JSONObject.NULL)
                    .put("state_bytes", hasState ? stateFile.length() : 0);
            result.put("parameters", parameters).put("parameter_unit", "normalized")
                    .put("parameters_available", parameters.length() > 0)
                    .put("parameters_status", parameters.length() == 0 ? "unavailable_model_cache" : "resident_model_cache");
            Object sidechain = plugin == null ? null : modelGet(modelGet(modelGet(plugin, "Idv"), "APa"), "LZe");
            result.put("sidechain", sourceInfo(sidechain, context));
            JSONArray rawPluginChains = new JSONArray();
            JSONArray sidechains = new JSONArray();
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            boolean complete = true;
            if (plugin != null) {
                complete &= readNestedComponents(target, modelGetOrNull(plugin, "Ce"), rawPluginChains, sidechains, seen,
                        context, path, owners, atomOwners, 0);
                complete &= readNestedComponents(target, modelGetOrNull(plugin, "vCL"), rawPluginChains, sidechains, seen,
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
            boolean complete = readNestedComponents(target, modelGet(target, "bhG"), chains, sidechains, seen,
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
        Object bankList = modelGet(modelGet(target, "YGC"), "Klm");
        if (!(bankList instanceof java.util.List<?> entries)) return modulators;
        Class<?> decimal = classForName("Ob");
        Class<?> bool = classForName("KOL");
        Class<?> indexed = classForName("mqD");
        // Self-device atom index: m1C identity -> CONTENTS-relative parameter
        // path, so bindings inside the owning device resolve exactly.
        java.util.IdentityHashMap<Object, String> atomPaths = deviceAtomPaths(target, decimal, bool, indexed);
        for (Object modulator : entries) {
            JSONObject item = new JSONObject()
                    .put("index", modulators.length())
                    .put("id", jsonModelValue(modelGet(modulator, "UNI")))
                    .put("device_name", modelGet(modulator, "HT3"))
                    .put("display_name", modelGet(modulator, "ruM"))
                    .put("preset", modelGet(modulator, "SVb"))
                    .put("x", modelGet(modulator, "AOA")).put("y", modelGet(modulator, "KF3"));
            try {
                Object contents = modelGet(modulator, "dsw");
                // nJS.ppu() is the definition's factory identity (F3z.Gcr() UUID).
                Object identity = contents == null ? null : modelGetOrNull(modelGetOrNull(contents, "ppu"), "Gcr");
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
        Object contents = modelGetOrNull(target, "bhG");
        if (contents == null) return atomPaths;
        java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.List<Object> found = new java.util.ArrayList<>();
        walkAtoms(contents, 0, seen, found, decimal, bool, indexed);
        for (Object atom : found) {
            String fullPath = automationIdentifier(target, atom);
            if (fullPath != null && fullPath.startsWith("CONTENTS/"))
                atomPaths.put(atom, fullPath.substring("CONTENTS/".length()));
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
            // Rem.wtf()/HDw.wtf() are the stored current values; dZZ.Qcu() is
            // the enum index. Values are model-native, not normalized.
            Object value = modelGetOrNull(atom, "wtf");
            if (value == null) value = modelGetOrNull(atom, "Qcu");
            if (value == null) value = modelGetOrNull(atom, "KF3");
            parameter.put("value", jsonModelValue(value));
            String unit = automationUnit(modelGetOrNull(atom, "ViH"));
            if (unit != null) parameter.put("unit", unit);
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
        Object children = contents == null ? null : modelGet(contents, "hMI");
        if (!(children instanceof java.util.List<?> components)) return bindings;
        for (Object component : components) {
            if (component == null || !"Hx3".equals(component.getClass().getSimpleName())) continue;
            Object entries = modelGetOrNull(component, "gTC");
            if (!(entries instanceof java.util.List<?> list)) continue;
            String key = (String) modelGetOrNull(component, "az_");
            for (Object entry : list) {
                if (entry == null) continue;
                if (bindings.length() >= 512) {
                    bindings.put(new JSONObject().put("truncated", true));
                    return bindings;
                }
                Object reference = modelGetOrNull(entry, "kxC");
                Object parameter = reference == null ? null : modelGetOrNull(reference, "XYl");
                Object amount = modelGetOrNull(entry, "Qcu");
                JSONObject binding = new JSONObject().put("component", key == null ? "" : key);
                String parameterPath = parameter == null ? null : atomPaths.get(parameter);
                if (parameterPath != null) {
                    binding.put("target_parameter", parameterPath);
                    binding.put("target_owner", new JSONObject().put("path", path).put("device", deviceIndex));
                } else if (parameter != null && atomOwners != null && atomOwners.containsKey(parameter)) {
                    JSONObject entryOwner = atomOwners.get(parameter);
                    binding.put("target_parameter", entryOwner.optString("parameter"));
                    binding.put("target_owner", new JSONObject()
                            .put("path", entryOwner.optString("path"))
                            .put("device", entryOwner.getInt("device")));
                } else {
                    Object storedPath = reference == null ? null : modelGetOrNull(reference, "TEI");
                    if (storedPath instanceof String text && !text.isEmpty()) {
                        binding.put("target_parameter",
                                text.startsWith("CONTENTS/") ? text.substring("CONTENTS/".length()) : text);
                    } else {
                        Object name = reference == null ? null : modelGetOrNull(reference, "heQ");
                        if (name == null && parameter != null) name = modelGetOrNull(parameter, "NMa");
                        if (name == null && parameter != null) name = modelGetOrNull(parameter, "IW");
                        if (name == null && parameter != null) name = modelGetOrNull(parameter, "aH_");
                        if (name != null && !name.toString().isEmpty()) binding.put("target", name.toString());
                    }
                    if (parameter != null && !binding.has("target_parameter"))
                        binding.put("target_model", parameter.getClass().getSimpleName());
                    JSONObject located = null;
                    if (reference != null) {
                        Object ownerDevice = modelGetOrNull(reference, "hMI");
                        if (ownerDevice == null) ownerDevice = modelGetOrNull(reference, "Lg");
                        located = locateBindingOwner(ownerDevice, self, path, deviceIndex, owners);
                        if (located == null && parameter != null) {
                            located = locateBindingOwner(parameter, self, path, deviceIndex, owners);
                            if (located == null) {
                                located = locateBindingOwner(modelGetOrNull(parameter, "U3o"),
                                        self, path, deviceIndex, owners);
                            }
                        }
                        if (located == null) {
                            // QFL.xTe() is the stored track (roo) of the reference.
                            JSONObject track = context == null ? null
                                    : context.get(modelGetOrNull(reference, "xTe"));
                            if (track != null) located = new JSONObject().put("path", track.optString("path"));
                        }
                    }
                    if (located != null) binding.put("target_owner", located);
                    else if (parameter != null)
                        binding.put("target_owner", new JSONObject().put("resolved", false));
                }
                if (amount instanceof Number number) binding.put("amount", number.doubleValue());
                // AvA.LZe(): amount over the target's modulation span, clamped to -1..1.
                Object normalized = modelGetOrNull(entry, "LZe");
                if (normalized instanceof Number number) binding.put("amount_normalized", number.doubleValue());
                Object enabled = modelGetOrNull(entry, "oLP");
                if (enabled instanceof Boolean flag) binding.put("enabled", flag);
                bindings.put(binding);
            }
        }
        return bindings;
    }

    private static JSONObject locateBindingOwner(Object start, Object self, String path, int deviceIndex,
                                                 java.util.IdentityHashMap<Object, JSONObject> owners) {
        Object cursor = start;
        for (int depth = 0; cursor != null && depth < 24; depth++) {
            if (cursor == self) {
                return new JSONObject().put("path", path).put("device", deviceIndex);
            }
            if (owners != null) {
                JSONObject matched = owners.get(cursor);
                if (matched != null) return matched;
            }
            cursor = modelGetOrNull(cursor, "L_");
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
        if (automationInstance("WYJ", node)) {
            Object source = modelGet(modelGet(node, "APa"), "LZe");
            sidechains.put(new JSONObject().put("component_path", jsonModelValue(automationIdentifier(owner, node)))
                    .put("source", sourceInfo(source, context)));
            return true;
        }
        if (automationInstance("com.bitwig.flt.document.core.master.device.YLB", node)) {
            JSONObject item = readNestedChain(owner, node, "chain", out.length(), path, context, owners, atomOwners, out.length());
            Object chainName = modelGetOrNull(node, "az_");
            if (chainName instanceof String text && !text.isEmpty()) item.put("name", text);
            out.put(item);
            return true;
        }
        if (automationInstance("com.bitwig.flt.document.core.master.device.YhC", node)) {
            Object chain = modelGetOrNull(node, "Lg");
            if (chain != null) {
                JSONObject item = readNestedChain(owner, chain, "chain", out.length(), path, context, owners, atomOwners, out.length());
                Object slotName = modelGetOrNull(node, "IYM");
                if (slotName instanceof String text && !text.isEmpty()) item.put("name", text);
                out.put(item);
            }
            return true;
        }
        if (automationInstance("com.bitwig.flt.document.core.master.device.EhV", node)) {
            String chainKind = automationInstance("com.bitwig.flt.document.core.master.device.AYz", node)
                    ? "layer" : automationInstance("com.bitwig.flt.document.core.master.device.eiA", node)
                    ? "multi_out" : "band";
            int index = 0;
            for (Object chain : (java.util.List<?>) modelGet(node, "NFl")) {
                JSONObject item = readNestedChain(owner, chain, chainKind, index, path, context, owners, atomOwners, out.length());
                Object chainName = modelGetOrNull(chain, "az_");
                if (chainName instanceof String text && !text.isEmpty()) item.put("name", text);
                out.put(item);
                index++;
            }
            return true;
        }
        if (automationInstance("Jnx", node)) {
            int index = 0;
            for (Object pad : (java.util.List<?>) modelGet(node, "IYM")) {
                Object chain = modelGet(pad, "n3R");
                JSONObject item = chain == null ? new JSONObject().put("kind", "drum_pad")
                        .put("index", index).put("has_chain", false).put("devices", new JSONArray())
                        : readNestedChain(owner, chain, "drum_pad", index, path, context, owners, atomOwners, out.length());
                item.put("key", modelGet(pad, "KF3"));
                out.put(item);
                index++;
            }
            return true;
        }
        // These model classes are leaves for nesting discovery.
        if (automationInstance("Ob", node) || automationInstance("KOL", node) ||
                automationInstance("mqD", node)) return true;
        boolean complete = true;
        try {
            java.lang.reflect.Method childrenOf = node.getClass().getMethod("hMI");
            childrenOf.setAccessible(true);
            Object children = childrenOf.invoke(node);
            if (children instanceof java.util.List<?> members)
                for (Object child : members)
                    complete &= readNestedComponents(owner, child, out, sidechains, seen, context, path, owners, atomOwners, depth + 1);
        } catch (NoSuchMethodException leaf) {
            // No stored component children.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("stored nested component read", failure);
        }
        return complete;
    }

    private static JSONObject readNestedChain(Object owner, Object chain, String kind, int index,
            String path, java.util.IdentityHashMap<Object, JSONObject> context,
            java.util.IdentityHashMap<Object, JSONObject> owners,
            java.util.IdentityHashMap<Object, JSONObject> atomOwners, int chainPosition) {
        Object stored = modelGet(chain, "nY");
        JSONArray devices = new JSONArray();
        if (stored != null) {
            try {
                Class<?> wrapper = classForName("com.bitwig.flt.document.api.document.ApiDeviceChain");
                java.lang.reflect.Constructor<?> constructor = wrapper.getDeclaredConstructor(
                        classForName("com.bitwig.flt.document.core.master.device.Wju"));
                constructor.setAccessible(true);
                DeviceChain api = (DeviceChain) constructor.newInstance(stored);
                java.util.List<?> members = api.getDevices();
                for (int i = 0; i < members.size(); i++) {
                    Device member = (Device) members.get(i);
                    Object memberTarget = ((ApiObject) member).getTarget();
                    if (owners != null && memberTarget != null)
                        owners.put(memberTarget, new JSONObject().put("path", path)
                                .put("chain", chainPosition).put("device", i));
                    devices.put(readDevice(member, i, path, context, owners, atomOwners));
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("stored nested chain wrapper", failure);
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
                .put("label", modelGet(source, "IxG")).put("resolved", false);
    }

    // Optional-getter variant: absent methods read as null instead of failing
    // the surrounding read (used where the exact build may legitimately lack
    // a getter on a subclass).
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
        try {
            java.lang.reflect.Constructor<?> constructor = classForName("com.bitwig.flt.document.api.document.ApiDeviceChain")
                    .getDeclaredConstructor(classForName("com.bitwig.flt.document.core.master.device.Wju"));
            constructor.setAccessible(true);
            return (DeviceChain) constructor.newInstance(stored);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("nested device chain wrapper failed", failure); }
    }

    private static Object deviceComponent(Object owner, String path, Class<?> type) {
        if (path == null || path.isBlank() || path.contains("..") || path.length() > 2048)
            throw new IllegalArgumentException("native component path required");
        try {
            Object value = owner.getClass().getMethod("IYM", String.class, Class.class).invoke(owner, path, type);
            if (value == null || !type.isInstance(value)) throw new IllegalArgumentException("component has the wrong kind: " + path);
            return value;
        } catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("cannot resolve component: " + path, failure); }
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
            Object layer = deviceComponent(owner, step.getString("component"), classForName("com.bitwig.flt.document.core.master.device.YLB"));
            Object stored = modelGet(layer, "nY");
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
        Object grid = deviceComponent(owner, "CONTENTS/MODULES", classForName("com.bitwig.flt.document.core.master.device.ruo"));
        JSONArray modules = new JSONArray();
        for (Object module : (java.util.List<?>) modelGet(grid, "Klm")) {
            Object definition = modelGet(module, "dsw");
            JSONArray inputs = new JSONArray(), outputs = new JSONArray();
            try (java.util.stream.Stream<?> atoms = (java.util.stream.Stream<?>) modelGet(module, "wGq")) {
                for (Object atom : atoms.toList()) {
                    boolean input = automationInstance("com.bitwig.flt.document.core.master.device.BIC", atom);
                    boolean output = automationInstance("com.bitwig.flt.document.core.master.device.oil", atom);
                    if (!input && !output) continue;
                    JSONObject port = new JSONObject().put("component", automationIdentifier(owner, atom));
                    if (input) {
                        Object source = modelGet(atom, "xFs");
                        port.put("source", source == null ? JSONObject.NULL : automationIdentifier(owner, source));
                    }
                    (input ? inputs : outputs).put(port);
                }
            }
            modules.put(new JSONObject().put("component", automationIdentifier(owner, module))
                    .put("module_id", modelGet(definition, "KF3").toString())
                    .put("name", modelGet(module, "HT3"))
                    .put("x", modelGet(module, "AOA")).put("y", modelGet(module, "KF3"))
                    .put("width", modelGet(module, "ap")).put("height", modelGet(module, "XK"))
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

    private static void gridPosition(Object grid, Object module, int x, int y) {
        int width = ((Number) modelGet(module, "ap")).intValue(), height = ((Number) modelGet(module, "XK")).intValue();
        for (Object other : (java.util.List<?>) modelGet(grid, "Klm")) {
            if (other == module) continue;
            int ox = ((Number) modelGet(other, "AOA")).intValue(), oy = ((Number) modelGet(other, "KF3")).intValue();
            int ow = ((Number) modelGet(other, "ap")).intValue(), oh = ((Number) modelGet(other, "XK")).intValue();
            if (x < ox + ow && x + width > ox && y < oy + oh && y + height > oy)
                throw new IllegalArgumentException("module position overlaps another module");
        }
    }

    private static JSONObject executeGrid(String method, Track track, JSONObject params) {
        NativeDevice device = nativeDeviceOnTrack(track, params.getInt("device"));
        Object owner = ((ApiObject) device).getTarget();
        Class<?> gridClass = classForName("com.bitwig.flt.document.core.master.device.ruo");
        Class<?> moduleClass = classForName("com.bitwig.flt.document.core.master.device.zpF");
        Object grid = deviceComponent(owner, "CONTENTS/MODULES", gridClass);
        try {
            if (method.equals("device.grid_add")) {
                if (((java.util.List<?>) modelGet(grid, "Klm")).size() >= 4096) throw new IllegalArgumentException("Grid module limit exceeded");
                java.util.UUID id = java.util.UUID.fromString(params.getString("module_id"));
                int x = gridCoordinate(params, "x"), y = gridCoordinate(params, "y");
                Object module = moduleClass.getMethod("tDS", java.util.UUID.class).invoke(null, id);
                gridPosition(grid, module, x, y);
                java.util.List<?> before = new java.util.ArrayList<>((java.util.List<?>) modelGet(grid, "Klm"));
                // Match the GUI's cloned-content insertion and its own undo operation.
                gridClass.getMethod("jPB", java.util.Collection.class, int.class, int.class, boolean.class,
                        classForName("com.bitwig.base.async.F3z"))
                        .invoke(grid, java.util.List.of(module), x, y, true, null);
                Object inserted = ((java.util.List<?>) modelGet(grid, "Klm")).stream().filter(candidate -> !before.contains(candidate)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Grid module insertion did not add a module"));
                return new JSONObject().put("component", automationIdentifier(owner, inserted)).put("grid", readGrid(device));
            }
            if (method.equals("device.grid_connect")) {
                if (!params.has("output")) throw new IllegalArgumentException("output required (null disconnects)");
                String inputPath = params.getString("input");
                if (!inputPath.startsWith("CONTENTS/MODULES/")) throw new IllegalArgumentException("Grid input required");
                Object input = deviceComponent(owner, inputPath, classForName("com.bitwig.flt.document.core.master.device.BIC"));
                Object output = null;
                if (!params.isNull("output")) {
                    String outputPath = params.getString("output");
                    if (!outputPath.startsWith("CONTENTS/MODULES/")) throw new IllegalArgumentException("Grid output required");
                    output = deviceComponent(owner, outputPath, classForName("com.bitwig.flt.document.core.master.device.oil"));
                }
                // BIC's public edit entry handles cable graph notifications and undo.
                invokeModelSetter(input, "IYM", classForName("Mdm"), output);
                return readGrid(device);
            }
            String component = params.getString("component");
            Object module = deviceComponent(owner, component, moduleClass);
            if (!((java.util.List<?>) modelGet(grid, "Klm")).contains(module)) throw new IllegalArgumentException("module outside selected grid");
            if (method.equals("device.grid_move")) {
                int x = gridCoordinate(params, "x"), y = gridCoordinate(params, "y");
                gridPosition(grid, module, x, y);
                DirectAccess.runInUndo(track.clipTimeline(), () -> {
                    invokeModelSetter(module, "Gcr", int.class, x);
                    invokeModelSetter(module, "a1y", int.class, y);
                });
            } else if (method.equals("device.grid_delete")) {
                // Native deletion cleans up attached cables and owns its undo transaction.
                invokeModelSetter(grid, "IYM", java.util.List.class, java.util.List.of(module));
            } else throw new IllegalArgumentException("unsupported Grid operation");
            return readGrid(device);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native Grid operation failed", failure); }
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
        try {
            Object api = classForName("com.bitwig.flt.document.api.document.ApiNestedDeviceChain")
                    .getConstructor(classForName("com.bitwig.flt.document.core.master.device.YLB")).newInstance(layer);
            return (Mixer) api.getClass().getMethod("getMixer").invoke(api);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("nested mixer wrapper failed", failure); }
    }

    private static JSONObject layerState(Object owner, Object layer) {
        Mixer mixer = layerMixer(layer);
        JSONObject result = new JSONObject().put("component", automationIdentifier(owner, layer))
                .put("name", modelGet(layer, "fNm")).put("title", modelGet(layer, "cc_"));
        if (mixer == null) return result.put("mixer", JSONObject.NULL);
        double db = mixer.getVolume().getValue(Unit.DECIBEL);
        return result.put("mixer", new JSONObject().put("volume", mixer.getVolume().getValue(Unit.RATIO))
                .put("volume_db", Double.isFinite(db) ? db : JSONObject.NULL)
                .put("pan", mixer.getPan().getValue(Unit.PERCENTAGE_OF_RANGE) / 100)
                .put("muted", mixer.getMute().getValue()));
    }

    private static JSONObject editLayer(String method, Track track, JSONObject params) {
        Object owner = ((ApiObject) nativeDeviceOnTrack(track, params.getInt("device"))).getTarget();
        Class<?> layerType = classForName("com.bitwig.flt.document.core.master.device.YLB");
        Object layer = deviceComponent(owner, params.getString("component"), layerType);
        if (method.equals("device.layer_read")) return layerState(owner, layer);
        if (method.equals("device.layer_move")) {
            Object bank = modelGet(layer, "ExU");
            if (!automationInstance("com.bitwig.flt.document.core.master.device.AYz", bank)) throw new IllegalArgumentException("reordering requires a parallel layer");
            java.util.List<?> members = (java.util.List<?>) modelGet(bank, "NFl");
            int source = members.indexOf(layer);
            Object value = params.get("index");
            if (!(value instanceof Integer index) || index < 0 || index >= members.size()) throw new IllegalArgumentException("layer destination index out of range");
            int destination = (Integer) value;
            if (source == destination) return new JSONObject().put("state", "complete").put("data", layerState(owner, layer));
            JSONObject status = newDeviceJob();
            try {
                Class<?> type = classForName("com.bitwig.base.async.rdP");
                Object callback = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, call, args) -> {
                    if (call.getDeclaringClass() == Object.class) return switch (call.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "OpenwigLayerMove";
                    };
                    if (call.getName().equals("taskCompleted") || (call.getName().equals("IYM") && call.getParameterTypes()[0] == Object.class)) {
                        try {
                            if (modelGet(layer, "ExU") != bank || ((java.util.List<?>) modelGet(bank, "NFl")).indexOf(layer) != destination)
                                throw new IllegalStateException("layer did not reach its requested index");
                            status.put("data", layerState(owner, layer)).put("state", "complete");
                        } catch (RuntimeException failure) { status.put("state", "failed").put("error", failure.toString()); }
                    } else if (call.getName().equals("IYM")) status.put("state", "failed").put("error", String.valueOf(args[0]));
                    return null;
                });
                bank.getClass().getMethod("jPB", java.util.List.class, int.class, boolean.class, type)
                        .invoke(bank, java.util.List.of(layer), destination > source ? destination + 1 : destination, false, callback);
            } catch (ReflectiveOperationException failure) {
                status.put("state", "failed").put("error", failure.toString());
                throw new IllegalStateException("native layer reordering failed", failure);
            }
            return new JSONObject(status.toString());
        }
        if (method.equals("device.layer_delete")) {
            Object bank = modelGet(layer, "ExU");
            if (!automationInstance("com.bitwig.flt.document.core.master.device.AYz", bank))
                throw new IllegalArgumentException("deletion requires a parallel layer");
            JSONObject status = newDeviceJob();
            Runnable remove = () -> {
                try {
                    if (modelGet(layer, "ExU") != bank) throw new IllegalArgumentException("layer moved during state preparation");
                    int before = ((java.util.List<?>) modelGet(bank, "NFl")).size();
                    DirectAccess.runInUndo(track.clipTimeline(), () -> {
                        modelGet(bank, "VMm");
                        try {
                            try {
                                classForName("com.bitwig.flt.document.core.master.device.wQg").getMethod("IYM", classForName("pqP")).invoke(null, layer);
                                bank.getClass().getMethod("IYM", layerType, boolean.class).invoke(bank, layer, true);
                            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native layer deletion failed", failure); }
                        } finally { modelGet(bank, "VxG"); }
                    });
                    int after = ((java.util.List<?>) modelGet(bank, "NFl")).size();
                    if (after != before - 1 || modelGetOrNull(layer, "L_") != null) throw new IllegalStateException("layer ownership was not removed");
                    status.put("data", new JSONObject().put("count", after)).put("state", "complete");
                } catch (RuntimeException failure) { status.put("state", "failed").put("error", failure.toString()); }
            };
            try {
                Class<?> errorType = classForName("com.bitwig.base.async.urr");
                Object errors = java.lang.reflect.Proxy.newProxyInstance(errorType.getClassLoader(), new Class<?>[]{errorType}, (proxy, call, args) -> {
                    if (call.getDeclaringClass() == Object.class) return switch (call.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "OpenwigLayerDeleteError";
                    };
                    if (call.getName().equals("IYM")) status.put("state", "failed").put("error", String.valueOf(args[0]));
                    return null;
                });
                classForName("com.bitwig.flt.document.core.master.ymV")
                        .getMethod("IYM", java.util.Collection.class, Runnable.class, errorType)
                        .invoke(null, java.util.List.of(layer), remove, errors);
            } catch (ReflectiveOperationException failure) {
                status.put("state", "failed").put("error", failure.toString());
                throw new IllegalStateException("layer state preparation failed", failure);
            }
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
            if (changes.has("name")) invokeModelSetter(layer, "Qcu", String.class, changes.getString("name"));
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
        try {
            Class<?> table = classForName("com.bitwig.flt.packaging.core.MD");
            for (Object entry : (java.util.List<?>) table.getMethod("IYM").invoke(null)) {
                if (!((Enum<?>) modelGet(entry, "Gcr")).name().equals("MODULATOR")) continue;
                result.put(new JSONObject().put("name", modelGet(entry, "jPB"))
                        .put("modulator_id", modelGet(entry, "IYM").toString()));
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("modulator catalog unavailable", failure); }
        return result;
    }

    private static java.lang.reflect.Method declaredMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Look on the superclass.
            }
        }
        throw new IllegalStateException("native method missing: " + type.getName() + "." + name);
    }

    private static Object invokeNative(Object target, java.lang.reflect.Method method, Object... args) {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("native call failed: " + method.getName(), failure.getCause());
        } catch (IllegalAccessException failure) { throw new IllegalStateException("native call denied: " + method.getName(), failure); }
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

    private static java.util.List<?> modulatorList(Object owner) {
        Object bank = modelGetOrNull(owner, "YGC");
        if (bank == null) throw new IllegalArgumentException("device has no modulator bank");
        return (java.util.List<?>) modelGet(bank, "Klm");
    }

    // Hx3 source components of one modulator, keyed by their stored component key.
    private static java.util.Map<String, Object> modulationSources(Object modulator) {
        java.util.Map<String, Object> sources = new java.util.LinkedHashMap<>();
        Object children = modelGetOrNull(modelGet(modulator, "dsw"), "hMI");
        if (children instanceof java.util.List<?> list)
            for (Object child : list)
                if (automationInstance("com.bitwig.flt.document.core.master.device.Hx3", child))
                    sources.put(String.valueOf(modelGetOrNull(child, "az_")), child);
        return sources;
    }

    private static java.util.Map<String, Object> modulatorAtoms(Object modulator) {
        Class<?> decimal = classForName("Ob"), bool = classForName("KOL"), indexed = classForName("mqD");
        Object contents = modelGet(modulator, "dsw");
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
        String type = automationInstance("Ob", atom) ? "ApiDecimalValueAtom" : automationInstance("KOL", atom) ? "ApiBooleanValueAtom"
                : automationInstance("mqD", atom) ? "ApiIndexedValueAtom" : null;
        if (type == null) throw new IllegalArgumentException("unsupported modulator parameter kind");
        try {
            Class<?> wrapper = classForName("com.bitwig.flt.document.api.document." + type);
            return wrapper.getConstructors()[0].newInstance(atom);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("parameter wrapper failed", failure); }
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
            if (wrapper instanceof EnumParameter && valid) {
                // dZZ.F1E() is the stored enum type; aJ.Trh() its entry count.
                int count = ((Number) modelGet(modelGet(atom, "F1E"), "Trh")).intValue();
                valid = (Integer) value >= 0 && (Integer) value < count;
            }
            if (!valid) throw new IllegalArgumentException("invalid value for modulator parameter: " + name);
            wrappers.put(name, wrapper);
        }
        return wrappers;
    }

    private static void applyModulatorValues(java.util.Map<String, Object> wrappers, JSONObject changes, String unitName) {
        Unit unit = unitByName(unitName);
        for (java.util.Map.Entry<String, Object> entry : wrappers.entrySet()) {
            Object value = changes.get(entry.getKey());
            if (entry.getValue() instanceof DoubleParameter p) p.setValue(fromClientValue(((Number) value).doubleValue(), unitName), unit);
            else if (entry.getValue() instanceof BooleanParameter p) p.setValue((Boolean) value);
            else if (entry.getValue() instanceof EnumParameter p) p.setValue((Integer) value);
        }
    }

    private static Object bindingFor(Object source, Object atom) {
        for (Object entry : (java.util.List<?>) modelGet(source, "gTC")) {
            Object reference = modelGetOrNull(entry, "kxC");
            if (reference != null && modelGetOrNull(reference, "XYl") == atom) return entry;
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
        Object bank = modelGet(owner, "YGC");
        Class<?> modulatorType = classForName("com.bitwig.flt.document.core.master.device.gDz");
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
            if (params.has("x") && invokeNative(bank, declaredMethod(bank.getClass(), "IYM", int.class, int.class), x, y) != null)
                throw new IllegalArgumentException("modulator cell is occupied");
            Object created = invokeNative(null, declaredMethod(modulatorType, "tDS", java.util.UUID.class), id);
            if (created == null || modelGet(created, "dsw") == null) throw new IllegalStateException("modulator definition did not load");
            // Initial values are written to the staged modulator, so the engine
            // instantiates it with them. A write to a just-inserted modulator in
            // a separate, immediately following event was live-verified to be
            // dropped by the engine (document and audio then disagree).
            if (params.has("values")) applyModulatorValues(stageModulatorValues(modulatorAtoms(created), params, null),
                    params.getJSONObject("values"), null);
            java.util.List<?> before = new java.util.ArrayList<>(modulators);
            // Match the GUI insertion point (roB): cloned content, native "Add modulator".
            invokeNative(bank, declaredMethod(bank.getClass(), "IYM", java.util.Collection.class, int.class, int.class,
                    boolean.class, classForName("com.bitwig.base.async.F3z")), java.util.List.of(created), x, y, true, null);
            java.util.List<?> after = modulatorList(owner);
            Object inserted = after.stream().filter(candidate -> !before.contains(candidate)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("modulator insertion did not add a modulator"));
            if (after.size() != before.size() + 1) throw new IllegalStateException("modulator count mismatch after insertion");
            JSONObject result = new JSONObject().put("modulator", after.indexOf(inserted)).put("count", after.size());
            if (params.has("values")) result.put("values", modulatorValues(modulatorAtoms(inserted), unitByName(null), null));
            return result;
        }
        int index = integerField(params, "modulator", 0, Math.max(0, modulators.size() - 1));
        if (index >= modulators.size()) throw new IllegalArgumentException("modulator index out of range");
        Object modulator = modulators.get(index);
        switch (method) {
            case "device.modulator_delete" -> {
                invokeModelSetter(bank, "IYM", java.util.List.class, java.util.List.of(modulator));
                if (modulatorList(owner).contains(modulator) || modelGetOrNull(modulator, "L_") != null)
                    throw new IllegalStateException("modulator was not removed");
                return new JSONObject().put("count", modulatorList(owner).size());
            }
            case "device.modulator_move" -> {
                int x = integerField(params, "x", 0, 4095), y = integerField(params, "y", 0, 2);
                int dx = x - ((Number) modelGet(modulator, "AOA")).intValue(), dy = y - ((Number) modelGet(modulator, "KF3")).intValue();
                if (dx == 0 && dy == 0) return new JSONObject().put("modulator", index).put("x", x).put("y", y);
                if (invokeNative(bank, declaredMethod(bank.getClass(), "IYM", int.class, int.class), x, y) != null)
                    throw new IllegalArgumentException("modulator cell is occupied");
                invokeNative(bank, declaredMethod(bank.getClass(), "IYM", modulatorType, java.util.Collection.class, int.class, int.class,
                        classForName("com.bitwig.base.async.F3z")), modulator, java.util.List.of(modulator), dx, dy, null);
                if (((Number) modelGet(modulator, "AOA")).intValue() != x || ((Number) modelGet(modulator, "KF3")).intValue() != y)
                    throw new IllegalStateException("modulator did not reach its requested cell");
                return new JSONObject().put("modulator", modulatorList(owner).indexOf(modulator)).put("x", x).put("y", y);
            }
            case "device.modulator_parameters" -> {
                java.util.Map<String, Object> atoms = modulatorAtoms(modulator);
                String unitName = params.optString("unit", null);
                if (params.has("values")) {
                    java.util.Map<String, Object> wrappers = stageModulatorValues(atoms, params, unitName);
                    DirectAccess.runInUndo(track.clipTimeline(), () -> applyModulatorValues(wrappers, params.getJSONObject("values"), unitName));
                }
                return new JSONObject().put("modulator", index).put("values", modulatorValues(atoms, unitByName(unitName), unitName));
            }
            case "device.modulator_bind", "device.modulator_unbind" -> {
                java.util.Map<String, Object> sources = modulationSources(modulator);
                String key;
                if (params.has("source")) {
                    key = params.getString("source");
                    if (!sources.containsKey(key)) throw new IllegalArgumentException("unknown modulation source " + key + "; available " + sources.keySet());
                } else if (sources.size() == 1) key = sources.keySet().iterator().next();
                else throw new IllegalArgumentException("modulation source required; available " + sources.keySet());
                Object source = sources.get(key);
                Object targetOwner = params.has("target_device") ? modulatorOwner(track, params, "target_device") : owner;
                String parameter = params.optString("target_parameter", "");
                if (parameter.isBlank() || parameter.startsWith("/") || parameter.contains("..") || parameter.length() > 1024)
                    throw new IllegalArgumentException("target_parameter path required");
                Object atom = deviceComponent(targetOwner, "CONTENTS/" + parameter, classForName("m1C"));
                if (method.equals("device.modulator_unbind")) {
                    if (bindingFor(source, atom) == null) throw new IllegalArgumentException("no modulation mapping to " + parameter);
                    invokeNative(source, declaredMethod(source.getClass(), "Gcr", classForName("m1C")), atom);
                    if (bindingFor(source, atom) != null) throw new IllegalStateException("modulation mapping was not removed");
                    return new JSONObject().put("modulator", index).put("source", key).put("removed", true);
                }
                Object amountValue = params.opt("amount");
                if (!(amountValue instanceof Number amount) || amountValue instanceof Boolean || !Double.isFinite(amount.doubleValue())
                        || Math.abs(amount.doubleValue()) > 1)
                    throw new IllegalArgumentException("amount must be a finite number within -1..1");
                if (!(Boolean) invokeNative(source, declaredMethod(source.getClass(), "IYM", classForName("sQi")), atom))
                    throw new IllegalArgumentException("Bitwig does not allow this modulation target: " + parameter);
                Object range = modelGetOrNull(modelGetOrNull(atom, "VMm"), "Nwn");
                double span = range instanceof Number number ? number.doubleValue() : Double.NaN;
                if (!Double.isFinite(span) || span <= 0) throw new IllegalArgumentException("target has no modulation range");
                // AwX.jPB(m1C,double): creates or updates the mapping in Bitwig's own
                // (mergeable) mapping transaction; amount is native, clamped to +/-span.
                invokeNative(source, declaredMethod(source.getClass(), "jPB", classForName("m1C"), double.class),
                        atom, amount.doubleValue() * span);
                Object binding = bindingFor(source, atom);
                if (binding == null) throw new IllegalStateException("modulation mapping was not created");
                return new JSONObject().put("modulator", index).put("source", key)
                        .put("amount", ((Number) modelGet(binding, "Qcu")).doubleValue() / span)
                        .put("native_amount", modelGet(binding, "Qcu"));
            }
            default -> throw new IllegalArgumentException("unsupported modulator operation");
        }
    }

    private static JSONArray chainBanks(Object owner) {
        JSONArray banks = new JSONArray();
        collectChainBanks(owner, modelGetOrNull(owner, "bhG"), banks, new java.util.IdentityHashMap<>(), 0);
        if (automationInstance("com.bitwig.flt.document.core.master.device.vgq", owner)) {
            Object plugin = modelGet(owner, "Oof");
            collectChainBanks(owner, modelGetOrNull(plugin, "vCL"), banks, new java.util.IdentityHashMap<>(), 0);
        }
        return banks;
    }

    private static void collectChainBanks(Object owner, Object node, JSONArray banks,
            java.util.IdentityHashMap<Object, Boolean> seen, int depth) {
        if (node == null || depth > 32 || seen.size() > 10000 || seen.put(node, true) != null) return;
        if (automationInstance("com.bitwig.flt.document.core.master.device.EhV", node)) {
            banks.put(new JSONObject().put("component", jsonModelValue(automationIdentifier(owner, node)))
                    .put("count", ((java.util.List<?>) modelGet(node, "NFl")).size())
                    .put("can_create_layer", automationInstance("com.bitwig.flt.document.core.master.device.AYz", node)));
            return;
        }
        if (automationInstance("com.bitwig.flt.document.core.master.device.YLB", node)) return;
        Object children = modelGetOrNull(node, "hMI");
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

    private static Olb automationTimeline(DoubleParameter parameter) {
        W2C lane = ((m1C) ((ApiObject) parameter).getTarget()).Noo();
        return lane == null ? null : lane.oLP().ExU();
    }

    private static JSONArray automationPoints(DoubleParameter parameter) {
        JSONArray result = new JSONArray();
        Olb timeline = automationTimeline(parameter);
        if (timeline == null) return result;
        aB range = ((Ob) ((ApiObject) parameter).getTarget()).ViH();
        for (Object item : timeline.IYM()) {
            GUX point = (GUX) item;
            result.put(new JSONObject().put("beat", point.tS())
                    .put("normalized", range.IYM(point.IYM()))
                    .put("curvature", point.dT_())
                    .put("interpolation", point.AOA() == HWV.IYM ? "hold" : "linear"));
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
                Olb timeline = automationTimeline(parameter);
                if (timeline == null) throw new IllegalStateException("automation lane missing after document commit");
                // Bitwig adds a beat-zero anchor when the requested lane starts
                // later. Preserve it, and shape only the points the caller sent.
                int offset = timeline.IYM().size() == points.length() + 1
                        && points.getJSONArray(0).getDouble(0) > 0
                        && ((GUX) timeline.IYM().get(0)).tS() == 0 ? 1 : 0;
                if (timeline.IYM().size() != points.length() + offset)
                    throw new IllegalStateException("automation point count mismatch after document commit");
                for (int i = 0; i < points.length(); i++) {
                    if (Math.abs(((GUX) timeline.IYM().get(i + offset)).tS()
                            - points.getJSONArray(i).getDouble(0)) > 1e-6)
                        throw new IllegalStateException("automation point time mismatch after document commit");
                }
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    aB range = ((Ob) ((ApiObject) parameter).getTarget()).ViH();
                    for (int i = 0; i < points.length(); i++) {
                        JSONArray input = points.getJSONArray(i);
                        GUX point = (GUX) timeline.IYM().get(i + offset);
                        String unit = params.optString("unit", "");
                        if (!params.getString("param").equals("tempo") && unit.isBlank()) {
                            point.IYM(range.jPB(input.getDouble(1)));
                        }
                        boolean hold = input.optString(3, "linear").equals("hold");
                        point.IYM(hold ? HWV.IYM : HWV.jPB);
                        // Native serialization discards curvature on hold segments.
                        point.jPB(hold ? 0 : input.optDouble(2, 0));
                    }
                });
            } else if (write && params.optBoolean("clear", false)) {
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    Olb existing = automationTimeline(parameter);
                    if (existing != null) existing.W1k();
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
                    Olb existing = automationTimeline(parameter);
                    if (existing != null) existing.W1k();
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
        // tjt.bhG() is a pure getter for the native device's CONTENTS root.
        Object contents;
        try {
            contents = target.getClass().getMethod("bhG").invoke(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("native device contents unavailable", error);
        }
        Class<?> decimal = classForName("Ob");
        Class<?> bool = classForName("KOL");
        Class<?> indexed = classForName("mqD");
        java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.List<Object> found = new java.util.ArrayList<>();
        walkAtoms(contents, 0, seen, found, decimal, bool, indexed);
        java.lang.reflect.Method pathOf = identifierPathMethod(target.getClass());
        JSONArray out = new JSONArray();
        for (Object atom : found) {
            JSONObject item = new JSONObject().put("kind", atomKind(atom, decimal, bool, indexed));
            if (pathOf != null) {
                try {
                    String path = (String) pathOf.invoke(target, atom);
                    if (path != null && path.startsWith("CONTENTS/")) {
                        item.put("path", path.substring("CONTENTS/".length()));
                    } else {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
            } else {
                break;
            }
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

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            return null;
        }
    }

    private static String atomKind(Object atom, Class<?> decimal, Class<?> bool, Class<?> indexed) {
        if (decimal != null && decimal.isInstance(atom)) return "double";
        if (bool != null && bool.isInstance(atom)) return "boolean";
        if (indexed != null && indexed.isInstance(atom)) return "enum";
        return "unknown";
    }

    private static java.lang.reflect.Method identifierPathMethod(Class<?> owner) {
        // Exact 6.1.5 signature: IYM(BIp) renders a preset identifier path.
        Class<?> component = classForName("com.bitwig.flt.document.core.iface.BIp");
        if (component == null) return null;
        try {
            // The public implementation can be inherited from an interface.
            return owner.getMethod("IYM", component);
        } catch (NoSuchMethodException ignored) {
            // Fall back to non-public superclass implementations.
        }
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod("IYM", component);
                if (method.getReturnType() != String.class) continue;
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Look on the superclass.
            }
        }
        return null;
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
        if (automationInstance("com.bitwig.flt.document.core.master.device.ruo", node)) {
            for (Object module : (java.util.List<?>) modelGet(node, "Klm"))
                walkAtoms(modelGet(module, "dsw"), depth + 1, seen, found, decimal, bool, indexed);
            return;
        }
        try {
            Object value = node.getClass().getMethod("hMI").invoke(node);
            if (value instanceof java.util.List<?> children) {
                for (Object child : children) {
                    walkAtoms(child, depth + 1, seen, found, decimal, bool, indexed);
                }
            }
        } catch (NoSuchMethodException ignored) {
            // Leaf model node.
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("device component walk failed", error);
        }
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
        String stage = "resolve device";
        try {
            java.util.UUID id = DirectAccess.factoryDeviceId(name + ".bwdevice");
            if (id == null) {
                throw new IllegalArgumentException("unknown Bitwig factory device: " + name);
            }
            stage = "create device";
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
        Object comment = modelGetOrNull(rawTrack, "lzE");
        Object channel = modelGetOrNull(rawTrack, "fcV");
        Object solo = channel == null ? null : modelGetOrNull(channel, "oLP");
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
                Object comment = modelGetOrNull(rawScene, "Vc");
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
        Object comment = modelGetOrNull(rawTrack, "lzE");
        Object channel = modelGetOrNull(rawTrack, "fcV");
        Object solo = channel == null ? null : modelGetOrNull(channel, "oLP");
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
        int slotCount;
        try {
            Object target = ((ApiObject) view).getTarget();
            slotCount = (Integer) target.getClass().getMethod("ce_").invoke(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("launcher slot count unavailable for " + path, error);
        }
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
        for (Object lane : (java.util.List<?>) modelGet(modelGet(trackTarget, "HMG"), "IYM")) {
            Object target = modelGet(lane, "DvC");
            JSONObject item = new JSONObject().put("track_path", path)
                    .put("target_class", target == null ? JSONObject.NULL : target.getClass().getName())
                    .put("target_name", target == null ? JSONObject.NULL : jsonModelValue(modelGet(target, "rqQ")))
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
            Object timeline = modelGet(lane, "oLP");
            if (timeline == null) {
                out.put(item.put("status", "null_timeline").put("unit", JSONObject.NULL)
                        .put("points", new JSONArray()).put("point_count", 0));
                continue;
            }
            Object type = modelGet(modelGet(timeline, "Nsg"), "NFl");
            boolean midi = automationInstance("YOq", target);
            boolean plugin = automationInstance("com.bitwig.flt.document.core.master.device.GAN", target);
            String unit = target == pan || midi || plugin ? "normalized" : automationUnit(type);
            if (midi) {
                Object kind = modelGet(target, "ViH");
                item.put("expression", enumField(kind, "Gcr") ? "channel_controller" :
                        enumField(kind, "jPB") ? "channel_pressure" : "pitch_bend")
                        .put("channel", modelGet(target, "wtf"));
                if (enumField(kind, "Gcr")) item.put("controller", modelGet(target, "CaE"));
            }
            if (plugin) item.put("parameter_id", jsonModelValue(modelGet(target, "KF3")));
            JSONArray points = readModelPoints(timeline, type, unit);
            out.put(item.put("unit", jsonModelValue(unit)).put("time_unit", "beats")
                    .put("points", points).put("point_count", points.length()).put("status", "read"));
        }
        return out;
    }

    private static Object jsonModelValue(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static boolean automationInstance(String name, Object value) {
        Class<?> type = classForName(name);
        return value != null && type != null && type.isInstance(value);
    }

    private static String automationIdentifier(Object owner, Object target) {
        if (owner == null || target == null) return null;
        java.lang.reflect.Method method = identifierPathMethod(owner.getClass());
        if (method == null) return null;
        try { return (String) method.invoke(owner, target); }
        catch (ReflectiveOperationException absent) { return null; }
    }

    private static String automationUnit(Object type) {
        if (!automationInstance("aB", type)) return null;
        Object domain = modelGet(type, "ppu");
        if (enumField(domain, "jPB")) return "decibel";
        if (enumField(domain, "Gcr") || enumField(domain, "Qcu")) return "semitones";
        Object unit = modelGet(type, "Mqo");
        if (enumField(unit, "nsm")) return "beats";
        if (enumField(unit, "eUA")) return "bpm";
        if (enumField(unit, "xeZ")) return "seconds";
        if (enumField(unit, "wtf")) return "hertz";
        return "linear";
    }

    // For raw note-expression or clip-automation timelines (lGQ subclasses).
    // Expression-specific unit overrides can use readModelPoints directly.
    private static JSONArray readExpressionPoints(Object timeline) {
        if (timeline == null) return new JSONArray();
        Object type = modelGet(timeline, "eUA");
        return readModelPoints(timeline, type, automationUnit(type));
    }

    private static JSONArray readModelPoints(Object timeline, Object type, String unit) {
        JSONArray points = new JSONArray();
        if (timeline == null) return points;
        if (!automationInstance("jmN", timeline)) {
            for (Object point : (java.util.List<?>) modelGet(timeline, "IYM"))
                points.put(readModelPoint(point, type, unit));
            return points;
        }
        try {
            Class<?> listenerType = classForName("htX");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("automationWillBePlayedBack") || name.equals("automationAfterEndPointWillBePlayedBack"))
                            points.put(readModelPoint(args[0], type, unit));
                        if (name.equals("ne_")) return true;
                        if (name.equals("hK_")) return false;
                        if (name.equals("hashCode")) return System.identityHashCode(proxy);
                        if (name.equals("equals")) return proxy == args[0];
                        if (name.equals("toString")) return "Openwig automation reader";
                        return null; // All other exact-build htX/nFh methods return void and are no-op.
                    });
            Object context = modelGet(timeline, "Nsg");
            Object filter = classForName("QsC").getMethod("IYM").invoke(null);
            double end = classForName("dZ").getField("jPB").getDouble(null);
            java.lang.reflect.Method walk = classForName("jmN").getMethod("IYM",
                    classForName("com.bitwig.flt.document.core.iface.fui"), double.class, double.class,
                    classForName("TCM"), boolean.class, boolean.class, listenerType);
            walk.invoke(timeline, context, 0.0, end, filter, true, true, listener);
            return points;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("native composite automation read", failure);
        }
    }

    private static JSONObject readModelPoint(Object point, Object type, String unit) {
        if (point == null) return new JSONObject().put("status", "null_point")
                .put("time", JSONObject.NULL).put("value", JSONObject.NULL);
        JSONObject out = new JSONObject().put("time", modelGet(point, "tS"))
                .put("point_class", point.getClass().getName());
        if (automationInstance("GUX", point)) {
            double raw = ((Number) modelGet(point, "IYM")).doubleValue();
            return out.put("kind", "real").put("raw_value", raw)
                    .put("value", convertAutomationReal(raw, type, unit))
                    .put("interpolation", "linear"); // This is the exporter's interpolation.
        }
        if (automationInstance("MJZ", point))
            return out.put("kind", "boolean").put("value", modelGet(point, "eUA"));
        if (automationInstance("NME", point)) {
            int raw = ((Number) modelGet(point, "Wqw")).intValue();
            try {
                Object value = classForName("aJ").getMethod("jPB", int.class).invoke(type, raw);
                return out.put("kind", "enum").put("raw_value", raw).put("value", value);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("native enum automation conversion", failure);
            }
        }
        if (automationInstance("xmH", point))
            return out.put("kind", "integer").put("value", modelGet(point, "Wqw"));
        if (automationInstance("pmA", point))
            return out.put("kind", "time_signature").put("value", JSONObject.NULL)
                    .put("numerator", modelGet(point, "dU_")).put("denominator", modelGet(point, "Qcu"));
        return out.put("status", "unsupported_point_type").put("value", JSONObject.NULL);
    }

    private static double convertAutomationReal(double raw, Object type, String unit) {
        if (!automationInstance("aB", type))
            throw new IllegalStateException("real automation requires aB value type");
        try {
            if ("normalized".equals(unit))
                return ((Number) classForName("aB").getMethod("IYM", double.class).invoke(type, raw)).doubleValue();
            String field = switch (unit == null ? "linear" : unit) {
                case "percent" -> "jPB";
                case "decibel" -> "AOA";
                case "hertz" -> "wtf";
                case "semitones" -> "Qcu";
                case "seconds" -> "xeZ";
                case "beats" -> "nsm";
                case "bpm" -> "eUA";
                default -> "IYM";
            };
            Object domain = modelGet(type, "ppu");
            Object destination = classForName("fH").getField(field).get(null);
            return ((Number) classForName("fK").getMethod("IYM", double.class,
                    classForName("fG"), classForName("fH")).invoke(null, raw, domain, destination)).doubleValue();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("native real automation conversion", failure);
        }
    }

    private static boolean enumField(Object value, String field) {
        try { return value != null && value == ((Enum<?>) value).getDeclaringClass().getField(field).get(null); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("model enum field " + field, failure); }
    }


    // Read-only PNT/Tc getters from exact original 6.1.5. Both vuj MIDI notes
    // and KoO audio events inherit these fields. No expression creation calls.
    private static JSONObject readNoteExtras(Object model) {
        if (model == null) return new JSONObject().put("status", "null_note_model");
        int recurrence = ((Number) modelGet(model, "QmZ")).intValue();
        int period = 1 + (recurrence & 7);
        JSONArray iterations = new JSONArray();
        for (int i = 0; i < period; i++) iterations.put((recurrence & (1 << (3 + i))) != 0);
        int repeat = ((Number) modelGet(model, "XYl")).intValue();
        JSONObject out = new JSONObject()
                .put("chance_enabled", modelGet(model, "HT3"))
                .put("chance", modelGet(model, "Mqo"))
                .put("occurrence_enabled", modelGet(model, "WEC"))
                .put("occurrence", ((Enum<?>) modelGet(model, "tY1")).name())
                .put("recurrence_enabled", modelGet(model, "kAg"))
                .put("recurrence", recurrence)
                .put("recurrence_period", period)
                .put("recurrence_iterations", iterations)
                .put("repeat_enabled", modelGet(model, "ap"))
                .put("repeat_count", repeat)
                .put("repeat_subdivisions", Math.abs(repeat) + 1)
                .put("repeat_curve", modelGet(model, "QPT"))
                .put("content_start", modelGet(model, "ySK"));
        JSONArray expressions = new JSONArray();
        Object content = modelGet(model, "smZ");
        if (content != null) {
            for (Object timeline : (java.util.List<?>) modelGet(content, "CZR")) {
                String name = ((Enum<?>) modelGet(timeline, "bXk")).name();
                String unit = name.equals("TRANSPOSE") || name.equals("FORMANT") ? "semitones" : "linear";
                Object type = modelGet(timeline, "eUA");
                // This uses the same fK.IYM(raw, fG source, fH destination)
                // conversion as ApiDawProjectExporter. In particular, gain is
                // converted out of its CUBE_ROOT storage domain to linear.
                JSONArray points = readModelPoints(timeline, type, unit);
                java.util.List<?> stored = (java.util.List<?>) modelGet(timeline, "IYM");
                // Exporter flattens interpolation to linear. Retain the actual
                // model interpolation/curve in JSON for native read fidelity.
                for (int i = 0; i < stored.size(); i++) {
                    Object point = stored.get(i);
                    if (automationInstance("GUX", point)) {
                        points.getJSONObject(i)
                                .put("interpolation", ((Enum<?>) modelGet(point, "AOA")).name().toLowerCase(java.util.Locale.ROOT))
                                .put("curvature", modelGet(point, "dT_"));
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
        Object storedWarp = modelGet(model, "p3P");
        int count = ((Number) modelGet(storedWarp, "WEC")).intValue();
        try {
            java.lang.reflect.Method markerAt = storedWarp.getClass().getMethod("Gcr", int.class);
            markerAt.setAccessible(true);
            for (int i = 0; i < count; i++) {
                Object marker = markerAt.invoke(storedWarp, i);
                markers.put(new JSONObject().put("time", modelGet(marker, "tS"))
                        .put("content_time", modelGet(marker, "IYM")));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("stored warp marker read", failure);
        }
        return markers;
    }

    private static JSONObject readAudioEvent(com.bitwig.extension.api.project.timeline.AudioNote event) {
        Object model = ((ApiObject) event).getTarget();
        Object sample = modelGet(model, "xFs");
        java.io.File file = sample == null ? null : event.getAudioFile();
        JSONObject out = new JSONObject().put("start", event.getTime())
                .put("duration", event.getDuration()).put("muted", event.isMuted())
                .put("file", file == null ? JSONObject.NULL : file.getAbsolutePath())
                .put("sample_file_status", file == null ? "unresolved" : "resolved")
                .put("sample_rate", sample == null ? JSONObject.NULL : event.getSampleRate()).put("channels", sample == null ? JSONObject.NULL : event.getChannelCount())
                .put("sample_duration_seconds", sample == null ? JSONObject.NULL : event.getAudioFileDurationInSeconds())
                .put("algorithm", modelGet(modelGet(model, "TdG"), "jPB"))
                .put("fade_in", modelGet(model, "dM_"))
                .put("fade_out", modelGet(model, "KF3"))
                .put("fade_time_unit", "beats")
                .put("play_start_offset", modelGet(model, "xTe"))
                .put("warp_mode", event.getWarpMode().name())
                .put("preserve_transients_mode", event.getPreserveTransientsMode().name())
                .put("transient_rate", event.getTransientRate().name())
                .put("warp_grain_size", event.getWarpGrainSize().name())
                .put("warp_tail_mode", event.getWarpTailMode().name())
                .put("preview_onsets", event.getPreviewOnsets())
                .put("preserve_formants", event.getPreserveFormants())
                .put("formant_envelope_resolution", event.getFormantEnvelopeResolution())
                .put("reverse", modelGet(modelGet(model, "jsk"), "wtf"))
                .put("sample_play_start_seconds", modelGet(model, "IT3"))
                .put("sample_play_stop_seconds", modelGet(model, "Klm"));
        com.bitwig.extension.api.project.timeline.WarpMarkers warp = event.getWarpEvents();
        JSONArray markers = storedWarpMarkers(model);
        if (!event.getWarpMode().name().equals("RAW")) {
            double mappedStop = modelDoubleArgument(modelGet(model, "p3P"), "IYM",
                    ((Number) modelGet(model, "ySK")).doubleValue() + event.getDuration());
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
                .put("velocity_spread", modelGet(raw, "Qcu")).put("muted", note.isMuted());
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
            Class<?> decimal = classForName("Ob");
            if (decimal == null || target == null) {
                continue;
            }
            java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
            java.util.List<Object> found = new java.util.ArrayList<>();
            Object contents;
            try {
                contents = target.getClass().getMethod("bhG").invoke(target);
            } catch (ReflectiveOperationException missing) {
                continue;
            }
            walkAtoms(contents, 0, seen, found, decimal, null, null);
            java.lang.reflect.Method pathOf = identifierPathMethod(target.getClass());
            if (pathOf == null) {
                continue;
            }
            for (Object atom : found) {
                String name;
                try {
                    name = (String) pathOf.invoke(target, atom);
                } catch (Throwable unreadable) {
                    continue;
                }
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
            Olb timeline = automationTimeline(parameter);
            if (timeline == null) {
                return;
            }
            int points = timeline.IYM().size();
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
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("value must be between 0 and 1");
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
                Object channel = modelGet(rawTrack, "fcV");
                modifyModel((ApiObject) track, () -> invokeModelSetter(channel, "jPB", boolean.class, on));
            }
            case "comment" -> {
                String comment = params == null ? "" : params.optString("comment", "");
                modifyModel((ApiObject) track, () -> invokeModelSetter(rawTrack, "a1y", String.class, comment));
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
            case "device.modulator_add", "device.modulator_delete", "device.modulator_move", "device.modulator_parameters", "device.modulator_bind", "device.modulator_unbind", "device.layer_read", "device.layer_update", "device.layer_delete", "device.layer_move", "device.grid_add", "device.grid_move", "device.grid_delete", "device.grid_connect", "device.grid_read", "device.read", "device.chain_banks", "device.create_layer", "device.count", "device.snapshot", "device.set_enabled", "device.insert_factory", "device.insert_plugin", "device.plugin_parameters", "device.export_plugin_state", "device.plugin_state_status", "device.operation_status", "device.capture_plugin_state",
                 "device.set_parameters", "device.get_parameters", "device.rename",
                 "device.set_expanded", "device.delete", "device.list_parameters" ->
                    executeDevice(method, p);
            case "clip.count", "clip.note_count", "clip.create_arranger_with_notes" ->
                    executeClip(method, p);
            default -> throw new IllegalArgumentException("unsupported document method: " + method);
        };
    }

    private static JSONObject executeDevice(String method, JSONObject params) {
        ApiProject project = project();
        try {
            Track targetTrack = deviceScope(resolveTrack(project, params), params);
            String path = params.optString("path", null);
            return switch (method) {
                case "device.layer_read", "device.layer_update", "device.layer_delete", "device.layer_move" -> editLayer(method, targetTrack, params);
                case "device.modulator_add", "device.modulator_delete", "device.modulator_move", "device.modulator_parameters", "device.modulator_bind", "device.modulator_unbind" -> editModulator(method, targetTrack, params);
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
                        Object bank = deviceComponent(owner, params.getString("component"), classForName("com.bitwig.flt.document.core.master.device.AYz"));
                        Object[] created = new Object[1];
                        DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> {
                            try {
                                Object wrapper = classForName("com.bitwig.flt.document.api.document.ApiParallelNestedDeviceChainContainer")
                                        .getConstructor(classForName("com.bitwig.flt.document.core.master.device.AYz")).newInstance(bank);
                                created[0] = ((ApiObject) wrapper.getClass().getMethod("createChain").invoke(wrapper)).getTarget();
                            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native layer creation failed", failure); }
                        });
                        yield new JSONObject().put("component", automationIdentifier(owner, created[0]))
                                .put("count", ((java.util.List<?>) modelGet(bank, "NFl")).size());
                    }
                    yield readDevice(device, index, path, routingContext(project), deviceOwners(project), atomOwnerIndex(project));
                }
                case "device.capture_plugin_state" -> {
                    int index = params.getInt("device");
                    java.util.List<?> devices = targetTrack.deviceChain().getDevices();
                    if (index < 0 || index >= devices.size() || !(devices.get(index) instanceof PluginDevice))
                        throw new IllegalArgumentException("plugin device index required");
                    yield capturePluginState(modelGet(((ApiObject) devices.get(index)).getTarget(), "Oof"));
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
                        Class<?> callbackType = classForName("com.bitwig.base.async.rdP");
                        Object callback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, call, args) -> {
                            if (call.getName().equals("taskCompleted") || (call.getName().equals("IYM") && call.getParameterTypes()[0] == Object.class))
                                status.put("state", "complete").put("bytes", file.length());
                            else if (call.getName().equals("IYM") && args != null && args.length == 1)
                                status.put("state", "failed").put("error", String.valueOf(args[0]));
                            else if (call.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            else if (call.getName().equals("equals")) return proxy == args[0];
                            else if (call.getName().equals("toString")) return "OpenwigPluginStateExport";
                            return null;
                        });
                        pluginStateJobs.put(job, status);
                        Object holder = modelGet(((ApiObject) devices.get(index)).getTarget(), "Oof");
                        holder.getClass().getMethod("IYM", java.io.File.class, callbackType).invoke(holder, file, callback);
                    } catch (ReflectiveOperationException | java.io.IOException failure) {
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
                    Object holder = modelGet(((ApiObject) devices.get(index)).getTarget(), "Oof");
                    java.util.Map<String, DoubleParameter> available = new java.util.HashMap<>();
                    try {
                        java.lang.reflect.Constructor<?> wrapper = classForName("com.bitwig.flt.document.api.document.ApiPluginDeviceParameterAtom")
                                .getConstructor(classForName("com.bitwig.flt.document.core.master.device.GAN"));
                        for (Object atom : (java.util.List<?>) modelGet(holder, "HT3"))
                            available.put(String.valueOf(modelGet(atom, "KF3")), (DoubleParameter) wrapper.newInstance(atom));
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException("plugin parameter wrapper failed", failure); }
                    JSONObject changes = params.optJSONObject("values");
                    if (changes != null) {
                        if (!((oZU) project.getTarget()).KF3() || !((oZU) project.getTarget()).va2())
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
                    yield new JSONObject().put("values", values).put("state_dirty", modelGet(holder, "dJV"))
                            .put("load_state", ((Enum<?>) modelGet(holder, "NFl")).name())
                            .put("available", !available.isEmpty());
                }
                case "device.insert_plugin" -> {
                    String name = params.getString("name");
                    String format = params.getString("format");
                    String id = params.getString("id");
                    if (name.isBlank()) throw new IllegalArgumentException("plugin name required");
                    com.bitwig.extension.api.project.mixer.PluginPreset preset;
                    if (format.equals("vst3")) {
                        if (!id.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("VST3 ID needs 32 hex digits");
                        preset = project.getFactory().createVst3DevicePreset(name, java.util.HexFormat.of().parseHex(id));
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
        Object comment = modelGetOrNull(((ApiObject) scene).getTarget(), "Vc");
        return new JSONObject().put("index", index).put("name", scene.getTitle())
                .put("comment", comment == null ? "" : comment)
                .put("color", color == null ? JSONObject.NULL : color.toHex());
    }

    private static JSONObject editSceneBank(String method, JSONObject params) {
        ApiProject project = project();
        try {
            int before = sceneCount();
            Object root = ((oZU) project.getTarget()).Qjx();
            if (method.equals("scene.snapshot")) {
                JSONArray scenes = new JSONArray();
                for (int i = 0; i < before; i++) scenes.put(sceneSnapshot(project, i));
                return new JSONObject().put("count", before).put("scenes", scenes);
            }
            int index = params.has("index") ? sceneIndex(params, "index", before, method.equals("scene.insert")) : before;
            if (method.equals("scene.insert")) {
                if (before >= 4096) throw new IllegalArgumentException("scene bank limit exceeded");
                java.lang.reflect.Method insert = Yko.class.getDeclaredMethod("Gcr", int.class, classForName("obl"));
                insert.setAccessible(true);
                insert.invoke(root, index, null);
                if (sceneCount() != before + 1) throw new IllegalStateException("scene insertion count mismatch");
            } else if (method.equals("scene.delete")) {
                index = sceneIndex(params, "index", before, false);
                java.lang.reflect.Method delete = Yko.class.getDeclaredMethod("IYM", int[].class);
                delete.setAccessible(true);
                delete.invoke(root, (Object) new int[]{index});
                if (sceneCount() != before - 1) throw new IllegalStateException("scene deletion count mismatch");
                return new JSONObject().put("count", sceneCount());
            } else if (method.equals("scene.move")) {
                index = sceneIndex(params, "index", before, false);
                int destination = sceneIndex(params, "destination", before, false);
                if (index != destination)
                    root.getClass().getMethod("IYM", int[].class, int.class)
                            .invoke(root, new int[]{index}, destination > index ? destination + 1 : destination);
                if (sceneCount() != before) throw new IllegalStateException("scene reorder changed bank size");
                index = destination;
            } else throw new IllegalArgumentException("unsupported scene operation");
            return new JSONObject().put("count", sceneCount()).put("index", index).put("data", sceneSnapshot(project, index));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native scene operation failed", failure); }
        finally { project.dispose(); }
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
                    modifyModel((ApiObject) scene, () -> invokeModelSetter(rawScene, "nsm", String.class, params.getString("comment")));
                }
                if (color != null) scene.setColor(color);
            });
            return sceneSnapshot(project, index);
        } finally { project.dispose(); }
    }

    private static double modelDoubleArgument(Object target, String method, double value) {
        try {
            return ((Number) target.getClass().getMethod(method, double.class).invoke(target, value)).doubleValue();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("native numeric operation " + method, failure);
        }
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
                    modelGet(((ApiObject) clip).getTarget(), "knw");
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
                try {
                    Object raw = modelGet(((ApiObject) clip).getTarget(), "VxG");
                    classForName("com.bitwig.ramona.core.nvG").getMethod("IYM", classForName("com.bitwig.ramona.core.kX1"))
                            .invoke(null, raw);
                    Class<?> wrapper = classForName(clip instanceof AudioClip
                            ? "com.bitwig.flt.document.api.document.ApiAudioNoteClipEvent"
                            : "com.bitwig.flt.document.api.document.ApiInstrumentNoteClipEvent");
                    Clip cloned = (Clip) wrapper.getConstructor(classForName(clip instanceof AudioClip ? "ukL" : "ME2"))
                            .newInstance(raw);
                    cloned.setTime(start);
                    Object selection = modelGet(modelGet(modelGet(project.getTarget(), "qVp"), "TcG"), "tY1");
                    Object context = modelGet(selection, "gn_");
                    java.lang.reflect.Method inserted = raw.getClass().getMethod("IYM", classForName("XCv"), classForName("dP"));
                    Object originalRaw = ((ApiObject) clip).getTarget();
                    double fadeTime = clip instanceof AudioClip ? ((AudioClip) clip).getFadeInTime() : 0;
                    double fadeGain = clip instanceof AudioClip ? ((Number) modelGet(originalRaw, "tDS")).doubleValue() : 1;
                    Object fadeCurve = clip instanceof AudioClip ? modelGet(originalRaw, "wtf") : null;
                    DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                        output.addEvent(cloned);
                        // Native clone references can share content. Unlink using
                        // Bitwig's own make-unique operation after attaching it.
                        modelGet(raw, "knw");
                        try { inserted.invoke(raw, ((ApiObject) output).getTarget(), context); }
                        catch (ReflectiveOperationException failure) { throw new IllegalStateException("native copy insertion finalization failed", failure); }
                        if (cloned instanceof AudioClip) {
                            // Insertion initializes automatic fade-in defaults;
                            // retain the source's explicit time, gain and curve.
                            ((AudioClip) cloned).setFadeInTime(fadeTime);
                            invokeModelSetter(raw, "HT3", double.class, fadeGain);
                            invokeModelSetter(raw, "KF3", classForName("xEd"), fadeCurve);
                        }
                    });
                    return clipLocation(output, raw);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("native clip copy failed", failure);
                }
            }
            if (method.equals("clip.split")) {
                if (launcher) throw new IllegalArgumentException("split requires an arranger clip");
                double beat = finiteField(params, "beat", Double.NaN);
                if (beat <= clip.getTime() + 1e-6 || beat >= clip.getTime() + clip.getDuration() - 1e-6)
                    throw new IllegalArgumentException("split must be strictly inside the clip");
                try {
                    Object selection = modelGet(modelGet(modelGet(project.getTarget(), "qVp"), "TcG"), "tY1");
                    Object context = modelGet(selection, "gn_");
                    Object rawTimeline = ((ApiObject) timeline).getTarget();
                    // The native Split Event entry point owns its transaction.
                    rawTimeline.getClass().getMethod("IYM", classForName("L3"), double.class,
                            classForName("ydz"), classForName("wYJ"))
                            .invoke(rawTimeline, ((ApiObject) clip).getTarget(), beat, context, null);
                    JSONArray pieces = new JSONArray();
                    for (Object member : timeline.getEvents()) {
                        Clip piece = (Clip) member;
                        if (((ApiObject) piece).getTarget() == ((ApiObject) clip).getTarget()
                                || Math.abs(piece.getTime() - beat) < 1e-6)
                            pieces.put(clipLocation(timeline, ((ApiObject) piece).getTarget()));
                    }
                    if (pieces.length() != 2) throw new IllegalStateException("split did not produce two clips");
                    return new JSONObject().put("clips", pieces);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("native clip split failed", failure);
                }
            }
            if (method.equals("clip.delete")) {
                if (launcher) {
                    DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(),
                            () -> invokeModelSetter(((ApiObject) launcherSlot(project, track, index, false)).getTarget(), "jPB", classForName("XEQ"), null));
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
                if (!allowed.contains(key)) throw new IllegalArgumentException("unknown clip field: " + key);
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
            if (fadeIn < 0 || fadeOut < 0 || fadeIn > duration || fadeOut > duration)
                throw new IllegalArgumentException("fades must fit within the clip duration");
            Color color = changes.has("color") ? parseColor(changes) : null;
            if (launcher && changes.has("start")) throw new IllegalArgumentException("launcher clips have no arranger start");
            if (!launcher) noClipOverlap(timeline, clip, start, duration);
            boolean reposition = !launcher && (start != clip.getTime() || duration != clip.getDuration());
            DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                if (reposition) {
                    try {
                        Object rawTimeline = ((ApiObject) timeline).getTarget();
                        rawTimeline.getClass().getMethod("IYM", classForName("No"), boolean.class)
                                .invoke(rawTimeline, ((ApiObject) clip).getTarget(), false);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("cannot detach arranger clip", failure);
                    }
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
            modelGet(((ApiObject) clip).getTarget(), "knw");
            EventTimeline timeline = ((NoteClip) clip).getContent().getEventTimeline();
            if (!method.equals("note.import")) {
                Object raw = ((ApiObject) timeline.getEvents().get(index)).getTarget();
                Object lane = modelGet(raw, "L_");
                try { lane.getClass().getMethod("IYM", classForName("No"), boolean.class).invoke(lane, raw, false); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("native note removal failed", failure); }
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
                modelGet(((ApiObject) clip).getTarget(), "knw");
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
        double fadeIn = finiteField(changes, "fade_in", ((Number) modelGet(((ApiObject) existing).getTarget(), "dM_")).doubleValue());
        double fadeOut = finiteField(changes, "fade_out", ((Number) modelGet(((ApiObject) existing).getTarget(), "KF3")).doubleValue());
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
        if (start < 0 || duration <= 0 || !Double.isFinite(start + duration) || fadeIn < 0 || fadeOut < 0 || fadeIn > duration || fadeOut > duration)
            throw new IllegalArgumentException("invalid audio event range/fades");
        boolean reversed = (Boolean) modelGet(modelGet(((ApiObject) existing).getTarget(), "jsk"), "wtf");
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
            if (curves.has("transpose") && (mode.name().equals("RAW") || mode.name().equals("REPITCH")))
                throw new IllegalArgumentException("transpose expression requires a pitch-processing warp mode");
            writeExpressions(project.getFactory(), stagedConditions, curves, true);
        }
        final JSONObject[] result = new JSONObject[1];
        DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
            modelGet(((ApiObject) clip).getTarget(), "knw");
            EventTimeline timeline = ((AudioClip) clip).getContent().getEventTimeline();
            com.bitwig.extension.api.project.timeline.AudioNote event = (com.bitwig.extension.api.project.timeline.AudioNote) timeline.getEvents().get(index);
            Object raw = ((ApiObject) event).getTarget();
            boolean reposition = changes.has("start") || changes.has("duration");
            try {
                if (method.equals("audio_event.delete") || reposition) {
                    // API audio content wraps the polyphonic bank; its events
                    // actually belong to the (channel=0,key=0) native lane.
                    Object bank = ((ApiObject) timeline).getTarget();
                    Object lane = bank.getClass().getMethod("Qcu", int.class, int.class, boolean.class)
                            .invoke(bank, 0, 0, false);
                    lane.getClass().getMethod("IYM", classForName("No"), boolean.class).invoke(lane, raw, false);
                }
                if (method.equals("audio_event.delete")) {
                    result[0] = new JSONObject().put("deleted", index).put("count", timeline.getEvents().size());
                    return;
                }
                if (reposition) { event.setTime(start); event.setDuration(duration); timeline.addEvent(event); }
                if (stagedWarps != null) event.setWarpEvents(stagedWarps);
                if (changes.has("warp_mode")) event.setWarpMode(mode);
                if (changes.has("content_offset")) invokeModelSetter(raw, "UJq", double.class, contentOffset);
                if (changes.has("source_offset")) invokeModelSetter(raw, "UJq", double.class, sourceContentOffset);
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
                    Object selection = modelGet(modelGet(modelGet(project.getTarget(), "qVp"), "TcG"), "tY1");
                    raw.getClass().getMethod("IYM", classForName("fKr")).invoke(raw, modelGet(selection, "gn_"));
                }
                applyNoteConditions(event, changes);
                if (changes.has("expressions")) writeExpressions(project.getFactory(), event, changes.getJSONObject("expressions"), true);
                result[0] = audioEventLocation(timeline, raw);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native audio event edit failed", failure); }
        });
        return result[0];
    }

    private static com.bitwig.extension.api.project.timeline.ClipSlot launcherSlot(ApiProject project, Track track, int index, boolean create) {
        int scenes = ((Number) modelGet(modelGet(project.getTarget(), "Qjx"), "ce_")).intValue();
        if (index < 0 || index >= scenes) throw new IllegalArgumentException("slot index outside project scene bank");
        int slots = ((Number) modelGet(((ApiObject) track).getTarget(), "ce_")).intValue();
        if (!create && index >= slots) return null;
        return track.clipSlot(index);
    }

    private static JSONObject launcherSnapshot(com.bitwig.extension.api.project.timeline.ClipSlot slot, int index) {
        Clip clip = slot == null ? null : slot.getClip();
        return new JSONObject().put("slot", index).put("has_stop", slot != null && slot.hasStopButton())
                .put("clip", clip == null ? JSONObject.NULL : readClip(clip))
                .put("playing", clip != null && (Boolean) modelGet(((ApiObject) clip).getTarget(), "TEI"))
                .put("queued", clip != null && (Boolean) modelGet(((ApiObject) clip).getTarget(), "Vgk"));
    }

    private static Object namedNativeEnum(String type, String name) {
        try { return classForName(type).getMethod("valueOf", String.class).invoke(null, name); }
        catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("unsupported " + type + " value: " + name, failure); }
    }

    private static JSONObject launchSettings(Clip clip) {
        Object raw = ((ApiObject) clip).getTarget();
        return new JSONObject().put("launch_quantization", ((Enum<?>) modelGet(raw, "sGy")).name())
                .put("launch_mode", ((Enum<?>) modelGet(raw, "yi1")).name())
                .put("next_action", ((Enum<?>) modelGet(raw, "CaN")).name())
                .put("next_action_time", modelGet(raw, "ncg"))
                .put("use_loop_start_as_quantization_reference", clip.useLoopStartAsQuantizationReference());
    }

    private static JSONObject editLauncher(String method, JSONObject params) {
        ApiProject project = project();
        try {
            Track track = resolveTrack(project, params);
            if (method.equals("launcher.stop") || method.equals("launcher.return_to_arranger")) {
                Object raw = ((ApiObject) track).getTarget();
                try {
                    if (method.equals("launcher.stop")) {
                        if (params.has("immediate") && !(params.get("immediate") instanceof Boolean)) throw new IllegalArgumentException("immediate must be boolean");
                        Object quantization = namedNativeEnum("RXG", params.optBoolean("immediate", false) ? "OFF" : "DEFAULT");
                        raw.getClass().getMethod("IYM", classForName("RXG"), boolean.class, int.class).invoke(raw, quantization, false, -1);
                    } else modelGet(raw, "Sxv");
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("launcher playback command failed", failure); }
                return new JSONObject().put("requested", method);
            }
            int index = params.getInt("slot");
            com.bitwig.extension.api.project.timeline.ClipSlot existing = launcherSlot(project, track, index, false);
            if (method.equals("launcher.read")) return launcherSnapshot(existing, index);
            if (method.equals("launcher.launch")) {
                if (existing == null) throw new IllegalArgumentException("launcher slot does not exist");
                try { ((ApiObject) existing).getTarget().getClass().getMethod("jPB", boolean.class, boolean.class)
                        .invoke(((ApiObject) existing).getTarget(), true, false); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("native launcher trigger failed", failure); }
                return launcherSnapshot(existing, index);
            }
            if (method.equals("launcher.settings")) {
                if (existing == null || existing.getClip() == null) throw new IllegalArgumentException("launcher slot is empty");
                JSONObject changes = params.getJSONObject("changes");
                java.util.Set<String> allowed = java.util.Set.of("launch_quantization", "launch_mode", "next_action", "next_action_time",
                        "use_loop_start_as_quantization_reference", "has_stop");
                for (String key : changes.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("unknown launch setting: " + key);
                for (String key : new String[]{"has_stop", "use_loop_start_as_quantization_reference"})
                    if (changes.has(key) && !(changes.get(key) instanceof Boolean)) throw new IllegalArgumentException(key + " must be boolean");
                Object quantization = changes.has("launch_quantization") ? namedNativeEnum("RXG", changes.getString("launch_quantization")) : null;
                Object mode = changes.has("launch_mode") ? namedNativeEnum("xSr", changes.getString("launch_mode")) : null;
                Object action = changes.has("next_action") ? namedNativeEnum("bP", changes.getString("next_action")) : null;
                double time = finiteField(changes, "next_action_time", 0);
                if (time < 0) throw new IllegalArgumentException("next action time must be nonnegative");
                Clip clip = existing.getClip();
                Object raw = ((ApiObject) clip).getTarget();
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    if (quantization != null) invokeModelSetter(raw, "IYM", classForName("RXG"), quantization);
                    if (mode != null) invokeModelSetter(raw, "jPB", classForName("xSr"), mode);
                    if (action != null) invokeModelSetter(raw, "IYM", classForName("bP"), action);
                    if (changes.has("next_action_time")) invokeModelSetter(raw, "p3P", double.class, time);
                    if (changes.has("use_loop_start_as_quantization_reference")) clip.setUseLoopStartAsQuantizationReference(changes.getBoolean("use_loop_start_as_quantization_reference"));
                    if (changes.has("has_stop")) existing.setHasStopButton(changes.getBoolean("has_stop"));
                });
                return launcherSnapshot(existing, index);
            }
            if (params.has("has_stop") && !(params.get("has_stop") instanceof Boolean)) throw new IllegalArgumentException("has_stop must be boolean");
            if (method.equals("launcher.clear")) {
                if (existing != null || params.has("has_stop")) DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    com.bitwig.extension.api.project.timeline.ClipSlot slot = existing == null ? launcherSlot(project, track, index, true) : existing;
                    invokeModelSetter(((ApiObject) slot).getTarget(), "jPB", classForName("XEQ"), null);
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
                Object raw = modelGet(((ApiObject) source).getTarget(), "VxG");
                try {
                    classForName("com.bitwig.ramona.core.nvG").getMethod("IYM", classForName("com.bitwig.ramona.core.kX1")).invoke(null, raw);
                    staged = (Clip) classForName(source instanceof AudioClip ? "com.bitwig.flt.document.api.document.ApiAudioNoteClipEvent"
                            : "com.bitwig.flt.document.api.document.ApiInstrumentNoteClipEvent")
                            .getConstructor(classForName(source instanceof AudioClip ? "ukL" : "ME2")).newInstance(raw);
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("native launcher copy failed", failure); }
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
                modelGet(((ApiObject) staged).getTarget(), "knw");
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
        Object transport = modelGet(project.getTarget(), "THS");
        double natural = modelDoubleArgument(transport, "AOA", modelDoubleArgument(transport, "xeZ", globalStart) + seconds) - globalStart;
        double duration = finiteField(params, "duration", natural);
        if (duration <= 0 || !Double.isFinite(globalStart + duration)) throw new IllegalArgumentException("invalid audio duration");
        com.bitwig.extension.api.project.timeline.AudioNote event = factory.createAudioNote(0, duration, sample);
        event.setWarpMode(com.bitwig.extension.api.project.timeline.WarpMode.RAW);
        invokeModelSetter(((ApiObject) event).getTarget(), "UJq", double.class, offset);
        event.getGain().setValue(gain, Unit.DECIBEL);
        return event;
    }

    private static final java.util.Set<String> NOTE_CONDITIONS = java.util.Set.of("chance", "chance_enabled",
            "occurrence", "occurrence_enabled", "recurrence", "recurrence_enabled", "repeat_count", "repeat_curve", "repeat_enabled");

    private static void applyNoteConditions(com.bitwig.extension.api.project.timeline.Note note, JSONObject data) {
        Object raw = ((ApiObject) note).getTarget();
        for (String name : new String[]{"chance_enabled", "occurrence_enabled", "recurrence_enabled", "repeat_enabled"}) {
            if (!data.has(name)) continue;
            if (!(data.get(name) instanceof Boolean)) throw new IllegalArgumentException(name + " must be boolean");
            String setter = switch (name) { case "chance_enabled" -> "xeZ"; case "occurrence_enabled" -> "KF3";
                case "recurrence_enabled" -> "wtf"; default -> "Qcu"; };
            invokeModelSetter(raw, setter, boolean.class, data.getBoolean(name));
        }
        if (data.has("chance")) {
            double value = finiteField(data, "chance", 1);
            if (value < 0 || value > 1) throw new IllegalArgumentException("chance must be in 0..1");
            note.setChance(value);
        }
        if (data.has("occurrence")) invokeModelSetter(raw, "IYM", classForName("Cff"), namedNativeEnum("Cff", data.getString("occurrence")));
        for (String name : new String[]{"recurrence", "repeat_count"}) {
            if (!data.has(name)) continue;
            double value = finiteField(data, name, 0);
            if (value != Math.rint(value) || (name.equals("recurrence") ? value < 0 || value > 2047 : value < -127 || value > 127))
                throw new IllegalArgumentException("invalid " + name);
            invokeModelSetter(raw, name.equals("recurrence") ? "qFH" : "tPX", int.class, (int) value);
        }
        if (data.has("repeat_curve")) {
            double value = finiteField(data, "repeat_curve", 0);
            if (value < -1 || value > 1) throw new IllegalArgumentException("repeat curve must be in -1..1");
            invokeModelSetter(raw, "Trh", double.class, value);
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
                if (!Double.isFinite(time) || !Double.isFinite(value) || !Double.isFinite(curvature) || time < 0 || time <= previous
                        || curvature < -1 || curvature > 1 || (!mode.equals("linear") && !mode.equals("hold")))
                    throw new IllegalArgumentException("invalid expression point");
                events.addPoint(time, value); previous = time;
            }
            expression.setEvents(events, unit);
            Object content = modelGet(((ApiObject) note).getTarget(), "smZ");
            Object selected = null;
            for (Object timeline : (java.util.List<?>) modelGet(content, "CZR"))
                if (((Enum<?>) modelGet(timeline, "bXk")).name().equalsIgnoreCase(name)) selected = timeline;
            if (selected == null) throw new IllegalStateException("expression timeline missing");
            java.util.List<?> stored = (java.util.List<?>) modelGet(selected, "IYM");
            if (stored.size() != points.length()) throw new IllegalStateException("expression point count mismatch");
            for (int j = 0; j < points.length(); j++) {
                JSONArray input = points.getJSONArray(j);
                GUX point = (GUX) stored.get(j);
                boolean hold = input.optString(3, "linear").equals("hold");
                point.IYM(hold ? HWV.IYM : HWV.jPB);
                point.jPB(hold ? 0 : input.optDouble(2, 0));
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
        if (key != Math.rint(key) || key < 0 || key > 127 || channel != Math.rint(channel) || channel < 0 || channel > 15
                || start < 0 || duration <= 0 || !Double.isFinite(start + duration) || offset < 0
                || on < 0 || on > 1 || off < 0 || off > 1 || spread < 0 || spread > 1)
            throw new IllegalArgumentException("invalid note key/channel/time/velocity/spread/offset");
        if (data.has("muted") && !(data.get("muted") instanceof Boolean)) throw new IllegalArgumentException("note muted must be boolean");
        InstrumentNote note = factory.createInstrumentNote((int) channel, (int) key, start, duration, on, off);
        note.setIsMuted(data.optBoolean("muted", false));
        note.setVelocitySpread(spread);
        invokeModelSetter(((ApiObject) note).getTarget(), "UJq", double.class, offset);
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
            Object raw = project.getTarget();
            Object root = modelGet(raw, "Qjx");
            Object selection = modelGet(modelGet(modelGet(raw, "qVp"), "TcG"), "tY1");
            Object editor = classForName("pZb").getMethod("mjf").invoke(null);
            // These native commands own their undo transactions and handle
            // crossing clips, automation and cue markers across the root tree.
            String method = switch (operation) {
                case "duplicate" -> "wtf";
                case "insert" -> "Gcr";
                case "delete" -> "AOA";
                default -> throw new IllegalArgumentException("unknown range operation");
            };
            editor.getClass().getMethod(method, java.util.List.class, double.class,
                    double.class, classForName("Lsv")).invoke(editor,
                    java.util.List.of(modelGet(root, "bV_")), start, end, selection);
            return new JSONObject().put("start", start).put("end", end)
                    .put(operation.equals("delete") ? "deleted_beats" : "inserted_beats", end - start);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("native arranger " + operation + " failed", failure);
        } finally { project.dispose(); }
    }

    private static java.util.List<Object> mediaIdentity(Object file) {
        Object location = modelGet(file, "QmZ");
        return java.util.List.of(modelGet(file, "Gcr"), modelGet(file, "tDS"),
                modelGet(file, "kAg"), modelGet(file, "xTe"),
                location == null ? "" : modelGet(location, "AOA"));
    }

    private static JSONObject saveNativeProjectCopy(String path) {
        if (path == null || path.isBlank() || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".bwproject"))
            throw new IllegalArgumentException("a .bwproject destination is required");
        java.io.File target = new java.io.File(path).getAbsoluteFile();
        if (target.exists()) throw new IllegalArgumentException("destination already exists: " + target);
        ApiProject project = project();
        Object copy = null;
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
            Object source = project.getTarget();
            Object serializer = classForName("com.bitwig.flt.document.file.m1G").getMethod("Gcr").invoke(null);
            byte[] serialized = (byte[]) serializer.getClass().getMethod("jPB", Object.class).invoke(serializer, source);
            copy = serializer.getClass().getMethod("IYM", byte[].class).invoke(serializer, (Object) serialized);
            // Resolve the clone against the source directory first. Native
            // cloning drops unused media entries, so source/clone list indexes
            // cannot be paired after edits such as deleting an audio clip.
            copy.getClass().getMethod("Qcu", String.class).invoke(copy, modelGet(source, "UBi"));
            copy.getClass().getMethod("KF3", String.class).invoke(copy, modelGet(source, "BEq"));
            modelGet(copy, "SOE");
            java.util.List<?> copyFiles = (java.util.List<?>) modelGet(modelGet(copy, "mfz"), "IYM");
            java.util.Map<java.util.List<Object>, Object> originalByIdentity = new java.util.HashMap<>();
            for (Object original : (java.util.List<?>) modelGet(modelGet(source, "mfz"), "IYM")) {
                Object previous = originalByIdentity.put(mediaIdentity(original), original);
                if (previous != null && !java.util.Objects.equals(modelGet(previous, "xeZ"), modelGet(original, "xeZ")))
                    throw new IllegalStateException("ambiguous native media identity");
            }
            java.util.List<java.io.File> resolvedFiles = new java.util.ArrayList<>();
            for (Object cloned : copyFiles) {
                Object original = originalByIdentity.get(mediaIdentity(cloned));
                if (original == null) throw new IllegalStateException("unmatched cloned media identity");
                resolvedFiles.add((java.io.File) modelGet(original, "xeZ"));
            }
            copy.getClass().getMethod("Qcu", String.class).invoke(copy, target.getParent());
            copy.getClass().getMethod("KF3", String.class).invoke(copy, target.getPath());
            int mediaCount = 0;
            java.util.Map<java.nio.file.Path, String> collected = new java.util.HashMap<>();
            assets = java.nio.file.Files.createTempDirectory(target.getParentFile().toPath(), "openwig-media-");
            for (int i = 0; i < copyFiles.size(); i++) {
                Object cloned = copyFiles.get(i);
                java.io.File sourceFile = resolvedFiles.get(i);
                if (sourceFile == null || !sourceFile.isFile()) {
                    if (!String.valueOf(modelGet(cloned, "kAg")).isEmpty()) continue;
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
                cloned.getClass().getMethod("jPB", String.class).invoke(cloned, relative);
                cloned.getClass().getMethod("jPB", classForName("com.bitwig.flt.packaging.core.MU2")).invoke(cloned, new Object[]{null});
                cloned.getClass().getMethod("jPB", java.io.File.class).invoke(cloned, new java.io.File(target.getParentFile(), relative));
            }
            serializer.getClass().getMethod("jPB", Object.class, java.io.File.class).invoke(serializer, copy, target);
            java.util.Set<String> states = new java.util.HashSet<>();
            source.getClass().getMethod("IYM", java.util.Set.class).invoke(source, states);
            Object embedded = classForName("Y3m").getConstructor().newInstance();
            for (String state : states) {
                java.io.File stateFile = (java.io.File) classForName("ri2").getMethod("Gcr", String.class).invoke(null, state);
                if (!stateFile.isFile()) throw new IllegalStateException("plugin state unavailable: " + state);
                embedded.getClass().getMethod("IYM", String.class, java.io.File.class).invoke(embedded, "plugin-states/" + state, stateFile);
            }
            if (!states.isEmpty()) classForName("com.bitwig.base.serial.file.rdP")
                    .getMethod("IYM", java.io.File.class, classForName("mgr")).invoke(null, target, embedded);
            complete = true;
            return new JSONObject().put("path", target.getPath()).put("bytes", target.length())
                    .put("media_files", mediaCount).put("plugin_states", states.size());
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("native project copy failed", failure);
        } finally {
            try {
                if (copy != null) modelGet(copy, "iz_");
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
                Class<?> importerClass = classForName("com.bitwig.flt.document.api.document.ApiDawProjectImporter");
                java.lang.reflect.Constructor<?> ctor = importerClass.getConstructor(
                        classForName("com.bitwig.flt.document.core.master.oZU"),
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
