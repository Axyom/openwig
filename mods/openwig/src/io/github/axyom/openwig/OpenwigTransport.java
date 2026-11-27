package io.github.axyom.openwig;

import bitwig.obf.MjQ; // the transport's loop settings
import bitwig.obf.OnD; // the document's transport
import bitwig.obf.kpl; // a loop region (start and length)
import org.json.JSONObject;

/** The transport calls Bitwig's own TransportProxy makes. */
public final class OpenwigTransport {
    private OpenwigTransport() {}

    public static JSONObject execute(String method, JSONObject params) {
        OnD transport = (OnD) DirectAccess.activeTransportObject();
        switch (method) {
            case "document.audio_status" -> { return OpenwigDocument.audioStatus(); }
            case "document.activate_audio" -> { return OpenwigDocument.activateAudio(); }
            case "transport.beats_to_seconds" -> {
                double beats = finite(params, "beats");
                if (beats < 0) throw new IllegalArgumentException("beats must be nonnegative");
                return new JSONObject().put("seconds", transport.BBg(beats)); // beats to seconds at the tempo map
            }
            case "transport.snapshot" -> { }
            case "transport.play" -> transport.IHE(); // play
            case "transport.stop" -> transport.ed_(); // stop
            case "transport.set_position" -> {
                double beats = finite(params, "beats");
                if (beats < 0) throw new IllegalArgumentException("beats must be nonnegative");
                transport.byf(beats); // move the play position
            }
            case "transport.set_loop" -> {
                if (params == null || !(params.opt("on") instanceof Boolean)) {
                    throw new IllegalArgumentException("on must be a boolean");
                }
                transport.G1p().beI(params.getBoolean("on")); // loop settings: loop on or off
            }
            case "transport.set_loop_region" -> {
                double start = finite(params, "start");
                double length = finite(params, "length");
                if (start < 0 || length <= 0) {
                    throw new IllegalArgumentException("loop start must be nonnegative and length positive");
                }
                kpl region = transport.G1p().mEL(); // loop region
                region.yAy().setDoubleValue(start);  // its start
                region.sM3().setDoubleValue(length); // its length
            }
            default -> throw new IllegalArgumentException("unknown transport operation: " + method);
        }
        MjQ loop = transport.G1p();
        return new JSONObject().put("playing", transport.QDQ())      // is playing
                .put("beats", transport.bcT().getDoubleValue())         // play position
                .put("loop", loop.ZRB())                                // loop on
                .put("loop_start", loop.mEL().yAy().getDoubleValue())
                .put("loop_length", loop.mEL().sM3().getDoubleValue());
    }

    private static double finite(JSONObject params, String name) {
        Object value = params == null ? null : params.opt(name);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.doubleValue();
    }
}
