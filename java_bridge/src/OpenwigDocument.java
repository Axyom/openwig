import com.bitwig.extension.api.project.Factory;
import com.bitwig.extension.api.project.timeline.ClipTimeline;
import com.bitwig.extension.api.project.timeline.Clip;
import com.bitwig.extension.api.project.timeline.EventTimeline;
import com.bitwig.extension.api.project.timeline.InstrumentNote;
import com.bitwig.extension.api.project.timeline.NoteClip;
import com.bitwig.extension.api.project.track.Track;
import com.bitwig.extension.api.project.mixer.Mixer;
import com.bitwig.extension.api.project.mixer.DeviceChain;
import com.bitwig.extension.api.project.mixer.NativeDevice;
import com.bitwig.extension.api.project.mixer.Device;
import com.bitwig.extension.api.project.parameter.Unit;
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

    private static Track track(ApiProject project, int index) {
        if (index < 0 || index >= project.getTrackGroup().mainTracks().size()) {
            throw new IllegalArgumentException("main track index out of range: " + index);
        }
        return (Track) project.getTrackGroup().mainTracks().get(index);
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
            JSONArray devices = new JSONArray();
            java.util.List<?> members = track(project, trackIndex).deviceChain().getDevices();
            for (int i = 0; i < members.size(); i++) {
                Device device = (Device) members.get(i);
                devices.put(new JSONObject().put("index", i)
                        .put("display_name", device.getDisplayName())
                        .put("enabled", device.getEnabled().getValue()));
            }
            return devices;
        } finally {
            project.dispose();
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
        return new JSONObject()
                .put("index", index)
                .put("name", track.getTitle())
                .put("volume", mixer.getVolume().getValue(Unit.PERCENTAGE_OF_RANGE) / 100.0)
                .put("pan", (mixer.getPan().getValue(Unit.PERCENTAGE_OF_RANGE) + 100.0) / 200.0)
                .put("mute", mixer.getMute().getValue());
    }

    public static JSONObject trackSnapshot(int trackIndex) {
        ApiProject project = project();
        try {
            return trackSnapshot(track(project, trackIndex), trackIndex);
        } finally {
            project.dispose();
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
