package io.github.axyom.openwig;

import bitwig.obf.aCG; // an open project tab
import bitwig.obf.cxg; // an observable value
import com.bitwig.extension.api.project.parameter.Unit;
import com.bitwig.extension.api.project.track.Track;
import com.bitwig.flt.app.gEH; // the application
import com.bitwig.flt.document.api.document.ApiDevice;
import com.bitwig.flt.document.api.document.ApiDeviceChain;
import com.bitwig.flt.document.api.document.ApiProject;
import com.bitwig.flt.document.api.document.ApiTransport;
import com.bitwig.flt.document.core.iface.bMt; // a project document seen from the UI
import com.bitwig.flt.document.core.master.CmH; // a project document
import com.bitwig.flt.document.core.master.GJy; // the project's effect track group
import com.bitwig.flt.document.core.master.device.k3K; // a device chain
import com.bitwig.flt.document.core.master.device.sgH; // a device
import com.bitwig.flt.packaging.core.SJF; // Bitwig's factory device catalogue
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Entry points the bridge server calls; the ASM-generated adapter of the patched jar, as plain Java. */
public final class DirectAccess {
    private DirectAccess() {}

    private static List<?> tabs() {
        cxg holder = gEH.xw3().VNw(); // the application's open project tabs
        return holder == null ? null : (List<?>) holder.getValue();
    }

    public static boolean hasActiveDocument() {
        gEH app = gEH.xw3();
        if (app == null) return false;
        List<?> tabs = tabs();
        return tabs != null && !tabs.isEmpty() && ((aCG) tabs.get(0)).Anf() != null; // the tab's document
    }

    private static ApiTransport firstTransport() {
        return new ApiTransport(((CmH) ((aCG) tabs().get(0)).Anf()).jPe()); // the document's transport model
    }

    public static double readTempo() {
        return firstTransport().getTempo().getValue(Unit.BPM);
    }

    public static void writeTempo(double bpm) {
        firstTransport().getTempo().setValue(bpm, Unit.BPM);
    }

    public static void dispatch(Runnable task) {
        gEH.xw3().asyncExec(task);
    }

    public static int mainTrackCount() {
        ApiProject project = new ApiProject((CmH) activeDocumentObject());
        int count = project.getTrackGroup().mainTracks().size();
        project.dispose();
        return count;
    }

    public static int createInstrumentTrack(String name) {
        ApiProject project = new ApiProject((CmH) activeDocumentObject());
        Track track = project.getFactory().createInstrumentTrack();
        track.setTitle(name);
        project.getTrackGroup().addMainTrack(track);
        project.dispose();
        return mainTrackCount();
    }

    public static int createMainTrack(String kind, String name) { return OpenwigDocument.createMainTrack(kind, name); }
    public static int deleteTrack(int index) { return OpenwigDocument.deleteTrack(index); }
    public static int clearMainTracks() { return OpenwigDocument.clearMainTracks(); }
    public static int effectTrackCount() { return OpenwigDocument.effectTrackCount(); }
    public static int createEffectTrack(String name) { return OpenwigDocument.createEffectTrack(name); }
    public static int deleteEffectTrack(int index) { return OpenwigDocument.deleteEffectTrack(index); }
    public static int clearEffectTracks() { return OpenwigDocument.clearEffectTracks(); }

    public static void removeEffectTrack(Object group, Object track) {
        GJy effects = (GJy) group;
        effects.sCA((bitwig.obf.PiT) track);       // detach the effect track's visible reference
        effects.beI((bitwig.obf.PiT) track, true); // delete it with its bank
    }

    public static int masterDeviceCount() { return OpenwigDocument.masterDeviceCount(); }
    public static int insertMasterFactoryDevice(String name) { return OpenwigDocument.insertMasterFactoryDevice(name); }
    public static int clearMasterDevices() { return OpenwigDocument.clearMasterDevices(); }
    public static JSONObject clearProject() { return OpenwigDocument.clearProject(); }

    public static void removeDevice(Object chain, Object device) {
        ((k3K) ((ApiDeviceChain) chain).getTarget()).beI((sgH) ((ApiDevice) device).getTarget(), true); // delete from the chain
    }

    public static JSONObject setDeviceParameters(int track, int device, JSONObject params, String unit) {
        return OpenwigDocument.setDeviceParameters(track, device, params, unit);
    }
    public static JSONObject getDeviceParameters(int track, int device, JSONArray names, String unit) {
        return OpenwigDocument.getDeviceParameters(track, device, names, unit);
    }
    public static JSONObject renameDevice(int track, int device, String name) { return OpenwigDocument.renameDevice(track, device, name); }
    public static JSONObject setDeviceExpanded(int track, int device, boolean on) { return OpenwigDocument.setDeviceExpanded(track, device, on); }
    public static int deleteTrackDevice(int track, int device) { return OpenwigDocument.deleteTrackDevice(track, device); }
    public static JSONArray listDeviceParameters(int track, int device) { return OpenwigDocument.listDeviceParameters(track, device); }
    public static JSONObject trackSnapshot(int index) { return OpenwigDocument.trackSnapshot(index); }
    public static JSONObject readObject(String method, String path, int index) { return OpenwigDocument.readObject(method, path, index); }
    public static JSONArray automationEnumeratePath(String path) { return OpenwigDocument.automationEnumerate(path); }
    public static JSONObject dumpProject() { return OpenwigDocument.dumpProject(); }
    public static JSONObject exportDawProject(String path, boolean embed) { return OpenwigDocument.exportDawProject(path, embed); }
    public static JSONArray automationEnumerate(int track) { return OpenwigDocument.automationEnumerate(track); }
    public static JSONObject updateTrack(int index, String field, JSONObject params) { return OpenwigDocument.updateTrack(index, field, params); }
    public static Object activeDocumentObject() { return OpenwigDocument.currentDocument(); }
    public static int clipCount(int track) { return OpenwigDocument.clipCount(track); }
    public static int noteCount(int track, int clip) { return OpenwigDocument.noteCount(track, clip); }

    public static UUID factoryDeviceId(String fileName) {
        return SJF.beI(fileName); // a factory device's ID from its file name
    }

    public static int deviceCount(int track) { return OpenwigDocument.deviceCount(track); }
    public static JSONArray deviceSnapshot(int track) { return OpenwigDocument.deviceSnapshot(track); }
    public static JSONObject setDeviceEnabled(int track, int device, boolean on) { return OpenwigDocument.setDeviceEnabled(track, device, on); }
    public static int insertFactoryDevice(int track, String name) { return OpenwigDocument.insertFactoryDevice(track, name); }
    public static void runInUndo(Object timeline, Runnable action) { OpenwigUndo.run(timeline, action); }
    public static int addNoteClip(int track, double start, double duration, JSONArray notes) {
        return OpenwigDocument.addNoteClip(track, start, duration, notes);
    }
    public static JSONObject automation(JSONObject params, boolean write) { return OpenwigDocument.automation(params, write); }

    public static Object activeTransportObject() {
        return ((bMt) activeDocumentObject()).XzC(); // the document's transport
    }

    public static JSONObject transport(String method, JSONObject params) { return OpenwigTransport.execute(method, params); }
    public static JSONObject execute(String method, JSONObject params) { return OpenwigDocument.execute(method, params); }
}
