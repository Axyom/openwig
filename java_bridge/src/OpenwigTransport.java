import com.openwig.bridge.DirectAccess;
import org.json.JSONObject;

/** Exact 6.1.5 transport calls used by Bitwig's own TransportProxy. */
public final class OpenwigTransport {
    private OpenwigTransport() {}

    public static JSONObject execute(String method, JSONObject params) {
        SsT transport = (SsT) DirectAccess.activeTransportObject();
        switch (method) {
            case "document.audio_status" -> { return OpenwigDocument.audioStatus(); }
            case "document.activate_audio" -> { return OpenwigDocument.activateAudio(); }
            case "transport.beats_to_seconds" -> {
                double beats = finite(params, "beats");
                if (beats < 0) throw new IllegalArgumentException("beats must be nonnegative");
                return new JSONObject().put("seconds", transport.xeZ(beats));
            }
            case "transport.snapshot" -> { }
            case "transport.play" -> transport.v1D();
            case "transport.stop" -> transport.ed_();
            case "transport.set_position" -> {
                double beats = finite(params, "beats");
                if (beats < 0) throw new IllegalArgumentException("beats must be nonnegative");
                transport.Qcu(beats);
            }
            case "transport.set_loop" -> {
                if (params == null || !(params.opt("on") instanceof Boolean)) {
                    throw new IllegalArgumentException("on must be a boolean");
                }
                transport.UqY().IYM(params.getBoolean("on"));
            }
            case "transport.set_loop_region" -> {
                double start = finite(params, "start");
                double length = finite(params, "length");
                if (start < 0 || length <= 0) {
                    throw new IllegalArgumentException("loop start must be nonnegative and length positive");
                }
                Kwn region = transport.UqY().MnS();
                region.Kkj().setDoubleValue(start);
                region.Jxd().setDoubleValue(length);
            }
            default -> throw new IllegalArgumentException("unknown transport operation: " + method);
        }
        Yos loop = transport.UqY();
        return new JSONObject().put("playing", transport.CZR())
                .put("beats", transport.sXx().getDoubleValue())
                .put("loop", loop.SOE())
                .put("loop_start", loop.MnS().Kkj().getDoubleValue())
                .put("loop_length", loop.MnS().Jxd().getDoubleValue());
    }

    private static double finite(JSONObject params, String name) {
        Object value = params == null ? null : params.opt(name);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.doubleValue();
    }
}
