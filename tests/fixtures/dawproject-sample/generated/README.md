Generated from Bitwig 6.1.5's native DAWproject export after creating three
new tracks through JavaSong: FM-4 with two MIDI notes, an empty audio
track, and an effect track receiving a 0.3 send. The source project was
never cleared or saved. Only these tracks, their arranger lanes, the
first scene's matching slots, and a sanitized master remain. Transport
and metadata are synthetic; no media or plugin state is included.

Regenerate on an open disposable project (close WITHOUT saving):

    $env:PYTHONPATH='src'; python tests/live_java_reader_fixture.py --capture

Verified send mode AUTO, effective POST; targeted effect and send reads matched the atomic dump.
