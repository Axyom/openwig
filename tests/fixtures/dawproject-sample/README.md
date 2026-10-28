This fixture is hand-authored synthetic data, not an export from the owner's
project. It covers nested groups, a plugin, arranger notes, nested audio clips,
automation, launcher clips, scenes and metadata. No audio or plugin state is
included. Element/attribute names were checked against the Bitwig 6.1.5 JAXB
classes in libs.jar, including XML names `vel` and `rel` on Note.

This offline edge fixture tests parsing and scope accounting. The `generated/`
subdirectory contains the separate live JavaSong-authored fixture, captured
from Bitwig 6.1.5 and trimmed to newly created synthetic tracks. Its README
records provenance and the capture command.
