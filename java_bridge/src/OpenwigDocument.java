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
    private OpenwigDocument() {}

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
            DirectAccess.runInUndo(effect.clipTimeline(),
                    () -> DirectAccess.removeEffectTrack(group, target));
            int after = project.getTrackGroup().effectTracks().size();
            if (after != before - 1) {
                throw new IllegalStateException("effect track count did not decrease after deletion");
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
                DirectAccess.runInUndo(master.clipTimeline(),
                        () -> DirectAccess.removeDevice(chain, device));
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
            Track targetTrack = track(project, trackIndex);
            Object groupApi = project.getTrackGroup();
            Yko group = (Yko) ((ApiObject) groupApi).getTarget();
            Kkl target = (Kkl) ((ApiObject) targetTrack).getTarget();
            int before = project.getTrackGroup().mainTracks().size();
            DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> group.jPB(target));
            int after = project.getTrackGroup().mainTracks().size();
            if (after != before - 1) {
                throw new IllegalStateException("track count did not decrease after deletion");
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
            return deviceSnapshotList(track(project, trackIndex));
        } finally {
            project.dispose();
        }
    }

    private static JSONArray deviceSnapshotList(Track view) {
        JSONArray devices = new JSONArray();
        java.util.List<?> members = view.deviceChain().getDevices();
        for (int i = 0; i < members.size(); i++) {
            Device device = (Device) members.get(i);
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
            devices.put(new JSONObject().put("index", i)
                    .put("display_name", device.getDisplayName())
                    .put("kind", kind)
                    .put("enabled", device.getEnabled().getValue())
                    .put("active", device.getActive()));
        }
        return devices;
    }

    private static NativeDevice nativeDevice(ApiProject project, int trackIndex, int deviceIndex) {
        java.util.List<?> members = track(project, trackIndex).deviceChain().getDevices();
        if (deviceIndex < 0 || deviceIndex >= members.size()) {
            throw new IllegalArgumentException("device index out of range: " + deviceIndex);
        }
        Device device = (Device) members.get(deviceIndex);
        if (!(device instanceof NativeDevice target)) {
            throw new IllegalArgumentException("device has no named parameters");
        }
        return target;
    }

    private static DoubleParameter automationParameter(ApiProject project, JSONObject params) {
        String name = params.getString("param");
        if (name.equals("tempo")) return project.getTransport().getTempo();
        Track target = track(project, params.getInt("track"));
        if (name.equals("volume")) return target.mixer().getVolume();
        if (name.equals("pan")) return target.mixer().getPan();
        DoubleParameter parameter = nativeDevice(project, params.getInt("track"),
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
                if (timeline == null || timeline.IYM().size() != points.length()) {
                    throw new IllegalStateException("automation point count mismatch after document commit");
                }
                DirectAccess.runInUndo(project.getTrackGroup().masterTrack().clipTimeline(), () -> {
                    aB range = ((Ob) ((ApiObject) parameter).getTarget()).ViH();
                    for (int i = 0; i < points.length(); i++) {
                        JSONArray input = points.getJSONArray(i);
                        GUX point = (GUX) timeline.IYM().get(i);
                        String unit = params.optString("unit", "");
                        if (!params.getString("param").equals("tempo") && unit.isBlank()) {
                            point.IYM(range.jPB(input.getDouble(1)));
                        }
                        point.IYM(input.optString(3, "linear").equals("hold") ? HWV.IYM : HWV.jPB);
                        point.jPB(input.optDouble(2, 0));
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
                DirectAccess.runInUndo(project.getTrackGroup().mainTracks().isEmpty()
                        ? project.getTrackGroup().masterTrack().clipTimeline()
                        : ((Track) project.getTrackGroup().mainTracks().get(0)).clipTimeline(), () -> {
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

    public static JSONObject setDeviceParameters(int trackIndex, int deviceIndex,
                                                 JSONObject params, String unitName) {
        ApiProject project = project();
        try {
            NativeDevice device = nativeDevice(project, trackIndex, deviceIndex);
            Unit unit = unitByName(unitName);
            java.util.Map<String, String> kinds = parameterKinds(trackIndex, deviceIndex);
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
        } finally {
            project.dispose();
        }
    }

    public static JSONObject getDeviceParameters(int trackIndex, int deviceIndex,
                                                 JSONArray names, String unitName) {
        ApiProject project = project();
        try {
            NativeDevice device = nativeDevice(project, trackIndex, deviceIndex);
            Unit unit = unitByName(unitName);
            java.util.Map<String, String> kinds = parameterKinds(trackIndex, deviceIndex);
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
        } finally {
            project.dispose();
        }
    }

    public static JSONObject renameDevice(int trackIndex, int deviceIndex, String name) {
        ApiProject project = project();
        try {
            java.util.List<?> members = track(project, trackIndex).deviceChain().getDevices();
            if (deviceIndex < 0 || deviceIndex >= members.size()) {
                throw new IllegalArgumentException("device index out of range: " + deviceIndex);
            }
            Device device = (Device) members.get(deviceIndex);
            device.setDisplayName(name);
            return new JSONObject().put("index", deviceIndex)
                    .put("display_name", device.getDisplayName());
        } finally {
            project.dispose();
        }
    }

    public static JSONObject setDeviceExpanded(int trackIndex, int deviceIndex, boolean expanded) {
        ApiProject project = project();
        try {
            java.util.List<?> members = track(project, trackIndex).deviceChain().getDevices();
            if (deviceIndex < 0 || deviceIndex >= members.size()) {
                throw new IllegalArgumentException("device index out of range: " + deviceIndex);
            }
            Device device = (Device) members.get(deviceIndex);
            device.setIsExpanded(expanded);
            return new JSONObject().put("index", deviceIndex).put("expanded", expanded);
        } finally {
            project.dispose();
        }
    }

    public static int deleteTrackDevice(int trackIndex, int deviceIndex) {
        ApiProject project = project();
        try {
            Track targetTrack = track(project, trackIndex);
            DeviceChain chain = targetTrack.deviceChain();
            java.util.List<?> members = chain.getDevices();
            if (deviceIndex < 0 || deviceIndex >= members.size()) {
                throw new IllegalArgumentException("device index out of range: " + deviceIndex);
            }
            Object device = members.get(deviceIndex);
            DirectAccess.runInUndo(targetTrack.clipTimeline(),
                    () -> DirectAccess.removeDevice(chain, device));
            return chain.getDevices().size();
        } finally {
            project.dispose();
        }
    }

    /** Walk the device's CONTENTS children using bFO.hMI(), a read-only model getter. */
    public static JSONArray listDeviceParameters(int trackIndex, int deviceIndex) {
        ApiProject project = project();
        try {
            java.util.List<?> members = track(project, trackIndex).deviceChain().getDevices();
            if (deviceIndex < 0 || deviceIndex >= members.size()) {
                throw new IllegalArgumentException("device index out of range: " + deviceIndex);
            }
            Object target = ((ApiObject) members.get(deviceIndex)).getTarget();
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
        } finally {
            project.dispose();
        }
    }

    private static java.util.Map<String, String> parameterKinds(int trackIndex, int deviceIndex) {
        JSONArray available = listDeviceParameters(trackIndex, deviceIndex);
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

    public static JSONObject setDeviceEnabled(int trackIndex, int deviceIndex, boolean enabled) {
        ApiProject project = project();
        try {
            java.util.List<?> members = track(project, trackIndex).deviceChain().getDevices();
            if (deviceIndex < 0 || deviceIndex >= members.size()) {
                throw new IllegalArgumentException("device index out of range: " + deviceIndex);
            }
            Device device = (Device) members.get(deviceIndex);
            device.getEnabled().setValue(enabled);
            return new JSONObject().put("index", deviceIndex)
                    .put("display_name", device.getDisplayName())
                    .put("enabled", device.getEnabled().getValue());
        } finally {
            project.dispose();
        }
    }

    public static int insertFactoryDevice(int trackIndex, String name) {
        ApiProject project = project();
        String stage = "resolve device";
        try {
            java.util.UUID id = DirectAccess.factoryDeviceId(name + ".bwdevice");
            if (id == null) {
                throw new IllegalArgumentException("unknown Bitwig factory device: " + name);
            }
            stage = "create device";
            NativeDevice device = project.getFactory().createNativeDevice(id);
            stage = "insert device";
            Track targetTrack = track(project, trackIndex);
            DeviceChain chain = targetTrack.deviceChain();
            DirectAccess.runInUndo(targetTrack.clipTimeline(), () -> chain.addDevice(device));
            stage = "count devices";
            return chain.getDevices().size();
        } catch (Throwable failure) {
            throw new IllegalStateException("device stage " + stage, failure);
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
        return new JSONObject()
                .put("index", index)
                .put("name", track.getTitle())
                .put("volume", mixer.getVolume().getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0)
                .put("pan", (mixer.getPan().getValue(Unit.PERCENTAGE_OF_RANGE) + 100.0) / 200.0)
                .put("mute", mixer.getMute().getValue())
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
            JSONArray main = new JSONArray();
            java.util.List<?> tops = project.getTrackGroup().mainTracks();
            for (int i = 0; i < tops.size(); i++) {
                main.put(dumpTrackOrGroup((TrackOrTrackGroup) tops.get(i), i));
            }
            out.put("main_tracks", main);
            JSONArray effects = new JSONArray();
            java.util.List<?> fx = project.getTrackGroup().effectTracks();
            for (int i = 0; i < fx.size(); i++) {
                effects.put(dumpTrackOrGroup((TrackOrTrackGroup) fx.get(i), -i - 2));
            }
            out.put("effect_tracks", effects);
            out.put("master", dumpTrackOrGroup(project.getTrackGroup().masterTrack(), -1));
            return out;
        } finally {
            project.dispose();
        }
    }

    private static JSONObject dumpTrackOrGroup(TrackOrTrackGroup item, int index) {
        Color color = item.getColor();
        JSONObject out = new JSONObject()
                .put("index", index)
                .put("name", item.getTitle())
                .put("is_group", item instanceof TrackGroup)
                .put("color", color == null ? JSONObject.NULL : color.toHex());
        if (item instanceof TrackGroup group) {
            JSONArray children = new JSONArray();
            java.util.List<?> members = group.mainTracks();
            for (int i = 0; i < members.size(); i++) {
                children.put(dumpTrackOrGroup((TrackOrTrackGroup) members.get(i), i));
            }
            out.put("children", children);
        }
        Track view = item instanceof Track direct ? direct : item.asTrack();
        out.put("track_type", trackTypeName(view));
        out.put("is_active", view.isActive());
        Mixer mixer = view.mixer();
        out.put("volume", mixer.getVolume().getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0)
                .put("pan", (mixer.getPan().getValue(Unit.PERCENTAGE_OF_RANGE) + 100.0) / 200.0)
                .put("mute", mixer.getMute().getValue());
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
        out.put("devices", deviceSnapshotList(view));
        JSONArray clips = new JSONArray();
        try {
            for (Object clip : view.clipTimeline().getEvents()) {
                clips.put(dumpClip((Clip) clip));
            }
        } catch (RuntimeException noTimeline) {
            // Some channels (for example the master) have no clip timeline to read.
        }
        out.put("clips", clips);
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

    private static JSONObject dumpClip(Clip clip) {
        Color color = clip.getColor();
        JSONObject out = new JSONObject()
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
                notes.put(new JSONObject()
                        .put("channel", note.getChannel())
                        .put("key", note.getKey())
                        .put("start", note.getTime())
                        .put("duration", note.getDuration())
                        .put("on_velocity", note.getOnVelocity())
                        .put("off_velocity", note.getOffVelocity())
                        .put("muted", note.isMuted()));
            }
            out.put("notes", notes);
        } else if (clip instanceof AudioClip audioClip) {
            out.put("content", "audio")
                    .put("fade_in", audioClip.getFadeInTime())
                    .put("fade_out", audioClip.getFadeOutTime());
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
            JSONArray lanes = new JSONArray();
            Track view = track(project, trackIndex);
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
        } finally {
            project.dispose();
        }
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

    public static JSONObject updateTrack(int trackIndex, String field, JSONObject params) {
        ApiProject project = project();
        try {
            Track track = track(project, trackIndex);
            switch (field) {
                case "name" -> track.setTitle(params.getString("name"));
                case "volume" -> track.mixer().getVolume()
                        .setValue(params.getDouble("value") * 100.0, Unit.PERCENTAGE_OF_RANGE);
                case "pan" -> track.mixer().getPan()
                        .setValue((params.getDouble("value") * 2.0 - 1.0) * 100.0,
                                Unit.PERCENTAGE_OF_RANGE);
                case "mute" -> track.mixer().getMute().setValue(params.getBoolean("on"));
                case "monitor" -> track.setMonitorMode(monitorMode(params.getString("mode")));
                case "color" -> {
                    double r = params.getDouble("r");
                    double g = params.getDouble("g");
                    double b = params.getDouble("b");
                    double a = params.optDouble("a", 1.0);
                    if (r < 0 || r > 1 || g < 0 || g > 1 || b < 0 || b > 1 ||
                            a < 0 || a > 1) {
                        throw new IllegalArgumentException("color components must be within 0..1");
                    }
                    track.setColor(a >= 1.0 ? Color.fromRGB(r, g, b)
                            : Color.fromRGBA(r, g, b, a));
                }
                case "send" -> {
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
            return trackSnapshot(track, trackIndex);
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

    public static int addNoteClip(int trackIndex, double start, double duration, JSONArray notes) {
        ApiProject project = project();
        String stage = "factory";
        try {
            Factory factory = project.getFactory();
            stage = "track";
            Track track = track(project, trackIndex);
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
        } finally {
            project.dispose();
        }
    }
}
