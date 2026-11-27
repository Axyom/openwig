package io.github.axyom.openwigagent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds and installs mods the agent writes, with nothing but Bitwig's own Java: the bundled Eclipse compiler
 * (Bitwig's Java has no javac), Patchbay's mod kit, and Patchbay's preparer run on a private copy of the loader
 * (so the runtime a running Bitwig uses is never touched). The steps follow Patchbay's docs/sdk/building.md.
 *
 * <p>Workspace: {@code <agent data>/mods/<mod-id>/} with mod.json, optional build.json, src/, README.md and
 * package/. Output: {@code <agent data>/out/<mod-id>/}. Install copies the output to {@code <Patchbay>/mods/<mod-id>}.
 */
final class ModBuilder {
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");
    private static final String MARKER = ".openwig-agent-generated"; // only folders with this marker may be replaced
    private static final Set<String> RESERVED = Set.of("openwig", "openwig-agent");
    private static final Pattern CLASS_IN_DESCRIPTOR = Pattern.compile("L([^;]+);");
    private static final Pattern SWIDTAG = Pattern.compile("^Bitwig Studio (\\d+\\.\\d+\\.\\d+)\\.swidtag$");

    private final Path data;      // the agent mod's data folder
    private final Path ecj;       // the bundled Eclipse compiler
    private final Path bml;       // the Patchbay install
    private final Path installed; // Patchbay's mods folder
    private final Path modkit;    // Patchbay's bml-modkit.jar
    private final Path bitwigBin; // Bitwig's bin folder
    private final Path java;      // Bitwig's own java.exe
    private final String bitwigVersion;

    ModBuilder(Path data, Path ecj) {
        this.data = data;
        this.ecj = ecj;
        Path javaHome = Path.of(System.getProperty("java.home"));
        this.java = javaHome.resolve("bin").resolve("java.exe");
        this.bitwigBin = javaHome.getParent().resolve("bin");
        this.bitwigVersion = installedVersion(javaHome.getParent());
        this.bml = patchbayRoot();
        this.installed = bml.resolve("mods");
        this.modkit = bml.resolve("modkit").resolve("bml-modkit.jar");
    }

    /** The running Bitwig's version, from the install's swidtag file name ("Bitwig Studio 6.1.7.swidtag"). */
    private static String installedVersion(Path bitwigRoot) {
        try (Stream<Path> s = Files.list(bitwigRoot.resolve("swidtag"))) {
            for (Path p : s.toList()) {
                Matcher m = SWIDTAG.matcher(p.getFileName().toString());
                if (m.matches()) return m.group(1);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "the running Bitwig version";
    }

    String bitwigVersion() { return bitwigVersion; }

    /** The Patchbay install: our helper jar is loaded from {@code <Patchbay>/loader/mod-runtime/}. */
    private static Path patchbayRoot() {
        String override = System.getenv("OPENWIG_PATCHBAY_ROOT");
        if (override != null && !override.isBlank()) return Path.of(override);
        try {
            Path jar = Path.of(ModBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path p = jar.getParent(); p != null; p = p.getParent())
                if (Files.isDirectory(p.resolve("loader")) && Files.isDirectory(p.resolve("mods"))) return p;
        } catch (Exception ignored) {
            // fall through
        }
        throw new IllegalStateException("cannot find the Patchbay install (set OPENWIG_PATCHBAY_ROOT)");
    }

    // ---- workspace files ---------------------------------------------------------------------------------------

    String writeFile(String modId, String path, String content) throws IOException {
        Path root = workspace(modId);
        Path file = inside(root, path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return "wrote " + root.relativize(file) + " (" + content.length() + " chars)";
    }

    String readFile(String modId, String path) throws IOException {
        Path root = workspace(modId);
        Path file = inside(root, path);
        if (Files.isDirectory(file)) {
            try (Stream<Path> s = Files.walk(file)) {
                StringBuilder out = new StringBuilder();
                s.filter(Files::isRegularFile).sorted().forEach(p -> out.append(root.relativize(p)).append('\n'));
                return out.length() == 0 ? "(empty)" : out.toString();
            }
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** A Patchbay modder-guide page, read-only: index when name is empty. */
    String docs(String name) throws IOException {
        Path dir = bml.resolve("docs").resolve("sdk");
        if (name == null || name.isBlank()) {
            try (Stream<Path> s = Files.list(dir)) {
                return "Patchbay modder guide pages: " + String.join(", ",
                        s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".md")).sorted().toList());
            }
        }
        if (!name.matches("^[a-z-]+\\.md$")) throw new IllegalArgumentException("page name like building.md");
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    /** The mod's workspace folder (absolute), once the id is valid and the agent's to use. */
    private Path workspace(String modId) {
        if (!ID.matcher(modId).matches()) throw new IllegalArgumentException("mod id: lowercase letters, digits and -, e.g. hello-mod");
        if (RESERVED.contains(modId) || Files.exists(installed.resolve(modId)) && !Files.exists(installed.resolve(modId).resolve(MARKER)))
            throw new IllegalArgumentException("mod id " + modId + " belongs to an installed mod the agent did not make");
        return data.resolve("mods").resolve(modId).toAbsolutePath().normalize();
    }

    private Path outDir(String modId) { return data.resolve("out").resolve(modId); }

    private static Path inside(Path root, String path) {
        Path file = root.resolve(path == null ? "" : path).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("path must stay inside the mod's folder");
        return file;
    }

    // ---- build -------------------------------------------------------------------------------------------------

    /** Compiles, finishes, writes helper.jar and the finished mod.json, then runs the offline check. */
    String build(String modId) throws IOException, InterruptedException {
        Path src = workspace(modId);
        JSONObject source = new JSONObject(Files.readString(src.resolve("mod.json")));
        String id = source.getJSONObject("mod").getString("id");
        if (!id.equals(modId)) throw new IllegalArgumentException("mod.json id is " + id + ", expected " + modId);
        Path work = data.resolve("build").resolve(modId);
        Path out = outDir(modId);
        delete(work);
        delete(out);
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        Files.createDirectories(out);

        List<Path> deps = new ArrayList<>(); // helper jars of the mods it requires (frameworks)
        for (String req : requiredIds(source)) {
            Path jar = installed.resolve(req).resolve("helper.jar");
            if (Files.exists(jar)) deps.add(jar);
        }
        Path compileJar = compileJar();
        List<Path> cp = new ArrayList<>(List.of(compileJar, bitwigBin.resolve("libs.jar"), bml.resolve("modkit").resolve("bml-api.jar")));
        cp.addAll(deps);
        String classpath = String.join(";", cp.stream().map(Path::toString).toList());

        if (Files.isDirectory(src.resolve("src"))) {
            // -21: Java 21 source and class files. ECJ's --release needs a zip filesystem module Bitwig's Java lacks.
            run("compile", List.of(java.toString(), "-jar", ecj.toString(), "-21", "-proc:none", "-encoding", "UTF-8",
                    "-nowarn", "-cp", classpath, "-d", classes.toString(), src.resolve("src").toString()));
            List<String> finish = new ArrayList<>(List.of(java.toString(), "-jar", modkit.toString(),
                    "finish", classes.toString(), work.resolve("hooks.json").toString(),
                    bitwigBin.resolve("bitwig.jar").toString(), bitwigBin.resolve("libs.jar").toString()));
            for (Path d : deps) finish.add(d.toString());
            run("finish", finish);
            copyTree(src.resolve("src"), classes, p -> !p.toString().endsWith(".java"));
            zip(classes, out.resolve("helper.jar"));
        }
        JSONObject hooks = Files.exists(work.resolve("hooks.json"))
                ? new JSONObject(Files.readString(work.resolve("hooks.json"))) : new JSONObject();
        JSONObject finished = finishedManifest(source, hooks);
        Files.writeString(out.resolve("mod.json"), finished.toString(2) + "\n");
        if (Files.exists(src.resolve("README.md"))) Files.copy(src.resolve("README.md"), out.resolve("README.md"));
        if (Files.isDirectory(src.resolve("package"))) copyTree(src.resolve("package"), out, p -> true);
        return check(modId, out, finished);
    }

    /** The ids in a manifest's requires list. */
    private static List<String> requiredIds(JSONObject manifest) {
        List<String> ids = new ArrayList<>();
        for (Object r : array(manifest, "requires")) ids.add(((JSONObject) r).getString("id"));
        return ids;
    }

    private static JSONArray array(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }

    /** The compile jar for this Bitwig build, made once from the user's own bitwig.jar (never shipped). */
    private Path compileJar() throws IOException, InterruptedException {
        Path jar = bitwigBin.resolve("bitwig.jar");
        String key = Files.size(jar) + "-" + Files.getLastModifiedTime(jar).toMillis();
        Path out = data.resolve("compile").resolve("bitwig-" + key + "-compile.jar");
        if (Files.exists(out)) return out;
        Files.createDirectories(out.getParent());
        run("compile-jar", List.of(java.toString(), "-jar", modkit.toString(),
                "compile-jar", jar.toString(), out.toString()));
        return out;
    }

    /** Patchbay's preparer on this mod and the mods it requires, against a private loader copy. */
    private String check(String modId, Path out, JSONObject manifest) throws IOException, InterruptedException {
        Path root = data.resolve("check");
        Path loader = root.resolve("loader");
        if (!Files.exists(loader.resolve("app").resolve("bml-control.jar"))) {
            for (String part : List.of("api", "app", "jvmti", "tools", "find-java.cmd", "prepare-mod.cmd", "mod.schema.json", "mod-v3.schema.json")) {
                Path from = bml.resolve("loader").resolve(part);
                if (Files.isDirectory(from)) copyTree(from, loader.resolve(part), p -> true);
                else if (Files.exists(from)) { Files.createDirectories(loader); Files.copy(from, loader.resolve(part), StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        Path maps = root.resolve("maps");
        if (!Files.exists(maps)) run("link maps", List.of("cmd", "/c", "mklink", "/J", maps.toString(), bml.resolve("maps").toString()));
        Path mods = root.resolve("mods");
        delete(mods);
        Files.createDirectories(mods);
        copyTree(out, mods.resolve(modId), p -> true);
        for (String req : requiredIds(manifest)) {
            Path from = installed.resolve(req);
            if (Files.isDirectory(from)) copyTree(from, mods.resolve(req), p -> true);
        }
        String log = run("offline check", List.of(java.toString(), "-cp", loader.resolve("app").resolve("bml-control.jar").toString(),
                "dev.bitwigre.bml.JavaPreparer", "--loader-root", loader.toString(), "--bitwig-bin", bitwigBin.toString(),
                "--mods-root", mods.toString()));
        String ok = log.lines().filter(l -> l.startsWith("PREPARE_OK")).findFirst().orElse(null);
        if (ok == null) throw new IllegalStateException("offline check failed:\n" + tail(log, 25));
        return "built " + modId + ": " + ok + "\nCall mod_install to install it; it loads the next time Bitwig starts.";
    }

    // ---- install -----------------------------------------------------------------------------------------------

    String install(String modId) throws IOException {
        workspace(modId); // validates the id and that it is ours to replace
        Path out = outDir(modId);
        if (!Files.exists(out.resolve("mod.json"))) throw new IllegalStateException("build " + modId + " first (mod_build)");
        Path target = installed.resolve(modId);
        delete(target);
        copyTree(out, target, p -> true);
        Files.writeString(target.resolve(MARKER), "made by the OpenWig Agent\n");
        AgentPanels.LOG.log("installed generated mod " + modId + " into " + target);
        return "installed " + modId + " into " + target
                + ". It loads the next time Bitwig starts through Patchbay (tick it in Patchbay's Mods list if it is not ticked).";
    }

    // ---- the finished mod.json (building.md, "The finished mod.json"; same rules as mods/openwig/build.py) ------

    static JSONObject finishedManifest(JSONObject source, JSONObject hooks) {
        Map<String, JSONObject> targets = new LinkedHashMap<>();
        JSONArray outHooks = new JSONArray();
        for (Object h : array(hooks, "hooks")) {
            JSONObject hook = (JSONObject) h;
            JSONObject entry = new JSONObject().put("id", hook.get("id")).put("target", addTarget(targets, hook.getJSONObject("target")))
                    .put("transform", hook.get("transform"));
            if (hook.has("returns")) entry.put("returns", hook.get("returns"));
            if (hook.has("method_from")) {
                entry.put("method", new JSONObject().put("from_target", addTarget(targets, hook.getJSONObject("method_from"))));
                entry.put("body", new JSONObject().put("template", hook.get("body")));
            }
            if (hook.has("match")) {
                JSONObject match = hook.getJSONObject("match");
                JSONObject m = new JSONObject();
                if (match.has("target")) {
                    m.put("opcode", match.get("opcode")).put("target", addTarget(targets, match.getJSONObject("target"))).put("occurrence", match.get("occurrence"));
                } else {
                    for (String k : List.of("opcode", "owner", "name", "descriptor", "occurrence")) if (match.has(k)) m.put(k, match.get(k));
                }
                entry.put("match", m);
            }
            entry.put("bridge", hook.get("bridge"));
            outHooks.put(entry);
        }
        JSONArray fields = new JSONArray();
        for (Object f : array(hooks, "fields")) {
            JSONObject field = (JSONObject) f;
            String owner = addTarget(targets, new JSONObject().put("kind", "class").put("class", field.getString("owner")));
            Matcher m = CLASS_IN_DESCRIPTOR.matcher(field.getString("descriptor"));
            while (m.find()) if (!m.group(1).startsWith("java/")) addTarget(targets, new JSONObject().put("kind", "class").put("class", m.group(1)));
            fields.put(new JSONObject().put("id", field.get("id")).put("owner", owner).put("name", field.get("name")).put("descriptor", field.get("descriptor")));
        }
        List<String> opens = new ArrayList<>();
        for (Object a : array(hooks, "access")) opens.add(addTarget(targets, (JSONObject) a));
        opens.sort(Comparator.naturalOrder());

        JSONObject manifest = new JSONObject(source.toString());
        JSONArray hookClasses = hooks.optJSONArray("hook_classes");
        if (hookClasses != null && !hookClasses.isEmpty())
            manifest.put("helper", new JSONObject().put("jar", "helper.jar").put("class", hookClasses.getString(0)));
        if (!targets.isEmpty() || !outHooks.isEmpty()) {
            JSONObject t = new JSONObject();
            targets.forEach(t::put);
            manifest.put("targets", t);
            manifest.put("hooks", outHooks);
        }
        if (!fields.isEmpty()) manifest.put("fields", fields);
        if (!opens.isEmpty()) manifest.put("opens", new JSONArray(opens));
        return manifest;
    }

    private static String addTarget(Map<String, JSONObject> targets, JSONObject target) {
        for (Map.Entry<String, JSONObject> e : targets.entrySet()) if (e.getValue().similar(target)) return e.getKey();
        String raw = target.getString("kind").equals("class") ? target.getString("class") : target.getString("owner") + "." + target.getString("name");
        String base = raw.replace("/", ".").replace("$", "_").replaceAll("[^A-Za-z0-9_.-]", "");
        String key = base;
        for (int n = 2; targets.containsKey(key); n++) key = base + "-" + n;
        targets.put(key, target);
        return key;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static String run(String step, List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output;
        try (var in = p.getInputStream()) { output = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroy(); throw new IllegalStateException(step + " timed out"); }
        if (p.exitValue() != 0) throw new IllegalStateException(step + " failed:\n" + tail(output, 40));
        return output;
    }

    private static String tail(String text, int lines) {
        List<String> all = text.lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    private static void zip(Path dir, Path jar) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(file); Stream<Path> s = Files.walk(dir)) {
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            z.closeEntry();
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String name = dir.relativize(p).toString().replace('\\', '/');
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
                z.putNextEntry(new ZipEntry(name));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
    }

    private static void copyTree(Path from, Path to, java.util.function.Predicate<Path> keep) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else if (keep.test(p)) { Files.createDirectories(dest.getParent()); Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
