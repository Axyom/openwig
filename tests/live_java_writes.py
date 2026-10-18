"""Manual write/import checks; clears the open disposable project without saving.

Run: PYTHONPATH=src python tests/live_java_writes.py --disposable
Open scratch/probe-project/probe.bwproject in the isolated 6.1.5 installation.
DAWproject import appends content; it is not a lossless project round trip.
"""

import argparse
from pathlib import Path
import time

from openwig.java_song import JavaSong


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disposable", action="store_true",
                        help="acknowledge that the active project will be cleared")
    args = parser.parse_args()
    if not args.disposable:
        parser.error("open a disposable project and pass --disposable")

    with JavaSong(tempo=124.0, bars=8) as s:
        cleared = s.clear()
        print("Cleared project:", cleared)

        # 1. Project metadata & cue markers & scene update
        meta = s.set_metadata(title="Write Probe 1", artist="Openwig", genre="Electronic", year="2026")
        assert meta["title"] == "Write Probe 1" and meta["artist"] == "Openwig", meta
        print("Metadata set:", meta)

        marker = s.add_cue_marker(8.0, "Chorus", color="#ff5500")
        assert marker["name"] == "Chorus" and abs(marker["time"] - 8.0) < 1e-6, marker
        print("Cue marker added:", marker)

        scene = s.update_scene(0, name="Scene A", comment="Main groove", color=(0.2, 0.8, 0.4))
        assert scene["name"] == "Scene A" and scene["comment"] == "Main groove", scene
        print("Scene updated:", scene)

        # 2. Group tracks & nested child tracks
        drums = s.group_track("Drums")
        drums.rename("Drums/Bus").rename("Drums")
        drums.comment("Drum bus").color(0.9, 0.3, 0.1)
        perc = s.group_track("Perc", parent=drums)
        kick = s.track("Kick", device="Polymer", kind="instrument", parent=drums)
        hat = s.track("Hat", kind="audio", parent=perc)
        hat.rename("Hat/Closed").rename("Hat")
        lead = s.track("Lead", device="Polysynth", kind="instrument")

        # 3. Solo, comment, active, destination routing, volume/pan/mute on nested tracks
        kick.solo(True).comment("Punchy kick").volume(0.75).pan(-0.25)
        hat.comment("Closed hat").active(False)
        lead.route_to(drums).comment("Routed to Drums")

        # 4. Also verify enum parameter write live on Kick's Polymer device
        deadline = time.monotonic() + 15
        while True:
            params_list = kick.list_parameters(0)
            enum_params = [p["path"] for p in params_list if p["kind"] == "enum"]
            if enum_params or time.monotonic() >= deadline:
                break
            time.sleep(0.2)
        assert enum_params, "expected enum parameters on Polymer"
        target_enum = enum_params[0]
        before_enum = kick.get_parameters(0, [target_enum])[target_enum]
        new_enum_val = 1 if before_enum == 0 else 0
        kick.parameters(0, **{target_enum: new_enum_val})
        after_enum = kick.get_parameters(0, [target_enum])[target_enum]
        assert after_enum == new_enum_val, f"enum write failed: {before_enum} -> {after_enum}"
        kick.rename_device(0, "Kick Synth").expand_device(0, False).enable_device(0, False)
        assert kick.devices()[0]["enabled"] is False
        kick.enable_device(0, True)
        print(f"Verified live enum parameter write on nested track ({target_enum}: {before_enum} -> {after_enum})")

        # 5. Add a clip on nested track Kick
        kick.clip([(36, 0.0, 1.0, 0.9), (36, 1.0, 1.0, 0.85)], dur=4.0, start=0.0)

        # 6. Full dump verification
        dump = s.dump()
        assert dump["metadata"]["title"] == "Write Probe 1"
        assert any(m["name"] == "Chorus" for m in dump["cue_markers"])
        assert dump["scenes"][0]["name"] == "Scene A" and dump["scenes"][0]["comment"] == "Main groove"

        drums_dump = dump["main_tracks"][0]
        assert drums_dump["name"] == "Drums" and drums_dump["is_group"] is True
        assert drums_dump["comment"] == "Drum bus"
        children_by_name = {c["name"]: c for c in drums_dump["children"]}
        assert "Perc" in children_by_name and "Kick" in children_by_name
        kick_dump = children_by_name["Kick"]
        assert kick_dump["solo"] is True
        assert kick_dump["comment"] == "Punchy kick"
        assert abs(kick_dump["volume"] - 0.75) < 0.01
        assert abs(kick_dump["pan"] - 0.375) < 0.01
        assert len(kick_dump["clips"]) == 1 and len(kick_dump["clips"][0]["notes"]) == 2
        hat_dump = children_by_name["Perc"]["children"][0]
        assert hat_dump["name"] == "Hat" and hat_dump["is_active"] is False and hat_dump["comment"] == "Closed hat"

        lead_dump = dump["main_tracks"][1]
        assert lead_dump["name"] == "Lead"
        assert lead_dump["destination"]["path"] == "Drums", lead_dump["destination"]
        print("Dump verified for groups, nested tracks, solo, comment, active, destination!")

        # 7. Export to .dawproject, clear project, and import .dawproject back!
        daw_path = Path("scratch/java-write-probe.dawproject").resolve()
        exp = s.export_dawproject(daw_path, embed_media=True)
        print("Exported .dawproject:", exp)
        s.clear()
        assert len(s.dump()["main_tracks"]) == 0
        imp = s.import_dawproject(daw_path)
        print("Imported .dawproject:", imp)
        re_dump = s.dump()
        assert re_dump["metadata"]["title"] == "Write Probe 1"
        re_names = [t["name"] for t in re_dump["main_tracks"]]
        assert "Drums" in re_names and "Lead" in re_names, re_names
        re_drums = next(t for t in re_dump["main_tracks"] if t["name"] == "Drums")
        re_kick = next(c for c in re_drums["children"] if c["name"] == "Kick")
        assert re_kick["solo"] is True and re_kick["comment"] == "Punchy kick", re_kick
        assert len(re_kick["clips"]) == 1 and len(re_kick["clips"][0]["notes"]) == 2
        print("DAWproject import verified for groups, notes, solo, comments, and metadata")

        s.clear()
        print("WRITE FEATURE LIVE CHECKS PASSED")



if __name__ == "__main__":
    main()
